package org.strand.authoring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher

/**
 * Q-063 scenario 2 (per-name half) — the load-bearing byte-identity
 * invariant: for every reserved name, the node resolved through the bundled
 * prelude module hashes identically to the node the legacy synthesis path
 * produced. This is what makes resolution-replacing-synthesis a zero-impact
 * change for program hashes (the density-fixture half of the scenario lives
 * in `org.strand.corpus.PreludeResolutionDensityEquivalenceTest`).
 *
 * Method: for each reserved name, build a minimal dag-json document rooted
 * at that name containing its transitive reserved closure, once with nodes
 * synthesized from the in-memory table ([PreludeModuleGenerator.reservedNodeJson],
 * the legacy path's shaping) and once with nodes resolved from the bundled
 * snapshot ([PreludeModule.nodeJson]); ingest + finalize both and require
 * equal root hashes. The resolved hash is also triangulated against the
 * loader's own per-name hash (computed from the snapshot at load).
 */
class PreludeResolutionEquivalenceTest {

    private val printer = Json { prettyPrint = false }

    @TestFactory
    fun `every reserved name resolves hash-identically to legacy synthesis`(): List<DynamicTest> =
        LayerAGrammar.reservedNodes.keys.map { name ->
            DynamicTest.dynamicTest(name) {
                val legacyHash = rootHash(miniDocument(name) { id ->
                    PreludeModuleGenerator.reservedNodeJson(LayerAGrammar.reservedNodes.getValue(id))
                })
                val resolvedHash = rootHash(miniDocument(name) { id ->
                    PreludeModule.nodeJson(id)
                })
                assertEquals(legacyHash, resolvedHash) {
                    "reserved '$name': module-resolved node hash diverges from the " +
                        "legacy-synthesized node hash — the byte-identity invariant is violated"
                }
                assertEquals(PreludeModule.loaded.nodeHashes.getValue(name), resolvedHash) {
                    "reserved '$name': mini-document hash diverges from the loader's pinned hash"
                }
            }
        }

    @TestFactory
    fun `every manifest export resolves through the manifest to the same hash`(): List<DynamicTest> =
        PreludeModule.loaded.exportHashes.keys.map { name ->
            DynamicTest.dynamicTest(name) {
                assertTrue(name in LayerAGrammar.reservedNodes) {
                    "manifest export '$name' is not a reserved name"
                }
                assertEquals(
                    PreludeModule.loaded.nodeHashes.getValue(name),
                    PreludeModule.loaded.exportHashes.getValue(name),
                ) {
                    "export '$name': manifest displayName→target resolution diverges from the " +
                        "author-id resolution"
                }
            }
        }

    /** Minimal document rooted at [rootName] over its reserved transitive closure. */
    private fun miniDocument(rootName: String, nodeJson: (String) -> JsonObject): String {
        val closure = LinkedHashSet<String>()
        fun add(id: String) {
            if (!closure.add(id)) return
            for (dep in LayerAGrammar.reservedNodes.getValue(id).dependencies) add(dep)
        }
        add(rootName)
        val document = buildJsonObject {
            put("version", 1)
            put("root", rootName)
            put("nodes", buildJsonObject {
                for (id in closure) put(id, nodeJson(id))
            })
        }
        return printer.encodeToString(JsonObject.serializer(), document)
    }

    private fun rootHash(documentText: String): Hash {
        val ingest = JsonIngest.parse(documentText)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return finalized.nodeIdToHash.getValue(finalized.root)
    }
}
