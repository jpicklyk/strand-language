package org.strand.corpus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.strand.authoring.LayerAGrammar
import org.strand.authoring.PreludeModule
import org.strand.authoring.PreludeModuleGenerator
import org.strand.core.JsonIngest
import org.strand.hashing.CanonicalEncoding
import org.strand.hashing.Hasher
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import java.nio.file.Files
import java.nio.file.Path

/**
 * Q-063 scenario 1 — the prelude module's pinned-golden conformance net.
 *
 * The generated prelude module exists in two committed artifacts:
 *
 *  * `corpus/prelude-manifest.json` (repo top-level corpus) — the pinned
 *    manifest hash plus per-export and per-node hashes;
 *  * `impl-kotlin/authoring/src/main/resources/org/strand/authoring/prelude-module.json`
 *    — the bundled dag-json snapshot the [PreludeModule] loader (and so the
 *    emitter's resolution path and the CLI's default registry) reads.
 *
 * This suite asserts that regenerating from the live reserved-spec table
 * reproduces both artifacts exactly, so any reserved-spec edit fails loudly
 * until the goldens are deliberately regenerated — the structural fix for
 * the 2026-06-11 projection-arity drift class. It also runs the snapshot
 * through the verifier: the manifest is admitted under the existing N-046
 * certification (per-export declared effects exactly equal each export's
 * effect surface), with no new verifier rules.
 *
 * Regeneration (when the reserved table legitimately changes): from
 * `impl-kotlin/`,
 *
 *     .\gradlew.bat :corpus:test --tests "org.strand.corpus.PreludeModuleConformanceTest" -Dstrand.regeneratePreludeModule=true
 *
 * which rewrites both artifacts in place, then rerun without the flag to
 * assert the fresh files. Diff before committing — every changed hash must
 * be explainable by an intentional reserved-table change.
 */
class PreludeModuleConformanceTest {

    private val corpusDir by lazy { GoldenHashes.findCorpusDir() }
    private val repoRoot by lazy { corpusDir.parent }

    private val regenerationRequested: Boolean
        get() = System.getProperty(REGENERATE_PROPERTY) == "true"

    private fun assumeNotRegenerating() {
        Assumptions.assumeFalse(regenerationRequested) {
            "Skipped while regenerating the prelude module artifacts; rerun without " +
                "-D$REGENERATE_PROPERTY to assert the fresh files"
        }
    }

    @Test
    fun `regenerate prelude module artifacts when requested`() {
        Assumptions.assumeTrue(regenerationRequested) {
            "Set -D$REGENERATE_PROPERTY=true to rewrite the prelude snapshot and golden"
        }
        val generated = PreludeModuleGenerator.generate()

        val snapshotPath = snapshotResourcePath(repoRoot)
        Files.createDirectories(snapshotPath.parent)
        Files.writeString(snapshotPath, generated.text)

        val goldenPath = corpusDir.resolve(GOLDEN_FILE_NAME)
        Files.writeString(goldenPath, goldenText(generated))

        println(
            "Regenerated $snapshotPath and $goldenPath: " +
                "${generated.exportHashes.size} exports, ${generated.nodeHashes.size} reserved nodes, " +
                "manifest ${generated.manifestHash}"
        )
    }

    @Test
    fun `golden epoch matches the implementation's declared encoding epoch`() {
        assumeNotRegenerating()
        val golden = readGolden()
        assertEquals(CanonicalEncoding.EPOCH, golden.epoch) {
            "corpus/$GOLDEN_FILE_NAME declares encoding epoch ${golden.epoch} but this " +
                "implementation declares epoch ${CanonicalEncoding.EPOCH} " +
                "(CanonicalEncoding.EPOCH). Regenerate the prelude artifacts in the same " +
                "pass as the epoch advance (Q-062)."
        }
    }

    @Test
    fun `regenerated manifest hash matches the pinned golden`() {
        assumeNotRegenerating()
        val golden = readGolden()
        val generated = PreludeModuleGenerator.generate()
        assertEquals(golden.manifestHash, generated.manifestHash.toString()) {
            "Prelude manifest hash drift. If the reserved-spec table changed intentionally, " +
                "regenerate the prelude artifacts (-D$REGENERATE_PROPERTY=true); otherwise " +
                "this is an unintended prelude change."
        }
    }

    @Test
    fun `regenerated per-export hashes match the pinned golden`() {
        assumeNotRegenerating()
        val golden = readGolden()
        val generated = PreludeModuleGenerator.generate()
        assertEquals(golden.exports, generated.exportHashes.mapValues { it.value.toString() }) {
            "Prelude export-hash drift against corpus/$GOLDEN_FILE_NAME (regenerate deliberately " +
                "with -D$REGENERATE_PROPERTY=true if the reserved table changed)"
        }
    }

    @Test
    fun `regenerated per-node hashes match the pinned golden and cover every reserved name`() {
        assumeNotRegenerating()
        val golden = readGolden()
        val generated = PreludeModuleGenerator.generate()
        assertEquals(LayerAGrammar.reservedNodes.keys, golden.nodes.keys) {
            "corpus/$GOLDEN_FILE_NAME nodes section is out of sync with LayerAGrammar.reservedNodes " +
                "(regenerate with -D$REGENERATE_PROPERTY=true)"
        }
        assertEquals(golden.nodes, generated.nodeHashes.mapValues { it.value.toString() }) {
            "Prelude node-hash drift against corpus/$GOLDEN_FILE_NAME"
        }
    }

    @Test
    fun `bundled snapshot matches the regenerated document`() {
        assumeNotRegenerating()
        val generated = PreludeModuleGenerator.generate()
        // Structural equality (key order does not affect content hashes).
        val bundled = Json.parseToJsonElement(PreludeModule.loaded.text).jsonObject
        assertEquals(generated.document, bundled) {
            "The bundled prelude-module.json snapshot is stale relative to the reserved-spec " +
                "table; regenerate with -D$REGENERATE_PROPERTY=true"
        }
        assertEquals(generated.manifestHash, PreludeModule.loaded.manifestHash)
        assertEquals(generated.nodeHashes, PreludeModule.loaded.nodeHashes)
        assertEquals(generated.exportHashes, PreludeModule.loaded.exportHashes)
    }

    @Test
    fun `bundled snapshot verifies cleanly under the existing N-046 manifest certification`() {
        assumeNotRegenerating()
        val ingest = JsonIngest.parse(PreludeModule.loaded.text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val result = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(result is VerifyResult.Ok) {
            "The prelude module snapshot must be admitted by the verifier " +
                "(N-046 per-export effect certification included); got: $result"
        }
    }

    // ── golden file plumbing ─────────────────────────────────────────────────

    private data class Golden(
        /** The canonical-encoding epoch (Q-062); must equal [CanonicalEncoding.EPOCH]. */
        val epoch: Int?,
        val manifestHash: String,
        val exports: Map<String, String>,
        val nodes: Map<String, String>,
    )

    private fun readGolden(): Golden {
        val path = corpusDir.resolve(GOLDEN_FILE_NAME)
        check(Files.isRegularFile(path)) {
            "Missing $path. Generate it from impl-kotlin/ with: ${PreludeModule.REGENERATE_COMMAND}"
        }
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        fun section(key: String): Map<String, String> =
            root[key]?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.content } ?: emptyMap()
        return Golden(
            epoch = root["epoch"]?.jsonPrimitive?.int,
            manifestHash = root["manifestHash"]!!.jsonPrimitive.content,
            exports = section("exports"),
            nodes = section("nodes"),
        )
    }

    private fun goldenText(generated: PreludeModuleGenerator.GeneratedPreludeModule): String {
        val document = buildJsonObject {
            put(
                "description",
                JsonPrimitive(
                    "Pinned content hashes for the generated prelude module (Q-063): the N-046 " +
                        "ModuleManifest hash, per-export hashes (the ForeignNode entries the manifest " +
                        "exports), and per-node hashes for every reserved name in the Layer A implicit " +
                        "prelude. Language-independent conformance vectors: any Strand implementation's " +
                        "prelude must reproduce these hashes. Regenerate via the command in " +
                        "corpus/README.md when the reserved-spec table legitimately changes."
                )
            )
            put(
                "hashFormat",
                JsonPrimitive(
                    "BLAKE3-256 multihash over the canonical node encoding (design/canonical-encoding.md): " +
                        "one 0x1e prefix byte + 32 digest bytes, rendered as lowercase hex (66 hex chars)."
                )
            )
            put("epoch", JsonPrimitive(CanonicalEncoding.EPOCH))
            put("manifestHash", JsonPrimitive(generated.manifestHash.toString()))
            put("exports", buildJsonObject {
                generated.exportHashes.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v.toString())) }
            })
            put("nodes", buildJsonObject {
                generated.nodeHashes.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v.toString())) }
            })
        }
        return prettyJson.encodeToString(JsonObject.serializer(), document) + "\n"
    }

    companion object {
        const val GOLDEN_FILE_NAME = "prelude-manifest.json"
        const val REGENERATE_PROPERTY = "strand.regeneratePreludeModule"

        private val prettyJson = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /** The on-disk location of the bundled snapshot resource, from the repo root. */
        fun snapshotResourcePath(repoRoot: Path): Path =
            repoRoot.resolve("impl-kotlin")
                .resolve("authoring").resolve("src").resolve("main").resolve("resources")
                .resolve("org").resolve("strand").resolve("authoring")
                .resolve("prelude-module.json")
    }
}
