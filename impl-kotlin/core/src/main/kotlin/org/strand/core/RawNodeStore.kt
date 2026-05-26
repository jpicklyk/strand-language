package org.strand.core

/**
 * Pre-finalization store of [StoredNode]s, produced by `JsonIngest.parse` and
 * consumed by `Hasher.finalize`. Mirrors [NodeStore]'s surface but holds
 * [StoredNode] entries — so a `NodeRef` slot can be filled with
 * [StoredNode.RawNodeRef] while its target hash is still unknown.
 *
 * IDs are sequential integers assigned at insertion time (same scheme as
 * [NodeStore]). The two stores share an ID space conceptually — a downstream
 * canonical [NodeStore] produced by finalization assigns the same [NodeId] to
 * the same conceptual node.
 *
 * Lives in `core` because [JsonIngest] (which produces it) is in `core`. The
 * hashing module operates on this type when finalizing; verifier and
 * interpreter never touch it.
 */
class RawNodeStore {
    private val nodes = mutableListOf<StoredNode>()

    fun add(stored: StoredNode): NodeId {
        val id = NodeId(nodes.size)
        nodes.add(stored)
        return id
    }

    fun get(id: NodeId): StoredNode {
        require(id.value in nodes.indices) { "Unknown node id: $id" }
        return nodes[id.value]
    }

    fun getOrNull(id: NodeId): StoredNode? = nodes.getOrNull(id.value)

    fun contains(id: NodeId): Boolean = id.value in nodes.indices

    val size: Int get() = nodes.size

    fun ids(): List<NodeId> = nodes.indices.map { NodeId(it) }

    /** Snapshot of all (id, stored) pairs in insertion order. */
    fun entries(): List<Pair<NodeId, StoredNode>> = nodes.mapIndexed { i, n -> NodeId(i) to n }
}
