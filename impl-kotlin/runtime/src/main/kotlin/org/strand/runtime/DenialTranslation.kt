package org.strand.runtime

import org.strand.interpreter.DenialPhase
import org.strand.interpreter.DenialReport
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException

/**
 * Q-064 runtime-side denial translation helpers. The interpreter constructs
 * the [DenialReport] at the denial site with phase `expression` (or
 * `invariant`) and no instance attribution; the runtime is the layer that
 * knows which machine instance and which event was in flight, so it attaches
 * those fields when translating a denial into a halt ([HaltReason.
 * CapabilityDenial] on the sync fold, a clean actor halt on the async path)
 * or re-tags a group-startup denial with phase `group-start` before
 * rethrowing.
 */

/** The denial report carried by [error], or null when [error] is not a capability/refinement denial. */
internal fun denialReportOf(error: InterpretError): DenialReport? = when (error) {
    is InterpretError.CapabilityViolation -> error.report
    is InterpretError.RefinementViolation -> error.report
    else -> null
}

/**
 * Attach the machine [instanceId], zero-based [eventIndex], and phase
 * `transition` to a denial report at halt translation. The denial decision
 * and every other report field are unchanged.
 */
internal fun DenialReport.atTransition(instanceId: InstanceId, eventIndex: Int): DenialReport =
    copy(
        instanceId = instanceId.value,
        eventIndex = eventIndex,
        phase = DenialPhase.Transition,
    )

/**
 * Run [block]; if it throws an [InterpretException] carrying a capability or
 * refinement denial, rethrow it with the report re-tagged as phase
 * `group-start` (the denial fired during `runGroup` startup — initial
 * instance spawning or a Q-046 source-opener evaluation — before any actor
 * processed an event). Non-denial exceptions pass through untouched.
 */
internal fun <T> attachingGroupStartPhase(block: () -> T): T =
    try {
        block()
    } catch (e: InterpretException) {
        throw when (val err = e.error) {
            is InterpretError.CapabilityViolation ->
                InterpretException(err.copy(report = err.report.copy(phase = DenialPhase.GroupStart)))
            is InterpretError.RefinementViolation ->
                InterpretException(err.copy(report = err.report.copy(phase = DenialPhase.GroupStart)))
            else -> e
        }
    }
