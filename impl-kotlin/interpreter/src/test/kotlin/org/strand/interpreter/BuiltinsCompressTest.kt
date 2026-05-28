package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 5, Group F — Compress.Gzip / Compress.Gunzip.
 * Backed by java.util.zip.GZIPOutputStream / GZIPInputStream (JDK-
 * native; no extra dependency).
 *
 * Zstd / Unzstd are deferred — supporting them would require adding
 * the com.github.luben:zstd-jni dependency, which is a load-bearing
 * decision better made in a separate slice.
 */
class BuiltinsCompressTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    @Test
    fun `Compress_Gzip round-trips through Gunzip`() {
        val gzip = lookup("strand-builtin:Compress.Gzip")
        val gunzip = lookup("strand-builtin:Compress.Gunzip")
        val original = "Hello, world! This is a test of gzip round-tripping. ".repeat(20)
            .toByteArray(Charsets.UTF_8)
        val compressed = gzip.invoke(listOf(Value.BytesV(original))) as Value.BytesV
        // Compressed should be shorter for repetitive data.
        assertTrue(compressed.v.size < original.size) {
            "expected compression to shrink ${original.size} bytes, got ${compressed.v.size}"
        }
        // Gzip magic bytes 0x1f 0x8b at start.
        assertEquals(0x1f.toByte(), compressed.v[0])
        assertEquals(0x8b.toByte(), compressed.v[1])
        val decompressed = gunzip.invoke(listOf(compressed)) as Value.SumV
        assertEquals("Some", decompressed.case)
        assertArrayEquals(original, (decompressed.payload as Value.BytesV).v)
    }

    @Test
    fun `Compress_Gzip empty input round-trips`() {
        val gzip = lookup("strand-builtin:Compress.Gzip")
        val gunzip = lookup("strand-builtin:Compress.Gunzip")
        val compressed = gzip.invoke(listOf(Value.BytesV(ByteArray(0)))) as Value.BytesV
        // Gzip header alone is ~20 bytes.
        assertTrue(compressed.v.size > 0)
        val decompressed = gunzip.invoke(listOf(compressed)) as Value.SumV
        assertEquals("Some", decompressed.case)
        assertEquals(0, (decompressed.payload as Value.BytesV).v.size)
    }

    @Test
    fun `Compress_Gunzip None on malformed input`() {
        val gunzip = lookup("strand-builtin:Compress.Gunzip")
        // Random bytes — definitely not gzip.
        val bogus = Value.BytesV(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05))
        val result = gunzip.invoke(listOf(bogus)) as Value.SumV
        assertEquals("None", result.case)
    }

    @Test
    fun `Compress_Gunzip None on truncated gzip`() {
        val gzip = lookup("strand-builtin:Compress.Gzip")
        val gunzip = lookup("strand-builtin:Compress.Gunzip")
        val data = "Some content to compress and then truncate".toByteArray(Charsets.UTF_8)
        val compressed = (gzip.invoke(listOf(Value.BytesV(data))) as Value.BytesV).v
        // Truncate halfway through.
        val truncated = compressed.copyOf(compressed.size / 2)
        val result = gunzip.invoke(listOf(Value.BytesV(truncated))) as Value.SumV
        assertEquals("None", result.case)
    }

    @Test
    fun `Compress_Gzip binary data round-trips losslessly`() {
        val gzip = lookup("strand-builtin:Compress.Gzip")
        val gunzip = lookup("strand-builtin:Compress.Gunzip")
        // Non-text bytes including null and high bytes.
        val original = ByteArray(256) { it.toByte() }
        val compressed = gzip.invoke(listOf(Value.BytesV(original))) as Value.BytesV
        val decompressed = gunzip.invoke(listOf(compressed)) as Value.SumV
        assertEquals("Some", decompressed.case)
        assertArrayEquals(original, (decompressed.payload as Value.BytesV).v)
    }
}
