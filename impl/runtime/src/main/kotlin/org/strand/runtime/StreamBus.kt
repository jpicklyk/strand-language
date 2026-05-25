package org.strand.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import org.strand.interpreter.Value

/**
 * Per-stream transport allocated by [StateMachineRuntime.runGroup] (Layer 6
 * step 3 slice 3.6).
 *
 * A [StreamBus] abstracts the publish/subscribe pattern for one EventStream.
 * Producers write into the bus's [producerChannel]; consumers read from a
 * channel obtained via [consumerChannel]. The bus owns the wiring between
 * the two — direct pass-through for single-consumer streams, fan-out
 * pumping for broadcast.
 *
 * Two concrete variants:
 *
 *  * [Direct] — pre-slice-3.6 behavior preserved. One underlying
 *    `Channel<Value>` is both the producer-facing write end and every
 *    consumer's read end. Multi-producer fan-in works automatically because
 *    multiple senders may share a single [Channel] (per-producer FIFO,
 *    nondeterministic interleaving across producers — Q-009 default). The
 *    bus owns no extra coroutines.
 *
 *  * [Broadcast] — slice 3.6 broadcast fan-out. The producer-facing
 *    [producerChannel] is drained by a pump coroutine launched in
 *    [StateMachineRuntime] which forwards every value to every per-consumer
 *    [Channel]. Each consumer machine's input is a fresh
 *    `Channel<Value>` allocated lazily by [consumerChannel]. Closing the
 *    producer channel causes the pump to drain remaining values, then close
 *    every per-consumer channel — so each consumer actor sees EOF on its
 *    input and halts normally.
 *
 *    The implementation chose explicit per-consumer Channel fan-out over
 *    `MutableSharedFlow` (the proposal's §6.4 sketch) for two reasons:
 *    (a) SharedFlow with `replay=0` silently drops emissions made before
 *    any collector subscribes, which requires a subscription-count gate
 *    that complicates the pump loop with test-scope timing dependence;
 *    (b) explicit fan-out keeps the closure semantics symmetric with the
 *    per-bus producer-counting model the runtime already uses. Tests
 *    against the SharedFlow alternative were flaky under `runTest` and the
 *    explicit pump is structurally simpler. The observable behavior
 *    (every consumer sees every event in producer FIFO order) is the same.
 *
 * The bus keeps the [MachineActor] surface unchanged: every actor still
 * `select`s over `Channel<Value>` for inputs and `send`s to `Channel<Value>`
 * for outputs.
 */
internal sealed class StreamBus {
    /**
     * Producer-facing write channel. Multiple producers share this single
     * Channel. For broadcast streams, this is the input to the pump
     * coroutine that forwards values to each per-consumer channel.
     */
    abstract val producerChannel: Channel<Value>

    /**
     * Number of producer machines that will write into this bus. The runtime
     * decrements this counter each time a producer halts (via
     * [producerHalted]); when the count reaches zero the producer channel
     * is closed so downstream consumers see EOF. Pre-slice-3.6 single-
     * producer streams use [producerCount] = 1 and behave identically to
     * the step-2 behavior. Streams with no machine producer (external-input
     * streams) use [producerCount] = 0 and never auto-close — the host owns
     * channel closure via the [externalInputs] SendChannel.
     */
    abstract val producerCount: Int

    /**
     * Counter of producers still emitting. Initialized to [producerCount];
     * each producer machine's actor loop calls [producerHalted] once on
     * exit. When the counter reaches zero, [producerChannel] is closed.
     */
    private var remainingProducers: Int = 0

    /**
     * Initialize [remainingProducers] from [producerCount]. Called once at
     * bus construction; using a setter rather than init order to keep the
     * subclass constructors clean.
     */
    fun initProducerCount() {
        remainingProducers = producerCount
    }

    /**
     * Decrement the producer-alive counter; if all producers have halted,
     * close the producer-facing channel so consumers see EOF. Called from
     * the actor loop's `finally` block via [MachineActor].
     */
    @Synchronized
    fun producerHalted() {
        if (remainingProducers <= 0) return
        remainingProducers--
        if (remainingProducers == 0) {
            producerChannel.close()
        }
    }

    /**
     * Increment the producer-alive counter (Layer 6 step 3 slice 3.2
     * supervision). Called by [RuntimeContext.spawn] when a new producer
     * is added to an existing stream after group startup — without this
     * the bus would see a producer halt before the new producer ever
     * registered, dropping the count below zero or closing the channel
     * prematurely. Returns the new producer count for diagnostics.
     */
    @Synchronized
    fun producerSpawned(): Int {
        remainingProducers++
        return remainingProducers
    }

    /**
     * Decrement the counter exactly once per initial machine that produces
     * into this stream. Slice 3.2 spawns INITIAL machines through the same
     * `RuntimeContext.spawn` path that handles dynamic spawn, so each initial
     * machine's `producerSpawned()` call would double-count vs. the
     * [producerCount] baked into the bus at Pass 1 of `runGroup`. This
     * offset cancels that double-count so the bus ends up with
     * [remainingProducers] == [producerCount] after initial spawn.
     */
    @Synchronized
    fun producerSpawnedOffsetForInitial() {
        if (remainingProducers > 0) remainingProducers--
    }

    /**
     * Obtain (allocating if necessary) a read-facing channel for a specific
     * consumer machine instance.
     *
     *  * For [Direct], this returns the same single channel regardless of
     *    [consumerKey] — the lone consumer reads from the producer channel.
     *  * For [Broadcast], a fresh per-consumer [Channel] is allocated on
     *    first call for [consumerKey]. The broadcast pump (launched in
     *    [StateMachineRuntime.runGroup]) reads the per-consumer channels
     *    via [perConsumerChannels].
     *
     * [consumerKey] is any value uniquely identifying the consumer — in
     * practice the consumer machine's NodeId.
     */
    abstract fun consumerChannel(consumerKey: Any, scope: CoroutineScope): Channel<Value>

    /**
     * Direct (single-consumer) bus: producers and the lone consumer share
     * one `Channel<Value>`. Allocated for streams whose
     * [org.strand.core.Node.EventStream.consumerMode] is `null` or
     * [org.strand.core.ConsumerMode.Single] (the default).
     */
    internal class Direct(
        override val producerChannel: Channel<Value>,
        override val producerCount: Int,
    ) : StreamBus() {
        init { initProducerCount() }
        override fun consumerChannel(consumerKey: Any, scope: CoroutineScope): Channel<Value> =
            producerChannel
    }

    /**
     * Broadcast bus: a producer-facing `Channel<Value>` is drained by a
     * pump coroutine and every drained value is forwarded to every
     * per-consumer channel. Each consumer machine gets a fresh
     * `Channel<Value>`.
     *
     * Closure semantics: when the producer-facing channel closes (because
     * all upstream actors have halted), the pump exits and closes every
     * per-consumer channel via [closeAllConsumerChannels]. Downstream
     * consumer actors then see EOF on their inputs and halt normally.
     */
    internal class Broadcast(
        override val producerChannel: Channel<Value>,
        override val producerCount: Int,
        private val perConsumerBufferCapacity: Int,
    ) : StreamBus() {
        init { initProducerCount() }
        private val perConsumer: MutableMap<Any, Channel<Value>> = LinkedHashMap()

        override fun consumerChannel(consumerKey: Any, scope: CoroutineScope): Channel<Value> {
            perConsumer[consumerKey]?.let { return it }
            val channel = Channel<Value>(capacity = perConsumerBufferCapacity)
            perConsumer[consumerKey] = channel
            return channel
        }

        /**
         * The per-consumer channels allocated so far. The runtime's
         * broadcast pump iterates this list on every drain to forward the
         * value into each consumer's channel. Snapshot the values (not the
         * live map) to avoid concurrent-modification surprises during
         * pump iteration if a consumer subscribes late.
         */
        internal fun perConsumerChannels(): List<Channel<Value>> = perConsumer.values.toList()

        /**
         * Close every per-consumer channel allocated so far. Called by the
         * runtime's broadcast pump after the producer-facing channel has
         * closed and the pump has drained any remaining values. After this
         * returns, the consumer actors see EOF on their inputs and halt.
         */
        internal fun closeAllConsumerChannels() {
            for (channel in perConsumer.values) {
                channel.close()
            }
        }
    }
}
