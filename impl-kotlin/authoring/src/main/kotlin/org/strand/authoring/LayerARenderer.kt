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

    /**
     * Render a Kotlin String as a Layer A double-quoted string. Escapes `\\`,
     * `\"`, `\n`, `\t`, `\r`; every other control character (and DEL and
     * unpaired surrogates) becomes `\uXXXX` so the rendered line never
     * contains a raw line break and always reads back to the same string.
     */
    private fun renderString(s: String): String {
        val sb = StringBuilder().append('"')
        for ((i, c) in s.withIndex()) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\t' -> sb.append("\\t")
                c == '\r' -> sb.append("\\r")
                c.code < 0x20 || c.code == 0x7f || isUnpairedSurrogate(s, i) ->
                    sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun isUnpairedSurrogate(s: String, i: Int): Boolean {
        val c = s[i]
        return when {
            c.isHighSurrogate() -> !(i + 1 < s.length && s[i + 1].isLowSurrogate())
            c.isLowSurrogate() -> !(i > 0 && s[i - 1].isHighSurrogate())
            else -> false
        }
    }

    /**
     * Render a Double in a form [LayerAParser] accepts and reads back to the
     * identical value. [Double.toString] is the shortest round-tripping decimal
     * and always contains a `.`; its exponent forms (`1.0E10`, `1.0E-5`) match
     * the parser's `digits[.digits](e|E)[+-]digits` float grammar, and `-0.0`
     * keeps its sign. NaN and the infinities have no Layer A literal and are
     * rejected with a typed [AuthoringError.UnrenderableFloat].
     */
    private fun renderFloat(d: Double): String {
        if (d.isNaN() || d.isInfinite()) {
            throw AuthoringException(listOf(AuthoringError.UnrenderableFloat(line = 0, value = d.toString())))
        }
        return d.toString()
    }
}
