package org.strand.runtime

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
 * Why a [Trace] terminated. Layer 6 step 1 only ever emits [EventsExhausted];
 * step 2's supervisor patterns will introduce additional reasons (explicit
 * termination, unrecoverable failure, supervisor-initiated stop).
 */
enum class HaltReason {
    /** The supplied event list was consumed in full; no events remain. */
    EventsExhausted,
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
