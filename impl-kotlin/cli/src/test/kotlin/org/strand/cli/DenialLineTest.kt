package org.strand.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.ExhaustionKind
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.DenialPhase
import org.strand.interpreter.DenialReport
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value

/**
 * Q-064 — the CLI's `strand:denial {json}` stderr line. CLI failure paths
 * call `exitProcess`, so (per the CliFederationTest precedent) these tests
 * exercise [DenialLine] — the single helper every denial-termination path
 * in Main.kt routes through exactly once — rather than driving an erroring
 * `main(...)` invocation. Scenarios from the proposal's § 6: one valid JSON
 * line with category/requested/held/annotated node (1), the transition
 * fields (2), report fidelity for scrubbed values (3), kind-only nulls (4),
 * single-line shape (6, including the invariant phase), and no line on
 * non-denial outcomes (7).
 */
class DenialLineTest {

    private val parser = Json

    /** Drive a real refinement denial through the interpreter to get the error the CLI catches. */
    private fun realDenial(): Pair<InterpretError, NodeRefAnnotator> {
        val json = """{
          "version": 1, "root": "outerApp",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "strT":      { "type": "PrimitiveType", "kind": "String" },
            "fsWriteFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                           "parameters": ["strT"] },
            "writeT":    { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                           "effects": ["fsWriteFx"] },
            "write":     { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                           "foreignType": "writeT", "effects": ["fsWriteFx"] },
            "outerP":    { "type": "ParameterDecl", "name": "p", "paramType": "strT" },
            "pRef":      { "type": "VarRef", "binder": "outerP" },
            "writeDecl": { "type": "EffectDecl", "effectType": "fsWriteFx",
                           "parameters": ["pRef"] },
            "writeCall": { "type": "Application", "function": "write",
                           "arguments": ["pRef"], "effectInstances": ["writeDecl"] },
            "outerLam":  { "type": "Lambda", "parameters": ["outerP"], "body": "writeCall",
                           "effects": ["fsWriteFx"] },
            "pathLit":   { "type": "StringLit", "value": "/etc/passwd" },
            "outerApp":  { "type": "Application", "function": "outerLam",
                           "arguments": ["pathLit"] }
          }
        }"""
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val fsWrite = ingest.nameMap.getValue("fsWriteFx")
        val granted = CapabilitySet(mapOf(fsWrite to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/work/log.txt")))),
        )))
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(finalized.store, finalized.hashToNodeId).eval(finalized.root, granted)
        }
        // The annotator the CLI would build for this program, with a Layer A
        // source line for the denying call (the `strand author` path).
        val annotator = NodeRefAnnotator(
            ingest.nameMap,
            sourceLines = mapOf("writeCall" to 9),
        )
        return ex.error to annotator
    }

    private fun parseLine(line: String): JsonObject {
        assertTrue(line.startsWith(DenialLine.PREFIX)) { "missing prefix: $line" }
        return parser.parseToJsonElement(line.removePrefix(DenialLine.PREFIX)) as JsonObject
    }

    // ----- Scenario 1: one valid JSON line, category/requested/held/annotated node -----

    @Test
    fun `run denial renders one valid JSON line with category, requested, held, and annotated node`() {
        val (error, annotator) = realDenial()
        val line = DenialLine.forError(error, annotator)!!
        val json = parseLine(line)
        assertEquals("Filesystem.Write", json.getValue("category").jsonPrimitive.content)
        assertEquals(
            listOf("/etc/passwd"),
            json.getValue("requested").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("Filesystem.Write{/tmp/work/log.txt}"),
            json.getValue("held").jsonArray.map { it.jsonPrimitive.content },
        )
        // Node renders as #N with the Q-051 author id and source line as
        // their own fields.
        val report = (error as InterpretError.RefinementViolation).report
        assertEquals(report.node.toString(), json.getValue("node").jsonPrimitive.content)
        assertEquals("writeCall", json.getValue("author").jsonPrimitive.content)
        assertEquals(9, json.getValue("line").jsonPrimitive.content.toInt())
        assertEquals(JsonNull, json.getValue("instance"))
        assertEquals(JsonNull, json.getValue("eventIndex"))
        assertEquals("expression", json.getValue("phase").jsonPrimitive.content)
    }

    // ----- Scenario 2 (stderr half): transition fields -----

    @Test
    fun `transition report renders instance, eventIndex, and phase transition`() {
        val report = DenialReport(
            category = "Filesystem.Write",
            requested = listOf("evil"),
            held = listOf("Filesystem.Write{ok}"),
            node = NodeId(12),
            instanceId = "instance-abc",
            eventIndex = 1,
            phase = DenialPhase.Transition,
        )
        val json = parseLine(DenialLine.forReport(report, NodeRefAnnotator(emptyMap())))
        assertEquals("instance-abc", json.getValue("instance").jsonPrimitive.content)
        assertEquals(1, json.getValue("eventIndex").jsonPrimitive.content.toInt())
        assertEquals("transition", json.getValue("phase").jsonPrimitive.content)
        // No name map → node renders bare, author/line null.
        assertEquals("#12", json.getValue("node").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("author"))
        assertEquals(JsonNull, json.getValue("line"))
    }

    // ----- Scenario 3 (stderr half): the line is the report field-for-field -----

    @Test
    fun `line carries the report's scrubbed values verbatim`() {
        // Scrubbing happens at report construction (asserted against the
        // real CredentialScrubber in the interpreter module's
        // DenialReportTest); the line must carry those values verbatim,
        // never re-deriving from raw values it does not have.
        val report = DenialReport(
            category = "LLM.Generate",
            requested = listOf("/keys/[REDACTED:anthropic:api_key]/upload"),
            held = listOf("LLM.Generate{anthropic, *}"),
            node = null,
            instanceId = null,
            eventIndex = null,
            phase = DenialPhase.Expression,
        )
        val line = DenialLine.forReport(report, NodeRefAnnotator(emptyMap()))
        val json = parseLine(line)
        assertEquals(
            listOf("/keys/[REDACTED:anthropic:api_key]/upload"),
            json.getValue("requested").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    // ----- Scenario 4 (stderr half): kind-only verbosity drops values -----

    @Test
    fun `kind-only report renders null requested and held`() {
        val report = DenialReport(
            category = "Filesystem.Write",
            requested = null,
            held = null,
            node = NodeId(7),
            instanceId = null,
            eventIndex = null,
            phase = DenialPhase.Expression,
        )
        val json = parseLine(DenialLine.forReport(report, NodeRefAnnotator(emptyMap())))
        assertEquals("Filesystem.Write", json.getValue("category").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("requested"))
        assertEquals(JsonNull, json.getValue("held"))
        assertEquals("#7", json.getValue("node").jsonPrimitive.content)
    }

    // ----- Scenario 6: single-line shape, including the invariant phase -----

    @Test
    fun `the denial line is exactly one physical line`() {
        val (error, annotator) = realDenial()
        val line = DenialLine.forError(error, annotator)!!
        assertFalse('\n' in line || '\r' in line) { "denial line must be a single physical line: $line" }
    }

    @Test
    fun `invariant-phase report renders phase invariant`() {
        val report = DenialReport(
            category = "Test.Fx",
            requested = emptyList(),
            held = emptyList(),
            node = null,
            instanceId = null,
            eventIndex = null,
            phase = DenialPhase.Invariant,
        )
        val json = parseLine(DenialLine.forReport(report, NodeRefAnnotator(emptyMap())))
        assertEquals("invariant", json.getValue("phase").jsonPrimitive.content)
    }

    // ----- Scenario 7: no line on non-denial outcomes -----

    @Test
    fun `non-denial errors produce no line`() {
        val annotator = NodeRefAnnotator(emptyMap())
        assertNull(DenialLine.forError(
            InterpretError.IoFailure(at = NodeId(1), kind = "filesystem-read", detail = "missing"),
            annotator,
        ))
        assertNull(DenialLine.forError(
            InterpretError.ResourceExhaustion(
                at = null, kind = ExhaustionKind.Steps, current = 11, limit = 10,
            ),
            annotator,
        ))
        assertNull(DenialLine.forError(
            InterpretError.NoMatchingCase(at = NodeId(3)),
            annotator,
        ))
        assertNull(DenialLine.forError(
            InterpretError.SchemaInvariantViolation(
                at = NodeId(4), schema = NodeId(5), invariant = NodeId(6), valueDescription = "x",
            ),
            annotator,
        ))
    }
}
