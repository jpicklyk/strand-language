package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 2 — direct unit tests of List.* primitives
 * (Empty, IsEmpty, Length, Reverse, Take, Drop, Concat, Nth). All
 * pure (no IO), no resource cleanup needed.
 */
class BuiltinsListTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    /** Build a SumV-encoded Cons/Nil list from a Kotlin List<Int>. */
    private fun listOfInts(ints: List<Long>): Value {
        var cur: Value = Value.SumV("Nil", null)
        for (n in ints.reversed()) {
            cur = Value.SumV("Cons", Value.ProductV(mapOf("head" to Value.IntV(n), "tail" to cur)))
        }
        return cur
    }

    /** Walk a SumV-encoded Cons/Nil list collecting IntV heads. */
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

    // ---------- Construction + emptiness ----------

    @Test
    fun `List_Empty returns Nil`() {
        val nil = lookup("strand-builtin:List.Empty").invoke(emptyList())
        assertEquals(Value.SumV("Nil", null), nil)
    }

    @Test
    fun `List_IsEmpty distinguishes Nil from Cons`() {
        val isEmpty = lookup("strand-builtin:List.IsEmpty")
        assertEquals(Value.BoolV(true), isEmpty.invoke(listOf(Value.SumV("Nil", null))))
        assertEquals(Value.BoolV(false), isEmpty.invoke(listOf(listOfInts(listOf(1L)))))
    }

    // ---------- Length ----------

    @Test
    fun `List_Length of empty and non-empty lists`() {
        val len = lookup("strand-builtin:List.Length")
        assertEquals(Value.IntV(0L), len.invoke(listOf(Value.SumV("Nil", null))))
        assertEquals(Value.IntV(3L), len.invoke(listOf(listOfInts(listOf(10L, 20L, 30L)))))
        assertEquals(Value.IntV(1L), len.invoke(listOf(listOfInts(listOf(42L)))))
    }

    // ---------- Reverse ----------

    @Test
    fun `List_Reverse preserves elements in reverse order`() {
        val rev = lookup("strand-builtin:List.Reverse")
        val input = listOfInts(listOf(1L, 2L, 3L, 4L))
        val reversed = rev.invoke(listOf(input))
        assertEquals(listOf(4L, 3L, 2L, 1L), collectInts(reversed))
    }

    @Test
    fun `List_Reverse of empty list is empty`() {
        val rev = lookup("strand-builtin:List.Reverse")
        assertEquals(Value.SumV("Nil", null), rev.invoke(listOf(Value.SumV("Nil", null))))
    }

    @Test
    fun `List_Reverse of single-element list is itself`() {
        val rev = lookup("strand-builtin:List.Reverse")
        val input = listOfInts(listOf(99L))
        assertEquals(listOf(99L), collectInts(rev.invoke(listOf(input))))
    }

    // ---------- Take ----------

    @Test
    fun `List_Take of n less than length yields first n`() {
        val take = lookup("strand-builtin:List.Take")
        val input = listOfInts(listOf(1L, 2L, 3L, 4L, 5L))
        assertEquals(listOf(1L, 2L, 3L), collectInts(take.invoke(listOf(input, Value.IntV(3L)))))
    }

    @Test
    fun `List_Take of zero or negative yields Nil`() {
        val take = lookup("strand-builtin:List.Take")
        val input = listOfInts(listOf(1L, 2L, 3L))
        assertEquals(Value.SumV("Nil", null), take.invoke(listOf(input, Value.IntV(0L))))
        assertEquals(Value.SumV("Nil", null), take.invoke(listOf(input, Value.IntV(-5L))))
    }

    @Test
    fun `List_Take of n greater than length yields full list`() {
        val take = lookup("strand-builtin:List.Take")
        val input = listOfInts(listOf(1L, 2L))
        assertEquals(listOf(1L, 2L), collectInts(take.invoke(listOf(input, Value.IntV(99L)))))
    }

    // ---------- Drop ----------

    @Test
    fun `List_Drop skips first n elements`() {
        val drop = lookup("strand-builtin:List.Drop")
        val input = listOfInts(listOf(1L, 2L, 3L, 4L, 5L))
        assertEquals(listOf(4L, 5L), collectInts(drop.invoke(listOf(input, Value.IntV(3L)))))
    }

    @Test
    fun `List_Drop of zero or negative yields whole list`() {
        val drop = lookup("strand-builtin:List.Drop")
        val input = listOfInts(listOf(1L, 2L, 3L))
        assertEquals(listOf(1L, 2L, 3L), collectInts(drop.invoke(listOf(input, Value.IntV(0L)))))
        assertEquals(listOf(1L, 2L, 3L), collectInts(drop.invoke(listOf(input, Value.IntV(-5L)))))
    }

    @Test
    fun `List_Drop of n greater than length yields Nil`() {
        val drop = lookup("strand-builtin:List.Drop")
        val input = listOfInts(listOf(1L, 2L))
        assertEquals(Value.SumV("Nil", null), drop.invoke(listOf(input, Value.IntV(99L))))
    }

    // ---------- Concat ----------

    @Test
    fun `List_Concat appends second list to first`() {
        val concat = lookup("strand-builtin:List.Concat")
        val a = listOfInts(listOf(1L, 2L, 3L))
        val b = listOfInts(listOf(4L, 5L))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), collectInts(concat.invoke(listOf(a, b))))
    }

    @Test
    fun `List_Concat with empty arguments`() {
        val concat = lookup("strand-builtin:List.Concat")
        val nil = Value.SumV("Nil", null)
        val a = listOfInts(listOf(7L, 8L))
        // empty + nonempty
        assertEquals(listOf(7L, 8L), collectInts(concat.invoke(listOf(nil, a))))
        // nonempty + empty
        assertEquals(listOf(7L, 8L), collectInts(concat.invoke(listOf(a, nil))))
        // empty + empty
        assertEquals(Value.SumV("Nil", null), concat.invoke(listOf(nil, nil)))
    }

    // ---------- Nth ----------

    @Test
    fun `List_Nth returns Some for in-range index`() {
        val nth = lookup("strand-builtin:List.Nth")
        val input = listOfInts(listOf(10L, 20L, 30L, 40L))
        assertEquals(Value.SumV("Some", Value.IntV(10L)), nth.invoke(listOf(input, Value.IntV(0L))))
        assertEquals(Value.SumV("Some", Value.IntV(30L)), nth.invoke(listOf(input, Value.IntV(2L))))
        assertEquals(Value.SumV("Some", Value.IntV(40L)), nth.invoke(listOf(input, Value.IntV(3L))))
    }

    @Test
    fun `List_Nth returns None for out-of-range and negative indices`() {
        val nth = lookup("strand-builtin:List.Nth")
        val input = listOfInts(listOf(10L, 20L))
        assertEquals(Value.SumV("None", null), nth.invoke(listOf(input, Value.IntV(5L))))
        assertEquals(Value.SumV("None", null), nth.invoke(listOf(input, Value.IntV(-1L))))
        assertEquals(Value.SumV("None", null), nth.invoke(listOf(Value.SumV("Nil", null), Value.IntV(0L))))
    }

    // ---------- Polymorphic head types ----------

    @Test
    fun `List operations work with String heads`() {
        // Builtins are polymorphic in head type — the runtime never inspects
        // head values, just threads them through.
        val len = lookup("strand-builtin:List.Length")
        val nth = lookup("strand-builtin:List.Nth")
        var cur: Value = Value.SumV("Nil", null)
        for (s in listOf("c", "b", "a")) {
            cur = Value.SumV("Cons", Value.ProductV(mapOf("head" to Value.StringV(s), "tail" to cur)))
        }
        assertEquals(Value.IntV(3L), len.invoke(listOf(cur)))
        assertEquals(Value.SumV("Some", Value.StringV("b")), nth.invoke(listOf(cur, Value.IntV(1L))))
    }
}
