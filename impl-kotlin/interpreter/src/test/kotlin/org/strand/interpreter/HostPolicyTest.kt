package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.strand.core.EvaluationLimits

/**
 * Q-054 scenarios 1 and 2: the [HostPolicy] value object's defaults and copy
 * threading. These assert the policy-bundle shape independent of any runtime;
 * the isolation and restoration properties are exercised by the facade tests
 * in `:runtime`.
 */
class HostPolicyTest {

    @Test
    fun `OPEN default mirrors the open sandbox and the singleton defaults`() {
        val open = HostPolicy.OPEN
        assertSame(SandboxPolicy.OPEN_DEFAULT, open.sandbox)
        assertEquals(EvaluationLimits.DEFAULTS, open.limits)
        assertSame(Builtins.SystemClock, open.clock)
        assertSame(EnvCredentialProvider, open.credentialProvider)
        assertSame(SystemNameResolver, open.nameResolver)
        assertSame(DefaultLlmHttpClient, open.llmHttpClient)
        assertSame(JdkHttpTransport, open.vectorHttpTransport)
        assertSame(Builtins.StderrLogSink, open.logSink)
        assertSame(Builtins.SystemOsEnv, open.osEnv)
        assertSame(Builtins.RealExitHandler, open.exitHandler)
        assertEquals(10, open.toolLoopLimit)
    }

    @Test
    fun `SECURE differs from OPEN only in the sandbox field`() {
        val open = HostPolicy.OPEN
        val secure = HostPolicy.SECURE
        // The one intended difference.
        assertSame(SandboxPolicy.SECURE_DEFAULT, secure.sandbox)
        assertNotEquals(open.sandbox, secure.sandbox)
        // Everything else equal: SECURE is OPEN.copy(sandbox = SECURE_DEFAULT),
        // so copying SECURE back to the open sandbox reproduces OPEN exactly.
        assertEquals(open, secure.copy(sandbox = SandboxPolicy.OPEN_DEFAULT))
    }

    @Test
    fun `copy threads a tightened limits and leaves every other field at the OPEN defaults`() {
        val tighter = EvaluationLimits.DEFAULTS.copy(maxSteps = 100L)
        val policy = HostPolicy.OPEN.copy(limits = tighter)
        assertEquals(100L, policy.limits.maxSteps)
        // Untouched fields.
        assertSame(HostPolicy.OPEN.sandbox, policy.sandbox)
        assertSame(HostPolicy.OPEN.clock, policy.clock)
        assertSame(HostPolicy.OPEN.credentialProvider, policy.credentialProvider)
        assertEquals(HostPolicy.OPEN.toolLoopLimit, policy.toolLoopLimit)
    }

    @Test
    fun `copy threads a seeded RNG for reproducible test runs`() {
        val seeded = java.util.Random(42L)
        val policy = HostPolicy.OPEN.copy(random = seeded)
        assertSame(seeded, policy.random)
    }
}
