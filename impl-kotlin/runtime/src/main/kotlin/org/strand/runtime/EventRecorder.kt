package org.strand.runtime

import org.strand.interpreter.Value

/**
 * Per-instance event log used by Layer 6 step 2's async runtime to capture
 * the exact sequence of input events an actor consumed. The recording is the
 * replay-determinism seam back to step 1: given a recorded `List<Value>`,
 * `StateMachineRuntime.runMachine(machine, recordedEvents)` reproduces the
 * step-by-step state transitions the async run observed, modulo per-step
 * recorded ordering across input streams.
 *
 * The recorder is opt-in at the [MachineGroup] level via its `recordInputs`
 * flag. When recording is disabled, instances carry a `null` recorder and
 * the actor loop's `record(...)` call becomes a no-op. When enabled, the
 * recorder accumulates an unbounded list — production workloads that don't
 * need replay should disable it.
 *
 * Bounded / sliding-window recording is a step-3 refinement once snapshot
 * persistence lands and decides whether the recorder is the snapshot's
 * source-of-truth or a separate mechanism.
 */
class EventRecorder {
    private val events = mutableListOf<Value>()

    /** Append [event] to the recording. Thread-safety is owned by the calling actor. */
    fun record(event: Value) {
        events.add(event)
    }

    /** Snapshot of the recorded events in arrival order. */
    fun snapshot(): List<Value> = events.toList()

    /** Number of recorded events without copying. */
    val size: Int get() = events.size
}
