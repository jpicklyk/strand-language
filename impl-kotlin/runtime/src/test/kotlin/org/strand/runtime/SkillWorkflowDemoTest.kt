package org.strand.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.interpreter.Builtins
import org.strand.interpreter.DenialPhase
import org.strand.interpreter.InterpretError
import org.strand.interpreter.Value
import java.nio.file.Path

/**
 * Regression net for the skill-workflow demonstration ([SkillWorkflowDemo]). Each
 * test asserts the real property the corresponding scenario exercises, so the
 * demonstration cannot silently rot: if a future change weakens the surfaced-closure
 * manifest, the data-containment of a poisoned note, the refinement bound on the
 * model-supplied path, or replay determinism, one of these fails. The transcript
 * ([SkillWorkflowDemo.main]) and these assertions run the same scenario code.
 *
 * The honesty bar: every assertion below reflects a property the shipped runtime
 * enforces. No closure is hardcoded — each is the verifier-computed `rootClosure`.
 * No denial is staged — SK2b's DenialReport is the one the interpreter actually
 * constructed at the refinement-denial site. No digest is fabricated — every file
 * content is read back from the temp file the real run wrote.
 */
class SkillWorkflowDemoTest {

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    // ------------------------------------------------------------------
    // SK1 — The actuator runs under its published bound.
    // ------------------------------------------------------------------

    @Test
    fun `SK1 actuator runs under its surfaced-closure bound and writes the model's content`(@TempDir tmp: Path) {
        val sk1 = SkillWorkflowDemo.scenarioSK1(tmp)

        assertTrue(sk1.verifiedClean, "the benign actuator must verify clean")

        // The published harm-bound manifest is exactly {Filesystem.Write} — read off
        // the verifier's own rootClosure, not hardcoded.
        assertEquals(
            setOf("Filesystem.Write"), sk1.surfacedClosure,
            "the surfaced closure must be the verifier-computed {Filesystem.Write}",
        )
        // No egress category appears in the closure: the manifest carries no socket.
        assertTrue(
            sk1.egressInClosure.isEmpty(),
            "no egress category may appear in the actuator's manifest; got ${sk1.egressInClosure}",
        )

        // The graph acted: a real write occurred (the byte count is the run result).
        val written = sk1.bytesWritten
        assertTrue(
            written is Value.IntV && written.v > 0L,
            "the actuator must write a non-empty digest; got $written",
        )
        // The model's decision flowed into the written digest (read back from disk).
        assertTrue(
            sk1.contentCarriesModelData,
            "the written digest must carry the model's note content; got:\n${sk1.fileContent}",
        )
    }

    // ------------------------------------------------------------------
    // SK2a — The action set is graph structure (keystone, part 1).
    // ------------------------------------------------------------------

    @Test
    fun `SK2a a poisoned note is contained as data and cannot expand the action set`(@TempDir tmp: Path) {
        val sk2a = SkillWorkflowDemo.scenarioSK2a(tmp)

        // The keystone: the surfaced closure is IDENTICAL regardless of what the
        // decisions say, because it is computed from the actuator, not the data.
        assertEquals(
            setOf("Filesystem.Write"), sk2a.benignClosure,
            "the benign actuator's surfaced closure must be {Filesystem.Write}",
        )
        assertEquals(
            sk2a.benignClosure, sk2a.poisonedClosure,
            "the poisoned-content actuator's closure must EQUAL the benign one " +
                "(the closure is the actuator, not the decisions)",
        )
        assertTrue(sk2a.closuresIdentical, "closures must be identical across the two programs")

        // The write to report.md succeeded and the injection text is CONTAINED as
        // inert digest content (read back from disk) — no other effect occurred.
        val written = sk2a.bytesWritten
        assertTrue(
            written is Value.IntV && written.v > 0L,
            "the poisoned-content actuator must still write to report.md; got $written",
        )
        assertTrue(
            sk2a.injectionContained,
            "the injection text must land as contained digest content; got:\n${sk2a.fileContent}",
        )
        // The closure is still just {Filesystem.Write}: the poisoned note added no
        // action — no egress, no new effect category.
        assertTrue(
            sk2a.egressInClosure.isEmpty(),
            "a poisoned decision must not add any egress action; got ${sk2a.egressInClosure}",
        )
    }

    // ------------------------------------------------------------------
    // SK2b — The model parameter is bounded by refinement (keystone, part 2).
    // ------------------------------------------------------------------

    @Test
    fun `SK2b a model-supplied path escape is denied by the refined capability`(@TempDir tmp: Path) {
        val sk2b = SkillWorkflowDemo.scenarioSK2b(tmp)

        // The actuator admits cleanly — the escape is data, not a structural defect.
        assertTrue(sk2b.verified, "the poisoned-path actuator must admit cleanly (the path is data)")

        // The denial is a real RefinementViolation carrying a structured DenialReport.
        assertTrue(
            sk2b.denialError is InterpretError.RefinementViolation,
            "the out-of-grant write must be denied with a RefinementViolation; got ${sk2b.denialError}",
        )
        val report = sk2b.denialReport
        assertNotNull(report, "the denial must carry a structured DenialReport")
        report!!

        // The report's fields are the ones the interpreter constructed at the denial
        // site: the denied category, the requested escape path, the held grant.
        assertEquals("Filesystem.Write", report.category)
        assertEquals(DenialPhase.Expression, report.phase)
        assertNotNull(report.node, "the report must name the denying node")
        assertTrue(
            report.requested?.any { "../../etc/shadow" in it } == true,
            "the report's requested params must include the model-supplied escape path; got ${report.requested}",
        )
        assertTrue(
            report.held?.any { "report.md" in it } == true,
            "the report's held summary must include the granted report path; got ${report.held}",
        )

        // The denial fired before any IO: nothing reached the escape target.
        assertTrue(
            sk2b.shadowFileAbsent,
            "the escape target must not have been written (the denial precedes IO)",
        )
    }

    // ------------------------------------------------------------------
    // SK3 — The skill replays deterministically.
    // ------------------------------------------------------------------

    @Test
    fun `SK3 the same decisions replay to a byte-identical digest`(@TempDir tmp: Path) {
        val sk3 = SkillWorkflowDemo.scenarioSK3(tmp)

        assertTrue(sk3.firstDigest.isNotEmpty(), "the first run must write a non-empty digest")
        assertTrue(
            sk3.byteIdentical,
            "two runs under the same decisions must write a byte-identical digest",
        )
    }
}
