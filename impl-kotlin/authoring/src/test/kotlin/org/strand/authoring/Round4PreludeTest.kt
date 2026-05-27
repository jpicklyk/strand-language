package org.strand.authoring

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the stdlib expansion round 4 prelude entries — the
 * reserved-name shortcuts for Float.* arithmetic + comparisons,
 * Bool.Eq, Bytes.Eq, Path.*, and DateTime.* (excluding ParseIso
 * which returns Option<Int> and stays explicit per the documented
 * exception). List structure ops + reducers (Group 3) and
 * Markdown.Stringify (Group 6) have NO prelude entries — those
 * are polymorphic / agent-typed per the documented exceptions in
 * impl-kotlin/CLAUDE.md.
 *
 * Each test compiles a tiny Layer A program that uses one of the
 * new reserved names and asserts the synthesized ForeignNode + its
 * FunctionType elaborate to the expected canonical JSON shape.
 */
class Round4PreludeTest {

    /** Helper: compile and return the named node's JSON object. */
    private fun nodeOf(text: String, id: String) =
        Authoring.compileToJsonObject(text).jsonObject["nodes"]!!.jsonObject[id]!!.jsonObject

    // ---------- Float arithmetic ----------

    @Test
    fun `fAdd elaborates to Float_Add ForeignNode with Float-Float-Float FNT`() {
        val text = """
            @v=1 root=app
            app APP fAdd [1.0 2.0]
        """.trimIndent()
        val fAdd = nodeOf(text, "fAdd")
        assertEquals("ForeignNode", fAdd["type"]!!.jsonPrimitive.content)
        assertEquals("strand-builtin:Float.Add", fAdd["target"]!!.jsonPrimitive.content)
        assertEquals("fAddT", fAdd["foreignType"]!!.jsonPrimitive.content)
        // Pure — no effects.
        assertTrue(fAdd["effects"] == null || fAdd["effects"]!!.jsonArray.isEmpty())
        val fnt = nodeOf(text, "fAddT")
        assertEquals(listOf("floatT", "floatT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("floatT", fnt["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Float arithmetic family all wire to Float_ targets`() {
        for ((name, target) in listOf(
            "fAdd" to "strand-builtin:Float.Add",
            "fSub" to "strand-builtin:Float.Sub",
            "fMul" to "strand-builtin:Float.Mul",
            "fDiv" to "strand-builtin:Float.Div",
        )) {
            val text = "@v=1 root=a\na APP $name [1.0 2.0]"
            assertEquals(target, nodeOf(text, name)["target"]!!.jsonPrimitive.content)
        }
        // Neg is unary.
        val negText = "@v=1 root=a\na APP fNeg [1.0]"
        val neg = nodeOf(negText, "fNeg")
        assertEquals("strand-builtin:Float.Neg", neg["target"]!!.jsonPrimitive.content)
        val negFnt = nodeOf(negText, "fNegT")
        assertEquals(listOf("floatT"), negFnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("floatT", negFnt["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Float comparisons all return Bool`() {
        for ((name, target) in listOf(
            "fEq" to "strand-builtin:Float.Eq",
            "fLt" to "strand-builtin:Float.Lt",
            "fLe" to "strand-builtin:Float.Le",
            "fGt" to "strand-builtin:Float.Gt",
            "fGe" to "strand-builtin:Float.Ge",
        )) {
            val text = "@v=1 root=a\na APP $name [1.0 2.0]"
            val node = nodeOf(text, name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            val fntId = node["foreignType"]!!.jsonPrimitive.content
            val fnt = nodeOf(text, fntId)
            assertEquals(listOf("floatT", "floatT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("boolT", fnt["result"]!!.jsonPrimitive.content)
        }
    }

    // ---------- Equality variants ----------

    @Test
    fun `eqBool wires to Bool_Eq with Bool-Bool-Bool FNT`() {
        val text = "@v=1 root=a\na APP eqBool [true false]"
        val node = nodeOf(text, "eqBool")
        assertEquals("strand-builtin:Bool.Eq", node["target"]!!.jsonPrimitive.content)
        val fnt = nodeOf(text, "eqBoolT")
        assertEquals(listOf("boolT", "boolT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("boolT", fnt["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `eqBytes wires to Bytes_Eq with Bytes-Bytes-Bool FNT`() {
        val text = """
            @v=1 root=a
            empty BYT ""
            a APP eqBytes [empty empty]
        """.trimIndent()
        val node = nodeOf(text, "eqBytes")
        assertEquals("strand-builtin:Bytes.Eq", node["target"]!!.jsonPrimitive.content)
        val fnt = nodeOf(text, "eqBytesT")
        assertEquals(listOf("bytesT", "bytesT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("boolT", fnt["result"]!!.jsonPrimitive.content)
    }

    // ---------- Path operations ----------

    @Test
    fun `pathJoin is String-String-String`() {
        val text = """
            @v=1 root=a
            foo STR "foo"
            bar STR "bar"
            a APP pathJoin [foo bar]
        """.trimIndent()
        val node = nodeOf(text, "pathJoin")
        assertEquals("strand-builtin:Path.Join", node["target"]!!.jsonPrimitive.content)
        val fnt = nodeOf(text, "pathJoinT")
        assertEquals(listOf("stringT", "stringT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("stringT", fnt["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Unary Path builtins are pure String-String`() {
        for ((name, target) in listOf(
            "pathBase" to "strand-builtin:Path.Basename",
            "pathDir" to "strand-builtin:Path.Dirname",
            "pathExt" to "strand-builtin:Path.Extension",
            "pathNorm" to "strand-builtin:Path.Normalize",
        )) {
            val text = """
                @v=1 root=a
                p STR "foo/bar"
                a APP $name [p]
            """.trimIndent()
            val node = nodeOf(text, name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            // Pure — no effects.
            assertTrue(node["effects"] == null || node["effects"]!!.jsonArray.isEmpty())
            val fntId = node["foreignType"]!!.jsonPrimitive.content
            val fnt = nodeOf(text, fntId)
            assertEquals(listOf("stringT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("stringT", fnt["result"]!!.jsonPrimitive.content)
        }
    }

    // ---------- DateTime ----------

    @Test
    fun `dtFormatIso is Int-String`() {
        val text = "@v=1 root=a\na APP dtFormatIso [0]"
        val node = nodeOf(text, "dtFormatIso")
        assertEquals("strand-builtin:DateTime.FormatIso", node["target"]!!.jsonPrimitive.content)
        val fnt = nodeOf(text, "dtFormatIsoT")
        assertEquals(listOf("intT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("stringT", fnt["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `DateTime component extractors are all Int-Int`() {
        for ((name, target) in listOf(
            "dtYear" to "strand-builtin:DateTime.Year",
            "dtMonth" to "strand-builtin:DateTime.Month",
            "dtDay" to "strand-builtin:DateTime.Day",
            "dtHour" to "strand-builtin:DateTime.Hour",
            "dtMinute" to "strand-builtin:DateTime.Minute",
            "dtSecond" to "strand-builtin:DateTime.Second",
        )) {
            val text = "@v=1 root=a\na APP $name [0]"
            val node = nodeOf(text, name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            val fntId = node["foreignType"]!!.jsonPrimitive.content
            val fnt = nodeOf(text, fntId)
            assertEquals(listOf("intT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("intT", fnt["result"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `DateTime arithmetic ops are all Int-Int-Int`() {
        for ((name, target) in listOf(
            "dtAddDays" to "strand-builtin:DateTime.AddDays",
            "dtAddHours" to "strand-builtin:DateTime.AddHours",
            "dtAddMinutes" to "strand-builtin:DateTime.AddMinutes",
            "dtAddSeconds" to "strand-builtin:DateTime.AddSeconds",
        )) {
            val text = "@v=1 root=a\na APP $name [0 1]"
            val node = nodeOf(text, name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            val fntId = node["foreignType"]!!.jsonPrimitive.content
            val fnt = nodeOf(text, fntId)
            assertEquals(listOf("intT", "intT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("intT", fnt["result"]!!.jsonPrimitive.content)
        }
    }

}
