package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 4 — Float arithmetic and comparisons, plus
 * the missing equality variants Bool.Eq and Bytes.Eq. All pure (no IO).
 */
class BuiltinsFloatTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    // ---------- Float arithmetic ----------

    @Test
    fun `Float_Add Sub Mul Div`() {
        val add = lookup("strand-builtin:Float.Add")
        val sub = lookup("strand-builtin:Float.Sub")
        val mul = lookup("strand-builtin:Float.Mul")
        val div = lookup("strand-builtin:Float.Div")
        assertEquals(Value.FloatV(5.5), add.invoke(listOf(Value.FloatV(2.0), Value.FloatV(3.5))))
        assertEquals(Value.FloatV(-1.5), sub.invoke(listOf(Value.FloatV(2.0), Value.FloatV(3.5))))
        assertEquals(Value.FloatV(7.0), mul.invoke(listOf(Value.FloatV(2.0), Value.FloatV(3.5))))
        assertEquals(Value.FloatV(2.5), div.invoke(listOf(Value.FloatV(5.0), Value.FloatV(2.0))))
    }

    @Test
    fun `Float_Div by zero is IEEE infinity (no exception)`() {
        val div = lookup("strand-builtin:Float.Div")
        val result = div.invoke(listOf(Value.FloatV(1.0), Value.FloatV(0.0))) as Value.FloatV
        assertTrue(result.v.isInfinite())
        // 0.0 / 0.0 is NaN per IEEE 754
        val nan = div.invoke(listOf(Value.FloatV(0.0), Value.FloatV(0.0))) as Value.FloatV
        assertTrue(nan.v.isNaN())
    }

    @Test
    fun `Float_Neg negates`() {
        val neg = lookup("strand-builtin:Float.Neg")
        assertEquals(Value.FloatV(-3.5), neg.invoke(listOf(Value.FloatV(3.5))))
        assertEquals(Value.FloatV(3.5), neg.invoke(listOf(Value.FloatV(-3.5))))
        // -0.0 has a distinct bit pattern from 0.0 in IEEE 754 — JUnit's
        // assertEquals uses Double.equals which distinguishes them. Use
        // primitive `==` (IEEE 754 ordering) where +0.0 == -0.0 is true.
        val negZero = neg.invoke(listOf(Value.FloatV(0.0))) as Value.FloatV
        assertTrue(negZero.v == 0.0) { "negate(0.0) should equal 0.0 under IEEE, got ${negZero.v}" }
    }

    // ---------- Float comparisons ----------

    @Test
    fun `Float_Eq compares values`() {
        val eq = lookup("strand-builtin:Float.Eq")
        assertEquals(Value.BoolV(true), eq.invoke(listOf(Value.FloatV(2.5), Value.FloatV(2.5))))
        assertEquals(Value.BoolV(false), eq.invoke(listOf(Value.FloatV(2.5), Value.FloatV(2.6))))
    }

    @Test
    fun `Float_Eq NaN compared to anything is false (IEEE 754)`() {
        val eq = lookup("strand-builtin:Float.Eq")
        val nan = Double.NaN
        assertEquals(Value.BoolV(false), eq.invoke(listOf(Value.FloatV(nan), Value.FloatV(nan))))
        assertEquals(Value.BoolV(false), eq.invoke(listOf(Value.FloatV(nan), Value.FloatV(1.0))))
    }

    @Test
    fun `Float_Lt Le Gt Ge ordering`() {
        val lt = lookup("strand-builtin:Float.Lt")
        val le = lookup("strand-builtin:Float.Le")
        val gt = lookup("strand-builtin:Float.Gt")
        val ge = lookup("strand-builtin:Float.Ge")
        val a = Value.FloatV(1.0)
        val b = Value.FloatV(2.0)
        assertEquals(Value.BoolV(true),  lt.invoke(listOf(a, b)))
        assertEquals(Value.BoolV(false), lt.invoke(listOf(b, a)))
        assertEquals(Value.BoolV(false), lt.invoke(listOf(a, a)))
        assertEquals(Value.BoolV(true),  le.invoke(listOf(a, b)))
        assertEquals(Value.BoolV(true),  le.invoke(listOf(a, a)))
        assertEquals(Value.BoolV(false), le.invoke(listOf(b, a)))
        assertEquals(Value.BoolV(true),  gt.invoke(listOf(b, a)))
        assertEquals(Value.BoolV(false), gt.invoke(listOf(a, a)))
        assertEquals(Value.BoolV(true),  ge.invoke(listOf(a, a)))
        assertEquals(Value.BoolV(true),  ge.invoke(listOf(b, a)))
    }

    @Test
    fun `Float comparison with NaN is always false`() {
        val lt = lookup("strand-builtin:Float.Lt")
        val gt = lookup("strand-builtin:Float.Gt")
        val nan = Value.FloatV(Double.NaN)
        val one = Value.FloatV(1.0)
        assertFalse((lt.invoke(listOf(nan, one)) as Value.BoolV).v)
        assertFalse((gt.invoke(listOf(nan, one)) as Value.BoolV).v)
    }

    // ---------- Bool.Eq ----------

    @Test
    fun `Bool_Eq`() {
        val eq = lookup("strand-builtin:Bool.Eq")
        assertEquals(Value.BoolV(true),  eq.invoke(listOf(Value.BoolV(true),  Value.BoolV(true))))
        assertEquals(Value.BoolV(true),  eq.invoke(listOf(Value.BoolV(false), Value.BoolV(false))))
        assertEquals(Value.BoolV(false), eq.invoke(listOf(Value.BoolV(true),  Value.BoolV(false))))
        assertEquals(Value.BoolV(false), eq.invoke(listOf(Value.BoolV(false), Value.BoolV(true))))
    }

    // ---------- Bytes.Eq ----------

    @Test
    fun `Bytes_Eq compares content not reference`() {
        val eq = lookup("strand-builtin:Bytes.Eq")
        val a = Value.BytesV(byteArrayOf(1, 2, 3))
        val b = Value.BytesV(byteArrayOf(1, 2, 3))  // distinct ByteArray, same content
        val c = Value.BytesV(byteArrayOf(1, 2, 4))
        val empty = Value.BytesV(ByteArray(0))
        val empty2 = Value.BytesV(ByteArray(0))
        assertEquals(Value.BoolV(true),  eq.invoke(listOf(a, b)))
        assertEquals(Value.BoolV(false), eq.invoke(listOf(a, c)))
        assertEquals(Value.BoolV(true),  eq.invoke(listOf(empty, empty2)))
        assertEquals(Value.BoolV(false), eq.invoke(listOf(empty, a)))
    }
}
