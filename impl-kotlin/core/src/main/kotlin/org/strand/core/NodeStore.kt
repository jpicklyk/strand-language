package org.strand.core

/**
 * In-memory store of Strand graph nodes.
 *
 * Layer 1: keyed by sequential [NodeId]. Insertion order is preserved.
 *
 * Layer 2 will replace this with a content-addressed store; the public
 * surface here is intentionally minimal so it can be re-fronted by a hash
 * map without changing call sites.
 */
class NodeStore {
    private val nodes = mutableListOf<Node>()

    fun add(node: Node): NodeId {
        val id = NodeId(nodes.size)
        nodes.add(node)
        return id
    }

    fun get(id: NodeId): Node {
        require(id.value in nodes.indices) { "Unknown node id: $id" }
        return nodes[id.value]
    }

    fun getOrNull(id: NodeId): Node? = nodes.getOrNull(id.value)

    /**
     * Replace the node already stored at [id]. Used by the Layer 2 step 3
     * federation admission protocol, which reserves a local NodeId with an
     * untranslated placeholder (so reference cycles — Schema↔Invariant, a
     * VarRef to an enclosing binder — can resolve to it during the recursive
     * re-base) and then overwrites it with the NodeId-translated node. The id
     * must already exist (a prior [add]); this never extends the store.
     */
    fun set(id: NodeId, node: Node) {
        require(id.value in nodes.indices) { "Cannot set unknown node id: $id" }
        nodes[id.value] = node
    }

    fun contains(id: NodeId): Boolean = id.value in nodes.indices

    val size: Int get() = nodes.size

    fun ids(): List<NodeId> = nodes.indices.map { NodeId(it) }

    /** Snapshot of all (id, node) pairs in insertion order. */
    fun entries(): List<Pair<NodeId, Node>> = nodes.mapIndexed { i, n -> NodeId(i) to n }
}
