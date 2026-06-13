package org.strand.runtime

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.interpreter.Value

/**
 * Q-059: round-trip, determinism, and non-snapshotable-rejection tests for
 * [ValueCodec] — the deterministic, reversible serialization of the
 * snapshot-relevant [Value] subset. This codec is runtime-state serialization,
 * NOT the node canonical encoding (no golden-hash interaction).
 */
class ValueCodecTest {

    private fun roundTrip(v: Value): Value = ValueCodec.decodeFromString(ValueCodec.encodeToString(v))

    @Test
    fun `round-trips every primitive`() {
        val primitives = listOf<Value>(
            Value.IntV(0),
            Value.IntV(7),
            Value.IntV(Long.MIN_VALUE),
            Value.IntV(Long.MAX_VALUE),
            Value.IntV(-1),
            Value.FloatV(3.5),
            Value.FloatV(0.0),
            Value.FloatV(-0.0),
            Value.FloatV(Double.NaN),
            Value.FloatV(Double.POSITIVE_INFINITY),
            Value.FloatV(Double.NEGATIVE_INFINITY),
            Value.FloatV(1.0 / 3.0),
            Value.StringV(""),
            Value.StringV("hello"),
            Value.StringV("unicode: é中文😀"),
            Value.BoolV(true),
            Value.BoolV(false),
            Value.UnitV,
            Value.BytesV(ByteArray(0)),
            Value.BytesV(byteArrayOf(0, 1, 2, -1, 127, -128)),
        )
        for (v in primitives) {
            assertEquals(v, roundTrip(v), "round-trip failed for $v")
        }
    }

    @Test
    fun `signed zero is preserved distinctly`() {
        // -0.0 == 0.0 by Double ==, so assert on the raw bits.
        val decoded = roundTrip(Value.FloatV(-0.0)) as Value.FloatV
        assertEquals(
            java.lang.Double.doubleToRawLongBits(-0.0),
            java.lang.Double.doubleToRawLongBits(decoded.v),
        )
    }

    @Test
    fun `round-trips deeply nested structure`() {
        // A ProductV containing a SumV containing a MapV and a SetV and a
        // Cons/Nil list spine, deeply nested.
        val list = Value.SumV(
            "Cons",
            Value.ProductV(
                linkedMapOf(
                    "head" to Value.IntV(1),
                    "tail" to Value.SumV(
                        "Cons",
                        Value.ProductV(
                            linkedMapOf(
                                "head" to Value.IntV(2),
                                "tail" to Value.SumV("Nil", null),
                            )
                        ),
                    ),
                )
            ),
        )
        val map = Value.MapV(
            persistentMapOf<Value, Value>(
                Value.StringV("a") to Value.IntV(1),
                Value.StringV("b") to Value.SumV("Some", Value.BoolV(true)),
            )
        )
        val set = Value.SetV(
            persistentSetOf<Value>(Value.IntV(3), Value.IntV(1), Value.IntV(2))
        )
        val nested = Value.ProductV(
            linkedMapOf(
                "list" to list,
                "map" to map,
                "set" to set,
                "wrapped" to Value.SumV("Tag", Value.ProductV(linkedMapOf("x" to map, "y" to set))),
            )
        )
        assertEquals(nested, roundTrip(nested))
    }

    @Test
    fun `product field order does not affect encoding`() {
        val a = Value.ProductV(linkedMapOf("z" to Value.IntV(1), "a" to Value.IntV(2)))
        val b = Value.ProductV(linkedMapOf("a" to Value.IntV(2), "z" to Value.IntV(1)))
        assertEquals(a, b)  // ProductV equality is map equality, order-insensitive
        assertEquals(ValueCodec.encodeToString(a), ValueCodec.encodeToString(b))
    }

    @Test
    fun `map insertion order does not affect encoding`() {
        val a = Value.MapV(
            persistentMapOf<Value, Value>().put(Value.StringV("x"), Value.IntV(1)).put(Value.StringV("y"), Value.IntV(2))
        )
        val b = Value.MapV(
            persistentMapOf<Value, Value>().put(Value.StringV("y"), Value.IntV(2)).put(Value.StringV("x"), Value.IntV(1))
        )
        assertEquals(ValueCodec.encodeToString(a), ValueCodec.encodeToString(b))
    }

    @Test
    fun `set insertion order does not affect encoding`() {
        val a = Value.SetV(persistentSetOf<Value>(Value.IntV(3), Value.IntV(1), Value.IntV(2)))
        val b = Value.SetV(persistentSetOf<Value>(Value.IntV(1), Value.IntV(2), Value.IntV(3)))
        assertEquals(ValueCodec.encodeToString(a), ValueCodec.encodeToString(b))
    }

    @Test
    fun `encoding is stable across repeated encodes`() {
        val v = Value.ProductV(
            linkedMapOf(
                "m" to Value.MapV(persistentMapOf<Value, Value>(Value.IntV(2) to Value.StringV("b"), Value.IntV(1) to Value.StringV("a"))),
                "s" to Value.SetV(persistentSetOf<Value>(Value.IntV(9), Value.IntV(4))),
            )
        )
        assertEquals(ValueCodec.encodeToString(v), ValueCodec.encodeToString(v))
    }

    @Test
    fun `distinct values encode distinctly`() {
        assertNotEquals(
            ValueCodec.encodeToString(Value.IntV(1)),
            ValueCodec.encodeToString(Value.IntV(2)),
        )
        assertNotEquals(
            ValueCodec.encodeToString(Value.SumV("None", null)),
            ValueCodec.encodeToString(Value.SumV("Some", Value.UnitV)),
        )
    }

    @Test
    fun `rejects Closure as non-snapshotable`() {
        val lambda = Node.Lambda(parameters = emptyList(), body = NodeId(1), effects = emptyList())
        val closure = Value.Closure(lambda = lambda, env = emptyMap(), self = NodeId(2))
        val e = assertThrows(ValueCodecError.NotSnapshotable::class.java) { ValueCodec.encode(closure) }
        assertEquals("Closure", e.variant)
    }

    @Test
    fun `rejects Resource as non-snapshotable`() {
        val resource = Value.Resource(id = 1L, kind = "socket")
        val e = assertThrows(ValueCodecError.NotSnapshotable::class.java) { ValueCodec.encode(resource) }
        assertEquals("Resource", e.variant)
        assertTrue(e.reason.contains("socket"))
    }

    @Test
    fun `rejects ToolDefV as non-snapshotable`() {
        val tool = Value.ToolDefV(
            self = NodeId(3),
            name = "n",
            description = "d",
            parameterSchemaId = NodeId(4),
            implementation = Value.UnitV,
        )
        val e = assertThrows(ValueCodecError.NotSnapshotable::class.java) { ValueCodec.encode(tool) }
        assertEquals("ToolDefV", e.variant)
    }

    @Test
    fun `non-snapshotable nested inside a product is rejected`() {
        val product = Value.ProductV(
            linkedMapOf(
                "ok" to Value.IntV(1),
                "bad" to Value.Resource(id = 2L, kind = "file"),
            )
        )
        assertThrows(ValueCodecError.NotSnapshotable::class.java) { ValueCodec.encode(product) }
    }

    @Test
    fun `rejects unknown tag`() {
        assertThrows(ValueCodecError.MalformedEncoding::class.java) {
            ValueCodec.decodeFromString("""{"tag":"bogus","value":1}""")
        }
    }

    @Test
    fun `rejects map missing entries`() {
        assertThrows(ValueCodecError.MalformedEncoding::class.java) {
            ValueCodec.decodeFromString("""{"tag":"map"}""")
        }
    }

    @Test
    fun `rejects bytes with invalid base64`() {
        assertThrows(ValueCodecError.MalformedEncoding::class.java) {
            ValueCodec.decodeFromString("""{"tag":"bytes","value":"!!!not base64!!!"}""")
        }
    }

    @Test
    fun `rejects missing tag`() {
        assertThrows(ValueCodecError.MalformedEncoding::class.java) {
            ValueCodec.decodeFromString("""{"value":1}""")
        }
    }

    @Test
    fun `rejects non-object input`() {
        assertThrows(ValueCodecError.MalformedEncoding::class.java) {
            ValueCodec.decodeFromString("""[1,2,3]""")
        }
    }

    @Test
    fun `rejects non-JSON input`() {
        assertThrows(ValueCodecError.MalformedEncoding::class.java) {
            ValueCodec.decodeFromString("""not json at all""")
        }
    }

    @Test
    fun `legacy event JSON is a subset that EventCodec still decodes`() {
        // The EventCodec event shape decodes identically through the delegate.
        val events = EventCodec.parseEventList("""
        {
          "events": [
            { "tag": "int", "value": 7 },
            { "tag": "sum", "case": "Some", "payload": { "tag": "int", "value": 42 } },
            { "tag": "sum", "case": "None" }
          ]
        }
        """.trimIndent())
        assertEquals(
            listOf<Value>(
                Value.IntV(7),
                Value.SumV("Some", Value.IntV(42)),
                Value.SumV("None", null),
            ),
            events,
        )
    }
}
