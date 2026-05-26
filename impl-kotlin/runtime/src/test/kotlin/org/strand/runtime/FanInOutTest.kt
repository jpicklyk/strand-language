package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.ConsumerMode
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.core.StreamKind
import org.strand.interpreter.Value

/**
 * Unit tests for Layer 6 step 3 slice 3.6: multi-producer fan-in and
 * broadcast fan-out on internal streams.
 *
 * Slice 3.6 relaxes the step-2 single-producer / single-consumer
 * enforcement on internal streams in two directions:
 *
 *  1. **Fan-in** (multi-producer) — multiple machines may emit into the
 *     same internal EventStream. The runtime allocates one
 *     [kotlinx.coroutines.channels.Channel] per stream and all producers
 *     send into it; nondeterministic interleaving across producers is
 *     accepted (Q-009 default). Tested below: two producers' emissions
 *     converge on one consumer and the consumer sees the union (multiset
 *     equality, not ordering).
 *
 *  2. **Fan-out** (broadcast) — when an EventStream's `consumerMode` is
 *     [ConsumerMode.Broadcast], multiple consumers may subscribe. The
 *     runtime wraps the stream in a `MutableSharedFlow<Value>`; each
 *     consumer machine gets a dedicated per-consumer channel fed by its
 *     own collection coroutine. Tested below: one producer's emissions
 *     are delivered to all three subscribed consumers.
 *
 * Built programmatically (not via JSON) so these tests exercise the
 * runtime in isolation — the verifier remains unaware of consumerMode at
 * this slice (it is purely a runtime concern; a future verifier-side
 * `BroadcastStreamWithBlockProducerWarning` diagnostic is left to a
 * follow-up).
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class FanInOutTest {

    /**
     * Topology relaxation: a MachineGroup with two machines that BOTH list
     * the same `internal` stream in their outputStreams now validates
     * (slice 3.6). Pre-slice-3.6 this raised
     * [MachineGroupValidationError.InternalStreamMultipleProducers]; that
     * sealed-class member is retained but no longer raised by
     * `validateTopology`.
     */
    @Test
    fun `topology validation allows multi-producer fan-in on internal streams`() = runTest {
        val store = NodeStore()
        val (producer1, externalInput1, internalAB) = buildPassthroughProducer(store, internalOutputForReuse = null)
        val (producer2, externalInput2, _) = buildPassthroughProducer(store, internalOutputForReuse = internalAB)
        val (consumer, _, externalOutput) = buildPassthroughConsumer(store, internalAB)

        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(producer1, producer2, consumer),
        )
        // Smoke: this should not throw — validateTopology relaxed in slice 3.6.
        val handle = runtime.runGroup(group, this)
        // Close inputs so producers can halt cleanly.
        handle.externalInputs.getValue(externalInput1).close()
        handle.externalInputs.getValue(externalInput2).close()
        handle.await()
        // Confirm the output channel saw EOF (the consumer received the
        // EOF from the shared internal stream after both producers halted).
        assertTrue(handle.externalOutputs.getValue(externalOutput).isClosedForReceive) {
            "consumer should have halted and closed its output stream after both producers EOF'd"
        }
    }

    /**
     * Topology rejection: a non-broadcast (default Single) internal stream
     * with multiple consumer machines still raises
     * [MachineGroupValidationError.InternalStreamMultipleConsumers] —
     * broadcast is opt-in.
     */
    @Test
    fun `topology validation rejects multi-consumer on non-broadcast internal stream`() = runTest {
        val store = NodeStore()
        val (producer, _, internalAB) = buildPassthroughProducer(store, internalOutputForReuse = null)
        // Two consumers both listening on the same internal stream, default Single mode.
        val (consumer1, _, _) = buildPassthroughConsumer(store, internalAB)
        val (consumer2, _, _) = buildPassthroughConsumer(store, internalAB)

        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(producer, consumer1, consumer2),
        )
        val ex = assertThrows(MachineGroupValidationError.InternalStreamMultipleConsumers::class.java) {
            runtime.runGroup(group, this)
        }
        assertEquals(internalAB, ex.stream)
    }

    /**
     * Topology relaxation: when the internal stream's [ConsumerMode] is
     * [ConsumerMode.Broadcast], multiple consumers are accepted.
     */
    @Test
    fun `topology validation allows multi-consumer when consumerMode is Broadcast`() = runTest {
        val store = NodeStore()
        val (producer, externalInput, internalAB) = buildPassthroughProducer(
            store,
            internalOutputForReuse = null,
            broadcast = true,
        )
        val (consumer1, _, externalOutput1) = buildPassthroughConsumer(store, internalAB)
        val (consumer2, _, externalOutput2) = buildPassthroughConsumer(store, internalAB)

        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(producer, consumer1, consumer2),
        )
        // Should not throw — Broadcast consumerMode permits multi-consumer.
        val handle = runtime.runGroup(group, this)
        handle.externalInputs.getValue(externalInput).close()
        handle.await()
        // Both consumers should have halted (their inputs closed once the
        // broadcast bus signalled end-of-stream).
        assertTrue(handle.externalOutputs.getValue(externalOutput1).isClosedForReceive)
        assertTrue(handle.externalOutputs.getValue(externalOutput2).isClosedForReceive)
    }

    /**
     * Multi-producer fan-in semantics: two producers' emissions converge
     * on one consumer. The consumer sees the union of producers' outputs;
     * ordering across producers is nondeterministic (per-producer FIFO is
     * preserved). The total event count and the multiset of payloads
     * uniquely identify a correct run.
     */
    @Test
    fun `fan-in two producers' outputs converge on one consumer`() = runTest {
        val store = NodeStore()
        val (producer1, externalInput1, internalAB) = buildLabeledProducer(store, label = 100, broadcast = false)
        val (producer2, externalInput2, _) = buildLabeledProducer(
            store,
            label = 200,
            broadcast = false,
            internalOutputForReuse = internalAB,
        )
        val (consumer, _, externalOutput) = buildPassthroughConsumerInt(store, internalAB)

        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(producer1, producer2, consumer),
        )
        val handle = runtime.runGroup(group, this)

        val input1 = handle.externalInputs.getValue(externalInput1)
        val input2 = handle.externalInputs.getValue(externalInput2)
        val output = handle.externalOutputs.getValue(externalOutput)

        // Each producer emits 3 events labelled by its base + 0..2.
        repeat(3) { input1.send(Value.UnitV) }
        repeat(3) { input2.send(Value.UnitV) }
        input1.close()
        input2.close()

        val drained = mutableListOf<Value>()
        repeat(6) { drained += output.receive() }
        handle.await()

        // Producer 1 emits 100, 101, 102; producer 2 emits 200, 201, 202.
        // The consumer's output is the multiset {100..102, 200..202} —
        // order across producers is nondeterministic.
        val received = drained.map { (it as Value.IntV).v.toInt() }.toSet()
        assertEquals(setOf(100, 101, 102, 200, 201, 202), received) {
            "fan-in consumer should see every event from both producers; got $drained"
        }
    }

    /**
     * Broadcast fan-out semantics: one producer emits N events; three
     * consumers each receive every event in the producer's FIFO order.
     * Verifies (a) every event reaches every consumer, (b) per-consumer
     * order is the producer's emission order.
     */
    @Test
    fun `fan-out broadcast delivers each event to every consumer`() = runTest {
        val store = NodeStore()
        val (producer, externalInput, internalBus) = buildLabeledProducer(
            store,
            label = 10,
            broadcast = true,
        )
        val (consumer1, _, output1) = buildPassthroughConsumerInt(store, internalBus)
        val (consumer2, _, output2) = buildPassthroughConsumerInt(store, internalBus)
        val (consumer3, _, output3) = buildPassthroughConsumerInt(store, internalBus)

        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(producer, consumer1, consumer2, consumer3),
        )
        val handle = runtime.runGroup(group, this)
        val input = handle.externalInputs.getValue(externalInput)

        // Producer emits 10, 11, 12, 13, 14.
        repeat(5) { input.send(Value.UnitV) }
        input.close()

        val drained1 = drainAll(handle.externalOutputs.getValue(output1), 5)
        val drained2 = drainAll(handle.externalOutputs.getValue(output2), 5)
        val drained3 = drainAll(handle.externalOutputs.getValue(output3), 5)
        handle.await()

        val expected = listOf<Value>(Value.IntV(10), Value.IntV(11), Value.IntV(12), Value.IntV(13), Value.IntV(14))
        assertEquals(expected, drained1) { "consumer 1 should see every broadcast event in order" }
        assertEquals(expected, drained2) { "consumer 2 should see every broadcast event in order" }
        assertEquals(expected, drained3) { "consumer 3 should see every broadcast event in order" }
    }

    /**
     * EventStream's consumerMode field is part of its canonical encoding
     * (additive versioning when default Single, distinct hash when
     * Broadcast). Verifies the runtime correctly distinguishes the two
     * via the validator-level "multi-consumer allowed" branch.
     */
    @Test
    fun `single consumer on Broadcast stream also works (degenerate broadcast)`() = runTest {
        val store = NodeStore()
        val (producer, externalInput, internalBus) = buildLabeledProducer(
            store,
            label = 50,
            broadcast = true,
        )
        val (consumer, _, output) = buildPassthroughConsumerInt(store, internalBus)

        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(producer, consumer),
        )
        val handle = runtime.runGroup(group, this)
        val input = handle.externalInputs.getValue(externalInput)
        repeat(3) { input.send(Value.UnitV) }
        input.close()

        val drained = drainAll(handle.externalOutputs.getValue(output), 3)
        handle.await()

        assertEquals(listOf<Value>(Value.IntV(50), Value.IntV(51), Value.IntV(52)), drained)
    }

    /**
     * Drain [count] values from [channel] in arrival order.
     */
    private suspend fun drainAll(channel: ReceiveChannel<Value>, count: Int): List<Value> {
        val out = mutableListOf<Value>()
        repeat(count) { out += channel.receive() }
        return out
    }

    /**
     * Build a "passthrough producer" Bool machine: state is Bool starting
     * at false, every event flips and emits `Some(newState)` on the
     * internal output. If [internalOutputForReuse] is supplied, it is used
     * as the output stream (fan-in setup — both producers share the same
     * internal stream); otherwise a fresh internal stream is allocated.
     *
     * Returns (machineId, externalInputStreamId, internalOutputStreamId).
     */
    private fun buildPassthroughProducer(
        store: NodeStore,
        internalOutputForReuse: NodeId?,
        broadcast: Boolean = false,
    ): Triple<NodeId, NodeId, NodeId> {
        val boolT = store.add(Node.PrimitiveType(Primitive.Bool))
        val unitT = store.add(Node.PrimitiveType(Primitive.Unit))

        val someCase = store.add(Node.SumTypeCase("Some", boolT))
        val noneCase = store.add(Node.SumTypeCase("None", null))
        val optionBool = store.add(Node.SumType(listOf(someCase, noneCase)))

        val output0Field = store.add(Node.ProductTypeField("output_0", optionBool))
        val outputBatchType = store.add(Node.ProductType(listOf(output0Field)))
        val stateField = store.add(Node.ProductTypeField("state", boolT))
        val outputsField = store.add(Node.ProductTypeField("outputs", outputBatchType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))

        val sParam = store.add(Node.ParameterDecl("s", boolT))
        val eParam = store.add(Node.ParameterDecl("e", unitT))
        val sRef = store.add(Node.VarRef(sParam))
        val notType = store.add(Node.FunctionType(parameters = listOf(boolT), result = boolT))
        val notFn = store.add(Node.ForeignNode(target = "strand-builtin:Bool.Not", foreignType = notType))
        val flipped = store.add(Node.Application(function = notFn, arguments = listOf(sRef)))
        val someFlipped = store.add(Node.SumValue(optionBool, "Some", flipped))
        val output0Value = store.add(Node.ProductFieldValue("output_0", someFlipped))
        val outputBatchValue = store.add(Node.ProductValue(outputBatchType, listOf(output0Value)))
        val newStateField = store.add(Node.ProductFieldValue("state", flipped))
        val newOutputsField = store.add(Node.ProductFieldValue("outputs", outputBatchValue))
        val resultValue = store.add(Node.ProductValue(resultType, listOf(newStateField, newOutputsField)))
        val transitionLambda = store.add(Node.Lambda(parameters = listOf(sParam, eParam), body = resultValue))

        val initialState = store.add(Node.BoolLit(false))
        val externalInput = store.add(Node.EventStream(eventType = unitT, streamKind = StreamKind.External))
        val internalOutput = internalOutputForReuse
            ?: store.add(Node.EventStream(
                eventType = boolT,
                streamKind = StreamKind.Internal,
                consumerMode = if (broadcast) ConsumerMode.Broadcast else null,
            ))

        val machine = store.add(Node.StateMachine(
            transitionFn = transitionLambda,
            initialState = initialState,
            inputStreams = listOf(externalInput),
            outputStreams = listOf(internalOutput),
            effects = emptyList(),
        ))
        return Triple(machine, externalInput, internalOutput)
    }

    /**
     * Build a "passthrough consumer" Bool machine: state is the latest Bool
     * received; emits Some(received) on its external output every event.
     */
    private fun buildPassthroughConsumer(
        store: NodeStore,
        internalInputStream: NodeId,
    ): Triple<NodeId, NodeId, NodeId> {
        val boolT = store.add(Node.PrimitiveType(Primitive.Bool))

        val someCase = store.add(Node.SumTypeCase("Some", boolT))
        val noneCase = store.add(Node.SumTypeCase("None", null))
        val optionBool = store.add(Node.SumType(listOf(someCase, noneCase)))

        val output0Field = store.add(Node.ProductTypeField("output_0", optionBool))
        val outputBatchType = store.add(Node.ProductType(listOf(output0Field)))
        val stateField = store.add(Node.ProductTypeField("state", boolT))
        val outputsField = store.add(Node.ProductTypeField("outputs", outputBatchType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))

        val sParam = store.add(Node.ParameterDecl("s", boolT))
        val eParam = store.add(Node.ParameterDecl("e", boolT))
        val eRef = store.add(Node.VarRef(eParam))
        val someE = store.add(Node.SumValue(optionBool, "Some", eRef))
        val output0Value = store.add(Node.ProductFieldValue("output_0", someE))
        val outputBatchValue = store.add(Node.ProductValue(outputBatchType, listOf(output0Value)))
        val newStateField = store.add(Node.ProductFieldValue("state", eRef))
        val newOutputsField = store.add(Node.ProductFieldValue("outputs", outputBatchValue))
        val resultValue = store.add(Node.ProductValue(resultType, listOf(newStateField, newOutputsField)))
        val transitionLambda = store.add(Node.Lambda(parameters = listOf(sParam, eParam), body = resultValue))

        val initialState = store.add(Node.BoolLit(false))
        val externalOutput = store.add(Node.EventStream(eventType = boolT, streamKind = StreamKind.Output))

        val machine = store.add(Node.StateMachine(
            transitionFn = transitionLambda,
            initialState = initialState,
            inputStreams = listOf(internalInputStream),
            outputStreams = listOf(externalOutput),
            effects = emptyList(),
        ))
        return Triple(machine, internalInputStream, externalOutput)
    }

    /**
     * Int counterpart of [buildPassthroughConsumer] — passes through Int
     * events for the labeled-producer fan-in / fan-out tests.
     */
    private fun buildPassthroughConsumerInt(
        store: NodeStore,
        internalInputStream: NodeId,
    ): Triple<NodeId, NodeId, NodeId> {
        val intT = store.add(Node.PrimitiveType(Primitive.Int))

        val someCase = store.add(Node.SumTypeCase("Some", intT))
        val noneCase = store.add(Node.SumTypeCase("None", null))
        val optionInt = store.add(Node.SumType(listOf(someCase, noneCase)))

        val output0Field = store.add(Node.ProductTypeField("output_0", optionInt))
        val outputBatchType = store.add(Node.ProductType(listOf(output0Field)))
        val stateField = store.add(Node.ProductTypeField("state", intT))
        val outputsField = store.add(Node.ProductTypeField("outputs", outputBatchType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))

        val sParam = store.add(Node.ParameterDecl("s", intT))
        val eParam = store.add(Node.ParameterDecl("e", intT))
        val eRef = store.add(Node.VarRef(eParam))
        val someE = store.add(Node.SumValue(optionInt, "Some", eRef))
        val output0Value = store.add(Node.ProductFieldValue("output_0", someE))
        val outputBatchValue = store.add(Node.ProductValue(outputBatchType, listOf(output0Value)))
        val newStateField = store.add(Node.ProductFieldValue("state", eRef))
        val newOutputsField = store.add(Node.ProductFieldValue("outputs", outputBatchValue))
        val resultValue = store.add(Node.ProductValue(resultType, listOf(newStateField, newOutputsField)))
        val transitionLambda = store.add(Node.Lambda(parameters = listOf(sParam, eParam), body = resultValue))

        val initialState = store.add(Node.IntLit(0))
        val externalOutput = store.add(Node.EventStream(eventType = intT, streamKind = StreamKind.Output))

        val machine = store.add(Node.StateMachine(
            transitionFn = transitionLambda,
            initialState = initialState,
            inputStreams = listOf(internalInputStream),
            outputStreams = listOf(externalOutput),
            effects = emptyList(),
        ))
        return Triple(machine, internalInputStream, externalOutput)
    }

    /**
     * Build a "labeled producer" Int machine: state is an Int starting at
     * [label], each Unit event emits `Some(state)` then increments state
     * by 1. Used to distinguish producers in fan-in tests (each gets a
     * distinct label base) and to generate a deterministic sequence for
     * fan-out tests.
     *
     * If [internalOutputForReuse] is non-null, the producer reuses that
     * stream (fan-in setup); otherwise a fresh internal stream is allocated,
     * with the supplied [broadcast] consumerMode.
     */
    private fun buildLabeledProducer(
        store: NodeStore,
        label: Int,
        broadcast: Boolean,
        internalOutputForReuse: NodeId? = null,
    ): Triple<NodeId, NodeId, NodeId> {
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val unitT = store.add(Node.PrimitiveType(Primitive.Unit))

        val someCase = store.add(Node.SumTypeCase("Some", intT))
        val noneCase = store.add(Node.SumTypeCase("None", null))
        val optionInt = store.add(Node.SumType(listOf(someCase, noneCase)))

        val output0Field = store.add(Node.ProductTypeField("output_0", optionInt))
        val outputBatchType = store.add(Node.ProductType(listOf(output0Field)))
        val stateField = store.add(Node.ProductTypeField("state", intT))
        val outputsField = store.add(Node.ProductTypeField("outputs", outputBatchType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))

        // Transition: \(s, _) -> { state: s + 1, outputs: { output_0: Some(s) } }
        val sParam = store.add(Node.ParameterDecl("s", intT))
        val eParam = store.add(Node.ParameterDecl("e", unitT))
        val sRef = store.add(Node.VarRef(sParam))
        val oneLit = store.add(Node.IntLit(1))
        val addType = store.add(Node.FunctionType(parameters = listOf(intT, intT), result = intT))
        val addFn = store.add(Node.ForeignNode(target = "strand-builtin:Int.Add", foreignType = addType))
        val nextState = store.add(Node.Application(function = addFn, arguments = listOf(sRef, oneLit)))
        val someS = store.add(Node.SumValue(optionInt, "Some", sRef))
        val output0Value = store.add(Node.ProductFieldValue("output_0", someS))
        val outputBatchValue = store.add(Node.ProductValue(outputBatchType, listOf(output0Value)))
        val newStateField = store.add(Node.ProductFieldValue("state", nextState))
        val newOutputsField = store.add(Node.ProductFieldValue("outputs", outputBatchValue))
        val resultValue = store.add(Node.ProductValue(resultType, listOf(newStateField, newOutputsField)))
        val transitionLambda = store.add(Node.Lambda(parameters = listOf(sParam, eParam), body = resultValue))

        val initialState = store.add(Node.IntLit(label.toLong()))
        val externalInput = store.add(Node.EventStream(eventType = unitT, streamKind = StreamKind.External))
        val internalOutput = internalOutputForReuse
            ?: store.add(Node.EventStream(
                eventType = intT,
                streamKind = StreamKind.Internal,
                consumerMode = if (broadcast) ConsumerMode.Broadcast else null,
            ))

        val machine = store.add(Node.StateMachine(
            transitionFn = transitionLambda,
            initialState = initialState,
            inputStreams = listOf(externalInput),
            outputStreams = listOf(internalOutput),
            effects = emptyList(),
        ))
        return Triple(machine, externalInput, internalOutput)
    }
}
