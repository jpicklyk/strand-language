package org.strand.interpreter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Q-038 Phase 1 — Chroma builtin unit tests. Mocks the HTTP
 * transport via [InMemoryHttpTransport]; covers Open / Add /
 * Query / Delete / Get / Close.
 */
class BuiltinsChromaTest {

    private val server = "http://localhost:8000"
    private val collectionName = "docs"
    private val collectionId = "11111111-2222-3333-4444-555555555555"
    private lateinit var transport: InMemoryHttpTransport

    private fun lookup(name: String) = Builtins.lookup(name)!!

    @BeforeEach
    fun setUp() {
        transport = InMemoryHttpTransport()
        Builtins.vectorHttpTransport = transport
        Builtins.credentialProvider = InMemoryCredentialProvider(mapOf(
            ("chroma" to "api_key") to "chroma-test-token",
        ))
        // Pre-arm the GET that Open issues to resolve the collection id.
        transport.on(
            "GET",
            "$server/api/v1/collections/$collectionName",
            HttpResponse(200, """{"id":"$collectionId","name":"$collectionName"}""".toByteArray()),
        )
    }

    @AfterEach
    fun tearDown() {
        Builtins.vectorHttpTransport = JdkHttpTransport
        Builtins.credentialProvider = EnvCredentialProvider
        ResourceTable.resetForTest()
    }

    private fun openConfigValue(): Value.ProductV = Value.ProductV(mapOf(
        "collectionName" to Value.StringV(collectionName),
        "serverUrl" to Value.StringV(server),
        "metric" to VectorValueMarshal.metricToValue(VectorMetric.Cosine),
    ))

    private fun openHandle(): Value =
        lookup("strand-builtin:Chroma.Collection.Open").invoke(listOf(openConfigValue()))

    /** 3-dim float32 LE vector as Bytes. */
    private fun vec(a: Float, b: Float, c: Float): Value.BytesV {
        val buf = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(a); buf.putFloat(b); buf.putFloat(c)
        return Value.BytesV(buf.array())
    }

    private fun emptyMetadata(): Value.SumV = Value.SumV("JsonObjectNil", null)

    private fun upsertItem(id: String, v: Value.BytesV, meta: Value.SumV): Value.ProductV =
        Value.ProductV(mapOf(
            "id" to Value.StringV(id), "vector" to v, "metadata" to meta,
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
    fun `Open resolves collection id and returns chroma_collection handle`() {
        val handle = openHandle()
        assertNotNull(handle as? Value.Resource)
        assertEquals("chroma_collection", (handle as Value.Resource).kind)
        // The first captured request is the collection-lookup GET.
        val req = transport.captured.first()
        assertEquals("GET", req.method)
        assertEquals("$server/api/v1/collections/$collectionName", req.url)
        assertEquals("chroma-test-token", req.headers["X-Chroma-Token"])
    }

    @Test
    fun `Open without credential still works (Chroma local server is unauthenticated)`() {
        Builtins.credentialProvider = InMemoryCredentialProvider.empty()
        val handle = openHandle()
        assertNotNull(handle as? Value.Resource)
        // No X-Chroma-Token header on the lookup.
        val req = transport.captured.first()
        assertEquals(null, req.headers["X-Chroma-Token"])
    }

    @Test
    fun `Open with non-2xx surfaces chroma-open IoFailure`() {
        // Re-stub the GET to return 404.
        val transport2 = InMemoryHttpTransport()
        transport2.on(
            "GET",
            "$server/api/v1/collections/$collectionName",
            HttpResponse(404, "collection not found".toByteArray()),
        )
        Builtins.vectorHttpTransport = transport2
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> { openHandle() }
        assertEquals("chroma-open", ex.kind)
    }

    @Test
    fun `Add posts to upsert endpoint with parallel ids embeddings metadatas arrays`() {
        val handle = openHandle()
        transport.on(
            "POST",
            "$server/api/v1/collections/$collectionId/upsert",
            HttpResponse(200, "{}".toByteArray()),
        )
        val items = listOfItems(
            upsertItem("a", vec(1f, 0f, 0f), emptyMetadata()),
            upsertItem("b", vec(0f, 1f, 0f), emptyMetadata()),
        )
        lookup("strand-builtin:Chroma.Collection.Add").invoke(listOf(handle, items))

        val req = transport.captured.first { it.url.endsWith("/upsert") }
        val body = Json.parseToJsonElement(String(req.body, Charsets.UTF_8)).jsonObject
        val ids = body["ids"] as kotlinx.serialization.json.JsonArray
        val embeddings = body["embeddings"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, ids.size)
        assertEquals(2, embeddings.size)
        // Each embedding is a 3-element float array.
        assertEquals(3, (embeddings[0] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `Query parses Chromas nested-per-query response shape`() {
        val handle = openHandle()
        // Chroma's response shape: `ids: [[id1, id2]]` etc — outer array
        // indexes by query position, inner by hit.
        val responseBody = """{
            "ids": [["a", "b"]],
            "distances": [[0.1, 0.5]],
            "metadatas": [[{"cat": "x"}, {"cat": "y"}]]
        }""".trimIndent().toByteArray()
        transport.on(
            "POST",
            "$server/api/v1/collections/$collectionId/query",
            HttpResponse(200, responseBody),
        )
        val queryReq = Value.ProductV(mapOf(
            "vector" to vec(1f, 0f, 0f),
            "k" to Value.IntV(2L),
            "filter" to Value.SumV("None", null),
            "includeVector" to Value.BoolV(false),
            "includeMetadata" to Value.BoolV(true),
            "metric" to Value.SumV("None", null),
        ))
        val result = lookup("strand-builtin:Chroma.Collection.Query").invoke(listOf(handle, queryReq))
        // Walk the resulting list and assert ids.
        val ids = mutableListOf<String>()
        var cur: Value = result
        while (cur is Value.SumV && cur.case == "Cons") {
            val head = (cur.payload as Value.ProductV).fields.getValue("head") as Value.ProductV
            ids += (head.fields.getValue("id") as Value.StringV).v
            cur = (cur.payload as Value.ProductV).fields.getValue("tail")
        }
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `Query with non-None metric raises vector-metric-fixed`() {
        val handle = openHandle()
        val queryReq = Value.ProductV(mapOf(
            "vector" to vec(1f, 0f, 0f),
            "k" to Value.IntV(2L),
            "filter" to Value.SumV("None", null),
            "includeVector" to Value.BoolV(false),
            "includeMetadata" to Value.BoolV(true),
            "metric" to Value.SumV("Some", VectorValueMarshal.metricToValue(VectorMetric.Cosine)),
        ))
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
            lookup("strand-builtin:Chroma.Collection.Query").invoke(listOf(handle, queryReq))
        }
        assertEquals("vector-metric-fixed", ex.kind)
    }

    @Test
    fun `Delete posts ids to delete endpoint`() {
        val handle = openHandle()
        transport.on(
            "POST",
            "$server/api/v1/collections/$collectionId/delete",
            HttpResponse(200, "{}".toByteArray()),
        )
        lookup("strand-builtin:Chroma.Collection.Delete").invoke(listOf(handle, listOfStrings("a", "b")))
        val req = transport.captured.first { it.url.endsWith("/delete") }
        val body = Json.parseToJsonElement(String(req.body, Charsets.UTF_8)).jsonObject
        val ids = body["ids"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, ids.size)
    }

    @Test
    fun `Get parses Chromas flat-array response`() {
        val handle = openHandle()
        // Chroma's `get` returns flat arrays (no per-query nesting).
        val responseBody = """{
            "ids": ["a", "b"],
            "metadatas": [{"cat":"x"}, {"cat":"y"}],
            "embeddings": [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]]
        }""".trimIndent().toByteArray()
        transport.on(
            "POST",
            "$server/api/v1/collections/$collectionId/get",
            HttpResponse(200, responseBody),
        )
        val result = lookup("strand-builtin:Chroma.Collection.Get").invoke(listOf(handle, listOfStrings("a", "b")))
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
        val handle = openHandle() as Value.Resource
        assertEquals(Value.UnitV, lookup("strand-builtin:Chroma.Collection.Close").invoke(listOf(handle)))
        assertEquals(Value.UnitV, lookup("strand-builtin:Chroma.Collection.Close").invoke(listOf(handle)))
    }

    @Test
    fun `Add with non-2xx surfaces chroma-upsert IoFailure`() {
        val handle = openHandle()
        transport.on(
            "POST",
            "$server/api/v1/collections/$collectionId/upsert",
            HttpResponse(500, "server error".toByteArray()),
        )
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
            lookup("strand-builtin:Chroma.Collection.Add").invoke(listOf(
                handle,
                listOfItems(upsertItem("a", vec(1f, 0f, 0f), emptyMetadata())),
            ))
        }
        assertEquals("chroma-upsert", ex.kind)
        assertTrue(ex.detail.contains("500"))
    }
}
