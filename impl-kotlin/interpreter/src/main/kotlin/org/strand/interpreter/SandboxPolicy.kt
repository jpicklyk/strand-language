package org.strand.interpreter

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Q-041: host-configured policy that mediates every `Fs.*`, `Net.Connect`,
 * and `Http.Request` foreign call at the builtin layer. See
 * `proposals/implemented/io-builtin-sandboxing.md`.
 *
 * The policy is checked at the foreign-call boundary, inside each I/O
 * builtin's [Builtins.Fn] body, AFTER Q-039's capability check has fired
 * and BEFORE the JVM call. Two separate defenses, two separate gates:
 *
 *  - Q-039 closes the *capability* attack — the value passed to
 *    `checkCapabilities` is bound by structure to the value the foreign
 *    code receives.
 *  - Q-041 closes the *argument* attack — even when the capability
 *    permits the operation, the runtime refuses if the resource named
 *    by the argument is out-of-policy (path traversal, SSRF, etc.).
 *
 * Both `Fs.*` paths and `Net.Connect` / `Http.Request` host strings
 * pass through their respective resolver / checker; failure becomes a
 * runtime [SandboxViolation] exception that the interpreter catches at
 * the `applyForeign` site and translates to [InterpretError.SandboxViolation]
 * carrying the call-site NodeId.
 *
 * Sandboxing is **runtime policy, not a graph property** (§ 5 of the
 * proposal). The verifier does not see it; the canonical encoding does
 * not record it. Two evaluations of the same canonical graph under
 * different policies produce different results — which is the point.
 */
data class SandboxPolicy(
    val fs: FsPolicy,
    val net: NetPolicy,
) {
    companion object {
        /**
         * Default IP-range blocklist per the OWASP SSRF Prevention
         * Cheat Sheet plus the cloud-metadata literals identified in
         * the audit. Ranges are checked against every resolved
         * address (every A record for a multi-A hostname).
         */
        val SECURE_DEFAULT_BLOCKED_RANGES: List<IpRange> = listOf(
            // IPv4 loopback
            IpRange("127.0.0.0/8"),
            // IPv6 loopback
            IpRange("::1/128"),
            // RFC1918 private ranges
            IpRange("10.0.0.0/8"),
            IpRange("172.16.0.0/12"),
            IpRange("192.168.0.0/16"),
            // Link-local
            IpRange("169.254.0.0/16"),
            IpRange("fe80::/10"),
            // IPv6 ULA
            IpRange("fc00::/7"),
            // Multicast
            IpRange("224.0.0.0/4"),
            IpRange("ff00::/8"),
            // IPv4 broadcast
            IpRange("255.255.255.255/32"),
            // Cloud-metadata literals (also covered by 169.254.0.0/16
            // above, but listed explicitly so a future operator who
            // relaxes the link-local block keeps the metadata block.)
            IpRange("169.254.169.254/32"),  // AWS / GCP / Azure IMDS
            IpRange("169.254.170.2/32"),    // AWS ECS task-role
            IpRange("100.100.100.200/32"),  // Alibaba
        )

        /**
         * Hostname blocklist matching the cloud-metadata IP literals
         * above. Compose with the IP-range check: the IP check denies
         * any connect *to* the address regardless of the name used;
         * this list denies any DNS query *for* the name regardless of
         * what IP it resolves to.
         */
        val SECURE_DEFAULT_BLOCKED_HOSTNAMES: Set<String> = setOf(
            "metadata.google.internal",
            "metadata.azure.com",
            "metadata",
        )

        /**
         * **Open default — opt-out.** No filesystem workspace constraint,
         * no network default-deny, no blocked ranges. Used by the
         * singleton on [Builtins.sandboxPolicy] so library and test
         * callers that exercise `Fs.*` / `Net.Connect` / `Http.Request`
         * against `@TempDir` paths or local sockets see the pre-Q-041
         * behaviour unchanged. CLI invocations from agents override
         * this with [SECURE_DEFAULT] (or a custom flag-driven policy)
         * at startup.
         *
         * The deliberate inversion — "open" as the singleton default,
         * "secure" as the CLI default — keeps the 895-test pre-Q-041
         * baseline running unchanged while still establishing the
         * agent-facing surface as default-deny. The sandbox-aware
         * test files (FsSandboxTest, NetSandboxTest, HttpSandboxTest)
         * install [SECURE_DEFAULT] or a custom policy in `@BeforeEach`
         * and reset to [OPEN_DEFAULT] in `@AfterEach`.
         */
        val OPEN_DEFAULT = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = null, escape = EscapePolicy.Allow, followSymlinks = true),
            net = NetPolicy(defaultDeny = false, allowedHosts = emptyList(), blockedRanges = emptyList()),
        )

        /**
         * **Secure default — the policy the CLI installs by default.**
         * Workspace rooted at the JVM working directory with escape
         * detection and symlink rejection; network default-deny on
         * loopback, RFC1918, link-local, multicast, broadcast, IPv6
         * ULA, and the cloud-metadata literals; DNS pin-at-check.
         *
         * The CLI's `--workspace-root`, `--allow-fs-escape`,
         * `--allow-host`, and `--allow-net-internal` flags relax
         * this default; absent flags inherit from here.
         */
        val SECURE_DEFAULT = SandboxPolicy(
            fs = FsPolicy(
                workspaceRoot = Paths.get(System.getProperty("user.dir", ".")),
                escape = EscapePolicy.Deny,
                followSymlinks = false,
            ),
            net = NetPolicy(
                defaultDeny = true,
                allowedHosts = emptyList(),
                blockedRanges = SECURE_DEFAULT_BLOCKED_RANGES,
                blockedHostnames = SECURE_DEFAULT_BLOCKED_HOSTNAMES,
                dnsPolicy = DnsPolicy.PinAtCheck,
            ),
        )
    }
}

/**
 * Filesystem sandbox policy.
 *
 * @property workspaceRoot if non-null, every resolved path must lie
 *   lexically beneath this directory after canonicalisation. The
 *   resolver applies `..`-normalisation, then re-checks containment.
 *   Null disables fs sandboxing entirely (test / library default).
 * @property escape behaviour when a path resolves outside [workspaceRoot]:
 *   [EscapePolicy.Deny] raises [SandboxViolation]; [EscapePolicy.Allow]
 *   passes through.
 * @property followSymlinks when false (secure default), the resolver
 *   refuses any path whose canonical form differs from its lexical
 *   form due to a symlink component. When true, follow the symlink and
 *   re-check containment against the link target.
 */
data class FsPolicy(
    val workspaceRoot: Path?,
    val escape: EscapePolicy,
    val followSymlinks: Boolean = false,
)

enum class EscapePolicy { Allow, Deny }

/**
 * Network sandbox policy.
 *
 * @property defaultDeny when true, the IP-range and hostname blocklists
 *   below are enforced; when false, every host is admitted. The toggle
 *   exists so the test default ([SandboxPolicy.OPEN_DEFAULT]) can opt
 *   out wholesale without separately clearing the blocklists.
 * @property allowedHosts non-empty list narrows the policy to only
 *   permit hosts matching one of these glob patterns (`*.example.com`
 *   etc.); empty list means "no allowlist gate, only the blocklist
 *   filters." Matched against the *original* host argument string,
 *   pre-DNS — so a friendly hostname can be allowlisted even when its
 *   IPs change.
 * @property blockedRanges IP ranges denied regardless of allowlist.
 *   The check fires against every resolved address (defeats multi-A
 *   SSRF) plus the original argument if it is an IP literal.
 * @property blockedHostnames hostnames denied regardless of the IPs
 *   they resolve to. Matched case-insensitively against the original
 *   host argument before any DNS resolution.
 * @property dnsPolicy how to handle the gap between policy-time DNS
 *   resolution and connect-time DNS resolution. Default
 *   [DnsPolicy.PinAtCheck]: resolve once, pass the resolved IP to the
 *   JVM `Socket(InetAddress, port)` constructor so the second
 *   resolution cannot subvert the check.
 */
data class NetPolicy(
    val defaultDeny: Boolean = true,
    val allowedHosts: List<HostPattern> = emptyList(),
    val blockedRanges: List<IpRange> = SandboxPolicy.SECURE_DEFAULT_BLOCKED_RANGES,
    val blockedHostnames: Set<String> = SandboxPolicy.SECURE_DEFAULT_BLOCKED_HOSTNAMES,
    val dnsPolicy: DnsPolicy = DnsPolicy.PinAtCheck,
)

enum class DnsPolicy { PinAtCheck, RecheckAtConnect, RequireIpLiteral }

/**
 * Pluggable name resolver used by [NetSandbox]. The production
 * implementation calls [InetAddress.getAllByName]; sandbox tests
 * install a deterministic in-memory resolver to exercise the
 * multi-A-record SSRF path without needing real DNS.
 */
fun interface NameResolver {
    fun resolve(host: String): Array<InetAddress>
}

/** Default name resolver — delegates to the JVM. */
object SystemNameResolver : NameResolver {
    override fun resolve(host: String): Array<InetAddress> =
        InetAddress.getAllByName(host)
}

/**
 * Glob-style host pattern. Translates `*` to a regex `.*` so patterns
 * like `*.example.com` match `api.example.com` and `foo.example.com`
 * but not `evil.com`. Match is case-insensitive — DNS is case-folding
 * by design.
 */
class HostPattern(val glob: String) {
    private val regex: Regex = Regex(
        buildString {
            append("^")
            for (c in glob) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.', '+', '(', ')', '[', ']', '{', '}', '|', '\\', '^', '$' -> {
                        append('\\').append(c)
                    }
                    else -> append(c)
                }
            }
            append("$")
        },
        RegexOption.IGNORE_CASE,
    )

    fun matches(host: String): Boolean = regex.matches(host)

    override fun toString(): String = "HostPattern($glob)"
}

/**
 * IPv4 or IPv6 CIDR range with an [InetAddress] membership check.
 * Accepts the standard `<address>/<prefix-length>` format; raises
 * [IllegalArgumentException] at construction on a malformed input.
 *
 * The membership check is prefix-bit comparison: convert both the
 * range's base address and the candidate to a byte array, mask off
 * the low (32 - prefixLen) or (128 - prefixLen) bits, and compare.
 * Mixing IPv4 and IPv6 sides returns false — an IPv4 candidate
 * cannot be inside an IPv6 range and vice versa.
 */
data class IpRange(val cidr: String) {
    private val baseBytes: ByteArray
    private val prefixLen: Int

    init {
        val slash = cidr.indexOf('/')
        require(slash > 0 && slash < cidr.length - 1) {
            "IpRange CIDR must be <addr>/<prefix>, got '$cidr'"
        }
        val addrStr = cidr.substring(0, slash)
        prefixLen = cidr.substring(slash + 1).toIntOrNull()
            ?: throw IllegalArgumentException("IpRange prefix length not an integer: '$cidr'")
        val addr = InetAddress.getByName(addrStr)
        baseBytes = addr.address
        val maxPrefix = baseBytes.size * 8
        require(prefixLen in 0..maxPrefix) {
            "IpRange prefix length $prefixLen out of range [0, $maxPrefix] for '$cidr'"
        }
    }

    fun contains(addr: InetAddress): Boolean {
        val candidate = addr.address
        if (candidate.size != baseBytes.size) return false
        // Compare the first `prefixLen` bits.
        val fullBytes = prefixLen / 8
        val extraBits = prefixLen % 8
        for (i in 0 until fullBytes) {
            if (candidate[i] != baseBytes[i]) return false
        }
        if (extraBits > 0) {
            val mask = (0xFF shl (8 - extraBits)) and 0xFF
            val candMasked = candidate[fullBytes].toInt() and mask
            val baseMasked = baseBytes[fullBytes].toInt() and mask
            if (candMasked != baseMasked) return false
        }
        return true
    }

    override fun toString(): String = "IpRange($cidr)"
}

/**
 * Filesystem-side sandbox enforcer. Each `Fs.*` builtin calls
 * [resolve] on its first argument before invoking the JVM file API.
 *
 * Per § 4.1 of the proposal:
 *  1. If `policy.workspaceRoot` is null, no constraint — return
 *     [Paths.get] of the supplied string. The host opted out.
 *  2. Otherwise resolve the supplied string against the workspace
 *     root. `resolve` against an absolute path discards the root
 *     (which the canonicalisation in step 3 catches).
 *  3. Canonicalise: `toRealPath(NOFOLLOW_LINKS)` when
 *     `followSymlinks=false`, else `toRealPath()`. On a non-existent
 *     target — common for `Fs.Write` to a new file — fall back to
 *     `normalize().toAbsolutePath()`.
 *  4. Check that the canonical form is lexically prefixed by the
 *     canonicalised workspace root. If not: [EscapePolicy.Deny]
 *     raises [SandboxViolation(FsPathEscape)]; [EscapePolicy.Allow]
 *     proceeds.
 *  5. If `followSymlinks=false` and the supplied path's lexical
 *     resolution differs from its canonical form due to symlink
 *     traversal, raise [SandboxViolation(FsSymlinkRejected)].
 *  6. Return the canonical (or lexical fallback) path.
 */
object FsSandbox {
    fun resolve(policy: FsPolicy, supplied: String): Path {
        val workspaceRoot = policy.workspaceRoot
            ?: return Paths.get(supplied)

        // Step 2: lexical resolution. An absolute supplied path
        // discards workspaceRoot here, which step 4 catches.
        val candidate = workspaceRoot.resolve(supplied)

        // Step 3: canonicalise the workspace root once. The root
        // itself must exist (otherwise the policy is misconfigured;
        // we surface that as FsWorkspaceNotConfigured).
        val canonicalRoot = try {
            workspaceRoot.toRealPath()
        } catch (e: java.io.IOException) {
            throw SandboxViolation(
                SandboxViolationKind.FsWorkspaceNotConfigured,
                "workspace root $workspaceRoot does not exist: ${e.message}",
            )
        }

        // Step 5 (interleaved with 3): if symlinks are disallowed,
        // refuse a path whose canonical form requires following a
        // symlink. We detect by computing both the symlink-following
        // canonical form and the symlink-rejecting canonical form
        // and comparing; a difference means a symlink is involved.
        // Path may not yet exist (Fs.Write to a new file) — fall
        // back to lexical normalisation in that case.
        val canonicalCandidate: Path = try {
            if (policy.followSymlinks) {
                candidate.toRealPath()
            } else {
                val noFollow = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS)
                val follow = candidate.toRealPath()
                if (follow != noFollow) {
                    throw SandboxViolation(
                        SandboxViolationKind.FsSymlinkRejected,
                        "path '$supplied' resolves through a symlink (forbidden when followSymlinks=false)",
                    )
                }
                noFollow
            }
        } catch (_: java.nio.file.NoSuchFileException) {
            // Common path for Fs.Write of a new file: the leaf does
            // not yet exist. Lexically normalise so the containment
            // check below still sees a deterministic absolute path.
            // We must additionally check whether *any prefix* of the
            // path is a symlink (a non-leaf symlink would let a write
            // land outside the workspace even though the leaf is new).
            checkNoSymlinkInPrefix(candidate, policy)
            candidate.normalize().toAbsolutePath()
        } catch (e: java.io.IOException) {
            throw SandboxViolation(
                SandboxViolationKind.FsPathEscape,
                "path '$supplied' could not be canonicalised: ${e.message}",
            )
        }

        // Step 4: containment check.
        val withinRoot = canonicalCandidate.startsWith(canonicalRoot)
        if (!withinRoot) {
            if (policy.escape == EscapePolicy.Deny) {
                throw SandboxViolation(
                    SandboxViolationKind.FsPathEscape,
                    "path '$supplied' resolves to '$canonicalCandidate' which escapes workspace '$canonicalRoot'",
                )
            }
            // EscapePolicy.Allow: pass through.
        }

        return canonicalCandidate
    }

    /**
     * When a path's leaf does not exist (Fs.Write of a new file),
     * canonicalisation falls back to lexical normalisation. Lexical
     * normalisation doesn't catch a symlink at a parent directory.
     * Walk the path upward and reject if any *existing* prefix is a
     * symlink.
     */
    private fun checkNoSymlinkInPrefix(candidate: Path, policy: FsPolicy) {
        if (policy.followSymlinks) return
        var p: Path? = candidate.normalize().parent
        while (p != null) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) &&
                Files.isSymbolicLink(p)
            ) {
                throw SandboxViolation(
                    SandboxViolationKind.FsSymlinkRejected,
                    "path '$candidate' has a symlink at prefix '$p' (forbidden when followSymlinks=false)",
                )
            }
            p = p.parent
        }
    }
}

/**
 * Network-side sandbox enforcer. `Net.Connect` and `Http.Request`
 * call [checkConnect] with the (host, port) pair before the JVM
 * socket / HTTP connection opens.
 *
 * Per § 4.2 of the proposal:
 *  1. Decide whether `host` is an IP literal or a hostname.
 *  2. Hostname blocklist check (against the *original* host string
 *     before any DNS) — denies cloud-metadata hostnames regardless
 *     of resolution.
 *  3. Hostname allowlist check (when [NetPolicy.allowedHosts] is
 *     non-empty) — denies any host not matched by a glob.
 *  4. Resolve the host through [resolver] and check every resolved
 *     address against [NetPolicy.blockedRanges] — defeats multi-A
 *     SSRF.
 *  5. Return the resolved [InetAddress] for the caller to pass to
 *     `Socket(InetAddress, port)` (pin-at-check).
 *
 * Honours [NetPolicy.defaultDeny]: when false, blocked ranges and
 * blocked hostnames are not enforced (test mode).
 *
 * @param resolver injectable for tests; defaults to [SystemNameResolver].
 */
object NetSandbox {
    fun checkConnect(
        policy: NetPolicy,
        host: String,
        port: Int,
        resolver: NameResolver = SystemNameResolver,
    ): InetAddress {
        // The `defaultDeny = false` test policy disables every check,
        // returning whatever DNS resolves to. This is the test-mode
        // bypass for `BuiltinsIoTest` and similar that connect to
        // local sockets on 127.0.0.1.
        if (!policy.defaultDeny) {
            return resolver.resolve(host).first()
        }

        // RequireIpLiteral mode: refuse anything that isn't already
        // an IP literal. Otherwise resolve and check.
        val isIpLiteral = isIpLiteral(host)
        if (policy.dnsPolicy == DnsPolicy.RequireIpLiteral && !isIpLiteral) {
            throw SandboxViolation(
                SandboxViolationKind.NetHostnameRejected,
                "policy requires IP literal but got hostname '$host'",
            )
        }

        // Hostname blocklist — matches the original host string before
        // any DNS. The lookup keys are lower-cased; the comparison is
        // case-insensitive because DNS is case-folding.
        val hostLower = host.lowercase()
        if (!isIpLiteral && hostLower in policy.blockedHostnames) {
            throw SandboxViolation(
                SandboxViolationKind.NetHostBlocked,
                "host '$host' is in the default blocklist (cloud-metadata or similar)",
            )
        }

        // Hostname allowlist — only enforced when non-empty.
        if (policy.allowedHosts.isNotEmpty()) {
            val matched = policy.allowedHosts.any { it.matches(host) }
            if (!matched) {
                throw SandboxViolation(
                    SandboxViolationKind.NetHostNotAllowlisted,
                    "host '$host' is not in the allowlist " +
                        "(${policy.allowedHosts.joinToString(", ") { it.glob }})",
                )
            }
        }

        // Resolve via the injected resolver. The JVM may return
        // multiple A records for a hostname; every one must pass
        // the IP-range check. This is the multi-A SSRF defence.
        val resolved = try {
            resolver.resolve(host)
        } catch (e: java.net.UnknownHostException) {
            throw SandboxViolation(
                SandboxViolationKind.NetHostBlocked,
                "host '$host' could not be resolved: ${e.message}",
            )
        }
        if (resolved.isEmpty()) {
            throw SandboxViolation(
                SandboxViolationKind.NetHostBlocked,
                "host '$host' resolved to no addresses",
            )
        }
        for (addr in resolved) {
            val blocked = policy.blockedRanges.firstOrNull { it.contains(addr) }
            if (blocked != null) {
                throw SandboxViolation(
                    SandboxViolationKind.NetHostBlocked,
                    "host '$host' resolves to ${addr.hostAddress} which is in blocked range $blocked",
                )
            }
        }

        // Return the first resolved address — that's the one the
        // caller will pin into Socket(InetAddress, port). All
        // returned addresses passed the blocklist check, so this
        // choice is safe regardless of which one we hand back.
        return resolved.first()
    }

    /**
     * True when [host] parses as an IPv4 or IPv6 literal (with or
     * without brackets / zone id). We use the JVM's own parser to
     * decide — anything `InetAddress` accepts as a literal without
     * DNS qualifies.
     */
    private fun isIpLiteral(host: String): Boolean {
        // Strip surrounding brackets for IPv6 literals like "[::1]".
        val stripped = if (host.startsWith("[") && host.endsWith("]")) {
            host.substring(1, host.length - 1)
        } else host
        // Fast pre-check: contains a `:` (IPv6) or is all-digits-and-dots (IPv4).
        if (':' in stripped) return true
        if (stripped.isNotEmpty() && stripped.all { it.isDigit() || it == '.' }) {
            // Could still be invalid (e.g. "1.2.3"); leave the final
            // call to InetAddress.getByName which won't do DNS for a
            // syntactically-valid literal.
            return try {
                InetAddress.getByName(stripped)
                true
            } catch (_: java.net.UnknownHostException) {
                false
            }
        }
        return false
    }
}
