package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.bytecode.Lowerer
import org.strand.core.ErrorVerbosity
import org.strand.core.EvaluationLimits
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher
import org.strand.interpreter.AuditOutcome
import org.strand.interpreter.AuditRecord
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.CollectingAuditSink
import org.strand.interpreter.DenialPhase
import org.strand.interpreter.HostContext
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import org.strand.vm.Vm

/**
 * Parity of the VM's foreign-dispatch capability check with the interpreter's
 * `dispatchForeign` / `checkCapabilities`. Each case runs the same verified
 * program on both backends under the same grant and asserts value parity on
 * success and full error parity (variant, category, requirement, report,
 * call-site NodeId) on denial.
 *
 * Unrefined-grant rule (review H3): a foreign dispatch is a performing site,
 * so a parameterized category it declares but does not instantiate (no
 * EffectDecl, no Q-039 projection) is covered only by an unrefined grant. The
 * VM applied it at none of its foreign-dispatch paths — a direct CALL, a
 * foreign handler standing in for an intercepted call, and `applyClosure` on a
 * foreign callable — so a refined grant let the instance-free call through.
 *
 * Programs bind `strand-builtin:Test.EffectfulNoOp` (exempt from the builtin
 * effect floor; returns IntV(0)) so no real I/O runs.
 */
class VmForeignDispatchParityTest {

    private class Program(
        val store: NodeStore,
        val root: NodeId,
        val hashToNodeId: Map<Hash, NodeId>,
        val names: Map<String, NodeId>,
    ) {
        fun id(name: String): NodeId = names.getValue(name)
    }

    private fun load(json: String): Program {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "program failed verify: $verify" }
        return Program(finalized.store, finalized.root, finalized.hashToNodeId, ingest.nameMap)
    }

    private fun host(): HostContext = HostContext.processDefault()

    private fun interp(p: Program, caps: CapabilitySet, limits: EvaluationLimits = EvaluationLimits.DEFAULTS): Value =
        Interpreter(p.store, p.hashToNodeId, hostContext = host()).eval(p.root, caps, limits)

    private fun vm(p: Program, caps: CapabilitySet, limits: EvaluationLimits = EvaluationLimits.DEFAULTS): Value =
        Vm(Lowerer(p.store, p.hashToNodeId).lower(p.root), host()).run(caps, limits)

    private fun assertValueParity(p: Program, caps: CapabilitySet, expected: Value) {
        assertEquals(expected, interp(p, caps), "interpreter value")
        assertEquals(expected, vm(p, caps), "VM value")
    }

    private fun assertDenialParity(
        p: Program,
        caps: CapabilitySet,
        limits: EvaluationLimits = EvaluationLimits.DEFAULTS,
    ): InterpretError {
        val i = assertThrows<InterpretException> { interp(p, caps, limits) }.error
        val v = assertThrows<InterpretException> { vm(p, caps, limits) }.error
        assertTrue(i is InterpretError.CapabilityViolation || i is InterpretError.RefinementViolation) {
            "expected a capability denial from the interpreter, got $i"
        }
        assertEquals(i, v) { "VM denial must equal the interpreter's" }
        return v
    }

    private fun refined(category: NodeId, path: String) = CapabilitySet(mapOf(
        category to listOf(CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV(path))))),
    ))

    private fun CapabilitySet.plus(other: CapabilitySet) = CapabilitySet(grants + other.grants)

    // ----- programs -----

    /** The shared node set: a parameterized Filesystem.Write and a parameterless Tick. */
    private val common = """
        "intT":      { "type": "PrimitiveType", "kind": "Int" },
        "strT":      { "type": "PrimitiveType", "kind": "String" },
        "fsWriteFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                       "parameters": ["strT"] },
        "tickFx":    { "type": "EffectCategory", "categoryName": "Test.Tick" },
        "writeT":    { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                       "effects": ["fsWriteFx"] },
        "write":     { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                       "foreignType": "writeT", "effects": ["fsWriteFx"] },
        "tickT":     { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                       "effects": ["tickFx"] },
        "tick":      { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                       "foreignType": "tickT", "effects": ["tickFx"] },
        "pathLit":   { "type": "StringLit", "value": "/tmp/a" }
    """

    /** `write("/tmp/a")` with no EffectDecl: the parameterized category is uninstantiated. */
    private fun directUninstantiated() = load("""{
      "version": 1, "root": "app",
      "nodes": { $common,
        "app": { "type": "Application", "function": "write", "arguments": ["pathLit"] }
      }
    }""")

    /** `handle tickFx with write in tick("/tmp/a")`: the foreign handler performs uninstantiated. */
    private fun foreignHandler() = load("""{
      "version": 1, "root": "handler",
      "nodes": { $common,
        "callTick": { "type": "Application", "function": "tick", "arguments": ["pathLit"] },
        "handler":  { "type": "Handler", "intercept": "tickFx", "handle": "write", "body": "callTick" }
      }
    }""")

    /** The bare foreign callable, for the `applyClosure` / `applyCallable` boundary. */
    private fun bareForeign() = load("""{
      "version": 1, "root": "write",
      "nodes": { $common }
    }""")

    // ----- (i) direct CALL -----

    @Test
    fun `uninstantiated parameterized category under a refined grant - both raise the same RefinementViolation`() {
        val p = directUninstantiated()
        val fsWrite = p.id("fsWriteFx")
        val err = assertDenialParity(p, refined(fsWrite, "/tmp/a"))
        err as InterpretError.RefinementViolation
        assertEquals(fsWrite, err.category)
        assertEquals(emptyList<Value>(), err.requirement)
        assertEquals(p.id("app"), err.at)
        assertEquals(listOf("*"), err.report.requested)
        assertEquals(listOf("Filesystem.Write{/tmp/a}"), err.report.held)
        assertEquals(p.id("app"), err.report.node)
    }

    @Test
    fun `uninstantiated parameterized category under a wildcard grant - both succeed`() {
        val p = directUninstantiated()
        val fsWrite = p.id("fsWriteFx")
        assertValueParity(p, CapabilitySet.ofCategories(setOf(fsWrite)), Value.IntV(0))
        assertValueParity(
            p,
            CapabilitySet(mapOf(fsWrite to listOf(CapabilityPattern(listOf(CapabilityArgument.Wildcard))))),
            Value.IntV(0),
        )
    }

    @Test
    fun `unrefined-grant denial under RedactedWithKindOnly - both withhold the requested list`() {
        val p = directUninstantiated()
        val limits = EvaluationLimits.DEFAULTS.copy(errorVerbosity = ErrorVerbosity.RedactedWithKindOnly)
        val err = assertDenialParity(p, refined(p.id("fsWriteFx"), "/tmp/a"), limits)
        assertNull((err as InterpretError.RefinementViolation).report.requested)
    }

    // ----- (ii) foreign handler, applyClosure -----

    @Test
    fun `foreign handler performing an uninstantiated parameterized category - same RefinementViolation`() {
        val p = foreignHandler()
        val tick = p.id("tickFx")
        val fsWrite = p.id("fsWriteFx")
        val caps = CapabilitySet.ofCategories(setOf(tick)).plus(refined(fsWrite, "/tmp/a"))
        val err = assertDenialParity(p, caps)
        err as InterpretError.RefinementViolation
        assertEquals(fsWrite, err.category)
        assertEquals(p.id("callTick"), err.at)
        assertEquals(listOf("*"), err.report.requested)
    }

    @Test
    fun `foreign handler under wildcard grants - both succeed`() {
        val p = foreignHandler()
        assertValueParity(p, CapabilitySet.ofCategories(setOf(p.id("tickFx"), p.id("fsWriteFx"))), Value.IntV(0))
    }

    @Test
    fun `applyClosure on a foreign callable under a refined grant - same RefinementViolation at the boundary`() {
        val p = bareForeign()
        val fsWrite = p.id("fsWriteFx")
        val caps = refined(fsWrite, "/tmp/a")
        val args = listOf(Value.StringV("/tmp/a"))

        val interpreter = Interpreter(p.store, p.hashToNodeId, hostContext = host())
        val fnI = interpreter.eval(p.root, CapabilitySet.EMPTY)
        val i = assertThrows<InterpretException> { interpreter.applyCallable(fnI, args, caps) }.error

        val vm = Vm(Lowerer(p.store, p.hashToNodeId).lower(p.root), host())
        val fnV = vm.evaluate(CapabilitySet.EMPTY)
        val v = assertThrows<InterpretException> { vm.applyClosure(fnV, args, caps) }.error

        assertTrue(i is InterpretError.RefinementViolation) { "interpreter: $i" }
        assertEquals(i, v)
        assertNull((v as InterpretError.RefinementViolation).report.node)
        assertEquals(listOf("*"), v.report.requested)

        // Under the wildcard grant both apply the callable.
        val wildcard = CapabilitySet.ofCategories(setOf(fsWrite))
        assertEquals(Value.IntV(0), interpreter.applyCallable(fnI, args, wildcard))
        assertEquals(Value.IntV(0), vm.applyClosure(fnV, args, wildcard))
    }

    @Test
    fun `applyClosure on a projected foreign callable - the boundary synthesizes the instance from the argument`() {
        // Before the fix the VM's boundary check was instance-free, so a
        // refined grant for a different path let the projected call through.
        val p = load("""{
          "version": 1, "root": "pwrite",
          "nodes": { $common,
            "pwrite": { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                        "foreignType": "writeT", "effects": ["fsWriteFx"],
                        "effectProjections": [
                          { "category": "fsWriteFx", "sources": [ { "kind": "ArgRef", "index": 0 } ] }
                        ] }
          }
        }""")
        val caps = refined(p.id("fsWriteFx"), "/tmp/a")

        val interpreter = Interpreter(p.store, p.hashToNodeId, hostContext = host())
        val fnI = interpreter.eval(p.root, CapabilitySet.EMPTY)
        val vm = Vm(Lowerer(p.store, p.hashToNodeId).lower(p.root), host())
        val fnV = vm.evaluate(CapabilitySet.EMPTY)

        assertEquals(Value.IntV(0), interpreter.applyCallable(fnI, listOf(Value.StringV("/tmp/a")), caps))
        assertEquals(Value.IntV(0), vm.applyClosure(fnV, listOf(Value.StringV("/tmp/a")), caps))

        val other = listOf(Value.StringV("/etc/passwd"))
        val i = assertThrows<InterpretException> { interpreter.applyCallable(fnI, other, caps) }.error
        val v = assertThrows<InterpretException> { vm.applyClosure(fnV, other, caps) }.error
        assertTrue(i is InterpretError.RefinementViolation) { "interpreter: $i" }
        assertEquals(i, v)
        assertEquals(other, (v as InterpretError.RefinementViolation).requirement)
    }

    @Test
    fun `Lambda call sites stay propagating - an uninstantiated category under a refined grant passes through`() {
        // The outer Lambda declares Filesystem.Write and is called without an
        // EffectDecl; the inner foreign call instantiates it. Only the inner,
        // performing site is refinement-checked.
        val p = load("""{
          "version": 1, "root": "outerApp",
          "nodes": { $common,
            "outerP":    { "type": "ParameterDecl", "name": "p", "paramType": "strT" },
            "pRef":      { "type": "VarRef", "binder": "outerP" },
            "writeDecl": { "type": "EffectDecl", "effectType": "fsWriteFx", "parameters": ["pRef"] },
            "innerApp":  { "type": "Application", "function": "write",
                           "arguments": ["pRef"], "effectInstances": ["writeDecl"] },
            "outerLam":  { "type": "Lambda", "parameters": ["outerP"], "body": "innerApp",
                           "effects": ["fsWriteFx"] },
            "outerApp":  { "type": "Application", "function": "outerLam", "arguments": ["pathLit"] }
          }
        }""")
        assertValueParity(p, refined(p.id("fsWriteFx"), "/tmp/a"), Value.IntV(0))
    }

    // ----- foreign effect row: foreignType effects ∪ ForeignNode.effects -----

    /**
     * A ForeignNode whose effect is declared only on its foreignType. The
     * interpreter's row (`foreignEffectRow`) is the union of the two lists,
     * as the verifier types it; the Lowerer used to carry `effects` only, so
     * the VM saw an effect-free callee.
     */
    private val typeOnlyTick = """
        "typeTick": { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                      "foreignType": "tickT", "effects": [] },
        "callTypeTick": { "type": "Application", "function": "typeTick", "arguments": ["pathLit"] }
    """

    @Test
    fun `effect declared only on the foreignType - both backends check it against the grant`() {
        val p = load("""{
          "version": 1, "root": "callTypeTick",
          "nodes": { $common, $typeOnlyTick }
        }""")
        val err = assertDenialParity(p, CapabilitySet.EMPTY)
        assertEquals(setOf(p.id("tickFx")), (err as InterpretError.CapabilityViolation).missing)
        assertValueParity(p, CapabilitySet.ofCategories(setOf(p.id("tickFx"))), Value.IntV(0))
    }

    @Test
    fun `effect declared only on the foreignType - both backends route the call to the handler`() {
        // The handler returns the argument's length stand-in via a Lambda
        // returning a distinct literal, so interception is observable.
        val p = load("""{
          "version": 1, "root": "handler",
          "nodes": { $common, $typeOnlyTick,
            "hP":      { "type": "ParameterDecl", "name": "s", "paramType": "strT" },
            "seven":   { "type": "IntLit", "value": 7 },
            "hLam":    { "type": "Lambda", "parameters": ["hP"], "body": "seven", "effects": [] },
            "handler": { "type": "Handler", "intercept": "tickFx", "handle": "hLam", "body": "callTypeTick" }
          }
        }""")
        assertValueParity(p, CapabilitySet.EMPTY, Value.IntV(7))
    }

    // ----- builtin effect floor (defence in depth for unverified stores) -----

    @Test
    fun `under-declared effect floor on an unverified store - both raise the same BuiltinContractViolation`() {
        // Time.Now with an empty row: the verifier's BuiltinEffectMismatch
        // rejects it, so the store is taken unverified. Before the fix the VM
        // dispatched the builtin under an empty grant.
        val ingest = JsonIngest.parse("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT": { "type": "PrimitiveType", "kind": "Int" },
            "nowT": { "type": "FunctionType", "parameters": [], "result": "intT", "effects": [] },
            "now":  { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                      "foreignType": "nowT", "effects": [] },
            "app":  { "type": "Application", "function": "now", "arguments": [] }
          }
        }""")
        val f = Hasher(ingest.rawStore).finalize(ingest.root)
        val p = Program(f.store, f.root, f.hashToNodeId, ingest.nameMap)
        val i = assertThrows<InterpretException> { interp(p, CapabilitySet.EMPTY) }.error
        val v = assertThrows<InterpretException> { vm(p, CapabilitySet.EMPTY) }.error
        assertTrue(i is InterpretError.BuiltinContractViolation) { "interpreter: $i" }
        assertEquals(i, v)
        assertEquals(p.id("app"), (v as InterpretError.BuiltinContractViolation).at)

        // The same floor at the applyClosure boundary.
        val interpreter = Interpreter(p.store, p.hashToNodeId, hostContext = host())
        val vmInst = Vm(Lowerer(p.store, p.hashToNodeId).lower(p.id("now")), host())
        val bi = assertThrows<InterpretException> {
            interpreter.applyCallable(interpreter.eval(p.id("now"), CapabilitySet.EMPTY), emptyList(), CapabilitySet.EMPTY)
        }.error
        val bv = assertThrows<InterpretException> {
            vmInst.applyClosure(vmInst.evaluate(CapabilitySet.EMPTY), emptyList(), CapabilitySet.EMPTY)
        }.error
        assertTrue(bi is InterpretError.BuiltinContractViolation) { "interpreter: $bi" }
        assertEquals(bi, bv)
    }

    // ----- (iii) Q-055 audit-record parity -----

    /**
     * Run [p] on both backends under [caps], each with its own collecting
     * audit sink, assert the outcomes agree (equal value, or equal error), and
     * assert the two record sequences are equal. Returns the records.
     */
    private fun assertAuditParity(p: Program, caps: CapabilitySet): List<AuditRecord> {
        val iSink = CollectingAuditSink()
        val vSink = CollectingAuditSink()
        val iOut = runCatching {
            Interpreter(p.store, p.hashToNodeId, hostContext = host().copy(auditSink = iSink)).eval(p.root, caps)
        }
        val vOut = runCatching {
            Vm(Lowerer(p.store, p.hashToNodeId).lower(p.root), host().copy(auditSink = vSink)).run(caps)
        }
        for (out in listOf(iOut, vOut)) {
            out.exceptionOrNull()?.let { e -> assertTrue(e is InterpretException) { "unexpected failure: $e" } }
        }
        assertEquals(iOut.getOrNull(), vOut.getOrNull(), "value parity")
        assertEquals(
            (iOut.exceptionOrNull() as? InterpretException)?.error,
            (vOut.exceptionOrNull() as? InterpretException)?.error,
            "error parity",
        )
        assertEquals(iSink.records, vSink.records, "audit record sequences must be equal")
        return vSink.records
    }

    /** `write("/tmp/a") [Filesystem.Write{"/tmp/a"}]` then `write("/tmp/b") [Filesystem.Write{"/tmp/b"}]`. */
    private fun twoInstantiatedWrites() = load("""{
      "version": 1, "root": "seq",
      "nodes": { $common,
        "pathB":  { "type": "StringLit", "value": "/tmp/b" },
        "declA":  { "type": "EffectDecl", "effectType": "fsWriteFx", "parameters": ["pathLit"] },
        "declB":  { "type": "EffectDecl", "effectType": "fsWriteFx", "parameters": ["pathB"] },
        "appA":   { "type": "Application", "function": "write", "arguments": ["pathLit"],
                    "effectInstances": ["declA"] },
        "appB":   { "type": "Application", "function": "write", "arguments": ["pathB"],
                    "effectInstances": ["declB"] },
        "seq":    { "type": "Let", "name": "_first", "value": "appA", "body": "appB" }
      }
    }""")

    private fun grantPaths(category: NodeId, vararg paths: String) = CapabilitySet(mapOf(
        category to paths.map { CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV(it)))) },
    ))

    @Test
    fun `allowed instantiated calls - equal Allowed record sequences`() {
        val p = twoInstantiatedWrites()
        val records = assertAuditParity(p, grantPaths(p.id("fsWriteFx"), "/tmp/a", "/tmp/b"))
        assertEquals(2, records.size)
        assertTrue(records.all { it.outcome == AuditOutcome.Allowed })
        assertEquals(listOf(p.id("appA"), p.id("appB")), records.map { it.callSiteNodeId })
        assertEquals(listOf(listOf("/tmp/a"), listOf("/tmp/b")), records.map { it.refinementParameters })
        assertEquals("Filesystem.Write", records[0].effectCategory)
        assertEquals(DenialPhase.Expression, records[0].phase)
    }

    @Test
    fun `allowed then refinement-denied - equal Allowed, Denied sequence`() {
        val p = twoInstantiatedWrites()
        val records = assertAuditParity(p, grantPaths(p.id("fsWriteFx"), "/tmp/a"))
        assertEquals(AuditOutcome.Allowed, records[0].outcome)
        val denied = records[1].outcome as AuditOutcome.Denied
        assertEquals(p.id("appB"), records[1].callSiteNodeId)
        assertEquals(listOf("/tmp/b"), records[1].refinementParameters)
        assertEquals(listOf("Filesystem.Write{/tmp/a}"), denied.report.held)
    }

    @Test
    fun `missing-category denial - equal single Denied record`() {
        val p = twoInstantiatedWrites()
        val record = assertAuditParity(p, CapabilitySet.EMPTY).single()
        val denied = record.outcome as AuditOutcome.Denied
        assertEquals("Filesystem.Write", record.effectCategory)
        assertEquals(listOf("/tmp/a"), record.refinementParameters)
        assertEquals(emptyList<String>(), denied.report.held)
    }

    @Test
    fun `refinement denial through a propagating Lambda - equal single Denied record at the inner site`() {
        val p = load("""{
          "version": 1, "root": "outerApp",
          "nodes": { $common,
            "outerP":    { "type": "ParameterDecl", "name": "p", "paramType": "strT" },
            "pRef":      { "type": "VarRef", "binder": "outerP" },
            "writeDecl": { "type": "EffectDecl", "effectType": "fsWriteFx", "parameters": ["pRef"] },
            "innerApp":  { "type": "Application", "function": "write",
                           "arguments": ["pRef"], "effectInstances": ["writeDecl"] },
            "outerLam":  { "type": "Lambda", "parameters": ["outerP"], "body": "innerApp",
                           "effects": ["fsWriteFx"] },
            "outerApp":  { "type": "Application", "function": "outerLam", "arguments": ["pathLit"] }
          }
        }""")
        val record = assertAuditParity(p, refined(p.id("fsWriteFx"), "/etc/passwd")).single()
        assertTrue(record.outcome is AuditOutcome.Denied)
        assertEquals(p.id("innerApp"), record.callSiteNodeId)
        assertEquals(listOf("/tmp/a"), record.refinementParameters)
    }

    @Test
    fun `unrefined-grant denial - equal Denied record with the wildcard request`() {
        val p = directUninstantiated()
        val record = assertAuditParity(p, refined(p.id("fsWriteFx"), "/tmp/a")).single()
        assertTrue(record.outcome is AuditOutcome.Denied)
        assertEquals(listOf("*"), record.refinementParameters)
        assertEquals(p.id("app"), record.callSiteNodeId)
    }

    @Test
    fun `instance-free performing call under a wildcard grant - neither backend emits a record`() {
        val p = directUninstantiated()
        assertEquals(emptyList<AuditRecord>(), assertAuditParity(p, CapabilitySet.ofCategories(setOf(p.id("fsWriteFx")))))
    }

    @Test
    fun `applyClosure boundary - the Allowed record carries a null call site on both backends`() {
        val p = load("""{
          "version": 1, "root": "pwrite",
          "nodes": { $common,
            "pwrite": { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                        "foreignType": "writeT", "effects": ["fsWriteFx"],
                        "effectProjections": [
                          { "category": "fsWriteFx", "sources": [ { "kind": "ArgRef", "index": 0 } ] }
                        ] }
          }
        }""")
        val caps = refined(p.id("fsWriteFx"), "/tmp/a")
        val args = listOf(Value.StringV("/tmp/a"))
        val iSink = CollectingAuditSink()
        val vSink = CollectingAuditSink()
        val interpreter = Interpreter(p.store, p.hashToNodeId, hostContext = host().copy(auditSink = iSink))
        interpreter.applyCallable(interpreter.eval(p.root, CapabilitySet.EMPTY), args, caps)
        val vm = Vm(Lowerer(p.store, p.hashToNodeId).lower(p.root), host().copy(auditSink = vSink))
        vm.applyClosure(vm.evaluate(CapabilitySet.EMPTY), args, caps)

        assertEquals(iSink.records, vSink.records)
        val record = vSink.records.single()
        assertEquals(AuditOutcome.Allowed, record.outcome)
        assertNull(record.callSiteNodeId)
        assertEquals(listOf("/tmp/a"), record.refinementParameters)
    }
}
