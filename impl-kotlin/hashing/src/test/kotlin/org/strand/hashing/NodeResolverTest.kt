package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.Primitive
import org.strand.core.RawNodeStore
import org.strand.core.StoredNode

/**
 * Q-043 step 3a — federation primitive tests.
 *
 * Exercises [NodeResolver] in isolation: walk completeness (including bound
 * nodes), chain composition, root-hash integrity claim check, caching.
 * Content integrity (re-hashing the admitted subgraph) is exercised in
 * [FederatedProgramTest].
 */
class NodeResolverTest {

    @Test
    fun `NoOpResolver returns null for every hash`() {
        assertNull(NoOpResolver.resolve(hashOf(Node.IntLit(42))))
    }

    @Test
    fun `LocalProgramResolver returns a SubgraphFetch rooted at the requested hash`() {
        val program = singleNodeProgram(Node.IntLit(42))
        val resolver = LocalProgramResolver(program)
        val hash = program.nodeIdToHash.getValue(program.root)
        val fetch = resolver.resolve(hash)
        assertNotNull(fetch)
        assertEquals(hash, fetch!!.rootHash)
        assertEquals(program.root, fetch.rootId)
        assertEquals(1, fetch.nodes.size, "leaf node fetch contains just the root")
        assertEquals(Node.IntLit(42), fetch.nodes[fetch.rootId])
    }

    @Test
    fun `LocalProgramResolver returns null for unknown hash`() {
        val resolver = LocalProgramResolver(singleNodeProgram(Node.IntLit(42)))
        val unknown = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { 0xab.toByte() })
        assertNull(resolver.resolve(unknown))
    }

    @Test
    fun `ChainedResolver returns first hit and short-circuits`() {
        val first = singleNodeProgram(Node.IntLit(7))
        val hash = first.nodeIdToHash.getValue(first.root)
        val secondCalls = intArrayOf(0)
        val second = object : NodeResolver {
            override fun resolve(hash: Hash): SubgraphFetch? { secondCalls[0]++; return null }
        }
        val chain = ChainedResolver(listOf(LocalProgramResolver(first), second))
        val fetch = chain.resolve(hash)
        assertNotNull(fetch)
        assertEquals(hash, fetch!!.rootHash)
        assertEquals(0, secondCalls[0], "second resolver must not be consulted after first hit")
    }

    @Test
    fun `ChainedResolver falls through to second resolver on first miss`() {
        val first = LocalProgramResolver(singleNodeProgram(Node.IntLit(111)))   // holds a different node
        val secondProgram = singleNodeProgram(Node.StringLit("library entry"))
        val hash = secondProgram.nodeIdToHash.getValue(secondProgram.root)
        val chain = ChainedResolver(listOf(first, LocalProgramResolver(secondProgram)))
        val fetch = chain.resolve(hash)
        assertNotNull(fetch)
        assertEquals(hash, fetch!!.rootHash)
        assertEquals(Node.StringLit("library entry"), fetch.nodes[fetch.rootId])
    }

    @Test
    fun `ChainedResolver returns null when no resolver holds the hash`() {
        val chain = ChainedResolver(listOf(NoOpResolver, NoOpResolver))
        assertNull(chain.resolve(hashOf(Node.IntLit(42))))
    }

    @Test
    fun `ChainedResolver detects root-hash claim violation`() {
        val requestedHash = hashOf(Node.IntLit(100))
        val wrongHash = hashOf(Node.IntLit(999))
        val badResolver = object : NodeResolver {
            override fun resolve(hash: Hash): SubgraphFetch =
                SubgraphFetch(
                    rootHash = wrongHash,
                    rootId = NodeId(0),
                    nodes = mapOf(NodeId(0) to Node.IntLit(999)),
                    nodeIdToHash = mapOf(NodeId(0) to wrongHash),
                )
        }
        val ex = assertThrows(NodeResolverIntegrityViolation::class.java) {
            ChainedResolver(listOf(badResolver)).resolve(requestedHash)
        }
        assertEquals(requestedHash, ex.expected)
        assertEquals(wrongHash, ex.actual)
    }

    @Test
    fun `CachingResolver caches first-fetch results`() {
        val program = singleNodeProgram(Node.IntLit(11))
        val hash = program.nodeIdToHash.getValue(program.root)
        val callCount = intArrayOf(0)
        val delegate = object : NodeResolver {
            override fun resolve(hash: Hash): SubgraphFetch? {
                callCount[0]++
                return LocalProgramResolver(program).resolve(hash)
            }
        }
        val caching = CachingResolver(delegate)
        val first = caching.resolve(hash)
        val second = caching.resolve(hash)
        val third = caching.resolve(hash)
        assertNotNull(first)
        assertSame(first, second)
        assertSame(first, third)
        assertEquals(1, callCount[0], "underlying resolver called exactly once across three lookups")
    }

    @Test
    fun `CachingResolver returns null without caching when delegate misses`() {
        val callCount = intArrayOf(0)
        val caching = CachingResolver(object : NodeResolver {
            override fun resolve(hash: Hash): SubgraphFetch? { callCount[0]++; return null }
        })
        val hash = hashOf(Node.IntLit(42))
        assertNull(caching.resolve(hash))
        assertNull(caching.resolve(hash))
        assertEquals(2, callCount[0], "null responses are not cached")
    }

    @Test
    fun `LocalProgramResolver walks past root to gather a multi-node subgraph`() {
        // ProductType {id: Int, name: String} — all nodes hashable, no bound nodes.
        val raw = RawNodeStore()
        val intType = raw.add(StoredNode.Canonical(Node.PrimitiveType(Primitive.Int)))
        val stringType = raw.add(StoredNode.Canonical(Node.PrimitiveType(Primitive.String)))
        val field0 = raw.add(StoredNode.Canonical(Node.ProductTypeField(fieldName = "id", fieldType = intType)))
        val field1 = raw.add(StoredNode.Canonical(Node.ProductTypeField(fieldName = "name", fieldType = stringType)))
        val productType = raw.add(StoredNode.Canonical(Node.ProductType(fields = listOf(field0, field1))))
        val program = Hasher(raw).finalize(productType)

        val fetch = LocalProgramResolver(program).resolve(program.nodeIdToHash.getValue(productType))
        assertNotNull(fetch)
        assertEquals(5, fetch!!.nodes.size, "ProductType + 2 fields + 2 primitives")
        assertEquals(5, fetch.nodeIdToHash.size, "all five are hashable")
        assertTrue(fetch.nodes.containsKey(productType))
        assertTrue(fetch.nodes.containsKey(field0))
        assertTrue(fetch.nodes.containsKey(intType))
    }

    @Test
    fun `LocalProgramResolver carries bound ParameterDecl nodes in the fetch but not in nodeIdToHash`() {
        // The identity Lambda (x: Int) -> x. Its ParameterDecl is a bound node
        // with no standalone hash: it MUST appear in `nodes` (so admission can
        // re-base it) but MUST NOT appear in `nodeIdToHash` (it has no hash).
        val raw = RawNodeStore()
        val intT = raw.add(StoredNode.Canonical(Node.PrimitiveType(Primitive.Int)))
        val param = raw.add(StoredNode.Canonical(Node.ParameterDecl(name = "x", paramType = intT)))
        val body = raw.add(StoredNode.Canonical(Node.VarRef(binder = param)))
        val lam = raw.add(StoredNode.Canonical(Node.Lambda(parameters = listOf(param), body = body)))
        val program = Hasher(raw).finalize(lam)

        val fetch = LocalProgramResolver(program).resolve(program.nodeIdToHash.getValue(lam))
        assertNotNull(fetch)
        // nodes: Lambda + ParameterDecl + PrimitiveType + VarRef
        assertEquals(4, fetch!!.nodes.size)
        assertTrue(fetch.nodes.containsKey(param), "bound ParameterDecl must be carried in the fetch")
        assertTrue(fetch.nodes[param] is Node.ParameterDecl)
        // nodeIdToHash excludes the bound ParameterDecl.
        assertFalse(fetch.nodeIdToHash.containsKey(param), "bound ParameterDecl has no hash and is excluded from nodeIdToHash")
        assertTrue(fetch.nodeIdToHash.containsKey(lam))
        assertTrue(fetch.nodeIdToHash.containsKey(body), "VarRef is hashable")
        assertTrue(fetch.nodeIdToHash.containsKey(intT))
    }

    private fun singleNodeProgram(node: Node): FinalizedProgram {
        val raw = RawNodeStore()
        val id = raw.add(StoredNode.Canonical(node))
        return Hasher(raw).finalize(id)
    }

    private fun hashOf(node: Node): Hash {
        val raw = RawNodeStore()
        val id = raw.add(StoredNode.Canonical(node))
        return Hasher(raw).hashRoot(id)
    }
}
