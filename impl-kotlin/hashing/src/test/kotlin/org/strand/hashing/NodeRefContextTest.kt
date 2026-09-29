package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node

/**
 * Review H1 (2026-09-29): a local NodeRef target, and a ModuleManifest export
 * target, is hashed under the EMPTY binder context (no binder frames, no
 * enclosing recursive binders) per design/canonical-encoding.md and the
 * independent Python encoder — never in the context the reference sits in.
 *
 * The targets below are deliberately NOT closed (a free VarRef, a free
 * RecursiveSelf): that is the only shape where the enclosing context changes
 * the target's bytes, and the verifier does not check type-position NodeRefs
 * for closedness, so such targets reach the hasher in admitted programs.
 */
class NodeRefContextTest {

    private fun finalizeJson(json: String) = JsonIngest.parse(json).let { Hasher(it.rawStore).finalize(it.root) }

    private fun rootHash(json: String) = JsonIngest.parse(json).let { Hasher(it.rawStore).hashRoot(it.root) }

    private fun nodeRefTarget(json: String, refName: String): org.strand.core.Hash {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val ref = finalized.store.get(ingest.nameMap.getValue(refName))
        assertTrue(ref is Node.NodeRef)
        return (ref as Node.NodeRef).target
    }

    // The target `v` (a VarRef to `p`) standing alone at top level.
    private val topLevelVarRef = """{
      "version": 1, "root": "v",
      "nodes": {
        "int": { "type": "PrimitiveType", "kind": "Int" },
        "p": { "type": "ParameterDecl", "name": "p", "paramType": "int" },
        "v": { "type": "VarRef", "binder": "p" }
      }
    }"""

    @Test
    fun `NodeRef inside a Lambda hashes its target in the empty context`() {
        val insideLambda = """{
          "version": 1, "root": "lam",
          "nodes": {
            "int": { "type": "PrimitiveType", "kind": "Int" },
            "p": { "type": "ParameterDecl", "name": "p", "paramType": "int" },
            "v": { "type": "VarRef", "binder": "p" },
            "ref": { "type": "NodeRef", "target": "v" },
            "lam": { "type": "Lambda", "parameters": ["p"], "body": "ref" }
          }
        }"""
        assertEquals(rootHash(topLevelVarRef), nodeRefTarget(insideLambda, "ref"))
    }

    @Test
    fun `NodeRef inside a RecursiveType hashes its target with no enclosing recursive binder`() {
        val targetAlone = """{
          "version": 1, "root": "pt",
          "nodes": {
            "self": { "type": "RecursiveSelf" },
            "f": { "type": "ProductTypeField", "name": "next", "fieldType": "self" },
            "pt": { "type": "ProductType", "fields": ["f"] }
          }
        }"""
        val insideMu = """{
          "version": 1, "root": "mu",
          "nodes": {
            "self": { "type": "RecursiveSelf" },
            "f": { "type": "ProductTypeField", "name": "next", "fieldType": "self" },
            "pt": { "type": "ProductType", "fields": ["f"] },
            "ref": { "type": "NodeRef", "target": "pt" },
            "mu": { "type": "RecursiveType", "body": "ref" }
          }
        }"""
        assertEquals(rootHash(targetAlone), nodeRefTarget(insideMu, "ref"))
    }

    @Test
    fun `re-hashing a finalized store reproduces the root hash for nested NodeRefs`() {
        // `v` is reached twice: directly as the Let's value (Lambda context)
        // and through `ref` inside the Let body (Lambda + Let context). The
        // raw encoding and the finalized NodeRef.target must agree.
        val json = """{
          "version": 1, "root": "lam",
          "nodes": {
            "int": { "type": "PrimitiveType", "kind": "Int" },
            "p": { "type": "ParameterDecl", "name": "p", "paramType": "int" },
            "v": { "type": "VarRef", "binder": "p" },
            "ref": { "type": "NodeRef", "target": "v" },
            "let": { "type": "Let", "name": "x", "value": "v", "body": "ref" },
            "lam": { "type": "Lambda", "parameters": ["p"], "body": "let" }
          }
        }"""
        val finalized = finalizeJson(json)
        assertEquals(finalized.nodeIdToHash.getValue(finalized.root), Hasher(finalized.store).hashRoot(finalized.root))
        // The empty-context target hash resolves back to the target.
        val ingest = JsonIngest.parse(json)
        val ref = finalized.store.get(ingest.nameMap.getValue("ref")) as Node.NodeRef
        assertEquals(ingest.nameMap.getValue("v"), finalized.hashToNodeId[ref.target])
    }

    @Test
    fun `ModuleManifest export target is hashed in the empty context`() {
        // The export target `lam` is closed, so its hash is the same in every
        // context; re-hashing the finalized manifest must reproduce the root.
        val json = """{
          "version": 1, "root": "m",
          "nodes": {
            "int": { "type": "PrimitiveType", "kind": "Int" },
            "p": { "type": "ParameterDecl", "name": "p", "paramType": "int" },
            "v": { "type": "VarRef", "binder": "p" },
            "lam": { "type": "Lambda", "parameters": ["p"], "body": "v" },
            "m": { "type": "ModuleManifest", "exports": [ { "target": "lam", "displayName": "id" } ] }
          }
        }"""
        val finalized = finalizeJson(json)
        assertEquals(finalized.nodeIdToHash.getValue(finalized.root), Hasher(finalized.store).hashRoot(finalized.root))
    }
}
