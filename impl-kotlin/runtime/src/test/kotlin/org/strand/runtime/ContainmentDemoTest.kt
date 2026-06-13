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
import org.strand.interpreter.SandboxViolationKind
import org.strand.interpreter.Value
import java.nio.file.Path

/**
 * Regression net for the containment demonstration ([ContainmentDemo]). Each
 * test asserts the real containment property the corresponding scenario
 * exercises, so the demonstration cannot silently rot: if a future change
 * weakens admission rejection, concurrent isolation, runtime denial, or
 * run-by-hash equivalence, one of these fails. The transcript ([ContainmentDemo.main])
 * and these assertions run the same scenario code.
 *
 * The honesty bar (stated in the demo doc): every assertion below reflects a
 * property the shipped runtime enforces. No denial is staged, no harm bound is
 * hardcoded — S1's bound is computed from the artifact, S3's DenialReport is the
 * one the interpreter actually constructed at the denial site.
 */
class ContainmentDemoTest {

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    // ------------------------------------------------------------------
    // S1 — Admission rejection: over-reach rejected at verify, harm bound
    //      computed from the artifact.
    // ------------------------------------------------------------------

    @Test
    fun `S1 over-reaching submission is rejected at admission`() {
        val s1 = ContainmentDemo.scenarioS1()

        // Rejected at verify (not run) — harm bounded BEFORE execution.
        assertTrue(s1.rejected, "the over-reaching program must be rejected at admission")
        assertTrue(
            s1.diagnostics.any { "ProjectionMismatch" in it },
            "rejection must be the structured Q-039 projection-drift error; got ${s1.diagnostics}",
        )

        // The harm bound is computed from the artifact, not hardcoded: the writer
        // reaches Filesystem.Write, which is beyond a Time.Now-only grant.
        assertTrue(
            "Filesystem.Write" in s1.harmBound.reachable,
            "the artifact's declared closure must include Filesystem.Write; got ${s1.harmBound.reachable}",
        )
        assertTrue(
            "Filesystem.Write" in s1.harmBound.beyondGrant,
            "Filesystem.Write must be beyond the {Time.Now} grant",
        )
        assertFalse(s1.harmBound.withinGrant, "the program must reach an effect beyond the grant")
    }

    // ------------------------------------------------------------------
    // S2 — Concurrent isolation: sandbox + credential, under forced overlap.
    // ------------------------------------------------------------------

    @Test
    fun `S2 concurrent distrusting tenants are sandbox- and credential-isolated`(@TempDir tmp: Path) {
        val s2 = ContainmentDemo.scenarioS2(tmp)

        // Sandbox: each tenant denied reading the other's workspace.
        assertEquals(
            SandboxViolationKind.FsPathEscape, s2.tenantADeniedKind,
            "tenant A must be denied reading tenant B's workspace (FsPathEscape)",
        )
        assertEquals(
            SandboxViolationKind.FsPathEscape, s2.tenantBDeniedKind,
            "tenant B must be denied reading tenant A's workspace (FsPathEscape)",
        )
        assertTrue(s2.sandboxIsolated, "both tenants must be confined to their own workspace")

        // Credential: identical error text, per-tenant redaction. A's scrubber
        // redacts A's key but not B's; symmetrically for B.
        assertFalse(s2.tenantAKey in s2.tenantAErrorDetail, "tenant A's own key must be redacted in A's error")
        assertTrue(s2.tenantBKey in s2.tenantAErrorDetail, "tenant B's key must NOT be scrubbed by A's scrubber")
        assertFalse(s2.tenantBKey in s2.tenantBErrorDetail, "tenant B's own key must be redacted in B's error")
        assertTrue(s2.tenantAKey in s2.tenantBErrorDetail, "tenant A's key must NOT be scrubbed by B's scrubber")
        assertTrue(s2.credentialIsolated, "each tenant's credential must stay within its own context's scrubber")
    }

    // ------------------------------------------------------------------
    // S3 — Runtime denial: DenialReport emitted, co-tenant survives.
    // ------------------------------------------------------------------

    @Test
    fun `S3 runtime denial emits a DenialReport and the co-tenant completes`() {
        val s3 = ContainmentDemo.scenarioS3()

        // The denial is a real RefinementViolation, not a staged one.
        assertTrue(
            s3.denialError is InterpretError.RefinementViolation,
            "the out-of-grant write must be denied with a RefinementViolation; got ${s3.denialError}",
        )
        val report = s3.denialReport
        assertNotNull(report, "the denial must carry a structured DenialReport")
        report!!

        // The report's fields are the ones the interpreter constructed at the
        // denial site: the denied category, the requested path, the held grant.
        assertEquals("Filesystem.Write", report.category)
        assertEquals(DenialPhase.Expression, report.phase)
        assertNotNull(report.node, "the report names the denying node")
        assertTrue(
            report.requested?.any { "/tenant/secret.log" in it } == true,
            "the report's requested params must include the actual target path; got ${report.requested}",
        )
        assertTrue(
            report.held?.any { "/tenant/out.log" in it } == true,
            "the report's held summary must include the granted path; got ${report.held}",
        )

        // The co-tenant submitted in the same batch is unaffected.
        assertEquals(Value.IntV(42), s3.coTenantValue, "the benign co-tenant must complete to 42")

        // Q-067 success path: this clean-verifying program lets the host read the
        // verifier's own effect closure off VerifyResult.Ok rather than walking.
        // The program reaches Filesystem.Write and has no Handler, so the surfaced
        // closure is exactly {Filesystem.Write} and equals the structural walk.
        assertEquals(
            setOf("Filesystem.Write"), s3.surfacedClosure,
            "the surfaced root closure must be the verifier-computed {Filesystem.Write}",
        )
        assertTrue(
            s3.surfacedEqualsWalk,
            "for this Handler-free program the surfaced closure must equal the structural walk; " +
                "surfaced=${s3.surfacedClosure} walk=${s3.walkedClosure}",
        )
    }

    // ------------------------------------------------------------------
    // S4 — Run-by-hash equivalence.
    // ------------------------------------------------------------------

    @Test
    fun `S4 run-by-hash reuses the verdict and yields an identical result`(@TempDir tmp: Path) {
        val s4 = ContainmentDemo.scenarioS4(tmp.resolve("store"))

        assertEquals(Value.IntV(42), s4.firstRunValue, "the first run must produce 42")
        assertTrue(s4.verdictCachedAfterIngest, "ingest must record a verify verdict keyed by root hash")
        assertEquals(
            s4.firstRunValue, s4.byHashRunValue,
            "the run-by-hash result must equal the run-from-image result",
        )
        assertTrue(s4.identicalResult, "run-by-hash must be value-identical to the first run")
    }
}
