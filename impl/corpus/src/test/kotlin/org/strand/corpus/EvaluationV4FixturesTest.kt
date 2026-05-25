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
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Validates the `evaluation/tasks/<task>/reference.layer-a.density-v4`
 * files. Each must compile to canonical dag-json whose root hash equals
 * the corresponding `reference.canonical-json.json`'s root hash.
 *
 * The evaluation files live OUTSIDE the impl/ tree (at the repo root's
 * `evaluation/`) so we resolve them via filesystem paths relative to
 * the workspace, not the classpath. The test is best-effort: when the
 * evaluation/ directory isn't reachable from the test's working
 * directory, all fixture tests are skipped via assumption.
 */
class EvaluationV4FixturesTest {

    private val taskNames = listOf("01-factorial", "02-json-value", "03-toggle-machine")

    @TestFactory
    fun densityV4RoundTrip(): List<DynamicTest> = taskNames.map { taskName ->
        DynamicTest.dynamicTest(taskName) {
            // Locate evaluation/ dir. The test's CWD is the corpus
            // module; walk upward to find evaluation/.
            val cwd = Paths.get("").toAbsolutePath()
            var search = cwd
            var evalDir = search.resolve("evaluation")
            while (!Files.isDirectory(evalDir) && search.parent != null) {
                search = search.parent
                evalDir = search.resolve("evaluation")
            }
            if (!Files.isDirectory(evalDir)) {
                // Skip: not reachable from this working directory.
                org.junit.jupiter.api.Assumptions.assumeTrue(
                    false, "evaluation/ not found from $cwd",
                )
                return@dynamicTest
            }
            val taskDir = evalDir.resolve("tasks").resolve(taskName)
            val layerAFile = taskDir.resolve("reference.layer-a.density-v4")
            val canonicalFile = taskDir.resolve("reference.canonical-json.json")
            if (!Files.exists(layerAFile) || !Files.exists(canonicalFile)) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                    false, "reference files missing in $taskDir",
                )
                return@dynamicTest
            }

            val canonicalText = Files.readString(canonicalFile)
            val layerAText = Files.readString(layerAFile)

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
            assertEquals(canonicalRootHash, compiledRootHash) {
                "$taskName: density-v4 root hash $compiledRootHash differs from canonical $canonicalRootHash"
            }
            val compiledVerify = Verifier(compiledFinal.store, compiledFinal.hashToNodeId)
                .verify(compiledFinal.root)
            assertTrue(compiledVerify is VerifyResult.Ok) {
                "$taskName: density-v4 form failed verification: $compiledVerify"
            }
        }
    }
}
