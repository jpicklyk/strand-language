package org.strand.runtime

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.joinAll
import org.strand.core.NodeId
import org.strand.interpreter.Value

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
    /** Each `external` input stream NodeId, paired with the channel the host pushes into. */
    val externalInputs: Map<NodeId, SendChannel<Value>>,
    /** Each `output` stream NodeId, paired with the channel the host drains. */
    val externalOutputs: Map<NodeId, ReceiveChannel<Value>>,
    /** Every instance spawned by `runGroup`. Indexed by InstanceId for inspection. */
    val instances: Map<InstanceId, MachineInstanceHandle>,
    private val jobs: List<Job>,
) {
    /**
     * Suspend until every actor coroutine in the group completes. An actor
     * completes when its input channels all close (no upstream producer
     * left) or it is cancelled.
     */
    suspend fun await() {
        jobs.joinAll()
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
    fun recordedEvents(): List<Value>? = instance.recorder?.snapshot()
}
