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

    private inline fun <reified E : VerifyError> assertRejects(r: VerifyResult): E {
        val f = r as? VerifyResult.Failed ?: error("expected rejection with ${E::class.simpleName}, got $r")
        return f.errors.filterIsInstance<E>().firstOrNull()
            ?: error("expected ${E::class.simpleName}, got ${f.errors}")
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
