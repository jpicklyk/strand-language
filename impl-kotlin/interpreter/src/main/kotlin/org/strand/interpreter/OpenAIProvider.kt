package org.strand.interpreter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI Chat Completions + Embeddings API binding.
 *
 * Endpoints:
 *  - `https://api.openai.com/v1/chat/completions` for [generate]
 *  - `https://api.openai.com/v1/embeddings` for [embed]
 *
 * Authentication: requires the credential provider to return a non-null
 * key for the provider string `"openai"`. Without it, the calls throw
 * an [IoFailure] with kind `openai-credentials-missing`.
 *
 * The wire format translates between Strand's unified [LlmMessage] /
 * [LlmBlock] shapes and OpenAI's `messages: [{role, content}, ...]`
 * shape on request, and OpenAI's `choices[0].message.content` plus
 * `choices[0].message.tool_calls` on response. Tool definitions ride
 * the `tools` field; the response's `tool_calls` map back to
 * [LlmBlock.ToolUse] entries the builtin's loop dispatches on.
 */
object OpenAIProvider {

    private const val CHAT_URL = "https://api.openai.com/v1/chat/completions"
    private const val EMBEDDINGS_URL = "https://api.openai.com/v1/embeddings"

    fun generate(
        req: GenerateRequest,
        client: LlmHttpClient = DefaultLlmHttpClient,
        credentials: CredentialProvider = Builtins.credentialProvider,
    ): GenerateResult {
        val credential = credentials.apiKey("openai")
            ?: throw IoFailure(
                "openai-credentials-missing",
                "no API key configured for provider 'openai' (env: OPENAI_API_KEY)",
            )

        val body = buildBody(req, stream = false)

        // Q-042: single auditable `.reveal()` call site for OpenAI generate.
        // The revealed value is consumed by the Bearer-token header below.
        val headers = listOf(
            "Authorization" to "Bearer ${credential.reveal()}",
            "Content-Type" to "application/json",
        )

        val response = try {
            client.post(CHAT_URL, headers, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            throw IoFailure("openai-http", "chat: ${e.message}")
        }

        if (response.status !in 200..299) {
            throw IoFailure(
                "openai-http-status",
                "status ${response.status}: ${String(response.body, Charsets.UTF_8).take(500)}",
            )
        }

        val (content, parsed) = LlmJson.parseOpenAiResponse(response.body)
        return GenerateResult(
            content = content,
            stopReason = parsed.stop,
            usage = parsed.usage,
            finalMessages = req.messages,
        )
    }

    fun embed(
        req: EmbedRequest,
        client: LlmHttpClient = DefaultLlmHttpClient,
        credentials: CredentialProvider = Builtins.credentialProvider,
    ): ByteArray {
        val credential = credentials.apiKey("openai")
            ?: throw IoFailure(
                "openai-credentials-missing",
                "no API key configured for provider 'openai' (env: OPENAI_API_KEY)",
            )

        val body = buildJsonObject {
            put("model", JsonPrimitive(req.model))
            put("input", JsonPrimitive(req.text))
            put("encoding_format", JsonPrimitive("float"))
            if (req.dimensions != null) put("dimensions", JsonPrimitive(req.dimensions))
        }

        // Q-042: single auditable `.reveal()` call site for OpenAI embed.
        // The revealed value is consumed by the Bearer-token header below.
        val headers = listOf(
            "Authorization" to "Bearer ${credential.reveal()}",
            "Content-Type" to "application/json",
        )

        val response = try {
            client.post(EMBEDDINGS_URL, headers, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            throw IoFailure("openai-http", "embeddings: ${e.message}")
        }

        if (response.status !in 200..299) {
            throw IoFailure(
                "openai-http-status",
                "status ${response.status}: ${String(response.body, Charsets.UTF_8).take(500)}",
            )
        }

        val parsed = LlmJson.json.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject
        val data = parsed["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IoFailure("openai-embed", "response missing data[0]")
        val embedding = data["embedding"]?.jsonArray
            ?: throw IoFailure("openai-embed", "response missing embedding array")
        val values = embedding.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }
        return floatsToBytesLE(values)
    }

    /**
     * Build the Chat Completions request body. Shared by [generate]
     * (`stream = false`) and [generateStreamOpen] (`stream = true`).
     * The `stream` flag switches the response to SSE `data:`-framed
     * chunks; `stream_options.include_usage` requests a trailing usage
     * frame so a Strand-side decoder can recover token accounting.
     */
    private fun buildBody(req: GenerateRequest, stream: Boolean) = buildJsonObject {
        put("model", JsonPrimitive(req.model))
        if (req.maxTokens != null) put("max_tokens", JsonPrimitive(req.maxTokens))
        if (req.temperature != null) put("temperature", JsonPrimitive(req.temperature))
        put("messages", buildJsonArray {
            // OpenAI takes system as a separate `system` role rather
            // than a top-level field.
            if (req.system != null) add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(req.system))
            })
            for (m in req.messages) add(openaiMessage(m))
        })
        if (req.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                for (t in req.tools) add(buildJsonObject {
                    put("type", JsonPrimitive("function"))
                    put("function", buildJsonObject {
                        put("name", JsonPrimitive(t.name))
                        put("description", JsonPrimitive(t.description))
                        put("parameters", t.parameterSchema)
                    })
                })
            })
        }
        if (req.responseSchema != null) {
            put("response_format", buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put("json_schema", buildJsonObject {
                    put("name", JsonPrimitive("response"))
                    put("strict", JsonPrimitive(true))
                    put("schema", req.responseSchema)
                })
            })
        }
        if (req.providerExtras is JsonObject) {
            for ((k, v) in req.providerExtras) put(k, v)
        }
        if (stream) {
            put("stream", JsonPrimitive(true))
            put("stream_options", buildJsonObject { put("include_usage", JsonPrimitive(true)) })
        }
    }

    /**
     * Q-045: open a streaming Chat Completion. Issues the same request as
     * [generate] with `stream: true`, opens the SSE response, and returns
     * the live [LlmHttpClient.LlmStream] the `OpenAI.Chat.CompletionsStream`
     * builtin registers under [ResourceTable.KIND_LLM_STREAM]. Stateless;
     * chunks are raw bytes (SSE decoding is deferred per proposal § 8).
     */
    fun generateStreamOpen(
        req: GenerateRequest,
        client: LlmHttpClient = DefaultLlmHttpClient,
        credentials: CredentialProvider = Builtins.credentialProvider,
    ): LlmHttpClient.LlmStream {
        val credential = credentials.apiKey("openai")
            ?: throw IoFailure(
                "openai-credentials-missing",
                "no API key configured for provider 'openai' (env: OPENAI_API_KEY)",
            )
        val body = buildBody(req, stream = true)
        // Q-042: single auditable `.reveal()` call site for the OpenAI
        // streaming open, symmetric with [generate]'s header builder.
        val headers = listOf(
            "Authorization" to "Bearer ${credential.reveal()}",
            "Content-Type" to "application/json",
            "Accept" to "text/event-stream",
        )
        return try {
            client.openStream(CHAT_URL, headers, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            throw IoFailure("openai-http", "chat-stream: ${e.message}")
        }
    }

    private fun openaiMessage(m: LlmMessage): JsonObject = when (m) {
        is LlmMessage.User -> buildJsonObject {
            put("role", JsonPrimitive("user"))
            // OpenAI accepts a plain string for text-only content, or an
            // array of typed parts for multi-modal. Use the array form
            // when there's anything non-textual; the string form
            // otherwise (smaller payload, cheaper for the common case).
            if (m.content.size == 1 && m.content[0] is LlmBlock.Text) {
                put("content", JsonPrimitive((m.content[0] as LlmBlock.Text).text))
            } else {
                put("content", buildJsonArray { for (b in m.content) add(openaiContentPart(b)) })
            }
        }
        is LlmMessage.Assistant -> buildJsonObject {
            put("role", JsonPrimitive("assistant"))
            val textBlocks = m.content.filterIsInstance<LlmBlock.Text>()
            val toolBlocks = m.content.filterIsInstance<LlmBlock.ToolUse>()
            if (textBlocks.isNotEmpty()) {
                put("content", JsonPrimitive(textBlocks.joinToString("") { it.text }))
            }
            if (toolBlocks.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    for (t in toolBlocks) add(buildJsonObject {
                        put("id", JsonPrimitive(t.id))
                        put("type", JsonPrimitive("function"))
                        put("function", buildJsonObject {
                            put("name", JsonPrimitive(t.name))
                            put("arguments", JsonPrimitive(t.input.toString()))
                        })
                    })
                })
            }
        }
        is LlmMessage.ToolResult -> buildJsonObject {
            put("role", JsonPrimitive("tool"))
            put("tool_call_id", JsonPrimitive(m.toolUseId))
            put("content", JsonPrimitive(String(m.content, Charsets.UTF_8)))
        }
    }

    private fun openaiContentPart(b: LlmBlock): JsonElement = when (b) {
        is LlmBlock.Text -> buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(b.text))
        }
        is LlmBlock.Image -> buildJsonObject {
            put("type", JsonPrimitive("image_url"))
            put("image_url", buildJsonObject {
                put("url", JsonPrimitive(
                    "data:${b.mediaType};base64,${java.util.Base64.getEncoder().encodeToString(b.bytes)}"
                ))
            })
        }
        is LlmBlock.Document -> buildJsonObject {
            // OpenAI's document handling routes through file uploads;
            // inline base64 is not a first-class option on chat
            // completions today. Encode as a text fallback so the model
            // at least sees the bytes' base64 representation, with the
            // mediaType prefix for context.
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(
                "[document ${b.mediaType}; base64]: " +
                    java.util.Base64.getEncoder().encodeToString(b.bytes)
            ))
        }
        is LlmBlock.ToolUse -> buildJsonObject {
            // tool_use inside a user-role message is unusual; encode as text.
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive("[tool_use ${b.name}]"))
        }
    }
}
