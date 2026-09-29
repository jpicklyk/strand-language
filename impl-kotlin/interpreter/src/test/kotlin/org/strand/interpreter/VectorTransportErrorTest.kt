package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Review M4: the vector providers never caught `IOException`, so a raw
 * `ConnectException` / `UnknownHostException` (URL in the message)
 * escaped the builtin and `Attempt` could not catch it. Transport I/O
 * failures now surface as a catchable, credential-scrubbed
 * [IoFailure] at the transport boundary.
 */
class VectorTransportErrorTest {

    @BeforeEach
    fun setUp() = CredentialScrubber.resetForTesting()

    @AfterEach
    fun tearDown() {
        ResourceTable.resetForTest()
        CredentialScrubber.resetForTesting()
    }

    @Test
    fun `JdkHttpTransport connection refused is an IoFailure`() {
        val port = java.net.ServerSocket(0).use { it.localPort }  // closed again: nothing listens
        val ex = assertThrows<IoFailure> { JdkHttpTransport.execute(HttpRequest("GET", "http://127.0.0.1:$port/x")) }
        assertEquals("vector-http", ex.kind)
    }

    @Test
    fun `JdkHttpTransport unknown host is an IoFailure`() {
        val ex = assertThrows<IoFailure> { JdkHttpTransport.execute(HttpRequest("GET", "http://no-such-host.invalid/x")) }
        assertEquals("vector-http", ex.kind)
    }

    @Test
    fun `JdkHttpTransport stalled server times out as an IoFailure`() {
        java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).use { s ->
            val transport = BoundedJdkHttpTransport(BuiltinLimits.DEFAULT.copy(readTimeoutMillis = 300))
            val ex = org.junit.jupiter.api.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10)) {
                assertThrows<IoFailure> { transport.execute(HttpRequest("GET", "http://127.0.0.1:${s.localPort}/")) }
            }
            assertEquals("vector-http", ex.kind)
        }
    }

    @Test
    fun `an injected transport's IOException becomes a scrubbed IoFailure through the builtin`() {
        val secret = "pk-very-secret-9876"
        val throwing = object : VectorHttpTransport {
            override fun execute(request: HttpRequest): HttpResponse =
                throw java.net.ConnectException("connect to ${request.url} failed; header Api-Key=$secret")
        }
        val ctx = HostContext.processDefault().copy(
            sandboxPolicy = SandboxPolicy.OPEN_DEFAULT,
            vectorHttpTransport = throwing,
            credentialProvider = InMemoryCredentialProvider(mapOf(("pinecone" to "api_key") to secret)),
        )
        val config = Value.ProductV(mapOf(
            "indexName" to Value.StringV("main"),
            "environment" to Value.StringV("env"),
            "metric" to VectorValueMarshal.metricToValue(VectorMetric.Cosine),
            "dimensions" to Value.IntV(3L),
            "host" to Value.SumV("Some", Value.StringV("http://vector.local:7777")),
        ))
        val handle = Builtins.lookup("strand-builtin:Pinecone.Index.Open")!!.invoke(ctx, listOf(config))
        val ids = Value.SumV("Cons", Value.ProductV(mapOf("head" to Value.StringV("a"), "tail" to Value.SumV("Nil", null))))
        val ex = assertThrows<IoFailure> {
            Builtins.lookup("strand-builtin:Pinecone.Index.Fetch")!!.invoke(ctx, listOf(handle, ids))
        }
        assertEquals("vector-http", ex.kind)
        assertFalse(ex.detail.contains(secret), ex.detail)
        assertTrue(ex.detail.contains("REDACTED"), ex.detail)
    }
}
