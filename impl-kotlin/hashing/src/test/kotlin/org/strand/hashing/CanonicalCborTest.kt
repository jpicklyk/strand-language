package org.strand.hashing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Byte-level tests of the canonical CBOR primitive encoder against
 * hand-computed expected output. These tests pin the encoder to a fixed byte
 * format so any inadvertent change to the CBOR layer is caught immediately.
 *
 * Hex notation in comments and assertions uses the RFC 8949 examples and the
 * canonical-form constraints from RFC 8949 § 4.2.1: definite length only,
 * shortest argument always.
 */
class CanonicalCborTest {

    // ----- Unsigned integers (major type 0) -----

    @Test
    fun `small uint values fit in one byte`() {
        // 0..23 are encoded directly in the initial byte (no extra length byte).
        assertBytes(byteArrayOf(0x00), CanonicalCbor.encodeUint(0))
        assertBytes(byteArrayOf(0x01), CanonicalCbor.encodeUint(1))
        assertBytes(byteArrayOf(0x17), CanonicalCbor.encodeUint(23))
    }

    @Test
    fun `uint 24 uses one-byte argument`() {
        // 24..255 use 0x18 + one byte.
        assertBytes(byteArrayOf(0x18, 0x18), CanonicalCbor.encodeUint(24))
        assertBytes(byteArrayOf(0x18, 0xFF.toByte()), CanonicalCbor.encodeUint(255))
    }

    @Test
    fun `uint 256 uses two-byte big-endian argument`() {
        // 256..65535 use 0x19 + two bytes BE.
        assertBytes(byteArrayOf(0x19, 0x01, 0x00), CanonicalCbor.encodeUint(256))
        assertBytes(byteArrayOf(0x19, 0xFF.toByte(), 0xFF.toByte()), CanonicalCbor.encodeUint(65535))
    }

    @Test
    fun `uint 65536 uses four-byte big-endian argument`() {
        assertBytes(
            byteArrayOf(0x1A, 0x00, 0x01, 0x00, 0x00),
            CanonicalCbor.encodeUint(65536),
        )
    }

    @Test
    fun `uint above 2 to the 32 uses eight-byte argument`() {
        // 2^32 = 4294967296, needs 0x1B + 8 bytes.
        assertBytes(
            byteArrayOf(0x1B, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00),
            CanonicalCbor.encodeUint(0x1_0000_0000L),
        )
    }

    @Test
    fun `negative uint argument is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalCbor.encodeUint(-1)
        }
    }

    // ----- Signed integers (negative uses major type 1) -----

    @Test
    fun `encodeInt routes positive values to major type 0`() {
        // 42 is > 23 so it uses the one-byte argument form: 0x18 0x2A.
        assertBytes(byteArrayOf(0x18, 0x2A), CanonicalCbor.encodeInt(42))
        // 5 is <= 23, so it uses the immediate form: 0x05.
        assertBytes(byteArrayOf(0x05), CanonicalCbor.encodeInt(5))
    }

    @Test
    fun `encodeInt negative one is major type 1 argument zero`() {
        // -1 encodes as 0x20 (major type 1, argument 0; value = -1 - 0).
        assertBytes(byteArrayOf(0x20), CanonicalCbor.encodeInt(-1))
    }

    @Test
    fun `encodeInt negative twenty-four uses argument byte`() {
        // -24 encodes as -1 - 23 = -24, so argument is 23 (immediate).
        assertBytes(byteArrayOf(0x37), CanonicalCbor.encodeInt(-24))
        // -25 encodes as argument 24, requiring 0x38 + 0x18.
        assertBytes(byteArrayOf(0x38, 0x18), CanonicalCbor.encodeInt(-25))
    }

    @Test
    fun `encodeInt handles Long MIN_VALUE without overflow`() {
        // Long.MIN_VALUE = -2^63. Argument = -1 - (-2^63) = 2^63 - 1 = Long.MAX_VALUE.
        // Encoded as 0x3B + 8 bytes = 0x7F FF FF FF FF FF FF FF.
        val expected = byteArrayOf(
            0x3B,
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        assertBytes(expected, CanonicalCbor.encodeInt(Long.MIN_VALUE))
    }

    // ----- Byte strings (major type 2) -----

    @Test
    fun `empty byte string encodes as 0x40`() {
        assertBytes(byteArrayOf(0x40), CanonicalCbor.encodeBytes(byteArrayOf()))
    }

    @Test
    fun `byte string up to 23 bytes uses immediate length`() {
        val payload = ByteArray(5) { (it + 1).toByte() }
        val expected = byteArrayOf(0x45, 0x01, 0x02, 0x03, 0x04, 0x05)
        assertBytes(expected, CanonicalCbor.encodeBytes(payload))
    }

    @Test
    fun `byte string of 24 bytes uses one-byte length argument`() {
        val payload = ByteArray(24) { 0x00 }
        val encoded = CanonicalCbor.encodeBytes(payload)
        // Header: 0x58 (major 2 + arg 24) followed by length byte 0x18.
        assertEquals(0x58.toByte(), encoded[0])
        assertEquals(0x18.toByte(), encoded[1])
        assertEquals(24 + 2, encoded.size)
    }

    // ----- Arrays (major type 4) -----

    @Test
    fun `empty array encodes as 0x80`() {
        assertBytes(byteArrayOf(0x80.toByte()), CanonicalCbor.encodeArray(emptyList()))
    }

    @Test
    fun `array concatenates header and pre-encoded items in order`() {
        val items = listOf(
            CanonicalCbor.encodeUint(1),  // 0x01
            CanonicalCbor.encodeUint(2),  // 0x02
            CanonicalCbor.encodeUint(3),  // 0x03
        )
        val expected = byteArrayOf(0x83.toByte(), 0x01, 0x02, 0x03)
        assertBytes(expected, CanonicalCbor.encodeArray(items))
    }

    private fun assertBytes(expected: ByteArray, actual: ByteArray) {
        assertArrayEquals(expected, actual) {
            "expected: ${expected.toHex()}, actual: ${actual.toHex()}"
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
}
