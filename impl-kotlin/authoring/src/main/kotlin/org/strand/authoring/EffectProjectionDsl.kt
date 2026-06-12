package org.strand.authoring

/**
 * Q-060 M-4 — the compact string DSL for a ForeignNode's Q-039
 * `effectProjections` in Layer A.
 *
 * The FN code's optional fourth argument is a quoted string of
 * semicolon-separated per-category entries:
 *
 *     "<categoryRef>:<source>,<source>;<categoryRef>:..."
 *
 * where each source is either a non-negative integer (an
 * `ArgRef(index)` into the foreign function's positional arguments) or
 * `@<authorId>` (a `LiteralNode` reference to a literal node). A
 * category whose EffectCategory declares no parameters takes an empty
 * source list — `<categoryRef>:` with nothing after the colon.
 *
 * Example — the seven-arg `Http.Request` pinning `Network.Connect`'s
 * `{host, port}` to arguments 0 and 1:
 *
 *     reqFn FN "strand-builtin:Http.Request" reqT [connectFx netSendFx netRecvFx] "connectFx:0,1;netSendFx:;netRecvFx:"
 *
 * Per the N-020 contract, when a ForeignNode carries any projection it
 * must carry one entry per declared effect, in the same order as the
 * `effects` list; the verifier enforces this at admission — the DSL
 * parser checks syntax only.
 */
internal object EffectProjectionDsl {

    sealed class Source {
        data class ArgIndex(val index: Int) : Source()
        data class Literal(val target: String) : Source()
    }

    data class Entry(val category: String, val sources: List<Source>)

    /** Parse [text]; null on any syntax error. */
    fun parse(text: String): List<Entry>? {
        if (text.isBlank()) return null
        val entries = mutableListOf<Entry>()
        for (rawEntry in text.split(';')) {
            val entry = rawEntry.trim()
            val colon = entry.indexOf(':')
            if (colon <= 0) return null
            val category = entry.substring(0, colon).trim()
            if (category.isEmpty()) return null
            val sourcesText = entry.substring(colon + 1).trim()
            val sources = if (sourcesText.isEmpty()) {
                emptyList()
            } else {
                sourcesText.split(',').map { raw ->
                    val token = raw.trim()
                    when {
                        token.startsWith("@") && token.length > 1 -> Source.Literal(token.substring(1))
                        else -> Source.ArgIndex(token.toIntOrNull()?.takeIf { it >= 0 } ?: return null)
                    }
                }
            }
            entries += Entry(category, sources)
        }
        return entries
    }

    /** Render [entries] in the canonical DSL form ([parse]'s inverse). */
    fun format(entries: List<Entry>): String = entries.joinToString(";") { entry ->
        entry.category + ":" + entry.sources.joinToString(",") { src ->
            when (src) {
                is Source.ArgIndex -> src.index.toString()
                is Source.Literal -> "@${src.target}"
            }
        }
    }
}
