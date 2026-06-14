package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.interpreter.DenialPhase
import org.strand.interpreter.Value

/**
 * Regression net for the supervised multi-agent group demonstration
 * ([MultiAgentSupervisorDemo]). Each test asserts the real orchestration /
 * bound / isolation property its scenario exercises, so the demonstration
 * cannot silently rot: if a future change weakens the verified orchestration,
 * the per-agent effect bound, or the supervised isolation, one of these fails.
 * The transcript ([MultiAgentSupervisorDemo.main]) and these assertions run the
 * same scenario code.
 *
 * The honesty bar: every assertion below reflects a property the shipped async
 * runtime produced. No denial is staged, no survival is hardcoded — MA2's
 * DenialReport is the one the actor runtime actually constructed at C's denial
 * site, and the OK workers' completion is read off the live instance handles.
 */
class MultiAgentSupervisorDemoTest {

    // ------------------------------------------------------------------
    // MA1 — Verified orchestration: concurrent agents, expected outputs.
    // ------------------------------------------------------------------

    @Test
    fun `MA1 the group runs as a verified orchestration and produces the expected outputs`() {
        val ma1 = MultiAgentSupervisorDemo.scenarioMA1()

        // The orchestration is a verified, content-addressed graph, not glue.
        assertTrue(ma1.verified, "the multi-agent orchestration must verify")
        assertTrue(ma1.topologyValid, "the cross-machine topology must validate")

        // Every worker completed normally under a grant covering its write.
        assertFalse(ma1.anyDenial, "no agent should be denied under the all-granted run")
        assertTrue(ma1.allWorkersCompleted, "every worker must complete its work")

        // The workers folded their events deterministically.
        assertEquals(Value.IntV(12), ma1.workerFinalStates["workerA"], "A: 5 + 7 = 12")
        assertEquals(Value.IntV(10), ma1.workerFinalStates["workerB"], "B: 10")
        assertEquals(Value.IntV(3), ma1.workerFinalStates["workerC"], "C: 3")

        // The supervisor folds every worker emission into a running total. Each
        // worker emits its running counter on every transition (A emits {5,12},
        // B {10}, C {3}), so the final aggregate is 5+12+10+3 = 30. Addition
        // commutes, so the total is order-independent across actor interleavings.
        assertTrue(ma1.supervisorOutputs.isNotEmpty(), "the supervisor must emit aggregates")
        assertEquals(30L, ma1.supervisorTotal, "the supervisor's final aggregate is 5 + 12 + 10 + 3 = 30")
    }

    // ------------------------------------------------------------------
    // MA2 — Supervised isolation (keystone): rogue denied, group survives.
    // ------------------------------------------------------------------

    @Test
    fun `MA2 a rogue worker is denied at the actor boundary and the others complete`() {
        val ma2 = MultiAgentSupervisorDemo.scenarioMA2()

        // The rogue worker (C) was denied with a real DenialReport, captured at
        // its actor boundary — not staged.
        val report = ma2.rogueDenial
        assertNotNull(report, "worker C's over-reach must be captured as a DenialReport")
        report!!
        assertEquals("Filesystem.Write", report.category, "the denied category is Filesystem.Write")
        assertEquals(DenialPhase.Transition, report.phase, "the denial fired inside a transition")
        assertNotNull(report.instanceId, "the report names the denied actor instance")
        assertEquals(0, report.eventIndex, "C's first event already over-reaches")
        assertTrue(
            report.requested?.any { MultiAgentSupervisorDemo.PATH_C in it } == true,
            "the report's requested params must include C's actual write path; got ${report.requested}",
        )
        assertTrue(
            report.held?.any { MultiAgentSupervisorDemo.PATH_A in it } == true &&
                report.held?.any { MultiAgentSupervisorDemo.PATH_B in it } == true,
            "the report's held summary must show the A+B grant C exceeded; got ${report.held}",
        )
        assertTrue(ma2.rogueHalted, "the rogue actor must halt cleanly")

        // The OTHER agents kept running and completed -- the group survived.
        assertEquals(Value.IntV(12), ma2.okWorkerFinalStates["workerA"], "A completes (5 + 7 = 12)")
        assertEquals(Value.IntV(10), ma2.okWorkerFinalStates["workerB"], "B completes (10)")
        assertTrue(ma2.okWorkersHalted["workerA"] == true, "A halts on its own input exhaustion, not C's denial")
        assertTrue(ma2.okWorkersHalted["workerB"] == true, "B halts on its own input exhaustion, not C's denial")
        assertTrue(ma2.supervisorOutputs.isNotEmpty(), "the supervisor stayed up and aggregated the survivors")

        // The keystone property: denied AND survived.
        assertTrue(ma2.contained, "a rogue agent is contained; the multi-agent system survives")
    }

    @Test
    fun `MA2 only the rogue worker carries a denial report`() {
        val ma2 = MultiAgentSupervisorDemo.scenarioMA2()
        // Only the rogue (C) holds a report; the survivors (A, B) halted on
        // their own input exhaustion, so their denialReport is null.
        assertNull(ma2.okWorkerDenials["workerA"], "A is not denied under the A+B grant")
        assertNull(ma2.okWorkerDenials["workerB"], "B is not denied under the A+B grant")
        assertNotNull(ma2.rogueDenial, "only C is denied")
    }

    // ------------------------------------------------------------------
    // MA3 — Per-instance replay determinism.
    // ------------------------------------------------------------------

    @Test
    fun `MA3 a worker's recorded events replay to the same final state`() {
        val ma3 = MultiAgentSupervisorDemo.scenarioMA3()

        assertNotNull(ma3.recordedEvents, "the group must have recorded worker A's events")
        assertEquals(
            listOf(Value.IntV(5), Value.IntV(7)), ma3.recordedEvents,
            "worker A consumed exactly its two events in order",
        )
        assertEquals(Value.IntV(12), ma3.liveFinalState, "A's live final state is 12")
        assertEquals(
            ma3.liveFinalState, ma3.replayedFinalState,
            "feeding the recorded events into runMachine reproduces the trajectory",
        )
        assertTrue(ma3.matches, "the replayed trajectory must match the live one")
    }
}
