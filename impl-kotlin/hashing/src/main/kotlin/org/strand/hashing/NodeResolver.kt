package org.strand.hashing

import org.strand.core.Hash
import org.strand.core.Node

/**
 * Layer 2 step 3 — cross-store federation entry point.
 *
 * Given a [Hash], returns the canonical [Node] the hash binds, or `null` if
 * the resolver does not hold that node. The verifier and interpreter consult
 * a resolver whenever a [Node.NodeRef] target's hash is not in the local
 * `hashToNodeId` reverse map.
 *
 * The interface is intentionally minimal. A `Node?` return is sufficient:
 * the verifier needs the node to type-check, the interpreter needs the node
 * to evaluate. The resolver does not return a [org.strand.core.NodeId]
 * because NodeIds are per-store and meaningful only to the verifier's
 * caller — admitted-to-local-store NodeIds are assigned by the caller after
 * the fetch.
 *
 * **Integrity** — the canonical hash binds the content. A resolver that
 * returns a node whose canonical hash does not equal the requested hash has
 * a bug or has been tampered with; downstream callers (typically the
 * [ChainedResolver] integrity-check wrapper) detect this and surface it as
 * [NodeResolverIntegrityViolation].
 *
 * Concrete resolvers in this module:
 * - [NoOpResolver] — always returns null; the default for single-store programs.
 * - [LocalHashStoreResolver] — wraps a [HashStore] and returns nodes by hash.
 * - [ChainedResolver] — tries each delegate in order; performs integrity check.
 * - [CachingResolver] — wraps another resolver and admits fetched nodes to a
 *   per-session cache.
 *
 * Networked resolvers (HTTP, IPFS, S3), signed-binding resolvers, and
 * capability-gated resolvers are downstream extensions of this interface
 * and are explicitly deferred per the Q-043 proposal.
 */
interface NodeResolver {
    /** Returns the canonical [Node] for [hash], or `null` if not held. */
    fun resolve(hash: Hash): Node?
}

/**
 * The default resolver for single-store programs. Always returns `null`,
 * which causes every cross-store reference to surface as
 * `NodeRefTargetUnresolvable` at the verifier — exactly the pre-Q-043
 * behaviour preserved bit-for-bit. Every existing corpus program runs
 * against a [NoOpResolver] without producing any resolver calls because
 * every NodeRef target hash is in the local `hashToNodeId` map.
 */
object NoOpResolver : NodeResolver {
    override fun resolve(hash: Hash): Node? = null
}

/**
 * Wraps a [HashStore] as a [NodeResolver]. The store is admitted by hash,
 * so the integrity property is structural: a returned node's canonical
 * hash is its admission key. Cross-resolver integrity (when this resolver
 * is one link in a [ChainedResolver]) is enforced at the chain level.
 */
class LocalHashStoreResolver(private val store: HashStore) : NodeResolver {
    override fun resolve(hash: Hash): Node? = store.get(hash)
}

/**
 * Composes a list of [NodeResolver]s by trying each in order. Returns the
 * first non-null result. Per the Q-043 proposal § 4.2, the chain order is
 * caller-controlled — typically the local store first, then peer stores in
 * priority order.
 *
 * **Integrity check** — when a delegate returns a non-null node, the
 * canonical hash of that node is recomputed and compared against the
 * requested hash. A mismatch throws [NodeResolverIntegrityViolation] —
 * a hard failure that halts the session, since the resolver is delivering
 * content that does not match what was asked for.
 *
 * The check covers every fetch (including local-store hits) so there is
 * one check site rather than per-resolver trust assertions. For
 * [LocalHashStoreResolver] the check is in practice a no-op (the store
 * admits by hash) but the cost is negligible and the uniformity is worth
 * it.
 *
 * **Pre-step-3b note** — until the canonical encoder exposes a single-node
 * hash function, the integrity check is structural rather than
 * cryptographic. The implementation hashes the returned node by wrapping
 * it in a temporary [RawNodeStore] / [Hasher] pair; for simple primitives
 * this is identical to the production hash. Once [Node.ModuleManifest]
 * ships in step 3b the test surface covers cross-resolver mismatch
 * scenarios end-to-end.
 */
class ChainedResolver(private val resolvers: List<NodeResolver>) : NodeResolver {
    override fun resolve(hash: Hash): Node? {
        for (r in resolvers) {
            val node = r.resolve(hash) ?: continue
            // Integrity check — recompute the canonical hash of the
            // returned node and compare. For a LocalHashStoreResolver
            // hit the check is structural (HashStore admits by hash);
            // for FileSystemResolver and future networked resolvers it
            // is load-bearing.
            verifyIntegrity(hash, node)
            return node
        }
        return null
    }

    private fun verifyIntegrity(expected: Hash, node: Node) {
        // The simplest portable integrity check: round-trip the node
        // through a single-entry RawNodeStore and Hasher.hashRoot. This
        // matches what the canonical encoder produces for a top-level
        // (root-context) node — exactly the context used for storing
        // and retrieving a hash-keyed node.
        //
        // NodeRef targets are inherently closed (verifier rule
        // NodeRefTargetMustBeClosed), so the empty binder stack used by
        // hashRoot is correct.
        val tempStore = org.strand.core.RawNodeStore()
        val id = tempStore.add(org.strand.core.StoredNode.Canonical(node))
        val actual = Hasher(tempStore).hashRoot(id)
        if (actual != expected) {
            throw NodeResolverIntegrityViolation(expected = expected, actual = actual)
        }
    }
}

/**
 * Wraps another resolver with a per-session [HashStore] cache. Fetched
 * nodes are admitted to the cache; subsequent lookups for the same hash
 * short-circuit. The cache is a strict performance optimisation — semantics
 * are identical with or without caching.
 *
 * The cache is **per-session**: each [CachingResolver] instance owns its
 * cache. Persistent on-disk caching is explicitly deferred per the
 * Q-043 proposal.
 */
class CachingResolver(
    private val delegate: NodeResolver,
    private val cache: HashStore = HashStore(),
) : NodeResolver {
    override fun resolve(hash: Hash): Node? {
        cache.get(hash)?.let { return it }
        val node = delegate.resolve(hash) ?: return null
        cache.put(hash, node)
        return node
    }
}

/**
 * Thrown when a [NodeResolver] returns a node whose canonical hash does
 * not equal the requested hash. The session halts; the exception carries
 * the expected and actual hashes so the offending resolver can be
 * identified from logs.
 *
 * This is a **runtime exception** rather than a verifier diagnostic
 * because the failure is not in the program — it is in the supplied
 * federation chain. A graph that produces this exception is well-formed;
 * its dependencies were sourced from an untrustworthy resolver.
 */
class NodeResolverIntegrityViolation(
    val expected: Hash,
    val actual: Hash,
) : RuntimeException(
    "NodeResolver integrity violation: requested $expected, got $actual"
)
