package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.strand.authoring.Authoring
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.hashing.LocalProgramResolver
import org.strand.hashing.federated
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.streams.toList

/**
 * The corpus is curated: every program wires every node it declares, so
 * the store-wide unreachable-node pass must report ZERO warnings for
 * every corpus program that verifies. This sweep pins that — a corpus
 * edit that leaves a node unwired (or a regression in the warning pass's
 * root set) fails here.
 *
 * Coverage: every top-level `.json` program in the corpus directory
 * (event lists excluded), the corpus 76 federation pair (lib standalone;
 * app verified against lib as a peer), and every `.layer-a` density
 * fixture under `corpus/layer-a` compiled through the Layer A pipeline
 * (so IF / WHEN / prelude expansions are also covered).
 *
 * Programs that fail verification (the deliberate negative corpus
 * programs) are skipped — the warnings channel only exists on
 * [VerifyResult.Ok].
 */
class CorpusWarningSweepTest {

    /** Walk upward from CWD to the repo's top-level corpus/ directory. */
    private fun corpusDir(): Path? {
        var search: Path? = Paths.get("").toAbsolutePath()
        while (search != null) {
            val candidate = search.resolve("corpus")
            if (Files.isRegularFile(candidate.resolve("01-int-literal.json"))) return candidate
            search = search.parent
        }
        return null
    }

    private fun verifyText(text: String): VerifyResult {
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    private fun assertWarningClean(label: String, result: VerifyResult) {
        if (result is VerifyResult.Ok) {
            assertTrue(result.warnings.isEmpty()) {
                "$label: corpus programs are curated and must be warning-clean, got: ${result.warnings}"
            }
        }
        // Failed programs are the deliberate negative cases — no warnings channel.
    }

    @TestFactory
    fun `top-level corpus programs are warning-clean`(): List<DynamicTest> {
        val dir = corpusDir()
        Assumptions.assumeTrue(dir != null, "corpus/ not found from ${Paths.get("").toAbsolutePath()}")
        // Exclude event-list files (.events.json) and non-program metadata files
        // (golden-hashes.json and prelude-manifest.json are hash-vector
        // registries, not Strand programs).
        val metadataFiles = setOf("golden-hashes.json", "prelude-manifest.json")
        val programs = Files.list(dir).use { stream ->
            stream.toList()
                .filter { it.extension == "json" && !it.name.endsWith(".events.json") && it.name !in metadataFiles }
                .sortedBy { it.name }
        }
        assertTrue(programs.isNotEmpty(), "no corpus programs found under $dir")
        return programs.map { path ->
            DynamicTest.dynamicTest(path.name) {
                assertWarningClean(path.name, verifyText(Files.readString(path)))
            }
        }
    }

    @TestFactory
    fun `layer-a density fixtures compile warning-clean`(): List<DynamicTest> {
        val dir = corpusDir()
        Assumptions.assumeTrue(dir != null, "corpus/ not found from ${Paths.get("").toAbsolutePath()}")
        val layerADir = dir!!.resolve("layer-a")
        Assumptions.assumeTrue(Files.isDirectory(layerADir), "corpus/layer-a not found")
        val fixtures = Files.walk(layerADir).use { stream ->
            stream.toList()
                .filter { Files.isRegularFile(it) && it.name.endsWith(".layer-a") }
                .sortedBy { it.toString() }
        }
        assertTrue(fixtures.isNotEmpty(), "no layer-a fixtures found under $layerADir")
        return fixtures.map { path ->
            DynamicTest.dynamicTest("${path.parent.name}/${path.name}") {
                val dagJson = Authoring.compileToDagJson(Files.readString(path))
                assertWarningClean("${path.parent.name}/${path.name}", verifyText(dagJson))
            }
        }
    }

    @Test
    fun `corpus 76 federation pair is warning-clean`() {
        val dir = corpusDir()
        Assumptions.assumeTrue(dir != null, "corpus/ not found from ${Paths.get("").toAbsolutePath()}")
        val fedDir = dir!!.resolve("76-multi-store-composition")

        val libText = Files.readString(fedDir.resolve("lib.json"))
        val libResult = verifyText(libText)
        assertTrue(libResult is VerifyResult.Ok) { "lib.json must verify, got: $libResult" }
        assertWarningClean("76/lib.json", libResult)

        // The app references lib's export by content hash; verify federated so
        // the admitted subgraph also participates in the reachability walk.
        val libIngest = JsonIngest.parse(libText)
        val lib = Hasher(libIngest.rawStore).finalize(libIngest.root)
        val appIngest = JsonIngest.parse(Files.readString(fedDir.resolve("app.json")))
        val app = Hasher(appIngest.rawStore).finalize(appIngest.root)
            .federated(LocalProgramResolver(lib))
        val appResult = Verifier(app.store, app.hashToNodeId, app::fetchAndAdmit).verify(app.root)
        assertTrue(appResult is VerifyResult.Ok) { "app.json must verify against the peer, got: $appResult" }
        assertWarningClean("76/app.json (federated)", appResult)
    }
}
