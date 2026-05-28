package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 5, Group E — Url.Parse / Url.QueryEncode /
 * Url.QueryDecode. Uses java.net.URI and java.net.URLEncoder/Decoder.
 */
class BuiltinsUrlTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    // ---------- Url.Parse ----------

    @Test
    fun `Url_Parse full URL returns Some with all components`() {
        val fn = lookup("strand-builtin:Url.Parse")
        val result = fn.invoke(listOf(Value.StringV("https://user@example.com:8443/path/sub?q=value&x=1#section"))) as Value.SumV
        assertEquals("Some", result.case)
        val p = result.payload as Value.ProductV
        assertEquals("https", (p.fields.getValue("scheme") as Value.StringV).v)
        assertEquals("example.com", (p.fields.getValue("host") as Value.StringV).v)
        assertEquals(8443L, (p.fields.getValue("port") as Value.IntV).v)
        assertEquals("/path/sub", (p.fields.getValue("path") as Value.StringV).v)
        assertEquals("q=value&x=1", (p.fields.getValue("query") as Value.StringV).v)
        assertEquals("section", (p.fields.getValue("fragment") as Value.StringV).v)
    }

    @Test
    fun `Url_Parse without port returns -1`() {
        val fn = lookup("strand-builtin:Url.Parse")
        val result = fn.invoke(listOf(Value.StringV("http://example.com/foo"))) as Value.SumV
        val p = result.payload as Value.ProductV
        assertEquals(-1L, (p.fields.getValue("port") as Value.IntV).v)
    }

    @Test
    fun `Url_Parse without query or fragment yields empty strings`() {
        val fn = lookup("strand-builtin:Url.Parse")
        val result = fn.invoke(listOf(Value.StringV("https://example.com/"))) as Value.SumV
        val p = result.payload as Value.ProductV
        assertEquals("", (p.fields.getValue("query") as Value.StringV).v)
        assertEquals("", (p.fields.getValue("fragment") as Value.StringV).v)
    }

    @Test
    fun `Url_Parse without scheme returns None`() {
        val fn = lookup("strand-builtin:Url.Parse")
        // No scheme — URI parses, but our None-on-missing-scheme rule fires.
        val result = fn.invoke(listOf(Value.StringV("/just/a/path"))) as Value.SumV
        assertEquals("None", result.case)
    }

    @Test
    fun `Url_Parse malformed returns None`() {
        val fn = lookup("strand-builtin:Url.Parse")
        // Spaces in URLs are a URISyntaxException.
        val result = fn.invoke(listOf(Value.StringV("http://example.com/has spaces"))) as Value.SumV
        assertEquals("None", result.case)
    }

    // ---------- Url.QueryEncode / Decode ----------

    @Test
    fun `Url_QueryEncode replaces special chars`() {
        val fn = lookup("strand-builtin:Url.QueryEncode")
        // application/x-www-form-urlencoded: space -> '+', other reserved -> %XX
        assertEquals(Value.StringV("hello+world"),
            fn.invoke(listOf(Value.StringV("hello world"))))
        assertEquals(Value.StringV("a%26b%3Dc"),
            fn.invoke(listOf(Value.StringV("a&b=c"))))
    }

    @Test
    fun `Url_QueryDecode inverts QueryEncode`() {
        val enc = lookup("strand-builtin:Url.QueryEncode")
        val dec = lookup("strand-builtin:Url.QueryDecode")
        for (s in listOf("hello world", "a&b=c", "plain", "100%", "tab\there")) {
            val encoded = enc.invoke(listOf(Value.StringV(s))) as Value.StringV
            val decoded = dec.invoke(listOf(encoded)) as Value.SumV
            assertEquals("Some", decoded.case)
            assertEquals(Value.StringV(s), decoded.payload)
        }
    }

    @Test
    fun `Url_QueryDecode None on malformed percent-encoding`() {
        val dec = lookup("strand-builtin:Url.QueryDecode")
        // %ZZ is not valid hex.
        val result = dec.invoke(listOf(Value.StringV("hello%ZZ"))) as Value.SumV
        assertEquals("None", result.case)
        // truncated percent at end
        val result2 = dec.invoke(listOf(Value.StringV("hello%2"))) as Value.SumV
        assertEquals("None", result2.case)
    }
}
