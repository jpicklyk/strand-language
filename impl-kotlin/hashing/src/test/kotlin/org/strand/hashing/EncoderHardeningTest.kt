package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.strand.core.ExhaustionKind
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.IngestError
import org.strand.core.Node
import org.strand.core.NodeStore

/**
 * Encoder/hasher hardening from the 2026-09-29 review (core/hashing
 * findings) for stores that bypass JSON ingest (programmatically built).
 */
class EncoderHardeningTest {

    @Test
    fun `a lone surrogate fails loudly instead of colliding with a question mark`() {
        val lone = NodeStore().apply { add(Node.StringLit("\uD800")) }
        val err = assertThrows<IngestError.Malformed> { Hasher(lone).hashRoot(org.strand.core.NodeId(0)) }
        assertTrue(err.message!!.contains("surrogate"), err.message)

        // Same field, other string-carrying categories.
        val field = NodeStore().apply {
            val t = add(Node.PrimitiveType(org.strand.core.Primitive.Int))
            add(Node.ProductTypeField(fieldName = "x\uDC00", fieldType = t))
        }
        assertThrows<IngestError.Malformed> { Hasher(field).hashRoot(org.strand.core.NodeId(1)) }
    }

    /** A programmatic Let chain of [n] Lets (bypasses the ingest depth cap). */
    private fun letChainStore(n: Int): Pair<NodeStore, org.strand.core.NodeId> {
        val store = NodeStore()
        val one = store.add(Node.IntLit(1))
        // Build inside-out: innermost body is the IntLit, each Let wraps it.
        var body = one
        repeat(n) { i -> body = store.add(Node.Let(name = "x$i", value = one, body = body)) }
        return store to body
    }

    @Test
    fun `hashing a store deeper than the JVM stack raises typed GraphDepth exhaustion`() {
        val (store, root) = letChainStore(200_000)
        val hashRoot = assertThrows<IngestError.ResourceExhaustion> { Hasher(store).hashRoot(root) }
        assertEquals(ExhaustionKind.GraphDepth, hashRoot.kind)
        assertTrue(hashRoot.current > 0)
        val reachable = assertThrows<IngestError.ResourceExhaustion> { Hasher(store).hashReachable(root) }
        assertEquals(ExhaustionKind.GraphDepth, reachable.kind)
        // The encoder recovers: a shallow graph hashes fine afterwards.
        val (small, smallRoot) = letChainStore(10)
        Hasher(small).hashRoot(smallRoot)
    }

    @Test
    fun `well-formed strings still hash, including astral characters`() {
        val q = NodeStore().apply { add(Node.StringLit("?")) }
        val astral = NodeStore().apply { add(Node.StringLit("😀")) }
        assertNotEquals(
            Hasher(q).hashRoot(org.strand.core.NodeId(0)),
            Hasher(astral).hashRoot(org.strand.core.NodeId(0)),
        )
    }
}
