package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.JsonIngest

/**
 * N-046 ModuleManifest canonical-encoding properties (Q-043 § 4.4).
 *
 * Hashing runs before verification, so the declared effects in these fixtures
 * need not match any target's effect surface — the suite exercises the
 * canonical ENCODING (the raw→canonical finalize bridge plus the dual
 * encoder paths), not the verifier admission rule.
 *
 * Properties asserted:
 *  - deterministic across independent ingests,
 *  - `displayName` and `manifestSignature` are metadata, excluded from the hash,
 *  - each export's `declaredEffects` is a set (order does not affect the hash),
 *  - the `exports` list is positional (reordering changes the hash).
 */
class ModuleManifestEncodingTest {

    private fun rootHash(json: String): Hash {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return finalized.nodeIdToHash.getValue(finalized.root)
    }

    /** A one-export manifest over a pure identity Lambda. */
    private fun oneExport(
        declaredEffects: String,
        displayName: String,
        signatureField: String = "",
    ): String = """{
      "version": 1, "root": "lib",
      "nodes": {
        "intT": { "type": "PrimitiveType", "kind": "Int" },
        "fxA":  { "type": "EffectCategory", "categoryName": "A.Read",  "parameters": [] },
        "fxB":  { "type": "EffectCategory", "categoryName": "B.Write", "parameters": [] },
        "p":    { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
        "b":    { "type": "VarRef", "binder": "p" },
        "fn":   { "type": "Lambda", "parameters": ["p"], "body": "b" },
        "lib":  { "type": "ModuleManifest"$signatureField, "exports": [
          { "target": "fn", "declaredEffects": $declaredEffects, "displayName": "$displayName" }
        ] }
      }
    }"""

    @Test
    fun `manifest hashes deterministically across independent ingests`() {
        val json = oneExport("[\"fxA\", \"fxB\"]", "List.helpers")
        assertEquals(rootHash(json), rootHash(json))
    }

    @Test
    fun `displayName is excluded from the manifest hash`() {
        val a = rootHash(oneExport("[\"fxA\"]", "First.Name"))
        val b = rootHash(oneExport("[\"fxA\"]", "Totally.Different"))
        assertEquals(a, b, "displayName is metadata and must not affect the hash")
    }

    @Test
    fun `manifestSignature is excluded from the manifest hash`() {
        val withSig = rootHash(oneExport("[\"fxA\"]", "n", ", \"manifestSignature\": \"deadbeef\""))
        val withoutSig = rootHash(oneExport("[\"fxA\"]", "n"))
        assertEquals(withSig, withoutSig, "manifestSignature is metadata and must not affect the hash")
    }

    @Test
    fun `declaredEffects order within an export does not affect the hash`() {
        val ab = rootHash(oneExport("[\"fxA\", \"fxB\"]", "n"))
        val ba = rootHash(oneExport("[\"fxB\", \"fxA\"]", "n"))
        assertEquals(ab, ba, "declaredEffects is a set — declaration order must not affect the hash")
    }

    @Test
    fun `export list order is significant for the manifest hash`() {
        // Two structurally-distinct exports (different parameter types so the
        // Lambdas hash differently); swapping their positions must change the
        // manifest hash because the export list is positionally ordered.
        fun twoExports(first: String, second: String) = """{
          "version": 1, "root": "lib",
          "nodes": {
            "intT": { "type": "PrimitiveType", "kind": "Int" },
            "strT": { "type": "PrimitiveType", "kind": "String" },
            "pI":   { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "bI":   { "type": "VarRef", "binder": "pI" },
            "fnI":  { "type": "Lambda", "parameters": ["pI"], "body": "bI" },
            "pS":   { "type": "ParameterDecl", "name": "x", "paramType": "strT" },
            "bS":   { "type": "VarRef", "binder": "pS" },
            "fnS":  { "type": "Lambda", "parameters": ["pS"], "body": "bS" },
            "lib":  { "type": "ModuleManifest", "exports": [ $first, $second ] }
          }
        }"""
        val expI = """{ "target": "fnI", "declaredEffects": [], "displayName": "i" }"""
        val expS = """{ "target": "fnS", "declaredEffects": [], "displayName": "s" }"""
        assertNotEquals(
            rootHash(twoExports(expI, expS)),
            rootHash(twoExports(expS, expI)),
            "export list is positional — reordering must change the hash",
        )
    }
}
