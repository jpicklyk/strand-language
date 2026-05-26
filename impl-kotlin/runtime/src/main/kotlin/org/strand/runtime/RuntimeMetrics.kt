package org.strand.runtime

import org.strand.core.NodeId
import org.strand.interpreter.Value
import java.util.concurrent.atomic.AtomicLong

/**
 * Observable runtime metrics for a [MachineGroup] (Layer 6 step 3 slice 3.4).
 *
 * A `RuntimeMetrics` is an immutable snapshot of counter values at one point
 * in time. Callers obtain a fresh snapshot via [MachineGroupHandle.metrics];
 * the host scrapes at its own cadence (per-second polling for a Prometheus
 * exporter, on-demand for a debugger, etc.). No export protocol is embedded
 * in the runtime — the host translates the snapshot into its own metrics
 * infrastructure.
 *
 * Per-instance counters cover the actor lifecycle (events processed,
 * transitions executed, last-transition latency); per-stream counters cover
 * overflow events visible at producer-side. Each snapshot is consistent
 * within each counter cell (`AtomicLong.get` is atomic) but the cross-
 * counter view is best-effort — a metric for a producer's drop count and
 * the consumer's receive count may disagree by a small delta if read across
 * the boundary. This is fine for monitoring; precise per-event correlation
 * needs the trace API, not the metrics API.
 *
 * Three metrics specified in `proposals/state-machines-runtime-step-3.md`
 * §4.6 are deliberately deferred from this slice:
 *
 *  * **currentStateHash** — would require a canonical encoder over runtime
 *    [Value]s. The existing [org.strand.hashing.CanonicalEncoder] operates
 *    on [org.strand.core.Node], not on post-evaluation values. Operators
 *    can inspect [InstanceMetrics.currentState] directly for now.
 *  * **queueDepth** — would require instrumenting both the dispatcher-wrapped
 *    send path and the actor's `select`-based receive path with paired
 *    counters, and would surface as an approximation under Broadcast streams
 *    where each consumer has its own queue. The [StreamMetrics.overflowDrops]
 *    counter already conveys the most actionable backpressure signal.
 *  * **oldestPendingEventAgeNanos** — would require tagging every channel
 *    payload with a send timestamp. Defer until a workload demands it.
 */
data class RuntimeMetrics(
    /** Per-instance counters. The key is the runtime [InstanceId]. */
    val perInstance: Map<InstanceId, InstanceMetrics>,
    /** Per-stream counters. The key is the EventStream [NodeId]. */
    val perStream: Map<NodeId, StreamMetrics>,
)

/**
 * Snapshot of one [MachineInstance]'s counters at metrics-collection time.
 *
 * Counter semantics:
 *
 *  * [eventsReceived] — number of payloads the actor has dequeued from its
 *    input channels (including events that were wrapped into the tagged
 *    InputEvent sum for multi-stream machines — one tick per `select` pick).
 *  * [transitionsExecuted] — number of completed transition-function calls.
 *    Equals [eventsReceived] in steady state; lags by 1 when a transition is
 *    in progress at snapshot time; equals [eventsReceived] - 1 if the actor
 *    halted mid-transition (slice 3.4 does not surface this case yet).
 *  * [lastTransitionLatencyNanos] — wall-clock nanoseconds spent in the most
 *    recently completed transition. `0L` if no transition has executed yet.
 *  * [halted] — the actor's halt flag; true after the input channels close
 *    and the run loop exits.
 *  * [currentState] — the actor's last observed state value. Snapshot semantics
 *    are best-effort: the field is read without locking, so a metrics snapshot
 *    taken mid-transition may capture either the pre-transition or post-
 *    transition state. The actor mutates `currentState` between transitions,
 *    so the in-flight transition's effect on the state is atomic from the
 *    actor's perspective.
 */
data class InstanceMetrics(
    val eventsReceived: Long,
    val transitionsExecuted: Long,
    val lastTransitionLatencyNanos: Long,
    val halted: Boolean,
    val currentState: Value,
)

/**
 * Snapshot of one EventStream's counters at metrics-collection time.
 *
 * Counter semantics:
 *
 *  * [overflowDrops] — payloads discarded due to the stream's declared
 *    [org.strand.core.OverflowPolicy]: `DropNewest` increments on every
 *    failed send; `DropOldest` increments on every evicted stale value;
 *    `Sample(n)` increments on every event arriving inside the sample
 *    window. `BlockProducer` streams (the default) never increment.
 *  * [closed] — true when the underlying producer channel is closed for
 *    sending (i.e. every producer has halted, or the host has closed the
 *    external-input channel).
 */
data class StreamMetrics(
    val overflowDrops: Long,
    val closed: Boolean,
)

/**
 * Mutable per-instance counters owned by a [MachineInstance]. The actor
 * updates these inline as it processes events; [snapshot] produces an
 * immutable [InstanceMetrics] from the current counter values.
 *
 * All counters are `AtomicLong` so a host scraping metrics on a different
 * thread sees coherent reads. The trade-off — atomic increment cost per
 * event — is negligible compared to the transition-function dispatch cost
 * and matches the proposal's "lock-free counter cell" intent.
 */
internal class InstanceCounters {
    private val eventsReceived = AtomicLong(0)
    private val transitionsExecuted = AtomicLong(0)
    private val lastTransitionLatencyNanos = AtomicLong(0)

    fun recordEventReceived() {
        eventsReceived.incrementAndGet()
    }

    fun recordTransitionCompleted(latencyNanos: Long) {
        transitionsExecuted.incrementAndGet()
        lastTransitionLatencyNanos.set(latencyNanos)
    }

    fun snapshot(halted: Boolean, currentState: Value): InstanceMetrics = InstanceMetrics(
        eventsReceived = eventsReceived.get(),
        transitionsExecuted = transitionsExecuted.get(),
        lastTransitionLatencyNanos = lastTransitionLatencyNanos.get(),
        halted = halted,
        currentState = currentState,
    )
}
