package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.strand.hashing.CanonicalEncoding
import java.nio.file.Files

/**
 * Asserts the language-independent golden-hash conformance file
 * `corpus/golden-hashes.json` against the live implementation:
 *
 *  1. every golden entry's recomputed root hash matches the committed value,
 *  2. coverage is bidirectional — every corpus program file has a golden
 *     entry AND every golden entry has a corpus file — so adding a corpus
 *     program without updating the goldens fails loudly (and so does
 *     deleting one without removing its entry),
 *  3. the same two properties for the Layer A fixture section, which doubles
 *     as a Layer A <-> canonical-form conformance witness.
 *
 * Regeneration (when the corpus legitimately changes): from `impl-kotlin/`,
 *
 *     .\gradlew.bat :corpus:test --tests "org.strand.corpus.CorpusGoldenHashTest" -Dstrand.regenerateGoldenHashes=true
 *
 * which rewrites `corpus/golden-hashes.json` in place via
 * [regenerate golden hashes when requested], then asserts the fresh file.
 * Diff the result before committing — every changed hash must be explainable
 * by an intentional corpus or encoding change.
 */
class CorpusGoldenHashTest {

    private val corpusDir by lazy { GoldenHashes.findCorpusDir() }

    private val regenerationRequested: Boolean
        get() = System.getProperty(GoldenHashes.REGENERATE_PROPERTY) == "true"

    /**
     * During a regeneration run the assertion tests below are skipped (the
     * file is being rewritten in the same run and JUnit execution order is
     * unspecified); run the test again without the property to assert the
     * fresh file.
     */
    private fun assumeNotRegenerating() {
        Assumptions.assumeFalse(regenerationRequested) {
            "Skipped while regenerating golden-hashes.json; rerun without " +
                "-D${GoldenHashes.REGENERATE_PROPERTY} to assert the fresh file"
        }
    }

    @Test
    fun `regenerate golden hashes when requested`() {
        Assumptions.assumeTrue(regenerationRequested) {
            "Set -D${GoldenHashes.REGENERATE_PROPERTY}=true to rewrite corpus/golden-hashes.json"
        }
        val golden = GoldenHashes.computeGoldenFile(corpusDir)
        GoldenHashes.writeGoldenFile(corpusDir, golden)
        println(
            "Regenerated ${corpusDir.resolve(GoldenHashes.GOLDEN_FILE_NAME)}: " +
                "${golden.programs.size} programs, ${golden.layerA.size} Layer A fixtures"
        )
    }

    @Test
    fun `golden epoch matches the implementation's declared encoding epoch`() {
        assumeNotRegenerating()
        val golden = GoldenHashes.readGoldenFile(corpusDir)
        assertEquals(CanonicalEncoding.EPOCH, golden.epoch) {
            "corpus/golden-hashes.json declares encoding epoch ${golden.epoch} but this " +
                "implementation declares epoch ${CanonicalEncoding.EPOCH} " +
                "(CanonicalEncoding.EPOCH). An epoch advance must ship as one pass: its own " +
                "proposal, the constant bump, regenerated goldens, and an Epoch log entry in " +
                "design/canonical-encoding.md (Q-062)."
        }
    }

    @TestFactory
    fun `every golden program hash matches the implementation`(): List<DynamicTest> {
        assumeNotRegenerating()
        return GoldenHashes.readGoldenFile(corpusDir).programs.map { (relPath, expected) ->
            DynamicTest.dynamicTest(relPath) {
                val file = corpusDir.resolve(relPath)
                assertTrue(Files.isRegularFile(file)) {
                    "Golden entry '$relPath' has no corpus file at $file"
                }
                val actual = GoldenHashes.computeRootHashOrMarker(Files.readString(file))
                if (expected.startsWith(GoldenHashes.UNHASHABLE_PREFIX)) {
                    // Marker entries assert only that standalone hashing still
                    // fails; the recorded reason text is informational.
                    assertTrue(actual.startsWith(GoldenHashes.UNHASHABLE_PREFIX)) {
                        "$relPath is marked unhashable-standalone in the goldens but now " +
                            "hashes to $actual; update golden-hashes.json"
                    }
                } else {
                    assertEquals(expected, actual) {
                        "Root hash drift for $relPath. If the corpus or the canonical " +
                            "encoding changed intentionally, regenerate the goldens " +
                            "(see corpus/README.md); otherwise this is a hash-compatibility break."
                    }
                }
            }
        }
    }

    @Test
    fun `program coverage is bidirectional`() {
        assumeNotRegenerating()
        val goldenKeys = GoldenHashes.readGoldenFile(corpusDir).programs.keys
        val corpusFiles = GoldenHashes.enumerateProgramFiles(corpusDir).toSet()

        val missingFromGoldens = corpusFiles - goldenKeys
        val missingFromCorpus = goldenKeys - corpusFiles
        assertTrue(missingFromGoldens.isEmpty() && missingFromCorpus.isEmpty()) {
            buildString {
                appendLine("corpus/golden-hashes.json is out of sync with corpus/ (see corpus/README.md to regenerate):")
                if (missingFromGoldens.isNotEmpty()) {
                    appendLine("  program files without a golden entry: $missingFromGoldens")
                }
                if (missingFromCorpus.isNotEmpty()) {
                    appendLine("  golden entries without a program file: $missingFromCorpus")
                }
            }
        }
    }

    @TestFactory
    fun `every golden Layer A hash matches the implementation`(): List<DynamicTest> {
        assumeNotRegenerating()
        return GoldenHashes.readGoldenFile(corpusDir).layerA.map { (relPath, expected) ->
            DynamicTest.dynamicTest(relPath) {
                val file = corpusDir.resolve(relPath)
                assertTrue(Files.isRegularFile(file)) {
                    "Golden entry '$relPath' has no corpus file at $file"
                }
                val actual = GoldenHashes.computeLayerARootHash(Files.readString(file))
                assertEquals(expected, actual) {
                    "Compiled root hash drift for $relPath (Layer A -> canonical conformance)."
                }
            }
        }
    }

    @Test
    fun `Layer A coverage is bidirectional`() {
        assumeNotRegenerating()
        val goldenKeys = GoldenHashes.readGoldenFile(corpusDir).layerA.keys
        val corpusFiles = GoldenHashes.enumerateLayerAFiles(corpusDir).toSet()

        val missingFromGoldens = corpusFiles - goldenKeys
        val missingFromCorpus = goldenKeys - corpusFiles
        assertTrue(missingFromGoldens.isEmpty() && missingFromCorpus.isEmpty()) {
            buildString {
                appendLine("golden-hashes.json layerA section is out of sync with corpus/layer-a/:")
                if (missingFromGoldens.isNotEmpty()) {
                    appendLine("  fixtures without a golden entry: $missingFromGoldens")
                }
                if (missingFromCorpus.isNotEmpty()) {
                    appendLine("  golden entries without a fixture: $missingFromCorpus")
                }
            }
        }
    }
}
