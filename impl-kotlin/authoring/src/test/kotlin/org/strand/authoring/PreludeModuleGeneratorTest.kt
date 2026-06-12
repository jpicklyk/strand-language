package org.strand.authoring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.VerifyError
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-063 scenarios 7 and 8 — the generated prelude module's certification and
 * evolution properties.
 *
 * Scenario 7 (manifest certification): the module is admitted under the
 * existing N-046 rules, so tampering with a generated export's
 * `declaredEffects` fails admission with
 * [VerifyError.ManifestExportEffectMismatch] — the prelude is a certified
 * artifact, not a trusted table.
 *
 * Scenario 8 (evolution by hash): adding a builtin to the reserved table
 * produces a new manifest hash while every pre-existing export hash is
 * unchanged — programs reference export node hashes, not the manifest, so a
 * stdlib round leaves them untouched; only the registry's `prelude` name
 * advances.
 */
class PreludeModuleGeneratorTest {

    private val printer = Json { prettyPrint = false }

    @Test
    fun `tampering with a generated export's declaredEffects fails N-046 admission`() {
        val document = PreludeModuleGenerator.generateDocument()
        val nodes = document["nodes"]!!.jsonObject

        // Strip the effectful fsWrite export's declared effects (under-declaration).
        val manifest = nodes[PreludeModuleGenerator.MANIFEST_AUTHOR_ID]!!.jsonObject
        val tamperedExports = manifest["exports"]!!.jsonArray.map { export ->
            val obj = export.jsonObject
            if (obj["displayName"]!!.jsonPrimitive.content == "fsWrite") {
                JsonObject(obj + ("declaredEffects" to JsonArray(emptyList())))
            } else {
                obj
            }
        }
        val tamperedManifest = JsonObject(manifest + ("exports" to JsonArray(tamperedExports)))
        val tamperedDocument = buildJsonObject {
            put("version", 1)
            put("root", PreludeModuleGenerator.MANIFEST_AUTHOR_ID)
            put("nodes", JsonObject(nodes + (PreludeModuleGenerator.MANIFEST_AUTHOR_ID to tamperedManifest)))
        }

        val text = printer.encodeToString(JsonObject.serializer(), tamperedDocument)
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val result = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)

        val failed = result as? VerifyResult.Failed
            ?: error("expected the tampered prelude manifest to fail admission, got $result")
        assertTrue(failed.errors.any { it is VerifyError.ManifestExportEffectMismatch }) {
            "expected ManifestExportEffectMismatch among the errors, got ${failed.errors}"
        }
    }

    @Test
    fun `adding a synthetic builtin yields a new manifest hash with pre-existing export hashes unchanged`() {
        val baseline = PreludeModuleGenerator.generate()

        val extended = LinkedHashMap(LayerAGrammar.reservedNodes)
        extended["testSyntheticBuiltinT"] = LayerAGrammar.ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "intT"),
        )
        extended["testSyntheticBuiltin"] = LayerAGrammar.ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Test.Synthetic"),
            refFields = mapOf("foreignType" to "testSyntheticBuiltinT"),
        )
        val evolved = PreludeModuleGenerator.generate(extended)

        assertNotEquals(baseline.manifestHash, evolved.manifestHash) {
            "adding an export must advance the manifest hash"
        }
        assertTrue("testSyntheticBuiltin" in evolved.exportHashes) {
            "the synthetic builtin must be exported by the evolved manifest"
        }
        for ((name, hash) in baseline.exportHashes) {
            assertEquals(hash, evolved.exportHashes[name]) {
                "pre-existing export '$name' hash must be unchanged by prelude evolution"
            }
        }
        for ((name, hash) in baseline.nodeHashes) {
            assertEquals(hash, evolved.nodeHashes[name]) {
                "pre-existing reserved node '$name' hash must be unchanged by prelude evolution"
            }
        }
    }
}
