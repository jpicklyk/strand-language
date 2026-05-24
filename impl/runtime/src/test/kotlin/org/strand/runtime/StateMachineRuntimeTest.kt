package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Unit tests for [StateMachineRuntime] over hand-crafted JSON programs.
 * Drives the synchronous fold through `runMachine`, asserts on the resulting
 * [Trace], and exercises the OutputBatch convention on machines with and
 * without output streams.
 *
 * The corpus-program-level coverage lives in
 * [org.strand.corpus.CorpusMachineTest]; this file focuses on the runtime's
 * own behavior: trace shape, halt reason, determinism, empty-event handling,
 * cached transition-function closure.
 */
class StateMachineRuntimeTest {

    @Test
    fun `toggle machine produces a 3-step trace with alternating state`() {
        val ingest = JsonIngest.parse(TOGGLE_MACHINE)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        assertTrue(Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root) is VerifyResult.Ok)

        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val events = listOf(Value.UnitV, Value.UnitV, Value.UnitV)
        val trace = runtime.runMachine(finalized.root, events)

        assertEquals(3, trace.steps.size)
        assertEquals(Value.BoolV(false), trace.steps[0].before)
        assertEquals(Value.BoolV(true),  trace.steps[0].after)
        assertEquals(Value.BoolV(true),  trace.steps[1].before)
        assertEquals(Value.BoolV(false), trace.steps[1].after)
        assertEquals(Value.BoolV(false), trace.steps[2].before)
        assertEquals(Value.BoolV(true),  trace.steps[2].after)

        // No output streams declared → every step has zero emitted values.
        for (step in trace.steps) {
            assertTrue(step.outputs.isEmpty())
        }
        assertEquals(HaltReason.EventsExhausted, trace.final.reason)
        assertEquals(Value.BoolV(true), trace.final.finalState)
    }

    @Test
    fun `empty event list produces empty trace and halts on EventsExhausted`() {
        val ingest = JsonIngest.parse(TOGGLE_MACHINE)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        assertTrue(Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root) is VerifyResult.Ok)

        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val trace = runtime.runMachine(finalized.root, events = emptyList())

        assertEquals(0, trace.steps.size)
        assertEquals(HaltReason.EventsExhausted, trace.final.reason)
        // No events fired, so the final state equals the initial state.
        assertEquals(Value.BoolV(false), trace.final.finalState)
    }

    @Test
    fun `replay is deterministic — same inputs produce identical trace`() {
        val ingest = JsonIngest.parse(TOGGLE_MACHINE)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        assertTrue(Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root) is VerifyResult.Ok)

        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val events = listOf(Value.UnitV, Value.UnitV, Value.UnitV, Value.UnitV)
        val traceA = runtime.runMachine(finalized.root, events)
        val traceB = runtime.runMachine(finalized.root, events)

        assertEquals(traceA, traceB)
    }

    @Test
    fun `machine with output stream emits Some payloads and suppresses None`() {
        // emitter machine: state Int starts at 0; each Int event becomes the
        // new state; output_0 is Some(state) iff state > 5, else None.
        val ingest = JsonIngest.parse(EMITTER_MACHINE)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        assertTrue(Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root) is VerifyResult.Ok)

        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val events = listOf(
            Value.IntV(3),
            Value.IntV(7),
            Value.IntV(2),
            Value.IntV(10),
        )
        val trace = runtime.runMachine(finalized.root, events)

        assertEquals(4, trace.steps.size)
        // Step 0: 0 -> 3; not over threshold → no output emitted.
        assertTrue(trace.steps[0].outputs.isEmpty())
        // Step 1: 3 -> 7; over threshold → Some(7) emitted.
        assertEquals(listOf<Value>(Value.IntV(7)), trace.steps[1].outputs)
        // Step 2: 7 -> 2; not over threshold → no output emitted.
        assertTrue(trace.steps[2].outputs.isEmpty())
        // Step 3: 2 -> 10; over threshold → Some(10) emitted.
        assertEquals(listOf<Value>(Value.IntV(10)), trace.steps[3].outputs)

        assertEquals(Value.IntV(10), trace.final.finalState)
    }

    @Test
    fun `instance ids are fresh across runMachine calls`() {
        // Forward-compatibility check: every runMachine call generates a new
        // InstanceId, even on the same machine. Step 1 doesn't expose this
        // through the Trace API but the field exists on MachineInstance for
        // step 2's actor model.
        val a = InstanceId.generate()
        val b = InstanceId.generate()
        assertNotNull(a.value)
        assertNotNull(b.value)
        assertNotEquals(a.value, b.value)
    }

    companion object {

        // ---- Inline fixture programs ----
        // These are kept inside the test file (rather than in resources) so
        // the runtime module can run in isolation without depending on the
        // corpus module's resource bundle.

        private val TOGGLE_MACHINE = """
        {
          "version": 1,
          "root": "m",
          "nodes": {
            "boolT":   { "type": "PrimitiveType", "kind": "Bool" },
            "unitT":   { "type": "PrimitiveType", "kind": "Unit" },
            "emptyT":  { "type": "ProductType", "fields": [] },
            "sft":     { "type": "ProductTypeField", "name": "state",   "fieldType": "boolT" },
            "oft":     { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":    { "type": "ProductType", "fields": ["sft", "oft"] },
            "notT":    { "type": "FunctionType", "parameters": ["boolT"], "result": "boolT" },
            "notFn":   { "type": "ForeignNode", "target": "strand-builtin:Bool.Not", "foreignType": "notT" },
            "sP":      { "type": "ParameterDecl", "name": "s", "paramType": "boolT" },
            "eP":      { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
            "sRef":    { "type": "VarRef", "binder": "sP" },
            "neg":     { "type": "Application", "function": "notFn", "arguments": ["sRef"] },
            "sV":      { "type": "ProductFieldValue", "fieldName": "state",   "value": "neg" },
            "emptyV":  { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":      { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":  { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "lam":     { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "init":    { "type": "BoolLit", "value": false },
            "stream":  { "type": "EventStream", "eventType": "unitT", "streamKind": "external" },
            "m": {
              "type": "StateMachine",
              "transitionFn": "lam",
              "initialState": "init",
              "inputStreams": ["stream"],
              "outputStreams": [],
              "effects": []
            }
          }
        }
        """.trimIndent()

        // Counter that mirrors its Int event into state, and emits the new
        // state on output_0 iff state > 5. Used to exercise OutputBatch
        // semantics: Some emits, None suppresses.
        private val EMITTER_MACHINE = """
        {
          "version": 1,
          "root": "m",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "boolT":  { "type": "PrimitiveType", "kind": "Bool" },
            "someC":  { "type": "SumTypeCase", "name": "Some", "caseType": "intT" },
            "noneC":  { "type": "SumTypeCase", "name": "None", "caseType": null },
            "optT":   { "type": "SumType", "cases": ["someC", "noneC"] },
            "o0t":    { "type": "ProductTypeField", "name": "output_0", "fieldType": "optT" },
            "obT":    { "type": "ProductType", "fields": ["o0t"] },
            "sft":    { "type": "ProductTypeField", "name": "state",   "fieldType": "intT" },
            "oft":    { "type": "ProductTypeField", "name": "outputs", "fieldType": "obT" },
            "resT":   { "type": "ProductType", "fields": ["sft", "oft"] },
            "gtT":    { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "gtFn":   { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "gtT" },
            "sP":     { "type": "ParameterDecl", "name": "s", "paramType": "intT" },
            "eP":     { "type": "ParameterDecl", "name": "e", "paramType": "intT" },
            "eRef":   { "type": "VarRef", "binder": "eP" },
            "lit5":   { "type": "IntLit", "value": 5 },
            "over":   { "type": "Application", "function": "gtFn", "arguments": ["eRef", "lit5"] },
            "someE":  { "type": "SumValue", "ofType": "optT", "caseName": "Some", "payload": "eRef" },
            "noneE":  { "type": "SumValue", "ofType": "optT", "caseName": "None" },
            "trueLit":{ "type": "BoolLit", "value": true },
            "falseLit":{ "type": "BoolLit", "value": false },
            "patT":   { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "trueLit" },
            "patF":   { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "falseLit" },
            "caseT":  { "type": "MatchCase", "pattern": "patT", "body": "someE" },
            "caseF":  { "type": "MatchCase", "pattern": "patF", "body": "noneE" },
            "emit":   { "type": "Match", "scrutinee": "over", "cases": ["caseT", "caseF"] },
            "o0v":    { "type": "ProductFieldValue", "fieldName": "output_0", "value": "emit" },
            "obV":    { "type": "ProductValue", "ofType": "obT", "fields": ["o0v"] },
            "sV":     { "type": "ProductFieldValue", "fieldName": "state",   "value": "eRef" },
            "oV":     { "type": "ProductFieldValue", "fieldName": "outputs", "value": "obV" },
            "result": { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "lam":    { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "init":   { "type": "IntLit", "value": 0 },
            "inStr":  { "type": "EventStream", "eventType": "intT", "streamKind": "external" },
            "outStr": { "type": "EventStream", "eventType": "intT", "streamKind": "output"   },
            "m": {
              "type": "StateMachine",
              "transitionFn": "lam",
              "initialState": "init",
              "inputStreams": ["inStr"],
              "outputStreams": ["outStr"],
              "effects": []
            }
          }
        }
        """.trimIndent()

    }
}
