package org.strand.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Q-060 M-3 grammar gate: the historical Layer A slip form — `_` in an
 * optional LIST_REF slot, e.g. `APP fn args _` — must be UNREPRESENTABLE
 * under the GBNF that `strand grammar` emits, while the valid forms
 * (a bracketed `[...]` list, or omitting the slot) remain derivable.
 *
 * Run 6 (2026-05-28) measured this slip as the dominant retry driver:
 * four cells failed first-attempt with exactly
 * `code 'APP' at position 2 expected [ref ref ...] list but got null '_'`.
 * Under constrained decoding the emitted grammar masks the `_` token to
 * zero probability at that position, so the slip cannot be emitted at
 * all. No hosted constrained-decode backend is available yet, so the
 * gate ships as this grammar-level rejection test, per the proposal's
 * fallback (`proposals/authoring-cost-reduction.md` section 2.3).
 *
 * The check is driven by [GbnfMatcher], a minimal recursive-descent,
 * fully-backtracking matcher over the GBNF subset [ConstraintGrammar]
 * emits (literals, character classes, rule references, grouping,
 * alternation, `* ? +` repetition). The matcher answers one question —
 * "does rule R derive string S exactly?" — which is precisely what a
 * decode-time mask enforces token by token.
 *
 * A structural pin on the generated `node_APP` / `optional_APP` rules
 * backs the matcher up: the optional tail offers only `list_ref`
 * alternatives, never `nullable_ref`.
 */
class ConstraintGrammarSlipGateTest {

    private val matcher = GbnfMatcher.parse(ConstraintGrammar.emitGbnf())

    // ------------------------------------------------------------------
    // Matcher self-checks — establish the matcher is not vacuous before
    // trusting its negative answers.
    // ------------------------------------------------------------------

    @Test
    fun `lexical primitives behave as specified`() {
        assertTrue(matcher.derives("identifier", "fn"))
        assertTrue(matcher.derives("identifier", "_x9"))
        assertFalse(matcher.derives("identifier", "9x"))
        // Density v5: dotted registry-builtin names are derivable
        // references (`List.Map` in callee position).
        assertTrue(matcher.derives("identifier", "List.Map"))
        assertTrue(matcher.derives("int", "-42"))
        assertFalse(matcher.derives("int", "4.2"))
        assertTrue(matcher.derives("string", "\"hi there\""))
        assertTrue(matcher.derives("list_ref", "[]"))
        assertTrue(matcher.derives("list_ref", "[a b c]"))
        assertFalse(matcher.derives("list_ref", "[a b"))
        // Density v5 slice b: the effect-instances slot admits an explicit
        // list or the @auto synthesis marker — never the `_` slip.
        assertTrue(matcher.derives("effect_instances", "[e]"))
        assertTrue(matcher.derives("effect_instances", "@auto"))
        assertFalse(matcher.derives("effect_instances", "_"))
    }

    @Test
    fun `list_ref does not derive a bare underscore but nullable_ref does`() {
        assertFalse(matcher.derives("list_ref", "_"))
        assertTrue(matcher.derives("nullable_ref", "_"))
    }

    // ------------------------------------------------------------------
    // The gate: slip form unrepresentable, valid forms derivable.
    // ------------------------------------------------------------------

    @Test
    fun `slip form APP fn args underscore is not derivable as a node line`() {
        assertFalse(matcher.derives("node", "m APP fn [a b] _"))
        assertFalse(matcher.derives("node_APP", "m APP fn [a b] _"))
    }

    @Test
    fun `skip-middle slip form APP fn args underscore list is not derivable`() {
        // The Run 6 variant that skips typeArguments to reach
        // effectInstances: `_` is not derivable in EITHER optional slot.
        assertFalse(matcher.derives("node", "m APP fn [a] _ [efd]"))
        assertFalse(matcher.derives("node", "m APP fn [a] [t] _"))
    }

    @Test
    fun `valid APP forms are derivable`() {
        assertTrue(matcher.derives("node", "m APP fn [a b]"))
        assertTrue(matcher.derives("node", "m APP fn [a] [t]"))
        assertTrue(matcher.derives("node", "m APP fn [a] [t] [e]"))
        assertTrue(matcher.derives("node", "m APP fn []"))
        // Density v5: dotted callee + @auto effect synthesis spellings.
        assertTrue(matcher.derives("node", "m APP List.Map [xs f]"))
        assertTrue(matcher.derives("node", "m APP fn [a] [] @auto"))
        assertTrue(matcher.derives("node", "m APP fn [a] @auto"))
        // The marker never derives at a non-effect slot's expense: `_`
        // remains unrepresentable in both optional positions.
        assertFalse(matcher.derives("node", "m APP fn [a] _ @auto"))
    }

    @Test
    fun `full document with the slip line is not derivable from root`() {
        assertFalse(matcher.derives("root", "@v=1 root=m\nm APP fn [a b] _"))
        assertTrue(matcher.derives("root", "@v=1 root=m\nm APP fn [a b]"))
    }

    @Test
    fun `underscore stays derivable at a genuine nullable slot`() {
        // SCS declares caseType as NULLABLE_REF — `_` is the documented
        // way to write a payload-less sum case. The gate must not
        // collaterally ban `_` where the grammar means it.
        assertTrue(matcher.derives("node", "c SCS \"None\" _"))
    }

    // ------------------------------------------------------------------
    // Structural pin on the generated rules.
    // ------------------------------------------------------------------

    @Test
    fun `node_APP and optional_APP rules offer no underscore alternative`() {
        val grammar = ConstraintGrammar.emitGbnf()
        val nodeApp = grammar.lines().first { it.startsWith("node_APP ::=") }
        val optionalApp = grammar.lines().first { it.startsWith("optional_APP ::=") }
        assertEquals(
            "node_APP ::= identifier \" \" \"APP\" \" \" identifier \" \" list_ref optional_APP?",
            nodeApp,
        )
        // Density v5 slice b: the trailing slot is `effect_instances`
        // (`list_ref | "@auto"`) and the truncated single-slot alternative
        // widens to it so `APP fn args @auto` stays derivable. Neither
        // alternative admits `nullable_ref`, so the `_` slip remains
        // unrepresentable.
        assertEquals(
            "optional_APP ::= \" \" effect_instances | \" \" list_ref \" \" effect_instances",
            optionalApp,
        )
        assertFalse(optionalApp.contains("nullable_ref")) {
            "APP's optional slots must not admit the `_` null marker"
        }
    }

    // ------------------------------------------------------------------
    // Parser regression pin (post-Run-6 behavior).
    // ------------------------------------------------------------------

    @Test
    fun `layer A parser absorbs the slip form as sugar for the empty list`() {
        // The post-Run-6 fix made the surface parser ACCEPT `_` at a
        // LIST_REF slot as sugar for `[]` (DagJsonEmitter, LIST_REF arm),
        // so an unconstrained emission of the slip no longer burns a
        // retry. This pin keeps the two layers consistent: the GBNF gate
        // makes the slip unrepresentable under constrained decoding; the
        // parser absorbs it when decoding is unconstrained.
        val slip = """
            @v=1 root=app
            app APP add [1 2] _
        """.trimIndent()
        val explicit = """
            @v=1 root=app
            app APP add [1 2] []
        """.trimIndent()
        val slipJson = Authoring.compileToDagJson(slip)
        val explicitJson = Authoring.compileToDagJson(explicit)
        assertEquals(explicitJson, slipJson) {
            "slip form must compile identically to the explicit-[] form"
        }
        // The omission form must also compile (the third valid spelling).
        val omitted = """
            @v=1 root=app
            app APP add [1 2]
        """.trimIndent()
        Authoring.compileToDagJson(omitted)
    }
}

/**
 * Minimal recursive-descent matcher for the GBNF subset emitted by
 * [ConstraintGrammar]: quoted literals (with `\" \\ \n \t` escapes),
 * character classes (`[A-Za-z_]`, negation, ranges, escapes), rule
 * references, grouping `( ... )`, alternation `|`, and `* ? +`
 * repetition. Matching is fully backtracking — every alternative end
 * position is explored lazily — so `derives(rule, text)` is an exact
 * derivability decision for these grammars, not a greedy approximation.
 */
internal class GbnfMatcher private constructor(private val rules: Map<String, Ex>) {

    sealed interface Ex {
        data class Lit(val text: String) : Ex
        data class Cls(val negated: Boolean, val ranges: List<CharRange>) : Ex
        data class Ref(val name: String) : Ex
        data class Seq(val items: List<Ex>) : Ex
        data class Alt(val branches: List<Ex>) : Ex
        data class Rep(val item: Ex, val min: Int, val max: Int?) : Ex
    }

    /** True iff [ruleName] derives exactly [text] (the whole string). */
    fun derives(ruleName: String, text: String): Boolean =
        match(Ex.Ref(ruleName), text, 0).any { it == text.length }

    private fun match(ex: Ex, input: String, pos: Int): Sequence<Int> = when (ex) {
        is Ex.Lit ->
            if (input.startsWith(ex.text, pos)) sequenceOf(pos + ex.text.length)
            else emptySequence()
        is Ex.Cls -> {
            if (pos < input.length && ex.ranges.any { input[pos] in it } != ex.negated) {
                sequenceOf(pos + 1)
            } else emptySequence()
        }
        is Ex.Ref -> match(
            rules[ex.name] ?: error("undefined GBNF rule '${ex.name}'"),
            input,
            pos,
        )
        is Ex.Seq -> ex.items.fold(sequenceOf(pos)) { acc, item ->
            acc.flatMap { p -> match(item, input, p) }
        }
        is Ex.Alt -> ex.branches.asSequence().flatMap { match(it, input, pos) }
        is Ex.Rep -> matchRep(ex, input, pos)
    }

    private fun matchRep(rep: Ex.Rep, input: String, pos: Int): Sequence<Int> = sequence {
        if (rep.min == 0) yield(pos)
        if (rep.max == 0) return@sequence
        for (next in match(rep.item, input, pos)) {
            if (next == pos) continue // zero-width item: avoid infinite loops
            yieldAll(
                matchRep(
                    Ex.Rep(rep.item, maxOf(0, rep.min - 1), rep.max?.minus(1)),
                    input,
                    next,
                )
            )
        }
    }

    companion object {
        /** Parse a GBNF document (one `name ::= body` rule per line). */
        fun parse(gbnf: String): GbnfMatcher {
            val rules = mutableMapOf<String, Ex>()
            for (line in gbnf.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val sep = trimmed.indexOf("::=")
                if (sep < 0) continue
                val name = trimmed.substring(0, sep).trim()
                val body = trimmed.substring(sep + 3).trim()
                rules[name] = BodyParser(body).parseAlternation()
            }
            return GbnfMatcher(rules)
        }
    }

    private class BodyParser(private val s: String) {
        private var i = 0

        fun parseAlternation(): Ex {
            val branches = mutableListOf(parseSequence())
            skipWs()
            while (i < s.length && s[i] == '|') {
                i++
                branches += parseSequence()
                skipWs()
            }
            return if (branches.size == 1) branches[0] else Ex.Alt(branches)
        }

        private fun parseSequence(): Ex {
            val items = mutableListOf<Ex>()
            while (true) {
                skipWs()
                if (i >= s.length || s[i] == '|' || s[i] == ')') break
                items += parseRepeated()
            }
            return if (items.size == 1) items[0] else Ex.Seq(items)
        }

        private fun parseRepeated(): Ex {
            var atom = parseAtom()
            while (i < s.length && (s[i] == '*' || s[i] == '?' || s[i] == '+')) {
                atom = when (s[i]) {
                    '*' -> Ex.Rep(atom, 0, null)
                    '?' -> Ex.Rep(atom, 0, 1)
                    else -> Ex.Rep(atom, 1, null)
                }
                i++
            }
            return atom
        }

        private fun parseAtom(): Ex = when (s[i]) {
            '"' -> parseLiteral()
            '[' -> parseCharClass()
            '(' -> {
                i++ // consume '('
                val inner = parseAlternation()
                skipWs()
                require(i < s.length && s[i] == ')') { "unbalanced '(' in: $s" }
                i++ // consume ')'
                inner
            }
            else -> parseRuleRef()
        }

        private fun parseLiteral(): Ex {
            i++ // consume opening quote
            val sb = StringBuilder()
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length) {
                    i++
                    sb.append(decodeEscape(s[i]))
                } else {
                    sb.append(s[i])
                }
                i++
            }
            require(i < s.length) { "unterminated literal in: $s" }
            i++ // consume closing quote
            return Ex.Lit(sb.toString())
        }

        private fun parseCharClass(): Ex {
            i++ // consume '['
            var negated = false
            if (i < s.length && s[i] == '^') {
                negated = true
                i++
            }
            val ranges = mutableListOf<CharRange>()
            while (i < s.length && s[i] != ']') {
                val lo = readClassChar()
                if (i + 1 < s.length && s[i] == '-' && s[i + 1] != ']') {
                    i++ // consume '-'
                    ranges += lo..readClassChar()
                } else {
                    ranges += lo..lo
                }
            }
            require(i < s.length) { "unterminated char class in: $s" }
            i++ // consume ']'
            return Ex.Cls(negated, ranges)
        }

        private fun readClassChar(): Char {
            val c = if (s[i] == '\\' && i + 1 < s.length) {
                i++
                decodeEscape(s[i])
            } else {
                s[i]
            }
            i++
            return c
        }

        private fun parseRuleRef(): Ex {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '-')) i++
            require(i > start) { "expected rule name at offset $start in: $s" }
            return Ex.Ref(s.substring(start, i))
        }

        private fun decodeEscape(c: Char): Char = when (c) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            else -> c // \" \\ and friends map to themselves
        }

        private fun skipWs() {
            while (i < s.length && s[i] == ' ') i++
        }
    }
}
