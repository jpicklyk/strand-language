package org.strand.authoring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher

/**
 * Q-063 — generate the implicit prelude as a content-addressed module.
 *
 * Walks the reserved-spec table ([LayerAGrammar.reservedNodes]), emits every
 * reserved node in its canonical dag-json form (byte-identical to what
 * [DagJsonEmitter]'s legacy per-program synthesis produced), and wraps the
 * expression-level exports — the `ForeignNode` entries, i.e. the callable
 * surface of the prelude — in an N-046 `ModuleManifest` whose per-export
 * `declaredEffects` exactly match each ForeignNode's effect surface, so the
 * existing manifest certification (`Verifier.checkManifests`) admits the
 * bundle with no new verifier rules.
 *
 * Type-level reserved entries (PrimitiveType, FunctionType, ProductType,
 * ProductTypeField) and EffectCategory entries are not `ModuleManifest`
 * exports — N-046 export targets are inferred as expressions, and type /
 * effect declarations are not expressions — but they ship in the same
 * snapshot document (reachable as the exports' signature and effect
 * dependencies) and are individually pinned by hash in the
 * `corpus/prelude-manifest.json` golden, so every reserved name resolves to
 * a content hash regardless of export status.
 *
 * The generated artifacts:
 *
 *  * the bundled snapshot — `prelude-module.json` on the `:authoring`
 *    classpath, loaded at emit time by [PreludeModule] (the resolution path
 *    that replaces per-program synthesis);
 *  * the pinned golden — `corpus/prelude-manifest.json` at the repo's
 *    top-level corpus, recording the manifest hash plus per-export and
 *    per-node hashes. `PreludeModuleConformanceTest` asserts regeneration
 *    reproduces the pinned hashes, so the reserved-spec table cannot drift
 *    from the published module silently (the structural fix for the
 *    2026-06-11 projection-arity defect class).
 *
 * Regeneration (when the reserved table legitimately changes): from
 * `impl-kotlin/`,
 *
 *     .\gradlew.bat :corpus:test --tests "org.strand.corpus.PreludeModuleConformanceTest" -Dstrand.regeneratePreludeModule=true
 *
 * then rerun without the flag to assert the fresh artifacts.
 *
 * The Kotlin reserved-spec table remains the single source of truth in this
 * slice (proposal § 7's deferred source-of-truth inversion); the generator
 * is deterministic — table iteration order is preserved, the manifest is
 * appended last, and the document is serialized with a stable pretty-printer.
 */
object PreludeModuleGenerator {

    /** Author id of the ModuleManifest root in the generated document. */
    const val MANIFEST_AUTHOR_ID = "__preludeManifest"

    private val printer = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * The dag-json object for one reserved spec. This is the exact JSON shape
     * the legacy [DagJsonEmitter] synthesis path inlined per program — the
     * emitter's `synthesizeReserved` delegates here, which is what makes the
     * Q-063 byte-identity invariant (resolved node bytes == synthesized node
     * bytes) hold by construction.
     */
    fun reservedNodeJson(spec: LayerAGrammar.ReservedNodeSpec): JsonObject {
        val fields = mutableMapOf<String, JsonElement>()
        fields["type"] = JsonPrimitive(spec.jsonType)
        for ((k, v) in spec.stringFields) {
            fields[k] = JsonPrimitive(v)
        }
        for ((k, v) in spec.refFields) {
            fields[k] = JsonPrimitive(v)
        }
        for ((k, v) in spec.refListFields) {
            fields[k] = JsonArray(v.map { JsonPrimitive(it) })
        }
        // Q-039: emit effectProjections when non-empty. Each projection
        // is an object with `category` and `sources`; each source is
        // either {kind:"ArgRef", index:N} or {kind:"LiteralNode", target:"<id>"}.
        // Matches the schema in [JsonIngest.optionalEffectProjections].
        if (spec.effectProjections.isNotEmpty()) {
            val projections = spec.effectProjections.map { proj ->
                val sources = proj.sources.map { src ->
                    when (src) {
                        is LayerAGrammar.ReservedProjectionSource.ArgRef -> JsonObject(mapOf(
                            "kind" to JsonPrimitive("ArgRef"),
                            "index" to JsonPrimitive(src.index),
                        ))
                        is LayerAGrammar.ReservedProjectionSource.LiteralNode -> JsonObject(mapOf(
                            "kind" to JsonPrimitive("LiteralNode"),
                            "target" to JsonPrimitive(src.target),
                        ))
                    }
                }
                JsonObject(mapOf(
                    "category" to JsonPrimitive(proj.category),
                    "sources" to JsonArray(sources),
                ))
            }
            fields["effectProjections"] = JsonArray(projections)
        }
        return JsonObject(fields)
    }

    /**
     * The reserved names that become [org.strand.core.ManifestExport]s:
     * exactly the ForeignNode entries (expression-level, admissible under the
     * existing N-046 certification). Iteration order matches [specs].
     */
    fun exportNames(
        specs: Map<String, LayerAGrammar.ReservedNodeSpec> = LayerAGrammar.reservedNodes,
    ): List<String> = specs.filterValues { it.jsonType == "ForeignNode" }.keys.toList()

    /**
     * Build the full prelude module document: every reserved node plus the
     * `ModuleManifest` root. Deterministic — table order, manifest last.
     */
    fun generateDocument(
        specs: Map<String, LayerAGrammar.ReservedNodeSpec> = LayerAGrammar.reservedNodes,
    ): JsonObject {
        require(!specs.containsKey(MANIFEST_AUTHOR_ID)) {
            "reserved-spec table must not declare the manifest author id '$MANIFEST_AUTHOR_ID'"
        }
        val nodesObj = buildJsonObject {
            for ((id, spec) in specs) {
                put(id, reservedNodeJson(spec))
            }
            put(MANIFEST_AUTHOR_ID, manifestJson(specs))
        }
        return buildJsonObject {
            put("version", 1)
            put("root", MANIFEST_AUTHOR_ID)
            put("nodes", nodesObj)
        }
    }

    /**
     * The N-046 ModuleManifest node: one export per ForeignNode entry, with
     * `displayName` set to the reserved name and `declaredEffects` set to the
     * ForeignNode's declared effect list — which, for the prelude's
     * ForeignNodes (whose FunctionTypes carry no effects of their own), is
     * exactly the effect surface `Verifier.checkManifests` certifies against.
     */
    private fun manifestJson(specs: Map<String, LayerAGrammar.ReservedNodeSpec>): JsonObject {
        val exports = exportNames(specs).map { name ->
            val spec = specs.getValue(name)
            buildJsonObject {
                put("target", name)
                put("declaredEffects", JsonArray(
                    (spec.refListFields["effects"] ?: emptyList()).map { JsonPrimitive(it) }
                ))
                put("displayName", name)
            }
        }
        require(exports.isNotEmpty()) { "prelude module must export at least one ForeignNode" }
        return buildJsonObject {
            put("type", "ModuleManifest")
            put("exports", JsonArray(exports))
        }
    }

    /** Serialize [generateDocument] deterministically (LF, trailing newline). */
    fun documentText(
        specs: Map<String, LayerAGrammar.ReservedNodeSpec> = LayerAGrammar.reservedNodes,
    ): String = printer.encodeToString(JsonObject.serializer(), generateDocument(specs)) + "\n"

    /**
     * A generated prelude module with its content hashes computed: the
     * manifest (root) hash, the per-export hashes (manifest exports — the
     * ForeignNode entries), and the per-node hashes for every reserved name.
     */
    data class GeneratedPreludeModule(
        val document: JsonObject,
        val text: String,
        val manifestHash: Hash,
        val exportHashes: Map<String, Hash>,
        val nodeHashes: Map<String, Hash>,
    )

    /** Generate the module document and compute every pinned hash. */
    fun generate(
        specs: Map<String, LayerAGrammar.ReservedNodeSpec> = LayerAGrammar.reservedNodes,
    ): GeneratedPreludeModule {
        val document = generateDocument(specs)
        val text = printer.encodeToString(JsonObject.serializer(), document) + "\n"
        val ingest = JsonIngest.parse(text)
        val hasher = Hasher(ingest.rawStore)
        val finalized = hasher.finalize(ingest.root)
        val nodeHashes = specs.keys.associateWith { name ->
            val nodeId = ingest.nameMap[name]
                ?: error("reserved name '$name' missing from the generated document's ingest")
            // Most reserved nodes are reachable from the manifest root (export
            // targets plus their signature/effect dependencies) and were hashed
            // by finalize. The handful that no export references — the
            // StateMachine effect categories and the N-047 ErrorPayload type
            // trio, which exist for sugar and runtime references — are hashed
            // standalone; the canonical encoding is context-independent, so
            // the standalone hash equals the hash in any reachable position.
            finalized.nodeIdToHash[nodeId]
                ?: hasher.hashReachable(nodeId)[nodeId]
                ?: error("reserved name '$name' produced no content hash")
        }
        val exportHashes = exportNames(specs).associateWith { nodeHashes.getValue(it) }
        val manifestHash = finalized.nodeIdToHash.getValue(finalized.root)
        return GeneratedPreludeModule(
            document = document,
            text = text,
            manifestHash = manifestHash,
            exportHashes = exportHashes,
            nodeHashes = nodeHashes,
        )
    }
}
