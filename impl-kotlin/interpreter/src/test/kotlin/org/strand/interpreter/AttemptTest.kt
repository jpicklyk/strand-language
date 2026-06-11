package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.EvaluationLimits
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher

/**
 * N-047 Attempt (Q-048, proposals/error-recovery.md § 7) — interpreter-level
 * scenarios. The verifier-side scenarios (synthesized Result type, the
 * AttemptBodyMustBeMonomorphic rule, hash-identity with an agent-declared sum)
 * live in the verifier module's `AttemptVerifierTest`.
 *
 * These tests bypass the verifier (the interpreter trusts verified input) and
 * exercise the eval-level catch/Ok-Err shaping directly. The `Fs.Read`-based
 * tests run under the test JVM's default [SandboxPolicy.OPEN_DEFAULT] (escape
 * allowed, workspace unrooted), so a relative path that cannot exist surfaces
 * as a catchable `filesystem-read` [InterpretError.IoFailure].
 */
class AttemptTest {

    @AfterEach
    fun cleanup() {
        ResourceTable.resetForTest()
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

    // ----- Scenario 1: Ok passthrough -----

    @Test
    fun `TRY over a pure expression yields Ok(value)`() {
        val l = load("""{
          "version": 1, "root": "att",
          "nodes": {
            "lit": { "type": "IntLit", "value": 42 },
            "att": { "type": "Attempt", "body": "lit" }
          }
        }""")
        val v = Interpreter(l.store, l.hashToNodeId).eval(l.root)
        val sum = v as Value.SumV
        assertEquals("Ok", sum.case)
        assertEquals(Value.IntV(42), sum.payload)
    }

    // ----- Scenario 2: Fs.Read fallback (catchable IoFailure → Err) -----

    private val fsReadAttemptJson = """{
      "version": 1, "root": "att",
      "nodes": {
        "stringT":  { "type": "PrimitiveType", "kind": "String" },
        "bytesT":   { "type": "PrimitiveType", "kind": "Bytes" },
        "readFx":   { "type": "EffectCategory", "categoryName": "Filesystem.Read" },
        "fsReadT":  { "type": "FunctionType", "parameters": ["stringT"], "result": "bytesT", "effects": ["readFx"] },
        "fsRead":   { "type": "ForeignNode", "target": "strand-builtin:Fs.Read", "foreignType": "fsReadT", "effects": ["readFx"] },
        "pathS":    { "type": "StringLit", "value": "strand-nonexistent-dir-xyz/missing.json" },
        "readApp":  { "type": "Application", "function": "fsRead", "arguments": ["pathS"] },
        "att":      { "type": "Attempt", "body": "readApp" }
      }
    }"""

    @Test
    fun `TRY over Fs Read on a missing file yields Err with filesystem-read kind`() {
        val l = load(fsReadAttemptJson)
        val readFx = l.names.getValue("readFx")
        val v = Interpreter(l.store, l.hashToNodeId).eval(l.root, capabilities = setOf(readFx))
        val sum = v as Value.SumV
        assertEquals("Err", sum.case)
        val payload = sum.payload as Value.ProductV
        assertEquals(Value.StringV("filesystem-read"), payload.fields["kind"])
        // detail is platform-varying; assert only that it is a (non-empty) string.
        val detail = payload.fields["detail"] as Value.StringV
        assertTrue(detail.v.isNotEmpty(), "expected a non-empty detail string")
    }

    // ----- Scenario 5/6: uncatchable errors propagate -----

    @Test
    fun `CapabilityViolation inside TRY propagates uncaught`() {
        // Same program, but no capability grant: the effectful call is denied
        // before dispatch. CapabilityViolation is uncatchable, so the Attempt
        // must NOT convert it to Err — the exception reaches the host.
        val l = load(fsReadAttemptJson)
        val ex = org.junit.jupiter.api.assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, capabilities = emptySet())
        }
        assertTrue(
            ex.error is InterpretError.CapabilityViolation,
            "expected CapabilityViolation to escape TRY, got ${ex.error}",
        )
    }

    @Test
    fun `ResourceExhaustion inside TRY propagates uncaught`() {
        // An Attempt over an infinite Fixpoint under a tight maxSteps budget:
        // the budget is a hard stop regardless of TRY. The Fixpoint recurses
        // forever; maxSteps trips first and ResourceExhaustion (uncatchable)
        // must escape rather than be caught as Err.
        val l = load("""{
          "version": 1, "root": "att",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "loopT":    { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "self":     { "type": "ParameterDecl", "name": "self", "paramType": "loopT" },
            "n":        { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "selfRef":  { "type": "VarRef", "binder": "self" },
            "nRef":     { "type": "VarRef", "binder": "n" },
            "recur":    { "type": "Application", "function": "selfRef", "arguments": ["nRef"] },
            "loopBody": { "type": "Lambda", "parameters": ["self", "n"], "body": "recur" },
            "loop":     { "type": "Fixpoint", "recursionType": "loopT", "body": "loopBody" },
            "zero":     { "type": "IntLit", "value": 0 },
            "callLoop": { "type": "Application", "function": "loop", "arguments": ["zero"] },
            "att":      { "type": "Attempt", "body": "callLoop" }
          }
        }""")
        // Keep maxSteps small so the infinite Fixpoint trips ResourceExhaustion
        // after a shallow recursion — enough to prove propagation, without
        // leaving deep-stack pressure in the shared test fork.
        val limits = EvaluationLimits.DEFAULTS.copy(maxSteps = 50L)
        val ex = org.junit.jupiter.api.assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, CapabilitySet.EMPTY, limits)
        }
        assertTrue(
            ex.error is InterpretError.ResourceExhaustion,
            "expected ResourceExhaustion to escape TRY, got ${ex.error}",
        )
    }

    @Test
    fun `NoMatchingCase inside TRY propagates uncaught`() {
        // A Match with a single literal case that does not match the scrutinee.
        // NoMatchingCase is a program defect — uncatchable — so it escapes TRY.
        val l = load("""{
          "version": 1, "root": "att",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "scrut":   { "type": "IntLit", "value": 7 },
            "litOne":  { "type": "IntLit", "value": 1 },
            "patOne":  { "type": "Pattern", "kind": "literal", "patternType": "intT", "literal": "litOne" },
            "bodyOne": { "type": "IntLit", "value": 100 },
            "caseOne": { "type": "MatchCase", "pattern": "patOne", "body": "bodyOne" },
            "match":   { "type": "Match", "scrutinee": "scrut", "cases": ["caseOne"] },
            "att":     { "type": "Attempt", "body": "match" }
          }
        }""")
        val ex = org.junit.jupiter.api.assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root)
        }
        assertTrue(
            ex.error is InterpretError.NoMatchingCase,
            "expected NoMatchingCase to escape TRY, got ${ex.error}",
        )
    }

    // ----- Scenario 9: nested Attempts -----

    @Test
    fun `nested TRY — inner catches, outer sees Ok of the inner Err`() {
        // Outer Attempt wraps an inner Attempt over a failing Fs.Read. The
        // inner catches the IoFailure to Err; the outer succeeds and wraps the
        // inner's Err value in Ok.
        val l = load("""{
          "version": 1, "root": "outer",
          "nodes": {
            "stringT": { "type": "PrimitiveType", "kind": "String" },
            "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
            "readFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Read" },
            "fsReadT": { "type": "FunctionType", "parameters": ["stringT"], "result": "bytesT", "effects": ["readFx"] },
            "fsRead":  { "type": "ForeignNode", "target": "strand-builtin:Fs.Read", "foreignType": "fsReadT", "effects": ["readFx"] },
            "pathS":   { "type": "StringLit", "value": "strand-nonexistent-dir-xyz/missing.json" },
            "readApp": { "type": "Application", "function": "fsRead", "arguments": ["pathS"] },
            "inner":   { "type": "Attempt", "body": "readApp" },
            "outer":   { "type": "Attempt", "body": "inner" }
          }
        }""")
        val readFx = l.names.getValue("readFx")
        val v = Interpreter(l.store, l.hashToNodeId).eval(l.root, capabilities = setOf(readFx))
        val outerSum = v as Value.SumV
        assertEquals("Ok", outerSum.case)
        val innerSum = outerSum.payload as Value.SumV
        assertEquals("Err", innerSum.case)
        val payload = innerSum.payload as Value.ProductV
        assertEquals(Value.StringV("filesystem-read"), payload.fields["kind"])
    }

    @Test
    fun `nested TRY — an uncatchable error crosses both`() {
        // Outer and inner Attempt over an infinite Fixpoint under a tight
        // budget: ResourceExhaustion crosses both Attempt frames to the host.
        val l = load("""{
          "version": 1, "root": "outer",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "loopT":    { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "self":     { "type": "ParameterDecl", "name": "self", "paramType": "loopT" },
            "n":        { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "selfRef":  { "type": "VarRef", "binder": "self" },
            "nRef":     { "type": "VarRef", "binder": "n" },
            "recur":    { "type": "Application", "function": "selfRef", "arguments": ["nRef"] },
            "loopBody": { "type": "Lambda", "parameters": ["self", "n"], "body": "recur" },
            "loop":     { "type": "Fixpoint", "recursionType": "loopT", "body": "loopBody" },
            "zero":     { "type": "IntLit", "value": 0 },
            "callLoop": { "type": "Application", "function": "loop", "arguments": ["zero"] },
            "inner":    { "type": "Attempt", "body": "callLoop" },
            "outer":    { "type": "Attempt", "body": "inner" }
          }
        }""")
        // Keep maxSteps small so the infinite Fixpoint trips ResourceExhaustion
        // after a shallow recursion — enough to prove propagation, without
        // leaving deep-stack pressure in the shared test fork.
        val limits = EvaluationLimits.DEFAULTS.copy(maxSteps = 50L)
        val ex = org.junit.jupiter.api.assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, CapabilitySet.EMPTY, limits)
        }
        assertTrue(ex.error is InterpretError.ResourceExhaustion)
    }

    // ----- Scenario 11: credential scrubbing of caught detail -----

    @Test
    fun `caught Err detail carries the redacted form of a registered credential`() {
        // A test-only ForeignNode throws an IoFailure whose raw detail embeds
        // a registered credential. The interpreter's translateIoFailure path
        // scrubs it before constructing the InterpretError; the Attempt then
        // observes the already-scrubbed detail. Catching must not recover the
        // credential.
        CredentialScrubber.resetForTesting()
        CredentialScrubber.register(Credential("sk-attempt-test-9999zzzz", "anthropic", "api_key"))
        try {
            val store = NodeStore()
            val unitT = store.add(org.strand.core.Node.PrimitiveType(kind = org.strand.core.Primitive.Unit))
            val fnT = store.add(org.strand.core.Node.FunctionType(parameters = emptyList(), result = unitT))
            val foreign = store.add(org.strand.core.Node.ForeignNode(
                target = "test:throw-with-secret", foreignType = fnT, effects = emptyList(),
            ))
            val call = store.add(org.strand.core.Node.Application(function = foreign, arguments = emptyList()))
            val att = store.add(org.strand.core.Node.Attempt(body = call))

            val dispatcher = object : ForeignDispatcher {
                override fun dispatch(target: String, args: List<Value>): Value? {
                    if (target == "test:throw-with-secret") {
                        throw IoFailure("upstream-echo", "status 401: key sk-attempt-test-9999zzzz")
                    }
                    return null
                }
            }
            val v = Interpreter(store, emptyMap(), dispatcher).eval(att, CapabilitySet.EMPTY)
            val sum = v as Value.SumV
            assertEquals("Err", sum.case)
            val payload = sum.payload as Value.ProductV
            val detail = (payload.fields["detail"] as Value.StringV).v
            assertTrue(
                "[REDACTED:anthropic:api_key]" in detail,
                "expected the caught detail to carry the redacted token, got: $detail",
            )
            assertTrue(
                "sk-attempt-test-9999zzzz" !in detail,
                "the raw credential must not be reachable through Err.detail",
            )
        } finally {
            CredentialScrubber.resetForTesting()
        }
    }
}
