package org.strand.interpreter

import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 3 phase 3 — Map.* opaque-handle builtins.
 * Each MapV is backed by a kotlinx-collections-immutable PersistentMap;
 * put/remove return new instances via path-copy so values stay
 * structurally equal across calls.
 */
class BuiltinsMapTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!
    private fun lookupH(name: String) = Builtins.lookupHigherOrder(name)!!

    private fun collectInts(v: Value): List<Long> {
        val out = mutableListOf<Long>()
        var cur = v
        while (cur is Value.SumV && cur.case == "Cons") {
            val p = cur.payload as Value.ProductV
            out += (p.fields.getValue("head") as Value.IntV).v
            cur = p.fields.getValue("tail")
        }
        return out
    }

    private fun collectStrings(v: Value): List<String> {
        val out = mutableListOf<String>()
        var cur = v
        while (cur is Value.SumV && cur.case == "Cons") {
            val p = cur.payload as Value.ProductV
            out += (p.fields.getValue("head") as Value.StringV).v
            cur = p.fields.getValue("tail")
        }
        return out
    }

    // ---------- Construction + basic Get/Put ----------

    @Test
    fun `Map_Empty returns an empty MapV`() {
        val m = lookup("strand-builtin:Map.Empty").invoke(emptyList()) as Value.MapV
        assertEquals(0, m.entries.size)
    }

    @Test
    fun `Map_Put + Map_Get round-trips`() {
        val empty = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        val put = lookup("strand-builtin:Map.Put").invoke(listOf(empty, Value.StringV("k"), Value.IntV(42L)))
        val got = lookup("strand-builtin:Map.Get").invoke(listOf(put, Value.StringV("k")))
        assertEquals(Value.SumV("Some", Value.IntV(42L)), got)
    }

    @Test
    fun `Map_Get returns None on miss`() {
        val empty = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        val got = lookup("strand-builtin:Map.Get").invoke(listOf(empty, Value.StringV("missing")))
        assertEquals(Value.SumV("None", null), got)
    }

    @Test
    fun `Map_Put replaces the prior value at the same key`() {
        var m: Value = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        m = lookup("strand-builtin:Map.Put").invoke(listOf(m, Value.StringV("k"), Value.IntV(1L)))
        m = lookup("strand-builtin:Map.Put").invoke(listOf(m, Value.StringV("k"), Value.IntV(2L)))
        assertEquals(
            Value.SumV("Some", Value.IntV(2L)),
            lookup("strand-builtin:Map.Get").invoke(listOf(m, Value.StringV("k"))),
        )
        assertEquals(Value.IntV(1L), lookup("strand-builtin:Map.Size").invoke(listOf(m)))
    }

    // ---------- Has, Size ----------

    @Test
    fun `Map_Has true on present false on missing`() {
        val m = Value.MapV(persistentMapOf(Value.StringV("a") to Value.IntV(1L)))
        assertEquals(Value.BoolV(true), lookup("strand-builtin:Map.Has").invoke(listOf(m, Value.StringV("a"))))
        assertEquals(Value.BoolV(false), lookup("strand-builtin:Map.Has").invoke(listOf(m, Value.StringV("b"))))
    }

    @Test
    fun `Map_Size grows and shrinks correctly`() {
        val empty = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        assertEquals(Value.IntV(0L), lookup("strand-builtin:Map.Size").invoke(listOf(empty)))
        var m: Value = empty
        for (i in 1..5) {
            m = lookup("strand-builtin:Map.Put").invoke(listOf(m, Value.IntV(i.toLong()), Value.StringV("v$i")))
        }
        assertEquals(Value.IntV(5L), lookup("strand-builtin:Map.Size").invoke(listOf(m)))
        m = lookup("strand-builtin:Map.Remove").invoke(listOf(m, Value.IntV(3L)))
        assertEquals(Value.IntV(4L), lookup("strand-builtin:Map.Size").invoke(listOf(m)))
    }

    // ---------- Remove ----------

    @Test
    fun `Map_Remove deletes the binding`() {
        var m: Value = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        m = lookup("strand-builtin:Map.Put").invoke(listOf(m, Value.StringV("k"), Value.IntV(42L)))
        m = lookup("strand-builtin:Map.Remove").invoke(listOf(m, Value.StringV("k")))
        assertEquals(
            Value.SumV("None", null),
            lookup("strand-builtin:Map.Get").invoke(listOf(m, Value.StringV("k"))),
        )
    }

    @Test
    fun `Map_Remove of missing key is a no-op`() {
        val m = Value.MapV(persistentMapOf(Value.StringV("a") to Value.IntV(1L)))
        val after = lookup("strand-builtin:Map.Remove").invoke(listOf(m, Value.StringV("not-there")))
        assertEquals(Value.IntV(1L), lookup("strand-builtin:Map.Size").invoke(listOf(after)))
    }

    // ---------- Keys, Values, Entries ----------

    @Test
    fun `Map_Keys returns keys in insertion order`() {
        var m: Value = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        for (k in listOf("alpha", "beta", "gamma")) {
            m = lookup("strand-builtin:Map.Put").invoke(listOf(m, Value.StringV(k), Value.IntV(0L)))
        }
        val keys = collectStrings(lookup("strand-builtin:Map.Keys").invoke(listOf(m)))
        assertEquals(listOf("alpha", "beta", "gamma"), keys)
    }

    @Test
    fun `Map_Values returns values in insertion order`() {
        var m: Value = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        for ((k, v) in listOf("a" to 1L, "b" to 2L, "c" to 3L)) {
            m = lookup("strand-builtin:Map.Put").invoke(listOf(m, Value.StringV(k), Value.IntV(v)))
        }
        val values = collectInts(lookup("strand-builtin:Map.Values").invoke(listOf(m)))
        assertEquals(listOf(1L, 2L, 3L), values)
    }

    @Test
    fun `Map_Entries returns Cons-Nil of key-value ProductVs`() {
        var m: Value = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        m = lookup("strand-builtin:Map.Put").invoke(listOf(m, Value.StringV("k1"), Value.IntV(10L)))
        m = lookup("strand-builtin:Map.Put").invoke(listOf(m, Value.StringV("k2"), Value.IntV(20L)))
        var cur: Value = lookup("strand-builtin:Map.Entries").invoke(listOf(m))
        val collected = mutableListOf<Pair<String, Long>>()
        while (cur is Value.SumV && cur.case == "Cons") {
            val p = cur.payload as Value.ProductV
            val entry = p.fields.getValue("head") as Value.ProductV
            val key = (entry.fields.getValue("key") as Value.StringV).v
            val value = (entry.fields.getValue("value") as Value.IntV).v
            collected += (key to value)
            cur = p.fields.getValue("tail")
        }
        assertEquals(listOf("k1" to 10L, "k2" to 20L), collected)
    }

    // ---------- Persistence (path-copy on writes) ----------

    @Test
    fun `Map_Put does not mutate the input map`() {
        val original = lookup("strand-builtin:Map.Put").invoke(
            listOf(lookup("strand-builtin:Map.Empty").invoke(emptyList()), Value.StringV("k"), Value.IntV(1L)),
        )
        val updated = lookup("strand-builtin:Map.Put").invoke(
            listOf(original, Value.StringV("k"), Value.IntV(2L)),
        )
        // original still has v=1; updated has v=2
        assertEquals(
            Value.SumV("Some", Value.IntV(1L)),
            lookup("strand-builtin:Map.Get").invoke(listOf(original, Value.StringV("k"))),
        )
        assertEquals(
            Value.SumV("Some", Value.IntV(2L)),
            lookup("strand-builtin:Map.Get").invoke(listOf(updated, Value.StringV("k"))),
        )
    }

    // ---------- Map.Fold (higher-order) ----------

    @Test
    fun `Map_Fold sums values via a 3-arg accumulator fn`() {
        var m: Value = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        for ((k, v) in listOf("a" to 1L, "b" to 2L, "c" to 3L)) {
            m = lookup("strand-builtin:Map.Put").invoke(listOf(m, Value.StringV(k), Value.IntV(v)))
        }
        val sumStep = Builtins.ApplyFn { _, args ->
            // (acc, key, value) -> acc + value
            Value.IntV((args[0] as Value.IntV).v + (args[2] as Value.IntV).v)
        }
        val result = lookupH("strand-builtin:Map.Fold").invoke(listOf(m, Value.IntV(0L), Value.UnitV), sumStep)
        assertEquals(Value.IntV(6L), result)
    }

    @Test
    fun `Map_Fold over empty map returns init`() {
        val empty = lookup("strand-builtin:Map.Empty").invoke(emptyList())
        val anyStep = Builtins.ApplyFn { _, args -> args[0] }
        val result = lookupH("strand-builtin:Map.Fold").invoke(
            listOf(empty, Value.IntV(99L), Value.UnitV), anyStep,
        )
        assertEquals(Value.IntV(99L), result)
    }

    // ---------- Structural equality across Maps ----------

    @Test
    fun `Two MapVs with the same content are equal`() {
        val a = Value.MapV(persistentMapOf(
            Value.StringV("k") to Value.IntV(1L),
            Value.StringV("k2") to Value.IntV(2L),
        ))
        val b = Value.MapV(persistentMapOf(
            Value.StringV("k") to Value.IntV(1L),
            Value.StringV("k2") to Value.IntV(2L),
        ))
        assertEquals(a, b)
    }
}
