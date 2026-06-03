package org.strand.hashing

import org.strand.core.ConsumerMode
import org.strand.core.EffectProjection
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.OverflowPolicy
import org.strand.core.ProjectionSource
import org.strand.core.RawNodeStore
import org.strand.core.StoredNode

/**
 * Type alias for the per-NodeId lookup the [CanonicalEncoder] uses to fetch
 * stored entries. Both [RawNodeStore] (pre-finalization) and [NodeStore]
 * (post-finalization) can provide one — see [rawNodeStoreLookup] and
 * [canonicalNodeStoreLookup].
 */
internal typealias StoreLookup = (NodeId) -> StoredNode

internal fun rawNodeStoreLookup(store: RawNodeStore): StoreLookup = { id -> store.get(id) }

internal fun canonicalNodeStoreLookup(store: NodeStore): StoreLookup =
    { id -> StoredNode.Canonical(store.get(id)) }

/**
 * Walk a pattern tree, collecting the NodeIds of every VariablePattern
 * reachable from the root via ConstructorPattern.payloadPattern edges. The
 * order matches a depth-first traversal of the pattern tree — stable for a
 * given pattern shape, which is what the de Bruijn positional encoding
 * depends on. Used by both [CanonicalEncoder] (to compute the body's binder
 * stack) and [Hasher.walk] (to compute child hashes in the same context).
 *
 * Patterns are always canonical [Node]s — never raw [StoredNode.RawNodeRef]
 * — so the lookup is expected to return [StoredNode.Canonical] for every
 * pattern subgraph node. Anything else indicates verifier failure.
 */
internal fun collectPatternBinders(
    lookup: StoreLookup,
    patternId: NodeId,
    pattern: Node.Pattern,
): List<NodeId> {
    val out = mutableListOf<NodeId>()
    fun visit(id: NodeId, p: Node.Pattern) {
        when (p) {
            is Node.Pattern.VariablePattern -> out += id
            is Node.Pattern.WildcardPattern, is Node.Pattern.LiteralPattern -> Unit
            is Node.Pattern.ConstructorPattern -> {
                val inner = p.payloadPattern
                if (inner != null) {
                    val innerStored = lookup(inner)
                    val innerNode = (innerStored as? StoredNode.Canonical)?.node
                    if (innerNode is Node.Pattern) visit(inner, innerNode)
                }
            }
        }
    }
    visit(patternId, pattern)
    return out
}

/**
 * A binder stack tracks the lexical scopes enclosing a position in the graph.
 * Each frame is a list of bound [NodeId]s — the parameters of a Lambda, the
 * type parameters of a TypeAbstraction or ForallType, or the single binder of
 * a Let. The frame at the end of the list is the innermost (most recently
 * entered) binder.
 *
 * The encoder uses the stack to resolve [Node.VarRef.binder] and bound
 * [Node.TypeParameter] references into de Bruijn `(depth, index)` pairs:
 * `depth` counts the number of intervening binders out from the innermost
 * (depth 0 is the innermost binder), `index` is the position within the
 * matched frame.
 */
internal typealias BinderStack = List<List<NodeId>>

/**
 * Computes the canonical byte encoding of a Strand node per
 * design/node-algebra.md § Hash construction. The encoding has the form:
 *
 *   (4-byte big-endian category tag) || (content fields in fixed order)
 *
 * where every field is itself a canonical-CBOR item. References to child
 * nodes are emitted as CBOR byte strings carrying the child's 33-byte
 * multi-hash (one prefix byte + 32 digest bytes). References to bound
 * variables (VarRef binders and TypeParameter references) are emitted inline
 * as `(depth, index)` pairs rather than as child hashes, because their
 * identity is positional, not structural.
 *
 * The encoding is deliberately position-based at every binding site so that
 * alpha-equivalent terms — two lambdas that differ only in parameter naming,
 * two type abstractions that differ only in type-parameter naming — produce
 * byte-identical encodings and therefore identical hashes. See ADR-003 §
 * Alternatives considered for the rationale.
 *
 * Product type fields and sum type cases are sorted by their name field
 * before encoding, so two product types with the same `{name: type}` set in
 * any declaration order hash identically.
 *
 * The encoder caches per-(NodeId, stack) results within a single instance.
 * Hashing a graph of N reachable nodes runs in O(N) hash computations, each
 * over a constant-size byte sequence dominated by hash references.
 *
 * The encoder is parameterized by a hash function ([hashFn]) so it can be
 * tested against a deterministic mock without pulling in BLAKE3. In
 * production, [Hasher] composes this encoder with a real BLAKE3
 * implementation.
 */
internal class CanonicalEncoder(
    private val lookup: StoreLookup,
    private val hashFn: (ByteArray) -> ByteArray,
) {

    private val encodingCache = HashMap<EncodingKey, ByteArray>()

    /**
     * Tracks the number of enclosing `RecursiveType` binders the encoder
     * is currently within. `RecursiveSelf` is well-formed only when this
     * is positive (the verifier statically enforces the same condition).
     * Modified in lock-step with `encodeRecursiveType`'s push/pop so the
     * encoder's view of the binder stack matches the lexical structure
     * of whatever it is encoding.
     */
    private var currentRecDepth: Int = 0

    /**
     * Encode the node at [id] in the given binder context. Returns the
     * canonical bytes ready to feed into the hash function.
     */
    fun encode(id: NodeId, stack: BinderStack = emptyList()): ByteArray {
        val key = EncodingKey(id, stack, currentRecDepth)
        encodingCache[key]?.let { return it }
        val stored = lookup(id)
        val encoded = encodeDispatch(id, stored, stack)
        encodingCache[key] = encoded
        return encoded
    }

    private fun fetchCanonical(id: NodeId): Node {
        val stored = lookup(id)
        return (stored as? StoredNode.Canonical)?.node
            ?: error("Expected canonical node at $id, found ${stored::class.simpleName}")
    }

    /** Hash the canonical encoding of [id] in the given binder context. */
    fun hash(id: NodeId, stack: BinderStack = emptyList()): ByteArray =
        hashFn(encode(id, stack))

    /**
     * Push one recursive-binder frame. Callers (this encoder's own
     * `encodeRecursiveType` and [Hasher.walk] when descending into a
     * RecursiveType body) must pair every push with a pop.
     */
    internal fun pushRecursiveBinder() { currentRecDepth++ }
    internal fun popRecursiveBinder() {
        require(currentRecDepth > 0) { "popRecursiveBinder without matching push" }
        currentRecDepth--
    }

    private fun encodeDispatch(id: NodeId, stored: StoredNode, stack: BinderStack): ByteArray =
        when (stored) {
            is StoredNode.RawNodeRef -> encodeRawNodeRef(stored.targetId, stack)
            is StoredNode.RawModuleManifest -> encodeRawModuleManifest(stored, stack)
            is StoredNode.Canonical -> encodeCanonicalNode(id, stored.node, stack)
        }

    private fun encodeRawNodeRef(targetId: NodeId, stack: BinderStack): ByteArray =
        // Raw form: target's hash is not yet known. Recurse to compute it, then
        // emit (NodeRef tag, target-hash-bytes). The resulting canonical bytes
        // are byte-identical to those produced for the canonical Node.NodeRef
        // form (which carries the pre-computed hash directly) — that identity
        // is what preserves hash compatibility across the Layer 2 step 2
        // rewrite.
        encodeWithTag(CategoryTag.NodeRef, listOf(
            CanonicalCbor.encodeBytes(hash(targetId, stack)),
        ))

    private fun encodeCanonicalNode(id: NodeId, node: Node, stack: BinderStack): ByteArray = when (node) {
        is Node.IntLit -> encodeWithTag(CategoryTag.IntLit, listOf(
            CanonicalCbor.encodeInt(node.value)
        ))
        is Node.FloatLit -> encodeWithTag(CategoryTag.FloatLit, listOf(
            CanonicalCbor.encodeBytes(doubleToBytes(node.value))
        ))
        is Node.StringLit -> encodeWithTag(CategoryTag.StringLit, listOf(
            CanonicalCbor.encodeBytes(node.value.toByteArray(Charsets.UTF_8))
        ))
        is Node.BoolLit -> encodeWithTag(CategoryTag.BoolLit, listOf(
            CanonicalCbor.encodeUint(if (node.value) 1L else 0L)
        ))
        Node.UnitLit -> encodeWithTag(CategoryTag.UnitLit, emptyList())
        is Node.BytesLit -> encodeWithTag(CategoryTag.BytesLit, listOf(
            CanonicalCbor.encodeBytes(node.value)
        ))

        is Node.PrimitiveType -> encodeWithTag(CategoryTag.PrimitiveType, listOf(
            CanonicalCbor.encodeUint(node.kind.ordinal.toLong())
        ))
        is Node.ProductType -> encodeProductType(node, stack)
        is Node.ProductTypeField -> encodeProductTypeField(node, stack)
        is Node.SumType -> encodeSumType(node, stack)
        is Node.SumTypeCase -> encodeSumTypeCase(node, stack)
        is Node.FunctionType -> encodeFunctionType(node, stack)
        is Node.TypeParameter -> encodeBoundTypeParameter(id, stack)
        is Node.ForallType -> encodeForallType(node, stack)

        is Node.Lambda -> encodeLambda(node, stack)
        is Node.TypeAbstraction -> encodeTypeAbstraction(node, stack)
        is Node.ParameterDecl -> error(
            "ParameterDecl $id has no standalone canonical encoding; it is " +
                "intrinsic to its enclosing Lambda and is encoded inline there."
        )
        is Node.Application -> encodeApplication(node, stack)
        is Node.Let -> encodeLet(id, node, stack)
        is Node.VarRef -> encodeVarRef(node, stack)

        is Node.NodeRef -> encodeNodeRef(node, stack)
        is Node.ForeignNode -> encodeForeignNode(node, stack)

        is Node.EffectCategory -> encodeEffectCategory(node, stack)
        is Node.EffectDecl -> encodeEffectDecl(node, stack)
        is Node.CapabilityScope -> encodeCapabilityScope(node, stack)

        is Node.Match -> encodeMatch(node, stack)
        is Node.MatchCase -> encodeMatchCase(id, node, stack)
        is Node.Pattern -> encodePattern(node, stack)
        is Node.Fixpoint -> encodeFixpoint(node, stack)

        is Node.ProductValue -> encodeProductValue(node, stack)
        is Node.ProductFieldValue -> encodeProductFieldValue(node, stack)
        is Node.ProductFieldGet -> encodeProductFieldGet(node, stack)
        is Node.SumValue -> encodeSumValue(node, stack)

        is Node.RecursiveType -> encodeRecursiveType(node, stack)
        is Node.RecursiveSelf -> encodeRecursiveSelf(node)

        is Node.Handler -> encodeHandler(node, stack)

        is Node.StateMachine -> encodeStateMachine(node, stack)
        is Node.EventStream -> encodeEventStream(node, stack)
        is Node.Transition -> encodeTransition(node, stack)

        is Node.Schema -> encodeSchema(node, stack)
        is Node.Invariant -> encodeInvariant(node, stack)

        is Node.ToolDef -> encodeToolDef(node, stack)
        is Node.ResponseSchemaSpec -> encodeResponseSchemaSpec(node, stack)

        is Node.ModuleManifest -> encodeModuleManifest(node, stack)
    }

    // ----- Type-position encodings -----

    private fun encodeProductType(node: Node.ProductType, stack: BinderStack): ByteArray {
        // Field order is normalized by lexicographic order on the field name.
        // Two product types declared with the same name/type pairs in any order
        // therefore hash identically (per node-algebra.md § Type system).
        val sortedFieldIds = node.fields.sortedBy { fieldId ->
            requireProductTypeField(fieldId).fieldName
        }
        val fieldHashes = sortedFieldIds.map { fieldId ->
            CanonicalCbor.encodeBytes(hash(fieldId, stack))
        }
        return encodeWithTag(CategoryTag.ProductType, listOf(
            CanonicalCbor.encodeArray(fieldHashes)
        ))
    }

    private fun encodeProductTypeField(node: Node.ProductTypeField, stack: BinderStack): ByteArray {
        return encodeWithTag(CategoryTag.ProductTypeField, listOf(
            CanonicalCbor.encodeBytes(node.fieldName.toByteArray(Charsets.UTF_8)),
            encodeTypePositionChild(node.fieldType, stack),
        ))
    }

    private fun encodeSumType(node: Node.SumType, stack: BinderStack): ByteArray {
        val sortedCaseIds = node.cases.sortedBy { caseId ->
            requireSumTypeCase(caseId).caseName
        }
        val caseHashes = sortedCaseIds.map { caseId ->
            CanonicalCbor.encodeBytes(hash(caseId, stack))
        }
        return encodeWithTag(CategoryTag.SumType, listOf(
            CanonicalCbor.encodeArray(caseHashes)
        ))
    }

    private fun encodeSumTypeCase(node: Node.SumTypeCase, stack: BinderStack): ByteArray {
        // Optional caseType encoded as a presence-prefixed field: 0 = absent
        // (nullary case), 1 = present (followed by the type-position child).
        val caseType = node.caseType
        val typeFields = if (caseType == null) {
            listOf(CanonicalCbor.encodeUint(0L))
        } else {
            listOf(
                CanonicalCbor.encodeUint(1L),
                encodeTypePositionChild(caseType, stack),
            )
        }
        return encodeWithTag(CategoryTag.SumTypeCase, listOf(
            CanonicalCbor.encodeBytes(node.caseName.toByteArray(Charsets.UTF_8)),
        ) + typeFields)
    }

    private fun encodeFunctionType(node: Node.FunctionType, stack: BinderStack): ByteArray {
        val paramEncodings = node.parameters.map { paramId ->
            encodeTypePositionChild(paramId, stack)
        }
        // Effects are a set, not a list: sort by hash bytes for determinism so
        // two FunctionTypes that differ only in declared effect order hash
        // identically.
        val effectHashes = node.effects
            .map { hash(it, stack) }
            .sortedWith(byteArrayLexicographicComparator)
            .map { CanonicalCbor.encodeBytes(it) }
        val baseFields = listOf(
            CanonicalCbor.encodeArray(paramEncodings),
            encodeTypePositionChild(node.result, stack),
            CanonicalCbor.encodeArray(effectHashes),
        )
        // Q-039: `effectProjections` is gated on non-empty — pre-Q-039
        // FunctionTypes (no projections) hash byte-identically to their
        // post-Q-039 form.
        val allFields = if (node.effectProjections.isEmpty()) {
            baseFields
        } else {
            baseFields + encodeEffectProjectionList(node.effectProjections, stack)
        }
        return encodeWithTag(CategoryTag.FunctionType, allFields)
    }

    private fun encodeRecursiveType(node: Node.RecursiveType, stack: BinderStack): ByteArray {
        // Push one recursive-binder frame, encode the body, pop. The body's
        // canonical bytes may reference the binder via RecursiveSelf nodes;
        // each such reference emits its de Bruijn depth at encoding time,
        // and since this is the innermost binder until the body itself
        // enters another RecursiveType, the depth resolves correctly.
        pushRecursiveBinder()
        try {
            val bodyEncoding = encodeTypePositionChild(node.body, stack)
            return encodeWithTag(CategoryTag.RecursiveType, listOf(bodyEncoding))
        } finally {
            popRecursiveBinder()
        }
    }

    private fun encodeRecursiveSelf(node: Node.RecursiveSelf): ByteArray {
        // Emit the node's depth field directly. If RecursiveSelf is
        // outside any RecursiveType binder, OR its depth exceeds the
        // count of enclosing binders, the verifier will report
        // UnboundRecursiveSelf. We still emit a sentinel for the
        // out-of-range cases so finalize can produce a hash for the
        // surrounding subgraph and the verifier gets a chance to run.
        val depth: Long = if (node.depth in 0 until currentRecDepth) {
            node.depth.toLong()
        } else {
            Long.MAX_VALUE  // sentinel: encoder-detectable out-of-range
        }
        return encodeWithTag(CategoryTag.RecursiveSelf, listOf(
            CanonicalCbor.encodeUint(depth)
        ))
    }

    private fun encodeForallType(node: Node.ForallType, stack: BinderStack): ByteArray {
        // ForallType binds its typeParameters over the body. The bound
        // parameters themselves are not encoded by hash — only the arity is
        // captured. Two ForallTypes that quantify over the same number of
        // parameters with the same body structure hash identically.
        val newStack = stack + listOf(node.typeParameters)
        return encodeWithTag(CategoryTag.ForallType, listOf(
            CanonicalCbor.encodeUint(node.typeParameters.size.toLong()),
            encodeTypePositionChild(node.body, newStack),
        ))
    }

    private fun encodeBoundTypeParameter(typeParamId: NodeId, stack: BinderStack): ByteArray {
        // If the TypeParameter is not bound by an enclosing TypeAbstraction or
        // ForallType, the verifier will report `UnboundTypeParameter`; the
        // encoder here emits an out-of-range sentinel position so finalize
        // can still produce a hash for the surrounding subgraph and the
        // verifier gets a chance to run. The sentinel cannot collide with a
        // real position — real binder stacks are bounded by Int.MAX_VALUE.
        val position = resolvePosition(typeParamId, stack) ?: UNBOUND_SENTINEL
        return encodeWithTag(CategoryTag.TypeParameterRef, listOf(
            CanonicalCbor.encodeUint(position.depth.toLong()),
            CanonicalCbor.encodeUint(position.index.toLong()),
        ))
    }

    // ----- Expression-position encodings -----

    private fun encodeLambda(node: Node.Lambda, stack: BinderStack): ByteArray {
        // Encode each parameter's type in the OUTER stack (parameter types are
        // declared, not bound by the Lambda's own parameters). Then push the
        // Lambda's parameters and encode the body in the EXTENDED stack.
        val paramTypeEncodings = node.parameters.map { paramId ->
            val paramDecl = requireParameterDecl(paramId)
            encodeTypePositionChild(paramDecl.paramType, stack)
        }
        val newStack = stack + listOf(node.parameters)
        // Effects are a set: sort by hash bytes for canonical-order
        // determinism. Two lambdas that differ only in effect declaration
        // order hash identically.
        val effectHashes = node.effects
            .map { hash(it, stack) }
            .sortedWith(byteArrayLexicographicComparator)
            .map { CanonicalCbor.encodeBytes(it) }
        return encodeWithTag(CategoryTag.Lambda, listOf(
            CanonicalCbor.encodeArray(paramTypeEncodings),
            encodeExpressionChild(node.body, newStack),
            CanonicalCbor.encodeArray(effectHashes),
        ))
    }

    private fun encodeTypeAbstraction(node: Node.TypeAbstraction, stack: BinderStack): ByteArray {
        val newStack = stack + listOf(node.typeParameters)
        return encodeWithTag(CategoryTag.TypeAbstraction, listOf(
            CanonicalCbor.encodeUint(node.typeParameters.size.toLong()),
            encodeExpressionChild(node.body, newStack),
        ))
    }

    private fun encodeApplication(node: Node.Application, stack: BinderStack): ByteArray {
        val argEncodings = node.arguments.map { argId ->
            encodeExpressionChild(argId, stack)
        }
        val typeArgEncodings = node.typeArguments.map { typeArgId ->
            encodeTypePositionChild(typeArgId, stack)
        }
        // Effect-instances field is gated on `effectInstances.size > 0` for
        // backward-compat: pre-Q-031 Applications (no effect instances)
        // hash identically to their post-Q-031 form. When present, each
        // entry is the 33-byte multi-hash of the referenced EffectDecl,
        // sorted lexicographically so two Applications differing only in
        // the declaration order of their effect instances hash identically.
        val baseFields = listOf(
            encodeExpressionChild(node.function, stack),
            CanonicalCbor.encodeArray(argEncodings),
            CanonicalCbor.encodeArray(typeArgEncodings),
        )
        val allFields = if (node.effectInstances.isEmpty()) {
            baseFields
        } else {
            val effectInstanceHashes = node.effectInstances
                .map { hash(it, stack) }
                .sortedWith(byteArrayLexicographicComparator)
                .map { CanonicalCbor.encodeBytes(it) }
            baseFields + CanonicalCbor.encodeArray(effectInstanceHashes)
        }
        return encodeWithTag(CategoryTag.Application, allFields)
    }

    private fun encodeLet(letId: NodeId, node: Node.Let, stack: BinderStack): ByteArray {
        // The Let's value is in the OUTER stack; the body sees the Let as a
        // single binder. VarRef.binder pointing at this Let's NodeId resolves
        // to (depth 0, index 0) within the body.
        val valueEncoding = encodeExpressionChild(node.value, stack)
        val newStack = stack + listOf(listOf(letId))
        val bodyEncoding = encodeExpressionChild(node.body, newStack)
        return encodeWithTag(CategoryTag.Let, listOf(valueEncoding, bodyEncoding))
    }

    private fun encodeVarRef(node: Node.VarRef, stack: BinderStack): ByteArray {
        // If the binder is not in scope, the verifier will report
        // `UnboundVariable`; emit an out-of-range sentinel so finalize can
        // still produce a hash for the surrounding subgraph and the verifier
        // gets a chance to run.
        val position = resolvePosition(node.binder, stack) ?: UNBOUND_SENTINEL
        return encodeWithTag(CategoryTag.VarRef, listOf(
            CanonicalCbor.encodeUint(position.depth.toLong()),
            CanonicalCbor.encodeUint(position.index.toLong()),
        ))
    }

    private fun encodeNodeRef(node: Node.NodeRef, @Suppress("UNUSED_PARAMETER") stack: BinderStack): ByteArray {
        // Canonical form: NodeRef.target is the pre-computed Hash of the
        // referenced node, populated by Hasher.finalize. The encoder emits
        // the multi-hash bytes directly — the verifier's
        // NodeRefTargetMustBeClosed rule guarantees the hash is context-
        // independent, so the binder [stack] is irrelevant here.
        //
        // These bytes are byte-identical to those emitted by the raw form
        // (StoredNode.RawNodeRef) for the same target, which is what keeps
        // pre-step-2 corpus hashes intact across the rewrite.
        return encodeWithTag(CategoryTag.NodeRef, listOf(
            CanonicalCbor.encodeBytes(node.target.bytes),
        ))
    }

    private fun encodeForeignNode(node: Node.ForeignNode, stack: BinderStack): ByteArray {
        // [tag=20, target-utf8-bytes, foreignType-hash, sorted-effect-hashes,
        //  effectProjections?]
        // The optional `binding → Provenance` edge is metadata-excluded and
        // therefore not present in the canonical encoding (Layer 4 step 1
        // does not yet model the binding edge in the in-memory ADT either).
        //
        // Q-039: `effectProjections` is gated on non-empty — pre-Q-039
        // ForeignNodes (no projections) hash byte-identically to their
        // post-Q-039 form, so every existing corpus program's hash is
        // unchanged.
        val effectHashes = node.effects
            .map { hash(it, stack) }
            .sortedWith(byteArrayLexicographicComparator)
            .map { CanonicalCbor.encodeBytes(it) }
        val baseFields = listOf(
            CanonicalCbor.encodeBytes(node.target.toByteArray(Charsets.UTF_8)),
            CanonicalCbor.encodeBytes(hash(node.foreignType, stack)),
            CanonicalCbor.encodeArray(effectHashes),
        )
        val allFields = if (node.effectProjections.isEmpty()) {
            baseFields
        } else {
            baseFields + encodeEffectProjectionList(node.effectProjections, stack)
        }
        return encodeWithTag(CategoryTag.ForeignNode, allFields)
    }

    /**
     * Encode a non-empty list of [EffectProjection] as a single CBOR field
     * (per Q-039 § 4.2). Used by [encodeForeignNode] and
     * [encodeFunctionType]; called only when the list is non-empty so the
     * gate on the calling side preserves pre-Q-039 hashes.
     *
     * The projection list is *positional* — its order matches the order of
     * the enclosing node's `effects` list (each projection covers the
     * same-index effect). Unlike `effects` (which is encoded as a sorted
     * set), `effectProjections` is encoded in declaration order because
     * its order is structural (it parallels `effects`).
     */
    private fun encodeEffectProjectionList(
        projections: List<EffectProjection>,
        stack: BinderStack,
    ): ByteArray {
        val entries = projections.map { proj -> encodeEffectProjection(proj, stack) }
        return CanonicalCbor.encodeArray(entries)
    }

    private fun encodeEffectProjection(
        proj: EffectProjection,
        stack: BinderStack,
    ): ByteArray {
        // Each projection encodes as [category-hash, [source, source, ...]].
        // The category hash is computed via `hash(...)` so two projections
        // covering structurally-equal categories produce identical bytes.
        // Sources are encoded in declaration order — their order is
        // structural (positional within the EffectCategory.parameters).
        val sourceEntries = proj.sources.map { src -> encodeProjectionSource(src, stack) }
        return CanonicalCbor.encodeArray(listOf(
            CanonicalCbor.encodeBytes(hash(proj.category, stack)),
            CanonicalCbor.encodeArray(sourceEntries),
        ))
    }

    private fun encodeProjectionSource(
        src: ProjectionSource,
        stack: BinderStack,
    ): ByteArray {
        // Tagged-union encoding: [tag, payload]. Tag 0 = ArgRef (payload =
        // index as CBOR uint), Tag 1 = LiteralNode (payload = target hash
        // as CBOR bytes). Positions stable forever per Q-039 § 4.2.
        return when (src) {
            is ProjectionSource.ArgRef -> CanonicalCbor.encodeArray(listOf(
                CanonicalCbor.encodeUint(0),
                CanonicalCbor.encodeUint(src.index.toLong()),
            ))
            is ProjectionSource.LiteralNode -> CanonicalCbor.encodeArray(listOf(
                CanonicalCbor.encodeUint(1),
                CanonicalCbor.encodeBytes(hash(src.target, stack)),
            ))
        }
    }

    // ----- Effects and capabilities -----

    private fun encodeEffectCategory(node: Node.EffectCategory, stack: BinderStack): ByteArray {
        val paramEncodings = node.parameters.map { paramId ->
            encodeTypePositionChild(paramId, stack)
        }
        return encodeWithTag(CategoryTag.EffectCategory, listOf(
            CanonicalCbor.encodeBytes(node.categoryName.toByteArray(Charsets.UTF_8)),
            CanonicalCbor.encodeArray(paramEncodings),
        ))
    }

    private fun encodeEffectDecl(node: Node.EffectDecl, stack: BinderStack): ByteArray {
        val paramEncodings = node.parameters.map { paramId ->
            encodeExpressionChild(paramId, stack)
        }
        return encodeWithTag(CategoryTag.EffectDecl, listOf(
            CanonicalCbor.encodeBytes(hash(node.effectType, stack)),
            CanonicalCbor.encodeArray(paramEncodings),
        ))
    }

    // ----- Control flow -----

    private fun encodeMatch(node: Node.Match, stack: BinderStack): ByteArray {
        // The scrutinee is evaluated in the OUTER stack. Cases each carry
        // their own binder context (pushed inside encodeMatchCase if the
        // pattern is a VariablePattern). Case order is structural — pattern
        // semantics is "first match wins" — so we encode cases in
        // declaration order.
        val caseEncodings = node.cases.map { caseId ->
            CanonicalCbor.encodeBytes(hash(caseId, stack))
        }
        return encodeWithTag(CategoryTag.Match, listOf(
            encodeExpressionChild(node.scrutinee, stack),
            CanonicalCbor.encodeArray(caseEncodings),
        ))
    }

    private fun encodeMatchCase(id: NodeId, node: Node.MatchCase, stack: BinderStack): ByteArray {
        // Pattern itself is encoded in the OUTER stack (its patternType may
        // reference outer TypeParameters). The body is encoded in an
        // EXTENDED stack: every VariablePattern reachable from `pattern`
        // (transitively via ConstructorPattern.payloadPattern edges) is
        // pushed as one binder frame so VarRefs in the body that point at
        // any of those patterns resolve positionally.
        val patternNode = fetchCanonical(node.pattern)
        if (patternNode !is Node.Pattern) {
            error(
                "MatchCase $id pattern field points to a non-Pattern node " +
                    "(${patternNode::class.simpleName}); verifier should have rejected this."
            )
        }
        val binders = collectPatternBinders(node.pattern, patternNode)
        val bodyStack = if (binders.isEmpty()) stack else stack + listOf(binders)
        return encodeWithTag(CategoryTag.MatchCase, listOf(
            CanonicalCbor.encodeBytes(hash(node.pattern, stack)),
            encodeExpressionChild(node.body, bodyStack),
        ))
    }

    private fun collectPatternBinders(patternId: NodeId, pattern: Node.Pattern): List<NodeId> =
        org.strand.hashing.collectPatternBinders(lookup, patternId, pattern)

    // ----- Composite values -----

    private fun encodeProductValue(node: Node.ProductValue, stack: BinderStack): ByteArray {
        // Sort fields by fieldName for canonical order — two ProductValues
        // with the same field-name-to-value mapping must hash identically,
        // matching the canonical-field-ordering rule for ProductType.
        val sortedFieldIds = node.fields.sortedBy { fieldId ->
            requireProductFieldValue(fieldId).fieldName
        }
        val fieldHashes = sortedFieldIds.map { fieldId ->
            CanonicalCbor.encodeBytes(hash(fieldId, stack))
        }
        return encodeWithTag(CategoryTag.ProductValue, listOf(
            CanonicalCbor.encodeBytes(hash(node.ofType, stack)),
            CanonicalCbor.encodeArray(fieldHashes),
        ))
    }

    private fun encodeProductFieldValue(node: Node.ProductFieldValue, stack: BinderStack): ByteArray {
        return encodeWithTag(CategoryTag.ProductFieldValue, listOf(
            CanonicalCbor.encodeBytes(node.fieldName.toByteArray(Charsets.UTF_8)),
            encodeExpressionChild(node.value, stack),
        ))
    }

    private fun encodeProductFieldGet(node: Node.ProductFieldGet, stack: BinderStack): ByteArray {
        return encodeWithTag(CategoryTag.ProductFieldGet, listOf(
            encodeExpressionChild(node.target, stack),
            CanonicalCbor.encodeBytes(node.fieldName.toByteArray(Charsets.UTF_8)),
        ))
    }

    private fun encodeSumValue(node: Node.SumValue, stack: BinderStack): ByteArray {
        // [tag=40, ofType-hash, caseName-bytes, presence, optional payload-hash]
        val payload = node.payload
        val payloadFields = if (payload == null) {
            listOf(CanonicalCbor.encodeUint(0L))
        } else {
            listOf(
                CanonicalCbor.encodeUint(1L),
                encodeExpressionChild(payload, stack),
            )
        }
        return encodeWithTag(CategoryTag.SumValue, listOf(
            CanonicalCbor.encodeBytes(hash(node.ofType, stack)),
            CanonicalCbor.encodeBytes(node.caseName.toByteArray(Charsets.UTF_8)),
        ) + payloadFields)
    }

    private fun requireProductFieldValue(id: NodeId): Node.ProductFieldValue {
        val node = fetchCanonical(id)
        return node as? Node.ProductFieldValue ?: error(
            "Expected ProductFieldValue at $id, got ${node::class.simpleName}"
        )
    }

    private fun encodeFixpoint(node: Node.Fixpoint, stack: BinderStack): ByteArray {
        // [tag=26, recursionType-hash, body-hash]. The body is a Lambda
        // whose first parameter is the recursive call slot; that
        // structural relationship is verified, not encoded — the canonical
        // bytes simply reference the two children by hash.
        return encodeWithTag(CategoryTag.Fixpoint, listOf(
            CanonicalCbor.encodeBytes(hash(node.recursionType, stack)),
            CanonicalCbor.encodeBytes(hash(node.body, stack)),
        ))
    }

    private fun encodePattern(node: Node.Pattern, stack: BinderStack): ByteArray {
        // All Pattern variants share tag 25 (one node category in the
        // algebra). A small kind discriminator distinguishes the variants:
        //   0 = LiteralPattern, 1 = VariablePattern, 2 = WildcardPattern,
        //   3 = ConstructorPattern.
        // Variable-pattern names are metadata (analogous to ParameterDecl.
        // name) and are NOT encoded — two variable patterns of the same
        // type, differing only in their bound name, hash identically.
        return when (node) {
            is Node.Pattern.LiteralPattern -> encodeWithTag(CategoryTag.Pattern, listOf(
                CanonicalCbor.encodeUint(KIND_LITERAL),
                encodeTypePositionChild(node.patternType, stack),
                CanonicalCbor.encodeBytes(hash(node.literal, stack)),
            ))
            is Node.Pattern.VariablePattern -> encodeWithTag(CategoryTag.Pattern, listOf(
                CanonicalCbor.encodeUint(KIND_VARIABLE),
                encodeTypePositionChild(node.patternType, stack),
            ))
            is Node.Pattern.WildcardPattern -> encodeWithTag(CategoryTag.Pattern, listOf(
                CanonicalCbor.encodeUint(KIND_WILDCARD),
                encodeTypePositionChild(node.patternType, stack),
            ))
            is Node.Pattern.ConstructorPattern -> {
                // [kind=3, patternType-encoding, caseName-bytes, presence,
                //  optional payload-pattern-hash]
                val payload = node.payloadPattern
                val payloadFields = if (payload == null) {
                    listOf(CanonicalCbor.encodeUint(0L))
                } else {
                    listOf(
                        CanonicalCbor.encodeUint(1L),
                        CanonicalCbor.encodeBytes(hash(payload, stack)),
                    )
                }
                encodeWithTag(CategoryTag.Pattern, listOf(
                    CanonicalCbor.encodeUint(KIND_CONSTRUCTOR),
                    encodeTypePositionChild(node.patternType, stack),
                    CanonicalCbor.encodeBytes(node.caseName.toByteArray(Charsets.UTF_8)),
                ) + payloadFields)
            }
        }
    }

    private fun encodeCapabilityScope(node: Node.CapabilityScope, stack: BinderStack): ByteArray {
        // Capability list is a set: sort by hash for canonical order.
        val capabilityHashes = node.capabilities
            .map { hash(it, stack) }
            .sortedWith(byteArrayLexicographicComparator)
            .map { CanonicalCbor.encodeBytes(it) }
        return encodeWithTag(CategoryTag.CapabilityScope, listOf(
            CanonicalCbor.encodeArray(capabilityHashes),
            encodeExpressionChild(node.body, stack),
        ))
    }

    private fun encodeHandler(node: Node.Handler, stack: BinderStack): ByteArray {
        // [tag=43, intercept-hash, handle-hash, body-hash]. Handler introduces
        // no binder of its own — `handle` is evaluated in the surrounding
        // scope, `body` is too. The three fields are in fixed positional
        // order so identity is fully structural: two Handlers with the same
        // (intercept, handle, body) triple hash byte-identically.
        return encodeWithTag(CategoryTag.Handler, listOf(
            CanonicalCbor.encodeBytes(hash(node.intercept, stack)),
            encodeExpressionChild(node.handle, stack),
            encodeExpressionChild(node.body, stack),
        ))
    }

    // ----- State machines (N-027..N-029) -----

    private fun encodeStateMachine(node: Node.StateMachine, stack: BinderStack): ByteArray {
        // [tag=27, transitionFn-hash, initialState-hash,
        //  inputStreams[]-hash-list (positional, ORDER MATTERS),
        //  outputStreams[]-hash-list (positional, ORDER MATTERS),
        //  effects[]-hash-set (sorted)]
        // Input/output streams are positional: outputs are indexed by their
        // position into the runtime's OutputBatch (field name "output_i"),
        // and step 2 will tag input events by their stream's position too.
        // Effects are a set (sort by hash bytes for determinism).
        val inputHashes = node.inputStreams.map { CanonicalCbor.encodeBytes(hash(it, stack)) }
        val outputHashes = node.outputStreams.map { CanonicalCbor.encodeBytes(hash(it, stack)) }
        val effectHashes = node.effects
            .map { hash(it, stack) }
            .sortedWith(byteArrayLexicographicComparator)
            .map { CanonicalCbor.encodeBytes(it) }
        return encodeWithTag(CategoryTag.StateMachine, listOf(
            CanonicalCbor.encodeBytes(hash(node.transitionFn, stack)),
            encodeExpressionChild(node.initialState, stack),
            CanonicalCbor.encodeArray(inputHashes),
            CanonicalCbor.encodeArray(outputHashes),
            CanonicalCbor.encodeArray(effectHashes),
        ))
    }

    private fun encodeEventStream(node: Node.EventStream, stack: BinderStack): ByteArray {
        // [tag=28, eventType-hash, streamKind-uint,
        //  optional bufferSize-uint, optional overflowPolicy-tag (+ Sample param),
        //  optional consumerMode-uint]
        // streamKind ordinal: 0=External, 1=Internal, 2=Output. The order
        // is the enum declaration order in core/Node.kt — stable forever.
        //
        // Slice 3.1 / 3.6 additive-versioning rule: when bufferSize == null,
        // overflowPolicy is null or BlockProducer (the default), AND
        // consumerMode is null or Single (the default), the encoder emits no
        // additional fields and the bytes are byte-identical to the
        // pre-step-3 encoding. Pre-step-3 corpus EventStream hashes are
        // therefore unchanged.
        //
        // When at least one of the three new fields is set to a non-default
        // value, ALL THREE are emitted (with sentinels for fields left at
        // default) so the encoding remains unambiguous. The ordering is
        // bufferSize, overflowPolicy, consumerMode. The policy carries its
        // own tag (0=Block, 1=DropNew, 2=DropOld, 3=Sample), with the Sample
        // variant trailed by its intervalNanos parameter. ConsumerMode
        // ordinal: 0=Single, 1=Broadcast — stable forever.
        // Q-046 additive-versioning rule: when source == null the encoding is
        // byte-identical to the pre-Q-046 form in BOTH branches below (the
        // all-default base-only case and the slice-3.1/3.6 some-non-default
        // case). When source is set, its hash is appended as a single trailing
        // field. This is collision-free: a source-only stream encodes to 3
        // fields ([eventType, streamKind, source-hash]) — a length no pre-Q-046
        // EventStream produces (those produce 2 or 5) — and the trailing field
        // is a byte string, distinct in CBOR major type from the trailing uint
        // (consumerMode) of the 5-field form.
        val baseFields = mutableListOf(
            CanonicalCbor.encodeBytes(hash(node.eventType, stack)),
            CanonicalCbor.encodeUint(node.streamKind.ordinal.toLong()),
        )
        val sourceField: ByteArray? =
            node.source?.let { CanonicalCbor.encodeBytes(hash(it, stack)) }
        val hasNonDefaultBuffer = node.bufferSize != null
        val hasNonDefaultPolicy = node.overflowPolicy != null &&
            node.overflowPolicy !is OverflowPolicy.BlockProducer
        val hasNonDefaultMode = node.consumerMode != null &&
            node.consumerMode != ConsumerMode.Single
        if (!hasNonDefaultBuffer && !hasNonDefaultPolicy && !hasNonDefaultMode) {
            val fields = if (sourceField != null) baseFields + sourceField else baseFields
            return encodeWithTag(CategoryTag.EventStream, fields)
        }
        // Slice 3.1 / 3.6 fields: bufferSize (sentinel 0 for unset / default),
        // policy tag + optional Sample param, then consumerMode (sentinel 0 =
        // Single). Using 0 as "unset" for bufferSize is safe because a real
        // bufferSize of 0 makes no semantic sense (no events could be queued);
        // the verifier could reject it as MalformedOverflowPolicy for future
        // tightening.
        val bufferEncoded = CanonicalCbor.encodeUint((node.bufferSize ?: 0).toLong())
        val policyFields = encodeOverflowPolicy(node.overflowPolicy ?: OverflowPolicy.BlockProducer)
        val modeEncoded = CanonicalCbor.encodeUint((node.consumerMode ?: ConsumerMode.Single).ordinal.toLong())
        val slice36 = baseFields + bufferEncoded + policyFields + modeEncoded
        val fields = if (sourceField != null) slice36 + sourceField else slice36
        return encodeWithTag(CategoryTag.EventStream, fields)
    }

    /**
     * Encode an [OverflowPolicy] as a small tag (0=Block, 1=DropNew,
     * 2=DropOld, 3=Sample) followed by any per-variant parameters. The
     * Sample variant emits its `intervalNanos` parameter as a CBOR uint;
     * the other three variants are bare tags.
     */
    private fun encodeOverflowPolicy(policy: OverflowPolicy): List<ByteArray> = when (policy) {
        OverflowPolicy.BlockProducer -> listOf(CanonicalCbor.encodeUint(0L))
        OverflowPolicy.DropNewest -> listOf(CanonicalCbor.encodeUint(1L))
        OverflowPolicy.DropOldest -> listOf(CanonicalCbor.encodeUint(2L))
        is OverflowPolicy.Sample -> listOf(
            CanonicalCbor.encodeUint(3L),
            CanonicalCbor.encodeUint(policy.intervalNanos),
        )
    }

    private fun encodeTransition(node: Node.Transition, stack: BinderStack): ByteArray {
        // [tag=29, presence, optional guard-hash, body-hash]. The optional
        // guard is gated on its presence; a guard-less Transition (always-
        // taken) hashes differently from one with a guard, even if the
        // guard's value is always true.
        val guard = node.guard
        val guardFields = if (guard == null) {
            listOf(CanonicalCbor.encodeUint(0L))
        } else {
            listOf(
                CanonicalCbor.encodeUint(1L),
                encodeExpressionChild(guard, stack),
            )
        }
        return encodeWithTag(CategoryTag.Transition, guardFields + listOf(
            encodeExpressionChild(node.body, stack),
        ))
    }

    // ----- Structured outputs (N-032, N-033) -----

    private fun encodeSchema(node: Node.Schema, stack: BinderStack): ByteArray {
        // [tag=32, schemaName-utf8-bytes, valueType-hash, sorted-invariant-hashes]
        // Invariants are a SET — the order in which they are declared has no
        // semantic significance. Sort by hash bytes for canonical-order
        // determinism so two Schemas with the same invariants in any order
        // hash identically. The optional `libraryBinding` Provenance edge is
        // metadata-excluded per the design (the in-memory ADT does not
        // currently carry it; the field is reserved for the security-model
        // work that lands alongside ForeignNode-backed checkers).
        val invariantHashes = node.invariants
            .map { hash(it, stack) }
            .sortedWith(byteArrayLexicographicComparator)
            .map { CanonicalCbor.encodeBytes(it) }
        return encodeWithTag(CategoryTag.Schema, listOf(
            CanonicalCbor.encodeBytes(node.schemaName.toByteArray(Charsets.UTF_8)),
            CanonicalCbor.encodeBytes(hash(node.valueType, stack)),
            CanonicalCbor.encodeArray(invariantHashes),
        ))
    }

    // ----- Agent-native capabilities (N-044, N-045) -----

    private fun encodeToolDef(node: Node.ToolDef, stack: BinderStack): ByteArray {
        // [tag=44, parameterSchema-hash, implementation-hash].
        //
        // `name` and `description` are metadata content fields and are
        // intentionally EXCLUDED from the canonical encoding (consistent
        // with ParameterDecl.name treatment). Two ToolDefs that share
        // parameterSchema and implementation but differ only in name or
        // description hash identically — letting agents rename a tool
        // without churning the program's hash.
        //
        // Both edges are positional (fixed order parameterSchema then
        // implementation); their identity is fully structural so two
        // ToolDefs with the same (parameterSchema, implementation) pair
        // hash byte-identically.
        return encodeWithTag(CategoryTag.ToolDef, listOf(
            CanonicalCbor.encodeBytes(hash(node.parameterSchema, stack)),
            encodeExpressionChild(node.implementation, stack),
        ))
    }

    private fun encodeResponseSchemaSpec(node: Node.ResponseSchemaSpec, stack: BinderStack): ByteArray {
        // [tag=45, schema-hash].
        //
        // ResponseSchemaSpec is a one-edge wrapper: its structural identity
        // is the Schema reference alone. The wrapper has no metadata fields
        // (no implementation to dispatch, no per-call name to forward),
        // so two ResponseSchemaSpecs around equal Schemas hash byte-
        // identically.
        return encodeWithTag(CategoryTag.ResponseSchemaSpec, listOf(
            CanonicalCbor.encodeBytes(hash(node.schema, stack)),
        ))
    }

    // ----- Composition and distribution (N-046) -----

    /**
     * Encode a canonical [Node.ModuleManifest] (post-finalize: export targets
     * are [org.strand.core.Hash] values). [tag=46, [export-entry, ...]].
     *
     * The export list is positional (matching ProductType.fields — author-
     * significant order is preserved). `displayName` and `manifestSignature`
     * are metadata and are NOT encoded.
     */
    private fun encodeModuleManifest(node: Node.ModuleManifest, stack: BinderStack): ByteArray {
        val exportEntries = node.exports.map { export ->
            encodeManifestExportEntry(export.target.bytes, export.declaredEffects, stack)
        }
        return encodeWithTag(CategoryTag.ModuleManifest, listOf(
            CanonicalCbor.encodeArray(exportEntries),
        ))
    }

    /**
     * Encode the pre-finalize [StoredNode.RawModuleManifest] (export targets
     * are in-document [NodeId]s). Produces bytes byte-identical to
     * [encodeModuleManifest] for the same manifest after finalize: the raw
     * path supplies `hash(targetNodeId)`, the canonical path supplies the
     * resolved `target.bytes`, and `Hasher.finalize` guarantees these are
     * equal (it sets `export.target = nodeIdToHash[rawExport.target]`, the
     * same hash this encoder computes). This is the same raw/canonical byte-
     * identity that [encodeRawNodeRef] / [encodeNodeRef] maintain for NodeRef.
     */
    private fun encodeRawModuleManifest(stored: StoredNode.RawModuleManifest, stack: BinderStack): ByteArray {
        val exportEntries = stored.exports.map { export ->
            encodeManifestExportEntry(hash(export.target, stack), export.declaredEffects, stack)
        }
        return encodeWithTag(CategoryTag.ModuleManifest, listOf(
            CanonicalCbor.encodeArray(exportEntries),
        ))
    }

    /**
     * Encode one manifest export as a CBOR array
     * `[target-hash-bytes, sorted-declaredEffect-hash-array]`. Shared by the
     * raw and canonical encode paths so they emit byte-identical output. The
     * effect hashes are lex-sorted (set semantics — declaration order does
     * not affect identity, matching FunctionType.effects). `displayName` is
     * metadata and is NOT encoded.
     */
    private fun encodeManifestExportEntry(
        targetHashBytes: ByteArray,
        declaredEffects: List<NodeId>,
        stack: BinderStack,
    ): ByteArray {
        val effectHashes = declaredEffects
            .map { hash(it, stack) }
            .sortedWith(byteArrayLexicographicComparator)
            .map { CanonicalCbor.encodeBytes(it) }
        return CanonicalCbor.encodeArray(listOf(
            CanonicalCbor.encodeBytes(targetHashBytes),
            CanonicalCbor.encodeArray(effectHashes),
        ))
    }

    private fun encodeInvariant(node: Node.Invariant, stack: BinderStack): ByteArray {
        // [tag=33, invariantName-utf8-bytes, body-hash].
        //
        // Notable: `targetSchema` is intentionally EXCLUDED from the
        // canonical encoding. Including it would create a Schema↔Invariant
        // hash cycle (Schema encodes invariants by hash; Invariant would
        // encode targetSchema by hash → infinite recursion), which the
        // proposal's literal encoding scheme does not address. This matches
        // the pattern used by N-009 ProductTypeField and N-011 SumTypeCase,
        // whose parent ProductType / SumType is not part of their
        // canonical encoding — the parent-child relationship is recoverable
        // from the parent's `fields` / `cases` list.
        //
        // The `targetSchema` field is retained on the in-memory ADT for the
        // verifier's `InvariantTargetMismatch` defensive topology check.
        // Two Invariants with the same (invariantName, body) pair therefore
        // hash identically even if they were originally authored against
        // different Schema nodes; the Schema that lists them is the
        // authoritative association.
        return encodeWithTag(CategoryTag.Invariant, listOf(
            CanonicalCbor.encodeBytes(node.invariantName.toByteArray(Charsets.UTF_8)),
            CanonicalCbor.encodeBytes(hash(node.body, stack)),
        ))
    }

    // ----- Child-position helpers -----

    /**
     * Encode a child that appears in type position. If the child is a
     * TypeParameter bound in [stack], emits its positional reference inline;
     * otherwise emits the child's hash as a CBOR byte string.
     */
    private fun encodeTypePositionChild(childId: NodeId, stack: BinderStack): ByteArray {
        val child = fetchCanonical(childId)
        return if (child is Node.TypeParameter && resolvePosition(childId, stack) != null) {
            // Bound TypeParameter: inline positional reference (NOT a hash).
            encode(childId, stack)
        } else {
            // Any other type, or a free TypeParameter (the verifier would
            // reject the latter, but we encode defensively): emit the child's
            // hash as a 33-byte byte string.
            CanonicalCbor.encodeBytes(hash(childId, stack))
        }
    }

    /**
     * Encode a child that appears in expression position: always emits the
     * child's hash as a CBOR byte string. VarRefs are hashed normally; their
     * positional encoding is captured inside the VarRef's own canonical form.
     */
    private fun encodeExpressionChild(childId: NodeId, stack: BinderStack): ByteArray =
        CanonicalCbor.encodeBytes(hash(childId, stack))

    // ----- Common framing -----

    private fun encodeWithTag(tag: CategoryTag, fields: List<ByteArray>): ByteArray {
        val tagBytes = tag.toBytes()
        val totalSize = tagBytes.size + fields.sumOf { it.size }
        val out = ByteArray(totalSize)
        tagBytes.copyInto(out, 0)
        var pos = tagBytes.size
        for (f in fields) {
            f.copyInto(out, pos)
            pos += f.size
        }
        return out
    }

    // ----- Binder-stack utilities -----

    private data class BoundPosition(val depth: Int, val index: Int)

    /**
     * Find [target] in the binder stack. Returns the de Bruijn position:
     * depth counts from the innermost frame (depth 0 = innermost), index is
     * the offset within the matched frame.
     */
    private fun resolvePosition(target: NodeId, stack: BinderStack): BoundPosition? {
        for (i in stack.indices.reversed()) {
            val idx = stack[i].indexOf(target)
            if (idx >= 0) {
                return BoundPosition(depth = stack.lastIndex - i, index = idx)
            }
        }
        return null
    }

    // ----- Cache key -----

    private data class EncodingKey(val id: NodeId, val stack: BinderStack, val recDepth: Int)

    // ----- Store-typed accessors with clearer error messages -----

    private fun requireProductTypeField(id: NodeId): Node.ProductTypeField {
        val node = fetchCanonical(id)
        return node as? Node.ProductTypeField ?: error(
            "Expected ProductTypeField at $id, got ${node::class.simpleName}"
        )
    }

    private fun requireSumTypeCase(id: NodeId): Node.SumTypeCase {
        val node = fetchCanonical(id)
        return node as? Node.SumTypeCase ?: error(
            "Expected SumTypeCase at $id, got ${node::class.simpleName}"
        )
    }

    private fun requireParameterDecl(id: NodeId): Node.ParameterDecl {
        val node = fetchCanonical(id)
        return node as? Node.ParameterDecl ?: error(
            "Expected ParameterDecl at $id, got ${node::class.simpleName}"
        )
    }

    private fun doubleToBytes(d: Double): ByteArray {
        val bits = d.toRawBits()
        return ByteArray(8) { i ->
            ((bits ushr (56 - i * 8)) and 0xFF).toByte()
        }
    }

    /**
     * Lexicographic comparator on byte arrays, used to put set-like edge
     * lists (effect declarations, capability lists) into canonical order.
     * Bytes compared as unsigned 8-bit values.
     */
    private val byteArrayLexicographicComparator = Comparator<ByteArray> { a, b ->
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a[i].toInt() and 0xFF
            val bi = b[i].toInt() and 0xFF
            if (ai != bi) return@Comparator ai - bi
        }
        a.size - b.size
    }

    private companion object {
        // Pattern variant discriminators. Stable forever — never reused.
        const val KIND_LITERAL: Long = 0
        const val KIND_VARIABLE: Long = 1
        const val KIND_WILDCARD: Long = 2
        const val KIND_CONSTRUCTOR: Long = 3

        /**
         * Sentinel returned by [encodeVarRef] / [encodeBoundTypeParameter] when
         * the referenced binder is out of scope. The verifier later reports
         * the ill-formedness; emitting a deterministic sentinel here lets
         * finalize proceed and the verifier surface the real error.
         */
        val UNBOUND_SENTINEL = BoundPosition(depth = Int.MAX_VALUE, index = Int.MAX_VALUE)
    }
}
