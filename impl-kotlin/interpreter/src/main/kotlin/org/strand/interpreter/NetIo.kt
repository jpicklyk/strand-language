package org.strand.interpreter

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Shared hardening helpers for the HTTP-speaking builtins (`Http.Request`,
 * the vector-store transports, the LLM transports). Kept out of the
 * [Builtins] map literal so every transport applies the same rules rather
 * than a copy of them.
 */
object NetIo {

    /**
     * Review H1: build the request URI for `Http.Request` from the
     * policy-approved (pinned) address and a program-supplied path.
     *
     * The path is untrusted graph data. Concatenating it after
     * `host:port` let `@169.254.169.254/x` turn the pinned address into
     * userinfo and `81/` turn port 80 into 8081. The path is therefore
     * validated before it is placed in the URI:
     *
     *  - it must start with `/` (so it can only ever be the path
     *    component, never continue the authority);
     *  - it must not contain `@`, `\`, `#`, whitespace, or control
     *    characters;
     *  - every remaining character must be an RFC 3986 path / query
     *    character, and `%` must introduce a two-hex-digit escape.
     *
     * The validated string is then parsed, and the parsed URI's host,
     * port and userinfo are compared with the pinned values before the
     * URI is returned: a URI whose authority is not exactly the pinned
     * address and port is refused even if a future edit weakens the
     * character check. The single-argument parse is used deliberately
     * instead of the seven-argument [URI] constructor, which always
     * re-quotes `%` and so would double-encode an already-escaped path
     * (`/a%20b` would become `/a%2520b`).
     *
     * @throws SandboxViolation with [SandboxViolationKind.HttpPathRejected]
     *   when the path is malformed or the parsed authority does not match.
     */
    fun buildPinnedUri(scheme: String, pinned: InetAddress, port: Int, path: String): URI {
        validateRequestPath(path)
        val hostLiteral = pinned.hostAddress.let { if (pinned is Inet6Address) "[${it.substringBefore('%')}]" else it }
        val uri = try {
            URI("${scheme.lowercase()}://$hostLiteral:$port$path")
        } catch (e: java.net.URISyntaxException) {
            throw SandboxViolation(SandboxViolationKind.HttpPathRejected, "request path is not a valid URI path: ${e.message}")
        }
        val parsedHost = uri.host?.removePrefix("[")?.removeSuffix("]")
        val expectedHost = hostLiteral.removePrefix("[").removeSuffix("]")
        val hostMatches = parsedHost != null && try {
            InetAddress.getByName(parsedHost) == InetAddress.getByName(expectedHost)
        } catch (_: java.net.UnknownHostException) {
            false
        }
        if (!hostMatches || uri.port != port || uri.rawUserInfo != null) {
            throw SandboxViolation(
                SandboxViolationKind.HttpPathRejected,
                "request URI authority '${uri.rawAuthority}' does not match the policy-approved address " +
                    "$expectedHost:$port",
            )
        }
        return uri
    }

    /**
     * The character-level half of [buildPinnedUri], exposed for direct
     * unit testing. Throws [SandboxViolation] ([SandboxViolationKind.HttpPathRejected])
     * on any violation; returns normally otherwise.
     */
    fun validateRequestPath(path: String) {
        fun reject(why: String): Nothing =
            throw SandboxViolation(SandboxViolationKind.HttpPathRejected, "request path rejected: $why")
        if (!path.startsWith("/")) reject("must start with '/'")
        var i = 0
        while (i < path.length) {
            val c = path[i]
            when {
                c == '@' -> reject("'@' is not allowed (it would introduce userinfo)")
                c == '\\' -> reject("'\\' is not allowed")
                c == '#' -> reject("fragments are not allowed")
                c.isWhitespace() || c.isISOControl() -> reject("whitespace or control character at index $i")
                c == '%' -> {
                    if (i + 2 >= path.length || !isHex(path[i + 1]) || !isHex(path[i + 2])) {
                        reject("'%' at index $i is not followed by two hex digits")
                    }
                    i += 2
                }
                !isPathOrQueryChar(c) -> reject("character '${c}' (U+%04X) is not a URI path or query character".format(c.code))
            }
            i++
        }
    }

    /**
     * Review H2: open an [java.net.HttpURLConnection] that never follows
     * redirects. A followed redirect is a second connection to an address
     * the network sandbox never checked (an allow-listed server answering
     * `302 Location: http://169.254.169.254/...`), so every transport opens
     * its connection through here and then calls [rejectRedirect].
     */
    fun openConnection(url: java.net.URL): java.net.HttpURLConnection {
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = false
        return conn
    }

    /**
     * Review H2: a 3xx response other than `304 Not Modified` is a
     * redirect the transport will not follow; it surfaces as a catchable
     * `IoFailure("http-redirect", ...)` carrying the status and the
     * `Location` the server asked for. The connection is disconnected.
     */
    fun rejectRedirect(conn: java.net.HttpURLConnection, status: Int) {
        if (status in 300..399 && status != 304) {
            val location = conn.getHeaderField("Location") ?: "<none>"
            conn.disconnect()
            throw IoFailure(
                "http-redirect",
                "status=$status location=$location (redirects are not followed; the target was not sandbox-checked)",
            )
        }
    }

    /**
     * Review H4: gate a full URL (the vector-store providers build theirs
     * from program-supplied config) through the same [NetSandbox] check
     * `Net.Connect` / `Http.Request` use. The scheme must be `http` or
     * `https` and userinfo is refused under every policy; the host / range
     * / allowlist checks run when [NetPolicy.defaultDeny] is set (the open
     * test policy performs no DNS here).
     *
     * Residual: the providers speak through an [VectorHttpTransport] that
     * takes a URL, so the checked address is not pinned into the
     * connection the way `Http.Request` pins it; the JDK re-resolves at
     * connect. Re-checking every request (see [SandboxedVectorHttpTransport])
     * narrows the rebinding window to the JVM DNS cache interval.
     */
    fun checkUrl(url: String, policy: NetPolicy, resolver: NameResolver) {
        val uri = try {
            URI(url)
        } catch (e: java.net.URISyntaxException) {
            throw SandboxViolation(SandboxViolationKind.HttpPathRejected, "malformed URL: ${e.message}")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw SandboxViolation(
                SandboxViolationKind.HttpSchemeRejected,
                "scheme '${uri.scheme}' is not allowed; expected 'http' or 'https'",
            )
        }
        if (uri.rawUserInfo != null) {
            throw SandboxViolation(SandboxViolationKind.HttpPathRejected, "URL userinfo is not allowed")
        }
        val host = uri.host
            ?: throw SandboxViolation(SandboxViolationKind.NetHostBlocked, "URL '$url' has no host")
        if (!policy.defaultDeny) return
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        NetSandbox.checkConnect(policy, host, port, resolver)
    }

    /**
     * Review M4: the [IoFailure] a vector transport raises for an I/O
     * failure. [IoFailure] scrubs registered credentials from the detail
     * at construction, and the interpreter re-scrubs against the tenant's
     * own scrubber at translation.
     */
    fun vectorIoFailure(request: HttpRequest, e: Exception): IoFailure =
        IoFailure("vector-http", "${request.method} ${request.url}: ${e::class.simpleName}: ${e.message}")

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** RFC 3986 unreserved + sub-delims + `:` `/` `?` (the `@` of pchar is excluded above). */
    private fun isPathOrQueryChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c in "-._~" || c in "!$&'()*+,;=" || c == ':' || c == '/' || c == '?'
}

/**
 * Review H4: a [VectorHttpTransport] decorator that runs
 * [NetIo.checkUrl] on every request before handing it to [delegate], so a
 * program-chosen vector-store host (and the API key attached to the
 * request) never reaches a blocked or non-allow-listed address. The
 * vector builtins wrap the context's transport with this on every call.
 */
class SandboxedVectorHttpTransport(
    private val delegate: VectorHttpTransport,
    private val policy: NetPolicy,
    private val resolver: NameResolver,
) : VectorHttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        NetIo.checkUrl(request.url, policy, resolver)
        // Review M4: whatever transport the host injected, an I/O failure
        // crosses the builtin boundary as a catchable, scrubbed IoFailure.
        return try {
            delegate.execute(request)
        } catch (e: java.io.IOException) {
            throw NetIo.vectorIoFailure(request, e)
        }
    }
}

/** The active context's vector transport behind the network sandbox (review H4). */
internal fun HostContext.sandboxedVectorTransport(): VectorHttpTransport =
    SandboxedVectorHttpTransport(vectorHttpTransport, sandboxPolicy.net, nameResolver)
