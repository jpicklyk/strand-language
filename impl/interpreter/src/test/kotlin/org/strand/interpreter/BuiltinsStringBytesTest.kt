package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Layer 4 step 2 — direct unit tests of String.* and Bytes.* stdlib
 * builtins. All pure (no IO), so no resource cleanup needed.
 */
class BuiltinsStringBytesTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    /** Walk a SumV-encoded Cons/Nil list collecting StringV heads. */
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

    /** Build a SumV-encoded Cons/Nil list from a Kotlin List<String>. */
    private fun listOfStrings(strs: List<String>): Value {
        var cur: Value = Value.SumV("Nil", null)
        for (s in strs.reversed()) {
            cur = Value.SumV("Cons", Value.ProductV(mapOf("head" to Value.StringV(s), "tail" to cur)))
        }
        return cur
    }

    // ---------- String basics ----------

    @Test
    fun `String_Length`() {
        assertEquals(Value.IntV(5L), lookup("strand-builtin:String.Length").invoke(listOf(Value.StringV("hello"))))
        assertEquals(Value.IntV(0L), lookup("strand-builtin:String.Length").invoke(listOf(Value.StringV(""))))
    }

    @Test
    fun `String_Substring clamps and slices`() {
        val fn = lookup("strand-builtin:String.Substring")
        assertEquals(Value.StringV("ell"), fn.invoke(listOf(Value.StringV("hello"), Value.IntV(1L), Value.IntV(4L))))
        assertEquals(Value.StringV("hello"), fn.invoke(listOf(Value.StringV("hello"), Value.IntV(-5L), Value.IntV(100L))))
        assertEquals(Value.StringV(""), fn.invoke(listOf(Value.StringV("hello"), Value.IntV(3L), Value.IntV(3L))))
    }

    @Test
    fun `String_IndexOf and Contains`() {
        val idx = lookup("strand-builtin:String.IndexOf")
        val contains = lookup("strand-builtin:String.Contains")
        assertEquals(Value.IntV(2L), idx.invoke(listOf(Value.StringV("hello"), Value.StringV("ll"))))
        assertEquals(Value.IntV(-1L), idx.invoke(listOf(Value.StringV("hello"), Value.StringV("xyz"))))
        assertEquals(Value.BoolV(true), contains.invoke(listOf(Value.StringV("hello"), Value.StringV("ell"))))
        assertEquals(Value.BoolV(false), contains.invoke(listOf(Value.StringV("hello"), Value.StringV("xyz"))))
    }

    @Test
    fun `String_Replace literal`() {
        val fn = lookup("strand-builtin:String.Replace")
        assertEquals(Value.StringV("hxyzlo"), fn.invoke(listOf(Value.StringV("hello"), Value.StringV("el"), Value.StringV("xyz"))))
        // Multiple occurrences.
        assertEquals(Value.StringV("xyzxyzxyz"), fn.invoke(listOf(Value.StringV("aaa"), Value.StringV("a"), Value.StringV("xyz"))))
    }

    @Test
    fun `String_Split round-trips through Join`() {
        val split = lookup("strand-builtin:String.Split")
        val join = lookup("strand-builtin:String.Join")
        val parts = split.invoke(listOf(Value.StringV("a,b,c"), Value.StringV(",")))
        assertEquals(listOf("a", "b", "c"), collectStrings(parts))
        val rejoined = join.invoke(listOf(parts, Value.StringV(","))) as Value.StringV
        assertEquals("a,b,c", rejoined.v)
    }

    @Test
    fun `String_Split rejects empty separator`() {
        val fn = lookup("strand-builtin:String.Split")
        val ex = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            fn.invoke(listOf(Value.StringV("abc"), Value.StringV("")))
        }
        org.junit.jupiter.api.Assertions.assertTrue(ex.message!!.contains("non-empty"))
    }

    @Test
    fun `String_Join with empty list returns empty string`() {
        val fn = lookup("strand-builtin:String.Join")
        assertEquals(Value.StringV(""), fn.invoke(listOf(listOfStrings(emptyList()), Value.StringV(","))))
        assertEquals(Value.StringV("solo"), fn.invoke(listOf(listOfStrings(listOf("solo")), Value.StringV(","))))
    }

    @Test
    fun `String_ToUpper ToLower Trim`() {
        assertEquals(Value.StringV("HELLO"), lookup("strand-builtin:String.ToUpper").invoke(listOf(Value.StringV("hello"))))
        assertEquals(Value.StringV("hello"), lookup("strand-builtin:String.ToLower").invoke(listOf(Value.StringV("HELLO"))))
        assertEquals(Value.StringV("hi"), lookup("strand-builtin:String.Trim").invoke(listOf(Value.StringV("  hi  "))))
    }

    @Test
    fun `String_ParseInt and ParseFloat return Option`() {
        val parseInt = lookup("strand-builtin:String.ParseInt")
        val parseFloat = lookup("strand-builtin:String.ParseFloat")
        assertEquals(Value.SumV("Some", Value.IntV(42L)), parseInt.invoke(listOf(Value.StringV("42"))))
        assertEquals(Value.SumV("None", null), parseInt.invoke(listOf(Value.StringV("not a number"))))
        assertEquals(Value.SumV("Some", Value.FloatV(3.14)), parseFloat.invoke(listOf(Value.StringV("3.14"))))
        assertEquals(Value.SumV("None", null), parseFloat.invoke(listOf(Value.StringV("nope"))))
    }

    @Test
    fun `String_From conversions`() {
        assertEquals(Value.StringV("42"), lookup("strand-builtin:String.FromInt").invoke(listOf(Value.IntV(42L))))
        assertEquals(Value.StringV("3.14"), lookup("strand-builtin:String.FromFloat").invoke(listOf(Value.FloatV(3.14))))
        assertEquals(Value.StringV("true"), lookup("strand-builtin:String.FromBool").invoke(listOf(Value.BoolV(true))))
        assertEquals(Value.StringV("false"), lookup("strand-builtin:String.FromBool").invoke(listOf(Value.BoolV(false))))
    }

    // ---------- Bytes ----------

    @Test
    fun `Bytes_Length`() {
        assertEquals(Value.IntV(3L), lookup("strand-builtin:Bytes.Length").invoke(listOf(Value.BytesV(byteArrayOf(1, 2, 3)))))
        assertEquals(Value.IntV(0L), lookup("strand-builtin:Bytes.Length").invoke(listOf(Value.BytesV(ByteArray(0)))))
    }

    @Test
    fun `Bytes_Slice clamps and slices`() {
        val fn = lookup("strand-builtin:Bytes.Slice")
        val src = Value.BytesV(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(Value.BytesV(byteArrayOf(2, 3, 4)), fn.invoke(listOf(src, Value.IntV(1L), Value.IntV(4L))))
        assertEquals(Value.BytesV(byteArrayOf(1, 2, 3, 4, 5)), fn.invoke(listOf(src, Value.IntV(-10L), Value.IntV(100L))))
    }

    @Test
    fun `Bytes_Concat`() {
        val fn = lookup("strand-builtin:Bytes.Concat")
        val a = Value.BytesV(byteArrayOf(1, 2))
        val b = Value.BytesV(byteArrayOf(3, 4, 5))
        assertEquals(Value.BytesV(byteArrayOf(1, 2, 3, 4, 5)), fn.invoke(listOf(a, b)))
    }

    @Test
    fun `Bytes_ParseUtf8 round-trips with FromUtf8`() {
        val parse = lookup("strand-builtin:Bytes.ParseUtf8")
        val from = lookup("strand-builtin:Bytes.FromUtf8")
        val original = "hello — strand"
        val bytes = from.invoke(listOf(Value.StringV(original)))
        assertEquals(Value.SumV("Some", Value.StringV(original)), parse.invoke(listOf(bytes)))
    }

    @Test
    fun `Bytes_ParseUtf8 returns None on invalid UTF-8`() {
        val fn = lookup("strand-builtin:Bytes.ParseUtf8")
        // 0xFF is not a valid UTF-8 start byte.
        assertEquals(Value.SumV("None", null), fn.invoke(listOf(Value.BytesV(byteArrayOf(0xFF.toByte())))))
    }

    @Test
    fun `Bytes_FormatBase64 round-trips with ParseBase64`() {
        val fmt = lookup("strand-builtin:Bytes.FormatBase64")
        val parse = lookup("strand-builtin:Bytes.ParseBase64")
        val original = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        val encoded = fmt.invoke(listOf(Value.BytesV(original))) as Value.StringV
        assertEquals(Value.SumV("Some", Value.BytesV(original)), parse.invoke(listOf(encoded)))
    }

    @Test
    fun `Bytes_ParseBase64 returns None on invalid input`() {
        val fn = lookup("strand-builtin:Bytes.ParseBase64")
        assertEquals(Value.SumV("None", null), fn.invoke(listOf(Value.StringV("!!! not base64 !!!"))))
    }

    // ---------- Json.Parse ----------

    @Test
    fun `Json_Parse null returns Some JsonNull`() {
        val fn = lookup("strand-builtin:Json.Parse")
        assertEquals(Value.SumV("Some", Value.SumV("JsonNull", null)), fn.invoke(listOf(Value.StringV("null"))))
    }

    @Test
    fun `Json_Parse true and false return Some JsonBool`() {
        val fn = lookup("strand-builtin:Json.Parse")
        assertEquals(Value.SumV("Some", Value.SumV("JsonBool", Value.BoolV(true))), fn.invoke(listOf(Value.StringV("true"))))
        assertEquals(Value.SumV("Some", Value.SumV("JsonBool", Value.BoolV(false))), fn.invoke(listOf(Value.StringV("false"))))
    }

    @Test
    fun `Json_Parse integer returns Some JsonNumber`() {
        val fn = lookup("strand-builtin:Json.Parse")
        assertEquals(Value.SumV("Some", Value.SumV("JsonNumber", Value.IntV(42L))), fn.invoke(listOf(Value.StringV("42"))))
        assertEquals(Value.SumV("Some", Value.SumV("JsonNumber", Value.IntV(-7L))), fn.invoke(listOf(Value.StringV("-7"))))
    }

    @Test
    fun `Json_Parse string returns Some JsonString`() {
        val fn = lookup("strand-builtin:Json.Parse")
        assertEquals(Value.SumV("Some", Value.SumV("JsonString", Value.StringV("hello"))), fn.invoke(listOf(Value.StringV("\"hello\""))))
    }

    @Test
    fun `Json_Parse malformed input returns None`() {
        val fn = lookup("strand-builtin:Json.Parse")
        assertEquals(Value.SumV("None", null), fn.invoke(listOf(Value.StringV("{invalid"))))
        assertEquals(Value.SumV("None", null), fn.invoke(listOf(Value.StringV(""))))
    }

    @Test
    fun `Json_Parse array degrades to JsonNull`() {
        val fn = lookup("strand-builtin:Json.Parse")
        // Arrays + objects aren't representable in the current blessed
        // JsonValue (nested-μ blocker); they degrade to JsonNull rather
        // than None so the caller knows the input parsed but the
        // structure wasn't capturable. A nested-μ JsonArray/JsonObject
        // expansion would replace this.
        assertEquals(Value.SumV("Some", Value.SumV("JsonNull", null)), fn.invoke(listOf(Value.StringV("[1,2,3]"))))
        assertEquals(Value.SumV("Some", Value.SumV("JsonNull", null)), fn.invoke(listOf(Value.StringV("{}"))))
    }

    // ---------- Markdown.Parse ----------

    @Test
    fun `Markdown_Parse wraps input as a single Paragraph`() {
        val fn = lookup("strand-builtin:Markdown.Parse")
        val result = fn.invoke(listOf(Value.StringV("hello world"))) as Value.SumV
        assertEquals("Some", result.case)
        val list = result.payload as Value.SumV
        assertEquals("Cons", list.case)
        val firstEntry = (list.payload as Value.ProductV).fields
        val firstBlock = firstEntry.getValue("head") as Value.SumV
        assertEquals("Paragraph", firstBlock.case)
        assertEquals(Value.StringV("hello world"), firstBlock.payload)
        // Tail is Nil.
        val tail = firstEntry.getValue("tail") as Value.SumV
        assertEquals("Nil", tail.case)
    }
}
