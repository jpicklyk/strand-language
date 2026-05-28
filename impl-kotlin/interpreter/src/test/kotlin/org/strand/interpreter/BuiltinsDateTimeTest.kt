package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 4 — DateTime.* operations on Int millis.
 * All UTC. All pure (no clock dependency — agents pass in millis
 * obtained from Time.Now or any other source).
 */
class BuiltinsDateTimeTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    // Anchor: 2026-05-27T15:30:45.123Z = 1779629445123 ms
    // Computed via java.time.Instant.parse("2026-05-27T15:30:45.123Z").toEpochMilli()
    private val anchor: Long = java.time.Instant.parse("2026-05-27T15:30:45.123Z").toEpochMilli()

    // ---------- FormatIso + ParseIso round-trip ----------

    @Test
    fun `FormatIso emits ISO 8601 UTC with millis`() {
        val fn = lookup("strand-builtin:DateTime.FormatIso")
        val result = (fn.invoke(listOf(Value.IntV(anchor))) as Value.StringV).v
        assertEquals("2026-05-27T15:30:45.123Z", result)
    }

    @Test
    fun `ParseIso roundtrips FormatIso`() {
        val format = lookup("strand-builtin:DateTime.FormatIso")
        val parse = lookup("strand-builtin:DateTime.ParseIso")
        val text = format.invoke(listOf(Value.IntV(anchor))) as Value.StringV
        val parsed = parse.invoke(listOf(text)) as Value.SumV
        assertEquals("Some", parsed.case)
        assertEquals(Value.IntV(anchor), parsed.payload)
    }

    @Test
    fun `ParseIso None on garbage input`() {
        val parse = lookup("strand-builtin:DateTime.ParseIso")
        val result = parse.invoke(listOf(Value.StringV("not a date"))) as Value.SumV
        assertEquals("None", result.case)
    }

    @Test
    fun `ParseIso accepts Z-suffixed instants without millis`() {
        val parse = lookup("strand-builtin:DateTime.ParseIso")
        val result = parse.invoke(listOf(Value.StringV("2026-01-01T00:00:00Z"))) as Value.SumV
        assertEquals("Some", result.case)
        val expected = java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertEquals(Value.IntV(expected), result.payload)
    }

    // ---------- Component extraction ----------

    @Test
    fun `Year Month Day Hour Minute Second extract UTC components`() {
        // anchor = 2026-05-27T15:30:45.123Z
        assertEquals(Value.IntV(2026L), lookup("strand-builtin:DateTime.Year").invoke(listOf(Value.IntV(anchor))))
        assertEquals(Value.IntV(5L), lookup("strand-builtin:DateTime.Month").invoke(listOf(Value.IntV(anchor))))
        assertEquals(Value.IntV(27L), lookup("strand-builtin:DateTime.Day").invoke(listOf(Value.IntV(anchor))))
        assertEquals(Value.IntV(15L), lookup("strand-builtin:DateTime.Hour").invoke(listOf(Value.IntV(anchor))))
        assertEquals(Value.IntV(30L), lookup("strand-builtin:DateTime.Minute").invoke(listOf(Value.IntV(anchor))))
        assertEquals(Value.IntV(45L), lookup("strand-builtin:DateTime.Second").invoke(listOf(Value.IntV(anchor))))
    }

    @Test
    fun `Year Month Day at unix epoch`() {
        assertEquals(Value.IntV(1970L), lookup("strand-builtin:DateTime.Year").invoke(listOf(Value.IntV(0L))))
        assertEquals(Value.IntV(1L), lookup("strand-builtin:DateTime.Month").invoke(listOf(Value.IntV(0L))))
        assertEquals(Value.IntV(1L), lookup("strand-builtin:DateTime.Day").invoke(listOf(Value.IntV(0L))))
        assertEquals(Value.IntV(0L), lookup("strand-builtin:DateTime.Hour").invoke(listOf(Value.IntV(0L))))
    }

    // ---------- Arithmetic ----------

    @Test
    fun `AddDays handles month and year boundaries`() {
        val add = lookup("strand-builtin:DateTime.AddDays")
        // 2026-05-27 + 10 days = 2026-06-06
        val later = (add.invoke(listOf(Value.IntV(anchor), Value.IntV(10L))) as Value.IntV).v
        assertEquals(6L, (lookup("strand-builtin:DateTime.Month").invoke(listOf(Value.IntV(later))) as Value.IntV).v)
        assertEquals(6L, (lookup("strand-builtin:DateTime.Day").invoke(listOf(Value.IntV(later))) as Value.IntV).v)
        // 2026-12-31 + 1 day = 2027-01-01
        val yearEnd = java.time.Instant.parse("2026-12-31T12:00:00Z").toEpochMilli()
        val newYear = (add.invoke(listOf(Value.IntV(yearEnd), Value.IntV(1L))) as Value.IntV).v
        assertEquals(2027L, (lookup("strand-builtin:DateTime.Year").invoke(listOf(Value.IntV(newYear))) as Value.IntV).v)
        assertEquals(1L, (lookup("strand-builtin:DateTime.Month").invoke(listOf(Value.IntV(newYear))) as Value.IntV).v)
        assertEquals(1L, (lookup("strand-builtin:DateTime.Day").invoke(listOf(Value.IntV(newYear))) as Value.IntV).v)
    }

    @Test
    fun `AddHours wraps day boundary`() {
        val add = lookup("strand-builtin:DateTime.AddHours")
        // 2026-05-27T15:30 + 10h = 2026-05-28T01:30 (UTC)
        val later = (add.invoke(listOf(Value.IntV(anchor), Value.IntV(10L))) as Value.IntV).v
        assertEquals(28L, (lookup("strand-builtin:DateTime.Day").invoke(listOf(Value.IntV(later))) as Value.IntV).v)
        assertEquals(1L, (lookup("strand-builtin:DateTime.Hour").invoke(listOf(Value.IntV(later))) as Value.IntV).v)
    }

    @Test
    fun `AddMinutes and AddSeconds compose with millis arithmetic`() {
        val addM = lookup("strand-builtin:DateTime.AddMinutes")
        val addS = lookup("strand-builtin:DateTime.AddSeconds")
        // +90 seconds = +1m30s
        val plus90s = (addS.invoke(listOf(Value.IntV(anchor), Value.IntV(90L))) as Value.IntV).v
        val plus1m30s = (addS.invoke(listOf(addM.invoke(listOf(Value.IntV(anchor), Value.IntV(1L))), Value.IntV(30L))) as Value.IntV).v
        assertEquals(plus90s, plus1m30s)
    }

    @Test
    fun `AddDays negative subtracts`() {
        val add = lookup("strand-builtin:DateTime.AddDays")
        // anchor - 5 days = 2026-05-22
        val earlier = (add.invoke(listOf(Value.IntV(anchor), Value.IntV(-5L))) as Value.IntV).v
        assertEquals(22L, (lookup("strand-builtin:DateTime.Day").invoke(listOf(Value.IntV(earlier))) as Value.IntV).v)
        assertEquals(5L, (lookup("strand-builtin:DateTime.Month").invoke(listOf(Value.IntV(earlier))) as Value.IntV).v)
    }
}
