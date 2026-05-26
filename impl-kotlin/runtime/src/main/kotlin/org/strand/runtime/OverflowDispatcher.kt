package org.strand.runtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import org.strand.core.OverflowPolicy
import org.strand.interpreter.Value
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-output-channel send adapter wrapping a `Channel<Value>` with one of the
 * four [OverflowPolicy] variants (Layer 6 step 3 slice 3.1, Q-015).
 *
 * Allocated once per output stream at [MachineGroup.runGroup] startup, then
 * consulted on every send the actor performs. The dispatcher owns no state
 * of its own beyond a drop counter and (for [OverflowPolicy.Sample]) the
 * last-accepted timestamp; the [channel] is the same `Channel<Value>` shared
 * with downstream consumers, so the dispatcher is a pure decorator with no
 * channel-lifecycle obligations.
 *
 * Send semantics per policy:
 *
 *  * **BlockProducer** — `channel.send(value)` (suspending). The producer
 *    is suspended until the channel has capacity, matching step-2's default
 *    backpressure behavior exactly.
 *  * **DropNewest** — `channel.trySend(value)`; if it returns
 *    `ChannelResult.isFailure` (channel full or closed), the incoming value
 *    is discarded and the drop counter ticks. The producer never suspends.
 *  * **DropOldest** — `channel.tryReceive()` to drain one stale value, then
 *    `channel.trySend(value)` for the new one. Both succeed in steady state
 *    (we just freed a slot). If the channel is closed mid-operation we fall
 *    back to the closed-state behavior of trySend. The drop counter ticks
 *    once per drained-stale-value.
 *  * **Sample(n)** — block-producer behavior, but events arriving within
 *    `intervalNanos` of the previous accepted event are silently dropped.
 *    The clock source is wall-clock (`System.nanoTime`).
 *
 * The dispatcher is internal to the runtime — consumers (other machines or
 * the host's external-output drain) interact with the underlying
 * `Channel<Value>` directly.
 */
internal class OverflowDispatcher(
    val channel: Channel<Value>,
    val policy: OverflowPolicy,
) {
    /** Number of values dropped due to overflow (or sample-rate limiting). */
    private val drops = AtomicLong(0)

    /** Last accepted-event timestamp (nanos), used by [OverflowPolicy.Sample]. */
    private val lastAcceptedNanos = AtomicLong(Long.MIN_VALUE)

    val droppedCount: Long get() = drops.get()

    suspend fun send(value: Value) {
        when (policy) {
            OverflowPolicy.BlockProducer -> channel.send(value)
            OverflowPolicy.DropNewest -> {
                val result: ChannelResult<Unit> = channel.trySend(value)
                if (result.isFailure) drops.incrementAndGet()
            }
            OverflowPolicy.DropOldest -> sendWithReplace(value)
            is OverflowPolicy.Sample -> sampleAndSend(value, policy.intervalNanos)
        }
    }

    /**
     * DropOldest implementation: free one slot via `tryReceive`, then enqueue.
     * If the channel is empty (no stale value to drop) we just enqueue —
     * which may suspend if a concurrent send filled the channel. This
     * non-blocking-on-stale behavior is what distinguishes DropOldest from
     * DropNewest: a slow-but-not-stuck consumer eventually catches up.
     */
    private suspend fun sendWithReplace(value: Value) {
        // Fast path: channel has capacity → enqueue without dropping.
        val tryFirst = channel.trySend(value)
        if (tryFirst.isSuccess) return
        if (tryFirst.isClosed) {
            // Closed (or in the process of closing) — the producer should
            // exit. Surface via the standard send call which will throw the
            // expected ClosedSendChannelException.
            channel.send(value)
            return
        }
        // Channel is full but open. Drain one and retry.
        val drained = channel.tryReceive()
        if (drained.isSuccess) {
            drops.incrementAndGet()
        }
        // Retry the send. May still need to wait briefly if a concurrent
        // consumer beat us to the freed slot; the suspending send handles
        // that without busy-looping.
        channel.send(value)
    }

    /**
     * Sample(n) implementation: accept the event only if `intervalNanos`
     * have passed since the previously accepted event. Coarse rate-limiting
     * at the producer side. The first event after construction always
     * passes (the sentinel `Long.MIN_VALUE` guarantees the elapsed-time
     * comparison succeeds).
     */
    private suspend fun sampleAndSend(value: Value, intervalNanos: Long) {
        val now = System.nanoTime()
        val last = lastAcceptedNanos.get()
        val elapsed = now - last
        if (last != Long.MIN_VALUE && elapsed < intervalNanos) {
            drops.incrementAndGet()
            return
        }
        // CAS to make the timestamp update atomic w.r.t. concurrent sends
        // through the same dispatcher. If another sender beat us, we still
        // accept the event (their sample-window decision is independent).
        lastAcceptedNanos.compareAndSet(last, now)
        channel.send(value)
    }
}
