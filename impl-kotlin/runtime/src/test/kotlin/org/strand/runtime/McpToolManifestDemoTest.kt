package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression net for the verifiable-tool-capability-manifest demonstration
 * ([McpToolManifestDemo]). Each test asserts the real property the corresponding
 * scenario exercises, so the demonstration cannot silently rot: if a future
 * change weakens manifest certification, the honesty (exact-equality) rule, or
 * the content-addressing of the manifest, one of these fails. The transcript
 * ([McpToolManifestDemo.main]) and these assertions run the same scenario code.
 *
 * The honesty bar: every assertion below reflects a property the shipped verifier
 * enforces. No rejection is staged — M2's mismatch is the structured
 * `ManifestExportEffectMismatch` the verifier actually produced; M3's hashes are
 * the ones the canonical encoder actually computed.
 */
class McpToolManifestDemoTest {

    // ------------------------------------------------------------------
    // M1 — Manifest as machine-checked contract.
    // ------------------------------------------------------------------

    @Test
    fun `M1 well-formed manifest verifies and renders verified per-export effects`() {
        val m1 = McpToolManifestDemo.scenarioM1()

        // The manifest is admitted precisely because each export's declaredEffects
        // equals its effect surface.
        assertTrue(m1.admitted, "the well-formed manifest must be admitted (verified)")

        // Two exports, in declaration order: the read-only lookup and the pure formatter.
        assertEquals(2, m1.exports.size, "the bundle declares exactly two tool exports")

        val lookup = m1.exports.first { it.displayName == "fs.lookup" }
        val format = m1.exports.first { it.displayName == "text.format" }

        // The verified capability statement: the lookup tool can ONLY read; the
        // formatter is pure. These are checked against the code, not claimed.
        assertEquals(
            listOf("Filesystem.Read"), lookup.declaredEffects,
            "the lookup tool's verified surface must be exactly {Filesystem.Read}",
        )
        assertTrue(
            format.declaredEffects.isEmpty(),
            "the format tool's verified surface must be empty (pure); got ${format.declaredEffects}",
        )
    }

    // ------------------------------------------------------------------
    // M2 — Honesty enforced: under-declaration rejected.
    // ------------------------------------------------------------------

    @Test
    fun `M2 under-declaring manifest is rejected with ManifestExportEffectMismatch`() {
        val m2 = McpToolManifestDemo.scenarioM2()

        // Rejected at verify — the manifest cannot be published.
        assertTrue(m2.rejected, "the under-declaring manifest must be rejected at admission")

        // The rejection is the structured exact-equality mismatch, not a generic error.
        val mismatch = m2.mismatch
        assertNotNull(
            mismatch,
            "rejection must be the structured ManifestExportEffectMismatch; got ${m2.diagnostics}",
        )
        mismatch!!

        // The mismatch is on the under-declared lookup export: declared {} versus an
        // actual surface that is non-empty (the {Filesystem.Read} the code incurs).
        assertTrue(
            mismatch.declared.isEmpty(),
            "the manifest under-declared (declared must be empty); got ${mismatch.declared}",
        )
        assertFalse(
            mismatch.actual.isEmpty(),
            "the actual effect surface must be non-empty (the code reads the filesystem)",
        )
    }

    // ------------------------------------------------------------------
    // M3 — Hash-pinned contract: a capability change is a different hash.
    // ------------------------------------------------------------------

    @Test
    fun `M3 a capability change produces a different manifest hash`() {
        val m3 = McpToolManifestDemo.scenarioM3()

        // Both manifests are honest about their code, so both verify.
        assertTrue(m3.baselineAdmitted, "the baseline manifest must verify")
        assertTrue(m3.elevatedAdmitted, "the elevated manifest must verify")

        // The only difference is the lookup tool's declared capability: Read vs Write.
        assertEquals(
            listOf("Filesystem.Read"), m3.baselineLookupEffects,
            "the baseline lookup tool's verified surface must be {Filesystem.Read}",
        )
        assertEquals(
            listOf("Filesystem.Write"), m3.elevatedLookupEffects,
            "the elevated lookup tool's verified surface must be {Filesystem.Write}",
        )

        // The capability change is visible as a content-hash change.
        assertTrue(
            m3.hashesDiffer,
            "a capability change must produce a different manifest hash; " +
                "baseline=${m3.baselineHashHex} elevated=${m3.elevatedHashHex}",
        )
    }
}
