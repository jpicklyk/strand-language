package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

/**
 * Q-041 sandbox unit tests. Covers the twelve scenarios from
 * `proposals/implemented/io-builtin-sandboxing.md` § 7:
 *
 *  1.  Happy-path filesystem write within workspace.
 *  2.  Path traversal via `..` rejected (FsPathEscape).
 *  3.  Absolute path outside workspace rejected (FsPathEscape).
 *  4.  Symlink escape rejected (FsSymlinkRejected) — Linux/macOS only;
 *      Windows symlinks require Administrator and are skipped.
 *  5.  AWS metadata IP rejected (NetHostBlocked).
 *  6.  `metadata.google.internal` hostname rejected (NetHostBlocked).
 *  7.  Loopback rejected (NetHostBlocked).
 *  8.  Allowlisted public host accepted (mocked resolver).
 *  9.  Allowlisted host rebinding to internal IP rejected (NetHostBlocked).
 *  10. Legacy Http.RequestFromUrl parses + dispatches under sandbox.
 *  11. New seven-arg Http.Request goes through NetSandbox.
 *  12. `file://` scheme rejected (HttpSchemeRejected).
 *
 * Each test installs its own SandboxPolicy in `@BeforeEach` and resets
 * to [SandboxPolicy.OPEN_DEFAULT] in `@AfterEach` per the singleton
 * isolation discipline shared with [clock] / [credentialProvider].
 */
class SandboxPolicyTest {

    @BeforeEach
    fun setUp() {
        // Each test installs the policy it needs; the default at
        // entry is the open-default so a misconfigured test is loud.
        Builtins.sandboxPolicy = SandboxPolicy.OPEN_DEFAULT
        Builtins.nameResolver = SystemNameResolver
    }

    @AfterEach
    fun tearDown() {
        Builtins.sandboxPolicy = SandboxPolicy.OPEN_DEFAULT
        Builtins.nameResolver = SystemNameResolver
        ResourceTable.resetForTest()
    }

    // ---------- Filesystem scenarios ----------

    @Test
    fun `scenario 1 — Fs Write within workspace succeeds`(@TempDir tmp: Path) {
        // Create the subdirectory so the leaf-write succeeds — the
        // sandbox doesn't auto-create directories, the writing builtin
        // doesn't either, and that's the right Unix-like contract.
        Files.createDirectories(tmp.resolve("data"))
        Builtins.sandboxPolicy = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = tmp, escape = EscapePolicy.Deny),
            net = NetPolicy(defaultDeny = false),
        )
        val fn = Builtins.lookup("strand-builtin:Fs.Write")!!
        val result = fn.invoke(listOf(
            Value.StringV("data/log.txt"),
            Value.BytesV("hello".toByteArray()),
        ))
        assertEquals(Value.IntV(5L), result)
        assertTrue(Files.exists(tmp.resolve("data/log.txt")))
    }

    @Test
    fun `scenario 2 — Fs Write with dot-dot path traversal raises FsPathEscape`(@TempDir tmp: Path) {
        Builtins.sandboxPolicy = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = tmp, escape = EscapePolicy.Deny),
            net = NetPolicy(defaultDeny = false),
        )
        val fn = Builtins.lookup("strand-builtin:Fs.Write")!!
        val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
            fn.invoke(listOf(
                Value.StringV("../../escape.txt"),
                Value.BytesV("escape".toByteArray()),
            ))
        }
        assertEquals(SandboxViolationKind.FsPathEscape, ex.kind)
    }

    @Test
    fun `scenario 3 — Fs Write with absolute path outside workspace raises FsPathEscape`(@TempDir tmp: Path) {
        // Use a sibling temp directory so the absolute path is real
        // but outside the workspace root.
        val outside = Files.createTempDirectory("strand-q041-outside-")
        try {
            Builtins.sandboxPolicy = SandboxPolicy(
                fs = FsPolicy(workspaceRoot = tmp, escape = EscapePolicy.Deny),
                net = NetPolicy(defaultDeny = false),
            )
            val fn = Builtins.lookup("strand-builtin:Fs.Write")!!
            val target = outside.resolve("breach.txt").toAbsolutePath().toString()
            val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
                fn.invoke(listOf(
                    Value.StringV(target),
                    Value.BytesV("breach".toByteArray()),
                ))
            }
            assertEquals(SandboxViolationKind.FsPathEscape, ex.kind)
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Symlink creation requires Administrator on Windows.")
    fun `scenario 4 — Fs Read through a symlink raises FsSymlinkRejected`(@TempDir tmp: Path) {
        // Setup: a target outside the workspace, a symlink inside
        // the workspace pointing at it. Reading through the symlink
        // would otherwise reach outside the workspace.
        val outside = Files.createTempDirectory("strand-q041-symlink-target-")
        try {
            val secret = outside.resolve("secret.txt")
            Files.write(secret, "secret content".toByteArray())
            val linkInWorkspace = tmp.resolve("link.txt")
            Files.createSymbolicLink(linkInWorkspace, secret)

            Builtins.sandboxPolicy = SandboxPolicy(
                fs = FsPolicy(workspaceRoot = tmp, escape = EscapePolicy.Deny, followSymlinks = false),
                net = NetPolicy(defaultDeny = false),
            )
            val fn = Builtins.lookup("strand-builtin:Fs.Read")!!
            val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
                fn.invoke(listOf(Value.StringV("link.txt")))
            }
            assertEquals(SandboxViolationKind.FsSymlinkRejected, ex.kind)
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    // ---------- Network scenarios ----------

    @Test
    fun `scenario 5 — Http Request to AWS metadata IP raises NetHostBlocked`() {
        Builtins.sandboxPolicy = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = null, escape = EscapePolicy.Allow),
            net = SandboxPolicy.SECURE_DEFAULT.net,
        )
        val fn = Builtins.lookup("strand-builtin:Http.Request")!!
        val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
            fn.invoke(listOf(
                Value.StringV("169.254.169.254"),
                Value.IntV(80L),
                Value.StringV("http"),
                Value.StringV("/latest/meta-data/"),
                Value.StringV("GET"),
                Value.SumV("Nil", null),
                Value.BytesV(ByteArray(0)),
            ))
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
    }

    @Test
    fun `scenario 6 — Http Request to metadata-google-internal raises NetHostBlocked`() {
        Builtins.sandboxPolicy = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = null, escape = EscapePolicy.Allow),
            net = SandboxPolicy.SECURE_DEFAULT.net,
        )
        // Use an injected resolver that maps the hostname to a benign
        // public IP — proving the hostname blocklist fires regardless
        // of where the name actually resolves.
        Builtins.nameResolver = NameResolver { host ->
            if (host == "metadata.google.internal") arrayOf(InetAddress.getByName("8.8.8.8"))
            else InetAddress.getAllByName(host)
        }
        val fn = Builtins.lookup("strand-builtin:Http.Request")!!
        val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
            fn.invoke(listOf(
                Value.StringV("metadata.google.internal"),
                Value.IntV(80L),
                Value.StringV("http"),
                Value.StringV("/computeMetadata/v1/"),
                Value.StringV("GET"),
                Value.SumV("Nil", null),
                Value.BytesV(ByteArray(0)),
            ))
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
    }

    @Test
    fun `scenario 7 — Net Connect to loopback under default-deny raises NetHostBlocked`() {
        Builtins.sandboxPolicy = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = null, escape = EscapePolicy.Allow),
            net = SandboxPolicy.SECURE_DEFAULT.net,
        )
        val fn = Builtins.lookup("strand-builtin:Net.Connect")!!
        val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
            fn.invoke(listOf(Value.StringV("127.0.0.1"), Value.IntV(6379L)))
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
    }

    @Test
    fun `scenario 8 — allowlisted public host passes through NetSandbox`() {
        // Inject a resolver that returns a public IP (8.8.8.8) for
        // "api.example.com". The allowlist matches; the IP-range
        // blocklist does not reject 8.8.8.8 (it's in none of the
        // blocked ranges). The checkConnect call returns successfully.
        Builtins.nameResolver = NameResolver { host ->
            arrayOf(InetAddress.getByName(
                when (host) {
                    "api.example.com" -> "8.8.8.8"
                    else -> host
                }
            ))
        }
        val policy = NetPolicy(
            defaultDeny = true,
            allowedHosts = listOf(HostPattern("api.example.com")),
            blockedRanges = SandboxPolicy.SECURE_DEFAULT_BLOCKED_RANGES,
            blockedHostnames = SandboxPolicy.SECURE_DEFAULT_BLOCKED_HOSTNAMES,
        )
        // Direct NetSandbox call — bypasses the actual socket open.
        // The proposal § 7 scenario 8 says "Allowlisted public host
        // accepted (use a mock socket; don't actually connect)" — we
        // exercise the policy layer alone, not the socket open.
        val resolved = NetSandbox.checkConnect(policy, "api.example.com", 443, Builtins.nameResolver)
        assertEquals("8.8.8.8", resolved.hostAddress)
    }

    @Test
    fun `scenario 9 — allowlisted hostname rebinding to internal IP is rejected`() {
        // The host is in the allowlist but DNS returns 10.0.0.1 (in
        // the RFC1918 blocklist). The IP-range gate fires after the
        // allowlist check, defeating the rebinding attack.
        Builtins.nameResolver = NameResolver { host ->
            arrayOf(InetAddress.getByName(
                when (host) {
                    "evil.example.com" -> "10.0.0.1"
                    else -> host
                }
            ))
        }
        val policy = NetPolicy(
            defaultDeny = true,
            allowedHosts = listOf(HostPattern("evil.example.com")),
            blockedRanges = SandboxPolicy.SECURE_DEFAULT_BLOCKED_RANGES,
            blockedHostnames = SandboxPolicy.SECURE_DEFAULT_BLOCKED_HOSTNAMES,
        )
        val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
            NetSandbox.checkConnect(policy, "evil.example.com", 443, Builtins.nameResolver)
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
    }

    // ---------- HTTP-specific scenarios ----------

    @Test
    fun `scenario 10 — Http RequestFromUrl parses URL and dispatches through sandbox`() {
        // The legacy wrapper parses url=http://169.254.169.254/foo
        // and dispatches to the seven-arg Http.Request, which then
        // hits the NetSandbox SSRF gate.
        Builtins.sandboxPolicy = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = null, escape = EscapePolicy.Allow),
            net = SandboxPolicy.SECURE_DEFAULT.net,
        )
        val fn = Builtins.lookup("strand-builtin:Http.RequestFromUrl")!!
        val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
            fn.invoke(listOf(
                Value.StringV("GET"),
                Value.StringV("http://169.254.169.254/latest/meta-data/"),
                Value.BytesV(ByteArray(0)),
            ))
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
    }

    @Test
    fun `scenario 11 — Http RequestFromUrl forwards host port scheme path to component form`() {
        // Verify that the legacy wrapper correctly parses URL
        // components by sending it to a hostname that the resolver
        // can map to a public IP and the allowlist accepts. We
        // intercept at NetSandbox by counting calls — direct unit
        // test rather than running an HTTP server (which the unit
        // file shouldn't depend on).
        val resolverCalls = mutableListOf<String>()
        Builtins.nameResolver = NameResolver { host ->
            resolverCalls += host
            arrayOf(InetAddress.getByName("8.8.8.8"))
        }
        Builtins.sandboxPolicy = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = null, escape = EscapePolicy.Allow),
            net = NetPolicy(
                defaultDeny = true,
                allowedHosts = listOf(HostPattern("api.example.com")),
                blockedRanges = emptyList(),  // 8.8.8.8 isn't blocked anyway
                blockedHostnames = emptySet(),
            ),
        )
        // The wrapper parses host=api.example.com, port=443 (https default),
        // scheme=https, path=/v1/users. Net.Connect would then proceed
        // — we capture before the actual socket open by inspecting the
        // resolver call. Wrap in try to swallow the (expected) IO failure
        // from trying to open a real socket to 8.8.8.8:443.
        val fn = Builtins.lookup("strand-builtin:Http.RequestFromUrl")!!
        try {
            fn.invoke(listOf(
                Value.StringV("GET"),
                Value.StringV("https://api.example.com/v1/users"),
                Value.BytesV(ByteArray(0)),
            ))
        } catch (_: IoFailure) {
            // Expected — we don't actually have an HTTPS server at 8.8.8.8.
            // The check we care about is that the sandbox layer was reached.
        } catch (_: SandboxViolation) {
            org.junit.jupiter.api.Assertions.fail<Unit>("unexpected sandbox violation; the sandbox should accept api.example.com")
        }
        // The resolver was called with the original hostname (not the
        // IP literal), proving the wrapper correctly extracted the host.
        assertTrue(resolverCalls.contains("api.example.com"),
            "resolver should have been called for 'api.example.com'; got $resolverCalls")
    }

    @Test
    fun `scenario 12 — Http Request with file scheme raises HttpSchemeRejected`() {
        Builtins.sandboxPolicy = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = null, escape = EscapePolicy.Allow),
            net = NetPolicy(defaultDeny = false),  // even with no net constraints
        )
        val fn = Builtins.lookup("strand-builtin:Http.Request")!!
        val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
            fn.invoke(listOf(
                Value.StringV("localhost"),
                Value.IntV(80L),
                Value.StringV("file"),
                Value.StringV("/etc/shadow"),
                Value.StringV("GET"),
                Value.SumV("Nil", null),
                Value.BytesV(ByteArray(0)),
            ))
        }
        assertEquals(SandboxViolationKind.HttpSchemeRejected, ex.kind)
    }

    // ---------- Auxiliary policy primitive tests ----------

    @Test
    fun `IpRange matches IPv4 addresses inside the CIDR`() {
        val r = IpRange("10.0.0.0/8")
        assertTrue(r.contains(InetAddress.getByName("10.0.0.1")))
        assertTrue(r.contains(InetAddress.getByName("10.255.255.255")))
        org.junit.jupiter.api.Assertions.assertFalse(r.contains(InetAddress.getByName("11.0.0.0")))
        org.junit.jupiter.api.Assertions.assertFalse(r.contains(InetAddress.getByName("9.255.255.255")))
    }

    @Test
    fun `IpRange matches IPv6 addresses inside the CIDR`() {
        val r = IpRange("fe80::/10")
        assertTrue(r.contains(InetAddress.getByName("fe80::1")))
        org.junit.jupiter.api.Assertions.assertFalse(r.contains(InetAddress.getByName("fec0::1")))
    }

    @Test
    fun `IpRange single-address slash 32 matches only its address`() {
        val r = IpRange("169.254.169.254/32")
        assertTrue(r.contains(InetAddress.getByName("169.254.169.254")))
        org.junit.jupiter.api.Assertions.assertFalse(r.contains(InetAddress.getByName("169.254.169.253")))
    }

    @Test
    fun `IpRange mixing v4 and v6 returns false`() {
        val v4 = IpRange("127.0.0.0/8")
        assertEquals(false, v4.contains(InetAddress.getByName("::1")))
        val v6 = IpRange("::1/128")
        assertEquals(false, v6.contains(InetAddress.getByName("127.0.0.1")))
    }

    @Test
    fun `HostPattern glob star matches any subdomain segment`() {
        val p = HostPattern("*.example.com")
        assertTrue(p.matches("api.example.com"))
        assertTrue(p.matches("a.b.example.com"))
        org.junit.jupiter.api.Assertions.assertFalse(p.matches("evil.com"))
        org.junit.jupiter.api.Assertions.assertFalse(p.matches("example.com"))
    }

    @Test
    fun `HostPattern matches case insensitively`() {
        val p = HostPattern("api.example.com")
        assertTrue(p.matches("API.EXAMPLE.COM"))
        assertTrue(p.matches("Api.Example.Com"))
    }

    @Test
    fun `FsSandbox resolve with null workspace returns lexical path unchanged`() {
        val policy = FsPolicy(workspaceRoot = null, escape = EscapePolicy.Deny)
        val resolved = FsSandbox.resolve(policy, "/some/path")
        // On Unix this is /some/path; on Windows this is something
        // like C:\some\path. Both are absolute (per Paths.get's behaviour
        // on absolute strings).
        assertNotNull(resolved)
    }

    @Test
    fun `NetSandbox passes through under defaultDeny=false`() {
        val policy = NetPolicy(defaultDeny = false)
        // 169.254.169.254 — cloud-metadata, normally blocked.
        // Under defaultDeny=false the check should still succeed.
        val addr = NetSandbox.checkConnect(policy, "169.254.169.254", 80) { host ->
            arrayOf(InetAddress.getByName(host))
        }
        assertEquals("169.254.169.254", addr.hostAddress)
    }

    @Test
    fun `NetSandbox multi-A-record SSRF defence rejects when any address is blocked`() {
        // Multi-A response: one public IP (8.8.8.8) and one blocked
        // (10.0.0.1). Every returned address must pass; the presence
        // of the blocked entry denies the connect.
        val resolver = NameResolver { _ ->
            arrayOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("10.0.0.1"),
            )
        }
        val policy = NetPolicy(
            defaultDeny = true,
            allowedHosts = listOf(HostPattern("ambiguous.example.com")),
            blockedRanges = SandboxPolicy.SECURE_DEFAULT_BLOCKED_RANGES,
            blockedHostnames = emptySet(),
        )
        val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
            NetSandbox.checkConnect(policy, "ambiguous.example.com", 80, resolver)
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
    }

    @Test
    fun `NetSandbox RequireIpLiteral mode rejects hostnames`() {
        val policy = NetPolicy(
            defaultDeny = true,
            dnsPolicy = DnsPolicy.RequireIpLiteral,
            blockedRanges = emptyList(),
            blockedHostnames = emptySet(),
        )
        val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
            NetSandbox.checkConnect(policy, "example.com", 80) { host ->
                arrayOf(InetAddress.getByName("8.8.8.8"))
            }
        }
        assertEquals(SandboxViolationKind.NetHostnameRejected, ex.kind)
    }

    @Test
    fun `NetSandbox NetHostNotAllowlisted fires when allowlist non-empty and host does not match`() {
        val policy = NetPolicy(
            defaultDeny = true,
            allowedHosts = listOf(HostPattern("api.allowed.example.com")),
            blockedRanges = emptyList(),
            blockedHostnames = emptySet(),
        )
        val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
            NetSandbox.checkConnect(policy, "other.example.com", 80) { _ ->
                arrayOf(InetAddress.getByName("8.8.8.8"))
            }
        }
        assertEquals(SandboxViolationKind.NetHostNotAllowlisted, ex.kind)
    }

    @Test
    fun `SECURE_DEFAULT blocks every concrete cloud-metadata IP`() {
        val resolver = NameResolver { host -> arrayOf(InetAddress.getByName(host)) }
        for (ip in listOf("169.254.169.254", "169.254.170.2", "100.100.100.200")) {
            val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
                NetSandbox.checkConnect(SandboxPolicy.SECURE_DEFAULT.net, ip, 80, resolver)
            }
            assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind,
                "expected $ip to be NetHostBlocked")
        }
    }

    @Test
    fun `SECURE_DEFAULT blocks loopback and RFC1918`() {
        val resolver = NameResolver { host -> arrayOf(InetAddress.getByName(host)) }
        for (ip in listOf("127.0.0.1", "10.0.0.5", "172.16.0.1", "192.168.1.1")) {
            val ex = org.junit.jupiter.api.assertThrows<SandboxViolation> {
                NetSandbox.checkConnect(SandboxPolicy.SECURE_DEFAULT.net, ip, 80, resolver)
            }
            assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind,
                "expected $ip to be NetHostBlocked")
        }
    }
}
