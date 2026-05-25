package org.strand.runtime

import kotlinx.coroutines.channels.Channel
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Value
import java.util.UUID

/**
 * Runtime identity for a single state machine instance. Distinct from the
 * machine's *node identity* (the StateMachine NodeId / hash): the node is
 * the static, content-addressed declaration; the instance is the dynamic
 * runtime occurrence. Two `runMachine` / `runGroup` invocations on the same
 * StateMachine node produce two MachineInstances with distinct InstanceIds.
 *
 * Layer 6 step 1 does not expose InstanceIds in the [Trace] API (one
 * instance per `runMachine` call, so the id is implicit). Step 2's
 * [MachineGroupHandle] exposes the InstanceId → recording mapping so
 * callers can drive replay-determinism assertions against [Trace].
 *
 * Backed by [java.util.UUID] for global uniqueness; the string form is
 * stable across runs only when the host supplies one (test code does this
 * via an InstanceId constructor; the default factory generates a fresh
 * random UUID each time).
 */
@JvmInline
value class InstanceId(val value: String) {
    companion object {
        /** Fresh random instance id. */
        fun generate(): InstanceId = InstanceId(UUID.randomUUID().toString())
    }
}

/**
 * One running state machine instance.
 *
 * Two execution shapes share this type:
 *
 *  * **Layer 6 step 1 (synchronous fold).** Populated via
 *    [StateMachineRuntime.runMachine]. [inputQueues] is the pre-loaded
 *    `ArrayDeque<Value>` per input stream; [outputSinks] is a `MutableList`
 *    per output stream. [inputChannels], [outputChannels], and [recorder]
 *    are null. Drives synchronously over a fixed event list.
 *
 *  * **Layer 6 step 2 (async multi-machine).** Populated via
 *    [StateMachineRuntime.runGroup]. [inputChannels] is `Channel<Value>` per
 *    input stream; [outputChannels] is `SendChannel<Value>` per output
 *    stream (shared with downstream consumers when an internal stream wires
 *    machines together). [recorder] is non-null when the group enabled
 *    `recordInputs`. [inputQueues] / [outputSinks] are empty in the async
 *    shape — channels replace them.
 *
 * The cached [transitionFnValue], [currentState], [capabilities], and
 * [halted] fields carry through both shapes. The split fields are gated on
 * nullability so the runtime can dispatch by which entry point was used.
 *
 * Mutable fields are marked `var` because the actor abstraction in step 2
 * mutates [currentState] and [halted]; in step 1 the only mutator is
 * [StateMachineRuntime].
 */
internal class MachineInstance(
    val instanceId: InstanceId,
    val node: Node.StateMachine,
    /**
     * The source [Node.StateMachine] NodeId this instance was built from.
     * Used by [MachineGroupHandle.snapshot] (Layer 6 step 3 slice 3.3) to
     * look up the machine's content hash in [MachineGroup.nodeIdToHash].
     * Null for fixtures that build a MachineInstance outside the runtime's
     * `runMachine` / `runGroup` entry points (they get no snapshot support).
     */
    val machineNodeId: NodeId? = null,
    val transitionFnValue: Value,            // pre-evaluated: Closure | ForeignFn | FixpointFn
    /**
     * Per-event apply hook. When non-null, [MachineActor.stepOnce] and
     * [StateMachineRuntime.stepOnce] dispatch through it instead of
     * calling [interpreter.applyCallable] directly. Default null keeps
     * the pre-Track-A.4 behavior (interpreter dispatch on
     * [transitionFnValue]) so test fixtures that build a MachineInstance
     * directly continue to work unchanged. The runtime entry points
     * populate this via the [MachineGroup.dispatcherFactory] when present.
     */
    val dispatcher: TransitionDispatcher? = null,
    var currentState: Value,
    val capabilities: CapabilitySet,
    val inputQueues: Map<NodeId, ArrayDeque<Value>> = emptyMap(),
    val outputSinks: Map<NodeId, MutableList<Value>> = emptyMap(),
    val inputChannels: Map<NodeId, Channel<Value>> = emptyMap(),
    val outputChannels: Map<NodeId, Channel<Value>> = emptyMap(),
    /**
     * Per-output-stream send adapter wrapping each channel in
     * [outputChannels] with the stream's declared
     * [org.strand.core.OverflowPolicy] (Layer 6 step 3 slice 3.1). The actor
     * sends through these instead of `outputChannels[id].send(...)` directly,
     * which lets DropNewest / DropOldest / Sample non-block-producer
     * semantics apply per the EventStream's declared policy. Empty in the
     * step 1 sync `runMachine` path (which never sends through channels).
     */
    val outputDispatchers: Map<NodeId, OverflowDispatcher> = emptyMap(),
    /**
     * Per-output-stream [StreamBus] handle (Layer 6 step 3 slice 3.6). The
     * actor calls `bus.producerHalted()` on each output bus when it exits
     * so multi-producer fan-in streams stay open until every producer has
     * halted (vs. the step-2 behavior of closing the channel directly).
     * Empty in the step 1 sync `runMachine` path.
     */
    val outputBuses: Map<NodeId, StreamBus> = emptyMap(),
    val recorder: EventRecorder? = null,
    var halted: Boolean = false,
    /**
     * Per-instance counter cells for Layer 6 step 3 slice 3.4 metrics. Updated
     * by [MachineActor] on every event dequeued and on every completed
     * transition; snapshot read by [MachineGroupHandle.metrics]. Always non-null
     * — the cells are cheap and tracking is always-on, so a host that doesn't
     * care about metrics simply doesn't call `metrics()`.
     */
    val counters: InstanceCounters = InstanceCounters(),
)
