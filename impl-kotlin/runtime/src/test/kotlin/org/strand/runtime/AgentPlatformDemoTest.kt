package org.strand.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.interpreter.Builtins
import org.strand.interpreter.InterpretError
import org.strand.interpreter.Value
import java.nio.file.Path

/**
 * Regression net for the agent-platform end-to-end demonstration
 * ([AgentPlatformDemo]). The platform story is only as honest as the guarantees
 * it composes: these assertions pin the key property of each of the five
 * lifecycle stages, IN lifecycle order, off a single composed [AgentPlatformDemo.run]
 * pass. If any underlying mechanism rots — an egress category slips the admission
 * closure, the run grant stops covering exactly the surfaced closure, a malformed
 * model response escapes the runtime check, an over-reach is no longer denied, or
 * a replay diverges — one of these fails, and the narrative cannot silently rot.
 *
 * The honesty bar matches the rest of the suite: every assertion reflects a
 * property the shipped runtime enforces, drawn from the demonstration that
 * isolates it. Nothing is staged — the closures are the verifier's own surfaced
 * `rootClosure`, the denials are real `DenialReport`s, the replay is bit-identical
 * over the recorded log.
 *
 * Sequencing all five stages through one [AgentPlatformDemo.run] also proves the
 * scenarios do not cross-contaminate: several install test builtins
 * ([VerifiedLlmOutputDemo] installs `Demo.ScoreModel`, [ContainmentDemo] S2 the
 * rendezvous/leak builtins — though only S1/S4 run here), and each clears its own;
 * the composed run passing is that proof.
 */
class AgentPlatformDemoTest {

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    /**
     * The whole lifecycle, composed once and asserted stage by stage in order.
     * Each block names the stage and the demonstration its guarantee is drawn
     * from, so a failure points straight at the rotted mechanism.
     */
    @Test
    fun `the agent platform lifecycle holds end to end, stage by stage`(@TempDir tmp: Path) {
        val lc = AgentPlatformDemo.run(tmp)

        // ============================================================
        // STAGE 1 — ADMIT: bound it before running.
        // ============================================================

        // ADMIT (clean-room CR1): the support agent is admitted only after the
        // host computes its maximum harm from the artifact and finds no egress.
        val cr1 = lc.admitCleanRoom
        assertTrue(cr1.verifiedClean, "ADMIT: the support agent must verify clean")
        assertEquals(
            setOf("Filesystem.Read"), cr1.surfacedClosure,
            "ADMIT: the surfaced closure is the maximum harm computed from the artifact",
        )
        assertTrue(
            cr1.egressInClosure.isEmpty(),
            "ADMIT: no egress in the closure -> cannot exfiltrate by construction; got ${cr1.egressInClosure}",
        )
        assertTrue(cr1.cannotExfiltrateByConstruction, "ADMIT: empty egress-in-closure is the non-exfiltration proof")
        assertTrue(cr1.admitted, "ADMIT: a clean task within grant is admitted")

        // ADMIT (containment S1): an over-reaching submission is rejected at
        // admission, before any execution.
        val s1 = lc.admitReject
        assertTrue(
            s1.rejected,
            "ADMIT: an over-reaching submission must be REJECTED at admission (verify), before execution",
        )
        assertTrue(
            s1.harmBound.beyondGrant.isNotEmpty(),
            "ADMIT: the over-reach reaches an effect beyond the grant; got ${s1.harmBound.beyondGrant}",
        )

        // ADMIT (clean-room CR2): an exfiltrator with egress in its closure is
        // refused before any run.
        val cr2 = lc.admitExfiltrator
        assertEquals(
            setOf("Network.Connect"), cr2.harmBound.beyondGrant,
            "ADMIT: the exfiltrator's beyondGrant is exactly the egress category",
        )
        assertTrue(cr2.refusedAdmission, "ADMIT: the exfiltrator is refused at admission, before any run")

        // ============================================================
        // STAGE 2 — RUN: bounded execution under exactly the declared caps.
        // ============================================================

        // RUN (bounded-rag R1): the agent retrieves from one refined index and the
        // grant covers exactly the surfaced closure — nothing more.
        val r1 = lc.runRag
        assertTrue(r1.verifiedClean, "RUN: the RAG task must verify clean")
        assertEquals(
            setOf("LLM.Embed", "LLM.Generate", "Vector.Read"), r1.surfacedClosure,
            "RUN: the surfaced closure is the agent's data-access manifest",
        )
        assertTrue(r1.grantMatchesClosure, "RUN: the grant must cover exactly the surfaced closure")
        assertTrue(r1.allThreeStagesRan, "RUN: embed/query/generate each ran once under the bound")
        assertTrue(r1.retrievedHitCount > 0, "RUN: the refined index returned real hits")
        assertNotNull(r1.resultValue, "RUN: the bounded pipeline produced a result")

        // RUN (agent-workflow A1): the model call runs under exactly {LLM.Generate}.
        val a1 = lc.runAgent
        assertEquals(setOf("LLM.Generate"), a1.surfacedClosure, "RUN: the agent's bound is {LLM.Generate}")
        assertTrue(a1.grantMatchesClosure, "RUN: the grant covers exactly the agent's surfaced closure")
        assertTrue(a1.completed, "RUN: the agent produced its model result under the bound")

        // ============================================================
        // STAGE 3 — VERIFY: the model's output is checked.
        // ============================================================

        // VERIFY (verified-llm-output VO1): a valid structured response is
        // constrained and passes the runtime invariant.
        val vo1 = lc.verifyValid
        assertTrue(vo1.verifiedClean, "VERIFY: the constrain-then-verify pipeline must verify clean")
        assertEquals(1, vo1.modelCallCount, "VERIFY: the value originates from a genuine model call")
        assertTrue(vo1.completed, "VERIFY: a valid response passes the runtime invariant")
        assertEquals(
            VerifiedLlmOutputDemo.VALID_SCORE, vo1.resultScore,
            "VERIFY: the validated result is the in-range score the model returned",
        )

        // VERIFY (verified-llm-output VO2): a semantically-invalid response is
        // contained at the value-flow site before output.
        val vo2 = lc.verifyInvalid
        assertEquals(1, vo2.modelCallCount, "VERIFY: the invalid value is a genuine model response")
        assertTrue(vo2.raisedAtRuntime, "VERIFY: an out-of-range response raises at runtime, before output")
        assertNotNull(vo2.violation, "VERIFY: the runtime raised a real SchemaInvariantViolation")
        assertTrue(vo2.contained, "VERIFY: the malformed model output is contained before it escapes")

        // ============================================================
        // STAGE 4 — WITHSTAND: attacks are contained.
        // ============================================================

        // WITHSTAND (agent-workflow A2): an over-reaching tool effect is denied at
        // dispatch — untrusted CODE bounded.
        val a2 = lc.withstandTool
        assertTrue(a2.denied, "WITHSTAND: an over-reaching tool effect must be denied at dispatch")
        assertNotNull(a2.denialReport, "WITHSTAND: the tool denial carries a structured DenialReport")

        // WITHSTAND (bounded-rag R2): a query against a non-granted index is denied
        // before any request leaves the process.
        val r2 = lc.withstandStore
        assertTrue(r2.denied, "WITHSTAND: a query against a non-granted index must be denied")
        assertTrue(r2.denialError is InterpretError.RefinementViolation, "WITHSTAND: the store denial is a RefinementViolation")
        assertNotNull(r2.denialReport, "WITHSTAND: the store denial carries a structured DenialReport")
        assertTrue(r2.queryNeverIssued, "WITHSTAND: the denied query never reaches the wire")

        // WITHSTAND (skill-workflow SK2a): a poisoned input is contained as data —
        // the action set is graph structure, fixed at admission.
        val sk = lc.withstandPoison
        assertTrue(
            sk.closuresIdentical,
            "WITHSTAND: the closure is computed from the actuator, so poisoned data cannot change it",
        )
        assertTrue(sk.injectionContained, "WITHSTAND: the injection text lands as inert digest content")
        assertTrue(
            sk.egressInClosure.isEmpty(),
            "WITHSTAND: a poisoned decision adds no action (no egress); got ${sk.egressInClosure}",
        )

        // WITHSTAND (multi-agent-supervisor MA2): a rogue agent is denied while the
        // group survives.
        val ma = lc.withstandRogue
        assertNotNull(ma.rogueDenial, "WITHSTAND: the rogue worker is denied with a structured DenialReport")
        assertTrue(ma.rogueHalted, "WITHSTAND: the rogue actor halts cleanly")
        assertTrue(
            ma.okWorkerFinalStates.values.all { it != null },
            "WITHSTAND: the other agents keep running and complete",
        )
        assertTrue(ma.supervisorOutputs.isNotEmpty(), "WITHSTAND: the supervisor stays up")
        assertTrue(ma.contained, "WITHSTAND: the rogue is contained AND the group survives")

        // ============================================================
        // STAGE 5 — AUDIT: reproducible and content-addressed.
        // ============================================================

        // AUDIT (replay-timetravel R1): the run replays bit-identically, with zero
        // live IO.
        val rp = lc.auditReplay
        assertTrue(rp.replayTouchedNoLiveIo, "AUDIT: replay performs zero live IO")
        assertTrue(rp.trajectoriesIdentical, "AUDIT: the replay trajectory is bit-identical to the live run")
        assertTrue(rp.outputsIdentical, "AUDIT: the replayed outputs are bit-identical")
        assertTrue(
            rp.replayMatchesLiveButContrastDiffers,
            "AUDIT: replay reproduces the live run while a fresh live run genuinely differs",
        )

        // AUDIT (containment S4): the task is admitted-once and re-run by hash with
        // an identical result.
        val s4 = lc.auditByHash
        assertTrue(s4.verdictCachedAfterIngest, "AUDIT: the verdict is recorded at ingest (admit-and-verify-once)")
        assertNotNull(s4.firstRunValue, "AUDIT: the first admission produced a value")
        assertEquals(s4.firstRunValue, s4.byHashRunValue, "AUDIT: the run-by-hash reproduces the value")
        assertTrue(s4.identicalResult, "AUDIT: re-running by content hash yields the identical result")
    }

    /**
     * The two denials the WITHSTAND stage shows are genuinely different error
     * kinds at different boundaries — a coarse cross-check that the composed
     * `DenialReport`s are not the same object surfaced twice. The tool over-reach
     * is denied at the foreign-call boundary inside the tool-dispatch loop; the
     * store over-reach is a `RefinementViolation` at the vector-call boundary.
     */
    @Test
    fun `WITHSTAND surfaces distinct denials at distinct boundaries`(@TempDir tmp: Path) {
        val lc = AgentPlatformDemo.run(tmp)

        val toolReport = lc.withstandTool.denialReport
        val storeReport = lc.withstandStore.denialReport
        assertNotNull(toolReport, "the tool over-reach must produce a DenialReport")
        assertNotNull(storeReport, "the store over-reach must produce a DenialReport")

        // The store denial names a Vector.Read refinement; the tool denial is a
        // Filesystem.Write category. The categories must differ.
        assertFalse(
            toolReport!!.category == storeReport!!.category,
            "the tool and store denials are over different effect categories",
        )
        // And the rogue-agent denial is yet a third, carried on the async actor
        // path (it has an instanceId; the synchronous denials do not).
        assertNotNull(lc.withstandRogue.rogueDenial, "the rogue worker denial must be present")
        assertNotNull(
            lc.withstandRogue.rogueDenial!!.instanceId,
            "the actor-boundary denial carries a machine instance id",
        )
    }
}
