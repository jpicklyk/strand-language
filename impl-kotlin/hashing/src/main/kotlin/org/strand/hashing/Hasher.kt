package org.strand.hashing

import io.github.rctcwyvrn.blake3.Blake3
import org.strand.core.Hash
import org.strand.core.HashFunction
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.RawNodeStore
import org.strand.core.StoredNode

/**
 * The Layer 2 hashing entry point. Given a [RawNodeStore] from JSON ingest
 * (or any other producer) and a root [NodeId], walks the reachable subgraph
 * and produces a hash for every reachable node in the binder context
 * appropriate to each node's position.
 *
 * The primary public entry point is [finalize], which turns a raw store into
 * a canonical [NodeStore] where every `NodeRef.target` is the BLAKE3 multi-
 * hash of the referenced subgraph. The verifier and interpreter only see this
 * canonical form.
 *
 * The hash function is BLAKE3 over the canonical encoding produced by
 * [CanonicalEncoder]. Output is multi-hash per ADR-003: one prefix byte
 * (0x1e for BLAKE3) followed by the 32-byte digest.
 *
 * Bound nodes — [Node.ParameterDecl] and [Node.TypeParameter] — are not
 * independently hashed. ParameterDecl is intrinsic to its enclosing Lambda
 * and is encoded inline there; TypeParameter has no standalone encoding
 * (its identity is positional). Both are skipped during the walk.
 */
class Hasher(private val rawStore: RawNodeStore) {

    /**
     * Convenience: wrap a canonical [NodeStore] as a synthetic [RawNodeStore]
     * (every entry as [StoredNode.Canonical]) so post-finalize hashes can be
     * recomputed without ferrying the raw form around. Hashes are identical
     * either way for graphs that have no NodeRefs, and for graphs that do,
     * canonical NodeRefs already carry their target hash inline so the
     * encoder dispatches the same bytes.
     */
    constructor(canonicalStore: NodeStore) : this(toRawNodeStore(canonicalStore))

    private val encoder: CanonicalEncoder = CanonicalEncoder(rawNodeStoreLookup(rawStore)) { bytes ->
        blake3MultiHash(bytes)
    }

    /**
     * Compute the hash of the node at [rootId] (and, as a side effect of the
     * recursive encoding, of every reachable node). For graphs with no
     * extra-root NodeRefs and no Let/Lambda nesting that creates contextual
     * differences, every node is hashed once.
     */
    fun hashRoot(rootId: NodeId): Hash = Hash(encoder.hash(rootId))

    /**
     * Compute the hash of every reachable node from [rootId] and return the
     * full [NodeId] → [Hash] mapping. Bound nodes ([Node.ParameterDecl],
     * [Node.TypeParameter], [Node.RecursiveSelf]) are excluded; they have no
     * standalone hash.
     */
    fun hashReachable(rootId: NodeId): Map<NodeId, Hash> {
        val out = LinkedHashMap<NodeId, Hash>()
        walk(rootId, emptyList(), out)
        return out
    }

    /**
     * Layer 2 step 2: convert this raw store into a finalized [FinalizedProgram].
     *
     * Replaces every [StoredNode.RawNodeRef] entry with a canonical
     * [Node.NodeRef] whose `target` is the BLAKE3 multi-hash of the referenced
     * subgraph (computed under an empty binder stack — the verifier's
     * `NodeRefTargetMustBeClosed` rule guarantees this is the same hash the
     * encoder would emit for the target in any position).
     *
     * Preserves [NodeId]s exactly: the canonical [NodeStore] assigns the same
     * id to each node that the raw store did, so downstream NodeId-keyed
     * consumers (verifier scope tracking, interpreter environment) continue
     * to work unchanged.
     *
     * Errors out if a [StoredNode.RawNodeRef]'s target is not reachable from
     * [rootId] — every authored node is expected to be reachable from the
     * program's root.
     */
    fun finalize(rootId: NodeId): FinalizedProgram {
        val nodeIdToHash = hashReachable(rootId)

        val canonicalStore = NodeStore()
        val hashToNodeId = LinkedHashMap<Hash, NodeId>()
        for ((id, stored) in rawStore.entries()) {
            val canonical: Node = when (stored) {
                is StoredNode.Canonical -> stored.node
                is StoredNode.RawNodeRef -> {
                    val targetHash = nodeIdToHash[stored.targetId]
                        ?: error(
                            "RawNodeRef at $id targets ${stored.targetId}, which was not " +
                                "hashed (unreachable from root $rootId). Every authored node " +
                                "must be reachable from the program root."
                        )
                    Node.NodeRef(target = targetHash)
                }
            }
            val assigned = canonicalStore.add(canonical)
            check(assigned == id) {
                "Finalization invariant violation: expected NodeStore to assign $id but got $assigned"
            }
            // hashToNodeId only contains hashable nodes; bound nodes
            // (ParameterDecl, TypeParameter, RecursiveSelf) are absent from
            // nodeIdToHash and so absent here too. On collision (true hash
            // dedup), keep the first-inserted NodeId — they are structurally
            // equivalent and either is a valid resolution target.
            nodeIdToHash[id]?.let { hash -> hashToNodeId.putIfAbsent(hash, id) }
        }
        return FinalizedProgram(canonicalStore, rootId, nodeIdToHash, hashToNodeId)
    }

    private fun fetchCanonicalNode(id: NodeId): Node {
        val stored = rawStore.get(id)
        return (stored as? StoredNode.Canonical)?.node
            ?: error("Expected canonical node at $id, found ${stored::class.simpleName}")
    }

    private fun walk(
        id: NodeId,
        stack: BinderStack,
        out: MutableMap<NodeId, Hash>,
    ) {
        if (id in out) return
        val stored = rawStore.get(id)
        when (stored) {
            is StoredNode.RawNodeRef -> {
                // Hash this NodeRef first so re-entry via a shared subgraph
                // sees the hash already populated; then recurse into the
                // target to populate its hash entry too.
                out[id] = Hash(encoder.hash(id, stack))
                walk(stored.targetId, stack, out)
            }
            is StoredNode.Canonical -> {
                val node = stored.node
                if (node is Node.ParameterDecl ||
                    node is Node.TypeParameter ||
                    node is Node.RecursiveSelf
                ) {
                    // Bound nodes: skipped — they have no standalone hash. The
                    // enclosing Lambda / TypeAbstraction / ForallType /
                    // RecursiveType subsumes them.
                    return
                }
                out[id] = Hash(encoder.hash(id, stack))
                walkChildren(id, node, stack, out)
            }
        }
    }

    private fun walkChildren(
        id: NodeId,
        node: Node,
        stack: BinderStack,
        out: MutableMap<NodeId, Hash>,
    ) {
        when (node) {
            is Node.IntLit, is Node.FloatLit, is Node.StringLit, is Node.BoolLit,
            Node.UnitLit, is Node.BytesLit, is Node.PrimitiveType -> Unit

            is Node.ProductType -> node.fields.forEach { walk(it, stack, out) }
            is Node.ProductTypeField -> walk(node.fieldType, stack, out)
            is Node.SumType -> node.cases.forEach { walk(it, stack, out) }
            is Node.SumTypeCase -> node.caseType?.let { walk(it, stack, out) }
            is Node.FunctionType -> {
                node.parameters.forEach { walk(it, stack, out) }
                walk(node.result, stack, out)
                node.effects.forEach { walk(it, stack, out) }
            }
            is Node.ForallType -> {
                walk(node.body, stack + listOf(node.typeParameters), out)
            }

            is Node.Lambda -> {
                // Visit parameter types in OUTER stack. Their NodeIds belong
                // to ParameterDecls, which we don't hash directly; recurse
                // into the paramType for each.
                node.parameters.forEach { paramId ->
                    val pd = fetchCanonicalNode(paramId) as Node.ParameterDecl
                    walk(pd.paramType, stack, out)
                }
                walk(node.body, stack + listOf(node.parameters), out)
                // Effects are visited in the OUTER stack (they reference
                // EffectCategory nodes that exist outside the Lambda's body
                // scope).
                node.effects.forEach { walk(it, stack, out) }
            }
            is Node.TypeAbstraction -> {
                walk(node.body, stack + listOf(node.typeParameters), out)
            }
            is Node.Application -> {
                walk(node.function, stack, out)
                node.arguments.forEach { walk(it, stack, out) }
                node.typeArguments.forEach { walk(it, stack, out) }
                node.effectInstances.forEach { walk(it, stack, out) }
            }
            is Node.Let -> {
                walk(node.value, stack, out)
                walk(node.body, stack + listOf(listOf(id)), out)
            }
            is Node.VarRef -> Unit  // binder is referenced positionally, not walked

            is Node.NodeRef -> error(
                "Canonical Node.NodeRef encountered in raw store at $id; " +
                    "JsonIngest must produce StoredNode.RawNodeRef for NodeRef entries."
            )

            is Node.ForeignNode -> {
                walk(node.foreignType, stack, out)
                node.effects.forEach { walk(it, stack, out) }
            }

            is Node.EffectCategory -> node.parameters.forEach { walk(it, stack, out) }
            is Node.EffectDecl -> {
                walk(node.effectType, stack, out)
                node.parameters.forEach { walk(it, stack, out) }
            }
            is Node.CapabilityScope -> {
                node.capabilities.forEach { walk(it, stack, out) }
                walk(node.body, stack, out)
            }

            is Node.Match -> {
                walk(node.scrutinee, stack, out)
                node.cases.forEach { walk(it, stack, out) }
            }
            is Node.MatchCase -> {
                walk(node.pattern, stack, out)
                // Body sees a binder frame for every VariablePattern
                // reachable via the pattern tree (including those nested
                // inside ConstructorPatterns). The encoder uses the same
                // helper at encoding time so the stacks line up.
                val patternNode = fetchCanonicalNode(node.pattern) as Node.Pattern
                val binders = collectPatternBinders(rawNodeStoreLookup(rawStore), node.pattern, patternNode)
                val bodyStack = if (binders.isEmpty()) stack else stack + listOf(binders)
                walk(node.body, bodyStack, out)
            }
            is Node.Pattern -> {
                walk(node.patternType, stack, out)
                when (node) {
                    is Node.Pattern.LiteralPattern -> walk(node.literal, stack, out)
                    is Node.Pattern.ConstructorPattern -> {
                        node.payloadPattern?.let { walk(it, stack, out) }
                    }
                    is Node.Pattern.VariablePattern, is Node.Pattern.WildcardPattern -> Unit
                }
            }
            is Node.Fixpoint -> {
                walk(node.recursionType, stack, out)
                walk(node.body, stack, out)
            }

            is Node.ProductValue -> {
                walk(node.ofType, stack, out)
                node.fields.forEach { walk(it, stack, out) }
            }
            is Node.ProductFieldValue -> walk(node.value, stack, out)
            is Node.ProductFieldGet -> walk(node.target, stack, out)
            is Node.SumValue -> {
                walk(node.ofType, stack, out)
                node.payload?.let { walk(it, stack, out) }
            }

            is Node.RecursiveType -> {
                // Push the encoder's recursive-binder depth so any hash
                // calls into the body resolve RecursiveSelf correctly.
                encoder.pushRecursiveBinder()
                try {
                    walk(node.body, stack, out)
                } finally {
                    encoder.popRecursiveBinder()
                }
            }
            is Node.RecursiveSelf -> Unit  // intrinsic — no standalone hash entry

            is Node.Handler -> {
                walk(node.intercept, stack, out)
                walk(node.handle, stack, out)
                walk(node.body, stack, out)
            }

            is Node.StateMachine -> {
                walk(node.transitionFn, stack, out)
                walk(node.initialState, stack, out)
                node.inputStreams.forEach { walk(it, stack, out) }
                node.outputStreams.forEach { walk(it, stack, out) }
                node.effects.forEach { walk(it, stack, out) }
            }
            is Node.EventStream -> walk(node.eventType, stack, out)
            is Node.Transition -> {
                node.guard?.let { walk(it, stack, out) }
                walk(node.body, stack, out)
            }

            is Node.Schema -> {
                walk(node.valueType, stack, out)
                node.invariants.forEach { walk(it, stack, out) }
            }
            is Node.ToolDef -> {
                walk(node.parameterSchema, stack, out)
                walk(node.implementation, stack, out)
            }
            is Node.Invariant -> {
                // `targetSchema` is intentionally NOT walked here — it is
                // excluded from the canonical encoding (see
                // CanonicalEncoder.encodeInvariant for the rationale) and
                // walking it would re-enter the parent Schema, which the
                // visited-set guards against but which is wasted work. The
                // parent Schema is reachable from the program root via some
                // other path (every well-formed graph has Schema reachable
                // before its Invariants are looked at).
                walk(node.body, stack, out)
            }

            is Node.ParameterDecl, is Node.TypeParameter -> Unit  // unreachable, guarded above
        }
    }

    private fun blake3MultiHash(bytes: ByteArray): ByteArray {
        val hasher = Blake3.newInstance()
        hasher.update(bytes)
        val digest = hasher.digest()
        val out = ByteArray(1 + digest.size)
        out[0] = HashFunction.Blake3.prefix
        digest.copyInto(out, 1)
        return out
    }
}

private fun toRawNodeStore(canonical: NodeStore): RawNodeStore {
    val raw = RawNodeStore()
    for ((_, node) in canonical.entries()) {
        raw.add(StoredNode.Canonical(node))
    }
    return raw
}

/**
 * Result of [Hasher.finalize]. Contains the canonical [NodeStore] where every
 * `NodeRef.target` is a [Hash], plus the bidirectional [NodeId] ↔ [Hash]
 * mappings the verifier and interpreter need to resolve NodeRef targets back
 * to local NodeIds.
 *
 * The verifier and interpreter consume this whole record: the [store] for
 * traversal and type inference, [hashToNodeId] for NodeRef resolution.
 */
data class FinalizedProgram(
    val store: NodeStore,
    val root: NodeId,
    val nodeIdToHash: Map<NodeId, Hash>,
    val hashToNodeId: Map<Hash, NodeId>,
)
