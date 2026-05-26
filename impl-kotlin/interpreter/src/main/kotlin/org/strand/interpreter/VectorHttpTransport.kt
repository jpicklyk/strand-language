package org.strand.interpreter

/**
 * Minimal HTTP transport abstraction used by the vector-store provider
 * objects (PineconeProvider, ChromaProvider — Q-038 Phase 1). The
 * provider objects depend on this interface rather than directly on
 * [java.net.HttpURLConnection] so tests can mock the wire layer
 * without spinning up a real HTTP server.
 *
 * The interface is deliberately narrow — just the request/response
 * shape the vector-store providers need. It is not a general HTTP
 * client. Production runtime injects [JdkHttpTransport] (a thin
 * [java.net.HttpURLConnection] wrapper); tests install
 * [InMemoryHttpTransport] or any other implementation that returns
 * canned responses.
 *
 * The interface follows the [Builtins.clock] injection pattern: the
 * active transport is a JVM-wide [Builtins.vectorHttpTransport] var.
 * Thread-safety responsibilities match [Builtins.clock] — tests that
 * mutate must not run in parallel with the providers under test.
 */
interface VectorHttpTransport {
    /**
     * Execute one HTTP request and return the response. Implementations
     * are responsible for any retry/backoff policy; the provider code
     * treats this as a single round trip.
     *
     * Non-2xx responses are returned, not thrown — the provider object
     * inspects [HttpResponse.status] and decides whether to translate
     * to [IoFailure].
     */
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * One HTTP request. Body is bytes; the caller has already serialized
 * any JSON payload. Headers are flat `name -> value` pairs (no multi-
 * value support — none of the vector providers need it).
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return method == other.method &&
            url == other.url &&
            headers == other.headers &&
            body.contentEquals(other.body)
    }
    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

/**
 * One HTTP response. Body is bytes; the caller decodes any JSON.
 * Status is the HTTP status code (200, 404, 429, etc.). Response
 * headers are not surfaced — the providers do not consume any.
 */
data class HttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false
        return status == other.status && body.contentEquals(other.body)
    }
    override fun hashCode(): Int {
        var result = status
        result = 31 * result + body.contentHashCode()
        return result
    }
}

/**
 * Default JDK-backed transport. Uses [java.net.HttpURLConnection]
 * matching the style of `strand-builtin:Http.Request` in
 * [Builtins]. HTTPS via the JVM's default truststore.
 */
object JdkHttpTransport : VectorHttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val url = java.net.URI(request.url).toURL()
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = request.method.uppercase()
        conn.doInput = true
        for ((name, value) in request.headers) {
            conn.setRequestProperty(name, value)
        }
        if (request.body.isNotEmpty()) {
            conn.doOutput = true
            conn.outputStream.use { it.write(request.body) }
        }
        val status = conn.responseCode
        val body = try {
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            stream?.readBytes() ?: ByteArray(0)
        } catch (_: java.io.IOException) {
            ByteArray(0)
        }
        conn.disconnect()
        return HttpResponse(status, body)
    }
}

/**
 * In-memory transport for tests. Constructed with a list of
 * `(matcher, response)` rules; the first matcher that matches a
 * request returns its response. Captures every request for assertion.
 *
 * Unmatched requests throw [IllegalStateException] so tests fail loud
 * rather than masking missing matchers.
 */
class InMemoryHttpTransport(
    private val rules: MutableList<Pair<(HttpRequest) -> Boolean, HttpResponse>> = mutableListOf(),
) : VectorHttpTransport {
    private val _captured = mutableListOf<HttpRequest>()
    val captured: List<HttpRequest> get() = _captured.toList()

    /** Add a matcher + response. Matchers are checked in registration order. */
    fun on(matcher: (HttpRequest) -> Boolean, response: HttpResponse) {
        rules.add(matcher to response)
    }

    /** Convenience: match by exact method + url. */
    fun on(method: String, url: String, response: HttpResponse) {
        on({ it.method.equals(method, ignoreCase = true) && it.url == url }, response)
    }

    /** Convenience: match by method + url-prefix. */
    fun onPrefix(method: String, urlPrefix: String, response: HttpResponse) {
        on({ it.method.equals(method, ignoreCase = true) && it.url.startsWith(urlPrefix) }, response)
    }

    override fun execute(request: HttpRequest): HttpResponse {
        _captured.add(request)
        for ((matcher, response) in rules) {
            if (matcher(request)) return response
        }
        error("no matcher for HTTP request ${request.method} ${request.url}")
    }

    /** Test helper: clear captured-request history without losing matcher rules. */
    fun clearCaptured() {
        _captured.clear()
    }
}
