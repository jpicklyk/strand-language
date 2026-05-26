package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeStore
import org.strand.core.OverflowPolicy
import org.strand.core.Primitive
import org.strand.core.StreamKind
import org.strand.hashing.Hasher
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Unit tests for [OverflowDispatcher] (Layer 6 step 3 slice 3.1). Each policy
 * variant is exercised in isolation through a directly-constructed channel —
 * these tests do not spin up the full actor pipeline; that integration is
 * covered separately by [BackpressureIntegrationTest].
 *
 * The Sample-policy test uses real wall-clock timing (sleeps in real time)
 * because the dispatcher's clock source is `System.nanoTime`, not the
 * virtual time of `kotlinx.coroutines.test`. The sleep is short (15ms total)
 * to keep the test fast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverflowDispatcherTest {

    @Test
    fun `BlockProducer suspends until consumer drains - matches step-2 behavior`() = runTest {
        val channel = Channel<Value>(capacity = 2)
        val dispatcher = OverflowDispatcher(channel, OverflowPolicy.BlockProducer)

        // Fill the channel to capacity.
        dispatcher.send(Value.IntV(1))
        dispatcher.send(Value.IntV(2))

        // The third send must suspend. Start it async, give it a tick, then
        // drain one to release it.
        val sendJob = async { dispatcher.send(Value.IntV(3)) }
        // Yield once so the sender can actually attempt the send and suspend.
        delay(1)
        // Without a drain it would never complete; drain one slot.
        assertEquals(Value.IntV(1), channel.receive())
        // Now the suspended send completes.
        sendJob.await()

        assertEquals(Value.IntV(2), channel.receive())
        assertEquals(Value.IntV(3), channel.receive())
        assertEquals(0L, dispatcher.droppedCount) { "BlockProducer never drops" }
    }

    @Test
    fun `DropNewest discards incoming events when channel is full`() = runTest {
        val channel = Channel<Value>(capacity = 2)
        val dispatcher = OverflowDispatcher(channel, OverflowPolicy.DropNewest)

        dispatcher.send(Value.IntV(1))
        dispatcher.send(Value.IntV(2))
        // Channel is full; these three sends each drop the incoming event.
        dispatcher.send(Value.IntV(3))
        dispatcher.send(Value.IntV(4))
        dispatcher.send(Value.IntV(5))

        // The channel still contains the first two values, in FIFO order.
        assertEquals(Value.IntV(1), channel.receive())
        assertEquals(Value.IntV(2), channel.receive())

        assertEquals(3L, dispatcher.droppedCount) {
            "DropNewest should record one drop per failed send"
        }
    }

    @Test
    fun `DropOldest evicts the head and enqueues the new event`() = runTest {
        val channel = Channel<Value>(capacity = 2)
        val dispatcher = OverflowDispatcher(channel, OverflowPolicy.DropOldest)

        dispatcher.send(Value.IntV(1))
        dispatcher.send(Value.IntV(2))
        // Channel is full; this send drops 1 and enqueues 3.
        dispatcher.send(Value.IntV(3))
        // Again: drops 2 and enqueues 4.
        dispatcher.send(Value.IntV(4))

        // After two replacing sends, the channel contains the two newest values.
        assertEquals(Value.IntV(3), channel.receive())
        assertEquals(Value.IntV(4), channel.receive())

        assertEquals(2L, dispatcher.droppedCount) {
            "DropOldest should record one drop per evicted head"
        }
    }

    @Test
    fun `Sample rate-limits events arriving faster than the interval`() = runBlocking {
        // Real-time test (not runTest virtual clock) because the dispatcher's
        // clock source is System.nanoTime.
        val channel = Channel<Value>(capacity = 16)
        val intervalNanos = 5_000_000L  // 5 ms
        val dispatcher = OverflowDispatcher(channel, OverflowPolicy.Sample(intervalNanos))

        // First send always accepted (sentinel last-time is Long.MIN_VALUE).
        dispatcher.send(Value.IntV(1))
        // Immediately follow with two more — both must be dropped (way less
        // than 5ms elapsed).
        dispatcher.send(Value.IntV(2))
        dispatcher.send(Value.IntV(3))

        // Wait past the interval, then send again — accepted.
        delay(10)
        dispatcher.send(Value.IntV(4))

        assertEquals(Value.IntV(1), channel.tryReceive().getOrNull())
        assertEquals(Value.IntV(4), channel.tryReceive().getOrNull())
        // Channel should be empty now — the rate-limited 2 and 3 never arrived.
        assertEquals(null, channel.tryReceive().getOrNull())

        assertEquals(2L, dispatcher.droppedCount) {
            "Sample should drop two events that arrived inside the interval"
        }
    }

    /**
     * Sample(n) requires `n > 0`. The data class init block enforces this.
     */
    @Test
    fun `Sample with non-positive interval is rejected at construction`() {
        val ex = runCatching { OverflowPolicy.Sample(0) }
        assertTrue(ex.isFailure) {
            "Sample(0) should fail construction; got success"
        }
        val ex2 = runCatching { OverflowPolicy.Sample(-1L) }
        assertTrue(ex2.isFailure) {
            "Sample(-1) should fail construction; got success"
        }
    }

    /**
     * End-to-end integration: a one-machine group with a slow consumer and
     * a DropNewest output stream. The producer emits N events; the consumer
     * sees at most K (the channel capacity) and the dispatcher's drop counter
     * accumulates the rest. Confirms the policy travels from the EventStream
     * node through the runtime allocation into the actor's send path.
     */
    @Test
    fun `DropNewest on output stream drops events the consumer cannot keep up with`() = runTest {
        val ingest = JsonIngest.parse(COUNTER_DROPNEWEST_OUTPUT_JSON)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verifyResult = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verifyResult is VerifyResult.Ok) { "verifier failed: $verifyResult" }

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

        // Send 8 events into the producer; the output channel has capacity 2
        // and DropNewest policy, and we don't drain until later, so the
        // dispatcher will record drops as the producer outruns the empty sink.
        // 8 events arrive at the producer one-by-one; each fires a transition
        // that tries to push one Int onto the output channel.
        for (i in 1..8) {
            input.send(Value.IntV(i.toLong()))
        }
        input.close()
        // Wait for the actor to drain and halt.
        handle.await()

        // Now drain whatever made it onto the output channel.
        val drained = mutableListOf<Value>()
        while (true) {
            val r = output.tryReceive()
            if (r.isSuccess) drained += r.getOrThrow() else break
        }

        // The output channel's capacity is 2. The first two sends fit
        // unconditionally; subsequent sends drop. So at most 2 values
        // survive on the channel.
        assertTrue(drained.size <= 2) {
            "expected at most 2 surviving events on the capacity-2 DropNewest output, got ${drained.size}: $drained"
        }
        // At least one drop must have occurred (8 events sent, ≤2 survive).
        assertTrue(drained.size < 8) {
            "if all 8 events made it onto the output channel, DropNewest didn't fire"
        }
    }

    /**
     * Verify the channel allocated by `runGroup` honors the EventStream's
     * declared bufferSize. We do this by reading the channel's capacity from
     * a directly-built MachineGroup — the only public observation is that
     * sending more than `bufferSize` events without a drain on a
     * BlockProducer stream would block; we just confirm two events fit.
     */
    @Test
    fun `runGroup allocates per-stream channels with declared bufferSize`() = runTest {
        val store = NodeStore()
        val boolT = store.add(Node.PrimitiveType(Primitive.Bool))
        val unitT = store.add(Node.PrimitiveType(Primitive.Unit))
        val emptyProductType = store.add(Node.ProductType(emptyList()))
        val stateField = store.add(Node.ProductTypeField("state", boolT))
        val outputsField = store.add(Node.ProductTypeField("outputs", emptyProductType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))

        val sParam = store.add(Node.ParameterDecl("s", boolT))
        val eParam = store.add(Node.ParameterDecl("e", unitT))
        val sRef = store.add(Node.VarRef(sParam))
        val notType = store.add(Node.FunctionType(parameters = listOf(boolT), result = boolT))
        val notFn = store.add(Node.ForeignNode(target = "strand-builtin:Bool.Not", foreignType = notType))
        val flipped = store.add(Node.Application(function = notFn, arguments = listOf(sRef)))
        val newStateField = store.add(Node.ProductFieldValue("state", flipped))
        val emptyValue = store.add(Node.ProductValue(emptyProductType, emptyList()))
        val newOutputsField = store.add(Node.ProductFieldValue("outputs", emptyValue))
        val resultValue = store.add(Node.ProductValue(resultType, listOf(newStateField, newOutputsField)))
        val transitionLambda = store.add(Node.Lambda(parameters = listOf(sParam, eParam), body = resultValue))

        val initialState = store.add(Node.BoolLit(false))
        // Override the input stream's bufferSize to 4 — the host should be
        // able to push 4 events without a drain (the receive side runs in
        // parallel under runTest, so this test only confirms no exceptions
        // or deadlocks within the timeout).
        val inputStream = store.add(Node.EventStream(
            eventType = unitT,
            streamKind = StreamKind.External,
            bufferSize = 4,
            overflowPolicy = OverflowPolicy.BlockProducer,
        ))

        val machine = store.add(Node.StateMachine(
            transitionFn = transitionLambda,
            initialState = initialState,
            inputStreams = listOf(inputStream),
            outputStreams = emptyList(),
            effects = emptyList(),
        ))

        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machine),
        )
        val handle = runtime.runGroup(group, this)
        val inputChannel = handle.externalInputs.getValue(inputStream)

        // Push 4 events without a drain. If bufferSize=4 is honored, this
        // proceeds without the test scope hanging.
        withTimeout(5_000) {
            repeat(4) { inputChannel.send(Value.UnitV) }
        }
        inputChannel.close()
        handle.await()

        // After all 4 toggles from initial false: false→true→false→true→false.
        assertEquals(Value.BoolV(false), handle.instances.values.single().currentState)
    }

    private companion object {
        /**
         * Counter machine identical in shape to corpus 46 but with a
         * DropNewest output stream of capacity 2 — so the rapid producer
         * outpaces the unread consumer.
         */
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
