package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.BuiltinEffectTable
import org.strand.interpreter.Builtins
import org.strand.verifier.BuiltinSignatures

/**
 * The core [BuiltinEffectTable] floor (used by the verifier on a classpath
 * without the Q-056 oracle, and by the interpreter at dispatch when the
 * oracle is not resolvable) must agree with the Q-056
 * `BuiltinsSignatureOracle` (service-loaded from `:authoring`, on this
 * classpath) for every `strand-builtin:` target. Agreement means: every
 * table row for a target the oracle knows is contained in the oracle's
 * effect set (rows for targets the oracle does not model are the sole
 * floor there, applied by the verifier's table fallback), and every registered builtin the oracle calls
 * effectful (outside the exempt `Test.` namespace) carries a table row. The
 * table may record a floor narrower than the oracle's declared set (for
 * example a handle-opening builtin whose prelude declaration covers both
 * directions of the returned handle); the verifier with an oracle checks
 * the exact set, so a narrower floor never admits more than the oracle.
 */
class BuiltinEffectTableOracleConsistencyTest {

    @Test
    fun `the oracle is resolvable on the corpus classpath`() {
        assertTrue(BuiltinSignatures.oracleAvailable())
    }

    @Test
    fun `every table row is contained in the oracle's effect set`() {
        val disagreements = BuiltinEffectTable.table
            .filterKeys { it.startsWith("strand-builtin:") }
            .mapNotNull { (target, floor) ->
                val oracle = BuiltinSignatures.effectNamesFor(target)
                // A row the oracle does not know (legacy Q-031 stubs, the
                // streaming LLM opens) is the only floor for that target and
                // is checked by the verifier's table fallback.
                if (oracle != null && !oracle.containsAll(floor)) "$target: table $floor not within oracle $oracle" else null
            }
        assertTrue(disagreements.isEmpty()) { "table/oracle disagreements:\n" + disagreements.joinToString("\n") }
    }

    @Test
    fun `every effectful registered builtin the oracle knows carries a table row`() {
        val missing = Builtins.registeredTargets()
            .filter { !BuiltinEffectTable.isExempt(it) }
            .filter { BuiltinSignatures.effectNamesFor(it)?.isNotEmpty() == true }
            .filter { BuiltinEffectTable.requiredCategories(it) == null }
        assertTrue(missing.isEmpty()) { "oracle-effectful builtins with no table row: $missing" }
    }
}
