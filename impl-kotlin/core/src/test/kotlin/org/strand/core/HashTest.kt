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
}
