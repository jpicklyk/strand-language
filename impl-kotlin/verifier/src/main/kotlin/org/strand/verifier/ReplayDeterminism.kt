package org.strand.verifier

import java.util.ServiceLoader

/**
 * Q-065: the verifier's view of the builtin registry's behavioral
 * determinism metadata.
 *
 * The metadata authority is the interpreter's `Builtins` registry, but
 * the module dependency direction is `interpreter -> verifier -> core`,
 * so the verifier cannot reference it directly. The oracle is therefore
 * resolved through [ServiceLoader]: the interpreter module publishes a
 * `Builtins`-backed implementation under
 * `META-INF/services/org.strand.verifier.BuiltinDeterminismOracle`, and
 * any classpath that carries the interpreter (the CLI, the corpus and
 * runtime test suites, every host that can actually execute a program)
 * resolves it automatically. On a verifier-only classpath no provider is
 * found and [ReplayDeterminism.isDeterministic] answers null
 * ("unknown") for every target — the replay-determinism warning then
 * never fires, which is the correct conservative reading: with no
 * registry present there is no registry claim to check.
 */
fun interface BuiltinDeterminismOracle {
    /**
     * Whether the registry entry for [target] is marked Deterministic.
     * Returns null when the target is not a registered builtin (for
     * example a `wasm:` or `process:` binding) — non-registry foreign
     * bindings carry no determinism claim until the Q-006 trust-model
     * work adjudicates binding-author claims.
     */
    fun isDeterministic(target: String): Boolean?
}

object ReplayDeterminism {

    /**
     * Test seam: when non-null, consulted instead of the
     * [ServiceLoader]-resolved provider. Verifier-module tests (which
     * deliberately have no interpreter on the classpath) install a fake
     * oracle here; tests must reset to null in teardown and must not run
     * in parallel with other oracle-reading tests.
     */
    @Volatile
    var override: BuiltinDeterminismOracle? = null

    private val loaded: BuiltinDeterminismOracle? by lazy {
        ServiceLoader.load(BuiltinDeterminismOracle::class.java).firstOrNull()
    }

    /** Resolve [target] through the override (tests) or the loaded provider. */
    fun isDeterministic(target: String): Boolean? =
        (override ?: loaded)?.isDeterministic(target)
}
