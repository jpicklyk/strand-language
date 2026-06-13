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
     * Q-061: the authoring surface a [compile] input is written in.
     * [LAYER_A] is the compact line-oriented projection (Q-034);
     * [FAMILIAR] is the TypeScript-shaped Layer F dialect
     * (proposals/familiar-surface-lowering.md). Both lower to the same
     * canonical dag-json document model and share the Elaborator +
     * DagJsonEmitter pipeline, so a Layer F program hashes identically
     * to its Layer A equivalent.
     */
    enum class Surface { LAYER_A, FAMILIAR }

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
        /**
         * Inference cases the [Elaborator] attempted but could not resolve
         * (Q-034 gap policy). Diagnostic only — the [dagJson] is emitted
         * regardless. Drivers surface these ONLY when downstream
         * ingest/verification fails; a gap that the verifier does not
         * trip over is not worth reporting.
         */
        val elaborationGaps: List<ElaborationGap> = emptyList(),
    )

    /** Parse [text] into a [LayerADocument]. */
    fun parse(text: String): LayerADocument = LayerAParser.parse(text)

    /** Compile [text] into canonical dag-json text. Runs elaboration. */
    fun compileToDagJson(text: String): String = compile(text).dagJson

    /**
     * Compile [text] into canonical dag-json plus the per-node source-line
     * map and elaboration-gap records (see [CompileResult]). Runs
     * elaboration. When the emit phase fails, the thrown
     * [AuthoringException] carries the gap records (the omitted
     * annotation an inference case could not fill is frequently the
     * root cause of the emit failure).
     */
    fun compile(text: String): CompileResult = compile(text, Surface.LAYER_A)

    /**
     * Compile [text] written in [surface] into canonical dag-json plus
     * the per-node source-line map (see [CompileResult]). The Layer F
     * path parses the familiar dialect and lowers it to the same
     * [LayerADocument] model the Layer A parser produces; both then run
     * the unconditional Elaborator (prelude resolution, implicit dotted
     * builtins, `@auto` effect synthesis, the 11 inference cases) and
     * the DagJsonEmitter.
     */
    fun compile(text: String, surface: Surface): CompileResult {
        val document = when (surface) {
            Surface.LAYER_A -> parse(text)
            Surface.FAMILIAR -> org.strand.authoring.familiar.FamiliarLowerer(
                org.strand.authoring.familiar.FamiliarParser.parse(text),
            ).lower()
        }
        val result = Elaborator.elaborateWithGaps(document)
        val elaborated = result.document
        val sourceLines = elaborated.nodes
            .filter { it.line > 0 }
            .associate { it.id to it.line }
        val dagJson = try {
            DagJsonEmitter.emit(elaborated)
        } catch (e: AuthoringException) {
            throw AuthoringException(e.errors, result.gaps)
        }
        return CompileResult(
            dagJson = dagJson,
            sourceLines = sourceLines,
            elaborationGaps = result.gaps,
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
