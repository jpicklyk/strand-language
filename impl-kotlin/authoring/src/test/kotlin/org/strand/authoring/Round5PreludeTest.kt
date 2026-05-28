package org.strand.authoring

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the stdlib expansion round 5 prelude entries — the five
 * preludable shortcuts: padLeft, padRight, strRepeat (Group A), urlEncode
 * (Group E), gzip (Group F). Every other Round 5 builtin is polymorphic
 * / Option-returning / agent-typed and stays out of the prelude per the
 * documented exceptions.
 */
class Round5PreludeTest {

    private fun nodeOf(text: String, id: String) =
        Authoring.compileToJsonObject(text).jsonObject["nodes"]!!.jsonObject[id]!!.jsonObject

    @Test
    fun `padLeft elaborates to String_PadLeft with String-Int-String-String FNT`() {
        val text = """
            @v=1 root=app
            s STR "42"
            pad STR "0"
            app APP padLeft [s 4 pad]
        """.trimIndent()
        val node = nodeOf(text, "padLeft")
        assertEquals("ForeignNode", node["type"]!!.jsonPrimitive.content)
        assertEquals("strand-builtin:String.PadLeft", node["target"]!!.jsonPrimitive.content)
        assertEquals("padLeftT", node["foreignType"]!!.jsonPrimitive.content)
        assertTrue(node["effects"] == null || node["effects"]!!.jsonArray.isEmpty())
        val fnt = nodeOf(text, "padLeftT")
        assertEquals(listOf("stringT", "intT", "stringT"),
            fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("stringT", fnt["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `padRight wires to String_PadRight`() {
        val text = """
            @v=1 root=app
            s STR "42"
            pad STR " "
            app APP padRight [s 4 pad]
        """.trimIndent()
        assertEquals("strand-builtin:String.PadRight",
            nodeOf(text, "padRight")["target"]!!.jsonPrimitive.content)
    }

    @Test
    fun `strRepeat is String-Int-String`() {
        val text = """
            @v=1 root=app
            s STR "ab"
            app APP strRepeat [s 3]
        """.trimIndent()
        val node = nodeOf(text, "strRepeat")
        assertEquals("strand-builtin:String.Repeat", node["target"]!!.jsonPrimitive.content)
        val fnt = nodeOf(text, "strRepeatT")
        assertEquals(listOf("stringT", "intT"),
            fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("stringT", fnt["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `urlEncode is pure String-String`() {
        val text = """
            @v=1 root=app
            s STR "hello world"
            app APP urlEncode [s]
        """.trimIndent()
        val node = nodeOf(text, "urlEncode")
        assertEquals("strand-builtin:Url.QueryEncode", node["target"]!!.jsonPrimitive.content)
        // Pure — no effects.
        assertTrue(node["effects"] == null || node["effects"]!!.jsonArray.isEmpty())
        val fnt = nodeOf(text, "urlEncodeT")
        assertEquals(listOf("stringT"),
            fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("stringT", fnt["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gzip is pure Bytes-Bytes (matches Hash family shape)`() {
        val text = """
            @v=1 root=app
            empty BYT ""
            app APP gzip [empty]
        """.trimIndent()
        val node = nodeOf(text, "gzip")
        assertEquals("strand-builtin:Compress.Gzip", node["target"]!!.jsonPrimitive.content)
        // Pure.
        assertTrue(node["effects"] == null || node["effects"]!!.jsonArray.isEmpty())
        val fnt = nodeOf(text, "gzipT")
        assertEquals(listOf("bytesT"),
            fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("bytesT", fnt["result"]!!.jsonPrimitive.content)
    }
}
