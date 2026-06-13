package org.strand.runtime

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.strand.interpreter.Value
import java.util.Base64

/**
 * Q-059: a deterministic, reversible serialization of the snapshot-relevant
 * subset of runtime [Value]s — the state a machine carries between transitions.
 *
 * **This is runtime-state serialization, NOT the node canonical encoding.**
 * The canonical encoding ([org.strand.hashing.CanonicalEncoder]) operates on
 * `Node` and produces the content hash that pins a program's identity;
 * `design/canonical-encoding.md` and the golden program hashes are governed by
 * that encoder and are untouched by this one. `ValueCodec` serializes
 * *post-evaluation runtime values* — the dynamic state of a running machine —
 * to a JSON document the host can persist and reload. The two domains are
 * strictly separate.
 *
 * **Serializable subset.** A transition function's *state* is by construction
 * first-order data: the machine's transition is `(state, event) -> {state,
 * outputs}`, and state is data, not a callable or a handle. The codec covers
 * the full first-order subset and arbitrary nesting of it:
 *  - [Value.IntV], [Value.FloatV], [Value.StringV], [Value.BoolV],
 *    [Value.UnitV], [Value.BytesV] — primitives
 *  - [Value.ProductV] — records (lists-as-Cons/Nil fall out of SumV)
 *  - [Value.SumV] — variants (Option, Result, list spines, tagged unions)
 *  - [Value.MapV], [Value.SetV] — the opaque persistent collections
 *
 * **Non-snapshotable values.** [Value.Closure], [Value.FixpointFn], and
 * [Value.ForeignFn] capture an evaluation environment that is not
 * content-addressable as data; [Value.Resource] is a live OS handle that
 * cannot be re-opened deterministically across a restart;
 * [Value.ToolDefV] / [Value.ResponseSchemaSpecV] carry NodeId identity into a
 * live callable. Encoding any of these raises
 * [ValueCodecError.NotSnapshotable] naming the variant and the reason, rather
 * than silently corrupting a snapshot — the cooperative-error stance
 * container checkpoint-restore systems take for un-migratable handles. A
 * Closure or live Resource appearing *in state* is a legal-but-non-snapshotable
 * program, and the precise error at snapshot time is far better than a dropped
 * or fabricated value at resume time.
 *
 * **Determinism.** [encode] is a function of the [Value] alone:
 *  - [Value.ProductV] fields are emitted sorted by field name.
 *  - [Value.MapV] entries and [Value.SetV] elements are emitted sorted by the
 *    serialized bytes of their own encoding (so insertion order does not leak).
 *  - [encodeToString] uses a fixed [Json] configuration (no pretty-printing).
 * Two equal [Value]s in the subset produce byte-identical strings.
 *
 * **Reversibility.** `decode(encode(v)) == v` for every `v` in the subset.
 *
 * **Reusability (Q-058).** This codec is a self-contained component with no
 * dependency on the snapshot or group machinery; the persistent-store work
 * (Q-058) reuses it for serializing run results and cached values.
 *
 * The JSON format extends the tagged shape [EventCodec] already decodes
 * (`{"tag": "int", "value": N}`, `{"tag": "product", "fields": {...}}`,
 * `{"tag": "sum", "case": C, "payload": ...}`), adding `bytes` (base64),
 * `float` (string-encoded for NaN / Inf / signed-zero fidelity), `map` (a
 * sorted association list), and `set` (a sorted element list). [EventCodec]
 * delegates its decode to this codec, so the two stay in lockstep and the
 * legacy event JSON is a strict subset of this format.
 */
object ValueCodec {

    private val json = Json { }

    // ---- encode -------------------------------------------------------------

    /** Encode [value] to a canonical [JsonElement]. Raises [ValueCodecError.NotSnapshotable] for runtime-only variants. */
    fun encode(value: Value): JsonElement = when (value) {
        is Value.IntV -> buildJsonObject {
            put("tag", "int"); put("value", value.v)
        }
        is Value.FloatV -> buildJsonObject {
            // String-encoded so NaN / +-Infinity / signed zero round-trip
            // exactly — JSON numbers cannot represent them. Mirrors the Q-066
            // FloatLit "hash raw IEEE 754 bits" discipline at the value layer.
            put("tag", "float"); put("value", encodeFloat(value.v))
        }
        is Value.StringV -> buildJsonObject {
            put("tag", "string"); put("value", value.v)
        }
        is Value.BoolV -> buildJsonObject {
            put("tag", "bool"); put("value", value.v)
        }
        is Value.UnitV -> buildJsonObject {
            put("tag", "unit")
        }
        is Value.BytesV -> buildJsonObject {
            put("tag", "bytes"); put("value", Base64.getEncoder().encodeToString(value.v))
        }
        is Value.ProductV -> buildJsonObject {
            put("tag", "product")
            put("fields", buildJsonObject {
                // Deterministic field order: sorted by name.
                for (name in value.fields.keys.sorted()) {
                    put(name, encode(value.fields.getValue(name)))
                }
            })
        }
        is Value.SumV -> buildJsonObject {
            put("tag", "sum")
            put("case", value.case)
            val payload = value.payload
            if (payload != null) put("payload", encode(payload))
        }
        is Value.MapV -> buildJsonObject {
            put("tag", "map")
            // Each entry is {key, value}; entries sorted by the serialized
            // bytes of the encoded key, so two maps with equal contents in
            // different insertion order encode identically.
            val entries = value.entries.entries
                .map { (k, v) -> encode(k) to encode(v) }
                .sortedBy { it.first.toString() }
            put("entries", buildJsonArray {
                for ((k, v) in entries) {
                    add(buildJsonObject {
                        put("key", k)
                        put("value", v)
                    })
                }
            })
        }
        is Value.SetV -> buildJsonObject {
            put("tag", "set")
            val elements = value.entries
                .map { encode(it) }
                .sortedBy { it.toString() }
            put("elements", buildJsonArray { for (e in elements) add(e) })
        }
        // ---- non-snapshotable runtime-only variants ------------------------
        is Value.Closure -> throw ValueCodecError.NotSnapshotable(
            "Closure",
            "captures an evaluation environment that is not content-addressable as data; " +
                "a transition function's state is first-order data, but a Closure stored as state cannot round-trip",
        )
        is Value.FixpointFn -> throw ValueCodecError.NotSnapshotable(
            "FixpointFn",
            "captures an evaluation environment (recursive call slot + closed-over bindings) that cannot round-trip as data",
        )
        is Value.ForeignFn -> throw ValueCodecError.NotSnapshotable(
            "ForeignFn",
            "is a reference to a host builtin, not first-order data; it cannot be serialized as state",
        )
        is Value.Resource -> throw ValueCodecError.NotSnapshotable(
            "Resource",
            "is a live OS handle (kind=${value.kind}) that cannot be re-opened deterministically across a restart",
        )
        is Value.ToolDefV -> throw ValueCodecError.NotSnapshotable(
            "ToolDefV",
            "carries NodeId identity into a live callable implementation; it is not first-order data",
        )
        is Value.ResponseSchemaSpecV -> throw ValueCodecError.NotSnapshotable(
            "ResponseSchemaSpecV",
            "carries NodeId identity for a schema projection; it is not first-order data",
        )
    }

    /** Encode [value] to a canonical, non-pretty JSON string. Deterministic. */
    fun encodeToString(value: Value): String = json.encodeToString(JsonElement.serializer(), encode(value))

    // ---- decode -------------------------------------------------------------

    /** Decode a [JsonElement] (produced by [encode], or the legacy [EventCodec] event shape) into a [Value]. */
    fun decode(element: JsonElement, ctx: String = "<root>"): Value {
        val obj = element as? JsonObject
            ?: throw ValueCodecError.MalformedEncoding("value at $ctx must be a JSON object with a 'tag' field")
        val tag = obj["tag"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw ValueCodecError.MalformedEncoding("value at $ctx is missing the 'tag' field")
        return when (tag) {
            "int" -> Value.IntV(requireLong(obj, "value", ctx))
            "float" -> Value.FloatV(decodeFloat(requireString(obj, "value", ctx), ctx))
            "string" -> Value.StringV(requireString(obj, "value", ctx))
            "bool" -> Value.BoolV(requireBool(obj, "value", ctx))
            "unit" -> Value.UnitV
            "bytes" -> Value.BytesV(decodeBytes(requireString(obj, "value", ctx), ctx))
            "product" -> {
                val fields = (obj["fields"] as? JsonObject)
                    ?: throw ValueCodecError.MalformedEncoding("'product' at $ctx requires a 'fields' object")
                val out = LinkedHashMap<String, Value>(fields.size)
                for ((name, elt) in fields) {
                    out[name] = decode(elt, "$ctx.fields.$name")
                }
                Value.ProductV(out)
            }
            "sum" -> {
                val case = requireString(obj, "case", ctx)
                val payloadElt = obj["payload"]
                val payload = if (payloadElt == null || payloadElt is JsonNull) {
                    null
                } else {
                    decode(payloadElt, "$ctx.payload")
                }
                Value.SumV(case, payload)
            }
            "map" -> {
                val entries = (obj["entries"] as? JsonArray)
                    ?: throw ValueCodecError.MalformedEncoding("'map' at $ctx requires an 'entries' array")
                var m = persistentMapOf<Value, Value>()
                for ((i, entryElt) in entries.withIndex()) {
                    val entryObj = (entryElt as? JsonObject)
                        ?: throw ValueCodecError.MalformedEncoding("'map' entry at $ctx.entries[$i] must be an object {key, value}")
                    val k = decode(
                        entryObj["key"] ?: throw ValueCodecError.MalformedEncoding("'map' entry at $ctx.entries[$i] missing 'key'"),
                        "$ctx.entries[$i].key",
                    )
                    val v = decode(
                        entryObj["value"] ?: throw ValueCodecError.MalformedEncoding("'map' entry at $ctx.entries[$i] missing 'value'"),
                        "$ctx.entries[$i].value",
                    )
                    m = m.put(k, v)
                }
                Value.MapV(m)
            }
            "set" -> {
                val elements = (obj["elements"] as? JsonArray)
                    ?: throw ValueCodecError.MalformedEncoding("'set' at $ctx requires an 'elements' array")
                var s = persistentSetOf<Value>()
                for ((i, elt) in elements.withIndex()) {
                    s = s.add(decode(elt, "$ctx.elements[$i]"))
                }
                Value.SetV(s)
            }
            else -> throw ValueCodecError.MalformedEncoding(
                "unknown value tag '$tag' at $ctx (expected int, float, string, bool, unit, bytes, product, sum, map, or set)"
            )
        }
    }

    /** Decode a canonical JSON string into a [Value]. */
    fun decodeFromString(text: String): Value {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw ValueCodecError.MalformedEncoding("not valid JSON: ${e.message}")
        }
        return decode(element)
    }

    // ---- helpers ------------------------------------------------------------

    private fun encodeFloat(d: Double): String = when {
        d.isNaN() -> "NaN"
        d == Double.POSITIVE_INFINITY -> "Infinity"
        d == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> d.toString()  // Double.toString preserves -0.0 vs 0.0 and full precision
    }

    private fun decodeFloat(s: String, ctx: String): Double = when (s) {
        "NaN" -> Double.NaN
        "Infinity" -> Double.POSITIVE_INFINITY
        "-Infinity" -> Double.NEGATIVE_INFINITY
        else -> s.toDoubleOrNull()
            ?: throw ValueCodecError.MalformedEncoding("'float' value at $ctx is not a number: '$s'")
    }

    private fun decodeBytes(s: String, ctx: String): ByteArray = try {
        Base64.getDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        throw ValueCodecError.MalformedEncoding("'bytes' value at $ctx is not valid base64: '$s'")
    }

    private fun requireLong(obj: JsonObject, field: String, ctx: String): Long =
        (obj[field] as? JsonPrimitive)?.longOrNull
            ?: throw ValueCodecError.MalformedEncoding("field '$field' at $ctx must be an integer")

    private fun requireString(obj: JsonObject, field: String, ctx: String): String =
        (obj[field] as? JsonPrimitive)?.contentOrNull
            ?: throw ValueCodecError.MalformedEncoding("field '$field' at $ctx must be a string")

    private fun requireBool(obj: JsonObject, field: String, ctx: String): Boolean =
        (obj[field] as? JsonPrimitive)?.booleanOrNull
            ?: throw ValueCodecError.MalformedEncoding("field '$field' at $ctx must be a boolean")
}

/**
 * Q-059: structured errors raised by [ValueCodec]. These are host-facing
 * serialization failures, not in-language `Attempt`-catchable errors — they
 * surface to the host calling `snapshot` / `writeSnapshot`, which is the right
 * place to learn that a program stored a non-serializable value as state.
 */
sealed class ValueCodecError(message: String) : RuntimeException(message) {
    /**
     * The value (or a sub-value) is a runtime-only variant that cannot be
     * serialized as first-order state: a Closure / FixpointFn / ForeignFn
     * (captured environment), a Resource (live OS handle), or a
     * ToolDefV / ResponseSchemaSpecV (NodeId-identity callable).
     */
    class NotSnapshotable(val variant: String, val reason: String) :
        ValueCodecError("cannot snapshot a $variant value: $reason")

    /** The JSON being decoded is not a well-formed value encoding. */
    class MalformedEncoding(val detail: String) :
        ValueCodecError("malformed Value encoding: $detail")
}
