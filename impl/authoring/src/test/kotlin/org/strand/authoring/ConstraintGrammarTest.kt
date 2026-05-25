package org.strand.authoring

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [ConstraintGrammar] (Q-034 step 1 Layer B). The grammar is
 * generated dynamically from [LayerAGrammar.codes], so this test mainly
 * pins the shape and a few invariants — the codes themselves are covered
 * by [DagJsonEmitter] and round-trip tests elsewhere.
 */
class ConstraintGrammarTest {

    @Test
    fun `emitted grammar starts with the root and header rules`() {
        val grammar = ConstraintGrammar.emitGbnf()
        assertTrue(grammar.contains("root ::= header")) { "grammar must have a root rule" }
        assertTrue(grammar.contains("header ::= \"@v=\"")) { "grammar must have a header rule" }
    }

    @Test
    fun `every code in LayerAGrammar has a corresponding per-code rule`() {
        val grammar = ConstraintGrammar.emitGbnf()
        for (code in LayerAGrammar.codes.keys) {
            assertTrue(grammar.contains("node_$code ::=")) {
                "missing per-code rule for code '$code'"
            }
        }
    }

    @Test
    fun `lexical primitives are emitted`() {
        val grammar = ConstraintGrammar.emitGbnf()
        for (rule in listOf("identifier", "int", "float", "bool", "string", "list_ref", "nullable_ref", "keyword", "newline")) {
            assertTrue(grammar.contains("$rule ::=")) {
                "missing lexical primitive '$rule'"
            }
        }
    }

    @Test
    fun `node alternatives enumerate every code`() {
        val grammar = ConstraintGrammar.emitGbnf()
        val nodeRuleLine = grammar.lines().first { it.startsWith("node ::=") }
        for (code in LayerAGrammar.codes.keys) {
            assertTrue(nodeRuleLine.contains("node_$code")) {
                "node rule alternatives must include node_$code (line: $nodeRuleLine)"
            }
        }
    }

    @Test
    fun `codes with optional slots emit an optional rule`() {
        val grammar = ConstraintGrammar.emitGbnf()
        for ((code, schema) in LayerAGrammar.codes) {
            if (schema.optional.isEmpty()) continue
            assertTrue(grammar.contains("optional_$code ::=")) {
                "code '$code' has optional slots; expected an optional_$code rule"
            }
        }
    }
}
