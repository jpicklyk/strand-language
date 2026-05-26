package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Stdlib expansion round 3 phase 2 — Regex.* builtins. All pure.
 * Pattern-compile errors throw [IoFailure(kind="regex-compile")]
 * which the interpreter translates to a structured
 * [InterpretError.IoFailure] carrying the call-site NodeId.
 */
class BuiltinsRegexTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

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

    // ---------- Regex.Match ----------

    @Test
    fun `Regex_Match returns Some of the first match`() {
        val fn = lookup("strand-builtin:Regex.Match")
        assertEquals(
            Value.SumV("Some", Value.StringV("123")),
            fn.invoke(listOf(Value.StringV("\\d+"), Value.StringV("abc 123 def 456"))),
        )
    }

    @Test
    fun `Regex_Match returns None when no match`() {
        val fn = lookup("strand-builtin:Regex.Match")
        assertEquals(
            Value.SumV("None", null),
            fn.invoke(listOf(Value.StringV("\\d+"), Value.StringV("no digits here"))),
        )
    }

    @Test
    fun `Regex_Match with anchors`() {
        val fn = lookup("strand-builtin:Regex.Match")
        assertEquals(
            Value.SumV("Some", Value.StringV("hello")),
            fn.invoke(listOf(Value.StringV("^hello"), Value.StringV("hello world"))),
        )
        assertEquals(
            Value.SumV("None", null),
            fn.invoke(listOf(Value.StringV("^hello"), Value.StringV("say hello"))),
        )
    }

    // ---------- Regex.FindAll ----------

    @Test
    fun `Regex_FindAll returns every non-overlapping match`() {
        val fn = lookup("strand-builtin:Regex.FindAll")
        val result = fn.invoke(listOf(Value.StringV("\\d+"), Value.StringV("abc 123 def 456 ghi 789")))
        assertEquals(listOf("123", "456", "789"), collectStrings(result))
    }

    @Test
    fun `Regex_FindAll on no-match returns Nil`() {
        val fn = lookup("strand-builtin:Regex.FindAll")
        assertEquals(
            Value.SumV("Nil", null),
            fn.invoke(listOf(Value.StringV("\\d+"), Value.StringV("no digits"))),
        )
    }

    // ---------- Regex.Replace ----------

    @Test
    fun `Regex_Replace replaces every non-overlapping match`() {
        val fn = lookup("strand-builtin:Regex.Replace")
        assertEquals(
            Value.StringV("abc N def N ghi N"),
            fn.invoke(listOf(Value.StringV("\\d+"), Value.StringV("abc 123 def 456 ghi 789"), Value.StringV("N"))),
        )
    }

    @Test
    fun `Regex_Replace supports group-reference replacements`() {
        // $1 inserts the first captured group's matched substring.
        val fn = lookup("strand-builtin:Regex.Replace")
        assertEquals(
            Value.StringV("[abc] [def]"),
            fn.invoke(listOf(
                Value.StringV("(\\w+)"),
                Value.StringV("abc def"),
                Value.StringV("[$1]"),
            )),
        )
    }

    // ---------- Regex.Split ----------

    @Test
    fun `Regex_Split splits on every match`() {
        val fn = lookup("strand-builtin:Regex.Split")
        val result = fn.invoke(listOf(Value.StringV("\\s+"), Value.StringV("hello   world\tfrom\nstrand")))
        assertEquals(listOf("hello", "world", "from", "strand"), collectStrings(result))
    }

    @Test
    fun `Regex_Split with adjacent matches yields empty entries`() {
        val fn = lookup("strand-builtin:Regex.Split")
        val result = fn.invoke(listOf(Value.StringV(","), Value.StringV("a,,b")))
        assertEquals(listOf("a", "", "b"), collectStrings(result))
    }

    // ---------- Error paths ----------

    @Test
    fun `Regex builtins throw IoFailure on pattern compile failure`() {
        for (name in listOf(
            "strand-builtin:Regex.Match",
            "strand-builtin:Regex.FindAll",
            "strand-builtin:Regex.Split",
        )) {
            val fn = lookup(name)
            val ex = assertThrows<IoFailure> {
                fn.invoke(listOf(Value.StringV("[invalid"), Value.StringV("input")))
            }
            assertEquals("regex-compile", ex.kind)
        }
        val replace = lookup("strand-builtin:Regex.Replace")
        val ex = assertThrows<IoFailure> {
            replace.invoke(listOf(Value.StringV("[invalid"), Value.StringV("input"), Value.StringV("repl")))
        }
        assertEquals("regex-compile", ex.kind)
    }
}
