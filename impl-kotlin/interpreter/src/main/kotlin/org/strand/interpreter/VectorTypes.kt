package org.strand.interpreter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Cross-provider shapes for vector-store builtins (Q-038 Phase 1).
 *
 * The Strand surface marshals these in/out of ProductV / SumV /
 * BytesV / StringV / ListV values. The provider objects
 * (PineconeProvider, ChromaProvider) consume the Kotlin-side
 * representation defined here and translate to/from the provider's
 * wire format.
 *
 * Metric grain decision (per proposal § 3.4.1): per-collection at
 * Open time for both Pinecone and Chroma. The optional `metric`
 * field on [QueryRequest] is rejected (with an [IoFailure]) by
 * both providers in this phase; pgvector's per-query metric lands
 * in Phase 2.
 *
 * Filter language decision (per proposal § 3.4.3): loose
 * [JsonElement] for the initial slice. Pinecone receives the
 * filter as a structured boolean expression over metadata; Chroma
 * receives a where-filter dict; both pass through unchanged from
 * the call site's JsonValue argument.
 *
 * Idempotency decision (per proposal § 3.4.7): upsert semantics
 * across the board — insert with an existing id replaces.
 */

/** Metric choices: cosine, dot product, euclidean, manhattan. */
enum class VectorMetric { Cosine, DotProduct, Euclidean, Manhattan }

/** One vector element being upserted into a store. */
data class UpsertItem(
    val id: String,
    /** Vector as IEEE 754 float32 little-endian bytes; length = 4 * dimensions. */
    val vector: ByteArray,
    /** Caller-supplied metadata; provider-shape-dependent at the wire level. */
    val metadata: JsonElement,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UpsertItem) return false
        return id == other.id &&
            vector.contentEquals(other.vector) &&
            metadata == other.metadata
    }
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}

/** Top-level query shape; provider validates per its capabilities. */
data class QueryRequest(
    /** Query vector as IEEE 754 float32 little-endian bytes. */
    val vector: ByteArray,
    val k: Int,
    /** Optional structured filter; pass-through to the provider. */
    val filter: JsonElement?,
    val includeVector: Boolean,
    val includeMetadata: Boolean,
    /**
     * Optional per-query metric. Pinecone and Chroma set metric at
     * collection-open time; both providers reject a non-null value
     * here with an [IoFailure] of kind `"vector-metric-fixed"`.
     */
    val metric: VectorMetric?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueryRequest) return false
        return vector.contentEquals(other.vector) &&
            k == other.k &&
            filter == other.filter &&
            includeVector == other.includeVector &&
            includeMetadata == other.includeMetadata &&
            metric == other.metric
    }
    override fun hashCode(): Int {
        var result = vector.contentHashCode()
        result = 31 * result + k
        result = 31 * result + (filter?.hashCode() ?: 0)
        result = 31 * result + includeVector.hashCode()
        result = 31 * result + includeMetadata.hashCode()
        result = 31 * result + (metric?.hashCode() ?: 0)
        return result
    }
}

/** One hit returned by a query (or a fetch by id). */
data class QueryHit(
    val id: String,
    /** Provider-supplied similarity score; higher = closer for cosine/dot, lower = closer for euclidean. */
    val score: Double,
    val metadata: JsonElement,
    /** Vector bytes; null when the request did not opt in via includeVector. */
    val vector: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueryHit) return false
        return id == other.id &&
            score == other.score &&
            metadata == other.metadata &&
            (vector?.contentEquals(other.vector ?: ByteArray(0)) ?: (other.vector == null))
    }
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + (vector?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Pinecone-specific open config: index name + environment + metric +
 * dimensions. ANN parameters are hidden by Pinecone, so there is no
 * `indexType` field. Per the proposal § 3.4.2, managed services do
 * not expose ANN config.
 */
data class PineconeIndexConfig(
    val indexName: String,
    /** Pinecone control-plane environment (e.g. "us-east-1-aws"). Used to compose the index host URL. */
    val environment: String,
    val metric: VectorMetric,
    val dimensions: Int,
    /**
     * Optional override for the index host base URL. When null, the
     * provider computes `https://<indexName>-<environment>.svc.pinecone.io`
     * (matching Pinecone's serverless URL shape). Tests inject a
     * stub URL so they can match against a known wire endpoint.
     */
    val host: String? = null,
)

/**
 * Chroma-specific open config: collection name + server endpoint +
 * metric. Embedded mode (in-process) is out of scope for this
 * initial slice; the config requires a server URL.
 */
data class ChromaCollectionConfig(
    val collectionName: String,
    /** Base URL of the Chroma server (e.g. "http://localhost:8000"). */
    val serverUrl: String,
    val metric: VectorMetric,
    /** Cached after a successful Open; the provider remembers the collection's UUID for subsequent calls. */
    val collectionId: String? = null,
)

/**
 * Internal Pinecone handle held by the [ResourceTable]. Pinecone's
 * REST endpoints accept the index host plus an API key; the handle
 * caches both so subsequent operations don't have to resolve them.
 *
 * Q-042: the credential field is the opaque [Credential] wrapper, not a
 * raw String. The single `.reveal()` call site lives in
 * [PineconeProvider.standardHeaders] (the header builder).
 */
internal data class PineconeIndexHandle(
    val config: PineconeIndexConfig,
    val apiCredential: Credential,
    val baseUrl: String,
)

/**
 * Internal Chroma handle held by the [ResourceTable]. Chroma's REST
 * endpoints are keyed on collection UUID; the handle caches the UUID
 * resolved at Open time.
 *
 * Q-042: the credential field is the opaque [Credential] wrapper (or
 * `null` when Chroma runs locally without auth). The single `.reveal()`
 * call site lives in [ChromaProvider.standardHeaders] (the header
 * builder), guarded by a null check.
 */
internal data class ChromaCollectionHandle(
    val config: ChromaCollectionConfig,
    val apiCredential: Credential?,
    val collectionId: String,
)

/**
 * Helpers for converting between Strand [Value]s and the Kotlin-side
 * vector-store shapes above.
 *
 * The Strand-side representations follow the canonical conventions
 * used elsewhere in [Builtins]:
 *
 *   - Lists encode as the SumV-based Cons/Nil chain (matches Fs.List,
 *     String.Split, List.* primitives).
 *   - Options encode as SumV("Some", payload) / SumV("None", null).
 *   - JSON values use the spliced-variant JsonValueFull shape from
 *     corpus 66 (matches Json.Parse / Json.Stringify).
 *   - Bytes are BytesV.
 */
internal object VectorValueMarshal {
    /**
     * Decode a Strand-side UpsertItem ProductV into the Kotlin shape.
     * Expected fields: `id` (StringV), `vector` (BytesV), `metadata`
     * (JsonValueFull SumV).
     */
    fun toUpsertItem(v: Value): UpsertItem {
        val p = v as? Value.ProductV
            ?: throw IoFailure("vector-bad-upsert-item", "expected ProductV, got ${v::class.simpleName}")
        val id = (p.fields["id"] as? Value.StringV)?.v
            ?: throw IoFailure("vector-bad-upsert-item", "missing or non-String 'id' field")
        val vector = (p.fields["vector"] as? Value.BytesV)?.v
            ?: throw IoFailure("vector-bad-upsert-item", "missing or non-Bytes 'vector' field")
        val metadata = p.fields["metadata"]?.let { jsonValueToElement(it) }
            ?: JsonObject(emptyMap())
        return UpsertItem(id, vector, metadata)
    }

    /** Decode a Strand-side List<UpsertItem> Cons/Nil chain. */
    fun toUpsertItems(v: Value): List<UpsertItem> {
        val out = mutableListOf<UpsertItem>()
        var cur: Value = v
        while (true) {
            val sumV = cur as? Value.SumV ?: break
            if (sumV.case != "Cons") break
            val payload = sumV.payload as? Value.ProductV
                ?: throw IoFailure("vector-bad-list", "Cons payload is not a ProductV")
            out += toUpsertItem(payload.fields.getValue("head"))
            cur = payload.fields.getValue("tail")
        }
        return out
    }

    /** Decode a Strand-side List<String> Cons/Nil chain. */
    fun toStringList(v: Value): List<String> {
        val out = mutableListOf<String>()
        var cur: Value = v
        while (true) {
            val sumV = cur as? Value.SumV ?: break
            if (sumV.case != "Cons") break
            val payload = sumV.payload as? Value.ProductV
                ?: throw IoFailure("vector-bad-list", "Cons payload is not a ProductV")
            val head = payload.fields["head"] as? Value.StringV
                ?: throw IoFailure("vector-bad-list", "Cons head is not a StringV")
            out += head.v
            cur = payload.fields.getValue("tail")
        }
        return out
    }

    /**
     * Decode a Strand-side QueryRequest ProductV. Required fields:
     * `vector` (BytesV), `k` (IntV), `includeVector` (BoolV),
     * `includeMetadata` (BoolV). Optional: `filter` (SumV Option of
     * JsonValueFull), `metric` (SumV Option of metric-SumV).
     */
    fun toQueryRequest(v: Value): QueryRequest {
        val p = v as? Value.ProductV
            ?: throw IoFailure("vector-bad-query", "expected ProductV, got ${v::class.simpleName}")
        val vector = (p.fields["vector"] as? Value.BytesV)?.v
            ?: throw IoFailure("vector-bad-query", "missing or non-Bytes 'vector' field")
        val k = (p.fields["k"] as? Value.IntV)?.v?.toInt()
            ?: throw IoFailure("vector-bad-query", "missing or non-Int 'k' field")
        val includeVector = (p.fields["includeVector"] as? Value.BoolV)?.v ?: false
        val includeMetadata = (p.fields["includeMetadata"] as? Value.BoolV)?.v ?: true
        val filter = optionToNullable(p.fields["filter"])?.let { jsonValueToElement(it) }
        val metric = optionToNullable(p.fields["metric"])?.let { metricFromValue(it) }
        return QueryRequest(vector, k, filter, includeVector, includeMetadata, metric)
    }

    /** Encode a list of QueryHit results as the canonical Cons/Nil SumV chain. */
    fun fromQueryHits(hits: List<QueryHit>): Value {
        var listValue: Value = Value.SumV("Nil", null)
        for (h in hits.reversed()) {
            val hitV = Value.ProductV(mapOf(
                "id" to Value.StringV(h.id),
                "score" to Value.FloatV(h.score),
                "metadata" to elementToJsonValue(h.metadata),
                "vector" to (h.vector?.let { Value.SumV("Some", Value.BytesV(it)) }
                    ?: Value.SumV("None", null)),
            ))
            listValue = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to hitV, "tail" to listValue,
            )))
        }
        return listValue
    }

    /** Pinecone open config decoder. */
    fun toPineconeConfig(v: Value): PineconeIndexConfig {
        val p = v as? Value.ProductV
            ?: throw IoFailure("vector-bad-pinecone-config", "expected ProductV, got ${v::class.simpleName}")
        val indexName = (p.fields["indexName"] as? Value.StringV)?.v
            ?: throw IoFailure("vector-bad-pinecone-config", "missing 'indexName'")
        val environment = (p.fields["environment"] as? Value.StringV)?.v
            ?: throw IoFailure("vector-bad-pinecone-config", "missing 'environment'")
        val metric = metricFromValue(p.fields["metric"]
            ?: throw IoFailure("vector-bad-pinecone-config", "missing 'metric'"))
        val dimensions = (p.fields["dimensions"] as? Value.IntV)?.v?.toInt()
            ?: throw IoFailure("vector-bad-pinecone-config", "missing 'dimensions'")
        // Optional host override
        val host = optionToNullable(p.fields["host"])?.let {
            (it as? Value.StringV)?.v
                ?: throw IoFailure("vector-bad-pinecone-config", "'host' is not a StringV")
        }
        return PineconeIndexConfig(indexName, environment, metric, dimensions, host)
    }

    /** Chroma open config decoder. */
    fun toChromaConfig(v: Value): ChromaCollectionConfig {
        val p = v as? Value.ProductV
            ?: throw IoFailure("vector-bad-chroma-config", "expected ProductV, got ${v::class.simpleName}")
        val collectionName = (p.fields["collectionName"] as? Value.StringV)?.v
            ?: throw IoFailure("vector-bad-chroma-config", "missing 'collectionName'")
        val serverUrl = (p.fields["serverUrl"] as? Value.StringV)?.v
            ?: throw IoFailure("vector-bad-chroma-config", "missing 'serverUrl'")
        val metric = metricFromValue(p.fields["metric"]
            ?: throw IoFailure("vector-bad-chroma-config", "missing 'metric'"))
        return ChromaCollectionConfig(collectionName, serverUrl, metric)
    }

    /**
     * Translate the SumV-based JsonValueFull encoding (corpus 66) to
     * a kotlinx-serialization [JsonElement]. Used for `metadata`
     * and `filter` payloads heading to providers.
     */
    fun jsonValueToElement(v: Value): JsonElement {
        if (v !is Value.SumV) {
            // Conservative: treat non-SumV metadata as empty object.
            return JsonObject(emptyMap())
        }
        return when (v.case) {
            "JsonNull" -> JsonNull
            "JsonBool" -> JsonPrimitive((v.payload as Value.BoolV).v)
            "JsonNumber" -> JsonPrimitive((v.payload as Value.IntV).v)
            "JsonString" -> JsonPrimitive((v.payload as Value.StringV).v)
            "JsonArrayCons", "JsonArrayNil" -> buildJsonArray {
                var cur: Value = v
                while (true) {
                    val sumV = cur as? Value.SumV ?: break
                    if (sumV.case != "JsonArrayCons") break
                    val payload = sumV.payload as Value.ProductV
                    add(jsonValueToElement(payload.fields.getValue("head")))
                    cur = payload.fields.getValue("tail")
                }
            }
            "JsonObjectCons", "JsonObjectNil" -> buildJsonObject {
                var cur: Value = v
                while (true) {
                    val sumV = cur as? Value.SumV ?: break
                    if (sumV.case != "JsonObjectCons") break
                    val payload = sumV.payload as Value.ProductV
                    val key = (payload.fields.getValue("key") as Value.StringV).v
                    put(key, jsonValueToElement(payload.fields.getValue("value")))
                    cur = payload.fields.getValue("tail")
                }
            }
            else -> JsonObject(emptyMap())
        }
    }

    /** Inverse of [jsonValueToElement]. */
    fun elementToJsonValue(e: JsonElement): Value = when (e) {
        is JsonNull -> Value.SumV("JsonNull", null)
        is JsonPrimitive -> {
            when {
                e.isString -> Value.SumV("JsonString", Value.StringV(e.content))
                e.content == "true" -> Value.SumV("JsonBool", Value.BoolV(true))
                e.content == "false" -> Value.SumV("JsonBool", Value.BoolV(false))
                e.content.toLongOrNull() != null -> Value.SumV("JsonNumber", Value.IntV(e.content.toLong()))
                else -> Value.SumV("JsonString", Value.StringV(e.content))
            }
        }
        is JsonArray -> {
            var chain: Value = Value.SumV("JsonArrayNil", null)
            for (entry in e.reversed()) {
                chain = Value.SumV("JsonArrayCons", Value.ProductV(mapOf(
                    "head" to elementToJsonValue(entry),
                    "tail" to chain,
                )))
            }
            chain
        }
        is JsonObject -> {
            var chain: Value = Value.SumV("JsonObjectNil", null)
            for ((key, value) in e.entries.reversed()) {
                chain = Value.SumV("JsonObjectCons", Value.ProductV(mapOf(
                    "key" to Value.StringV(key),
                    "value" to elementToJsonValue(value),
                    "tail" to chain,
                )))
            }
            chain
        }
    }

    /** Unwrap an Option<T> SumV; returns null for None or absent. */
    private fun optionToNullable(v: Value?): Value? {
        if (v == null) return null
        val sumV = v as? Value.SumV ?: return v
        return when (sumV.case) {
            "Some" -> sumV.payload
            "None" -> null
            else -> v
        }
    }

    /**
     * Decode a metric SumV. Accepts SumV cases `Cosine`, `DotProduct`,
     * `Euclidean`, `Manhattan` (no payload on any), matching the
     * `Metric = Cosine | DotProduct | Euclidean | Manhattan` shape
     * from proposal § 3.4.1.
     */
    fun metricFromValue(v: Value): VectorMetric {
        val sumV = v as? Value.SumV
            ?: throw IoFailure("vector-bad-metric", "expected SumV, got ${v::class.simpleName}")
        return when (sumV.case) {
            "Cosine" -> VectorMetric.Cosine
            "DotProduct" -> VectorMetric.DotProduct
            "Euclidean" -> VectorMetric.Euclidean
            "Manhattan" -> VectorMetric.Manhattan
            else -> throw IoFailure("vector-bad-metric", "unknown metric case '${sumV.case}'")
        }
    }

    /** Encode a metric back to a SumV; payload is always null. */
    fun metricToValue(m: VectorMetric): Value = when (m) {
        VectorMetric.Cosine -> Value.SumV("Cosine", null)
        VectorMetric.DotProduct -> Value.SumV("DotProduct", null)
        VectorMetric.Euclidean -> Value.SumV("Euclidean", null)
        VectorMetric.Manhattan -> Value.SumV("Manhattan", null)
    }

    /** Encode the wire-level metric string each provider expects. */
    fun metricWireName(m: VectorMetric, provider: String): String = when (provider) {
        "pinecone" -> when (m) {
            VectorMetric.Cosine -> "cosine"
            VectorMetric.DotProduct -> "dotproduct"
            VectorMetric.Euclidean -> "euclidean"
            VectorMetric.Manhattan -> throw IoFailure(
                "vector-metric-unsupported",
                "Pinecone does not support the Manhattan metric",
            )
        }
        "chroma" -> when (m) {
            VectorMetric.Cosine -> "cosine"
            VectorMetric.DotProduct -> "ip"
            VectorMetric.Euclidean -> "l2"
            VectorMetric.Manhattan -> throw IoFailure(
                "vector-metric-unsupported",
                "Chroma does not support the Manhattan metric",
            )
        }
        else -> throw IoFailure("vector-metric-unsupported", "unknown provider '$provider'")
    }

    /**
     * Convert a float32 LE byte array to a kotlinx-serialization
     * JsonArray of numbers. Used when sending vectors to providers
     * whose REST APIs expect JSON arrays.
     */
    fun vectorBytesToJsonArray(bytes: ByteArray): JsonArray {
        require(bytes.size % 4 == 0) { "vector bytes length ${bytes.size} is not a multiple of 4" }
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return buildJsonArray {
            while (buf.hasRemaining()) {
                add(JsonPrimitive(buf.float.toDouble()))
            }
        }
    }

    /** Inverse of [vectorBytesToJsonArray]: provider response → bytes. */
    fun jsonArrayToVectorBytes(array: JsonArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(array.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (entry in array) {
            val prim = entry as? JsonPrimitive
                ?: throw IoFailure("vector-bad-response", "vector element is not a JSON primitive")
            val d = prim.content.toDoubleOrNull()
                ?: throw IoFailure("vector-bad-response", "vector element '${prim.content}' is not a number")
            buf.putFloat(d.toFloat())
        }
        return buf.array()
    }
}
