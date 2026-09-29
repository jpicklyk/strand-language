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

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** RFC 3986 unreserved + sub-delims + `:` `/` `?` (the `@` of pchar is excluded above). */
    private fun isPathOrQueryChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c in "-._~" || c in "!$&'()*+,;=" || c == ':' || c == '/' || c == '?'
}
