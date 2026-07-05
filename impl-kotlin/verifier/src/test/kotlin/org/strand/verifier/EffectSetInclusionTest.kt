package org.strand.verifier

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher

/**
 * Q-049: effect-set inclusion at the outermost arrow at value-flow sites, and
 * the hard rejection of a non-null `TypeParameter.bound`.
 *
 * The value-flow relaxation accepts a function-typed value whose effect set is
 * a *subset* of the expected function-typed position's effect set (parameters
 * and result stay strictly equal). The canonical friction case is a pure
 * lambda flowing into a callback parameter declared with an effect row. The
 * reverse (actual ⊃ expected) stays rejected, and nested arrows keep strict
 * equality this slice.
 */
class EffectSetInclusionTest {

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    @Test
    fun `pure lambda flows into an effectful callback parameter`() {
        // callee `apply` has parameter type `(Int) -> Int ! {someFx}`; the
        // argument is a pure `(Int) -> Int` lambda. Effect-set inclusion
        // accepts the pure argument (∅ ⊆ {someFx}). The callee's body does not
        // invoke the callback, so it declares no effects of its own.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "someFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "callbackT":{ "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": ["someFx"] },

            "cb":       { "type": "ParameterDecl", "name": "cb", "paramType": "callbackT" },
            "applyBody":{ "type": "IntLit", "value": 0 },
            "apply":    { "type": "Lambda", "parameters": ["cb"], "body": "applyBody", "effects": [] },

            "px":       { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "pxRef":    { "type": "VarRef", "binder": "px" },
            "pureCb":   { "type": "Lambda", "parameters": ["px"], "body": "pxRef", "effects": [] },

            "app":      { "type": "Application", "function": "apply", "arguments": ["pureCb"] }
          }
        }""")
        assertTrue(r is VerifyResult.Ok) {
            "expected Ok, got: ${(r as? VerifyResult.Failed)?.errors}"
        }
    }

    @Test
    fun `effectful lambda flowing into a pure callback parameter is rejected`() {
        // Reverse direction: expected `(Int) -> Int` (pure), actual
        // `(Int) -> Int ! {someFx}`. actual effects ⊃ expected — rejected with
        // the existing mismatch error.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "someFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "callbackT":{ "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": [] },

            "cb":       { "type": "ParameterDecl", "name": "cb", "paramType": "callbackT" },
            "applyBody":{ "type": "IntLit", "value": 0 },
            "apply":    { "type": "Lambda", "parameters": ["cb"], "body": "applyBody", "effects": [] },

            "px":       { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "pxRef":    { "type": "VarRef", "binder": "px" },
            "fxCb":     { "type": "Lambda", "parameters": ["px"], "body": "pxRef", "effects": ["someFx"] },

            "app":      { "type": "Application", "function": "apply", "arguments": ["fxCb"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.ParameterTypeMismatch }) {
            "expected ParameterTypeMismatch (actual effects ⊃ expected), got: ${failed.errors}"
        }
    }

    @Test
    fun `nested-position arrow keeps strict equality`() {
        // The relaxation applies only at the OUTERMOST arrow. Here a whole
        // PRODUCT value flows into an Application argument position: the
        // compared types are two products, not arrows, so no arrow relaxation
        // applies. The products differ only in a NESTED function field's effect
        // row — expected `{f: (Int) -> Int ! {someFx}}`, actual
        // `{f: (Int) -> Int}` — and a nested arrow stays strictly equal, so the
        // argument is rejected. (A future Q-049 variance increment would accept
        // this; this pin locks the current strict behavior.)
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":       { "type": "PrimitiveType", "kind": "Int" },
            "someFx":     { "type": "EffectCategory", "categoryName": "Time.Now" },

            "fxFieldT":   { "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": ["someFx"] },
            "fFieldExp":  { "type": "ProductTypeField", "name": "f", "fieldType": "fxFieldT" },
            "recExpectedT":{ "type": "ProductType", "fields": ["fFieldExp"] },

            "pureFieldT": { "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": [] },
            "fFieldAct":  { "type": "ProductTypeField", "name": "f", "fieldType": "pureFieldT" },
            "recActualT": { "type": "ProductType", "fields": ["fFieldAct"] },

            "rec":        { "type": "ParameterDecl", "name": "r", "paramType": "recExpectedT" },
            "calleeBody": { "type": "IntLit", "value": 0 },
            "callee":     { "type": "Lambda", "parameters": ["rec"], "body": "calleeBody", "effects": [] },

            "px":         { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "pxRef":      { "type": "VarRef", "binder": "px" },
            "pureCb":     { "type": "Lambda", "parameters": ["px"], "body": "pxRef", "effects": [] },
            "fFieldVal":  { "type": "ProductFieldValue", "fieldName": "f", "value": "pureCb" },
            "actualRec":  { "type": "ProductValue", "ofType": "recActualT", "fields": ["fFieldVal"] },

            "app":        { "type": "Application", "function": "callee", "arguments": ["actualRec"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.ParameterTypeMismatch }) {
            "expected ParameterTypeMismatch (nested arrow strict), got: ${failed.errors}"
        }
    }

    @Test
    fun `effect-set inclusion at a SumValue payload arrow`() {
        // Payload position is a value-flow site too. A sum case whose payload
        // type is `(Int) -> Int ! {someFx}` accepts a pure `(Int) -> Int`
        // payload by outermost-arrow inclusion.
        val r = verify("""{
          "version": 1, "root": "sv",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "someFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "callbackT":{ "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": ["someFx"] },
            "wrapCase": { "type": "SumTypeCase", "name": "Wrap", "caseType": "callbackT" },
            "wrapperT": { "type": "SumType", "cases": ["wrapCase"] },

            "px":       { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "pxRef":    { "type": "VarRef", "binder": "px" },
            "pureCb":   { "type": "Lambda", "parameters": ["px"], "body": "pxRef", "effects": [] },

            "sv":       { "type": "SumValue", "ofType": "wrapperT", "caseName": "Wrap", "payload": "pureCb" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok) {
            "expected Ok, got: ${(r as? VerifyResult.Failed)?.errors}"
        }
    }

    @Test
    fun `non-null TypeParameter bound is a hard verify error`() {
        // A TypeParameter carrying a non-null `bound` is rejected — bounded
        // polymorphism is unimplemented, and a silently-ignored bound is worse
        // than rejection.
        val r = verify("""{
          "version": 1, "root": "id",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "T_a":     { "type": "TypeParameter", "name": "a", "bound": "intT" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.TypeParameterBoundUnsupported }) {
            "expected TypeParameterBoundUnsupported, got: ${failed.errors}"
        }
    }

    @Test
    fun `null or absent TypeParameter bound verifies unchanged`() {
        // The same polymorphic identity without a bound verifies cleanly — the
        // rejection fires only on a non-null bound.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "arg":     { "type": "IntLit", "value": 1 },
            "app":     { "type": "Application", "function": "id", "arguments": ["arg"], "typeArguments": ["intT"] }
          }
        }""")
        assertTrue(r is VerifyResult.Ok) {
            "expected Ok, got: ${(r as? VerifyResult.Failed)?.errors}"
        }
    }

    @Test
    fun `unreferenced bounded quantified parameter is rejected at declaration`() {
        // The bound-bearing TypeParameter is declared in the TypeAbstraction's
        // typeParameters but never referenced in the body, so resolveType never
        // fires on it — the declaration-site check catches it.
        val r = verify("""{
          "version": 1, "root": "abs",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "T_a":    { "type": "TypeParameter", "name": "a", "bound": "intT" },
            "body":   { "type": "IntLit", "value": 7 },
            "abs":    { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "body" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.TypeParameterBoundUnsupported }) {
            "expected TypeParameterBoundUnsupported at declaration, got: ${failed.errors}"
        }
    }
}
