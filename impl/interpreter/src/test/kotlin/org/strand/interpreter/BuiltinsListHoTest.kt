package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 2, Slice 2.2 — direct unit tests of the
 * higher-order List ops (Map, Filter, Fold, Find, Any, All). The
 * tests construct an ApplyFn directly in Kotlin rather than going
 * through the Interpreter; this isolates the builtin's traversal
 * logic from interpreter semantics. The end-to-end path
 * (ForeignNode -> Application -> applyForeign -> ApplyFn ->
 * applyValueToArgs -> Closure body eval) is covered by
 * InterpreterTest at the interpreter integration layer (not yet
 * added for the new higher-order builtins — a separate corpus
 * follow-up would exercise them end-to-end).
 */
class BuiltinsListHoTest {

    private fun lookupH(name: String) = Builtins.lookupHigherOrder(name)!!

    private fun listOfInts(ints: List<Long>): Value {
        var cur: Value = Value.SumV("Nil", null)
        for (n in ints.reversed()) {
            cur = Value.SumV("Cons", Value.ProductV(mapOf("head" to Value.IntV(n), "tail" to cur)))
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

    // ---------- List.Map ----------

    @Test
    fun `List_Map applies fn to each element`() {
        val doubleIt = Builtins.ApplyFn { _, args ->
            Value.IntV((args[0] as Value.IntV).v * 2L)
        }
        val input = listOfInts(listOf(1L, 2L, 3L, 4L))
        val result = lookupH("strand-builtin:List.Map").invoke(listOf(input, Value.UnitV), doubleIt)
        assertEquals(listOf(2L, 4L, 6L, 8L), collectInts(result))
    }

    @Test
    fun `List_Map of empty list is empty`() {
        val anyFn = Builtins.ApplyFn { _, args -> args[0] }
        val result = lookupH("strand-builtin:List.Map").invoke(
            listOf(Value.SumV("Nil", null), Value.UnitV), anyFn,
        )
        assertEquals(Value.SumV("Nil", null), result)
    }

    @Test
    fun `List_Map preserves order`() {
        val toString = Builtins.ApplyFn { _, args ->
            Value.StringV("(${(args[0] as Value.IntV).v})")
        }
        val input = listOfInts(listOf(10L, 20L, 30L))
        val result = lookupH("strand-builtin:List.Map").invoke(listOf(input, Value.UnitV), toString)
        val strs = mutableListOf<String>()
        var cur = result
        while (cur is Value.SumV && cur.case == "Cons") {
            val payload = cur.payload as Value.ProductV
            strs += (payload.fields.getValue("head") as Value.StringV).v
            cur = payload.fields.getValue("tail")
        }
        assertEquals(listOf("(10)", "(20)", "(30)"), strs)
    }

    // ---------- List.Filter ----------

    @Test
    fun `List_Filter keeps only true elements`() {
        val isEven = Builtins.ApplyFn { _, args ->
            Value.BoolV((args[0] as Value.IntV).v % 2L == 0L)
        }
        val input = listOfInts(listOf(1L, 2L, 3L, 4L, 5L, 6L))
        val result = lookupH("strand-builtin:List.Filter").invoke(listOf(input, Value.UnitV), isEven)
        assertEquals(listOf(2L, 4L, 6L), collectInts(result))
    }

    @Test
    fun `List_Filter all-true keeps everything, all-false yields Nil`() {
        val alwaysTrue = Builtins.ApplyFn { _, _ -> Value.BoolV(true) }
        val alwaysFalse = Builtins.ApplyFn { _, _ -> Value.BoolV(false) }
        val input = listOfInts(listOf(1L, 2L, 3L))
        val keepAll = lookupH("strand-builtin:List.Filter").invoke(listOf(input, Value.UnitV), alwaysTrue)
        val keepNone = lookupH("strand-builtin:List.Filter").invoke(listOf(input, Value.UnitV), alwaysFalse)
        assertEquals(listOf(1L, 2L, 3L), collectInts(keepAll))
        assertEquals(Value.SumV("Nil", null), keepNone)
    }

    // ---------- List.Fold ----------

    @Test
    fun `List_Fold sums via accumulator`() {
        val sumStep = Builtins.ApplyFn { _, args ->
            Value.IntV((args[0] as Value.IntV).v + (args[1] as Value.IntV).v)
        }
        val input = listOfInts(listOf(1L, 2L, 3L, 4L, 5L))
        val result = lookupH("strand-builtin:List.Fold").invoke(
            listOf(input, Value.IntV(0L), Value.UnitV), sumStep,
        )
        assertEquals(Value.IntV(15L), result)
    }

    @Test
    fun `List_Fold over empty list returns init`() {
        val anyStep = Builtins.ApplyFn { _, args -> args[0] }
        val result = lookupH("strand-builtin:List.Fold").invoke(
            listOf(Value.SumV("Nil", null), Value.IntV(99L), Value.UnitV), anyStep,
        )
        assertEquals(Value.IntV(99L), result)
    }

    @Test
    fun `List_Fold preserves left-to-right order`() {
        // Concat strings — order matters.
        val concat = Builtins.ApplyFn { _, args ->
            Value.StringV((args[0] as Value.StringV).v + (args[1] as Value.StringV).v)
        }
        var input: Value = Value.SumV("Nil", null)
        for (s in listOf("c", "b", "a")) {
            input = Value.SumV("Cons", Value.ProductV(mapOf("head" to Value.StringV(s), "tail" to input)))
        }
        // input is now a, b, c
        val result = lookupH("strand-builtin:List.Fold").invoke(
            listOf(input, Value.StringV(""), Value.UnitV), concat,
        )
        assertEquals(Value.StringV("abc"), result)
    }

    // ---------- List.Find ----------

    @Test
    fun `List_Find returns Some on first match`() {
        val greaterThanThree = Builtins.ApplyFn { _, args ->
            Value.BoolV((args[0] as Value.IntV).v > 3L)
        }
        val input = listOfInts(listOf(1L, 2L, 3L, 4L, 5L))
        val result = lookupH("strand-builtin:List.Find").invoke(listOf(input, Value.UnitV), greaterThanThree)
        assertEquals(Value.SumV("Some", Value.IntV(4L)), result)
    }

    @Test
    fun `List_Find returns None when no match`() {
        val never = Builtins.ApplyFn { _, _ -> Value.BoolV(false) }
        val input = listOfInts(listOf(1L, 2L, 3L))
        val result = lookupH("strand-builtin:List.Find").invoke(listOf(input, Value.UnitV), never)
        assertEquals(Value.SumV("None", null), result)
    }

    @Test
    fun `List_Find short-circuits on first match`() {
        // Counts how many times the predicate is called.
        var calls = 0
        val countingPredicate = Builtins.ApplyFn { _, args ->
            calls++
            Value.BoolV((args[0] as Value.IntV).v == 3L)
        }
        val input = listOfInts(listOf(1L, 2L, 3L, 4L, 5L, 6L))
        val result = lookupH("strand-builtin:List.Find").invoke(listOf(input, Value.UnitV), countingPredicate)
        assertEquals(Value.SumV("Some", Value.IntV(3L)), result)
        assertEquals(3, calls, "predicate should run for 1, 2, 3 then short-circuit")
    }

    // ---------- List.Any / List.All ----------

    @Test
    fun `List_Any short-circuits true on first match`() {
        var calls = 0
        val isFive = Builtins.ApplyFn { _, args ->
            calls++
            Value.BoolV((args[0] as Value.IntV).v == 5L)
        }
        val input = listOfInts(listOf(1L, 2L, 3L, 4L, 5L, 6L))
        val result = lookupH("strand-builtin:List.Any").invoke(listOf(input, Value.UnitV), isFive)
        assertEquals(Value.BoolV(true), result)
        assertEquals(5, calls)
    }

    @Test
    fun `List_Any false on empty and on no matches`() {
        val never = Builtins.ApplyFn { _, _ -> Value.BoolV(false) }
        assertEquals(
            Value.BoolV(false),
            lookupH("strand-builtin:List.Any").invoke(listOf(Value.SumV("Nil", null), Value.UnitV), never),
        )
        assertEquals(
            Value.BoolV(false),
            lookupH("strand-builtin:List.Any").invoke(listOf(listOfInts(listOf(1L, 2L, 3L)), Value.UnitV), never),
        )
    }

    @Test
    fun `List_All true only when every element matches`() {
        val isPositive = Builtins.ApplyFn { _, args ->
            Value.BoolV((args[0] as Value.IntV).v > 0L)
        }
        val allPositive = listOfInts(listOf(1L, 2L, 3L))
        val withZero = listOfInts(listOf(1L, 0L, 3L))
        assertEquals(Value.BoolV(true), lookupH("strand-builtin:List.All").invoke(listOf(allPositive, Value.UnitV), isPositive))
        assertEquals(Value.BoolV(false), lookupH("strand-builtin:List.All").invoke(listOf(withZero, Value.UnitV), isPositive))
        // Empty list is vacuously true.
        assertEquals(
            Value.BoolV(true),
            lookupH("strand-builtin:List.All").invoke(listOf(Value.SumV("Nil", null), Value.UnitV), isPositive),
        )
    }

    @Test
    fun `List_All short-circuits false on first mismatch`() {
        var calls = 0
        val isPositive = Builtins.ApplyFn { _, args ->
            calls++
            Value.BoolV((args[0] as Value.IntV).v > 0L)
        }
        val input = listOfInts(listOf(1L, 2L, -3L, 4L, 5L))
        val result = lookupH("strand-builtin:List.All").invoke(listOf(input, Value.UnitV), isPositive)
        assertEquals(Value.BoolV(false), result)
        assertEquals(3, calls, "should stop at first negative")
    }
}
