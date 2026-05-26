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
 * Layer 4 step 2 — direct unit tests of the Filesystem / Time / Process
 * builtins via the [Builtins.lookup] dispatch. The tests bypass the
 * full interpreter (no JSON ingest, no capability check) because each
 * builtin's contract is "given evaluated args, return a value" — that's
 * what gets exercised end-to-end here. The interpreter-level capability
 * check is covered by the broader InterpreterTest suite.
 */
class BuiltinsIoTest {

    @AfterEach
    fun cleanupResourceTable() {
        ResourceTable.resetForTest()
    }

    // ---------- Filesystem ----------

    @Test
    fun `Filesystem_Write writes bytes and returns count`(@TempDir tmp: Path) {
        val fn = Builtins.lookup("strand-builtin:Fs.Write")!!
        val target = tmp.resolve("hello.txt")
        val payload = "hello, strand\n".toByteArray(Charsets.UTF_8)
        val result = fn.invoke(listOf(Value.StringV(target.toString()), Value.BytesV(payload)))
        assertEquals(Value.IntV(payload.size.toLong()), result)
        assertTrue(Files.exists(target))
        assertEquals(payload.toList(), Files.readAllBytes(target).toList())
    }

    @Test
    fun `Filesystem_Read returns the file's bytes`(@TempDir tmp: Path) {
        val fn = Builtins.lookup("strand-builtin:Fs.Read")!!
        val target = tmp.resolve("greet.txt")
        val payload = "hi".toByteArray(Charsets.UTF_8)
        Files.write(target, payload)
        val result = fn.invoke(listOf(Value.StringV(target.toString())))
        assertEquals(Value.BytesV(payload), result)
    }

    @Test
    fun `Filesystem_Read on missing file raises IoFailure with filesystem-read kind`(@TempDir tmp: Path) {
        val fn = Builtins.lookup("strand-builtin:Fs.Read")!!
        val missing = tmp.resolve("nope.txt")
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
            fn.invoke(listOf(Value.StringV(missing.toString())))
        }
        assertEquals("filesystem-read", ex.kind)
        assertTrue(ex.detail.contains("does not exist"), "got detail: ${ex.detail}")
    }

    @Test
    fun `Filesystem_Append concatenates to an existing file`(@TempDir tmp: Path) {
        val target = tmp.resolve("log.txt")
        val writeFn = Builtins.lookup("strand-builtin:Fs.Write")!!
        val appendFn = Builtins.lookup("strand-builtin:Fs.Append")!!
        writeFn.invoke(listOf(Value.StringV(target.toString()), Value.BytesV("a".toByteArray())))
        appendFn.invoke(listOf(Value.StringV(target.toString()), Value.BytesV("b".toByteArray())))
        appendFn.invoke(listOf(Value.StringV(target.toString()), Value.BytesV("c\n".toByteArray())))
        assertEquals("abc\n", Files.readString(target))
    }

    @Test
    fun `Filesystem_Append creates the file if it doesn't exist`(@TempDir tmp: Path) {
        val target = tmp.resolve("new.txt")
        val appendFn = Builtins.lookup("strand-builtin:Fs.Append")!!
        appendFn.invoke(listOf(Value.StringV(target.toString()), Value.BytesV("first".toByteArray())))
        assertEquals("first", Files.readString(target))
    }

    @Test
    fun `Filesystem_Exists reports true and false correctly`(@TempDir tmp: Path) {
        val fn = Builtins.lookup("strand-builtin:Fs.Exists")!!
        val present = tmp.resolve("here.txt").also { Files.write(it, "x".toByteArray()) }
        val absent = tmp.resolve("notHere.txt")
        assertEquals(Value.BoolV(true), fn.invoke(listOf(Value.StringV(present.toString()))))
        assertEquals(Value.BoolV(false), fn.invoke(listOf(Value.StringV(absent.toString()))))
    }

    @Test
    fun `Filesystem_Delete removes the file and returns true, then false when absent`(@TempDir tmp: Path) {
        val fn = Builtins.lookup("strand-builtin:Fs.Delete")!!
        val target = tmp.resolve("doomed.txt").also { Files.write(it, "bye".toByteArray()) }
        assertTrue(Files.exists(target))
        assertEquals(Value.BoolV(true), fn.invoke(listOf(Value.StringV(target.toString()))))
        assertFalse(Files.exists(target))
        // Calling again returns false (file no longer exists; deleteIfExists semantics).
        assertEquals(Value.BoolV(false), fn.invoke(listOf(Value.StringV(target.toString()))))
    }

    @Test
    fun `Filesystem_List returns a SumV Cons chain sorted alphabetically`(@TempDir tmp: Path) {
        val fn = Builtins.lookup("strand-builtin:Fs.List")!!
        Files.write(tmp.resolve("c.txt"), "c".toByteArray())
        Files.write(tmp.resolve("a.txt"), "a".toByteArray())
        Files.write(tmp.resolve("b.txt"), "b".toByteArray())
        val result = fn.invoke(listOf(Value.StringV(tmp.toString())))
        // Walk the Cons/Nil chain and collect the heads in order.
        val collected = mutableListOf<String>()
        var cur: Value = result
        while (cur is Value.SumV && cur.case == "Cons") {
            val payload = cur.payload as Value.ProductV
            collected += (payload.fields.getValue("head") as Value.StringV).v
            cur = payload.fields.getValue("tail")
        }
        assertTrue(cur is Value.SumV && (cur as Value.SumV).case == "Nil")
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), collected)
    }

    @Test
    fun `Filesystem_List on missing directory raises IoFailure`(@TempDir tmp: Path) {
        val fn = Builtins.lookup("strand-builtin:Fs.List")!!
        val missing = tmp.resolve("not-a-dir")
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
            fn.invoke(listOf(Value.StringV(missing.toString())))
        }
        assertEquals("filesystem-list", ex.kind)
    }

    // ---------- Time ----------

    @Test
    fun `Time_Now reads from the active Builtins clock (FixedClock)`() {
        val saved = Builtins.clock
        try {
            Builtins.clock = Builtins.FixedClock(1_234_567_890_000L)
            val fn = Builtins.lookup("strand-builtin:Time.Now")!!
            assertEquals(Value.IntV(1_234_567_890_000L), fn.invoke(emptyList()))
        } finally {
            Builtins.clock = saved
        }
    }

    @Test
    fun `Time_Now under SystemClock returns a value near System_currentTimeMillis`() {
        val saved = Builtins.clock
        try {
            Builtins.clock = Builtins.SystemClock
            val before = System.currentTimeMillis()
            val fn = Builtins.lookup("strand-builtin:Time.Now")!!
            val result = (fn.invoke(emptyList()) as Value.IntV).v
            val after = System.currentTimeMillis()
            // Result is between before and after (inclusive on both ends
            // because System.currentTimeMillis may return the same value
            // on fast machines).
            assertTrue(result in before..after, "Time.Now returned $result; expected $before..$after")
        } finally {
            Builtins.clock = saved
        }
    }

    // ---------- Network ----------

    @Test
    fun `Net round-trip via local ServerSocket`() {
        val server = java.net.ServerSocket(0)  // ephemeral port
        try {
            val port = server.localPort
            val message = "hello, strand network".toByteArray(Charsets.UTF_8)
            // Spawn a thread that accepts one connection, echoes the payload.
            val echoThread = Thread {
                server.accept().use { client ->
                    val buf = ByteArray(1024)
                    val n = client.getInputStream().read(buf)
                    if (n > 0) {
                        client.getOutputStream().write(buf, 0, n)
                        client.getOutputStream().flush()
                    }
                }
            }
            echoThread.start()

            val connect = Builtins.lookup("strand-builtin:Net.Connect")!!
            val send = Builtins.lookup("strand-builtin:Net.Send")!!
            val receive = Builtins.lookup("strand-builtin:Net.Receive")!!
            val close = Builtins.lookup("strand-builtin:Net.Close")!!

            val handle = connect.invoke(listOf(Value.StringV("127.0.0.1"), Value.IntV(port.toLong()))) as Value.Resource
            assertEquals("socket", handle.kind)

            val sent = send.invoke(listOf(handle, Value.BytesV(message))) as Value.IntV
            assertEquals(message.size.toLong(), sent.v)

            val received = receive.invoke(listOf(handle, Value.IntV(1024L))) as Value.BytesV
            assertEquals(message.toList(), received.v.toList())

            close.invoke(listOf(handle))
            echoThread.join(2000)
        } finally {
            server.close()
        }
    }

    @Test
    fun `Net_Connect on unreachable port raises IoFailure`() {
        // Port 1 is privileged on most systems and not listening.
        // We can't be 100% sure no service is on it, so use a port in
        // the registered range that's likely closed; a random high
        // port that the OS just gave us and we never bound is safer.
        val server = java.net.ServerSocket(0)
        val port = server.localPort
        server.close()  // close so the port is no longer listening
        val connect = Builtins.lookup("strand-builtin:Net.Connect")!!
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
            connect.invoke(listOf(Value.StringV("127.0.0.1"), Value.IntV(port.toLong())))
        }
        assertEquals("network-connect", ex.kind)
    }

    @Test
    fun `Net_Close is idempotent`() {
        val server = java.net.ServerSocket(0)
        try {
            val port = server.localPort
            val acceptThread = Thread { server.accept().close() }
            acceptThread.start()
            val connect = Builtins.lookup("strand-builtin:Net.Connect")!!
            val close = Builtins.lookup("strand-builtin:Net.Close")!!
            val handle = connect.invoke(listOf(Value.StringV("127.0.0.1"), Value.IntV(port.toLong()))) as Value.Resource
            close.invoke(listOf(handle))
            // Second close: shouldn't throw.
            close.invoke(listOf(handle))
            acceptThread.join(2000)
        } finally {
            server.close()
        }
    }

    // ---------- Process ----------

    @Test
    fun `Process_EnvVar returns Some when the variable is set`() {
        val fn = Builtins.lookup("strand-builtin:Process.EnvVar")!!
        // PATH is virtually always set in CI environments + dev machines.
        val v = fn.invoke(listOf(Value.StringV("PATH")))
        assertTrue(v is Value.SumV && (v as Value.SumV).case == "Some")
        val payload = (v as Value.SumV).payload as Value.StringV
        assertTrue(payload.v.isNotEmpty(), "PATH should be non-empty")
    }

    @Test
    fun `Process_EnvVar returns None when the variable is unset`() {
        val fn = Builtins.lookup("strand-builtin:Process.EnvVar")!!
        // A name we're confident isn't set.
        val v = fn.invoke(listOf(Value.StringV("STRAND_TEST_UNSET_${System.nanoTime()}")))
        assertTrue(v is Value.SumV && (v as Value.SumV).case == "None")
        assertEquals(null, (v as Value.SumV).payload)
    }

    @Test
    fun `Process_Spawn and Process_Wait run a simple command`() {
        // Pick a portable command. On Windows: `cmd /c exit 0`; on POSIX:
        // `true`. Detect via os.name. This test exercises Spawn → Wait
        // round-trip; the inheritIO stdio model means the child's
        // (empty) output goes to the test runner.
        val (cmd, argList) = if (System.getProperty("os.name").lowercase().contains("windows")) {
            "cmd" to listOf("/c", "exit", "0")
        } else {
            "true" to emptyList()
        }
        val nil: Value = Value.SumV(case = "Nil", payload = null)
        val argChain: Value = argList.foldRight(nil) { s, acc ->
            Value.SumV(case = "Cons", payload = Value.ProductV(
                mapOf("head" to Value.StringV(s), "tail" to acc)
            ))
        }
        val spawnFn = Builtins.lookup("strand-builtin:Process.Spawn")!!
        val handle = spawnFn.invoke(listOf(Value.StringV(cmd), argChain)) as Value.Resource
        assertEquals("process", handle.kind)
        val waitFn = Builtins.lookup("strand-builtin:Process.Wait")!!
        val exit = waitFn.invoke(listOf(handle))
        assertEquals(Value.IntV(0L), exit)
    }

    @Test
    fun `Process_Spawn on missing command raises IoFailure`() {
        val nil: Value = Value.SumV(case = "Nil", payload = null)
        val spawnFn = Builtins.lookup("strand-builtin:Process.Spawn")!!
        val ex = org.junit.jupiter.api.assertThrows<IoFailure> {
            spawnFn.invoke(listOf(Value.StringV("strand-no-such-command-${System.nanoTime()}"), nil))
        }
        assertEquals("process-spawn", ex.kind)
    }

    @Test
    fun `Time_Sleep under FixedClock is a no-op`() {
        val saved = Builtins.clock
        try {
            Builtins.clock = Builtins.FixedClock(0L)
            val fn = Builtins.lookup("strand-builtin:Time.Sleep")!!
            val t0 = System.nanoTime()
            assertEquals(Value.UnitV, fn.invoke(listOf(Value.IntV(1000L))))
            val elapsedMillis = (System.nanoTime() - t0) / 1_000_000
            // FixedClock.sleep is a no-op; should return in well under 100ms.
            assertTrue(elapsedMillis < 100, "Time.Sleep took ${elapsedMillis}ms under FixedClock")
        } finally {
            Builtins.clock = saved
        }
    }
}
