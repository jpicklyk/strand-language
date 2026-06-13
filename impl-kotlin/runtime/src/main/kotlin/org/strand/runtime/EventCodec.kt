package org.strand.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * This codec used to be a self-contained decoder limited to primitives, Unit,
 * products, and sums. As of Q-059 it **delegates its element decode to
 * [ValueCodec]** — the reversible, deterministic [Value] codec whose encode
 * side the snapshot-persistence work added. The legacy event JSON is a strict
 * subset of [ValueCodec]'s format (every `int`/`float`/`string`/`bool`/`unit`/
 * `product`/`sum` event decodes byte-identically), and the richer tags
 * [ValueCodec] adds — `bytes` / `map` / `set` — are now also accepted as event
 * payloads for free. [EventCodec] retains only the document-level concern
 * (the `{"events": [...]}` envelope) plus its [EventCodecError] type for
 * back-compatible error reporting; per-element malformations surface as
 * [ValueCodecError.MalformedEncoding] from the delegate.
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

    /**
     * Decode a single JSON event element into a [Value]. Delegates to
     * [ValueCodec.decode] (Q-059) so the event format and the snapshot value
     * format are the same vocabulary. A per-element malformation from the
     * delegate is re-wrapped as [EventCodecError] so the event-decode error
     * contract (the `strand machine` / `strand group` CLI catches
     * [EventCodecError]) is unchanged.
     */
    fun decodeValue(element: JsonElement, ctx: String = "<root>"): Value =
        try {
            ValueCodec.decode(element, ctx)
        } catch (e: ValueCodecError.MalformedEncoding) {
            throw EventCodecError(e.detail)
        }
}

class EventCodecError(message: String) : RuntimeException(message)
