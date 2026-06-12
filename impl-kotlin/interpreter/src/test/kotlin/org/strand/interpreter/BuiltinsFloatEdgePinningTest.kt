package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Q-065 floating-point edge pinning. The Float.* family is registered
 * Deterministic; these tests pin the IEEE 754 edge semantics that
 * determination rests on, as a recorded contract rather than an
 * implementation accident:
 *
 *  - `Float.Div(0, 0)` is NaN; NaN propagates through arithmetic;
 *  - every NaN comparison (including NaN = NaN) is false;
 *  - repeated evaluation is bit-identical, asserted on raw bits
 *    (doubleToRawLongBits), not on `==` — NaN never compares equal to
 *    itself under `==`, and distinct NaN payloads would be invisible
 *    to boxed equality.
 */
class BuiltinsFloatEdgePinningTest {

    private fun lookup(name: String) = Builtins.lookup("strand-builtin:$name")!!

    private fun f(v: Double) = Value.FloatV(v)

    private fun rawBits(v: Value): Long = java.lang.Double.doubleToRawLongBits((v as Value.FloatV).v)

    @Test
    fun `Float Div of zero by zero is NaN`() {
        val result = lookup("Float.Div").invoke(listOf(f(0.0), f(0.0))) as Value.FloatV
        assertTrue(result.v.isNaN())
    }

    @Test
    fun `Float Div of nonzero by zero is signed infinity`() {
        val pos = lookup("Float.Div").invoke(listOf(f(1.0), f(0.0))) as Value.FloatV
        val neg = lookup("Float.Div").invoke(listOf(f(-1.0), f(0.0))) as Value.FloatV
        assertEquals(Double.POSITIVE_INFINITY, pos.v)
        assertEquals(Double.NEGATIVE_INFINITY, neg.v)
    }

    @Test
    fun `NaN propagates through Float arithmetic`() {
        val nan = f(Double.NaN)
        for (op in listOf("Float.Add", "Float.Sub", "Float.Mul", "Float.Div")) {
            val viaLeft = lookup(op).invoke(listOf(nan, f(1.5))) as Value.FloatV
            val viaRight = lookup(op).invoke(listOf(f(1.5), nan)) as Value.FloatV
            assertTrue(viaLeft.v.isNaN()) { "$op(NaN, 1.5) must be NaN" }
            assertTrue(viaRight.v.isNaN()) { "$op(1.5, NaN) must be NaN" }
        }
        val negated = lookup("Float.Neg").invoke(listOf(nan)) as Value.FloatV
        assertTrue(negated.v.isNaN()) { "Float.Neg(NaN) must be NaN" }
    }

    @Test
    fun `every NaN comparison is false`() {
        val nan = f(Double.NaN)
        for (op in listOf("Float.Eq", "Float.Lt", "Float.Le", "Float.Gt", "Float.Ge")) {
            assertFalse((lookup(op).invoke(listOf(nan, f(1.5))) as Value.BoolV).v) {
                "$op(NaN, 1.5) must be false"
            }
            assertFalse((lookup(op).invoke(listOf(f(1.5), nan)) as Value.BoolV).v) {
                "$op(1.5, NaN) must be false"
            }
            assertFalse((lookup(op).invoke(listOf(nan, nan)) as Value.BoolV).v) {
                "$op(NaN, NaN) must be false"
            }
        }
    }

    @Test
    fun `repeated evaluation is bit-identical including NaN results`() {
        val nanCase = lookup("Float.Div")
        assertEquals(
            rawBits(nanCase.invoke(listOf(f(0.0), f(0.0)))),
            rawBits(nanCase.invoke(listOf(f(0.0), f(0.0)))),
            "Float.Div(0, 0) must re-evaluate to the same NaN bit pattern",
        )

        val arithmetic = lookup("Float.Add")
        assertEquals(
            rawBits(arithmetic.invoke(listOf(f(0.1), f(0.2)))),
            rawBits(arithmetic.invoke(listOf(f(0.1), f(0.2)))),
            "Float.Add(0.1, 0.2) must re-evaluate bit-identically",
        )

        // Signed zero is preserved and distinguishable on raw bits.
        val negZero = lookup("Float.Neg").invoke(listOf(f(0.0)))
        assertEquals(java.lang.Double.doubleToRawLongBits(-0.0), rawBits(negZero))
        assertEquals(rawBits(negZero), rawBits(lookup("Float.Neg").invoke(listOf(f(0.0)))))
    }
}
