package org.strand.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NodeStoreTest {

    @Test
    fun `add returns sequential ids and get retrieves nodes`() {
        val store = NodeStore()
        val a = store.add(Node.IntLit(1))
        val b = store.add(Node.IntLit(2))
        assertEquals(0, a.value)
        assertEquals(1, b.value)
        assertEquals(Node.IntLit(1), store.get(a))
        assertEquals(Node.IntLit(2), store.get(b))
        assertEquals(2, store.size)
    }

    @Test
    fun `contains is true only for assigned ids`() {
        val store = NodeStore()
        val id = store.add(Node.UnitLit)
        assertTrue(store.contains(id))
        assertFalse(store.contains(NodeId(99)))
    }
}
