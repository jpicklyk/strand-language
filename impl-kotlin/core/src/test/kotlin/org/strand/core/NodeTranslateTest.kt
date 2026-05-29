package org.strand.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [translateNodeIds] — the structural NodeId-rewrite used by
 * the Layer 2 step 3 federation admission protocol. These tests pin the rewrite
 * at the field level; the hash-equality property of a faithful re-base (a
 * translated node hashes identically to its source) is asserted in the
 * :hashing module where the canonical encoder is available.
 */
class NodeTranslateTest {

    // Shift every NodeId by +1000 — an easy-to-assert bijection.
    private val shift: (NodeId) -> NodeId = { NodeId(it.value + 1000) }

    @Test
    fun `Lambda rewrites parameters body and effects`() {
        val lam = Node.Lambda(parameters = listOf(NodeId(1), NodeId(2)), body = NodeId(3), effects = listOf(NodeId(4)))
        val t = lam.translateNodeIds(shift) as Node.Lambda
        assertEquals(listOf(NodeId(1001), NodeId(1002)), t.parameters)
        assertEquals(NodeId(1003), t.body)
        assertEquals(listOf(NodeId(1004)), t.effects)
    }

    @Test
    fun `VarRef rewrites its binder`() {
        assertEquals(NodeId(1007), (Node.VarRef(NodeId(7)).translateNodeIds(shift) as Node.VarRef).binder)
    }

    @Test
    fun `NodeRef is unchanged - its target is a Hash boundary`() {
        val ref = Node.NodeRef(target = Hash(ByteArray(33) { i -> if (i == 0) 0x1e.toByte() else 0x42.toByte() }))
        val t = ref.translateNodeIds(shift)
        assertSame(ref, t, "NodeRef has no NodeId fields and should be returned unchanged")
    }

    @Test
    fun `ForallType rewrites the binder typeParameters - which childNodeIds omits`() {
        val fa = Node.ForallType(typeParameters = listOf(NodeId(5), NodeId(6)), body = NodeId(7))
        val t = fa.translateNodeIds(shift) as Node.ForallType
        assertEquals(listOf(NodeId(1005), NodeId(1006)), t.typeParameters)
        assertEquals(NodeId(1007), t.body)
    }

    @Test
    fun `TypeAbstraction rewrites the binder typeParameters`() {
        val ta = Node.TypeAbstraction(typeParameters = listOf(NodeId(8)), body = NodeId(9))
        val t = ta.translateNodeIds(shift) as Node.TypeAbstraction
        assertEquals(listOf(NodeId(1008)), t.typeParameters)
        assertEquals(NodeId(1009), t.body)
    }

    @Test
    fun `Invariant rewrites targetSchema and body - targetSchema omitted by childNodeIds`() {
        val inv = Node.Invariant(invariantName = "x", targetSchema = NodeId(10), body = NodeId(11))
        val t = inv.translateNodeIds(shift) as Node.Invariant
        assertEquals(NodeId(1010), t.targetSchema)
        assertEquals(NodeId(1011), t.body)
        assertEquals("x", t.invariantName, "name is metadata, preserved")
    }

    @Test
    fun `FunctionType rewrites effectProjection category and LiteralNode target`() {
        val ft = Node.FunctionType(
            parameters = listOf(NodeId(1)),
            result = NodeId(2),
            effects = listOf(NodeId(3)),
            effectProjections = listOf(
                EffectProjection(
                    category = NodeId(3),
                    sources = listOf(ProjectionSource.ArgRef(0), ProjectionSource.LiteralNode(NodeId(4))),
                )
            ),
        )
        val t = ft.translateNodeIds(shift) as Node.FunctionType
        assertEquals(NodeId(1003), t.effectProjections[0].category)
        assertEquals(ProjectionSource.ArgRef(0), t.effectProjections[0].sources[0], "ArgRef index is not a NodeId")
        assertEquals(NodeId(1004), (t.effectProjections[0].sources[1] as ProjectionSource.LiteralNode).target)
    }

    @Test
    fun `ConstructorPattern rewrites patternType and payloadPattern but preserves caseName`() {
        val p = Node.Pattern.ConstructorPattern(patternType = NodeId(1), caseName = "Cons", payloadPattern = NodeId(2))
        val t = p.translateNodeIds(shift) as Node.Pattern.ConstructorPattern
        assertEquals(NodeId(1001), t.patternType)
        assertEquals(NodeId(1002), t.payloadPattern)
        assertEquals("Cons", t.caseName)
    }

    @Test
    fun `ModuleManifest rewrites declaredEffects but leaves export target Hash unchanged`() {
        val targetHash = Hash(ByteArray(33) { i -> if (i == 0) 0x1e.toByte() else 0x99.toByte() })
        val m = Node.ModuleManifest(
            exports = listOf(ManifestExport(target = targetHash, declaredEffects = listOf(NodeId(1), NodeId(2)), displayName = "f")),
            manifestSignature = byteArrayOf(1, 2, 3),
        )
        val t = m.translateNodeIds(shift) as Node.ModuleManifest
        assertEquals(targetHash, t.exports[0].target, "export target is a Hash boundary, unchanged")
        assertEquals(listOf(NodeId(1001), NodeId(1002)), t.exports[0].declaredEffects)
        assertEquals("f", t.exports[0].displayName)
    }

    @Test
    fun `RecursiveSelf and literals have no NodeId fields`() {
        assertSame(Node.RecursiveSelf(depth = 2), Node.RecursiveSelf(depth = 2).translateNodeIds(shift).let { it })
        assertEquals(2, (Node.RecursiveSelf(depth = 2).translateNodeIds(shift) as Node.RecursiveSelf).depth)
        assertEquals(42L, (Node.IntLit(42).translateNodeIds(shift) as Node.IntLit).value)
    }

    @Test
    fun `identity translation returns an equal node`() {
        val app = Node.Application(
            function = NodeId(1), arguments = listOf(NodeId(2), NodeId(3)),
            typeArguments = listOf(NodeId(4)), effectInstances = listOf(NodeId(5)),
        )
        assertEquals(app, app.translateNodeIds { it })
    }

    @Test
    fun `translation composes - shift then unshift round-trips`() {
        val sm = Node.StateMachine(
            transitionFn = NodeId(1), initialState = NodeId(2),
            inputStreams = listOf(NodeId(3)), outputStreams = listOf(NodeId(4)), effects = listOf(NodeId(5)),
        )
        val there = sm.translateNodeIds(shift)
        val back = there.translateNodeIds { NodeId(it.value - 1000) }
        assertEquals(sm, back)
        assertTrue(there != sm)
    }
}
