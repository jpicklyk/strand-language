package org.strand.runtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import org.strand.core.ConsumerMode
import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.StreamKind
import org.strand.interpreter.CapabilitySet

/**
 * Topology and host I/O for one async multi-machine run.
 *
 * A `MachineGroup` is the unit of execution for [StateMachineRuntime.runGroup].
 * It bundles the canonical store + reverse map produced by `Hasher.finalize`,
 * the list of [StateMachine] NodeIds to run as actors, optional host-side
 * sources and sinks for streams declared `external` or `output` in the graph
 * topology, and the per-group switches (channel capacity, recording).
 *
 * Stream wiring is computed once at `runGroup` startup from the topology:
 *
 *  * Every EventStream node referenced by any machine gets one
 *    `Channel<Value>` whose capacity is the EventStream's own `bufferSize`
 *    field (Layer 6 step 3 slice 3.1) — falling back to the group's
 *    [bufferCapacity] when the field is absent. Structurally-equal streams
 *    share a channel (content-addressing pins this); when two such streams
 *    disagree on `bufferSize` they will already have been hashed to distinct
 *    nodes by the canonical encoder, so the share-by-hash invariant holds.
 *  * Each output channel is wrapped in an [OverflowDispatcher] whose policy
 *    is the EventStream's own `overflowPolicy` field (slice 3.1), defaulting
 *    to [org.strand.core.OverflowPolicy.BlockProducer] when absent. Actors
 *    send through the dispatcher; consumers read the underlying channel
 *    unchanged.
 *  * `external` input streams expose a [SendChannel] for the host to push
 *    events into; the underlying channel feeds the receiving machine.
 *  * `output` streams expose a [ReceiveChannel] for the host to drain
 *    emitted events; the underlying channel is fed by the producing machine.
 *  * `internal` streams have no host-side handle — the channel is shared
 *    between the producing machine's output and the consuming machine's
 *    input.
 *
 * Step 2 enforces single-producer and single-consumer on internal streams
 * (see verifier rules in the proposal); fan-in and fan-out are deferred to
 * step 3 slice 3.6.
 */
data class MachineGroup(
    /** Canonical NodeStore (post-finalize) containing the machine definitions and all reachable nodes. */
    val store: NodeStore,
    /** Hash → NodeId reverse map from [org.strand.hashing.Hasher.finalize], threaded to verifier/interpreter for NodeRef resolution. */
    val hashToNodeId: Map<Hash, NodeId>,
    /** Each NodeId is a [Node.StateMachine] in [store]. The verifier has accepted each. */
    val machines: List<NodeId>,
    /**
     * Optional NodeId → Hash forward map from [org.strand.hashing.Hasher.finalize].
     * Used by Layer 6 step 3 slice 3.3 [MachineGroupHandle.snapshot] to record
     * the snapshotted machine's content hash for replay-integrity checks. Empty
     * by default — callers that don't need snapshots can omit it; callers that
     * do (the CLI `strand group --snapshot`, replay-determinism tests) pass
     * `finalized.nodeIdToHash`.
     */
    val nodeIdToHash: Map<NodeId, Hash> = emptyMap(),
    /**
     * Capability context surrounding all machines in this group. Each actor
     * evaluates its transition function under this context; the implicit
     * `StateMachine.Send` / `Receive` effects are runtime-internal and not
     * checked here. The transition function's own effects ARE checked.
     */
    val capabilities: CapabilitySet = CapabilitySet.EMPTY,
    /** Bounded-channel capacity per stream. Default 1024 per Q-015 step-2 backpressure-preview. */
    val bufferCapacity: Int = 1024,
    /**
     * Toggle per-instance event recording. Default `true` so test workflows
     * get replay-determinism support; production workloads pass `false` to
     * avoid unbounded recorder accumulation. When `false`, each
     * [MachineInstance.recorder] is null and the actor's `record(...)` is a
     * no-op.
     */
    val recordInputs: Boolean = true,
    /**
     * Optional per-instance transition-dispatcher factory (Track A.4 VM
     * integration). When non-null, every spawned [MachineInstance] gets
     * a dispatcher built by this factory; the actor's per-event step
     * goes through `dispatcher.applyTransition` instead of the legacy
     * `interpreter.applyCallable` path. Default null preserves the
     * pre-Track-A.4 behavior for existing tests + fixtures. Used by
     * `:corpus`'s `VmAsyncMachineEquivalenceTest` to drive the actor
     * loop through the bytecode VM.
     */
    val dispatcherFactory: TransitionDispatcherFactory? = null,
) {

    /**
     * Unique EventStream NodeIds across all machines in the group. Channel
     * allocation iterates this set once; the result is the wiring's keyspace.
     */
    internal fun uniqueStreams(): Set<NodeId> {
        val out = LinkedHashSet<NodeId>()
        for (machineId in machines) {
            val machine = store.get(machineId) as Node.StateMachine
            out += machine.inputStreams
            out += machine.outputStreams
        }
        return out
    }

    /**
     * For each input stream declared `external` in the topology, return a
     * [SendChannel] the host can push events into. The handle is built by
     * the runtime at `runGroup` time and returned via [MachineGroupHandle].
     */
    internal fun externalInputStreams(): List<NodeId> =
        uniqueStreams().filter { streamKindOf(it) == StreamKind.External }

    /**
     * For each output stream declared `output` in the topology, return a
     * [ReceiveChannel] the host can drain emitted events from.
     */
    internal fun externalOutputStreams(): List<NodeId> =
        uniqueStreams().filter { streamKindOf(it) == StreamKind.Output }

    internal fun internalStreams(): List<NodeId> =
        uniqueStreams().filter { streamKindOf(it) == StreamKind.Internal }

    internal fun streamKindOf(streamId: NodeId): StreamKind {
        val node = store.get(streamId) as? Node.EventStream
            ?: error("Expected EventStream at $streamId, got ${store.get(streamId)::class.simpleName}")
        return node.streamKind
    }

    /**
     * Validate the topology of this group before [StateMachineRuntime.runGroup]
     * allocates channels and spawns actors. Throws [MachineGroupValidationError]
     * for any of:
     *
     *  * an `internal` stream listed as an input by some machine but as an
     *    output by no machine — the consumer would block forever;
     *  * an `internal` stream listed as an output but with no consumer —
     *    the producer would block on a full buffer forever;
     *  * an `internal` stream whose [ConsumerMode] is [ConsumerMode.Single]
     *    (the default) with more than one consumer — single-consumer fan-out
     *    is enforced. Multi-consumer broadcast requires the stream's
     *    `consumerMode` to be [ConsumerMode.Broadcast] (slice 3.6).
     *
     * Slice 3.6 (Layer 6 step 3) relaxations:
     *  * Multi-producer fan-in on internal streams is now allowed
     *    unconditionally — multiple producer machines may emit into the same
     *    `internal` stream; the runtime merges their sends with
     *    nondeterministic interleaving (per-producer FIFO).
     *  * Multi-consumer fan-out is allowed when the stream's [ConsumerMode]
     *    is [ConsumerMode.Broadcast]; the runtime wraps the stream in a
     *    `MutableSharedFlow<Value>` and each consumer sees every emitted
     *    event.
     *
     * External-input streams are owned by the host (host pushes events
     * through the [MachineGroupHandle]); external-output streams are also
     * host-owned (host drains the handle). Their producer/consumer rules
     * follow the same single-side pattern but apply to the machines in the
     * group: an external-input stream may have at most one machine consumer
     * by default (relax with `consumerMode = Broadcast`); an external-output
     * stream may be produced by any number of machines (fan-in into the
     * host-drained sink).
     */
    internal fun validateTopology() {
        for (streamId in uniqueStreams()) {
            val producers = machines.filter { mid ->
                (store.get(mid) as Node.StateMachine).outputStreams.contains(streamId)
            }
            val consumers = machines.filter { mid ->
                (store.get(mid) as Node.StateMachine).inputStreams.contains(streamId)
            }
            val kind = streamKindOf(streamId)
            val mode = consumerModeOf(streamId)
            when (kind) {
                StreamKind.Internal -> {
                    if (producers.isEmpty()) throw MachineGroupValidationError.InternalStreamNoProducer(streamId)
                    // Multi-producer fan-in is now allowed (slice 3.6).
                    if (consumers.isEmpty()) throw MachineGroupValidationError.InternalStreamNoConsumer(streamId)
                    if (consumers.size > 1 && mode == ConsumerMode.Single) {
                        throw MachineGroupValidationError.InternalStreamMultipleConsumers(streamId, consumers)
                    }
                }
                StreamKind.External -> {
                    if (producers.isNotEmpty()) throw MachineGroupValidationError.ExternalStreamCannotBeProduced(streamId, producers)
                    if (consumers.size > 1 && mode == ConsumerMode.Single) {
                        throw MachineGroupValidationError.InternalStreamMultipleConsumers(streamId, consumers)
                    }
                }
                StreamKind.Output -> {
                    if (consumers.isNotEmpty()) throw MachineGroupValidationError.OutputStreamCannotBeConsumed(streamId, consumers)
                    // Multi-producer fan-in on output streams is allowed (slice 3.6).
                }
            }
        }
    }

    /**
     * Resolve the [ConsumerMode] of [streamId], defaulting to
     * [ConsumerMode.Single] when the EventStream's `consumerMode` field is
     * null (pre-slice-3.6 corpus programs). Slice 3.6 introduced this field
     * with the additive-versioning rule.
     */
    internal fun consumerModeOf(streamId: NodeId): ConsumerMode {
        val node = store.get(streamId) as? Node.EventStream
            ?: error("Expected EventStream at $streamId, got ${store.get(streamId)::class.simpleName}")
        return node.consumerMode ?: ConsumerMode.Single
    }

    /**
     * Q-046: the `External` streams in this group that carry a `source` edge,
     * each paired with its IO-opening node. The runtime launches one feeder
     * coroutine per entry at `runGroup`.
     */
    internal fun sourceBoundExternalStreams(): List<Pair<NodeId, NodeId>> =
        uniqueStreams().mapNotNull { streamId ->
            val node = store.get(streamId) as? Node.EventStream ?: return@mapNotNull null
            val src = node.source
            if (node.streamKind == StreamKind.External && src != null) streamId to src else null
        }

    /**
     * Q-046: the subset of [externalInputStreams] the host may feed and
     * close — external input streams WITHOUT a `source` edge. A
     * `source`-bound external stream is deliberately excluded: its sole
     * producer is the runtime's [ExternalStreamFeeder], which owns channel
     * closure via [StreamBus.producerHalted] when the IO source reaches
     * EOF / failure. Exposing a host-facing [kotlinx.coroutines.channels.SendChannel]
     * for it would create a second producer with conflicting closure
     * ownership — a host `close()` while the feeder is mid-drain crashes
     * the feeder with `ClosedSendChannelException`, and host-injected
     * events would interleave non-replayably with the recorded IO chunks.
     * [StateMachineRuntime.runGroup] builds [MachineGroupHandle.externalInputs]
     * from this list, so source-bound streams never surface to the host.
     */
    internal fun hostFeedableExternalStreams(): List<NodeId> {
        val sourceBound = sourceBoundExternalStreams().map { it.first }.toSet()
        return externalInputStreams().filter { it !in sourceBound }
    }

    /**
     * Q-046 group-start soundness gate. The host-side feeder performs the
     * opener's semantic effect (E-035 `LLM.Generate` / E-001 `Network.Connect`)
     * and the E-004 `Network.Receive` reads on the program's behalf, outside
     * any transition — so those transport effects appear in no machine's
     * closure. To keep `closure(g)` a sound upper bound (the Q-044 harm bound)
     * the group must hold the capabilities for them: for every `source`-bound
     * external stream, the union of the opener's declared effect categories and
     * `Network.Receive` must be covered by [capabilities]. Coverage is checked
     * by category name (the feeder's `Network.Receive` has no graph node to key
     * on, and the gate only needs to confirm the category is granted; the
     * concrete socket the feeder opens is independently constrained by the
     * Q-041 sandbox). A gap aborts the run before any IO source is opened, so a
     * group whose context revoked `Network.Receive` (directly or via an
     * enclosing `CapabilityScope`) cannot bridge a live stream.
     */
    internal fun validateStreamSourceCoverage() {
        val bound = sourceBoundExternalStreams()
        if (bound.isEmpty()) return
        val grantedNames = capabilities.grants.keys.mapNotNull { catId ->
            (store.getOrNull(catId) as? Node.EffectCategory)?.categoryName
        }.toSet()
        for ((streamId, sourceId) in bound) {
            val required = openerEffectNames(sourceId) + DRAIN_EFFECT_NAME
            val missing = required - grantedNames
            if (missing.isNotEmpty()) {
                throw MachineGroupValidationError.ExternalStreamSourceEffectUncovered(streamId, sourceId, missing)
            }
        }
    }

    /** The effect category names the opener Application at [sourceId] declares. */
    private fun openerEffectNames(sourceId: NodeId): Set<String> {
        val app = store.getOrNull(sourceId) as? Node.Application ?: return emptySet()
        return app.effectInstances.mapNotNull { declId ->
            val decl = store.getOrNull(declId) as? Node.EffectDecl ?: return@mapNotNull null
            (store.getOrNull(decl.effectType) as? Node.EffectCategory)?.categoryName
        }.toSet()
    }

    private companion object {
        /** The transport effect the feeder's `Stream.Receive` reads incur. */
        const val DRAIN_EFFECT_NAME = "Network.Receive"
    }
}

/**
 * Structured validation errors raised at [MachineGroup.validateTopology]. These
 * are runtime-side topology checks because the per-machine [org.strand.verifier.Verifier]
 * verifies one StateMachine at a time and cannot reason about cross-machine
 * stream connections. A future "GroupVerifier" could lift these into
 * verifier rules; for step 2 the runtime-side checks are sufficient.
 */
sealed class MachineGroupValidationError(message: String) : RuntimeException(message) {
    class InternalStreamNoProducer(val stream: NodeId) :
        MachineGroupValidationError("internal stream $stream has no producing machine in the group")
    /**
     * Retained as a sealed-hierarchy member but no longer raised by
     * [MachineGroup.validateTopology] as of slice 3.6 — multi-producer
     * fan-in on internal and output streams is now allowed unconditionally.
     */
    class InternalStreamMultipleProducers(val stream: NodeId, val producers: List<NodeId>) :
        MachineGroupValidationError("internal stream $stream has multiple producers $producers (retained for compatibility; slice 3.6 allows multi-producer fan-in)")
    class InternalStreamNoConsumer(val stream: NodeId) :
        MachineGroupValidationError("internal stream $stream has no consuming machine; producer would block forever")
    class InternalStreamMultipleConsumers(val stream: NodeId, val consumers: List<NodeId>) :
        MachineGroupValidationError("internal stream $stream has multiple consumers $consumers; step 2 enforces single-consumer (broadcast deferred to step 3)")
    class ExternalStreamCannotBeProduced(val stream: NodeId, val producers: List<NodeId>) :
        MachineGroupValidationError("external stream $stream is listed as an output by $producers; external streams must be host-driven (no producing machine in the group)")
    class OutputStreamCannotBeConsumed(val stream: NodeId, val consumers: List<NodeId>) :
        MachineGroupValidationError("output stream $stream is listed as an input by $consumers; output streams must be host-drained (no consuming machine in the group)")
    /**
     * Q-046: a `source`-bound external stream's transport effects are not all
     * covered by the group's capability context. Raised at group start before
     * any IO source is opened — the soundness gate that keeps the harm bound
     * intact for host-driven stream feeders.
     */
    class ExternalStreamSourceEffectUncovered(val stream: NodeId, val source: NodeId, val missing: Set<String>) :
        MachineGroupValidationError("source-bound external stream $stream (opener $source) requires capabilities $missing not granted to the group")
}
