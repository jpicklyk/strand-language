package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Q-045 — streaming and asynchronous I/O. Drives the streaming builtins
 * directly off the [Builtins] registry with an injected
 * [LlmHttpClient] streaming transport (no network) for the LLM path and a
 * local loopback [java.net.ServerSocket] for the socket path. Covers the
 * proposal § 7 scenarios that live at the interpreter / builtin layer:
 * happy-path drain (1), Option-EOF (2), receive-after-close (3),
 * kind-mismatch (4), mid-stream transport failure (9), double-close
 * idempotence (10), plus the default `openStream` fallback that lets a
 * post-only mock transport serve a streaming response. The verifier /
 * capability / sandbox / wall-clock scenarios (5–8) are exercised at
 * their own layers (the effect closure is the existing E-004 machinery;
 * the corpus exemplar positively exercises the happy-path closure).
 */
class StreamingReceiveTest {

    private val savedClient = Builtins.llmHttpClient
    private val savedCredentials = Builtins.credentialProvider

    @BeforeEach
    fun setUp() {
        CredentialScrubber.resetForTesting()
        ResourceTable.resetForTest()
        Builtins.credentialProvider = StaticCredentialProvider(
            mapOf("anthropic" to "sk-test-key", "openai" to "sk-test-openai", "gemini" to "test-gemini"),
        )
    }

    @AfterEach
    fun tearDown() {
        Builtins.llmHttpClient = savedClient
        Builtins.credentialProvider = savedCredentials
        ResourceTable.resetForTest()
        CredentialScrubber.resetForTesting()
    }

    // --- test transports -------------------------------------------------

    /** A drainable stub stream over a fixed chunk list; EOF is `null`. */
    private class StubStream(
        chunks: List<ByteArray>,
        private val throwAfter: Int = -1,
    ) : LlmHttpClient.LlmStream {
        private val queue = ArrayDeque(chunks)
        private var reads = 0
        var closed = false
        override fun read(maxBytes: Int): ByteArray? {
            if (throwAfter in 0..reads) throw java.io.IOException("simulated mid-stream failure")
            reads++
            return queue.removeFirstOrNull()
        }
        override fun close() { closed = true }
    }

    /** Streaming-only transport: [post] is unused; [openStream] serves a stub. */
    private class StreamingStubClient(val stream: StubStream) : LlmHttpClient {
        override fun post(url: String, headers: List<Pair<String, String>>, body: ByteArray) =
            error("post() must not be called on the streaming path")
        override fun openStream(url: String, headers: List<Pair<String, String>>, body: ByteArray) = stream
    }

    private fun receiveLlm(handle: Value, maxBytes: Long): Value =
        Builtins.lookup("strand-builtin:LLM.Stream.Receive")!!.invoke(listOf(handle, Value.IntV(maxBytes)))

    private fun some(v: Value): ByteArray {
        val sum = v as Value.SumV
        assertEquals("Some", sum.case)
        return (sum.payload as Value.BytesV).v
    }

    private fun assertNone(v: Value) {
        val sum = v as Value.SumV
        assertEquals("None", sum.case)
        assertNull(sum.payload)
    }

    // --- scenario 1: LLM streaming happy path ----------------------------

    @Test
    fun `LLM stream drains Some per chunk then None, Close releases`() {
        val stub = StubStream(listOf("alpha".toByteArray(), "beta".toByteArray(), "gamma".toByteArray()))
        Builtins.llmHttpClient = StreamingStubClient(stub)

        val req = LlmTestSupport.simpleRequest("claude-opus-4-8", "stream please")
        val handle = Builtins.lookup("strand-builtin:Anthropic.Messages.CreateStream")!!
            .invoke(listOf(req)) as Value.Resource
        assertEquals(ResourceTable.KIND_LLM_STREAM, handle.kind)

        assertArrayEquals("alpha".toByteArray(), some(receiveLlm(handle, 4096)))
        assertArrayEquals("beta".toByteArray(), some(receiveLlm(handle, 4096)))
        assertArrayEquals("gamma".toByteArray(), some(receiveLlm(handle, 4096)))
        assertNone(receiveLlm(handle, 4096))

        val closed = Builtins.lookup("strand-builtin:LLM.Stream.Close")!!.invoke(listOf(handle))
        assertEquals(Value.UnitV, closed)
        assertTrue(stub.closed, "Close must release the underlying stream")
    }

    // --- scenario 2: Option-EOF over a real socket -----------------------

    @Test
    fun `Net_Stream_Receive yields Some then None at EOF, distinct from Some empty`() {
        val server = java.net.ServerSocket(0)
        try {
            val port = server.localPort
            val payload = "socket-bytes".toByteArray(Charsets.UTF_8)
            val writerThread = Thread {
                server.accept().use { client ->
                    client.getOutputStream().write(payload)
                    client.getOutputStream().flush()
                    // Close to signal EOF to the reader.
                }
            }
            writerThread.start()

            val connect = Builtins.lookup("strand-builtin:Net.Connect")!!
            val streamReceive = Builtins.lookup("strand-builtin:Net.Stream.Receive")!!
            val close = Builtins.lookup("strand-builtin:Net.Close")!!

            val handle = connect.invoke(
                listOf(Value.StringV("127.0.0.1"), Value.IntV(port.toLong())),
            ) as Value.Resource

            // A zero-length read is a Some(empty) short read — NOT EOF.
            val short = streamReceive.invoke(listOf(handle, Value.IntV(0L))) as Value.SumV
            assertEquals("Some", short.case)
            assertEquals(0, (short.payload as Value.BytesV).v.size)

            // Drain the payload (loopback delivers it atomically for this size).
            assertArrayEquals(
                payload,
                some(streamReceive.invoke(listOf(handle, Value.IntV(1024L)))),
            )

            // Peer has closed → EOF is None, distinct from the Some(empty) above.
            assertNone(streamReceive.invoke(listOf(handle, Value.IntV(1024L))))

            close.invoke(listOf(handle))
            writerThread.join(2000)
        } finally {
            server.close()
        }
    }

    // --- scenario 3: receive after close ---------------------------------

    @Test
    fun `LLM_Stream_Receive after Close raises resource-not-found`() {
        Builtins.llmHttpClient = StreamingStubClient(StubStream(listOf("x".toByteArray())))
        val handle = Builtins.lookup("strand-builtin:Anthropic.Messages.CreateStream")!!
            .invoke(listOf(LlmTestSupport.simpleRequest("m", "hi"))) as Value.Resource
        Builtins.lookup("strand-builtin:LLM.Stream.Close")!!.invoke(listOf(handle))

        val ex = assertThrows<IoFailure> { receiveLlm(handle, 4096) }
        assertEquals("resource-not-found", ex.kind)
    }

    // --- scenario 4: kind mismatch ---------------------------------------

    @Test
    fun `LLM_Stream_Receive on a socket handle raises resource-kind-mismatch`() {
        val socketHandle = ResourceTable.register(ResourceTable.KIND_SOCKET, java.net.Socket())
        val ex = assertThrows<IoFailure> { receiveLlm(socketHandle, 4096) }
        assertEquals("resource-kind-mismatch", ex.kind)
    }

    // --- scenario 9: mid-stream transport failure ------------------------

    @Test
    fun `LLM_Stream_Receive surfaces IoFailure on mid-stream transport error`() {
        val stub = StubStream(listOf("one".toByteArray(), "two".toByteArray()), throwAfter = 2)
        Builtins.llmHttpClient = StreamingStubClient(stub)
        val handle = Builtins.lookup("strand-builtin:Anthropic.Messages.CreateStream")!!
            .invoke(listOf(LlmTestSupport.simpleRequest("m", "hi"))) as Value.Resource

        assertArrayEquals("one".toByteArray(), some(receiveLlm(handle, 4096)))
        assertArrayEquals("two".toByteArray(), some(receiveLlm(handle, 4096)))
        val ex = assertThrows<IoFailure> { receiveLlm(handle, 4096) }
        assertEquals("llm-stream-receive", ex.kind)
    }

    // --- scenario 10: double close idempotent ----------------------------

    @Test
    fun `LLM_Stream_Close is idempotent`() {
        Builtins.llmHttpClient = StreamingStubClient(StubStream(listOf("a".toByteArray())))
        val close = Builtins.lookup("strand-builtin:LLM.Stream.Close")!!
        val handle = Builtins.lookup("strand-builtin:Anthropic.Messages.CreateStream")!!
            .invoke(listOf(LlmTestSupport.simpleRequest("m", "hi"))) as Value.Resource
        assertEquals(Value.UnitV, close.invoke(listOf(handle)))
        // Second close (now an unknown id) is a no-op.
        assertEquals(Value.UnitV, close.invoke(listOf(handle)))
    }

    // --- default openStream fallback -------------------------------------

    @Test
    fun `default openStream serves a post-only transport body as chunks`() {
        // A transport that only implements post() inherits the default
        // openStream, which serves the buffered body in maxBytes-bounded
        // chunks then EOF — so existing mocks support streaming unchanged.
        val recording = RecordingHttpClient().apply {
            canned = LlmHttpClient.HttpResponse(200, "0123456789".toByteArray())
        }
        Builtins.llmHttpClient = recording
        val handle = Builtins.lookup("strand-builtin:OpenAI.Chat.CompletionsStream")!!
            .invoke(listOf(LlmTestSupport.simpleRequest("gpt", "hi"))) as Value.Resource

        assertArrayEquals("0123".toByteArray(), some(receiveLlm(handle, 4)))
        assertArrayEquals("4567".toByteArray(), some(receiveLlm(handle, 4)))
        assertArrayEquals("89".toByteArray(), some(receiveLlm(handle, 4)))
        assertNone(receiveLlm(handle, 4))
    }
}
