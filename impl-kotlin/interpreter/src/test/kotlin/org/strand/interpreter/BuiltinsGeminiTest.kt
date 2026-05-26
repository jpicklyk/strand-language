package org.strand.interpreter

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
 * Q-037 Phase 1 — Gemini generateContent + embedContent roundtrip
 * with mock HTTP. No real network calls.
 */
class BuiltinsGeminiTest {

    private val savedClient = Builtins.llmHttpClient
    private val savedCredentials = Builtins.credentialProvider
    private lateinit var captured: RecordingHttpClient

    @BeforeEach
    fun setUp() {
        captured = RecordingHttpClient()
        Builtins.llmHttpClient = captured
        Builtins.credentialProvider = StaticCredentialProvider(mapOf("gemini" to "AIza-test"))
    }

    @AfterEach
    fun tearDown() {
        Builtins.llmHttpClient = savedClient
        Builtins.credentialProvider = savedCredentials
    }

    @Test
    fun `Gemini_GenerateContent plain generation roundtrip`() {
        captured.canned = LlmHttpClient.HttpResponse(
            200,
            """
            {
              "candidates": [{
                "content": {
                  "parts": [{"text": "Hello from Gemini."}],
                  "role": "model"
                },
                "finishReason": "STOP"
              }],
              "usageMetadata": {"promptTokenCount": 9, "candidatesTokenCount": 4, "totalTokenCount": 13}
            }
            """.trimIndent().toByteArray(),
        )
        val req = LlmTestSupport.simpleRequest("gemini-pro-2.0", "Hi.")
        val fn = Builtins.lookupHigherOrder("strand-builtin:Gemini.GenerateContent")!!
        val result = fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV }) as Value.ProductV

        assertEquals(1, captured.calls.size)
        val (url, _, body) = captured.calls[0]
        // The URL embeds the model and the API key as a query parameter.
        assertTrue(url.contains("/models/gemini-pro-2.0:generateContent"))
        assertTrue(url.contains("key=AIza-test"))
        val reqJson = LlmTestSupport.json.parseToJsonElement(String(body)).jsonObject
        val contents = reqJson["contents"]!!.jsonArray
        assertEquals(1, contents.size)
        assertEquals("user", contents[0].jsonObject["role"]?.jsonPrimitive?.content)

        val first = LlmTestSupport.firstBlock(result.fields.getValue("content"))
        assertEquals("Text", first.case)
        assertEquals("Hello from Gemini.", (first.payload as Value.StringV).v)
        val usage = result.fields.getValue("usage") as Value.ProductV
        assertEquals(9L, (usage.fields.getValue("inputTokens") as Value.IntV).v)
        assertEquals(4L, (usage.fields.getValue("outputTokens") as Value.IntV).v)
    }

    @Test
    fun `Gemini functionCall maps to ToolUse and loop continues`() {
        captured.responseQueue.add(LlmHttpClient.HttpResponse(
            200,
            """
            {
              "candidates": [{
                "content": {
                  "parts": [{"functionCall": {"name": "echo", "args": {"msg": "ping"}}}],
                  "role": "model"
                },
                "finishReason": "STOP"
              }],
              "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 2}
            }
            """.trimIndent().toByteArray(),
        ))
        captured.responseQueue.add(LlmHttpClient.HttpResponse(
            200,
            """
            {
              "candidates": [{
                "content": {"parts": [{"text": "ack"}], "role": "model"},
                "finishReason": "STOP"
              }]
            }
            """.trimIndent().toByteArray(),
        ))

        var toolCalled = 0
        val applyFn = Builtins.ApplyFn { _, _ ->
            toolCalled++
            Value.StringV("done")
        }
        val req = LlmTestSupport.requestWithTool("gemini-pro-2.0", "use it", "echo", "Echo.", Value.StringV("impl"))
        val fn = Builtins.lookupHigherOrder("strand-builtin:Gemini.GenerateContent")!!
        val result = fn.invoke(listOf(req), applyFn) as Value.ProductV

        assertEquals(2, captured.calls.size)
        assertEquals(1, toolCalled)
        val first = LlmTestSupport.firstBlock(result.fields.getValue("content"))
        assertEquals("ack", (first.payload as Value.StringV).v)
    }

    @Test
    fun `Gemini_EmbedContent encodes floats as little-endian bytes`() {
        captured.canned = LlmHttpClient.HttpResponse(
            200,
            """
            {"embedding": {"values": [0.5, -0.25, 1.5]}}
            """.trimIndent().toByteArray(),
        )
        val req = LlmTestSupport.embedRequest("text-embedding-004", "hello")
        val fn = Builtins.lookupHigherOrder("strand-builtin:Gemini.EmbedContent")!!
        val result = fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV }) as Value.BytesV

        assertEquals(12, result.v.size)
        val buf = java.nio.ByteBuffer.wrap(result.v).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0.5f, buf.getFloat())
        assertEquals(-0.25f, buf.getFloat())
        assertEquals(1.5f, buf.getFloat())
    }

    @Test
    fun `Gemini missing credentials raises structured IoFailure`() {
        Builtins.credentialProvider = StaticCredentialProvider(emptyMap())
        val req = LlmTestSupport.simpleRequest("gemini-pro-2.0", "Hi.")
        val fn = Builtins.lookupHigherOrder("strand-builtin:Gemini.GenerateContent")!!
        val ex = assertThrows<IoFailure> {
            fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV })
        }
        assertEquals("gemini-credentials-missing", ex.kind)
    }
}
