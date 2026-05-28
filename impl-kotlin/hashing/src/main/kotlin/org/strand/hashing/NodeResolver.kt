package org.strand.hashing

import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.RawNodeStore
import org.strand.core.StoredNode
import org.strand.core.childNodeIds

/**
 * Layer 2 step 3 — cross-store federation entry point.
 *
 * Given a [Hash], returns a self-contained [SubgraphFetch] rooted at that
 * hash, or `null` if the resolver does not hold the hash. The verifier and
 * interpreter consult a resolver whenever a [Node.NodeRef] target's hash
 * is not in the local `hashToNodeId` reverse map.
 *
 * **Why subgraphs and not single nodes** — a Strand [Node] in the
 * in-memory ADT carries internal [NodeId] references for non-NodeRef
 * edges (Lambda.body, Application.arguments, ProductValue.fields, and so
 * on). Those NodeIds are meaningful only in the store they were assigned
 * in. Returning a single Node would leave its internal references
 * pointing into a foreign-store ID space the local verifier cannot
 * resolve. The [SubgraphFetch] interface returns the whole self-contained
 * subgraph (everything reachable from the root that is not itself a
 * NodeRef boundary), plus the foreign store's `nodeIdToHash` mapping, so
 * the receiver can translate every foreign NodeId reference to its
 * canonical hash and then to a local NodeId at admission time.
 *
 * Concrete resolvers in this module:
 * - [NoOpResolver] — always returns null; the default for single-store
 *   programs.
 * - [LocalHashStoreResolver] — wraps a [HashStore] + `nodeIdToHash` map
 *   and returns subgraphs by walking from the requested root.
 * - [ChainedResolver] — tries each delegate in order; performs root-hash
 *   integrity check.
 * - [CachingResolver] — wraps another resolver and caches fetched
 *   subgraphs in a per-session map.
 *
 * Networked resolvers (HTTP, IPFS, S3), signed-binding resolvers, and
 * capability-gated resolvers are downstream extensions of this interface
 * and are explicitly deferred per the Q-043 proposal.
 */
interface NodeResolver {
    /** Returns a self-contained [SubgraphFetch] rooted at [hash], or `null` if not held. */
    fun resolve(hash: Hash): SubgraphFetch?
}

/**
 * A self-contained subgraph fetched through a [NodeResolver].
 *
 * Contains the requested root node plus every transitively-referenced
 * internal node, stopping at NodeRef boundaries (a NodeRef inside the
 * subgraph stays a NodeRef and is resolved lazily on demand). The
 * [nodeIdToHash] map carries the foreign store's NodeId→Hash mapping for
 * the contained nodes, used by the receiver ([FederatedProgram.fetchAndAdmit])
 * to translate every Node's internal NodeId references into the local
 * ID space.
 *
 * Per the Q-043 proposal § 4.5 admission protocol:
 *
 * 1. The receiver builds a foreign-to-local NodeId map by admitting each
 *    fetched node (or finding an existing local mapping for its hash).
 * 2. After admission, each admitted Node is re-walked via
 *    `Node.translateNodeIds(foreignToLocal)` to rewrite its internal
 *    NodeId references into local IDs.
 * 3. The canonical hash of each translated Node is recomputed and
 *    compared to its declared hash; mismatch raises
 *    [NodeResolverIntegrityViolation].
 */
data class SubgraphFetch(
    /** The hash that was requested; must equal the hash of the root node. */
    val rootHash: Hash,
    /**
     * Every node reachable from the root that is not itself a NodeRef
     * boundary — including the root. Keyed by canonical hash.
     */
    val nodes: Map<Hash, Node>,
    /**
     * The foreign store's NodeId → Hash mapping for the contained
     * [nodes]. Used by the receiver to translate the foreign NodeId
     * references inside each admitted node into the local ID space.
     */
    val nodeIdToHash: Map<NodeId, Hash>,
)

/**
 * The default resolver for single-store programs. Always returns `null`,
 * which causes every cross-store reference to surface as
 * `NodeRefTargetUnresolvable` at the verifier — exactly the pre-Q-043
 * behaviour preserved bit-for-bit. Every existing corpus program runs
 * against a [NoOpResolver] without producing any resolver calls because
 * every NodeRef target hash is in the local `hashToNodeId` map.
 */
object NoOpResolver : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? = null
}

/**
 * Wraps a [HashStore] + its `nodeIdToHash` map as a [NodeResolver].
 * Walks the store from the requested root, gathering every internal node
 * reachable through non-NodeRef edges, and returns them in a
 * [SubgraphFetch].
 *
 * The store and the `nodeIdToHash` map must agree — every entry in the
 * map must point at a node that exists in the store, and every node in
 * the store reachable from the root must have an entry in the map. The
 * map is produced by [Hasher.finalize] (it is the same `nodeIdToHash`
 * field on [FinalizedProgram]); pre-finalization stores are not
 * federated targets.
 */
class LocalHashStoreResolver(
    private val store: HashStore,
    private val nodeIdToHash: Map<NodeId, Hash>,
) : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? {
        if (store.get(hash) == null) return null
        // Reverse map: Hash → NodeId. Built from nodeIdToHash so we can
        // walk children using local NodeIds.
        val hashToNodeId = nodeIdToHash.entries.associate { (id, h) -> h to id }
        val rootNodeId = hashToNodeId[hash] ?: return null

        val walked = mutableMapOf<Hash, Node>()
        val walkedIds = mutableMapOf<NodeId, Hash>()
        walkSubgraph(rootNodeId, store, nodeIdToHash, hashToNodeId, walked, walkedIds)
        return SubgraphFetch(rootHash = hash, nodes = walked, nodeIdToHash = walkedIds)
    }

    private fun walkSubgraph(
        startId: NodeId,
        store: HashStore,
        nodeIdToHash: Map<NodeId, Hash>,
        hashToNodeId: Map<Hash, NodeId>,
        outNodes: MutableMap<Hash, Node>,
        outIds: MutableMap<NodeId, Hash>,
    ) {
        if (outIds.containsKey(startId)) return
        val hash = nodeIdToHash[startId] ?: return
        val node = store.get(hash) ?: return
        outNodes[hash] = node
        outIds[startId] = hash
        // Recurse through every NodeId-typed field in `node`. NodeRef
        // targets are NOT followed — they are subgraph boundaries.
        for (childId in node.childNodeIds()) {
            walkSubgraph(childId, store, nodeIdToHash, hashToNodeId, outNodes, outIds)
        }
    }
}

/**
 * Composes a list of [NodeResolver]s by trying each in order. Returns the
 * first non-null result. Per the Q-043 proposal § 4.2, the chain order is
 * caller-controlled — typically the local store first, then peer stores in
 * priority order.
 *
 * **Integrity check** — confirms the resolver-claimed `rootHash` matches
 * the requested hash. Per-node hashes are confirmed at admission time as
 * the receiver re-runs the canonical encoder over each admitted node and
 * compares to the declared `nodeIdToHash` mapping (see
 * [FederatedProgram.fetchAndAdmit]).
 *
 * The root-level check covers every fetch (including local-store hits) so
 * there is one check site rather than per-resolver trust assertions.
 */
class ChainedResolver(private val resolvers: List<NodeResolver>) : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? {
        for (r in resolvers) {
            val fetch = r.resolve(hash) ?: continue
            // Root-level integrity check
            if (fetch.rootHash != hash) {
                throw NodeResolverIntegrityViolation(expected = hash, actual = fetch.rootHash)
            }
            return fetch
        }
        return null
    }
}

/**
 * Wraps another resolver with a per-session subgraph cache. Fetched
 * subgraphs are stored under their root hash; subsequent lookups for the
 * same hash short-circuit. The cache is a strict performance optimisation
 * — semantics are identical with or without caching.
 *
 * The cache is **per-session**: each [CachingResolver] instance owns its
 * cache. Persistent on-disk caching is explicitly deferred per the
 * Q-043 proposal.
 */
class CachingResolver(
    private val delegate: NodeResolver,
    private val cache: MutableMap<Hash, SubgraphFetch> = mutableMapOf(),
) : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? {
        cache[hash]?.let { return it }
        val fetch = delegate.resolve(hash) ?: return null
        cache[hash] = fetch
        return fetch
    }
}

/**
 * Thrown when a [NodeResolver] returns a subgraph whose root canonical
 * hash does not equal the requested hash. The session halts; the
 * exception carries the expected and actual hashes so the offending
 * resolver can be identified from logs.
 *
 * This is a **runtime exception** rather than a verifier diagnostic
 * because the failure is not in the program — it is in the supplied
 * federation chain.
 */
class NodeResolverIntegrityViolation(
    val expected: Hash,
    val actual: Hash,
) : RuntimeException(
    "NodeResolver integrity violation: requested $expected, got $actual"
)
