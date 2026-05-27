package org.strand.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Q-040 ingest-time resource-exhaustion tests. The proposal § 7 specifies
 * the three hostile-graph admission scenarios (deep JSON, million-node
 * payload, multi-megabyte body) — each should reject before any node is
 * materialized, surfacing as [IngestError.ResourceExhaustion] with the
 * appropriate [ExhaustionKind].
 *
 * Plus a pass-through assertion: every default-limit ingest of a real
 * corpus program still succeeds.
 */
class JsonIngestLimitsTest {

    @Test
    fun `scenario 1 - hostile JSON depth raises ResourceExhaustion(JsonDepth)`() {
        // Build {"v":{"v":...}} with depth-1000 nesting wrapping a single
        // node payload at the deepest position. The depth pre-scan must
        // reject before kotlinx-serialization is called (it would crash
        // the JVM with StackOverflowError on its own recursive parser).
        val depth = 1000
        val sb = StringBuilder()
        sb.append("""{"version":1,"root":"x","nodes":{"x":""")
        // Tower of {"v": ...} wrappers. The verifier will reject the
        // resulting node graph, but we only care that the depth check
        // fires before the parser is invoked.
        repeat(depth) { sb.append("""{"v":""") }
        sb.append("""{"type":"IntLit","value":0}""")
        repeat(depth) { sb.append("}") }
        sb.append("""}}""")

        val text = sb.toString()
        val limits = EvaluationLimits(maxJsonDepth = 256)
        val err = assertThrows<IngestError.ResourceExhaustion> {
            JsonIngest.parse(text, limits)
        }
        assertEquals(ExhaustionKind.JsonDepth, err.kind)
    }

    @Test
    fun `scenario 2 - hostile node count raises ResourceExhaustion(NodeCount)`() {
        // Build a program with 1500 trivial nodes; cap at 1024.
        val count = 1500
        val sb = StringBuilder()
        sb.append("""{"version":1,"root":"n0","nodes":{""")
        repeat(count) { i ->
            if (i > 0) sb.append(",")
            sb.append("""${'"'}n$i${'"'}:{"type":"IntLit","value":$i}""")
        }
        sb.append("}}")

        val text = sb.toString()
        val limits = EvaluationLimits(maxNodeCount = 1024)
        val err = assertThrows<IngestError.ResourceExhaustion> {
            JsonIngest.parse(text, limits)
        }
        assertEquals(ExhaustionKind.NodeCount, err.kind)
        // Observed count should exceed the limit; report the actual.
        assert(err.current > err.limit)
        assertEquals(count.toLong(), err.current)
    }

    @Test
    fun `scenario 3 - hostile byte count raises ResourceExhaustion(IngestBytes)`() {
        // A 1KB payload, capped at 256 bytes.
        val sb = StringBuilder()
        sb.append("""{"version":1,"root":"x","nodes":{"x":{"type":"StringLit","value":"""")
        // Pad with 'a' chars to exceed the byte cap. Need text.length > 256.
        repeat(1024) { sb.append('a') }
        sb.append("""\"}}}""")
        val text = sb.toString()
        val limits = EvaluationLimits(maxIngestBytes = 256L)
        val err = assertThrows<IngestError.ResourceExhaustion> {
            JsonIngest.parse(text, limits)
        }
        assertEquals(ExhaustionKind.IngestBytes, err.kind)
        assertEquals(text.length.toLong(), err.current)
        assertEquals(256L, err.limit)
    }

    @Test
    fun `defaults pass-through - ordinary corpus program ingests cleanly`() {
        // A modest program: identity applied to a literal.
        val json = """
            {
              "version": 1,
              "root": "app",
              "nodes": {
                "app": { "type": "Application", "function": "id", "arguments": ["arg"] },
                "id":  { "type": "Lambda", "parameters": ["p"], "body": "pRef" },
                "p":   { "type": "ParameterDecl", "name": "p", "paramType": "Tp" },
                "Tp":  { "type": "TypeParameter", "name": "a" },
                "pRef":{ "type": "VarRef", "binder": "p" },
                "arg": { "type": "IntLit", "value": 1 }
              }
            }
        """.trimIndent()
        // No explicit limits → uses EvaluationLimits.DEFAULTS internally.
        val result = JsonIngest.parse(json)
        assertNotNull(result.rawStore)
        assertEquals(6, result.nameMap.size)
    }

    @Test
    fun `defaults pass-through - explicit DEFAULTS produces the same result`() {
        val json = """
            {"version":1,"root":"x","nodes":{"x":{"type":"IntLit","value":42}}}
        """.trimIndent()
        val a = JsonIngest.parse(json)
        val b = JsonIngest.parse(json, EvaluationLimits.DEFAULTS)
        assertEquals(a.nameMap.size, b.nameMap.size)
        assertEquals(a.root, b.root)
    }

    @Test
    fun `validateJsonDepth happy path - flat document passes`() {
        val text = """{"a":1, "b":2, "c":[1,2,3]}"""
        JsonIngest.validateJsonDepth(text, maxDepth = 16)
    }

    @Test
    fun `validateJsonDepth ignores braces inside strings`() {
        // The depth here is 1 (one outer object), the brace inside the
        // string must not increase the counter.
        val text = """{"key":"this is a brace { in a string"}"""
        JsonIngest.validateJsonDepth(text, maxDepth = 2)
    }

    @Test
    fun `validateJsonDepth rejects when depth exceeds cap`() {
        val text = """{"a":{"b":{"c":{"d":{"e":1}}}}}"""
        // Depth = 5. Cap at 3.
        val err = assertThrows<IngestError.ResourceExhaustion> {
            JsonIngest.validateJsonDepth(text, maxDepth = 3)
        }
        assertEquals(ExhaustionKind.JsonDepth, err.kind)
    }

    @Test
    fun `IngestError sealed class - Malformed still surfaces existing messages`() {
        val json = """{"version":1,"root":"missing","nodes":{"a":{"type":"IntLit","value":1}}}"""
        val err = assertThrows<IngestError.Malformed> { JsonIngest.parse(json) }
        assert(err.message!!.contains("missing")) { "expected 'missing' substring, got: ${err.message}" }
    }
}
