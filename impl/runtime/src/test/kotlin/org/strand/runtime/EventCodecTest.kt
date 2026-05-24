package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.strand.interpreter.Value

/**
 * Tests for [EventCodec] — the JSON-to-[Value] decoder used by the
 * `strand machine` CLI. Each test exercises one tag of the codec's
 * discriminated union plus a few error paths.
 */
class EventCodecTest {

    @Test
    fun `parses primitive int float string bool unit`() {
        val events = EventCodec.parseEventList("""
        {
          "events": [
            { "tag": "int",    "value": 7 },
            { "tag": "float",  "value": 3.5 },
            { "tag": "string", "value": "hi" },
            { "tag": "bool",   "value": true },
            { "tag": "unit" }
          ]
        }
        """.trimIndent())
        assertEquals(
            listOf<Value>(
                Value.IntV(7),
                Value.FloatV(3.5),
                Value.StringV("hi"),
                Value.BoolV(true),
                Value.UnitV,
            ),
            events,
        )
    }

    @Test
    fun `parses product event with nested fields`() {
        val events = EventCodec.parseEventList("""
        {
          "events": [
            { "tag": "product", "fields": {
              "requestId": { "tag": "int", "value": 1 },
              "payload":   { "tag": "string", "value": "ping" }
            }}
          ]
        }
        """.trimIndent())
        assertEquals(1, events.size)
        val p = events[0] as Value.ProductV
        assertEquals(Value.IntV(1), p.fields["requestId"])
        assertEquals(Value.StringV("ping"), p.fields["payload"])
    }

    @Test
    fun `parses sum events with and without payload`() {
        val events = EventCodec.parseEventList("""
        {
          "events": [
            { "tag": "sum", "case": "Some", "payload": { "tag": "int", "value": 42 } },
            { "tag": "sum", "case": "None" }
          ]
        }
        """.trimIndent())
        assertEquals(2, events.size)
        assertEquals(Value.SumV("Some", Value.IntV(42)), events[0])
        assertEquals(Value.SumV("None", null), events[1])
    }

    @Test
    fun `unknown tag is rejected`() {
        assertThrows(EventCodecError::class.java) {
            EventCodec.parseEventList("""
              { "events": [ { "tag": "alien", "value": 1 } ] }
            """.trimIndent())
        }
    }

    @Test
    fun `missing events field is rejected`() {
        assertThrows(EventCodecError::class.java) {
            EventCodec.parseEventList("""{ "noEvents": [] }""")
        }
    }

    @Test
    fun `top-level non-object is rejected`() {
        assertThrows(EventCodecError::class.java) {
            EventCodec.parseEventList("""[1, 2, 3]""")
        }
    }
}
