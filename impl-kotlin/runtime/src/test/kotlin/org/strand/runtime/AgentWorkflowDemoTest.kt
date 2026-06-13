package org.strand.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.interpreter.Builtins
import org.strand.interpreter.DenialPhase
import org.strand.interpreter.InterpretError

/**
 * Regression net for the bounded-agent-workflow demonstration
 * ([AgentWorkflowDemo]). Each test asserts the real property the corresponding
 * scenario exercises, so the demonstration cannot silently rot: if a future
 * change weakens the surfaced bound, the tightness of the grant, the runtime
 * denial of an over-reaching tool, or the deterministic re-run, one of these
 * fails. The transcript ([AgentWorkflowDemo.main]) and these assertions run the
 * same scenario code.
 *
 * The honesty bar (stated in the demo doc): every assertion below reflects a
 * property the shipped runtime enforces. The LLM transport is a deterministic
 * mock, but the surfaced closure, the capability grant, and the denial are all
 * the ones the real verifier and interpreter produce — nothing is staged.
 */
class AgentWorkflowDemoTest {

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    // ------------------------------------------------------------------
    // A1 — Bounded run: surfaced closure, tight grant, completion.
    // ------------------------------------------------------------------

    @Test
    fun `A1 the workflow runs under a grant of exactly its surfaced closure`() {
        val a1 = AgentWorkflowDemo.scenarioA1()

        // The program verifies and its surfaced closure is exactly {LLM.Generate}
        // — the verifier-computed bound, read off VerifyResult.Ok before running.
        assertTrue(a1.verifiedClean, "the agent program must verify clean")
        assertEquals(
            setOf("LLM.Generate"), a1.surfacedClosure,
            "the surfaced root closure must be the verifier-computed {LLM.Generate}",
        )

        // The grant covers exactly the surfaced closure — the bound is tight,
        // not a superset.
        assertEquals(
            a1.surfacedClosure, a1.grantedCategories,
            "the grant must equal the surfaced closure (a tight bound)",
        )
        assertTrue(a1.grantMatchesClosure)

        // The workflow runs to completion under that grant: one LLM turn,
        // producing the deterministic completion.
        assertEquals(1, a1.llmCallCount, "A1 is a single LLM turn (no tool invoked)")
        assertTrue(a1.completed, "the workflow must run to completion under the grant")
        assertEquals(
            "It is sunny and 72F.", a1.resultText,
            "the produced GenerateResult must carry the mock's completion text",
        )
    }

    // ------------------------------------------------------------------
    // A2 — Over-reach contained: ungranted tool effect denied at dispatch.
    // ------------------------------------------------------------------

    @Test
    fun `A2 an ungranted tool effect is denied at the foreign-call boundary`() {
        val a2 = AgentWorkflowDemo.scenarioA2()

        // The over-reach program verifies clean and its surfaced closure is
        // STILL {LLM.Generate} — the ToolDef's Filesystem.Write is intentionally
        // not in the root closure (it fires at dispatch, checked against the
        // live grant). The static bound looks identical to A1's.
        assertTrue(a2.verifiedClean, "the over-reach program must still verify clean")
        assertEquals(
            setOf("LLM.Generate"), a2.surfacedClosure,
            "the tool's Filesystem.Write must NOT appear in the static root closure",
        )

        // The tool reach is denied at runtime with a structured capability denial.
        assertTrue(
            a2.denialError is InterpretError.CapabilityViolation,
            "the ungranted write must be denied with a CapabilityViolation; got ${a2.denialError}",
        )
        assertTrue(a2.denied)

        // The model was driven to invoke the tool: one LLM turn before the denial.
        assertEquals(1, a2.llmCallCount, "the model asked for the tool on its first turn")

        // The denial carries a structured DenialReport naming the denied
        // category and the denying node — the orchestrating-principal surface.
        val report = a2.denialReport
        assertNotNull(report, "the denial must carry a structured DenialReport")
        report!!
        assertEquals("Filesystem.Write", report.category)
        assertNotNull(report.node, "the report names the denying node")
        // The category was absent from the grant entirely (a CapabilityViolation),
        // so the held-grant summary for that category is empty.
        assertTrue(
            report.held?.isEmpty() == true,
            "nothing was held for the denied category; got held=${report.held}",
        )
    }

    @Test
    fun `A2 the denial phase is expression (a plain run, not a transition)`() {
        val a2 = AgentWorkflowDemo.scenarioA2()
        val report = a2.denialReport
        assertNotNull(report)
        // The workflow is driven via StrandRuntime.run (an ordinary expression
        // evaluation), so the denial fires in the Expression phase.
        assertEquals(DenialPhase.Expression, report!!.phase)
    }

    // ------------------------------------------------------------------
    // A3 — Deterministic re-run (scoped).
    // ------------------------------------------------------------------

    @Test
    fun `A3 two runs under the same mock produce a bit-identical result`() {
        val a3 = AgentWorkflowDemo.scenarioA3()

        assertNotNull(a3.firstResult, "the first run must complete")
        assertNotNull(a3.secondResult, "the second run must complete")
        assertEquals(
            a3.firstResult, a3.secondResult,
            "the workflow is a pure function of (program, grant, mock) — re-running is identical",
        )
        assertTrue(a3.identical)
    }
}
