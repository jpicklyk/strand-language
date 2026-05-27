package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 4 — List structural ops (Range/Zip/Unzip/
 * Distinct), Int-specialized reducers (Sum/Product/Min/Max), and
 * the higher-order List.Sort. All polymorphic (no prelude entries —
 * agents declare explicit FNT + FRN at the use site).
 */
class BuiltinsListRound4Test {

    private fun lookup(name: String) = Builtins.lookup(name)!!
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

    // ---------- List.Range ----------

    @Test
    fun `List_Range inclusive start, exclusive end`() {
        val fn = lookup("strand-builtin:List.Range")
        val result = fn.invoke(listOf(Value.IntV(2L), Value.IntV(6L)))
        assertEquals(listOf(2L, 3L, 4L, 5L), collectInts(result))
    }

    @Test
    fun `List_Range empty when start equals end`() {
        val fn = lookup("strand-builtin:List.Range")
        assertEquals(Value.SumV("Nil", null), fn.invoke(listOf(Value.IntV(5L), Value.IntV(5L))))
    }

    @Test
    fun `List_Range empty when start exceeds end`() {
        val fn = lookup("strand-builtin:List.Range")
        assertEquals(Value.SumV("Nil", null), fn.invoke(listOf(Value.IntV(10L), Value.IntV(5L))))
    }

    @Test
    fun `List_Range with negative bounds`() {
        val fn = lookup("strand-builtin:List.Range")
        assertEquals(listOf(-2L, -1L, 0L, 1L),
            collectInts(fn.invoke(listOf(Value.IntV(-2L), Value.IntV(2L)))))
    }

    // ---------- List.Zip + Unzip ----------

    @Test
    fun `List_Zip pairs elementwise, stops at shorter list`() {
        val fn = lookup("strand-builtin:List.Zip")
        val a = listOfInts(listOf(1L, 2L, 3L))
        val b = listOfInts(listOf(10L, 20L))
        val result = fn.invoke(listOf(a, b))
        // Should produce [{first=1, second=10}, {first=2, second=20}]
        val pairs = mutableListOf<Pair<Long, Long>>()
        var cur = result
        while (cur is Value.SumV && cur.case == "Cons") {
            val payload = cur.payload as Value.ProductV
            val pair = payload.fields.getValue("head") as Value.ProductV
            pairs += (pair.fields.getValue("first") as Value.IntV).v to
                     (pair.fields.getValue("second") as Value.IntV).v
            cur = payload.fields.getValue("tail")
        }
        assertEquals(listOf(1L to 10L, 2L to 20L), pairs)
    }

    @Test
    fun `List_Zip with empty input yields empty`() {
        val fn = lookup("strand-builtin:List.Zip")
        val a = listOfInts(emptyList())
        val b = listOfInts(listOf(1L, 2L))
        assertEquals(Value.SumV("Nil", null), fn.invoke(listOf(a, b)))
    }

    @Test
    fun `List_Unzip inverts List_Zip`() {
        val zip = lookup("strand-builtin:List.Zip")
        val unzip = lookup("strand-builtin:List.Unzip")
        val a = listOfInts(listOf(1L, 2L, 3L))
        val b = listOfInts(listOf(10L, 20L, 30L))
        val pairs = zip.invoke(listOf(a, b))
        val result = unzip.invoke(listOf(pairs)) as Value.ProductV
        assertEquals(listOf(1L, 2L, 3L), collectInts(result.fields.getValue("first")))
        assertEquals(listOf(10L, 20L, 30L), collectInts(result.fields.getValue("second")))
    }

    @Test
    fun `List_Unzip of empty yields two empty lists`() {
        val unzip = lookup("strand-builtin:List.Unzip")
        val result = unzip.invoke(listOf(Value.SumV("Nil", null))) as Value.ProductV
        assertEquals(Value.SumV("Nil", null), result.fields.getValue("first"))
        assertEquals(Value.SumV("Nil", null), result.fields.getValue("second"))
    }

    // ---------- List.Distinct ----------

    @Test
    fun `List_Distinct removes duplicates preserving first occurrence`() {
        val fn = lookup("strand-builtin:List.Distinct")
        val input = listOfInts(listOf(3L, 1L, 4L, 1L, 5L, 9L, 2L, 6L, 5L, 3L))
        assertEquals(listOf(3L, 1L, 4L, 5L, 9L, 2L, 6L), collectInts(fn.invoke(listOf(input))))
    }

    @Test
    fun `List_Distinct of empty is empty`() {
        val fn = lookup("strand-builtin:List.Distinct")
        assertEquals(Value.SumV("Nil", null), fn.invoke(listOf(Value.SumV("Nil", null))))
    }

    @Test
    fun `List_Distinct uses structural value equality`() {
        val fn = lookup("strand-builtin:List.Distinct")
        // Build a list of String values with duplicates.
        var cur: Value = Value.SumV("Nil", null)
        for (s in listOf("c", "b", "a", "b", "a").reversed()) {
            cur = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to Value.StringV(s), "tail" to cur,
            )))
        }
        val result = fn.invoke(listOf(cur))
        val out = mutableListOf<String>()
        var r = result
        while (r is Value.SumV && r.case == "Cons") {
            val payload = r.payload as Value.ProductV
            out += (payload.fields.getValue("head") as Value.StringV).v
            r = payload.fields.getValue("tail")
        }
        assertEquals(listOf("c", "b", "a"), out)
    }

    // ---------- List.Sum / Product ----------

    @Test
    fun `List_Sum and List_Product of empty are identity values`() {
        val sum = lookup("strand-builtin:List.Sum")
        val product = lookup("strand-builtin:List.Product")
        assertEquals(Value.IntV(0L), sum.invoke(listOf(Value.SumV("Nil", null))))
        assertEquals(Value.IntV(1L), product.invoke(listOf(Value.SumV("Nil", null))))
    }

    @Test
    fun `List_Sum and List_Product over nonempty`() {
        val sum = lookup("strand-builtin:List.Sum")
        val product = lookup("strand-builtin:List.Product")
        val input = listOfInts(listOf(1L, 2L, 3L, 4L))
        assertEquals(Value.IntV(10L), sum.invoke(listOf(input)))
        assertEquals(Value.IntV(24L), product.invoke(listOf(input)))
    }

    // ---------- List.Min / Max ----------

    @Test
    fun `List_Min and List_Max of empty return None`() {
        val min = lookup("strand-builtin:List.Min")
        val max = lookup("strand-builtin:List.Max")
        assertEquals(Value.SumV("None", null), min.invoke(listOf(Value.SumV("Nil", null))))
        assertEquals(Value.SumV("None", null), max.invoke(listOf(Value.SumV("Nil", null))))
    }

    @Test
    fun `List_Min and List_Max of nonempty return Some`() {
        val min = lookup("strand-builtin:List.Min")
        val max = lookup("strand-builtin:List.Max")
        val input = listOfInts(listOf(3L, 1L, 4L, 1L, 5L, 9L, 2L, 6L))
        assertEquals(Value.SumV("Some", Value.IntV(1L)), min.invoke(listOf(input)))
        assertEquals(Value.SumV("Some", Value.IntV(9L)), max.invoke(listOf(input)))
    }

    @Test
    fun `List_Min and Max with negative numbers`() {
        val min = lookup("strand-builtin:List.Min")
        val max = lookup("strand-builtin:List.Max")
        val input = listOfInts(listOf(-3L, -1L, -4L, -1L, -5L))
        assertEquals(Value.SumV("Some", Value.IntV(-5L)), min.invoke(listOf(input)))
        assertEquals(Value.SumV("Some", Value.IntV(-1L)), max.invoke(listOf(input)))
    }

    // ---------- List.Sort (higher-order) ----------

    @Test
    fun `List_Sort ascending via Int lt comparator`() {
        val sort = lookupH("strand-builtin:List.Sort")
        val ascending = Builtins.ApplyFn { _, args ->
            Value.BoolV((args[0] as Value.IntV).v < (args[1] as Value.IntV).v)
        }
        val input = listOfInts(listOf(3L, 1L, 4L, 1L, 5L, 9L, 2L, 6L))
        val result = sort.invoke(listOf(input, Value.UnitV), ascending)
        assertEquals(listOf(1L, 1L, 2L, 3L, 4L, 5L, 6L, 9L), collectInts(result))
    }

    @Test
    fun `List_Sort descending via gt comparator`() {
        val sort = lookupH("strand-builtin:List.Sort")
        val descending = Builtins.ApplyFn { _, args ->
            Value.BoolV((args[0] as Value.IntV).v > (args[1] as Value.IntV).v)
        }
        val input = listOfInts(listOf(3L, 1L, 4L, 1L, 5L, 9L, 2L, 6L))
        val result = sort.invoke(listOf(input, Value.UnitV), descending)
        assertEquals(listOf(9L, 6L, 5L, 4L, 3L, 2L, 1L, 1L), collectInts(result))
    }

    @Test
    fun `List_Sort empty list returns empty`() {
        val sort = lookupH("strand-builtin:List.Sort")
        val anyCmp = Builtins.ApplyFn { _, _ -> Value.BoolV(true) }
        val result = sort.invoke(listOf(Value.SumV("Nil", null), Value.UnitV), anyCmp)
        assertEquals(Value.SumV("Nil", null), result)
    }

    @Test
    fun `List_Sort singleton returns same element`() {
        val sort = lookupH("strand-builtin:List.Sort")
        val cmp = Builtins.ApplyFn { _, args ->
            Value.BoolV((args[0] as Value.IntV).v < (args[1] as Value.IntV).v)
        }
        val input = listOfInts(listOf(42L))
        val result = sort.invoke(listOf(input, Value.UnitV), cmp)
        assertEquals(listOf(42L), collectInts(result))
    }

    @Test
    fun `List_Sort stable on equal elements`() {
        // Comparator that orders by abs value — multiple elements
        // with same abs value should preserve original order.
        val sort = lookupH("strand-builtin:List.Sort")
        val byAbs = Builtins.ApplyFn { _, args ->
            val a = kotlin.math.abs((args[0] as Value.IntV).v)
            val b = kotlin.math.abs((args[1] as Value.IntV).v)
            Value.BoolV(a < b)
        }
        // Input: 3, -3, 1, -1 — by abs: should produce 1, -1, 3, -3
        // (sign preserved within equal-abs runs).
        val input = listOfInts(listOf(3L, -3L, 1L, -1L))
        val result = sort.invoke(listOf(input, Value.UnitV), byAbs)
        assertEquals(listOf(1L, -1L, 3L, -3L), collectInts(result))
    }
}
