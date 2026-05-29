package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
 * Q-043 step 3a — federated cross-store admission.
 *
 * [FederatedProgram.fetchAndAdmit] resolves a hash through the resolver chain
 * and admits the fetched subgraph into the local store, re-basing every foreign
 * NodeId reference into the local ID space via `Node.translateNodeIds`. The
 * headline test is the bound-node case: admitting a Lambda whose ParameterDecl
 * has no standalone hash, and confirming the admitted body's VarRef re-binds to
 * the freshly-admitted local ParameterDecl AND that the admitted root re-hashes
 * to the requested hash (the trust-minimizing integrity check).
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
        val resolver = LocalProgramResolver(singleNodeProgram(Node.IntLit(2)))
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
    fun `fetchAndAdmit admits a cross-store leaf and dedups on re-fetch`() {
        val app = singleNodeProgram(Node.IntLit(1)).federated(
            LocalProgramResolver(singleNodeProgram(Node.StringLit("from peer")))
        )
        val peerHash = singleNodeProgram(Node.StringLit("from peer")).let { it.nodeIdToHash[it.root]!! }

        val sizeBefore = app.store.size
        val localId = app.fetchAndAdmit(peerHash)
        assertNotEquals(null, localId)
        assertEquals(Node.StringLit("from peer"), app.store.get(localId!!))
        assertEquals(peerHash, app.nodeIdToHash[localId])
        assertEquals(localId, app.hashToNodeId[peerHash])
        assertEquals(sizeBefore + 1, app.store.size, "exactly one node admitted")

        // Re-fetch hits the now-local hash; no further admission.
        assertEquals(localId, app.fetchAndAdmit(peerHash))
        assertEquals(sizeBefore + 1, app.store.size, "re-fetch is a local hit")
    }

    @Test
    fun `fetchAndAdmit re-bases a cross-store Lambda including its bound ParameterDecl`() {
        // Peer program: the identity Lambda (x: Int) -> x. Its ParameterDecl
        // has no standalone hash and is absent from nodeIdToHash; the body's
        // VarRef binds it by foreign NodeId. Admission must admit the
        // ParameterDecl fresh and re-bind the VarRef to the local one.
        val peer = lambdaIdentityProgram()
        val lambdaHash = peer.nodeIdToHash.getValue(peer.root)

        val app = singleNodeProgram(Node.IntLit(0)).federated(LocalProgramResolver(peer))
        val localLam = app.fetchAndAdmit(lambdaHash)
            ?: error("expected the peer Lambda to be admitted")

        // The admitted root is a Lambda whose parameter and body are LOCAL ids.
        val lam = app.store.get(localLam) as Node.Lambda
        assertEquals(1, lam.parameters.size)
        val localParam = lam.parameters[0]
        val paramNode = app.store.get(localParam)
        assertTrue(paramNode is Node.ParameterDecl, "the parameter re-admitted as a local ParameterDecl, got $paramNode")

        // The body VarRef must re-bind to the freshly-admitted local ParameterDecl,
        // not to a dangling foreign NodeId.
        val bodyNode = app.store.get(lam.body)
        assertTrue(bodyNode is Node.VarRef, "the body re-admitted as a local VarRef, got $bodyNode")
        assertEquals(localParam, (bodyNode as Node.VarRef).binder,
            "the admitted VarRef must bind the local ParameterDecl (faithful re-base)")

        // Trust-minimizing integrity: the admitted local Lambda re-hashes to the
        // requested hash (fetchAndAdmit already enforced this; assert explicitly).
        assertEquals(lambdaHash, Hasher(app.store).hashRoot(localLam),
            "the admitted Lambda must canonically hash to the requested hash")
        assertEquals(localLam, app.hashToNodeId[lambdaHash])
    }

    @Test
    fun `fetchAndAdmit does not alias a fetched VarRef to a same-shaped local one`() {
        // The app is the identity Lambda (y: Int) -> y, so its store already
        // holds a VarRef to a first parameter. The peer's inc = (x: Int) -> x + 1
        // also contains a VarRef to its first parameter. finalize hashes a bare
        // VarRef through the unbound de-Bruijn sentinel, so both VarRefs share a
        // per-node hash. Interior hash-dedup would alias inc's body VarRef to the
        // app's parameter, shifting a de-Bruijn position and corrupting the
        // re-base; admitting interior nodes fresh keeps the binding faithful, so
        // the admitted Lambda binds its OWN parameter and re-hashes to the
        // requested hash.
        val peer = incProgram()
        val incHash = peer.nodeIdToHash.getValue(peer.root)
        val app = lambdaIdentityProgram().federated(LocalProgramResolver(peer))

        val localInc = app.fetchAndAdmit(incHash) ?: error("expected inc to be admitted")
        val lam = app.store.get(localInc) as Node.Lambda
        val body = app.store.get(lam.body) as Node.Application
        // body = Add(VarRef(x), 1); arguments[0] is the body's VarRef.
        val varRef = app.store.get(body.arguments[0]) as Node.VarRef
        assertEquals(lam.parameters[0], varRef.binder,
            "the admitted body VarRef must bind inc's own parameter, not a same-shaped local one")
        assertEquals(incHash, Hasher(app.store).hashRoot(localInc),
            "the faithfully re-based Lambda re-hashes to the requested hash")
    }

    @Test
    fun `fetchAndAdmit raises an integrity violation when the fetched content does not hash to the requested hash`() {
        // A resolver claims rootHash = hash(IntLit(1)) but returns IntLit(999).
        // The ChainedResolver claim-check would pass (the rootHash field equals
        // the request), so the trust comes from the post-admission re-hash.
        val claimedHash = hashOf(Node.IntLit(1))
        val liar = object : NodeResolver {
            override fun resolve(hash: Hash): SubgraphFetch =
                SubgraphFetch(
                    rootHash = hash,
                    rootId = NodeId(0),
                    nodes = mapOf(NodeId(0) to Node.IntLit(999)),
                    nodeIdToHash = mapOf(NodeId(0) to hash),
                )
        }
        val app = singleNodeProgram(Node.IntLit(0)).federated(liar)
        val ex = assertThrows(NodeResolverIntegrityViolation::class.java) {
            app.fetchAndAdmit(claimedHash)
        }
        assertEquals(claimedHash, ex.expected)
        assertEquals(hashOf(Node.IntLit(999)), ex.actual)
    }

    // ----- helpers -----

    private fun singleNodeProgram(node: Node): FinalizedProgram {
        val raw = RawNodeStore()
        val id = raw.add(StoredNode.Canonical(node))
        return Hasher(raw).finalize(id)
    }

    /** Build `inc = (x: Int) -> x + 1` as a finalized peer program. */
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

    /** Build the identity Lambda `(x: Int) -> x` as a finalized peer program. */
    private fun lambdaIdentityProgram(): FinalizedProgram {
        val raw = RawNodeStore()
        val intT = raw.add(StoredNode.Canonical(Node.PrimitiveType(Primitive.Int)))
        val param = raw.add(StoredNode.Canonical(Node.ParameterDecl(name = "x", paramType = intT)))
        val body = raw.add(StoredNode.Canonical(Node.VarRef(binder = param)))
        val lam = raw.add(StoredNode.Canonical(Node.Lambda(parameters = listOf(param), body = body)))
        return Hasher(raw).finalize(lam)
    }

    private fun hashOf(node: Node): Hash {
        val raw = RawNodeStore()
        val id = raw.add(StoredNode.Canonical(node))
        return Hasher(raw).hashRoot(id)
    }
}
