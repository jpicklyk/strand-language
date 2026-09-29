package org.strand.verifier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher

/**
 * Adversarial soundness tests for the verifier (2026-09-29 review, Stream A).
 * Each test writes the exploit from the review as a program and asserts the
 * verifier now rejects it with a structured error (or, for the complexity
 * findings, finishes within a bound).
 */
class VerifierSoundnessTest {

    private data class Verified(val result: VerifyResult, val names: Map<String, NodeId>)

    private fun verifyNamed(json: String): Verified {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val r = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        return Verified(r, ingest.nameMap)
    }

    private fun verify(json: String): VerifyResult = verifyNamed(json).result

    /**
     * Ingest a well-formed [json], overwrite [authorId]'s node with [patch]
     * in the finalized store, and verify. For shapes JsonIngest refuses
     * (duplicate field or case names): the verifier rule must still hold for
     * stores built programmatically. No NodeRefs are involved, so the stale
     * hash of the patched node is never consulted.
     */
    private fun verifyPatched(
        json: String,
        authorId: String,
        patch: (org.strand.core.Node) -> org.strand.core.Node,
    ): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val id = ingest.nameMap.getValue(authorId)
        finalized.store.set(id, patch(finalized.store.get(id)))
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    private inline fun <reified E : VerifyError> assertRejects(r: VerifyResult): E {
        val f = r as? VerifyResult.Failed ?: error("expected rejection with ${E::class.simpleName}, got $r")
        return f.errors.filterIsInstance<E>().firstOrNull()
            ?: error("expected ${E::class.simpleName}, got ${f.errors}")
    }

    // ---- A5: shared DAGs verify in linear time ------------------------------

    /** `x_0 = 1; x_i = Int.Add(x_{i-1}, x_{i-1})`, optionally threading each level through a Let. */
    private fun sharedChain(n: Int, viaLet: Boolean): String {
        val nodes = StringBuilder()
        nodes.append(""""intT": { "type": "PrimitiveType", "kind": "Int" },""")
        nodes.append(""""addT": { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },""")
        nodes.append(""""add": { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },""")
        nodes.append(""""x0": { "type": "IntLit", "value": 1 },""")
        for (i in 1..n) {
            val prev = "x${i - 1}"
            if (viaLet) {
                nodes.append(""""a$i": { "type": "Application", "function": "add", "arguments": ["$prev", "$prev"] },""")
                nodes.append(""""x$i": { "type": "Let", "name": "l$i", "value": "$prev", "body": "a$i" },""")
            } else {
                nodes.append(""""x$i": { "type": "Application", "function": "add", "arguments": ["$prev", "$prev"] },""")
            }
        }
        return """{ "version": 1, "root": "x$n", "nodes": { ${nodes.toString().trimEnd(',')} } }"""
    }

    @Test
    fun `a 40-node shared application chain verifies in under two seconds`() {
        val json = sharedChain(40, viaLet = false)
        val start = System.nanoTime()
        val r = verify(json)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(r is VerifyResult.Ok) { "got $r" }
        assertTrue(ms < 2_000) { "40-node shared chain took $ms ms" }
    }

    /**
     * Verifies [json] over a NodeStore built directly from the ingest's raw
     * store (no NodeRefs, so no finalization is needed). Used where the
     * hasher itself, not the verifier, is the bottleneck under test: the
     * canonical encoder is exponential on shared Let chains (reported to the
     * hashing stream), and this test isolates the verifier's cost.
     */
    private fun verifyUnhashed(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val store = org.strand.core.NodeStore()
        for ((_, stored) in ingest.rawStore.entries()) {
            store.add((stored as org.strand.core.StoredNode.Canonical).node)
        }
        return Verifier(store).verify(ingest.root)
    }

    @Test
    fun `a 40-level shared chain threaded through Lets verifies in under two seconds`() {
        val json = sharedChain(40, viaLet = true)
        val start = System.nanoTime()
        val r = verifyUnhashed(json)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(r is VerifyResult.Ok) { "got $r" }
        assertTrue(ms < 2_000) { "40-level Let chain took $ms ms" }
    }

    @Test
    fun `a schema obligation on a shared argument survives a later plain use (review H2)`() {
        // `five` flows into a PositiveInt parameter and then into a plain Int
        // parameter of Int.Add. The plain re-inference used to overwrite the
        // SchemaType record, dropping the invariant obligation.
        val v = verifyNamed("""{
          "version": 1, "root": "root",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "boolT":  { "type": "PrimitiveType", "kind": "Bool" },
            "zero":   { "type": "IntLit", "value": 0 },
            "xParam": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":   { "type": "VarRef", "binder": "xParam" },
            "gtT":    { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "gt":     { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "gtT" },
            "gtBody": { "type": "Application", "function": "gt", "arguments": ["xRef", "zero"] },
            "pred":   { "type": "Lambda", "parameters": ["xParam"], "body": "gtBody" },
            "inv":    { "type": "Invariant", "invariantName": "pos", "targetSchema": "posInt", "body": "pred" },
            "posInt": { "type": "Schema", "schemaName": "PositiveInt", "valueType": "intT", "invariants": ["inv"] },
            "five":   { "type": "IntLit", "value": 5 },
            "pIn":    { "type": "ParameterDecl", "name": "p", "paramType": "posInt" },
            "pRef":   { "type": "VarRef", "binder": "pIn" },
            "idPos":  { "type": "Lambda", "parameters": ["pIn"], "body": "pRef" },
            "claim":  { "type": "Application", "function": "idPos", "arguments": ["five"] },
            "addT":   { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":    { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "root":   { "type": "Application", "function": "add", "arguments": ["claim", "five"] }
          }
        }""")
        val ok = v.result as? VerifyResult.Ok ?: error("expected Ok, got ${v.result}")
        val recorded = ok.nodeTypes[v.names.getValue("five")]
        assertTrue(recorded is TypeExpr.SchemaType) { "schema obligation lost: $recorded" }
    }

    // ---- A6: duplicate field and case names ---------------------------------

    @Test
    fun `a ProductType with a duplicate field name is rejected (review C1 exploit)`() {
        // {x: Int, x: String}: the value checks x against String (last
        // duplicate) while the read types x as Int (first duplicate), so
        // Int.Add received a String at runtime.
        val r = verifyPatched("""{
          "version": 1, "root": "sum",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "strT":  { "type": "PrimitiveType", "kind": "String" },
            "f1":    { "type": "ProductTypeField", "name": "x", "fieldType": "intT" },
            "f2":    { "type": "ProductTypeField", "name": "x2", "fieldType": "strT" },
            "pT":    { "type": "ProductType", "fields": ["f1", "f2"] },
            "s":     { "type": "StringLit", "value": "not an int" },
            "fv":    { "type": "ProductFieldValue", "fieldName": "x", "value": "s" },
            "pv":    { "type": "ProductValue", "ofType": "pT", "fields": ["fv"] },
            "get":   { "type": "ProductFieldGet", "target": "pv", "fieldName": "x" },
            "one":   { "type": "IntLit", "value": 1 },
            "addT":  { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":   { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "sum":   { "type": "Application", "function": "add", "arguments": ["get", "one"] }
          }
        }""", "f2") { (it as org.strand.core.Node.ProductTypeField).copy(fieldName = "x") }
        val err = assertRejects<VerifyError.DuplicateFieldName>(r)
        assertEquals("x", err.name)
    }

    @Test
    fun `a SumType with a duplicate case name is rejected`() {
        val r = verifyPatched("""{
          "version": 1, "root": "v",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "strT":  { "type": "PrimitiveType", "kind": "String" },
            "c1":    { "type": "SumTypeCase", "name": "A", "caseType": "intT" },
            "c2":    { "type": "SumTypeCase", "name": "B", "caseType": "strT" },
            "sT":    { "type": "SumType", "cases": ["c1", "c2"] },
            "one":   { "type": "IntLit", "value": 1 },
            "v":     { "type": "SumValue", "ofType": "sT", "caseName": "A", "payload": "one" }
          }
        }""", "c2") { (it as org.strand.core.Node.SumTypeCase).copy(caseName = "A") }
        val err = assertRejects<VerifyError.DuplicateCaseName>(r)
        assertEquals("A", err.name)
    }

    // ---- A7: TypeParameter rebinding and capture ---------------------------

    @Test
    fun `an inner binder rebinding an in-scope TypeParameter cannot fake an identity (review C2)`() {
        // outer = Λa. λ(x: a). (Λa. λ(y: a). x)
        // The inner abstraction returns the OUTER x, yet with the rebound `a`
        // its type reads as `forall a. a -> a`. outer[String]("s")[Int](5)
        // then typed as Int while evaluating to "s": a String -> Int identity.
        val r = verify("""{
          "version": 1, "root": "root",
          "nodes": {
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "strT":    { "type": "PrimitiveType", "kind": "String" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "y":       { "type": "ParameterDecl", "name": "y", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "innerLam":{ "type": "Lambda", "parameters": ["y"], "body": "xRef" },
            "innerTA": { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "innerLam" },
            "outerLam":{ "type": "Lambda", "parameters": ["x"], "body": "innerTA" },
            "outerTA": { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "outerLam" },
            "s":       { "type": "StringLit", "value": "s" },
            "five":    { "type": "IntLit", "value": 5 },
            "app1":    { "type": "Application", "function": "outerTA", "arguments": ["s"], "typeArguments": ["strT"] },
            "app2":    { "type": "Application", "function": "app1", "arguments": ["five"], "typeArguments": ["intT"] },
            "addT":    { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":     { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "root":    { "type": "Application", "function": "add", "arguments": ["app2", "five"] }
          }
        }""")
        assertRejects<VerifyError.TypeParameterRebound>(r)
    }

    @Test
    fun `a ForallType rebinding an in-scope TypeParameter is rejected`() {
        // Λa. λ(g: forall a. a -> a). g — the parameter's type rebinds `a`.
        val r = verify("""{
          "version": 1, "root": "ta",
          "nodes": {
            "T_a":  { "type": "TypeParameter", "name": "a" },
            "fnT":  { "type": "FunctionType", "parameters": ["T_a"], "result": "T_a" },
            "allT": { "type": "ForallType", "typeParameters": ["T_a"], "body": "fnT" },
            "g":    { "type": "ParameterDecl", "name": "g", "paramType": "allT" },
            "gRef": { "type": "VarRef", "binder": "g" },
            "lam":  { "type": "Lambda", "parameters": ["g"], "body": "gRef" },
            "ta":   { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "lam" }
          }
        }""")
        assertRejects<VerifyError.TypeParameterRebound>(r)
    }

    @Test
    fun `instantiating a rank-2 callee with a TypeParameter its inner Forall binds is refused`() {
        // f  = Λa. λ(g: forall b. b -> a). g[Int](5)        : forall a. (forall b. b -> a) -> a
        // id = Λb. λ(z: b). z                                : forall b. b -> b
        // use = Λb. λ(w: b). f[b](id)
        // f and id are Let-bound outside `use`, so no binder is lexically
        // nested under another binding the same TypeParameter; only the
        // substitution at f[b] can capture.
        // Substituting a := b into (forall b. b -> a) captures b and yields
        // forall b. b -> b, so `id` would be accepted and f[b](id) would be
        // typed b while evaluating to 5; use[String]("s") is then an Int
        // typed as String.
        val r = verifyNamed("""{
          "version": 1, "root": "root",
          "nodes": {
            "T_a":   { "type": "TypeParameter", "name": "a" },
            "T_b":   { "type": "TypeParameter", "name": "b" },
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "strT":  { "type": "PrimitiveType", "kind": "String" },
            "gT":    { "type": "FunctionType", "parameters": ["T_b"], "result": "T_a" },
            "gAll":  { "type": "ForallType", "typeParameters": ["T_b"], "body": "gT" },
            "g":     { "type": "ParameterDecl", "name": "g", "paramType": "gAll" },
            "gRef":  { "type": "VarRef", "binder": "g" },
            "five":  { "type": "IntLit", "value": 5 },
            "callG": { "type": "Application", "function": "gRef", "arguments": ["five"], "typeArguments": ["intT"] },
            "fLam":  { "type": "Lambda", "parameters": ["g"], "body": "callG" },
            "f":     { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "fLam" },
            "z":     { "type": "ParameterDecl", "name": "z", "paramType": "T_b" },
            "zRef":  { "type": "VarRef", "binder": "z" },
            "idLam": { "type": "Lambda", "parameters": ["z"], "body": "zRef" },
            "id":    { "type": "TypeAbstraction", "typeParameters": ["T_b"], "body": "idLam" },
            "idLet": { "type": "Let", "name": "idv", "value": "id", "body": "top" },
            "idRef": { "type": "VarRef", "binder": "idLet" },
            "fLet":  { "type": "Let", "name": "fv", "value": "f", "body": "idLet" },
            "fRef":  { "type": "VarRef", "binder": "fLet" },
            "fb":    { "type": "Application", "function": "fRef", "arguments": ["idRef"], "typeArguments": ["T_b"] },
            "w":     { "type": "ParameterDecl", "name": "w", "paramType": "T_b" },
            "useLam":{ "type": "Lambda", "parameters": ["w"], "body": "fb" },
            "use":   { "type": "TypeAbstraction", "typeParameters": ["T_b"], "body": "useLam" },
            "s":     { "type": "StringLit", "value": "s" },
            "top":   { "type": "Application", "function": "use", "arguments": ["s"], "typeArguments": ["strT"] },
            "root":  { "type": "Let", "name": "unused", "value": "five", "body": "fLet" }
          }
        }""")
        val err = assertRejects<VerifyError.TypeParameterRebound>(r.result)
        assertEquals(r.names.getValue("T_b"), err.param)
        assertEquals(r.names.getValue("fb"), err.at)
    }

    // ---- A9: Handler signature check covers polymorphic and callback calls --

    @Test
    fun `a Handler around a polymorphic effectful callee is signature-checked at its instantiation`() {
        // polyF = Λa. λ(x: a) ![Time.Now]. x ; handler handle : () -> Int.
        // The intercepted call polyF[Int](3) passes one Int, so the handler's
        // zero-parameter signature disagrees; the check used to return early
        // because the callee's recorded type was a Forall.
        val r = verify("""{
          "version": 1, "root": "h",
          "nodes": {
            "T_a":    { "type": "TypeParameter", "name": "a" },
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "x":      { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":   { "type": "VarRef", "binder": "x" },
            "lam":    { "type": "Lambda", "parameters": ["x"], "body": "xRef", "effects": ["timeFx"] },
            "polyF":  { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "lam" },
            "three":  { "type": "IntLit", "value": 3 },
            "call":   { "type": "Application", "function": "polyF", "arguments": ["three"], "typeArguments": ["intT"] },
            "mock":   { "type": "IntLit", "value": 0 },
            "mockFn": { "type": "Lambda", "parameters": [], "body": "mock" },
            "h":      { "type": "Handler", "intercept": "timeFx", "handle": "mockFn", "body": "call" }
          }
        }""")
        assertRejects<VerifyError.HandlerSignatureMismatch>(r)
    }

    @Test
    fun `a Handler is signature-checked against calls inside a callback run by a higher-order builtin`() {
        // cb = λ(n: Int) ![Time.Now]. now()   (Let-bound outside the Handler)
        // Handler(Time.Now, handle: λ(k: Int). k, body: List.Map(xs, cb))
        // List.Map's row does not carry Time.Now, so the static closure of the
        // body has no Time.Now and the check used to be skipped; at runtime
        // the handler is invoked for now() with zero arguments.
        val r = verify("""{
          "version": 1, "root": "cbLet",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":   { "type": "FunctionType", "parameters": [], "result": "intT", "effects": ["timeFx"] },
            "now":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now", "foreignType": "nowT", "effects": ["timeFx"] },
            "n":      { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "callNow":{ "type": "Application", "function": "now", "arguments": [] },
            "cb":     { "type": "Lambda", "parameters": ["n"], "body": "callNow", "effects": ["timeFx"] },
            "cbT":    { "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": ["timeFx"] },
            "headF":  { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tailF":  { "type": "ProductTypeField", "name": "tail", "fieldType": "self" },
            "consP":  { "type": "ProductType", "fields": ["headF", "tailF"] },
            "consC":  { "type": "SumTypeCase", "name": "Cons", "caseType": "consP" },
            "nilC":   { "type": "SumTypeCase", "name": "Nil", "caseType": null },
            "body":   { "type": "SumType", "cases": ["consC", "nilC"] },
            "self":   { "type": "RecursiveSelf" },
            "listT":  { "type": "RecursiveType", "body": "body" },
            "xs":     { "type": "SumValue", "ofType": "listT", "caseName": "Nil", "payload": null },
            "mapT":   { "type": "FunctionType", "parameters": ["listT", "cbT"], "result": "listT" },
            "listMap":{ "type": "ForeignNode", "target": "strand-builtin:List.Map", "foreignType": "mapT" },
            "cbRef":  { "type": "VarRef", "binder": "cbLet" },
            "mapped": { "type": "Application", "function": "listMap", "arguments": ["xs", "cbRef"] },
            "k":      { "type": "ParameterDecl", "name": "k", "paramType": "intT" },
            "kRef":   { "type": "VarRef", "binder": "k" },
            "mockFn": { "type": "Lambda", "parameters": ["k"], "body": "kRef" },
            "h":      { "type": "Handler", "intercept": "timeFx", "handle": "mockFn", "body": "mapped" },
            "cbLet":  { "type": "Let", "name": "cb", "value": "cb", "body": "h" }
          }
        }""")
        assertRejects<VerifyError.HandlerSignatureMismatch>(r)
    }

    @Test
    fun `a Handler whose signature matches the callback's inner call still verifies`() {
        val r = verify("""{
          "version": 1, "root": "cbLet",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "nowT":   { "type": "FunctionType", "parameters": [], "result": "intT", "effects": ["timeFx"] },
            "now":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now", "foreignType": "nowT", "effects": ["timeFx"] },
            "n":      { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "callNow":{ "type": "Application", "function": "now", "arguments": [] },
            "cb":     { "type": "Lambda", "parameters": ["n"], "body": "callNow", "effects": ["timeFx"] },
            "cbT":    { "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": ["timeFx"] },
            "headF":  { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tailF":  { "type": "ProductTypeField", "name": "tail", "fieldType": "self" },
            "consP":  { "type": "ProductType", "fields": ["headF", "tailF"] },
            "consC":  { "type": "SumTypeCase", "name": "Cons", "caseType": "consP" },
            "nilC":   { "type": "SumTypeCase", "name": "Nil", "caseType": null },
            "body":   { "type": "SumType", "cases": ["consC", "nilC"] },
            "self":   { "type": "RecursiveSelf" },
            "listT":  { "type": "RecursiveType", "body": "body" },
            "xs":     { "type": "SumValue", "ofType": "listT", "caseName": "Nil", "payload": null },
            "mapT":   { "type": "FunctionType", "parameters": ["listT", "cbT"], "result": "listT" },
            "listMap":{ "type": "ForeignNode", "target": "strand-builtin:List.Map", "foreignType": "mapT" },
            "cbRef":  { "type": "VarRef", "binder": "cbLet" },
            "mapped": { "type": "Application", "function": "listMap", "arguments": ["xs", "cbRef"] },
            "mock":   { "type": "IntLit", "value": 0 },
            "mockFn": { "type": "Lambda", "parameters": [], "body": "mock" },
            "h":      { "type": "Handler", "intercept": "timeFx", "handle": "mockFn", "body": "mapped" },
            "cbLet":  { "type": "Let", "name": "cb", "value": "cb", "body": "h" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok) { "got $r" }
    }

    // ---- A10: manifest export surface includes latent effect rows ---------

    /** A manifest exporting [exportTarget] with [declared] effects, over an Fs.Write-wrapping writer. */
    private fun latentManifest(exportTarget: String, declared: String) = """{
      "version": 1, "root": "lib",
      "nodes": {
        "intT":   { "type": "PrimitiveType", "kind": "Int" },
        "strT":   { "type": "PrimitiveType", "kind": "String" },
        "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
        "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
        "writeT":  { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
        "writeFn": { "type": "ForeignNode", "target": "strand-builtin:Fs.Write",
                     "foreignType": "writeT", "effects": ["writeFx"] },
        "wPath":    { "type": "ParameterDecl", "name": "path", "paramType": "strT" },
        "wData":    { "type": "ParameterDecl", "name": "data", "paramType": "bytesT" },
        "wVarPath": { "type": "VarRef", "binder": "wPath" },
        "wVarData": { "type": "VarRef", "binder": "wData" },
        "wBody":    { "type": "Application", "function": "writeFn", "arguments": ["wVarPath", "wVarData"] },
        "writer":   { "type": "Lambda", "parameters": ["wPath", "wData"], "body": "wBody", "effects": ["writeFx"] },
        "wFnT":     { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT", "effects": ["writeFx"] },
        "toolF":    { "type": "ProductTypeField", "name": "write", "fieldType": "wFnT" },
        "toolsT":   { "type": "ProductType", "fields": ["toolF"] },
        "toolV":    { "type": "ProductFieldValue", "fieldName": "write", "value": "writer" },
        "tools":    { "type": "ProductValue", "ofType": "toolsT", "fields": ["toolV"] },
        "u":        { "type": "ParameterDecl", "name": "u", "paramType": "intT" },
        "curried":  { "type": "Lambda", "parameters": ["u"], "body": "writer" },
        "lib": { "type": "ModuleManifest", "exports": [
          { "target": "$exportTarget", "declaredEffects": $declared, "displayName": "export" }
        ] }
      }
    }"""

    @Test
    fun `a manifest exporting a record of effectful functions cannot declare no effects`() {
        val r = verify(latentManifest("tools", "[]"))
        val err = assertRejects<VerifyError.ManifestExportEffectMismatch>(r)
        assertEquals(1, err.actual.size)
        assertTrue(verify(latentManifest("tools", "[\"writeFx\"]")) is VerifyResult.Ok)
    }

    @Test
    fun `a manifest exporting a curried function with an effectful inner row cannot declare no effects`() {
        val r = verify(latentManifest("curried", "[]"))
        val err = assertRejects<VerifyError.ManifestExportEffectMismatch>(r)
        assertEquals(1, err.actual.size)
        assertTrue(verify(latentManifest("curried", "[\"writeFx\"]")) is VerifyResult.Ok)
    }

    // ---- A11: medium findings ------------------------------------------------

    @Test
    fun `a StateMachine's effectful initialState must be covered by its declared effects`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "boolT":   { "type": "PrimitiveType", "kind": "Bool" },
            "unitT":   { "type": "PrimitiveType", "kind": "Unit" },
            "emptyT":  { "type": "ProductType", "fields": [] },
            "sft":     { "type": "ProductTypeField", "name": "state",   "fieldType": "boolT" },
            "oft":     { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":    { "type": "ProductType", "fields": ["sft", "oft"] },
            "sP":      { "type": "ParameterDecl", "name": "s", "paramType": "boolT" },
            "eP":      { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
            "sRef":    { "type": "VarRef", "binder": "sP" },
            "sV":      { "type": "ProductFieldValue", "fieldName": "state",   "value": "sRef" },
            "emptyV":  { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":      { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":  { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "transitionLambda": { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "timeFx":  { "type": "EffectCategory", "categoryName": "Time.Now" },
            "f":       { "type": "BoolLit", "value": false },
            "effInit": { "type": "Lambda", "parameters": [], "body": "f", "effects": ["timeFx"] },
            "initialState": { "type": "Application", "function": "effInit", "arguments": [] },
            "receiveFx": { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "inputStream": { "type": "EventStream", "eventType": "unitT", "streamKind": "external" },
            "m": { "type": "StateMachine", "transitionFn": "transitionLambda", "initialState": "initialState",
                   "inputStreams": ["inputStream"], "outputStreams": [], "effects": ["receiveFx"] }
          }
        }""")
        val err = assertRejects<VerifyError.StateMachineEffectCoverageViolation>(r)
        assertEquals(1, err.missing.size)
    }

    @Test
    fun `a graph too deep for the recursive descent is a typed error, not a StackOverflowError`() {
        val store = org.strand.core.NodeStore()
        val boolT = store.add(org.strand.core.Node.PrimitiveType(org.strand.core.Primitive.Bool))
        val notT = store.add(org.strand.core.Node.FunctionType(parameters = listOf(boolT), result = boolT))
        val not = store.add(org.strand.core.Node.ForeignNode(target = "strand-builtin:Bool.Not", foreignType = notT))
        var cur = store.add(org.strand.core.Node.BoolLit(true))
        repeat(300_000) {
            cur = store.add(org.strand.core.Node.Application(function = not, arguments = listOf(cur)))
        }
        val r = Verifier(store).verify(cur)
        assertRejects<VerifyError.VerificationTooDeep>(r)
    }

    @Test
    fun `a handler's own effects must survive a CapabilityScope between it and the intercepted call`() {
        // Handler(Time.Now, handle: λ() ![Log.Write]. 0,
        //         body: CapabilityScope([Time.Now], now()))
        // The handler runs at now()'s site, inside the narrowed context that
        // no longer holds Log.Write, so it would always be denied at runtime.
        val r = verify("""{
          "version": 1, "root": "h",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
            "logFx":  { "type": "EffectCategory", "categoryName": "Log.Write" },
            "nowT":   { "type": "FunctionType", "parameters": [], "result": "intT", "effects": ["timeFx"] },
            "now":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now", "foreignType": "nowT", "effects": ["timeFx"] },
            "call":   { "type": "Application", "function": "now", "arguments": [] },
            "scope":  { "type": "CapabilityScope", "capabilities": ["timeFx"], "body": "call" },
            "zero":   { "type": "IntLit", "value": 0 },
            "mockFn": { "type": "Lambda", "parameters": [], "body": "zero", "effects": ["logFx"] },
            "h":      { "type": "Handler", "intercept": "timeFx", "handle": "mockFn", "body": "scope" }
          }
        }""")
        val err = assertRejects<VerifyError.CapabilityScopeUnsatisfiable>(r)
        assertEquals(1, err.missing.size)
    }

    private fun schemaSumMatch(cases: String) = """{
      "version": 1, "root": "m",
      "nodes": {
        "intT":  { "type": "PrimitiveType", "kind": "Int" },
        "boolT": { "type": "PrimitiveType", "kind": "Bool" },
        "aC":    { "type": "SumTypeCase", "name": "A", "caseType": null },
        "bC":    { "type": "SumTypeCase", "name": "B", "caseType": null },
        "sT":    { "type": "SumType", "cases": ["aC", "bC"] },
        "x":     { "type": "ParameterDecl", "name": "x", "paramType": "sT" },
        "tru":   { "type": "BoolLit", "value": true },
        "pred":  { "type": "Lambda", "parameters": ["x"], "body": "tru" },
        "inv":   { "type": "Invariant", "invariantName": "any", "targetSchema": "sch", "body": "pred" },
        "sch":   { "type": "Schema", "schemaName": "S", "valueType": "sT", "invariants": ["inv"] },
        "p":     { "type": "ParameterDecl", "name": "p", "paramType": "sch" },
        "pRef":  { "type": "VarRef", "binder": "p" },
        "one":   { "type": "IntLit", "value": 1 },
        "two":   { "type": "IntLit", "value": 2 },
        "pA":    { "type": "Pattern", "kind": "constructor", "patternType": "sT", "caseName": "A" },
        "pB":    { "type": "Pattern", "kind": "constructor", "patternType": "sT", "caseName": "B" },
        "cA":    { "type": "MatchCase", "pattern": "pA", "body": "one" },
        "cB":    { "type": "MatchCase", "pattern": "pB", "body": "two" },
        "match": { "type": "Match", "scrutinee": "pRef", "cases": $cases },
        "m":     { "type": "Lambda", "parameters": ["p"], "body": "match" }
      }
    }"""

    @Test
    fun `an exhaustive Match over a Schema-wrapped Sum verifies`() {
        val r = verify(schemaSumMatch("[\"cA\", \"cB\"]"))
        assertTrue(r is VerifyResult.Ok) { "got $r" }
    }

    @Test
    fun `a non-exhaustive Match over a Schema-wrapped Sum names the missing case`() {
        val err = assertRejects<VerifyError.NonExhaustiveMatch>(verify(schemaSumMatch("[\"cA\"]")))
        assertEquals(listOf("B"), err.missingCases)
    }

    @Test
    fun `ProductFieldGet reads through a Schema-wrapped Product`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "fX":    { "type": "ProductTypeField", "name": "x", "fieldType": "intT" },
            "pT":    { "type": "ProductType", "fields": ["fX"] },
            "v":     { "type": "ParameterDecl", "name": "v", "paramType": "pT" },
            "tru":   { "type": "BoolLit", "value": true },
            "pred":  { "type": "Lambda", "parameters": ["v"], "body": "tru" },
            "inv":   { "type": "Invariant", "invariantName": "any", "targetSchema": "sch", "body": "pred" },
            "sch":   { "type": "Schema", "schemaName": "P", "valueType": "pT", "invariants": ["inv"] },
            "p":     { "type": "ParameterDecl", "name": "p", "paramType": "sch" },
            "pRef":  { "type": "VarRef", "binder": "p" },
            "get":   { "type": "ProductFieldGet", "target": "pRef", "fieldName": "x" },
            "m":     { "type": "Lambda", "parameters": ["p"], "body": "get" }
          }
        }""")
        val ok = r as? VerifyResult.Ok ?: error("got $r")
        assertEquals(TypeExpr.Prim(org.strand.core.Primitive.Int), (ok.rootType as TypeExpr.Fun).result)
    }

    // ---- A8: NodeRefs in type position must be closed ----------------------

    @Test
    fun `a type-position NodeRef to a free TypeParameter is rejected`() {
        // forall a. (ref -> ref) where ref = NodeRef(a -> a). `a` is
        // bound only by the enclosing ForallType, so its hash would be a
        // context-free sentinel shared by every open parameter.
        val r = verify("""{
          "version": 1, "root": "id",
          "nodes": {
            "T_a":   { "type": "TypeParameter", "name": "a" },
            "open":  { "type": "FunctionType", "parameters": ["T_a"], "result": "T_a" },
            "ref":   { "type": "NodeRef", "target": "open" },
            "fnT":   { "type": "FunctionType", "parameters": ["ref"], "result": "ref" },
            "allT":  { "type": "ForallType", "typeParameters": ["T_a"], "body": "fnT" },
            "x":     { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":  { "type": "VarRef", "binder": "x" },
            "lam":   { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "id":    { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "lam" },
            "fT":    { "type": "FunctionType", "parameters": ["allT"], "result": "allT" },
            "p":     { "type": "ParameterDecl", "name": "p", "paramType": "allT" },
            "pRef":  { "type": "VarRef", "binder": "p" },
            "use":   { "type": "Lambda", "parameters": ["p"], "body": "pRef" }
          }
        }""".replace("\"root\": \"id\"", "\"root\": \"use\""))
        val err = assertRejects<VerifyError.NodeRefTargetMustBeClosed>(r)
        assertTrue(err.openReferences.isNotEmpty())
    }

    @Test
    fun `a type-position NodeRef to an escaping RecursiveSelf is rejected`() {
        // mu. Cons(NodeRef({head: Int, tail: self})) | Nil — the NodeRef
        // target contains a RecursiveSelf whose binder lies outside it.
        val r = verify("""{
          "version": 1, "root": "nil",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "self":  { "type": "RecursiveSelf" },
            "hF":    { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tF":    { "type": "ProductTypeField", "name": "tail", "fieldType": "self" },
            "open":  { "type": "ProductType", "fields": ["hF", "tF"] },
            "cons":  { "type": "NodeRef", "target": "open" },
            "cC":    { "type": "SumTypeCase", "name": "Cons", "caseType": "cons" },
            "nC":    { "type": "SumTypeCase", "name": "Nil", "caseType": null },
            "body":  { "type": "SumType", "cases": ["cC", "nC"] },
            "listT": { "type": "RecursiveType", "body": "body" },
            "nil":   { "type": "SumValue", "ofType": "listT", "caseName": "Nil", "payload": null }
          }
        }""")
        assertRejects<VerifyError.NodeRefTargetMustBeClosed>(r)
    }

    @Test
    fun `a closed type-position NodeRef still resolves`() {
        val r = verify("""{
          "version": 1, "root": "lam",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "ref":   { "type": "NodeRef", "target": "intT" },
            "x":     { "type": "ParameterDecl", "name": "x", "paramType": "ref" },
            "xRef":  { "type": "VarRef", "binder": "x" },
            "lam":   { "type": "Lambda", "parameters": ["x"], "body": "xRef" }
          }
        }""")
        assertTrue(r is VerifyResult.Ok) { "got $r" }
    }
}
