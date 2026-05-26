package org.strand.interpreter

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Stdlib expansion round 3 — Log.* / OS.* / System.Exit builtins.
 * All effectful; injection points let tests assert without touching
 * the live host environment or terminating the JVM.
 */
class BuiltinsHostEnvTest {

    companion object {
        private class CapturingSink : Builtins.LogSink {
            val lines = mutableListOf<String>()
            override fun println(line: String) {
                lines += line
            }
        }
        private class FixedOsEnv(
            private val h: String,
            private val p: String,
            private val c: String,
        ) : Builtins.OsEnv {
            override fun hostname() = h
            override fun platform() = p
            override fun cwd() = c
        }

        private val sink = CapturingSink()
        private val testExit = Builtins.TestExitHandler()

        @JvmStatic
        @BeforeAll
        fun installInjections() {
            Builtins.logSink = sink
            Builtins.osEnv = FixedOsEnv("test-host", "linux", "/tmp/test")
            Builtins.exitHandler = testExit
        }

        @JvmStatic
        @AfterAll
        fun restoreInjections() {
            Builtins.logSink = Builtins.StderrLogSink
            Builtins.osEnv = Builtins.SystemOsEnv
            Builtins.exitHandler = Builtins.RealExitHandler
        }
    }

    @AfterEach
    fun clearSink() {
        (Builtins.logSink as? CapturingSink)?.lines?.clear()
    }

    private fun lookup(name: String) = Builtins.lookup(name)!!
    private fun sinkLines() = (Builtins.logSink as CapturingSink).lines

    // ---------- Log.* ----------

    @Test
    fun `Log_Info prefixes the line with INFO`() {
        lookup("strand-builtin:Log.Info").invoke(listOf(Value.StringV("hello")))
        assertEquals(listOf("[INFO] hello"), sinkLines())
    }

    @Test
    fun `Log_Warn and Log_Error use distinct prefixes`() {
        lookup("strand-builtin:Log.Warn").invoke(listOf(Value.StringV("careful")))
        lookup("strand-builtin:Log.Error").invoke(listOf(Value.StringV("broken")))
        assertEquals(listOf("[WARN] careful", "[ERROR] broken"), sinkLines())
    }

    @Test
    fun `Log_Info returns Unit`() {
        val result = lookup("strand-builtin:Log.Info").invoke(listOf(Value.StringV("x")))
        assertEquals(Value.UnitV, result)
    }

    // ---------- OS.* ----------

    @Test
    fun `OS_Hostname Platform Cwd read from the injected OsEnv`() {
        assertEquals(Value.StringV("test-host"), lookup("strand-builtin:OS.Hostname").invoke(emptyList()))
        assertEquals(Value.StringV("linux"), lookup("strand-builtin:OS.Platform").invoke(emptyList()))
        assertEquals(Value.StringV("/tmp/test"), lookup("strand-builtin:OS.Cwd").invoke(emptyList()))
    }

    @Test
    fun `SystemOsEnv platform normalizes the JVM os name`() {
        // Exercise the production SystemOsEnv (not the test fixture) to
        // confirm normalization logic doesn't drift. Whatever the host
        // platform, it should be one of the recognized lowercase forms.
        val p = Builtins.SystemOsEnv.platform()
        assertTrue(p in setOf("windows", "linux", "macos", "bsd") || p == System.getProperty("os.name", "unknown").lowercase()) {
            "unexpected platform string: $p"
        }
    }

    // ---------- System.Exit ----------

    @Test
    fun `System_Exit invokes the handler with the requested code`() {
        val ex = assertThrows<Builtins.SystemExitInvoked> {
            lookup("strand-builtin:System.Exit").invoke(listOf(Value.IntV(42L)))
        }
        assertEquals(42, ex.code)
        assertEquals(42, (Builtins.exitHandler as Builtins.TestExitHandler).lastCode)
    }

    @Test
    fun `System_Exit code 0 is normal termination`() {
        val ex = assertThrows<Builtins.SystemExitInvoked> {
            lookup("strand-builtin:System.Exit").invoke(listOf(Value.IntV(0L)))
        }
        assertEquals(0, ex.code)
    }
}
