package org.strand.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Ingest-boundary hardening from the 2026-09-29 review (core/hashing
 * findings). Each test is the exploit document; the pipeline must reject
 * it with a structured [IngestError] instead of admitting it.
 */
class JsonIngestHardeningTest {

    private fun doc(nodes: String, root: String = "r") =
        """{ "version": 1, "root": "$root", "nodes": { $nodes } }"""

    // ----- H2: ill-formed UTF-16 -----

    @Test
    fun `unpaired high surrogate in a StringLit is rejected`() {
        val err = assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "StringLit", "value": "a\ud800b" } """))
        }
        assertTrue(err.message!!.contains("surrogate"), err.message)
    }

    @Test
    fun `unpaired low surrogate in a name field is rejected`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "EffectCategory", "categoryName": "\udc00" } """))
        }
    }

    @Test
    fun `unpaired surrogate in an author id key is rejected`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "UnitLit" }, "\ud800": { "type": "UnitLit" } """))
        }
    }

    // ----- H3: graph depth -----

    private fun letChain(n: Int): String {
        val sb = StringBuilder("""{"version":1,"root":"l0","nodes":{"one":{"type":"IntLit","value":1},""")
        for (i in 0 until n) {
            val body = if (i == n - 1) "v" else "l${i + 1}"
            sb.append(""""l$i":{"type":"Let","name":"x$i","value":"one","body":"$body"},""")
        }
        sb.append(""""v":{"type":"VarRef","binder":"l${n - 1}"}}}""")
        return sb.toString()
    }

    @Test
    fun `a Let chain deeper than maxGraphDepth is rejected as GraphDepth exhaustion`() {
        // 600 Lets + the IntLit leaf: longest chain l0 -> ... -> l599 -> one = 601 nodes.
        val err = assertThrows<IngestError.ResourceExhaustion> { JsonIngest.parse(letChain(600)) }
        assertEquals(ExhaustionKind.GraphDepth, err.kind)
        assertEquals(EvaluationLimits.DEFAULTS.maxGraphDepth.toLong(), err.limit)
    }

    @Test
    fun `a chain at the cap is admitted and the cap is configurable`() {
        // 511 Lets + leaf = depth 512 = the default cap exactly.
        JsonIngest.parse(letChain(511))
        val err = assertThrows<IngestError.ResourceExhaustion> {
            JsonIngest.parse(letChain(20), EvaluationLimits(maxGraphDepth = 20))
        }
        assertEquals(ExhaustionKind.GraphDepth, err.kind)
        assertEquals(21L, err.current)
    }

    @Test
    fun `graph depth is computed iteratively for chains near the node-count cap`() {
        // 90k-deep chain: a recursive depth computation would overflow the
        // JVM stack long before reaching the cap check.
        val err = assertThrows<IngestError.ResourceExhaustion> { JsonIngest.parse(letChain(90_000)) }
        assertEquals(ExhaustionKind.GraphDepth, err.kind)
        // PERMISSIVE lifts the cap; ingest itself stays iterative.
        JsonIngest.parse(letChain(90_000), EvaluationLimits.PERMISSIVE)
    }

    @Test
    fun `a well-formed surrogate pair is accepted`() {
        val result = JsonIngest.parse(doc(""" "r": { "type": "StringLit", "value": "😀" } """))
        val stored = result.rawStore.get(result.root) as StoredNode.Canonical
        assertEquals(Node.StringLit("😀"), stored.node)
    }
}
