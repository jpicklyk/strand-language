package org.strand.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.strand.core.NodeId
import org.strand.interpreter.Builtins
import org.strand.interpreter.IoFailure
import org.strand.interpreter.ResourceTable
import org.strand.interpreter.Value

/**
 * Q-046 actor-runtime bridge: a host-side feeder coroutine that drains a
 * Q-045 streaming handle and delivers each chunk into a `source`-bound
 * `External` EventStream as an ordinary `Bytes` event.
 *
 * The feeder is the bridge between the synchronous *pull* primitive
 * (`Stream.Receive`) and the push-based actor runtime. One feeder runs per
 * `source`-bound external stream, launched in [StateMachineRuntime.runGroup]
 * after the consuming actors exist. It loops calling the shipped
 * `Stream.Receive` builtin on [Dispatchers.IO] (the read blocks natively, so
 * it must not run on the actor `select` dispatcher) and pushes each `Some`
 * chunk through the stream's existing [OverflowDispatcher] — so the consumer's
 * overflow policy and the per-instance [EventRecorder] apply unchanged, which
 * is what makes a bridged run replayable.
 *
 * Lifecycle (`Start → Draining → (Eof | Failure | Cancelled) → Closed`):
 *  * On EOF (`Stream.Receive` returns `None`) the loop exits.
 *  * On a transport [IoFailure] the failure kind is recorded and the loop
 *    exits — the feeder never retries (a silent reconnect would resume a live
 *    stream at a non-deterministic offset and break replay) and never fails
 *    the group (per-instance "let it crash").
 *  * On cancellation the loop's `isActive` check exits; a blocking read in
 *    flight is bounded by the host `streamReceiveTimeoutMillis`.
 *
 * Every exit path runs the single `finally`: it closes the underlying handle
 * exactly once (the `ResourceTable.remove` the close builtin performs is
 * idempotent) and calls [StreamBus.producerHalted], which — because a
 * `source`-bound external stream has no machine producer (baked
 * `producerCount == 0`, incremented to 1 by the feeder's
 * [StreamBus.producerSpawned] at start) — closes the producer channel so the
 * consuming actor sees EOF and halts. The terminal marker the machine observes
 * is therefore channel closure, not an in-band sentinel.
 *
 * The raw-`Bytes` core slice cannot carry a typed failure case, so a transport
 * failure surfaces to the machine as the same channel closure as a clean EOF;
 * the cause is retained in [failureKind] for metrics/diagnostics. A decoded
 * `Chunk | Eof | Failure` event shape is deferred (proposal § 8).
 */
internal class ExternalStreamFeeder(
    val streamId: NodeId,
    private val handle: Value.Resource,
    private val dispatcher: OverflowDispatcher,
    private val bus: StreamBus,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    /** The scrubbed transport-failure kind if the feeder ended on failure; null on clean EOF/cancel. */
    @Volatile
    var failureKind: String? = null
        private set

    private val receiveTarget: String = when (handle.kind) {
        ResourceTable.KIND_LLM_STREAM -> "strand-builtin:LLM.Stream.Receive"
        ResourceTable.KIND_SOCKET -> "strand-builtin:Net.Stream.Receive"
        else -> error("ExternalStreamFeeder: unsupported handle kind '${handle.kind}'")
    }
    private val closeTarget: String = when (handle.kind) {
        ResourceTable.KIND_LLM_STREAM -> "strand-builtin:LLM.Stream.Close"
        ResourceTable.KIND_SOCKET -> "strand-builtin:Net.Close"
        else -> error("ExternalStreamFeeder: unsupported handle kind '${handle.kind}'")
    }

    suspend fun run() {
        bus.producerSpawned()
        try {
            while (currentCoroutineContext().isActive) {
                when (val chunk = withContext(Dispatchers.IO) { receiveOnce() }) {
                    is Chunk.Data -> dispatcher.send(Value.BytesV(chunk.bytes))
                    Chunk.Eof -> break
                    is Chunk.Failure -> {
                        failureKind = chunk.kind
                        break
                    }
                }
            }
        } finally {
            closeHandle()
            bus.producerHalted()
        }
    }

    private fun receiveOnce(): Chunk {
        val fn = Builtins.lookup(receiveTarget) ?: error("ExternalStreamFeeder: missing builtin $receiveTarget")
        return try {
            val result = fn.invoke(listOf(handle, Value.IntV(maxBytes.toLong()))) as Value.SumV
            when (result.case) {
                "Some" -> Chunk.Data((result.payload as Value.BytesV).v)
                else -> Chunk.Eof
            }
        } catch (e: IoFailure) {
            Chunk.Failure(e.kind)
        }
    }

    private fun closeHandle() {
        Builtins.lookup(closeTarget)?.let { close ->
            try {
                close.invoke(listOf(handle))
            } catch (_: IoFailure) {
                // Close is idempotent; a failure here means already-closed.
            }
        }
    }

    private sealed interface Chunk {
        data class Data(val bytes: ByteArray) : Chunk
        object Eof : Chunk
        data class Failure(val kind: String) : Chunk
    }

    companion object {
        /** Per-read ceiling on bytes pulled per `Stream.Receive`. */
        const val DEFAULT_MAX_BYTES = 65536
    }
}
