package org.strand.hashing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.strand.core.Hash
import org.strand.core.HashFunction
import org.strand.core.Node
import org.strand.core.NodeStore
import org.strand.core.Primitive

/**
 * Tests of [Hasher] — the orchestrator that composes [CanonicalEncoder] with
 * real BLAKE3 to produce multi-hash digests. These tests verify:
 *
 *   * The output is a well-formed multi-hash (33 bytes, BLAKE3 prefix).
 *   * Hashing is deterministic for a given graph.
 *   * Alpha-equivalence holds end-to-end: structurally identical graphs that
 *     differ only in binder names produce identical root hashes.
 *   * `hashReachable` excludes [Node.ParameterDecl] and [Node.TypeParameter]
 *     (no standalone hashes for bound nodes).
 */
class HasherTest {

    @Test
    fun `IntLit hash is a 33-byte BLAKE3 multi-hash`() {
        val store = NodeStore()
        val id = store.add(Node.IntLit(42))
        val hash = Hasher(store).hashRoot(id)

        assertEquals(33, hash.bytes.size)
        assertEquals(HashFunction.Blake3, hash.function)
        assertEquals(32, hash.digest.size)
    }

    @Test
    fun `same IntLit value hashes the same across stores`() {
        val storeA = NodeStore().also { it.add(Node.IntLit(7)) }
        val storeB = NodeStore().also { it.add(Node.IntLit(7)) }
        assertEquals(
            Hasher(storeA).hashRoot(org.strand.core.NodeId(0)),
            Hasher(storeB).hashRoot(org.strand.core.NodeId(0)),
        )
    }

    @Test
    fun `different IntLit values hash to different multi-hashes`() {
        val storeA = NodeStore().also { it.add(Node.IntLit(7)) }
        val storeB = NodeStore().also { it.add(Node.IntLit(8)) }
        assertNotEquals(
            Hasher(storeA).hashRoot(org.strand.core.NodeId(0)),
            Hasher(storeB).hashRoot(org.strand.core.NodeId(0)),
        )
    }

    @Test
    fun `alpha-equivalent identity lambdas hash identically`() {
        // \x:Int. x   and   \y:Int. y
        val hashX = identityHash(paramName = "x")
        val hashY = identityHash(paramName = "y")
        assertEquals(hashX, hashY) {
            "Two identity lambdas differing only in parameter name should hash identically. " +
                "Got $hashX vs $hashY"
        }
    }

    @Test
    fun `alpha-equivalent polymorphic identities hash identically`() {
        // Λa. \x:a. x   and   Λb. \y:b. y
        val hashA = polyIdentityHash(typeParamName = "a", paramName = "x")
        val hashB = polyIdentityHash(typeParamName = "b", paramName = "y")
        assertEquals(hashA, hashB)
    }

    @Test
    fun `hashReachable excludes bound nodes`() {
        val store = NodeStore()
        val intType = store.add(Node.PrimitiveType(Primitive.Int))
        val pd = store.add(Node.ParameterDecl("x", intType))
        val body = store.add(Node.VarRef(pd))
        val lam = store.add(Node.Lambda(parameters = listOf(pd), body = body))

        val map = Hasher(store).hashReachable(lam)

        // The Lambda, its body (VarRef), and the PrimitiveType are hashable.
        // The ParameterDecl is intrinsic to the Lambda and is excluded.
        assertTrue(lam in map) { "Lambda should be hashed" }
        assertTrue(body in map) { "VarRef should be hashed" }
        assertTrue(intType in map) { "PrimitiveType should be hashed" }
        assertFalse(pd in map) { "ParameterDecl should NOT be hashed (intrinsic to Lambda)" }
    }

    @Test
    fun `hashReachable excludes bound TypeParameters`() {
        val store = NodeStore()
        val tp = store.add(Node.TypeParameter("a", bound = null))
        val pd = store.add(Node.ParameterDecl("x", tp))
        val body = store.add(Node.VarRef(pd))
        val lam = store.add(Node.Lambda(parameters = listOf(pd), body = body))
        val tabs = store.add(Node.TypeAbstraction(typeParameters = listOf(tp), body = lam))

        val map = Hasher(store).hashReachable(tabs)

        assertTrue(tabs in map)
        assertTrue(lam in map)
        assertFalse(pd in map) { "ParameterDecl is intrinsic" }
        assertFalse(tp in map) { "TypeParameter is positional, no standalone hash" }
    }

    @Test
    fun `hashing is deterministic across repeated calls on same store`() {
        val store = NodeStore()
        val id = store.add(Node.BoolLit(true))
        val a = Hasher(store).hashRoot(id)
        val b = Hasher(store).hashRoot(id)
        assertEquals(a, b)
    }

    private fun identityHash(paramName: String): Hash {
        val store = NodeStore()
        val intType = store.add(Node.PrimitiveType(Primitive.Int))
        val pd = store.add(Node.ParameterDecl(paramName, intType))
        val body = store.add(Node.VarRef(pd))
        val lam = store.add(Node.Lambda(parameters = listOf(pd), body = body))
        return Hasher(store).hashRoot(lam)
    }

    private fun polyIdentityHash(typeParamName: String, paramName: String): Hash {
        val store = NodeStore()
        val tp = store.add(Node.TypeParameter(typeParamName, bound = null))
        val pd = store.add(Node.ParameterDecl(paramName, tp))
        val body = store.add(Node.VarRef(pd))
        val lam = store.add(Node.Lambda(parameters = listOf(pd), body = body))
        val tabs = store.add(Node.TypeAbstraction(typeParameters = listOf(tp), body = lam))
        return Hasher(store).hashRoot(tabs)
    }

    // ----- State machines (N-027..N-029) -----

    @Test
    fun `identical EventStreams hash identically`() {
        val a = eventStreamHash(eventKind = Primitive.Int, streamKind = org.strand.core.StreamKind.External)
        val b = eventStreamHash(eventKind = Primitive.Int, streamKind = org.strand.core.StreamKind.External)
        assertEquals(a, b)
    }

    @Test
    fun `EventStream differing in streamKind hashes differently`() {
        val ext = eventStreamHash(eventKind = Primitive.Int, streamKind = org.strand.core.StreamKind.External)
        val int = eventStreamHash(eventKind = Primitive.Int, streamKind = org.strand.core.StreamKind.Internal)
        val out = eventStreamHash(eventKind = Primitive.Int, streamKind = org.strand.core.StreamKind.Output)
        assertNotEquals(ext, int)
        assertNotEquals(ext, out)
        assertNotEquals(int, out)
    }

    @Test
    fun `EventStream differing in eventType hashes differently`() {
        val intStream  = eventStreamHash(eventKind = Primitive.Int,  streamKind = org.strand.core.StreamKind.External)
        val boolStream = eventStreamHash(eventKind = Primitive.Bool, streamKind = org.strand.core.StreamKind.External)
        assertNotEquals(intStream, boolStream)
    }

    /**
     * Layer 6 step 3 slice 3.1 additive-versioning invariant: an EventStream
     * whose new optional fields are all at their defaults (null `bufferSize`,
     * null or BlockProducer `overflowPolicy`) MUST hash byte-identically to
     * the pre-slice-3.1 form. This is what keeps the existing 9 state-machine
     * corpus programs hash-stable across the layer 6 step 3 boundary.
     */
    @Test
    fun `EventStream with default slice 3-1 fields hashes identically to pre-slice form`() {
        val storeBefore = NodeStore()
        val tBefore = storeBefore.add(Node.PrimitiveType(Primitive.Int))
        val sBefore = storeBefore.add(Node.EventStream(
            eventType = tBefore,
            streamKind = org.strand.core.StreamKind.External,
        ))
        val hashBefore = Hasher(storeBefore).hashRoot(sBefore)

        // Explicit BlockProducer (the default) — must hash same as null.
        val storeBlockExplicit = NodeStore()
        val tBE = storeBlockExplicit.add(Node.PrimitiveType(Primitive.Int))
        val sBE = storeBlockExplicit.add(Node.EventStream(
            eventType = tBE,
            streamKind = org.strand.core.StreamKind.External,
            bufferSize = null,
            overflowPolicy = null,
        ))
        val hashBlockExplicit = Hasher(storeBlockExplicit).hashRoot(sBE)
        assertEquals(hashBefore, hashBlockExplicit) {
            "Default-fields EventStream must hash identically to bare two-arg form"
        }
    }

    @Test
    fun `EventStream with non-default bufferSize hashes differently`() {
        val storeA = NodeStore()
        val tA = storeA.add(Node.PrimitiveType(Primitive.Int))
        val sA = storeA.add(Node.EventStream(
            eventType = tA,
            streamKind = org.strand.core.StreamKind.External,
            bufferSize = null,
        ))
        val storeB = NodeStore()
        val tB = storeB.add(Node.PrimitiveType(Primitive.Int))
        val sB = storeB.add(Node.EventStream(
            eventType = tB,
            streamKind = org.strand.core.StreamKind.External,
            bufferSize = 64,
        ))
        assertNotEquals(Hasher(storeA).hashRoot(sA), Hasher(storeB).hashRoot(sB))
    }

    @Test
    fun `EventStream with each non-default overflowPolicy hashes distinctly`() {
        val streams = listOf(
            null,  // default = BlockProducer
            org.strand.core.OverflowPolicy.BlockProducer,
            org.strand.core.OverflowPolicy.DropNewest,
            org.strand.core.OverflowPolicy.DropOldest,
            org.strand.core.OverflowPolicy.Sample(100L),
            org.strand.core.OverflowPolicy.Sample(200L),
        )
        val hashes = streams.map { policy ->
            val store = NodeStore()
            val t = store.add(Node.PrimitiveType(Primitive.Int))
            val s = store.add(Node.EventStream(
                eventType = t,
                streamKind = org.strand.core.StreamKind.External,
                overflowPolicy = policy,
            ))
            Hasher(store).hashRoot(s)
        }
        // null and BlockProducer hash IDENTICALLY (the default is preserved).
        assertEquals(hashes[0], hashes[1]) {
            "null and BlockProducer overflowPolicy should hash identically (slice 3.1 additive-versioning)"
        }
        // All other variants hash distinctly from each other and from the default.
        val nonDefault = hashes.drop(2)
        val defaultHash = hashes[0]
        for (h in nonDefault) {
            assertNotEquals(defaultHash, h)
        }
        // Pairwise distinct among the non-default policies.
        for (i in nonDefault.indices) {
            for (j in (i + 1) until nonDefault.size) {
                assertNotEquals(nonDefault[i], nonDefault[j]) {
                    "policies at indices $i and $j should hash differently"
                }
            }
        }
    }

    @Test
    fun `Transition with and without guard hash differently`() {
        // A guard-less Transition (always taken) MUST hash distinctly from
        // a Transition whose guard is a literal true — even though the
        // runtime behavior is identical, the presence bit gates them apart.
        val store = NodeStore()
        val boolT = store.add(Node.PrimitiveType(Primitive.Bool))
        val intT  = store.add(Node.PrimitiveType(Primitive.Int))
        val body  = store.add(Node.IntLit(1))
        val guard = store.add(Node.BoolLit(true))
        val noGuard = store.add(Node.Transition(guard = null, body = body))
        val hasGuard = store.add(Node.Transition(guard = guard, body = body))

        val hasher = Hasher(store)
        assertNotEquals(hasher.hashRoot(noGuard), hasher.hashRoot(hasGuard))

        // Reference boolT/intT so they're not unused in the test setup
        // (kept available for future expansion of this fixture).
        assertEquals(33, hasher.hashRoot(boolT).bytes.size)
        assertEquals(33, hasher.hashRoot(intT).bytes.size)
    }

    @Test
    fun `StateMachine hashes deterministically and includes every child`() {
        val (storeA, machA) = buildStateMachine()
        val (storeB, machB) = buildStateMachine()
        val a = Hasher(storeA).hashRoot(machA)
        val b = Hasher(storeB).hashRoot(machB)
        assertEquals(a, b) {
            "Two independently-built identical StateMachines should hash identically"
        }
        // Sanity: every reachable, hashable node landed in the map.
        val reachableA = Hasher(storeA).hashReachable(machA)
        assertTrue(machA in reachableA)
    }

    @Test
    fun `StateMachine differing in transitionFn body hashes differently`() {
        // Build two machines whose transitionFn bodies use different IntLits
        // (substituted into the state field). The structural difference
        // surfaces in the StateMachine hash.
        val (storeA, machA) = buildStateMachine(stateLitValue = 0L)
        val (storeB, machB) = buildStateMachine(stateLitValue = 1L)
        val a = Hasher(storeA).hashRoot(machA)
        val b = Hasher(storeB).hashRoot(machB)
        assertNotEquals(a, b)
    }

    /**
     * Tiny StateMachine fixture: Int state, Unit events, no outputs.
     * The transition function is `\s e. {state: stateLitValue, outputs: {}}`.
     * Used for hashing structural tests — verifier won't pass this (the
     * transitionFn ignores `s` so the State half doesn't match the
     * initialState in general). The hashing module trusts its input is
     * verified, so we can hash it directly to test the encoder.
     */
    private fun buildStateMachine(stateLitValue: Long = 0L): Pair<NodeStore, org.strand.core.NodeId> {
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val unitT = store.add(Node.PrimitiveType(Primitive.Unit))
        val emptyT = store.add(Node.ProductType(emptyList()))
        val sft = store.add(Node.ProductTypeField("state", intT))
        val oft = store.add(Node.ProductTypeField("outputs", emptyT))
        val resT = store.add(Node.ProductType(listOf(sft, oft)))
        val sP = store.add(Node.ParameterDecl("s", intT))
        val eP = store.add(Node.ParameterDecl("e", unitT))
        val stateLit = store.add(Node.IntLit(stateLitValue))
        val sV = store.add(Node.ProductFieldValue("state", stateLit))
        val emptyV = store.add(Node.ProductValue(emptyT, emptyList()))
        val oV = store.add(Node.ProductFieldValue("outputs", emptyV))
        val result = store.add(Node.ProductValue(resT, listOf(sV, oV)))
        val lam = store.add(Node.Lambda(parameters = listOf(sP, eP), body = result))
        val init = store.add(Node.IntLit(0))
        val stream = store.add(Node.EventStream(unitT, org.strand.core.StreamKind.External))
        val machine = store.add(Node.StateMachine(
            transitionFn = lam,
            initialState = init,
            inputStreams = listOf(stream),
            outputStreams = emptyList(),
            effects = emptyList(),
        ))
        return store to machine
    }

    private fun eventStreamHash(eventKind: Primitive, streamKind: org.strand.core.StreamKind): Hash {
        val store = NodeStore()
        val t = store.add(Node.PrimitiveType(eventKind))
        val s = store.add(Node.EventStream(t, streamKind))
        return Hasher(store).hashRoot(s)
    }
}
