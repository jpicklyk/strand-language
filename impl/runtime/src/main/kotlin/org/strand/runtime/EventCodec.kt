package org.strand.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.strand.interpreter.Value

/**
 * Decode an event list from JSON into a list of runtime [Value]s for the
 * `strand machine` CLI subcommand. The JSON shape mirrors a tagged
 * sum/product encoding so any Strand value used as an event payload can be
 * expressed:
 *
 *   {
 *     "events": [
 *       { "tag": "int", "value": 7 },
 *       { "tag": "bool", "value": true },
 *       { "tag": "string", "value": "hello" },
 *       { "tag": "unit" },
 *       { "tag": "product", "fields": { "k": { "tag": "int", "value": 1 } } },
 *       { "tag": "sum", "case": "Some",
 *         "payload": { "tag": "int", "value": 42 } },
 *       { "tag": "sum", "case": "None" }
 *     ]
 *   }
 *
 * This codec is deliberately limited to the value shapes the runtime can
 * usefully consume as events — primitives, Unit, products, sums. Closures
 * and foreign callables are not events; bytes are not yet supported (no
 * corpus event currently needs them).
 *
 * The codec is **decode-only**. For replay determinism we serialize traces
 * via `toString()`-style human inspection rather than via a structured
 * round-trip codec; if a future step wires up snapshot/replay-from-log, an
 * `encode` side will land alongside it.
 */
object EventCodec {

    private val parser = Json { ignoreUnknownKeys = false }

    /** Parse a JSON document and decode its `events` array. */
    fun parseEventList(text: String): List<Value> {
        val root = parser.parseToJsonElement(text) as? JsonObject
            ?: throw EventCodecError("Top-level JSON value must be an object")
        val events = root["events"] as? JsonArray
            ?: throw EventCodecError("Missing or non-array 'events' field")
        return events.mapIndexed { i, elt -> decodeValue(elt, "events[$i]") }
    }

    /** Decode a single JSON event element into a [Value]. */
    fun decodeValue(element: JsonElement, ctx: String = "<root>"): Value {
        val obj = element as? JsonObject
            ?: throw EventCodecError("Event at $ctx must be an object with a 'tag' field")
        val tag = obj["tag"]?.jsonPrimitive?.contentOrNull
            ?: throw EventCodecError("Event at $ctx is missing the 'tag' field")
        return when (tag) {
            "int" -> Value.IntV(requireLong(obj, "value", ctx))
            "float" -> Value.FloatV(requireDouble(obj, "value", ctx))
            "string" -> Value.StringV(requireString(obj, "value", ctx))
            "bool" -> Value.BoolV(requireBoolean(obj, "value", ctx))
            "unit" -> Value.UnitV
            "product" -> {
                val fields = (obj["fields"] as? JsonObject
                    ?: throw EventCodecError("'product' event at $ctx requires a 'fields' object"))
                val out = LinkedHashMap<String, Value>(fields.size)
                for ((name, elt) in fields) {
                    out[name] = decodeValue(elt, "$ctx.fields.$name")
                }
                Value.ProductV(out)
            }
            "sum" -> {
                val case = requireString(obj, "case", ctx)
                val payloadElt = obj["payload"]
                val payload = if (payloadElt == null || payloadElt is JsonPrimitive && payloadElt.contentOrNull == null) {
                    null
                } else {
                    decodeValue(payloadElt, "$ctx.payload")
                }
                Value.SumV(case, payload)
            }
            else -> throw EventCodecError(
                "Unknown event tag '$tag' at $ctx (expected int, float, string, bool, unit, product, or sum)"
            )
        }
    }

    private fun requireLong(obj: JsonObject, field: String, ctx: String): Long =
        obj[field]?.jsonPrimitive?.longOrNull
            ?: throw EventCodecError("Field '$field' at $ctx must be an integer")

    private fun requireDouble(obj: JsonObject, field: String, ctx: String): Double =
        obj[field]?.jsonPrimitive?.doubleOrNull
            ?: throw EventCodecError("Field '$field' at $ctx must be a number")

    private fun requireString(obj: JsonObject, field: String, ctx: String): String =
        obj[field]?.jsonPrimitive?.contentOrNull
            ?: throw EventCodecError("Field '$field' at $ctx must be a string")

    private fun requireBoolean(obj: JsonObject, field: String, ctx: String): Boolean =
        obj[field]?.jsonPrimitive?.booleanOrNull
            ?: throw EventCodecError("Field '$field' at $ctx must be a boolean")
}

class EventCodecError(message: String) : RuntimeException(message)
