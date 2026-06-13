package org.strand.corpus

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.Value
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult
import org.strand.verifier.VerifyWarning

/**
 * Q-065 end-to-end: the two-lock door exercised through the REAL
 * resolution chain. A builtin is injected into the registry overlay
 * ([Builtins.installTestBuiltin]) as effect-free but force-marked
 * Nondeterministic — the combination the registration constraint blocks
 * for shipping entries — and the verifier sees it through the
 * ServiceLoader-resolved `BuiltinsDeterminismOracle`, with no test
 * override on the verifier side. A transition closure referencing it
 * warns `NondeterministicInReplayContext`; once the overlay is cleared
 * the same program verifies warning-free (an unregistered target
 * carries no registry claim).
 */
class DeterminismWarningEndToEndTest {

    private val fakeTarget = "strand-builtin:Test.NondetPure"

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    private fun replayWarnings(result: VerifyResult): List<VerifyWarning.NondeterministicInReplayContext> {
        val ok = result as? VerifyResult.Ok
            ?: throw AssertionError("program must verify, got: $result")
        return ok.warnings.filterIsInstance<VerifyWarning.NondeterministicInReplayContext>()
    }

    private fun toggleMachineJson(target: String): String = """{
      "version": 1, "root": "toggleMachine",
      "nodes": {
        "boolT":            { "type": "PrimitiveType", "kind": "Bool" },
        "unitT":            { "type": "PrimitiveType", "kind": "Unit" },
        "emptyOutputsT":    { "type": "ProductType", "fields": [] },
        "stateFieldT":      { "type": "ProductTypeField", "name": "state", "fieldType": "boolT" },
        "outputsFieldT":    { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyOutputsT" },
        "resultT":          { "type": "ProductType", "fields": ["stateFieldT", "outputsFieldT"] },
        "stepFnT":          { "type": "FunctionType", "parameters": ["boolT"], "result": "boolT" },
        "stepFn":           { "type": "ForeignNode", "target": "$target", "foreignType": "stepFnT" },
        "stateParam":       { "type": "ParameterDecl", "name": "s", "paramType": "boolT" },
        "eventParam":       { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
        "stateParamRef":    { "type": "VarRef", "binder": "stateParam" },
        "nextState":        { "type": "Application", "function": "stepFn", "arguments": ["stateParamRef"] },
        "stateFieldV":      { "type": "ProductFieldValue", "fieldName": "state", "value": "nextState" },
        "emptyOutputsV":    { "type": "ProductValue", "ofType": "emptyOutputsT", "fields": [] },
        "outputsFieldV":    { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyOutputsV" },
        "transitionResult": { "type": "ProductValue", "ofType": "resultT", "fields": ["stateFieldV", "outputsFieldV"] },
        "transitionLambda": { "type": "Lambda", "parameters": ["stateParam", "eventParam"], "body": "transitionResult" },
        "initialState":     { "type": "BoolLit", "value": false },
        "inputStream":      { "type": "EventStream", "eventType": "unitT", "streamKind": "external" },
        "receiveFx":        { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
        "toggleMachine":    {
          "type": "StateMachine",
          "transitionFn": "transitionLambda",
          "initialState": "initialState",
          "inputStreams": ["inputStream"],
          "outputStreams": [],
          "effects": ["receiveFx"]
        }
      }
    }"""

    @Test
    fun `a transition referencing an overlay-injected nondeterministic builtin warns through the real oracle`() {
        Builtins.installTestBuiltin(
            fakeTarget,
            effectful = false,
            determinism = Builtins.Determinism.Nondeterministic,
            fn = Builtins.Fn { _, args -> args[0] },
        )

        val warning = replayWarnings(verify(toggleMachineJson(fakeTarget))).single()
        assertEquals(fakeTarget, warning.builtin)
    }

    @Test
    fun `an ordinary expression closure referencing the same builtin does not warn`() {
        Builtins.installTestBuiltin(
            fakeTarget,
            effectful = false,
            determinism = Builtins.Determinism.Nondeterministic,
            fn = Builtins.Fn { _, args -> args[0] },
        )

        val result = verify("""{
          "version": 1, "root": "call",
          "nodes": {
            "boolT":  { "type": "PrimitiveType", "kind": "Bool" },
            "fnT":    { "type": "FunctionType", "parameters": ["boolT"], "result": "boolT" },
            "fn":     { "type": "ForeignNode", "target": "$fakeTarget", "foreignType": "fnT" },
            "arg":    { "type": "BoolLit", "value": true },
            "call":   { "type": "Application", "function": "fn", "arguments": ["arg"] }
          }
        }""")
        assertTrue(replayWarnings(result).isEmpty()) {
            "ordinary expressions are not replay-relevant, got: ${replayWarnings(result)}"
        }
    }

    @Test
    fun `clearing the overlay clears the warning — an unregistered target carries no claim`() {
        Builtins.installTestBuiltin(
            fakeTarget,
            effectful = false,
            determinism = Builtins.Determinism.Nondeterministic,
            fn = Builtins.Fn { _, _ -> Value.UnitV },
        )
        assertTrue(replayWarnings(verify(toggleMachineJson(fakeTarget))).isNotEmpty())

        Builtins.clearTestBuiltins()
        assertTrue(replayWarnings(verify(toggleMachineJson(fakeTarget))).isEmpty()) {
            "with the overlay cleared the target is unregistered and must not be flagged"
        }
    }

    @Test
    fun `a transition referencing a shipping Deterministic builtin does not warn`() {
        assertTrue(replayWarnings(verify(toggleMachineJson("strand-builtin:Bool.Not"))).isEmpty())
    }
}
