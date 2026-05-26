package org.strand.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import org.strand.core.ConsumerMode
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
        // Pass 0: validate topology. Slice 3.6 relaxations: multi-producer
        // fan-in always allowed; multi-consumer allowed when the stream's
        // consumerMode is Broadcast. Fails fast before allocating any
        // channels or coroutines.
        group.validateTopology()
        // Pass 1: allocate one StreamBus per unique stream NodeId. Direct
        // buses (the pre-slice-3.6 default) wrap a single Channel<Value>;
        // broadcast buses wrap a producer-facing Channel<Value> bridged into
        // a MutableSharedFlow plus per-consumer dedicated channels (allocated
        // lazily in [StreamBus.Broadcast.consumerChannel]).
        //
        // Slice 3.1: each producer-facing channel's capacity is the
        // EventStream's own `bufferSize` content field when set; otherwise
        // the group's [MachineGroup.bufferCapacity] (default 1024).
        // Content-addressing pins structurally-equal streams to the same
        // NodeId, so two machines that reference the same stream share a
        // bus automatically — that IS the wiring.
        val streamBuses: Map<NodeId, StreamBus> = group.uniqueStreams()
            .associateWith { streamId ->
                val streamNode = group.store.get(streamId) as Node.EventStream
                val capacity = streamNode.bufferSize ?: group.bufferCapacity
                val producerChannel = Channel<Value>(capacity = capacity)
                // Producer count for the bus's "all producers halted → close
                // channel" lifecycle (slice 3.6 multi-producer fan-in
                // support). Counts only machines in the group whose
                // outputStreams contains this stream; external-input streams
                // have producer count 0 and never auto-close (the host owns
                // closure via the externalInputs SendChannel).
                val producerCount = group.machines.count { mid ->
                    (group.store.get(mid) as Node.StateMachine).outputStreams.contains(streamId)
                }
                when (group.consumerModeOf(streamId)) {
                    ConsumerMode.Single -> StreamBus.Direct(
                        producerChannel = producerChannel,
                        producerCount = producerCount,
                    )
                    ConsumerMode.Broadcast -> StreamBus.Broadcast(
                        producerChannel = producerChannel,
                        producerCount = producerCount,
                        perConsumerBufferCapacity = capacity,
                    )
                }
            }

        // Pass 1b (slice 3.1): allocate one OverflowDispatcher per stream
        // that any machine sends INTO. The dispatcher wraps the producer-
        // facing channel (the bus's `producerChannel`); consumer-side reads
        // go through their bus.consumerChannel(...) unchanged. The dispatcher
        // carries the EventStream's declared `overflowPolicy` (defaulting to
        // BlockProducer for absent / null policies).
        val streamDispatchers: Map<NodeId, OverflowDispatcher> = streamBuses.mapValues { (id, bus) ->
            val streamNode = group.store.get(id) as Node.EventStream
            val policy = streamNode.overflowPolicy ?: org.strand.core.OverflowPolicy.BlockProducer
            OverflowDispatcher(bus.producerChannel, policy)
        }

        // Slice 3.2 supervision: allocate the RuntimeContext that owns the
        // mutable instance/job maps and provides the foreign dispatcher for
        // in-band Spawn/Terminate calls. Initial instances are spawned via
        // [RuntimeContext.spawn] below; subsequent supervisor-driven Spawn
        // calls go through the same path, so dynamic spawning is the same
        // mechanism as initial spawning.
        val context = RuntimeContext(
            store = group.store,
            hashToNodeId = group.hashToNodeId,
            nodeIdToHash = group.nodeIdToHash,
            scope = scope,
            streamBuses = streamBuses,
            streamDispatchers = streamDispatchers,
            capabilities = group.capabilities,
            recordInputs = group.recordInputs,
            dispatcherFactory = group.dispatcherFactory,
        )

        // Pass 2: spawn one initial actor per declared machine. Each
        // spawn() call builds a MachineInstance (channels, dispatchers,
        // buses), registers it in the context's instance map, and launches
        // a coroutine actor running [MachineActor]. Per-actor [Interpreter]
        // instances are constructed inside spawn() with the context's
        // foreign dispatcher, so in-band Spawn/Terminate calls reach the
        // runtime here.
        //
        // The producer-spawn counter is incremented for each stream the
        // spawned machine produces into; for INITIAL spawn this would
        // double-count vs. the producerCount baked into each StreamBus at
        // Pass 1 (which already accounts for the original machine list).
        // To prevent this, we offset by decrementing each bus's count once
        // per initial machine — net effect: the bus's remainingProducers
        // ends up equal to the original producerCount.
        for (machineId in group.machines) {
            val machineNode = group.store.get(machineId) as Node.StateMachine
            for (streamId in machineNode.outputStreams) {
                streamBuses.getValue(streamId).producerSpawnedOffsetForInitial()
            }
        }
        for (machineId in group.machines) {
            context.spawn(machineId)
        }

        // Pass 2b (slice 3.6): launch one pump coroutine per Broadcast bus.
        // Each pump drains the bus's producer-facing channel and forwards
        // every value into every per-consumer Channel<Value> allocated in
        // Pass 2. When the producer channel closes, the pump finishes
        // draining and closes every per-consumer channel so each consumer
        // actor sees EOF on its input.
        val pumpJobs: List<Job> = streamBuses.values.mapNotNull { bus ->
            when (bus) {
                is StreamBus.Direct -> null
                is StreamBus.Broadcast -> scope.launch { runBroadcastPump(bus) }
            }
        }

        // Host-facing channel handles. External inputs: host pushes events
        // into the producer-facing channel. External outputs: host drains
        // from the producer-facing channel (output streams have no machine
        // consumer, so they remain pre-slice-3.6 plain Channel<Value>).
        val externalInputs: Map<NodeId, SendChannel<Value>> = group.externalInputStreams()
            .associateWith { streamId -> streamBuses.getValue(streamId).producerChannel }
        val externalOutputs: Map<NodeId, ReceiveChannel<Value>> = group.externalOutputStreams()
            .associateWith { streamId -> streamBuses.getValue(streamId).producerChannel }

        val instanceHandles = context.instances.entries.associate { (id, instance) ->
            id to MachineInstanceHandle(instance)
        }

        // Slice 3.4 metrics: thread the per-stream dispatcher and producer-
        // channel maps into the handle so `metrics()` can surface per-stream
        // overflow drops and closed-for-send status. Both maps cover every
        // unique stream in the group (`streamBuses.keys`), so external inputs,
        // external outputs, and internal streams all show up under
        // [RuntimeMetrics.perStream].
        val streamProducerChannels: Map<NodeId, Channel<Value>> = streamBuses.mapValues { (_, bus) ->
            bus.producerChannel
        }

        return MachineGroupHandle(
            externalInputs = externalInputs,
            externalOutputs = externalOutputs,
            instances = instanceHandles,
            jobs = context.actorJobs.toList() + pumpJobs,
            streamDispatchers = streamDispatchers,
            streamProducerChannels = streamProducerChannels,
            nodeIdToHash = group.nodeIdToHash,
            context = context,
        )
    }

    /**
     * Resume an instance from a [Snapshot] and drive it over [additionalEvents]
     * synchronously (Layer 6 step 3 slice 3.3). Returns the [Trace] covering
     * only the post-snapshot events; combining with the pre-snapshot trace
     * (recoverable from the snapshot's [Snapshot.recordedEventsPreSnapshot]
     * via `runMachine(machineId, snapshotState=snapshot.snapshotState, ...)`)
     * reconstructs the full history.
     *
     * Replay determinism: this is the property [`design/state-machines.md`]
     * § Conceptual model commits to. Given a snapshot S and post-events E,
     * `resume(machineId, S, E)` produces the same per-step trace the live
     * actor would have produced after the snapshot point — by construction,
     * since transitions are pure.
     *
     * Throws [SnapshotMachineHashMismatch] when [machineId]'s hash (looked
     * up in [nodeIdToHash], which must be non-null and contain the machine)
     * does not match the snapshot's recorded [Snapshot.machineHash]. This
     * is the integrity check that prevents replaying a snapshot against a
     * recompiled (different-content) version of the machine.
     */
    fun resume(
        machineId: NodeId,
        snapshot: Snapshot,
        additionalEvents: List<Value>,
        nodeIdToHash: Map<NodeId, Hash>,
        capabilities: CapabilitySet = CapabilitySet.EMPTY,
    ): Trace {
        // Integrity check: the machine has not been recompiled.
        val actualHash = nodeIdToHash[machineId]
            ?: error("nodeIdToHash missing entry for $machineId; cannot verify snapshot integrity")
        if (actualHash != snapshot.machineHash) {
            throw SnapshotMachineHashMismatch(expected = snapshot.machineHash, actual = actualHash)
        }
        // Build a fresh instance pre-loaded with the snapshot state instead
        // of the machine's declared initialState. Everything else (cached
        // transitionFn closure, input queue, output sinks) follows the same
        // `runMachine`-style construction.
        val node = store.get(machineId) as? Node.StateMachine
            ?: error(
                "resume: expected a StateMachine at $machineId, got " +
                    "${store.getOrNull(machineId)?.javaClass?.simpleName}"
            )
        require(node.inputStreams.size == 1) {
            "Layer 6 step 3 slice 3.3 sync resume requires exactly 1 input stream " +
                "(matching runMachine); got ${node.inputStreams.size}"
        }
        val transitionFnValue = interpreter.eval(node.transitionFn, capabilities)
        val inputStreamId = node.inputStreams.single()
        val inputQueues = mapOf<NodeId, ArrayDeque<Value>>(
            inputStreamId to ArrayDeque(additionalEvents)
        )
        val outputSinks = node.outputStreams.associateWith { mutableListOf<Value>() }
        val instance = MachineInstance(
            instanceId = InstanceId.generate(),
            node = node,
            machineNodeId = machineId,
            transitionFnValue = transitionFnValue,
            currentState = snapshot.snapshotState,
            capabilities = capabilities,
            inputQueues = inputQueues,
            outputSinks = outputSinks,
            halted = false,
        )
        val steps = mutableListOf<TraceStep.Step>()
        val queue = instance.inputQueues.getValue(inputStreamId)
        while (queue.isNotEmpty() && !instance.halted) {
            val event = queue.removeFirst()
            steps += stepOnce(instance, event)
        }
        return Trace(
            steps = steps,
            final = TraceStep.Halt(
                finalState = instance.currentState,
                reason = HaltReason.EventsExhausted,
            ),
        )
    }

    /**
     * Drain [bus]'s producer-facing channel and forward every value to
     * every per-consumer channel (Layer 6 step 3 slice 3.6 broadcast
     * fan-out). Runs as a dedicated coroutine, one per Broadcast bus.
     * Completes when the producer channel is closed; at that point it
     * closes every per-consumer channel so each consumer actor sees EOF
     * on its input and halts normally.
     *
     * Per-consumer channels are allocated in Pass 2 of `runGroup` before
     * this pump launches, so [StreamBus.Broadcast.perConsumerChannels] is
     * complete at pump-start time. If a consumer subscribes late (no
     * mechanism for this in slice 3.6, but defensive), it sees only events
     * from its subscription point forward — the snapshot taken at every
     * drain pass picks up new consumers on the next iteration.
     */
    private suspend fun runBroadcastPump(bus: StreamBus.Broadcast) {
        try {
            for (value in bus.producerChannel) {
                for (channel in bus.perConsumerChannels()) {
                    channel.send(value)
                }
            }
        } finally {
            bus.closeAllConsumerChannels()
        }
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
            machineNodeId = machineId,
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
        // Track A.4: dispatch through the per-instance dispatcher when
        // one was supplied (VM-backed in test scenarios, etc.); fall
        // through to the legacy interpreter.applyCallable on the cached
        // transitionFnValue when no dispatcher is set.
        val resultValue = instance.dispatcher
            ?.applyTransition(before, event)
            ?: interpreter.applyCallable(
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
