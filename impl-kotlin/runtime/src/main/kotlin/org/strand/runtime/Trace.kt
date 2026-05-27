package org.strand.runtime

import org.strand.core.ExhaustionKind
import org.strand.interpreter.Value

/**
 * One step in a state machine's lifetime trajectory: the event that drove it,
 * the state before and after the transition, and the list of values emitted on
 * the machine's output streams during this step (in declaration order, with
 * suppressed None outputs excluded — only the values actually emitted appear).
 *
 * [TraceStep] is the per-event observation; [Trace] aggregates them and
 * appends a terminating [Halt] that records why the run stopped.
 */
sealed class TraceStep {

    /**
     * A successful transition. [event] is the input value; [before] and
     * [after] are the state values bracketing the transition; [outputs] is
     * the (possibly empty) list of values the transition function emitted
     * on the machine's output streams this step.
     */
    data class Step(
        val event: Value,
        val before: Value,
        val after: Value,
        val outputs: List<Value>,
    ) : TraceStep()

    /**
     * The machine halted. [finalState] is the state value at the moment of
     * halt; [reason] is the structured cause. Layer 6 step 1 produces only
     * [HaltReason.EventsExhausted].
     */
    data class Halt(
        val finalState: Value,
        val reason: HaltReason,
    ) : TraceStep()
}

/**
 * Why a [Trace] terminated. Layer 6 step 1 emits [EventsExhausted] for the
 * normal case; Q-040 added [ResourceExhaustion] when a per-event closure
 * invocation breached the host's [org.strand.core.EvaluationLimits].
 */
sealed class HaltReason {
    /** The supplied event list was consumed in full; no events remain. */
    object EventsExhausted : HaltReason() {
        override fun toString(): String = "EventsExhausted"
    }

    /**
     * Q-040: a per-event closure invocation exhausted a host resource
     * limit. [kind] discriminates the dimension; [atEventIndex] is the
     * zero-based index in the events list where the breach was raised
     * — the event whose closure invocation tripped the cap.
     *
     * The trace's `steps` list contains all successfully-processed
     * events strictly before this index; the failing event itself
     * does not produce a `TraceStep.Step` because the closure call
     * threw before the step could complete.
     */
    data class ResourceExhaustion(
        val kind: ExhaustionKind,
        val atEventIndex: Int,
    ) : HaltReason()
}

/**
 * The full lifetime of a state machine instance over a fixed event sequence:
 * the per-event [steps] in order, followed by a single [final] halt record.
 *
 * The Trace is deterministic by construction for pure transition functions:
 * the same `(machine, events, capabilities)` triple always produces the same
 * Trace. This is the property `proposals/state-machines-runtime.md` § 6
 * pins as the basis for replay debugging and training-corpus generation.
 */
data class Trace(
    val steps: List<TraceStep.Step>,
    val final: TraceStep.Halt,
)
