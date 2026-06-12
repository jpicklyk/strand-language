package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.authoring.BuiltinSignatures
import org.strand.authoring.LayerAGrammar
import org.strand.interpreter.Builtins

/**
 * Q-060 M-4 slice a — the registry sweep.
 *
 * Every builtin target registered in `interpreter/Builtins.kt` must be
 * reachable from Layer A by bare name through exactly one of:
 *
 *  1. the implicit prelude ([LayerAGrammar.reservedNodes] — monomorphic
 *     builtins with a reserved short name),
 *  2. the implicit signature table ([BuiltinSignatures.table] —
 *     polymorphic / Option-returning / structured-shape builtins called
 *     by their dotted canonical name), or
 *  3. the documented exclusion list ([BuiltinSignatures.exclusions] —
 *     entries whose signature genuinely cannot be expressed, with the
 *     reason recorded).
 *
 * A registry addition that lands in none of the three fails here — the
 * structural fix for the 2026-05-26 "round-2 builtins shipped without
 * prelude entries" defect class, extended to the non-monomorphic
 * surface.
 */
class BuiltinRegistrySweepTest {

    private val registryTargets: Set<String> = Builtins.registeredTargets()

    private val preludeTargets: Set<String> = LayerAGrammar.reservedNodes.values
        .filter { it.jsonType == "ForeignNode" }
        .mapNotNull { it.stringFields["target"] }
        .toSet()

    private val tableTargets: Set<String> = BuiltinSignatures.table.keys
        .map { BuiltinSignatures.targetFor(it) }
        .toSet()

    private val excludedTargets: Set<String> = BuiltinSignatures.exclusions.keys
        .map { BuiltinSignatures.targetFor(it) }
        .toSet()

    @Test
    fun `every registry builtin is prelude-covered, table-covered, or excluded`() {
        val uncovered = registryTargets - preludeTargets - tableTargets - excludedTargets
        assertTrue(uncovered.isEmpty()) {
            "registry builtins reachable by no Layer A surface (add a prelude entry, a " +
                "signature-table entry, or a documented exclusion): ${uncovered.sorted()}"
        }
    }

    @Test
    fun `the signature table names only real registry targets`() {
        val stale = tableTargets - registryTargets
        assertTrue(stale.isEmpty()) {
            "signature-table entries with no registry implementation: ${stale.sorted()}"
        }
    }

    @Test
    fun `the exclusion list names only real registry targets`() {
        val stale = excludedTargets - registryTargets
        assertTrue(stale.isEmpty()) {
            "exclusion-list entries with no registry implementation: ${stale.sorted()}"
        }
    }

    @Test
    fun `table, prelude, and exclusions are pairwise disjoint`() {
        val tablePrelude = tableTargets intersect preludeTargets
        assertTrue(tablePrelude.isEmpty()) {
            "targets both table-covered and prelude-covered (pick one surface): " +
                "${tablePrelude.sorted()}"
        }
        val tableExcluded = tableTargets intersect excludedTargets
        assertTrue(tableExcluded.isEmpty()) {
            "targets both table-covered and excluded: ${tableExcluded.sorted()}"
        }
        val preludeExcluded = preludeTargets intersect excludedTargets
        assertTrue(preludeExcluded.isEmpty()) {
            "targets both prelude-covered and excluded: ${preludeExcluded.sorted()}"
        }
    }

    @Test
    fun `every projected table effect references a reserved category or synthesizable one`() {
        for ((name, sig) in BuiltinSignatures.table) {
            for (effect in sig.effects) {
                val reserved = effect.reservedId
                if (reserved != null) {
                    val spec = LayerAGrammar.reservedNodes[reserved]
                    assertTrue(spec != null && spec.jsonType == "EffectCategory") {
                        "$name references reserved effect '$reserved' which is not a " +
                            "reserved EffectCategory"
                    }
                    // Projection arity must match the category's declared
                    // parameter count (the Q-039 admission rule).
                    val sources = effect.sources
                    if (sources != null) {
                        val arity = spec!!.refListFields["parameters"]?.size ?: 0
                        assertTrue(sources.size == arity) {
                            "$name projects ${sources.size} source(s) onto '$reserved' " +
                                "whose category declares $arity parameter(s)"
                        }
                    }
                } else {
                    // Synthesized categories are parameterless by
                    // construction; a projection onto one must be empty.
                    val sources = effect.sources
                    assertTrue(sources == null || sources.isEmpty()) {
                        "$name projects onto synthesized category '${effect.categoryName}' " +
                            "which is parameterless"
                    }
                }
            }
        }
    }
}
