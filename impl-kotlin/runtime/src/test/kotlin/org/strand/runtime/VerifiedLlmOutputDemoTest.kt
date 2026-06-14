package org.strand.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.interpreter.Builtins

/**
 * Regression net for the verified-llm-output demonstration
 * ([VerifiedLlmOutputDemo]). Each test asserts the real property the
 * corresponding scenario exercises, so the demonstration cannot silently rot: if
 * a future change weakens the N-045 projection (the constraint), the genuine
 * origin of the verified value (the model call actually runs), or the Q-047
 * runtime containment (the keystone), one of these fails. The transcript
 * ([VerifiedLlmOutputDemo.main]) and these assertions run the same scenario code.
 *
 * The honesty bar (stated in the demo doc): every assertion below reflects a
 * property the shipped runtime enforces. No violation is staged — VO2's
 * `SchemaInvariantViolation` is the one the interpreter actually raised at the
 * value-flow site, on a value that genuinely came from the model call (the
 * model's call count is asserted to be 1 in both scenarios).
 */
class VerifiedLlmOutputDemoTest {

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    // ------------------------------------------------------------------
    // VO1 — Constrain + valid: the constraint projects; the valid response
    //       flows through the Schema position and passes.
    // ------------------------------------------------------------------

    @Test
    fun `VO1 the N-045 constraint projects and a valid model response passes the invariant`() {
        val vo1 = VerifiedLlmOutputDemo.scenarioVO1()

        // The program verifies clean.
        assertTrue(vo1.verifiedClean, "the VO1 program must verify clean")

        // The constrain layer: the BoundedScore schema projects to the provider's
        // structured-output JSON schema. BoundedScore wraps an Int, so the
        // projection is {"type": "integer"} (the constraint the model receives).
        val constraint = vo1.constraint as? JsonObject
        assertNotNull(constraint, "the projected constraint must be a JSON object; got ${vo1.constraint}")
        assertEquals(
            JsonPrimitive("integer"), constraint!!["type"],
            "the BoundedScore constraint must project to a JSON integer schema; got $constraint",
        )

        // The verified value GENUINELY originates from the model call: the model
        // transport was actually reached exactly once (not a hand-built literal).
        assertEquals(1, vo1.modelCallCount, "the model transport must be invoked exactly once")
        assertEquals(VerifiedLlmOutputDemo.VALID_SCORE, vo1.modelScore)

        // The valid response flowed through the BoundedScore position and the
        // Q-047 invariant passed: the program produced the validated result.
        assertTrue(vo1.completed, "the valid model response must pass the invariant and produce a result")
        assertEquals(
            VerifiedLlmOutputDemo.VALID_SCORE, vo1.resultScore,
            "the validated result must be the score the model returned",
        )
    }

    // ------------------------------------------------------------------
    // VO2 — Verify the output (keystone): a structurally-typed but
    //       semantically-invalid model response is contained at runtime.
    // ------------------------------------------------------------------

    @Test
    fun `VO2 a semantically-invalid model response is contained by the Q-047 runtime invariant`() {
        val vo2 = VerifiedLlmOutputDemo.scenarioVO2()

        // The value genuinely came from the model call (the keystone over the
        // output-by-construction demo, which verified a HAND-BUILT value).
        assertEquals(1, vo2.modelCallCount, "the model transport must be invoked exactly once")
        assertEquals(VerifiedLlmOutputDemo.INVALID_SCORE, vo2.modelScore)

        // The run halted with a runtime schema violation: the malformed model
        // output was contained BEFORE it reached output.
        assertTrue(vo2.raisedAtRuntime, "the out-of-range model response must raise at runtime")
        assertTrue(vo2.contained, "the malformed model output must be contained (no value escaped)")

        // The violation is the real one the interpreter constructed at the
        // value-flow site, not a staged one.
        val v = vo2.violation
        assertNotNull(v, "the run must raise InterpretError.SchemaInvariantViolation")
        v!!
        assertNotNull(v.at, "the violation must blame the value-flow node")
        assertNotNull(v.schema, "the violation must name the failed schema")
        assertNotNull(v.invariant, "the violation must name the failed invariant")
        // The offending value description is the out-of-range score.
        assertTrue(
            v.valueDescription.contains("142"),
            "the offending value description must include the out-of-range score; got ${v.valueDescription}",
        )
    }

    // ------------------------------------------------------------------
    // Cross-scenario: the two scenarios share the program byte-for-byte and
    // differ only in the model's returned value (one program, two responses).
    // ------------------------------------------------------------------

    @Test
    fun `the valid and invalid runs differ only in the model response, not the program`() {
        // Both scenarios load the same shared program; the difference is entirely
        // the canned model response. VO1 produces a result; VO2 raises. Same
        // graph, same Schema, same invariant — the model output is what diverges.
        val vo1 = VerifiedLlmOutputDemo.scenarioVO1()
        val vo2 = VerifiedLlmOutputDemo.scenarioVO2()

        assertTrue(vo1.completed, "the valid response completes")
        assertTrue(vo2.contained, "the invalid response is contained")
        assertEquals(
            1, vo1.modelCallCount + vo2.modelCallCount - 1,
            "each scenario invokes the model exactly once over the shared pipeline",
        )
    }
}
