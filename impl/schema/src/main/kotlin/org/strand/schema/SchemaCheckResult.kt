package org.strand.schema

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
 * A check is *clean* iff both lists are empty.
 */
data class SchemaCheckResult(
    val violations: List<VerifyError.SchemaInvariantViolation>,
    val deferred: List<VerifyError.SchemaInvariantDeferred>,
) {
    val isClean: Boolean get() = violations.isEmpty() && deferred.isEmpty()
    val hasViolations: Boolean get() = violations.isNotEmpty()
}
