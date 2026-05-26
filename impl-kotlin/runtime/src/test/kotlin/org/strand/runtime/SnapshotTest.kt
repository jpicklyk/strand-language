package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.HashFunction
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Tests for Layer 6 step 3 slice 3.3 — snapshot/replay-from-log.
 *
 * Each test exercises one half of the replay-determinism property: capture
 * a snapshot via `MachineGroupHandle.snapshot`, then `runtime.resume` against
 * the snapshot + additional events. The combination must produce a trace
 * equivalent to a fresh `runMachine` over the full event list — the property
 * `design/state-machines.md` § Conceptual model pins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotTest {

    @Test
    fun `snapshot then resume reproduces the trace a fresh runMachine would have produced`() = runTest {
        val ingest = JsonIngest.parse(RuntimeMetricsTestSeed.COUNTER_JSON)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        assertTrue(Verifier(finalized.store, finalized.hashToNodeId)
            .verify(finalized.root) is VerifyResult.Ok)
        val machineId = finalized.root

        // Drive the machine through the FIRST 3 of 6 events via runGroup
        // and capture a snapshot.
        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val group = MachineGroup(
            store = finalized.store,
            hashToNodeId = finalized.hashToNodeId,
            machines = listOf(machineId),
            nodeIdToHash = finalized.nodeIdToHash,
        )
        val handle = runtime.runGroup(group, this)
        val input = handle.externalInputs.values.single()
        val output = handle.externalOutputs.values.single()

        for (i in 1..3) input.send(Value.IntV(i.toLong()))
        // Drain the first 3 emissions so the actor's transitions all complete
        // before we snapshot.
        repeat(3) { output.receive() }
        val snapshot = handle.snapshot(handle.instances.keys.single())

        // Wind down the live group.
        input.close()
        handle.await()

        // Sanity: the snapshot captures state after 3 events of accumulation
        // (1+2+3 = 6), and the recorder agrees on event count.
        assertEquals(Value.IntV(6L), snapshot.snapshotState)
        assertEquals(3L, snapshot.processedEventCount)
        assertNotNull(snapshot.recordedEventsPreSnapshot)
        assertEquals(3, snapshot.recordedEventsPreSnapshot!!.size)

        // Resume with the remaining 3 events.
        val remainingEvents = listOf(Value.IntV(4L), Value.IntV(5L), Value.IntV(6L))
        val resumedTrace = runtime.resume(
            machineId = machineId,
            snapshot = snapshot,
            additionalEvents = remainingEvents,
            nodeIdToHash = finalized.nodeIdToHash,
        )

        // The resumed trace's final state is the full accumulation (1+...+6 = 21).
        assertEquals(Value.IntV(21L), resumedTrace.final.finalState)

        // Cross-check: a fresh `runMachine` over ALL 6 events produces the
        // same final state and the same per-step states across the
        // overlap (steps 4-6 of the full trace == steps 1-3 of the resumed).
        val freshTrace = runtime.runMachine(
            machine = machineId,
            events = (1..6).map { Value.IntV(it.toLong()) },
        )
        assertEquals(freshTrace.final.finalState, resumedTrace.final.finalState)
        // Per-step state-after equality on the overlap.
        for (i in 0 until 3) {
            assertEquals(
                freshTrace.steps[i + 3].after,
                resumedTrace.steps[i].after,
                "step $i state mismatch: fresh=${freshTrace.steps[i + 3].after} resumed=${resumedTrace.steps[i].after}",
            )
            assertEquals(
                freshTrace.steps[i + 3].outputs,
                resumedTrace.steps[i].outputs,
                "step $i outputs mismatch",
            )
        }
    }

    @Test
    fun `resume with a mismatched machine hash throws SnapshotMachineHashMismatch`() = runTest {
        val ingest = JsonIngest.parse(RuntimeMetricsTestSeed.COUNTER_JSON)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val machineId = finalized.root

        // Construct a snapshot with a deliberately wrong machineHash.
        // BLAKE3-prefix byte 0x1e + 32 zero digest bytes is a well-formed Hash
        // that doesn't match any real machine.
        val wrongHash = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32))
        val badSnapshot = Snapshot(
            machineHash = wrongHash,
            snapshotState = Value.IntV(99L),
            processedEventCount = 0L,
        )

        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val ex = assertThrows(SnapshotMachineHashMismatch::class.java) {
            runtime.resume(
                machineId = machineId,
                snapshot = badSnapshot,
                additionalEvents = listOf(Value.IntV(1L)),
                nodeIdToHash = finalized.nodeIdToHash,
            )
        }
        assertEquals(wrongHash, ex.expected)
        assertNotEquals(wrongHash, ex.actual)
    }

    @Test
    fun `snapshot on a group without nodeIdToHash throws clear error`() = runTest {
        val ingest = JsonIngest.parse(RuntimeMetricsTestSeed.COUNTER_JSON)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val machineId = finalized.root

        // Build the group WITHOUT nodeIdToHash — snapshots should fail fast.
        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val group = MachineGroup(
            store = finalized.store,
            hashToNodeId = finalized.hashToNodeId,
            machines = listOf(machineId),
            // nodeIdToHash deliberately omitted (defaults to emptyMap()).
        )
        val handle = runtime.runGroup(group, this)
        val ex = assertThrows(IllegalStateException::class.java) {
            handle.snapshot(handle.instances.keys.single())
        }
        assertTrue(ex.message!!.contains("nodeIdToHash"))
        // Wind down so the test scope doesn't leak the actor.
        handle.externalInputs.values.single().close()
        handle.await()
    }

    @Test
    fun `snapshot on unknown instance throws SnapshotUnknownInstance`() = runTest {
        val ingest = JsonIngest.parse(RuntimeMetricsTestSeed.COUNTER_JSON)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val machineId = finalized.root

        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val group = MachineGroup(
            store = finalized.store,
            hashToNodeId = finalized.hashToNodeId,
            machines = listOf(machineId),
            nodeIdToHash = finalized.nodeIdToHash,
        )
        val handle = runtime.runGroup(group, this)
        val bogusInstance = InstanceId("not-a-real-instance-id")
        assertThrows(SnapshotUnknownInstance::class.java) {
            handle.snapshot(bogusInstance)
        }
        handle.externalInputs.values.single().close()
        handle.await()
    }
}

/**
 * Reusable counter-machine JSON fixture for snapshot/metrics tests. Kept
 * in a top-level singleton so multiple test classes share the same string
 * literal without re-declaring it.
 */
internal object RuntimeMetricsTestSeed {
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
}
