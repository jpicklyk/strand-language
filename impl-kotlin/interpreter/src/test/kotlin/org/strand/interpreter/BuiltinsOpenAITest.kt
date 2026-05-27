package org.strand.interpreter

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Q-037 Phase 1 — OpenAI Chat Completions + Embeddings roundtrip
 * with mock HTTP. No real network calls.
 */
class BuiltinsOpenAITest {

    private val savedClient = Builtins.llmHttpClient
    private val savedCredentials = Builtins.credentialProvider
    private lateinit var captured: RecordingHttpClient

    @BeforeEach
    fun setUp() {
        // Q-042: clear the scrubber registry; StaticCredentialProvider
        // re-registers on first resolve.
        CredentialScrubber.resetForTesting()
        captured = RecordingHttpClient()
        Builtins.llmHttpClient = captured
        Builtins.credentialProvider = StaticCredentialProvider(mapOf("openai" to "sk-openai-test"))
    }

    @AfterEach
    fun tearDown() {
        Builtins.llmHttpClient = savedClient
        Builtins.credentialProvider = savedCredentials
        CredentialScrubber.resetForTesting()
    }

    @Test
    fun `OpenAI_Chat_Completions plain generation roundtrip`() {
        captured.canned = LlmHttpClient.HttpResponse(
            200,
            """
            {
              "id": "chatcmpl-abc",
              "object": "chat.completion",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "Hi from GPT."},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 8, "completion_tokens": 3, "total_tokens": 11}
            }
            """.trimIndent().toByteArray(),
        )
        val req = LlmTestSupport.simpleRequest("gpt-5", "Hello.")
        val fn = Builtins.lookupHigherOrder("strand-builtin:OpenAI.Chat.Completions")!!
        val result = fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV }) as Value.ProductV

        assertEquals(1, captured.calls.size)
        val (url, headers, body) = captured.calls[0]
        assertEquals("https://api.openai.com/v1/chat/completions", url)
        assertTrue(headers.any { it.first == "Authorization" && it.second == "Bearer sk-openai-test" })
        val reqJson = LlmTestSupport.json.parseToJsonElement(String(body)).jsonObject
        assertEquals("gpt-5", reqJson["model"]?.jsonPrimitive?.content)
        val messages = reqJson["messages"]!!.jsonArray
        // One user message — system was omitted in the simpleRequest.
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)

        val first = LlmTestSupport.firstBlock(result.fields.getValue("content"))
        assertEquals("Text", first.case)
        assertEquals("Hi from GPT.", (first.payload as Value.StringV).v)
        val usage = result.fields.getValue("usage") as Value.ProductV
        assertEquals(8L, (usage.fields.getValue("inputTokens") as Value.IntV).v)
        assertEquals(3L, (usage.fields.getValue("outputTokens") as Value.IntV).v)
    }

    @Test
    fun `OpenAI tool_calls map to ToolUse blocks`() {
        // First call: model wants to use a tool. Second call: model replies with text.
        captured.responseQueue.add(LlmHttpClient.HttpResponse(
            200,
            """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "echo", "arguments": "{\"msg\": \"hi\"}"}
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5}
            }
            """.trimIndent().toByteArray(),
        ))
        captured.responseQueue.add(LlmHttpClient.HttpResponse(
            200,
            """{"choices": [{"message": {"role": "assistant", "content": "done"}, "finish_reason": "stop"}], "usage": {}}""".toByteArray(),
        ))

        // Tool implementation: a host-side ApplyFn that observes invocations.
        var toolCallCount = 0
        var lastToolInput: Value? = null
        val applyFn = Builtins.ApplyFn { _, args ->
            toolCallCount++
            lastToolInput = args[0]
            Value.StringV("tool-output")
        }
        // The tool implementation is a Closure-like Value placeholder;
        // the dispatch loop just hands it to the ApplyFn we install.
        val impl = Value.StringV("placeholder-impl")
        val req = LlmTestSupport.requestWithTool("gpt-5", "use the tool", "echo", "Echoes input.", impl)
        val fn = Builtins.lookupHigherOrder("strand-builtin:OpenAI.Chat.Completions")!!
        val result = fn.invoke(listOf(req), applyFn) as Value.ProductV

        assertEquals(2, captured.calls.size, "tool loop should issue two calls")
        assertEquals(1, toolCallCount, "tool implementation should run exactly once")
        // The tool input arrived as a Strand JsonValue SumV.
        assertNotNull(lastToolInput as? Value.SumV)
        val first = LlmTestSupport.firstBlock(result.fields.getValue("content"))
        assertEquals("Text", first.case)
        assertEquals("done", (first.payload as Value.StringV).v)
    }

    @Test
    fun `OpenAI_Embeddings_Create encodes floats as little-endian bytes`() {
        captured.canned = LlmHttpClient.HttpResponse(
            200,
            """
            {
              "object": "list",
              "data": [{"object": "embedding", "embedding": [1.0, 2.0, -0.5], "index": 0}],
              "model": "text-embedding-3-small"
            }
            """.trimIndent().toByteArray(),
        )
        val req = LlmTestSupport.embedRequest("text-embedding-3-small", "hello world")
        val fn = Builtins.lookupHigherOrder("strand-builtin:OpenAI.Embeddings.Create")!!
        val result = fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV }) as Value.BytesV

        // 3 floats × 4 bytes/float = 12 bytes.
        assertEquals(12, result.v.size)
        val buf = java.nio.ByteBuffer.wrap(result.v).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.0f, buf.getFloat())
        assertEquals(2.0f, buf.getFloat())
        assertEquals(-0.5f, buf.getFloat())
    }

    @Test
    fun `OpenAI missing credentials raises structured IoFailure`() {
        Builtins.credentialProvider = StaticCredentialProvider(emptyMap())
        val req = LlmTestSupport.simpleRequest("gpt-5", "Hi.")
        val fn = Builtins.lookupHigherOrder("strand-builtin:OpenAI.Chat.Completions")!!
        val ex = assertThrows<IoFailure> {
            fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV })
        }
        assertEquals("openai-credentials-missing", ex.kind)
    }

    @Test
    fun `responseSchema as N-045 wrapper projects through JsonSchemaProjection and reaches provider`() {
        // Install the synthetic schema entries — both the tool-input
        // and the response-side schema (a Product with an `answer: String`
        // field). The wrapper's parameterSchemaId looks up here.
        val savedNodeTypes = LlmTestSupport.installSyntheticSchemas()
        try {
            // Override the synthetic response schema with a non-empty
            // Product so the projected JSON Schema has visible
            // `properties`/`required` fields the test can assert on.
            val answerFld = org.strand.verifier.TypeExpr.Product.Field(
                name = "answer",
                type = org.strand.verifier.TypeExpr.Prim(org.strand.core.Primitive.String)
            )
            val responseValueType = org.strand.verifier.TypeExpr.Product(
                origin = org.strand.core.NodeId(-103),
                fields = listOf(answerFld),
            )
            val current = Builtins.verifierNodeTypes!!.toMutableMap()
            current[LlmTestSupport.syntheticResponseSchemaId] = org.strand.verifier.TypeExpr.SchemaType(
                schemaId = LlmTestSupport.syntheticResponseSchemaId,
                valueType = responseValueType,
                invariants = emptyList(),
            )
            Builtins.verifierNodeTypes = current

            captured.canned = LlmHttpClient.HttpResponse(
                200,
                """
                {
                  "choices": [{
                    "message": {"role": "assistant", "content": "{\"answer\": \"42\"}"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 5, "completion_tokens": 2}
                }
                """.trimIndent().toByteArray(),
            )

            val req = LlmTestSupport.requestWithResponseSchema("gpt-5", "What is 6 times 7?")
            val fn = Builtins.lookupHigherOrder("strand-builtin:OpenAI.Chat.Completions")!!
            fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV })

            // The recorded outbound request body should now carry an
            // OpenAI-format response_format clause whose schema field is
            // the projected JSON Schema (not a JsonValue tower).
            assertEquals(1, captured.calls.size)
            val (_, _, body) = captured.calls[0]
            val reqJson = LlmTestSupport.json.parseToJsonElement(String(body)).jsonObject
            val responseFormat = reqJson["response_format"]?.jsonObject
            assertNotNull(responseFormat, "OpenAI request body should carry response_format")
            assertEquals("json_schema", responseFormat!!["type"]?.jsonPrimitive?.content)
            val jsonSchema = responseFormat["json_schema"]?.jsonObject!!
            val schema = jsonSchema["schema"]?.jsonObject!!
            // Confirm the schema is the projected form (object with
            // `properties.answer` of type string), not a JsonValue tower.
            assertEquals("object", schema["type"]?.jsonPrimitive?.content)
            val properties = schema["properties"]?.jsonObject!!
            val answer = properties["answer"]?.jsonObject!!
            assertEquals("string", answer["type"]?.jsonPrimitive?.content)
            val required = schema["required"]?.jsonArray!!
            assertEquals(1, required.size)
            assertEquals("answer", required[0].jsonPrimitive.content)
        } finally {
            Builtins.verifierNodeTypes = savedNodeTypes
        }
    }

    @Test
    fun `responseSchema with missing verifierNodeTypes entry falls back to empty JSON Schema`() {
        // Defensive path: if the verifierNodeTypes singleton isn't
        // populated (e.g., tests that drive the builtin directly
        // without invoking the verifier), the dispatch path falls
        // back to an empty schema rather than crashing.
        val savedNodeTypes = Builtins.verifierNodeTypes
        Builtins.verifierNodeTypes = null
        try {
            captured.canned = LlmHttpClient.HttpResponse(
                200,
                """{"choices": [{"message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}], "usage": {}}""".toByteArray(),
            )
            val req = LlmTestSupport.requestWithResponseSchema("gpt-5", "Hi.")
            val fn = Builtins.lookupHigherOrder("strand-builtin:OpenAI.Chat.Completions")!!
            // Should not throw — defensive fallback returns {}.
            fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV })
            assertEquals(1, captured.calls.size)
            val (_, _, body) = captured.calls[0]
            val reqJson = LlmTestSupport.json.parseToJsonElement(String(body)).jsonObject
            val responseFormat = reqJson["response_format"]?.jsonObject
            assertNotNull(responseFormat, "response_format should still be emitted")
            // The fallback empty schema produces {}.
            val schema = responseFormat!!["json_schema"]?.jsonObject?.get("schema")?.jsonObject
            assertEquals(0, schema?.size ?: -1, "fallback schema should be empty object")
        } finally {
            Builtins.verifierNodeTypes = savedNodeTypes
        }
    }

    // -- Q-042 scenario 4: upstream 401 echoing the API key is scrubbed --

    @Test
    fun `Q-042 upstream 401 echoing the API key has the key scrubbed in IoFailure detail`() {
        // setUp registered `sk-openai-test` (14 chars) — above the scrubber's
        // 8-character minimum. A misconfigured proxy echoing the key in
        // the 401 body must surface a scrubbed IoFailure.
        captured.canned = LlmHttpClient.HttpResponse(
            401,
            """{"error":{"message":"Invalid API key sk-openai-test","type":"authentication_error"}}""".toByteArray(),
        )
        val req = LlmTestSupport.simpleRequest("gpt-5", "Hi.")
        val fn = Builtins.lookupHigherOrder("strand-builtin:OpenAI.Chat.Completions")!!
        val ex = assertThrows<IoFailure> {
            fn.invoke(listOf(req), Builtins.ApplyFn { _, _ -> Value.UnitV })
        }
        assertEquals("openai-http-status", ex.kind)
        assertFalse("sk-openai-test" in ex.detail,
            "API key leaked through scrubbed IoFailure detail: ${ex.detail}")
        assertTrue("[REDACTED:openai:api_key]" in ex.detail,
            "scrubbed placeholder should appear in detail: ${ex.detail}")
        // Structural diagnostics survive.
        assertTrue("authentication_error" in ex.detail)
        // Unscrubbed form retains the raw value.
        assertTrue("sk-openai-test" in ex.unscrubbedDetail)
    }
}
