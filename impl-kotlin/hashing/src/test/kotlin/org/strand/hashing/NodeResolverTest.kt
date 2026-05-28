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
 * Exercises [NodeResolver] in isolation: chain composition, integrity check,
 * caching. Cross-store verifier behavior is in `CrossStoreVerifierTest`
 * (verifier module) once the Verifier-side threading lands.
 */
class NodeResolverTest {

    @Test
    fun `NoOpResolver returns null for every hash`() {
        val hash = hashOf(Node.IntLit(42))
        assertNull(NoOpResolver.resolve(hash))
    }

    @Test
    fun `LocalHashStoreResolver returns nodes admitted to its store`() {
        val node = Node.IntLit(42)
        val hash = hashOf(node)
        val store = HashStore().also { it.put(hash, node) }

        val resolver = LocalHashStoreResolver(store)
        assertSame(node, resolver.resolve(hash))
    }

    @Test
    fun `LocalHashStoreResolver returns null for unknown hash`() {
        val resolver = LocalHashStoreResolver(HashStore())
        val hash = hashOf(Node.IntLit(42))
        assertNull(resolver.resolve(hash))
    }

    @Test
    fun `ChainedResolver returns first hit and short-circuits`() {
        val node = Node.IntLit(7)
        val hash = hashOf(node)

        val firstStore = HashStore().also { it.put(hash, node) }
        val secondCallCount = intArrayOf(0)
        val second = object : NodeResolver {
            override fun resolve(hash: Hash): Node? {
                secondCallCount[0]++
                return null
            }
        }

        val chain = ChainedResolver(listOf(LocalHashStoreResolver(firstStore), second))
        assertSame(node, chain.resolve(hash))
        assertEquals(0, secondCallCount[0], "Second resolver must not be consulted after first hit")
    }

    @Test
    fun `ChainedResolver falls through to second resolver on first miss`() {
        val node = Node.StringLit("library entry")
        val hash = hashOf(node)

        val firstStore = HashStore()  // empty
        val secondStore = HashStore().also { it.put(hash, node) }
        val chain = ChainedResolver(listOf(LocalHashStoreResolver(firstStore), LocalHashStoreResolver(secondStore)))

        assertSame(node, chain.resolve(hash))
    }

    @Test
    fun `ChainedResolver returns null when no resolver holds the hash`() {
        val chain = ChainedResolver(listOf(NoOpResolver, NoOpResolver))
        val hash = hashOf(Node.IntLit(42))
        assertNull(chain.resolve(hash))
    }

    @Test
    fun `ChainedResolver detects integrity violation`() {
        // A bad resolver returns the wrong node for a requested hash.
        // The chain's integrity check recomputes the hash of the returned
        // node and rejects the mismatch as NodeResolverIntegrityViolation.
        val realNode = Node.IntLit(100)
        val realHash = hashOf(realNode)
        val wrongNode = Node.IntLit(999)

        val badResolver = object : NodeResolver {
            override fun resolve(hash: Hash): Node? = wrongNode
        }
        val chain = ChainedResolver(listOf(badResolver))

        val ex = assertThrows(NodeResolverIntegrityViolation::class.java) {
            chain.resolve(realHash)
        }
        assertEquals(realHash, ex.expected)
        assertEquals(hashOf(wrongNode), ex.actual)
    }

    @Test
    fun `CachingResolver caches first-fetch results`() {
        val node = Node.IntLit(11)
        val hash = hashOf(node)
        val callCount = intArrayOf(0)
        val delegate = object : NodeResolver {
            override fun resolve(hash: Hash): Node? {
                callCount[0]++
                return node
            }
        }
        val caching = CachingResolver(delegate)

        // Three identical lookups
        assertSame(node, caching.resolve(hash))
        assertSame(node, caching.resolve(hash))
        assertSame(node, caching.resolve(hash))

        assertEquals(1, callCount[0], "Underlying resolver must be called exactly once across three lookups")
    }

    @Test
    fun `CachingResolver returns null without caching when delegate misses`() {
        val callCount = intArrayOf(0)
        val delegate = object : NodeResolver {
            override fun resolve(hash: Hash): Node? {
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
    fun `ChainedResolver with single LocalHashStoreResolver behaves identically to bare LocalHashStoreResolver`() {
        val node = Node.BoolLit(true)
        val hash = hashOf(node)
        val store = HashStore().also { it.put(hash, node) }

        val bare = LocalHashStoreResolver(store)
        val chained = ChainedResolver(listOf(LocalHashStoreResolver(store)))

        assertEquals(bare.resolve(hash), chained.resolve(hash))
    }

    @Test
    fun `LocalHashStoreResolver respects HashStore deduplication`() {
        val node = Node.IntLit(42)
        val hash = hashOf(node)
        val store = HashStore()
        assertEquals(true, store.put(hash, node), "First put admits the node")
        assertEquals(false, store.put(hash, node), "Duplicate put is a no-op")

        val resolver = LocalHashStoreResolver(store)
        assertSame(node, resolver.resolve(hash))
        assertEquals(1, store.size, "Deduplication is invisible to the resolver — one entry remains")
    }

    private fun hashOf(node: Node): Hash {
        val store = RawNodeStore()
        val id = store.add(StoredNode.Canonical(node))
        return Hasher(store).hashRoot(id)
    }
}
