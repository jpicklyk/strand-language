package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Stdlib expansion round 5, Group A — String.Format / PadLeft /
 * PadRight / Repeat / Lines / Chars / CharAt. All pure.
 */
class BuiltinsStringFormatTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    private fun listOfStrings(strs: List<String>): Value {
        var cur: Value = Value.SumV("Nil", null)
        for (s in strs.reversed()) {
            cur = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to Value.StringV(s), "tail" to cur,
            )))
        }
        return cur
    }

    private fun collectStrings(v: Value): List<String> {
        val out = mutableListOf<String>()
        var cur = v
        while (cur is Value.SumV && cur.case == "Cons") {
            val payload = cur.payload as Value.ProductV
            out += (payload.fields.getValue("head") as Value.StringV).v
            cur = payload.fields.getValue("tail")
        }
        return out
    }

    // ---------- String.Format ----------

    @Test
    fun `String_Format substitutes positional placeholders`() {
        val fn = lookup("strand-builtin:String.Format")
        val result = fn.invoke(listOf(
            Value.StringV("hello {0}, you are {1} years old"),
            listOfStrings(listOf("Alice", "30")),
        ))
        assertEquals(Value.StringV("hello Alice, you are 30 years old"), result)
    }

    @Test
    fun `String_Format with empty args list leaves placeholders verbatim`() {
        val fn = lookup("strand-builtin:String.Format")
        val result = fn.invoke(listOf(
            Value.StringV("hello {0}"),
            listOfStrings(emptyList()),
        ))
        assertEquals(Value.StringV("hello {0}"), result)
    }

    @Test
    fun `String_Format with out-of-range index leaves placeholder verbatim`() {
        val fn = lookup("strand-builtin:String.Format")
        val result = fn.invoke(listOf(
            Value.StringV("{0} and {5}"),
            listOfStrings(listOf("first", "second")),
        ))
        assertEquals(Value.StringV("first and {5}"), result)
    }

    @Test
    fun `String_Format with non-numeric placeholder is ignored`() {
        val fn = lookup("strand-builtin:String.Format")
        val result = fn.invoke(listOf(
            Value.StringV("{abc} and {0}"),
            listOfStrings(listOf("first")),
        ))
        assertEquals(Value.StringV("{abc} and first"), result)
    }

    @Test
    fun `String_Format same index used multiple times`() {
        val fn = lookup("strand-builtin:String.Format")
        val result = fn.invoke(listOf(
            Value.StringV("{0} {0} {0}"),
            listOfStrings(listOf("ha")),
        ))
        assertEquals(Value.StringV("ha ha ha"), result)
    }

    // ---------- String.PadLeft / PadRight ----------

    @Test
    fun `String_PadLeft pads to target length`() {
        val fn = lookup("strand-builtin:String.PadLeft")
        assertEquals(Value.StringV("0042"),
            fn.invoke(listOf(Value.StringV("42"), Value.IntV(4L), Value.StringV("0"))))
        assertEquals(Value.StringV("--hi"),
            fn.invoke(listOf(Value.StringV("hi"), Value.IntV(4L), Value.StringV("-"))))
    }

    @Test
    fun `String_PadLeft when already long enough returns unchanged`() {
        val fn = lookup("strand-builtin:String.PadLeft")
        assertEquals(Value.StringV("hello"),
            fn.invoke(listOf(Value.StringV("hello"), Value.IntV(3L), Value.StringV("0"))))
        assertEquals(Value.StringV("hello"),
            fn.invoke(listOf(Value.StringV("hello"), Value.IntV(5L), Value.StringV("0"))))
    }

    @Test
    fun `String_PadLeft with multi-char pad truncates if needed`() {
        val fn = lookup("strand-builtin:String.PadLeft")
        // pad="ab", need 5 chars left of "x" → "abab" then truncate to 4: "abab" + "x" = "ababx"
        assertEquals(Value.StringV("ababx"),
            fn.invoke(listOf(Value.StringV("x"), Value.IntV(5L), Value.StringV("ab"))))
    }

    @Test
    fun `String_PadLeft empty pad is rejected`() {
        val fn = lookup("strand-builtin:String.PadLeft")
        assertThrows<IllegalArgumentException> {
            fn.invoke(listOf(Value.StringV("x"), Value.IntV(5L), Value.StringV("")))
        }
    }

    @Test
    fun `String_PadRight pads on the right`() {
        val fn = lookup("strand-builtin:String.PadRight")
        assertEquals(Value.StringV("42  "),
            fn.invoke(listOf(Value.StringV("42"), Value.IntV(4L), Value.StringV(" "))))
        assertEquals(Value.StringV("hi--"),
            fn.invoke(listOf(Value.StringV("hi"), Value.IntV(4L), Value.StringV("-"))))
    }

    // ---------- String.Repeat ----------

    @Test
    fun `String_Repeat with positive n`() {
        val fn = lookup("strand-builtin:String.Repeat")
        assertEquals(Value.StringV("abcabcabc"),
            fn.invoke(listOf(Value.StringV("abc"), Value.IntV(3L))))
        assertEquals(Value.StringV("---"),
            fn.invoke(listOf(Value.StringV("-"), Value.IntV(3L))))
    }

    @Test
    fun `String_Repeat with zero is empty string`() {
        val fn = lookup("strand-builtin:String.Repeat")
        assertEquals(Value.StringV(""),
            fn.invoke(listOf(Value.StringV("abc"), Value.IntV(0L))))
    }

    @Test
    fun `String_Repeat with negative n is rejected`() {
        val fn = lookup("strand-builtin:String.Repeat")
        assertThrows<IllegalArgumentException> {
            fn.invoke(listOf(Value.StringV("x"), Value.IntV(-1L)))
        }
    }

    // ---------- String.Lines ----------

    @Test
    fun `String_Lines splits on newline`() {
        val fn = lookup("strand-builtin:String.Lines")
        assertEquals(listOf("a", "b", "c"),
            collectStrings(fn.invoke(listOf(Value.StringV("a\nb\nc")))))
    }

    @Test
    fun `String_Lines empty string yields one empty entry`() {
        val fn = lookup("strand-builtin:String.Lines")
        // Kotlin's split on empty string returns one empty entry.
        assertEquals(listOf(""), collectStrings(fn.invoke(listOf(Value.StringV("")))))
    }

    @Test
    fun `String_Lines trailing newline yields trailing empty entry`() {
        val fn = lookup("strand-builtin:String.Lines")
        assertEquals(listOf("a", "b", ""),
            collectStrings(fn.invoke(listOf(Value.StringV("a\nb\n")))))
    }

    // ---------- String.Chars ----------

    @Test
    fun `String_Chars splits into single chars`() {
        val fn = lookup("strand-builtin:String.Chars")
        assertEquals(listOf("h", "e", "l", "l", "o"),
            collectStrings(fn.invoke(listOf(Value.StringV("hello")))))
    }

    @Test
    fun `String_Chars empty string yields empty list`() {
        val fn = lookup("strand-builtin:String.Chars")
        assertEquals(Value.SumV("Nil", null), fn.invoke(listOf(Value.StringV(""))))
    }

    // ---------- String.CharAt ----------

    @Test
    fun `String_CharAt in range returns Some single char`() {
        val fn = lookup("strand-builtin:String.CharAt")
        assertEquals(Value.SumV("Some", Value.StringV("e")),
            fn.invoke(listOf(Value.StringV("hello"), Value.IntV(1L))))
        assertEquals(Value.SumV("Some", Value.StringV("o")),
            fn.invoke(listOf(Value.StringV("hello"), Value.IntV(4L))))
    }

    @Test
    fun `String_CharAt out of range returns None`() {
        val fn = lookup("strand-builtin:String.CharAt")
        assertEquals(Value.SumV("None", null),
            fn.invoke(listOf(Value.StringV("hello"), Value.IntV(5L))))
        assertEquals(Value.SumV("None", null),
            fn.invoke(listOf(Value.StringV("hello"), Value.IntV(-1L))))
        assertEquals(Value.SumV("None", null),
            fn.invoke(listOf(Value.StringV(""), Value.IntV(0L))))
    }
}
