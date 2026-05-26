package org.strand.interpreter

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Stdlib expansion round 2 — Random.* builtins driven via the
 * pluggable [Builtins.random] source. Installs a seeded
 * java.util.Random in @BeforeAll so values are reproducible across
 * runs; restores to SecureRandom in @AfterAll.
 */
class BuiltinsRandomTest {

    companion object {
        // Reset to SecureRandom after the test class. The seed value
        // is the same one the rest of the Strand test suite uses
        // (FIXED_REPLAY_TIMESTAMP) for consistency, though Random
        // determinism only requires it stay constant within a test.
        private const val SEED: Long = 1_780_000_000_000L

        @JvmStatic
        @BeforeAll
        fun installSeeded() {
            Builtins.random = java.util.Random(SEED)
        }

        @JvmStatic
        @AfterAll
        fun restoreSecure() {
            Builtins.random = java.security.SecureRandom()
        }
    }

    @BeforeEach
    fun resetSeed() {
        // Per-test reset so test order doesn't matter and each test
        // sees the same initial seed state.
        Builtins.random = java.util.Random(SEED)
    }

    private fun lookup(name: String) = Builtins.lookup(name)!!

    // ---------- Random.Int ----------

    @Test
    fun `Random_Int stays within bounds across many samples`() {
        val fn = lookup("strand-builtin:Random.Int")
        val min = 10L
        val max = 20L
        repeat(1000) {
            val v = (fn.invoke(listOf(Value.IntV(min), Value.IntV(max))) as Value.IntV).v
            assertTrue(v in min until max) { "value $v out of bounds [$min, $max)" }
        }
    }

    @Test
    fun `Random_Int with seeded source is deterministic`() {
        val fn = lookup("strand-builtin:Random.Int")
        Builtins.random = java.util.Random(SEED)
        val a = (0 until 5).map { (fn.invoke(listOf(Value.IntV(0L), Value.IntV(1000L))) as Value.IntV).v }
        Builtins.random = java.util.Random(SEED)
        val b = (0 until 5).map { (fn.invoke(listOf(Value.IntV(0L), Value.IntV(1000L))) as Value.IntV).v }
        assertEquals(a, b)
    }

    @Test
    fun `Random_Int rejects max less or equal to min`() {
        val fn = lookup("strand-builtin:Random.Int")
        assertThrows<IllegalArgumentException> {
            fn.invoke(listOf(Value.IntV(5L), Value.IntV(5L)))
        }
        assertThrows<IllegalArgumentException> {
            fn.invoke(listOf(Value.IntV(5L), Value.IntV(3L)))
        }
    }

    @Test
    fun `Random_Int single-value range yields min`() {
        // max = min + 1 means the only legal value is min itself.
        val fn = lookup("strand-builtin:Random.Int")
        repeat(50) {
            val v = (fn.invoke(listOf(Value.IntV(7L), Value.IntV(8L))) as Value.IntV).v
            assertEquals(7L, v)
        }
    }

    // ---------- Random.Float ----------

    @Test
    fun `Random_Float stays in zero-to-one across many samples`() {
        val fn = lookup("strand-builtin:Random.Float")
        repeat(1000) {
            val v = (fn.invoke(emptyList()) as Value.FloatV).v
            assertTrue(v >= 0.0 && v < 1.0) { "value $v out of [0.0, 1.0)" }
        }
    }

    @Test
    fun `Random_Float with seeded source is deterministic`() {
        val fn = lookup("strand-builtin:Random.Float")
        Builtins.random = java.util.Random(SEED)
        val a = (0 until 5).map { (fn.invoke(emptyList()) as Value.FloatV).v }
        Builtins.random = java.util.Random(SEED)
        val b = (0 until 5).map { (fn.invoke(emptyList()) as Value.FloatV).v }
        assertEquals(a, b)
    }

    // ---------- Random.Bytes ----------

    @Test
    fun `Random_Bytes produces exactly n bytes`() {
        val fn = lookup("strand-builtin:Random.Bytes")
        for (n in listOf(0, 1, 16, 100, 1000)) {
            val v = (fn.invoke(listOf(Value.IntV(n.toLong()))) as Value.BytesV).v
            assertEquals(n, v.size)
        }
    }

    @Test
    fun `Random_Bytes with seeded source is deterministic`() {
        val fn = lookup("strand-builtin:Random.Bytes")
        Builtins.random = java.util.Random(SEED)
        val a = (fn.invoke(listOf(Value.IntV(32L))) as Value.BytesV).v
        Builtins.random = java.util.Random(SEED)
        val b = (fn.invoke(listOf(Value.IntV(32L))) as Value.BytesV).v
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun `Random_Bytes successive calls produce different output`() {
        val fn = lookup("strand-builtin:Random.Bytes")
        val a = (fn.invoke(listOf(Value.IntV(32L))) as Value.BytesV).v
        val b = (fn.invoke(listOf(Value.IntV(32L))) as Value.BytesV).v
        // 32 bytes of fully-random output colliding is astronomically unlikely.
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `Random_Bytes rejects negative n`() {
        val fn = lookup("strand-builtin:Random.Bytes")
        assertThrows<IllegalArgumentException> {
            fn.invoke(listOf(Value.IntV(-1L)))
        }
    }
}
