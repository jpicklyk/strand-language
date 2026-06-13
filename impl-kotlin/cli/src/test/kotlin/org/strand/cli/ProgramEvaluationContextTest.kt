package org.strand.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.strand.core.EvaluationLimits
import org.strand.core.NodeId
import org.strand.core.Primitive
import org.strand.interpreter.HostContext
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.verifier.TypeExpr

/**
 * Q-054 follow-up: the per-invocation [HostContext] derivation the facade and
 * CLI now depend on, in place of the retired `Builtins.snapshot` /
 * `install(policy, nodeTypes)` / `restore` install/restore protocol. The
 * load-bearing assertion remains the verifier node-types threading: before any
 * context plumbing existed, the field was only ever set by test harnesses, so
 * a real CLI run silently degraded N-044 ToolDef parameter schemas and N-045
 * ResponseSchemaSpec projections to empty `{}` JSON schemas. Now the facade
 * derives `HostContext.fromPolicy(policy, verifyResult.nodeTypes)` and threads
 * it into the interpreter as a value — this test pins that the context carries
 * the node-types, the flag-derived sandbox, and the stream-receive timeout the
 * CLI builds into its policy. The end-to-end concurrent-isolation coverage
 * lives in `org.strand.runtime.StrandRuntimeIsolationTest`.
 */
class ProgramEvaluationContextTest {

    @Test
    fun `fromPolicy carries verifier nodeTypes plus sandbox and stream timeout into the context`() {
        val nodeTypes: Map<NodeId, TypeExpr> = mapOf(NodeId(7) to TypeExpr.Prim(Primitive.Int))
        val limits = EvaluationLimits.DEFAULTS.copy(streamReceiveTimeoutMillis = 1234L)
        val policy = HostPolicy.OPEN.copy(sandbox = SandboxPolicy.SECURE_DEFAULT, limits = limits)

        val ctx = HostContext.fromPolicy(policy, nodeTypes)

        assertSame(nodeTypes, ctx.verifierNodeTypes, "verifier nodeTypes must thread into the context")
        assertEquals(1234L, ctx.streamReceiveTimeoutMillis)
        assertSame(SandboxPolicy.SECURE_DEFAULT, ctx.sandboxPolicy)
    }

    @Test
    fun `null nodeTypes is carried through as null`() {
        val ctx = HostContext.fromPolicy(HostPolicy.OPEN, null)
        assertNull(ctx.verifierNodeTypes)
    }

    @Test
    fun `the CLI's flag-derived OPEN-base policy projects to a context with that sandbox`() {
        // Mirrors the CLI building an OPEN base with a flag-driven secure
        // sandbox; the projected context must carry the chosen sandbox.
        val cliPolicy = HostPolicy.OPEN.copy(sandbox = SandboxPolicy.SECURE_DEFAULT)
        assertSame(SandboxPolicy.SECURE_DEFAULT, HostContext.fromPolicy(cliPolicy).sandboxPolicy)
    }
}
