package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertNotEquals
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
