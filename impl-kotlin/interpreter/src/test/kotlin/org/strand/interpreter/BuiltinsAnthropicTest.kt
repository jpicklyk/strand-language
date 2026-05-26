package org.strand.interpreter

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Q-037 Phase 1 — Anthropic Messages API roundtrip with mock HTTP.
 *
 * Tests use a [RecordingHttpClient] that captures the outgoing request
 * and returns a canned response — no real network call. Covers:
 *
 *  - request body shape (model, messages, system, max_tokens, tools);
 *  - response parsing (text + tool_use blocks);
 *  - missing credentials route to a structured IoFailure;
 *  - HTTP-level errors surface as IoFailure with the response body
 *    captured in the detail.
 */
class BuiltinsAnthropicTest {

    private val savedClient = Builtins.llmHttpClient
    private val savedCredentials = Builtins.credentialProvider
    private lateinit var captured: RecordingHttpClient

    @BeforeEach
    fun setUp() {
        captured = RecordingHttpClient()
        Builtins.llmHttpClient = captured
        Builtins.credentialProvider = StaticCredentialProvider(mapOf("anthropic" to "sk-test-key"))
    }

    @AfterEach
    fun tearDown() {
        Builtins.llmHttpClient = savedClient
        Builtins.credentialProvider = savedCredentials
    }

    @Test
    fun `Anthropic_Messages_Create plain generation roundtrip`() {
        captured.canned = LlmHttpClient.HttpResponse(
            200,
            """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-4-7",
              "content": [{"type": "text", "text": "Hello from Claude."}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 10, "output_tokens": 4}
            }
            """.trimIndent().toByteArray(),
        )

        val req = LlmTestSupport.simpleRequest("claude-opus-4-7", "Hi there.")
        val fn = Builtins.lookupHigherOrder("strand-builtin:Anthropic.Messages.Create")!!
        val result = fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV }) as Value.ProductV

        // Request body has the right structure.
        assertEquals(1, captured.calls.size)
        val (url, headers, body) = captured.calls[0]
        assertEquals("https://api.anthropic.com/v1/messages", url)
        assertTrue(headers.any { it.first == "x-api-key" && it.second == "sk-test-key" })
        val reqJson = LlmTestSupport.json.parseToJsonElement(String(body)).jsonObject
        assertEquals("claude-opus-4-7", reqJson["model"]?.jsonPrimitive?.content)
        val messages = reqJson["messages"]?.jsonArray
        assertNotNull(messages)
        assertEquals(1, messages!!.size)
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)

        // Result is a ProductV with the expected blocks.
        val content = result.fields.getValue("content")
        val first = LlmTestSupport.firstBlock(content)
        assertEquals("Text", first.case)
        assertEquals("Hello from Claude.", (first.payload as Value.StringV).v)
        assertEquals("EndTurn", (result.fields.getValue("stopReason") as Value.SumV).case)
        val usage = result.fields.getValue("usage") as Value.ProductV
        assertEquals(10L, (usage.fields.getValue("inputTokens") as Value.IntV).v)
        assertEquals(4L, (usage.fields.getValue("outputTokens") as Value.IntV).v)
    }

    @Test
    fun `Anthropic_Messages_Create tool_use response surfaces in content blocks`() {
        captured.canned = LlmHttpClient.HttpResponse(
            200,
            """
            {
              "content": [
                {"type": "tool_use", "id": "tu_1", "name": "lookup", "input": {"q": "weather"}}
              ],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 5, "output_tokens": 1}
            }
            """.trimIndent().toByteArray(),
        )
        // No tools provided — the loop terminates after one call because
        // the tool dispatch will fall through to "tool not found" and
        // immediately try another generate; we need the second call to
        // return EndTurn to stop the loop. Set a queue:
        captured.responseQueue.add(captured.canned!!)
        captured.responseQueue.add(LlmHttpClient.HttpResponse(
            200,
            """{"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn", "usage": {}}""".toByteArray(),
        ))
        captured.canned = null  // force queue use

        val req = LlmTestSupport.simpleRequest("claude-opus-4-7", "What's the weather?")
        val fn = Builtins.lookupHigherOrder("strand-builtin:Anthropic.Messages.Create")!!
        val result = fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV }) as Value.ProductV

        // Two calls total — first returned tool_use, second returned EndTurn.
        assertEquals(2, captured.calls.size)
        // The final blocks reflect the second call.
        val firstBlock = LlmTestSupport.firstBlock(result.fields.getValue("content"))
        assertEquals("Text", firstBlock.case)
        assertEquals("ok", (firstBlock.payload as Value.StringV).v)
        // Usage accumulates across calls.
        val usage = result.fields.getValue("usage") as Value.ProductV
        assertEquals(5L, (usage.fields.getValue("inputTokens") as Value.IntV).v)
        assertEquals(1L, (usage.fields.getValue("outputTokens") as Value.IntV).v)
    }

    @Test
    fun `missing API key raises a structured IoFailure`() {
        Builtins.credentialProvider = StaticCredentialProvider(emptyMap())
        val req = LlmTestSupport.simpleRequest("claude-opus-4-7", "Hi.")
        val fn = Builtins.lookupHigherOrder("strand-builtin:Anthropic.Messages.Create")!!
        val ex = assertThrows<IoFailure> {
            fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV })
        }
        assertEquals("anthropic-credentials-missing", ex.kind)
    }

    @Test
    fun `HTTP 500 surfaces as IoFailure with the response body in detail`() {
        captured.canned = LlmHttpClient.HttpResponse(500, "rate limited".toByteArray())
        val req = LlmTestSupport.simpleRequest("claude-opus-4-7", "Hi.")
        val fn = Builtins.lookupHigherOrder("strand-builtin:Anthropic.Messages.Create")!!
        val ex = assertThrows<IoFailure> {
            fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV })
        }
        assertEquals("anthropic-http-status", ex.kind)
        assertTrue(ex.detail.contains("rate limited"))
    }

    @Test
    fun `Anthropic_Embeddings_Create is not supported (Voyage recommended)`() {
        // Direct provider call to exercise the dedicated rejection path.
        val ex = assertThrows<IoFailure> {
            AnthropicProvider.embed(EmbedRequest(model = "voyage-2", text = "hello"))
        }
        assertEquals("anthropic-embed-not-supported", ex.kind)
        assertTrue(ex.detail.contains("Voyage"))
    }
}
