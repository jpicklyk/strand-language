package org.strand.authoring

/**
 * Renders a [LayerADocument] as Layer A text — the inverse of [LayerAParser].
 *
 * Output is one line per [NodeDecl] of the form `<id> <code> <arg>...`,
 * preceded by the header line `@v=<version> root=<rootId>`. Args are formatted
 * per their [Arg] shape (see [renderArg]).
 *
 * The renderer is deterministic: equivalent [LayerADocument]s produce
 * byte-identical text. Line order follows the document's node-list order;
 * [LayerATranslator] is responsible for producing nodes in a stable order
 * (typically the JSON document's insertion order). The renderer does not
 * consult [LayerAGrammar] — it trusts the document is well-formed.
 *
 * Used by Q-036 reverse projection (canonical dag-json → Layer A text). Pairs
 * with [LayerATranslator]; together they project a canonical store entry
 * into a form an LLM can read.
 */
object LayerARenderer {

    /** Render the full document to Layer A text. Output ends with a trailing newline. */
    fun render(doc: LayerADocument): String {
        val sb = StringBuilder()
        sb.append("@v=").append(doc.version)
            .append(" root=").append(doc.rootId)
            .append('\n')
        for (decl in doc.nodes) {
            sb.append(decl.id).append(' ').append(decl.code)
            for (arg in decl.args) {
                sb.append(' ').append(renderArg(arg))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Render one [Arg] as a single Layer A token (or bracketed list / nested form). */
    private fun renderArg(arg: Arg): String = when (arg) {
        is Arg.Bare -> arg.text
        is Arg.Str -> renderString(arg.value)
        is Arg.IntL -> arg.value.toString()
        is Arg.FloatL -> renderFloat(arg.value)
        is Arg.BoolL -> if (arg.value) "true" else "false"
        is Arg.Listing -> arg.items.joinToString(separator = " ", prefix = "[", postfix = "]") { renderArg(it) }
        is Arg.Null -> "_"
        is Arg.Nested -> {
            val inner = arg.args.joinToString(separator = " ") { renderArg(it) }
            if (inner.isEmpty()) "(${arg.code})" else "(${arg.code} $inner)"
        }
    }

    /** Render a Kotlin String as a Layer A double-quoted string with the standard escapes. */
    private fun renderString(s: String): String {
        val sb = StringBuilder().append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * Render a Double so the output reliably contains a `.` (Layer A's float
     * discriminator vs. integer). Kotlin's [Double.toString] already includes
     * the dot for finite values (e.g., `1.0` → `"1.0"`, `3.14` → `"3.14"`),
     * and the exponential form (`"1.0E10"`) still contains a dot. Non-finite
     * values (NaN, Infinity) aren't produced by the current corpus; if they
     * later are, [LayerAParser]'s float grammar would need extension.
     */
    private fun renderFloat(d: Double): String = d.toString()
}
