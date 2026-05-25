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

    /** Parse [text] into a [LayerADocument]. */
    fun parse(text: String): LayerADocument = LayerAParser.parse(text)

    /** Compile [text] into canonical dag-json text. Runs elaboration. */
    fun compileToDagJson(text: String): String =
        DagJsonEmitter.emit(Elaborator.elaborate(parse(text)))

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
