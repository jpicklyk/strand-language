package org.strand.runtime

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.joinAll
import org.strand.core.Hash
import org.strand.core.NodeId
import org.strand.interpreter.Value
import kotlinx.coroutines.channels.Channel

/**
 * Async handle returned by [StateMachineRuntime.runGroup]. Callers use it to
 * feed events into external input streams, drain emitted events from external
 * output streams, await termination, cancel, and (for replay-determinism
 * tests) recover the per-instance event recordings.
 *
 * The handle is the boundary between the actor coroutines and the calling
 * host. Inside the group, machines communicate via shared `Channel<Value>`
 * instances allocated at startup; the handle exposes producer/consumer
 * half-types for the streams declared `external` or `output` in the topology.
 *
 * Lifecycle:
 *  * Construct via `runGroup` — actors are spawned, channels allocated.
 *  * Host pushes events into [externalInputs] and drains [externalOutputs].
 *  * Host calls [await] to wait for natural quiescence (all input channels
 *    close, all actors halt), or [cancel] to force a teardown.
 *  * After termination, [recordedEvents] returns the input event sequence
 *    each instance saw — feed back into [StateMachineRuntime.runMachine]
 *    for byte-equal trace assertions.
 */
class MachineGroupHandle internal constructor(
    /**
     * Each host-feedable `external` input stream NodeId, paired with the
     * channel the host pushes into. The host owns closure of these channels
     * (close after the last event so the consuming actors halt naturally).
     *
     * Q-046: `source`-bound external streams do NOT appear here — their
     * sole producer is the runtime's [ExternalStreamFeeder], which owns
     * channel closure when the IO source reaches EOF or fails. See
     * [MachineGroup.hostFeedableExternalStreams].
     */
    val externalInputs: Map<NodeId, SendChannel<Value>>,
    /** Each `output` stream NodeId, paired with the channel the host drains. */
    val externalOutputs: Map<NodeId, ReceiveChannel<Value>>,
    /**
     * Snapshot of every instance the group started with. Use [allInstances]
     * for a view that includes any instances spawned dynamically after
     * group startup (Layer 6 step 3 slice 3.2 supervision).
     */
    val instances: Map<InstanceId, MachineInstanceHandle>,
    private val jobs: List<Job>,
    /**
     * Per-stream [OverflowDispatcher] map (Layer 6 step 3 slice 3.4 metrics
     * support). Used by [metrics] to surface per-stream `overflowDrops`
     * counters. May be empty for legacy test fixtures that build a handle
     * without going through `StateMachineRuntime.runGroup`.
     */
    private val streamDispatchers: Map<NodeId, OverflowDispatcher> = emptyMap(),
    /**
     * Per-stream producer-facing channel map for the slice 3.4 metrics
     * `closed` field. The bus's `producerChannel` is the same `Channel<Value>`
     * the dispatcher wraps; we read its closed-for-send state to surface
     * stream halt. May be empty in legacy fixtures.
     */
    private val streamProducerChannels: Map<NodeId, Channel<Value>> = emptyMap(),
    /**
     * Per-machine NodeId → Hash forward map, threaded from
     * [MachineGroup.nodeIdToHash]. Used by [snapshot] to record the
     * snapshotted instance's machine hash for replay-integrity verification.
     * Empty when the host didn't supply it; in that case [snapshot] throws
     * a clear error instead of recording a placeholder.
     */
    private val nodeIdToHash: Map<NodeId, Hash> = emptyMap(),
    /**
     * Per-group [RuntimeContext] (Layer 6 step 3 slice 3.2 supervision)
     * holding the mutable instance/job maps and the foreign-call dispatcher
     * that intercepts `strand-runtime:StateMachine.Spawn` / `.Terminate`
     * calls. Null for legacy test fixtures that build a handle outside
     * `StateMachineRuntime.runGroup`; host-driven [spawn] and [terminate]
     * APIs throw `IllegalStateException` in that case.
     */
    private val context: RuntimeContext? = null,
) {
    /**
     * Suspend until every actor coroutine in the group completes. An actor
     * completes when its input channels all close (no upstream producer
     * left) or it is cancelled.
     *
     * Dynamic-spawn aware: if new instances were spawned (Layer 6 step 3
     * slice 3.2) after the initial set, the await loop picks them up by
     * polling the runtime context's job set each pass — completes only when
     * a full pass finds no new jobs added.
     */
    suspend fun await() {
        if (context == null) {
            jobs.joinAll()
            return
        }
        while (true) {
            val snapshot = context.actorJobs.toList()
            snapshot.joinAll()
            // Did any new jobs appear during the join? If so, loop and join
            // those too. Steady state is "every pass sees the same set of
            // jobs and they're all complete."
            val after = context.actorJobs.toList()
            if (after.size == snapshot.size) break
        }
    }

    /**
     * All instances in the group at the moment of call, including any
     * spawned dynamically via [spawn] (Layer 6 step 3 slice 3.2). [instances]
     * is the static initial set; this is the live view.
     */
    val allInstances: Map<InstanceId, MachineInstanceHandle>
        get() = context?.instances?.mapValues { (_, instance) ->
            MachineInstanceHandle(instance)
        } ?: instances

    /**
     * Spawn a new [MachineInstance] for the supplied StateMachine
     * [machineId] (Layer 6 step 3 slice 3.2 supervision — host-driven API).
     * Returns the new InstanceId. The spawned actor begins running
     * immediately on the coroutine scope used by `runGroup`.
     *
     * Throws `IllegalStateException` when this handle was constructed
     * outside `StateMachineRuntime.runGroup` (no [context] threaded);
     * legacy test fixtures don't get supervision support.
     */
    fun spawn(machineId: NodeId): InstanceId {
        val ctx = context
            ?: error("handle has no RuntimeContext; spawn is only supported on handles produced by StateMachineRuntime.runGroup")
        return ctx.spawn(machineId)
    }

    /**
     * Cancel the actor coroutine for [instance] (Layer 6 step 3 slice 3.2
     * supervision — host-driven API). Returns true if the instance existed
     * and was cancelled, false if the id was unknown.
     */
    fun terminate(instance: InstanceId): Boolean {
        val ctx = context
            ?: error("handle has no RuntimeContext; terminate is only supported on handles produced by StateMachineRuntime.runGroup")
        return ctx.terminate(instance)
    }

    /**
     * Cancel every actor coroutine and close every output channel. Returns
     * once all jobs have observed the cancellation. Cancellation is
     * cooperative — a transition currently in progress runs to completion
     * before the actor sees cancellation, but no new transition starts after.
     */
    suspend fun cancel() {
        for (job in jobs) job.cancel()
        for (job in jobs) job.join()
    }

    /**
     * The input events [instance] consumed in arrival order. Returns null
     * if the group disabled `recordInputs`. Feeding this list to
     * [StateMachineRuntime.runMachine] against the same StateMachine NodeId
     * reproduces the per-step state transitions the actor observed —
     * replay-determinism property.
     */
    fun recordedEvents(instance: InstanceId): List<Value>? =
        instances[instance]?.recordedEvents()

    /**
     * Snapshot every per-instance and per-stream counter (Layer 6 step 3
     * slice 3.4). Returns a fresh [RuntimeMetrics] each call — the host
     * scrapes at its own cadence and translates the snapshot into its
     * external metrics infrastructure (Prometheus exporter, OpenTelemetry
     * exporter, structured-log emitter, etc.).
     *
     * Snapshot semantics: each counter cell is atomic, so individual values
     * are coherent; the cross-counter view is best-effort. See the
     * [RuntimeMetrics] doc comment for the deferred metrics (currentStateHash,
     * queueDepth, oldestPendingEventAgeNanos).
     */
    /**
     * Capture a [Snapshot] of a running instance (Layer 6 step 3 slice 3.3).
     *
     * Reads `currentState`, `transitionsExecuted`, and (when recorder is
     * enabled) the recorded event list directly from the actor. The read is
     * a best-effort consistent point: transitions are atomic from the
     * actor's perspective (it updates `currentState` after `applyCallable`
     * returns), so a snapshot taken between transitions sees a coherent
     * post-transition state. A snapshot taken concurrently with a transition
     * may see either the pre- or post-transition value — both are valid
     * snapshot points, just at slightly different times.
     *
     * For tight mid-transition consistency, the proposal §6.3 describes a
     * control-mailbox protocol; that's a hardening follow-up. For the slice
     * 3.3 MVP, this best-effort read is the contract.
     *
     * Throws [SnapshotUnknownInstance] when [instance] is not part of this
     * group. Throws `IllegalStateException` when the group was constructed
     * without [MachineGroup.nodeIdToHash] (snapshots require it for the
     * machine-hash integrity check).
     */
    fun snapshot(instance: InstanceId): Snapshot {
        val handle = instances[instance] ?: throw SnapshotUnknownInstance(instance)
        return handle.captureSnapshot(nodeIdToHash)
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun metrics(): RuntimeMetrics {
        val perInstance = instances.mapValues { (_, handle) ->
            handle.instanceMetricsSnapshot()
        }
        val perStream = streamDispatchers.entries.associate { (streamId, dispatcher) ->
            val producerChannel = streamProducerChannels[streamId]
            streamId to StreamMetrics(
                overflowDrops = dispatcher.droppedCount,
                // isClosedForSend is marked DelicateCoroutinesApi because
                // racing it with a send is unreliable; here we use it only
                // for an informational metrics snapshot, where a stale-by-one
                // read is acceptable per the doc on [RuntimeMetrics].
                closed = producerChannel?.isClosedForSend ?: false,
            )
        }
        return RuntimeMetrics(perInstance = perInstance, perStream = perStream)
    }
}

/**
 * Per-instance inspection surface exposed by [MachineGroupHandle.instances].
 * Wraps the internal [MachineInstance] so tests and host code can observe
 * the instance's final state, halted-status, and recorded events without
 * the host owning the mutable [MachineInstance] type directly.
 */
class MachineInstanceHandle internal constructor(
    private val instance: MachineInstance,
) {
    val instanceId: InstanceId get() = instance.instanceId
    val machineId: NodeId get() = instance.node.transitionFn  // for diagnostics; not the StateMachine id itself
    val currentState: Value get() = instance.currentState
    val halted: Boolean get() = instance.halted

    /**
     * Q-064: the structured denial report when this instance halted on a
     * capability or refinement denial during a transition (instance id,
     * zero-based event index, and phase `transition` attached at halt
     * translation); null for every other halt cause. The async analogue
     * of the sync fold's [HaltReason.CapabilityDenial].
     */
    val denialReport: org.strand.interpreter.DenialReport? get() = instance.denialHalt

    fun recordedEvents(): List<Value>? = instance.recorder?.snapshot()

    /**
     * Internal accessor for [MachineGroupHandle.metrics]. Reads the actor's
     * counter cells plus the current state and halt flag. The returned
     * [InstanceMetrics] is an immutable snapshot at the moment of the call.
     */
    internal fun instanceMetricsSnapshot(): InstanceMetrics =
        instance.counters.snapshot(halted = instance.halted, currentState = instance.currentState)

    /**
     * Internal accessor for [MachineGroupHandle.snapshot]. Reads the actor's
     * `currentState`, transitions count, and (when enabled) recorded events.
     * Requires the host-supplied [nodeIdToHash] map to record the machine
     * hash; throws `IllegalStateException` if absent (snapshots without
     * machine-hash integrity are unsafe to resume).
     */
    internal fun captureSnapshot(nodeIdToHash: Map<NodeId, Hash>): Snapshot {
        // The MachineInstance stores its source StateMachine NodeId only
        // indirectly (via the node it references); look up via the input
        // streams' container. Simplest: store machineId at instance build
        // time. For slice 3.3 MVP we cheat: every MachineInstance is built
        // from exactly one StateMachine node, and the runtime puts the
        // StateMachine NodeId on the instance via [MachineInstance.machineNodeId]
        // (added in this slice).
        val machineId = instance.machineNodeId
            ?: error(
                "Snapshot requires the source machine NodeId on MachineInstance; " +
                    "fixture constructed an instance without one. Use " +
                    "StateMachineRuntime.runGroup or runMachine to get a snapshot-able instance."
            )
        val machineHash = nodeIdToHash[machineId]
            ?: error(
                "Snapshot requires MachineGroup.nodeIdToHash to be populated for " +
                    "machine $machineId; the group was constructed without it. " +
                    "Pass finalized.nodeIdToHash when constructing the MachineGroup."
            )
        return Snapshot(
            machineHash = machineHash,
            snapshotState = instance.currentState,
            processedEventCount = instance.counters.snapshot(
                halted = instance.halted,
                currentState = instance.currentState,
            ).transitionsExecuted,
            recordedEventsPreSnapshot = instance.recorder?.snapshot(),
        )
    }
}
