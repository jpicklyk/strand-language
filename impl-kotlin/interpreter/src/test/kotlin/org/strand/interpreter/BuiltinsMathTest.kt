package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Stdlib expansion round 2 — direct unit tests of Math.* builtins
 * plus the Float.FromInt / Int.FromFloatTrunc coercion helpers. All
 * pure (no IO), so no resource cleanup needed.
 */
class BuiltinsMathTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    private fun assertFloatNear(expected: Double, actual: Value, tolerance: Double = 1e-9) {
        val f = (actual as Value.FloatV).v
        assertTrue(kotlin.math.abs(f - expected) < tolerance) {
            "expected ~$expected, got $f (delta ${kotlin.math.abs(f - expected)})"
        }
    }

    // ---------- Int-typed Math.* ----------

    @Test
    fun `Math_Abs of negative, positive, zero`() {
        val fn = lookup("strand-builtin:Math.Abs")
        assertEquals(Value.IntV(7L), fn.invoke(listOf(Value.IntV(-7L))))
        assertEquals(Value.IntV(7L), fn.invoke(listOf(Value.IntV(7L))))
        assertEquals(Value.IntV(0L), fn.invoke(listOf(Value.IntV(0L))))
    }

    @Test
    fun `Math_Sign of negative, positive, zero`() {
        val fn = lookup("strand-builtin:Math.Sign")
        assertEquals(Value.IntV(-1L), fn.invoke(listOf(Value.IntV(-42L))))
        assertEquals(Value.IntV(1L), fn.invoke(listOf(Value.IntV(42L))))
        assertEquals(Value.IntV(0L), fn.invoke(listOf(Value.IntV(0L))))
    }

    @Test
    fun `Math_Min and Math_Max`() {
        val min = lookup("strand-builtin:Math.Min")
        val max = lookup("strand-builtin:Math.Max")
        assertEquals(Value.IntV(3L), min.invoke(listOf(Value.IntV(3L), Value.IntV(7L))))
        assertEquals(Value.IntV(-7L), min.invoke(listOf(Value.IntV(-3L), Value.IntV(-7L))))
        assertEquals(Value.IntV(7L), max.invoke(listOf(Value.IntV(3L), Value.IntV(7L))))
        assertEquals(Value.IntV(-3L), max.invoke(listOf(Value.IntV(-3L), Value.IntV(-7L))))
    }

    @Test
    fun `Math_Mod is always non-negative for positive divisors`() {
        // True mathematical modulo, distinct from JVM's % which follows
        // sign-of-dividend. The contrast that motivates this builtin:
        // Int.Mod(-1, 5) == -1, but Math.Mod(-1, 5) == 4.
        val fn = lookup("strand-builtin:Math.Mod")
        assertEquals(Value.IntV(4L), fn.invoke(listOf(Value.IntV(-1L), Value.IntV(5L))))
        assertEquals(Value.IntV(1L), fn.invoke(listOf(Value.IntV(11L), Value.IntV(5L))))
        assertEquals(Value.IntV(0L), fn.invoke(listOf(Value.IntV(10L), Value.IntV(5L))))
    }

    @Test
    fun `Math_Mod by zero rejected`() {
        val fn = lookup("strand-builtin:Math.Mod")
        assertThrows<IllegalArgumentException> {
            fn.invoke(listOf(Value.IntV(7L), Value.IntV(0L)))
        }
    }

    // ---------- Float-to-Int rounding ----------

    @Test
    fun `Math_Floor rounds toward negative infinity`() {
        val fn = lookup("strand-builtin:Math.Floor")
        assertEquals(Value.IntV(3L), fn.invoke(listOf(Value.FloatV(3.7))))
        assertEquals(Value.IntV(-4L), fn.invoke(listOf(Value.FloatV(-3.7))))
        assertEquals(Value.IntV(0L), fn.invoke(listOf(Value.FloatV(0.0))))
    }

    @Test
    fun `Math_Ceil rounds toward positive infinity`() {
        val fn = lookup("strand-builtin:Math.Ceil")
        assertEquals(Value.IntV(4L), fn.invoke(listOf(Value.FloatV(3.2))))
        assertEquals(Value.IntV(-3L), fn.invoke(listOf(Value.FloatV(-3.7))))
        assertEquals(Value.IntV(0L), fn.invoke(listOf(Value.FloatV(0.0))))
    }

    @Test
    fun `Math_Round is half-to-even`() {
        // Kotlin's kotlin.math.round delegates to Math.rint which is
        // round-half-to-even. 2.5 → 2, 3.5 → 4, 4.5 → 4.
        val fn = lookup("strand-builtin:Math.Round")
        assertEquals(Value.IntV(2L), fn.invoke(listOf(Value.FloatV(2.5))))
        assertEquals(Value.IntV(4L), fn.invoke(listOf(Value.FloatV(3.5))))
        assertEquals(Value.IntV(4L), fn.invoke(listOf(Value.FloatV(4.5))))
        assertEquals(Value.IntV(3L), fn.invoke(listOf(Value.FloatV(3.4))))
    }

    // ---------- Float-typed Math.* ----------

    @Test
    fun `Math_Sqrt of positive`() {
        assertFloatNear(2.0, lookup("strand-builtin:Math.Sqrt").invoke(listOf(Value.FloatV(4.0))))
        assertFloatNear(3.0, lookup("strand-builtin:Math.Sqrt").invoke(listOf(Value.FloatV(9.0))))
    }

    @Test
    fun `Math_Sqrt of negative is NaN`() {
        val result = lookup("strand-builtin:Math.Sqrt").invoke(listOf(Value.FloatV(-1.0)))
        assertTrue((result as Value.FloatV).v.isNaN())
    }

    @Test
    fun `Math_Pow`() {
        val fn = lookup("strand-builtin:Math.Pow")
        assertFloatNear(8.0, fn.invoke(listOf(Value.FloatV(2.0), Value.FloatV(3.0))))
        assertFloatNear(1.0, fn.invoke(listOf(Value.FloatV(5.0), Value.FloatV(0.0))))
        assertFloatNear(0.25, fn.invoke(listOf(Value.FloatV(2.0), Value.FloatV(-2.0))))
    }

    @Test
    fun `Math_Log and Math_Exp inverses`() {
        val log = lookup("strand-builtin:Math.Log")
        val exp = lookup("strand-builtin:Math.Exp")
        // log(exp(x)) ~ x for any finite x.
        val x = 2.5
        val roundTrip = log.invoke(listOf(exp.invoke(listOf(Value.FloatV(x)))))
        assertFloatNear(x, roundTrip, tolerance = 1e-10)
    }

    @Test
    fun `Math_Log of zero or negative is non-finite`() {
        val log = lookup("strand-builtin:Math.Log")
        val zero = log.invoke(listOf(Value.FloatV(0.0)))
        assertTrue((zero as Value.FloatV).v.isInfinite() || zero.v.isNaN())
        val neg = log.invoke(listOf(Value.FloatV(-1.0)))
        assertTrue((neg as Value.FloatV).v.isNaN())
    }

    @Test
    fun `Math_Sin Cos Tan`() {
        assertFloatNear(0.0, lookup("strand-builtin:Math.Sin").invoke(listOf(Value.FloatV(0.0))))
        assertFloatNear(1.0, lookup("strand-builtin:Math.Cos").invoke(listOf(Value.FloatV(0.0))))
        assertFloatNear(0.0, lookup("strand-builtin:Math.Tan").invoke(listOf(Value.FloatV(0.0))))
        // sin(pi/2) ~ 1, cos(pi/2) ~ 0
        val halfPi = kotlin.math.PI / 2
        assertFloatNear(1.0, lookup("strand-builtin:Math.Sin").invoke(listOf(Value.FloatV(halfPi))))
        assertFloatNear(0.0, lookup("strand-builtin:Math.Cos").invoke(listOf(Value.FloatV(halfPi))), tolerance = 1e-10)
    }

    // ---------- Coercions ----------

    @Test
    fun `Float_FromInt round-trip with Int_FromFloatTrunc`() {
        val toF = lookup("strand-builtin:Float.FromInt")
        val toI = lookup("strand-builtin:Int.FromFloatTrunc")
        assertEquals(Value.FloatV(7.0), toF.invoke(listOf(Value.IntV(7L))))
        assertEquals(Value.FloatV(-7.0), toF.invoke(listOf(Value.IntV(-7L))))
        // Truncation toward zero: 7.9 -> 7, -7.9 -> -7. Distinct from
        // Math.Floor which would give 7 and -8.
        assertEquals(Value.IntV(7L), toI.invoke(listOf(Value.FloatV(7.9))))
        assertEquals(Value.IntV(-7L), toI.invoke(listOf(Value.FloatV(-7.9))))
    }
}
