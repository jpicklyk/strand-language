package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review M5: the secure-default network blocklist missed `0.0.0.0/8`,
 * the IPv6 unspecified address, NAT64 (`64:ff9b::/96`), and IPv6 forms
 * that embed a blocked IPv4 address. `DnsPolicy.RecheckAtConnect` was
 * declared but never implemented.
 */
class NetBlocklistTest {

    private val secure = SandboxPolicy.SECURE_DEFAULT.net

    /** Resolve every host through a fixed table, byte-exact (no JVM IPv4-mapped folding for the IPv6 entries). */
    private fun resolver(vararg entries: Pair<String, ByteArray>): NameResolver {
        val table = entries.toMap()
        return NameResolver { host -> arrayOf(InetAddress.getByAddress(host, table.getValue(host))) }
    }

    private fun v6(text: String): ByteArray = InetAddress.getByName(text).address.also { check(it.size == 16) }

    private fun denied(host: String, bytes: ByteArray) {
        val ex = assertThrows<SandboxViolation>("expected $host to be blocked") {
            NetSandbox.checkConnect(secure, host, 80, resolver(host to bytes))
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
    }

    @Test
    fun `this-network 0_0_0_0 slash 8 is blocked`() {
        denied("zero.example", byteArrayOf(0, 0, 0, 0))
        denied("zero2.example", byteArrayOf(0, 1, 2, 3))
    }

    @Test
    fun `IPv6 unspecified address is blocked`() {
        denied("unspec.example", ByteArray(16))
    }

    @Test
    fun `NAT64 addresses are blocked`() {
        denied("nat64.example", v6("64:ff9b::a9fe:a9fe"))       // 169.254.169.254 via NAT64
        denied("nat64-public.example", v6("64:ff9b::808:808"))   // the whole well-known prefix
        denied("nat64-local.example", v6("64:ff9b:1::a00:1"))    // RFC 8215 local-use prefix
    }

    @Test
    fun `IPv4-mapped and IPv4-compatible forms of blocked addresses are blocked`() {
        val mapped = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 169.toByte(); it[13] = 254.toByte(); it[14] = 169.toByte(); it[15] = 254.toByte() }
        // Construct an Inet6Address directly so the JVM cannot fold it to IPv4 first.
        val mappedAddr = java.net.Inet6Address.getByAddress("mapped.example", mapped, -1)
        val ex = assertThrows<SandboxViolation> {
            NetSandbox.checkConnect(secure, "mapped.example", 80) { arrayOf(mappedAddr) }
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)

        val compatible = ByteArray(16).also { it[12] = 10; it[15] = 1 }  // ::10.0.0.1
        val compatAddr = java.net.Inet6Address.getByAddress("compat.example", compatible, -1)
        assertThrows<SandboxViolation> {
            NetSandbox.checkConnect(secure, "compat.example", 80) { arrayOf(compatAddr) }
        }
    }

    @Test
    fun `IPv6 literal of a mapped metadata address is blocked`() {
        val ex = assertThrows<SandboxViolation> {
            NetSandbox.checkConnect(secure, "[::ffff:169.254.169.254]", 80, SystemNameResolver)
        }
        assertEquals(SandboxViolationKind.NetHostBlocked, ex.kind)
    }

    @Test
    fun `public addresses still pass`() {
        val addr = NetSandbox.checkConnect(secure, "pub.example", 443, resolver("pub.example" to byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
        assertEquals("93.184.216.34", addr.hostAddress)
        NetSandbox.checkConnect(secure, "pub6.example", 443, resolver("pub6.example" to v6("2606:2800:220:1::1")))
    }

    // ---------- RecheckAtConnect ----------

    @Test
    fun `RecheckAtConnect detects a rebinding between check and connect`() {
        val calls = AtomicInteger()
        val rebinding = NameResolver { host ->
            val ip = if (calls.getAndIncrement() == 0) byteArrayOf(93, 184.toByte(), 216.toByte(), 34) else byteArrayOf(93, 184.toByte(), 216.toByte(), 99)
            arrayOf(InetAddress.getByAddress(host, ip))
        }
        val policy = secure.copy(dnsPolicy = DnsPolicy.RecheckAtConnect)
        val pinned = NetSandbox.checkConnect(policy, "flip.example", 80, rebinding)
        val ex = assertThrows<SandboxViolation> { NetSandbox.recheckAtConnect(policy, "flip.example", pinned, rebinding) }
        assertEquals(SandboxViolationKind.NetDnsRebindingDetected, ex.kind)
    }

    @Test
    fun `RecheckAtConnect admits a stable resolution and is a no-op under PinAtCheck`() {
        val stable = resolver("stable.example" to byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val policy = secure.copy(dnsPolicy = DnsPolicy.RecheckAtConnect)
        val pinned = NetSandbox.checkConnect(policy, "stable.example", 80, stable)
        NetSandbox.recheckAtConnect(policy, "stable.example", pinned, stable)
        // PinAtCheck never re-resolves.
        NetSandbox.recheckAtConnect(secure, "stable.example", pinned) { error("no DNS under PinAtCheck") }
    }

    @Test
    fun `Net_Connect under RecheckAtConnect refuses a rebound host before opening the socket`() {
        val calls = AtomicInteger()
        val rebinding = NameResolver { host ->
            val ip = if (calls.getAndIncrement() == 0) byteArrayOf(93, 184.toByte(), 216.toByte(), 34) else byteArrayOf(10, 0, 0, 1)
            arrayOf(InetAddress.getByAddress(host, ip))
        }
        val ctx = HostContext.processDefault().copy(
            sandboxPolicy = SandboxPolicy(SandboxPolicy.OPEN_DEFAULT.fs, secure.copy(dnsPolicy = DnsPolicy.RecheckAtConnect)),
            nameResolver = rebinding,
        )
        val ex = assertThrows<SandboxViolation> {
            Builtins.lookup("strand-builtin:Net.Connect")!!.invoke(ctx, listOf(Value.StringV("flip.example"), Value.IntV(80)))
        }
        assertEquals(SandboxViolationKind.NetDnsRebindingDetected, ex.kind)
    }
}
