package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.interpreter.DenialPhase
import org.strand.interpreter.InterpretError

/**
 * Regression net for the bounded-RAG demonstration ([BoundedRagDemo]). Each test
 * asserts the real property the corresponding scenario exercises, so the
 * demonstration cannot silently rot: if a future change weakens the surfaced
 * data-access manifest or the refined-store denial, one of these fails. The
 * transcript ([BoundedRagDemo.main]) and these assertions run the same scenario
 * code.
 *
 * The honesty bar: every assertion reflects a property the shipped runtime
 * enforces. The surfaced closure is the verifier's own; the denial's DenialReport
 * is the one the interpreter constructed at the refinement-check site. Nothing is
 * staged.
 */
class BoundedRagDemoTest {

    // ------------------------------------------------------------------
    // R1 — Bounded RAG run: surfaced manifest, tight grant, three stages.
    // ------------------------------------------------------------------

    @Test
    fun `R1 runs the embed-query-generate pipeline under a refined-store grant`() {
        val r1 = BoundedRagDemo.scenarioR1()

        // The program verifies, and its surfaced effect closure (the data-access
        // manifest) is exactly the canonical RAG triple.
        assertTrue(r1.verifiedClean, "the RAG program must verify clean")
        assertEquals(
            setOf("LLM.Embed", "Vector.Read", "LLM.Generate"), r1.surfacedClosure,
            "the surfaced closure must be the RAG triple {LLM.Embed, Vector.Read, LLM.Generate}",
        )

        // The grant covers exactly the surfaced closure — the bound is tight.
        assertEquals(
            setOf("LLM.Embed", "Vector.Read", "LLM.Generate"), r1.grantedCategories,
            "the grant must permit exactly the surfaced categories",
        )
        assertTrue(r1.grantMatchesClosure, "the grant must cover exactly the surfaced closure")

        // Every stage actually ran against the mocks — the pipeline is real, not
        // a structural fiction: embed once, query once, generate once.
        assertEquals(1, r1.embedCalls, "the embed stage must call the LLM transport once")
        assertEquals(1, r1.queryCalls, "the retrieval stage must call the vector transport once")
        assertEquals(1, r1.generateCalls, "the generation stage must call the LLM transport once")
        assertTrue(r1.allThreeStagesRan, "all three RAG stages must have run")

        // Retrieval returned real hits, and the run produced a generated answer
        // bundled with the retrieved context.
        assertTrue(r1.completed, "the pipeline must run to completion")
        assertEquals(2, r1.retrievedHitCount, "the canned retrieval returns two hits")
        assertEquals("kb-doc-17", r1.firstHit, "the first retrieved hit id must be the canned one")
        assertTrue(
            r1.answerText.isNotBlank() && r1.answerText != "(no result)" && r1.answerText != "(no answer)",
            "the generation stage must produce a non-empty answer; got '${r1.answerText}'",
        )
    }

    // ------------------------------------------------------------------
    // R2 — Refined-store denial: hr-private query denied under a corp-kb grant.
    // ------------------------------------------------------------------

    @Test
    fun `R2 denies a query against a different store with a refinement DenialReport`() {
        val r2 = BoundedRagDemo.scenarioR2()

        // The variant verifies clean — the category-level manifest is identical to
        // R1's. The bound lives in the refinement, not the closure.
        assertTrue(r2.verifiedClean, "the hr-private variant must verify clean")
        assertEquals(
            setOf("LLM.Embed", "Vector.Read", "LLM.Generate"), r2.surfacedClosure,
            "the variant's surfaced closure must equal R1's (the manifest is identical)",
        )

        // The hr-private read is denied with a real RefinementViolation — not a
        // staged one. The denial is at the foreign-call boundary.
        assertTrue(
            r2.denialError is InterpretError.RefinementViolation,
            "the out-of-grant store read must be denied with a RefinementViolation; got ${r2.denialError}",
        )
        assertTrue(r2.denied, "the hr-private query must be denied")

        // The denial fires before any request reaches the vector wire — the embed
        // ran, but the query never issued an HTTP call.
        assertEquals(1, r2.embedCalls, "the embed stage runs before the denied retrieval")
        assertTrue(r2.queryNeverIssued, "the denied query must not reach the wire (queryCalls=${r2.queryCalls})")

        // The DenialReport names the requested store versus the held grant — the
        // fields the interpreter constructed at the refinement-check site.
        val report = r2.denialReport
        assertNotNull(report, "the denial must carry a structured DenialReport")
        report!!
        assertEquals("Vector.Read", report.category, "the denied category must be Vector.Read")
        assertEquals(DenialPhase.Expression, report.phase)
        assertNotNull(report.node, "the report must name the denying node")
        assertTrue(
            report.requested?.any { "hr-private" in it } == true,
            "the report's requested params must name the requested store hr-private; got ${report.requested}",
        )
        assertTrue(
            report.held?.any { "corp-kb" in it } == true,
            "the report's held summary must name the granted store corp-kb; got ${report.held}",
        )
        assertFalse(
            report.requested?.any { "corp-kb" in it } == true,
            "the requested store must be hr-private, not the granted corp-kb; got ${report.requested}",
        )
    }
}
