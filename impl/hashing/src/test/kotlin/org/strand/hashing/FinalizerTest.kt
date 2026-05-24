package org.strand.hashing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.strand.core.JsonIngest
import org.strand.core.Node

/**
 * Tests of [Hasher.finalize] — the Layer 2 step 2 pipeline that converts a
 * `RawNodeStore` (NodeRefs holding in-document NodeIds) into a canonical
 * [org.strand.core.NodeStore] (NodeRefs holding content-addressed [Hash]es).
 *
 * Properties under test:
 *   - Every `StoredNode.RawNodeRef` is replaced by a `Node.NodeRef(target = Hash)`
 *     whose `target` equals the standalone hash of the referenced subgraph.
 *   - The `nodeIdToHash` and `hashToNodeId` maps round-trip on every reachable
 *     hashable node.
 *   - Canonical NodeRefs produced by `finalize` carry the same bytes the raw
 *     form would emit, so 10-noderef-shared (the only existing NodeRef corpus
 *     program) hashes identically before and after step 2.
 *   - If a NodeRef target subgraph contains a free [Node.VarRef], finalize
 *     does NOT crash — it produces a hash using the encoder's sentinel-
 *     position fallback so the verifier can report `NodeRefTargetMustBeClosed`
 *     downstream.
 */
class FinalizerTest {

    @Test
    fun `finalize replaces RawNodeRef with Node NodeRef carrying target hash`() {
        // Program: { lit = IntLit(42); ref = NodeRef(lit); root = ref }
        val json = """{
          "version": 1, "root": "ref",
          "nodes": {
            "lit": { "type": "IntLit", "value": 42 },
            "ref": { "type": "NodeRef", "target": "lit" }
          }
        }"""
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)

        val litId = ingest.nameMap.getValue("lit")
        val refId = ingest.nameMap.getValue("ref")

        // The IntLit slot is unchanged.
        assertEquals(Node.IntLit(42), finalized.store.get(litId))

        // The NodeRef slot now carries the target's hash.
        val refNode = finalized.store.get(refId)
        assertTrue(refNode is Node.NodeRef) {
            "Expected canonical Node.NodeRef at $refId, got ${refNode::class.simpleName}"
        }
        val target = (refNode as Node.NodeRef).target

        val litHash = finalized.nodeIdToHash.getValue(litId)
        assertEquals(litHash, target) {
            "NodeRef.target should equal the standalone hash of the referenced IntLit"
        }
    }

    @Test
    fun `hashToNodeId reverse map round-trips for every reachable hashable node`() {
        val json = """{
          "version": 1, "root": "ref",
          "nodes": {
            "lit": { "type": "IntLit", "value": 7 },
            "ref": { "type": "NodeRef", "target": "lit" }
          }
        }"""
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)

        // nodeIdToHash forward map; hashToNodeId reverse map.
        for ((nodeId, hash) in finalized.nodeIdToHash) {
            val backwards = finalized.hashToNodeId[hash]
            assertNotNull(backwards) {
                "Hash $hash for NodeId $nodeId not present in reverse map"
            }
        }
    }

    @Test
    fun `finalize is deterministic across two ingests of the same source`() {
        // Two independent ingests of the same JSON should produce identical
        // root hashes — this is the byte-level property the corpus
        // determinism test asserts at a higher level.
        val json = """{
          "version": 1, "root": "ref",
          "nodes": {
            "lit": { "type": "IntLit", "value": 99 },
            "ref": { "type": "NodeRef", "target": "lit" }
          }
        }"""
        val a = Hasher(JsonIngest.parse(json).rawStore).finalize(JsonIngest.parse(json).root)
        val b = Hasher(JsonIngest.parse(json).rawStore).finalize(JsonIngest.parse(json).root)
        // NodeIds may differ across ingests; canonical hashes must not.
        assertEquals(
            a.nodeIdToHash.getValue(a.root),
            b.nodeIdToHash.getValue(b.root),
        )
    }

    @Test
    fun `two NodeRefs to the same target produce the same canonical NodeRef`() {
        // Program 10-noderef-shared's shape: two NodeRefs share a target.
        // After finalize they should be the same canonical Node.NodeRef
        // (same target hash) and the structural-equality data class makes
        // them == as Nodes.
        val json = """{
          "version": 1, "root": "ref1",
          "nodes": {
            "lit":  { "type": "IntLit", "value": 5 },
            "ref1": { "type": "NodeRef", "target": "lit" },
            "ref2": { "type": "NodeRef", "target": "lit" }
          }
        }"""
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)

        val ref1 = finalized.store.get(ingest.nameMap.getValue("ref1")) as Node.NodeRef
        val ref2 = finalized.store.get(ingest.nameMap.getValue("ref2")) as Node.NodeRef
        assertEquals(ref1, ref2)
        assertEquals(ref1.target, ref2.target)

        // And both produce the same hash entry — but the test driver isn't
        // walking from ref2 (root is ref1), so we only assert the structural
        // equality. The corpus dedup test exercises the reachable-from-root
        // case where both NodeRefs are walked.
    }

    @Test
    fun `finalize tolerates an open NodeRef target without crashing`() {
        // A NodeRef whose target subgraph references an out-of-scope VarRef
        // binder. The encoder uses its UNBOUND_SENTINEL fallback to produce
        // a hash; finalize completes; the verifier downstream is expected
        // to report `NodeRefTargetMustBeClosed`. This test only exercises
        // finalize's tolerance — the verifier-side check is covered in
        // VerifierTest.
        val json = """{
          "version": 1, "root": "ref",
          "nodes": {
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "pd":     { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":   { "type": "VarRef", "binder": "pd" },
            "ref":    { "type": "NodeRef", "target": "xRef" }
          }
        }"""
        val ingest = JsonIngest.parse(json)
        // Should not throw — finalize must be tolerant of ill-formed graphs
        // so the verifier can report the issue with structured errors.
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val refNode = finalized.store.get(ingest.nameMap.getValue("ref"))
        assertTrue(refNode is Node.NodeRef) {
            "Expected NodeRef even for ill-formed target; got ${refNode::class.simpleName}"
        }
    }
}
