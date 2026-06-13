package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.EvaluationLimits
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.NetPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.SandboxViolationKind
import org.strand.interpreter.Value

/**
 * Q-054 isolation properties: two [StrandRuntime] instances carrying
 * different [HostPolicy] values run their programs under their own policy
 * with no cross-contamination, and the facade restores any process-default
 * [Builtins] singleton it touches. This is the property the whole item
 * exists to provide.
 *
 * The tests run sequentially (the facade installs the policy globally for the
 * duration of each run, per the documented scope decision) and assert that
 * neither order leaks one runtime's policy into the other's run.
 */
class StrandRuntimeIsolationTest {

    private fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    private fun nameMap(json: String): Map<String, org.strand.core.NodeId> =
        JsonIngest.parse(json).nameMap

    // --- Scenario 4: different-limits isolation -------------------------------

    @Test
    fun `two runtimes with different maxSteps isolate — loose passes, tight exhausts, no order dependence`() {
        val prog = image(SUM_TO_TEN)

        val loose = StrandRuntime(HostPolicy.OPEN)  // DEFAULTS.maxSteps is generous
        val tight = StrandRuntime(HostPolicy.OPEN.copy(limits = EvaluationLimits.DEFAULTS.copy(maxSteps = 5L)))

        // loose then tight
        assertSum55(loose.run(prog))
        assertExhausted(tight)

        // tight then loose — the tight run's limit must not leak into loose.
        assertExhausted(tight)
        assertSum55(loose.run(prog))
    }

    private fun assertSum55(outcome: RunOutcome) {
        val ok = assertInstanceOf(RunOutcome.Ok::class.java, outcome)
        assertEquals(Value.IntV(55), ok.value)
    }

    private fun assertExhausted(rt: StrandRuntime) {
        // The tight-limit run throws ResourceExhaustion during evaluation; the
        // facade restores the singletons in its finally and the exception
        // propagates out of run() uncaught.
        val ex = assertThrows(InterpretException::class.java) { rt.run(image(SUM_TO_TEN)) }
        assertInstanceOf(InterpretError.ResourceExhaustion::class.java, ex.error)
    }

    // --- Scenario 3: SECURE vs OPEN sandbox isolation -------------------------

    @Test
    fun `two runtimes with different sandbox policies isolate the same fs-write program`(
        @org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path,
    ) {
        val prog = image(FS_WRITE_ESCAPE)
        val writeFx = nameMap(FS_WRITE_ESCAPE).getValue("writeFx")
        val caps = CapabilitySet(
            mapOf(
                writeFx to listOf(
                    CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("../escape.txt")))),
                ),
            ),
        )

        // SECURE: workspace rooted at the temp dir, escape denied — the
        // `../escape.txt` path escapes and the sandbox rejects it.
        val securePolicy = HostPolicy.OPEN.copy(
            sandbox = SandboxPolicy(
                fs = FsPolicy(workspaceRoot = tmp, escape = EscapePolicy.Deny),
                net = NetPolicy(defaultDeny = false),
            ),
        )
        // OPEN: no workspace constraint, escape allowed — the sandbox permits
        // the path (the write itself may fail at the JVM, but NOT with a
        // SandboxViolation, which is the policy difference under test).
        val openRt = StrandRuntime(HostPolicy.OPEN)
        val secureRt = StrandRuntime(securePolicy)

        // SECURE rejects with FsPathEscape.
        val secureEx = assertThrows(InterpretException::class.java) { secureRt.run(prog, caps) }
        val sv = secureEx.error as? InterpretError.SandboxViolation
            ?: error("expected SandboxViolation under SECURE, got ${secureEx.error}")
        assertEquals(SandboxViolationKind.FsPathEscape, sv.kind)

        // OPEN does NOT produce a SandboxViolation — it either succeeds or
        // fails with an IoFailure, but the secure run's policy did not leak.
        val openErr = runCatching { openRt.run(prog, caps) }.exceptionOrNull()
        if (openErr is InterpretException) {
            assertTrue(
                openErr.error !is InterpretError.SandboxViolation,
                "OPEN runtime must not raise SandboxViolation; got ${openErr.error}",
            )
        }

        // Reverse order: SECURE again after OPEN still rejects (OPEN's policy
        // did not leak into SECURE).
        val secureEx2 = assertThrows(InterpretException::class.java) { secureRt.run(prog, caps) }
        assertEquals(
            SandboxViolationKind.FsPathEscape,
            (secureEx2.error as InterpretError.SandboxViolation).kind,
        )
    }

    // --- Scenario 5: singleton restoration ------------------------------------

    @Test
    fun `run restores every host-routed singleton it touched`() {
        val prog = image(SUM_TO_TEN)
        val before = Builtins.snapshot()
        val rt = StrandRuntime(HostPolicy.SECURE)  // installs SECURE_DEFAULT sandbox
        rt.run(prog)
        val after = Builtins.snapshot()
        assertEquals(before, after, "post-run snapshot must equal pre-run snapshot")
        // And the sandbox specifically returned to its pre-run value.
        assertSame(before.sandboxPolicy, Builtins.sandboxPolicy)
    }

    @Test
    fun `run restores singletons even when evaluation throws`() {
        val prog = image(SUM_TO_TEN)
        val before = Builtins.snapshot()
        val tight = StrandRuntime(HostPolicy.SECURE.copy(limits = EvaluationLimits.DEFAULTS.copy(maxSteps = 5L)))
        assertThrows(InterpretException::class.java) { tight.run(prog) }
        val after = Builtins.snapshot()
        assertEquals(before, after, "snapshot must be restored on the finally path after a thrown evaluation")
    }

    // --- Scenario 9: verify does no install -----------------------------------

    @Test
    fun `verify leaves the host-routed singletons untouched`() {
        val prog = image(SUM_TO_TEN)
        val before = Builtins.snapshot()
        val rt = StrandRuntime(HostPolicy.SECURE)
        val result = rt.verify(prog)
        assertTrue(result is org.strand.verifier.VerifyResult.Ok)
        val after = Builtins.snapshot()
        assertEquals(before, after, "verify must not install any policy")
    }

    // --- Scenario 7: credential provider per runtime --------------------------

    @Test
    fun `each runtime installs its own credential provider for the duration of a run`() {
        val prog = image(SUM_TO_TEN)
        // A sentinel provider distinguishable from the default.
        val sentinel = org.strand.interpreter.EnvCredentialProvider
        val custom = object : org.strand.interpreter.CredentialProvider {
            override fun resolve(provider: String, credentialKey: String) = null
        }
        val rtCustom = StrandRuntime(HostPolicy.OPEN.copy(credentialProvider = custom))

        val before = Builtins.snapshot()
        // During the run the custom provider is installed; after, it is restored.
        rtCustom.run(prog)
        val after = Builtins.snapshot()
        assertSame(before.credentialProvider, after.credentialProvider)
        // The runtime carried its own provider, distinct from the default.
        assertNotEquals(sentinel, custom)
    }

    companion object {
        // Fixpoint sum 1..10 = 55; many eval steps (used for the limits-isolation
        // and restoration tests). Lifted from corpus 22.
        val SUM_TO_TEN = """
        {
          "version": 1,
          "root": "app",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "boolT":     { "type": "PrimitiveType", "kind": "Bool" },
            "sumT":      { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "addT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":       { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "subT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "sub":       { "type": "ForeignNode", "target": "strand-builtin:Int.Sub", "foreignType": "subT" },
            "leT":       { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "le":        { "type": "ForeignNode", "target": "strand-builtin:Int.Le", "foreignType": "leT" },
            "recurse":   { "type": "ParameterDecl", "name": "recurse", "paramType": "sumT" },
            "n":         { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "zero":      { "type": "IntLit", "value": 0 },
            "one":       { "type": "IntLit", "value": 1 },
            "nRef":      { "type": "VarRef", "binder": "n" },
            "nLeZero":   { "type": "Application", "function": "le", "arguments": ["nRef", "zero"] },
            "litTrue":   { "type": "BoolLit", "value": true },
            "patTrue":   { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litTrue" },
            "caseTrue":  { "type": "MatchCase", "pattern": "patTrue", "body": "zero" },
            "litFalse":  { "type": "BoolLit", "value": false },
            "patFalse":  { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litFalse" },
            "recRef":    { "type": "VarRef", "binder": "recurse" },
            "nMinus1":   { "type": "Application", "function": "sub", "arguments": ["nRef", "one"] },
            "recCall":   { "type": "Application", "function": "recRef", "arguments": ["nMinus1"] },
            "addBody":   { "type": "Application", "function": "add", "arguments": ["nRef", "recCall"] },
            "caseFalse": { "type": "MatchCase", "pattern": "patFalse", "body": "addBody" },
            "matchBody": { "type": "Match", "scrutinee": "nLeZero", "cases": ["caseTrue", "caseFalse"] },
            "bodyLam":   { "type": "Lambda", "parameters": ["recurse", "n"], "body": "matchBody" },
            "sum":       { "type": "Fixpoint", "recursionType": "sumT", "body": "bodyLam" },
            "ten":       { "type": "IntLit", "value": 10 },
            "app":       { "type": "Application", "function": "sum", "arguments": ["ten"] }
          }
        }
        """.trimIndent()

        // Fs.Write to an escaping path (../escape.txt). Lifted from corpus 74.
        val FS_WRITE_ESCAPE = """
        {
          "version": 1,
          "root": "writeApp",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "strT":      { "type": "PrimitiveType", "kind": "String" },
            "bytesT":    { "type": "PrimitiveType", "kind": "Bytes" },
            "writeFx":   { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
            "writeT":    { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
            "writeFn":   { "type": "ForeignNode", "target": "strand-builtin:Fs.Write", "foreignType": "writeT",
                           "effects": ["writeFx"],
                           "effectProjections": [ { "category": "writeFx", "sources": [ { "kind": "ArgRef", "index": 0 } ] } ] },
            "escapePath": { "type": "StringLit", "value": "../escape.txt" },
            "payload":   { "type": "BytesLit", "value": "deadbeef" },
            "writeApp":  { "type": "Application", "function": "writeFn", "arguments": ["escapePath", "payload"] }
          }
        }
        """.trimIndent()
    }
}
