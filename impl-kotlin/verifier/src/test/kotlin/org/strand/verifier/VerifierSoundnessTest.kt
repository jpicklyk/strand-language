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
