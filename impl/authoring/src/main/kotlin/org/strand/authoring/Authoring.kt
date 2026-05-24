package org.strand.authoring

import kotlinx.serialization.json.JsonObject

/**
 * Convenience entry points for the Q-034 step 1 authoring layer.
 *
 * The full agent-facing pipeline goes:
 *
 *   text → [LayerAParser] → [LayerADocument] → [DagJsonEmitter] → dag-json text
 *      → existing :core JsonIngest → RawNodeStore → :hashing Hasher → canonical
 *      NodeStore → :verifier → :interpreter / :runtime / :schema
 *
 * The authoring layer is the leftmost cell — purely syntax-level. Layer
 * C elaboration (bidirectional type inference + effect-closure
 * inference), Layer B grammar-constrained decoding, and tokenizer
 * alignment all remain deferred to follow-up steps.
 *
 * The first slice covers Layer 1 node categories only (literals, types,
 * lambda, application, let, varref, NodeRef, TypeAbstraction,
 * ForallType). Effects, foreign nodes, control flow, state machines,
 * schemas, recursive types, and handlers surface as
 * [AuthoringError.UnknownCode] until follow-up slices extend the
 * [LayerAGrammar.codes] table.
 */
object Authoring {

    /** Parse [text] into a [LayerADocument]. */
    fun parse(text: String): LayerADocument = LayerAParser.parse(text)

    /** Compile [text] into canonical dag-json text. */
    fun compileToDagJson(text: String): String = DagJsonEmitter.emit(parse(text))

    /** Compile [text] into a canonical dag-json [JsonObject]. */
    fun compileToJsonObject(text: String): JsonObject = DagJsonEmitter.emitJson(parse(text))

    /**
     * Parse + elaborate + compile (Q-034 Layer C first slice). Runs
     * [Elaborator.elaborate] between parsing and emission to fill in
     * inferred Lambda effects. The resulting dag-json is NOT guaranteed
     * to hash-equal a canonical-form sibling — elaboration is a one-way
     * compilation. Use this entry point when the agent emits effects-
     * elided Layer A; use [compileToDagJson] when the agent emits
     * fully-annotated Layer A and round-trip hash-equivalence is wanted.
     */
    fun compileWithElaboration(text: String): String =
        DagJsonEmitter.emit(Elaborator.elaborate(parse(text)))
}
