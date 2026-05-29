package org.strand.hashing

import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.translateNodeIds

/**
 * Layer 2 step 3 — cross-store federation entry point.
 *
 * Given a [Hash], returns a self-contained [SubgraphFetch] rooted at that
 * hash, or `null` if the resolver does not hold the hash. The verifier and
 * interpreter consult a resolver whenever a [Node.NodeRef] target's hash is
 * not in the local `hashToNodeId` reverse map.
 *
 * **Why subgraphs keyed by foreign NodeId** — a Strand [Node] carries internal
 * [NodeId] references for non-NodeRef edges (Lambda.body, Application.arguments,
 * VarRef.binder, and so on). Those NodeIds are meaningful only in the store
 * they were assigned in. The [SubgraphFetch] returns the whole self-contained
 * subgraph reachable from the root (everything that is not itself behind a
 * NodeRef boundary), keyed by the *foreign* store's NodeId, so the receiver
 * ([FederatedProgram.fetchAndAdmit]) can re-base every reference into the local
 * ID space via [Node.translateNodeIds].
 *
 * Crucially the fetch includes **bound nodes** — [Node.ParameterDecl],
 * [Node.TypeParameter], [Node.RecursiveSelf] — which have no standalone hash
 * (the canonical encoder encodes them inline within their parent). They are
 * keyed by foreign NodeId and re-admitted fresh as part of their parent's
 * re-base; they are absent from [SubgraphFetch.nodeIdToHash] (which carries
 * only hashable nodes, used for content-addressed dedup at admission).
 *
 * Concrete resolvers in this module:
 * - [NoOpResolver] — always returns null; the default for single-store programs.
 * - [LocalProgramResolver] — wraps a [FinalizedProgram] and returns subgraphs
 *   by walking its [org.strand.core.NodeStore] from the requested root.
 * - [ChainedResolver] — tries each delegate in order; performs the root-hash
 *   integrity check (the resolver's claim; the receiver re-hashes the admitted
 *   subgraph for the trust-minimizing check).
 * - [CachingResolver] — caches fetched subgraphs in a per-session map.
 *
 * Networked resolvers (HTTP, IPFS, S3), signed-binding resolvers, and
 * capability-gated resolvers are downstream extensions of this interface and
 * are explicitly deferred per the Q-043 proposal.
 */
interface NodeResolver {
    /** Returns a self-contained [SubgraphFetch] rooted at [hash], or `null` if not held. */
    fun resolve(hash: Hash): SubgraphFetch?
}

/**
 * A self-contained subgraph fetched through a [NodeResolver].
 *
 * Contains the requested root node plus every transitively-referenced node,
 * stopping at NodeRef boundaries (a NodeRef inside the subgraph stays a NodeRef
 * and is resolved lazily on demand). Nodes are keyed by the foreign store's
 * [NodeId] — *including* bound nodes that have no standalone hash — so the
 * receiver can re-base every internal reference.
 *
 * Per the Q-043 proposal § 4.5 admission protocol, the receiver
 * ([FederatedProgram.fetchAndAdmit]) walks from [rootId], assigns each fetched
 * node a fresh local NodeId (deduping hashable nodes against the local store by
 * hash), re-bases each node's references via [Node.translateNodeIds], and
 * finally recomputes the canonical hash of the admitted local root, requiring
 * it to equal the requested hash (the trust-minimizing integrity check — the
 * Merkle root hash binds the whole subgraph).
 */
data class SubgraphFetch(
    /** The hash that was requested; must equal the hash of the root node. */
    val rootHash: Hash,
    /** The foreign store's NodeId of the root node (the entry point for admission). */
    val rootId: NodeId,
    /**
     * Every node reachable from the root that is not itself behind a NodeRef
     * boundary — including the root and all bound nodes — keyed by foreign NodeId.
     */
    val nodes: Map<NodeId, Node>,
    /**
     * The foreign store's NodeId → Hash mapping for the *hashable* contained
     * nodes (bound nodes are absent). Used by the receiver to dedup against the
     * local store and to label admitted hashable nodes.
     */
    val nodeIdToHash: Map<NodeId, Hash>,
)

/**
 * The default resolver for single-store programs. Always returns `null`, which
 * causes every cross-store reference to surface as `NodeRefTargetUnresolvable`
 * at the verifier — exactly the pre-Q-043 behaviour preserved bit-for-bit.
 */
object NoOpResolver : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? = null
}

/**
 * Wraps a [FinalizedProgram] as a [NodeResolver]. Walks the program's
 * [org.strand.core.NodeStore] from the requested root, gathering every node
 * reachable through non-NodeRef edges (including bound nodes, which have no
 * hash and so cannot be served from a hash-keyed store), and returns them in a
 * [SubgraphFetch].
 *
 * The walk enumerates references via [Node.translateNodeIds] (the structural
 * dual of `childNodeIds`), so it visits *every* NodeId field — binder
 * declarations and metadata edges included — which is exactly the set the
 * admission re-base must rewrite. The visited-set guard makes the reference
 * cycle a Schema↔Invariant pair induces terminate.
 */
class LocalProgramResolver(private val program: FinalizedProgram) : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? {
        val rootId = program.hashToNodeId[hash] ?: return null
        val nodes = LinkedHashMap<NodeId, Node>()
        val ids = LinkedHashMap<NodeId, Hash>()
        walk(rootId, nodes, ids)
        return SubgraphFetch(rootHash = hash, rootId = rootId, nodes = nodes, nodeIdToHash = ids)
    }

    private fun walk(id: NodeId, nodes: MutableMap<NodeId, Node>, ids: MutableMap<NodeId, Hash>) {
        if (nodes.containsKey(id)) return
        val node = program.store.getOrNull(id) ?: return
        nodes[id] = node
        program.nodeIdToHash[id]?.let { ids[id] = it } // hashable nodes only
        // Visit every referenced NodeId (bound nodes + binder declarations
        // included); NodeRef has no NodeId fields, so the walk stops there.
        node.translateNodeIds { childId -> walk(childId, nodes, ids); childId }
    }
}

/**
 * Composes a list of [NodeResolver]s by trying each in order, returning the
 * first non-null result. Per Q-043 § 4.2 the chain order is caller-controlled —
 * typically the local store first, then peer stores in priority order.
 *
 * **Root-hash integrity check** — confirms the resolver-claimed `rootHash`
 * matches the requested hash. This catches a resolver returning a fetch for the
 * wrong hash. The *content* integrity check — that the returned nodes actually
 * hash to the claimed hash — happens at admission time, where the receiver
 * re-hashes the admitted local root (see [FederatedProgram.fetchAndAdmit]).
 */
class ChainedResolver(private val resolvers: List<NodeResolver>) : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? {
        for (r in resolvers) {
            val fetch = r.resolve(hash) ?: continue
            if (fetch.rootHash != hash) {
                throw NodeResolverIntegrityViolation(expected = hash, actual = fetch.rootHash)
            }
            return fetch
        }
        return null
    }
}

/**
 * Wraps another resolver with a per-session subgraph cache, keyed by root hash.
 * The cache is a strict performance optimisation — semantics are identical with
 * or without caching. Persistent on-disk caching is deferred per Q-043.
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
 * Thrown when a [NodeResolver] returns a subgraph whose root hash does not match
 * the requested hash (the [ChainedResolver] claim check), or when the canonical
 * re-hash of an admitted subgraph's root does not equal the requested hash (the
 * [FederatedProgram.fetchAndAdmit] content check). The session halts; the
 * exception carries the expected and actual hashes so the offending resolver
 * can be identified from logs.
 *
 * This is a **runtime exception** rather than a verifier diagnostic because the
 * failure is not in the program — it is in the supplied federation chain.
 */
class NodeResolverIntegrityViolation(
    val expected: Hash,
    val actual: Hash,
) : RuntimeException(
    "NodeResolver integrity violation: requested $expected, got $actual"
)
