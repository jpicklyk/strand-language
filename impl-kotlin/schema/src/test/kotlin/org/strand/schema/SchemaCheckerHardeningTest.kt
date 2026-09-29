package org.strand.schema

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.core.EvaluationLimits
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.HostContext
import org.strand.interpreter.InterpretError
import org.strand.interpreter.NetPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Review H3 consequence: verify-time invariant evaluation ran under the
 * process-global host policy instead of the tenant's, and a
 * `ResourceExhaustion` (or any other evaluation error) escaped `check()` raw.
 * The checker now evaluates under the caller's [HostContext] and returns
 * evaluation failures as structured results that also reject the value.
 */
class SchemaCheckerHardeningTest {

    @AfterEach
    fun reset() {
        Builtins.sandboxPolicy = SandboxPolicy.OPEN_DEFAULT
    }

    /** A PositiveInt-shaped schema over the literal 5 whose invariant body is [predLam] (plus [extraNodes]). */
    private fun program(extraNodes: String, predLam: String = "predLam") = """{
      "version": 1, "root": "claim",
      "nodes": {
        "intT":   { "type": "PrimitiveType", "kind": "Int" },
        "boolT":  { "type": "PrimitiveType", "kind": "Bool" },
        "strT":   { "type": "PrimitiveType", "kind": "String" },
        "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
        "zero":   { "type": "IntLit", "value": 0 },
        "xParam": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
        "xRef":   { "type": "VarRef", "binder": "xParam" },
        $extraNodes,
        "inv":    { "type": "Invariant", "invariantName": "inv", "targetSchema": "sch", "body": "$predLam" },
        "sch":    { "type": "Schema", "schemaName": "S", "valueType": "intT", "invariants": ["inv"] },
        "five":   { "type": "IntLit", "value": 5 },
        "pIn":    { "type": "ParameterDecl", "name": "p", "paramType": "sch" },
        "pRef":   { "type": "VarRef", "binder": "pIn" },
        "idS":    { "type": "Lambda", "parameters": ["pIn"], "body": "pRef" },
        "claim":  { "type": "Application", "function": "idS", "arguments": ["five"] }
      }
    }"""

    private val gtNodes = """
        "gtT":    { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
        "gt":     { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "gtT" },
        "gtBody": { "type": "Application", "function": "gt", "arguments": ["xRef", "zero"] },
        "predLam":{ "type": "Lambda", "parameters": ["xParam"], "body": "gtBody" }"""

    /** Invariant body `\x -> let w = Fs.Write(path, 0xde) in true` with an UNDER-declared `effects: []`. */
    private fun underDeclaredWriteNodes(path: String) = """
        "wT":     { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
        "w":      { "type": "ForeignNode", "target": "strand-builtin:Fs.Write", "foreignType": "wT", "effects": [] },
        "path":   { "type": "StringLit", "value": "$path" },
        "bytes":  { "type": "BytesLit", "value": "de" },
        "call":   { "type": "Application", "function": "w", "arguments": ["path", "bytes"] },
        "tru":    { "type": "BoolLit", "value": true },
        "letW":   { "type": "Let", "name": "w", "value": "call", "body": "tru" },
        "predLam":{ "type": "Lambda", "parameters": ["xParam"], "body": "letW" }"""

    private class Checked(val verify: VerifyResult, val run: (HostContext, EvaluationLimits) -> SchemaCheckResult)

    private fun prepare(json: String): Checked {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        return Checked(verify) { ctx, limits ->
            SchemaChecker(
                finalized.store,
                finalized.hashToNodeId,
                verify as VerifyResult.Ok,
                limits = limits,
                hostContext = ctx,
            ).check()
        }
    }

    @Test
    fun `ResourceExhaustion in an invariant body is a structured result, not a raw throw`() {
        val c = prepare(program(gtNodes))
        val result = c.run(HostContext.processDefault(), EvaluationLimits.DEFAULTS.copy(maxSteps = 2))
        val failure = result.evaluationFailures.single()
        assertTrue(failure.error is InterpretError.ResourceExhaustion) { "got ${failure.error}" }
        // Fail closed: the value is rejected through the existing violations list too.
        assertTrue(result.hasViolations)
        assertEquals(failure.invariant, result.violations.single().invariant)
    }

    @Test
    fun `an invariant body runs under the tenant host context, not the process-global policy`(@TempDir tmp: Path) {
        // Process-global policy is OPEN (writes anywhere); the tenant's is a
        // workspace sandbox rooted at tmp that denies escapes.
        val outside = tmp.parent.resolve("strand-invariant-escape-${UUID.randomUUID()}.bin")
        try {
            val c = prepare(program(underDeclaredWriteNodes(outside.toString().replace('\\', '/'))))
            // Today the under-declared ForeignNode verifies (Stream A's
            // admission rule closes that separately).
            if (c.verify !is VerifyResult.Ok) return
            val tenant = HostContext.processDefault().copy(
                sandboxPolicy = SandboxPolicy(
                    fs = FsPolicy(workspaceRoot = tmp, escape = EscapePolicy.Deny),
                    net = NetPolicy(defaultDeny = true),
                ),
            )
            val result = c.run(tenant, EvaluationLimits.DEFAULTS)
            assertFalse(Files.exists(outside)) { "the invariant wrote outside the tenant workspace" }
            val failure = result.evaluationFailures.single()
            assertTrue(failure.error is InterpretError.SandboxViolation) { "got ${failure.error}" }
            assertTrue(result.hasViolations)
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Disabled("pending Stream A merge")
    @Test
    fun `an invariant body calling Fs Write with under-declared effects is rejected at admission`() {
        val c = prepare(program(underDeclaredWriteNodes("/tmp/strand-invariant-under-declared.bin")))
        val failed = c.verify as? VerifyResult.Failed
            ?: error("expected the verifier to reject the under-declared Fs.Write, got ${c.verify}")
        assertTrue(failed.errors.any { it::class.simpleName == "ForeignEffectUnderDeclared" }) {
            "expected VerifyError.ForeignEffectUnderDeclared, got ${failed.errors}"
        }
    }
}
