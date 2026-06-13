package org.strand.verifier

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.strand.core.JsonIngest
import org.strand.core.Primitive
import org.strand.hashing.Hasher

class VerifierTest {

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    @Test
    fun `int literal verifies to Int`() {
        val r = verify("""{
          "version": 1, "root": "x",
          "nodes": { "x": { "type": "IntLit", "value": 5 } }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Int), ok.rootType)
    }

    @Test
    fun `identity applied to int checks at Int`() {
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "arg":     { "type": "IntLit", "value": 1 },
            "app":     { "type": "Application", "function": "id", "arguments": ["arg"], "typeArguments": ["intT"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Int), ok.rootType)
    }

    @Test
    fun `unbound varref is rejected`() {
        // VarRef.binder points to a node that exists but is not in scope
        // (the ParameterDecl is not part of any lambda the VarRef sits under).
        val r = verify("""{
          "version": 1, "root": "ref",
          "nodes": {
            "intT": { "type": "PrimitiveType", "kind": "Int" },
            "x":    { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "ref":  { "type": "VarRef", "binder": "x" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.UnboundVariable }, "expected UnboundVariable, got ${f.errors}")
    }

    @Test
    fun `varref pointing at a non-binder is rejected`() {
        val r = verify("""{
          "version": 1, "root": "ref",
          "nodes": {
            "lit": { "type": "IntLit", "value": 1 },
            "ref": { "type": "VarRef", "binder": "lit" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.IllegalBinder })
    }

    @Test
    fun `arity mismatch at application is rejected`() {
        val r = verify("""{
          "version": 1, "root": "bad",
          "nodes": {
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "a1":      { "type": "IntLit", "value": 1 },
            "a2":      { "type": "IntLit", "value": 2 },
            "bad":     { "type": "Application", "function": "id", "arguments": ["a1", "a2"], "typeArguments": ["intT"] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.ArityMismatch })
    }

    @Test
    fun `non-function in function position is rejected`() {
        val r = verify("""{
          "version": 1, "root": "bad",
          "nodes": {
            "lit": { "type": "IntLit", "value": 1 },
            "arg": { "type": "IntLit", "value": 2 },
            "bad": { "type": "Application", "function": "lit", "arguments": ["arg"] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.NotAFunction })
    }

    @Test
    fun `explicit instantiation allows two call sites at different types`() {
        val r = verify("""{
          "version": 1, "root": "letId",
          "nodes": {
            "T_a":       { "type": "TypeParameter", "name": "a" },
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "boolT":     { "type": "PrimitiveType", "kind": "Bool" },
            "x":         { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":      { "type": "VarRef", "binder": "x" },
            "idInner":   { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "idLam":     { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "idRef1":    { "type": "VarRef", "binder": "letId" },
            "i1":        { "type": "IntLit", "value": 1 },
            "callI":     { "type": "Application", "function": "idRef1", "arguments": ["i1"], "typeArguments": ["intT"] },
            "idRef2":    { "type": "VarRef", "binder": "letId" },
            "b1":        { "type": "BoolLit", "value": true },
            "callB":     { "type": "Application", "function": "idRef2", "arguments": ["b1"], "typeArguments": ["boolT"] },
            "inner":     { "type": "Let", "name": "_", "value": "callI", "body": "callB" },
            "letId":     { "type": "Let", "name": "id", "value": "idLam", "body": "inner" }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Bool), ok.rootType)
    }

    @Test
    fun `type-argument arity mismatch is rejected`() {
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "arg":     { "type": "IntLit", "value": 1 },
            "app":     { "type": "Application", "function": "id", "arguments": ["arg"] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.TypeArgumentArityMismatch },
            "expected TypeArgumentArityMismatch, got ${f.errors}")
    }

    @Test
    fun `parameter-type mismatch is rejected`() {
        // id : forall a. a -> a, instantiated at Int, but applied to a Bool argument.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "arg":     { "type": "BoolLit", "value": true },
            "app":     { "type": "Application", "function": "id", "arguments": ["arg"], "typeArguments": ["intT"] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.ParameterTypeMismatch },
            "expected ParameterTypeMismatch, got ${f.errors}")
    }

    @Test
    fun `unbound type parameter outside TypeAbstraction is rejected`() {
        // A Lambda whose parameter type references a TypeParameter not bound
        // by any enclosing TypeAbstraction. The verifier rejects this with
        // UnboundTypeParameter; previously such a Lambda was implicitly
        // polymorphic.
        val r = verify("""{
          "version": 1, "root": "id",
          "nodes": {
            "T_a":  { "type": "TypeParameter", "name": "a" },
            "x":    { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef": { "type": "VarRef", "binder": "x" },
            "id":   { "type": "Lambda", "parameters": ["x"], "body": "xRef" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.UnboundTypeParameter },
            "expected UnboundTypeParameter, got ${f.errors}")
    }

    @Test
    fun `polymorphic value passed at higher rank verifies`() {
        // apply : (forall a. a -> a) -> Int -> Int
        //       = \f. \n. f[Int](n)
        // applied to (TypeAbstraction T_a. \x:T_a. x) and 7. Expect Int.
        // Note: the TypeParameter node is shared between the parameter's
        // ForallType and the polymorphic argument's TypeAbstraction so that
        // the two ForallType values are structurally equal.
        val r = verify("""{
          "version": 1, "root": "topApp",
          "nodes": {
            "T_a":          { "type": "TypeParameter", "name": "a" },
            "intT":         { "type": "PrimitiveType", "kind": "Int" },
            "idFunType":    { "type": "FunctionType", "parameters": ["T_a"], "result": "T_a" },
            "idForallType": { "type": "ForallType", "typeParameters": ["T_a"], "body": "idFunType" },
            "f":            { "type": "ParameterDecl", "name": "f", "paramType": "idForallType" },
            "n":            { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "fRef":         { "type": "VarRef", "binder": "f" },
            "nRef":         { "type": "VarRef", "binder": "n" },
            "callFN":       { "type": "Application", "function": "fRef", "arguments": ["nRef"], "typeArguments": ["intT"] },
            "innerN":       { "type": "Lambda", "parameters": ["n"], "body": "callFN" },
            "applyLam":     { "type": "Lambda", "parameters": ["f"], "body": "innerN" },
            "idX":          { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "idXRef":       { "type": "VarRef", "binder": "idX" },
            "idInner":      { "type": "Lambda", "parameters": ["idX"], "body": "idXRef" },
            "idLam":        { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "argN":         { "type": "IntLit", "value": 7 },
            "partial":      { "type": "Application", "function": "applyLam", "arguments": ["idLam"], "typeArguments": [] },
            "topApp":       { "type": "Application", "function": "partial", "arguments": ["argN"], "typeArguments": [] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Int), ok.rootType)
    }

    @Test
    fun `dangling parameter reference is rejected`() {
        // A Lambda whose parameter list references a non-existent node.
        // Build this directly via a hand-crafted store rather than JSON since
        // JSON ingest pre-validates name resolution.
        val store = org.strand.core.NodeStore()
        val tpId = store.add(org.strand.core.Node.TypeParameter("a", null))
        val ghost = org.strand.core.NodeId(999)
        val lit = store.add(org.strand.core.Node.IntLit(0))
        val lambda = store.add(org.strand.core.Node.Lambda(parameters = listOf(ghost), body = lit))
        val v = Verifier(store).verify(lambda)
        val f = v as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.DanglingReference })
        // Touch the type-parameter id so the unused-variable warning does not fire.
        assertNotNull(tpId)
    }

    // ----- Layer 3: effects and capabilities -----

    @Test
    fun `Lambda may declare effects its body does not use`() {
        // Over-declaration is permitted. A Lambda that claims `Time.Now` but
        // never actually exercises it verifies cleanly.
        val r = verify("""{
          "version": 1, "root": "fn",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "x":      { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "body":   { "type": "VarRef", "binder": "x" },
            "fn":     { "type": "Lambda", "parameters": ["x"], "body": "body", "effects": ["timeFx"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        val funType = ok.rootType as TypeExpr.Fun
        assertEquals(1, funType.effects.size, "Lambda type should carry one declared effect")
    }

    @Test
    fun `Lambda calling an effectful Lambda without re-declaring is rejected`() {
        // Inner lambda `g` declares Time.Now. Outer lambda `f` applies `g` but
        // declares no effects of its own. Verifier flags UncoveredEffects on f.
        val r = verify("""{
          "version": 1, "root": "f",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },

            "gX":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "gBody":  { "type": "VarRef", "binder": "gX" },
            "g":      { "type": "Lambda", "parameters": ["gX"], "body": "gBody", "effects": ["timeFx"] },

            "fY":     { "type": "ParameterDecl", "name": "y", "paramType": "intT" },
            "fYRef":  { "type": "VarRef", "binder": "fY" },
            "fApply": { "type": "Application", "function": "g", "arguments": ["fYRef"] },
            "f":      { "type": "Lambda", "parameters": ["fY"], "body": "fApply", "effects": [] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.UncoveredEffects }) {
            "expected UncoveredEffects on f, got: ${failed.errors}"
        }
    }

    @Test
    fun `effect edge pointing to non-EffectCategory is rejected`() {
        val r = verify("""{
          "version": 1, "root": "fn",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "x":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "body":  { "type": "VarRef", "binder": "x" },
            "fn":    { "type": "Lambda", "parameters": ["x"], "body": "body", "effects": ["intT"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.NonEffectCategoryInEffectList }) {
            "expected NonEffectCategoryInEffectList, got: ${failed.errors}"
        }
    }

    @Test
    fun `CapabilityScope narrowing a body that needs more is rejected`() {
        // Body applies an effectful function (g declares Time.Now), but the
        // CapabilityScope narrows to the empty set. Verifier flags
        // CapabilityScopeUnsatisfiable.
        val r = verify("""{
          "version": 1, "root": "scope",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },

            "gX":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "gBody":  { "type": "VarRef", "binder": "gX" },
            "g":      { "type": "Lambda", "parameters": ["gX"], "body": "gBody", "effects": ["timeFx"] },

            "seven":  { "type": "IntLit", "value": 7 },
            "callG":  { "type": "Application", "function": "g", "arguments": ["seven"] },
            "scope":  { "type": "CapabilityScope", "capabilities": [], "body": "callG" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.CapabilityScopeUnsatisfiable }) {
            "expected CapabilityScopeUnsatisfiable, got: ${failed.errors}"
        }
    }

    @Test
    fun `CapabilityScope retaining the needed capability verifies cleanly`() {
        val r = verify("""{
          "version": 1, "root": "scope",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },

            "gX":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "gBody":  { "type": "VarRef", "binder": "gX" },
            "g":      { "type": "Lambda", "parameters": ["gX"], "body": "gBody", "effects": ["timeFx"] },

            "seven":  { "type": "IntLit", "value": 7 },
            "callG":  { "type": "Application", "function": "g", "arguments": ["seven"] },
            "scope":  { "type": "CapabilityScope", "capabilities": ["timeFx"], "body": "callG" }
          }
        }""")
        r as VerifyResult.Ok  // throws if not Ok
    }

    // ----- Layer 4 step 1: ForeignNode -----

    @Test
    fun `ForeignNode-typed application returns the foreign function's result type`() {
        // ForeignNode for `Int.Add: (Int, Int) -> Int` applied to two IntLits.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "addT":  { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":   { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "two":   { "type": "IntLit", "value": 2 },
            "three": { "type": "IntLit", "value": 3 },
            "app":   { "type": "Application", "function": "add", "arguments": ["two", "three"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Int), ok.rootType)
    }

    @Test
    fun `ForeignNode with declared effect propagates to caller's closure`() {
        // ForeignNode declares Time.Now. The outer Lambda calls it, so its
        // body's closure includes Time.Now and the Lambda must declare it.
        // Here we deliberately *don't* declare it on the outer Lambda — the
        // verifier should reject with UncoveredEffects.
        val r = verify("""{
          "version": 1, "root": "outer",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":   { "type": "FunctionType", "parameters": [], "result": "intT" },
            "now":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now", "foreignType": "nowT", "effects": ["timeFx"] },

            "callNow": { "type": "Application", "function": "now", "arguments": [] },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "outer":   { "type": "Lambda", "parameters": ["x"], "body": "callNow", "effects": [] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.UncoveredEffects }) {
            "expected UncoveredEffects from undeclared Time.Now use, got: ${failed.errors}"
        }
    }

    @Test
    fun `ForeignNode with non-FunctionType signature is rejected`() {
        val r = verify("""{
          "version": 1, "root": "bad",
          "nodes": {
            "intT": { "type": "PrimitiveType", "kind": "Int" },
            "bad":  { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "intT" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.CategoryMismatch }) {
            "expected CategoryMismatch, got: ${failed.errors}"
        }
    }

    // ----- Layer 5 step 1: Match -----

    @Test
    fun `Match against Int literal pattern type-checks to the case body's type`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "z":     { "type": "IntLit", "value": 0 },
            "pat":   { "type": "Pattern", "kind": "literal", "patternType": "intT", "literal": "z" },
            "body":  { "type": "IntLit", "value": 42 },
            "case":  { "type": "MatchCase", "pattern": "pat", "body": "body" },
            "wild":  { "type": "Pattern", "kind": "wildcard", "patternType": "intT" },
            "wbody": { "type": "IntLit", "value": 7 },
            "wcase": { "type": "MatchCase", "pattern": "wild", "body": "wbody" },
            "scrut": { "type": "IntLit", "value": 0 },
            "m":     { "type": "Match", "scrutinee": "scrut", "cases": ["case", "wcase"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Int), ok.rootType)
    }

    @Test
    fun `Match with empty cases is rejected`() {
        // Constructed via direct store building since the JSON ingest's
        // requireRefList allows empty arrays but the verifier rejects.
        val store = org.strand.core.NodeStore()
        val intT = store.add(org.strand.core.Node.PrimitiveType(Primitive.Int))
        val scrut = store.add(org.strand.core.Node.IntLit(0))
        val m = store.add(org.strand.core.Node.Match(scrutinee = scrut, cases = emptyList()))
        val r = Verifier(store).verify(m)
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.EmptyMatch })
        assertNotNull(intT)
    }

    @Test
    fun `Match case body type divergence is rejected`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "z":     { "type": "IntLit", "value": 0 },
            "pat":   { "type": "Pattern", "kind": "literal", "patternType": "intT", "literal": "z" },
            "body":  { "type": "IntLit", "value": 42 },
            "case":  { "type": "MatchCase", "pattern": "pat", "body": "body" },
            "wild":  { "type": "Pattern", "kind": "wildcard", "patternType": "intT" },
            "wbody": { "type": "BoolLit", "value": true },
            "wcase": { "type": "MatchCase", "pattern": "wild", "body": "wbody" },
            "scrut": { "type": "IntLit", "value": 0 },
            "m":     { "type": "Match", "scrutinee": "scrut", "cases": ["case", "wcase"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.MatchCaseBodyTypeDivergence }) {
            "expected MatchCaseBodyTypeDivergence (Int vs Bool case bodies), got: ${failed.errors}"
        }
    }

    @Test
    fun `Pattern of wrong type for scrutinee is rejected`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "boolT": { "type": "PrimitiveType", "kind": "Bool" },
            "wild":  { "type": "Pattern", "kind": "wildcard", "patternType": "boolT" },
            "body":  { "type": "IntLit", "value": 1 },
            "case":  { "type": "MatchCase", "pattern": "wild", "body": "body" },
            "scrut": { "type": "IntLit", "value": 0 },
            "m":     { "type": "Match", "scrutinee": "scrut", "cases": ["case"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.PatternTypeMismatch }) {
            "expected PatternTypeMismatch (Int scrutinee vs Bool pattern), got: ${failed.errors}"
        }
    }

    @Test
    fun `Variable pattern binds in scope and types the body`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "patN":  { "type": "Pattern", "kind": "variable", "patternType": "intT", "name": "n" },
            "nRef":  { "type": "VarRef", "binder": "patN" },
            "case":  { "type": "MatchCase", "pattern": "patN", "body": "nRef" },
            "scrut": { "type": "IntLit", "value": 5 },
            "m":     { "type": "Match", "scrutinee": "scrut", "cases": ["case"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Int), ok.rootType)
    }

    // ----- Layer 5 step 2: Fixpoint -----

    @Test
    fun `Fixpoint types to its recursionType`() {
        // A trivial well-formed Fixpoint: the body returns its parameter
        // unchanged (no actual recursion). The point is to exercise the
        // verifier's structural check on the body's shape.
        val r = verify("""{
          "version": 1, "root": "fp",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "recT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },

            "recurse":  { "type": "ParameterDecl", "name": "recurse", "paramType": "recT" },
            "n":        { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "nRef":     { "type": "VarRef", "binder": "n" },
            "bodyLam":  { "type": "Lambda", "parameters": ["recurse", "n"], "body": "nRef" },

            "fp":       { "type": "Fixpoint", "recursionType": "recT", "body": "bodyLam" }
          }
        }""")
        val ok = r as VerifyResult.Ok
        val funType = ok.rootType as TypeExpr.Fun
        assertEquals(1, funType.parameters.size)
        assertEquals(TypeExpr.Prim(Primitive.Int), funType.result)
    }

    @Test
    fun `Fixpoint with body whose first parameter type does not equal recursionType is rejected`() {
        // body's first param has type Int, but recursionType is (Int) -> Int.
        // The verifier should report FixpointBodyShapeMismatch.
        val r = verify("""{
          "version": 1, "root": "fp",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "recT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },

            "wrongP":   { "type": "ParameterDecl", "name": "wrong", "paramType": "intT" },
            "n":        { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "nRef":     { "type": "VarRef", "binder": "n" },
            "bodyLam":  { "type": "Lambda", "parameters": ["wrongP", "n"], "body": "nRef" },

            "fp":       { "type": "Fixpoint", "recursionType": "recT", "body": "bodyLam" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.FixpointBodyShapeMismatch }) {
            "expected FixpointBodyShapeMismatch, got: ${failed.errors}"
        }
    }

    @Test
    fun `Fixpoint with non-Lambda body is rejected`() {
        val r = verify("""{
          "version": 1, "root": "fp",
          "nodes": {
            "intT": { "type": "PrimitiveType", "kind": "Int" },
            "recT": { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "lit":  { "type": "IntLit", "value": 0 },
            "fp":   { "type": "Fixpoint", "recursionType": "recT", "body": "lit" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.CategoryMismatch }) {
            "expected CategoryMismatch, got: ${failed.errors}"
        }
    }

    // ----- Layer 5 step 3a: ProductValue / ProductFieldGet -----

    private val pointProgramHeader = """
        "intT":      { "type": "PrimitiveType", "kind": "Int" },
        "xField":    { "type": "ProductTypeField", "name": "x", "fieldType": "intT" },
        "yField":    { "type": "ProductTypeField", "name": "y", "fieldType": "intT" },
        "pointType": { "type": "ProductType", "fields": ["xField", "yField"] },
        "lit3":      { "type": "IntLit", "value": 3 },
        "lit4":      { "type": "IntLit", "value": 4 },
    """.trimIndent()

    @Test
    fun `ProductValue with all fields type-checks to the product type`() {
        val r = verify("""{
          "version": 1, "root": "point",
          "nodes": {
            $pointProgramHeader
            "xValue":    { "type": "ProductFieldValue", "fieldName": "x", "value": "lit3" },
            "yValue":    { "type": "ProductFieldValue", "fieldName": "y", "value": "lit4" },
            "point":     { "type": "ProductValue", "ofType": "pointType", "fields": ["xValue", "yValue"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertTrue(ok.rootType is TypeExpr.Product) {
            "expected Product type, got ${ok.rootType}"
        }
    }

    @Test
    fun `ProductValue missing a field is rejected`() {
        val r = verify("""{
          "version": 1, "root": "point",
          "nodes": {
            $pointProgramHeader
            "xValue":    { "type": "ProductFieldValue", "fieldName": "x", "value": "lit3" },
            "point":     { "type": "ProductValue", "ofType": "pointType", "fields": ["xValue"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.MissingProductValueFields }) {
            "expected MissingProductValueFields, got: ${failed.errors}"
        }
    }

    @Test
    fun `ProductValue with a duplicate field is rejected`() {
        val r = verify("""{
          "version": 1, "root": "point",
          "nodes": {
            $pointProgramHeader
            "xValue":    { "type": "ProductFieldValue", "fieldName": "x", "value": "lit3" },
            "xAgain":    { "type": "ProductFieldValue", "fieldName": "x", "value": "lit4" },
            "yValue":    { "type": "ProductFieldValue", "fieldName": "y", "value": "lit4" },
            "point":     { "type": "ProductValue", "ofType": "pointType", "fields": ["xValue", "xAgain", "yValue"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.DuplicateProductValueField }) {
            "expected DuplicateProductValueField, got: ${failed.errors}"
        }
    }

    @Test
    fun `ProductValue with an unknown field is rejected`() {
        val r = verify("""{
          "version": 1, "root": "point",
          "nodes": {
            $pointProgramHeader
            "xValue":    { "type": "ProductFieldValue", "fieldName": "x", "value": "lit3" },
            "yValue":    { "type": "ProductFieldValue", "fieldName": "y", "value": "lit4" },
            "zValue":    { "type": "ProductFieldValue", "fieldName": "z", "value": "lit4" },
            "point":     { "type": "ProductValue", "ofType": "pointType", "fields": ["xValue", "yValue", "zValue"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.UnknownProductValueField }) {
            "expected UnknownProductValueField, got: ${failed.errors}"
        }
    }

    @Test
    fun `ProductValue with a field value of the wrong type is rejected`() {
        val r = verify("""{
          "version": 1, "root": "point",
          "nodes": {
            $pointProgramHeader
            "boolLit":   { "type": "BoolLit", "value": true },
            "xValue":    { "type": "ProductFieldValue", "fieldName": "x", "value": "boolLit" },
            "yValue":    { "type": "ProductFieldValue", "fieldName": "y", "value": "lit4" },
            "point":     { "type": "ProductValue", "ofType": "pointType", "fields": ["xValue", "yValue"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.ProductFieldValueTypeMismatch }) {
            "expected ProductFieldValueTypeMismatch (Bool vs Int), got: ${failed.errors}"
        }
    }

    @Test
    fun `ProductFieldGet on a non-product target is rejected`() {
        val r = verify("""{
          "version": 1, "root": "bad",
          "nodes": {
            "lit":  { "type": "IntLit", "value": 1 },
            "bad":  { "type": "ProductFieldGet", "target": "lit", "fieldName": "x" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.CategoryMismatch }) {
            "expected CategoryMismatch (Int target, not Product), got: ${failed.errors}"
        }
    }

    @Test
    fun `ProductFieldGet of an unknown field is rejected`() {
        val r = verify("""{
          "version": 1, "root": "getZ",
          "nodes": {
            $pointProgramHeader
            "xValue":    { "type": "ProductFieldValue", "fieldName": "x", "value": "lit3" },
            "yValue":    { "type": "ProductFieldValue", "fieldName": "y", "value": "lit4" },
            "point":     { "type": "ProductValue", "ofType": "pointType", "fields": ["xValue", "yValue"] },
            "getZ":      { "type": "ProductFieldGet", "target": "point", "fieldName": "z" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.UnknownProductValueField }) {
            "expected UnknownProductValueField, got: ${failed.errors}"
        }
    }

    // ----- Layer 5 step 3b: SumValue + ConstructorPattern -----

    private val optionProgramHeader = """
        "intT":     { "type": "PrimitiveType", "kind": "Int" },
        "someCase": { "type": "SumTypeCase", "name": "Some", "caseType": "intT" },
        "noneCase": { "type": "SumTypeCase", "name": "None", "caseType": null },
        "optionT":  { "type": "SumType", "cases": ["someCase", "noneCase"] },
        "lit42":    { "type": "IntLit", "value": 42 },
    """.trimIndent()

    @Test
    fun `SumValue with the right case type-checks to the sum type`() {
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            $optionProgramHeader
            "v": { "type": "SumValue", "ofType": "optionT", "caseName": "Some", "payload": "lit42" }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertTrue(ok.rootType is TypeExpr.Sum)
    }

    @Test
    fun `SumValue with unknown case is rejected`() {
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            $optionProgramHeader
            "v": { "type": "SumValue", "ofType": "optionT", "caseName": "Bogus", "payload": "lit42" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.UnknownSumCase }) {
            "expected UnknownSumCase, got: ${failed.errors}"
        }
    }

    @Test
    fun `SumValue payload on a nullary case is rejected`() {
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            $optionProgramHeader
            "v": { "type": "SumValue", "ofType": "optionT", "caseName": "None", "payload": "lit42" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.UnexpectedSumPayload }) {
            "expected UnexpectedSumPayload, got: ${failed.errors}"
        }
    }

    @Test
    fun `SumValue missing payload on a unary case is rejected`() {
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            $optionProgramHeader
            "v": { "type": "SumValue", "ofType": "optionT", "caseName": "Some", "payload": null }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.MissingSumPayload }) {
            "expected MissingSumPayload, got: ${failed.errors}"
        }
    }

    @Test
    fun `ConstructorPattern binds payload variable in case body scope`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            $optionProgramHeader
            "v":         { "type": "SumValue", "ofType": "optionT", "caseName": "Some", "payload": "lit42" },
            "varN":      { "type": "Pattern", "kind": "variable", "patternType": "intT", "name": "n" },
            "patSome":   { "type": "Pattern", "kind": "constructor", "patternType": "optionT", "caseName": "Some", "payloadPattern": "varN" },
            "nRef":      { "type": "VarRef", "binder": "varN" },
            "caseSome":  { "type": "MatchCase", "pattern": "patSome", "body": "nRef" },
            "patNone":   { "type": "Pattern", "kind": "constructor", "patternType": "optionT", "caseName": "None" },
            "zero":      { "type": "IntLit", "value": 0 },
            "caseNone":  { "type": "MatchCase", "pattern": "patNone", "body": "zero" },
            "m":         { "type": "Match", "scrutinee": "v", "cases": ["caseSome", "caseNone"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Int), ok.rootType)
    }

    // ----- Recursive types (N-041, N-042) -----

    @Test
    fun `non-contractive recursive type μ X X is rejected`() {
        // μ. RecursiveSelf — the body IS the self-reference, no constructor
        // guards. Verifier reports NonContractiveRecursiveType.
        val store = org.strand.core.NodeStore()
        val recSelf = store.add(org.strand.core.Node.RecursiveSelf())
        val recType = store.add(org.strand.core.Node.RecursiveType(body = recSelf))
        // Force resolution via a Lambda whose parameter type is the recursive type.
        val param = store.add(org.strand.core.Node.ParameterDecl("x", recType))
        val pRef = store.add(org.strand.core.Node.VarRef(param))
        val lambda = store.add(org.strand.core.Node.Lambda(parameters = listOf(param), body = pRef))
        val r = Verifier(store).verify(lambda)
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.NonContractiveRecursiveType }) {
            "expected NonContractiveRecursiveType, got: ${failed.errors}"
        }
    }

    @Test
    fun `unbound RecursiveSelf is rejected`() {
        // RecursiveSelf used as a type without any enclosing RecursiveType binder.
        val store = org.strand.core.NodeStore()
        val recSelf = store.add(org.strand.core.Node.RecursiveSelf())
        val param = store.add(org.strand.core.Node.ParameterDecl("x", recSelf))
        val pRef = store.add(org.strand.core.Node.VarRef(param))
        val lambda = store.add(org.strand.core.Node.Lambda(parameters = listOf(param), body = pRef))
        val r = Verifier(store).verify(lambda)
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.UnboundRecursiveSelf }) {
            "expected UnboundRecursiveSelf, got: ${failed.errors}"
        }
    }

    @Test
    fun `RecursiveSelf depth exceeding enclosing binders is rejected`() {
        // μ. depth=1 — single enclosing RT, but RS asks for the next-outer
        // binder which does not exist. UnboundRecursiveSelf fires regardless
        // of contractivity (the contractivity guard requires structure
        // between binder and self; here intT in Cons's payload satisfies it).
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "rsOuter":  { "type": "RecursiveSelf", "depth": 1 },
            "consField":{ "type": "ProductTypeField", "name": "head", "fieldType": "rsOuter" },
            "consProd": { "type": "ProductType", "fields": ["consField"] },
            "consCase": { "type": "SumTypeCase", "name": "Cons", "caseType": "consProd" },
            "nilCase":  { "type": "SumTypeCase", "name": "Nil", "caseType": null },
            "body":     { "type": "SumType", "cases": ["consCase", "nilCase"] },
            "recT":     { "type": "RecursiveType", "body": "body" },
            "x":        { "type": "ParameterDecl", "name": "x", "paramType": "recT" },
            "xr":       { "type": "VarRef", "binder": "x" },
            "v":        { "type": "Lambda", "parameters": ["x"], "body": "xr" }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.UnboundRecursiveSelf }) {
            "expected UnboundRecursiveSelf for depth=1 with only 1 enclosing RT, got: ${failed.errors}"
        }
    }

    @Test
    fun `nested RecursiveTypes accept depth-0 inner and depth-1 outer references`() {
        // outer = μ jv. JsonLeaf(Int) | JsonArr(μ list. Cons(head=RSouter(depth=1), tail=RSinner(depth=0)) | Nil)
        // The shape that justifies adding the depth field — JsonValue with a
        // recursive array case whose List<JsonValue> is itself a nested μ.
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            "intT":          { "type": "PrimitiveType", "kind": "Int" },

            "rsInner":       { "type": "RecursiveSelf", "depth": 0 },
            "rsOuter":       { "type": "RecursiveSelf", "depth": 1 },

            "innerHead":     { "type": "ProductTypeField", "name": "head", "fieldType": "rsOuter" },
            "innerTail":     { "type": "ProductTypeField", "name": "tail", "fieldType": "rsInner" },
            "innerProd":     { "type": "ProductType", "fields": ["innerHead", "innerTail"] },
            "innerCons":     { "type": "SumTypeCase", "name": "Cons", "caseType": "innerProd" },
            "innerNil":      { "type": "SumTypeCase", "name": "Nil", "caseType": null },
            "innerBody":     { "type": "SumType", "cases": ["innerCons", "innerNil"] },
            "listT":         { "type": "RecursiveType", "body": "innerBody" },

            "leafCase":      { "type": "SumTypeCase", "name": "JsonLeaf", "caseType": "intT" },
            "arrCase":       { "type": "SumTypeCase", "name": "JsonArr", "caseType": "listT" },
            "outerBody":     { "type": "SumType", "cases": ["leafCase", "arrCase"] },
            "jsonT":         { "type": "RecursiveType", "body": "outerBody" },

            "x":             { "type": "ParameterDecl", "name": "x", "paramType": "jsonT" },
            "xr":            { "type": "VarRef", "binder": "x" },
            "v":             { "type": "Lambda", "parameters": ["x"], "body": "xr" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok) {
            "expected nested-μ JsonValue-shape to verify cleanly, got: $r"
        }
    }

    @Test
    fun `recursive list type with constructor guard verifies cleanly`() {
        // μ. Nil | Cons(Int) — contractive because Cons's payload guards
        // any (in this case absent) self-reference. Smallest well-formed
        // recursive-sum example.
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "consCase": { "type": "SumTypeCase", "name": "Cons", "caseType": "intT" },
            "nilCase":  { "type": "SumTypeCase", "name": "Nil", "caseType": null },
            "body":     { "type": "SumType", "cases": ["consCase", "nilCase"] },
            "recT":     { "type": "RecursiveType", "body": "body" },
            "v":        { "type": "SumValue", "ofType": "recT", "caseName": "Nil", "payload": null }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertTrue(ok.rootType is TypeExpr.Recursive)
    }

    // ----- Q-031: refinement-lattice capability matching -----

    @Test
    fun `Application supplying a well-formed effectInstance verifies cleanly`() {
        // A ForeignNode declares Filesystem.Write(path: String). The
        // Application supplies a matching EffectDecl with one String
        // parameter. The verifier checks shape (effectType is
        // EffectCategory, arity matches, parameter types match) and
        // coverage (the set of categories covered equals the callee's
        // declared effect set).
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "strT":     { "type": "PrimitiveType", "kind": "String" },
            "fsWriteFx":{ "type": "EffectCategory", "categoryName": "Filesystem.Write",
                          "parameters": ["strT"] },
            "writeT":   { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                          "effects": ["fsWriteFx"] },
            "write":    { "type": "ForeignNode", "target": "strand-builtin:Filesystem.Write",
                          "foreignType": "writeT", "effects": ["fsWriteFx"] },
            "path":     { "type": "StringLit", "value": "/tmp/x" },
            "pathInstance": { "type": "StringLit", "value": "/tmp/x" },
            "writeDecl":{ "type": "EffectDecl", "effectType": "fsWriteFx",
                          "parameters": ["pathInstance"] },
            "app":      { "type": "Application", "function": "write",
                          "arguments": ["path"], "effectInstances": ["writeDecl"] }
          }
        }""")
        r as VerifyResult.Ok  // throws if not Ok
    }

    @Test
    fun `Application missing an effectInstance for a declared category is rejected`() {
        // The callee declares two effects but the Application supplies
        // an instance for only one. The set covered does not equal the
        // declared set; verifier flags EffectInstanceCoverageMismatch.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "otherFx":  { "type": "EffectCategory", "categoryName": "Other.Effect" },
            "fnT":      { "type": "FunctionType", "parameters": [], "result": "intT",
                          "effects": ["timeFx", "otherFx"] },
            "fn":       { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                          "foreignType": "fnT", "effects": ["timeFx", "otherFx"] },
            "timeDecl": { "type": "EffectDecl", "effectType": "timeFx" },
            "app":      { "type": "Application", "function": "fn", "arguments": [],
                          "effectInstances": ["timeDecl"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        val err = (failed.errors.firstOrNull { it is VerifyError.EffectInstanceCoverageMismatch }
            as? VerifyError.EffectInstanceCoverageMismatch)
            ?: error("expected EffectInstanceCoverageMismatch, got: ${failed.errors}")
        assertEquals(1, err.missing.size, "expected exactly one missing category")
        assertTrue(err.extra.isEmpty(), "expected no extra categories")
    }

    @Test
    fun `Application supplying an extra effectInstance not declared by the callee is rejected`() {
        // The callee declares no effects. The Application supplies an
        // EffectDecl. The set covered (1 category) does not equal the
        // declared set (empty); verifier flags EffectInstanceCoverageMismatch.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "fnT":      { "type": "FunctionType", "parameters": [], "result": "intT" },
            "fn":       { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                          "foreignType": "fnT" },
            "timeDecl": { "type": "EffectDecl", "effectType": "timeFx" },
            "app":      { "type": "Application", "function": "fn", "arguments": [],
                          "effectInstances": ["timeDecl"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        val err = (failed.errors.firstOrNull { it is VerifyError.EffectInstanceCoverageMismatch }
            as? VerifyError.EffectInstanceCoverageMismatch)
            ?: error("expected EffectInstanceCoverageMismatch, got: ${failed.errors}")
        assertTrue(err.missing.isEmpty())
        assertEquals(1, err.extra.size)
    }

    @Test
    fun `EffectDecl whose effectType is not an EffectCategory is rejected`() {
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "fnT":      { "type": "FunctionType", "parameters": [], "result": "intT",
                          "effects": ["timeFx"] },
            "fn":       { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                          "foreignType": "fnT", "effects": ["timeFx"] },
            "badDecl":  { "type": "EffectDecl", "effectType": "intT" },
            "app":      { "type": "Application", "function": "fn", "arguments": [],
                          "effectInstances": ["badDecl"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        assertTrue(failed.errors.any { it is VerifyError.EffectDeclTypeMismatch }) {
            "expected EffectDeclTypeMismatch, got: ${failed.errors}"
        }
    }

    @Test
    fun `EffectDecl with wrong number of parameters is rejected`() {
        // EffectCategory declares one parameter (path: String). The
        // EffectDecl supplies zero. Verifier flags arity mismatch.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "strT":     { "type": "PrimitiveType", "kind": "String" },
            "writeFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                          "parameters": ["strT"] },
            "writeT":   { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                          "effects": ["writeFx"] },
            "write":    { "type": "ForeignNode", "target": "strand-builtin:Filesystem.Write",
                          "foreignType": "writeT", "effects": ["writeFx"] },
            "path":     { "type": "StringLit", "value": "/tmp/x" },
            "badDecl":  { "type": "EffectDecl", "effectType": "writeFx" },
            "app":      { "type": "Application", "function": "write",
                          "arguments": ["path"], "effectInstances": ["badDecl"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        val err = (failed.errors.firstOrNull { it is VerifyError.EffectDeclArityMismatch }
            as? VerifyError.EffectDeclArityMismatch)
            ?: error("expected EffectDeclArityMismatch, got: ${failed.errors}")
        assertEquals(1, err.expected)
        assertEquals(0, err.actual)
    }

    @Test
    fun `EffectDecl with a parameter of the wrong type is rejected`() {
        // EffectCategory declares parameter type String; EffectDecl
        // supplies an IntLit. Verifier flags parameter type mismatch.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "strT":     { "type": "PrimitiveType", "kind": "String" },
            "writeFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                          "parameters": ["strT"] },
            "writeT":   { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                          "effects": ["writeFx"] },
            "write":    { "type": "ForeignNode", "target": "strand-builtin:Filesystem.Write",
                          "foreignType": "writeT", "effects": ["writeFx"] },
            "path":     { "type": "StringLit", "value": "/tmp/x" },
            "wrongType":{ "type": "IntLit", "value": 42 },
            "badDecl":  { "type": "EffectDecl", "effectType": "writeFx",
                          "parameters": ["wrongType"] },
            "app":      { "type": "Application", "function": "write",
                          "arguments": ["path"], "effectInstances": ["badDecl"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        val err = (failed.errors.firstOrNull { it is VerifyError.EffectDeclParameterTypeMismatch }
            as? VerifyError.EffectDeclParameterTypeMismatch)
            ?: error("expected EffectDeclParameterTypeMismatch, got: ${failed.errors}")
        assertEquals(0, err.parameterIndex)
    }

    @Test
    fun `Application with no effectInstances on an effectful callee verifies (back-compat)`() {
        // Pre-Q-031 corpus programs (e.g. 12, 13, 14, 16, 17) call
        // effectful functions without supplying effectInstances. Empty
        // effectInstances is permitted by the verifier; the runtime falls
        // back to category-only matching via CapabilitySet.ofCategories.
        // This test pins the back-compat contract.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":     { "type": "FunctionType", "parameters": [], "result": "intT",
                          "effects": ["timeFx"] },
            "now":      { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                          "foreignType": "nowT", "effects": ["timeFx"] },
            "app":      { "type": "Application", "function": "now", "arguments": [] }
          }
        }""")
        r as VerifyResult.Ok
    }

    // ----- Q-030: effect handlers (N-043) -----

    @Test
    fun `Handler over a no-arg intercept with a matching handler signature verifies`() {
        // The body calls a no-arg ForeignNode declaring Time.Now (() -> Int);
        // the handler is `\. <fixed-int>` of type () -> Int.
        // The Handler's closure subtracts Time.Now: a surrounding Lambda
        // that doesn't declare Time.Now should verify without UncoveredEffects.
        val r = verify("""{
          "version": 1, "root": "outer",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":   { "type": "FunctionType", "parameters": [], "result": "intT",
                        "effects": ["timeFx"] },
            "now":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                        "foreignType": "nowT", "effects": ["timeFx"] },
            "callNow":{ "type": "Application", "function": "now", "arguments": [] },
            "fakeT":  { "type": "FunctionType", "parameters": [], "result": "intT" },
            "fakeLit":{ "type": "IntLit", "value": 42 },
            "fakeLam":{ "type": "Lambda", "parameters": [], "body": "fakeLit" },
            "handler":{ "type": "Handler", "intercept": "timeFx",
                        "handle": "fakeLam", "body": "callNow" },
            "outerP": { "type": "ParameterDecl", "name": "_", "paramType": "intT" },
            "outer":  { "type": "Lambda", "parameters": ["outerP"], "body": "handler" }
          }
        }""")
        r as VerifyResult.Ok
    }

    @Test
    fun `Handler whose handle has a non-Fun type is rejected as HandlerNotAFunction`() {
        // handle is an IntLit (type Int) — not a function type.
        val r = verify("""{
          "version": 1, "root": "handler",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "notAFn":   { "type": "IntLit", "value": 0 },
            "bodyLit":  { "type": "IntLit", "value": 1 },
            "handler":  { "type": "Handler", "intercept": "timeFx",
                          "handle": "notAFn", "body": "bodyLit" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.HandlerNotAFunction }) {
            "expected HandlerNotAFunction, got ${f.errors}"
        }
    }

    @Test
    fun `Handler whose handle is polymorphic is rejected as HandlerOverPolymorphicHandle`() {
        // handle is `Λa. \x:a. x` — a polymorphic identity. The verifier
        // requires monomorphic handlers.
        val r = verify("""{
          "version": 1, "root": "handler",
          "nodes": {
            "T_a":      { "type": "TypeParameter", "name": "a" },
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "polyP":    { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "polyRef":  { "type": "VarRef", "binder": "polyP" },
            "polyLam":  { "type": "Lambda", "parameters": ["polyP"], "body": "polyRef" },
            "polyAbs":  { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "polyLam" },
            "bodyLit":  { "type": "IntLit", "value": 1 },
            "handler":  { "type": "Handler", "intercept": "timeFx",
                          "handle": "polyAbs", "body": "bodyLit" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.HandlerOverPolymorphicHandle }) {
            "expected HandlerOverPolymorphicHandle, got ${f.errors}"
        }
    }

    @Test
    fun `Handler with signature mismatch is rejected as HandlerSignatureMismatch`() {
        // The intercepted call is now() : () -> Int (zero-arg). The handler
        // signature is (Int) -> Int (one-arg). The verifier's per-call
        // signature-agreement walk should catch this.
        val r = verify("""{
          "version": 1, "root": "handler",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":     { "type": "FunctionType", "parameters": [], "result": "intT",
                          "effects": ["timeFx"] },
            "now":      { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                          "foreignType": "nowT", "effects": ["timeFx"] },
            "callNow":  { "type": "Application", "function": "now", "arguments": [] },

            "wrongP":   { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "wrongRef": { "type": "VarRef", "binder": "wrongP" },
            "wrongLam": { "type": "Lambda", "parameters": ["wrongP"], "body": "wrongRef" },

            "handler":  { "type": "Handler", "intercept": "timeFx",
                          "handle": "wrongLam", "body": "callNow" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.HandlerSignatureMismatch }) {
            "expected HandlerSignatureMismatch, got ${f.errors}"
        }
    }

    @Test
    fun `Handler closure subtraction removes the intercepted effect from the parent`() {
        // The body calls Time.Now (closure {Time.Now}); the Handler should
        // subtract Time.Now. A surrounding Lambda that does NOT declare
        // Time.Now must still verify cleanly. If subtraction didn't work,
        // the verifier would raise UncoveredEffects on the Lambda.
        val r = verify("""{
          "version": 1, "root": "lam",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":     { "type": "FunctionType", "parameters": [], "result": "intT",
                          "effects": ["timeFx"] },
            "now":      { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                          "foreignType": "nowT", "effects": ["timeFx"] },
            "callNow":  { "type": "Application", "function": "now", "arguments": [] },

            "fakeT":    { "type": "FunctionType", "parameters": [], "result": "intT" },
            "fakeLit":  { "type": "IntLit", "value": 42 },
            "fakeLam":  { "type": "Lambda", "parameters": [], "body": "fakeLit" },

            "handler":  { "type": "Handler", "intercept": "timeFx",
                          "handle": "fakeLam", "body": "callNow" },

            "lamP":     { "type": "ParameterDecl", "name": "_", "paramType": "intT" },
            "lam":      { "type": "Lambda", "parameters": ["lamP"], "body": "handler" }
          }
        }""")
        r as VerifyResult.Ok
    }

    @Test
    fun `Handler whose handle itself performs an effect adds that effect to the parent closure`() {
        // The intercepted effect is Time.Now; the handler itself performs
        // Logging.Info. The Handler's closure is (closureOf(body) -
        // {Time.Now}) ∪ closureOf(handle), so Logging.Info should be in
        // the closure. A surrounding Lambda that declares only Time.Now
        // (and NOT Logging.Info) must fail with UncoveredEffects on
        // Logging.Info.
        val r = verify("""{
          "version": 1, "root": "lam",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":    { "type": "EffectCategory", "categoryName": "Time.Now" },
            "logFx":     { "type": "EffectCategory", "categoryName": "Logging.Info" },

            "nowT":      { "type": "FunctionType", "parameters": [], "result": "intT",
                           "effects": ["timeFx"] },
            "now":       { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                           "foreignType": "nowT", "effects": ["timeFx"] },
            "callNow":   { "type": "Application", "function": "now", "arguments": [] },

            "loggerT":   { "type": "FunctionType", "parameters": [], "result": "intT",
                           "effects": ["logFx"] },
            "logger":    { "type": "ForeignNode", "target": "ignored:logging",
                           "foreignType": "loggerT", "effects": ["logFx"] },
            "logCall":   { "type": "Application", "function": "logger", "arguments": [] },
            "handleLam": { "type": "Lambda", "parameters": [], "body": "logCall",
                           "effects": ["logFx"] },

            "handler":   { "type": "Handler", "intercept": "timeFx",
                           "handle": "handleLam", "body": "callNow" },

            "lamP":      { "type": "ParameterDecl", "name": "_", "paramType": "intT" },
            "lam":       { "type": "Lambda", "parameters": ["lamP"], "body": "handler",
                           "effects": ["timeFx"] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any {
            it is VerifyError.UncoveredEffects
        }) { "expected UncoveredEffects (Logging.Info uncovered by outer Lambda), got ${f.errors}" }
    }

    @Test
    fun `Handler whose body does not invoke the intercepted effect is a no-op closure-wise`() {
        // A Handler whose body never calls the intercepted effect just
        // adds the handle's closure (here, empty); the body's closure
        // is unchanged. Verifier should accept this with no errors.
        val r = verify("""{
          "version": 1, "root": "handler",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },

            "fakeT":    { "type": "FunctionType", "parameters": [], "result": "intT" },
            "fakeLit":  { "type": "IntLit", "value": 42 },
            "fakeLam":  { "type": "Lambda", "parameters": [], "body": "fakeLit" },

            "bodyLit":  { "type": "IntLit", "value": 7 },
            "handler":  { "type": "Handler", "intercept": "timeFx",
                          "handle": "fakeLam", "body": "bodyLit" }
          }
        }""")
        r as VerifyResult.Ok
    }

    // ============================================================
    // State machines (N-027..N-029) — Layer 6 step 1
    // ============================================================

    /**
     * Shared toggle-machine fixture for state-machine error tests.
     * Mutates one field per test to produce the targeted error case.
     */
    private fun toggleMachineJson(
        inputStreams: String = "[\"inputStream\"]",
        outputStreams: String = "[]",
        transitionFn: String = "transitionLambda",
        initialState: String = "initialState",
        // Default carries the StateMachine.Receive implicit effect that
        // Layer 6 step 3 slice 3.5 requires of every input-stream-bearing
        // machine. Tests that explicitly probe the slice-3.5 check
        // override this with a list that omits `receiveFx`.
        effects: String = "[\"receiveFx\"]",
    ): String = """{
      "version": 1, "root": "m",
      "nodes": {
        "boolT":   { "type": "PrimitiveType", "kind": "Bool" },
        "unitT":   { "type": "PrimitiveType", "kind": "Unit" },
        "intT":    { "type": "PrimitiveType", "kind": "Int" },
        "emptyT":  { "type": "ProductType", "fields": [] },
        "sft":     { "type": "ProductTypeField", "name": "state",   "fieldType": "boolT" },
        "oft":     { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
        "resT":    { "type": "ProductType", "fields": ["sft", "oft"] },
        "notT":    { "type": "FunctionType", "parameters": ["boolT"], "result": "boolT" },
        "notFn":   { "type": "ForeignNode", "target": "strand-builtin:Bool.Not", "foreignType": "notT" },
        "sP":      { "type": "ParameterDecl", "name": "s", "paramType": "boolT" },
        "eP":      { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
        "sRef":    { "type": "VarRef", "binder": "sP" },
        "neg":     { "type": "Application", "function": "notFn", "arguments": ["sRef"] },
        "sV":      { "type": "ProductFieldValue", "fieldName": "state",   "value": "neg" },
        "emptyV":  { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
        "oV":      { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
        "result":  { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
        "transitionLambda": { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
        "initialState":     { "type": "BoolLit", "value": false },
        "wrongInitialState":{ "type": "IntLit", "value": 0 },
        "inputStream":      { "type": "EventStream", "eventType": "unitT", "streamKind": "external" },
        "inputStreamB":     { "type": "EventStream", "eventType": "unitT", "streamKind": "external" },
        "notALambda":       { "type": "IntLit", "value": 1 },
        "timeFx":           { "type": "EffectCategory", "categoryName": "Time.Now" },
        "receiveFx":        { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
        "sendFx":           { "type": "EffectCategory", "categoryName": "StateMachine.Send" },
        "m": {
          "type": "StateMachine",
          "transitionFn": "$transitionFn",
          "initialState": "$initialState",
          "inputStreams": $inputStreams,
          "outputStreams": $outputStreams,
          "effects": $effects
        }
      }
    }"""

    @Test
    fun `well-formed toggle StateMachine verifies`() {
        val r = verify(toggleMachineJson())
        r as VerifyResult.Ok
    }

    @Test
    fun `StateMachine with empty inputStreams is rejected`() {
        val r = verify(toggleMachineJson(inputStreams = "[]"))
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.StateMachineRequiresInputStream }) {
            "expected StateMachineRequiresInputStream, got ${f.errors}"
        }
    }

    @Test
    fun `StateMachine with two inputStreams whose transition does not match synthesized InputEvent shape is rejected`() {
        // Layer 6 step 2 lifts the step-1 ==1 bound: multi-input machines
        // are now well-formed when their transition function consumes the
        // synthesized InputEvent sum. The toggle machine's transition
        // expects a bare Unit Event (the step-1 single-input shape), so a
        // two-input declaration produces a shape mismatch rather than the
        // old StateMachineInputStreamCountUnsupported rejection.
        val r = verify(toggleMachineJson(inputStreams = "[\"inputStream\", \"inputStreamB\"]"))
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.StateMachineTransitionFnShapeMismatch }) {
            "expected StateMachineTransitionFnShapeMismatch (single-input transition vs multi-input synthesized Event), got ${f.errors}"
        }
    }

    @Test
    fun `StateMachine with non-Lambda transitionFn is rejected`() {
        val r = verify(toggleMachineJson(transitionFn = "notALambda"))
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.StateMachineTransitionFnNotLambda }) {
            "expected StateMachineTransitionFnNotLambda, got ${f.errors}"
        }
    }

    @Test
    fun `StateMachine with initialState type mismatching transition State is rejected`() {
        // initialState is IntLit (type Int) but the transition expects Bool.
        // The verifier raises StateMachineTransitionFnShapeMismatch since the
        // overall (Bool, Unit) -> (Bool, {}) shape doesn't agree with what
        // we'd derive from an IntLit initialState. (The Initial-vs-State
        // mismatch path is the secondary one inside the same check.)
        val r = verify(toggleMachineJson(initialState = "wrongInitialState"))
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any {
            it is VerifyError.StateMachineTransitionFnShapeMismatch ||
                it is VerifyError.StateMachineInitialStateTypeMismatch
        }) {
            "expected ShapeMismatch or InitialStateTypeMismatch, got ${f.errors}"
        }
    }

    @Test
    fun `Transition node in expression position is rejected as TransitionStandalone`() {
        val r = verify("""{
          "version": 1, "root": "t",
          "nodes": {
            "lit":   { "type": "IntLit", "value": 1 },
            "t":     { "type": "Transition", "guard": null, "body": "lit" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.TransitionStandalone }) {
            "expected TransitionStandalone, got ${f.errors}"
        }
    }

    @Test
    fun `EventStream as root expression is rejected`() {
        // EventStreams are not expressions; reaching one through infer()
        // produces a CategoryMismatch (the "<expression position>" path).
        val r = verify("""{
          "version": 1, "root": "s",
          "nodes": {
            "intT": { "type": "PrimitiveType", "kind": "Int" },
            "s":    { "type": "EventStream", "eventType": "intT", "streamKind": "external" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.CategoryMismatch }) {
            "expected CategoryMismatch for EventStream-as-expression, got ${f.errors}"
        }
    }

    @Test
    fun `StateMachine effect coverage violation is reported when transitionFn declares effect StateMachine does not`() {
        // The transition Lambda declares Time.Now; the StateMachine declares
        // only the well-known StateMachine.Receive (so the slice 3.5 check
        // passes) but not Time.Now. The verifier reports
        // StateMachineEffectCoverageViolation for the missing Time.Now.
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "boolT":   { "type": "PrimitiveType", "kind": "Bool" },
            "unitT":   { "type": "PrimitiveType", "kind": "Unit" },
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "emptyT":  { "type": "ProductType", "fields": [] },
            "sft":     { "type": "ProductTypeField", "name": "state",   "fieldType": "boolT" },
            "oft":     { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":    { "type": "ProductType", "fields": ["sft", "oft"] },

            "timeFx":     { "type": "EffectCategory", "categoryName": "Time.Now" },
            "receiveFx":  { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "nowT":    { "type": "FunctionType", "parameters": [], "result": "intT",
                         "effects": ["timeFx"] },
            "nowFn":   { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                         "foreignType": "nowT", "effects": ["timeFx"] },
            "nowCall": { "type": "Application", "function": "nowFn", "arguments": [] },

            "sP":      { "type": "ParameterDecl", "name": "s", "paramType": "boolT" },
            "eP":      { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
            "sRef":    { "type": "VarRef", "binder": "sP" },

            "trueLit":   { "type": "BoolLit", "value": true },
            "falseLit":  { "type": "BoolLit", "value": false },
            "patEven":   { "type": "Pattern", "kind": "wildcard", "patternType": "intT" },
            "caseEven":  { "type": "MatchCase", "pattern": "patEven", "body": "sRef" },
            "stateMatch":{ "type": "Match", "scrutinee": "nowCall", "cases": ["caseEven"] },

            "sV":      { "type": "ProductFieldValue", "fieldName": "state",   "value": "stateMatch" },
            "emptyV":  { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":      { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":  { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },

            "transitionLambda": { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result",
                                  "effects": ["timeFx"] },
            "initialState":     { "type": "BoolLit", "value": false },
            "inputStream":      { "type": "EventStream", "eventType": "unitT", "streamKind": "external" },
            "m": {
              "type": "StateMachine",
              "transitionFn": "transitionLambda",
              "initialState": "initialState",
              "inputStreams": ["inputStream"],
              "outputStreams": [],
              "effects": ["receiveFx"]
            }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.StateMachineEffectCoverageViolation }) {
            "expected StateMachineEffectCoverageViolation, got ${f.errors}"
        }
    }

    @Test
    fun `StateMachine without Receive declared is rejected as MissingImplicitEffect (slice 3-5)`() {
        // Default toggleMachineJson declares ["receiveFx"]; override to []
        // so the slice 3.5 check fires for the missing well-known
        // StateMachine.Receive.
        val r = verify(toggleMachineJson(effects = "[]"))
        val f = r as VerifyResult.Failed
        val missing = f.errors.firstNotNullOfOrNull {
            (it as? VerifyError.StateMachineMissingImplicitEffect)?.missing
        }
        assertNotNull(missing) {
            "expected StateMachineMissingImplicitEffect, got ${f.errors}"
        }
        assertTrue("StateMachine.Receive" in missing!!) {
            "expected missing set to contain StateMachine.Receive, got $missing"
        }
    }

    @Test
    fun `EventStream with bufferSize zero is rejected as MalformedOverflowPolicy (slice 3-1)`() {
        // Use a manually-crafted JSON that overrides the inputStream's
        // bufferSize to 0. The verifier should fire MalformedOverflowPolicy
        // when it reaches the stream during resolveEventStream.
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "boolT":   { "type": "PrimitiveType", "kind": "Bool" },
            "unitT":   { "type": "PrimitiveType", "kind": "Unit" },
            "emptyT":  { "type": "ProductType", "fields": [] },
            "sft":     { "type": "ProductTypeField", "name": "state",   "fieldType": "boolT" },
            "oft":     { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":    { "type": "ProductType", "fields": ["sft", "oft"] },
            "notT":    { "type": "FunctionType", "parameters": ["boolT"], "result": "boolT" },
            "notFn":   { "type": "ForeignNode", "target": "strand-builtin:Bool.Not", "foreignType": "notT" },
            "sP":      { "type": "ParameterDecl", "name": "s", "paramType": "boolT" },
            "eP":      { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
            "sRef":    { "type": "VarRef", "binder": "sP" },
            "neg":     { "type": "Application", "function": "notFn", "arguments": ["sRef"] },
            "sV":      { "type": "ProductFieldValue", "fieldName": "state",   "value": "neg" },
            "emptyV":  { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":      { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":  { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "transitionLambda": { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "initialState":     { "type": "BoolLit", "value": false },
            "receiveFx":        { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "inputStream":      { "type": "EventStream", "eventType": "unitT", "streamKind": "external", "bufferSize": 0 },
            "m": {
              "type": "StateMachine",
              "transitionFn": "transitionLambda",
              "initialState": "initialState",
              "inputStreams": ["inputStream"],
              "outputStreams": [],
              "effects": ["receiveFx"]
            }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.MalformedOverflowPolicy }) {
            "expected MalformedOverflowPolicy for bufferSize=0, got ${f.errors}"
        }
    }

    @Test
    fun `EventStream with valid bufferSize and DropNewest policy verifies cleanly (slice 3-1)`() {
        // Adding the optional slice 3.1 fields with valid values must not
        // disturb the normal verify path — proves the field is accepted
        // through ingest, finalize, and the StateMachine inference.
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "boolT":   { "type": "PrimitiveType", "kind": "Bool" },
            "unitT":   { "type": "PrimitiveType", "kind": "Unit" },
            "emptyT":  { "type": "ProductType", "fields": [] },
            "sft":     { "type": "ProductTypeField", "name": "state",   "fieldType": "boolT" },
            "oft":     { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":    { "type": "ProductType", "fields": ["sft", "oft"] },
            "notT":    { "type": "FunctionType", "parameters": ["boolT"], "result": "boolT" },
            "notFn":   { "type": "ForeignNode", "target": "strand-builtin:Bool.Not", "foreignType": "notT" },
            "sP":      { "type": "ParameterDecl", "name": "s", "paramType": "boolT" },
            "eP":      { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
            "sRef":    { "type": "VarRef", "binder": "sP" },
            "neg":     { "type": "Application", "function": "notFn", "arguments": ["sRef"] },
            "sV":      { "type": "ProductFieldValue", "fieldName": "state",   "value": "neg" },
            "emptyV":  { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":      { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":  { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "transitionLambda": { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "initialState":     { "type": "BoolLit", "value": false },
            "receiveFx":        { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "inputStream":      {
              "type": "EventStream",
              "eventType": "unitT",
              "streamKind": "external",
              "bufferSize": 16,
              "overflowPolicy": "DropNewest"
            },
            "m": {
              "type": "StateMachine",
              "transitionFn": "transitionLambda",
              "initialState": "initialState",
              "inputStreams": ["inputStream"],
              "outputStreams": [],
              "effects": ["receiveFx"]
            }
          }
        }""")
        r as VerifyResult.Ok
    }

    @Test
    fun `EventStream with Sample policy via object form verifies cleanly (slice 3-1)`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "boolT":   { "type": "PrimitiveType", "kind": "Bool" },
            "unitT":   { "type": "PrimitiveType", "kind": "Unit" },
            "emptyT":  { "type": "ProductType", "fields": [] },
            "sft":     { "type": "ProductTypeField", "name": "state",   "fieldType": "boolT" },
            "oft":     { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":    { "type": "ProductType", "fields": ["sft", "oft"] },
            "notT":    { "type": "FunctionType", "parameters": ["boolT"], "result": "boolT" },
            "notFn":   { "type": "ForeignNode", "target": "strand-builtin:Bool.Not", "foreignType": "notT" },
            "sP":      { "type": "ParameterDecl", "name": "s", "paramType": "boolT" },
            "eP":      { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
            "sRef":    { "type": "VarRef", "binder": "sP" },
            "neg":     { "type": "Application", "function": "notFn", "arguments": ["sRef"] },
            "sV":      { "type": "ProductFieldValue", "fieldName": "state",   "value": "neg" },
            "emptyV":  { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":      { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":  { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "transitionLambda": { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "initialState":     { "type": "BoolLit", "value": false },
            "receiveFx":        { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "inputStream":      {
              "type": "EventStream",
              "eventType": "unitT",
              "streamKind": "external",
              "overflowPolicy": { "kind": "Sample", "intervalNanos": 1000000 }
            },
            "m": {
              "type": "StateMachine",
              "transitionFn": "transitionLambda",
              "initialState": "initialState",
              "inputStreams": ["inputStream"],
              "outputStreams": [],
              "effects": ["receiveFx"]
            }
          }
        }""")
        r as VerifyResult.Ok
    }

    @Test
    fun `StateMachine with outputs but no Send declared is rejected as MissingImplicitEffect (slice 3-5)`() {
        // Default machine has no output streams; add one and declare only
        // receiveFx — the slice 3.5 check must report missing
        // StateMachine.Send.
        val r = verify(toggleMachineJson(
            outputStreams = "[\"inputStreamB\"]",
            effects = "[\"receiveFx\"]",
        ))
        val f = r as VerifyResult.Failed
        // The transition function's shape disagrees with the output streams
        // (toggle has no output emission), so we'll see either
        // StateMachineTransitionFnShapeMismatch or MissingImplicitEffect;
        // both are acceptable outcomes — we want at least one of them.
        assertTrue(
            f.errors.any { it is VerifyError.StateMachineMissingImplicitEffect } ||
                f.errors.any { it is VerifyError.StateMachineTransitionFnShapeMismatch }
        ) {
            "expected MissingImplicitEffect or shape mismatch, got ${f.errors}"
        }
    }

    // ----- Layer 2 step 2: NodeRef closure check -----

    @Test
    fun `NodeRef whose target references a free VarRef is rejected as NodeRefTargetMustBeClosed`() {
        // The NodeRef target is a VarRef pointing at a ParameterDecl that is
        // not bound by any enclosing Lambda inside the target subgraph.
        // Under ADR-003's content-addressing invariant, NodeRef targets must
        // be closed terms so their hash is context-independent.
        val r = verify("""{
          "version": 1, "root": "ref",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "x":      { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":   { "type": "VarRef", "binder": "x" },
            "ref":    { "type": "NodeRef", "target": "xRef" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.NodeRefTargetMustBeClosed }) {
            "expected NodeRefTargetMustBeClosed, got ${f.errors}"
        }
        // The wrapper should strip the underlying UnboundVariable, leaving
        // the closure error as the primary diagnostic.
        assertTrue(f.errors.none { it is VerifyError.UnboundVariable }) {
            "UnboundVariable should be folded into NodeRefTargetMustBeClosed; got ${f.errors}"
        }
    }

    @Test
    fun `NodeRef with a closed IntLit target verifies cleanly`() {
        // The shape of corpus program 10-noderef-shared: a NodeRef whose
        // target is a literal — trivially closed. Verification should
        // succeed and the root type should match the target's type.
        val r = verify("""{
          "version": 1, "root": "ref",
          "nodes": {
            "lit": { "type": "IntLit", "value": 7 },
            "ref": { "type": "NodeRef", "target": "lit" }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Int), ok.rootType)
    }

    // ----- Layer 7 step 1: Schema and Invariant (N-032, N-033) -----

    @Test
    fun `Schema in type position resolves to SchemaType`() {
        // Smallest possible schema use: a Lambda whose parameter type is
        // a Schema. The verifier should resolve the Lambda's parameter
        // type to TypeExpr.SchemaType and the Lambda's overall type to
        // (SchemaType(_, Int, [...])) -> SchemaType(_, Int, [...]).
        val r = verify("""{
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
        }""")
        val ok = r as VerifyResult.Ok
        val funType = ok.rootType as TypeExpr.Fun
        assertTrue(funType.parameters[0] is TypeExpr.SchemaType,
            "Lambda parameter type should be SchemaType, got ${funType.parameters[0]}")
        val schemaType = funType.parameters[0] as TypeExpr.SchemaType
        assertEquals(TypeExpr.Prim(Primitive.Int), schemaType.valueType,
            "Schema's valueType should resolve to Int")
        assertEquals(1, schemaType.invariants.size, "expected exactly one invariant")
    }

    @Test
    fun `Application argument of plain Int into SchemaType parameter is accepted`() {
        // Scenario 1 (proposal § 7) at the verifier level: an Int literal
        // flows into a SchemaType-typed parameter position. Verifier
        // accepts (the SchemaChecker would then run the invariant — but
        // here we just confirm the verifier's typesCompatible relaxation
        // permits the assignment).
        val r = verify("""{
          "version": 1, "root": "schemaClaim",
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
            "five":       { "type": "IntLit", "value": 5 },
            "pIn":        { "type": "ParameterDecl", "name": "p", "paramType": "positiveInt" },
            "pInRef":     { "type": "VarRef", "binder": "pIn" },
            "idPos":      { "type": "Lambda", "parameters": ["pIn"], "body": "pInRef" },
            "schemaClaim":{ "type": "Application", "function": "idPos", "arguments": ["five"] }
          }
        }""")
        assertTrue(r is VerifyResult.Ok, "verifier should accept Int into SchemaType<Int>; got $r")
    }

    @Test
    fun `Schema valueType mismatch — Bool into PositiveInt position`() {
        // Scenario 5 (proposal § 7): a BoolLit at a PositiveInt-typed
        // position is rejected by the standard type-checker (not by the
        // invariant phase) — typesCompatible only relaxes when one side
        // is SchemaType-of-the-other-side's-valueType. Bool != Int, so
        // the Lambda's identityOfPositiveInt argument fails.
        val r = verify("""{
          "version": 1, "root": "schemaClaim",
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
            "tru":        { "type": "BoolLit", "value": true },
            "pIn":        { "type": "ParameterDecl", "name": "p", "paramType": "positiveInt" },
            "pInRef":     { "type": "VarRef", "binder": "pIn" },
            "idPos":      { "type": "Lambda", "parameters": ["pIn"], "body": "pInRef" },
            "schemaClaim":{ "type": "Application", "function": "idPos", "arguments": ["tru"] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.ParameterTypeMismatch },
            "expected ParameterTypeMismatch for Bool into PositiveInt position, got ${f.errors}")
    }

    @Test
    fun `Invariant body returning Int is rejected as SchemaInvariantBodyTypeMismatch`() {
        // Scenario 6 (proposal § 7): an Invariant whose body returns Int
        // instead of Bool. The verifier reaches the Schema in a type
        // position, validates each invariant body, and rejects this one
        // because its return type does not match the expected (Int) -> Bool.
        val r = verify("""{
          "version": 1, "root": "idPos",
          "nodes": {
            "intT":       { "type": "PrimitiveType", "kind": "Int" },
            "xParam":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "zero":       { "type": "IntLit", "value": 0 },
            "intReturningBody": { "type": "Lambda", "parameters": ["xParam"], "body": "zero" },
            "badInvariant": {
              "type": "Invariant",
              "invariantName": "always_zero",
              "targetSchema": "positiveInt",
              "body": "intReturningBody"
            },
            "positiveInt": {
              "type": "Schema",
              "schemaName": "PositiveInt",
              "valueType": "intT",
              "invariants": ["badInvariant"]
            },
            "pIn":        { "type": "ParameterDecl", "name": "p", "paramType": "positiveInt" },
            "pInRef":     { "type": "VarRef", "binder": "pIn" },
            "idPos":      { "type": "Lambda", "parameters": ["pIn"], "body": "pInRef" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.SchemaInvariantBodyTypeMismatch },
            "expected SchemaInvariantBodyTypeMismatch, got ${f.errors}")
    }

    @Test
    fun `ForeignNode-typed Invariant body is rejected as SchemaInvariantBodyMustBePure`() {
        // Scenario 8 (proposal § 7): an Invariant whose body is a
        // ForeignNode. Step 1 ships pure-expression invariants only;
        // ForeignNode-typed bodies are rejected.
        val r = verify("""{
          "version": 1, "root": "idPos",
          "nodes": {
            "intT":       { "type": "PrimitiveType", "kind": "Int" },
            "boolT":      { "type": "PrimitiveType", "kind": "Bool" },
            "predFnT":    { "type": "FunctionType", "parameters": ["intT"], "result": "boolT" },
            "predFn":     { "type": "ForeignNode", "target": "strand-builtin:Bool.Not", "foreignType": "predFnT" },
            "fnInvariant": {
              "type": "Invariant",
              "invariantName": "foreign_check",
              "targetSchema": "positiveInt",
              "body": "predFn"
            },
            "positiveInt": {
              "type": "Schema",
              "schemaName": "PositiveInt",
              "valueType": "intT",
              "invariants": ["fnInvariant"]
            },
            "pIn":        { "type": "ParameterDecl", "name": "p", "paramType": "positiveInt" },
            "pInRef":     { "type": "VarRef", "binder": "pIn" },
            "idPos":      { "type": "Lambda", "parameters": ["pIn"], "body": "pInRef" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.SchemaInvariantBodyMustBePure },
            "expected SchemaInvariantBodyMustBePure, got ${f.errors}")
    }

    @Test
    fun `polymorphic Invariant body is rejected as SchemaInvariantBodyMustBeMonomorphic`() {
        // Scenario 7 (proposal § 7): an Invariant whose body has type
        // Forall(a). (a) -> Bool is rejected. Polymorphic invariants
        // would need a separate type-application protocol step 1 does
        // not provide. We wrap a polymorphic identity-returning-true
        // Lambda in a TypeAbstraction to get a Forall-typed body.
        val r = verify("""{
          "version": 1, "root": "idPos",
          "nodes": {
            "intT":       { "type": "PrimitiveType", "kind": "Int" },
            "boolT":      { "type": "PrimitiveType", "kind": "Bool" },
            "T_a":        { "type": "TypeParameter", "name": "a" },
            "aParam":     { "type": "ParameterDecl", "name": "a", "paramType": "T_a" },
            "tru":        { "type": "BoolLit", "value": true },
            "polyBodyLam":{ "type": "Lambda", "parameters": ["aParam"], "body": "tru" },
            "polyBody":   { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "polyBodyLam" },
            "polyInvariant": {
              "type": "Invariant",
              "invariantName": "always_true",
              "targetSchema": "positiveInt",
              "body": "polyBody"
            },
            "positiveInt": {
              "type": "Schema",
              "schemaName": "PositiveInt",
              "valueType": "intT",
              "invariants": ["polyInvariant"]
            },
            "pIn":        { "type": "ParameterDecl", "name": "p", "paramType": "positiveInt" },
            "pInRef":     { "type": "VarRef", "binder": "pIn" },
            "idPos":      { "type": "Lambda", "parameters": ["pIn"], "body": "pInRef" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.SchemaInvariantBodyMustBeMonomorphic },
            "expected SchemaInvariantBodyMustBeMonomorphic, got ${f.errors}")
    }

    @Test
    fun `Invariant whose targetSchema points elsewhere is rejected as InvariantTargetMismatch`() {
        // Defensive topology check: an Invariant whose targetSchema does
        // not match the Schema that lists it in its `invariants` edge is
        // rejected. We point the invariant's targetSchema at a sibling
        // Schema (otherSchema) but list it under positiveInt.
        val r = verify("""{
          "version": 1, "root": "idPos",
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
            "misdirectedInvariant": {
              "type": "Invariant",
              "invariantName": "x_positive",
              "targetSchema": "otherSchema",
              "body": "predLam"
            },
            "otherSchema": {
              "type": "Schema",
              "schemaName": "OtherPositiveInt",
              "valueType": "intT",
              "invariants": []
            },
            "positiveInt": {
              "type": "Schema",
              "schemaName": "PositiveInt",
              "valueType": "intT",
              "invariants": ["misdirectedInvariant"]
            },
            "pIn":        { "type": "ParameterDecl", "name": "p", "paramType": "positiveInt" },
            "pInRef":     { "type": "VarRef", "binder": "pIn" },
            "idPos":      { "type": "Lambda", "parameters": ["pIn"], "body": "pInRef" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.InvariantTargetMismatch },
            "expected InvariantTargetMismatch, got ${f.errors}")
    }

    // ====================================================================
    // Q-039 foreign-effect-projections — verifier rules
    // ====================================================================

    /**
     * Scenario 4 from the proposal § 7. A `Filesystem.Write`-projected
     * `Fs.Write` binding receives an authored EffectDecl whose `path`
     * parameter is a *fresh* StringLit("/safe") while the function's
     * `arguments[0]` is a different StringLit("/etc/shadow"). The two
     * literals have different NodeIds — the projection's `ArgRef(0)`
     * source requires NodeId equality, so the verifier reports
     * `ProjectionMismatch`.
     */
    @Test
    fun `Q-039 ProjectionMismatch when authored EffectDecl drifts from ArgRef projection`() {
        val r = verify("""{
          "version": 1, "root": "writeApp",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "strT":    { "type": "PrimitiveType", "kind": "String" },
            "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
            "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                        "parameters": ["strT"] },
            "writeT":  { "type": "FunctionType",
                        "parameters": ["strT", "bytesT"],
                        "result": "intT" },
            "writeFn": { "type": "ForeignNode",
                        "target": "strand-builtin:Fs.Write",
                        "foreignType": "writeT",
                        "effects": ["writeFx"],
                        "effectProjections": [
                          { "category": "writeFx",
                            "sources": [ { "kind": "ArgRef", "index": 0 } ] }
                        ] },
            "shadow":  { "type": "StringLit", "value": "/etc/shadow" },
            "safe":    { "type": "StringLit", "value": "/safe" },
            "bytes":   { "type": "BytesLit", "value": "deadbeef" },
            "authoredDecl": { "type": "EffectDecl",
                              "effectType": "writeFx",
                              "parameters": ["safe"] },
            "writeApp": { "type": "Application", "function": "writeFn",
                          "arguments": ["shadow", "bytes"],
                          "effectInstances": ["authoredDecl"] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.ProjectionMismatch },
            "expected ProjectionMismatch, got ${f.errors}")
    }

    /**
     * Scenario 6 from the proposal § 7. A `Network.Connect`-projected
     * binding declares only one ProjectionSource for a two-parameter
     * EffectCategory, surfacing as `ProjectionSourceArityMismatch` at
     * admission.
     */
    @Test
    fun `Q-039 ProjectionSourceArityMismatch when sources omit a category parameter`() {
        val r = verify("""{
          "version": 1, "root": "connectFn",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "strT":   { "type": "PrimitiveType", "kind": "String" },
            "connectFx": { "type": "EffectCategory", "categoryName": "Network.Connect",
                          "parameters": ["strT", "intT"] },
            "connectT": { "type": "FunctionType",
                          "parameters": ["strT", "intT"],
                          "result": "intT" },
            "connectFn": { "type": "ForeignNode",
                          "target": "strand-builtin:Net.Connect",
                          "foreignType": "connectT",
                          "effects": ["connectFx"],
                          "effectProjections": [
                            { "category": "connectFx",
                              "sources": [ { "kind": "ArgRef", "index": 0 } ] }
                          ] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.ProjectionSourceArityMismatch },
            "expected ProjectionSourceArityMismatch, got ${f.errors}")
    }

    /**
     * Scenario 7 from the proposal § 7. A projection's `ArgRef(5)`
     * references a position that does not exist in the function's
     * 2-parameter signature, surfacing as `ProjectionArgRefOutOfRange`
     * at admission.
     */
    @Test
    fun `Q-039 ProjectionArgRefOutOfRange when ArgRef index exceeds signature arity`() {
        val r = verify("""{
          "version": 1, "root": "writeFn",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "strT":   { "type": "PrimitiveType", "kind": "String" },
            "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
            "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                        "parameters": ["strT"] },
            "writeT":  { "type": "FunctionType",
                        "parameters": ["strT", "bytesT"],
                        "result": "intT" },
            "writeFn": { "type": "ForeignNode",
                        "target": "strand-builtin:Fs.Write",
                        "foreignType": "writeT",
                        "effects": ["writeFx"],
                        "effectProjections": [
                          { "category": "writeFx",
                            "sources": [ { "kind": "ArgRef", "index": 5 } ] }
                        ] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.ProjectionArgRefOutOfRange },
            "expected ProjectionArgRefOutOfRange, got ${f.errors}")
    }

    /**
     * Scenario 8 from the proposal § 7. A projection source is a
     * `LiteralNode` whose target's type does not structurally equal the
     * category parameter type. Here the category parameter is `String`
     * but the literal is an `IntLit`, surfacing as
     * `ProjectionLiteralTypeMismatch`.
     */
    @Test
    fun `Q-039 ProjectionLiteralTypeMismatch when literal type does not match category param`() {
        val r = verify("""{
          "version": 1, "root": "anthropicFn",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "strT":  { "type": "PrimitiveType", "kind": "String" },
            "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
            "llmGenFx": { "type": "EffectCategory", "categoryName": "LLM.Generate",
                          "parameters": ["strT", "strT"] },
            "sigT":  { "type": "FunctionType",
                      "parameters": ["bytesT"],
                      "result": "bytesT" },
            "wrongLit": { "type": "IntLit", "value": 42 },
            "modelLit": { "type": "StringLit", "value": "claude-3-5-sonnet" },
            "anthropicFn": { "type": "ForeignNode",
                            "target": "strand-builtin:Anthropic.Messages.Create",
                            "foreignType": "sigT",
                            "effects": ["llmGenFx"],
                            "effectProjections": [
                              { "category": "llmGenFx",
                                "sources": [
                                  { "kind": "LiteralNode", "target": "wrongLit" },
                                  { "kind": "LiteralNode", "target": "modelLit" }
                                ] }
                            ] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.ProjectionLiteralTypeMismatch },
            "expected ProjectionLiteralTypeMismatch, got ${f.errors}")
    }

    /**
     * Admission-level coverage: when the projection length does not
     * equal `effects.size`, the verifier raises
     * `ProjectionArityMismatch` (rule 2 of proposal § 5).
     */
    @Test
    fun `Q-039 ProjectionArityMismatch when projection count disagrees with effects count`() {
        val r = verify("""{
          "version": 1, "root": "writeFn",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "strT":  { "type": "PrimitiveType", "kind": "String" },
            "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
            "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                        "parameters": ["strT"] },
            "readFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Read",
                        "parameters": ["strT"] },
            "writeT":  { "type": "FunctionType",
                        "parameters": ["strT", "bytesT"],
                        "result": "intT" },
            "writeFn": { "type": "ForeignNode",
                        "target": "strand-builtin:Fs.Write",
                        "foreignType": "writeT",
                        "effects": ["writeFx", "readFx"],
                        "effectProjections": [
                          { "category": "writeFx",
                            "sources": [ { "kind": "ArgRef", "index": 0 } ] }
                        ] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.ProjectionArityMismatch },
            "expected ProjectionArityMismatch, got ${f.errors}")
    }

    /**
     * Admission-level coverage: when a projection's `category` does
     * not equal the same-position entry in `effects`, the verifier
     * raises `ProjectionCategoryMismatch` (rule 3 of proposal § 5).
     */
    @Test
    fun `Q-039 ProjectionCategoryMismatch when projection category disagrees with effects position`() {
        val r = verify("""{
          "version": 1, "root": "writeFn",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "strT":  { "type": "PrimitiveType", "kind": "String" },
            "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
            "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                        "parameters": ["strT"] },
            "readFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Read",
                        "parameters": ["strT"] },
            "writeT":  { "type": "FunctionType",
                        "parameters": ["strT", "bytesT"],
                        "result": "intT" },
            "writeFn": { "type": "ForeignNode",
                        "target": "strand-builtin:Fs.Write",
                        "foreignType": "writeT",
                        "effects": ["writeFx"],
                        "effectProjections": [
                          { "category": "readFx",
                            "sources": [ { "kind": "ArgRef", "index": 0 } ] }
                        ] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.ProjectionCategoryMismatch },
            "expected ProjectionCategoryMismatch, got ${f.errors}")
    }

    /**
     * Admission-level coverage: when a [ProjectionSource.LiteralNode]
     * target does not resolve to a literal node, the verifier raises
     * `ProjectionLiteralNotConstant` (rule 6 of proposal § 5).
     */
    @Test
    fun `Q-039 ProjectionLiteralNotConstant when target is not a literal`() {
        val r = verify("""{
          "version": 1, "root": "fn",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "strT":  { "type": "PrimitiveType", "kind": "String" },
            "bytesT":{ "type": "PrimitiveType", "kind": "Bytes" },
            "llmFx": { "type": "EffectCategory", "categoryName": "LLM.Generate",
                      "parameters": ["strT"] },
            "addT":  { "type": "FunctionType",
                      "parameters": ["intT", "intT"], "result": "intT" },
            "addFn": { "type": "ForeignNode",
                      "target": "strand-builtin:Int.Add",
                      "foreignType": "addT" },
            "sigT":  { "type": "FunctionType",
                      "parameters": ["bytesT"],
                      "result": "bytesT" },
            "fn":    { "type": "ForeignNode",
                      "target": "strand-builtin:Anthropic.Messages.Create",
                      "foreignType": "sigT",
                      "effects": ["llmFx"],
                      "effectProjections": [
                        { "category": "llmFx",
                          "sources": [
                            { "kind": "LiteralNode", "target": "addFn" }
                          ] }
                      ] }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(f.errors.any { it is VerifyError.ProjectionLiteralNotConstant },
            "expected ProjectionLiteralNotConstant, got ${f.errors}")
    }

    /**
     * Happy path: a well-formed projection admits and a matching
     * authored EffectDecl at the call site verifies clean. This is
     * scenarios 1 + 3 from the proposal § 7 combined — the verifier
     * accepts both forms (synthesis path when effectInstances is
     * empty, structural-match path when it's populated).
     */
    @Test
    fun `Q-039 well-formed projection with NodeId-matched authored EffectDecl verifies`() {
        val r = verify("""{
          "version": 1, "root": "writeApp",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "strT":    { "type": "PrimitiveType", "kind": "String" },
            "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
            "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                        "parameters": ["strT"] },
            "writeT":  { "type": "FunctionType",
                        "parameters": ["strT", "bytesT"],
                        "result": "intT" },
            "writeFn": { "type": "ForeignNode",
                        "target": "strand-builtin:Fs.Write",
                        "foreignType": "writeT",
                        "effects": ["writeFx"],
                        "effectProjections": [
                          { "category": "writeFx",
                            "sources": [ { "kind": "ArgRef", "index": 0 } ] }
                        ] },
            "safe":    { "type": "StringLit", "value": "/safe" },
            "bytes":   { "type": "BytesLit", "value": "deadbeef" },
            "authoredDecl": { "type": "EffectDecl",
                              "effectType": "writeFx",
                              "parameters": ["safe"] },
            "writeApp": { "type": "Application", "function": "writeFn",
                          "arguments": ["safe", "bytes"],
                          "effectInstances": ["authoredDecl"] }
          }
        }""")
        assertTrue(r is VerifyResult.Ok,
            "expected Ok for a well-formed Q-039 projection at a matching call site, got $r")
    }

    // ----- N-048 RecursiveProjection (Q-053) -----
    //
    // The shared nested-μ model used by several tests below:
    //
    //   jsonValueT = μ jv. JsonNumber(Int) | JsonArray(innerListT)
    //   innerListT = μ list. Cons(head: RecursiveSelf 1, tail: RecursiveSelf 0) | Nil
    //
    // The inner list's head reaches past the `list` binder (depth 1) to the
    // outer `jv` binder, so the list is a list OF json values — the precise
    // model the corpus-66 splice cannot express. A value-construction site
    // names a RecursiveProjection of jsonValueT selecting the position it
    // builds, never the bare open innerListT.

    private fun jsonModelNodes(): String = """
        "intT":        { "type": "PrimitiveType", "kind": "Int" },

        "selfOuter":   { "type": "RecursiveSelf", "depth": 1 },
        "selfInner":   { "type": "RecursiveSelf", "depth": 0 },
        "headField":   { "type": "ProductTypeField", "name": "head", "fieldType": "selfOuter" },
        "tailField":   { "type": "ProductTypeField", "name": "tail", "fieldType": "selfInner" },
        "consProduct": { "type": "ProductType", "fields": ["headField", "tailField"] },
        "consCase":    { "type": "SumTypeCase", "name": "Cons", "caseType": "consProduct" },
        "nilCase":     { "type": "SumTypeCase", "name": "Nil", "caseType": null },
        "listBody":    { "type": "SumType", "cases": ["consCase", "nilCase"] },
        "innerListT":  { "type": "RecursiveType", "body": "listBody" },

        "numCase":     { "type": "SumTypeCase", "name": "JsonNumber", "caseType": "intT" },
        "arrCase":     { "type": "SumTypeCase", "name": "JsonArray", "caseType": "innerListT" },
        "jvBody":      { "type": "SumType", "cases": ["numCase", "arrCase"] },
        "jsonValueT":  { "type": "RecursiveType", "body": "jvBody" },

        "projTop":     { "type": "RecursiveProjection", "recursiveType": "jsonValueT",
                         "path": [ { "step": "Unfold" } ] },
        "projList":    { "type": "RecursiveProjection", "recursiveType": "jsonValueT",
                         "path": [ { "step": "Case", "caseName": "JsonArray" }, { "step": "Unfold" } ] }
    """.trimIndent()

    @Test
    fun `RecursiveProjection - true JSON array of one type-checks with precision`() {
        // Build [1]: JsonArray( Cons(JsonNumber(1), Nil) ).
        val r = verify("""{
          "version": 1, "root": "arrayValue",
          "nodes": {
            ${jsonModelNodes()},

            "one":       { "type": "IntLit", "value": 1 },
            "numOne":    { "type": "SumValue", "ofType": "projTop", "caseName": "JsonNumber", "payload": "one" },

            "nilVal":    { "type": "SumValue", "ofType": "projList", "caseName": "Nil", "payload": null },
            "consHead":  { "type": "ProductFieldValue", "fieldName": "head", "value": "numOne" },
            "consTail":  { "type": "ProductFieldValue", "fieldName": "tail", "value": "nilVal" },
            "consProj":  { "type": "RecursiveProjection", "recursiveType": "jsonValueT",
                           "path": [ { "step": "Case", "caseName": "JsonArray" },
                                     { "step": "Unfold" },
                                     { "step": "Case", "caseName": "Cons" } ] },
            "consVal":   { "type": "ProductValue", "ofType": "consProj", "fields": ["consHead", "consTail"] },
            "listVal":   { "type": "SumValue", "ofType": "projList", "caseName": "Cons", "payload": "consVal" },

            "arrayValue":{ "type": "SumValue", "ofType": "projTop", "caseName": "JsonArray", "payload": "listVal" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok,
            "expected Ok for the precise nested-μ JSON array [1], got $r")
    }

    @Test
    fun `RecursiveProjection - malformed array spine is a verify error`() {
        // Cons(JsonNumber(1), JsonString("x")) — the tail must inhabit the
        // inner list (Cons | Nil), but a bare JsonNumber does not, so the
        // tail's payload type mismatches.
        val r = verify("""{
          "version": 1, "root": "consVal",
          "nodes": {
            ${jsonModelNodes()},
            "strT":      { "type": "PrimitiveType", "kind": "String" },

            "one":       { "type": "IntLit", "value": 1 },
            "numOne":    { "type": "SumValue", "ofType": "projTop", "caseName": "JsonNumber", "payload": "one" },

            "consHead":  { "type": "ProductFieldValue", "fieldName": "head", "value": "numOne" },
            "consTail":  { "type": "ProductFieldValue", "fieldName": "tail", "value": "numOne" },
            "consProj":  { "type": "RecursiveProjection", "recursiveType": "jsonValueT",
                           "path": [ { "step": "Case", "caseName": "JsonArray" },
                                     { "step": "Unfold" },
                                     { "step": "Case", "caseName": "Cons" } ] },
            "consVal":   { "type": "ProductValue", "ofType": "consProj", "fields": ["consHead", "consTail"] }
          }
        }""")
        assertTrue(r is VerifyResult.Failed,
            "expected a verify failure for a malformed array spine, got $r")
        r as VerifyResult.Failed
        assertTrue(r.errors.any { it is VerifyError.ProductFieldValueTypeMismatch },
            "expected a ProductFieldValueTypeMismatch for the ill-typed tail, got ${r.errors}")
    }

    @Test
    fun `RecursiveProjection - top-level inhabitant via single Unfold path`() {
        // The common non-nested case: a bare JsonNumber(7) as a JsonValue.
        val r = verify("""{
          "version": 1, "root": "num",
          "nodes": {
            ${jsonModelNodes()},
            "seven": { "type": "IntLit", "value": 7 },
            "num":   { "type": "SumValue", "ofType": "projTop", "caseName": "JsonNumber", "payload": "seven" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok,
            "expected Ok for a top-level JsonNumber via [Unfold], got $r")
    }

    @Test
    fun `RecursiveProjection - AST with child list resolves`() {
        // ast = μ a. Lit(Int) | Node(innerAstListT)
        // innerAstListT = μ l. Cons(head: RecursiveSelf 1, tail: RecursiveSelf 0) | Nil
        // Build Node([Lit(1)]).
        val r = verify("""{
          "version": 1, "root": "tree",
          "nodes": {
            "intT":        { "type": "PrimitiveType", "kind": "Int" },
            "selfOuter":   { "type": "RecursiveSelf", "depth": 1 },
            "selfInner":   { "type": "RecursiveSelf", "depth": 0 },
            "headField":   { "type": "ProductTypeField", "name": "head", "fieldType": "selfOuter" },
            "tailField":   { "type": "ProductTypeField", "name": "tail", "fieldType": "selfInner" },
            "consProduct": { "type": "ProductType", "fields": ["headField", "tailField"] },
            "consCase":    { "type": "SumTypeCase", "name": "Cons", "caseType": "consProduct" },
            "nilCase":     { "type": "SumTypeCase", "name": "Nil", "caseType": null },
            "listBody":    { "type": "SumType", "cases": ["consCase", "nilCase"] },
            "childListT":  { "type": "RecursiveType", "body": "listBody" },

            "litCase":     { "type": "SumTypeCase", "name": "Lit", "caseType": "intT" },
            "nodeCase":    { "type": "SumTypeCase", "name": "Node", "caseType": "childListT" },
            "astBody":     { "type": "SumType", "cases": ["litCase", "nodeCase"] },
            "astT":        { "type": "RecursiveType", "body": "astBody" },

            "projTop":     { "type": "RecursiveProjection", "recursiveType": "astT",
                             "path": [ { "step": "Unfold" } ] },
            "projList":    { "type": "RecursiveProjection", "recursiveType": "astT",
                             "path": [ { "step": "Case", "caseName": "Node" }, { "step": "Unfold" } ] },
            "projCons":    { "type": "RecursiveProjection", "recursiveType": "astT",
                             "path": [ { "step": "Case", "caseName": "Node" },
                                       { "step": "Unfold" },
                                       { "step": "Case", "caseName": "Cons" } ] },

            "one":       { "type": "IntLit", "value": 1 },
            "lit1":      { "type": "SumValue", "ofType": "projTop", "caseName": "Lit", "payload": "one" },
            "nilVal":    { "type": "SumValue", "ofType": "projList", "caseName": "Nil", "payload": null },
            "consHead":  { "type": "ProductFieldValue", "fieldName": "head", "value": "lit1" },
            "consTail":  { "type": "ProductFieldValue", "fieldName": "tail", "value": "nilVal" },
            "consVal":   { "type": "ProductValue", "ofType": "projCons", "fields": ["consHead", "consTail"] },
            "childList": { "type": "SumValue", "ofType": "projList", "caseName": "Cons", "payload": "consVal" },
            "tree":      { "type": "SumValue", "ofType": "projTop", "caseName": "Node", "payload": "childList" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok,
            "expected Ok for an AST node with a child list, got $r")
    }

    @Test
    fun `RecursiveProjection - target not recursive`() {
        val r = verify("""{
          "version": 1, "root": "num",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "case":  { "type": "SumTypeCase", "name": "A", "caseType": "intT" },
            "sumT":  { "type": "SumType", "cases": ["case"] },
            "proj":  { "type": "RecursiveProjection", "recursiveType": "sumT",
                       "path": [ { "step": "Case", "caseName": "A" } ] },
            "one":   { "type": "IntLit", "value": 1 },
            "num":   { "type": "SumValue", "ofType": "proj", "caseName": "A", "payload": "one" }
          }
        }""")
        assertTrue(r is VerifyResult.Failed)
        r as VerifyResult.Failed
        assertTrue(r.errors.any { it is VerifyError.RecursiveProjectionTargetNotRecursive },
            "expected RecursiveProjectionTargetNotRecursive, got ${r.errors}")
    }

    @Test
    fun `RecursiveProjection - target not closed`() {
        // The outer μ's body references depth=1, but there is only one
        // enclosing binder (the μ itself, depth 0): the body reaches a
        // binder outside itself, so the target is open.
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "escape":  { "type": "RecursiveSelf", "depth": 1 },
            "field":   { "type": "ProductTypeField", "name": "f", "fieldType": "escape" },
            "prod":    { "type": "ProductType", "fields": ["field"] },
            "case":    { "type": "SumTypeCase", "name": "A", "caseType": "prod" },
            "body":    { "type": "SumType", "cases": ["case"] },
            "openMu":  { "type": "RecursiveType", "body": "body" },
            "proj":    { "type": "RecursiveProjection", "recursiveType": "openMu",
                         "path": [ { "step": "Unfold" } ] },
            "v":       { "type": "ProductValue", "ofType": "proj", "fields": [] }
          }
        }""")
        assertTrue(r is VerifyResult.Failed)
        r as VerifyResult.Failed
        // An open μ is rejected. The verifier reaches the closed-ness check
        // (RecursiveProjectionTargetNotClosed) or the underlying
        // UnboundRecursiveSelf when resolving the μ standalone; either is a
        // sound rejection of the open target. Assert at least one fires.
        assertTrue(
            r.errors.any {
                it is VerifyError.RecursiveProjectionTargetNotClosed ||
                    it is VerifyError.UnboundRecursiveSelf
            },
            "expected the open μ target to be rejected, got ${r.errors}")
    }

    @Test
    fun `RecursiveProjection - case not found`() {
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            ${jsonModelNodes()},
            "proj":  { "type": "RecursiveProjection", "recursiveType": "jsonValueT",
                       "path": [ { "step": "Case", "caseName": "Nope" } ] },
            "v":     { "type": "ProductValue", "ofType": "proj", "fields": [] }
          }
        }""")
        assertTrue(r is VerifyResult.Failed)
        r as VerifyResult.Failed
        assertTrue(r.errors.any { it is VerifyError.RecursiveProjectionCaseNotFound },
            "expected RecursiveProjectionCaseNotFound, got ${r.errors}")
    }

    @Test
    fun `RecursiveProjection - field not found`() {
        // Route the root at a value whose ofType is the projection so the
        // verifier resolves the projection's path.
        val r2 = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            ${jsonModelNodes()},
            "proj":  { "type": "RecursiveProjection", "recursiveType": "jsonValueT",
                       "path": [ { "step": "Case", "caseName": "JsonArray" },
                                 { "step": "Unfold" },
                                 { "step": "Case", "caseName": "Cons" },
                                 { "step": "Field", "fieldName": "nope" } ] },
            "x":     { "type": "IntLit", "value": 0 },
            "v":     { "type": "ProductValue", "ofType": "proj", "fields": [] }
          }
        }""")
        assertTrue(r2 is VerifyResult.Failed)
        r2 as VerifyResult.Failed
        assertTrue(r2.errors.any { it is VerifyError.RecursiveProjectionFieldNotFound },
            "expected RecursiveProjectionFieldNotFound, got ${r2.errors}")
    }

    @Test
    fun `RecursiveProjection - path step mismatch on Field over a Sum focus`() {
        // A Field step applied directly to the outer μ (whose unfold is a
        // Sum, not a Product) is a step mismatch.
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            ${jsonModelNodes()},
            "proj":  { "type": "RecursiveProjection", "recursiveType": "jsonValueT",
                       "path": [ { "step": "Field", "fieldName": "head" } ] },
            "x":     { "type": "IntLit", "value": 0 },
            "v":     { "type": "ProductValue", "ofType": "proj", "fields": [] }
          }
        }""")
        assertTrue(r is VerifyResult.Failed)
        r as VerifyResult.Failed
        assertTrue(r.errors.any { it is VerifyError.RecursiveProjectionPathStepMismatch },
            "expected RecursiveProjectionPathStepMismatch for Field over a Sum focus, got ${r.errors}")
    }

    @Test
    fun `RecursiveProjection - selecting a nullary case is rejected`() {
        val r = verify("""{
          "version": 1, "root": "v",
          "nodes": {
            ${jsonModelNodes()},
            "proj":  { "type": "RecursiveProjection", "recursiveType": "jsonValueT",
                       "path": [ { "step": "Case", "caseName": "JsonArray" },
                                 { "step": "Unfold" },
                                 { "step": "Case", "caseName": "Nil" } ] },
            "x":     { "type": "IntLit", "value": 0 },
            "v":     { "type": "ProductValue", "ofType": "proj", "fields": [] }
          }
        }""")
        assertTrue(r is VerifyResult.Failed)
        r as VerifyResult.Failed
        assertTrue(r.errors.any { it is VerifyError.RecursiveProjectionPathSelectsNullaryCase },
            "expected RecursiveProjectionPathSelectsNullaryCase, got ${r.errors}")
    }
}
