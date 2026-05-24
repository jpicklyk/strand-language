package org.strand.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value

/**
 * Synchronous trace runtime for state machines (Layer 6 step 1).
 *
 * Given a verified StateMachine node and a fixed list of event values, drives
 * the machine over those events on the calling thread and returns the full
 * [Trace] of (event, before, after, outputs) triples plus a terminating
 * halt record.
 *
 * The runtime is a *deterministic fold over the event list*: for each event,
 * it applies the cached transition function closure to `(currentState,
 * event)`, decomposes the result into the next state plus an OutputBatch
 * (per the convention pinned in [stepOnce]), appends a step to the trace,
 * and continues. Step 1 halts only on event-list exhaustion; termination
 * sentinels and supervisor-initiated halts are step 2/3 features.
 *
 * **OutputBatch convention.** The transition function's result must be a
 * product `{state: State, outputs: OutputBatch}` where `OutputBatch` is a
 * product whose field at index `i` is named `output_i` and holds
 * `Option<outputStreams[i].eventType>` — `Some(payload)` if the slot
 * emits this step, `None` if not. Field naming is positional (`output_0`,
 * `output_1`, ...) by deliberate choice: there is no concrete syntax in
 * Strand, so the name carries no human meaning; the position is the
 * structural identity. Step 2 will switch to a recursive-list-based
 * representation `(State, List<TaggedOutput>)` now that recursive types
 * are landed.
 *
 * **Cached closure.** The transition Lambda is evaluated once at
 * `runMachine` entry and the resulting [Value.Closure] is stored on the
 * [MachineInstance]. This matters because the transition Lambda may be the
 * body of a let-expression whose captured environment is non-trivial to
 * recompute every event; caching makes per-event dispatch a single
 * `Interpreter.applyCallable` call. The cached value is also a forward-
 * compatible seam: step 2's actor loop calls the same `applyCallable`.
 *
 * The runtime never re-runs verification — it trusts its input has been
 * verified. The verifier (in step 4) ensures the StateMachine shape is
 * well-formed before this runtime sees it.
 */
class StateMachineRuntime(
    private val store: NodeStore,
    private val hashToNodeId: Map<Hash, NodeId> = emptyMap(),
    private val interpreter: Interpreter = Interpreter(store, hashToNodeId),
) {

    /**
     * Drive a [machine] (a verified [Node.StateMachine] NodeId) over the
     * supplied [events], under the supplied [capabilities] context. Returns
     * the full [Trace].
     *
     * The events are typed Strand values, not raw JSON or wire bytes —
     * callers that have a JSON event list should run them through
     * [EventCodec] first to get a `List<Value>`.
     *
     * Step 1 only supports machines with exactly one input stream
     * (verifier-enforced). Multi-stream machines are step 2.
     */
    fun runMachine(
        machine: NodeId,
        events: List<Value>,
        capabilities: CapabilitySet = CapabilitySet.EMPTY,
    ): Trace {
        val instance = buildInstance(machine, events, capabilities)
        val steps = mutableListOf<TraceStep.Step>()
        // Step 1 is a single-input-stream machine; the input queue we built
        // is the only one. We could equally loop over `events` directly,
        // but using the queue mirrors the data structures step 2's actor
        // loop will operate on (so the surrounding control flow stays
        // recognizable across the rewrite).
        val (inputStreamId, queue) = instance.inputQueues.entries.single()
        while (queue.isNotEmpty() && !instance.halted) {
            val event = queue.removeFirst()
            steps += stepOnce(instance, event)
        }
        // Step 1 only halts on event-list exhaustion. Step 2 will add
        // explicit termination and supervisor-initiated halts.
        val halt = TraceStep.Halt(
            finalState = instance.currentState,
            reason = HaltReason.EventsExhausted,
        )
        // inputStreamId is unused at this point; kept assigned to silence
        // the destructuring "val unused" complaint. The variable exists to
        // document that step 1 has exactly one input queue.
        @Suppress("UNUSED_VARIABLE")
        val _input = inputStreamId
        return Trace(steps = steps, final = halt)
    }

    /**
     * Layer 6 step 2: spawn one coroutine actor per machine in [group] and
     * return a [MachineGroupHandle] for the host to push events into
     * external inputs, drain external outputs, await completion, and
     * recover per-instance event recordings for replay-determinism tests.
     *
     * The synchronous fold from `runMachine` is preserved unchanged and
     * remains the deterministic-replay seam: feed `MachineGroupHandle.
     * recordedEvents(instanceId)` back into `runMachine` against the same
     * StateMachine NodeId to reproduce the per-step transitions the async
     * actor observed.
     *
     * Stream wiring is computed once at startup from the topology — every
     * EventStream gets one `Channel<Value>` of [MachineGroup.bufferCapacity];
     * `external` inputs and `output` streams expose host-facing half-types;
     * `internal` streams are shared between producer and consumer machines.
     * Single-producer / single-consumer is enforced on internal streams in
     * step 2 (the verifier rejects fan-in and fan-out topologies).
     *
     * The actors run on the supplied [scope]'s context (caller decides which
     * dispatcher). Tests typically pass a `TestScope` for virtual-time
     * scheduling; production callers pass a scope on `Dispatchers.Default`.
     */
    fun runGroup(group: MachineGroup, scope: CoroutineScope): MachineGroupHandle {
        // Pass 0: validate topology (single-producer / single-consumer on
        // internal streams; external/output streams are host-driven). Fails
        // fast before allocating any channels or coroutines.
        group.validateTopology()
        // Pass 1: allocate one Channel<Value> per unique stream NodeId.
        // Content-addressing pins structurally-equal streams to the same
        // NodeId, so two machines that reference the same stream share a
        // channel automatically — that IS the wiring.
        val streamChannels: Map<NodeId, Channel<Value>> = group.uniqueStreams()
            .associateWith { Channel(capacity = group.bufferCapacity) }

        // Pass 2: build one MachineInstance per machine, pointing each
        // instance's inputChannels / outputChannels at the shared channel
        // table.
        val groupInterpreter = Interpreter(group.store, group.hashToNodeId)
        val instances = group.machines.map { machineId ->
            buildActorInstance(machineId, streamChannels, group, groupInterpreter)
        }

        // Pass 3: spawn one actor coroutine per instance.
        val jobs: List<Job> = instances.map { instance ->
            val actor = MachineActor(instance, groupInterpreter)
            scope.launch { actor.run() }
        }

        // Host-facing channel handles. External inputs are SendChannel
        // (host pushes events in); external outputs are ReceiveChannel
        // (host drains events out).
        val externalInputs = group.externalInputStreams()
            .associateWith { streamChannels.getValue(it) }
        val externalOutputs = group.externalOutputStreams()
            .associateWith { streamChannels.getValue(it) }

        val instanceHandles = instances.associate { instance ->
            instance.instanceId to MachineInstanceHandle(instance)
        }

        return MachineGroupHandle(
            externalInputs = externalInputs,
            externalOutputs = externalOutputs,
            instances = instanceHandles,
            jobs = jobs,
        )
    }

    /**
     * Build an actor-shape [MachineInstance] (channels, not pre-loaded
     * queues). The transitionFn is evaluated once at instance start and
     * cached; this is the same caching the step 1 `buildInstance` does, so
     * per-event dispatch in the actor loop is a single `applyCallable`
     * call.
     */
    private fun buildActorInstance(
        machineId: NodeId,
        streamChannels: Map<NodeId, Channel<Value>>,
        group: MachineGroup,
        actorInterpreter: Interpreter,
    ): MachineInstance {
        val node = group.store.get(machineId) as? Node.StateMachine
            ?: error(
                "runGroup: expected a StateMachine at $machineId, got " +
                    "${group.store.getOrNull(machineId)?.javaClass?.simpleName}; " +
                    "did the verifier pass?"
            )
        val transitionFnValue = actorInterpreter.eval(node.transitionFn, group.capabilities)
        val initialStateValue = actorInterpreter.eval(node.initialState, group.capabilities)
        val inputChannels = node.inputStreams.associateWith { streamChannels.getValue(it) }
        val outputChannels = node.outputStreams.associateWith { streamChannels.getValue(it) }
        val recorder = if (group.recordInputs) EventRecorder() else null
        return MachineInstance(
            instanceId = InstanceId.generate(),
            node = node,
            transitionFnValue = transitionFnValue,
            currentState = initialStateValue,
            capabilities = group.capabilities,
            inputChannels = inputChannels,
            outputChannels = outputChannels,
            recorder = recorder,
            halted = false,
        )
    }

    /**
     * Build a fresh [MachineInstance]: evaluate the transitionFn (once,
     * cached), evaluate the initialState, allocate the input queue and
     * pre-load it with the supplied events, allocate the output sinks.
     *
     * The transitionFn is evaluated under the same [capabilities] context
     * that will surround per-event calls — its closure capture happens once,
     * at instance start.
     */
    private fun buildInstance(
        machineId: NodeId,
        events: List<Value>,
        capabilities: CapabilitySet,
    ): MachineInstance {
        val node = store.get(machineId) as? Node.StateMachine
            ?: error(
                "runMachine: expected a StateMachine at $machineId, got " +
                    "${store.getOrNull(machineId)?.javaClass?.simpleName}; " +
                    "did the verifier pass?"
            )
        // Step 1 invariant: exactly one input stream.
        require(node.inputStreams.size == 1) {
            "Layer 6 step 1 runtime requires exactly 1 input stream; " +
                "got ${node.inputStreams.size} — the verifier should have rejected this."
        }
        val transitionFnValue = interpreter.eval(node.transitionFn, capabilities)
        val initialStateValue = interpreter.eval(node.initialState, capabilities)
        val inputStreamId = node.inputStreams.single()
        val inputQueues = mapOf<NodeId, ArrayDeque<Value>>(
            inputStreamId to ArrayDeque(events)
        )
        val outputSinks = node.outputStreams.associateWith { mutableListOf<Value>() }
        return MachineInstance(
            instanceId = InstanceId.generate(),
            node = node,
            transitionFnValue = transitionFnValue,
            currentState = initialStateValue,
            capabilities = capabilities,
            inputQueues = inputQueues,
            outputSinks = outputSinks,
            halted = false,
        )
    }

    /**
     * One transition step: apply the cached transition function closure to
     * `(currentState, event)`, decompose the resulting `{state, outputs}`
     * product, update [MachineInstance.currentState], append emitted values
     * to the per-output-stream sinks, and return the per-step trace record.
     *
     * Decomposition expectations (per the OutputBatch convention):
     *   * The transition result is a [Value.ProductV] with at least
     *     fields `state` and `outputs`.
     *   * `outputs` is itself a [Value.ProductV] with fields `output_0`,
     *     `output_1`, ... one per declared output stream.
     *   * Each `output_i` is a [Value.SumV] with case `Some(payload)` or
     *     `None`. `Some` payloads append to the sink; `None` slots are
     *     silently dropped.
     *
     * Verifier shape enforcement covers all of these, so the casts below
     * are unconditional; mismatches indicate either a verifier bug or that
     * the runtime was handed an unverified graph.
     */
    private fun stepOnce(instance: MachineInstance, event: Value): TraceStep.Step {
        val before = instance.currentState
        val resultValue = interpreter.applyCallable(
            fn = instance.transitionFnValue,
            args = listOf(before, event),
            capabilities = instance.capabilities,
        )
        val resultProduct = resultValue as? Value.ProductV
            ?: error(
                "transition function returned a non-product value " +
                    "(${resultValue::class.simpleName}); expected " +
                    "{state, outputs}. Verifier should have rejected this graph."
            )
        val newState = resultProduct.fields["state"]
            ?: error(
                "transition function result product missing 'state' field; " +
                    "got fields ${resultProduct.fields.keys}. Verifier should " +
                    "have rejected this graph."
            )
        val outputBatch = resultProduct.fields["outputs"] as? Value.ProductV
            ?: error(
                "transition function result product missing 'outputs' field, " +
                    "or 'outputs' is not a product; got fields " +
                    "${resultProduct.fields.keys}. Verifier should have " +
                    "rejected this graph."
            )
        instance.currentState = newState

        val emitted = mutableListOf<Value>()
        for ((i, outputStreamId) in instance.node.outputStreams.withIndex()) {
            val slotName = "output_$i"
            val slotValue = outputBatch.fields[slotName] as? Value.SumV
                ?: error(
                    "OutputBatch missing slot '$slotName' (or wrong type); " +
                        "got fields ${outputBatch.fields.keys}. Verifier should " +
                        "have rejected this graph."
                )
            when (slotValue.case) {
                "Some" -> {
                    val payload = slotValue.payload
                        ?: error("Some output slot '$slotName' has null payload")
                    instance.outputSinks.getValue(outputStreamId).add(payload)
                    emitted += payload
                }
                "None" -> Unit  // no emission this step
                else -> error(
                    "OutputBatch slot '$slotName' has unexpected case " +
                        "'${slotValue.case}'; expected Some or None."
                )
            }
        }

        return TraceStep.Step(
            event = event,
            before = before,
            after = newState,
            outputs = emitted,
        )
    }
}
