package org.strand.verifier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Primitive
import org.strand.hashing.Hasher

/**
 * N-044 ToolDef verifier rules (proposal § 3.8 + § 3.8.1).
 *
 * Covers:
 *  - Positive: ToolDef with Primitive valueType accepted.
 *  - Positive: ToolDef with Product valueType accepted.
 *  - Positive: ToolDef with Recursive list valueType accepted (via $defs projection).
 *  - Negative: ToolDef with FunctionType valueType raises ToolParamTypeUnsupported.
 *  - Negative: ToolDef with ForallType valueType raises ToolParamTypeUnsupported.
 *  - Negative: ToolDef whose parameterSchema edge does NOT resolve to a Schema
 *    raises CategoryMismatch.
 *  - Negative: ToolDef whose implementation is not callable raises
 *    ToolImplementationNotAFunction.
 *  - Negative: ToolDef whose implementation has the wrong parameter type
 *    raises ToolImplementationParameterTypeMismatch.
 */
class ToolDefVerifierTest {

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    @Test
    fun `ToolDef with Int parameterSchema valueType is accepted`() {
        val r = verify("""{
          "version": 1, "root": "tool",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "strT":     { "type": "PrimitiveType", "kind": "String" },
            "schema":   { "type": "Schema", "schemaName": "IntInput",
                          "valueType": "intT", "invariants": [] },
            "param":    { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "body":     { "type": "StringLit", "value": "ok" },
            "impl":     { "type": "Lambda", "parameters": ["param"], "body": "body" },
            "tool":     { "type": "ToolDef", "name": "test", "description": "desc",
                          "parameterSchema": "schema", "implementation": "impl" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok, "expected Ok, got $r")
        assertEquals(TypeExpr.Prim(Primitive.Bytes), (r as VerifyResult.Ok).rootType)
    }

    @Test
    fun `ToolDef with Product parameterSchema valueType is accepted`() {
        val r = verify("""{
          "version": 1, "root": "tool",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "strT":     { "type": "PrimitiveType", "kind": "String" },
            "aFldT":    { "type": "ProductTypeField", "name": "a", "fieldType": "intT" },
            "bFldT":    { "type": "ProductTypeField", "name": "b", "fieldType": "strT" },
            "abT":      { "type": "ProductType", "fields": ["aFldT", "bFldT"] },
            "schema":   { "type": "Schema", "schemaName": "Pair",
                          "valueType": "abT", "invariants": [] },
            "param":    { "type": "ParameterDecl", "name": "p", "paramType": "abT" },
            "body":     { "type": "StringLit", "value": "ok" },
            "impl":     { "type": "Lambda", "parameters": ["param"], "body": "body" },
            "tool":     { "type": "ToolDef", "name": "test", "description": "desc",
                          "parameterSchema": "schema", "implementation": "impl" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok, "expected Ok, got $r")
    }

    @Test
    fun `ToolDef with Recursive list parameterSchema valueType is accepted via dollar-defs projection`() {
        // List of Int = μ. Nil | Cons({head: Int, tail: self})
        val r = verify("""{
          "version": 1, "root": "tool",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "strT":      { "type": "PrimitiveType", "kind": "String" },
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
            "param":     { "type": "ParameterDecl", "name": "p", "paramType": "listT" },
            "body":      { "type": "StringLit", "value": "ok" },
            "impl":      { "type": "Lambda", "parameters": ["param"], "body": "body" },
            "tool":      { "type": "ToolDef", "name": "sum-list", "description": "Sum a list of ints",
                           "parameterSchema": "schema", "implementation": "impl" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok, "expected Ok, got $r")
    }

    @Test
    fun `ToolDef with FunctionType parameterSchema valueType raises ToolParamTypeUnsupported`() {
        // The schema's valueType is `(Int) -> Int`, which JsonSchemaProjection rejects.
        val r = verify("""{
          "version": 1, "root": "tool",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "strT":     { "type": "PrimitiveType", "kind": "String" },
            "badT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "schema":   { "type": "Schema", "schemaName": "BadFn",
                          "valueType": "badT", "invariants": [] },
            "param":    { "type": "ParameterDecl", "name": "p", "paramType": "badT" },
            "body":     { "type": "StringLit", "value": "x" },
            "impl":     { "type": "Lambda", "parameters": ["param"], "body": "body" },
            "tool":     { "type": "ToolDef", "name": "bad", "description": "bad",
                          "parameterSchema": "schema", "implementation": "impl" }
          }
        }""")
        val f = r as VerifyResult.Failed
        val unsupported = f.errors.filterIsInstance<VerifyError.ToolParamTypeUnsupported>()
        assertTrue(unsupported.isNotEmpty(), "expected ToolParamTypeUnsupported, got ${f.errors}")
        assertEquals("FunctionType", unsupported[0].reason)
    }

    @Test
    fun `ToolDef with ForallType parameterSchema valueType raises ToolParamTypeUnsupported`() {
        val r = verify("""{
          "version": 1, "root": "tool",
          "nodes": {
            "T_a":      { "type": "TypeParameter", "name": "a" },
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "polyT":    { "type": "ForallType", "typeParameters": ["T_a"], "body": "T_a" },
            "schema":   { "type": "Schema", "schemaName": "BadForall",
                          "valueType": "polyT", "invariants": [] },
            "param":    { "type": "ParameterDecl", "name": "p", "paramType": "intT" },
            "body":     { "type": "StringLit", "value": "x" },
            "impl":     { "type": "Lambda", "parameters": ["param"], "body": "body" },
            "tool":     { "type": "ToolDef", "name": "bad", "description": "bad",
                          "parameterSchema": "schema", "implementation": "impl" }
          }
        }""")
        val f = r as VerifyResult.Failed
        val unsupported = f.errors.filterIsInstance<VerifyError.ToolParamTypeUnsupported>()
        assertTrue(unsupported.isNotEmpty(), "expected ToolParamTypeUnsupported, got ${f.errors}")
        assertEquals("ForallType", unsupported[0].reason)
    }

    @Test
    fun `ToolDef whose parameterSchema edge points at a non-Schema raises CategoryMismatch`() {
        // parameterSchema points directly at a ProductType, not a Schema.
        val r = verify("""{
          "version": 1, "root": "tool",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "strT":     { "type": "PrimitiveType", "kind": "String" },
            "fldT":     { "type": "ProductTypeField", "name": "x", "fieldType": "intT" },
            "prdT":     { "type": "ProductType", "fields": ["fldT"] },
            "param":    { "type": "ParameterDecl", "name": "p", "paramType": "prdT" },
            "body":     { "type": "StringLit", "value": "x" },
            "impl":     { "type": "Lambda", "parameters": ["param"], "body": "body" },
            "tool":     { "type": "ToolDef", "name": "bad", "description": "bad",
                          "parameterSchema": "prdT", "implementation": "impl" }
          }
        }""")
        val f = r as VerifyResult.Failed
        val mismatch = f.errors.filterIsInstance<VerifyError.CategoryMismatch>()
        assertTrue(mismatch.any { it.expectedCategory == "Schema" },
            "expected CategoryMismatch with expectedCategory=Schema, got ${f.errors}")
    }

    @Test
    fun `ToolDef whose implementation is a non-function raises ToolImplementationNotAFunction`() {
        // implementation is an IntLit, not a callable.
        val r = verify("""{
          "version": 1, "root": "tool",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "schema":   { "type": "Schema", "schemaName": "X",
                          "valueType": "intT", "invariants": [] },
            "lit":      { "type": "IntLit", "value": 42 },
            "tool":     { "type": "ToolDef", "name": "bad", "description": "bad",
                          "parameterSchema": "schema", "implementation": "lit" }
          }
        }""")
        val f = r as VerifyResult.Failed
        val nonFun = f.errors.filterIsInstance<VerifyError.ToolImplementationNotAFunction>()
        assertTrue(nonFun.isNotEmpty(),
            "expected ToolImplementationNotAFunction, got ${f.errors}")
    }

    @Test
    fun `ToolDef whose implementation parameter type does not match the schema raises ToolImplementationParameterTypeMismatch`() {
        // schema's valueType is Int but implementation takes a String.
        val r = verify("""{
          "version": 1, "root": "tool",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "strT":     { "type": "PrimitiveType", "kind": "String" },
            "schema":   { "type": "Schema", "schemaName": "IntInput",
                          "valueType": "intT", "invariants": [] },
            "param":    { "type": "ParameterDecl", "name": "p", "paramType": "strT" },
            "body":     { "type": "StringLit", "value": "x" },
            "impl":     { "type": "Lambda", "parameters": ["param"], "body": "body" },
            "tool":     { "type": "ToolDef", "name": "bad", "description": "bad",
                          "parameterSchema": "schema", "implementation": "impl" }
          }
        }""")
        val f = r as VerifyResult.Failed
        val mismatch = f.errors.filterIsInstance<VerifyError.ToolImplementationParameterTypeMismatch>()
        assertTrue(mismatch.isNotEmpty(),
            "expected ToolImplementationParameterTypeMismatch, got ${f.errors}")
        assertEquals(TypeExpr.Prim(Primitive.Int), mismatch[0].expected)
        assertEquals(TypeExpr.Prim(Primitive.String), mismatch[0].actual)
    }
}
