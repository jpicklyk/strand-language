package org.strand.authoring

import kotlinx.serialization.json.JsonObject

/**
 * Convenience entry points for the Q-034 step 1 authoring layer.
 *
 * The full agent-facing pipeline goes:
 *
 *   text → [LayerAParser] → [LayerADocument] → [Elaborator] → [DagJsonEmitter]
 *      → dag-json text → existing :core JsonIngest → RawNodeStore → :hashing
 *      Hasher → canonical NodeStore → :verifier → :interpreter / :runtime /
 *      :schema
 *
 * Elaboration is always on: [Elaborator.elaborate] fills in absent
 * annotations (Lambda effects, Application effectInstances /
 * typeArguments, Lambda paramType) before emission so the LLM can omit
 * derivable information. Layer A documents whose annotations are already
 * explicit pass through elaboration unchanged.
 */
object Authoring {

    /**
     * Result of [compile]: the canonical dag-json text plus the diagnostic
     * side-channels a driver (the `strand author` CLI) uses to map
     * downstream verifier/interpreter errors back to what the agent wrote.
     * Neither side-channel affects the emitted dag-json — [dagJson] is
     * byte-identical to [compileToDagJson]'s output for the same input.
     */
    data class CompileResult(
        /** Canonical dag-json text, identical to [compileToDagJson]. */
        val dagJson: String,
        /**
         * Author id → 1-based Layer A source line, for every node that
         * originates from a parsed line of the input document. Nodes the
         * elaborator or emitter synthesizes (sugar expansions, implicit
         * prelude) have no entry.
         */
        val sourceLines: Map<String, Int>,
    )

    /** Parse [text] into a [LayerADocument]. */
    fun parse(text: String): LayerADocument = LayerAParser.parse(text)

    /** Compile [text] into canonical dag-json text. Runs elaboration. */
    fun compileToDagJson(text: String): String = compile(text).dagJson

    /**
     * Compile [text] into canonical dag-json plus the per-node source-line
     * map (see [CompileResult]). Runs elaboration.
     */
    fun compile(text: String): CompileResult {
        val elaborated = Elaborator.elaborate(parse(text))
        val sourceLines = elaborated.nodes
            .filter { it.line > 0 }
            .associate { it.id to it.line }
        return CompileResult(
            dagJson = DagJsonEmitter.emit(elaborated),
            sourceLines = sourceLines,
        )
    }

    /** Compile [text] into a canonical dag-json [JsonObject]. Runs elaboration. */
    fun compileToJsonObject(text: String): JsonObject =
        DagJsonEmitter.emitJson(Elaborator.elaborate(parse(text)))

    /**
     * Project canonical dag-json [canonical] text back to Layer A text. The
     * inverse of [compileToDagJson] modulo elaboration-omission (Q-036).
     * Step 1 produces canonical-form Layer A only — no density sugars, no
     * inference-aware field stripping.
     */
    fun projectFromDagJson(canonical: String): String =
        LayerARenderer.render(LayerATranslator.translate(canonical))
}
