package org.strand.interpreter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pinecone vector-store provider (Q-038 Phase 1).
 *
 * Wraps Pinecone's REST API behind the operations required by the
 * cross-provider surface: open / close / upsert / query / delete /
 * fetch. The provider object is stateless — handle state lives in
 * [ResourceTable] under kind `pinecone_index`. Failures translate
 * to [IoFailure] with kind prefixes `pinecone-*`.
 *
 * Wire-protocol decisions for Phase 1:
 *
 *   - Authentication via `Api-Key` HTTP header. Key is resolved at
 *     Open time from [Builtins.credentialProvider] with key
 *     `("pinecone", "api_key")`, sourced by default from
 *     `PINECONE_API_KEY`.
 *   - Index host is computed from `<indexName>-<environment>.svc.pinecone.io`
 *     (matching Pinecone's serverless URL shape) unless the config
 *     supplies an explicit `host` override (tests set this to a
 *     stub URL so wire matchers fire predictably).
 *   - Upsert is bulk (`POST /vectors/upsert` with a `vectors` array).
 *   - Query is `POST /query` with optional `filter` and `topK`.
 *   - Fetch is `GET /vectors/fetch?ids=...`.
 *   - Delete is `POST /vectors/delete` with an `ids` array.
 *
 * Errors that surface as [IoFailure]:
 *   - "pinecone-open"     missing API key or non-2xx on probe
 *   - "pinecone-upsert"   non-2xx on POST /vectors/upsert
 *   - "pinecone-query"    non-2xx on POST /query
 *   - "pinecone-delete"   non-2xx on POST /vectors/delete
 *   - "pinecone-fetch"    non-2xx on GET /vectors/fetch
 *   - "vector-metric-fixed"  caller supplied [QueryRequest.metric] (rejected)
 */
object PineconeProvider {

    /** Provider string used in effect declarations and credential lookups. */
    const val PROVIDER_NAME = "pinecone"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Open an index. Resolves the API key, computes the host URL, and
     * returns a [ResourceTable]-registered handle of kind
     * `pinecone_index`. No probe request is issued — Phase 1 assumes
     * the index already exists (the agent created it out of band).
     */
    fun open(config: PineconeIndexConfig): Value.Resource {
        val apiCredential = Builtins.credentialProvider.resolve(PROVIDER_NAME, "api_key")
            ?: throw IoFailure("pinecone-open", "no PINECONE_API_KEY configured")
        val baseUrl = config.host
            ?: "https://${config.indexName}-${config.environment}.svc.pinecone.io"
        return ResourceTable.register(
            "pinecone_index",
            PineconeIndexHandle(config, apiCredential, baseUrl),
        )
    }

    /** Close an open index handle. Idempotent. */
    fun close(handle: Value.Resource): Value {
        ResourceTable.remove(handle)
        return Value.UnitV
    }

    /**
     * Bulk-upsert items. Items with existing ids are replaced
     * (Pinecone upsert semantics, matching the cross-provider
     * idempotency decision in proposal § 3.4.7).
     */
    fun upsert(handle: Value.Resource, items: List<UpsertItem>) {
        if (items.isEmpty()) return
        val h = ResourceTable.get(handle, "pinecone_index") as PineconeIndexHandle
        val body = buildJsonObject {
            putJsonArray("vectors") {
                for (item in items) {
                    add(buildJsonObject {
                        put("id", item.id)
                        put("values", VectorValueMarshal.vectorBytesToJsonArray(item.vector))
                        put("metadata", item.metadata)
                    })
                }
            }
        }
        val response = Builtins.vectorHttpTransport.execute(
            HttpRequest(
                method = "POST",
                url = "${h.baseUrl}/vectors/upsert",
                headers = standardHeaders(h.apiCredential),
                body = json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8),
            )
        )
        if (response.status !in 200..299) {
            throw IoFailure(
                "pinecone-upsert",
                "status=${response.status} body=${String(response.body, Charsets.UTF_8)}",
            )
        }
    }

    /** Run a vector similarity query. */
    fun query(handle: Value.Resource, request: QueryRequest): List<QueryHit> {
        if (request.metric != null) {
            throw IoFailure(
                "vector-metric-fixed",
                "Pinecone sets metric at index creation; remove the per-query metric field",
            )
        }
        val h = ResourceTable.get(handle, "pinecone_index") as PineconeIndexHandle
        val body = buildJsonObject {
            put("vector", VectorValueMarshal.vectorBytesToJsonArray(request.vector))
            put("topK", request.k)
            put("includeMetadata", request.includeMetadata)
            put("includeValues", request.includeVector)
            if (request.filter != null) {
                put("filter", request.filter)
            }
        }
        val response = Builtins.vectorHttpTransport.execute(
            HttpRequest(
                method = "POST",
                url = "${h.baseUrl}/query",
                headers = standardHeaders(h.apiCredential),
                body = json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8),
            )
        )
        if (response.status !in 200..299) {
            throw IoFailure(
                "pinecone-query",
                "status=${response.status} body=${String(response.body, Charsets.UTF_8)}",
            )
        }
        val parsed = json.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject
        val matches = parsed["matches"] as? JsonArray ?: return emptyList()
        return matches.map { hitElement ->
            decodeHit(hitElement.jsonObject, request.includeVector)
        }
    }

    /** Delete vectors by id. */
    fun delete(handle: Value.Resource, ids: List<String>) {
        if (ids.isEmpty()) return
        val h = ResourceTable.get(handle, "pinecone_index") as PineconeIndexHandle
        val body = buildJsonObject {
            putJsonArray("ids") {
                for (id in ids) add(JsonPrimitive(id))
            }
        }
        val response = Builtins.vectorHttpTransport.execute(
            HttpRequest(
                method = "POST",
                url = "${h.baseUrl}/vectors/delete",
                headers = standardHeaders(h.apiCredential),
                body = json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8),
            )
        )
        if (response.status !in 200..299) {
            throw IoFailure(
                "pinecone-delete",
                "status=${response.status} body=${String(response.body, Charsets.UTF_8)}",
            )
        }
    }

    /** Fetch vectors by id (no similarity search). */
    fun fetch(handle: Value.Resource, ids: List<String>): List<QueryHit> {
        if (ids.isEmpty()) return emptyList()
        val h = ResourceTable.get(handle, "pinecone_index") as PineconeIndexHandle
        // Build the query string: ids=...&ids=...  (Pinecone's REST shape).
        val query = ids.joinToString("&") { "ids=" + java.net.URLEncoder.encode(it, "UTF-8") }
        val response = Builtins.vectorHttpTransport.execute(
            HttpRequest(
                method = "GET",
                url = "${h.baseUrl}/vectors/fetch?$query",
                headers = standardHeaders(h.apiCredential),
            )
        )
        if (response.status !in 200..299) {
            throw IoFailure(
                "pinecone-fetch",
                "status=${response.status} body=${String(response.body, Charsets.UTF_8)}",
            )
        }
        val parsed = json.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject
        val vectorsField = parsed["vectors"] as? JsonObject ?: return emptyList()
        return vectorsField.entries.map { (_, vectorEntry) ->
            decodeHit(vectorEntry.jsonObject, includeVector = true, defaultScore = 0.0)
        }
    }

    private fun decodeHit(
        obj: JsonObject,
        includeVector: Boolean,
        defaultScore: Double = 0.0,
    ): QueryHit {
        val id = (obj["id"] as? JsonPrimitive)?.content
            ?: throw IoFailure("pinecone-bad-response", "match missing 'id'")
        val score = (obj["score"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: defaultScore
        val metadata = obj["metadata"] ?: JsonObject(emptyMap())
        val vector = if (includeVector) {
            (obj["values"] as? JsonArray)?.let { VectorValueMarshal.jsonArrayToVectorBytes(it) }
        } else {
            null
        }
        return QueryHit(id, score, metadata, vector)
    }

    // Q-042: single auditable `.reveal()` call site for the Pinecone
    // provider. The revealed value flows into the `Api-Key` HTTP header
    // map and never leaves this function — every other interpolation of
    // the credential inside [PineconeProvider] stays within the
    // [Credential] wrapper.
    private fun standardHeaders(apiCredential: Credential): Map<String, String> = mapOf(
        "Api-Key" to apiCredential.reveal(),
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    )
}
