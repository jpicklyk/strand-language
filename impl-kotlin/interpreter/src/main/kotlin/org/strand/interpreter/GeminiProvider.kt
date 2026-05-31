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
 * Gemini (Google AI) generateContent + embedContent API binding.
 *
 * Endpoints:
 *  - `https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent`
 *    for [generate]
 *  - `https://generativelanguage.googleapis.com/v1beta/models/<model>:embedContent`
 *    for [embed]
 *
 * Authentication: requires the credential provider to return a non-null
 * key for the provider string `"gemini"` (falls back to `"google"`).
 * The key rides the request via the `key=` query string parameter
 * (Google's published authentication pattern). Without it, the calls
 * throw an [IoFailure] with kind `gemini-credentials-missing`.
 *
 * The wire format translates between Strand's unified [LlmMessage] /
 * [LlmBlock] shapes and Gemini's `contents: [{role, parts: [...]}]`
 * shape on request, and Gemini's `candidates[0].content.parts` (with
 * `text` and `functionCall` part variants) on response.
 *
 * Note Gemini uses `model` and `user` as roles; assistant responses
 * are tagged `model`. Tool definitions ride the `tools.functionDeclarations`
 * field; response `functionCall` parts map to [LlmBlock.ToolUse].
 */
object GeminiProvider {

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    fun generate(
        req: GenerateRequest,
        client: LlmHttpClient = DefaultLlmHttpClient,
        credentials: CredentialProvider = Builtins.credentialProvider,
    ): GenerateResult {
        val credential = credentials.apiKey("gemini")
            ?: throw IoFailure(
                "gemini-credentials-missing",
                "no API key configured for provider 'gemini' (env: GEMINI_API_KEY or GOOGLE_API_KEY)",
            )

        val body = buildBody(req)

        // Q-042: single auditable `.reveal()` call site for Gemini generate.
        // Gemini's auth pattern is query-string (`?key=...`), not header — the
        // revealed value flows into the URL builder below.
        val url = "$BASE_URL/${req.model}:generateContent?key=${credential.reveal()}"
        val headers = listOf("Content-Type" to "application/json")

        val response = try {
            client.post(url, headers, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            throw IoFailure("gemini-http", "generateContent: ${e.message}")
        }

        if (response.status !in 200..299) {
            throw IoFailure(
                "gemini-http-status",
                "status ${response.status}: ${String(response.body, Charsets.UTF_8).take(500)}",
            )
        }

        val (content, parsed) = LlmJson.parseGeminiResponse(response.body)
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
        val credential = credentials.apiKey("gemini")
            ?: throw IoFailure(
                "gemini-credentials-missing",
                "no API key configured for provider 'gemini' (env: GEMINI_API_KEY or GOOGLE_API_KEY)",
            )

        val body = buildJsonObject {
            put("content", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", JsonPrimitive(req.text)) })
                })
            })
            if (req.dimensions != null) put("outputDimensionality", JsonPrimitive(req.dimensions))
        }

        // Q-042: single auditable `.reveal()` call site for Gemini embed.
        // Gemini's auth pattern is query-string (`?key=...`); the revealed
        // value flows into the URL builder below.
        val url = "$BASE_URL/${req.model}:embedContent?key=${credential.reveal()}"
        val headers = listOf("Content-Type" to "application/json")

        val response = try {
            client.post(url, headers, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            throw IoFailure("gemini-http", "embedContent: ${e.message}")
        }

        if (response.status !in 200..299) {
            throw IoFailure(
                "gemini-http-status",
                "status ${response.status}: ${String(response.body, Charsets.UTF_8).take(500)}",
            )
        }

        val parsed = LlmJson.json.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject
        val embedding = parsed["embedding"]?.jsonObject?.get("values")?.jsonArray
            ?: throw IoFailure("gemini-embed", "response missing embedding.values array")
        val values = embedding.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }
        return floatsToBytesLE(values)
    }

    /**
     * Build the generateContent request body. Shared by [generate] and
     * [generateStreamOpen] — Gemini's streaming variant takes an
     * identical body and differs only in the endpoint
     * (`:streamGenerateContent?alt=sse` versus `:generateContent`).
     */
    private fun buildBody(req: GenerateRequest) = buildJsonObject {
        put("contents", buildJsonArray {
            for (m in req.messages) add(geminiMessage(m))
        })
        if (req.system != null) {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", JsonPrimitive(req.system)) })
                })
            })
        }
        if (req.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        for (t in req.tools) add(buildJsonObject {
                            put("name", JsonPrimitive(t.name))
                            put("description", JsonPrimitive(t.description))
                            put("parameters", t.parameterSchema)
                        })
                    })
                })
            })
        }
        val genConfig = buildJsonObject {
            if (req.maxTokens != null) put("maxOutputTokens", JsonPrimitive(req.maxTokens))
            if (req.temperature != null) put("temperature", JsonPrimitive(req.temperature))
            if (req.responseSchema != null) {
                put("responseMimeType", JsonPrimitive("application/json"))
                put("responseSchema", req.responseSchema)
            }
        }
        if (genConfig.isNotEmpty()) put("generationConfig", genConfig)
        if (req.providerExtras is JsonObject) {
            for ((k, v) in req.providerExtras) put(k, v)
        }
    }

    /**
     * Q-045: open a streaming generateContent call. Posts the same body
     * as [generate] to the `:streamGenerateContent?alt=sse` endpoint,
     * opens the SSE response, and returns the live
     * [LlmHttpClient.LlmStream] the `Gemini.GenerateContentStream`
     * builtin registers under [ResourceTable.KIND_LLM_STREAM]. Stateless;
     * chunks are raw bytes (SSE decoding deferred per proposal § 8).
     */
    fun generateStreamOpen(
        req: GenerateRequest,
        client: LlmHttpClient = DefaultLlmHttpClient,
        credentials: CredentialProvider = Builtins.credentialProvider,
    ): LlmHttpClient.LlmStream {
        val credential = credentials.apiKey("gemini")
            ?: throw IoFailure(
                "gemini-credentials-missing",
                "no API key configured for provider 'gemini' (env: GEMINI_API_KEY or GOOGLE_API_KEY)",
            )
        val body = buildBody(req)
        // Q-042: single auditable `.reveal()` call site for the Gemini
        // streaming open; the query-string auth pattern matches [generate].
        val url = "$BASE_URL/${req.model}:streamGenerateContent?alt=sse&key=${credential.reveal()}"
        val headers = listOf("Content-Type" to "application/json")
        return try {
            client.openStream(url, headers, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            throw IoFailure("gemini-http", "streamGenerateContent: ${e.message}")
        }
    }

    private fun geminiMessage(m: LlmMessage): JsonObject = when (m) {
        is LlmMessage.User -> buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("parts", buildJsonArray { for (b in m.content) add(geminiPart(b)) })
        }
        is LlmMessage.Assistant -> buildJsonObject {
            put("role", JsonPrimitive("model"))
            put("parts", buildJsonArray { for (b in m.content) add(geminiPart(b)) })
        }
        is LlmMessage.ToolResult -> buildJsonObject {
            // Gemini conveys tool results as `functionResponse` parts
            // inside user-role content.
            put("role", JsonPrimitive("user"))
            put("parts", buildJsonArray {
                add(buildJsonObject {
                    put("functionResponse", buildJsonObject {
                        put("name", JsonPrimitive(m.toolUseId))
                        put("response", buildJsonObject {
                            put("content", JsonPrimitive(String(m.content, Charsets.UTF_8)))
                        })
                    })
                })
            })
        }
    }

    private fun geminiPart(b: LlmBlock): JsonElement = when (b) {
        is LlmBlock.Text -> buildJsonObject { put("text", JsonPrimitive(b.text)) }
        is LlmBlock.ToolUse -> buildJsonObject {
            put("functionCall", buildJsonObject {
                put("name", JsonPrimitive(b.name))
                put("args", b.input)
            })
        }
        is LlmBlock.Image -> buildJsonObject {
            put("inlineData", buildJsonObject {
                put("mimeType", JsonPrimitive(b.mediaType))
                put("data", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(b.bytes)))
            })
        }
        is LlmBlock.Document -> buildJsonObject {
            put("inlineData", buildJsonObject {
                put("mimeType", JsonPrimitive(b.mediaType))
                put("data", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(b.bytes)))
            })
        }
    }
}
