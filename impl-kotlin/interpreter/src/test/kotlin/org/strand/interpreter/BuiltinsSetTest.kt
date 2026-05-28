package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 5, Group B — Set.* opaque persistent set.
 * Uses kotlinx.collections.immutable.PersistentSet under the hood.
 * Polymorphic — no prelude entries; agents use the bytesT placeholder
 * convention at the use site (mirrors Map.*).
 */
class BuiltinsSetTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!
    private fun lookupH(name: String) = Builtins.lookupHigherOrder(name)!!

    private fun setOfInts(ints: List<Long>): Value {
        var set: kotlinx.collections.immutable.PersistentSet<Value> =
            kotlinx.collections.immutable.persistentSetOf()
        for (n in ints) set = set.add(Value.IntV(n))
        return Value.SetV(set)
    }

    private fun listOfInts(ints: List<Long>): Value {
        var cur: Value = Value.SumV("Nil", null)
        for (n in ints.reversed()) {
            cur = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to Value.IntV(n), "tail" to cur,
            )))
        }
        return cur
    }

    private fun collectInts(v: Value): List<Long> {
        val out = mutableListOf<Long>()
        var cur = v
        while (cur is Value.SumV && cur.case == "Cons") {
            val payload = cur.payload as Value.ProductV
            out += (payload.fields.getValue("head") as Value.IntV).v
            cur = payload.fields.getValue("tail")
        }
        return out
    }

    // ---------- Empty / Add / Has / Size / Remove ----------

    @Test
    fun `Set_Empty Has Size Add Remove basics`() {
        val empty = lookup("strand-builtin:Set.Empty").invoke(emptyList())
        assertEquals(0L, (lookup("strand-builtin:Set.Size").invoke(listOf(empty)) as Value.IntV).v)
        assertEquals(false, (lookup("strand-builtin:Set.Has").invoke(listOf(empty, Value.IntV(1L))) as Value.BoolV).v)

        val added = lookup("strand-builtin:Set.Add").invoke(listOf(empty, Value.IntV(1L)))
        assertEquals(1L, (lookup("strand-builtin:Set.Size").invoke(listOf(added)) as Value.IntV).v)
        assertEquals(true, (lookup("strand-builtin:Set.Has").invoke(listOf(added, Value.IntV(1L))) as Value.BoolV).v)

        // Add is idempotent.
        val readded = lookup("strand-builtin:Set.Add").invoke(listOf(added, Value.IntV(1L)))
        assertEquals(1L, (lookup("strand-builtin:Set.Size").invoke(listOf(readded)) as Value.IntV).v)

        val removed = lookup("strand-builtin:Set.Remove").invoke(listOf(added, Value.IntV(1L)))
        assertEquals(0L, (lookup("strand-builtin:Set.Size").invoke(listOf(removed)) as Value.IntV).v)

        // Remove on absent value is a no-op.
        val noChange = lookup("strand-builtin:Set.Remove").invoke(listOf(empty, Value.IntV(99L)))
        assertEquals(0L, (lookup("strand-builtin:Set.Size").invoke(listOf(noChange)) as Value.IntV).v)
    }

    // ---------- Union / Intersect / Difference ----------

    @Test
    fun `Set_Union combines elements`() {
        val a = setOfInts(listOf(1L, 2L, 3L))
        val b = setOfInts(listOf(3L, 4L, 5L))
        val u = lookup("strand-builtin:Set.Union").invoke(listOf(a, b))
        assertEquals(5L, (lookup("strand-builtin:Set.Size").invoke(listOf(u)) as Value.IntV).v)
        // Every element of either side must be in the union.
        for (n in listOf(1L, 2L, 3L, 4L, 5L)) {
            assertTrue((lookup("strand-builtin:Set.Has").invoke(listOf(u, Value.IntV(n))) as Value.BoolV).v) {
                "$n missing from union"
            }
        }
    }

    @Test
    fun `Set_Intersect keeps only common elements`() {
        val a = setOfInts(listOf(1L, 2L, 3L, 4L))
        val b = setOfInts(listOf(3L, 4L, 5L, 6L))
        val i = lookup("strand-builtin:Set.Intersect").invoke(listOf(a, b))
        assertEquals(2L, (lookup("strand-builtin:Set.Size").invoke(listOf(i)) as Value.IntV).v)
        assertTrue((lookup("strand-builtin:Set.Has").invoke(listOf(i, Value.IntV(3L))) as Value.BoolV).v)
        assertTrue((lookup("strand-builtin:Set.Has").invoke(listOf(i, Value.IntV(4L))) as Value.BoolV).v)
        assertTrue(!(lookup("strand-builtin:Set.Has").invoke(listOf(i, Value.IntV(1L))) as Value.BoolV).v)
    }

    @Test
    fun `Set_Difference keeps elements of a not in b`() {
        val a = setOfInts(listOf(1L, 2L, 3L, 4L))
        val b = setOfInts(listOf(3L, 4L, 5L, 6L))
        val d = lookup("strand-builtin:Set.Difference").invoke(listOf(a, b))
        assertEquals(2L, (lookup("strand-builtin:Set.Size").invoke(listOf(d)) as Value.IntV).v)
        assertTrue((lookup("strand-builtin:Set.Has").invoke(listOf(d, Value.IntV(1L))) as Value.BoolV).v)
        assertTrue((lookup("strand-builtin:Set.Has").invoke(listOf(d, Value.IntV(2L))) as Value.BoolV).v)
        assertTrue(!(lookup("strand-builtin:Set.Has").invoke(listOf(d, Value.IntV(3L))) as Value.BoolV).v)
    }

    // ---------- ToList / FromList ----------

    @Test
    fun `Set_FromList collapses duplicates and ToList round-trips`() {
        val input = listOfInts(listOf(3L, 1L, 4L, 1L, 5L, 9L, 2L, 6L, 5L, 3L))
        val set = lookup("strand-builtin:Set.FromList").invoke(listOf(input))
        // Should contain 7 distinct elements (3, 1, 4, 5, 9, 2, 6).
        assertEquals(7L, (lookup("strand-builtin:Set.Size").invoke(listOf(set)) as Value.IntV).v)
        // ToList preserves insertion order (first-occurrence).
        val asList = lookup("strand-builtin:Set.ToList").invoke(listOf(set))
        assertEquals(listOf(3L, 1L, 4L, 5L, 9L, 2L, 6L), collectInts(asList))
    }

    @Test
    fun `Set_ToList of empty is empty list`() {
        val empty = lookup("strand-builtin:Set.Empty").invoke(emptyList())
        val asList = lookup("strand-builtin:Set.ToList").invoke(listOf(empty))
        assertEquals(Value.SumV("Nil", null), asList)
    }

    @Test
    fun `Set value equality is content-based`() {
        val a = setOfInts(listOf(1L, 2L, 3L))
        val b = setOfInts(listOf(3L, 2L, 1L))  // distinct insertion order
        // PersistentHashSet considers them equal by content.
        assertEquals(a, b)
    }

    // ---------- Set.Fold (higher-order) ----------

    @Test
    fun `Set_Fold accumulates over elements`() {
        val sum = Builtins.ApplyFn { _, args ->
            Value.IntV((args[0] as Value.IntV).v + (args[1] as Value.IntV).v)
        }
        val set = setOfInts(listOf(1L, 2L, 3L, 4L))
        val result = lookupH("strand-builtin:Set.Fold")
            .invoke(listOf(set, Value.IntV(0L), Value.UnitV), sum)
        assertEquals(Value.IntV(10L), result)
    }

    @Test
    fun `Set_Fold of empty returns init`() {
        val sum = Builtins.ApplyFn { _, args ->
            Value.IntV((args[0] as Value.IntV).v + (args[1] as Value.IntV).v)
        }
        val empty = lookup("strand-builtin:Set.Empty").invoke(emptyList())
        val result = lookupH("strand-builtin:Set.Fold")
            .invoke(listOf(empty, Value.IntV(42L), Value.UnitV), sum)
        assertEquals(Value.IntV(42L), result)
    }
}
