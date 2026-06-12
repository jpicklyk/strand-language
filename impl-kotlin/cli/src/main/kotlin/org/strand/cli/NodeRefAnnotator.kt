package org.strand.cli

import org.strand.core.NodeId

/**
 * Maps opaque `#N` NodeId references in error output back to what the agent
 * actually wrote.
 *
 * Verifier, interpreter, and schema errors are typed data whose `toString()`
 * renders NodeIds as `#N` — meaningless to the agent that authored the
 * program by name. This helper post-processes the rendered message,
 * annotating every `#N` whose NodeId maps back to an author id from the
 * ingest's name map:
 *
 *  * authored node — `UnboundVarRef(at=#23 'togglefn')`
 *  * sugar-synthesized node (Layer A `__if*` / `__when*` / `__var*` /
 *    `__lit*` / `__expr*` / `__outerprd*` ids) — `#41 (synthesized
 *    '__if0_match')`, telling the agent the error is inside an expansion,
 *    not a line it wrote
 *  * implicit-prelude node (`strand author` only) — `#77 (prelude 'intT')`
 *  * authored node with a known Layer A source line (`strand author` only)
 *    — `#23 'togglefn' (line 5)`
 *
 * NodeIds with no name-map entry are left as bare `#N` (nodes that only
 * exist post-ingest, e.g. federation-admitted subgraphs). The annotation is
 * inline and additive — the original `#N` text is always preserved — so the
 * error types themselves stay untouched (they are shared API).
 */
internal class NodeRefAnnotator(
    nameMap: Map<String, NodeId>,
    /**
     * Author ids that belong to the Layer A implicit prelude. Only the
     * `strand author` path passes a non-empty set: in a hand-authored
     * dag-json document a node named `intT` is genuinely authored, while in
     * an emitted Layer A compile it was synthesized from the reserved-name
     * table.
     */
    private val preludeNames: Set<String> = emptySet(),
    /**
     * Author id to 1-based Layer A source line, for compiles that retain
     * the parser's per-node line numbers. Empty for plain dag-json input
     * (JSON ingest does not track lines).
     */
    private val sourceLines: Map<String, Int> = emptyMap(),
) {

    private val nameById: Map<Int, String> =
        nameMap.entries.associate { (name, id) -> id.value to name }

    /**
     * Q-064: the author id for [id] when the ingest name map knows it, else
     * null. Used by the `strand:denial` JSON line, which carries the author
     * id as its own field rather than the inline `#N 'name'` annotation.
     */
    fun authorIdOf(id: NodeId): String? = nameById[id.value]

    /** Q-064: the 1-based Layer A source line for [id]'s author id, when known. */
    fun sourceLineOf(id: NodeId): Int? = nameById[id.value]?.let { sourceLines[it] }

    /** Annotate every recognized `#N` reference in [message]. */
    fun annotate(message: String): String = NODE_REF.replace(message) { match ->
        val id = match.groupValues[1].toIntOrNull() ?: return@replace match.value
        val name = nameById[id] ?: return@replace match.value
        val line = sourceLines[name]
        when {
            name.startsWith(SYNTHESIZED_PREFIX) ->
                if (line != null) "#$id (synthesized '$name', line $line)"
                else "#$id (synthesized '$name')"
            name in preludeNames -> "#$id (prelude '$name')"
            line != null -> "#$id '$name' (line $line)"
            else -> "#$id '$name'"
        }
    }

    private companion object {
        /** A `#` followed by digits, as produced by [NodeId.toString]. */
        val NODE_REF = Regex("""#(\d+)""")

        /**
         * Every Layer A emitter / elaborator synthesized id is `__`-prefixed:
         * `__if<n>_*`, `__when<n>_*`, `__var<n>`, `__lit<n>`, `__expr<n>`,
         * `__lamprc<n>_*`, `__pfv<n>_*`, `__outerprd<n>_*`, `__outerprf<n>_*`,
         * `__nested<n>`.
         */
        const val SYNTHESIZED_PREFIX = "__"
    }
}
