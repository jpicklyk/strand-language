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
 * returns a node, the verifier admits it to the local [store] (assigning
 * a fresh [NodeId]) and extends [hashToNodeId] in place — so the verifier
 * and interpreter never need to know whether a NodeRef target was local
 * at finalize time or fetched at verify time.
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
     * the returned node to the local [store] under a fresh [NodeId];
     * extends both [nodeIdToHash] and [hashToNodeId] in place. Returns the
     * locally-assigned [NodeId] of the admitted node, or `null` if the
     * resolver does not hold the hash.
     *
     * The integrity check is performed by [ChainedResolver] (or whatever
     * resolver wraps this chain); the returned node's hash is guaranteed
     * to equal [hash] by the resolver's contract.
     */
    fun fetchAndAdmit(hash: Hash): NodeId? {
        hashToNodeId[hash]?.let { return it }
        val node = resolver.resolve(hash) ?: return null
        val newId = store.add(node)
        nodeIdToHash[newId] = hash
        hashToNodeId[hash] = newId
        return newId
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
