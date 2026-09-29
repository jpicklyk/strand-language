package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Review H5: `Process.EnvVar` must read the tenant's host environment
 * through [HostContext.osEnv], never `System.getenv`, and
 * [HostPolicy.SECURE] must expose an empty environment by default. The
 * environment is injected through a fake [Builtins.OsEnv]; the real JVM
 * environment is never mutated.
 */
class HostEnvIsolationTest {

    /** A host environment with a fixed variable table. */
    private class FakeEnv(private val vars: Map<String, String>) : Builtins.OsEnv {
        override fun hostname() = "h"
        override fun platform() = "linux"
        override fun cwd() = "/w"
        override fun environment(): Map<String, String> = vars
    }

    private fun envVar(ctx: HostContext, name: String): Value =
        Builtins.lookup("strand-builtin:Process.EnvVar")!!.invoke(ctx, listOf(Value.StringV(name)))

    @Test
    fun `EnvVar reads the context environment, not the JVM environment`() {
        val ctx = HostContext.processDefault().copy(osEnv = FakeEnv(mapOf("STRAND_ONLY_IN_FAKE" to "v1")))
        assertEquals(Value.SumV("Some", Value.StringV("v1")), envVar(ctx, "STRAND_ONLY_IN_FAKE"))
        // PATH exists in the real JVM environment but not in the fake one.
        assertEquals(Value.SumV("None", null), envVar(ctx, "PATH"))
    }

    @Test
    fun `SECURE hides a secret even when the underlying host environment has it`() {
        val host = FakeEnv(mapOf("ANTHROPIC_API_KEY" to "sk-ant-secret"))
        // The SECURE policy's environment wrapper, applied to an injected host env.
        val secureEnv = Builtins.EmptyEnvOsEnv(host)
        val ctx = HostContext.processDefault().copy(osEnv = secureEnv)
        assertEquals(Value.SumV("None", null), envVar(ctx, "ANTHROPIC_API_KEY"))
        assertTrue(secureEnv.environment().isEmpty())
        // Non-secret host facts still come from the wrapped source.
        assertEquals("h", secureEnv.hostname())
    }

    @Test
    fun `HostPolicy SECURE installs an empty environment`() {
        assertTrue(HostPolicy.SECURE.osEnv is Builtins.EmptyEnvOsEnv)
        val ctx = HostContext.fromPolicy(HostPolicy.SECURE)
        // PATH is set in every real environment the suite runs in.
        assertEquals(Value.SumV("None", null), envVar(ctx, "PATH"))
    }

    @Test
    fun `an OsEnv that does not override environment exposes nothing`() {
        val bare = object : Builtins.OsEnv {
            override fun hostname() = "h"
            override fun platform() = "p"
            override fun cwd() = "c"
        }
        assertTrue(bare.environment().isEmpty())
        assertEquals(null, bare.envVar("PATH"))
    }
}
