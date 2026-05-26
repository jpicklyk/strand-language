package org.strand.hashing

import org.strand.core.Hash
import org.strand.core.Node

/**
 * A content-addressed store of Strand nodes per ADR-003: keyed by [Hash],
 * deduplicating on add, immutable after admission. Two nodes with the same
 * canonical bytes admit to the same slot.
 *
 * Layer 2 step 1 uses [HashStore] as the post-hash representation of a
 * verified graph: a [Hasher] walks a [org.strand.core.NodeStore] bottom-up
 * (top-down for binder context, post-order for hash dependencies) and
 * populates a [HashStore] with the materialized nodes keyed by their hashes.
 * Subsequent operations that need only the hash-keyed view (cross-store
 * fetches, distribution, garbage collection by reachability) operate on
 * [HashStore]; the [org.strand.core.NodeStore] remains available for
 * verifier and interpreter calls that work in NodeId terms.
 *
 * The [Node] objects stored here may contain [org.strand.core.NodeId]
 * references that are meaningful only in their originating [org.strand.core.NodeStore].
 * Resolving those references to other [Hash]es is the job of an
 * accompanying `NodeId → Hash` mapping (returned by [Hasher.hashReachable]).
 * A future revision of this module will replace the in-Node [org.strand.core.NodeId]
 * references with [Hash] references directly, eliminating this two-map
 * indirection.
 */
class HashStore {
    private val nodes = LinkedHashMap<Hash, Node>()

    /**
     * Admit [node] at [hash]. If [hash] is already present, this is a no-op
     * (deduplication); the existing entry is retained. Returns whether the
     * node was newly admitted.
     */
    fun put(hash: Hash, node: Node): Boolean {
        val existing = nodes.putIfAbsent(hash, node)
        return existing == null
    }

    fun get(hash: Hash): Node? = nodes[hash]

    fun contains(hash: Hash): Boolean = hash in nodes

    val size: Int get() = nodes.size

    fun hashes(): Set<Hash> = nodes.keys.toSet()

    /** Snapshot of all (hash, node) pairs in insertion order. */
    fun entries(): List<Pair<Hash, Node>> = nodes.entries.map { (h, n) -> h to n }
}
