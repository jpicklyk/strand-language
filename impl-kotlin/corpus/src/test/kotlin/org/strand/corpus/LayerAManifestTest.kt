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
 * Q-057 byte-identity regression for the `MFT` (ModuleManifest) / `MEX`
 * (ManifestExport) Layer A authoring codes.
 *
 * `MEX` has no canonical dag-json node of its own — per the N-046 encoding
 * (`org.strand.core.StoredNode.RawManifestExport`, `Node.ManifestExport`),
 * an export is always an inline `{target, declaredEffects, displayName}`
 * object embedded in its `ModuleManifest`'s `exports` array. `MEX` is a
 * Layer A authoring convenience that [org.strand.authoring.DagJsonEmitter]
 * resolves and inlines at emit time; it never appears as a standalone
 * entry in the emitted `nodes` map.
 *
 * The defining correctness property (per
 * `proposals/layer-a-capability-manifest.md`): authoring corpus 79
 * (`module-manifest-with-effects`) and 80 (`manifest-effect-mismatch-rejected`)
 * through `MFT`/`MEX` must compile to canonical dag-json whose root hash is
 * BYTE-IDENTICAL to the hand-authored canonical JSON's root hash — the same
 * property [LayerADensityTest] establishes for the other density sugars.
 * `displayName` and `manifestSignature` are metadata excluded from the
 * canonical encoding (see `Node.ModuleManifest`'s doc comment), so hash
 * equality holds independent of the exact display strings chosen — the
 * fixtures below mirror the corpus display strings anyway for clarity.
 */
class LayerAManifestTest {

    private data class Pair(val layerAName: String, val canonicalBase: String)

    private val pairs = listOf(
        Pair("79-module-manifest-with-effects", "79-module-manifest-with-effects"),
        Pair("80-manifest-effect-mismatch-rejected", "80-manifest-effect-mismatch-rejected"),
    )

    @TestFactory
    fun manifestByteIdentity(): List<DynamicTest> = pairs.map { (layerAName, canonicalBase) ->
        DynamicTest.dynamicTest("manifest/$layerAName") {
            val canonicalText = loadResource("/corpus/$canonicalBase.json")
            val layerAText = loadResource("/corpus/layer-a/manifest/$layerAName.layer-a")

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
                "$canonicalBase: canonical root hash $canonicalRootHash differs from the " +
                    "MFT/MEX-compiled root hash $compiledRootHash — the manifest authoring path " +
                    "produced a different JSON shape than the hand-authored corpus form",
            )

            // Both forms verify identically (corpus 79 accepts; corpus 80 is
            // the deliberate under-declaration rejection — CorpusManifestTest
            // pins the exact VerifyError.ManifestExportEffectMismatch shape).
            val canonicalVerify = Verifier(canonicalFinal.store, canonicalFinal.hashToNodeId)
                .verify(canonicalFinal.root)
            val compiledVerify = Verifier(compiledFinal.store, compiledFinal.hashToNodeId)
                .verify(compiledFinal.root)
            assertEquals(
                canonicalVerify::class,
                compiledVerify::class,
                "$canonicalBase: verification outcome differs between canonical and MFT/MEX forms",
            )
            if (canonicalVerify is VerifyResult.Ok) {
                assertTrue(compiledVerify is VerifyResult.Ok)
                assertEquals(
                    canonicalVerify.rootType,
                    (compiledVerify as VerifyResult.Ok).rootType,
                    "$canonicalBase: root types differ between canonical and MFT/MEX forms",
                )
            }
        }
    }

    private fun loadResource(resource: String): String {
        val stream = LayerAManifestTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }
}
