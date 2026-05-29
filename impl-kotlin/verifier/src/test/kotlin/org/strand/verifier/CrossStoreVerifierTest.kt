package org.strand.verifier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.core.RawNodeStore
import org.strand.core.StoredNode
import org.strand.hashing.FederatedProgram
import org.strand.hashing.FinalizedProgram
import org.strand.hashing.Hasher
import org.strand.hashing.LocalProgramResolver

/**
 * Q-043 step 3a — cross-store NodeRef verification.
 *
 * The verifier resolves a NodeRef whose target hash is not local through the
 * `resolveTarget` callback (wired to [FederatedProgram.fetchAndAdmit]), which
 * fetches the target from a peer store, re-bases it into the shared store, and
 * returns its local NodeId; the verifier then verifies the admitted subgraph by
 * ordinary `infer`. A genuine miss (no peer holds the hash) reports
 * `NodeRefTargetUnresolvable` under federation, or the legacy
 * `NodeRefTargetNotFound` when no resolver is configured.
 *
 * The app program is constructed directly because JSON ingest does not yet
 * express a NodeRef whose target is an external peer hash (every ingested
 * NodeRef resolves to a local author id); the external-hash ingest form is part
 * of the federation CLI / corpus slice.
 */
class CrossStoreVerifierTest {

    /** The identity Lambda `(x: Int) -> x` as a finalized peer program. */
    private fun identityLambdaPeer(): FinalizedProgram {
        val raw = RawNodeStore()
        val intT = raw.add(StoredNode.Canonical(Node.PrimitiveType(Primitive.Int)))
        val param = raw.add(StoredNode.Canonical(Node.ParameterDecl(name = "x", paramType = intT)))
        val body = raw.add(StoredNode.Canonical(Node.VarRef(binder = param)))
        val lam = raw.add(StoredNode.Canonical(Node.Lambda(parameters = listOf(param), body = body)))
        return Hasher(raw).finalize(lam)
    }

    @Test
    fun `cross-store NodeRef to a peer Lambda verifies and type-checks the application`() {
        val peer = identityLambdaPeer()
        val peerLambdaHash = peer.nodeIdToHash.getValue(peer.root)

        // app: apply a NodeRef-to-the-peer-Lambda to IntLit(5).
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val arg = store.add(Node.IntLit(5))
        val ref = store.add(Node.NodeRef(target = peerLambdaHash))
        val appRoot = store.add(Node.Application(function = ref, arguments = listOf(arg)))
        val app = FederatedProgram(
            store = store,
            root = appRoot,
            nodeIdToHash = mutableMapOf(),
            hashToNodeId = mutableMapOf(),
            resolver = LocalProgramResolver(peer),
        )

        val result = Verifier(app.store, app.hashToNodeId, app::fetchAndAdmit).verify(app.root)
        assertTrue(result is VerifyResult.Ok, "expected Ok, got $result")
        assertEquals(TypeExpr.Prim(Primitive.Int), (result as VerifyResult.Ok).rootType,
            "applying the peer identity Lambda to an Int yields Int")
        assertTrue(app.hashToNodeId.containsKey(peerLambdaHash),
            "the peer Lambda was admitted into the app store and indexed by its hash")
    }

    @Test
    fun `cross-store NodeRef to a hash held by no peer reports NodeRefTargetUnresolvable`() {
        val peer = identityLambdaPeer() // does not hold the bogus hash
        val bogus = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { 0xAA.toByte() })

        val store = NodeStore()
        val ref = store.add(Node.NodeRef(target = bogus))
        val app = FederatedProgram(
            store = store, root = ref,
            nodeIdToHash = mutableMapOf(), hashToNodeId = mutableMapOf(),
            resolver = LocalProgramResolver(peer),
        )

        val result = Verifier(app.store, app.hashToNodeId, app::fetchAndAdmit).verify(app.root)
        val failed = result as? VerifyResult.Failed ?: error("expected Failed, got $result")
        assertTrue(failed.errors.any { it is VerifyError.NodeRefTargetUnresolvable },
            "federated miss reports NodeRefTargetUnresolvable, got ${failed.errors}")
    }

    @Test
    fun `a NodeRef miss with no resolver still reports the legacy NodeRefTargetNotFound`() {
        val bogus = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { 0xBB.toByte() })
        val store = NodeStore()
        val ref = store.add(Node.NodeRef(target = bogus))

        // No resolveTarget callback — the single-store path is preserved bit-for-bit.
        val result = Verifier(store, emptyMap()).verify(ref)
        val failed = result as? VerifyResult.Failed ?: error("expected Failed, got $result")
        assertTrue(failed.errors.any { it is VerifyError.NodeRefTargetNotFound },
            "non-federated miss preserves NodeRefTargetNotFound, got ${failed.errors}")
        assertFalse(failed.errors.any { it is VerifyError.NodeRefTargetUnresolvable },
            "non-federated miss must NOT report the federated NodeRefTargetUnresolvable")
    }
}
