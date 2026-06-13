package org.strand.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.interpreter.Builtins

/**
 * Regression net for the Handler-based LLM-virtualization demonstration
 * ([LlmVirtualizationDemo]). Each test asserts the real property the corresponding
 * scenario exercises, so the demonstration cannot silently rot: if a future change
 * weakens the closure-subtraction rule, the empty-grant run, or the
 * transport-never-invoked guarantee, one of these fails. The transcript
 * ([LlmVirtualizationDemo.main]) and these assertions run the same scenario code.
 *
 * The honesty bar (stated in the demo doc): every assertion below reflects a
 * property the shipped verifier and runtime produce. The empty surfaced closure is
 * the verifier's own closure-subtraction computation, and the empty-grant run is
 * the real runtime — nothing is staged. The model is a monomorphic stand-in
 * builtin under an `LLM.Generate` EffectCategory, so the intercept and the
 * subtraction it produces are genuine.
 */
class LlmVirtualizationDemoTest {

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    // ------------------------------------------------------------------
    // V1 — Unhandled baseline: the un-virtualized bound.
    // ------------------------------------------------------------------

    @Test
    fun `V1 the unhandled agent surfaces LLM_Generate and runs under that grant`() {
        val v1 = LlmVirtualizationDemo.scenarioV1()

        // The program verifies and its surfaced closure is exactly {LLM.Generate}
        // — the verifier-computed bound, read off VerifyResult.Ok before running.
        assertTrue(v1.verifiedClean, "the unhandled agent program must verify clean")
        assertEquals(
            setOf("LLM.Generate"), v1.surfacedClosure,
            "the unhandled surfaced root closure must be the verifier-computed {LLM.Generate}",
        )

        // The grant covers exactly the surfaced closure — the bound is tight.
        assertEquals(
            v1.surfacedClosure, v1.grantedCategories,
            "the grant must equal the surfaced closure (a tight bound)",
        )
        assertTrue(v1.grantMatchesClosure)

        // The model transport is actually invoked under the granted LLM.Generate,
        // and the program produces the model's deterministic completion.
        assertEquals(1, v1.modelCallCount, "V1 invokes the model transport exactly once")
        assertTrue(v1.completed, "the program must run to completion under the grant")
        assertEquals(
            LlmVirtualizationDemo.MODEL_COMPLETION, v1.resultText,
            "the produced result must be the stand-in model's completion text",
        )
    }

    // ------------------------------------------------------------------
    // V2 — Handled (keystone): the effect is subtracted; the run is pure.
    // ------------------------------------------------------------------

    @Test
    fun `V2 the Handler subtracts LLM_Generate so the surfaced closure is empty`() {
        val v2 = LlmVirtualizationDemo.scenarioV2()

        assertTrue(v2.verifiedClean, "the handled agent program must verify clean")

        // The keystone assertion: the verifier's own surfaced closure is now EMPTY.
        // The same model call, wrapped in a Handler intercepting LLM.Generate, has
        // that category subtracted by the closure-subtraction rule.
        assertEquals(
            emptySet<String>(), v2.surfacedClosure,
            "the handled surfaced closure must be EMPTY — LLM.Generate subtracted by the Handler",
        )
        assertTrue(v2.effectVirtualized, "the effect must be virtualized away from the closure")

        // Side by side: the unhandled program reaches LLM.Generate; the handled one
        // does not. The difference is the Handler, nothing else.
        assertEquals(
            setOf("LLM.Generate"), v2.unhandledClosure,
            "the unhandled variant must still surface {LLM.Generate}",
        )
        assertFalse(
            "LLM.Generate" in v2.surfacedClosure,
            "LLM.Generate must not appear in the handled closure",
        )
    }

    @Test
    fun `V2 the handled program runs under an empty grant with the transport never invoked`() {
        val v2 = LlmVirtualizationDemo.scenarioV2()

        // The program ran under CapabilitySet.EMPTY: no LLM.Generate granted. A
        // program whose closure still contained LLM.Generate could not run here.
        assertEquals(
            emptySet<String>(), v2.grantedCategories,
            "the handled program runs under no granted categories (CapabilitySet.EMPTY)",
        )
        assertTrue(v2.completed, "the program must still produce a result under the empty grant")

        // The model transport was never reached — the Handler replaced the call.
        assertEquals(
            0, v2.modelCallCount,
            "the model transport must never be invoked: the Handler virtualized the call",
        )
        assertTrue(v2.ranPureWithNoTransport)

        // The result is the pure handle's canned completion, deterministically, and
        // it is NOT the stand-in model's completion (the transport did not run).
        assertEquals(
            LlmVirtualizationDemo.VIRTUALIZED_COMPLETION, v2.resultText,
            "the result must come from the pure handle, not the model transport",
        )
        assertFalse(
            v2.resultText == LlmVirtualizationDemo.MODEL_COMPLETION,
            "the result must not be the model's completion — the transport never ran",
        )
    }
}
