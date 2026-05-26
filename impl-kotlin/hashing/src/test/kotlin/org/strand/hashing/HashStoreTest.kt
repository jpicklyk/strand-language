package org.strand.hashing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.strand.core.Hash
import org.strand.core.HashFunction
import org.strand.core.Node

class HashStoreTest {

    @Test
    fun `put returns true on first insertion and false on duplicate`() {
        val store = HashStore()
        val hash = fakeHash(byteArrayOf(0x01))
        val node = Node.IntLit(1)
        assertTrue(store.put(hash, node))
        assertFalse(store.put(hash, node))
        assertEquals(1, store.size)
    }

    @Test
    fun `put with same hash but different node retains the first`() {
        // Per dedup semantics, two nodes claiming the same hash must be
        // structurally identical. If a caller violates this, we keep the
        // first-admitted node — the store does not overwrite.
        val store = HashStore()
        val hash = fakeHash(byteArrayOf(0x02))
        val first = Node.IntLit(1)
        val second = Node.IntLit(2)
        assertTrue(store.put(hash, first))
        assertFalse(store.put(hash, second))
        assertEquals(first, store.get(hash))
    }

    @Test
    fun `contains and get behave consistently`() {
        val store = HashStore()
        val hash = fakeHash(byteArrayOf(0x03))
        assertFalse(store.contains(hash))
        assertNull(store.get(hash))
        store.put(hash, Node.UnitLit)
        assertTrue(store.contains(hash))
        assertSame(Node.UnitLit, store.get(hash))
    }

    @Test
    fun `entries preserves insertion order`() {
        val store = HashStore()
        val h1 = fakeHash(byteArrayOf(0x04))
        val h2 = fakeHash(byteArrayOf(0x05))
        val h3 = fakeHash(byteArrayOf(0x06))
        store.put(h1, Node.IntLit(1))
        store.put(h2, Node.IntLit(2))
        store.put(h3, Node.IntLit(3))
        val ordered = store.entries().map { it.first }
        assertEquals(listOf(h1, h2, h3), ordered)
    }

    /**
     * Build a Hash with the BLAKE3 prefix and a deterministic digest derived
     * from [seed]. Used by tests that need distinct hashes but don't care
     * about the digest content.
     */
    private fun fakeHash(seed: ByteArray): Hash {
        val digest = ByteArray(32)
        for (i in digest.indices) {
            digest[i] = seed[i % seed.size]
        }
        val out = ByteArray(33)
        out[0] = HashFunction.Blake3.prefix
        digest.copyInto(out, 1)
        return Hash(out)
    }
}
