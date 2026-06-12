package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.ErrorVerbosity
import org.strand.core.EvaluationLimits
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.hashing.Hasher
import org.strand.verifier.TypeExpr

/**
 * Q-064 (`proposals/capability-denial-observability.md`) — the structured
 * [DenialReport] carried on [InterpretError.CapabilityViolation] and
 * [InterpretError.RefinementViolation]. Interpreter-side halves of the
 * proposal's § 6 scenarios: report content (1), scrubbing (3), kind-only
 * verbosity (4), Attempt opacity with an unchanged report (5), and the
 * invariant-phase discriminator (6). The runtime instance/event/phase
 * attachment is covered in the `:runtime` module's `DenialHaltTest`; the
 * `strand:denial` stderr line in the `:cli` module's `DenialLineTest`.
 */
class DenialReportTest {

    @AfterEach
    fun cleanup() {
        CredentialScrubber.resetForTesting()
    }

    private data class Loaded(
        val store: NodeStore,
        val root: NodeId,
        val names: Map<String, NodeId>,
        val hashToNodeId: Map<Hash, NodeId>,
    )

    private fun load(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Loaded(finalized.store, finalized.root, ingest.nameMap, finalized.hashToNodeId)
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * The Q-031 confused-deputy shape: an outer Lambda declaring
     * `Filesystem.Write` whose body calls an effectful foreign with an
     * EffectDecl refinement on the caller-supplied path. Optionally wraps
     * the whole call in an Attempt (scenario 5).
     */
    private fun buildWriteCall(path: String, wrapInAttempt: Boolean = false): Loaded {
        val attemptNode = if (wrapInAttempt)
            """, "att": { "type": "Attempt", "body": "outerApp" }""" else ""
        val root = if (wrapInAttempt) "att" else "outerApp"
        return load("""{
          "version": 1, "root": "$root",
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
            "innerApp":  { "type": "Application", "function": "write",
                           "arguments": ["pRef"], "effectInstances": ["writeDecl"] },
            "outerLam":  { "type": "Lambda", "parameters": ["outerP"], "body": "innerApp",
                           "effects": ["fsWriteFx"] },
            "pathLit":   { "type": "StringLit", "value": ${jsonString(path)} },
            "outerApp":  { "type": "Application", "function": "outerLam",
                           "arguments": ["pathLit"] }$attemptNode
          }
        }""")
    }

    private fun tmpAGrant(category: NodeId): CapabilitySet = CapabilitySet(mapOf(
        category to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/a")))),
        )
    ))

    // ----- Scenario 1 (programmatic surface): report content -----

    @Test
    fun `refinement denial report carries category name, requested, held, and node`() {
        val l = buildWriteCall("/etc/passwd")
        val fsWrite = l.names.getValue("fsWriteFx")
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, tmpAGrant(fsWrite))
        }
        val err = ex.error as InterpretError.RefinementViolation
        val report = err.report
        assertEquals("Filesystem.Write", report.category)
        assertEquals(listOf("/etc/passwd"), report.requested)
        assertEquals(listOf("Filesystem.Write{/tmp/a}"), report.held)
        assertEquals(l.names.getValue("innerApp"), report.node)
        assertNull(report.instanceId)
        assertNull(report.eventIndex)
        assertEquals(DenialPhase.Expression, report.phase)
    }

    @Test
    fun `capability denial report carries category name and an empty held summary`() {
        // Empty context: the category is entirely absent. The denial fires
        // at the outer Application (the closure's instance-free check), so
        // requested is empty (no EffectDecl at that site) and held is empty
        // (nothing granted for the category).
        val l = buildWriteCall("/etc/passwd")
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, CapabilitySet.EMPTY)
        }
        val err = ex.error as InterpretError.CapabilityViolation
        val report = err.report
        assertEquals("Filesystem.Write", report.category)
        assertEquals(emptyList<String>(), report.requested)
        assertEquals(emptyList<String>(), report.held)
        assertEquals(l.names.getValue("outerApp"), report.node)
        assertEquals(DenialPhase.Expression, report.phase)
    }

    @Test
    fun `wildcard grant slots render as star in the held summary`() {
        val l = buildWriteCall("/etc/passwd")
        val fsWrite = l.names.getValue("fsWriteFx")
        // A two-pattern grant: one concrete, one built by ofCategories (the
        // back-compat single-wildcard sentinel) — but a sentinel would cover
        // everything, so use an explicit Wildcard slot in a same-arity
        // pattern... which also covers everything. Instead: two concrete
        // patterns plus assert the explicit Wildcard rendering separately.
        assertEquals(
            "Filesystem.Write{*}",
            DenialReport.renderGrant(
                "Filesystem.Write",
                CapabilityPattern(listOf(CapabilityArgument.Wildcard)),
            ),
        )
        assertEquals(
            "Time.Now",
            DenialReport.renderGrant("Time.Now", CapabilityPattern(emptyList())),
        )
        // And the denial against two concrete grants lists both.
        val granted = CapabilitySet(mapOf(fsWrite to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/a")))),
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/b")))),
        )))
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, granted)
        }
        val report = (ex.error as InterpretError.RefinementViolation).report
        assertEquals(listOf("Filesystem.Write{/tmp/a}", "Filesystem.Write{/tmp/b}"), report.held)
    }

    // ----- Scenario 3 (programmatic surface): scrubbing -----

    @Test
    fun `credential-bearing requested parameter is scrubbed in the report`() {
        // Mint a credential through the public provider seam so it lands in
        // the CredentialScrubber registry, then pass it through the denied
        // call's refinement parameter.
        val secret = "sk-ant-denial-test-9876"
        val provider = StaticCredentialProvider(mapOf("anthropic" to secret))
        val credential = provider.resolve("anthropic", "api_key")
        checkNotNull(credential)

        val l = buildWriteCall("/keys/$secret/upload")
        val fsWrite = l.names.getValue("fsWriteFx")
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, tmpAGrant(fsWrite))
        }
        val report = (ex.error as InterpretError.RefinementViolation).report
        val requested = report.requested!!.single()
        assertTrue(secret !in requested) { "raw credential leaked into the report: $requested" }
        assertEquals("/keys/[REDACTED:anthropic:api_key]/upload", requested)
    }

    // ----- Scenario 4: kind-only verbosity -----

    @Test
    fun `kind-only verbosity withholds requested and held but keeps category and node`() {
        val l = buildWriteCall("/etc/passwd")
        val fsWrite = l.names.getValue("fsWriteFx")
        val limits = EvaluationLimits.DEFAULTS.copy(
            errorVerbosity = ErrorVerbosity.RedactedWithKindOnly,
        )
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, tmpAGrant(fsWrite), limits)
        }
        val report = (ex.error as InterpretError.RefinementViolation).report
        assertEquals("Filesystem.Write", report.category)
        assertNull(report.requested)
        assertNull(report.held)
        assertEquals(l.names.getValue("innerApp"), report.node)
    }

    // ----- Scenario 5: Attempt opacity, report unchanged -----

    @Test
    fun `Attempt around the denying call still terminates and the report is unchanged`() {
        val fsWritePlain = buildWriteCall("/etc/passwd", wrapInAttempt = false)
        val fsWriteWrapped = buildWriteCall("/etc/passwd", wrapInAttempt = true)

        val exPlain = assertThrows(InterpretException::class.java) {
            Interpreter(fsWritePlain.store, fsWritePlain.hashToNodeId)
                .eval(fsWritePlain.root, tmpAGrant(fsWritePlain.names.getValue("fsWriteFx")))
        }
        val exWrapped = assertThrows(InterpretException::class.java) {
            Interpreter(fsWriteWrapped.store, fsWriteWrapped.hashToNodeId)
                .eval(fsWriteWrapped.root, tmpAGrant(fsWriteWrapped.names.getValue("fsWriteFx")))
        }
        // The denial escapes the Attempt (uncatchable — no Ok/Err shaping),
        // and the report is identical apart from the denying NodeId (the
        // two programs are distinct graphs, so ids differ; the denial
        // content does not).
        val plainReport = (exPlain.error as InterpretError.RefinementViolation).report
        val wrappedReport = (exWrapped.error as InterpretError.RefinementViolation).report
        assertEquals(plainReport.copy(node = null), wrappedReport.copy(node = null))
        assertEquals(
            fsWriteWrapped.names.getValue("innerApp"),
            wrappedReport.node,
        ) { "the wrapped program still blames the denying call site, not the Attempt" }
    }

    // ----- Scenario 6 (interpreter half): invariant-phase discriminator -----

    @Test
    fun `denial during invariant evaluation reports phase invariant`() {
        // An invariant body that calls an effectful foreign. The verifier
        // rejects such bodies (SchemaInvariantBodyMustBePure), but the
        // interpreter trusts its input — phase=invariant is the honest
        // representation on the trusting path. Invariant bodies run under
        // an empty capability context, so the call denies.
        val l = load("""{
          "version": 1, "root": "rootLit",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "strT":    { "type": "PrimitiveType", "kind": "String" },
            "fx":      { "type": "EffectCategory", "categoryName": "Test.Fx" },
            "fnT":     { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                         "effects": ["fx"] },
            "eff":     { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                         "foreignType": "fnT", "effects": ["fx"] },
            "vP":      { "type": "ParameterDecl", "name": "v", "paramType": "strT" },
            "vRef":    { "type": "VarRef", "binder": "vP" },
            "call":    { "type": "Application", "function": "eff", "arguments": ["vRef"] },
            "invBody": { "type": "Lambda", "parameters": ["vP"], "body": "call",
                         "effects": ["fx"] },
            "sch":     { "type": "Schema", "schemaName": "EffectfulSchema",
                         "valueType": "strT", "invariants": ["inv"] },
            "inv":     { "type": "Invariant", "invariantName": "effectful",
                         "targetSchema": "sch", "body": "invBody" },
            "rootLit": { "type": "StringLit", "value": "hello" }
          }
        }""")
        val obligations = mapOf(
            l.root to TypeExpr.SchemaType(
                schemaId = l.names.getValue("sch"),
                valueType = TypeExpr.Prim(Primitive.String),
                invariants = listOf(l.names.getValue("inv")),
            )
        )
        val interp = Interpreter(l.store, l.hashToNodeId, schemaObligations = obligations)
        val ex = assertThrows(InterpretException::class.java) {
            interp.eval(l.root, CapabilitySet.EMPTY)
        }
        val err = ex.error as InterpretError.CapabilityViolation
        assertEquals("Test.Fx", err.report.category)
        assertEquals(DenialPhase.Invariant, err.report.phase)
    }
}
