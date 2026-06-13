package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.core.RawNodeStore
import org.strand.core.StoredNode
import java.nio.file.Files
import java.nio.file.Path

/**
 * Q-058: on-disk store — round-trip, node-level dedup, fail-closed corruption,
 * fail-closed epoch/format-version mismatch, and verdict reuse.
 *
 * The round-trip property is the headline: a program written to the store and
 * read back by root hash (through the [DiskStoreResolver] + the existing
 * federation admission) reconstructs a [NodeStore] whose root re-hashes to the
 * original root hash — the same Merkle integrity the cross-store path proves.
 */
class PersistentStoreTest {

    @Test
    fun `round-trip a single-node program through the store`(@TempDir dir: Path) {
        val program = singleNodeProgram(Node.IntLit(42))
        val rootHash = program.nodeIdToHash.getValue(program.root)

        val store = PersistentStore.open(dir)
        store.writeProgram(program)

        val reconstructed = loadByHash(store, rootHash)
        assertNotNull(reconstructed)
        assertEquals(Node.IntLit(42), reconstructed!!.store.get(reconstructed.root))
        // Merkle integrity: the admitted root re-hashes to the requested hash.
        assertEquals(rootHash, Hasher(reconstructed.store).hashRoot(reconstructed.root))
    }

    @Test
    fun `round-trip a Lambda including its bound ParameterDecl`(@TempDir dir: Path) {
        val program = lambdaIdentityProgram()
        val rootHash = program.nodeIdToHash.getValue(program.root)

        val store = PersistentStore.open(dir)
        store.writeProgram(program)

        val reconstructed = loadByHash(store, rootHash)
        assertNotNull(reconstructed)
        val lam = reconstructed!!.store.get(reconstructed.root) as Node.Lambda
        // The bound ParameterDecl was re-admitted and the body VarRef re-binds to it.
        val param = reconstructed.store.get(lam.parameters.single()) as Node.ParameterDecl
        assertEquals("x", param.name)
        val body = reconstructed.store.get(lam.body) as Node.VarRef
        assertEquals(lam.parameters.single(), body.binder)
        // Merkle integrity over the whole subgraph.
        assertEquals(rootHash, Hasher(reconstructed.store).hashRoot(reconstructed.root))
    }

    @Test
    fun `node-level dedup stores a shared subgraph once`(@TempDir dir: Path) {
        // Two programs that both reference the same Int.Add ForeignNode subgraph
        // (built identically) share every hashable node of that subgraph.
        val p1 = incProgram()
        val p2 = incProgram()

        val store = PersistentStore.open(dir)
        val written1 = store.writeProgram(p1)
        val countAfter1 = store.nodeCount()
        val written2 = store.writeProgram(p2)
        val countAfter2 = store.nodeCount()

        assertTrue(written1 > 0, "first ingest writes nodes")
        assertEquals(0, written2, "second identical ingest writes nothing (full dedup)")
        assertEquals(countAfter1, countAfter2, "node count is unchanged by the duplicate ingest")
    }

    @Test
    fun `partial dedup shares the common subgraph between distinct programs`(@TempDir dir: Path) {
        // p1 = 7, p2 = an Application referencing a shared IntLit(7) leaf via a
        // shared Int.Add. Build two distinct programs that share the IntLit(1)
        // leaf hash (incProgram contains IntLit(1)).
        val store = PersistentStore.open(dir)
        val incA = incProgram()           // contains IntLit(1), Int.Add ForeignNode, Int type, FunctionType
        val one = singleNodeProgram(Node.IntLit(1))
        val oneHash = one.nodeIdToHash.getValue(one.root)

        store.writeProgram(incA)
        assertTrue(store.hasNode(oneHash), "the shared IntLit(1) leaf is present after the inc program")
        val before = store.nodeCount()
        val written = store.writeProgram(one)
        assertEquals(0, written, "ingesting the standalone IntLit(1) is a no-op — already present")
        assertEquals(before, store.nodeCount())
    }

    @Test
    fun `corruption fails closed on read`(@TempDir dir: Path) {
        val program = singleNodeProgram(Node.IntLit(42))
        val rootHash = program.nodeIdToHash.getValue(program.root)
        val store = PersistentStore.open(dir)
        store.writeProgram(program)

        // Tamper with the node's hash-relevant content: change the literal value
        // so the admitted node no longer hashes to the recorded root hash.
        val shard = "%02x".format(rootHash.bytes[0])
        val file = dir.resolve("nodes").resolve(shard).resolve("$rootHash.json")
        val text = Files.readString(file)
        val tampered = text.replace("\"value\":42", "\"value\":99")
        assertTrue(tampered != text, "the tamper actually changed the record")
        Files.writeString(file, tampered)

        // Reading + admitting fails closed: the Merkle root re-hash rejects the
        // unfaithful subgraph.
        val reopened = PersistentStore.open(dir)
        assertThrows(NodeResolverIntegrityViolation::class.java) {
            loadByHash(reopened, rootHash)
        }
    }

    @Test
    fun `corrupted rootHash key in record fails closed`(@TempDir dir: Path) {
        val program = singleNodeProgram(Node.IntLit(5))
        val rootHash = program.nodeIdToHash.getValue(program.root)
        val store = PersistentStore.open(dir)
        store.writeProgram(program)

        val shard = "%02x".format(rootHash.bytes[0])
        val file = dir.resolve("nodes").resolve(shard).resolve("$rootHash.json")
        // Rewrite the record's declared rootHash to a different (valid) hash.
        val otherHash = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { 0xab.toByte() })
        val text = Files.readString(file)
        val tampered = text.replace(rootHash.toString(), otherHash.toString())
        Files.writeString(file, tampered)

        val reopened = PersistentStore.open(dir)
        assertThrows(NodeResolverIntegrityViolation::class.java) {
            reopened.readNode(rootHash)
        }
    }

    @Test
    fun `epoch mismatch fails closed`(@TempDir dir: Path) {
        // Write a store header recording a different epoch than the running one,
        // then open it.
        PersistentStore.open(dir) // initialize at current epoch
        val header = dir.resolve("store.json")
        val text = Files.readString(header)
        val bumped = text.replace(
            "\"encodingEpoch\":${CanonicalEncoding.EPOCH}",
            "\"encodingEpoch\":${CanonicalEncoding.EPOCH + 1}",
        )
        assertTrue(bumped != text, "the epoch was actually changed in the header")
        Files.writeString(header, bumped)

        val ex = assertThrows(StoreEpochMismatch::class.java) { PersistentStore.open(dir) }
        assertEquals(CanonicalEncoding.EPOCH, ex.expected)
        assertEquals(CanonicalEncoding.EPOCH + 1, ex.found)
    }

    @Test
    fun `higher store format version is rejected`(@TempDir dir: Path) {
        PersistentStore.open(dir)
        val header = dir.resolve("store.json")
        val text = Files.readString(header)
        val bumped = text.replace(
            "\"storeFormatVersion\":${PersistentStore.STORE_FORMAT_VERSION}",
            "\"storeFormatVersion\":${PersistentStore.STORE_FORMAT_VERSION + 1}",
        )
        Files.writeString(header, bumped)
        assertThrows(StoreFormatError::class.java) { PersistentStore.open(dir) }
    }

    @Test
    fun `nodes dir without a header is a corrupt store`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("nodes"))
        assertThrows(StoreFormatError::class.java) { PersistentStore.open(dir) }
    }

    @Test
    fun `verdict round-trips and is keyed by root hash`(@TempDir dir: Path) {
        val store = PersistentStore.open(dir)
        val rootHash = singleNodeProgram(Node.IntLit(1)).let { it.nodeIdToHash.getValue(it.root) }
        val verdict = StoredVerdict.Ok(
            rootType = "Int",
            nodeTypesByHash = mapOf(rootHash.toString() to "Int"),
            warnings = listOf("warning: example"),
        )
        store.writeVerdict(rootHash, verdict)

        val read = store.readVerdict(rootHash)
        assertEquals(verdict, read)
    }

    @Test
    fun `failed verdict round-trips`(@TempDir dir: Path) {
        val store = PersistentStore.open(dir)
        val rootHash = singleNodeProgram(Node.IntLit(2)).let { it.nodeIdToHash.getValue(it.root) }
        val verdict = StoredVerdict.Failed(errors = listOf("CategoryMismatch at #3"))
        store.writeVerdict(rootHash, verdict)
        assertEquals(verdict, store.readVerdict(rootHash))
    }

    @Test
    fun `verdict recorded under a different epoch is ignored`(@TempDir dir: Path) {
        val store = PersistentStore.open(dir)
        val rootHash = singleNodeProgram(Node.IntLit(3)).let { it.nodeIdToHash.getValue(it.root) }
        store.writeVerdict(rootHash, StoredVerdict.Ok("Int", emptyMap(), emptyList()))

        // Hand-edit the verdict file's epoch to a future value.
        val shard = "%02x".format(rootHash.bytes[0])
        val vf = dir.resolve("verdicts").resolve(shard).resolve("$rootHash.json")
        val text = Files.readString(vf)
        Files.writeString(vf, text.replace(
            "\"encodingEpoch\":${CanonicalEncoding.EPOCH}",
            "\"encodingEpoch\":${CanonicalEncoding.EPOCH + 7}",
        ))
        assertNull(store.readVerdict(rootHash), "a stale-epoch verdict is ignored (re-verify), not trusted")
    }

    @Test
    fun `missing node returns null`(@TempDir dir: Path) {
        val store = PersistentStore.open(dir)
        val unknown = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { 0xff.toByte() })
        assertNull(store.readNode(unknown))
        assertFalse(store.hasNode(unknown))
    }

    // ----- helpers -----

    /**
     * Reconstruct a finalized program by root hash from the store: start with an
     * empty local store, resolve through the [DiskStoreResolver], and admit via
     * the existing federation path. Returns null when the root is not held.
     */
    private fun loadByHash(store: PersistentStore, rootHash: Hash): FinalizedProgram? {
        if (!store.hasNode(rootHash)) return null
        val empty = NodeStore()
        val federated = FederatedProgram(
            store = empty,
            root = NodeId(-1),                  // placeholder; reset to the admitted root below
            nodeIdToHash = HashMap(),
            hashToNodeId = HashMap(),
            resolver = DiskStoreResolver(store),
        )
        val localRoot = federated.fetchAndAdmit(rootHash) ?: return null
        return FinalizedProgram(
            store = federated.store,
            root = localRoot,
            nodeIdToHash = federated.nodeIdToHash,
            hashToNodeId = federated.hashToNodeId,
        )
    }

    private fun singleNodeProgram(node: Node): FinalizedProgram {
        val raw = RawNodeStore()
        val id = raw.add(StoredNode.Canonical(node))
        return Hasher(raw).finalize(id)
    }

    private fun lambdaIdentityProgram(): FinalizedProgram {
        val raw = RawNodeStore()
        val intT = raw.add(StoredNode.Canonical(Node.PrimitiveType(Primitive.Int)))
        val param = raw.add(StoredNode.Canonical(Node.ParameterDecl(name = "x", paramType = intT)))
        val body = raw.add(StoredNode.Canonical(Node.VarRef(binder = param)))
        val lam = raw.add(StoredNode.Canonical(Node.Lambda(parameters = listOf(param), body = body)))
        return Hasher(raw).finalize(lam)
    }

    private fun incProgram(): FinalizedProgram {
        val raw = RawNodeStore()
        val intT = raw.add(StoredNode.Canonical(Node.PrimitiveType(Primitive.Int)))
        val addT = raw.add(StoredNode.Canonical(Node.FunctionType(parameters = listOf(intT, intT), result = intT)))
        val addFn = raw.add(StoredNode.Canonical(Node.ForeignNode(target = "strand-builtin:Int.Add", foreignType = addT)))
        val x = raw.add(StoredNode.Canonical(Node.ParameterDecl(name = "x", paramType = intT)))
        val xRef = raw.add(StoredNode.Canonical(Node.VarRef(binder = x)))
        val one = raw.add(StoredNode.Canonical(Node.IntLit(1)))
        val addApp = raw.add(StoredNode.Canonical(Node.Application(function = addFn, arguments = listOf(xRef, one))))
        val inc = raw.add(StoredNode.Canonical(Node.Lambda(parameters = listOf(x), body = addApp)))
        return Hasher(raw).finalize(inc)
    }
}
