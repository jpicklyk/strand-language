package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 2 — direct unit tests of Hash.* builtins.
 * Each test uses a known input + expected-digest pair from public
 * test vectors so the implementation matches the standard.
 */
class BuiltinsHashTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    // ---------- BLAKE3 ----------

    @Test
    fun `Hash_Blake3 empty input matches official test vector`() {
        // BLAKE3 of empty input — official spec test vector (first 32 bytes).
        val expectedHex = "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"
        val result = lookup("strand-builtin:Hash.Blake3").invoke(listOf(Value.BytesV(ByteArray(0))))
        assertEquals(expectedHex, hex((result as Value.BytesV).v))
    }

    @Test
    fun `Hash_Blake3 of single ASCII byte`() {
        // BLAKE3 of byte 0x00 (single zero byte).
        val expectedHex = "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213"
        val result = lookup("strand-builtin:Hash.Blake3").invoke(listOf(Value.BytesV(byteArrayOf(0x00))))
        assertEquals(expectedHex, hex((result as Value.BytesV).v))
    }

    @Test
    fun `Hash_Blake3 is deterministic`() {
        val input = "the quick brown fox jumps over the lazy dog".toByteArray()
        val a = lookup("strand-builtin:Hash.Blake3").invoke(listOf(Value.BytesV(input)))
        val b = lookup("strand-builtin:Hash.Blake3").invoke(listOf(Value.BytesV(input)))
        assertArrayEquals((a as Value.BytesV).v, (b as Value.BytesV).v)
        assertEquals(32, a.v.size)
    }

    // ---------- SHA-256 ----------

    @Test
    fun `Hash_Sha256 of empty input matches NIST vector`() {
        // SHA-256 of empty input — NIST test vector.
        val expectedHex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val result = lookup("strand-builtin:Hash.Sha256").invoke(listOf(Value.BytesV(ByteArray(0))))
        assertEquals(expectedHex, hex((result as Value.BytesV).v))
    }

    @Test
    fun `Hash_Sha256 of abc matches NIST vector`() {
        // SHA-256 of "abc" — classic NIST test vector.
        val expectedHex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val result = lookup("strand-builtin:Hash.Sha256").invoke(listOf(Value.BytesV("abc".toByteArray())))
        assertEquals(expectedHex, hex((result as Value.BytesV).v))
    }

    // ---------- MD5 ----------

    @Test
    fun `Hash_Md5 of empty input matches RFC vector`() {
        // MD5 of empty input — RFC 1321 test vector.
        val expectedHex = "d41d8cd98f00b204e9800998ecf8427e"
        val result = lookup("strand-builtin:Hash.Md5").invoke(listOf(Value.BytesV(ByteArray(0))))
        assertEquals(expectedHex, hex((result as Value.BytesV).v))
    }

    @Test
    fun `Hash_Md5 of abc matches RFC vector`() {
        // MD5 of "abc" — RFC 1321 test vector.
        val expectedHex = "900150983cd24fb0d6963f7d28e17f72"
        val result = lookup("strand-builtin:Hash.Md5").invoke(listOf(Value.BytesV("abc".toByteArray())))
        assertEquals(expectedHex, hex((result as Value.BytesV).v))
    }

    @Test
    fun `Hash output sizes match standard digest sizes`() {
        val input = Value.BytesV("test".toByteArray())
        val blake3 = lookup("strand-builtin:Hash.Blake3").invoke(listOf(input)) as Value.BytesV
        val sha256 = lookup("strand-builtin:Hash.Sha256").invoke(listOf(input)) as Value.BytesV
        val md5 = lookup("strand-builtin:Hash.Md5").invoke(listOf(input)) as Value.BytesV
        assertEquals(32, blake3.v.size)
        assertEquals(32, sha256.v.size)
        assertEquals(16, md5.v.size)
    }

    @Test
    fun `Hash hex-helper round-trip sanity`() {
        val input = "hello"
        val rt = hex(fromHex("68656c6c6f"))
        assertEquals("68656c6c6f", rt)
        assertEquals(input, String(fromHex("68656c6c6f")))
    }
}
