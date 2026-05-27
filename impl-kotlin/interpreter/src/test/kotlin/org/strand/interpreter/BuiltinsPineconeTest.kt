package org.strand.interpreter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Q-038 Phase 1 — Pinecone builtin unit tests. Mocks the HTTP
 * transport via [InMemoryHttpTransport] so no real network calls
 * occur. Covers the full Open / Upsert / Query / Delete / Fetch /
 * Close roundtrip plus error cases (missing API key, per-query
 * metric rejection).
 */
class BuiltinsPineconeTest {

    private val testHost = "http://localhost:7777"
    private lateinit var transport: InMemoryHttpTransport

    private fun lookup(name: String) = Builtins.lookup(name)!!

    @BeforeEach
    fun setUp() {
        CredentialScrubber.resetForTesting()
        transport = InMemoryHttpTransport()
        Builtins.vectorHttpTransport = transport
        Builtins.credentialProvider = InMemoryCredentialProvider(mapOf(
            ("pinecone" to "api_key") to "pk-test-1234",
        ))
    }

    @AfterEach
    fun tearDown() {
        Builtins.vectorHttpTransport = JdkHttpTransport
        Builtins.credentialProvider = EnvCredentialProvider
        ResourceTable.resetForTest()
        CredentialScrubber.resetForTesting()
    }

    private fun openConfigValue(): Value.ProductV = Value.ProductV(mapOf(
        "indexName" to Value.StringV("main"),
        "environment" to Value.StringV("us-east-1-aws"),
        "metric" to VectorValueMarshal.metricToValue(VectorMetric.Cosine),
        "dimensions" to Value.IntV(3L),
        "host" to Value.SumV("Some", Value.StringV(testHost)),
    ))

    /** Build a 3-dim float32 LE vector as Bytes. */
    private fun vec(a: Float, b: Float, c: Float): Value.BytesV {
        val buf = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(a); buf.putFloat(b); buf.putFloat(c)
        return Value.BytesV(buf.array())
    }

    /** Encode a metadata ProductV as JsonValueFull SumV. */
    private fun metadata(vararg entries: Pair<String, String>): Value.SumV {
        var chain: Value = Value.SumV("JsonObjectNil", null)
        for ((k, v) in entries.reversed()) {
            chain = Value.SumV("JsonObjectCons", Value.ProductV(mapOf(
                "key" to Value.StringV(k),
                "value" to Value.SumV("JsonString", Value.StringV(v)),
                "tail" to chain,
            )))
        }
        return chain as Value.SumV
    }

    private fun upsertItem(id: String, v: Value.BytesV, meta: Value.SumV): Value.ProductV =
        Value.ProductV(mapOf(
            "id" to Value.StringV(id),
            "vector" to v,
            "metadata" to meta,
        ))

    private fun listOfItems(vararg items: Value.ProductV): Value {
        var listV: Value = Value.SumV("Nil", null)
        for (item in items.reversed()) {
            listV = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to item, "tail" to listV,
            )))
        }
        return listV
    }

    private fun listOfStrings(vararg ids: String): Value {
        var listV: Value = Value.SumV("Nil", null)
        for (id in ids.reversed()) {
            listV = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to Value.StringV(id), "tail" to listV,
            )))
        }
        return listV
    }

    @Test
    fun `Open registers a pinecone_index handle`() {
        val handle = lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue()))
        assertNotNull(handle as? Value.Resource)
        assertEquals("pinecone_index", (handle as Value.Resource).kind)
    }

    @Test
    fun `Open without credential surfaces IoFailure`() {
        Builtins.credentialProvider = InMemoryCredentialProvider.empty()
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
            lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue()))
        }
        assertEquals("pinecone-open", ex.kind)
    }

    @Test
    fun `Upsert posts to vectors-upsert with the right body`() {
        val handle = lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue()))
        transport.on("POST", "$testHost/vectors/upsert", HttpResponse(200, "{\"upsertedCount\":2}".toByteArray()))
        val items = listOfItems(
            upsertItem("doc1", vec(1f, 0f, 0f), metadata("category" to "a")),
            upsertItem("doc2", vec(0f, 1f, 0f), metadata("category" to "b")),
        )
        lookup("strand-builtin:Pinecone.Index.Upsert").invoke(listOf(handle, items))

        // Find the upsert request (Open has not made any HTTP calls).
        val req = transport.captured.first { it.method == "POST" }
        assertEquals("$testHost/vectors/upsert", req.url)
        assertEquals("pk-test-1234", req.headers["Api-Key"])
        val body = Json.parseToJsonElement(String(req.body, Charsets.UTF_8)).jsonObject
        val vectors = body["vectors"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, vectors.size)
        assertEquals("doc1", (vectors[0].jsonObject["id"] as JsonPrimitive).content)
        val firstValues = vectors[0].jsonObject["values"] as kotlinx.serialization.json.JsonArray
        assertEquals(3, firstValues.size)
    }

    @Test
    fun `Query returns hits parsed from the response`() {
        val handle = lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue()))
        // Canned response with two matches.
        val responseBody = """{
            "matches": [
                {"id": "doc1", "score": 0.95, "metadata": {"category": "a"}},
                {"id": "doc2", "score": 0.42, "metadata": {"category": "b"}}
            ]
        }""".trimIndent().toByteArray()
        transport.on("POST", "$testHost/query", HttpResponse(200, responseBody))

        val queryReq = Value.ProductV(mapOf(
            "vector" to vec(1f, 0f, 0f),
            "k" to Value.IntV(2L),
            "filter" to Value.SumV("None", null),
            "includeVector" to Value.BoolV(false),
            "includeMetadata" to Value.BoolV(true),
            "metric" to Value.SumV("None", null),
        ))
        val result = lookup("strand-builtin:Pinecone.Index.Query").invoke(listOf(handle, queryReq))
        // Result is List<QueryHit> as Cons/Nil SumV chain.
        val first = (result as Value.SumV).let {
            assertEquals("Cons", it.case)
            (it.payload as Value.ProductV).fields.getValue("head") as Value.ProductV
        }
        assertEquals("doc1", (first.fields.getValue("id") as Value.StringV).v)
        assertEquals(0.95, (first.fields.getValue("score") as Value.FloatV).v, 1e-9)
        // Vector field is None because includeVector=false.
        assertEquals("None", (first.fields.getValue("vector") as Value.SumV).case)
    }

    @Test
    fun `Query with non-None metric raises vector-metric-fixed`() {
        val handle = lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue()))
        val queryReq = Value.ProductV(mapOf(
            "vector" to vec(1f, 0f, 0f),
            "k" to Value.IntV(2L),
            "filter" to Value.SumV("None", null),
            "includeVector" to Value.BoolV(false),
            "includeMetadata" to Value.BoolV(true),
            "metric" to Value.SumV("Some", VectorValueMarshal.metricToValue(VectorMetric.Cosine)),
        ))
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
            lookup("strand-builtin:Pinecone.Index.Query").invoke(listOf(handle, queryReq))
        }
        assertEquals("vector-metric-fixed", ex.kind)
    }

    @Test
    fun `Delete posts ids to vectors-delete`() {
        val handle = lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue()))
        transport.on("POST", "$testHost/vectors/delete", HttpResponse(200, "{}".toByteArray()))
        lookup("strand-builtin:Pinecone.Index.Delete").invoke(listOf(handle, listOfStrings("doc1", "doc2")))
        val req = transport.captured.first { it.url.endsWith("/vectors/delete") }
        val body = Json.parseToJsonElement(String(req.body, Charsets.UTF_8)).jsonObject
        val ids = body["ids"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, ids.size)
        assertEquals("doc1", (ids[0] as JsonPrimitive).content)
        assertEquals("doc2", (ids[1] as JsonPrimitive).content)
    }

    @Test
    fun `Fetch parses the vectors map response`() {
        val handle = lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue()))
        val responseBody = """{
            "vectors": {
                "doc1": {"id": "doc1", "values": [1.0, 0.0, 0.0], "metadata": {"c": "a"}},
                "doc2": {"id": "doc2", "values": [0.0, 1.0, 0.0], "metadata": {"c": "b"}}
            }
        }""".trimIndent().toByteArray()
        transport.onPrefix("GET", "$testHost/vectors/fetch", HttpResponse(200, responseBody))

        val result = lookup("strand-builtin:Pinecone.Index.Fetch").invoke(listOf(handle, listOfStrings("doc1", "doc2")))
        // Count the entries in the Cons chain.
        var count = 0
        var cur: Value = result
        while (cur is Value.SumV && cur.case == "Cons") {
            count++
            cur = (cur.payload as Value.ProductV).fields.getValue("tail")
        }
        assertEquals(2, count)
    }

    @Test
    fun `Close idempotently removes the handle`() {
        val handle = lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue())) as Value.Resource
        // First close — succeeds.
        assertEquals(Value.UnitV, lookup("strand-builtin:Pinecone.Index.Close").invoke(listOf(handle)))
        // Second close — the handle is already gone, but Close is idempotent
        // (ResourceTable.remove returns null but we don't fail).
        assertEquals(Value.UnitV, lookup("strand-builtin:Pinecone.Index.Close").invoke(listOf(handle)))
    }

    @Test
    fun `Upsert with non-2xx response surfaces pinecone-upsert IoFailure`() {
        val handle = lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue()))
        transport.on("POST", "$testHost/vectors/upsert", HttpResponse(429, "rate-limited".toByteArray()))
        val items = listOfItems(
            upsertItem("doc1", vec(1f, 0f, 0f), metadata()),
        )
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
            lookup("strand-builtin:Pinecone.Index.Upsert").invoke(listOf(handle, items))
        }
        assertEquals("pinecone-upsert", ex.kind)
        assertTrue(ex.detail.contains("429"))
    }

    @Test
    fun `Full roundtrip - open upsert query delete fetch close`() {
        val handle = lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue()))
        // Stub every endpoint.
        transport.on("POST", "$testHost/vectors/upsert", HttpResponse(200, "{}".toByteArray()))
        transport.on("POST", "$testHost/query", HttpResponse(200, """
            {"matches":[{"id":"a","score":0.9,"metadata":{}}]}
        """.trimIndent().toByteArray()))
        transport.on("POST", "$testHost/vectors/delete", HttpResponse(200, "{}".toByteArray()))
        transport.onPrefix("GET", "$testHost/vectors/fetch", HttpResponse(200, """
            {"vectors":{"a":{"id":"a","values":[1.0,0.0,0.0]}}}
        """.trimIndent().toByteArray()))

        val items = listOfItems(upsertItem("a", vec(1f, 0f, 0f), metadata()))
        lookup("strand-builtin:Pinecone.Index.Upsert").invoke(listOf(handle, items))
        val queryReq = Value.ProductV(mapOf(
            "vector" to vec(1f, 0f, 0f),
            "k" to Value.IntV(1L),
            "filter" to Value.SumV("None", null),
            "includeVector" to Value.BoolV(false),
            "includeMetadata" to Value.BoolV(true),
            "metric" to Value.SumV("None", null),
        ))
        val queryResult = lookup("strand-builtin:Pinecone.Index.Query").invoke(listOf(handle, queryReq))
        val first = ((queryResult as Value.SumV).payload as Value.ProductV).fields.getValue("head") as Value.ProductV
        assertEquals("a", (first.fields.getValue("id") as Value.StringV).v)

        lookup("strand-builtin:Pinecone.Index.Delete").invoke(listOf(handle, listOfStrings("a")))
        lookup("strand-builtin:Pinecone.Index.Fetch").invoke(listOf(handle, listOfStrings("a")))
        lookup("strand-builtin:Pinecone.Index.Close").invoke(listOf(handle))
    }

    // -- Q-042 scenario 4: upstream response echoing the API key is scrubbed --

    @Test
    fun `Q-042 upstream error echoing the API key has the key scrubbed in IoFailure detail`() {
        // The setUp() installed `pk-test-1234` (12 chars) — above the
        // scrubber's 8-character minimum, so the value is registered.
        // A misconfigured upstream that echoes the key back in its
        // error response must surface as scrubbed IoFailure detail.
        val handle = lookup("strand-builtin:Pinecone.Index.Open").invoke(listOf(openConfigValue()))
        transport.on(
            "POST",
            "$testHost/vectors/upsert",
            HttpResponse(
                401,
                """{"error":"unauthorized — provided Api-Key=pk-test-1234 is invalid"}""".toByteArray(),
            ),
        )
        val items = listOfItems(upsertItem("doc1", vec(1f, 0f, 0f), metadata()))
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
            lookup("strand-builtin:Pinecone.Index.Upsert").invoke(listOf(handle, items))
        }
        assertEquals("pinecone-upsert", ex.kind)
        org.junit.jupiter.api.Assertions.assertFalse(
            "pk-test-1234" in ex.detail,
            "API key leaked through scrubbed IoFailure detail: ${ex.detail}",
        )
        assertTrue("[REDACTED:pinecone:api_key]" in ex.detail,
            "scrubbed placeholder should appear in detail: ${ex.detail}")
        assertTrue("unauthorized" in ex.detail)
        // The unscrubbed form preserves the raw value for ErrorVerbosity.Full.
        assertTrue("pk-test-1234" in ex.unscrubbedDetail)
    }
}
