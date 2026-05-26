package org.strand.verifier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Primitive
import org.strand.hashing.Hasher

/**
 * N-045 ResponseSchemaSpec verifier rules (proposal § 3.7).
 *
 * Symmetric to [ToolDefVerifierTest] but covering the response-side
 * Schema wrapper rather than the tool-input side. Both N-044 ToolDef
 * and N-045 ResponseSchemaSpec apply the same
 * [JsonSchemaProjection.project] check on their inner Schema's
 * `valueType` at admission — this test confirms the
 * `ResponseSchemaSpec` rule fires equivalently on the four cases that
 * exercise it: positive (Primitive / Product / Recursive valueTypes),
 * negative (`FunctionType` / `ForallType` raise
 * `ResponseSchemaTypeUnsupported`), and the `schema` edge pointing at
 * a non-Schema node raises `CategoryMismatch`.
 */
class ResponseSchemaSpecVerifierTest {

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    @Test
    fun `ResponseSchemaSpec with Int valueType is accepted`() {
        val r = verify("""{
          "version": 1, "root": "spec",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "schema":  { "type": "Schema", "schemaName": "IntResponse",
                         "valueType": "intT", "invariants": [] },
            "spec":    { "type": "ResponseSchemaSpec", "schema": "schema" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok, "expected Ok, got $r")
        assertEquals(TypeExpr.Prim(Primitive.Bytes), (r as VerifyResult.Ok).rootType)
    }

    @Test
    fun `ResponseSchemaSpec with Product valueType is accepted`() {
        val r = verify("""{
          "version": 1, "root": "spec",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "strT":    { "type": "PrimitiveType", "kind": "String" },
            "xFldT":   { "type": "ProductTypeField", "name": "x", "fieldType": "intT" },
            "yFldT":   { "type": "ProductTypeField", "name": "y", "fieldType": "intT" },
            "pointT":  { "type": "ProductType", "fields": ["xFldT", "yFldT"] },
            "schema":  { "type": "Schema", "schemaName": "Point",
                         "valueType": "pointT", "invariants": [] },
            "spec":    { "type": "ResponseSchemaSpec", "schema": "schema" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok, "expected Ok, got $r")
    }

    @Test
    fun `ResponseSchemaSpec with Recursive list valueType is accepted via dollar-defs projection`() {
        // List of Int = μ. Nil | Cons({head: Int, tail: self})
        val r = verify("""{
          "version": 1, "root": "spec",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "selfRef":   { "type": "RecursiveSelf" },
            "headFldT":  { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tailFldT":  { "type": "ProductTypeField", "name": "tail", "fieldType": "selfRef" },
            "consPayT":  { "type": "ProductType", "fields": ["headFldT", "tailFldT"] },
            "consCase":  { "type": "SumTypeCase", "name": "Cons", "caseType": "consPayT" },
            "nilCase":   { "type": "SumTypeCase", "name": "Nil", "caseType": null },
            "listBody":  { "type": "SumType", "cases": ["consCase", "nilCase"] },
            "listT":     { "type": "RecursiveType", "body": "listBody" },
            "schema":    { "type": "Schema", "schemaName": "IntList",
                           "valueType": "listT", "invariants": [] },
            "spec":      { "type": "ResponseSchemaSpec", "schema": "schema" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok, "expected Ok, got $r")
    }

    @Test
    fun `ResponseSchemaSpec with FunctionType valueType raises ResponseSchemaTypeUnsupported`() {
        // The schema's valueType is `(Int) -> Int`, which JsonSchemaProjection rejects.
        val r = verify("""{
          "version": 1, "root": "spec",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "badT":    { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "schema":  { "type": "Schema", "schemaName": "BadFn",
                         "valueType": "badT", "invariants": [] },
            "spec":    { "type": "ResponseSchemaSpec", "schema": "schema" }
          }
        }""")
        val f = r as VerifyResult.Failed
        val unsupported = f.errors.filterIsInstance<VerifyError.ResponseSchemaTypeUnsupported>()
        assertTrue(unsupported.isNotEmpty(), "expected ResponseSchemaTypeUnsupported, got ${f.errors}")
        assertEquals("FunctionType", unsupported[0].reason)
    }

    @Test
    fun `ResponseSchemaSpec with ForallType valueType raises ResponseSchemaTypeUnsupported`() {
        val r = verify("""{
          "version": 1, "root": "spec",
          "nodes": {
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "polyT":   { "type": "ForallType", "typeParameters": ["T_a"], "body": "T_a" },
            "schema":  { "type": "Schema", "schemaName": "BadForall",
                         "valueType": "polyT", "invariants": [] },
            "spec":    { "type": "ResponseSchemaSpec", "schema": "schema" }
          }
        }""")
        val f = r as VerifyResult.Failed
        val unsupported = f.errors.filterIsInstance<VerifyError.ResponseSchemaTypeUnsupported>()
        assertTrue(unsupported.isNotEmpty(), "expected ResponseSchemaTypeUnsupported, got ${f.errors}")
        assertEquals("ForallType", unsupported[0].reason)
    }

    @Test
    fun `ResponseSchemaSpec whose schema edge points at a non-Schema raises CategoryMismatch`() {
        // schema points directly at a ProductType, not a Schema.
        val r = verify("""{
          "version": 1, "root": "spec",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "fldT":    { "type": "ProductTypeField", "name": "x", "fieldType": "intT" },
            "prdT":    { "type": "ProductType", "fields": ["fldT"] },
            "spec":    { "type": "ResponseSchemaSpec", "schema": "prdT" }
          }
        }""")
        val f = r as VerifyResult.Failed
        val mismatch = f.errors.filterIsInstance<VerifyError.CategoryMismatch>()
        assertTrue(mismatch.any { it.expectedCategory == "Schema" },
            "expected CategoryMismatch with expectedCategory=Schema, got ${f.errors}")
    }
}
