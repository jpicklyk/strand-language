package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.strand.core.ExhaustionKind
import java.time.Duration

/**
 * Review H7: effect-free builtins whose output size is chosen by an
 * argument used to allocate outside the interpreter's counters, so a
 * program with no grants could exhaust the host. Each exploit below must
 * now fail fast with an uncatchable `ResourceExhaustion`, before the
 * allocation. Every call is wrapped in a preemptive timeout so a
 * regression fails the test instead of hanging the suite.
 */
class BuiltinAllocationBoundsTest {

    private val ctx: HostContext = HostContext.processDefault()

    private fun call(target: String, vararg args: Value, c: HostContext = ctx): Value =
        Builtins.lookup("strand-builtin:$target")!!.invoke(c, args.toList())

    private fun exhausts(kind: ExhaustionKind = ExhaustionKind.AllocatedValues, block: () -> Unit): InterpretError.ResourceExhaustion {
        val ex = assertTimeoutPreemptively(Duration.ofSeconds(10)) { assertThrows<InterpretException> { block() } }
        val err = ex.error as InterpretError.ResourceExhaustion
        assertEquals(kind, err.kind)
        assertTrue(err.current > err.limit)
        assertEquals(false, err.isCatchable)
        return err
    }

    @Test
    fun `List_Range over ten billion elements is refused`() {
        val err = exhausts { call("List.Range", Value.IntV(0), Value.IntV(10_000_000_000L)) }
        assertEquals(BuiltinLimits.DEFAULT.maxCollectionElements, err.limit)
        exhausts { call("List.Range", Value.IntV(Long.MIN_VALUE), Value.IntV(Long.MAX_VALUE)) }
    }

    @Test
    fun `List_Range within the cap still works`() {
        val v = call("List.Range", Value.IntV(0), Value.IntV(3))
        assertTrue(v is Value.SumV && v.case == "Cons")
    }

    @Test
    fun `String_Repeat to Int MAX_VALUE is refused`() {
        exhausts { call("String.Repeat", Value.StringV("x"), Value.IntV(Int.MAX_VALUE.toLong())) }
    }

    @Test
    fun `String_Repeat no longer truncates n to Int`() {
        // 2^32 + 5 used to narrow to 5 and silently succeed.
        exhausts { call("String.Repeat", Value.StringV("ab"), Value.IntV((1L shl 32) + 5)) }
        assertEquals(Value.StringV("abab"), call("String.Repeat", Value.StringV("ab"), Value.IntV(2)))
    }

    @Test
    fun `String_PadLeft and PadRight to a huge width are refused`() {
        exhausts { call("String.PadLeft", Value.StringV(""), Value.IntV(2_000_000_000), Value.StringV("x")) }
        exhausts { call("String.PadRight", Value.StringV(""), Value.IntV(2_000_000_000), Value.StringV("x")) }
    }

    @Test
    fun `Random_Bytes of Int MAX_VALUE is refused`() {
        exhausts { call("Random.Bytes", Value.IntV(Int.MAX_VALUE.toLong())) }
    }

    @Test
    fun `Net and LLM receive with a huge maxBytes are refused before the buffer is allocated`() {
        val bogus = Value.Resource(id = -1, kind = ResourceTable.KIND_SOCKET)
        exhausts { call("Net.Receive", bogus, Value.IntV(Int.MAX_VALUE.toLong())) }
        exhausts { call("Net.Stream.Receive", bogus, Value.IntV(Int.MAX_VALUE.toLong())) }
        exhausts { call("LLM.Stream.Receive", Value.Resource(id = -1, kind = ResourceTable.KIND_LLM_STREAM), Value.IntV(Int.MAX_VALUE.toLong())) }
    }

    @Test
    fun `Compress_Gunzip of a gzip bomb is refused as the output crosses the cap`() {
        val bomb = java.io.ByteArrayOutputStream().also { sink ->
            java.util.zip.GZIPOutputStream(sink).use { gz ->
                val zeros = ByteArray(1 shl 20)
                repeat(8) { gz.write(zeros) }  // 8 MiB of zeros, ~8 KiB compressed
            }
        }.toByteArray()
        assertTrue(bomb.size < 64 * 1024)
        val tight = ctx.copy(builtinLimits = BuiltinLimits.DEFAULT.copy(maxBuiltinBytes = 1L shl 20))
        exhausts { call("Compress.Gunzip", Value.BytesV(bomb), c = tight) }
        // Under the default 16 MiB cap the same payload decompresses.
        val ok = call("Compress.Gunzip", Value.BytesV(bomb)) as Value.SumV
        assertEquals("Some", ok.case)
    }

    @Test
    fun `Regex builtins refuse input and patterns over the cap`() {
        val tight = ctx.copy(builtinLimits = BuiltinLimits.DEFAULT.copy(maxRegexInputChars = 100))
        val long = Value.StringV("a".repeat(101))
        exhausts { call("Regex.Match", Value.StringV("a+"), long, c = tight) }
        exhausts { call("Regex.FindAll", Value.StringV("a"), long, c = tight) }
        exhausts { call("Regex.Replace", Value.StringV("a"), long, Value.StringV("b"), c = tight) }
        exhausts { call("Regex.Split", Value.StringV("a"), long, c = tight) }
        exhausts { call("Regex.Match", long, Value.StringV("a"), c = tight) }
        assertEquals(Value.SumV("Some", Value.StringV("aaa")), call("Regex.Match", Value.StringV("a+"), Value.StringV("aaa"), c = tight))
    }

    @Test
    fun `the bounded pure builtins keep their Deterministic registration`() {
        for (t in listOf("List.Range", "String.Repeat", "String.PadLeft", "String.PadRight", "Compress.Gunzip",
            "Regex.Match", "Regex.FindAll", "Regex.Replace", "Regex.Split")) {
            assertEquals(Builtins.Determinism.Deterministic, Builtins.determinismOf("strand-builtin:$t"), t)
        }
    }
}
