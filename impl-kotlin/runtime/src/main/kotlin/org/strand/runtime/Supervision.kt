package org.strand.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.strand.core.EvaluationLimits
import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.ForeignDispatcher
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import java.util.concurrent.ConcurrentHashMap

/**
 * Mutable per-group runtime state (Layer 6 step 3 slice 3.2 — supervision).
 *
 * Holds the dynamic actor set and the immutable channel/dispatcher allocations
 * shared by every actor in the group. Owned by [StateMachineRuntime.runGroup];
 * surfaced through [MachineGroupHandle] only via its [spawn] / [terminate]
 * methods.
 *
 * **Spawn protocol.** [spawn] allocates a fresh [MachineInstance] for the
 * given StateMachine NodeId, wires its input and output channels through the
 * existing [streamBuses] (sharing channels with any existing instances whose
 * topology includes the same streams), launches a coroutine actor, and
 * returns the new [InstanceId]. Spawned instances are full participants in
 * the group from then on — their counters appear in [MachineGroupHandle.metrics],
 * their snapshots can be taken via [MachineGroupHandle.snapshot], they can
 * themselves Spawn further instances.
 *
 * **Terminate protocol.** [terminate] cancels the coroutine of the named
 * instance; the actor's `finally` block runs (closing its outputs through
 * the bus producer-halt counter) before the coroutine exits. Returns true
 * if an instance with that id existed and was cancelled, false otherwise.
 *
 * **In-band dispatch.** [foreignDispatcher] is a [ForeignDispatcher] that
 * recognizes `strand-runtime:StateMachine.Spawn` and `.Terminate` ForeignNode
 * targets and applies the corresponding lifecycle operation. Each actor's
 * per-actor [Interpreter] is constructed with this dispatcher, so a Spawn
 * call inside a transition function becomes a real spawn at runtime; the
 * Interpreter's normal Builtins-lookup falls through to the dispatcher and
 * the dispatcher applies the side effect.
 *
 * **Concurrency.** Spawn/terminate may be called concurrently from any
 * actor or from the host. The instance and job maps use [ConcurrentHashMap]
 * so additions and removals are safe under contention; reads are best-effort
 * coherent (matching the [RuntimeMetrics] consistency model).
 *
 * **Deferred from this slice:**
 *  * Restart policies (OneForOne, OneForAll, RestForOne) — these are
 *    supervisor-pattern logic that lives in transition functions; the
 *    runtime here ships the lifecycle primitives that supervisors call.
 *    A corpus capstone demonstrating each policy is a follow-up.
 *  * Stream-topology adjustment at spawn time — spawned instances share
 *    the existing buses, so they must consume / produce streams that were
 *    in the group's original topology. Dynamic stream creation at spawn
 *    time is out of scope.
 *  * Spawn-time capability narrowing — spawned instances inherit the
 *    group's [capabilities] context unchanged. Per-instance capability
 *    overrides at spawn time would let supervisors restrict workers; that's
 *    a Q-031 follow-up.
 */
internal class RuntimeContext(
    val store: NodeStore,
    val hashToNodeId: Map<Hash, NodeId>,
    val nodeIdToHash: Map<NodeId, Hash>,
    val scope: CoroutineScope,
    val streamBuses: Map<NodeId, StreamBus>,
    val streamDispatchers: Map<NodeId, OverflowDispatcher>,
    val capabilities: CapabilitySet,
    val recordInputs: Boolean,
    /**
     * Track A.4 VM-dispatch hook. When non-null, every spawned
     * [MachineInstance] gets a dispatcher built by this factory and the
     * actor uses it instead of the default `interpreter.applyCallable`
     * path. Null preserves the original interpreter behavior.
     */
    val dispatcherFactory: TransitionDispatcherFactory? = null,
    /**
     * Q-040: shared evaluation limits for every spawned instance in this
     * group. Each actor gets its own [Interpreter.EvalCounters] (the proposal's
     * "independent per-instance budgets" contract); this carrier propagates
     * the limit policy uniformly across the group.
     */
    val limits: EvaluationLimits = EvaluationLimits.DEFAULTS,
    /**
     * Q-043 step 3a cross-store resolution callback, threaded into each
     * per-actor [Interpreter] so a transition function that references a
     * cross-store node (via a `targetHash` NodeRef) fetches and re-bases it
     * into the shared [store] on first use. Default null preserves the
     * single-store actor behaviour.
     */
    val resolveTarget: ((Hash) -> NodeId?)? = null,
    /**
     * Q-054 follow-up: the host context every per-actor [Interpreter] this
     * context spawns is bound to, so an actor's effectful builtins read the
     * group's tenant policy rather than the [org.strand.interpreter.Builtins]
     * singletons. Default [org.strand.interpreter.HostContext.processDefault].
     */
    val hostContext: org.strand.interpreter.HostContext =
        org.strand.interpreter.HostContext.processDefault(),
) {
    private val instancesMap = ConcurrentHashMap<InstanceId, MachineInstance>()
    private val actorJobsMap = ConcurrentHashMap<InstanceId, Job>()

    val instances: Map<InstanceId, MachineInstance> get() = instancesMap
    val actorJobs: Collection<Job> get() = actorJobsMap.values

    /**
     * Allocate, register, and launch a [MachineInstance] for the supplied
     * StateMachine [machineId]. Returns the new InstanceId.
     */
    fun spawn(machineId: NodeId): InstanceId {
        val node = store.get(machineId) as? Node.StateMachine
            ?: error(
                "spawn: expected StateMachine at $machineId, got " +
                    "${store.getOrNull(machineId)?.javaClass?.simpleName}"
            )
        // Each actor gets its own Interpreter so the foreign dispatcher is
        // bound to THIS group's runtime context (Spawn from inside the
        // transition spawns into this group, not some other one).
        val perActorInterpreter = Interpreter(store, hashToNodeId, foreignDispatcher, resolveTarget, hostContext = hostContext)
        // Q-040: build under the group's limits so eval-time caps apply
        // from instance construction through every per-event call.
        val transitionFnValue = perActorInterpreter.eval(node.transitionFn, capabilities, limits)
        val initialStateValue = perActorInterpreter.eval(node.initialState, capabilities, limits)

        // Wire the new instance's I/O through the existing buses. The
        // streams declared by the spawned machine must already exist in
        // [streamBuses] — dynamic stream creation at spawn time is out of
        // scope for slice 3.2 MVP.
        val inputChannels = node.inputStreams.associateWith { streamId ->
            (streamBuses[streamId] ?: error(
                "spawn: machine $machineId references input stream $streamId not in group topology"
            )).consumerChannel(machineId, scope)
        }
        val outputChannels = node.outputStreams.associateWith { streamId ->
            (streamBuses[streamId] ?: error(
                "spawn: machine $machineId references output stream $streamId not in group topology"
            )).producerChannel
        }
        val outputDispatchers = node.outputStreams.associateWith {
            streamDispatchers.getValue(it)
        }
        val outputBuses = node.outputStreams.associateWith { streamId ->
            streamBuses.getValue(streamId)
        }
        val recorder = if (recordInputs) EventRecorder() else null

        val dispatcher = dispatcherFactory?.build(node, machineId, capabilities)
        val instance = MachineInstance(
            instanceId = InstanceId.generate(),
            node = node,
            machineNodeId = machineId,
            transitionFnValue = transitionFnValue,
            dispatcher = dispatcher,
            currentState = initialStateValue,
            capabilities = capabilities,
            inputChannels = inputChannels,
            outputChannels = outputChannels,
            outputDispatchers = outputDispatchers,
            outputBuses = outputBuses,
            recorder = recorder,
            halted = false,
            limits = limits,
        )
        // Increment producer count on each output bus BEFORE launching the
        // actor — slice 3.6's bus.producerHalted() decrements; we must
        // ensure the bus doesn't see a 0-1-0 transition that closes early.
        for (bus in outputBuses.values) {
            bus.producerSpawned()
        }
        instancesMap[instance.instanceId] = instance
        val actor = MachineActor(instance, perActorInterpreter)
        actorJobsMap[instance.instanceId] = scope.launch { actor.run() }
        return instance.instanceId
    }

    /**
     * Cancel the actor coroutine for [instanceId]. Returns true if such an
     * instance existed; false if the id is unknown. Cancellation is
     * cooperative — a transition currently in progress runs to completion
     * before the actor sees cancellation.
     */
    fun terminate(instanceId: InstanceId): Boolean {
        val job = actorJobsMap[instanceId] ?: return false
        job.cancel()
        return true
    }

    /**
     * Foreign dispatcher recognizing `strand-runtime:StateMachine.Spawn` and
     * `.Terminate`. Each actor's per-actor Interpreter is constructed with
     * this dispatcher, so in-band calls from inside a transition function
     * reach the runtime here.
     *
     * Spawn: arg `Bytes` (the machine's content hash); resolved to a
     * StateMachine NodeId via [hashToNodeId]; allocates a new instance;
     * returns the new [InstanceId] as a [Value.StringV].
     *
     * Terminate: arg `String` (the InstanceId); cancels the named actor;
     * returns [Value.UnitV].
     */
    val foreignDispatcher: ForeignDispatcher = ForeignDispatcher { target, args ->
        when (target) {
            "strand-runtime:StateMachine.Spawn" -> {
                require(args.size == 1) {
                    "strand-runtime:StateMachine.Spawn expects 1 arg (machine hash: Bytes), got ${args.size}"
                }
                val hashBytes = (args[0] as? Value.BytesV)?.v
                    ?: error("Spawn arg must be BytesV (machine hash), got ${args[0]::class.simpleName}")
                val hash = Hash(hashBytes)
                val machineId = hashToNodeId[hash]
                    ?: error("Spawn: no machine in store matches hash $hash")
                val newInstanceId = spawn(machineId)
                Value.StringV(newInstanceId.value)
            }
            "strand-runtime:StateMachine.Terminate" -> {
                require(args.size == 1) {
                    "strand-runtime:StateMachine.Terminate expects 1 arg (instance id: String), got ${args.size}"
                }
                val idStr = (args[0] as? Value.StringV)?.v
                    ?: error("Terminate arg must be StringV (instance id), got ${args[0]::class.simpleName}")
                terminate(InstanceId(idStr))
                Value.UnitV
            }
            else -> null  // fall through to Builtins
        }
    }
}
