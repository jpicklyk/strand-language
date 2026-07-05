package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher

/**
 * Q-055 (`proposals/effect-audit-log.md`) — the structured effect-audit log
 * emitted at the interpreter's foreign-dispatch capability boundary through
 * the per-context [AuditSink]. An effectful program run under a covering grant
 * produces one Allowed record per exercised effect with the right category,
 * call-site, and (scrubbed) refinement value; a denied run produces a Denied
 * record reusing the Q-064 [DenialReport]; and a credential-bearing refinement
 * value appears scrubbed in the record (the Q-042 seam).
 *
 * The default no-op sink means every existing run behaves identically — this
 * test opts in by constructing a [HostContext] carrying a
 * [CollectingAuditSink].
 */
class EffectAuditLogTest {

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
     * The Q-064 confused-deputy shape reused: an outer Lambda declaring
     * `Filesystem.Write` whose body calls an effectful foreign with an
     * EffectDecl refinement on the caller-supplied path. `Test.EffectfulNoOp`
     * returns IntV(0) so the allowed path evaluates to a value.
     */
    private fun buildWriteCall(path: String): Loaded = load("""{
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
        "innerApp":  { "type": "Application", "function": "write",
                       "arguments": ["pRef"], "effectInstances": ["writeDecl"] },
        "outerLam":  { "type": "Lambda", "parameters": ["outerP"], "body": "innerApp",
                       "effects": ["fsWriteFx"] },
        "pathLit":   { "type": "StringLit", "value": ${jsonString(path)} },
        "outerApp":  { "type": "Application", "function": "outerLam",
                       "arguments": ["pathLit"] }
      }
    }""")

    private fun grant(category: NodeId, vararg paths: String): CapabilitySet = CapabilitySet(mapOf(
        category to paths.map {
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV(it))))
        }
    ))

    private fun wildcardGrant(category: NodeId): CapabilitySet = CapabilitySet(mapOf(
        category to listOf(CapabilityPattern(listOf(CapabilityArgument.Wildcard)))
    ))

    private fun interpreterWith(l: Loaded, sink: AuditSink): Interpreter =
        Interpreter(
            l.store, l.hashToNodeId,
            hostContext = HostContext.processDefault().copy(auditSink = sink),
        )

    @Test
    fun `an allowed effect dispatch emits one Allowed record with category and call-site`() {
        val l = buildWriteCall("/tmp/a")
        val fsWrite = l.names.getValue("fsWriteFx")
        val sink = CollectingAuditSink()

        val result = interpreterWith(l, sink).eval(l.root, grant(fsWrite, "/tmp/a"))
        assertEquals(Value.IntV(0), result)

        val record = sink.records.single()
        assertEquals(AuditOutcome.Allowed, record.outcome)
        assertEquals("Filesystem.Write", record.effectCategory)
        assertEquals(l.names.getValue("innerApp"), record.callSiteNodeId)
        assertEquals(listOf("/tmp/a"), record.refinementParameters)
        assertEquals(DenialPhase.Expression, record.phase)
    }

    @Test
    fun `a denied effect dispatch emits one Denied record reusing the denial report`() {
        val l = buildWriteCall("/etc/passwd")
        val fsWrite = l.names.getValue("fsWriteFx")
        val sink = CollectingAuditSink()

        assertThrows(InterpretException::class.java) {
            interpreterWith(l, sink).eval(l.root, grant(fsWrite, "/tmp/a"))
        }

        val record = sink.records.single()
        val denied = record.outcome as AuditOutcome.Denied
        assertEquals("Filesystem.Write", record.effectCategory)
        assertEquals(listOf("/etc/passwd"), record.refinementParameters)
        // The reused Q-064 report reconciles with the record.
        assertEquals(record.effectCategory, denied.report.category)
        assertEquals(record.callSiteNodeId, denied.report.node)
        assertEquals(listOf("Filesystem.Write{/tmp/a}"), denied.report.held)
    }

    @Test
    fun `the default no-op sink emits nothing`() {
        val l = buildWriteCall("/tmp/a")
        val fsWrite = l.names.getValue("fsWriteFx")
        // Default context (NoOpAuditSink) — the run behaves identically.
        val result = Interpreter(l.store, l.hashToNodeId).eval(l.root, grant(fsWrite, "/tmp/a"))
        assertEquals(Value.IntV(0), result)
    }

    @Test
    fun `a credential-bearing refinement value appears scrubbed in the record`() {
        // Register a credential whose raw value is the path the call site
        // supplies, into a per-context scrubber; the wildcard grant admits the
        // call, and the Allowed record's refinement value must be scrubbed.
        val secretPath = "/secret/sk-audit-test-1234abcd"
        val credential = Credential(secretPath, "audit", "path")
        val scrubber = Scrubber()
        scrubber.register(credential)

        val l = buildWriteCall(secretPath)
        val fsWrite = l.names.getValue("fsWriteFx")
        val sink = CollectingAuditSink()
        val ctx = HostContext.processDefault().copy(auditSink = sink, scrubber = scrubber)

        Interpreter(l.store, l.hashToNodeId, hostContext = ctx).eval(l.root, wildcardGrant(fsWrite))

        val record = sink.records.single()
        assertEquals(AuditOutcome.Allowed, record.outcome)
        assertEquals(listOf("[REDACTED:audit:path]"), record.refinementParameters)
        assertTrue(record.refinementParameters.none { secretPath in it }) {
            "the raw credential path must not appear in the audit record"
        }
    }
}
