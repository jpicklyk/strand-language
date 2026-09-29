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

    // ----- M4: no silent scalar coercion -----

    @Test
    fun `a number where a string field is expected is rejected`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "EffectCategory", "categoryName": 7 } """))
        }
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "StringLit", "value": true } """))
        }
    }

    @Test
    fun `a quoted number where an Int is expected is rejected`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "IntLit", "value": "42" } """))
        }
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "FloatLit", "value": "1.5" } """))
        }
    }

    @Test
    fun `a quoted boolean where a Bool is expected is rejected`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "BoolLit", "value": "true" } """))
        }
    }

    @Test
    fun `a numeric reference is rejected even when an author id of that spelling exists`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "7": { "type": "IntLit", "value": 1 }, "r": { "type": "NodeRef", "target": 7 } """))
        }
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "7": { "type": "IntLit", "value": 1 }, "r": { "type": "Attempt", "body": 7 } """))
        }
    }

    @Test
    fun `quoted schema version, root, type and optional ints are rejected`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse("""{ "version": "1", "root": "r", "nodes": { "r": { "type": "UnitLit" } } }""")
        }
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "RecursiveSelf", "depth": "0" } """))
        }
    }

    @Test
    fun `correctly typed scalars are still accepted`() {
        JsonIngest.parse(doc(""" "r": { "type": "IntLit", "value": 42 } """))
        JsonIngest.parse(doc(""" "r": { "type": "FloatLit", "value": 1.5 } """))
        JsonIngest.parse(doc(""" "r": { "type": "BoolLit", "value": false } """))
    }

    // ----- verifier C1 (ingest half): duplicate names -----

    @Test
    fun `duplicate ProductType field names are rejected`() {
        val err = assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc("""
                "int": { "type": "PrimitiveType", "kind": "Int" },
                "str": { "type": "PrimitiveType", "kind": "String" },
                "f1": { "type": "ProductTypeField", "name": "x", "fieldType": "int" },
                "f2": { "type": "ProductTypeField", "name": "x", "fieldType": "str" },
                "r": { "type": "ProductType", "fields": ["f1", "f2"] }
            """))
        }
        assertTrue(err.message!!.contains("'x'"), err.message)
    }

    @Test
    fun `the same field node listed twice is a duplicate`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc("""
                "int": { "type": "PrimitiveType", "kind": "Int" },
                "f1": { "type": "ProductTypeField", "name": "x", "fieldType": "int" },
                "r": { "type": "ProductType", "fields": ["f1", "f1"] }
            """))
        }
    }

    @Test
    fun `duplicate SumType case names are rejected`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc("""
                "int": { "type": "PrimitiveType", "kind": "Int" },
                "c1": { "type": "SumTypeCase", "name": "Some", "caseType": "int" },
                "c2": { "type": "SumTypeCase", "name": "Some" },
                "r": { "type": "SumType", "cases": ["c1", "c2"] }
            """))
        }
    }

    @Test
    fun `duplicate ProductValue field names are rejected`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc("""
                "int": { "type": "PrimitiveType", "kind": "Int" },
                "f1": { "type": "ProductTypeField", "name": "x", "fieldType": "int" },
                "t": { "type": "ProductType", "fields": ["f1"] },
                "one": { "type": "IntLit", "value": 1 },
                "two": { "type": "IntLit", "value": 2 },
                "v1": { "type": "ProductFieldValue", "fieldName": "x", "value": "one" },
                "v2": { "type": "ProductFieldValue", "fieldName": "x", "value": "two" },
                "r": { "type": "ProductValue", "ofType": "t", "fields": ["v1", "v2"] }
            """))
        }
    }

    // ----- Low: bufferSize 0 -----

    @Test
    fun `EventStream bufferSize 0 is rejected - it collided with the unset encoding`() {
        fun stream(size: Int) = doc("""
            "int": { "type": "PrimitiveType", "kind": "Int" },
            "r": { "type": "EventStream", "eventType": "int", "streamKind": "external", "bufferSize": $size }
        """)
        assertThrows<IngestError.Malformed> { JsonIngest.parse(stream(0)) }
        JsonIngest.parse(stream(1))
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
