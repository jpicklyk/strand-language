package org.strand.hashing

import org.strand.core.Hash
import org.strand.core.NodeId
import org.strand.core.NodeStore

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
     * 2. Build a `foreign → local` NodeId map by:
     *    - For each foreign `(fId → h)` entry: if `hashToNodeId[h]`
     *      exists, the mapping is `fId → hashToNodeId[h]`; otherwise
     *      admit `nodes[h]` to the local store under a fresh `lId`,
     *      extend `nodeIdToHash[lId] = h` and `hashToNodeId[h] = lId`,
     *      and record `fId → lId`.
     * 3. Re-walk each admitted Node and **translate** its internal
     *    NodeId references from foreign IDs to local IDs via
     *    `Node.translateNodeIds(foreignToLocal)`. The translation
     *    rewrites every NodeId-typed field exhaustively across the
     *    Node sealed hierarchy.
     * 4. Re-hash each translated node and compare to its declared hash
     *    from the foreign `nodeIdToHash`; mismatch raises
     *    [NodeResolverIntegrityViolation].
     *
     * **Implementation status (step 3a foundation, 2026-05-28):** Steps
     * 1 (resolver fetch) and partial 2 (admission without translation)
     * are implementable with the primitives here. **Steps 3 and 4 —
     * the translation pass and per-node integrity check — require
     * `Node.translateNodeIds` to be implemented in `:core` and the
     * canonical encoder to be exposed as a single-node hash function.
     * Both are queued for the verifier-threading session that follows
     * this foundation.**
     *
     * For now, this method throws [NotImplementedError] when invoked for
     * a non-local hash. Single-store callers (the local hash hits the
     * `hashToNodeId` map) return the existing NodeId without consulting
     * the resolver — that path is fully implemented.
     */
    fun fetchAndAdmit(hash: Hash): NodeId? {
        hashToNodeId[hash]?.let { return it }
        // Resolver fetch — works today.
        val fetch = resolver.resolve(hash) ?: return null
        // The remainder is the translation + per-node integrity check
        // that requires Node.translateNodeIds. Surface this as a clear
        // diagnostic until that work lands.
        throw NotImplementedError(
            "Cross-store admission requires Node.translateNodeIds to translate " +
                "foreign NodeId references into the local ID space (Q-043 § 4.5 step 3). " +
                "Resolver returned a subgraph of ${fetch.nodes.size} nodes rooted at " +
                "$hash — admitting it without translation would corrupt internal " +
                "NodeId references. Translation pass scheduled for the verifier-" +
                "threading commit."
        )
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
