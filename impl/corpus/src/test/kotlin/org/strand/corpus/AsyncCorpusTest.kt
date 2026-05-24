package org.strand.corpus

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.Value
import org.strand.runtime.EventCodec
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
 * inter-machine wiring, multi-stream merge with tagged InputEvent sums,
 * the tagged-output list shape, and replay-determinism via the recorder.
 *
 * Programs 47-49 use a routed event list (`{"stream": "<authorName>", ...}`)
 * because the runtime needs to know which external input stream each
 * event lands on. The single-stream 46 keeps the legacy un-routed format.
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
        val events = EventCodec.parseEventList(eventsText)
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

    @Test
    fun `47-async-multi-input-merge accepts events on both streams and emits per-stream labels`() = runTest {
        val ctx = loadProgram("47-async-multi-input-merge")
        val routedEvents = parseRoutedEvents(
            loadResource("/corpus/47-async-multi-input-merge.events.json")
        )

        val runtime = StateMachineRuntime(ctx.finalized.store, ctx.finalized.hashToNodeId)
        val group = MachineGroup(
            store = ctx.finalized.store,
            hashToNodeId = ctx.finalized.hashToNodeId,
            machines = listOf(ctx.finalized.root),
        )
        val handle = runtime.runGroup(group, this)

        val outputLogId = ctx.ingest.nameMap.getValue("outputLog")
        val output = handle.externalOutputs.getValue(outputLogId)

        // Send each event on its specified stream. select-merge means the
        // verifier-side correctness depends on per-stream FIFO and not on
        // global ordering (Q-009 nondeterministic merge default), but
        // sending sequentially with all inputs flushed before drain gives
        // a deterministic per-stream interleaving for the assert below.
        for ((streamName, payload) in routedEvents) {
            val streamId = ctx.ingest.nameMap.getValue(streamName)
            handle.externalInputs.getValue(streamId).send(payload)
        }
        for (channel in handle.externalInputs.values) channel.close()

        val drained = mutableListOf<Value>()
        repeat(routedEvents.size) { drained += output.receive() }
        handle.await()

        // The transition matches the synthesized InputEvent sum and emits
        // "A" for stream_0 events, "B" for stream_1 events. Regardless of
        // interleave order, the multiset of outputs must contain 2x "A"
        // and 2x "B" — the routed-event-list has 2 events on each input.
        val labelCounts: Map<String, Int> = drained.groupingBy { (it as Value.StringV).v }.eachCount()
        assertEquals(2, labelCounts["A"]) { "expected 2 'A' events, got $labelCounts" }
        assertEquals(2, labelCounts["B"]) { "expected 2 'B' events, got $labelCounts" }
    }

    @Test
    fun `48-async-supervisor-one-for-one wires workers to supervisor via internal streams`() = runTest {
        val ctx = loadProgram("48-async-supervisor-one-for-one")
        val routedEvents = parseRoutedEvents(
            loadResource("/corpus/48-async-supervisor-one-for-one.events.json")
        )

        // Three machines exist in the program (workerA, workerB, supervisor).
        // The CLI / runtime walks the store for all StateMachine NodeIds —
        // mirror that here so the MachineGroup includes every machine, not
        // just the root.
        val machineIds = ctx.finalized.store.entries()
            .filter { it.second is Node.StateMachine }
            .map { it.first }
        assertEquals(3, machineIds.size) { "expected 3 StateMachine nodes in 48, got ${machineIds.size}" }

        val runtime = StateMachineRuntime(ctx.finalized.store, ctx.finalized.hashToNodeId)
        val group = MachineGroup(
            store = ctx.finalized.store,
            hashToNodeId = ctx.finalized.hashToNodeId,
            machines = machineIds,
        )
        val handle = runtime.runGroup(group, this)

        val extOutputId = ctx.ingest.nameMap.getValue("extOutput")
        val supervisorOutput = handle.externalOutputs.getValue(extOutputId)

        for ((streamName, payload) in routedEvents) {
            val streamId = ctx.ingest.nameMap.getValue(streamName)
            handle.externalInputs.getValue(streamId).send(payload)
        }
        for (channel in handle.externalInputs.values) channel.close()

        // The supervisor sees one event per worker emission. Workers emit
        // for every received event (single output Some(state+event)). So
        // the supervisor's incoming event count equals the total external
        // input count, and it emits Some(count) on each step.
        val drained = mutableListOf<Value>()
        repeat(routedEvents.size) { drained += supervisorOutput.receive() }
        handle.await()

        // Supervisor state starts at 0 and increments by 1 per event.
        // After 3 worker emissions, supervisor's output history is [1, 2, 3].
        // (The order of which worker's event reaches the supervisor first is
        // nondeterministic per Q-009; only the multiset matters here.)
        val emittedInts = drained.map { (it as Value.IntV).v }.toSet()
        assertEquals(setOf(1L, 2L, 3L), emittedInts) {
            "expected supervisor to emit running counts 1..3, got $drained"
        }
    }

    @Test
    fun `49-async-tagged-output-list emits to multiple output streams per event`() = runTest {
        val ctx = loadProgram("49-async-tagged-output-list")
        val routedEvents = parseRoutedEvents(
            loadResource("/corpus/49-async-tagged-output-list.events.json")
        )

        val runtime = StateMachineRuntime(ctx.finalized.store, ctx.finalized.hashToNodeId)
        val group = MachineGroup(
            store = ctx.finalized.store,
            hashToNodeId = ctx.finalized.hashToNodeId,
            machines = listOf(ctx.finalized.root),
        )
        val handle = runtime.runGroup(group, this)

        val outputAId = ctx.ingest.nameMap.getValue("outputA")
        val outputBId = ctx.ingest.nameMap.getValue("outputB")
        val outputA = handle.externalOutputs.getValue(outputAId)
        val outputB = handle.externalOutputs.getValue(outputBId)

        for ((streamName, payload) in routedEvents) {
            val streamId = ctx.ingest.nameMap.getValue(streamName)
            handle.externalInputs.getValue(streamId).send(payload)
        }
        for (channel in handle.externalInputs.values) channel.close()

        // Each input event fires one transition, which emits to BOTH output
        // streams via the tagged-list shape (Cons(output_0(state), Cons(output_1(doubled), Nil))).
        // Events 5, 10 → states 5, 15. doubled = 10, 30.
        val drainedA = drainAll(outputA, routedEvents.size)
        val drainedB = drainAll(outputB, routedEvents.size)
        handle.await()

        assertEquals(listOf<Value>(Value.IntV(5), Value.IntV(15)), drainedA)
        assertEquals(listOf<Value>(Value.IntV(10), Value.IntV(30)), drainedB)
    }

    /**
     * Drain [count] values from [channel] in order. The channel is FIFO,
     * so the per-stream output order is deterministic; only inter-stream
     * interleaving is nondeterministic (Q-009 default).
     */
    private suspend fun drainAll(channel: ReceiveChannel<Value>, count: Int): List<Value> {
        val out = mutableListOf<Value>()
        repeat(count) { out += channel.receive() }
        return out
    }

    /**
     * Convenience: parse + finalize + verify a corpus program, returning the
     * loaded context. Throws via JUnit assertions on any pipeline failure.
     */
    private fun loadProgram(corpusName: String): ProgramContext {
        val text = loadResource("/corpus/$corpusName.json")
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verifyResult = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verifyResult is VerifyResult.Ok) { "verifier failed for $corpusName: $verifyResult" }
        return ProgramContext(ingest, finalized)
    }

    private data class ProgramContext(
        val ingest: JsonIngest.IngestResult,
        val finalized: org.strand.hashing.FinalizedProgram,
    )

    private fun loadResource(resource: String): String {
        val stream = AsyncCorpusTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }

    /**
     * Parse a routed event list of the form
     * `{"events": [{"stream": "<name>", "tag": "<tag>", ...}, ...]}`. Each
     * entry's `stream` field names an external input EventStream by its
     * author id; the remaining fields decode to a [Value] via the standard
     * [EventCodec] format.
     */
    private fun parseRoutedEvents(text: String): List<Pair<String, Value>> {
        val parser = Json { ignoreUnknownKeys = true }
        val root = parser.parseToJsonElement(text) as JsonObject
        val events = root["events"] as JsonArray
        return events.map { elt ->
            val obj = elt as JsonObject
            val streamName = obj["stream"]?.jsonPrimitive?.contentOrNull
                ?: error("routed event missing 'stream' field: $obj")
            val payload = EventCodec.decodeValue(obj)
            streamName to payload
        }
    }
}
