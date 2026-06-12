package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.authoring.DagJsonEmitter
import java.nio.file.Files

/**
 * Q-063 scenario 2 (density-fixture half) — for every Layer A fixture in the
 * corpus, compiling under the default module-resolution path and under the
 * legacy per-program synthesis path (`-Dstrand.prelude.legacySynthesis=true`)
 * produces identical canonical root hashes. Together with the per-name half
 * (`org.strand.authoring.PreludeResolutionEquivalenceTest`) this is the
 * equivalence suite that justified flipping resolution on as the default:
 * resolved prelude nodes are byte-identical to their synthesized twins, so
 * the swap has zero program-hash impact (scenario 3's root-hash invariance
 * over the same fixtures is asserted by [CorpusGoldenHashTest], whose
 * goldens were not regenerated for Q-063).
 */
class PreludeResolutionDensityEquivalenceTest {

    private val corpusDir by lazy { GoldenHashes.findCorpusDir() }

    @TestFactory
    fun `density fixtures compile hash-identically under resolution and legacy synthesis`(): List<DynamicTest> {
        val fixtures = GoldenHashes.enumerateLayerAFiles(corpusDir)
        assertTrue(fixtures.isNotEmpty()) { "no Layer A fixtures found under $corpusDir" }
        return fixtures.map { rel ->
            DynamicTest.dynamicTest(rel) {
                val text = Files.readString(corpusDir.resolve(rel))
                val resolvedHash = withLegacySynthesis(false) {
                    GoldenHashes.computeLayerARootHash(text)
                }
                val legacyHash = withLegacySynthesis(true) {
                    GoldenHashes.computeLayerARootHash(text)
                }
                assertEquals(legacyHash, resolvedHash) {
                    "$rel: module-resolution root hash diverges from legacy-synthesis root hash"
                }
            }
        }
    }

    private fun <T> withLegacySynthesis(enabled: Boolean, block: () -> T): T {
        val property = DagJsonEmitter.LEGACY_SYNTHESIS_PROPERTY
        val previous = System.getProperty(property)
        if (enabled) System.setProperty(property, "true") else System.clearProperty(property)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
}
