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
    )

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
