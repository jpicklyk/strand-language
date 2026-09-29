package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress

/**
 * Review H4: the vector-store providers take their host from the program
 * (Pinecone `host`, Chroma `serverUrl`) and attach the API key. Every
 * request must pass the same [NetSandbox] gate `Net.Connect` /
 * `Http.Request` use, before any byte reaches the transport. A recording
 * transport proves nothing was sent.
 */
class VectorSandboxTest {

    @AfterEach
    fun tearDown() {
        ResourceTable.resetForTest()
    }

    /** A transport that records every request and answers a canned 200. */
    private class Recorder(private val body: String = "{}") : VectorHttpTransport {
        val captured = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            captured += request
            return HttpResponse(200, body.toByteArray())
        }
    }

    /** Resolver whose answers can change between calls (DNS rebinding). */
    private class MutableResolver(var table: Map<String, String>) : NameResolver {
        override fun resolve(host: String): Array<InetAddress> {
            val ip = table[host.lowercase()] ?: InetAddress.getByName(host).hostAddress
            return arrayOf(InetAddress.getByName(ip))
        }
    }

    private fun ctx(
        recorder: VectorHttpTransport,
        net: NetPolicy = SandboxPolicy.SECURE_DEFAULT.net,
        resolver: NameResolver = MutableResolver(mapOf("idx.svc.pinecone.io" to "34.1.2.3", "chroma.example.com" to "34.1.2.4")),
    ): HostContext = HostContext.processDefault().copy(
        sandboxPolicy = SandboxPolicy(fs = SandboxPolicy.OPEN_DEFAULT.fs, net = net),
        nameResolver = resolver,
        vectorHttpTransport = recorder,
        credentialProvider = InMemoryCredentialProvider(mapOf(
            ("pinecone" to "api_key") to "pk-secret-1234",
            ("chroma" to "api_key") to "ck-secret-1234",
        )),
    )

    private fun pineconeConfig(host: String): Value = Value.ProductV(mapOf(
        "indexName" to Value.StringV("main"),
        "environment" to Value.StringV("us-east-1-aws"),
        "metric" to VectorValueMarshal.metricToValue(VectorMetric.Cosine),
        "dimensions" to Value.IntV(3L),
        "host" to Value.SumV("Some", Value.StringV(host)),
    ))

    private fun chromaConfig(serverUrl: String): Value = Value.ProductV(mapOf(
        "collectionName" to Value.StringV("docs"),
        "serverUrl" to Value.StringV(serverUrl),
        "metric" to VectorValueMarshal.metricToValue(VectorMetric.Cosine),
    ))

    private fun ids(vararg s: String): Value = s.foldRight(Value.SumV("Nil", null) as Value) { id, acc ->
        Value.SumV("Cons", Value.ProductV(mapOf("head" to Value.StringV(id), "tail" to acc)))
    }

    private fun call(ctx: HostContext, target: String, vararg args: Value): Value =
        Builtins.lookup("strand-builtin:$target")!!.invoke(ctx, args.toList())

    @Test
    fun `Pinecone config pointing at the metadata address is denied before any request`() {
        val rec = Recorder()
        val ex = assertThrows<SandboxViolation> {
            val h = call(ctx(rec), "Pinecone.Index.Open", pineconeConfig("http://169.254.169.254"))
            call(ctx(rec), "Pinecone.Index.Fetch", h, ids("a"))
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
        assertTrue(rec.captured.isEmpty(), "no request (and no API key) may reach the transport")
    }

    @Test
    fun `Chroma server URL pointing at the metadata address is denied before any request`() {
        val rec = Recorder()
        val ex = assertThrows<SandboxViolation> {
            call(ctx(rec), "Chroma.Collection.Open", chromaConfig("http://169.254.169.254:8000"))
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
        assertTrue(rec.captured.isEmpty())
    }

    @Test
    fun `non-allow-listed vector host is denied`() {
        val rec = Recorder()
        val net = SandboxPolicy.SECURE_DEFAULT.net.copy(allowedHosts = listOf(HostPattern("*.pinecone.io")))
        val ex = assertThrows<SandboxViolation> {
            val h = call(ctx(rec, net), "Pinecone.Index.Open", pineconeConfig("https://evil.example.com"))
            call(ctx(rec, net), "Pinecone.Index.Fetch", h, ids("a"))
        }
        assertEquals(SandboxViolationKind.NetHostNotAllowlisted, ex.kind)
        assertTrue(rec.captured.isEmpty())
    }

    @Test
    fun `non-http scheme in a vector URL is rejected`() {
        val rec = Recorder()
        val ex = assertThrows<SandboxViolation> {
            call(ctx(rec), "Chroma.Collection.Open", chromaConfig("file:///etc/passwd"))
        }
        assertEquals(SandboxViolationKind.HttpSchemeRejected, ex.kind)
        assertTrue(rec.captured.isEmpty())
    }

    @Test
    fun `allowed host passes, and a later rebinding to a blocked range is caught per request`() {
        val rec = Recorder("""{"vectors":{}}""")
        val resolver = MutableResolver(mapOf("idx.svc.pinecone.io" to "34.1.2.3"))
        val c = ctx(rec, resolver = resolver)
        val h = call(c, "Pinecone.Index.Open", pineconeConfig("https://idx.svc.pinecone.io"))
        call(c, "Pinecone.Index.Fetch", h, ids("a"))
        assertEquals(1, rec.captured.size)

        resolver.table = mapOf("idx.svc.pinecone.io" to "169.254.169.254")
        val ex = assertThrows<SandboxViolation> { call(c, "Pinecone.Index.Fetch", h, ids("a")) }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
        assertEquals(1, rec.captured.size, "the rebound request must not be sent")
    }

    @Test
    fun `open sandbox policy performs no DNS for vector hosts`() {
        val rec = Recorder("""{"vectors":{}}""")
        val c = ctx(rec, net = SandboxPolicy.OPEN_DEFAULT.net, resolver = NameResolver { error("no DNS expected") })
        val h = call(c, "Pinecone.Index.Open", pineconeConfig("https://no-such-host.invalid"))
        call(c, "Pinecone.Index.Fetch", h, ids("a"))
        assertEquals(1, rec.captured.size)
    }
}
