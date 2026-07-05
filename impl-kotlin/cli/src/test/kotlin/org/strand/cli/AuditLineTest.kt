package org.strand.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.NodeId
import org.strand.interpreter.AuditOutcome
import org.strand.interpreter.AuditRecord
import org.strand.interpreter.DenialPhase
import org.strand.interpreter.DenialReport
import java.io.StringWriter

/**
 * Q-055 — the CLI's `strand run --audit <file>` NDJSON rendering. Mirrors the
 * Q-064 [DenialLineTest] approach: the CLI's run path calls `exitProcess`, so
 * these tests exercise [FileAuditSink] — the helper the run path installs —
 * rather than driving a `main(...)` invocation.
 */
class AuditLineTest {

    private val parser = Json

    private fun parse(line: String): JsonObject =
        parser.parseToJsonElement(line) as JsonObject

    @Test
    fun `an allowed record renders one valid single-line JSON object`() {
        val record = AuditRecord(
            callSiteNodeId = NodeId(9),
            effectCategory = "Filesystem.Write",
            refinementParameters = listOf("/tmp/a"),
            outcome = AuditOutcome.Allowed,
        )
        val line = FileAuditSink.render(record, NodeRefAnnotator(mapOf("write" to NodeId(9)), sourceLines = mapOf("write" to 4)))
        assertFalse('\n' in line || '\r' in line) { "record must be a single physical line: $line" }
        val json = parse(line)
        assertEquals("allowed", json.getValue("outcome").jsonPrimitive.content)
        assertEquals("Filesystem.Write", json.getValue("category").jsonPrimitive.content)
        assertEquals(listOf("/tmp/a"), json.getValue("refinement").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("#9", json.getValue("node").jsonPrimitive.content)
        assertEquals("write", json.getValue("author").jsonPrimitive.content)
        assertEquals(4, json.getValue("line").jsonPrimitive.content.toInt())
        assertEquals(JsonNull, json.getValue("instance"))
        assertEquals("expression", json.getValue("phase").jsonPrimitive.content)
    }

    @Test
    fun `a denied record renders outcome denied and carries the transition fields`() {
        val report = DenialReport(
            category = "Filesystem.Write",
            requested = listOf("/etc/passwd"),
            held = listOf("Filesystem.Write{/tmp/a}"),
            node = NodeId(12),
            instanceId = "worker-C",
            eventIndex = 2,
            phase = DenialPhase.Transition,
        )
        val record = AuditRecord(
            callSiteNodeId = report.node,
            effectCategory = report.category,
            refinementParameters = report.requested ?: emptyList(),
            outcome = AuditOutcome.Denied(report),
            instanceId = report.instanceId,
            eventIndex = report.eventIndex,
            phase = report.phase,
        )
        val json = parse(FileAuditSink.render(record, NodeRefAnnotator(emptyMap())))
        assertEquals("denied", json.getValue("outcome").jsonPrimitive.content)
        assertEquals("worker-C", json.getValue("instance").jsonPrimitive.content)
        assertEquals(2, json.getValue("eventIndex").jsonPrimitive.content.toInt())
        assertEquals("transition", json.getValue("phase").jsonPrimitive.content)
    }

    @Test
    fun `the file sink writes one newline-delimited record per emission`() {
        val sw = StringWriter()
        val sink = FileAuditSink(sw.buffered(), NodeRefAnnotator(emptyMap()))
        sink.record(AuditRecord(NodeId(1), "A", emptyList(), AuditOutcome.Allowed))
        sink.record(AuditRecord(NodeId(2), "B", emptyList(), AuditOutcome.Allowed))
        sink.close()

        val lines = sw.toString().trim().lines()
        assertEquals(2, lines.size)
        assertEquals("A", parse(lines[0]).getValue("category").jsonPrimitive.content)
        assertEquals("B", parse(lines[1]).getValue("category").jsonPrimitive.content)
        assertTrue(lines.all { it.isNotBlank() })
    }
}
