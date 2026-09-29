package org.strand.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [Hash.fromHex] is the inverse of [Hash.toString]; the round-trip property is
 * what `strand registry put` relies on to turn an agent-supplied hex string
 * back into a [Hash]. Malformed input is rejected (odd length, non-hex digits,
 * and — via the [Hash] constructor — wrong prefix / digest length).
 */
class HashTest {

    @Test
    fun `fromHex inverts toString`() {
        val h = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { (it * 7 + 1).toByte() })
        assertEquals(h, Hash.fromHex(h.toString()))
    }

    @Test
    fun `fromHex rejects odd-length hex`() {
        assertThrows(IllegalArgumentException::class.java) { Hash.fromHex("1e16f60") }
    }

    @Test
    fun `fromHex rejects non-hex characters`() {
        assertThrows(IllegalArgumentException::class.java) { Hash.fromHex("zz" + "00".repeat(32)) }
    }

    @Test
    fun `an unknown prefix is bad input, not an internal error`() {
        assertThrows(IllegalArgumentException::class.java) { Hash.fromHex("ff" + "00".repeat(32)) }
    }

    @Test
    fun `Hash is immutable - neither the source array nor the exposed bytes alias its value`() {
        val source = byteArrayOf(0x1e.toByte()) + ByteArray(32) { it.toByte() }
        val h = Hash(source)
        val expected = Hash(source.copyOf())
        source[5] = 99
        assertEquals(expected, h, "mutating the constructor argument changed the Hash")
        h.bytes[5] = 42
        assertEquals(expected, h, "mutating the exposed bytes changed the Hash")
        assertEquals(expected.hashCode(), h.hashCode())
    }
}
