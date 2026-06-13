package org.strand.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.strand.core.EvaluationLimits
import org.strand.core.NodeId
import org.strand.core.Primitive
import org.strand.interpreter.Builtins
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.verifier.TypeExpr

/**
 * Q-054 wiring proof for the host-context install/restore protocol the
 * [org.strand.runtime.StrandRuntime] facade owns — `Builtins.snapshot` /
 * `install(policy, nodeTypes)` / `restore`, which replaced the CLI's former
 * `withProgramEvaluationContext` helper. The load-bearing assertion is the
 * [Builtins.verifierNodeTypes] install: before the install path existed, the
 * field was only ever set by test harnesses, so every real CLI run silently
 * degraded N-044 ToolDef parameter schemas and N-045 ResponseSchemaSpec
 * projections to empty `{}` JSON schemas. The facade's end-to-end isolation
 * coverage lives in `org.strand.runtime.StrandRuntimeIsolationTest`; this
 * file pins the primitive protocol the CLI depends on.
 */
class ProgramEvaluationContextTest {

    /** Mirror of the facade's internal withInstalled, expressed over the public protocol. */
    private fun <T> withInstalled(
        policy: HostPolicy,
        nodeTypes: Map<NodeId, TypeExpr>?,
        block: () -> T,
    ): T {
        val prior = Builtins.snapshot()
        Builtins.install(policy, nodeTypes)
        try {
            return block()
        } finally {
            Builtins.restore(prior)
        }
    }

    @Test
    fun `installs verifier nodeTypes plus sandbox and stream timeout inside the block`() {
        val priorSandbox = Builtins.sandboxPolicy
        val priorTimeout = Builtins.streamReceiveTimeoutMillis
        val priorNodeTypes = Builtins.verifierNodeTypes

        val nodeTypes: Map<NodeId, TypeExpr> = mapOf(NodeId(7) to TypeExpr.Prim(Primitive.Int))
        val limits = EvaluationLimits.DEFAULTS.copy(streamReceiveTimeoutMillis = 1234L)
        val policy = HostPolicy.OPEN.copy(sandbox = SandboxPolicy.SECURE_DEFAULT, limits = limits)

        var observedNodeTypes: Map<NodeId, TypeExpr>? = null
        var observedTimeout: Long = -1
        var observedSandbox: SandboxPolicy? = null
        val result = withInstalled(policy, nodeTypes) {
            observedNodeTypes = Builtins.verifierNodeTypes
            observedTimeout = Builtins.streamReceiveTimeoutMillis
            observedSandbox = Builtins.sandboxPolicy
            "done"
        }

        assertEquals("done", result)
        assertSame(nodeTypes, observedNodeTypes, "verifier nodeTypes must be installed during evaluation")
        assertEquals(1234L, observedTimeout)
        assertSame(SandboxPolicy.SECURE_DEFAULT, observedSandbox)
        // Priors restored after the block.
        assertSame(priorSandbox, Builtins.sandboxPolicy)
        assertEquals(priorTimeout, Builtins.streamReceiveTimeoutMillis)
        assertEquals(priorNodeTypes, Builtins.verifierNodeTypes)
    }

    @Test
    fun `restores priors when the block throws`() {
        val priorSandbox = Builtins.sandboxPolicy
        val priorTimeout = Builtins.streamReceiveTimeoutMillis
        val priorNodeTypes = Builtins.verifierNodeTypes

        val nodeTypes: Map<NodeId, TypeExpr> = mapOf(NodeId(3) to TypeExpr.Prim(Primitive.Bool))
        val policy = HostPolicy.OPEN.copy(
            sandbox = SandboxPolicy.SECURE_DEFAULT,
            limits = EvaluationLimits.DEFAULTS.copy(streamReceiveTimeoutMillis = 99L),
        )
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            withInstalled(policy, nodeTypes) {
                check(Builtins.verifierNodeTypes === nodeTypes)
                error("boom")
            }
        }
        assertSame(priorSandbox, Builtins.sandboxPolicy)
        assertEquals(priorTimeout, Builtins.streamReceiveTimeoutMillis)
        assertEquals(priorNodeTypes, Builtins.verifierNodeTypes)
    }

    @Test
    fun `null nodeTypes is installable and the prior non-null value comes back`() {
        val prior = Builtins.verifierNodeTypes
        val sentinel: Map<NodeId, TypeExpr> = mapOf(NodeId(1) to TypeExpr.Prim(Primitive.String))
        Builtins.verifierNodeTypes = sentinel
        try {
            withInstalled(HostPolicy.OPEN, null) {
                assertNull(Builtins.verifierNodeTypes)
            }
            assertSame(sentinel, Builtins.verifierNodeTypes)
        } finally {
            Builtins.verifierNodeTypes = prior
        }
    }
}
