package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.DiskStoreResolver
import org.strand.hashing.FederatedProgram
import org.strand.hashing.FinalizedProgram
import org.strand.hashing.Hasher
import org.strand.hashing.PersistentStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Q-058: the [PersistentStore] node codec round-trips every corpus program
 * across the full node-category surface. For each program: ingest, finalize,
 * write to a fresh store, read back by root hash (through the
 * [DiskStoreResolver] + the existing federation admission), and assert the
 * admitted root re-hashes to the original root hash — the Merkle integrity the
 * store relies on. This is the corpus-wide net behind the focused
 * `PersistentStoreTest` round-trip cases in `:hashing`.
 */
class CorpusPersistentStoreTest {

    private val corpusDir: Path by lazy { GoldenHashes.findCorpusDir() }

    @Test
    fun `every corpus program round-trips through the on-disk store`(@TempDir dir: Path) {
        val store = PersistentStore.open(dir)
        val programs = GoldenHashes.enumerateProgramFiles(corpusDir)
        assertTrue(programs.isNotEmpty(), "expected to find corpus programs")

        var checked = 0
        for (rel in programs) {
            val text = Files.readString(corpusDir.resolve(rel))
            val ingest = JsonIngest.parse(text)
            val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
            val rootHash = finalized.nodeIdToHash.getValue(finalized.root)

            store.writeProgram(finalized)
            assertTrue(store.hasNode(rootHash), "$rel: root present after ingest")

            val reconstructed = loadByHash(store, rootHash)
            assertNotNull(reconstructed, "$rel: reconstructable by root hash")
            assertEquals(
                rootHash,
                Hasher(reconstructed!!.store).hashRoot(reconstructed.root),
                "$rel: reconstructed root re-hashes to the original root hash",
            )
            checked++
        }
        // A second pass writes nothing new — full corpus dedup.
        val nodeCountAfterFirst = store.nodeCount()
        for (rel in programs) {
            val text = Files.readString(corpusDir.resolve(rel))
            val ingest = JsonIngest.parse(text)
            val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
            assertEquals(0, store.writeProgram(finalized), "$rel: re-ingest is a full no-op")
        }
        assertEquals(nodeCountAfterFirst, store.nodeCount(), "node count unchanged by the duplicate pass")
        assertTrue(checked > 50, "sanity: a substantial corpus was exercised (got $checked)")
    }

    private fun loadByHash(store: PersistentStore, rootHash: org.strand.core.Hash): FinalizedProgram? {
        if (!store.hasNode(rootHash)) return null
        val federated = FederatedProgram(
            store = NodeStore(),
            root = NodeId(-1),
            nodeIdToHash = HashMap(),
            hashToNodeId = HashMap(),
            resolver = DiskStoreResolver(store),
        )
        val localRoot = federated.fetchAndAdmit(rootHash) ?: return null
        return FinalizedProgram(federated.store, localRoot, federated.nodeIdToHash, federated.hashToNodeId)
    }
}
