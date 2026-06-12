package org.strand.verifier

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher

/**
 * Q-065 verifier guard: [VerifyWarning.NondeterministicInReplayContext]
 * fires when a StateMachine transition-function closure or an Invariant
 * body references an effect-free builtin whose registry entry is not
 * marked Deterministic — and only then.
 *
 * No shipping registry entry can produce the trigger (the registration
 * constraint blocks the effect-free-but-not-Deterministic combination),
 * so the tests install a fake oracle through the
 * [ReplayDeterminism.override] seam: `Test.NondetPure` answers
 * not-Deterministic, every other `strand-builtin:` target answers
 * Deterministic, everything else is unknown. The end-to-end path through
 * the real ServiceLoader-resolved `Builtins` oracle is covered by
 * `DeterminismWarningEndToEndTest` in `:corpus`.
 */
class NondeterministicInReplayContextWarningTest {

    private val fakeTarget = "strand-builtin:Test.NondetPure"

    @BeforeEach
    fun installFakeOracle() {
        ReplayDeterminism.override = BuiltinDeterminismOracle { target ->
            when {
                target == fakeTarget -> false
                target.startsWith("strand-builtin:") -> true
                else -> null
            }
        }
    }

    @AfterEach
    fun removeFakeOracle() {
        ReplayDeterminism.override = null
    }

    private data class Verified(val result: VerifyResult, val store: NodeStore)

    private fun verify(json: String): Verified {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val result = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        return Verified(result, finalized.store)
    }

    /** The corpus-41 toggle machine with the transition's builtin swapped to [target]. */
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

    private fun replayWarnings(result: VerifyResult): List<VerifyWarning.NondeterministicInReplayContext> {
        val ok = result as? VerifyResult.Ok
            ?: throw AssertionError("program must verify, got: $result")
        return ok.warnings.filterIsInstance<VerifyWarning.NondeterministicInReplayContext>()
    }

    private fun singleNodeOf(store: NodeStore, predicate: (Node) -> Boolean): NodeId =
        store.entries().single { (_, node) -> predicate(node) }.first

    @Test
    fun `a transition closure referencing the non-deterministic builtin warns`() {
        val v = verify(toggleMachineJson(fakeTarget))
        val warning = replayWarnings(v.result).single()
        assertEquals(fakeTarget, warning.builtin)
        assertEquals(
            singleNodeOf(v.store) { it is Node.StateMachine },
            warning.machineOrInvariant,
            "the warning must name the StateMachine whose transition closure is affected",
        )
    }

    @Test
    fun `a transition closure referencing a Deterministic builtin does not warn`() {
        val v = verify(toggleMachineJson("strand-builtin:Bool.Not"))
        assertTrue(replayWarnings(v.result).isEmpty()) {
            "Deterministic builtins are replay-safe, got: ${replayWarnings(v.result)}"
        }
    }

    @Test
    fun `an ordinary expression closure referencing the same builtin does not warn`() {
        val v = verify("""{
          "version": 1, "root": "call",
          "nodes": {
            "boolT":  { "type": "PrimitiveType", "kind": "Bool" },
            "fnT":    { "type": "FunctionType", "parameters": ["boolT"], "result": "boolT" },
            "fn":     { "type": "ForeignNode", "target": "$fakeTarget", "foreignType": "fnT" },
            "arg":    { "type": "BoolLit", "value": true },
            "call":   { "type": "Application", "function": "fn", "arguments": ["arg"] }
          }
        }""")
        assertTrue(replayWarnings(v.result).isEmpty()) {
            "ordinary expressions are not replay-relevant, got: ${replayWarnings(v.result)}"
        }
    }

    @Test
    fun `an invariant body referencing the non-deterministic builtin warns`() {
        val v = verify("""{
          "version": 1, "root": "schemaClaim",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "boolT":   { "type": "PrimitiveType", "kind": "Bool" },
            "zero":    { "type": "IntLit", "value": 0 },
            "xParam":  { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":    { "type": "VarRef", "binder": "xParam" },
            "predT":   { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "predFn":  { "type": "ForeignNode", "target": "$fakeTarget", "foreignType": "predT" },
            "predBody": { "type": "Application", "function": "predFn", "arguments": ["xRef", "zero"] },
            "predLam": { "type": "Lambda", "parameters": ["xParam"], "body": "predBody" },
            "inv": {
              "type": "Invariant",
              "invariantName": "x_probed",
              "targetSchema": "schemaNode",
              "body": "predLam"
            },
            "schemaNode": {
              "type": "Schema",
              "schemaName": "ProbedInt",
              "valueType": "intT",
              "invariants": ["inv"]
            },
            "five":    { "type": "IntLit", "value": 5 },
            "pIn":     { "type": "ParameterDecl", "name": "p", "paramType": "schemaNode" },
            "pInRef":  { "type": "VarRef", "binder": "pIn" },
            "idLam":   { "type": "Lambda", "parameters": ["pIn"], "body": "pInRef" },
            "schemaClaim": { "type": "Application", "function": "idLam", "arguments": ["five"] }
          }
        }""")
        val warning = replayWarnings(v.result).single()
        assertEquals(fakeTarget, warning.builtin)
        assertEquals(
            singleNodeOf(v.store) { it is Node.Invariant },
            warning.machineOrInvariant,
            "the warning must name the Invariant whose body is affected",
        )
    }

    @Test
    fun `an effect-declaring ForeignNode in a transition is not flagged — effects mediate it`() {
        // The same not-Deterministic target, but the ForeignNode honestly
        // declares an effect category and the machine declares it in
        // turn: the effect machinery mediates the call, so the
        // replay-determinism warning stays silent (flagging it would
        // double-report what the effect closure already surfaces).
        val v = verify("""{
          "version": 1, "root": "machine",
          "nodes": {
            "boolT":            { "type": "PrimitiveType", "kind": "Bool" },
            "unitT":            { "type": "PrimitiveType", "kind": "Unit" },
            "emptyOutputsT":    { "type": "ProductType", "fields": [] },
            "stateFieldT":      { "type": "ProductTypeField", "name": "state", "fieldType": "boolT" },
            "outputsFieldT":    { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyOutputsT" },
            "resultT":          { "type": "ProductType", "fields": ["stateFieldT", "outputsFieldT"] },
            "fakeFx":           { "type": "EffectCategory", "categoryName": "Test.Fake" },
            "stepFnT":          { "type": "FunctionType", "parameters": ["boolT"], "result": "boolT" },
            "stepFn":           { "type": "ForeignNode", "target": "$fakeTarget", "foreignType": "stepFnT", "effects": ["fakeFx"] },
            "stateParam":       { "type": "ParameterDecl", "name": "s", "paramType": "boolT" },
            "eventParam":       { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
            "stateParamRef":    { "type": "VarRef", "binder": "stateParam" },
            "nextState":        { "type": "Application", "function": "stepFn", "arguments": ["stateParamRef"] },
            "stateFieldV":      { "type": "ProductFieldValue", "fieldName": "state", "value": "nextState" },
            "emptyOutputsV":    { "type": "ProductValue", "ofType": "emptyOutputsT", "fields": [] },
            "outputsFieldV":    { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyOutputsV" },
            "transitionResult": { "type": "ProductValue", "ofType": "resultT", "fields": ["stateFieldV", "outputsFieldV"] },
            "transitionLambda": { "type": "Lambda", "parameters": ["stateParam", "eventParam"], "body": "transitionResult", "effects": ["fakeFx"] },
            "initialState":     { "type": "BoolLit", "value": false },
            "inputStream":      { "type": "EventStream", "eventType": "unitT", "streamKind": "external" },
            "receiveFx":        { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "machine":          {
              "type": "StateMachine",
              "transitionFn": "transitionLambda",
              "initialState": "initialState",
              "inputStreams": ["inputStream"],
              "outputStreams": [],
              "effects": ["receiveFx", "fakeFx"]
            }
          }
        }""")
        assertTrue(replayWarnings(v.result).isEmpty()) {
            "effect-declaring ForeignNodes are mediated by the effect machinery, got: " +
                "${replayWarnings(v.result)}"
        }
    }

    @Test
    fun `with no oracle installed the warning never fires — unknown targets are not flagged`() {
        ReplayDeterminism.override = BuiltinDeterminismOracle { null }
        val v = verify(toggleMachineJson(fakeTarget))
        assertTrue(replayWarnings(v.result).isEmpty()) {
            "an unknown target carries no registry claim to check, got: ${replayWarnings(v.result)}"
        }
    }
}
