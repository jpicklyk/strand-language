package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Stdlib expansion round 3 phase 4 — HTTP server. The test starts a
 * server on an ephemeral port, sends a real HTTP request from a
 * separate thread via Java's HttpClient, has the test thread drive
 * Http.Accept / Http.Respond, and asserts the round-trip.
 */
class BuiltinsHttpServerTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    /** Find an unused TCP port by opening + immediately closing a ServerSocket. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `Http_Listen Accept Respond round-trip with a real client`() {
        val port = freePort()
        val server = lookup("strand-builtin:Http.Listen").invoke(listOf(Value.IntV(port.toLong()))) as Value.Resource

        // Fire the client from a separate thread; it will block until the
        // test thread calls Http.Accept + Http.Respond.
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val responseFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            val request = HttpRequest.newBuilder(URI("http://localhost:$port/greet?name=Jeff"))
                .POST(HttpRequest.BodyPublishers.ofString("hello server"))
                .build()
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }

        try {
            val req = lookup("strand-builtin:Http.Accept").invoke(listOf(server)) as Value.ProductV
            assertEquals("POST", (req.fields.getValue("method") as Value.StringV).v)
            // path includes the query string.
            assertTrue((req.fields.getValue("path") as Value.StringV).v.startsWith("/greet"))
            assertEquals("hello server", String((req.fields.getValue("body") as Value.BytesV).v))
            val responder = req.fields.getValue("responder")
            assertNotNull(responder as? Value.Resource)

            lookup("strand-builtin:Http.Respond").invoke(listOf(
                responder, Value.IntV(200L), Value.BytesV("hi Jeff".toByteArray()),
            ))

            val response = responseFuture.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(200, response.statusCode())
            assertEquals("hi Jeff", response.body())
        } finally {
            lookup("strand-builtin:Http.ServerClose").invoke(listOf(server))
        }
    }

    @Test
    fun `Http_ServerClose is idempotent`() {
        val port = freePort()
        val server = lookup("strand-builtin:Http.Listen").invoke(listOf(Value.IntV(port.toLong())))
        // First close — succeeds.
        assertEquals(Value.UnitV, lookup("strand-builtin:Http.ServerClose").invoke(listOf(server)))
        // Second close on the same handle is a no-op (ResourceTable.remove
        // returned null on the second call; the builtin tolerates that).
        assertEquals(Value.UnitV, lookup("strand-builtin:Http.ServerClose").invoke(listOf(server)))
    }

    @Test
    fun `Http_Listen on a busy port surfaces IoFailure`() {
        // Hold a port open via a bare ServerSocket, then try to bind
        // the JDK HttpServer to the same port. Should fail with IoFailure.
        val port = freePort()
        val blocker = ServerSocket()
        blocker.reuseAddress = false
        blocker.bind(InetSocketAddress(port))
        try {
            val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
                lookup("strand-builtin:Http.Listen").invoke(listOf(Value.IntV(port.toLong())))
            }
            assertEquals("http-listen", ex.kind)
        } finally {
            blocker.close()
        }
    }
}
