package org.strand.hashing

import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.translateNodeIds

/**
 * Layer 2 step 3 — the federation-aware extension of [FinalizedProgram].
 *
 * Carries everything [FinalizedProgram] does, plus a [NodeResolver] that
 * the verifier and interpreter consult whenever a [org.strand.core.Node.NodeRef]'s
 * target hash is not in the local [hashToNodeId] map. When the resolver
 * returns a [SubgraphFetch], the verifier admits every fetched node to
 * the local [store] (assigning fresh local NodeIds) and **translates**
 * each admitted node's internal NodeId references from the foreign
 * ID space to the local one via the foreign→local mapping built during
 * admission.
 *
 * **Mutability** — [store], [nodeIdToHash], and [hashToNodeId] are
 * mutable references in the runtime sense: the verifier extends them as
 * it fetches subgraphs. The `data class` shape is preserved so callers
 * that don't fetch see a stable record; callers that do fetch see the
 * record grow over the session. For single-store programs that never
 * trigger a resolver call, the record is observationally identical to
 * [FinalizedProgram].
 *
 * **Upgrading from [FinalizedProgram]** — use [FinalizedProgram.federated]
 * to wrap an existing program with a resolver. Passing [NoOpResolver]
 * preserves pre-Q-043 behavior bit-for-bit (every NodeRef target hash must
 * already be in `hashToNodeId`, else the verifier raises
 * `NodeRefTargetNotFound` / `NodeRefTargetUnresolvable`).
 */
data class FederatedProgram(
    val store: NodeStore,
    val root: NodeId,
    val nodeIdToHash: MutableMap<NodeId, Hash>,
    val hashToNodeId: MutableMap<Hash, NodeId>,
    val resolver: NodeResolver,
) {
    /**
     * Resolves [hash] through the federation chain and, on success, admits
     * every fetched node to the local [store] under fresh local [NodeId]s.
     * Returns the locally-assigned [NodeId] of the root node, or `null` if
     * the resolver does not hold the hash.
     *
     * **Admission protocol (Q-043 proposal § 4.5):**
     *
     * 1. The resolver returns a [SubgraphFetch] containing every internal
     *    node reachable from the requested root (stopping at NodeRef
     *    boundaries), plus the foreign store's `nodeIdToHash` mapping.
     * 2. Walk the subgraph post-order from the root. Each fetched node is
     *    admitted to the local store under a fresh local NodeId, with its
     *    internal NodeId references **translated** from foreign IDs to local
     *    IDs via `Node.translateNodeIds`. The translation rewrites every
     *    NodeId-typed field exhaustively across the Node sealed hierarchy;
     *    within-fetch DAG sharing and reference cycles are resolved by a
     *    foreign→local memo seeded before recursion (see [admit]).
     * 3. Hashable nodes register their `hash → localId` mapping (without
     *    clobbering a pre-existing one). Interior nodes are *not* deduped
     *    against the local store by hash — see [admit] for why that is unsafe
     *    for context-dependent (open) nodes. Cross-target sharing happens at
     *    whole-target granularity through this method's leading
     *    `hashToNodeId[hash]` memo.
     *
     * **Integrity (Q-043 § 4.5b, refined).** Rather than re-hashing every
     * admitted node, the implementation recomputes the canonical hash of the
     * admitted local *root* and requires it to equal the requested hash. By
     * the Merkle property the root hash binds the entire reachable subgraph,
     * so a single root re-hash detects any unfaithful admission — including a
     * mis-rebased binder (which would shift a de Bruijn position and change
     * the hash). A mismatch raises [NodeResolverIntegrityViolation]. This is
     * what makes the protocol trust-minimizing: the resolver need not be
     * trusted because the hash binds the content.
     *
     * **Fresh admission.** Every fetched node — bound nodes
     * ([Node.ParameterDecl] / [Node.TypeParameter] / [Node.RecursiveSelf], which
     * have no standalone hash) and hashable nodes alike — is admitted fresh as
     * part of its parent's re-base, so every binder reference stays faithful to
     * the fetched subgraph. Reference cycles (Schema↔Invariant; a VarRef to an
     * enclosing binder) are handled by reserving the local NodeId with an
     * untranslated placeholder *before* recursing, so a cyclic reference
     * resolves to the reserved id; the placeholder is then overwritten with the
     * re-based node.
     */
    fun fetchAndAdmit(hash: Hash): NodeId? {
        hashToNodeId[hash]?.let { return it }
        val fetch = resolver.resolve(hash) ?: return null
        val localRoot = admit(fetch.rootId, fetch, HashMap())
        // Trust-minimizing content integrity check: the admitted local root
        // must canonically hash to the requested hash (Merkle: the root hash
        // binds the whole subgraph).
        val recomputed = Hasher(store).hashRoot(localRoot)
        if (recomputed != hash) {
            throw NodeResolverIntegrityViolation(expected = hash, actual = recomputed)
        }
        return localRoot
    }

    /**
     * Admit one fetched node (by foreign NodeId) into the local store, returning
     * its local NodeId. Post-order over the node's references via
     * [Node.translateNodeIds]:
     *  - already admitted in *this* fetch → return the memoized local id
     *    ([foreignToLocal]); this is what preserves the fetched subgraph's own
     *    DAG sharing (a node referenced from two places re-bases to one local id)
     *    and terminates reference cycles;
     *  - otherwise reserve a fresh local id with an untranslated placeholder
     *    (so a cyclic reference back to this node resolves to the reserved id),
     *    re-base the node's references via `translateNodeIds { admit(it) }`,
     *    overwrite the placeholder, and — for hashable nodes — register the
     *    hash↔id mapping (without clobbering a pre-existing mapping).
     *
     * **Why no interior hash-dedup against the local store.** It is tempting to
     * reuse an existing local node when a fetched interior node carries the same
     * hash (`hashToNodeId[h]`). That is *unsafe* for context-dependent (open)
     * nodes. `Hasher.finalize` assigns every node a standalone per-node hash, but
     * for a node carrying a free binder reference — a bare [Node.VarRef], a
     * [Node.RecursiveSelf], a [Node.TypeParameter] reference — the canonical
     * encoder emits an unbound de-Bruijn sentinel, so *every* such node hashes
     * identically regardless of which binder it actually points at. A `VarRef`
     * to a Lambda's first parameter therefore collides with any other first-
     * parameter `VarRef` anywhere in the local store. Deduping on that collision
     * aliases the fetched node's binder reference to an unrelated local binder,
     * shifting a de-Bruijn position and corrupting the re-base — which the root
     * re-hash in [fetchAndAdmit] then (correctly) rejects as an integrity
     * violation. Admitting interior nodes fresh keeps every binder reference
     * faithful; cross-target sharing still happens at whole-target granularity
     * through [fetchAndAdmit]'s leading `hashToNodeId[hash]` memo (so a library
     * export referenced from two NodeRefs is fetched and verified once).
     */
    private fun admit(
        foreignId: NodeId,
        fetch: SubgraphFetch,
        foreignToLocal: MutableMap<NodeId, NodeId>,
    ): NodeId {
        foreignToLocal[foreignId]?.let { return it }
        val node = fetch.nodes[foreignId]
            ?: error(
                "Incomplete SubgraphFetch from resolver: foreign NodeId $foreignId is " +
                    "referenced but not contained in the fetched subgraph."
            )
        val h = fetch.nodeIdToHash[foreignId] // null for bound nodes
        // Reserve a local id (placeholder = untranslated node) and memoize
        // BEFORE recursing, so any cyclic reference back to this node resolves
        // to the reserved id rather than recursing forever.
        val localId = store.add(node)
        foreignToLocal[foreignId] = localId
        val translated: Node = node.translateNodeIds { childForeignId ->
            admit(childForeignId, fetch, foreignToLocal)
        }
        store.set(localId, translated)
        if (h != null) {
            nodeIdToHash[localId] = h
            // putIfAbsent: never clobber a pre-existing local mapping for this
            // hash (e.g. the app's own copy of a shared closed node). Whole-
            // target resolution stays anchored to whatever was registered first.
            hashToNodeId.putIfAbsent(h, localId)
        }
        return localId
    }
}

/**
 * Upgrades a [FinalizedProgram] to a [FederatedProgram] by attaching a
 * resolver. The returned [FederatedProgram] carries mutable copies of
 * the program's maps; the original [FinalizedProgram] is untouched.
 *
 * Passing [NoOpResolver] is the no-op default — every existing corpus
 * program runs against an [NoOpResolver]-backed [FederatedProgram]
 * byte-identically to the pre-Q-043 path.
 */
fun FinalizedProgram.federated(resolver: NodeResolver = NoOpResolver): FederatedProgram =
    FederatedProgram(
        store = store,
        root = root,
        nodeIdToHash = nodeIdToHash.toMutableMap(),
        hashToNodeId = hashToNodeId.toMutableMap(),
        resolver = resolver,
    )
