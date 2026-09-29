package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review H1 / H2 / M2: adversarial tests for `Http.Request` — path
 * injection into the authority, redirect following, and unbounded
 * reads. Every server here is a local `com.sun.net.httpserver.HttpServer`
 * bound to 127.0.0.1 so no test touches a real network.
 */
class HttpRequestHardeningTest {

    private val servers = mutableListOf<com.sun.net.httpserver.HttpServer>()

    @AfterEach
    fun stopServers() {
        servers.forEach { it.stop(0) }
    }

    /** Start a loopback server whose every request increments [hits] and is answered by [handler]. */
    private fun server(
        hits: AtomicInteger = AtomicInteger(),
        handler: (com.sun.net.httpserver.HttpExchange) -> Unit = { ex ->
            val body = "ok".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        },
    ): Pair<Int, AtomicInteger> {
        val s = com.sun.net.httpserver.HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { ex ->
            hits.incrementAndGet()
            try { handler(ex) } finally { ex.close() }
        }
        s.start()
        servers += s
        return s.address.port to hits
    }

    private fun request(ctx: HostContext, port: Int, path: String): Value =
        Builtins.lookup("strand-builtin:Http.Request")!!.invoke(ctx, listOf(
            Value.StringV("127.0.0.1"),
            Value.IntV(port.toLong()),
            Value.StringV("http"),
            Value.StringV(path),
            Value.StringV("GET"),
            Value.SumV("Nil", null),
            Value.BytesV(ByteArray(0)),
        ))

    private val openCtx: HostContext
        get() = HostContext.processDefault().copy(sandboxPolicy = SandboxPolicy.OPEN_DEFAULT)

    // ---------- H1: path injection ----------

    @Test
    fun `path with at-sign cannot move the request to another authority`() {
        val (allowedPort, _) = server()
        val (victimPort, victimHits) = server()
        val ex = assertThrows<SandboxViolation> {
            request(openCtx, allowedPort, "@127.0.0.1:$victimPort/steal")
        }
        assertEquals(SandboxViolationKind.HttpPathRejected, ex.kind)
        assertEquals(0, victimHits.get(), "the injected authority must never be contacted")
    }

    @Test
    fun `path without leading slash cannot extend the port`() {
        val (port, hits) = server()
        // Pre-fix, port P plus path "1/" became port P*10+1.
        val ex = assertThrows<SandboxViolation> { request(openCtx, port, "1/") }
        assertEquals(SandboxViolationKind.HttpPathRejected, ex.kind)
        assertEquals(0, hits.get())
    }

    @Test
    fun `metadata userinfo injection is rejected before connect`() {
        val (port, _) = server()
        val ex = assertThrows<SandboxViolation> {
            request(openCtx, port, "@169.254.169.254/latest/meta-data/")
        }
        assertEquals(SandboxViolationKind.HttpPathRejected, ex.kind)
    }

    @Test
    fun `validateRequestPath rejects every escape character`() {
        for (bad in listOf("", "x", "/a@b", "/a\\b", "/a b", "/a\tb", "/a\nb", "/a#frag", "/a%zz", "/a%2", "/é")) {
            val ex = assertThrows<SandboxViolation>("expected rejection of '$bad'") { NetIo.validateRequestPath(bad) }
            assertEquals(SandboxViolationKind.HttpPathRejected, ex.kind)
        }
        for (good in listOf("/", "/a/b", "/search?q=a%20b&x=1", "/v1/x:y", "//double")) {
            NetIo.validateRequestPath(good)
        }
    }

    @Test
    fun `buildPinnedUri keeps escapes and pins host and port`() {
        val uri = NetIo.buildPinnedUri("http", InetAddress.getByName("10.1.2.3"), 8080, "/a%20b?c=d")
        assertEquals("10.1.2.3", uri.host)
        assertEquals(8080, uri.port)
        assertEquals("/a%20b", uri.rawPath)
        assertEquals("c=d", uri.rawQuery)
        val v6 = NetIo.buildPinnedUri("https", InetAddress.getByName("2001:db8::1"), 443, "//x")
        assertEquals(443, v6.port)
        assertTrue(v6.host.contains("2001:db8"))
    }

    // ---------- H2: redirects ----------

    /** A server that answers every request with `302 Location: <target>`. */
    private fun redirector(target: String): Pair<Int, AtomicInteger> = server { ex ->
        ex.responseHeaders.add("Location", target)
        ex.sendResponseHeaders(302, -1)
    }

    @Test
    fun `Http_Request does not follow a redirect to another address`() {
        val (victimPort, victimHits) = server()
        val (port, hits) = redirector("http://127.0.0.1:$victimPort/latest/meta-data/")
        val ex = assertThrows<IoFailure> { request(openCtx, port, "/") }
        assertEquals("http-redirect", ex.kind)
        assertTrue(ex.detail.contains("302"), ex.detail)
        assertEquals(1, hits.get())
        assertEquals(0, victimHits.get(), "the redirect target must never be contacted")
    }

    @Test
    fun `JdkHttpTransport does not follow redirects`() {
        val (victimPort, victimHits) = server()
        val (port, _) = redirector("http://127.0.0.1:$victimPort/")
        val ex = assertThrows<IoFailure> {
            JdkHttpTransport.execute(HttpRequest("GET", "http://127.0.0.1:$port/"))
        }
        assertEquals("http-redirect", ex.kind)
        assertEquals(0, victimHits.get())
    }

    @Test
    fun `DefaultLlmHttpClient does not follow redirects on post or openStream`() {
        val (victimPort, victimHits) = server()
        val (port, _) = redirector("http://127.0.0.1:$victimPort/")
        val url = "http://127.0.0.1:$port/v1/messages"
        val postEx = assertThrows<IoFailure> { DefaultLlmHttpClient.post(url, emptyList(), "{}".toByteArray()) }
        assertEquals("http-redirect", postEx.kind)
        val streamEx = assertThrows<IoFailure> { DefaultLlmHttpClient.openStream(url, emptyList(), "{}".toByteArray()) }
        assertEquals("http-redirect", streamEx.kind)
        assertEquals(0, victimHits.get())
    }

    // ---------- M2: timeouts and body caps ----------

    private val tight = BuiltinLimits(
        connectTimeoutMillis = 500, readTimeoutMillis = 300, acceptTimeoutMillis = 200,
        maxSleepMillis = 1_000, maxResponseBytes = 1024,
    )

    /** A loopback listener that completes the TCP handshake (backlog) but never answers. */
    private fun stalledPort(): Pair<Int, java.net.ServerSocket> {
        val s = java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        return s.localPort to s
    }

    @Test
    fun `Http_Request against a stalled server fails within the read timeout`() {
        val (port, sock) = stalledPort()
        sock.use {
            val ctx = openCtx.copy(builtinLimits = tight)
            val ex = org.junit.jupiter.api.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10)) {
                assertThrows<IoFailure> { request(ctx, port, "/") }
            }
            assertEquals("http-request", ex.kind)
        }
    }

    @Test
    fun `Http_Request refuses a response body over maxResponseBytes`() {
        val (port, _) = server { ex ->
            val body = ByteArray(64 * 1024) { 'x'.code.toByte() }
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        val ex = assertThrows<InterpretException> { request(openCtx.copy(builtinLimits = tight), port, "/") }
        val err = ex.error as InterpretError.ResourceExhaustion
        assertEquals(org.strand.core.ExhaustionKind.AllocatedValues, err.kind)
        assertEquals(1024L, err.limit)
    }

    @Test
    fun `vector and LLM transports cap response bodies`() {
        val (port, _) = server { ex ->
            val body = ByteArray(64 * 1024) { 'x'.code.toByte() }
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        val vector = assertThrows<InterpretException> {
            BoundedJdkHttpTransport(tight).execute(HttpRequest("GET", "http://127.0.0.1:$port/"))
        }
        assertTrue(vector.error is InterpretError.ResourceExhaustion)
        val llm = assertThrows<InterpretException> {
            BoundedLlmHttpClient(tight).post("http://127.0.0.1:$port/", emptyList(), "{}".toByteArray())
        }
        assertTrue(llm.error is InterpretError.ResourceExhaustion)
    }

    @Test
    fun `Http_Accept with no client fails after the accept timeout`() {
        val ctx = openCtx.copy(builtinLimits = tight)
        val server = Builtins.lookup("strand-builtin:Http.Listen")!!.invoke(ctx, listOf(Value.IntV(0))) as Value.Resource
        try {
            val ex = org.junit.jupiter.api.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10)) {
                assertThrows<IoFailure> { Builtins.lookup("strand-builtin:Http.Accept")!!.invoke(ctx, listOf(server)) }
            }
            assertEquals("http-accept-timeout", ex.kind)
        } finally {
            Builtins.lookup("strand-builtin:Http.ServerClose")!!.invoke(ctx, listOf(server))
        }
    }

    @Test
    fun `Time_Sleep beyond the wall-clock budget is refused without sleeping`() {
        val ctx = openCtx.copy(clock = Builtins.SystemClock, builtinLimits = tight)
        val t0 = System.nanoTime()
        val ex = assertThrows<InterpretException> {
            Builtins.lookup("strand-builtin:Time.Sleep")!!.invoke(ctx, listOf(Value.IntV(Long.MAX_VALUE)))
        }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
        val err = ex.error as InterpretError.ResourceExhaustion
        assertEquals(org.strand.core.ExhaustionKind.WallClock, err.kind)
        assertEquals(1_000L, err.limit)
    }

    @Test
    fun `BuiltinLimits derive from the policy wall-clock budget`() {
        val limits = org.strand.core.EvaluationLimits.DEFAULTS.copy(wallClockBudgetMillis = 1234)
        val ctx = HostContext.fromPolicy(HostPolicy.OPEN.copy(limits = limits))
        assertEquals(1234L, ctx.builtinLimits.connectTimeoutMillis)
        assertEquals(1234L, ctx.builtinLimits.readTimeoutMillis)
        assertEquals(1234L, ctx.builtinLimits.maxSleepMillis)
        assertEquals(0, BuiltinLimits.from(org.strand.core.EvaluationLimits.PERMISSIVE).readTimeoutInt)
    }

    @Test
    fun `well-formed path with query still reaches the server`() {
        val seen = mutableListOf<String>()
        val (port, hits) = server { ex ->
            seen += ex.requestURI.rawPath + "?" + ex.requestURI.rawQuery
            val body = "ok".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        val result = request(openCtx, port, "/echo%20me?x=1") as Value.ProductV
        assertEquals(Value.IntV(200), result.fields["status"])
        assertEquals(1, hits.get())
        assertEquals(listOf("/echo%20me?x=1"), seen)
    }
}
