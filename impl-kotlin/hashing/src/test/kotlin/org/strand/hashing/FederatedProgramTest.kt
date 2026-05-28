package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
 * [FinalizedProgram.federated] upgrade extension and the
 * [FederatedProgram.fetchAndAdmit] mutation path.
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
        val resolver = LocalHashStoreResolver(HashStore())
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
    fun `fetchAndAdmit admits a resolved node and extends both maps`() {
        val finalized = singleNodeProgram(Node.IntLit(1))
        val foreignNode = Node.StringLit("from peer store")
        val foreignHash = hashOf(foreignNode)

        val peerStore = HashStore().also { it.put(foreignHash, foreignNode) }
        val federated = finalized.federated(LocalHashStoreResolver(peerStore))

        val sizeBefore = federated.store.size
        val newId = federated.fetchAndAdmit(foreignHash)
        assertNotNull(newId)
        assertEquals(sizeBefore + 1, federated.store.size, "Admitted node grows the local store by one")
        assertEquals(foreignNode, federated.store.get(newId!!), "Local store now holds the foreign node")
        assertEquals(foreignHash, federated.nodeIdToHash[newId], "nodeIdToHash extended with the admitted NodeId")
        assertEquals(newId, federated.hashToNodeId[foreignHash], "hashToNodeId extended with the admitted hash")
    }

    @Test
    fun `repeated fetchAndAdmit for the same hash returns the same NodeId without re-admission`() {
        val finalized = singleNodeProgram(Node.IntLit(1))
        val foreignNode = Node.StringLit("once-only")
        val foreignHash = hashOf(foreignNode)
        val peerStore = HashStore().also { it.put(foreignHash, foreignNode) }
        val federated = finalized.federated(LocalHashStoreResolver(peerStore))

        val first = federated.fetchAndAdmit(foreignHash)
        val storeSizeAfterFirst = federated.store.size
        val second = federated.fetchAndAdmit(foreignHash)

        assertEquals(first, second, "Both fetches return the same locally-assigned NodeId")
        assertEquals(storeSizeAfterFirst, federated.store.size, "No double-admission")
    }

    @Test
    fun `fetchAndAdmit through ChainedResolver triggers integrity check`() {
        val finalized = singleNodeProgram(Node.IntLit(1))
        val node = Node.BoolLit(true)
        val nodeHash = hashOf(node)

        val peerStore = HashStore().also { it.put(nodeHash, node) }
        val chain = ChainedResolver(listOf(LocalHashStoreResolver(peerStore)))
        val federated = finalized.federated(chain)

        // Happy path — integrity check passes because the store admitted by hash
        val admittedId = federated.fetchAndAdmit(nodeHash)
        assertNotNull(admittedId)
        assertEquals(node, federated.store.get(admittedId!!))
    }

    private fun singleNodeProgram(node: Node): FinalizedProgram {
        val raw = RawNodeStore()
        val id = raw.add(StoredNode.Canonical(node))
        return Hasher(raw).finalize(id)
    }

    private fun hashOf(node: Node): Hash {
        val store = RawNodeStore()
        val id = store.add(StoredNode.Canonical(node))
        return Hasher(store).hashRoot(id)
    }
}
