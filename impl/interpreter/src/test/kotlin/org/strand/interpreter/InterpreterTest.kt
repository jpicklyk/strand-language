package org.strand.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher

class InterpreterTest {

    /**
     * Result of `ingestAndGetIds`: the finalized canonical [NodeStore] (with
     * Hash-bearing NodeRefs), the root [NodeId], the author-id → NodeId map
     * for picking out EffectCategory ids, and the Hash → NodeId reverse map
     * the verifier and interpreter need to resolve NodeRefs.
     */
    private data class Loaded(
        val store: NodeStore,
        val root: NodeId,
        val names: Map<String, NodeId>,
        val hashToNodeId: Map<Hash, NodeId>,
    )

    private fun run(json: String): Value {
        val l = ingestAndGetIds(json)
        return Interpreter(l.store, l.hashToNodeId).eval(l.root)
    }

    @Test
    fun `int literal evaluates to itself`() {
        assertEquals(Value.IntV(7),
            run("""{ "version": 1, "root": "x",
                    "nodes": { "x": { "type": "IntLit", "value": 7 } } }"""))
    }

    @Test
    fun `identity applied to int returns the int`() {
        val v = run("""{
          "version": 1, "root": "app",
          "nodes": {
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "arg":     { "type": "IntLit", "value": 42 },
            "app":     { "type": "Application", "function": "id", "arguments": ["arg"], "typeArguments": ["intT"] }
          }
        }""")
        assertEquals(Value.IntV(42), v)
    }

    @Test
    fun `K combinator returns first argument`() {
        val v = run("""{
          "version": 1, "root": "app",
          "nodes": {
            "T_a":    { "type": "TypeParameter", "name": "a" },
            "T_b":    { "type": "TypeParameter", "name": "b" },
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "boolT":  { "type": "PrimitiveType", "kind": "Bool" },
            "x":      { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "y":      { "type": "ParameterDecl", "name": "y", "paramType": "T_b" },
            "xRef":   { "type": "VarRef", "binder": "x" },
            "kInner": { "type": "Lambda", "parameters": ["x", "y"], "body": "xRef" },
            "k":      { "type": "TypeAbstraction", "typeParameters": ["T_a", "T_b"], "body": "kInner" },
            "a1":     { "type": "IntLit", "value": 1 },
            "a2":     { "type": "BoolLit", "value": false },
            "app":    { "type": "Application", "function": "k", "arguments": ["a1", "a2"], "typeArguments": ["intT", "boolT"] }
          }
        }""")
        assertEquals(Value.IntV(1), v)
    }

    @Test
    fun `let-bound identity is callable`() {
        val v = run("""{
          "version": 1, "root": "letId",
          "nodes": {
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "boolT":   { "type": "PrimitiveType", "kind": "Bool" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "idLam":   { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "idUse":   { "type": "VarRef", "binder": "letId" },
            "arg":     { "type": "BoolLit", "value": true },
            "call":    { "type": "Application", "function": "idUse", "arguments": ["arg"], "typeArguments": ["boolT"] },
            "letId":   { "type": "Let", "name": "id", "value": "idLam", "body": "call" }
          }
        }""")
        assertEquals(Value.BoolV(true), v)
    }

    @Test
    fun `noderef is transparent`() {
        val v = run("""{
          "version": 1, "root": "ref",
          "nodes": {
            "lit": { "type": "IntLit", "value": 5 },
            "ref": { "type": "NodeRef", "target": "lit" }
          }
        }""")
        assertEquals(Value.IntV(5), v)
    }

    // ----- Layer 3: capability checking -----

    private fun ingestAndGetIds(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Loaded(finalized.store, finalized.root, ingest.nameMap, finalized.hashToNodeId)
    }

    private val effectfulCallJson = """{
        "version": 1, "root": "callG",
        "nodes": {
          "intT":   { "type": "PrimitiveType", "kind": "Int" },
          "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
          "gX":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
          "gBody":  { "type": "VarRef", "binder": "gX" },
          "g":      { "type": "Lambda", "parameters": ["gX"], "body": "gBody", "effects": ["timeFx"] },
          "seven":  { "type": "IntLit", "value": 7 },
          "callG":  { "type": "Application", "function": "g", "arguments": ["seven"] }
        }
    }"""

    @Test
    fun `application of effectful Lambda succeeds when context grants the effect`() {
        val (store, root, names, hashToNodeId) =ingestAndGetIds(effectfulCallJson)
        val timeFx = names.getValue("timeFx")
        val v = Interpreter(store, hashToNodeId).eval(root, capabilities = setOf(timeFx))
        assertEquals(Value.IntV(7), v)
    }

    @Test
    fun `application of effectful Lambda raises CapabilityViolation without grant`() {
        val (store, root, names, hashToNodeId) =ingestAndGetIds(effectfulCallJson)
        val timeFx = names.getValue("timeFx")
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(store, hashToNodeId).eval(root, capabilities = emptySet())
        }
        val err = ex.error as InterpretError.CapabilityViolation
        assertEquals(setOf(timeFx), err.missing)
    }

    @Test
    fun `CapabilityScope narrowing away a needed effect raises at runtime`() {
        // CapabilityScope narrows to empty; the body's Application of g (which
        // declares Time.Now) sees the narrowed context and fails. This mirrors
        // the verifier's CapabilityScopeUnsatisfiable check at the runtime
        // layer: even if a graph wasn't pre-verified, the interpreter catches
        // the violation.
        val json = """{
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
        }"""
        val (store, root, names, hashToNodeId) =ingestAndGetIds(json)
        val timeFx = names.getValue("timeFx")
        val ex = assertThrows(InterpretException::class.java) {
            // Even if the outer context grants the effect, the narrowing
            // strips it before the inner call.
            Interpreter(store, hashToNodeId).eval(root, capabilities = setOf(timeFx))
        }
        assertTrue(ex.error is InterpretError.CapabilityViolation)
    }

    @Test
    fun `CapabilityScope retaining the needed effect lets the body proceed`() {
        val json = """{
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
        }"""
        val (store, root, names, hashToNodeId) =ingestAndGetIds(json)
        val timeFx = names.getValue("timeFx")
        val v = Interpreter(store, hashToNodeId).eval(root, capabilities = setOf(timeFx))
        assertEquals(Value.IntV(7), v)
    }

    // ----- Layer 4 step 1: ForeignNode + builtins -----

    @Test
    fun `pure builtin Int Add returns the sum`() {
        val v = run("""{
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
        assertEquals(Value.IntV(5), v)
    }

    @Test
    fun `effectful Time Now builtin returns the fixed replay timestamp under granted capability`() {
        val json = """{
          "version": 1, "root": "app",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":   { "type": "FunctionType", "parameters": [], "result": "intT" },
            "now":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now", "foreignType": "nowT", "effects": ["timeFx"] },
            "app":    { "type": "Application", "function": "now", "arguments": [] }
          }
        }"""
        val (store, root, names, hashToNodeId) =ingestAndGetIds(json)
        val timeFx = names.getValue("timeFx")
        val v = Interpreter(store, hashToNodeId).eval(root, capabilities = setOf(timeFx))
        assertEquals(Value.IntV(Builtins.FIXED_REPLAY_TIMESTAMP), v)
    }

    @Test
    fun `effectful builtin raises CapabilityViolation when context lacks the effect`() {
        val json = """{
          "version": 1, "root": "app",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":   { "type": "FunctionType", "parameters": [], "result": "intT" },
            "now":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now", "foreignType": "nowT", "effects": ["timeFx"] },
            "app":    { "type": "Application", "function": "now", "arguments": [] }
          }
        }"""
        val (store, root, names, hashToNodeId) =ingestAndGetIds(json)
        val timeFx = names.getValue("timeFx")
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(store, hashToNodeId).eval(root, capabilities = emptySet())
        }
        val err = ex.error as InterpretError.CapabilityViolation
        assertEquals(setOf(timeFx), err.missing)
    }

    @Test
    fun `unknown ForeignNode target raises UnknownForeignTarget`() {
        val json = """{
          "version": 1, "root": "app",
          "nodes": {
            "intT": { "type": "PrimitiveType", "kind": "Int" },
            "fnT":  { "type": "FunctionType", "parameters": [], "result": "intT" },
            "fn":   { "type": "ForeignNode", "target": "strand-builtin:Does.Not.Exist", "foreignType": "fnT" },
            "app":  { "type": "Application", "function": "fn", "arguments": [] }
          }
        }"""
        val (store, root, _, hashToNodeId) =ingestAndGetIds(json)
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(store, hashToNodeId).eval(root)
        }
        val err = ex.error as InterpretError.UnknownForeignTarget
        assertEquals("strand-builtin:Does.Not.Exist", err.target)
    }

    // ----- Layer 5 step 1: Match -----

    @Test
    fun `Match with no matching case raises NoMatchingCase at runtime`() {
        // Scrutinee = 1; only case matches literal 0; no wildcard fallback.
        val json = """{
          "version": 1, "root": "m",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "z":     { "type": "IntLit", "value": 0 },
            "pat":   { "type": "Pattern", "kind": "literal", "patternType": "intT", "literal": "z" },
            "body":  { "type": "IntLit", "value": 42 },
            "case":  { "type": "MatchCase", "pattern": "pat", "body": "body" },
            "scrut": { "type": "IntLit", "value": 1 },
            "m":     { "type": "Match", "scrutinee": "scrut", "cases": ["case"] }
          }
        }"""
        val ex = assertThrows(InterpretException::class.java) {
            run(json)
        }
        assertTrue(ex.error is InterpretError.NoMatchingCase) {
            "expected NoMatchingCase, got: ${ex.error}"
        }
    }

    @Test
    fun `Variable pattern binds the scrutinee for use inside the case body`() {
        // Match on 7; variable pattern binds n; body returns n unchanged.
        val v = run("""{
          "version": 1, "root": "m",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "patN":  { "type": "Pattern", "kind": "variable", "patternType": "intT", "name": "n" },
            "nRef":  { "type": "VarRef", "binder": "patN" },
            "case":  { "type": "MatchCase", "pattern": "patN", "body": "nRef" },
            "scrut": { "type": "IntLit", "value": 7 },
            "m":     { "type": "Match", "scrutinee": "scrut", "cases": ["case"] }
          }
        }""")
        assertEquals(Value.IntV(7), v)
    }

    // ----- Layer 5 step 2: Fixpoint -----

    @Test
    fun `Fixpoint base case returns without recursing`() {
        // A Fixpoint whose body always returns 42 regardless of input.
        // No actual recursion exercised; tests the call-arity contract
        // (user-arity = body-arity - 1).
        val v = run("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "recT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "recurse":  { "type": "ParameterDecl", "name": "recurse", "paramType": "recT" },
            "n":        { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "lit42":    { "type": "IntLit", "value": 42 },
            "bodyLam":  { "type": "Lambda", "parameters": ["recurse", "n"], "body": "lit42" },
            "fn":       { "type": "Fixpoint", "recursionType": "recT", "body": "bodyLam" },
            "input":    { "type": "IntLit", "value": 99 },
            "app":      { "type": "Application", "function": "fn", "arguments": ["input"] }
          }
        }""")
        assertEquals(Value.IntV(42), v)
    }

    // ----- Layer 5 step 3a: product values -----

    @Test
    fun `ProductValue constructs and ProductFieldGet retrieves a field`() {
        val v = run("""{
          "version": 1, "root": "getX",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "xField":    { "type": "ProductTypeField", "name": "x", "fieldType": "intT" },
            "yField":    { "type": "ProductTypeField", "name": "y", "fieldType": "intT" },
            "pointType": { "type": "ProductType", "fields": ["xField", "yField"] },
            "lit3":      { "type": "IntLit", "value": 3 },
            "lit4":      { "type": "IntLit", "value": 4 },
            "xValue":    { "type": "ProductFieldValue", "fieldName": "x", "value": "lit3" },
            "yValue":    { "type": "ProductFieldValue", "fieldName": "y", "value": "lit4" },
            "point":     { "type": "ProductValue", "ofType": "pointType", "fields": ["xValue", "yValue"] },
            "getX":      { "type": "ProductFieldGet", "target": "point", "fieldName": "x" }
          }
        }""")
        assertEquals(Value.IntV(3), v)
    }

    // ----- Layer 5 step 3b: sum values + constructor patterns -----

    private val optionHeader = """
        "intT":     { "type": "PrimitiveType", "kind": "Int" },
        "someCase": { "type": "SumTypeCase", "name": "Some", "caseType": "intT" },
        "noneCase": { "type": "SumTypeCase", "name": "None", "caseType": null },
        "optionT":  { "type": "SumType", "cases": ["someCase", "noneCase"] },
    """.trimIndent()

    @Test
    fun `ConstructorPattern on matching Some case binds payload to variable`() {
        val v = run("""{
          "version": 1, "root": "m",
          "nodes": {
            $optionHeader
            "lit42":     { "type": "IntLit", "value": 42 },
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
        assertEquals(Value.IntV(42), v)
    }

    @Test
    fun `ConstructorPattern on non-matching case falls through to next case`() {
        // Scrutinee is None; Some pattern should fail, None should match.
        val v = run("""{
          "version": 1, "root": "m",
          "nodes": {
            $optionHeader
            "v":         { "type": "SumValue", "ofType": "optionT", "caseName": "None", "payload": null },
            "varN":      { "type": "Pattern", "kind": "variable", "patternType": "intT", "name": "n" },
            "patSome":   { "type": "Pattern", "kind": "constructor", "patternType": "optionT", "caseName": "Some", "payloadPattern": "varN" },
            "nRef":      { "type": "VarRef", "binder": "varN" },
            "caseSome":  { "type": "MatchCase", "pattern": "patSome", "body": "nRef" },
            "patNone":   { "type": "Pattern", "kind": "constructor", "patternType": "optionT", "caseName": "None" },
            "fallback":  { "type": "IntLit", "value": -1 },
            "caseNone":  { "type": "MatchCase", "pattern": "patNone", "body": "fallback" },
            "m":         { "type": "Match", "scrutinee": "v", "cases": ["caseSome", "caseNone"] }
          }
        }""")
        assertEquals(Value.IntV(-1), v)
    }

    @Test
    fun `ProductValue field-construction order does not matter for the resulting value`() {
        // Same logical point {x:3, y:4} but the ProductValue lists yValue
        // first, then xValue. Should still evaluate to the same point and
        // getX should still return 3.
        val v = run("""{
          "version": 1, "root": "getX",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "xField":    { "type": "ProductTypeField", "name": "x", "fieldType": "intT" },
            "yField":    { "type": "ProductTypeField", "name": "y", "fieldType": "intT" },
            "pointType": { "type": "ProductType", "fields": ["xField", "yField"] },
            "lit3":      { "type": "IntLit", "value": 3 },
            "lit4":      { "type": "IntLit", "value": 4 },
            "xValue":    { "type": "ProductFieldValue", "fieldName": "x", "value": "lit3" },
            "yValue":    { "type": "ProductFieldValue", "fieldName": "y", "value": "lit4" },
            "point":     { "type": "ProductValue", "ofType": "pointType", "fields": ["yValue", "xValue"] },
            "getX":      { "type": "ProductFieldGet", "target": "point", "fieldName": "x" }
          }
        }""")
        assertEquals(Value.IntV(3), v)
    }

    @Test
    fun `Fixpoint countdown recurses through 5 calls and terminates`() {
        // f(n) = if n <= 0 then n else f(n - 1). Tests actual recursion
        // through the self-reference, not just the body-shape contract.
        val v = run("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "boolT":    { "type": "PrimitiveType", "kind": "Bool" },
            "recT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },

            "subT":     { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "sub":      { "type": "ForeignNode", "target": "strand-builtin:Int.Sub", "foreignType": "subT" },
            "leT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "le":       { "type": "ForeignNode", "target": "strand-builtin:Int.Le", "foreignType": "leT" },

            "recurse":  { "type": "ParameterDecl", "name": "recurse", "paramType": "recT" },
            "n":        { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "nRef":     { "type": "VarRef", "binder": "n" },
            "zero":     { "type": "IntLit", "value": 0 },
            "one":      { "type": "IntLit", "value": 1 },

            "nLe0":     { "type": "Application", "function": "le", "arguments": ["nRef", "zero"] },
            "litTrue":  { "type": "BoolLit", "value": true },
            "patTrue":  { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litTrue" },
            "caseTrue": { "type": "MatchCase", "pattern": "patTrue", "body": "nRef" },

            "litFalse": { "type": "BoolLit", "value": false },
            "patFalse": { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litFalse" },
            "recRef":   { "type": "VarRef", "binder": "recurse" },
            "nMinus1":  { "type": "Application", "function": "sub", "arguments": ["nRef", "one"] },
            "recCall":  { "type": "Application", "function": "recRef", "arguments": ["nMinus1"] },
            "caseFalse":{ "type": "MatchCase", "pattern": "patFalse", "body": "recCall" },

            "match":    { "type": "Match", "scrutinee": "nLe0", "cases": ["caseTrue", "caseFalse"] },
            "bodyLam":  { "type": "Lambda", "parameters": ["recurse", "n"], "body": "match" },
            "fn":       { "type": "Fixpoint", "recursionType": "recT", "body": "bodyLam" },

            "five":     { "type": "IntLit", "value": 5 },
            "app":      { "type": "Application", "function": "fn", "arguments": ["five"] }
          }
        }""")
        // Countdown from 5 reaches 0 (the first n where n <= 0).
        assertEquals(Value.IntV(0), v)
    }

    // ----- Q-031: refinement-lattice capability matching -----
    //
    // The 10 scenarios from § 8 of the proposal. Loosely:
    //   (1)  literal-host on literal-port matches concrete capability
    //   (2)  literal-host doesn't match different-literal-host → RefinementViolation
    //   (3)  literal-port matches wildcard-host
    //   (4)  wildcard-port matches literal-port
    //   (5)  multiple capabilities, any-match
    //   (6)  category present, refinement misses all → RefinementViolation
    //   (7)  EffectDecl arity mismatch caught by verifier (in VerifierTest)
    //   (8)  EffectDecl parameter type mismatch caught by verifier (in VerifierTest)
    //   (9)  confused-deputy scenario
    //   (10) backward compatibility
    //
    // Scenarios (7) and (8) are verifier-side and live in VerifierTest. The
    // other eight live here as interpreter integration tests.

    /**
     * Build a Strand graph that calls `Filesystem.Write` with a single
     * literal path argument. The Application carries an `effectInstances`
     * entry whose EffectDecl parameter is a VarRef into the caller's path
     * argument so the refinement reflects the actual call-site value.
     *
     * Wrapping the call in a Lambda + Application gives the EffectDecl a
     * stable VarRef binder to reference. Returns the parsed (store, root,
     * nameMap) triple so the test can extract the EffectCategory NodeId.
     */
    private fun buildFilesystemWriteCall(path: String): Loaded = ingestAndGetIds("""{
        "version": 1, "root": "outerApp",
        "nodes": {
          "intT":      { "type": "PrimitiveType", "kind": "Int" },
          "strT":      { "type": "PrimitiveType", "kind": "String" },
          "fsWriteFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                         "parameters": ["strT"] },
          "writeT":    { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                         "effects": ["fsWriteFx"] },
          "write":     { "type": "ForeignNode", "target": "strand-builtin:Filesystem.Write",
                         "foreignType": "writeT", "effects": ["fsWriteFx"] },
          "outerP":    { "type": "ParameterDecl", "name": "p", "paramType": "strT" },
          "pRef":      { "type": "VarRef", "binder": "outerP" },
          "writeDecl": { "type": "EffectDecl", "effectType": "fsWriteFx",
                         "parameters": ["pRef"] },
          "innerApp":  { "type": "Application", "function": "write",
                         "arguments": ["pRef"], "effectInstances": ["writeDecl"] },
          "outerLam":  { "type": "Lambda", "parameters": ["outerP"], "body": "innerApp",
                         "effects": ["fsWriteFx"] },
          "pathLit":   { "type": "StringLit", "value": ${jsonString(path)} },
          "outerApp":  { "type": "Application", "function": "outerLam",
                         "arguments": ["pathLit"] }
        }
      }""")

    /**
     * Build a Strand graph that calls `Network.Connect(host, port)`. The
     * call site supplies an EffectDecl with two VarRef parameters into
     * the caller's host and port arguments.
     */
    private fun buildNetworkConnectCall(host: String, port: Long): Loaded = ingestAndGetIds("""{
        "version": 1, "root": "outerApp",
        "nodes": {
          "intT":       { "type": "PrimitiveType", "kind": "Int" },
          "strT":       { "type": "PrimitiveType", "kind": "String" },
          "netConnFx":  { "type": "EffectCategory", "categoryName": "Network.Connect",
                          "parameters": ["strT", "intT"] },
          "connectT":   { "type": "FunctionType", "parameters": ["strT", "intT"], "result": "intT",
                          "effects": ["netConnFx"] },
          "connect":    { "type": "ForeignNode", "target": "strand-builtin:Network.Connect",
                          "foreignType": "connectT", "effects": ["netConnFx"] },
          "outerH":     { "type": "ParameterDecl", "name": "h", "paramType": "strT" },
          "outerP":     { "type": "ParameterDecl", "name": "p", "paramType": "intT" },
          "hRef":       { "type": "VarRef", "binder": "outerH" },
          "pRef":       { "type": "VarRef", "binder": "outerP" },
          "connDecl":   { "type": "EffectDecl", "effectType": "netConnFx",
                          "parameters": ["hRef", "pRef"] },
          "innerApp":   { "type": "Application", "function": "connect",
                          "arguments": ["hRef", "pRef"], "effectInstances": ["connDecl"] },
          "outerLam":   { "type": "Lambda", "parameters": ["outerH", "outerP"],
                          "body": "innerApp", "effects": ["netConnFx"] },
          "hostLit":    { "type": "StringLit", "value": ${jsonString(host)} },
          "portLit":    { "type": "IntLit", "value": $port },
          "outerApp":   { "type": "Application", "function": "outerLam",
                          "arguments": ["hostLit", "portLit"] }
        }
      }""")

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `Q-031 scenario 1 - literal host on literal port matches concrete capability`() {
        val (store, root, names, hashToNodeId) =buildNetworkConnectCall("api.example.com", 443L)
        val netConn = names.getValue("netConnFx")
        val granted = CapabilitySet(mapOf(netConn to listOf(
            CapabilityPattern(listOf(
                CapabilityArgument.Concrete(Value.StringV("api.example.com")),
                CapabilityArgument.Concrete(Value.IntV(443L)),
            ))
        )))
        val v = Interpreter(store, hashToNodeId).eval(root, granted)
        // Reference Builtins.Network.Connect returns IntV(1) on a successful
        // dispatch; the value is unimportant — what matters is that the
        // refinement check let the dispatch through.
        assertEquals(Value.IntV(1), v)
    }

    @Test
    fun `Q-031 scenario 2 - wrong literal host raises RefinementViolation`() {
        val (store, root, names, hashToNodeId) =buildNetworkConnectCall("attacker.com", 443L)
        val netConn = names.getValue("netConnFx")
        val granted = CapabilitySet(mapOf(netConn to listOf(
            CapabilityPattern(listOf(
                CapabilityArgument.Concrete(Value.StringV("api.example.com")),
                CapabilityArgument.Concrete(Value.IntV(443L)),
            ))
        )))
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(store, hashToNodeId).eval(root, granted)
        }
        val err = ex.error as InterpretError.RefinementViolation
        assertEquals(netConn, err.category)
        assertEquals(listOf(Value.StringV("attacker.com"), Value.IntV(443L)), err.requirement)
    }

    @Test
    fun `Q-031 scenario 3 - literal port matches wildcard host pattern`() {
        // Capability: Network.Connect{host: *, port: 443}; call any host on 443.
        val (store, root, names, hashToNodeId) =buildNetworkConnectCall("anywhere.example", 443L)
        val netConn = names.getValue("netConnFx")
        val granted = CapabilitySet(mapOf(netConn to listOf(
            CapabilityPattern(listOf(
                CapabilityArgument.Wildcard,
                CapabilityArgument.Concrete(Value.IntV(443L)),
            ))
        )))
        val v = Interpreter(store, hashToNodeId).eval(root, granted)
        assertEquals(Value.IntV(1), v)
    }

    @Test
    fun `Q-031 scenario 4 - wildcard port matches any literal port`() {
        val (store, root, names, hashToNodeId) =buildNetworkConnectCall("api.example.com", 8080L)
        val netConn = names.getValue("netConnFx")
        val granted = CapabilitySet(mapOf(netConn to listOf(
            CapabilityPattern(listOf(
                CapabilityArgument.Concrete(Value.StringV("api.example.com")),
                CapabilityArgument.Wildcard,
            ))
        )))
        val v = Interpreter(store, hashToNodeId).eval(root, granted)
        assertEquals(Value.IntV(1), v)
    }

    @Test
    fun `Q-031 scenario 5 - multiple capabilities give any-match disjunction`() {
        // Two grants for Filesystem.Write: /tmp/a and /tmp/b. Write to /tmp/a.
        val (store, root, names, hashToNodeId) =buildFilesystemWriteCall("/tmp/a")
        val fsWrite = names.getValue("fsWriteFx")
        val granted = CapabilitySet(mapOf(fsWrite to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/a")))),
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/b")))),
        )))
        val v = Interpreter(store, hashToNodeId).eval(root, granted)
        assertEquals(Value.IntV(0), v)  // Filesystem.Write returns 0
    }

    @Test
    fun `Q-031 scenario 6 - category present but no pattern covers raises RefinementViolation`() {
        // Same two grants as scenario 5; write to /tmp/c. Category present
        // but neither pattern covers → RefinementViolation (NOT
        // CapabilityViolation — the policy author granted the category,
        // just not for this resource).
        val (store, root, names, hashToNodeId) =buildFilesystemWriteCall("/tmp/c")
        val fsWrite = names.getValue("fsWriteFx")
        val granted = CapabilitySet(mapOf(fsWrite to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/a")))),
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/b")))),
        )))
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(store, hashToNodeId).eval(root, granted)
        }
        val err = ex.error as InterpretError.RefinementViolation
        assertEquals(fsWrite, err.category)
        assertEquals(listOf(Value.StringV("/tmp/c")), err.requirement)
        assertEquals(2, err.available.size, "both granted patterns should be reported as available")
    }

    @Test
    fun `Q-031 scenario 9 - confused deputy logger denies caller-supplied path`() {
        // The canonical confused-deputy demo. A "logger" sub-graph holds
        // Filesystem.Write{path: "/var/log/app.log"}. Its body writes to a
        // path supplied by its caller (typical logging API surface). The
        // caller invokes the logger with path = "/etc/passwd". The
        // logger's call to Filesystem.Write must be denied at the
        // call-site refinement check because the granted capability does
        // not authorize that path.
        //
        // Without parameter-tagged capabilities, the logger holds
        // "Filesystem.Write" wholesale and the caller could induce arbitrary
        // file writes. Q-031 closes the loophole structurally.
        val json = """{
          "version": 1, "root": "outerApp",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "strT":      { "type": "PrimitiveType", "kind": "String" },
            "fsWriteFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                           "parameters": ["strT"] },
            "writeT":    { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                           "effects": ["fsWriteFx"] },
            "write":     { "type": "ForeignNode", "target": "strand-builtin:Filesystem.Write",
                           "foreignType": "writeT", "effects": ["fsWriteFx"] },
            "loggerP":   { "type": "ParameterDecl", "name": "callerPath", "paramType": "strT" },
            "loggerPRef":{ "type": "VarRef", "binder": "loggerP" },
            "loggerDecl":{ "type": "EffectDecl", "effectType": "fsWriteFx",
                           "parameters": ["loggerPRef"] },
            "loggerCall":{ "type": "Application", "function": "write",
                           "arguments": ["loggerPRef"], "effectInstances": ["loggerDecl"] },
            "logger":    { "type": "Lambda", "parameters": ["loggerP"], "body": "loggerCall",
                           "effects": ["fsWriteFx"] },
            "attackerPath": { "type": "StringLit", "value": "/etc/passwd" },
            "outerApp":  { "type": "Application", "function": "logger",
                           "arguments": ["attackerPath"] }
          }
        }"""
        val (store, root, names, hashToNodeId) =ingestAndGetIds(json)
        val fsWrite = names.getValue("fsWriteFx")
        // The host runtime grants Filesystem.Write only for /var/log/app.log.
        val granted = CapabilitySet(mapOf(fsWrite to listOf(
            CapabilityPattern(listOf(
                CapabilityArgument.Concrete(Value.StringV("/var/log/app.log"))
            ))
        )))
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(store, hashToNodeId).eval(root, granted)
        }
        val err = ex.error as InterpretError.RefinementViolation
        assertEquals(fsWrite, err.category)
        assertEquals(listOf(Value.StringV("/etc/passwd")), err.requirement)
    }

    @Test
    fun `Q-031 scenario 10a - pure program runs unchanged under any CapabilitySet shape`() {
        // Back-compat invariant (load-bearing): a pure program — no
        // effect declarations anywhere — runs identically whether the
        // host hands the interpreter the new CapabilitySet or the old
        // Set<NodeId>. Same value, same evaluation path.
        val pure = """{
          "version": 1, "root": "app",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "addT":  { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":   { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "two":   { "type": "IntLit", "value": 2 },
            "three": { "type": "IntLit", "value": 3 },
            "app":   { "type": "Application", "function": "add", "arguments": ["two", "three"] }
          }
        }"""
        val (store, root, _, hashToNodeId) =ingestAndGetIds(pure)
        val viaSet = Interpreter(store, hashToNodeId).eval(root, capabilities = emptySet<org.strand.core.NodeId>())
        val viaStructured = Interpreter(store, hashToNodeId).eval(root, capabilities = CapabilitySet.EMPTY)
        assertEquals(Value.IntV(5), viaSet)
        assertEquals(viaSet, viaStructured)
    }

    @Test
    fun `Q-031 scenario 10b - pre-Q-031 effectful call runs against ofCategories wrapper`() {
        // Pre-Q-031 corpus programs call effectful functions with no
        // effectInstances on the Application. The runtime must accept
        // CapabilitySet.ofCategories(setOf(timeFx)) — the back-compat
        // wildcard sentinel — and dispatch identically.
        val json = """{
          "version": 1, "root": "app",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":   { "type": "FunctionType", "parameters": [], "result": "intT",
                        "effects": ["timeFx"] },
            "now":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                        "foreignType": "nowT", "effects": ["timeFx"] },
            "app":    { "type": "Application", "function": "now", "arguments": [] }
          }
        }"""
        val (store, root, names, hashToNodeId) =ingestAndGetIds(json)
        val timeFx = names.getValue("timeFx")
        val viaSet = Interpreter(store, hashToNodeId).eval(root, capabilities = setOf(timeFx))
        val viaStructured = Interpreter(store, hashToNodeId).eval(
            root, capabilities = CapabilitySet.ofCategories(setOf(timeFx))
        )
        assertEquals(Value.IntV(Builtins.FIXED_REPLAY_TIMESTAMP), viaSet)
        assertEquals(viaSet, viaStructured)
    }

    // ----- Q-030: effect handlers (N-043) -----
    //
    // The six scenarios from § 8 of the proposal.
    //   (1) Mock Time.Now with a fixed timestamp; program runs without the
    //       Time.Now capability.
    //   (2) Handler captures a value from outer Let.
    //   (3) Two handlers for same effect, nested — innermost wins.
    //   (4) Handler that itself performs an effect (Memory.MutableState);
    //       surrounding context needs the handler's effect but not the
    //       intercepted one.
    //   (5) Body that does not invoke the intercepted effect — handler is no-op.
    //   (6) Recursive function with Fixpoint inside a Handler — handler fires
    //       every recursive call.

    @Test
    fun `Q-030 scenario 1 - Handler mocks Time Now with a fixed timestamp under no Time Now capability`() {
        // The body calls Time.Now (an effectful ForeignNode) but the
        // surrounding context grants NO capabilities. A Handler over
        // Time.Now intercepts the call and returns a fixed 1700000000.
        // The program evaluates to that constant; the Time.Now builtin
        // is never invoked.
        val json = """{
          "version": 1, "root": "handler",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":     { "type": "FunctionType", "parameters": [], "result": "intT",
                          "effects": ["timeFx"] },
            "now":      { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                          "foreignType": "nowT", "effects": ["timeFx"] },
            "callNow":  { "type": "Application", "function": "now", "arguments": [] },

            "fakeT":    { "type": "FunctionType", "parameters": [], "result": "intT" },
            "fakeLit":  { "type": "IntLit", "value": 1700000000 },
            "fakeLam":  { "type": "Lambda", "parameters": [], "body": "fakeLit" },

            "handler":  { "type": "Handler", "intercept": "timeFx",
                          "handle": "fakeLam", "body": "callNow" }
          }
        }"""
        val (store, root, _, hashToNodeId) =ingestAndGetIds(json)
        // EMPTY context — the body's Time.Now requirement is removed by
        // the handler's closure subtraction.
        val v = Interpreter(store, hashToNodeId).eval(root, CapabilitySet.EMPTY)
        assertEquals(Value.IntV(1700000000L), v)
    }

    @Test
    fun `Q-030 scenario 2 - handler captures a value from outer Let`() {
        // An outer Let binds t = 1700000000; the handler is `\. t`. This
        // tests the lexical-capture property: the handler's environment
        // is captured at Handler-entry, so the t binding flows in.
        val json = """{
          "version": 1, "root": "letT",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":     { "type": "FunctionType", "parameters": [], "result": "intT",
                          "effects": ["timeFx"] },
            "now":      { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                          "foreignType": "nowT", "effects": ["timeFx"] },
            "callNow":  { "type": "Application", "function": "now", "arguments": [] },

            "fakeT":    { "type": "FunctionType", "parameters": [], "result": "intT" },
            "tRef":     { "type": "VarRef", "binder": "letT" },
            "fakeLam":  { "type": "Lambda", "parameters": [], "body": "tRef" },

            "handler":  { "type": "Handler", "intercept": "timeFx",
                          "handle": "fakeLam", "body": "callNow" },

            "tLit":     { "type": "IntLit", "value": 1700000000 },
            "letT":     { "type": "Let", "name": "t", "value": "tLit", "body": "handler" }
          }
        }"""
        val (store, root, _, hashToNodeId) =ingestAndGetIds(json)
        val v = Interpreter(store, hashToNodeId).eval(root, CapabilitySet.EMPTY)
        assertEquals(Value.IntV(1700000000L), v)
    }

    @Test
    fun `Q-030 scenario 3 - two handlers for the same effect nested innermost wins`() {
        // Handler(Time.Now, h_outer, Handler(Time.Now, h_inner, callNow))
        // h_outer returns 1, h_inner returns 2. The inner handler wins:
        // the body's Time.Now call is intercepted by h_inner, returning 2.
        val json = """{
          "version": 1, "root": "outer",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":    { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":      { "type": "FunctionType", "parameters": [], "result": "intT",
                           "effects": ["timeFx"] },
            "now":       { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                           "foreignType": "nowT", "effects": ["timeFx"] },
            "callNow":   { "type": "Application", "function": "now", "arguments": [] },

            "fakeT":     { "type": "FunctionType", "parameters": [], "result": "intT" },
            "innerLit":  { "type": "IntLit", "value": 2 },
            "innerLam":  { "type": "Lambda", "parameters": [], "body": "innerLit" },
            "inner":     { "type": "Handler", "intercept": "timeFx",
                           "handle": "innerLam", "body": "callNow" },

            "outerLit":  { "type": "IntLit", "value": 1 },
            "outerLam":  { "type": "Lambda", "parameters": [], "body": "outerLit" },
            "outer":     { "type": "Handler", "intercept": "timeFx",
                           "handle": "outerLam", "body": "inner" }
          }
        }"""
        val (store, root, _, hashToNodeId) =ingestAndGetIds(json)
        val v = Interpreter(store, hashToNodeId).eval(root, CapabilitySet.EMPTY)
        // Innermost wins → 2, not 1.
        assertEquals(Value.IntV(2L), v)
    }

    @Test
    fun `Q-030 scenario 4 - handler that itself performs an effect requires that effect in surrounding context`() {
        // Setup: body calls Time.Now; handler itself calls a builtin
        // declaring Memory.MutableState (we use a fictional "Log.Append"
        // surfaced as a Filesystem.Write builtin for convenience, since
        // Builtins.kt has Filesystem.Write registered as a no-op stub).
        // Surrounding context grants Filesystem.Write (NOT Time.Now).
        //
        // Without the handler, the call would fail with CapabilityViolation
        // (Time.Now missing). The handler intercepts; its own effect
        // (Filesystem.Write) is granted, so the handler's call succeeds
        // and the program evaluates.
        val json = """{
          "version": 1, "root": "handler",
          "nodes": {
            "intT":       { "type": "PrimitiveType", "kind": "Int" },
            "strT":       { "type": "PrimitiveType", "kind": "String" },

            "timeFx":     { "type": "EffectCategory", "categoryName": "Time.Now" },
            "fsWriteFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Write" },

            "nowT":       { "type": "FunctionType", "parameters": [], "result": "intT",
                            "effects": ["timeFx"] },
            "now":        { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                            "foreignType": "nowT", "effects": ["timeFx"] },
            "callNow":    { "type": "Application", "function": "now", "arguments": [] },

            "writeT":     { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                            "effects": ["fsWriteFx"] },
            "write":      { "type": "ForeignNode", "target": "strand-builtin:Filesystem.Write",
                            "foreignType": "writeT", "effects": ["fsWriteFx"] },
            "pathLit":    { "type": "StringLit", "value": "/tmp/log" },
            "callWrite":  { "type": "Application", "function": "write",
                            "arguments": ["pathLit"] },

            "handleLam":  { "type": "Lambda", "parameters": [], "body": "callWrite",
                            "effects": ["fsWriteFx"] },

            "handler":    { "type": "Handler", "intercept": "timeFx",
                            "handle": "handleLam", "body": "callNow" }
          }
        }"""
        val (store, root, names, hashToNodeId) =ingestAndGetIds(json)
        val fsWrite = names.getValue("fsWriteFx")
        // Grant Filesystem.Write (with a back-compat wildcard) but NOT Time.Now.
        val granted = CapabilitySet.ofCategories(setOf(fsWrite))
        val v = Interpreter(store, hashToNodeId).eval(root, granted)
        // Filesystem.Write builtin returns IntV(0) on success.
        assertEquals(Value.IntV(0L), v)
    }

    @Test
    fun `Q-030 scenario 5 - body that does not invoke the intercepted effect is unchanged`() {
        // Body is just IntLit(42); no effectful call. The Handler is a
        // no-op. Result: 42. Verifies that handlers don't pollute calls
        // they aren't supposed to intercept.
        val json = """{
          "version": 1, "root": "handler",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "timeFx":   { "type": "EffectCategory", "categoryName": "Time.Now" },

            "fakeT":    { "type": "FunctionType", "parameters": [], "result": "intT" },
            "fakeLit":  { "type": "IntLit", "value": 99 },
            "fakeLam":  { "type": "Lambda", "parameters": [], "body": "fakeLit" },

            "bodyLit":  { "type": "IntLit", "value": 42 },
            "handler":  { "type": "Handler", "intercept": "timeFx",
                          "handle": "fakeLam", "body": "bodyLit" }
          }
        }"""
        val (store, root, _, hashToNodeId) =ingestAndGetIds(json)
        val v = Interpreter(store, hashToNodeId).eval(root, CapabilitySet.EMPTY)
        assertEquals(Value.IntV(42L), v)
    }

    @Test
    fun `Q-030 scenario 6 - recursion through Fixpoint with handler installed inside the body`() {
        // Regression test for handler threading across Fixpoint
        // self-references. A function f(n) recurses N times; the body
        // wraps each iteration's leaf `now()` call in a fresh Handler
        // returning 1. We sum the handler results: f(3) returns
        // 1 + 1 + 1 + 1 = 4 (one call at each n=3,2,1,0).
        //
        // The Handler is installed INSIDE the body Lambda so the Fixpoint's
        // recursive call (which has signature `(Int) -> Int ![]` after
        // closure subtraction) does not get intercepted — the handler's
        // closure subtraction strips timeFx from the body Lambda's
        // effective closure. Without proper handler threading through
        // Fixpoint, the inner handler would be lost on the second
        // recursive call and the body's `now()` would fail with
        // CapabilityViolation (the surrounding context grants nothing).
        val json = """{
          "version": 1, "root": "app",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "boolT":     { "type": "PrimitiveType", "kind": "Bool" },
            "timeFx":    { "type": "EffectCategory", "categoryName": "Time.Now" },

            "recT":      { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },

            "nowT":      { "type": "FunctionType", "parameters": [], "result": "intT",
                           "effects": ["timeFx"] },
            "now":       { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                           "foreignType": "nowT", "effects": ["timeFx"] },

            "subT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "sub":       { "type": "ForeignNode", "target": "strand-builtin:Int.Sub", "foreignType": "subT" },
            "addT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":       { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "eqT":       { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "eq":        { "type": "ForeignNode", "target": "strand-builtin:Int.Eq", "foreignType": "eqT" },

            "recurse":   { "type": "ParameterDecl", "name": "recurse", "paramType": "recT" },
            "n":         { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "nRef":      { "type": "VarRef", "binder": "n" },
            "zero":      { "type": "IntLit", "value": 0 },
            "one":       { "type": "IntLit", "value": 1 },

            "nEq0":      { "type": "Application", "function": "eq", "arguments": ["nRef", "zero"] },

            "callNowT":  { "type": "Application", "function": "now", "arguments": [] },
            "fakeLitT":  { "type": "IntLit", "value": 1 },
            "fakeLamT":  { "type": "Lambda", "parameters": [], "body": "fakeLitT" },
            "innerHT":   { "type": "Handler", "intercept": "timeFx",
                           "handle": "fakeLamT", "body": "callNowT" },

            "litTrue":   { "type": "BoolLit", "value": true },
            "patTrue":   { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litTrue" },
            "caseTrue":  { "type": "MatchCase", "pattern": "patTrue", "body": "innerHT" },

            "litFalse":  { "type": "BoolLit", "value": false },
            "patFalse":  { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litFalse" },
            "recRef":    { "type": "VarRef", "binder": "recurse" },
            "nMinus1":   { "type": "Application", "function": "sub", "arguments": ["nRef", "one"] },
            "recCall":   { "type": "Application", "function": "recRef", "arguments": ["nMinus1"] },

            "callNowF":  { "type": "Application", "function": "now", "arguments": [] },
            "fakeLitF":  { "type": "IntLit", "value": 1 },
            "fakeLamF":  { "type": "Lambda", "parameters": [], "body": "fakeLitF" },
            "innerHF":   { "type": "Handler", "intercept": "timeFx",
                           "handle": "fakeLamF", "body": "callNowF" },

            "addExpr":   { "type": "Application", "function": "add",
                           "arguments": ["innerHF", "recCall"] },
            "caseFalse": { "type": "MatchCase", "pattern": "patFalse", "body": "addExpr" },

            "match":     { "type": "Match", "scrutinee": "nEq0", "cases": ["caseTrue", "caseFalse"] },
            "bodyLam":   { "type": "Lambda", "parameters": ["recurse", "n"], "body": "match" },
            "fn":        { "type": "Fixpoint", "recursionType": "recT", "body": "bodyLam" },

            "three":     { "type": "IntLit", "value": 3 },
            "app":       { "type": "Application", "function": "fn", "arguments": ["three"] }
          }
        }"""
        val (store, root, _, hashToNodeId) =ingestAndGetIds(json)
        val v = Interpreter(store, hashToNodeId).eval(root, CapabilitySet.EMPTY)
        // f(3) recurses: 1 + (1 + (1 + (1))) = 4. The handler fires four
        // times — once for the innerHT base case at n=0 and three times
        // for the innerHF nested in the recursive case (n=3,2,1).
        assertEquals(Value.IntV(4L), v)
    }
}
