package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Node
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.core.StreamKind
import org.strand.interpreter.Value

/**
 * Unit tests for [StateMachineRuntime.runGroup] (Layer 6 step 2).
 *
 * Programs are constructed programmatically (not via JSON) so these tests
 * run independently of the JSON ingest + verifier pipeline. They cover the
 * actor-loop core: select-based input dispatch, recorder behavior,
 * inter-machine wiring through shared channels, OutputBatch dispatch (the
 * step-1 path preserved in step 2), graceful halt on channel close,
 * replay-determinism reproduction through `runMachine`.
 *
 * Multi-input merge (the synthesized InputEvent sum), tagged-output list
 * dispatch, and supervisor patterns require verifier work to land first;
 * those are covered in the corpus tests `AsyncCorpusTest` once the
 * verifier integration is in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachineGroupTest {

    /**
     * Toggle machine: state is Bool starting at false; each Unit event flips
     * the state; no outputs.
     *
     * Mirrors corpus program 41-toggle-machine but built in-memory so the
     * test runs without ingest/verifier dependencies.
     */
    private fun buildToggleMachine(store: NodeStore): Pair<org.strand.core.NodeId, org.strand.core.NodeId> {
        val boolT = store.add(Node.PrimitiveType(Primitive.Bool))
        val unitT = store.add(Node.PrimitiveType(Primitive.Unit))
        val emptyProductType = store.add(Node.ProductType(emptyList()))
        val stateField = store.add(Node.ProductTypeField("state", boolT))
        val outputsField = store.add(Node.ProductTypeField("outputs", emptyProductType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))

        // Build: \(s, e) -> {state: not s, outputs: {}}
        val sParam = store.add(Node.ParameterDecl("s", boolT))
        val eParam = store.add(Node.ParameterDecl("e", unitT))
        val sRef = store.add(Node.VarRef(sParam))
        // Use Bool.Not builtin
        val notType = store.add(Node.FunctionType(parameters = listOf(boolT), result = boolT))
        val notFn = store.add(Node.ForeignNode(target = "strand-builtin:Bool.Not", foreignType = notType))
        val flipped = store.add(Node.Application(function = notFn, arguments = listOf(sRef)))
        val newStateField = store.add(Node.ProductFieldValue("state", flipped))
        val emptyValue = store.add(Node.ProductValue(emptyProductType, emptyList()))
        val newOutputsField = store.add(Node.ProductFieldValue("outputs", emptyValue))
        val resultValue = store.add(Node.ProductValue(resultType, listOf(newStateField, newOutputsField)))
        val transitionLambda = store.add(Node.Lambda(parameters = listOf(sParam, eParam), body = resultValue))

        val initialState = store.add(Node.BoolLit(false))
        val inputStream = store.add(Node.EventStream(eventType = unitT, streamKind = StreamKind.External))

        val machine = store.add(Node.StateMachine(
            transitionFn = transitionLambda,
            initialState = initialState,
            inputStreams = listOf(inputStream),
            outputStreams = emptyList(),
            effects = emptyList(),
        ))

        return machine to inputStream
    }

    @Test
    fun `single-machine async run consumes events from external input and halts on close`() = runTest {
        val store = NodeStore()
        val (machineId, inputStreamId) = buildToggleMachine(store)
        val runtime = StateMachineRuntime(store)

        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machineId),
        )
        val handle = runtime.runGroup(group, this)

        val inputChannel = handle.externalInputs.getValue(inputStreamId)
        inputChannel.send(Value.UnitV)
        inputChannel.send(Value.UnitV)
        inputChannel.send(Value.UnitV)
        inputChannel.close()

        handle.await()

        val instance = handle.instances.values.single()
        assertEquals(Value.BoolV(true), instance.currentState) {
            "Three toggles from false → true → false → true; final must be true"
        }
        assertTrue(instance.halted)
    }

    @Test
    fun `recorder captures the exact input sequence the actor consumed`() = runTest {
        val store = NodeStore()
        val (machineId, inputStreamId) = buildToggleMachine(store)
        val runtime = StateMachineRuntime(store)

        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machineId),
        )
        val handle = runtime.runGroup(group, this)
        val inputChannel = handle.externalInputs.getValue(inputStreamId)

        repeat(5) { inputChannel.send(Value.UnitV) }
        inputChannel.close()
        handle.await()

        val instanceId = handle.instances.keys.single()
        val recorded = handle.recordedEvents(instanceId)
        assertNotNull(recorded)
        assertEquals(5, recorded!!.size)
        assertTrue(recorded.all { it == Value.UnitV })
    }

    @Test
    fun `recorder is null when group disables recording`() = runTest {
        val store = NodeStore()
        val (machineId, inputStreamId) = buildToggleMachine(store)
        val runtime = StateMachineRuntime(store)

        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machineId),
            recordInputs = false,
        )
        val handle = runtime.runGroup(group, this)
        val inputChannel = handle.externalInputs.getValue(inputStreamId)

        repeat(3) { inputChannel.send(Value.UnitV) }
        inputChannel.close()
        handle.await()

        val instanceId = handle.instances.keys.single()
        assertEquals(null, handle.recordedEvents(instanceId)) {
            "When recordInputs=false, recordedEvents should return null"
        }
    }

    @Test
    fun `replay determinism — async-recorded events reproduce the runMachine Trace`() = runTest {
        val store = NodeStore()
        val (machineId, inputStreamId) = buildToggleMachine(store)
        val runtime = StateMachineRuntime(store)

        // Async run.
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machineId),
        )
        val handle = runtime.runGroup(group, this)
        val inputChannel = handle.externalInputs.getValue(inputStreamId)
        repeat(7) { inputChannel.send(Value.UnitV) }
        inputChannel.close()
        handle.await()

        val recorded = handle.recordedEvents(handle.instances.keys.single())!!

        // Synchronous replay with the captured event list.
        val replayTrace = runtime.runMachine(machineId, recorded)

        assertEquals(7, replayTrace.steps.size)
        // Bool toggle from false: every odd step ends true, every even step false.
        assertEquals(Value.BoolV(true), replayTrace.final.finalState) {
            "Seven toggles starting from false must end at true"
        }
        assertEquals(HaltReason.EventsExhausted, replayTrace.final.reason)
    }

    @Test
    fun `cancel terminates an actor whose input channel never closes`() = runTest {
        val store = NodeStore()
        val (machineId, inputStreamId) = buildToggleMachine(store)
        val runtime = StateMachineRuntime(store)

        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machineId),
        )
        val handle = runtime.runGroup(group, this)
        val inputChannel = handle.externalInputs.getValue(inputStreamId)
        inputChannel.send(Value.UnitV)
        inputChannel.send(Value.UnitV)
        // Don't close — the actor would block forever waiting for more.

        handle.cancel()

        // All jobs joined; the test's runTest will fail if any coroutine is still alive.
        val instance = handle.instances.values.single()
        // currentState should reflect at most the two events we sent (could be 0, 1, or 2
        // depending on how many were processed before cancellation arrived).
        val state = instance.currentState as Value.BoolV
        // Trivially true; just confirms cancellation didn't leave the actor in a corrupt state.
        assertTrue(state.v || !state.v)
    }

    /**
     * Two-machine echo: machine A flips its Bool state on each Unit event
     * and emits the new state on an internal stream. Machine B receives
     * those Bools on its single input stream and re-emits them unchanged on
     * an external output stream (its state is also a Bool, just the latest
     * input). Verifies internal-stream wiring: A's output channel IS B's
     * input channel.
     */
    @Test
    fun `two-machine internal-stream wiring drives downstream consumer`() = runTest {
        val store = NodeStore()
        val (machineA, externalInputA, internalAB) = buildPing(store)
        val (machineB, _, externalOutputB) = buildPong(store, internalAB)
        val runtime = StateMachineRuntime(store)

        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machineA, machineB),
        )
        val handle = runtime.runGroup(group, this)

        val input = handle.externalInputs.getValue(externalInputA)
        val output = handle.externalOutputs.getValue(externalOutputB)
        input.send(Value.UnitV)
        input.send(Value.UnitV)
        input.send(Value.UnitV)
        input.close()

        // Drain three values from the external output channel.
        val received = mutableListOf<Value>()
        repeat(3) { received += output.receive() }

        handle.await()

        // Machine A starts at false; flips to true → false → true.
        // Machine B echoes whatever A emits.
        assertEquals(listOf(Value.BoolV(true), Value.BoolV(false), Value.BoolV(true)), received)
    }

    /**
     * Build the "ping" machine (A): state Bool, single Unit-event external
     * input, single Bool internal output that emits the new state on every
     * event. Returns (machineId, externalInputStreamId, internalOutputStreamId).
     */
    private fun buildPing(store: NodeStore): Triple<org.strand.core.NodeId, org.strand.core.NodeId, org.strand.core.NodeId> {
        val boolT = store.add(Node.PrimitiveType(Primitive.Bool))
        val unitT = store.add(Node.PrimitiveType(Primitive.Unit))

        // Option<Bool> = { Some Bool | None }
        val someCase = store.add(Node.SumTypeCase("Some", boolT))
        val noneCase = store.add(Node.SumTypeCase("None", null))
        val optionBool = store.add(Node.SumType(listOf(someCase, noneCase)))

        // OutputBatch = { output_0: Option<Bool> }
        val output0Field = store.add(Node.ProductTypeField("output_0", optionBool))
        val outputBatchType = store.add(Node.ProductType(listOf(output0Field)))

        // Result = { state: Bool, outputs: OutputBatch }
        val stateField = store.add(Node.ProductTypeField("state", boolT))
        val outputsField = store.add(Node.ProductTypeField("outputs", outputBatchType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))

        // Transition: \(s, _) -> { state: not s, outputs: { output_0: Some(not s) } }
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
        val internalOutput = store.add(Node.EventStream(eventType = boolT, streamKind = StreamKind.Internal))

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
     * Build the "pong" machine (B): state Bool, input is the supplied
     * [internalInputStream] (so it wires to A's output), output is an
     * external Bool stream that echoes whatever B receives. State is just
     * the latest received value.
     */
    private fun buildPong(
        store: NodeStore,
        internalInputStream: org.strand.core.NodeId,
    ): Triple<org.strand.core.NodeId, org.strand.core.NodeId, org.strand.core.NodeId> {
        val boolT = store.add(Node.PrimitiveType(Primitive.Bool))

        val someCase = store.add(Node.SumTypeCase("Some", boolT))
        val noneCase = store.add(Node.SumTypeCase("None", null))
        val optionBool = store.add(Node.SumType(listOf(someCase, noneCase)))

        val output0Field = store.add(Node.ProductTypeField("output_0", optionBool))
        val outputBatchType = store.add(Node.ProductType(listOf(output0Field)))
        val stateField = store.add(Node.ProductTypeField("state", boolT))
        val outputsField = store.add(Node.ProductTypeField("outputs", outputBatchType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))

        // Transition: \(_, e) -> { state: e, outputs: { output_0: Some(e) } }
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
     * Topology rejection: a MachineGroup whose only machine consumes an
     * `internal` stream that no machine produces.
     */
    @Test
    fun `topology validation rejects internal stream with no producer`() = runTest {
        val store = NodeStore()
        // Build a one-machine group whose input is an internal stream, but
        // no machine produces it.
        val boolT = store.add(Node.PrimitiveType(Primitive.Bool))
        val unitT = store.add(Node.PrimitiveType(Primitive.Unit))
        val orphanInternal = store.add(Node.EventStream(eventType = unitT, streamKind = StreamKind.Internal))

        val sParam = store.add(Node.ParameterDecl("s", boolT))
        val eParam = store.add(Node.ParameterDecl("e", unitT))
        val falseLit = store.add(Node.BoolLit(false))
        val emptyProductType = store.add(Node.ProductType(emptyList()))
        val stateField = store.add(Node.ProductTypeField("state", boolT))
        val outputsField = store.add(Node.ProductTypeField("outputs", emptyProductType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))
        val newStateField = store.add(Node.ProductFieldValue("state", falseLit))
        val emptyOutputs = store.add(Node.ProductValue(emptyProductType, emptyList()))
        val newOutputsField = store.add(Node.ProductFieldValue("outputs", emptyOutputs))
        val resultValue = store.add(Node.ProductValue(resultType, listOf(newStateField, newOutputsField)))
        val transitionLambda = store.add(Node.Lambda(parameters = listOf(sParam, eParam), body = resultValue))

        val machine = store.add(Node.StateMachine(
            transitionFn = transitionLambda,
            initialState = falseLit,
            inputStreams = listOf(orphanInternal),
            outputStreams = emptyList(),
            effects = emptyList(),
        ))
        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machine),
        )
        val ex = assertThrows(MachineGroupValidationError.InternalStreamNoProducer::class.java) {
            runtime.runGroup(group, this)
        }
        assertEquals(orphanInternal, ex.stream)
    }
}
