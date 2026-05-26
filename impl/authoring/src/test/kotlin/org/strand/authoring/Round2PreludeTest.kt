package org.strand.authoring

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the stdlib expansion round 2 prelude entries — the
 * reserved-name shortcuts for Math.* / Hash.* / Random.* /
 * Bytes.FormatHex that ship alongside the underlying builtins.
 *
 * Each test compiles a tiny Layer A program that uses one of the
 * new reserved names and asserts the synthesized ForeignNode + its
 * FunctionType + (for effectful entries) effects list elaborate to
 * the expected canonical JSON shape.
 */
class Round2PreludeTest {

    /** Helper: compile and return the named node's JSON object. */
    private fun nodeOf(text: String, id: String) =
        Authoring.compileToJsonObject(text).jsonObject["nodes"]!!.jsonObject[id]!!.jsonObject

    // ---------- Math.* ----------

    @Test
    fun `Math_Sqrt via sqrt reserved name elaborates to Math_Sqrt ForeignNode + Float-Float FNT`() {
        val text = """
            @v=1 root=app
            app APP sqrt [4.0]
        """.trimIndent()
        val sqrt = nodeOf(text, "sqrt")
        assertEquals("ForeignNode", sqrt["type"]!!.jsonPrimitive.content)
        assertEquals("strand-builtin:Math.Sqrt", sqrt["target"]!!.jsonPrimitive.content)
        assertEquals("sqrtT", sqrt["foreignType"]!!.jsonPrimitive.content)
        val fnt = nodeOf(text, "sqrtT")
        assertEquals("FunctionType", fnt["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("floatT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("floatT", fnt["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Math_Min and Math_Max share Int-Int-Int shape with their own names`() {
        // Both share the (Int,Int)->Int FunctionType shape; reserved
        // names stay distinct for readability (mirrors addT/subT/etc.).
        val text = """
            @v=1 root=app
            app APP min [1 (APP max [2 3])]
        """.trimIndent()
        val minNode = nodeOf(text, "min")
        val maxNode = nodeOf(text, "max")
        assertEquals("strand-builtin:Math.Min", minNode["target"]!!.jsonPrimitive.content)
        assertEquals("strand-builtin:Math.Max", maxNode["target"]!!.jsonPrimitive.content)
        assertEquals("minT", minNode["foreignType"]!!.jsonPrimitive.content)
        assertEquals("maxT", maxNode["foreignType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mmod reserved name targets Math_Mod, distinct from existing mod which targets Int_Mod`() {
        // The two are *different* functions: `mod` is JVM `%`
        // (sign-of-dividend); `mmod` is true mathematical modulo
        // (always non-negative for positive divisors). Both reserved
        // names ship so agents can pick explicitly.
        val mathModText = """
            @v=1 root=app
            app APP mmod [(APP sub [0 1]) 5]
        """.trimIndent()
        assertEquals(
            "strand-builtin:Math.Mod",
            nodeOf(mathModText, "mmod")["target"]!!.jsonPrimitive.content,
        )
        val intModText = """
            @v=1 root=app
            app APP mod [(APP sub [0 1]) 5]
        """.trimIndent()
        assertEquals(
            "strand-builtin:Int.Mod",
            nodeOf(intModText, "mod")["target"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `floor ceil round take Float and return Int`() {
        val text = """
            @v=1 root=app
            app APP floor [3.7]
        """.trimIndent()
        val fnt = nodeOf(text, "floorT")
        assertEquals(listOf("floatT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("intT", fnt["result"]!!.jsonPrimitive.content)
        // Ceil and Round share the same shape — confirm separate names + targets.
        assertEquals(
            "strand-builtin:Math.Ceil",
            nodeOf("@v=1 root=a\na APP ceil [3.2]", "ceil")["target"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "strand-builtin:Math.Round",
            nodeOf("@v=1 root=a\na APP round [3.5]", "round")["target"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `toFloat and toIntTrunc coercions wire to Float_FromInt and Int_FromFloatTrunc`() {
        val toFloat = nodeOf("@v=1 root=a\na APP toFloat [7]", "toFloat")
        assertEquals("strand-builtin:Float.FromInt", toFloat["target"]!!.jsonPrimitive.content)
        val toIntTrunc = nodeOf("@v=1 root=a\na APP toIntTrunc [3.9]", "toIntTrunc")
        assertEquals("strand-builtin:Int.FromFloatTrunc", toIntTrunc["target"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Math trig and log are all Float-Float`() {
        for ((name, target) in listOf(
            "ln" to "strand-builtin:Math.Log",
            "exp" to "strand-builtin:Math.Exp",
            "sin" to "strand-builtin:Math.Sin",
            "cos" to "strand-builtin:Math.Cos",
            "tan" to "strand-builtin:Math.Tan",
        )) {
            val node = nodeOf("@v=1 root=a\na APP $name [0.0]", name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            val fntId = node["foreignType"]!!.jsonPrimitive.content
            val fnt = nodeOf("@v=1 root=a\na APP $name [0.0]", fntId)
            assertEquals(listOf("floatT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("floatT", fnt["result"]!!.jsonPrimitive.content)
        }
    }

    // ---------- Hash.* ----------

    @Test
    fun `Hash builtins are pure Bytes-Bytes`() {
        for ((name, target) in listOf(
            "blake3" to "strand-builtin:Hash.Blake3",
            "sha256" to "strand-builtin:Hash.Sha256",
            "md5" to "strand-builtin:Hash.Md5",
        )) {
            val text = "@v=1 root=a\nempty BYT \"\"\na APP $name [empty]"
            val node = nodeOf(text, name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            // Pure: no `effects` field on the ForeignNode.
            assertTrue(node["effects"] == null || node["effects"]!!.jsonArray.isEmpty()) {
                "$name should have no effects, got ${node["effects"]}"
            }
            val fntId = node["foreignType"]!!.jsonPrimitive.content
            val fnt = nodeOf(text, fntId)
            assertEquals(listOf("bytesT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("bytesT", fnt["result"]!!.jsonPrimitive.content)
        }
    }

    // ---------- Random.* + cryptoFx ----------

    @Test
    fun `Random builtins declare cryptoFx effect via the prelude`() {
        for ((name, target, params, resultType) in listOf(
            Quad("randInt", "strand-builtin:Random.Int", listOf("intT", "intT"), "intT"),
            Quad("randFloat", "strand-builtin:Random.Float", emptyList(), "floatT"),
            Quad("randBytes", "strand-builtin:Random.Bytes", listOf("intT"), "bytesT"),
        )) {
            val args = if (params.isEmpty()) "[]" else if (name == "randInt") "[0 100]" else "[32]"
            val text = "@v=1 root=a\na APP $name $args"
            val node = nodeOf(text, name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            // Effects list contains the prelude's cryptoFx — the E-024
            // Crypto.RandomBytes EffectCategory.
            assertEquals(
                listOf("cryptoFx"),
                node["effects"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            val fnt = nodeOf(text, node["foreignType"]!!.jsonPrimitive.content)
            assertEquals(params, fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(resultType, fnt["result"]!!.jsonPrimitive.content)
            // The cryptoFx EffectCategory itself is also in the synthesized prelude.
            val crypto = nodeOf(text, "cryptoFx")
            assertEquals("EffectCategory", crypto["type"]!!.jsonPrimitive.content)
            assertEquals("Crypto.RandomBytes", crypto["categoryName"]!!.jsonPrimitive.content)
        }
    }

    // ---------- Bytes.FormatHex ----------

    @Test
    fun `hexOf wires to Bytes_FormatHex with Bytes-String type`() {
        val text = "@v=1 root=a\nempty BYT \"\"\na APP hexOf [empty]"
        val node = nodeOf(text, "hexOf")
        assertEquals("strand-builtin:Bytes.FormatHex", node["target"]!!.jsonPrimitive.content)
        val fnt = nodeOf(text, "hexOfT")
        assertEquals(listOf("bytesT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("stringT", fnt["result"]!!.jsonPrimitive.content)
    }

    /** Quad helper for the Random table. */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
