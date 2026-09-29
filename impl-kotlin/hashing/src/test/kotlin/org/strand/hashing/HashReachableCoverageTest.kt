package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.IngestError
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.RawNodeStore
import org.strand.core.StoredNode
import java.io.File

/**
 * Review hashing M2/M3 (2026-09-29).
 *
 * M2: `hashReachable` must visit every node the canonical encoder hashes —
 * nodes reachable only through an `effectProjections` entry (its category
 * or a `LiteralNode` source) used to be missing from `nodeIdToHash`.
 *
 * M3: an unknown multi-hash prefix in a cross-store `targetHash` must be a
 * structured ingest error, not a raw `IllegalStateException`.
 */
class HashReachableCoverageTest {

    /** Every NodeId the encoder consults while hashing [root], minus intrinsic nodes. */
    private fun encoderTouched(store: RawNodeStore, root: NodeId): Set<NodeId> {
        val touched = HashSet<NodeId>()
        val recording: StoreLookup = { id -> touched += id; store.get(id) }
        CanonicalEncoder(recording) { bytes ->
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).let { byteArrayOf(0x1e) + it }
        }.hash(root)
        return touched.filterTo(HashSet()) { id ->
            val node = (store.get(id) as? StoredNode.Canonical)?.node
            node !is Node.ParameterDecl && node !is Node.TypeParameter && node !is Node.RecursiveSelf
        }
    }

    private fun assertCovered(label: String, store: RawNodeStore, root: NodeId) {
        val reachable = Hasher(store).hashReachable(root).keys
        val missing = encoderTouched(store, root) - reachable
        assertTrue(missing.isEmpty(), "$label: encoder hashed $missing but hashReachable did not visit them")
    }

    @Test
    fun `nodes reachable only through effect projections are in nodeIdToHash`() {
        // The EffectCategory and the path StringLit are reachable only via the
        // ForeignNode's effectProjections (the effects list names another
        // category), and likewise inside the FunctionType.
        val json = """{
          "version": 1, "root": "call",
          "nodes": {
            "strT": { "type": "PrimitiveType", "kind": "String" },
            "unitT": { "type": "PrimitiveType", "kind": "Unit" },
            "declared": { "type": "EffectCategory", "categoryName": "Filesystem.Read", "parameters": ["strT"] },
            "projOnly": { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
            "path": { "type": "StringLit", "value": "/tmp/x" },
            "typePath": { "type": "StringLit", "value": "/tmp/y" },
            "fnT": { "type": "FunctionType", "parameters": ["strT"], "result": "unitT", "effects": ["declared"],
                     "effectProjections": [ { "category": "projOnly", "sources": [ { "kind": "LiteralNode", "target": "typePath" } ] } ] },
            "fn": { "type": "ForeignNode", "target": "strand-builtin:Fs.Touch", "foreignType": "fnT", "effects": ["declared"],
                    "effectProjections": [ { "category": "projOnly", "sources": [ { "kind": "LiteralNode", "target": "path" }, { "kind": "ArgRef", "index": 0 } ] } ] },
            "arg": { "type": "StringLit", "value": "a" },
            "call": { "type": "Application", "function": "fn", "arguments": ["arg"] }
          }
        }"""
        val ingest = JsonIngest.parse(json)
        val reachable = Hasher(ingest.rawStore).hashReachable(ingest.root)
        for (name in listOf("projOnly", "path", "typePath")) {
            assertTrue(ingest.nameMap.getValue(name) in reachable, "'$name' missing from hashReachable")
        }
        assertCovered("projection program", ingest.rawStore, ingest.root)
        // finalize therefore resolves those hashes back to their nodes.
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val pathHash = finalized.nodeIdToHash.getValue(ingest.nameMap.getValue("path"))
        assertTrue(finalized.hashToNodeId[pathHash] == ingest.nameMap.getValue("path"))
    }

    @Test
    fun `hashReachable covers every node the encoder touches across the corpus`() {
        // Module working directory is impl-kotlin/hashing; the corpus is at the repo root.
        val corpusDir = File("../../corpus").canonicalFile
        val programs = corpusDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" && !it.name.endsWith(".expected.json") }
            .filter { "negative" !in it.path }
            .toList()
        var checked = 0
        for (f in programs) {
            val ingest = try { JsonIngest.parse(f.readText()) } catch (e: IngestError) { continue }
            assertCovered(f.name, ingest.rawStore, ingest.root)
            checked++
        }
        assertTrue(checked > 50, "expected to sweep the corpus from $corpusDir, checked $checked")
    }

    @Test
    fun `an unknown hash prefix in targetHash is a structured ingest error`() {
        val badPrefix = "ff" + "00".repeat(32)
        val err = assertThrows<IngestError.Malformed> {
            JsonIngest.parse("""{ "version": 1, "root": "r", "nodes": { "r": { "type": "NodeRef", "targetHash": "$badPrefix" } } }""")
        }
        assertTrue(err.message!!.contains("prefix"), err.message)
        val shortDigest = "1e" + "00".repeat(8)
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse("""{ "version": 1, "root": "r", "nodes": { "r": { "type": "NodeRef", "targetHash": "$shortDigest" } } }""")
        }
        // Hash.fromHex reports bad input as IllegalArgumentException, which
        // the store / snapshot codecs and CLI already translate.
        assertThrows<IllegalArgumentException> { org.strand.core.Hash.fromHex(badPrefix) }
    }
}
