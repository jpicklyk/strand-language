package org.strand.cli

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.strand.interpreter.DenialReport
import org.strand.interpreter.InterpretError

/**
 * Q-064: the CLI's machine-readable denial line. On any denial-caused
 * termination under `strand run`, `strand machine`, or `strand group`, the
 * CLI emits exactly one line to stderr —
 *
 * ```
 * strand:denial {"category":...,"requested":...,"held":...,"node":...,"author":...,"line":...,"instance":...,"eventIndex":...,"phase":...}
 * ```
 *
 * — alongside the existing human rendering. Always on, no flag: denials are
 * terminal, so the line cannot be noisy, and an orchestrator driving the
 * CLI gets the structure without opting in. The JSON shape is the
 * [DenialReport] field-for-field; the denying node renders as the `#N`
 * NodeId with the Q-051 author id and Layer A source line carried as their
 * own `author` / `line` fields when the ingest name map knows them.
 *
 * No line is emitted on success, IoFailure, resource exhaustion, or any
 * other non-denial termination — [forError] returns null for those.
 */
internal object DenialLine {

    const val PREFIX = "strand:denial "

    /**
     * The single denial line for [error], or null when [error] is not a
     * capability/refinement denial (no line is emitted for non-denial
     * terminations).
     */
    fun forError(error: InterpretError, annotator: NodeRefAnnotator): String? {
        val report = when (error) {
            is InterpretError.CapabilityViolation -> error.report
            is InterpretError.RefinementViolation -> error.report
            else -> return null
        }
        return forReport(report, annotator)
    }

    /**
     * Render [report] as the `strand:denial {json}` line. kotlinx
     * serialization's compact printer guarantees a single physical line
     * (no embedded newlines); field values are already rendered and
     * scrubbed at report construction.
     */
    fun forReport(report: DenialReport, annotator: NodeRefAnnotator): String {
        val json = buildJsonObject {
            put("category", JsonPrimitive(report.category))
            put(
                "requested",
                report.requested?.let { values ->
                    buildJsonArray { for (v in values) add(JsonPrimitive(v)) }
                } ?: JsonNull,
            )
            put(
                "held",
                report.held?.let { values ->
                    buildJsonArray { for (v in values) add(JsonPrimitive(v)) }
                } ?: JsonNull,
            )
            put("node", report.node?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put(
                "author",
                report.node?.let { annotator.authorIdOf(it) }?.let { JsonPrimitive(it) } ?: JsonNull,
            )
            put(
                "line",
                report.node?.let { annotator.sourceLineOf(it) }?.let { JsonPrimitive(it) } ?: JsonNull,
            )
            put("instance", report.instanceId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("eventIndex", report.eventIndex?.let { JsonPrimitive(it) } ?: JsonNull)
            put("phase", JsonPrimitive(report.phase.wire))
        }
        return PREFIX + json.toString()
    }

    /**
     * Emit the denial line for [error] to stderr when [error] is a denial;
     * no-op otherwise. Every CLI denial-termination path routes through
     * here (or [emitReport]) exactly once, which is what makes "exactly one
     * line per terminated run" hold by construction.
     */
    fun emitIfDenial(error: InterpretError, annotator: NodeRefAnnotator) {
        forError(error, annotator)?.let { System.err.println(it) }
    }

    /** Emit the denial line for an already-extracted [report] to stderr. */
    fun emitReport(report: DenialReport, annotator: NodeRefAnnotator) {
        System.err.println(forReport(report, annotator))
    }
}
