package org.strand.schema

import org.strand.core.NodeId
import org.strand.interpreter.InterpretError
import org.strand.verifier.VerifyError

/**
 * Outcome of a [SchemaChecker.check] pass over a verified graph.
 *
 * [violations] are the [VerifyError.SchemaInvariantViolation]s raised by
 * statically-evaluable values that failed at least one invariant of their
 * declared Schema. The presence of any violation means the graph should
 * not be admitted to the store — the SchemaChecker is the final gate.
 *
 * [deferred] are the informational [VerifyError.SchemaInvariantDeferred]
 * diagnostics raised at SchemaType positions whose value was not
 * statically known (function parameters, function results, anything
 * downstream of a runtime computation). Step 1 surfaces these without
 * failing the check; a deployment may want to reject all graphs with
 * non-empty deferred lists via its own policy layer.
 *
 * [evaluationFailures] (review H3) are invariant bodies that could not be
 * evaluated to a verdict — resource exhaustion, a capability or sandbox
 * denial, a builtin contract violation — each carrying the structured
 * [InterpretError]. A failure fails closed: the same position is also
 * reported in [violations], so every consumer that rejects on violations
 * rejects the value without learning about this list.
 *
 * A check is *clean* iff the violation and deferred lists are empty.
 */
data class SchemaCheckResult(
    val violations: List<VerifyError.SchemaInvariantViolation>,
    val deferred: List<VerifyError.SchemaInvariantDeferred>,
    val evaluationFailures: List<InvariantEvaluationFailure> = emptyList(),
) {
    val isClean: Boolean get() = violations.isEmpty() && deferred.isEmpty()
    val hasViolations: Boolean get() = violations.isNotEmpty()
}

/**
 * Review H3: an invariant body at [invariant] of [schema], applied to the
 * statically-known value at [at], raised [error] instead of producing a
 * verdict (evaluated under the checker's host context and limits).
 */
data class InvariantEvaluationFailure(
    val at: NodeId,
    val schema: NodeId,
    val invariant: NodeId,
    val error: InterpretError,
)
