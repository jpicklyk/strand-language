package org.strand.authoring

/**
 * Layer B constraint-grammar derivation (Q-034 § 5.2).
 *
 * Emits a [GBNF](https://github.com/ggerganov/llama.cpp/blob/master/grammars/README.md)
 * (GGML Backus-Naur Form) grammar describing exactly the well-formed Layer
 * A documents. The grammar is consumable by:
 *
 *  * `llama.cpp` directly via the `--grammar-file` argument
 *  * Outlines via translation to its CFG / regex DSL
 *  * LMQL via a similar translation
 *
 * When an LLM emits Layer A tokens under this grammar, every position's
 * next-token distribution is masked to the set of tokens that extend a
 * valid parse. Lexically invalid emissions have zero probability; the
 * decoder spends its bits on the SEMANTIC choices that matter (which
 * code to emit, which reference to use), not on whether the next character
 * happens to be a closing bracket.
 *
 * **Scope (this slice).** The emitted grammar enforces LEXICAL and per-code
 * SYNTACTIC correctness:
 *
 *  * The header line shape (`@v=<int> root=<id>`).
 *  * One node per line, `<id> <code> <args>...`.
 *  * For each [LayerAGrammar.CodeSchema] code, the correct per-arg shape
 *    (BARE / STRING / INT / FLOAT / BOOL / LIST / NULL) in declaration
 *    order, including optional positional slots.
 *
 * **Out of scope.** Semantic constraints — "this reference must point to
 * a declared node", "this list of TypeParameters must match the TAB's
 * declared count" — require context-sensitive parsing that GBNF can't
 * express. A future stricter constraint mechanism (perhaps integrated with
 * the elaborator or with a model-side state machine) would close that gap.
 * Per the proposal §5.2: "the live grammar state determines the set of
 * legal next tokens" — the live state is exactly this CFG slice today;
 * context-sensitive extensions are a follow-up.
 *
 * **Code references.** The set of legal codes is enumerated dynamically
 * from [LayerAGrammar.codes], so adding a new code to the grammar
 * automatically extends the constraint grammar — no manual sync needed.
 */
object ConstraintGrammar {

    /**
     * Emit the full GBNF grammar as a string. The returned grammar can be
     * fed to `llama.cpp -m model.gguf --grammar-file grammar.gbnf` or to
     * any GBNF-consuming runtime.
     */
    fun emitGbnf(): String {
        val sb = StringBuilder()
        sb.append("# Layer A constraint grammar — auto-generated from LayerAGrammar.codes.\n")
        sb.append("# This grammar enforces LEXICAL and per-code SYNTACTIC correctness.\n")
        sb.append("# Semantic correctness (references resolve, ids unique, etc.) is NOT\n")
        sb.append("# enforced — see [ConstraintGrammar] doc.\n\n")

        // Top-level: a document is the header + node lines.
        sb.append("root ::= header (newline node)* newline?\n")
        sb.append("header ::= \"@v=\" int \" root=\" identifier\n")
        sb.append("\n")

        // A node line is `<id> <code> <args...>`. The code alternative
        // expands to per-code rules; each per-code rule consumes the args
        // it needs and nothing else.
        sb.append("node ::= ")
        val nodeAlternatives = LayerAGrammar.codes.keys.sorted().joinToString(" | ") { code ->
            "node_$code"
        }
        sb.append(nodeAlternatives).append("\n")
        sb.append("\n")

        // Per-code rules — one per [CodeSchema].
        for ((code, schema) in LayerAGrammar.codes.entries.sortedBy { it.key }) {
            sb.append("node_$code ::= identifier \" \" \"$code\"")
            for (spec in schema.required) {
                sb.append(" \" \" ").append(ruleNameFor(spec.kind))
            }
            // Optional slots: produce alternatives that allow each suffix
            // (none, first only, first+second, ...). For the common case
            // where every optional slot has the same multiplicity pattern,
            // a tail-of-optionals encoding suffices.
            if (schema.optional.isNotEmpty()) {
                sb.append(" optional_${code}?")
            }
            sb.append("\n")
            if (schema.optional.isNotEmpty()) {
                // Each optional rule is "first" OR "first second" OR
                // "first second third"... — a left-leaning chain of
                // suffix alternatives.
                val parts = schema.optional.map { ruleNameFor(it.kind) }
                val alternatives = (1..parts.size).joinToString(" | ") { len ->
                    parts.take(len).joinToString(" \" \" ", prefix = "\" \" ")
                }
                sb.append("optional_$code ::= $alternatives\n")
            }
        }
        sb.append("\n")

        // Lexical primitives.
        sb.append("# Lexical primitives\n")
        sb.append("identifier ::= [A-Za-z_] [A-Za-z0-9_]*\n")
        sb.append("int ::= \"-\"? [0-9]+\n")
        sb.append("float ::= \"-\"? [0-9]+ \".\" [0-9]+\n")
        sb.append("bool ::= \"true\" | \"false\"\n")
        sb.append("string ::= \"\\\"\" string_char* \"\\\"\"\n")
        sb.append("string_char ::= [^\\\"\\\\] | \"\\\\\" [\\\"\\\\nt]\n")
        sb.append("list_ref ::= \"[\" (identifier (\" \" identifier)*)? \"]\"\n")
        sb.append("nullable_ref ::= identifier | \"_\"\n")
        sb.append("keyword ::= identifier\n")
        sb.append("newline ::= \"\\n\"\n")

        return sb.toString()
    }

    /**
     * GBNF rule name that recognizes a single [LayerAGrammar.ArgKind].
     * Each kind maps to one of the lexical primitives emitted at the
     * grammar's end.
     */
    private fun ruleNameFor(kind: LayerAGrammar.ArgKind): String = when (kind) {
        LayerAGrammar.ArgKind.REFERENCE -> "identifier"
        LayerAGrammar.ArgKind.KEYWORD -> "keyword"
        LayerAGrammar.ArgKind.STRING -> "string"
        LayerAGrammar.ArgKind.INT -> "int"
        LayerAGrammar.ArgKind.FLOAT -> "float"
        LayerAGrammar.ArgKind.BOOL -> "bool"
        LayerAGrammar.ArgKind.LIST_REF -> "list_ref"
        LayerAGrammar.ArgKind.NULLABLE_REF -> "nullable_ref"
        // Slice 5 (v2): PARAM_LIST accepts either a legacy bare-ref list
        // or compact `name:typeRef` entries. The constraint grammar
        // currently overapproximates as "list_ref" — a stricter alternative
        // permitting either form is a future refinement once GBNF
        // tooling around `:`-bearing tokens is exercised.
        LayerAGrammar.ArgKind.PARAM_LIST -> "list_ref"
        // Slice 8 (v2.5): same overapproximation for FIELD_LIST.
        LayerAGrammar.ArgKind.FIELD_LIST -> "list_ref"
    }
}
