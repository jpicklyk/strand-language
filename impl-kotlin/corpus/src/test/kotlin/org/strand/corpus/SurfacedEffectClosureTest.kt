package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.childNodeIds
import org.strand.hashing.Hasher
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * Q-067: the verifier surfaces its own effect closure on [VerifyResult.Ok]
 * (`nodeClosures`, keyed by NodeId exactly as `nodeTypes` is) plus a
 * [VerifyResult.Ok.rootClosure] accessor for the program root's closure — the
 * `closure(g)` half of the Q-044 harm bound. This test pins two properties:
 *
 *  - For a program with no Handler, the surfaced root closure (translated back
 *    to EffectCategory names) equals an independent structural walk from the
 *    root that collects every reachable EffectCategory — the closure the
 *    containment host would otherwise re-derive by hand.
 *  - For a program with a Handler that intercepts an effect, the surfaced root
 *    closure is Handler-aware: the intercepted category is subtracted. The
 *    independent structural walk, being Handler-unaware, still reports the
 *    intercepted category. This is the case the demonstration's own walk
 *    (`ContainmentDemo.declaredEffectClosure`) cannot get right — the surfaced
 *    value is the exact closure the verifier enforced, not a looser bound.
 */
class SurfacedEffectClosureTest {

    private data class Loaded(
        val store: NodeStore,
        val root: NodeId,
        val verify: VerifyResult.Ok,
        val nameToId: Map<String, NodeId>,
    )

    private fun load(resource: String): Loaded {
        val text = SurfacedEffectClosureTest::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.readText()
            ?: error("missing resource $resource")
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok, "verifier failed for $resource: $verify")
        verify as VerifyResult.Ok
        return Loaded(finalized.store, finalized.root, verify, ingest.nameMap)
    }

    /**
     * The structural-walk closure the containment host re-derives: every
     * EffectCategory reachable from [root] by induction over the graph. This is
     * Handler-unaware — it never subtracts an intercepted category, exactly the
     * looser bound `ContainmentDemo.declaredEffectClosure` produces.
     */
    private fun structuralWalkCategoryNames(store: NodeStore, root: NodeId): Set<String> {
        val seen = HashSet<NodeId>()
        val stack = ArrayDeque<NodeId>()
        stack.addLast(root)
        val names = sortedSetOf<String>()
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            val node = store.getOrNull(id) ?: continue
            if (node is Node.EffectCategory) names += node.categoryName
            for (child in node.childNodeIds()) stack.addLast(child)
        }
        return names
    }

    /** Render a set of EffectCategory NodeIds to their category names. */
    private fun categoryNames(store: NodeStore, ids: Set<NodeId>): Set<String> =
        ids.mapNotNull { (store.getOrNull(it) as? Node.EffectCategory)?.categoryName }
            .toSortedSet()

    @Test
    fun `surfaced root closure equals the structural walk for an effectful program with no handler`() {
        // Corpus 16: Time.Now under a granted Time.Now capability. No Handler,
        // so the verifier's closure and a structural walk must agree.
        val p = load("/corpus/16-builtin-time-now-under-capability.json")

        val surfaced = categoryNames(p.store, p.verify.rootClosure(p.root))
        val walked = structuralWalkCategoryNames(p.store, p.root)

        assertEquals(setOf("Time.Now"), surfaced, "surfaced root closure should be {Time.Now}")
        assertEquals(walked, surfaced, "surfaced closure must equal the host's structural walk")
    }

    @Test
    fun `surfaced node closures are keyed by NodeId within the typed-node set`() {
        // The surfaced map is keyed by NodeId, the same key shape as nodeTypes.
        // Every expression node the verifier recorded a closure for is a node it
        // also inferred a type for (the closure is recorded inside the same
        // infer pass), so nodeClosures.keys is a subset of nodeTypes.keys — some
        // type-only positions (e.g. a FunctionType reached only in type
        // position) carry a type but no expression closure.
        val p = load("/corpus/17-builtin-compose-pure-and-effectful.json")
        assertTrue(
            p.verify.nodeClosures.keys.all { it in p.verify.nodeTypes.keys },
            "every node with a surfaced closure must also have an inferred type (same NodeId keying)",
        )
        assertTrue(p.verify.nodeClosures.isNotEmpty(), "an effectful program must surface some closures")
        // The root is always present in both maps.
        assertTrue(p.root in p.verify.nodeClosures, "the root node must have a surfaced closure")
        val surfaced = categoryNames(p.store, p.verify.rootClosure(p.root))
        assertEquals(setOf("Time.Now"), surfaced)
    }

    @Test
    fun `surfaced root closure is Handler-aware where the structural walk is not`() {
        // Corpus 36: a Handler intercepts Time.Now and supplies a constant. The
        // body reaches Time.Now, but the Handler subtracts it, so the verifier's
        // Handler-aware closure of the root is EMPTY. A structural walk — which
        // never subtracts an intercepted category — still reports Time.Now.
        val p = load("/corpus/36-handler-mock-time-now.json")

        val surfaced = categoryNames(p.store, p.verify.rootClosure(p.root))
        val walked = structuralWalkCategoryNames(p.store, p.root)

        assertEquals(
            emptySet<String>(), surfaced,
            "the Handler subtracts the intercepted Time.Now, so the surfaced root closure is empty",
        )
        assertEquals(
            setOf("Time.Now"), walked,
            "the Handler-unaware structural walk still reaches the Time.Now EffectCategory",
        )
        assertTrue(
            surfaced != walked,
            "the surfaced (Handler-aware) closure must be tighter than the structural walk here",
        )
    }

    @Test
    fun `surfaced root closure preserves the handler's own residual effect`() {
        // Corpus 39: the Handler intercepts Time.Now but its handle itself
        // performs Filesystem.Write. The closure-subtraction-plus-union rule
        // means the surfaced root closure is {Filesystem.Write} (Time.Now
        // removed, the handle's own effect retained) — the exact harm bound the
        // surrounding context must satisfy. The structural walk, blind to the
        // subtraction, reports both categories.
        val p = load("/corpus/39-handler-itself-performs-effect.json")

        val surfaced = categoryNames(p.store, p.verify.rootClosure(p.root))
        val walked = structuralWalkCategoryNames(p.store, p.root)

        assertEquals(
            setOf("Filesystem.Write"), surfaced,
            "Time.Now is subtracted by the Handler; the handle's own Filesystem.Write is retained",
        )
        assertTrue("Time.Now" in walked, "the structural walk still reaches the intercepted Time.Now")
        assertTrue("Filesystem.Write" in walked, "the structural walk reaches the handle's Filesystem.Write")
    }
}
