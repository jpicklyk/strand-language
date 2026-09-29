package org.strand.cli

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.strand.interpreter.AuditOutcome
import org.strand.interpreter.AuditRecord
import org.strand.interpreter.AuditSink
import java.io.BufferedWriter

/**
 * Q-055: the CLI's `strand run --audit <file>` sink. Writes each
 * [AuditRecord] as one newline-delimited JSON object, mirroring the Q-064
 * `strand:denial` line's compact shape but covering the allowed path too.
 * Unlike the always-on denial line (denials are terminal, so at most one
 * fires), the audit log is opt-in via the flag — an allowed run may emit
 * many records.
 *
 * The JSON shape is the [AuditRecord] field-for-field, with the [author] and
 * [line] source-map fields the denial line also carries. Field values are
 * already rendered and credential-scrubbed at record construction.
 *
 * The writer is flushed per record and closed when the run completes so a
 * partial run still leaves a readable log.
 */
internal class FileAuditSink(
    private val writer: BufferedWriter,
    private val annotator: NodeRefAnnotator,
) : AuditSink {

    private val lock = Any()

    override fun record(record: AuditRecord) {
        val line = render(record, annotator)
        synchronized(lock) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    fun close() {
        synchronized(lock) { writer.close() }
    }

    companion object {
        /** Render one [AuditRecord] as a single-line JSON object. */
        fun render(record: AuditRecord, annotator: NodeRefAnnotator): String {
            val outcome = when (record.outcome) {
                AuditOutcome.Allowed -> "allowed"
                is AuditOutcome.Denied -> "denied"
            }
            val json = buildJsonObject {
                put("outcome", JsonPrimitive(outcome))
                put("category", JsonPrimitive(record.effectCategory))
                put(
                    "refinement",
                    buildJsonArray { for (v in record.refinementParameters) add(JsonPrimitive(v)) },
                )
                put("node", record.callSiteNodeId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put(
                    "author",
                    record.callSiteNodeId?.let { annotator.authorIdOf(it) }?.let { JsonPrimitive(it) }
                        ?: JsonNull,
                )
                put(
                    "line",
                    record.callSiteNodeId?.let { annotator.sourceLineOf(it) }?.let { JsonPrimitive(it) }
                        ?: JsonNull,
                )
                put("instance", record.instanceId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("eventIndex", record.eventIndex?.let { JsonPrimitive(it) } ?: JsonNull)
                put("phase", JsonPrimitive(record.phase.wire))
            }
            return json.toString()
        }
    }
}
