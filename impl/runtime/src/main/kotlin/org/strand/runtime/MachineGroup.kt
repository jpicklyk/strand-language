package org.strand.runtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
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
 *    `Channel<Value>` of the group's [bufferCapacity]; structurally-equal
 *    streams share a channel (content-addressing pins this).
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
 * step 3.
 */
data class MachineGroup(
    /** Canonical NodeStore (post-finalize) containing the machine definitions and all reachable nodes. */
    val store: NodeStore,
    /** Hash → NodeId reverse map from [org.strand.hashing.Hasher.finalize], threaded to verifier/interpreter for NodeRef resolution. */
    val hashToNodeId: Map<Hash, NodeId>,
    /** Each NodeId is a [Node.StateMachine] in [store]. The verifier has accepted each. */
    val machines: List<NodeId>,
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
     *  * an `internal` stream listed as an output by more than one machine —
     *    step 2 enforces single-producer fan-in (multi-producer is step 3);
     *  * an `internal` stream listed as an output but with no consumer —
     *    the producer would block on a full buffer forever;
     *  * an `internal` stream with more than one consumer — step 2 enforces
     *    single-consumer fan-out (multi-consumer broadcast is step 3).
     *
     * External-input streams are owned by the host (host pushes events
     * through the [MachineGroupHandle]); external-output streams are also
     * host-owned (host drains the handle). Their producer/consumer rules
     * follow the same single-side pattern but apply to the machines in the
     * group: an external-input stream is consumed by exactly one machine, an
     * external-output stream is produced by exactly one machine.
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
            when (kind) {
                StreamKind.Internal -> {
                    if (producers.isEmpty()) throw MachineGroupValidationError.InternalStreamNoProducer(streamId)
                    if (producers.size > 1) throw MachineGroupValidationError.InternalStreamMultipleProducers(streamId, producers)
                    if (consumers.isEmpty()) throw MachineGroupValidationError.InternalStreamNoConsumer(streamId)
                    if (consumers.size > 1) throw MachineGroupValidationError.InternalStreamMultipleConsumers(streamId, consumers)
                }
                StreamKind.External -> {
                    if (producers.isNotEmpty()) throw MachineGroupValidationError.ExternalStreamCannotBeProduced(streamId, producers)
                    if (consumers.size > 1) throw MachineGroupValidationError.InternalStreamMultipleConsumers(streamId, consumers)
                }
                StreamKind.Output -> {
                    if (consumers.isNotEmpty()) throw MachineGroupValidationError.OutputStreamCannotBeConsumed(streamId, consumers)
                    if (producers.size > 1) throw MachineGroupValidationError.InternalStreamMultipleProducers(streamId, producers)
                }
            }
        }
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
    class InternalStreamMultipleProducers(val stream: NodeId, val producers: List<NodeId>) :
        MachineGroupValidationError("internal stream $stream has multiple producers $producers; step 2 enforces single-producer (fan-in deferred to step 3)")
    class InternalStreamNoConsumer(val stream: NodeId) :
        MachineGroupValidationError("internal stream $stream has no consuming machine; producer would block forever")
    class InternalStreamMultipleConsumers(val stream: NodeId, val consumers: List<NodeId>) :
        MachineGroupValidationError("internal stream $stream has multiple consumers $consumers; step 2 enforces single-consumer (broadcast deferred to step 3)")
    class ExternalStreamCannotBeProduced(val stream: NodeId, val producers: List<NodeId>) :
        MachineGroupValidationError("external stream $stream is listed as an output by $producers; external streams must be host-driven (no producing machine in the group)")
    class OutputStreamCannotBeConsumed(val stream: NodeId, val consumers: List<NodeId>) :
        MachineGroupValidationError("output stream $stream is listed as an input by $consumers; output streams must be host-drained (no consuming machine in the group)")
}
