package org.strand.interpreter

import org.strand.verifier.BuiltinDeterminismOracle

/**
 * Q-065: the [Builtins]-backed [BuiltinDeterminismOracle], published to
 * the verifier through `META-INF/services` (see `ReplayDeterminism` in
 * the verifier module for the resolution contract). Consults
 * [Builtins.determinismOf], which covers both shipping registries plus
 * the test overlay — so a test-injected effect-free builtin force-marked
 * Nondeterministic is visible to the verifier's
 * `NondeterministicInReplayContext` sweep through the same path a real
 * registry entry would be.
 */
class BuiltinsDeterminismOracle : BuiltinDeterminismOracle {
    override fun isDeterministic(target: String): Boolean? =
        Builtins.determinismOf(target)?.let { it == Builtins.Determinism.Deterministic }
}
