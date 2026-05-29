package org.strand.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.core.RawNodeStore
import org.strand.core.StoredNode
import org.strand.hashing.FederatedProgram
import org.strand.hashing.FinalizedProgram
import org.strand.hashing.Hasher
import org.strand.hashing.LocalProgramResolver
import org.strand.verifier.TypeExpr
import org.strand.verifier.VerifyResult

/**
 * Q-043 step 3a — the SchemaChecker resolving a cross-store value.
 *
 * The static-value evaluator walks a Schema-typed position; when that value is
 * a `NodeRef` whose target lives in a peer store, the checker's `resolveTarget`
 * callback (wired to `FederatedProgram::fetchAndAdmit`) fetches and re-bases the
 * target into the shared store so the invariant body can run against the
 * concrete value. Without the callback the value is "not statically known" and
 * the check defers — these tests assert both directions, plus that a negative
 * cross-store value produces a real violation.
 *
 * The [VerifyResult.Ok] is hand-built rather than produced by the verifier: the
 * verifier would itself admit the cross-store target while type-checking the
 * NodeRef, leaving nothing for the SchemaChecker's own callback to resolve. By
 * constructing the federated program with an empty `hashToNodeId`, the NodeRef
 * target is genuinely absent at check time, so the SchemaChecker's resolution
 * path is the one under test.
 */
class CrossStoreSchemaTest {

    /** A single-node peer program exporting one IntLit; returns (program, hash). */
    private fun intLitPeer(value: Long): Pair<FinalizedProgram, org.strand.core.Hash> {
        val raw = RawNodeStore()
        val id = raw.add(StoredNode.Canonical(Node.IntLit(value)))
        val peer = Hasher(raw).finalize(id)
        return peer to peer.nodeIdToHash.getValue(peer.root)
    }

    /**
     * Build an application whose store holds a `PositiveInt` Schema (valueType
     * Int, invariant `x > 0`) plus a `NodeRef` to a peer-held IntLit. Returns
     * the federated program, the NodeRef's local id, and the SchemaType the
     * verifier would have assigned the NodeRef position.
     */
    private fun appReferencingPeer(peer: FinalizedProgram, targetHash: org.strand.core.Hash): Triple<FederatedProgram, NodeId, TypeExpr.SchemaType> {
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val boolT = store.add(Node.PrimitiveType(Primitive.Bool))
        val zero = store.add(Node.IntLit(0))
        val xParam = store.add(Node.ParameterDecl("x", intT))
        val xRef = store.add(Node.VarRef(xParam))
        val gtT = store.add(Node.FunctionType(parameters = listOf(intT, intT), result = boolT))
        val gtFn = store.add(Node.ForeignNode(target = "strand-builtin:Int.Gt", foreignType = gtT))
        val gtBody = store.add(Node.Application(function = gtFn, arguments = listOf(xRef, zero)))
        val predLam = store.add(Node.Lambda(parameters = listOf(xParam), body = gtBody))
        // targetSchema is a defensive verifier-side topology field; the
        // SchemaChecker never reads it, so a harmless placeholder (intT) keeps
        // the Schema↔Invariant construction acyclic here.
        val positiveInvariant = store.add(Node.Invariant("x_positive", targetSchema = intT, body = predLam))
        store.add(Node.Schema("PositiveInt", valueType = intT, invariants = listOf(positiveInvariant)))
        val schemaId = NodeId(store.size - 1)
        val nodeRef = store.add(Node.NodeRef(target = targetHash))

        val federated = FederatedProgram(
            store = store,
            root = nodeRef,
            nodeIdToHash = mutableMapOf(),
            hashToNodeId = mutableMapOf(),
            resolver = LocalProgramResolver(peer),
        )
        val schemaType = TypeExpr.SchemaType(schemaId, TypeExpr.Prim(Primitive.Int), listOf(positiveInvariant))
        return Triple(federated, nodeRef, schemaType)
    }

    @Test
    fun `cross-store positive value passes the invariant via resolveTarget`() {
        val (peer, hash) = intLitPeer(5)
        val (app, nodeRef, schemaType) = appReferencingPeer(peer, hash)
        val verifyResult = VerifyResult.Ok(rootType = schemaType, nodeTypes = mapOf(nodeRef to schemaType))

        val result = SchemaChecker(app.store, app.hashToNodeId, verifyResult, resolveTarget = app::fetchAndAdmit).check()
        assertTrue(result.violations.isEmpty(), "5 > 0 across the store boundary must pass; got ${result.violations}")
        assertTrue(result.deferred.isEmpty(), "the resolver makes the value statically known; nothing should defer")
    }

    @Test
    fun `cross-store negative value violates the invariant via resolveTarget`() {
        val (peer, hash) = intLitPeer(-4)
        val (app, nodeRef, schemaType) = appReferencingPeer(peer, hash)
        val verifyResult = VerifyResult.Ok(rootType = schemaType, nodeTypes = mapOf(nodeRef to schemaType))

        val result = SchemaChecker(app.store, app.hashToNodeId, verifyResult, resolveTarget = app::fetchAndAdmit).check()
        assertEquals(1, result.violations.size, "-4 > 0 across the store boundary must violate the invariant")
    }

    @Test
    fun `without a resolver the cross-store value defers rather than checking`() {
        val (peer, hash) = intLitPeer(5)
        val (app, nodeRef, schemaType) = appReferencingPeer(peer, hash)
        val verifyResult = VerifyResult.Ok(rootType = schemaType, nodeTypes = mapOf(nodeRef to schemaType))

        // No resolveTarget: the NodeRef target is in no local map, so the value
        // is not statically known and the check defers — single-store behaviour.
        val result = SchemaChecker(app.store, app.hashToNodeId, verifyResult).check()
        assertTrue(result.violations.isEmpty())
        assertTrue(result.deferred.isNotEmpty(), "an unresolvable cross-store value must defer, not check")
    }
}
