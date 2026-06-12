package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.authoring.Authoring
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Round-trip property for the Layer A density slices (v1 onward).
 *
 * Each fixture in `corpus/layer-a/density-v<n>/<baseName>.layer-a` uses
 * one or more of the new shorthand forms (implicit prelude, inline
 * literals, auto-VarRef, IF/WHEN sugar, anonymous nodes, ...) and is
 * paired with the original `corpus/<baseName>.json` canonical form. The
 * test asserts:
 *   1. The Layer A density form compiles to dag-json without errors.
 *   2. Its root hash equals the canonical JSON's root hash (so every
 *      reachable node is content-identical — the additive-versioning
 *      property the plan calls out).
 *   3. Both forms verify cleanly with the same inferred root type.
 *
 * Together these guarantee that the density shorthand is a pure
 * compression — the verifier (and every downstream consumer) sees a
 * canonical store byte-identical to the hand-authored equivalent.
 */
class LayerADensityTest {

    /**
     * Each pair is `<sliceFolder>/<baseName>.layer-a` (the density form)
     * + `<baseName>.json` (the canonical reference). The base name must
     * exist as a canonical JSON file under `corpus/`.
     */
    private data class Pair(val sliceFolder: String, val baseName: String)

    private val pairs = listOf(
        // Slice 1 — implicit prelude alone
        Pair("density-v1", "21-fixpoint-factorial-implicit") to "21-fixpoint-factorial",
        // Slices 1+2+3 — implicit prelude + inline literals + auto-VarRef
        Pair("density-v1", "21-fixpoint-factorial-density") to "21-fixpoint-factorial",
        Pair("density-v1", "54-json-value-primitives-density") to "54-json-value-primitives",
        Pair("density-v1", "41-toggle-machine-density") to "41-toggle-machine",
        // Slice 4 (v1.5) — IF sugar on top of v1
        Pair("density-v1.5", "21-fixpoint-factorial-if") to "21-fixpoint-factorial",
        // Slices 5+6+7 (v2) — compact LAM params + anonymous nodes + @last
        Pair("density-v2", "21-fixpoint-factorial-v2") to "21-fixpoint-factorial",
        Pair("density-v2", "54-json-value-primitives-v2") to "54-json-value-primitives",
        Pair("density-v2", "41-toggle-machine-v2") to "41-toggle-machine",
        // Slice 8 (v2.5) — inline ProductFieldValue list
        Pair("density-v2.5", "41-toggle-machine-v2.5") to "41-toggle-machine",
        // Slice 9 (v3) — WHEN/constructor-pattern sugar
        Pair("density-v3", "25-option-some-unwrap-when") to "25-option-some-unwrap",
        // Layer A density v4 — combined nested expressions (Agent A's
        // Slice 10) + deeper Elaborator type inference (Agent B's seven
        // new inference cases). The combined v4 fixtures use both
        // techniques in one file per task.
        Pair("density-v4", "21-fixpoint-factorial-v4") to "21-fixpoint-factorial",
        Pair("density-v4", "25-option-some-unwrap-v4") to "25-option-some-unwrap",
        Pair("density-v4", "41-toggle-machine-v4") to "41-toggle-machine",
        Pair("density-v4", "54-json-value-primitives-v4") to "54-json-value-primitives",
    )

    /**
     * Layer A density v5 (Q-060 M-4) — registry-wide implicit builtins
     * (slice a) and opt-in `@auto` effect synthesis (slice b). These
     * fixtures have no canonical-JSON corpus counterpart; each pairs a
     * density form against its explicit hand-declared Layer A
     * counterpart in the same folder (`<base>.layer-a` vs
     * `<base>-explicit.layer-a`), asserting byte-identical canonical
     * graphs — the section 2.4 gate of
     * `proposals/authoring-cost-reduction.md`.
     */
    private val v5Pairs = listOf(
        "list-map-double",
        "string-split-join",
        "fs-write-auto",
        "fs-list-auto",
    )

    @TestFactory
    fun densityV5RoundTrip(): List<DynamicTest> = v5Pairs.map { base ->
        DynamicTest.dynamicTest("density-v5/$base") {
            val densityText = loadResource("/corpus/layer-a/density-v5/$base.layer-a")
            val explicitText = loadResource("/corpus/layer-a/density-v5/$base-explicit.layer-a")

            val densityFinal = run {
                val ingest = JsonIngest.parse(Authoring.compileToDagJson(densityText))
                Hasher(ingest.rawStore).finalize(ingest.root)
            }
            val explicitFinal = run {
                val ingest = JsonIngest.parse(Authoring.compileToDagJson(explicitText))
                Hasher(ingest.rawStore).finalize(ingest.root)
            }
            val densityRootHash = densityFinal.nodeIdToHash.getValue(densityFinal.root)
            val explicitRootHash = explicitFinal.nodeIdToHash.getValue(explicitFinal.root)
            assertEquals(
                explicitRootHash,
                densityRootHash,
                "$base: explicit-form root hash $explicitRootHash differs from the density-v5 " +
                    "compiled root hash $densityRootHash — implicit-builtin / @auto synthesis " +
                    "broke the hash-stability invariant",
            )

            val densityVerify = Verifier(densityFinal.store, densityFinal.hashToNodeId)
                .verify(densityFinal.root)
            val explicitVerify = Verifier(explicitFinal.store, explicitFinal.hashToNodeId)
                .verify(explicitFinal.root)
            assertTrue(explicitVerify is VerifyResult.Ok) {
                "$base: explicit form failed verification: $explicitVerify"
            }
            assertTrue(densityVerify is VerifyResult.Ok) {
                "$base: density-v5 form failed verification: $densityVerify"
            }
            assertEquals(
                (explicitVerify as VerifyResult.Ok).rootType,
                (densityVerify as VerifyResult.Ok).rootType,
                "$base: root types differ between explicit and density-v5 forms",
            )
        }
    }

    @TestFactory
    fun densityRoundTrip(): List<DynamicTest> = pairs.map { (pair, canonicalBase) ->
        DynamicTest.dynamicTest("${pair.sliceFolder}/${pair.baseName}") {
            val canonicalText = loadResource("/corpus/$canonicalBase.json")
            val layerAText = loadResource("/corpus/layer-a/${pair.sliceFolder}/${pair.baseName}.layer-a")

            val compiledJson = Authoring.compileToDagJson(layerAText)

            val canonicalFinal = run {
                val ingest = JsonIngest.parse(canonicalText)
                Hasher(ingest.rawStore).finalize(ingest.root)
            }
            val compiledFinal = run {
                val ingest = JsonIngest.parse(compiledJson)
                Hasher(ingest.rawStore).finalize(ingest.root)
            }
            val canonicalRootHash = canonicalFinal.nodeIdToHash.getValue(canonicalFinal.root)
            val compiledRootHash = compiledFinal.nodeIdToHash.getValue(compiledFinal.root)
            assertEquals(
                canonicalRootHash,
                compiledRootHash,
                "${pair.baseName}: canonical root hash $canonicalRootHash differs from " +
                    "Layer A density compiled root hash $compiledRootHash — density shorthand " +
                    "broke the hash-stability invariant",
            )

            val canonicalVerify = Verifier(canonicalFinal.store, canonicalFinal.hashToNodeId)
                .verify(canonicalFinal.root)
            val compiledVerify = Verifier(compiledFinal.store, compiledFinal.hashToNodeId)
                .verify(compiledFinal.root)
            assertTrue(canonicalVerify is VerifyResult.Ok) {
                "${pair.baseName}: canonical form failed verification: $canonicalVerify"
            }
            assertTrue(compiledVerify is VerifyResult.Ok) {
                "${pair.baseName}: Layer A density form failed verification: $compiledVerify"
            }
            assertEquals(
                (canonicalVerify as VerifyResult.Ok).rootType,
                (compiledVerify as VerifyResult.Ok).rootType,
                "${pair.baseName}: root types differ between canonical and density forms",
            )
        }
    }

    private fun loadResource(resource: String): String {
        val stream = LayerADensityTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }
}
