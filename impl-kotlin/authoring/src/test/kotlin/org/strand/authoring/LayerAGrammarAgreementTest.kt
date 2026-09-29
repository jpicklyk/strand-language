package org.strand.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Cross-checks the hand-written [LayerAParser] against the GBNF that
 * [ConstraintGrammar] emits for constrained decoding (review authoring
 * finding 3). The two must accept the same language; where they diverge
 * this test pins the divergence so a new one fails loudly instead of
 * drifting. Divergences are reported, never fixed by loosening the grammar.
 */
class LayerAGrammarAgreementTest {

    private val matcher = GbnfMatcher.parse(ConstraintGrammar.emitGbnf())

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "corpus/layer-a").isDirectory) return dir
            dir = dir.parentFile
        }
        error("could not locate repo root containing corpus/layer-a from ${System.getProperty("user.dir")}")
    }

    private fun layerAFiles(): List<File> {
        val root = repoRoot()
        return listOf("corpus", "demos", "evaluation").flatMap { top ->
            File(root, top).walkTopDown()
                .filter { it.isFile && it.name.endsWith(".layer-a") && !it.path.contains("${File.separator}build${File.separator}") }
                .toList()
        }.sortedBy { it.path }
    }

    /** Source lines a decoder would emit: comments and blank lines are outside the grammar by design. */
    private fun grammarLines(text: String): List<Pair<Int, String>> =
        text.split('\n').mapIndexed { i, l -> (i + 1) to l.trimEnd() }
            .filter { (_, l) -> l.isNotBlank() && !l.trimStart().startsWith("#") }

    // ---- lexical agreement -------------------------------------------------

    @Test
    fun `parser and grammar agree on identifiers and digits being ASCII-only`() {
        assertTrue(matcher.derives("identifier", "caf"))
        assertTrue(!matcher.derives("identifier", "caf\u00e9"))
        val ex = org.junit.jupiter.api.Assertions.assertThrows(AuthoringException::class.java) {
            LayerAParser.parse("@v=1 root=caf\u00e9\ncaf\u00e9 ILT 1\n")
        }
        assertTrue(ex.message!!.contains("\u00e9")) { ex.message!! }
    }

    @Test
    fun `non-ASCII letter inside a node line is a typed error naming the character`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(AuthoringException::class.java) {
            LayerAParser.parse("@v=1 root=a\na ILT 1\nb VAR \u00fcber\n")
        }
        val err = ex.errors.filterIsInstance<AuthoringError.TokenError>().first { it.line == 3 }
        assertTrue(err.detail.contains("'\u00fc'")) { err.detail }
        assertTrue(err.detail.contains("ASCII")) { err.detail }
        assertEquals(7, err.column)
    }

    @Test
    fun `non-ASCII digits are not digits`() {
        // ARABIC-INDIC DIGIT ONE
        val ex = org.junit.jupiter.api.Assertions.assertThrows(AuthoringException::class.java) {
            LayerAParser.parse("@v=1 root=a\na ILT \u0661\n")
        }
        assertTrue(ex.errors.any { it is AuthoringError.TokenError && it.detail.contains("ASCII") })
    }

    @Test
    fun `float rule derives exactly the forms the parser lexes`() {
        val floats = listOf("1.0", "-0.5", "1.0E10", "1.0e-5", "2e3", "-2E+3", "123456789.0", "-0.0")
        for (f in floats) {
            assertTrue(matcher.derives("float", f)) { "grammar should derive float $f" }
        }
        for (notFloat in listOf("12", "1.", ".5", "1e", "1.0E", "e5", "1.0E+")) {
            assertTrue(!matcher.derives("float", notFloat)) { "grammar must not derive float $notFloat" }
        }
    }

    @Test
    fun `string escape set matches between parser and grammar`() {
        for (s in listOf("\"a\\nb\"", "\"\\r\"", "\"\\t\"", "\"\\u00e9\"", "\"\\\\\\\"\"")) {
            assertTrue(matcher.derives("string", s)) { "grammar should derive string $s" }
        }
        assertTrue(!matcher.derives("string", "\"\\u12\""))
        assertTrue(!matcher.derives("string", "\"\\x\""))
    }

    // ---- corpus agreement --------------------------------------------------

    @Test
    fun `every Layer A file in the repository parses`() {
        val files = layerAFiles()
        assertTrue(files.size >= 22) { "expected the corpus Layer A fixtures, found ${files.size}" }
        for (f in files) {
            // Parsing (not elaboration) is what E1/E3 change; failures here mean a fixture
            // used a reserved `__` id, a non-ASCII identifier or an unsupported version.
            try {
                LayerAParser.parse(f.readText())
            } catch (e: AuthoringException) {
                throw AssertionError("${f.path} no longer parses: ${e.message}")
            }
        }
    }

    @Test
    fun `no Layer A fixture uses a compiler-reserved double-underscore id`() {
        for (f in layerAFiles()) {
            val doc = LayerAParser.parse(f.readText())
            assertTrue(doc.nodes.none { it.id.startsWith("__") && !it.id.startsWith("__anon") }) { f.path }
        }
    }

    @Test
    fun `corpus Layer A lines are derivable by the constraint grammar except the pinned divergences`() {
        val divergent = sortedSetOf<String>(); val samples = linkedMapOf<String, MutableList<String>>()
        for (f in layerAFiles()) {
            for ((_, line) in grammarLines(f.readText())) {
                val rule = if (line.startsWith("@v=")) "header" else "node"
                if (!matcher.derives(rule, line)) {
                    divergent += classify(line); if (samples.getOrPut(classify(line)) { mutableListOf() }.size < 3) samples[classify(line)]!!.add(f.name + ": " + line)
                }
            }
        }
        samples.forEach { (k, v) -> println("SAMPLE $k => $v") }
        println("GBNF divergences over ${layerAFiles().size} files: $divergent")
        assertEquals(KNOWN_DIVERGENCES, divergent) {
            "Constraint-grammar divergence set changed. Grammar cannot derive: $divergent"
        }
    }

    /** Names the construct that makes [line] underivable so the pin is stable across fixtures. */
    private fun classify(line: String): String {
        val kinds = mutableListOf<String>()
        if (Regex("\\(\\s*[A-Z]{2,4}\\b").containsMatchIn(line)) kinds += "nested-expression"
        if ("@last" in line) kinds += "@last"
        if (Regex("[A-Za-z_][A-Za-z0-9_]*:[A-Za-z_]").containsMatchIn(line.replace(Regex("\"[^\"]*\""), ""))) kinds += "compact-name:type"
        if (Regex("[A-Za-z_][A-Za-z0-9_]*=[A-Za-z_(\\[]").containsMatchIn(line.replace(Regex("\"[^\"]*\""), "").replace(Regex("^@v=\\d+ root=\\S+"), ""))) kinds += "compact-name=ref"
        if (kinds.isEmpty()) kinds += "other"
        return kinds.joinToString("+")
    }

    private companion object {
        /**
         * Constructs the corpus uses that the emitted GBNF cannot derive (registered under Q-057
         * by the orchestrator): `@last` references; compact `name:type` LAM params and `name=ref`
         * PV/SV fields; nested `(CODE ...)` v4 expressions; and inline literals (int/bool/string)
         * in reference or list_ref slots (classified "other").
         */
        val KNOWN_DIVERGENCES: Set<String> = sortedSetOf(
            "@last", "compact-name:type", "compact-name=ref", "nested-expression",
            "nested-expression+compact-name:type", "nested-expression+compact-name=ref", "other",
        )
    }
}
