package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.HashFunction
import org.strand.core.Node
import org.strand.core.RawNodeStore
import org.strand.core.StoredNode

/**
 * Q-043 step 3a — federation primitive tests.
 *
 * Exercises [NodeResolver] in isolation: chain composition, root-level
 * integrity check, caching. Per-node integrity (via the canonical
 * re-hash of each translated node) is covered by federation tests in
 * the verifier module once the translation pass lands.
 */
class NodeResolverTest {

    @Test
    fun `NoOpResolver returns null for every hash`() {
        val hash = hashOf(Node.IntLit(42))
        assertNull(NoOpResolver.resolve(hash))
    }

    @Test
    fun `LocalHashStoreResolver returns a SubgraphFetch rooted at the requested hash`() {
        val (store, nodeIdToHash) = singleNodeFinalized(Node.IntLit(42))
        val resolver = LocalHashStoreResolver(store, nodeIdToHash)
        val hash = nodeIdToHash.values.first()
        val fetch = resolver.resolve(hash)
        assertNotNull(fetch)
        assertEquals(hash, fetch!!.rootHash)
        assertEquals(1, fetch.nodes.size, "Leaf node fetch contains just the root")
        assertEquals(Node.IntLit(42), fetch.nodes[hash])
    }

    @Test
    fun `LocalHashStoreResolver returns null for unknown hash`() {
        val (store, nodeIdToHash) = singleNodeFinalized(Node.IntLit(42))
        val resolver = LocalHashStoreResolver(store, nodeIdToHash)
        val unknown = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { 0xab.toByte() })
        assertNull(resolver.resolve(unknown))
    }

    @Test
    fun `ChainedResolver returns first hit and short-circuits`() {
        val (firstStore, firstMap) = singleNodeFinalized(Node.IntLit(7))
        val hash = firstMap.values.first()

        val secondCallCount = intArrayOf(0)
        val second = object : NodeResolver {
            override fun resolve(hash: Hash): SubgraphFetch? {
                secondCallCount[0]++
                return null
            }
        }

        val chain = ChainedResolver(listOf(LocalHashStoreResolver(firstStore, firstMap), second))
        val fetch = chain.resolve(hash)
        assertNotNull(fetch)
        assertEquals(hash, fetch!!.rootHash)
        assertEquals(0, secondCallCount[0], "Second resolver must not be consulted after first hit")
    }

    @Test
    fun `ChainedResolver falls through to second resolver on first miss`() {
        val (emptyStore, emptyMap) = emptyFinalized()
        val (secondStore, secondMap) = singleNodeFinalized(Node.StringLit("library entry"))
        val hash = secondMap.values.first()

        val chain = ChainedResolver(listOf(
            LocalHashStoreResolver(emptyStore, emptyMap),
            LocalHashStoreResolver(secondStore, secondMap),
        ))
        val fetch = chain.resolve(hash)
        assertNotNull(fetch)
        assertEquals(hash, fetch!!.rootHash)
        assertEquals(Node.StringLit("library entry"), fetch.nodes[hash])
    }

    @Test
    fun `ChainedResolver returns null when no resolver holds the hash`() {
        val chain = ChainedResolver(listOf(NoOpResolver, NoOpResolver))
        val hash = hashOf(Node.IntLit(42))
        assertNull(chain.resolve(hash))
    }

    @Test
    fun `ChainedResolver detects root-hash integrity violation`() {
        // A bad resolver returns a SubgraphFetch with a rootHash that
        // does not match the requested hash. The chain rejects it as
        // NodeResolverIntegrityViolation.
        val requestedHash = hashOf(Node.IntLit(100))
        val wrongHash = hashOf(Node.IntLit(999))

        val badResolver = object : NodeResolver {
            override fun resolve(hash: Hash): SubgraphFetch? =
                SubgraphFetch(
                    rootHash = wrongHash,
                    nodes = mapOf(wrongHash to Node.IntLit(999)),
                    nodeIdToHash = emptyMap(),
                )
        }
        val chain = ChainedResolver(listOf(badResolver))

        val ex = assertThrows(NodeResolverIntegrityViolation::class.java) {
            chain.resolve(requestedHash)
        }
        assertEquals(requestedHash, ex.expected)
        assertEquals(wrongHash, ex.actual)
    }

    @Test
    fun `CachingResolver caches first-fetch results`() {
        val (store, nodeIdToHash) = singleNodeFinalized(Node.IntLit(11))
        val hash = nodeIdToHash.values.first()
        val callCount = intArrayOf(0)
        val delegate = object : NodeResolver {
            override fun resolve(hash: Hash): SubgraphFetch? {
                callCount[0]++
                return LocalHashStoreResolver(store, nodeIdToHash).resolve(hash)
            }
        }
        val caching = CachingResolver(delegate)

        // Three identical lookups
        val first = caching.resolve(hash)
        val second = caching.resolve(hash)
        val third = caching.resolve(hash)
        assertNotNull(first)
        // Caching short-circuits before the second lookup
        assertSame(first, second, "Cache returns the same SubgraphFetch instance on second lookup")
        assertSame(first, third, "Cache returns the same SubgraphFetch instance on third lookup")
        assertEquals(1, callCount[0], "Underlying resolver must be called exactly once across three lookups")
    }

    @Test
    fun `CachingResolver returns null without caching when delegate misses`() {
        val callCount = intArrayOf(0)
        val delegate = object : NodeResolver {
            override fun resolve(hash: Hash): SubgraphFetch? {
                callCount[0]++
                return null
            }
        }
        val caching = CachingResolver(delegate)

        val hash = hashOf(Node.IntLit(42))
        assertNull(caching.resolve(hash))
        assertNull(caching.resolve(hash))
        assertEquals(2, callCount[0], "Null responses are not cached — each lookup re-queries the delegate")
    }

    @Test
    fun `LocalHashStoreResolver walks past root nodes to gather multi-node subgraphs`() {
        // A multi-node subgraph that does NOT involve bound nodes
        // (ParameterDecl / TypeParameter / RecursiveSelf): a ProductType
        // referencing two PrimitiveType fields. Each node has a
        // standalone hash, so all of them appear in nodeIdToHash and
        // the resolver returns them all.
        //
        // ParameterDecl and other bound nodes are intentionally excluded
        // from nodeIdToHash by Hasher.walk; carrying them through a
        // SubgraphFetch and across stores requires handling them inline
        // as part of their parent's canonical encoding rather than as
        // standalone hash-addressable entries. The cross-store
        // re-ingest of subgraphs containing bound nodes is part of the
        // verifier-threading session's translation work — see the
        // `Node.translateNodeIds` follow-up in proposal § 4.5 step 3.
        val raw = RawNodeStore()
        val intType = raw.add(StoredNode.Canonical(Node.PrimitiveType(org.strand.core.Primitive.Int)))
        val stringType = raw.add(StoredNode.Canonical(Node.PrimitiveType(org.strand.core.Primitive.String)))
        val field0 = raw.add(StoredNode.Canonical(Node.ProductTypeField(fieldName = "id", fieldType = intType)))
        val field1 = raw.add(StoredNode.Canonical(Node.ProductTypeField(fieldName = "name", fieldType = stringType)))
        val productType = raw.add(StoredNode.Canonical(Node.ProductType(fields = listOf(field0, field1))))

        val finalized = Hasher(raw).finalize(productType)
        val hashStore = HashStore()
        for ((id, h) in finalized.nodeIdToHash) {
            hashStore.put(h, finalized.store.get(id))
        }
        val resolver = LocalHashStoreResolver(hashStore, finalized.nodeIdToHash)

        val rootHash = finalized.nodeIdToHash[productType]!!
        val fetch = resolver.resolve(rootHash)
        assertNotNull(fetch)
        assertEquals(rootHash, fetch!!.rootHash)
        // ProductType + 2 ProductTypeField + 2 PrimitiveType = 5 nodes
        // (one PrimitiveType per distinct primitive; if both fields used
        // the same primitive it would dedup).
        assert(fetch.nodes.size == 5) {
            "ProductType subgraph must include the root plus 2 ProductTypeField + 2 PrimitiveType; got ${fetch.nodes.size}"
        }
        assert(fetch.nodeIdToHash.containsKey(productType)) { "Foreign NodeId for the ProductType must be in nodeIdToHash" }
        assert(fetch.nodeIdToHash.containsKey(field0)) { "Foreign NodeId for field0 must be in nodeIdToHash" }
        assert(fetch.nodeIdToHash.containsKey(intType)) { "Foreign NodeId for intType must be in nodeIdToHash" }
    }

    private fun hashOf(node: Node): Hash {
        val store = RawNodeStore()
        val id = store.add(StoredNode.Canonical(node))
        return Hasher(store).hashRoot(id)
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

    private fun emptyFinalized(): Pair<HashStore, Map<org.strand.core.NodeId, Hash>> =
        HashStore() to emptyMap()
}
