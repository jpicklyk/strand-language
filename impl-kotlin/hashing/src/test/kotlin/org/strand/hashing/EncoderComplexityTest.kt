package org.strand.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.core.StoredNode

/**
 * Review M1 (2026-09-29): hashing was quadratic in nesting depth — every
 * binder copied the whole `List<List<NodeId>>` stack and every cache lookup
 * hashed it. The stack is now a persistent, interned linked list (O(1) key
 * hashing) and closed subtrees are cached under a context-free key.
 */
class EncoderComplexityTest {

    /** Lambda nest of [depth] single-parameter Lambdas; the innermost body is a VarRef. */
    private fun nestedLambdas(depth: Int, bodyRefersToOutermost: Boolean): Pair<NodeStore, NodeId> {
        val store = NodeStore()
        val int = store.add(Node.PrimitiveType(Primitive.Int))
        val params = List(depth) { i -> store.add(Node.ParameterDecl(name = "p$i", paramType = int)) }
        var body = store.add(Node.VarRef(binder = if (bodyRefersToOutermost) params.first() else params.last()))
        for (i in depth - 1 downTo 0) {
            body = store.add(Node.Lambda(parameters = listOf(params[i]), body = body))
        }
        return store to body
    }

    /** Run [block] on a thread with a large stack: the depth here exceeds a default 1 MB stack. */
    private fun <T> onBigStack(block: () -> T): T {
        var result: Result<T>? = null
        val t = Thread(null, { result = runCatching(block) }, "deep-hash", 1L shl 30)
        t.start()
        t.join()
        return result!!.getOrThrow()
    }

    @Test
    fun `a 2000-deep nested-lambda program hashes in well under a second`() {
        val (store, root) = nestedLambdas(2_000, bodyRefersToOutermost = true)
        val elapsedMs = onBigStack {
            val start = System.nanoTime()
            val hasher = Hasher(store)
            hasher.hashRoot(root)
            hasher.hashReachable(root)
            (System.nanoTime() - start) / 1_000_000
        }
        assertTrue(elapsedMs < 1_000, "2000-deep hash took ${elapsedMs}ms")
    }

    @Test
    fun `hashing scales linearly in nesting depth`() {
        // 20k levels: the old per-binder stack copy + full-stack key hash was
        // ~2e8 list operations here (many seconds); linear is milliseconds.
        val (store, root) = nestedLambdas(20_000, bodyRefersToOutermost = false)
        val elapsedMs = onBigStack {
            val start = System.nanoTime()
            val hasher = Hasher(store)
            hasher.hashRoot(root)
            hasher.hashReachable(root)
            (System.nanoTime() - start) / 1_000_000
        }
        assertTrue(elapsedMs < 2_000, "20000-deep hash took ${elapsedMs}ms")
    }

    @Test
    fun `a closed subtree shared across many contexts is encoded once`() {
        // Let_0(value = S, body = Let_1(value = S, body = ... VarRef)) — S (a
        // closed Lambda) sits under 0, 1, 2, ... Let frames. With a
        // context-keyed cache only, S was re-encoded once per context.
        val store = NodeStore()
        val int = store.add(Node.PrimitiveType(Primitive.Int))
        val p = store.add(Node.ParameterDecl(name = "p", paramType = int))
        val pRef = store.add(Node.VarRef(binder = p))
        val shared = store.add(Node.Lambda(parameters = listOf(p), body = pRef))
        val n = 300
        // Innermost Let's body refers back to that Let (its id is the next slot).
        val innermostId = NodeId(store.size + 1)
        var root = store.add(Node.VarRef(binder = innermostId))
        root = store.add(Node.Let(name = "x", value = shared, body = root))
        check(root == innermostId)
        repeat(n - 1) { i -> root = store.add(Node.Let(name = "x$i", value = shared, body = root)) }

        var sharedLookups = 0
        val counting: StoreLookup = { id ->
            if (id == shared) sharedLookups++
            StoredNode.Canonical(store.get(id))
        }
        val encoder = CanonicalEncoder(counting) { bytes -> java.security.MessageDigest.getInstance("SHA-256").digest(bytes).let { byteArrayOf(0x1e) + it } }
        encoder.hash(root)
        assertEquals(1, sharedLookups, "closed subtree should be encoded once, not once per context")
    }
}
