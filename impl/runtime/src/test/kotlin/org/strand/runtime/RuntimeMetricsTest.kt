package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Tests for Layer 6 step 3 slice 3.4 — runtime metrics.
 *
 * Each test drives a counter machine through a known event sequence, then
 * asserts on the [MachineGroupHandle.metrics] snapshot. The counter machine
 * is identical in shape to corpus 46 (single-input single-output counter)
 * with optional overflow policy adjustments per test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeMetricsTest {

    @Test
    fun `instance metrics count every event and transition`() = runTest {
        val (runtime, group, ingest, inputName, outputName) = loadCounter(COUNTER_JSON)
        val handle = runtime.runGroup(group, this)
        val input = handle.externalInputs.getValue(ingest.nameMap.getValue(inputName))
        val output = handle.externalOutputs.getValue(ingest.nameMap.getValue(outputName))

        // Pre-drive metrics: counters all zero, instance is not halted.
        val before = handle.metrics()
        assertEquals(1, before.perInstance.size)
        val (id, beforeM) = before.perInstance.entries.single()
        assertEquals(0L, beforeM.eventsReceived)
        assertEquals(0L, beforeM.transitionsExecuted)
        assertEquals(0L, beforeM.lastTransitionLatencyNanos)
        assertFalse(beforeM.halted)

        // Drive 4 events.
        for (i in 1..4) input.send(Value.IntV(i.toLong()))
        input.close()
        // Drain outputs so the actor doesn't block on a full output channel.
        repeat(4) { output.receive() }
        handle.await()

        // Post-drive: counters reflect the four events and four transitions.
        val after = handle.metrics()
        val afterM = after.perInstance.getValue(id)
        assertEquals(4L, afterM.eventsReceived) {
            "expected 4 events received, got ${afterM.eventsReceived}"
        }
        assertEquals(4L, afterM.transitionsExecuted) {
            "expected 4 transitions executed, got ${afterM.transitionsExecuted}"
        }
        assertTrue(afterM.lastTransitionLatencyNanos >= 0L) {
            "lastTransitionLatencyNanos should be non-negative, got ${afterM.lastTransitionLatencyNanos}"
        }
        assertTrue(afterM.halted) {
            "instance should be halted after input channel close + await"
        }
        // Counter machine accumulates: 0 + 1 + 2 + 3 + 4 = 10.
        assertEquals(Value.IntV(10L), afterM.currentState)
    }

    @Test
    fun `per-stream metrics surface overflowDrops from DropNewest policy`() = runTest {
        // Counter machine with bufferSize=2 + DropNewest on the OUTPUT stream.
        // Driving 6 events without draining the output channel forces drops
        // visible in the per-stream metrics.
        val (runtime, group, ingest, inputName, outputName) =
            loadCounter(COUNTER_DROPNEWEST_OUTPUT_JSON)
        val handle = runtime.runGroup(group, this)
        val input = handle.externalInputs.getValue(ingest.nameMap.getValue(inputName))
        val outputStreamId = ingest.nameMap.getValue(outputName)

        for (i in 1..6) input.send(Value.IntV(i.toLong()))
        input.close()
        handle.await()

        val metrics = handle.metrics()
        val streamM = metrics.perStream[outputStreamId]
        assertNotNull(streamM) {
            "per-stream metrics must include the output stream $outputStreamId; got keys ${metrics.perStream.keys}"
        }
        assertTrue(streamM!!.overflowDrops > 0L) {
            "expected at least one overflow drop on the DropNewest output stream; got ${streamM.overflowDrops}"
        }
    }

    @Test
    fun `metrics snapshot is independent across calls`() = runTest {
        val (runtime, group, ingest, inputName, outputName) = loadCounter(COUNTER_JSON)
        val handle = runtime.runGroup(group, this)
        val input = handle.externalInputs.getValue(ingest.nameMap.getValue(inputName))
        val output = handle.externalOutputs.getValue(ingest.nameMap.getValue(outputName))

        input.send(Value.IntV(7L))
        output.receive()
        val mid = handle.metrics()
        val midM = mid.perInstance.values.single()
        assertEquals(1L, midM.eventsReceived)

        input.send(Value.IntV(3L))
        output.receive()
        input.close()
        handle.await()
        val end = handle.metrics()
        val endM = end.perInstance.values.single()
        assertEquals(2L, endM.eventsReceived)
        // The earlier snapshot's data must not have been mutated by the
        // second snapshot — the data classes are immutable by construction.
        assertEquals(1L, midM.eventsReceived)
    }

    private data class Loaded(
        val runtime: StateMachineRuntime,
        val group: MachineGroup,
        val ingest: JsonIngest.IngestResult,
        val inputName: String,
        val outputName: String,
    )

    private fun loadCounter(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verifyResult = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verifyResult is VerifyResult.Ok) { "verifier failed: $verifyResult" }
        val machineId = finalized.root
        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val group = MachineGroup(
            store = finalized.store,
            hashToNodeId = finalized.hashToNodeId,
            machines = listOf(machineId),
        )
        return Loaded(runtime, group, ingest, "inputStream", "outputStream")
    }

    private companion object {
        /** Plain counter, no overflow policy. */
        val COUNTER_JSON = """
        {
          "version": 1,
          "root": "machine",
          "nodes": {
            "intT":         { "type": "PrimitiveType", "kind": "Int" },
            "someCase":     { "type": "SumTypeCase", "name": "Some", "caseType": "intT" },
            "noneCase":     { "type": "SumTypeCase", "name": "None", "caseType": null },
            "optionInt":    { "type": "SumType", "cases": ["someCase", "noneCase"] },
            "output0Field": { "type": "ProductTypeField", "name": "output_0", "fieldType": "optionInt" },
            "outputBatchT": { "type": "ProductType", "fields": ["output0Field"] },
            "stateField":   { "type": "ProductTypeField", "name": "state", "fieldType": "intT" },
            "outputsField": { "type": "ProductTypeField", "name": "outputs", "fieldType": "outputBatchT" },
            "resultType":   { "type": "ProductType", "fields": ["stateField", "outputsField"] },
            "addT":         { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "addFn":        { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "sParam":       { "type": "ParameterDecl", "name": "s", "paramType": "intT" },
            "eParam":       { "type": "ParameterDecl", "name": "e", "paramType": "intT" },
            "sRef":         { "type": "VarRef", "binder": "sParam" },
            "eRef":         { "type": "VarRef", "binder": "eParam" },
            "newState":     { "type": "Application", "function": "addFn", "arguments": ["sRef", "eRef"] },
            "someNewState": { "type": "SumValue", "ofType": "optionInt", "caseName": "Some", "payload": "newState" },
            "output0Value": { "type": "ProductFieldValue", "fieldName": "output_0", "value": "someNewState" },
            "outputBatchValue": { "type": "ProductValue", "ofType": "outputBatchT", "fields": ["output0Value"] },
            "newStateFieldV":   { "type": "ProductFieldValue", "fieldName": "state", "value": "newState" },
            "newOutputsFieldV": { "type": "ProductFieldValue", "fieldName": "outputs", "value": "outputBatchValue" },
            "resultValue":      { "type": "ProductValue", "ofType": "resultType", "fields": ["newStateFieldV", "newOutputsFieldV"] },
            "transitionLambda": { "type": "Lambda", "parameters": ["sParam", "eParam"], "body": "resultValue" },
            "initialState":     { "type": "IntLit", "value": 0 },
            "inputStream":      { "type": "EventStream", "eventType": "intT", "streamKind": "external" },
            "outputStream":     { "type": "EventStream", "eventType": "intT", "streamKind": "output" },
            "receiveFx":        { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "sendFx":           { "type": "EffectCategory", "categoryName": "StateMachine.Send" },
            "machine":          {
              "type": "StateMachine",
              "transitionFn": "transitionLambda",
              "initialState": "initialState",
              "inputStreams": ["inputStream"],
              "outputStreams": ["outputStream"],
              "effects": ["receiveFx", "sendFx"]
            }
          }
        }
        """.trimIndent()

        /** Counter with bufferSize=2 + DropNewest on the output stream. */
        val COUNTER_DROPNEWEST_OUTPUT_JSON = """
        {
          "version": 1,
          "root": "machine",
          "nodes": {
            "intT":         { "type": "PrimitiveType", "kind": "Int" },
            "someCase":     { "type": "SumTypeCase", "name": "Some", "caseType": "intT" },
            "noneCase":     { "type": "SumTypeCase", "name": "None", "caseType": null },
            "optionInt":    { "type": "SumType", "cases": ["someCase", "noneCase"] },
            "output0Field": { "type": "ProductTypeField", "name": "output_0", "fieldType": "optionInt" },
            "outputBatchT": { "type": "ProductType", "fields": ["output0Field"] },
            "stateField":   { "type": "ProductTypeField", "name": "state", "fieldType": "intT" },
            "outputsField": { "type": "ProductTypeField", "name": "outputs", "fieldType": "outputBatchT" },
            "resultType":   { "type": "ProductType", "fields": ["stateField", "outputsField"] },
            "addT":         { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "addFn":        { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "sParam":       { "type": "ParameterDecl", "name": "s", "paramType": "intT" },
            "eParam":       { "type": "ParameterDecl", "name": "e", "paramType": "intT" },
            "sRef":         { "type": "VarRef", "binder": "sParam" },
            "eRef":         { "type": "VarRef", "binder": "eParam" },
            "newState":     { "type": "Application", "function": "addFn", "arguments": ["sRef", "eRef"] },
            "someNewState": { "type": "SumValue", "ofType": "optionInt", "caseName": "Some", "payload": "newState" },
            "output0Value": { "type": "ProductFieldValue", "fieldName": "output_0", "value": "someNewState" },
            "outputBatchValue": { "type": "ProductValue", "ofType": "outputBatchT", "fields": ["output0Value"] },
            "newStateFieldV":   { "type": "ProductFieldValue", "fieldName": "state", "value": "newState" },
            "newOutputsFieldV": { "type": "ProductFieldValue", "fieldName": "outputs", "value": "outputBatchValue" },
            "resultValue":      { "type": "ProductValue", "ofType": "resultType", "fields": ["newStateFieldV", "newOutputsFieldV"] },
            "transitionLambda": { "type": "Lambda", "parameters": ["sParam", "eParam"], "body": "resultValue" },
            "initialState":     { "type": "IntLit", "value": 0 },
            "inputStream":      { "type": "EventStream", "eventType": "intT", "streamKind": "external" },
            "outputStream":     {
              "type": "EventStream",
              "eventType": "intT",
              "streamKind": "output",
              "bufferSize": 2,
              "overflowPolicy": "DropNewest"
            },
            "receiveFx":        { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "sendFx":           { "type": "EffectCategory", "categoryName": "StateMachine.Send" },
            "machine":          {
              "type": "StateMachine",
              "transitionFn": "transitionLambda",
              "initialState": "initialState",
              "inputStreams": ["inputStream"],
              "outputStreams": ["outputStream"],
              "effects": ["receiveFx", "sendFx"]
            }
          }
        }
        """.trimIndent()
    }
}
