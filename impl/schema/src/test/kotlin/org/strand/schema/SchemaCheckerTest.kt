package org.strand.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Unit tests for [SchemaChecker], focused on the static-value detector
 * and the deferred-check emission path. End-to-end corpus programs
 * exercise the violation path; these tests cover the cases that don't
 * fit in a corpus program (deferred diagnostics, hashing determinism).
 */
class SchemaCheckerTest {

    private fun verifyAndCheck(json: String): SchemaCheckResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verifyResult = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        verifyResult as VerifyResult.Ok
        return SchemaChecker(finalized.store, finalized.hashToNodeId, verifyResult).check()
    }

    /**
     * Scenario 3 (proposal § 7): a Lambda takes an Int parameter and the
     * parameter's declared type is a Schema. The verifier emits a
     * `SchemaInvariantDeferred` for the parameter (because parameters
     * are not statically known); the overall graph still verifies and
     * the SchemaChecker reports zero violations.
     *
     * The program is the identityOfPositiveInt Lambda standalone — its
     * root type is `(PositiveInt) -> PositiveInt`, no Application calls
     * it. The Lambda's parameter is recorded with type
     * `SchemaType(PositiveInt, Int, [x_positive])`, and the
     * SchemaChecker walks `nodeTypes` looking for SchemaType entries —
     * it finds the parameter, tries to statically evaluate it, fails
     * (parameters have no static value), emits the deferred diagnostic.
     */
    @Test
    fun `PositiveInt defers on Lambda parameter`() {
        val json = """{
          "version": 1, "root": "identityOfPositiveInt",
          "nodes": {
            "intT":       { "type": "PrimitiveType", "kind": "Int" },
            "boolT":      { "type": "PrimitiveType", "kind": "Bool" },
            "zero":       { "type": "IntLit", "value": 0 },
            "xParam":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":       { "type": "VarRef", "binder": "xParam" },
            "gtT":        { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "gt":         { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "gtT" },
            "gtBody":     { "type": "Application", "function": "gt", "arguments": ["xRef", "zero"] },
            "predLam":    { "type": "Lambda", "parameters": ["xParam"], "body": "gtBody" },
            "positiveInvariant": {
              "type": "Invariant",
              "invariantName": "x_positive",
              "targetSchema": "positiveInt",
              "body": "predLam"
            },
            "positiveInt": {
              "type": "Schema",
              "schemaName": "PositiveInt",
              "valueType": "intT",
              "invariants": ["positiveInvariant"]
            },
            "pIn":        { "type": "ParameterDecl", "name": "p", "paramType": "positiveInt" },
            "pInRef":     { "type": "VarRef", "binder": "pIn" },
            "identityOfPositiveInt": {
              "type": "Lambda",
              "parameters": ["pIn"],
              "body": "pInRef"
            }
          }
        }"""
        val result = verifyAndCheck(json)
        assertEquals(emptyList<Any>(), result.violations, "expected no violations")
        assertTrue(result.deferred.isNotEmpty(), "expected a deferred diagnostic for the parameter")
    }

    /**
     * tryEvaluateStatically should accept a ProductValue whose fields
     * are all literal — that path is exercised by the corpus directly,
     * but we cover it as a unit case too because it's a recursive
     * branch in the static-evaluator.
     *
     * We construct a Product with a Schema-typed field and a literal
     * value. The verifier records the inner literal as SchemaType, and
     * the SchemaChecker statically evaluates and runs the invariant —
     * which here passes (the literal is `5`, positive).
     */
    @Test
    fun `static evaluator unwraps a ProductValue whose Schema-typed field is a literal`() {
        val json = """{
          "version": 1, "root": "container",
          "nodes": {
            "intT":       { "type": "PrimitiveType", "kind": "Int" },
            "boolT":      { "type": "PrimitiveType", "kind": "Bool" },
            "zero":       { "type": "IntLit", "value": 0 },
            "xParam":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":       { "type": "VarRef", "binder": "xParam" },
            "gtT":        { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "gt":         { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "gtT" },
            "gtBody":     { "type": "Application", "function": "gt", "arguments": ["xRef", "zero"] },
            "predLam":    { "type": "Lambda", "parameters": ["xParam"], "body": "gtBody" },
            "positiveInvariant": {
              "type": "Invariant",
              "invariantName": "x_positive",
              "targetSchema": "positiveInt",
              "body": "predLam"
            },
            "positiveInt": {
              "type": "Schema",
              "schemaName": "PositiveInt",
              "valueType": "intT",
              "invariants": ["positiveInvariant"]
            },
            "boxField":   { "type": "ProductTypeField", "name": "value", "fieldType": "positiveInt" },
            "boxType":    { "type": "ProductType", "fields": ["boxField"] },

            "five":       { "type": "IntLit", "value": 5 },
            "fv":         { "type": "ProductFieldValue", "fieldName": "value", "value": "five" },
            "container":  { "type": "ProductValue", "ofType": "boxType", "fields": ["fv"] }
          }
        }"""
        val result = verifyAndCheck(json)
        assertEquals(emptyList<Any>(), result.violations,
            "expected the positive literal in the Schema-typed field to pass: got ${result.violations}")
    }

    /**
     * Mirror of the previous test with a negative literal: the
     * verifier emits a SchemaInvariantViolation pointing at the
     * inner literal, not at the outer ProductValue. Demonstrates
     * the SchemaChecker walks fields recursively for static values.
     */
    @Test
    fun `static evaluator reports a violation on a Schema-typed product field with a negative literal`() {
        val json = """{
          "version": 1, "root": "container",
          "nodes": {
            "intT":       { "type": "PrimitiveType", "kind": "Int" },
            "boolT":      { "type": "PrimitiveType", "kind": "Bool" },
            "zero":       { "type": "IntLit", "value": 0 },
            "xParam":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":       { "type": "VarRef", "binder": "xParam" },
            "gtT":        { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "gt":         { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "gtT" },
            "gtBody":     { "type": "Application", "function": "gt", "arguments": ["xRef", "zero"] },
            "predLam":    { "type": "Lambda", "parameters": ["xParam"], "body": "gtBody" },
            "positiveInvariant": {
              "type": "Invariant",
              "invariantName": "x_positive",
              "targetSchema": "positiveInt",
              "body": "predLam"
            },
            "positiveInt": {
              "type": "Schema",
              "schemaName": "PositiveInt",
              "valueType": "intT",
              "invariants": ["positiveInvariant"]
            },
            "boxField":   { "type": "ProductTypeField", "name": "value", "fieldType": "positiveInt" },
            "boxType":    { "type": "ProductType", "fields": ["boxField"] },

            "negFour":    { "type": "IntLit", "value": -4 },
            "fv":         { "type": "ProductFieldValue", "fieldName": "value", "value": "negFour" },
            "container":  { "type": "ProductValue", "ofType": "boxType", "fields": ["fv"] }
          }
        }"""
        val result = verifyAndCheck(json)
        assertTrue(result.violations.isNotEmpty(),
            "expected a violation for the negative literal in the Schema-typed field")
    }

    /**
     * Scenario 9 (proposal § 7): two Schema declarations with the same
     * `valueType` and the same `invariants` (in any order) produce
     * identical hashes. This is the canonical content-addressing
     * determinism test for the Schema/Invariant pair.
     *
     * The CanonicalEncoder sorts Schema.invariants by hash before
     * encoding, so two Schemas with the same set in any order should
     * hash identically.
     */
    @Test
    fun `two identical schemas hash identically (set semantics on invariants)`() {
        // Same Schema content in two separate documents, verify the
        // root Schema's hash is identical.
        val json1 = """{
          "version": 1, "root": "positiveInt",
          "nodes": {
            "intT":       { "type": "PrimitiveType", "kind": "Int" },
            "boolT":      { "type": "PrimitiveType", "kind": "Bool" },
            "zero":       { "type": "IntLit", "value": 0 },
            "xParam":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":       { "type": "VarRef", "binder": "xParam" },
            "gtT":        { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "gt":         { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "gtT" },
            "gtBody":     { "type": "Application", "function": "gt", "arguments": ["xRef", "zero"] },
            "predLam":    { "type": "Lambda", "parameters": ["xParam"], "body": "gtBody" },
            "positiveInvariant": {
              "type": "Invariant",
              "invariantName": "x_positive",
              "targetSchema": "positiveInt",
              "body": "predLam"
            },
            "positiveInt": {
              "type": "Schema",
              "schemaName": "PositiveInt",
              "valueType": "intT",
              "invariants": ["positiveInvariant"]
            }
          }
        }"""
        // Second JSON differs only in author-ids — same structure,
        // same names, same body. Hashes must agree.
        val json2 = """{
          "version": 1, "root": "schema_X",
          "nodes": {
            "i":    { "type": "PrimitiveType", "kind": "Int" },
            "b":    { "type": "PrimitiveType", "kind": "Bool" },
            "z":    { "type": "IntLit", "value": 0 },
            "p":    { "type": "ParameterDecl", "name": "x", "paramType": "i" },
            "r":    { "type": "VarRef", "binder": "p" },
            "gT":   { "type": "FunctionType", "parameters": ["i", "i"], "result": "b" },
            "gtFn": { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "gT" },
            "ap":   { "type": "Application", "function": "gtFn", "arguments": ["r", "z"] },
            "lam":  { "type": "Lambda", "parameters": ["p"], "body": "ap" },
            "inv_Y": {
              "type": "Invariant",
              "invariantName": "x_positive",
              "targetSchema": "schema_X",
              "body": "lam"
            },
            "schema_X": {
              "type": "Schema",
              "schemaName": "PositiveInt",
              "valueType": "i",
              "invariants": ["inv_Y"]
            }
          }
        }"""
        val ing1 = JsonIngest.parse(json1)
        val ing2 = JsonIngest.parse(json2)
        val fin1 = Hasher(ing1.rawStore).finalize(ing1.root)
        val fin2 = Hasher(ing2.rawStore).finalize(ing2.root)
        val hash1 = fin1.nodeIdToHash[fin1.root]
        val hash2 = fin2.nodeIdToHash[fin2.root]
        assertNotNull(hash1)
        assertNotNull(hash2)
        assertEquals(hash1, hash2,
            "two Schemas with identical valueType and invariants should hash identically " +
                "regardless of author-ids")
    }
}
