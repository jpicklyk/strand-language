package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 2 — direct unit tests of Json.Stringify
 * and the Bytes hex codecs (FormatHex/ParseHex). Pure builtins,
 * no resource cleanup.
 */
class BuiltinsJsonHexTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    // ---------- Json.Stringify ----------

    @Test
    fun `Json_Stringify of JsonNull`() {
        val fn = lookup("strand-builtin:Json.Stringify")
        assertEquals(Value.StringV("null"), fn.invoke(listOf(Value.SumV("JsonNull", null))))
    }

    @Test
    fun `Json_Stringify of JsonBool true and false`() {
        val fn = lookup("strand-builtin:Json.Stringify")
        assertEquals(Value.StringV("true"), fn.invoke(listOf(Value.SumV("JsonBool", Value.BoolV(true)))))
        assertEquals(Value.StringV("false"), fn.invoke(listOf(Value.SumV("JsonBool", Value.BoolV(false)))))
    }

    @Test
    fun `Json_Stringify of JsonNumber`() {
        val fn = lookup("strand-builtin:Json.Stringify")
        assertEquals(Value.StringV("42"), fn.invoke(listOf(Value.SumV("JsonNumber", Value.IntV(42L)))))
        assertEquals(Value.StringV("-7"), fn.invoke(listOf(Value.SumV("JsonNumber", Value.IntV(-7L)))))
        assertEquals(Value.StringV("0"), fn.invoke(listOf(Value.SumV("JsonNumber", Value.IntV(0L)))))
    }

    @Test
    fun `Json_Stringify of JsonString escapes properly`() {
        val fn = lookup("strand-builtin:Json.Stringify")
        // The JSON library handles quoting + escape sequences.
        assertEquals(Value.StringV("\"hello\""), fn.invoke(listOf(Value.SumV("JsonString", Value.StringV("hello")))))
        // Quote inside the string should be escaped.
        assertEquals(
            Value.StringV("\"with\\\"quote\""),
            fn.invoke(listOf(Value.SumV("JsonString", Value.StringV("with\"quote")))),
        )
    }

    @Test
    fun `Json_Stringify round-trips via Json_Parse for each primitive case`() {
        val parse = lookup("strand-builtin:Json.Parse")
        val stringify = lookup("strand-builtin:Json.Stringify")
        for (input in listOf("null", "true", "false", "42", "-99", "\"hello world\"")) {
            val parsed = parse.invoke(listOf(Value.StringV(input)))
            val some = parsed as Value.SumV
            assertEquals("Some", some.case, "expected Some for $input")
            val jsonValue = some.payload!!
            val emitted = stringify.invoke(listOf(jsonValue))
            assertEquals(Value.StringV(input), emitted, "round-trip for $input")
        }
    }

    @Test
    fun `Json_Stringify handles arrays and objects via spliced variants`() {
        val parse = lookup("strand-builtin:Json.Parse")
        val stringify = lookup("strand-builtin:Json.Stringify")
        // Empty container forms — JsonArrayNil / JsonObjectNil.
        assertEquals(
            Value.StringV("[]"),
            stringify.invoke(listOf(Value.SumV("JsonArrayNil", null))),
        )
        assertEquals(
            Value.StringV("{}"),
            stringify.invoke(listOf(Value.SumV("JsonObjectNil", null))),
        )
        // Parse + stringify round-trip for non-trivial nested shapes.
        for (input in listOf("[1,2,3]", "{\"a\":1,\"b\":\"x\"}", "[true,null,\"y\"]")) {
            val parsed = (parse.invoke(listOf(Value.StringV(input))) as Value.SumV).payload!!
            assertEquals(Value.StringV(input), stringify.invoke(listOf(parsed)), input)
        }
    }

    // ---------- Bytes hex codecs ----------

    @Test
    fun `Bytes_FormatHex of empty bytes`() {
        val fn = lookup("strand-builtin:Bytes.FormatHex")
        assertEquals(Value.StringV(""), fn.invoke(listOf(Value.BytesV(ByteArray(0)))))
    }

    @Test
    fun `Bytes_FormatHex lowercase`() {
        val fn = lookup("strand-builtin:Bytes.FormatHex")
        assertEquals(
            Value.StringV("00ff7f80"),
            fn.invoke(listOf(Value.BytesV(byteArrayOf(0x00, 0xff.toByte(), 0x7f, 0x80.toByte())))),
        )
    }

    @Test
    fun `Bytes_FormatHex of UTF-8 hello`() {
        val fn = lookup("strand-builtin:Bytes.FormatHex")
        assertEquals(Value.StringV("68656c6c6f"), fn.invoke(listOf(Value.BytesV("hello".toByteArray()))))
    }

    @Test
    fun `Bytes_ParseHex round-trip with FormatHex`() {
        val format = lookup("strand-builtin:Bytes.FormatHex")
        val parse = lookup("strand-builtin:Bytes.ParseHex")
        val inputs = listOf(
            ByteArray(0),
            byteArrayOf(0x00),
            byteArrayOf(0xff.toByte()),
            "hello world".toByteArray(),
            ByteArray(100) { (it * 17).toByte() },
        )
        for (b in inputs) {
            val hex = format.invoke(listOf(Value.BytesV(b))) as Value.StringV
            val backOption = parse.invoke(listOf(hex)) as Value.SumV
            assertEquals("Some", backOption.case, "ParseHex returned None for ${hex.v}")
            val backBytes = (backOption.payload as Value.BytesV).v
            assertEquals(b.toList(), backBytes.toList(), "round-trip mismatch for ${hex.v}")
        }
    }

    @Test
    fun `Bytes_ParseHex accepts upper and lower case`() {
        val parse = lookup("strand-builtin:Bytes.ParseHex")
        val lower = parse.invoke(listOf(Value.StringV("deadbeef"))) as Value.SumV
        val upper = parse.invoke(listOf(Value.StringV("DEADBEEF"))) as Value.SumV
        val mixed = parse.invoke(listOf(Value.StringV("DeAdBeEf"))) as Value.SumV
        assertEquals(
            (lower.payload as Value.BytesV).v.toList(),
            (upper.payload as Value.BytesV).v.toList(),
        )
        assertEquals(
            (lower.payload as Value.BytesV).v.toList(),
            (mixed.payload as Value.BytesV).v.toList(),
        )
    }

    @Test
    fun `Bytes_ParseHex returns None on invalid input`() {
        val parse = lookup("strand-builtin:Bytes.ParseHex")
        // Odd length
        assertEquals(Value.SumV("None", null), parse.invoke(listOf(Value.StringV("abc"))))
        // Non-hex character
        assertEquals(Value.SumV("None", null), parse.invoke(listOf(Value.StringV("zz"))))
        assertEquals(Value.SumV("None", null), parse.invoke(listOf(Value.StringV("00gg"))))
    }
}
