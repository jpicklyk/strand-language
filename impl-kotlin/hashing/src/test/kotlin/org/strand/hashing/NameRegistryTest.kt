package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.HashFunction

/**
 * Q-043 step 3a — name registry round-trip and lookup tests.
 *
 * The registry is off-graph tooling; these tests cover the data class
 * surface (resolve, put, remove, names) and the JSON serialisation
 * format documented in the proposal § 4.6.
 */
class NameRegistryTest {

    @Test
    fun `empty registry resolves nothing`() {
        assertNull(NameRegistry.EMPTY.resolve("anything"))
        assertEquals(emptyList<String>(), NameRegistry.EMPTY.names())
    }

    @Test
    fun `put adds an entry and resolve returns the bound hash`() {
        val hash = fakeHash(0x01)
        val registry = NameRegistry.EMPTY.put("stdlib/identity", hash)
        assertEquals(hash, registry.resolve("stdlib/identity"))
    }

    @Test
    fun `put with an existing name overwrites the previous binding`() {
        val first = fakeHash(0x01)
        val second = fakeHash(0x02)
        val registry = NameRegistry.EMPTY
            .put("stdlib/identity", first)
            .put("stdlib/identity", second)
        assertEquals(second, registry.resolve("stdlib/identity"))
    }

    @Test
    fun `remove drops an entry`() {
        val hash = fakeHash(0x01)
        val registry = NameRegistry.EMPTY
            .put("stdlib/identity", hash)
            .remove("stdlib/identity")
        assertNull(registry.resolve("stdlib/identity"))
    }

    @Test
    fun `remove of unknown name is a no-op`() {
        val registry = NameRegistry.EMPTY.remove("does/not/exist")
        assertEquals(0, registry.entries.size)
    }

    @Test
    fun `names returns entries in sorted order`() {
        val registry = NameRegistry.EMPTY
            .put("zebra", fakeHash(0x03))
            .put("alpha", fakeHash(0x01))
            .put("mango", fakeHash(0x02))
        assertEquals(listOf("alpha", "mango", "zebra"), registry.names())
    }

    @Test
    fun `JSON round trip preserves entries`() {
        val original = NameRegistry.EMPTY
            .put("stdlib/json-parser", fakeHash(0x01))
            .put("stdlib/markdown-render", fakeHash(0x02))
            .put("app/main", fakeHash(0x03))
        val json = NameRegistry.toJson(original)
        val parsed = NameRegistry.fromJson(json)
        assertEquals(original.entries, parsed.entries)
    }

    @Test
    fun `JSON output is stable across reorderings`() {
        val a = NameRegistry.EMPTY
            .put("zebra", fakeHash(0x03))
            .put("alpha", fakeHash(0x01))
        val b = NameRegistry.EMPTY
            .put("alpha", fakeHash(0x01))
            .put("zebra", fakeHash(0x03))
        assertEquals(NameRegistry.toJson(a), NameRegistry.toJson(b),
            "Registry JSON output must sort by name for diff stability")
    }

    @Test
    fun `unsupported version is rejected`() {
        val json = """{"version": 2, "entries": {}}"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            NameRegistry.fromJson(json)
        }
        assert(ex.message?.contains("version") == true)
    }

    @Test
    fun `malformed JSON is rejected with a structured error`() {
        assertThrows(IllegalArgumentException::class.java) {
            NameRegistry.fromJson("{not json}")
        }
    }

    @Test
    fun `entries with escaped characters round-trip`() {
        val original = NameRegistry.EMPTY
            .put("path/with\"quotes", fakeHash(0x01))
            .put("path/with\\backslash", fakeHash(0x02))
        val json = NameRegistry.toJson(original)
        val parsed = NameRegistry.fromJson(json)
        assertEquals(original.entries, parsed.entries)
    }

    private fun fakeHash(seed: Int): Hash {
        // Construct a synthetic 33-byte BLAKE3 multi-hash (1-byte prefix + 32-byte digest).
        val bytes = ByteArray(33)
        bytes[0] = HashFunction.Blake3.prefix
        for (i in 1..32) {
            bytes[i] = ((seed + i) and 0xff).toByte()
        }
        return Hash(bytes)
    }
}
