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

/**
 * Chroma vector-store provider (Q-038 Phase 1).
 *
 * Wraps Chroma's HTTP API. The server mode is assumed (embedded mode
 * is out of scope for the initial slice). Collection ids are
 * resolved at Open time and cached in the handle so subsequent
 * operations don't have to re-query.
 *
 * Wire-protocol decisions for Phase 1 (matches Chroma's v1 REST API
 * shape — `/api/v1/collections/<id>/{add,get,query,delete}`):
 *
 *   - Authentication via `X-Chroma-Token` HTTP header when the
 *     credential is present. Chroma running locally without auth
 *     accepts requests without the header — so absence of an API
 *     key is not fatal.
 *   - Open resolves the collection by name via
 *     `GET /api/v1/collections/<name>` and caches the returned id.
 *   - Add (upsert): POST `/api/v1/collections/<id>/upsert` with
 *     parallel arrays `ids`, `embeddings`, `metadatas`.
 *   - Query: POST `/api/v1/collections/<id>/query` with
 *     `query_embeddings`, `n_results`, `where` (filter dict),
 *     `include` flags.
 *   - Get (id-based fetch): POST `/api/v1/collections/<id>/get` with
 *     `ids` array.
 *   - Delete: POST `/api/v1/collections/<id>/delete` with `ids`.
 *
 * Chroma's response shape for query nests results as parallel arrays
 * indexed by query position. We always issue a single query, so the
 * provider unpacks `ids[0]`, `distances[0]`, etc.
 *
 * Errors that surface as [IoFailure]:
 *   - "chroma-open"     non-2xx resolving the collection id
 *   - "chroma-upsert"   non-2xx on POST .../upsert
 *   - "chroma-query"    non-2xx on POST .../query
 *   - "chroma-delete"   non-2xx on POST .../delete
 *   - "chroma-get"      non-2xx on POST .../get
 *   - "vector-metric-fixed"  caller supplied per-query metric (rejected)
 */
object ChromaProvider {

    /** Provider string used in effect declarations and credential lookups. */
    const val PROVIDER_NAME = "chroma"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolve the collection id and return a [ResourceTable]-registered
     * handle of kind `chroma_collection`.
     */
    fun open(
        config: ChromaCollectionConfig,
        credentialProvider: CredentialProvider = Builtins.credentialProvider,
        transport: VectorHttpTransport = Builtins.vectorHttpTransport,
    ): Value.Resource {
        val apiCredential = credentialProvider.resolve(PROVIDER_NAME, "api_key")
        // Resolve the collection id by name. Chroma's REST returns
        // {"id": "...", "name": "...", ...} on success.
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = "${normalizeUrl(config.serverUrl)}/api/v1/collections/${config.collectionName}",
                headers = standardHeaders(apiCredential),
            )
        )
        if (response.status !in 200..299) {
            throw IoFailure(
                "chroma-open",
                "status=${response.status} body=${String(response.body, Charsets.UTF_8)}",
            )
        }
        val parsed = json.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject
        val collectionId = (parsed["id"] as? JsonPrimitive)?.content
            ?: throw IoFailure("chroma-open", "collection response missing 'id'")
        return ResourceTable.register(
            "chroma_collection",
            ChromaCollectionHandle(config, apiCredential, collectionId),
        )
    }

    /** Close an open collection handle. Idempotent. */
    fun close(handle: Value.Resource): Value {
        ResourceTable.remove(handle)
        return Value.UnitV
    }

    /**
     * Bulk add (upsert) items. Chroma's `upsert` endpoint provides
     * idempotent insert-or-replace semantics matching the cross-
     * provider commitment in proposal § 3.4.7.
     */
    fun add(
        handle: Value.Resource,
        items: List<UpsertItem>,
        transport: VectorHttpTransport = Builtins.vectorHttpTransport,
    ) {
        if (items.isEmpty()) return
        val h = ResourceTable.get(handle, "chroma_collection") as ChromaCollectionHandle
        val body = buildJsonObject {
            put("ids", buildJsonArray {
                for (item in items) add(JsonPrimitive(item.id))
            })
            put("embeddings", buildJsonArray {
                for (item in items) add(VectorValueMarshal.vectorBytesToJsonArray(item.vector))
            })
            put("metadatas", buildJsonArray {
                for (item in items) add(item.metadata)
            })
        }
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                url = collectionUrl(h, "upsert"),
                headers = standardHeaders(h.apiCredential),
                body = json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8),
            )
        )
        if (response.status !in 200..299) {
            throw IoFailure(
                "chroma-upsert",
                "status=${response.status} body=${String(response.body, Charsets.UTF_8)}",
            )
        }
    }

    /** Run a vector similarity query. */
    fun query(
        handle: Value.Resource,
        request: QueryRequest,
        transport: VectorHttpTransport = Builtins.vectorHttpTransport,
    ): List<QueryHit> {
        if (request.metric != null) {
            throw IoFailure(
                "vector-metric-fixed",
                "Chroma sets metric at collection creation; remove the per-query metric field",
            )
        }
        val h = ResourceTable.get(handle, "chroma_collection") as ChromaCollectionHandle
        val include = buildJsonArray {
            add(JsonPrimitive("distances"))
            if (request.includeMetadata) add(JsonPrimitive("metadatas"))
            if (request.includeVector) add(JsonPrimitive("embeddings"))
        }
        val body = buildJsonObject {
            put("query_embeddings", buildJsonArray {
                add(VectorValueMarshal.vectorBytesToJsonArray(request.vector))
            })
            put("n_results", request.k)
            if (request.filter != null) {
                put("where", request.filter)
            }
            put("include", include)
        }
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                url = collectionUrl(h, "query"),
                headers = standardHeaders(h.apiCredential),
                body = json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8),
            )
        )
        if (response.status !in 200..299) {
            throw IoFailure(
                "chroma-query",
                "status=${response.status} body=${String(response.body, Charsets.UTF_8)}",
            )
        }
        val parsed = json.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject
        // Chroma returns parallel arrays nested by query position; we
        // always run a single query so we read element [0] from each.
        val ids = (parsed["ids"] as? JsonArray)?.firstOrNull()?.jsonArray ?: return emptyList()
        val distances = (parsed["distances"] as? JsonArray)?.firstOrNull()?.jsonArray
        val metadatas = (parsed["metadatas"] as? JsonArray)?.firstOrNull() as? JsonArray
        val embeddings = (parsed["embeddings"] as? JsonArray)?.firstOrNull() as? JsonArray
        return ids.mapIndexed { i, idElement ->
            val id = (idElement as JsonPrimitive).content
            val score = (distances?.getOrNull(i) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val metadata = metadatas?.getOrNull(i) ?: JsonObject(emptyMap())
            val vectorBytes = if (request.includeVector) {
                (embeddings?.getOrNull(i) as? JsonArray)?.let { VectorValueMarshal.jsonArrayToVectorBytes(it) }
            } else null
            QueryHit(id, score, metadata, vectorBytes)
        }
    }

    /** Delete items by id. */
    fun delete(
        handle: Value.Resource,
        ids: List<String>,
        transport: VectorHttpTransport = Builtins.vectorHttpTransport,
    ) {
        if (ids.isEmpty()) return
        val h = ResourceTable.get(handle, "chroma_collection") as ChromaCollectionHandle
        val body = buildJsonObject {
            put("ids", buildJsonArray {
                for (id in ids) add(JsonPrimitive(id))
            })
        }
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                url = collectionUrl(h, "delete"),
                headers = standardHeaders(h.apiCredential),
                body = json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8),
            )
        )
        if (response.status !in 200..299) {
            throw IoFailure(
                "chroma-delete",
                "status=${response.status} body=${String(response.body, Charsets.UTF_8)}",
            )
        }
    }

    /**
     * Fetch items by id (no similarity search). Chroma names this
     * `get`; the cross-provider name in the proposal is `Get`.
     */
    fun get(
        handle: Value.Resource,
        ids: List<String>,
        transport: VectorHttpTransport = Builtins.vectorHttpTransport,
    ): List<QueryHit> {
        if (ids.isEmpty()) return emptyList()
        val h = ResourceTable.get(handle, "chroma_collection") as ChromaCollectionHandle
        val body = buildJsonObject {
            put("ids", buildJsonArray {
                for (id in ids) add(JsonPrimitive(id))
            })
            put("include", buildJsonArray {
                add(JsonPrimitive("metadatas"))
                add(JsonPrimitive("embeddings"))
            })
        }
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                url = collectionUrl(h, "get"),
                headers = standardHeaders(h.apiCredential),
                body = json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8),
            )
        )
        if (response.status !in 200..299) {
            throw IoFailure(
                "chroma-get",
                "status=${response.status} body=${String(response.body, Charsets.UTF_8)}",
            )
        }
        val parsed = json.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject
        // Chroma's `get` returns flat arrays (no per-query nesting,
        // since `get` doesn't take query embeddings).
        val resultIds = parsed["ids"] as? JsonArray ?: return emptyList()
        val metadatas = parsed["metadatas"] as? JsonArray
        val embeddings = parsed["embeddings"] as? JsonArray
        return resultIds.mapIndexed { i, idElement ->
            val id = (idElement as JsonPrimitive).content
            val metadata = metadatas?.getOrNull(i) ?: JsonObject(emptyMap())
            val vectorBytes = (embeddings?.getOrNull(i) as? JsonArray)
                ?.let { VectorValueMarshal.jsonArrayToVectorBytes(it) }
            QueryHit(id, 0.0, metadata, vectorBytes)
        }
    }

    private fun collectionUrl(handle: ChromaCollectionHandle, action: String): String {
        return "${normalizeUrl(handle.config.serverUrl)}/api/v1/collections/${handle.collectionId}/$action"
    }

    private fun normalizeUrl(url: String): String = url.trimEnd('/')

    // Q-042: single auditable `.reveal()` call site for the Chroma
    // provider. The revealed value flows into the `X-Chroma-Token`
    // header map and never leaves this function. Chroma can run locally
    // without auth, so the credential is optional — the header is
    // omitted entirely when no credential is configured.
    private fun standardHeaders(apiCredential: Credential?): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        if (apiCredential != null) {
            headers["X-Chroma-Token"] = apiCredential.reveal()
        }
        return headers
    }
}
