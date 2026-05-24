package org.strand.corpus

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Value
import org.strand.runtime.HaltReason
import org.strand.runtime.MachineGroup
import org.strand.runtime.StateMachineRuntime
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * End-to-end Layer 6 step 2 corpus tests. Each program is loaded through
 * the JSON ingest + finalize + verify pipeline, then driven through
 * [StateMachineRuntime.runGroup] (the async multi-machine entry point).
 *
 * Step 1's per-program tests live in [CorpusTest] (pure-evaluation) and
 * [CorpusMachineTest] (synchronous trace runtime). This file adds the
 * async runtime case: programs that demonstrate channel-based event flow,
 * inter-machine wiring, and replay-determinism via the recorder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsyncCorpusTest {

    @Test
    fun `46-async-single-machine-counter drains external output and reproduces via runMachine`() = runTest {
        val text = loadResource("/corpus/46-async-single-machine-counter.json")
        val eventsText = loadResource("/corpus/46-async-single-machine-counter.events.json")

        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verifyResult = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verifyResult is VerifyResult.Ok) {
            "verifier failed for 46-async-single-machine-counter: $verifyResult"
        }
        val events = org.strand.runtime.EventCodec.parseEventList(eventsText)
        val machineId = finalized.root
        val inputStreamId = ingest.nameMap.getValue("inputStream")
        val outputStreamId = ingest.nameMap.getValue("outputStream")

        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val group = MachineGroup(
            store = finalized.store,
            hashToNodeId = finalized.hashToNodeId,
            machines = listOf(machineId),
        )
        val handle = runtime.runGroup(group, this)

        val input = handle.externalInputs.getValue(inputStreamId)
        val output = handle.externalOutputs.getValue(outputStreamId)
        for (event in events) input.send(event)
        input.close()

        val drained = mutableListOf<Value>()
        repeat(events.size) { drained += output.receive() }
        handle.await()

        // Counter starts at 0 and accumulates each input. Events 1, 2, 3, 10 → states 1, 3, 6, 16.
        assertEquals(listOf<Value>(Value.IntV(1), Value.IntV(3), Value.IntV(6), Value.IntV(16)), drained)

        // Replay determinism: take the recorded events, replay through runMachine, expect equivalent trace.
        val recorded = handle.recordedEvents(handle.instances.keys.single())!!
        assertEquals(events, recorded) { "recorder must capture the exact event sequence the actor consumed" }
        val replayTrace = runtime.runMachine(machineId, recorded)
        assertEquals(events.size, replayTrace.steps.size)
        assertEquals(Value.IntV(16), replayTrace.final.finalState)
        assertEquals(HaltReason.EventsExhausted, replayTrace.final.reason)
    }

    private fun loadResource(resource: String): String {
        val stream = AsyncCorpusTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }
}
