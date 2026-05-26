package org.strand.interpreter

import kotlinx.serialization.json.Json
import org.strand.core.NodeId
import org.strand.core.Primitive
import org.strand.verifier.TypeExpr

/**
 * Shared helpers for the per-provider LLM builtin tests. Centralizes
 * the Strand-side ProductV construction so each test focuses on
 * provider-specific behavior.
 */
internal object LlmTestSupport {

    val json = Json { ignoreUnknownKeys = true }

    /**
     * Synthetic schema NodeId used by [requestWithTool] when the test
     * doesn't drive the verifier explicitly. The N-044 ToolDef
     * dispatch path looks up `parameterSchemaId` in
     * [Builtins.verifierNodeTypes] to recover the SchemaType for
     * JSON Schema projection; tests install a minimal entry pointing
     * at this NodeId before invoking the builtin.
     */
    val syntheticSchemaId: NodeId = NodeId(-100)

    /**
     * Install the synthetic schema's SchemaType in
     * [Builtins.verifierNodeTypes] so the LLM.Generate tool-dispatch
     * path can project it to JSON Schema. Returns the prior map (if
     * any) for restoration in @AfterEach. Tests call this in @BeforeEach.
     */
    fun installSyntheticToolSchema(): Map<NodeId, TypeExpr>? {
        val prior = Builtins.verifierNodeTypes
        val syntheticValueType = TypeExpr.Product(
            origin = NodeId(-101),
            fields = emptyList(),  // empty product matches the {type:"object", properties:{}} schema
        )
        Builtins.verifierNodeTypes = mapOf(
            syntheticSchemaId to TypeExpr.SchemaType(
                schemaId = syntheticSchemaId,
                valueType = syntheticValueType,
                invariants = emptyList(),
            )
        )
        return prior
    }

    /**
     * Build a Strand `GenerateRequest` ProductV with a single user
     * message containing one text block. Optional fields are omitted
     * via Strand's `Some` / `None` convention (the request shape
     * uses Option<T> for system / maxTokens / temperature / etc.).
     */
    fun simpleRequest(model: String, userText: String): Value.ProductV {
        val userBlock = Value.SumV("Text", Value.StringV(userText))
        val blockList = Value.SumV("Cons", Value.ProductV(mapOf(
            "head" to userBlock,
            "tail" to Value.SumV("Nil", null),
        )))
        val userMsg = Value.SumV("User", Value.ProductV(mapOf("content" to blockList)))
        val messageList = Value.SumV("Cons", Value.ProductV(mapOf(
            "head" to userMsg,
            "tail" to Value.SumV("Nil", null),
        )))
        return Value.ProductV(mapOf(
            "model" to Value.StringV(model),
            "messages" to messageList,
            "tools" to Value.SumV("Nil", null),
        ))
    }

    /**
     * Build a request with one tool. The tool's parameterSchema is the
     * supplied JsonElement encoded as a Strand JsonValue; the
     * implementation is the supplied Strand callable Value.
     */
    fun requestWithTool(
        model: String,
        userText: String,
        toolName: String,
        toolDescription: String,
        toolImpl: Value,
    ): Value.ProductV {
        // Build a real [Value.ToolDefV] — the N-044 graph-node-backed
        // convention. The tool's parameterSchema is the synthetic
        // [syntheticSchemaId]; tests should call [installSyntheticToolSchema]
        // in @BeforeEach so the dispatch path can project the schema
        // to JSON Schema.
        val toolDef = Value.ToolDefV(
            self = NodeId(-200),  // synthetic ToolDef NodeId (negative so it doesn't collide with any real store entry)
            name = toolName,
            description = toolDescription,
            parameterSchemaId = syntheticSchemaId,
            implementation = toolImpl,
        )
        val toolsList = Value.SumV("Cons", Value.ProductV(mapOf(
            "head" to toolDef,
            "tail" to Value.SumV("Nil", null),
        )))
        val base = simpleRequest(model, userText)
        return Value.ProductV(base.fields.toMutableMap().apply {
            this["tools"] = toolsList
        })
    }

    /**
     * Extract the first block from a Strand `List<Block>` Cons/Nil
     * chain. Tests assert against this for the canonical "first
     * content block is X" check.
     */
    fun firstBlock(content: Value): Value.SumV {
        val cons = content as Value.SumV
        require(cons.case == "Cons") { "expected non-empty content list" }
        val payload = cons.payload as Value.ProductV
        return payload.fields.getValue("head") as Value.SumV
    }

    /** Build an EmbedRequest ProductV. */
    fun embedRequest(model: String, text: String, dimensions: Int? = null): Value.ProductV {
        val fields = mutableMapOf<String, Value>(
            "model" to Value.StringV(model),
            "text" to Value.StringV(text),
        )
        if (dimensions != null) {
            fields["dimensions"] = Value.SumV("Some", Value.IntV(dimensions.toLong()))
        }
        return Value.ProductV(fields)
    }
}

/**
 * Test-only HTTP client that records the request and returns either a
 * single fixed response (`canned`) or a queue of responses (`responseQueue`,
 * consulted when `canned` is null). Set `canned` for single-shot tests;
 * push responses to `responseQueue` for multi-call tests (tool-use loop).
 */
internal class RecordingHttpClient : LlmHttpClient {
    val calls = mutableListOf<Triple<String, List<Pair<String, String>>, ByteArray>>()
    var canned: LlmHttpClient.HttpResponse? = null
    val responseQueue: ArrayDeque<LlmHttpClient.HttpResponse> = ArrayDeque()

    override fun post(
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
    ): LlmHttpClient.HttpResponse {
        calls += Triple(url, headers, body)
        return canned
            ?: responseQueue.removeFirstOrNull()
            ?: error("no response configured (call ${calls.size})")
    }
}
