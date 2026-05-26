package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Q-037 Phase 1 — end-to-end tool-use loop covered against a mock
 * provider. Verifies:
 *  - provider receives a tool_use response on call 1;
 *  - the loop parses the input JSON into a Strand JsonValue;
 *  - the named tool implementation runs exactly once;
 *  - a ToolResult is appended to the conversation;
 *  - the second provider call returns EndTurn and the loop terminates;
 *  - the GenerateResult's `finalMessages` chain reflects the appended
 *    Assistant + ToolResult turns.
 *
 * Uses Anthropic's wire format for the canned responses; the loop
 * logic is provider-independent.
 */
class ToolDispatchLoopTest {

    private val savedClient = Builtins.llmHttpClient
    private val savedCredentials = Builtins.credentialProvider
    private lateinit var captured: RecordingHttpClient

    @BeforeEach
    fun setUp() {
        captured = RecordingHttpClient()
        Builtins.llmHttpClient = captured
        Builtins.credentialProvider = StaticCredentialProvider(mapOf("anthropic" to "sk-test"))
    }

    @AfterEach
    fun tearDown() {
        Builtins.llmHttpClient = savedClient
        Builtins.credentialProvider = savedCredentials
    }

    @Test
    fun `tool_use on call 1, EndTurn on call 2 — loop runs the tool exactly once`() {
        captured.responseQueue.add(LlmHttpClient.HttpResponse(
            200,
            """
            {
              "content": [{
                "type": "tool_use",
                "id": "tu_42",
                "name": "lookup",
                "input": {"query": "weather", "city": "NYC"}
              }],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 10, "output_tokens": 1}
            }
            """.trimIndent().toByteArray(),
        ))
        captured.responseQueue.add(LlmHttpClient.HttpResponse(
            200,
            """
            {
              "content": [{"type": "text", "text": "It is sunny in NYC."}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 30, "output_tokens": 8}
            }
            """.trimIndent().toByteArray(),
        ))

        var toolCallCount = 0
        var observedInput: Value? = null
        val applyFn = Builtins.ApplyFn { _, args ->
            toolCallCount++
            observedInput = args[0]
            // Return a string the loop serializes as the ToolResult content.
            Value.StringV("sunny, 72F")
        }

        val req = LlmTestSupport.requestWithTool(
            "claude-opus-4-7", "What's the weather in NYC?", "lookup",
            "Look up the weather.", Value.StringV("impl-placeholder"),
        )
        val fn = Builtins.lookupHigherOrder("strand-builtin:Anthropic.Messages.Create")!!
        val result = fn.invoke(listOf(req), applyFn) as Value.ProductV

        // Two provider calls (the loop ran request -> tool -> request).
        assertEquals(2, captured.calls.size)
        // Exactly one tool invocation.
        assertEquals(1, toolCallCount)
        // The observed input arrived as a Strand JsonValue SumV.
        val inputSum = observedInput as Value.SumV
        // Outermost: JsonObjectCons (the JSON object {query, city}).
        assertTrue(inputSum.case == "JsonObjectCons", "expected JsonObjectCons, got ${inputSum.case}")

        // Result content is the final assistant text block.
        val first = LlmTestSupport.firstBlock(result.fields.getValue("content"))
        assertEquals("Text", first.case)
        assertEquals("It is sunny in NYC.", (first.payload as Value.StringV).v)
        assertEquals("EndTurn", (result.fields.getValue("stopReason") as Value.SumV).case)

        // Usage is accumulated across both calls.
        val usage = result.fields.getValue("usage") as Value.ProductV
        assertEquals(40L, (usage.fields.getValue("inputTokens") as Value.IntV).v)
        assertEquals(9L, (usage.fields.getValue("outputTokens") as Value.IntV).v)

        // finalMessages chain reflects the loop: original user + Assistant(tool_use) + ToolResult.
        val finalMessages = result.fields.getValue("finalMessages")
        val msgs = collectMessages(finalMessages)
        // Original user message + appended Assistant + appended ToolResult.
        assertEquals(3, msgs.size, "expected user, assistant, tool_result, got ${msgs.size}")
        assertEquals("User", msgs[0].case)
        assertEquals("Assistant", msgs[1].case)
        assertEquals("ToolResult", msgs[2].case)
        // ToolResult.toolUseId matches the canned id.
        val toolResultProduct = msgs[2].payload as Value.ProductV
        assertEquals("tu_42", (toolResultProduct.fields.getValue("toolUseId") as Value.StringV).v)
        // ToolResult.content is the bytes "sunny, 72F".
        assertEquals("sunny, 72F", String((toolResultProduct.fields.getValue("content") as Value.BytesV).v))
    }

    @Test
    fun `toolLoopLimit halts the loop with StopReason ToolUseLimit`() {
        val originalLimit = Builtins.toolLoopLimit
        Builtins.toolLoopLimit = 3
        try {
            // Every response is a tool_use — the loop will hit the limit.
            for (i in 0..5) {
                captured.responseQueue.add(LlmHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "content": [{"type": "tool_use", "id": "tu_$i", "name": "loop", "input": {}}],
                      "stop_reason": "tool_use",
                      "usage": {}
                    }
                    """.trimIndent().toByteArray(),
                ))
            }
            val applyFn = Builtins.ApplyFn { _, _ -> Value.StringV("ok") }
            val req = LlmTestSupport.requestWithTool(
                "claude-opus-4-7", "loop", "loop", "loop", Value.StringV("impl"),
            )
            val fn = Builtins.lookupHigherOrder("strand-builtin:Anthropic.Messages.Create")!!
            val result = fn.invoke(listOf(req), applyFn) as Value.ProductV

            // The loop ran exactly toolLoopLimit (3) iterations.
            assertEquals(3, captured.calls.size)
            assertEquals("ToolUseLimit", (result.fields.getValue("stopReason") as Value.SumV).case)
        } finally {
            Builtins.toolLoopLimit = originalLimit
        }
    }

    private fun collectMessages(listValue: Value): List<Value.SumV> {
        val out = mutableListOf<Value.SumV>()
        var cur = listValue
        while (cur is Value.SumV && cur.case == "Cons") {
            val payload = cur.payload as Value.ProductV
            out += payload.fields.getValue("head") as Value.SumV
            cur = payload.fields.getValue("tail")
        }
        return out
    }
}
