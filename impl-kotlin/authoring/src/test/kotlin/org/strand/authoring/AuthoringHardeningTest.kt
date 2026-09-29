package org.strand.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the Stream E authoring findings: reserved `__` ids
 * (finding 4), float exponent round-trip (finding 5), error columns, prelude
 * shadow warnings, escapes and header-version rejection.
 */
class AuthoringHardeningTest {

    private fun errorsOf(text: String): List<AuthoringError> =
        assertThrows(AuthoringException::class.java) { LayerAParser.parse(text) }.errors

    // ---- E1: reserved ids ---------------------------------------------------

    @Test
    fun `user id starting with double underscore is rejected as compiler-reserved`() {
        for (id in listOf("__lit0", "__var0", "__anon3", "__if0", "__when0", "__res0", "__expr0", "__x")) {
            val errs = errorsOf("@v=1 root=$id\n$id ILT 1\n")
            val err = errs.filterIsInstance<AuthoringError.ReservedNodeId>().single()
            assertEquals(id, err.id)
            assertTrue(err.detail.contains("reserved for the compiler")) { err.detail }
            assertEquals(1, err.column) { "column of the id in '$id ILT 1'" }
            assertEquals(2, err.line)
        }
    }

    @Test
    fun `user __anon id cannot hijack the at-last reference`() {
        val text = """
            @v=1 root=r
            intT PRM Int
            __anon9 ILT 7
            r APP f [@last]
        """.trimIndent()
        assertTrue(errorsOf(text).any { it is AuthoringError.ReservedNodeId })
    }

    @Test
    fun `underscore-prefixed ids with a single underscore stay legal`() {
        val doc = LayerAParser.parse("@v=1 root=_x\n_x ILT 1\n")
        assertEquals("_x", doc.rootId)
    }

    @Test
    fun `emitter asserts instead of silently overwriting on a synthesized id collision`() {
        // Bypass the parser (as Familiar / programmatic producers can): a user node named
        // like the emitter's first minted literal id.
        val doc = LayerADocument(
            version = 1,
            rootId = "r",
            nodes = listOf(
                NodeDecl("intT", "PRM", listOf(Arg.Bare("Int")), 1),
                NodeDecl("__lit0", "ILT", listOf(Arg.IntL(1)), 2),
                NodeDecl("addT", "FNT", listOf(Arg.Listing(listOf(Arg.Bare("intT"), Arg.Bare("intT"))), Arg.Bare("intT")), 3),
                NodeDecl("add", "FN", listOf(Arg.Str("strand-builtin:Int.Add"), Arg.Bare("addT")), 4),
                NodeDecl("r", "APP", listOf(Arg.Bare("add"), Arg.Listing(listOf(Arg.Bare("__lit0"), Arg.IntL(2)))), 5),
            ),
        )
        val ex = assertThrows(AuthoringException::class.java) { DagJsonEmitter.emit(doc) }
        val err = ex.errors.filterIsInstance<AuthoringError.SynthesizedIdCollision>().firstOrNull()
        assertTrue(err != null && err.id == "__lit0") { ex.message!! }
        assertTrue(err!!.detail.contains("reserved for the compiler"))
    }

    @Test
    fun `translating emitter output with synthesized ids yields Layer A that re-parses`() {
        val source = """
            @v=1 root=r
            intT PRM Int
            addT FNT [intT intT] intT
            add FN "strand-builtin:Int.Add" addT
            r APP add [1 2]
        """.trimIndent()
        val json = Authoring.compileToDagJson(source)
        assertTrue(json.contains("\"__lit")) { "expected synthesized literal ids in $json" }
        val layerA = Authoring.projectFromDagJson(json)
        assertTrue(!layerA.contains("__")) { layerA }
        Authoring.compileToDagJson(layerA)
        LayerAParser.parse(layerA)
    }

    // ---- E2: float round trip -----------------------------------------------

    @Test
    fun `parser lexes exponent floats`() {
        val cases = mapOf(
            "1.0E10" to 1.0E10, "1.0e-5" to 1.0E-5, "2e3" to 2000.0, "-2E+3" to -2000.0,
            "123456789.0" to 123456789.0, "-0.0" to -0.0, "1.5" to 1.5, "1e-320" to 1e-320,
        )
        for ((text, expected) in cases) {
            val doc = LayerAParser.parse("@v=1 root=a\na FLT $text\n")
            val arg = doc.nodes.single().args.single() as Arg.FloatL
            assertEquals(expected.toRawBits(), arg.value.toRawBits()) { "for $text" }
        }
    }

    @Test
    fun `renderer output for doubles re-parses to the identical bits`() {
        val values = listOf(
            1.0E10, 1.0E-5, -0.0, 0.0, 123456789.0, 1.0, -1.5, Double.MAX_VALUE, Double.MIN_VALUE,
            1.7976931348623157E300, 4.9E-324, 0.1 + 0.2, 1e21, -1e-7,
        )
        for (v in values) {
            val text = LayerARenderer.render(LayerADocument(1, "a", listOf(NodeDecl("a", "FLT", listOf(Arg.FloatL(v)), 1))))
            val back = (LayerAParser.parse(text).nodes.single().args.single() as Arg.FloatL).value
            assertEquals(v.toRawBits(), back.toRawBits()) { "$v rendered as: $text" }
        }
    }

    @Test
    fun `renderer rejects NaN and infinities with a typed error`() {
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val ex = assertThrows(AuthoringException::class.java) {
                LayerARenderer.render(LayerADocument(1, "a", listOf(NodeDecl("a", "FLT", listOf(Arg.FloatL(v)), 1))))
            }
            assertTrue(ex.errors.single() is AuthoringError.UnrenderableFloat)
        }
    }

    @Test
    fun `malformed exponents and overflowing floats are lexical errors`() {
        for (bad in listOf("1e", "1.0E", "1.0E+", "1e999")) {
            val errs = errorsOf("@v=1 root=a\na FLT $bad\n")
            assertTrue(errs.any { it is AuthoringError.TokenError }) { "$bad -> $errs" }
        }
    }

    // ---- E4: columns --------------------------------------------------------

    @Test
    fun `token errors carry a column and the message names it`() {
        val ex = assertThrows(AuthoringException::class.java) { LayerAParser.parse("@v=1 root=a\na ILT 1 \$\n") }
        val err = ex.errors.single()
        assertEquals(2, err.line)
        assertEquals(9, err.column)
        assertTrue(ex.message!!.contains("line 2, col 9:")) { ex.message!! }
    }

    @Test
    fun `columns account for leading indentation`() {
        val err = errorsOf("@v=1 root=a\n    a ILT \$\n").single()
        assertEquals(11, err.column)
    }

    @Test
    fun `duplicate node id carries the id column and errors without one omit it`() {
        val errs = errorsOf("@v=1 root=a\na ILT 1\n  a ILT 2\n")
        assertEquals(3, errs.filterIsInstance<AuthoringError.DuplicateNodeId>().single().column)
        assertNull(AuthoringError.UnknownRoot(line = 1, rootId = "x").column)
        val ex = AuthoringException(listOf(AuthoringError.UnknownRoot(line = 1, rootId = "x")))
        assertTrue(ex.message!!.contains("line 1: root 'x'")) { ex.message!! }
    }

    // ---- E5: prelude shadow warning -----------------------------------------

    @Test
    fun `user node shadowing a prelude reserved name with a different body warns`() {
        val source = """
            @v=1 root=r
            intT PRM Bool
            r ILT 1
        """.trimIndent()
        val result = Authoring.compile(source)
        val w = result.warnings.filterIsInstance<AuthoringWarning.ShadowedReservedName>().single()
        assertEquals("intT", w.name)
        assertEquals(2, w.line)
        assertTrue(w.message.contains("shadows the prelude reserved name 'intT'"))
    }

    @Test
    fun `redeclaring a reserved name identically to the prelude does not warn`() {
        val source = """
            @v=1 root=r
            intT PRM Int
            r ILT 1
        """.trimIndent()
        assertTrue(Authoring.compile(source).warnings.isEmpty())
    }

    // ---- E7: escapes and version --------------------------------------------

    @Test
    fun `strings with control characters round-trip through render and parse`() {
        val samples = listOf("a\rb", "line1\nline2", "tab\there", "\u0001\u001f\u007f", "quote\"back\\slash", "caf\u00e9 \uD83D\uDE00", "\uD800lone")
        for (s in samples) {
            val text = LayerARenderer.render(LayerADocument(1, "a", listOf(NodeDecl("a", "STR", listOf(Arg.Str(s)), 1))))
            assertEquals(2, text.trimEnd().split('\n').size) { "rendered form must stay on one line: $text" }
            val back = (LayerAParser.parse(text).nodes.single().args.single() as Arg.Str).value
            assertEquals(s, back)
        }
    }

    @Test
    fun `parser accepts backslash-r and backslash-u escapes and rejects malformed u escapes`() {
        val doc = LayerAParser.parse("@v=1 root=a\na STR \"x\\ry\\u0041\\u00e9\"\n")
        assertEquals("x\ryA\u00e9", (doc.nodes.single().args.single() as Arg.Str).value)
        assertTrue(errorsOf("@v=1 root=a\na STR \"\\u12\"\n").any { it is AuthoringError.TokenError })
        assertTrue(errorsOf("@v=1 root=a\na STR \"\\uZZZZ\"\n").any { it is AuthoringError.TokenError })
    }

    @Test
    fun `unknown header version is rejected at parse time`() {
        val errs = errorsOf("@v=2 root=a\na ILT 1\n")
        val err = errs.filterIsInstance<AuthoringError.HeaderError>().single()
        assertTrue(err.detail.contains("unsupported Layer A version @v=2")) { err.detail }
        assertEquals(1, LayerAParser.parse("@v=1 root=a\na ILT 1\n").version)
    }
}
