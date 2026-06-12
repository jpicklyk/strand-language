package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.authoring.BuiltinSignatures
import org.strand.authoring.LayerAGrammar
import org.strand.interpreter.Builtins

/**
 * Q-065 registry-consistency sweep.
 *
 * The registration constraint (see `Builtins.resolveRegistration`)
 * guarantees every entry has taken an explicit determinism position;
 * this sweep pins the SHIPPING values:
 *
 *  1. every effect-declaring entry is Stateful or Nondeterministic;
 *  2. every effect-free entry is Deterministic;
 *  3. the `Random.*` family is Nondeterministic;
 *  4. each entry's registry-side `effectful` flag agrees with the
 *     authoritative graph-side effect declarations — the implicit
 *     prelude ([LayerAGrammar.reservedNodes]) for prelude-covered
 *     targets, the signature table ([BuiltinSignatures.table]) for
 *     table-covered targets, and a documented expectation for the
 *     exclusion-list entries — so the metadata cannot drift from the
 *     surface agents actually declare effects through.
 */
class DeterminismRegistryConsistencyTest {

    private val metadata = Builtins.entryMetadata()

    @Test
    fun `every effect-declaring entry is Stateful or Nondeterministic`() {
        val offenders = metadata.filter {
            it.effectful && it.determinism == Builtins.Determinism.Deterministic
        }
        assertTrue(offenders.isEmpty()) {
            "effect-declaring entries must be Stateful or Nondeterministic: " +
                offenders.map { it.target }.sorted()
        }
    }

    @Test
    fun `every effect-free entry is Deterministic`() {
        val offenders = metadata.filter {
            !it.effectful && it.determinism != Builtins.Determinism.Deterministic
        }
        assertTrue(offenders.isEmpty()) {
            "effect-free entries must be Deterministic (a stateful or nondeterministic " +
                "builtin must declare its effect category): " +
                offenders.map { "${it.target} -> ${it.determinism}" }.sorted()
        }
    }

    @Test
    fun `the Random family is Nondeterministic`() {
        val randoms = metadata.filter { it.target.startsWith("strand-builtin:Random.") }
        assertTrue(randoms.size == 3) { "expected the three Random.* entries, got: $randoms" }
        val offenders = randoms.filter { it.determinism != Builtins.Determinism.Nondeterministic }
        assertTrue(offenders.isEmpty()) {
            "Random.* must be Nondeterministic: " +
                offenders.map { "${it.target} -> ${it.determinism}" }
        }
    }

    @Test
    fun `registry effectful flags agree with prelude and signature-table effect declarations`() {
        // Prelude authority: a reserved ForeignNode declares effects via
        // its `effects` refList.
        val preludeEffectful: Map<String, Boolean> = LayerAGrammar.reservedNodes.values
            .filter { it.jsonType == "ForeignNode" }
            .mapNotNull { spec ->
                spec.stringFields["target"]?.let { target ->
                    target to !spec.refListFields["effects"].isNullOrEmpty()
                }
            }
            .toMap()

        // Signature-table authority: a table entry declares effects via
        // its `effects` list.
        val tableEffectful: Map<String, Boolean> = BuiltinSignatures.table
            .map { (name, sig) -> BuiltinSignatures.targetFor(name) to sig.effects.isNotEmpty() }
            .toMap()

        // Exclusion-list entries have no Layer A surface; their effect
        // conventions are documented at the registration site. All six
        // are effectful (legacy reference stubs, the test-only effectful
        // no-op, and the three streaming LLM openers declaring E-035).
        val exclusionEffectful: Map<String, Boolean> = BuiltinSignatures.exclusions.keys
            .associate { BuiltinSignatures.targetFor(it) to true }

        val mismatches = metadata.mapNotNull { meta ->
            val expected = preludeEffectful[meta.target]
                ?: tableEffectful[meta.target]
                ?: exclusionEffectful[meta.target]
            when {
                expected == null -> "${meta.target}: no prelude / table / exclusion coverage " +
                    "(BuiltinRegistrySweepTest should also be failing)"
                expected != meta.effectful -> "${meta.target}: registry effectful=" +
                    "${meta.effectful} but the declared surface says effectful=$expected"
                else -> null
            }
        }
        assertTrue(mismatches.isEmpty()) { mismatches.sorted().joinToString("\n") }
    }
}
