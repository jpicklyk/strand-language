package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
 * Q-043 step 3a — cross-store execution. The interpreter resolves a NodeRef
 * whose target is a peer-store hash via the `resolveTarget` callback (wired to
 * [FederatedProgram.fetchAndAdmit]), which fetches and re-bases the target into
 * the shared store; evaluation then proceeds into the admitted node.
 *
 * Applying a cross-store identity Lambda to a literal and getting the literal
 * back proves the admitted Lambda's body VarRef re-binds to the freshly-admitted
 * local ParameterDecl at runtime — the runtime complement to the verifier-time
 * integrity re-hash.
 */
class CrossStoreInterpreterTest {

    private fun identityLambdaPeer(): FinalizedProgram {
        val raw = RawNodeStore()
        val intT = raw.add(StoredNode.Canonical(Node.PrimitiveType(Primitive.Int)))
        val param = raw.add(StoredNode.Canonical(Node.ParameterDecl(name = "x", paramType = intT)))
        val body = raw.add(StoredNode.Canonical(Node.VarRef(binder = param)))
        val lam = raw.add(StoredNode.Canonical(Node.Lambda(parameters = listOf(param), body = body)))
        return Hasher(raw).finalize(lam)
    }

    @Test
    fun `applying a cross-store identity Lambda to a literal evaluates to that literal`() {
        val peer = identityLambdaPeer()
        val peerLambdaHash = peer.nodeIdToHash.getValue(peer.root)

        val store = NodeStore()
        store.add(Node.PrimitiveType(Primitive.Int))
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

        val result = Interpreter(app.store, app.hashToNodeId, resolveTarget = app::fetchAndAdmit)
            .eval(app.root)
        assertEquals(Value.IntV(5), result,
            "the admitted identity Lambda must return its argument — proving the body VarRef re-bound correctly")
        assertTrue(app.hashToNodeId.containsKey(peerLambdaHash),
            "the peer Lambda was admitted into the app store during evaluation")
    }
}
