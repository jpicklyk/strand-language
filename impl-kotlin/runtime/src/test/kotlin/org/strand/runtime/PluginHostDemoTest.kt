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
 * Regression net for the plugin-host demonstration ([PluginHostDemo]). Each
 * test asserts the real attenuation / confused-deputy property the corresponding
 * scenario exercises, so the demonstration cannot silently rot: if a future
 * change weakens the Q-039 projection check, the Q-031 refinement match, or the
 * grant-level / category-level narrowing, one of these fails. The transcript
 * ([PluginHostDemo.main]) and these assertions run the same scenario code.
 *
 * The honesty bar (stated in the demo doc): every asserted denial/rejection is
 * the one the verifier/interpreter actually produces. C2's `ProjectionMismatch`
 * is the real verifier error; C3's and C4's DenialReports are the ones the
 * interpreter constructed at the denial site; nothing is staged.
 */
class PluginHostDemoTest {

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    // ------------------------------------------------------------------
    // C1 — Legitimate scoped operation: scoped write succeeds.
    // ------------------------------------------------------------------

    @Test
    fun `C1 a plugin granted its own slice writes there and succeeds`(@TempDir tmp: Path) {
        val c1 = PluginHostDemo.scenarioC1(tmp.resolve("plugins"))

        assertTrue(c1.verified, "the committed scoped-write plugin must admit cleanly")
        // The write returned the byte count (Fs.Write returns an Int) — it ran.
        assertTrue(c1.value is Value.IntV, "the scoped write must run to a byte count; got ${c1.value}")
        assertTrue(c1.wroteToOwnDir, "the file must land inside the plugin's own directory: ${c1.writtenPath}")
    }

    // ------------------------------------------------------------------
    // C2 — Argument drift blocked at verification (Q-039).
    // ------------------------------------------------------------------

    @Test
    fun `C2 argument-drift confused deputy is rejected at admission with ProjectionMismatch`() {
        val c2 = PluginHostDemo.scenarioC2()

        assertTrue(c2.rejected, "the drift plugin must be rejected at admission, before any execution")
        assertTrue(
            c2.diagnostics.any { "ProjectionMismatch" in it },
            "the rejection must be the structured Q-039 projection-drift error; got ${c2.diagnostics}",
        )
    }

    // ------------------------------------------------------------------
    // C3 — Out-of-scope escalation denied at runtime (Q-031 + Q-064).
    // ------------------------------------------------------------------

    @Test
    fun `C3 an out-of-scope write is denied at runtime with a real DenialReport`() {
        val c3 = PluginHostDemo.scenarioC3()

        assertTrue(c3.verified, "the honest out-of-scope plugin verifies clean (its projection agrees)")
        assertTrue(
            c3.denialError is InterpretError.RefinementViolation,
            "the out-of-scope write must be a RefinementViolation (category granted, path not covered); got ${c3.denialError}",
        )
        val report = c3.denialReport
        assertNotNull(report, "the denial must carry a structured DenialReport")
        report!!
        assertEquals("Filesystem.Write", report.category)
        assertEquals(DenialPhase.Expression, report.phase)
        assertNotNull(report.node, "the report names the denying node")
        assertTrue(
            report.requested?.any { "/plugins/B/secret.json" in it } == true,
            "the report's requested params must include the real out-of-scope target; got ${report.requested}",
        )
        assertTrue(
            report.held?.any { "/plugins/A/state.json" in it } == true,
            "the report's held summary must show the granted /plugins/A slice; got ${report.held}",
        )
        // The held grant must NOT include the out-of-scope target — the denial is
        // exactly because the slice does not cover it.
        assertTrue(
            report.held?.none { "/plugins/B" in it } == true,
            "the held grant must not cover any /plugins/B path; got ${report.held}",
        )
    }

    // ------------------------------------------------------------------
    // C4 — Attenuation below the host: grant-level narrowing.
    // ------------------------------------------------------------------

    @Test
    fun `C4 a plugin under a narrowed grant cannot reach a path the host could`(@TempDir tmp: Path) {
        val c4 = PluginHostDemo.scenarioC4(tmp.resolve("plugins"))

        // The host's broad authority genuinely reaches the sibling path — a real
        // write — so the attenuation below is real, not host incapacity.
        assertTrue(
            c4.hostCouldReachSibling,
            "with broad authority the host itself writes the sibling path (so the plugin's denial is attenuation, not incapacity)",
        )
        // The plugin, under the narrowed grant, is denied at the capability check
        // before any IO.
        assertTrue(
            c4.denialError is InterpretError.RefinementViolation,
            "the scoped plugin must be denied reaching the sibling path; got ${c4.denialError}",
        )
        val report = c4.denialReport
        assertNotNull(report, "the denial must carry a structured DenialReport")
        report!!
        assertEquals("Filesystem.Write", report.category)
        assertEquals(DenialPhase.Expression, report.phase)
        assertTrue(
            report.requested?.any { it.endsWith("/B/config.json") } == true,
            "the report's requested params must include the sibling target; got ${report.requested}",
        )
        assertTrue(
            report.held?.all { "/A/" in it || it.endsWith("/A/state.json") } == true && report.held!!.isNotEmpty(),
            "the held grant must be confined to the plugin's own slice; got ${report.held}",
        )
        // The held grant must not cover the sibling directory.
        assertTrue(
            report.held?.none { "/B/" in it } == true,
            "the held grant must not cover any sibling-dir path; got ${report.held}",
        )
    }

    // ------------------------------------------------------------------
    // C4' — Companion: N-036 CapabilityScope category-level narrowing,
    //       enforced at admission.
    // ------------------------------------------------------------------

    @Test
    fun `C4 prime CapabilityScope rejects a body whose effect exceeds the scope at admission`() {
        val cs = PluginHostDemo.scenarioC4CategoryScope()

        // The CapabilityScope (the N-036 node) retains only Read; its body
        // performs a Write. The verifier proves the body's effect closure exceeds
        // the retained set and rejects the program AT ADMISSION — category-level
        // narrowing enforced structurally, before execution.
        assertTrue(cs.rejected, "a CapabilityScope retaining only Read must reject a Write-performing body at admission")
        assertTrue(
            cs.diagnostics.any { "CapabilityScopeUnsatisfiable" in it },
            "the rejection must be the structured CapabilityScopeUnsatisfiable error; got ${cs.diagnostics}",
        )
    }
}
