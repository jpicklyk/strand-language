package org.strand.runtime

import kotlinx.coroutines.channels.ReceiveChannel
import org.strand.core.NodeId
import org.strand.interpreter.DenialReport
import org.strand.interpreter.Value

/**
 * Q-059: a service-mode (non-batch) driver for a long-running [MachineGroup].
 *
 * The batch path (`strand group`, the facade's `runGroup` usage in the CLI)
 * sends a routed-events file, immediately closes every host-feedable external
 * input, and drains until the actors see channel EOF and halt. That is correct
 * for a finite task but terminates anything server-shaped. `GroupService`
 * wraps the same live [MachineGroupHandle] without the close-and-drain: it
 * keeps external inputs open, accepts events over time via [send], lets the
 * host drain outputs via [outputs], and runs until the host calls [stop] (which
 * closes inputs and awaits natural quiescence) or a real halt occurs (an
 * actor's capability denial, resource exhaustion, or supervised termination —
 * the existing halt surfaces, unchanged).
 *
 * Construct via [StrandRuntime.serveGroup]. The facade scopes the policy
 * install/restore around the whole service lifetime (the actors evaluate
 * asynchronously), exactly as the batch CLI does via `withGroupInstalled`.
 *
 * **Harm bound (Q-044).** Service mode changes nothing about containment: each
 * event's processing is bounded by the policy's per-event budget (when set;
 * see [org.strand.core.EvaluationLimits.perEventStepBudget]), every effect is
 * still gated by the group's capability context, and the overflow / supervision
 * semantics are the same as the batch path. What service mode removes is only
 * the artificial whole-run wall-clock that killed a long-lived group for being
 * alive.
 *
 * **Stream addressing.** Inputs and outputs are addressed by the host-facing
 * name the caller supplies in [inputStreamIds] / [outputStreamIds] (the
 * `strand group` CLI uses the ingest author-id name map). [send] to an unknown
 * or non-external-input name raises [IllegalArgumentException]; [outputs] for
 * an unknown name does likewise.
 */
class GroupService internal constructor(
    private val handle: MachineGroupHandle,
    /** Host-facing input-stream name → NodeId for the group's host-feedable external inputs. */
    private val inputStreamIds: Map<String, NodeId>,
    /** Host-facing output-stream name → NodeId for the group's external outputs. */
    private val outputStreamIds: Map<String, NodeId>,
) {
    /**
     * Push [value] onto the live external input stream named [streamName]. The
     * stream stays open — this is the long-running counterpart to the batch
     * path's "send then close." Suspends if the input channel's buffer is full
     * (BlockProducer backpressure).
     */
    suspend fun send(streamName: String, value: Value) {
        val streamId = inputStreamIds[streamName]
            ?: throw IllegalArgumentException(
                "no host-feedable external input stream named '$streamName'; " +
                    "known inputs: ${inputStreamIds.keys.sorted()}"
            )
        val channel = handle.externalInputs[streamId]
            ?: throw IllegalArgumentException(
                "stream '$streamName' resolved to $streamId but is not a host-feedable external input " +
                    "(it may be source-bound — Q-046 — and fed by the runtime's feeder)"
            )
        channel.send(value)
    }

    /**
     * The live receive side of the external output stream named [streamName].
     * The host drains it over the service's lifetime; emissions arrive as the
     * actors produce them. The channel closes when the producing actors halt.
     */
    fun outputs(streamName: String): ReceiveChannel<Value> {
        val streamId = outputStreamIds[streamName]
            ?: throw IllegalArgumentException(
                "no external output stream named '$streamName'; " +
                    "known outputs: ${outputStreamIds.keys.sorted()}"
            )
        return handle.externalOutputs[streamId]
            ?: throw IllegalArgumentException("stream '$streamName' resolved to $streamId but is not an external output")
    }

    /** Live view of every instance in the group, including any spawned dynamically by a supervisor. */
    val instances: Map<InstanceId, MachineInstanceHandle> get() = handle.allInstances

    /** A fresh runtime-metrics snapshot (per-instance counters + per-stream gauges). */
    fun metrics(): RuntimeMetrics = handle.metrics()

    /** Capture a [Snapshot] of a running instance for persistence (see [StrandRuntime.writeSnapshot]). */
    fun snapshot(instance: InstanceId): Snapshot = handle.snapshot(instance)

    /**
     * The structured denial reports of any instance that halted on a capability
     * or refinement denial, in deterministic instance-id order. Empty when no
     * actor has denied. The host polls this (or [awaitHalt]) to detect a
     * denial-caused halt — service mode does not auto-exit on denial the way the
     * batch CLI does; the host decides.
     */
    fun denials(): List<DenialReport> =
        handle.allInstances.values.mapNotNull { it.denialReport }.sortedBy { it.instanceId }

    /**
     * Graceful stop: close every host-feedable external input so the consuming
     * actors see EOF and halt naturally, then suspend until every actor (and any
     * dynamically-spawned instance) completes. The batch shape, invoked on
     * demand rather than immediately after the first event burst.
     */
    suspend fun stop() {
        for (channel in handle.externalInputs.values) channel.close()
        handle.await()
    }

    /** Forceful stop: cancel every actor coroutine without draining. */
    suspend fun stopNow() {
        handle.cancel()
    }

    /**
     * Suspend until the group reaches a real halt — every actor completed
     * (inputs closed upstream or all actors halted on a real reason). Unlike
     * [stop], this does NOT close the inputs first: it waits for a halt the
     * group reaches on its own (a denial, resource exhaustion, a supervised
     * teardown, or an upstream closing the inputs). A service with inputs still
     * open and all actors idle-blocked in `select` is quiescent but not halted,
     * so this does not return for mere idleness — that distinction is left to
     * the host (derivable from [metrics] deltas).
     */
    suspend fun awaitHalt() {
        handle.await()
    }
}
