package org.strand.interpreter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared data shapes and helpers for the per-provider LLM ForeignNodes
 * (Q-037 Phase 1). The request/response shapes are unified across
 * providers — provider identity is encoded by which ForeignNode is
 * invoked, not by a field on the request (proposal § 3.3).
 *
 * The provider-specific objects ([AnthropicProvider], [OpenAIProvider],
 * [GeminiProvider]) translate to and from each provider's wire format
 * inside `generate(req)` / `embed(req)`. The tool-use loop lives in the
 * builtin entry (not the provider object) per the proposal call:
 * provider objects are stateless request/response.
 */

// ----------------------------------------------------------------------
// Unified message / block model (proposal § 3.3)
// ----------------------------------------------------------------------

/**
 * One conversation message. The unified shape mirrors Anthropic's
 * Messages API content blocks and maps trivially onto OpenAI / Gemini
 * equivalents at each provider library boundary.
 */
sealed class LlmMessage {
    data class User(val content: List<LlmBlock>) : LlmMessage()
    data class Assistant(val content: List<LlmBlock>) : LlmMessage()
    data class ToolResult(val toolUseId: String, val content: ByteArray) : LlmMessage() {
        // ByteArray equality treats arrays by content for sane test
        // assertions (otherwise default identity equality breaks
        // round-trip tests).
        override fun equals(other: Any?): Boolean =
            other is ToolResult && toolUseId == other.toolUseId && content.contentEquals(other.content)
        override fun hashCode(): Int = toolUseId.hashCode() * 31 + content.contentHashCode()
    }
}

/** One content block within a message. */
sealed class LlmBlock {
    data class Text(val text: String) : LlmBlock()
    data class ToolUse(val id: String, val name: String, val input: JsonElement) : LlmBlock()
    data class Image(val bytes: ByteArray, val mediaType: String) : LlmBlock() {
        override fun equals(other: Any?): Boolean =
            other is Image && mediaType == other.mediaType && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = mediaType.hashCode() * 31 + bytes.contentHashCode()
    }
    data class Document(val bytes: ByteArray, val mediaType: String) : LlmBlock() {
        override fun equals(other: Any?): Boolean =
            other is Document && mediaType == other.mediaType && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = mediaType.hashCode() * 31 + bytes.contentHashCode()
    }
}

/** Tool definition supplied to the model. The implementation Value is
 * a callable (Closure / FixpointFn / ForeignFn) — the builtin's
 * tool-dispatch loop invokes it via `Interpreter.applyValueToArgs`. */
data class LlmToolDef(
    val name: String,
    val description: String,
    val parameterSchema: JsonElement,
    val implementation: Value,
)

/** Why the model stopped generating. */
enum class StopReason { EndTurn, MaxTokens, StopSequence, ToolUseLimit }

/** Token-usage accounting returned alongside generation. */
data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
)

/**
 * Unified generate request. Provider identity is encoded by which
 * ForeignNode is invoked, not by a field on the request. The shape is
 * structurally identical to the Anthropic Messages API content; the
 * other provider libraries translate on the wire.
 */
data class GenerateRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val system: String? = null,
    val maxTokens: Int? = null,
    val tools: List<LlmToolDef> = emptyList(),
    val responseSchema: JsonElement? = null,
    val temperature: Double? = null,
    val providerExtras: JsonElement? = null,
)

/** Unified generate result. */
data class GenerateResult(
    val content: List<LlmBlock>,
    val stopReason: StopReason,
    val usage: TokenUsage,
    val finalMessages: List<LlmMessage>,
)

/** Embed request. Dimensions is provider/model dependent. */
data class EmbedRequest(
    val model: String,
    val text: String,
    val dimensions: Int? = null,
)

// ----------------------------------------------------------------------
// HTTP client injection seam
// ----------------------------------------------------------------------

/**
 * HTTP transport interface so tests can mock without real network
 * calls. The default implementation uses [java.net.HttpURLConnection]
 * — same surface as `Http.Request` in [Builtins] — keeping the runtime
 * dependency footprint minimal.
 *
 * `body` may be empty for GET-style requests. `headers` lists each
 * header as (name, value); duplicate names are permitted.
 */
interface LlmHttpClient {
    fun post(
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
    ): HttpResponse

    data class HttpResponse(val status: Int, val body: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is HttpResponse && status == other.status && body.contentEquals(other.body)
        override fun hashCode(): Int = status * 31 + body.contentHashCode()
    }
}

/** Default JVM HttpURLConnection-backed transport. */
object DefaultLlmHttpClient : LlmHttpClient {
    override fun post(
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
    ): LlmHttpClient.HttpResponse {
        val uri = java.net.URI(url)
        val conn = uri.toURL().openConnection() as java.net.HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doInput = true
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val respBody = try {
                (if (status in 200..299) conn.inputStream else conn.errorStream)?.readBytes()
                    ?: ByteArray(0)
            } catch (_: java.io.IOException) {
                ByteArray(0)
            }
            LlmHttpClient.HttpResponse(status, respBody)
        } finally {
            conn.disconnect()
        }
    }
}

// ----------------------------------------------------------------------
// JSON helpers shared across providers
// ----------------------------------------------------------------------

internal object LlmJson {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun blockToText(b: LlmBlock): String? = (b as? LlmBlock.Text)?.text

    /** Encode one message as Anthropic Messages API JSON. */
    fun anthropicMessage(m: LlmMessage): JsonObject = when (m) {
        is LlmMessage.User -> buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", buildJsonArray { for (b in m.content) add(anthropicBlock(b)) })
        }
        is LlmMessage.Assistant -> buildJsonObject {
            put("role", JsonPrimitive("assistant"))
            put("content", buildJsonArray { for (b in m.content) add(anthropicBlock(b)) })
        }
        is LlmMessage.ToolResult -> buildJsonObject {
            // Anthropic places tool_result inside a user-role content
            // block array. The wire format is symmetric.
            put("role", JsonPrimitive("user"))
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("tool_result"))
                    put("tool_use_id", JsonPrimitive(m.toolUseId))
                    put("content", JsonPrimitive(String(m.content, Charsets.UTF_8)))
                })
            })
        }
    }

    private fun anthropicBlock(b: LlmBlock): JsonElement = when (b) {
        is LlmBlock.Text -> buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(b.text))
        }
        is LlmBlock.ToolUse -> buildJsonObject {
            put("type", JsonPrimitive("tool_use"))
            put("id", JsonPrimitive(b.id))
            put("name", JsonPrimitive(b.name))
            put("input", b.input)
        }
        is LlmBlock.Image -> buildJsonObject {
            put("type", JsonPrimitive("image"))
            put("source", buildJsonObject {
                put("type", JsonPrimitive("base64"))
                put("media_type", JsonPrimitive(b.mediaType))
                put("data", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(b.bytes)))
            })
        }
        is LlmBlock.Document -> buildJsonObject {
            put("type", JsonPrimitive("document"))
            put("source", buildJsonObject {
                put("type", JsonPrimitive("base64"))
                put("media_type", JsonPrimitive(b.mediaType))
                put("data", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(b.bytes)))
            })
        }
    }

    /** Parse an Anthropic Messages API response into unified shape. */
    fun parseAnthropicResponse(body: ByteArray): Pair<List<LlmBlock>, ParsedStopAndUsage> {
        val root = json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        val content = root["content"]?.jsonArray?.map { el ->
            val obj = el.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> LlmBlock.Text(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
                "tool_use" -> LlmBlock.ToolUse(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    input = obj["input"] ?: JsonObject(emptyMap()),
                )
                else -> LlmBlock.Text(el.toString())
            }
        } ?: emptyList()
        val stopReason = when (root["stop_reason"]?.jsonPrimitive?.contentOrNull) {
            "tool_use" -> StopReason.EndTurn  // tool_use means model wants a tool; the loop continues
            "end_turn" -> StopReason.EndTurn
            "max_tokens" -> StopReason.MaxTokens
            "stop_sequence" -> StopReason.StopSequence
            else -> StopReason.EndTurn
        }
        val usageObj = root["usage"]?.jsonObject
        val usage = TokenUsage(
            inputTokens = usageObj?.get("input_tokens")?.jsonPrimitive?.intOrNull?.toLong() ?: 0,
            outputTokens = usageObj?.get("output_tokens")?.jsonPrimitive?.intOrNull?.toLong() ?: 0,
            cacheReadTokens = usageObj?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull?.toLong() ?: 0,
            cacheWriteTokens = usageObj?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull?.toLong() ?: 0,
        )
        // We need to know if this is a tool_use stop to drive the loop.
        // Convey via "wantsTool" in the parsed shape.
        val wantsTool = root["stop_reason"]?.jsonPrimitive?.contentOrNull == "tool_use"
        return content to ParsedStopAndUsage(stopReason, usage, wantsTool)
    }

    /** Parse an OpenAI Chat Completions API response into unified shape. */
    fun parseOpenAiResponse(body: ByteArray): Pair<List<LlmBlock>, ParsedStopAndUsage> {
        val root = json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        val firstChoice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val message = firstChoice?.get("message")?.jsonObject
        val blocks = mutableListOf<LlmBlock>()
        val text = message?.get("content")?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }
        if (!text.isNullOrEmpty()) blocks += LlmBlock.Text(text)
        val toolCalls = message?.get("tool_calls")?.jsonArray
        var wantsTool = false
        if (toolCalls != null) {
            for (call in toolCalls) {
                val obj = call.jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "function") {
                    val fn = obj["function"]?.jsonObject
                    val name = fn?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                    val argsStr = fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"
                    val argsJson = try { json.parseToJsonElement(argsStr) } catch (_: Exception) { JsonObject(emptyMap()) }
                    blocks += LlmBlock.ToolUse(
                        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = name,
                        input = argsJson,
                    )
                    wantsTool = true
                }
            }
        }
        val finishReason = firstChoice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
        val stopReason = when (finishReason) {
            "tool_calls", "stop" -> StopReason.EndTurn
            "length" -> StopReason.MaxTokens
            "function_call" -> StopReason.EndTurn
            else -> StopReason.EndTurn
        }
        val usageObj = root["usage"]?.jsonObject
        val usage = TokenUsage(
            inputTokens = usageObj?.get("prompt_tokens")?.jsonPrimitive?.intOrNull?.toLong() ?: 0,
            outputTokens = usageObj?.get("completion_tokens")?.jsonPrimitive?.intOrNull?.toLong() ?: 0,
        )
        return blocks to ParsedStopAndUsage(stopReason, usage, wantsTool)
    }

    /** Parse a Gemini generateContent response into unified shape. */
    fun parseGeminiResponse(body: ByteArray): Pair<List<LlmBlock>, ParsedStopAndUsage> {
        val root = json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        val candidates = root["candidates"]?.jsonArray ?: return emptyList<LlmBlock>() to ParsedStopAndUsage(StopReason.EndTurn, TokenUsage(), false)
        val firstCandidate = candidates.firstOrNull()?.jsonObject
        val content = firstCandidate?.get("content")?.jsonObject
        val parts = content?.get("parts")?.jsonArray
        val blocks = mutableListOf<LlmBlock>()
        var wantsTool = false
        if (parts != null) {
            for (part in parts) {
                val obj = part.jsonObject
                val text = obj["text"]?.jsonPrimitive?.contentOrNull
                if (text != null) {
                    blocks += LlmBlock.Text(text)
                    continue
                }
                val fnCall = obj["functionCall"]?.jsonObject
                if (fnCall != null) {
                    blocks += LlmBlock.ToolUse(
                        // Gemini doesn't carry per-call ids; synthesize.
                        id = "gemini-tool-${blocks.size}",
                        name = fnCall["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        input = fnCall["args"] ?: JsonObject(emptyMap()),
                    )
                    wantsTool = true
                }
            }
        }
        val finishReason = firstCandidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
        val stopReason = when (finishReason) {
            "MAX_TOKENS" -> StopReason.MaxTokens
            "STOP" -> StopReason.EndTurn
            else -> StopReason.EndTurn
        }
        val usageObj = root["usageMetadata"]?.jsonObject
        val usage = TokenUsage(
            inputTokens = usageObj?.get("promptTokenCount")?.jsonPrimitive?.intOrNull?.toLong() ?: 0,
            outputTokens = usageObj?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull?.toLong() ?: 0,
        )
        return blocks to ParsedStopAndUsage(stopReason, usage, wantsTool)
    }

    data class ParsedStopAndUsage(val stop: StopReason, val usage: TokenUsage, val wantsTool: Boolean)
}

/**
 * Encode a vector of doubles as IEEE 754 float32 little-endian bytes —
 * the wire format Q-037 § 3.9 documents for embedding outputs.
 */
internal fun floatsToBytesLE(values: List<Double>): ByteArray {
    val buf = java.nio.ByteBuffer.allocate(values.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (v in values) buf.putFloat(v.toFloat())
    return buf.array()
}
