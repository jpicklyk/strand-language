package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression net for the correct-by-construction demonstration
 * ([OutputByConstructionDemo]). Each test asserts the real property the
 * corresponding scenario exercises, so the demonstration cannot silently rot: if
 * a future change weakens N-048 nested construction, verify-time schema
 * rejection, or the Q-047 runtime obligation, one of these fails. The transcript
 * ([OutputByConstructionDemo.main]) and these assertions run the same scenario
 * code.
 *
 * The honesty bar (stated in the demo doc): every assertion reflects a property
 * the shipped runtime enforces. No violation is staged — W2's error is the
 * verify-time `SchemaInvariantViolation` the `SchemaChecker` actually produced,
 * and W3's is the runtime `InterpretError.SchemaInvariantViolation` the
 * interpreter actually raised at the obligation site.
 */
class OutputByConstructionDemoTest {

    // ------------------------------------------------------------------
    // W1 — Well-formed document produced; structure is a genuine N-048 list.
    // ------------------------------------------------------------------

    @Test
    fun `W1 well-formed document verifies and produces a genuine nested array`() {
        val w1 = OutputByConstructionDemo.scenarioW1()

        // The document evaluated to the well-formed array [1, 2, 3].
        assertEquals(listOf(1L, 2L, 3L), w1.elements, "the document must be the array [1, 2, 3]")
        assertEquals("[1,2,3]", w1.renderedJson, "the genuine structure must render to [1,2,3]")

        // The distinctive N-048 property: the runtime value is a real Cons/Nil
        // list (a spine of ProductV cells), not a flat spliced blob.
        assertTrue(
            w1.isGenuineNestedList,
            "the document must be a genuine Cons/Nil List<JsonValue>, not a flat blob",
        )
        assertEquals(3, w1.consDepth, "the spine must have exactly three Cons cells")

        // Concretely: the value is a SumV(Cons) whose payload is a ProductV with
        // head and tail fields — the genuine nested composite N-048 constructs.
        val v = w1.value as org.strand.interpreter.Value.SumV
        assertEquals("Cons", v.case)
        val cell = v.payload as org.strand.interpreter.Value.ProductV
        assertTrue(
            cell.fields.containsKey("head") && cell.fields.containsKey("tail"),
            "each spine cell must be a ProductV with head/tail — a real list cell",
        )
    }

    // ------------------------------------------------------------------
    // W2 — Malformed caught at VERIFY time on a statically-known value.
    // ------------------------------------------------------------------

    @Test
    fun `W2 statically-known malformed document is rejected at verify time`() {
        val w2 = OutputByConstructionDemo.scenarioW2()

        // Rejected at admission, before any execution.
        assertTrue(w2.rejectedAtVerify, "the malformed document must be rejected at verify time")

        // The rejection is the REAL verify-time SchemaInvariantViolation.
        val v = w2.violation
        assertNotNull(v, "rejection must carry a verify-time SchemaInvariantViolation")
        v!!

        // The offending value the checker recorded is the malformed array — it
        // contains the negative element -7 the invariant rejects.
        assertTrue(
            "IntV(v=-7)" in v.valueDescription,
            "the recorded offending value must contain the -7 element; got ${v.valueDescription}",
        )
        // It is the genuine nested structure that was folded and checked.
        assertTrue(
            "case=Cons" in v.valueDescription && "JsonNumber" in v.valueDescription,
            "the checked value must be the genuine Cons-spine of JsonNumbers; got ${v.valueDescription}",
        )
    }

    // ------------------------------------------------------------------
    // W3 — Malformed caught at RUNTIME on a dynamic value (Q-047).
    // ------------------------------------------------------------------

    @Test
    fun `W3 dynamically-computed malformed document is caught at runtime`() {
        val w3 = OutputByConstructionDemo.scenarioW3()

        // The dynamic value cannot be folded statically, so verify is clean and
        // the array value-flow site is deferred — the runtime check is the backstop.
        assertTrue(w3.verifiesClean, "a dynamic value must verify clean (no static violation)")
        assertTrue(w3.deferredOnArray, "the verifier must defer the dynamic array value-flow site")

        // The run halted with the REAL runtime SchemaInvariantViolation, before
        // the malformed document was returned.
        assertTrue(w3.raisedAtRuntime, "the run must halt with a runtime schema violation")
        val v = w3.violation
        assertNotNull(v, "the halt must be a runtime InterpretError.SchemaInvariantViolation")
        v!!

        // The obligation site blamed is a real value-flow position (the array),
        // and the offending value is the dynamically-built [1, -7, 3].
        assertNotNull(v.at, "the runtime violation names the obligation site")
        assertTrue(
            "IntV(v=-7)" in v.valueDescription,
            "the offending value must contain the runtime-computed -7; got ${v.valueDescription}",
        )
        assertTrue(
            "case=Cons" in v.valueDescription && "JsonNumber" in v.valueDescription,
            "the offending value must be the genuine nested array; got ${v.valueDescription}",
        )
    }

    // ------------------------------------------------------------------
    // Cross-scenario: the same schema admits W1 and rejects W2/W3.
    // ------------------------------------------------------------------

    @Test
    fun `the same invariant admits the well-formed document and rejects both malformed ones`() {
        val w1 = OutputByConstructionDemo.scenarioW1()
        val w2 = OutputByConstructionDemo.scenarioW2()
        val w3 = OutputByConstructionDemo.scenarioW3()

        // W1 produced a value; W2 and W3 produced none (rejected before output).
        assertFalse(w1.elements.isEmpty(), "W1 produces the document value")
        assertTrue(w2.rejectedAtVerify, "W2 is rejected before output")
        assertTrue(w3.raisedAtRuntime, "W3 is rejected before output")

        // W2 (static) and W3 (dynamic) reject the same shape — a negative element
        // — at the two different phases. The well-formedness predicate is one
        // invariant; the phase differs by whether the value is statically known.
        assertTrue("IntV(v=-7)" in (w2.offendingValueDescription ?: ""))
        assertTrue("IntV(v=-7)" in (w3.offendingValueDescription ?: ""))
    }
}
