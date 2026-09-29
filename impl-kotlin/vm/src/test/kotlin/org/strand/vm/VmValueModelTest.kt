package org.strand.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.bytecode.Lowerer
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Review M5 + Low findings on the VM value model and the lowerer:
 * closures stored in records / sums, the free-variable walk through
 * Match / Product / Sum / CapabilityScope / Handler / Fixpoint, the typed
 * NoMatchingCase error, per-call reset of the capability / handler stacks in
 * `applyClosure`, and a reentrant, memoizing lowerer. Each program runs on
 * both engines and asserts interpreter == VM.
 */
class VmValueModelTest {

    private class Loaded(
        val store: NodeStore,
        val root: NodeId,
        val hashToNodeId: Map<Hash, NodeId>,
        val names: Map<String, NodeId>,
    )

    private fun load(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        check(verify is VerifyResult.Ok) { "fixture failed verify: $verify" }
        return Loaded(finalized.store, finalized.root, finalized.hashToNodeId, ingest.nameMap)
    }

    private fun assertParity(l: Loaded, caps: CapabilitySet = CapabilitySet.EMPTY): Value {
        val interp = Interpreter(l.store, l.hashToNodeId).eval(l.root, caps)
        val vm = Vm(Lowerer(l.store, l.hashToNodeId).lower(l.root)).run(caps)
        assertEquals(interp, vm)
        return vm
    }

    @Test
    fun `closure captured through a product value - x to a y b x with y captured`() {
        // let y = 7 in (\x -> {a: y, b: x})(3)
        val l = load("""{
          "version": 1, "root": "prog",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "aF":    { "type": "ProductTypeField", "name": "a", "fieldType": "intT" },
            "bF":    { "type": "ProductTypeField", "name": "b", "fieldType": "intT" },
            "recT":  { "type": "ProductType", "fields": ["aF", "bF"] },
            "lit7":  { "type": "IntLit", "value": 7 },
            "lit3":  { "type": "IntLit", "value": 3 },
            "xP":    { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":  { "type": "VarRef", "binder": "xP" },
            "yRef":  { "type": "VarRef", "binder": "prog" },
            "aV":    { "type": "ProductFieldValue", "fieldName": "a", "value": "yRef" },
            "bV":    { "type": "ProductFieldValue", "fieldName": "b", "value": "xRef" },
            "rec":   { "type": "ProductValue", "ofType": "recT", "fields": ["aV", "bV"] },
            "lam":   { "type": "Lambda", "parameters": ["xP"], "body": "rec" },
            "app":   { "type": "Application", "function": "lam", "arguments": ["lit3"] },
            "prog":  { "type": "Let", "name": "y", "value": "lit7", "body": "app" }
          }
        }""")
        val v = assertParity(l)
        assertEquals(Value.ProductV(linkedMapOf("a" to Value.IntV(7), "b" to Value.IntV(3))), v)
    }

    @Test
    fun `closure stored in a record field is callable after projection`() {
        // {f: \x -> x}.f(5)
        val l = load("""{
          "version": 1, "root": "app",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "fT":    { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "fF":    { "type": "ProductTypeField", "name": "f", "fieldType": "fT" },
            "recT":  { "type": "ProductType", "fields": ["fF"] },
            "xP":    { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":  { "type": "VarRef", "binder": "xP" },
            "id":    { "type": "Lambda", "parameters": ["xP"], "body": "xRef" },
            "fV":    { "type": "ProductFieldValue", "fieldName": "f", "value": "id" },
            "rec":   { "type": "ProductValue", "ofType": "recT", "fields": ["fV"] },
            "getF":  { "type": "ProductFieldGet", "target": "rec", "fieldName": "f" },
            "lit5":  { "type": "IntLit", "value": 5 },
            "app":   { "type": "Application", "function": "getF", "arguments": ["lit5"] }
          }
        }""")
        assertEquals(Value.IntV(5), assertParity(l))
    }

    @Test
    fun `closure in a sum payload, bound by a pattern and called inside a lambda capturing an outer binder`() {
        // let k = 10 in (\u -> match Some(\x -> Int.Add(x, k)) { Some(g) -> g(u); None -> 0 })(4)
        val l = load("""{
          "version": 1, "root": "prog",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "fT":       { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "addT":     { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":      { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "someCase": { "type": "SumTypeCase", "name": "Some", "caseType": "fT" },
            "noneCase": { "type": "SumTypeCase", "name": "None", "caseType": null },
            "optT":     { "type": "SumType", "cases": ["someCase", "noneCase"] },
            "lit10":    { "type": "IntLit", "value": 10 },
            "lit4":     { "type": "IntLit", "value": 4 },
            "zero":     { "type": "IntLit", "value": 0 },
            "kRef":     { "type": "VarRef", "binder": "prog" },
            "xP":       { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":     { "type": "VarRef", "binder": "xP" },
            "addXK":    { "type": "Application", "function": "add", "arguments": ["xRef", "kRef"] },
            "inner":    { "type": "Lambda", "parameters": ["xP"], "body": "addXK" },
            "someV":    { "type": "SumValue", "ofType": "optT", "caseName": "Some", "payload": "inner" },
            "varG":     { "type": "Pattern", "kind": "variable", "patternType": "fT", "name": "g" },
            "patSome":  { "type": "Pattern", "kind": "constructor", "patternType": "optT", "caseName": "Some", "payloadPattern": "varG" },
            "patNone":  { "type": "Pattern", "kind": "constructor", "patternType": "optT", "caseName": "None" },
            "uP":       { "type": "ParameterDecl", "name": "u", "paramType": "intT" },
            "uRef":     { "type": "VarRef", "binder": "uP" },
            "gRef":     { "type": "VarRef", "binder": "varG" },
            "callG":    { "type": "Application", "function": "gRef", "arguments": ["uRef"] },
            "caseSome": { "type": "MatchCase", "pattern": "patSome", "body": "callG" },
            "caseNone": { "type": "MatchCase", "pattern": "patNone", "body": "zero" },
            "match":    { "type": "Match", "scrutinee": "someV", "cases": ["caseSome", "caseNone"] },
            "outer":    { "type": "Lambda", "parameters": ["uP"], "body": "match" },
            "app":      { "type": "Application", "function": "outer", "arguments": ["lit4"] },
            "prog":     { "type": "Let", "name": "k", "value": "lit10", "body": "app" }
          }
        }""")
        assertEquals(Value.IntV(14), assertParity(l))
    }

    @Test
    fun `a Match with no matching case raises the typed NoMatchingCase on both engines`() {
        // Built directly (the verifier would reject the non-exhaustive match).
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val one = store.add(Node.IntLit(1))
        val two = store.add(Node.IntLit(2))
        val pat = store.add(Node.Pattern.LiteralPattern(patternType = intT, literal = two))
        val case = store.add(Node.MatchCase(pattern = pat, body = two))
        val match = store.add(Node.Match(scrutinee = one, cases = listOf(case)))
        val i = assertThrows<InterpretException> { Interpreter(store).eval(match) }.error
        val v = assertThrows<InterpretException> { Vm(Lowerer(store).lower(match)).run() }.error
        assertTrue(v is InterpretError.NoMatchingCase)
        assertEquals(i, v)
    }

    @Test
    fun `applyClosure resets the handler stack after an escaped exception`() {
        // root = {a: \u -> Handler(fsWriteFx, \s -> 99, Int.Div(1, 0)),
        //         b: \u -> Test.EffectfulNoOp("x")}
        val l = load("""{
          "version": 1, "root": "rec",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "strT":   { "type": "PrimitiveType", "kind": "String" },
            "fsFx":   { "type": "EffectCategory", "categoryName": "Filesystem.Write" },
            "divT":   { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "div":    { "type": "ForeignNode", "target": "strand-builtin:Int.Div", "foreignType": "divT" },
            "wT":     { "type": "FunctionType", "parameters": ["strT"], "result": "intT", "effects": ["fsFx"] },
            "w":      { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp", "foreignType": "wT", "effects": ["fsFx"] },
            "one":    { "type": "IntLit", "value": 1 },
            "zero":   { "type": "IntLit", "value": 0 },
            "n99":    { "type": "IntLit", "value": 99 },
            "xs":     { "type": "StringLit", "value": "x" },
            "boom":   { "type": "Application", "function": "div", "arguments": ["one", "zero"] },
            "sP":     { "type": "ParameterDecl", "name": "s", "paramType": "strT" },
            "fake":   { "type": "Lambda", "parameters": ["sP"], "body": "n99" },
            "h":      { "type": "Handler", "intercept": "fsFx", "handle": "fake", "body": "boom" },
            "uP":     { "type": "ParameterDecl", "name": "u", "paramType": "intT" },
            "aLam":   { "type": "Lambda", "parameters": ["uP"], "body": "h" },
            "vP":     { "type": "ParameterDecl", "name": "v", "paramType": "intT" },
            "write":  { "type": "Application", "function": "w", "arguments": ["xs"] },
            "bLam":   { "type": "Lambda", "parameters": ["vP"], "body": "write", "effects": ["fsFx"] },
            "aT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "bT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": ["fsFx"] },
            "aF":     { "type": "ProductTypeField", "name": "a", "fieldType": "aT" },
            "bF":     { "type": "ProductTypeField", "name": "b", "fieldType": "bT" },
            "recT":   { "type": "ProductType", "fields": ["aF", "bF"] },
            "aV":     { "type": "ProductFieldValue", "fieldName": "a", "value": "aLam" },
            "bV":     { "type": "ProductFieldValue", "fieldName": "b", "value": "bLam" },
            "rec":    { "type": "ProductValue", "ofType": "recT", "fields": ["aV", "bV"] }
          }
        }""")
        val caps = CapabilitySet.ofCategories(setOf(l.names.getValue("fsFx")))
        val vm = Vm(Lowerer(l.store, l.hashToNodeId).lower(l.root))
        val rec = vm.run(caps) as Value.ProductV
        val a = vm.callable(rec.fields.getValue("a"))
        val b = vm.callable(rec.fields.getValue("b"))
        val ex = assertThrows<InterpretException> { vm.applyClosure(a, listOf(Value.IntV(0)), caps) }
        assertTrue(ex.error is InterpretError.BuiltinContractViolation)
        // A stale handler from the failed call would intercept this write and return 99.
        assertEquals(Value.IntV(0), vm.applyClosure(b, listOf(Value.IntV(0)), caps))
    }

    @Test
    fun `the lowerer is reentrant and lowers a shared lambda once`() {
        // Int.Add(f(1), f(2)) where f is ONE lambda node referenced twice.
        val l = load("""{
          "version": 1, "root": "sum",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "addT":  { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":   { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "xP":    { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":  { "type": "VarRef", "binder": "xP" },
            "f":     { "type": "Lambda", "parameters": ["xP"], "body": "xRef" },
            "one":   { "type": "IntLit", "value": 1 },
            "two":   { "type": "IntLit", "value": 2 },
            "f1":    { "type": "Application", "function": "f", "arguments": ["one"] },
            "f2":    { "type": "Application", "function": "f", "arguments": ["two"] },
            "sum":   { "type": "Application", "function": "add", "arguments": ["f1", "f2"] }
          }
        }""")
        val lowerer = Lowerer(l.store, l.hashToNodeId)
        val first = lowerer.lower(l.root)
        val second = lowerer.lower(l.root)
        assertEquals(first, second) { "a second lower() call must not see the first call's chunks" }
        assertEquals(2, first.chunks.size) { "root + one sub-chunk for the shared lambda" }
        assertEquals(Value.IntV(3), Vm(first).run())
    }
}
