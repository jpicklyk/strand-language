package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 5, Group C — Map.Map / Map.Merge / Map.Filter
 * higher-order extensions on top of round-3 Map.*. All polymorphic,
 * no prelude entries.
 */
class BuiltinsMapExtensionsTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!
    private fun lookupH(name: String) = Builtins.lookupHigherOrder(name)!!

    private fun mapOfIntToInt(pairs: List<Pair<Long, Long>>): Value {
        var m: kotlinx.collections.immutable.PersistentMap<Value, Value> =
            kotlinx.collections.immutable.persistentMapOf()
        for ((k, v) in pairs) m = m.put(Value.IntV(k), Value.IntV(v))
        return Value.MapV(m)
    }

    private fun intEntries(v: Value): Map<Long, Long> =
        (v as Value.MapV).entries.entries.associate { (k, vv) ->
            (k as Value.IntV).v to (vv as Value.IntV).v
        }

    // ---------- Map.Map ----------

    @Test
    fun `Map_Map transforms values, preserves keys`() {
        val input = mapOfIntToInt(listOf(1L to 10L, 2L to 20L, 3L to 30L))
        val double = Builtins.ApplyFn { _, args ->
            Value.IntV((args[0] as Value.IntV).v * 2L)
        }
        val result = lookupH("strand-builtin:Map.Map").invoke(listOf(input, Value.UnitV), double)
        assertEquals(mapOf(1L to 20L, 2L to 40L, 3L to 60L), intEntries(result))
    }

    @Test
    fun `Map_Map of empty map is empty`() {
        val empty = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        val identity = Builtins.ApplyFn { _, args -> args[0] }
        val result = lookupH("strand-builtin:Map.Map").invoke(listOf(empty, Value.UnitV), identity)
        assertEquals(0L, (lookup("strand-builtin:Map.Size").invoke(listOf(result)) as Value.IntV).v)
    }

    // ---------- Map.Merge ----------

    @Test
    fun `Map_Merge with no overlap unions both maps`() {
        val a = mapOfIntToInt(listOf(1L to 100L, 2L to 200L))
        val b = mapOfIntToInt(listOf(3L to 300L, 4L to 400L))
        val nope = Builtins.ApplyFn { _, _ -> error("conflict fn should not be called") }
        val result = lookupH("strand-builtin:Map.Merge").invoke(listOf(a, b, Value.UnitV), nope)
        assertEquals(mapOf(1L to 100L, 2L to 200L, 3L to 300L, 4L to 400L), intEntries(result))
    }

    @Test
    fun `Map_Merge invokes conflict fn on overlapping keys`() {
        val a = mapOfIntToInt(listOf(1L to 100L, 2L to 200L))
        val b = mapOfIntToInt(listOf(2L to 999L, 3L to 300L))
        val keepFirst = Builtins.ApplyFn { _, args -> args[0] }  // keeps a's value
        val result = lookupH("strand-builtin:Map.Merge").invoke(listOf(a, b, Value.UnitV), keepFirst)
        assertEquals(mapOf(1L to 100L, 2L to 200L, 3L to 300L), intEntries(result))

        val keepSecond = Builtins.ApplyFn { _, args -> args[1] }  // keeps b's value
        val result2 = lookupH("strand-builtin:Map.Merge").invoke(listOf(a, b, Value.UnitV), keepSecond)
        assertEquals(mapOf(1L to 100L, 2L to 999L, 3L to 300L), intEntries(result2))

        val sum = Builtins.ApplyFn { _, args ->
            Value.IntV((args[0] as Value.IntV).v + (args[1] as Value.IntV).v)
        }
        val result3 = lookupH("strand-builtin:Map.Merge").invoke(listOf(a, b, Value.UnitV), sum)
        assertEquals(mapOf(1L to 100L, 2L to 1199L, 3L to 300L), intEntries(result3))
    }

    // ---------- Map.Filter ----------

    @Test
    fun `Map_Filter keeps only entries where fn returns true`() {
        val input = mapOfIntToInt(listOf(1L to 10L, 2L to 20L, 3L to 30L, 4L to 40L))
        val valGt15 = Builtins.ApplyFn { _, args ->
            // args[0] is key, args[1] is value
            Value.BoolV((args[1] as Value.IntV).v > 15L)
        }
        val result = lookupH("strand-builtin:Map.Filter").invoke(listOf(input, Value.UnitV), valGt15)
        assertEquals(mapOf(2L to 20L, 3L to 30L, 4L to 40L), intEntries(result))
    }

    @Test
    fun `Map_Filter sees both key and value`() {
        val input = mapOfIntToInt(listOf(1L to 10L, 2L to 20L, 3L to 30L))
        val keyEqualsHalfVal = Builtins.ApplyFn { _, args ->
            val k = (args[0] as Value.IntV).v
            val v = (args[1] as Value.IntV).v
            Value.BoolV(v == k * 10L)
        }
        val result = lookupH("strand-builtin:Map.Filter").invoke(listOf(input, Value.UnitV), keyEqualsHalfVal)
        assertEquals(mapOf(1L to 10L, 2L to 20L, 3L to 30L), intEntries(result))
    }

    @Test
    fun `Map_Filter all-false yields empty`() {
        val input = mapOfIntToInt(listOf(1L to 10L, 2L to 20L))
        val never = Builtins.ApplyFn { _, _ -> Value.BoolV(false) }
        val result = lookupH("strand-builtin:Map.Filter").invoke(listOf(input, Value.UnitV), never)
        assertTrue(intEntries(result).isEmpty())
    }
}
