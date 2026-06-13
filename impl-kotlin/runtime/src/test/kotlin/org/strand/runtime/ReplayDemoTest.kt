package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import java.nio.file.Path

/**
 * Regression net for the replay / time-travel / restart-resume demonstration
 * ([ReplayDemo]). Each test asserts the real property the corresponding scenario
 * exercises, so the demonstration cannot silently rot: if a future change breaks
 * deterministic replay, time-travel state inspection, or restart-resume
 * equivalence, one of these fails. The transcript ([ReplayDemo.main]) and these
 * assertions run the same scenario code.
 *
 * The honesty bar (stated in the demo doc): every assertion below reflects a
 * property the shipped runtime enforces. No trajectory is faked — R1's
 * bit-identity is over the actual recorded events re-fed through `runMachine`
 * under a clock that throws if read, and R3's equivalence is the actual
 * snapshot-resume-vs-uninterrupted comparison the facade produces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayDemoTest {

    // ------------------------------------------------------------------
    // Soundness preamble: the transition is pure, so the verifier raises
    // no NondeterministicInReplayContext warning (Q-065) — the basis for
    // the bit-identical replay the scenarios assert.
    // ------------------------------------------------------------------

    @Test
    fun `the ledger machine verifies clean with no replay-nondeterminism warning`() {
        val ledger = ReplayDemo.loadLedger()
        assertTrue(
            ledger.verify is VerifyResult.Ok,
            "the ledger program must verify; got ${ledger.verify}",
        )
        assertTrue(
            ReplayDemo.nondeterminismWarnings(ledger.verify).isEmpty(),
            "a pure transition (Int.Add only) must raise no NondeterministicInReplayContext warning",
        )
    }

    // ------------------------------------------------------------------
    // R1 — Deterministic replay: bit-identical trajectory, zero live IO.
    // ------------------------------------------------------------------

    @Test
    fun `R1 replay reproduces the live trajectory bit-identically with zero live IO`() {
        val r1 = ReplayDemo.scenarioR1()

        // Bit-identical state trajectory and per-step outputs across live + replay.
        assertEquals(
            r1.liveTrajectory, r1.replayTrajectory,
            "the replayed state trajectory must be bit-identical to the live run",
        )
        assertTrue(r1.trajectoriesIdentical, "replay must reproduce the full state trajectory")
        assertEquals(
            r1.liveOutputs, r1.replayOutputs,
            "the replayed per-step outputs must be bit-identical to the live run",
        )
        assertTrue(r1.outputsIdentical, "replay must reproduce the emitted outputs")

        // Zero live IO: the replay ran under a clock that throws if read; a clean
        // run proves the replay path touched no live clock/IO.
        assertTrue(
            r1.replayTouchedNoLiveIo,
            "replay must perform zero live IO — the throwing clock must never be read",
        )

        // "Live IO would differ but replay doesn't" — concrete: a fresh live run
        // under a different clock genuinely diverges (its lastStamp differs), yet
        // replay of the first run's record reproduces the first run regardless.
        assertNotEquals(
            r1.liveTrajectory, r1.contrastTrajectory,
            "a fresh live run under a different clock must genuinely differ (live IO is real)",
        )
        assertTrue(
            r1.replayMatchesLiveButContrastDiffers,
            "replay must equal the recorded live run while a fresh live run differs",
        )

        // The recorded stamps actually came from the live clock (not zeros): the
        // demonstration is over a real IO-sourced value threaded as data.
        assertTrue(
            r1.recordedEvents.all { (it as Value.ProductV).fields.getValue("stamp") != Value.IntV(0L) },
            "every recorded event must carry a live-clock-sourced stamp",
        )

        // The trajectory is the expected running balance.
        val balances = r1.replayTrajectory.map {
            ((it as Value.ProductV).fields.getValue("balance") as Value.IntV).v
        }
        assertEquals(ReplayDemo.expectedBalances(), balances, "the balance trajectory must be the running sum")
    }

    // ------------------------------------------------------------------
    // R2 — Time-travel: read the exact state at any event index.
    // ------------------------------------------------------------------

    @Test
    fun `R2 the recorded run exposes the exact state at every event index`() {
        val r2 = ReplayDemo.scenarioR2()

        // One state-after per event; landing on any index reads its exact state.
        assertEquals(
            ReplayDemo.AMOUNTS.size, r2.stateAtEachIndex.size,
            "there must be one state-after per recorded event",
        )

        // The balance at each index is the running sum up to that index — the
        // "step back to event N" read is exact, not approximate.
        assertEquals(
            ReplayDemo.expectedBalances(), r2.balanceAtEachIndex,
            "the balance at each index must be the running sum to that index",
        )

        // Coherence: the state at index i records count == i+1.
        assertTrue(
            r2.countsAreConsecutive,
            "the state at index i must record count == i+1 (a coherent trajectory)",
        )

        // The final state matches the last step-after.
        assertEquals(
            r2.stateAtEachIndex.last(), r2.finalState,
            "the halt's finalState must equal the last step's state-after",
        )

        // Spot-check a specific mid-history landing: after event 1, balance is 70.
        val afterEvent1 = r2.stateAtEachIndex[1] as Value.ProductV
        assertEquals(
            Value.IntV(70L), afterEvent1.fields.getValue("balance"),
            "stepping back to event 1 must read balance=70 (100 - 30)",
        )
    }

    // ------------------------------------------------------------------
    // R3 — Snapshot -> restart -> resume equivalence.
    // ------------------------------------------------------------------

    @Test
    fun `R3 a resumed run in a fresh runtime equals an uninterrupted single run`(@TempDir tmp: Path) =
        runTest {
            val r3 = ReplayDemo.scenarioR3(this, tmp)

            // The snapshot captured the partway state (3 of 6 events: 100-30+50 = 120).
            assertEquals(3L, r3.processedEventCount, "the snapshot must record 3 processed events")
            assertEquals(
                Value.IntV(120L),
                (r3.snapshotState as Value.ProductV).fields.getValue("balance"),
                "the snapshot's balance must be 120 after the first 3 events",
            )

            // The post-snapshot trajectory equals the matching tail of an
            // uninterrupted single run — restart-resume equivalence.
            assertEquals(
                r3.uninterruptedTail, r3.resumedTrajectory,
                "the resumed post-snapshot trajectory must equal the uninterrupted run's tail",
            )
            assertTrue(r3.trajectoriesIdentical, "restart-resume must reproduce the post-snapshot trajectory")
            assertEquals(
                r3.uninterruptedTailOutputs, r3.resumedOutputs,
                "the resumed per-step outputs must equal the uninterrupted run's tail",
            )
            assertTrue(r3.outputsIdentical, "restart-resume must reproduce the emitted outputs")

            // The final states match (full run: 100-30+50+200-120+25 = 225).
            assertTrue(r3.finalStatesIdentical, "the resumed final state must equal the uninterrupted final state")
            assertEquals(
                Value.IntV(225L),
                (r3.resumedFinalState as Value.ProductV).fields.getValue("balance"),
                "the resumed final balance must be 225",
            )

            // The snapshot really left the process via disk (the file exists and
            // was read back into a fresh runtime).
            assertTrue(
                java.nio.file.Files.exists(r3.snapshotFile),
                "the snapshot must have been written to disk",
            )
        }

    // ------------------------------------------------------------------
    // The expected running balance is what the demo claims it is.
    // ------------------------------------------------------------------

    @Test
    fun `the expected balance trajectory matches the documented values`() {
        assertEquals(
            listOf(100L, 70L, 120L, 320L, 200L, 225L), ReplayDemo.expectedBalances(),
            "the documented balance trajectory must be the running sum of the AMOUNTS",
        )
    }
}
