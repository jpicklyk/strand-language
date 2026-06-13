package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.strand.core.EvaluationLimits
import org.strand.core.NodeId
import org.strand.core.Primitive
import org.strand.verifier.TypeExpr

/**
 * Q-054 follow-up: unit coverage for [HostContext] — the per-invocation
 * projection of a [HostPolicy] that the builtin lambdas read from instead of
 * the [Builtins] process-global singletons.
 */
class HostContextTest {

    @Test
    fun `fromPolicy threads every host-routed field off the policy`() {
        val clock = Builtins.FixedClock(123L)
        val random = java.util.Random(42)
        val sandbox = SandboxPolicy.SECURE_DEFAULT
        val provider = StaticCredentialProvider(mapOf("anthropic" to "sk-ant-fromPolicy"))
        val limits = EvaluationLimits.DEFAULTS.copy(streamReceiveTimeoutMillis = 4321L)
        val nodeTypes: Map<NodeId, TypeExpr> = mapOf(NodeId(9) to TypeExpr.Prim(Primitive.Int))
        val policy = HostPolicy.OPEN.copy(
            clock = clock,
            random = random,
            sandbox = sandbox,
            credentialProvider = provider,
            limits = limits,
            toolLoopLimit = 7,
        )

        val ctx = HostContext.fromPolicy(policy, nodeTypes)

        assertSame(clock, ctx.clock)
        assertSame(random, ctx.random)
        assertSame(sandbox, ctx.sandboxPolicy)
        // The credential provider is wrapped so resolved credentials register
        // into this context's scrubber; it delegates to the policy's provider
        // and, as a side effect, registers the resolved value into ctx.scrubber.
        val cred = ctx.credentialProvider.resolve("anthropic", "api_key")
        assertEquals("sk-ant-fromPolicy", cred?.reveal())
        assertEquals(
            "key=[REDACTED:anthropic:api_key]",
            ctx.scrubber.scrub("key=sk-ant-fromPolicy"),
            "resolving through the context provider registers the credential in the context scrubber",
        )
        assertEquals(4321L, ctx.streamReceiveTimeoutMillis)
        assertSame(nodeTypes, ctx.verifierNodeTypes)
        assertEquals(7, ctx.toolLoopLimit)
        assertSame(policy.nameResolver, ctx.nameResolver)
        assertSame(policy.llmHttpClient, ctx.llmHttpClient)
        assertSame(policy.vectorHttpTransport, ctx.vectorHttpTransport)
        assertSame(policy.logSink, ctx.logSink)
        assertSame(policy.osEnv, ctx.osEnv)
        assertSame(policy.exitHandler, ctx.exitHandler)
    }

    @Test
    fun `fromPolicy allocates a fresh per-context scrubber distinct from the global default`() {
        val a = HostContext.fromPolicy(HostPolicy.OPEN)
        val b = HostContext.fromPolicy(HostPolicy.OPEN)
        assertNotSame(a.scrubber, b.scrubber, "each context must carry its own scrubber")
        assertNotSame(CredentialScrubber.default, a.scrubber)
    }

    @Test
    fun `per-context scrubbers do not share registered credentials`() {
        val a = Scrubber()
        val b = Scrubber()
        a.register(Credential("sk-ant-tenant-a-secret", "anthropic", "api_key"))
        // a's credential is redacted by a, but b never saw it.
        assertEquals(
            "key=[REDACTED:anthropic:api_key]",
            a.scrub("key=sk-ant-tenant-a-secret"),
        )
        assertEquals(
            "key=sk-ant-tenant-a-secret",
            b.scrub("key=sk-ant-tenant-a-secret"),
            "tenant A's credential must not be scrubbed by tenant B's scrubber",
        )
    }

    @Test
    fun `processDefault reads the current Builtins singletons`() {
        val priorClock = Builtins.clock
        val fixed = Builtins.FixedClock(999L)
        try {
            Builtins.clock = fixed
            val ctx = HostContext.processDefault()
            assertSame(fixed, ctx.clock)
            assertSame(CredentialScrubber.default, ctx.scrubber)
        } finally {
            Builtins.clock = priorClock
        }
    }

    @Test
    fun `processDefault carries null verifierNodeTypes when the singleton is null`() {
        val prior = Builtins.verifierNodeTypes
        try {
            Builtins.verifierNodeTypes = null
            assertNull(HostContext.processDefault().verifierNodeTypes)
        } finally {
            Builtins.verifierNodeTypes = prior
        }
    }
}
