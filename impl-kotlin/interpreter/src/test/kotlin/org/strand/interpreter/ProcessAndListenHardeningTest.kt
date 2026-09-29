package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Review M1 / M7: `Process.Spawn` children inherited the full JVM
 * environment and working directory regardless of the tenant's policy,
 * and `Http.Listen` bound every interface with an unbounded pending queue.
 */
class ProcessAndListenHardeningTest {

    @AfterEach
    fun tearDown() = ResourceTable.resetForTest()

    private val windows = System.getProperty("os.name").lowercase().contains("windows")

    private class FakeEnv(private val vars: Map<String, String>) : Builtins.OsEnv {
        override fun hostname() = "h"
        override fun platform() = "p"
        override fun cwd() = "c"
        override fun environment(): Map<String, String> = vars
    }

    private fun list(items: List<String>): Value = items.foldRight(Value.SumV("Nil", null) as Value) { s, acc ->
        Value.SumV("Cons", Value.ProductV(mapOf("head" to Value.StringV(s), "tail" to acc)))
    }

    /** Spawn `shell -c script` (or `cmd /c script`) under [ctx] and return its exit code. */
    private fun runShell(ctx: HostContext, script: String): Long {
        val (cmd, args) = if (windows) "cmd" to listOf("/c", script) else "sh" to listOf("-c", script)
        val handle = Builtins.lookup("strand-builtin:Process.Spawn")!!.invoke(ctx, listOf(Value.StringV(cmd), list(args)))
        return (Builtins.lookup("strand-builtin:Process.Wait")!!.invoke(ctx, listOf(handle)) as Value.IntV).v
    }

    @Test
    fun `Spawn gives the child the tenant environment, not the JVM environment`() {
        // Q-075: the library default sandbox denies Process.Spawn; this test
        // exercises the environment, so it opts into the open sandbox.
        val ctx = HostContext.processDefault().copy(
            osEnv = FakeEnv(mapOf("STRAND_CHILD_VAR" to "42")),
            sandboxPolicy = SandboxPolicy.OPEN_DEFAULT,
        )
        val sees = if (windows) "if \"%STRAND_CHILD_VAR%\"==\"42\" (exit 0) else (exit 3)"
            else "test \"\$STRAND_CHILD_VAR\" = 42"
        assertEquals(0L, runShell(ctx, sees), "child must see the tenant's variable")
        // PATH (Windows) / HOME (POSIX; sh supplies a default PATH itself)
        // is set in the JVM environment but not in the tenant's.
        val leaks = if (windows) "if defined PATH (exit 4) else (exit 0)"
            else "if [ -n \"\${HOME:-}\" ]; then exit 4; else exit 0; fi"
        assertEquals(0L, runShell(ctx, leaks), "child must not inherit the JVM environment")
    }

    @Test
    fun `Spawn runs the child in the workspace root`(@TempDir tmp: Path) {
        val ws = Files.createDirectories(tmp.resolve("ws"))
        val ctx = HostContext.processDefault().copy(
            osEnv = FakeEnv(emptyMap()),
            sandboxPolicy = SandboxPolicy(
                fs = FsPolicy(workspaceRoot = ws, escape = EscapePolicy.Deny, followSymlinks = false),
                net = SandboxPolicy.OPEN_DEFAULT.net,
            ),
        )
        val touch = if (windows) "type nul > marker.txt" else ": > marker.txt"
        assertEquals(0L, runShell(ctx, touch))
        assertTrue(Files.exists(ws.resolve("marker.txt")), "the relative write must land in the workspace")
        assertFalse(Files.exists(Path.of(System.getProperty("user.dir")).resolve("marker.txt")))
    }

    @Test
    fun `Http_Listen binds loopback by default with a bounded pending queue`() {
        val ctx = HostContext.processDefault()
        val server = Builtins.lookup("strand-builtin:Http.Listen")!!.invoke(ctx, listOf(Value.IntV(0))) as Value.Resource
        try {
            val holder = ResourceTable.get(server, "http-server") as Builtins.HttpServerHolder
            assertTrue(holder.server.address.address.isLoopbackAddress, "bound ${holder.server.address}")
            assertEquals(Builtins.HTTP_LISTEN_MAX_PENDING, holder.queue.remainingCapacity())
        } finally {
            Builtins.lookup("strand-builtin:Http.ServerClose")!!.invoke(ctx, listOf(server))
        }
    }

    @Test
    fun `Http_Listen binds all interfaces only when the net policy allows it`() {
        val ctx = HostContext.processDefault().copy(
            sandboxPolicy = SandboxPolicy(
                fs = SandboxPolicy.OPEN_DEFAULT.fs,
                net = SandboxPolicy.OPEN_DEFAULT.net.copy(listenOnAllInterfaces = true),
            ),
        )
        val server = Builtins.lookup("strand-builtin:Http.Listen")!!.invoke(ctx, listOf(Value.IntV(0))) as Value.Resource
        try {
            val holder = ResourceTable.get(server, "http-server") as Builtins.HttpServerHolder
            assertTrue(holder.server.address.address.isAnyLocalAddress, "bound ${holder.server.address}")
        } finally {
            Builtins.lookup("strand-builtin:Http.ServerClose")!!.invoke(ctx, listOf(server))
        }
    }
}
