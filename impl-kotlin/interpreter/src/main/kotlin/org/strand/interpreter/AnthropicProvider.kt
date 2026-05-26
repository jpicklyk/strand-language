package org.strand.interpreter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Anthropic Messages API binding. Single round trip: builds the wire-
 * format request, posts it, and parses the response into the unified
 * [GenerateResult]. The tool-use loop lives in the builtin's `Fn`
 * entry (see `Builtins.kt`) — this object is stateless request/response.
 *
 * Endpoint: `https://api.anthropic.com/v1/messages` (per Anthropic's
 * Messages API documentation).
 *
 * Authentication: requires the credential provider to return a non-null
 * key for the provider string `"anthropic"`. Without it, [generate]
 * throws an [IoFailure] with kind `anthropic-credentials-missing` —
 * routed through the interpreter's IoFailure handling to surface as a
 * structured InterpretError carrying the call-site NodeId.
 *
 * Embedding: Anthropic does not ship native embeddings; their
 * recommendation is the Voyage AI API. This object's [embed] entry
 * throws `IoFailure("anthropic-embed-not-supported", ...)` with
 * actionable text. A future slice can wire Voyage's `api.voyageai.com`
 * endpoint into a `VoyageProvider` reachable via `Voyage.Embeddings.Create`
 * (or via this object if Anthropic's recommendation stabilizes).
 */
object AnthropicProvider {

    private const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"

    fun generate(
        req: GenerateRequest,
        client: LlmHttpClient = DefaultLlmHttpClient,
        credentials: CredentialProvider = Builtins.credentialProvider,
    ): GenerateResult {
        val key = credentials.apiKey("anthropic")
            ?: throw IoFailure(
                "anthropic-credentials-missing",
                "no API key configured for provider 'anthropic' (env: ANTHROPIC_API_KEY)",
            )

        // Build the request body. Anthropic's Messages API expects
        // `messages` plus optional `system`/`tools`/`max_tokens`/etc.
        val body = buildJsonObject {
            put("model", JsonPrimitive(req.model))
            put("max_tokens", JsonPrimitive(req.maxTokens ?: 1024))
            if (req.system != null) put("system", JsonPrimitive(req.system))
            put("messages", buildJsonArray {
                for (m in req.messages) add(LlmJson.anthropicMessage(m))
            })
            if (req.temperature != null) put("temperature", JsonPrimitive(req.temperature))
            if (req.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (t in req.tools) add(buildJsonObject {
                        put("name", JsonPrimitive(t.name))
                        put("description", JsonPrimitive(t.description))
                        put("input_schema", t.parameterSchema)
                    })
                })
            }
            // responseSchema is Anthropic's "tool-as-response-shape"
            // pattern; for the first slice we pass it through as
            // `providerExtras` if the agent supplies it that way.
            if (req.providerExtras != null) {
                // Merge providerExtras into the top-level object.
                val extras = req.providerExtras
                if (extras is kotlinx.serialization.json.JsonObject) {
                    for ((k, v) in extras) put(k, v)
                }
            }
        }

        val headers = listOf(
            "x-api-key" to key,
            "anthropic-version" to API_VERSION,
            "content-type" to "application/json",
        )

        val response = try {
            client.post(MESSAGES_URL, headers, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            throw IoFailure("anthropic-http", "messages: ${e.message}")
        }

        if (response.status !in 200..299) {
            throw IoFailure(
                "anthropic-http-status",
                "status ${response.status}: ${String(response.body, Charsets.UTF_8).take(500)}",
            )
        }

        val (content, parsed) = LlmJson.parseAnthropicResponse(response.body)
        return GenerateResult(
            content = content,
            stopReason = parsed.stop,
            usage = parsed.usage,
            finalMessages = req.messages,  // builtin layer rewrites this when tool loop runs
        )
    }

    /**
     * Anthropic.Embeddings.Create is not natively supported by
     * Anthropic; their published guidance is to use Voyage AI's
     * embedding API. The first slice surfaces this clearly rather than
     * silently no-op'ing. A follow-up can wire Voyage via this entry
     * (Q-037 § 9 Phase 1 deferred note).
     */
    fun embed(@Suppress("UNUSED_PARAMETER") req: EmbedRequest): ByteArray {
        throw IoFailure(
            "anthropic-embed-not-supported",
            "Anthropic does not ship native embeddings; use Voyage AI directly " +
                "(api.voyageai.com) via a future Voyage.Embeddings.Create binding " +
                "or by hand-rolling Http.Request against the Voyage endpoint.",
        )
    }
}
