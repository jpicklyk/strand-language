package org.strand.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.strand.core.Hash
import org.strand.interpreter.Value

/**
 * Q-059: serialization for the runtime [Snapshot] so a checkpointed machine
 * group can leave the process and be restored into a fresh JVM.
 *
 * Slice 3.3 (`proposals/implemented/state-machines-runtime-step-3.md`) shipped
 * the in-memory [Snapshot] data class and explicitly deferred a persistent wire
 * format until "a canonical encoder over runtime `Value`" existed. [ValueCodec]
 * is that encoder; `SnapshotCodec` is the wire format on top of it.
 *
 * The format is a self-describing JSON document:
 * ```
 * {
 *   "machineHash": "<lowercase-hex multihash>",
 *   "snapshotState": { ...ValueCodec... },
 *   "processedEventCount": 500,
 *   "recordedEventsPreSnapshot": [ { ...ValueCodec... }, ... ]   // or null
 * }
 * ```
 *
 * The `machineHash` serializes via [Hash.toString] (the lowercase-hex
 * multihash) and parses back via [Hash.fromHex]; the snapshot's integrity
 * check against a resuming machine's hash ([StateMachineRuntime.resume] →
 * [SnapshotMachineHashMismatch]) is unchanged.
 *
 * **Minimal-state choice.** We persist only what deterministic resume needs:
 * the current `Value` state and the processed-event cursor, plus (only when
 * `recordInputs` was enabled) the pre-snapshot recorder tail. The recorder tail
 * exists to reconstruct *pre-snapshot history*, not for resume correctness —
 * transitions are pure, so resuming from `snapshotState` over the post-snapshot
 * events is deterministic regardless of whether the tail is present. We do NOT
 * persist the [org.strand.core.EvaluationLimits] budget: resume resets it by
 * design (slice 3.3), and persisting it would couple the snapshot to the limits
 * model.
 *
 * If a snapshot's `snapshotState` or any recorder event is a non-snapshotable
 * runtime-only [Value] (a Closure stored as state, a live Resource), [encode]
 * propagates the [ValueCodecError.NotSnapshotable] from [ValueCodec] — the host
 * learns precisely why this snapshot cannot persist, rather than getting a
 * corrupt document.
 */
object SnapshotCodec {

    private val json = Json { }

    /** Encode [snapshot] to a JSON string. Propagates [ValueCodecError.NotSnapshotable] for non-serializable state. */
    fun encode(snapshot: Snapshot): String {
        val element = buildJsonObject {
            put("machineHash", snapshot.machineHash.toString())
            put("snapshotState", ValueCodec.encode(snapshot.snapshotState))
            put("processedEventCount", snapshot.processedEventCount)
            val recorded = snapshot.recordedEventsPreSnapshot
            if (recorded == null) {
                put("recordedEventsPreSnapshot", JsonNull)
            } else {
                put("recordedEventsPreSnapshot", buildJsonArray {
                    for (e in recorded) add(ValueCodec.encode(e))
                })
            }
        }
        return json.encodeToString(JsonElement.serializer(), element)
    }

    /** Decode a JSON string produced by [encode] back into a [Snapshot]. */
    fun decode(text: String): Snapshot {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw SnapshotCodecError("snapshot document is not valid JSON: ${e.message}")
        }
        val obj = element as? JsonObject
            ?: throw SnapshotCodecError("snapshot document must be a JSON object")

        val hashHex = (obj["machineHash"] as? JsonPrimitive)?.contentOrNull
            ?: throw SnapshotCodecError("snapshot is missing the 'machineHash' string field")
        val machineHash = try {
            Hash.fromHex(hashHex)
        } catch (e: IllegalArgumentException) {
            throw SnapshotCodecError("snapshot 'machineHash' is not a valid multihash hex: ${e.message}")
        }

        val stateElt = obj["snapshotState"]
            ?: throw SnapshotCodecError("snapshot is missing the 'snapshotState' field")
        val snapshotState: Value = try {
            ValueCodec.decode(stateElt, "snapshotState")
        } catch (e: ValueCodecError.MalformedEncoding) {
            throw SnapshotCodecError("snapshot 'snapshotState' is malformed: ${e.detail}")
        }

        val processedEventCount = (obj["processedEventCount"] as? JsonPrimitive)?.longOrNull
            ?: throw SnapshotCodecError("snapshot is missing the integer 'processedEventCount' field")

        val recordedElt = obj["recordedEventsPreSnapshot"]
        val recorded: List<Value>? = when {
            recordedElt == null || recordedElt is JsonNull -> null
            recordedElt is JsonArray -> recordedElt.mapIndexed { i, e ->
                try {
                    ValueCodec.decode(e, "recordedEventsPreSnapshot[$i]")
                } catch (ex: ValueCodecError.MalformedEncoding) {
                    throw SnapshotCodecError("snapshot 'recordedEventsPreSnapshot[$i]' is malformed: ${ex.detail}")
                }
            }
            else -> throw SnapshotCodecError("snapshot 'recordedEventsPreSnapshot' must be an array or null")
        }

        return Snapshot(
            machineHash = machineHash,
            snapshotState = snapshotState,
            processedEventCount = processedEventCount,
            recordedEventsPreSnapshot = recorded,
        )
    }
}

/** Q-059: a malformed [Snapshot] document encountered by [SnapshotCodec.decode]. */
class SnapshotCodecError(message: String) : RuntimeException(message)
