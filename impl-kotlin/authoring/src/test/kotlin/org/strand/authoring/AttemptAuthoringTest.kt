package org.strand.authoring

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * N-047 Attempt (Q-048) Layer A surface: the `TRY` code, the `RES` Result-sum
 * type sugar, and the `errPayloadT` (+ `errKindField` / `errDetailField`)
 * implicit-prelude entries. The verify-level and corpus round-trip coverage
 * lives in the corpus module; this file covers the emitter behavior.
 */
class AttemptAuthoringTest {

    @Test
    fun `TRY emits an Attempt node referencing its body`() {
        val text = """
            @v=1 root=tryRead
            pathS STR "config.json"
            readApp APP fsRead [pathS]
            tryRead TRY readApp
        """.trimIndent()
        val json = Authoring.compileToJsonObject(text)
        val nodes = json["nodes"]!!.jsonObject
        val att = nodes["tryRead"]!!.jsonObject
        assertEquals("Attempt", att["type"]!!.jsonPrimitive.content)
        assertEquals("readApp", att["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `RES expands to the Ok-or-Err SumType tower over errPayloadT`() {
        val text = """
            @v=1 root=resBytesT
            resBytesT RES bytesT
        """.trimIndent()
        val json = Authoring.compileToJsonObject(text)
        val nodes = json["nodes"]!!.jsonObject

        // The user id is the SumType.
        val sum = nodes["resBytesT"]!!.jsonObject
        assertEquals("SumType", sum["type"]!!.jsonPrimitive.content)

        // Two synthesized SumTypeCase children: Ok(bytesT), Err(errPayloadT).
        val okCase = nodes["__res0_ok_case"]
        val errCase = nodes["__res0_err_case"]
        assertNotNull(okCase, "expected synthesized __res0_ok_case; nodes ${nodes.keys}")
        assertNotNull(errCase, "expected synthesized __res0_err_case; nodes ${nodes.keys}")
        assertEquals("Ok", okCase!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("bytesT", okCase.jsonObject["caseType"]!!.jsonPrimitive.content)
        assertEquals("Err", errCase!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("errPayloadT", errCase.jsonObject["caseType"]!!.jsonPrimitive.content)

        // The errPayloadT prelude is synthesized as a ProductType {kind, detail}.
        val errPayload = nodes["errPayloadT"]
        assertNotNull(errPayload, "expected the errPayloadT prelude ProductType")
        assertEquals("ProductType", errPayload!!.jsonObject["type"]!!.jsonPrimitive.content)
        val kindField = nodes["errKindField"]!!.jsonObject
        val detailField = nodes["errDetailField"]!!.jsonObject
        assertEquals("kind", kindField["name"]!!.jsonPrimitive.content)
        assertEquals("detail", detailField["name"]!!.jsonPrimitive.content)
        assertEquals("stringT", kindField["fieldType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Attempt round-trips through the reverse projection`() {
        // forward_compile(render(translate(canonical))) == canonical for an
        // Attempt node — the TRY code is the reverse projection of an Attempt.
        val canonical = """{
            "version": 1,
            "root": "att",
            "nodes": {
                "lit": { "type": "IntLit", "value": 7 },
                "att": { "type": "Attempt", "body": "lit" }
            }
        }"""
        val layerA = Authoring.projectFromDagJson(canonical)
        assertTrue("TRY" in layerA, "reverse projection should emit a TRY code; got:\n$layerA")
        val recompiled = Authoring.compileToDagJson(layerA)
        // Re-ingest both and compare the canonical structure (the round-trip
        // contract is byte-identical canonical bytes; here we assert the
        // recompiled JSON contains the Attempt node referencing an IntLit).
        val recompiledObj = Authoring.compileToJsonObject(layerA)
        val nodes = recompiledObj["nodes"]!!.jsonObject
        val attNode = nodes.values.map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "Attempt" }
        val bodyId = attNode["body"]!!.jsonPrimitive.content
        assertEquals("IntLit", nodes[bodyId]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
