package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeStore
import org.strand.core.RawNodeStore
import org.strand.core.StoredNode

/**
 * Q-043 step 3a — federated program tests.
 *
 * Covers the [FederatedProgram] data class surface plus the
 * [FinalizedProgram.federated] upgrade extension. The
 * [FederatedProgram.fetchAndAdmit] cross-store admission is covered
 * partially here (local-hit path); the cross-store translation path
 * is gated on `Node.translateNodeIds` (queued for the verifier-threading
 * commit).
 */
class FederatedProgramTest {

    @Test
    fun `FinalizedProgram upgrades to FederatedProgram with NoOpResolver by default`() {
        val finalized = singleNodeProgram(Node.IntLit(42))
        val federated = finalized.federated()
        assertEquals(finalized.store.size, federated.store.size)
        assertEquals(finalized.root, federated.root)
        assertEquals(finalized.nodeIdToHash, federated.nodeIdToHash)
        assertEquals(finalized.hashToNodeId, federated.hashToNodeId)
        assertSame(NoOpResolver, federated.resolver)
    }

    @Test
    fun `federated() upgrade carries the supplied resolver`() {
        val finalized = singleNodeProgram(Node.IntLit(1))
        val emptyStore = HashStore()
        val resolver = LocalHashStoreResolver(emptyStore, emptyMap())
        val federated = finalized.federated(resolver)
        assertSame(resolver, federated.resolver)
    }

    @Test
    fun `fetchAndAdmit returns the existing NodeId when hash is local`() {
        val finalized = singleNodeProgram(Node.IntLit(42))
        val federated = finalized.federated()

        val existingHash = finalized.nodeIdToHash[finalized.root]!!
        val nodeId = federated.fetchAndAdmit(existingHash)
        assertEquals(finalized.root, nodeId, "Existing hash returns its local NodeId without consulting resolver")
        assertEquals(finalized.store.size, federated.store.size, "No new admissions when the hash is local")
    }

    @Test
    fun `fetchAndAdmit returns null when hash is not resolvable`() {
        val finalized = singleNodeProgram(Node.IntLit(42))
        val federated = finalized.federated()

        val unknown = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { 0xff.toByte() })
        assertNull(federated.fetchAndAdmit(unknown))
    }

    @Test
    fun `fetchAndAdmit raises NotImplementedError when cross-store translation is needed`() {
        // Foundation behaviour: until Node.translateNodeIds lands, fetching
        // a non-local hash through the resolver is detected and surfaced
        // explicitly rather than admitting un-translated nodes that would
        // corrupt internal NodeId references.
        val finalized = singleNodeProgram(Node.IntLit(1))
        val foreignNode = Node.StringLit("from peer store")
        val foreignHash = hashOf(foreignNode)

        val (peerStore, peerMap) = singleNodeFinalized(foreignNode)
        val federated = finalized.federated(LocalHashStoreResolver(peerStore, peerMap))

        val ex = assertThrows(NotImplementedError::class.java) {
            federated.fetchAndAdmit(foreignHash)
        }
        assert(ex.message?.contains("translateNodeIds") == true) {
            "NotImplementedError should mention the translateNodeIds requirement; got: ${ex.message}"
        }
    }

    private fun singleNodeProgram(node: Node): FinalizedProgram {
        val raw = RawNodeStore()
        val id = raw.add(StoredNode.Canonical(node))
        return Hasher(raw).finalize(id)
    }

    private fun singleNodeFinalized(node: Node): Pair<HashStore, Map<org.strand.core.NodeId, Hash>> {
        val raw = RawNodeStore()
        val id = raw.add(StoredNode.Canonical(node))
        val finalized = Hasher(raw).finalize(id)
        val store = HashStore()
        for ((nid, h) in finalized.nodeIdToHash) {
            store.put(h, finalized.store.get(nid))
        }
        return store to finalized.nodeIdToHash
    }

    private fun hashOf(node: Node): Hash {
        val store = RawNodeStore()
        val id = store.add(StoredNode.Canonical(node))
        return Hasher(store).hashRoot(id)
    }
}
