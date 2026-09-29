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

    /**
     * Append [event] to the recording. The actor appends while hosts copy
     * concurrently (snapshots, `recordedEvents`), so every access is
     * synchronized on the list (review M1).
     */
    fun record(event: Value) {
        synchronized(events) { events.add(event) }
    }

    /** Snapshot of the recorded events in arrival order. */
    fun snapshot(): List<Value> = synchronized(events) { events.toList() }

    /**
     * The first [count] recorded events (fewer if fewer were recorded). The
     * recording is append-only, so a prefix is stable once taken; snapshots
     * use it to pair the recording with an atomically-read transition count.
     */
    fun prefix(count: Int): List<Value> = synchronized(events) { events.take(count) }

    /** Number of recorded events without copying. */
    val size: Int get() = synchronized(events) { events.size }
}
