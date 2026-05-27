package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 4 — Markdown.Stringify. Inverse of
 * Markdown.Parse, typed against the canonical corpus-61 MarkdownDocument
 * shape:
 *   MarkdownDocument = μ. Cons({head: MarkdownBlock, tail: <self>}) | Nil
 *   MarkdownBlock = Heading{level, text} | Paragraph{text} |
 *                   CodeBlock{language, code} | HorizontalRule
 *
 * Backward compat: Markdown.Parse currently produces a bare-StringV
 * Paragraph payload; Stringify accepts both that legacy shape and
 * the canonical corpus-61 ProductV{text} shape so a round-trip
 * (Parse → Stringify) preserves single-paragraph input verbatim.
 */
class BuiltinsMarkdownStringifyTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    // Helpers to construct MarkdownDocument values.
    private fun heading(level: Int, text: String): Value =
        Value.SumV("Heading", Value.ProductV(mapOf(
            "level" to Value.IntV(level.toLong()),
            "text" to Value.StringV(text),
        )))

    private fun paragraphCanonical(text: String): Value =
        Value.SumV("Paragraph", Value.ProductV(mapOf("text" to Value.StringV(text))))

    private fun paragraphLegacy(text: String): Value =
        Value.SumV("Paragraph", Value.StringV(text))

    private fun codeBlock(language: String, code: String): Value =
        Value.SumV("CodeBlock", Value.ProductV(mapOf(
            "language" to Value.StringV(language),
            "code" to Value.StringV(code),
        )))

    private val hr: Value = Value.SumV("HorizontalRule", null)

    private fun docOf(blocks: List<Value>): Value {
        var cur: Value = Value.SumV("Nil", null)
        for (b in blocks.reversed()) {
            cur = Value.SumV("Cons", Value.ProductV(mapOf("head" to b, "tail" to cur)))
        }
        return cur
    }

    // ---------- Single block tests ----------

    @Test
    fun `Heading levels 1 through 6`() {
        val fn = lookup("strand-builtin:Markdown.Stringify")
        for (level in 1..6) {
            val doc = docOf(listOf(heading(level, "Title")))
            val result = (fn.invoke(listOf(doc)) as Value.StringV).v
            assertEquals("#".repeat(level) + " Title", result)
        }
    }

    @Test
    fun `Heading level clamps to 1-6`() {
        // Level 0 or > 6 is unusual Markdown — we clamp to keep output valid.
        val fn = lookup("strand-builtin:Markdown.Stringify")
        assertEquals("# Big", (fn.invoke(listOf(docOf(listOf(heading(0, "Big"))))) as Value.StringV).v)
        assertEquals("###### Small", (fn.invoke(listOf(docOf(listOf(heading(99, "Small"))))) as Value.StringV).v)
    }

    @Test
    fun `Paragraph canonical ProductV shape`() {
        val fn = lookup("strand-builtin:Markdown.Stringify")
        val doc = docOf(listOf(paragraphCanonical("Hello world")))
        assertEquals("Hello world", (fn.invoke(listOf(doc)) as Value.StringV).v)
    }

    @Test
    fun `Paragraph legacy StringV shape (Markdown_Parse output)`() {
        val fn = lookup("strand-builtin:Markdown.Stringify")
        val doc = docOf(listOf(paragraphLegacy("Hello world")))
        assertEquals("Hello world", (fn.invoke(listOf(doc)) as Value.StringV).v)
    }

    @Test
    fun `CodeBlock with language fence`() {
        val fn = lookup("strand-builtin:Markdown.Stringify")
        val doc = docOf(listOf(codeBlock("kotlin", "val x = 42")))
        val expected = "```kotlin\nval x = 42\n```"
        assertEquals(expected, (fn.invoke(listOf(doc)) as Value.StringV).v)
    }

    @Test
    fun `CodeBlock with empty language`() {
        val fn = lookup("strand-builtin:Markdown.Stringify")
        val doc = docOf(listOf(codeBlock("", "raw code")))
        assertEquals("```\nraw code\n```", (fn.invoke(listOf(doc)) as Value.StringV).v)
    }

    @Test
    fun `HorizontalRule emits three dashes`() {
        val fn = lookup("strand-builtin:Markdown.Stringify")
        assertEquals("---", (fn.invoke(listOf(docOf(listOf(hr)))) as Value.StringV).v)
    }

    // ---------- Multi-block + boundary ----------

    @Test
    fun `Empty document is empty string`() {
        val fn = lookup("strand-builtin:Markdown.Stringify")
        assertEquals("", (fn.invoke(listOf(Value.SumV("Nil", null))) as Value.StringV).v)
    }

    @Test
    fun `Multiple blocks separated by blank line`() {
        val fn = lookup("strand-builtin:Markdown.Stringify")
        val doc = docOf(listOf(
            heading(1, "Title"),
            paragraphCanonical("First paragraph."),
            hr,
            paragraphCanonical("Second paragraph."),
        ))
        val result = (fn.invoke(listOf(doc)) as Value.StringV).v
        assertEquals("# Title\n\nFirst paragraph.\n\n---\n\nSecond paragraph.", result)
    }

    // ---------- Round-trip with Markdown.Parse ----------

    @Test
    fun `Round-trip Markdown_Parse then Stringify preserves single paragraph`() {
        val parse = lookup("strand-builtin:Markdown.Parse")
        val stringify = lookup("strand-builtin:Markdown.Stringify")
        val source = "A single paragraph."
        val parsed = (parse.invoke(listOf(Value.StringV(source))) as Value.SumV)
        assertEquals("Some", parsed.case)
        val doc = parsed.payload!!
        val out = (stringify.invoke(listOf(doc)) as Value.StringV).v
        assertEquals(source, out)
    }

    @Test
    fun `Unknown block case raises IoFailure`() {
        val fn = lookup("strand-builtin:Markdown.Stringify")
        val bogus = Value.SumV("Bogus", null)
        val doc = docOf(listOf(bogus))
        try {
            fn.invoke(listOf(doc))
            error("expected IoFailure")
        } catch (e: IoFailure) {
            assertTrue(e.kind == "markdown-stringify")
            assertTrue(e.detail.contains("Bogus"))
        }
    }
}
