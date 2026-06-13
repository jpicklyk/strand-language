package org.strand.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.interpreter.Builtins
import org.strand.interpreter.Value
import java.nio.file.Path

/**
 * Regression net for the clean-room demonstration ([CleanRoomDemo]). Each test
 * asserts the real structural property the corresponding scenario exercises, so
 * the demonstration cannot silently rot: if a future change lets an egress
 * category slip past the closure check, or weakens the admission harm bound, one
 * of these fails. The transcript ([CleanRoomDemo.main]) and these assertions run
 * the same scenario code.
 *
 * The honesty bar: every assertion reflects a property the shipped runtime
 * enforces. No proof is staged — CR1's egress-empty closure is the verifier's own
 * surfaced `rootClosure`, and CR2's `beyondGrant` is computed from the artifact's
 * declared closure, not hardcoded.
 */
class CleanRoomDemoTest {

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    // ------------------------------------------------------------------
    // CR1 — Admit with proof: clean submission verifies, egress closure is
    //       empty by construction, and it runs over a real dataset.
    // ------------------------------------------------------------------

    @Test
    fun `CR1 clean submission has no egress in its closure and is admitted`(@TempDir tmp: Path) {
        val cr1 = CleanRoomDemo.scenarioCR1(tmp)

        // The submission verifies clean, so the host reads the verifier's surfaced
        // effect closure rather than re-deriving it.
        assertTrue(cr1.verifiedClean, "the clean submission must verify")

        // The closure is exactly {Filesystem.Read} — the analytic over the dataset.
        assertEquals(
            setOf("Filesystem.Read"), cr1.surfacedClosure,
            "the surfaced root closure must be the verifier-computed {Filesystem.Read}",
        )

        // The structural proof: the egress categories intersect the closure to ∅.
        // This is not an observation of one run — the admitted graph has no node
        // that can open a socket or spawn a process.
        assertTrue(
            cr1.egressInClosure.isEmpty(),
            "no egress category may appear in the closure; got ${cr1.egressInClosure}",
        )
        assertTrue(
            cr1.cannotExfiltrateByConstruction,
            "empty egress-in-closure is the structural non-exfiltration proof",
        )

        // The host admitted it and ran it over the real dataset; the analytic
        // result is the dataset's byte count, computed with no egress edge present.
        assertTrue(cr1.admitted, "a clean submission with no egress must be admitted")
        assertEquals(
            Value.IntV(cr1.datasetByteCount.toLong()), cr1.analyticResult,
            "the analytic must reduce the dataset to its byte count",
        )
    }

    // ------------------------------------------------------------------
    // CR2 — Reject the exfiltrator: Network.Connect in the closure lands
    //       beyond the clean-room grant, refused before any run.
    // ------------------------------------------------------------------

    @Test
    fun `CR2 exfiltrator has Network_Connect in its closure and is refused before running`() {
        val cr2 = CleanRoomDemo.scenarioCR2()

        // The exfiltrator's closure carries the egress category, computed from the
        // artifact (the structural walk), not hardcoded.
        assertTrue(
            "Network.Connect" in cr2.harmBound.reachable,
            "the exfiltrator's declared closure must include Network.Connect; got ${cr2.harmBound.reachable}",
        )
        // It still reaches the benign read too — it is the clean computation PLUS
        // the egress edge.
        assertTrue(
            "Filesystem.Read" in cr2.harmBound.reachable,
            "the exfiltrator also reaches Filesystem.Read; got ${cr2.harmBound.reachable}",
        )

        // Under the clean-room grant {Filesystem.Read}, beyondGrant is exactly the
        // egress category — the read is within grant, the connect is not.
        assertEquals(
            setOf("Network.Connect"), cr2.harmBound.beyondGrant,
            "beyondGrant must be exactly {Network.Connect}",
        )
        assertFalse(
            cr2.harmBound.withinGrant,
            "the exfiltrator reaches an effect beyond the clean-room grant",
        )

        // The host refuses admission before any execution.
        assertTrue(
            cr2.refusedAdmission,
            "the host must refuse to admit a submission that reaches an egress category",
        )
    }
}
