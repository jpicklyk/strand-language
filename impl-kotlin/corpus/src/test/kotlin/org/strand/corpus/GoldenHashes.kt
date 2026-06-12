package org.strand.corpus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.strand.authoring.Authoring
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * Shared machinery for the language-independent golden-hash conformance file
 * `corpus/golden-hashes.json` (at the repo's top-level `corpus/` directory,
 * next to the programs it covers).
 *
 * The golden file maps every corpus *program* to the canonical string form of
 * its root content hash (BLAKE3-256 multihash: one 0x1e prefix byte plus the
 * 32 digest bytes, lowercase hex — `Hash.toString()`). A second section maps
 * every Layer A fixture to the root hash of its compiled canonical form,
 * witnessing Layer A <-> canonical conformance.
 *
 * Program/non-program classification is structural: a corpus JSON file is a
 * program iff its top-level JSON object carries both a `root` and a `nodes`
 * key. Event-input files (`*.events.json`, shape `{"events": [...]}`), the
 * federation name registry (corpus 77 `registry.json`, shape
 * `{"entries": ...}`), and the golden file itself are all excluded by that rule.
 *
 * Hashing does not require verification: deliberately verifier-failing
 * fixtures still hash. A program that cannot be hashed *standalone* (ingest
 * or finalize throws without external context) is recorded with an explicit
 * `"unhashable-standalone: <reason>"` marker instead of a hash, never
 * silently omitted.
 *
 * Used by [CorpusGoldenHashTest] both to assert the committed goldens and to
 * regenerate them (under `-Dstrand.regenerateGoldenHashes=true`) when the
 * corpus legitimately changes.
 */
object GoldenHashes {

    const val GOLDEN_FILE_NAME = "golden-hashes.json"
    const val UNHASHABLE_PREFIX = "unhashable-standalone"
    const val REGENERATE_PROPERTY = "strand.regenerateGoldenHashes"

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * Locate the repo's top-level `corpus/` directory by walking up from the
     * test working directory (the `:corpus` Gradle module dir). Identified by
     * containing `01-int-literal.json`, which disambiguates it from the
     * `impl-kotlin/corpus` module directory of the same name.
     */
    fun findCorpusDir(): Path {
        var search: Path? = Paths.get("").toAbsolutePath()
        while (search != null) {
            val candidate = search.resolve("corpus")
            if (Files.isRegularFile(candidate.resolve("01-int-literal.json"))) {
                return candidate
            }
            search = search.parent
        }
        error("Could not locate the top-level corpus/ directory from ${Paths.get("").toAbsolutePath()}")
    }

    /**
     * Enumerate every corpus *program* file as a corpus-relative path with
     * forward slashes (e.g. `01-int-literal.json`,
     * `76-multi-store-composition/app.json`). Covers flat JSON files in
     * `corpus/` plus JSON files one level inside its subdirectories, excluding
     * `layer-a/` (covered by [enumerateLayerAFiles]), `negative/` (Q-066:
     * negative-corpus entries are defined by their rejection and carry no
     * golden hashes — driven by [CorpusNegativeTest]), and the golden file.
     * Non-program JSON (no `root` + `nodes` keys) is excluded by structure.
     */
    fun enumerateProgramFiles(corpusDir: Path): List<String> {
        val out = mutableListOf<String>()
        val entries = Files.list(corpusDir).use { it.toList() }.sorted()
        for (entry in entries) {
            val name = entry.fileName.toString()
            if (Files.isRegularFile(entry)) {
                if (name.endsWith(".json") && name != GOLDEN_FILE_NAME &&
                    isProgramDocument(Files.readString(entry))
                ) {
                    out += name
                }
            } else if (Files.isDirectory(entry) && name != "layer-a" && name != "negative") {
                val inner = Files.list(entry).use { it.toList() }.sorted()
                for (file in inner) {
                    val innerName = file.fileName.toString()
                    if (Files.isRegularFile(file) && innerName.endsWith(".json") &&
                        isProgramDocument(Files.readString(file))
                    ) {
                        out += "$name/$innerName"
                    }
                }
            }
        }
        return out.sorted()
    }

    /**
     * Enumerate every Layer A fixture under `corpus/layer-a/` as a
     * corpus-relative path with forward slashes
     * (e.g. `layer-a/density-v4/21-fixpoint-factorial-v4.layer-a`).
     */
    fun enumerateLayerAFiles(corpusDir: Path): List<String> {
        val layerADir = corpusDir.resolve("layer-a")
        if (!Files.isDirectory(layerADir)) return emptyList()
        return Files.walk(layerADir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".layer-a") }
                .map { corpusDir.relativize(it).toString().replace('\\', '/') }
                .toList()
        }.sorted()
    }

    /** Structural program test: top-level JSON object with `root` and `nodes`. */
    fun isProgramDocument(text: String): Boolean = try {
        val element = Json.parseToJsonElement(text)
        element is JsonObject && element.containsKey("root") && element.containsKey("nodes")
    } catch (e: Exception) {
        false
    }

    /**
     * Ingest a canonical dag-json program and return its root content hash in
     * the implementation's canonical serialized form (multihash lowercase hex).
     * Hashing deliberately skips verification.
     */
    fun computeRootHash(dagJsonText: String): String {
        val ingest = JsonIngest.parse(dagJsonText)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return finalized.nodeIdToHash.getValue(finalized.root).toString()
    }

    /**
     * [computeRootHash], but a failure to hash standalone yields the explicit
     * `unhashable-standalone` marker rather than throwing.
     */
    fun computeRootHashOrMarker(dagJsonText: String): String = try {
        computeRootHash(dagJsonText)
    } catch (e: Exception) {
        "$UNHASHABLE_PREFIX: ${e::class.simpleName}: ${e.message?.lineSequence()?.first().orEmpty()}"
    }

    /** Compile a Layer A fixture and return the root hash of its canonical form. */
    fun computeLayerARootHash(layerAText: String): String =
        computeRootHash(Authoring.compileToDagJson(layerAText))

    /** Parse the committed golden file into its two path -> value sections. */
    fun readGoldenFile(corpusDir: Path): GoldenFile {
        val path = corpusDir.resolve(GOLDEN_FILE_NAME)
        check(Files.isRegularFile(path)) {
            "Missing $path. Generate it with: " +
                ".\\gradlew.bat :corpus:test --tests \"org.strand.corpus.CorpusGoldenHashTest\" " +
                "-D$REGENERATE_PROPERTY=true (from impl-kotlin/)"
        }
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        fun section(key: String): Map<String, String> =
            root[key]?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.content } ?: emptyMap()
        return GoldenFile(programs = section("programs"), layerA = section("layerA"))
    }

    /** Recompute both sections from the corpus on disk. */
    fun computeGoldenFile(corpusDir: Path): GoldenFile {
        val programs = enumerateProgramFiles(corpusDir).associateWith { rel ->
            computeRootHashOrMarker(Files.readString(corpusDir.resolve(rel)))
        }
        val layerA = enumerateLayerAFiles(corpusDir).associateWith { rel ->
            computeLayerARootHash(Files.readString(corpusDir.resolve(rel)))
        }
        return GoldenFile(programs, layerA)
    }

    /** Serialize and write the golden file deterministically (sorted keys, LF, trailing newline). */
    fun writeGoldenFile(corpusDir: Path, golden: GoldenFile) {
        val document = buildJsonObject {
            put(
                "description",
                JsonPrimitive(
                    "Golden root content hashes for every corpus program and Layer A fixture. " +
                        "Language-independent conformance vectors: any Strand implementation must " +
                        "reproduce these hashes from the same inputs. Regenerate via the command in " +
                        "corpus/README.md when the corpus legitimately changes."
                )
            )
            put(
                "hashFormat",
                JsonPrimitive(
                    "BLAKE3-256 multihash over the canonical node encoding (design/canonical-encoding.md): " +
                        "one 0x1e prefix byte + 32 digest bytes, rendered as lowercase hex (66 hex chars)."
                )
            )
            put(
                "exclusionRule",
                JsonPrimitive(
                    "A corpus JSON file is a program iff its top-level object has both 'root' and 'nodes' " +
                        "keys. Event-input files (*.events.json), the name registry " +
                        "(77-name-registry-resolution/registry.json), the negative corpus (negative/, whose " +
                        "entries are defined by their rejection), and this file are excluded by that rule. " +
                        "Programs that cannot be hashed standalone carry an explicit " +
                        "'unhashable-standalone: <reason>' marker instead of a hash."
                )
            )
            put("programs", buildJsonObject {
                golden.programs.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
            put("layerA", buildJsonObject {
                golden.layerA.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
        }
        val text = json.encodeToString(JsonObject.serializer(), document) + "\n"
        Files.writeString(corpusDir.resolve(GOLDEN_FILE_NAME), text)
    }

    data class GoldenFile(
        val programs: Map<String, String>,
        val layerA: Map<String, String>,
    )
}
