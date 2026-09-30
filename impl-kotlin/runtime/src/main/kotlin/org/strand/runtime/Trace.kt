package org.strand.runtime

import org.strand.core.ExhaustionKind
import org.strand.interpreter.DenialReport
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

    /**
     * Q-064: a per-event transition invocation hit a capability or
     * refinement denial. The denial still terminates this machine's
     * evaluation — the halt is the termination — but the already-decided
     * outcome is exposed structurally to the orchestrating principal on
     * [report], with the machine instance id, the zero-based event index
     * of the denying event, and phase `transition` attached at translation.
     * The denying event does not produce a [TraceStep.Step] (the closure
     * call threw before the step could complete), matching the
     * [ResourceExhaustion] shape.
     *
     * Nothing here is observable from inside the graph: the transition
     * function saw an ordinary uncatchable termination, and no Value
     * carries the report.
     */
    data class CapabilityDenial(
        val report: DenialReport,
    ) : HaltReason()

    /**
     * Q-047 (machine path): a per-event transition invocation produced a
     * value violating the invariant of a Schema it flowed into — the runtime
     * half of a schema obligation the verify-time SchemaChecker could only
     * defer. Raised only when the runtime was given the verify result's
     * schema obligations (`StrandRuntime`'s `VerifyResult.Ok` overloads, or
     * [StateMachineRuntime]'s `schemaObligations`). [error] is the
     * structured violation (node, schema, invariant, offending value);
     * [atEventIndex] is the zero-based index of the event whose transition
     * raised it. The same shape on the sync fold and the async actor: the
     * failing event produces no [TraceStep.Step], and on the async path the
     * halt stops THIS instance only, its siblings keep running.
     *
     * A violation raised while an instance is built (its `initialState`)
     * is not a halt: it propagates as an
     * [org.strand.interpreter.InterpretException], as a denial there does.
     */
    data class SchemaViolation(
        val error: org.strand.interpreter.InterpretError.SchemaInvariantViolation,
        val atEventIndex: Int,
    ) : HaltReason()

    /**
     * Review M6: an async actor's transition (or its output dispatch) failed
     * with an error that is neither a denial nor an exhaustion — an
     * unexpected JVM throwable or a non-denial [org.strand.interpreter.InterpretError].
     * The actor is its own supervision boundary: the failure halts THIS
     * instance only, siblings and the caller's scope keep running, and the
     * group surfaces it through [MachineGroupHandle.failedInstances].
     *
     * [throwableClass] is the fully qualified class of the throwable;
     * [interpretError] is the structured error when the throwable was an
     * [org.strand.interpreter.InterpretException]; [atEventIndex] is the
     * zero-based index of the event whose processing failed. The sync fold
     * ([StateMachineRuntime.runMachine]) runs on the caller's thread and
     * rethrows instead.
     */
    data class InstanceFailure(
        val throwableClass: String,
        val message: String?,
        val atEventIndex: Int,
        val interpretError: org.strand.interpreter.InterpretError? = null,
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
