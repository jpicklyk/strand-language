package org.strand.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.DenialReport
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult

/**
 * A supervised multi-agent group, demonstrated.
 *
 * Concurrent agents run as a verified orchestration — a [MachineGroup] of
 * per-machine coroutine actors wired through content-addressed streams — each
 * with its own effect bound. The distinctive property is SUPERVISED ISOLATION:
 * when one worker reaches an effect beyond the group's grant, it is denied at
 * the actor boundary, that actor halts cleanly with a structured
 * [DenialReport], and the OTHER agents keep running and complete. A rogue or
 * compromised agent is contained without taking down the multi-agent system.
 *
 * The orchestration is the actor-model substrate (per-machine coroutines over
 * `Channel<Value>`, `select`-merged inputs, content-addressed wiring) with
 * per-agent harm bounds. This is not a state-graph simulation of multi-agent
 * coordination; it is the coordination running on a verified concurrent
 * runtime.
 *
 * The agents are state machines. Each worker is trivial by design — it folds
 * its `Int` events into a running counter and, on every transition, performs an
 * effectful write refined to its own per-worker path (the bound that makes the
 * isolation meaningful). The supervisor aggregates the workers' emitted
 * counters via internal streams into a running total it emits to the host. The
 * point on screen is the orchestration, the bounds, and the isolation — not
 * rich behavior — so the worker logic is deliberately a counter.
 *
 * Three scenarios:
 *
 *  - **MA1 Verified orchestration.** The group runs as a content-addressed
 *    verified graph: the orchestration verifies (each agent type-checks, the
 *    cross-machine topology validates), runs under a CapabilitySet covering
 *    every agent's effect, and produces the expected aggregate outputs. The
 *    orchestration is a verified graph, not trusted glue code.
 *  - **MA2 Supervised isolation (keystone).** The group is granted a
 *    CapabilitySet covering the supervisor and the OK workers' write paths but
 *    NOT one worker's. That worker's write is denied at its actor boundary — a
 *    real [DenialReport] is captured via [MachineInstanceHandle.denialReport] —
 *    the rogue actor halts, and the OTHER agents complete their work. The group
 *    survives.
 *  - **MA3 Per-instance replay determinism.** The events one worker actually
 *    consumed are fed back into the synchronous [StateMachineRuntime.runMachine]
 *    against the same StateMachine node, and the reproduced final state matches
 *    the live actor's.
 *
 * The three scenario methods return structured results that both [main] (the
 * transcript) and `MultiAgentSupervisorDemoTest` (the assertions) consume, so
 * the printed demonstration and the regression net share one body of code.
 * Nothing is staged: every output, denial, and survival is what the real
 * async runtime produced.
 */
object MultiAgentSupervisorDemo {

    // ------------------------------------------------------------------
    // Program loading — the host admits the canonical dag-json artifact.
    // ------------------------------------------------------------------

    /**
     * Load the committed program (canonical dag-json) from the classpath. The
     * source of truth is `demos/multi-agent-supervisor/programs/<name>.json`;
     * the `:runtime` build copies those onto the test classpath under
     * `/demo/programs/` (see `runtime/build.gradle.kts`).
     */
    fun loadProgramJson(name: String): String =
        MultiAgentSupervisorDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    /** Finalize a program's authored dag-json to the host's [ProgramImage]. */
    fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    /** The author-id → NodeId map for a program (used to build refined grants and route events). */
    fun nameMap(json: String): Map<String, NodeId> = JsonIngest.parse(json).nameMap

    /** The single committed program for this demonstration. */
    private const val PROGRAM = "supervisor-group"

    /** Per-worker write paths the EffectDecls refine `Filesystem.Write` with. */
    const val PATH_A = "/agent/worker-a.log"
    const val PATH_B = "/agent/worker-b.log"
    const val PATH_C = "/agent/worker-c.log"

    // ------------------------------------------------------------------
    // Grants — the per-agent harm bounds the host hands the group.
    // ------------------------------------------------------------------

    /**
     * A `Filesystem.Write` grant refined to exactly the supplied [paths]. A
     * worker whose write targets a path NOT in this list is denied at the
     * refinement check inside its transition. The category id is the program's
     * `writeFx` EffectCategory.
     */
    private fun writeGrant(names: Map<String, NodeId>, paths: List<String>): CapabilitySet =
        CapabilitySet(
            mapOf(
                // One single-slot pattern per granted path: `Filesystem.Write`
                // has one parameter (path), so each pattern is one concrete
                // path. A worker whose write path matches NO pattern is denied.
                names.getValue("writeFx") to paths.map { path ->
                    CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV(path))))
                },
            ),
        )

    // ------------------------------------------------------------------
    // Group construction + driving — reused by every scenario.
    // ------------------------------------------------------------------

    /**
     * The StateMachine NodeIds in the program's canonical store, keyed by the
     * author id. Mirrors the CLI's `group` subcommand: it walks the store for
     * every StateMachine NodeId rather than relying on the root, so the
     * Let-chain that keeps every machine reachable is enough.
     */
    private fun machineIdsByName(names: Map<String, NodeId>): Map<String, NodeId> {
        val machineNames = listOf("workerA", "workerB", "workerC", "supervisor")
        // names maps author id -> the pre-finalize NodeId; the finalized store
        // re-keys nodes, but StateMachine ids are stable in both because the
        // ingest NodeId IS the store key here (single-store, no federation).
        return machineNames.associateWith { names.getValue(it) }
    }

    /** What one worker emitted and where it ended up. */
    data class WorkerOutcome(
        val finalState: Value?,
        val halted: Boolean,
        val denialReport: DenialReport?,
    )

    /** The structured result of driving the group once. */
    data class GroupRun(
        /** Aggregate values the supervisor emitted to the external output, in arrival order. */
        val supervisorOutputs: List<Value>,
        /** Per-worker outcome, keyed by worker author id (workerA / workerB / workerC). */
        val workers: Map<String, WorkerOutcome>,
        /** The events each worker instance actually consumed, keyed by worker author id. */
        val recordedEvents: Map<String, List<Value>?>,
        /** The verifier-validated orchestration verdict. */
        val verified: Boolean,
        /** Whether the cross-machine topology validated (no producer/consumer gap). */
        val topologyValid: Boolean,
    )

    /**
     * Build the [MachineGroup] from the program and drive it once under [grant].
     * Sends [perWorkerEvents] to each worker's external input, drains the
     * supervisor's external output, and reads each worker's outcome (final
     * state, halt status, any denial report) off the handle.
     *
     * The group is driven on real coroutines (`Dispatchers.Default`) exactly as
     * the CLI drives it: send routed events, close the host-feedable inputs,
     * drain outputs concurrently, then `await()` natural quiescence.
     */
    fun driveGroup(
        grant: CapabilitySet,
        perWorkerEvents: Map<String, List<Int>>,
    ): GroupRun {
        val json = loadProgramJson(PROGRAM)
        val program = image(json)
        val names = nameMap(json)
        val machines = machineIdsByName(names)

        val host = StrandRuntime(HostPolicy.OPEN)
        val verify = host.verify(program)
        val verified = verify is VerifyResult.Ok

        val group = MachineGroup(
            store = program.store,
            hashToNodeId = program.hashToNodeId,
            machines = machines.values.toList(),
            capabilities = grant,
            recordInputs = true,
        )
        // Topology validation runs inside runGroup; surface it explicitly so
        // MA1 can assert the orchestration is a validated graph, not glue.
        val topologyValid = runCatching { group.validateTopology() }.isSuccess

        val inputStreamByWorker = mapOf(
            "workerA" to names.getValue("extInputA"),
            "workerB" to names.getValue("extInputB"),
            "workerC" to names.getValue("extInputC"),
        )
        val outputStreamId = names.getValue("extOutput")

        val supervisorOutputs = mutableListOf<Value>()

        val workerOutcomes: Map<String, WorkerOutcome>
        val recorded: Map<String, List<Value>?>

        val handleHolder = ArrayList<MachineGroupHandle>(1)
        runBlocking {
            withContext(Dispatchers.Default) {
                val handle = host.runGroup(program, group, this)
                handleHolder.add(handle)

                // Feed each worker its events on its own external input, then
                // close every host-feedable input so the actors halt naturally.
                for ((worker, streamId) in inputStreamByWorker) {
                    val channel = handle.externalInputs.getValue(streamId)
                    for (e in perWorkerEvents[worker].orEmpty()) channel.send(Value.IntV(e.toLong()))
                }
                for (channel in handle.externalInputs.values) channel.close()

                // Drain the supervisor's external output concurrently.
                coroutineScope {
                    val out = handle.externalOutputs.getValue(outputStreamId)
                    launch {
                        for (v in out) supervisorOutputs.add(v)
                    }
                }
                handle.await()
            }
        }

        val handle = handleHolder.single()
        // Map each StateMachine NodeId back to its worker author id so we can
        // read the per-worker outcome off the (NodeId-agnostic) instance set.
        // Each worker is a distinct machine, so the instances are 1:1 with the
        // machine NodeIds; we match by the cached transition-fn node, which the
        // handle exposes as MachineInstanceHandle.machineId.
        val transitionFnByWorker: Map<NodeId, String> = mapOf(
            names.getValue("wATransition") to "workerA",
            names.getValue("wBTransition") to "workerB",
            names.getValue("wCTransition") to "workerC",
        )
        val outcomes = HashMap<String, WorkerOutcome>()
        val recordedByWorker = HashMap<String, List<Value>?>()
        for ((instanceId, ih) in handle.instances) {
            val worker = transitionFnByWorker[ih.machineId] ?: continue
            outcomes[worker] = WorkerOutcome(
                finalState = ih.currentState,
                halted = ih.halted,
                denialReport = ih.denialReport,
            )
            recordedByWorker[worker] = handle.recordedEvents(instanceId)
        }
        workerOutcomes = outcomes
        recorded = recordedByWorker

        return GroupRun(
            supervisorOutputs = supervisorOutputs,
            workers = workerOutcomes,
            recordedEvents = recorded,
            verified = verified,
            topologyValid = topologyValid,
        )
    }

    // ==================================================================
    // MA1 — Verified orchestration
    // ==================================================================

    /**
     * Result of MA1. The orchestration verified, the topology validated, every
     * worker completed normally, and the supervisor emitted the expected
     * aggregate(s).
     */
    data class MA1Result(
        val verified: Boolean,
        val topologyValid: Boolean,
        val workerFinalStates: Map<String, Value?>,
        val anyDenial: Boolean,
        val supervisorOutputs: List<Value>,
        val supervisorTotal: Long?,
    ) {
        val allWorkersCompleted: Boolean get() = !anyDenial && workerFinalStates.values.all { it != null }
    }

    /**
     * Run the whole group under a grant covering all three workers' write
     * paths. Every worker folds its events and writes (its bound is satisfied);
     * the supervisor aggregates their emissions into a running total.
     *
     * The events: A gets {5, 7}, B gets {10}, C gets {3}. Each worker's write
     * (a Test.EffectfulNoOp returning 0) is summed into its accumulator, so the
     * final states are A=12, B=10, C=3. Each worker emits its running counter
     * on EVERY transition: A emits {5, 12}, B emits {10}, C emits {3}. The
     * supervisor folds every emission it sees into a running total, so its
     * final aggregate is 5 + 12 + 10 + 3 = 30. Addition commutes, so the final
     * total is order-independent even though the actor interleaving is not.
     */
    fun scenarioMA1(): MA1Result {
        val grant = writeGrant(
            nameMap(loadProgramJson(PROGRAM)),
            listOf(PATH_A, PATH_B, PATH_C),
        )
        val run = driveGroup(
            grant = grant,
            perWorkerEvents = mapOf(
                "workerA" to listOf(5, 7),
                "workerB" to listOf(10),
                "workerC" to listOf(3),
            ),
        )
        val anyDenial = run.workers.values.any { it.denialReport != null }
        val total = (run.supervisorOutputs.lastOrNull() as? Value.IntV)?.v
        return MA1Result(
            verified = run.verified,
            topologyValid = run.topologyValid,
            workerFinalStates = run.workers.mapValues { it.value.finalState },
            anyDenial = anyDenial,
            supervisorOutputs = run.supervisorOutputs,
            supervisorTotal = total,
        )
    }

    // ==================================================================
    // MA2 — Supervised isolation (keystone)
    // ==================================================================

    /**
     * Result of MA2. The rogue worker (C) was denied with a structured report;
     * the OK workers (A, B) completed; the supervisor stayed up and aggregated
     * the surviving workers' emissions.
     */
    data class MA2Result(
        val rogueWorker: String,
        val rogueDenial: DenialReport?,
        val rogueHalted: Boolean,
        val okWorkerFinalStates: Map<String, Value?>,
        val okWorkersHalted: Map<String, Boolean>,
        val okWorkerDenials: Map<String, DenialReport?>,
        val supervisorOutputs: List<Value>,
    ) {
        /** The denial was captured AND the other agents completed: the group survived. */
        val contained: Boolean
            get() = rogueDenial != null && rogueHalted &&
                okWorkerFinalStates.values.all { it != null } &&
                supervisorOutputs.isNotEmpty()
    }

    /**
     * Grant `Filesystem.Write` refined to A's and B's paths but NOT C's. Worker
     * C's transition reaches `Filesystem.Write{/agent/worker-c.log}`, which no
     * granted pattern covers, so the refinement check denies it at C's actor
     * boundary — C halts cleanly with a [DenialReport]. Workers A and B fold
     * their events and complete; the supervisor aggregates the survivors'
     * emissions and stays up.
     *
     * This is the contained-rogue story: a single agent's over-reach is denied
     * and isolated; the multi-agent system survives.
     */
    fun scenarioMA2(): MA2Result {
        val grant = writeGrant(
            nameMap(loadProgramJson(PROGRAM)),
            listOf(PATH_A, PATH_B),  // C's path is deliberately absent.
        )
        val run = driveGroup(
            grant = grant,
            perWorkerEvents = mapOf(
                "workerA" to listOf(5, 7),
                "workerB" to listOf(10),
                "workerC" to listOf(3),  // C tries to write; its first event denies.
            ),
        )
        val c = run.workers.getValue("workerC")
        return MA2Result(
            rogueWorker = "workerC",
            rogueDenial = c.denialReport,
            rogueHalted = c.halted,
            okWorkerFinalStates = mapOf(
                "workerA" to run.workers["workerA"]?.finalState,
                "workerB" to run.workers["workerB"]?.finalState,
            ),
            okWorkersHalted = mapOf(
                "workerA" to (run.workers["workerA"]?.halted ?: false),
                "workerB" to (run.workers["workerB"]?.halted ?: false),
            ),
            okWorkerDenials = mapOf(
                "workerA" to run.workers["workerA"]?.denialReport,
                "workerB" to run.workers["workerB"]?.denialReport,
            ),
            supervisorOutputs = run.supervisorOutputs,
        )
    }

    // ==================================================================
    // MA3 — Per-instance replay determinism
    // ==================================================================

    /**
     * Result of MA3. The events worker A actually consumed in the live group,
     * fed back into the synchronous [StateMachineRuntime.runMachine] against
     * the same StateMachine node, reproduce A's final state.
     */
    data class MA3Result(
        val recordedEvents: List<Value>?,
        val liveFinalState: Value?,
        val replayedFinalState: Value?,
    ) {
        val matches: Boolean get() = liveFinalState != null && liveFinalState == replayedFinalState
    }

    /**
     * Re-run the group (all-granted, like MA1), capture the events worker A
     * consumed, and feed them back into `runMachine` against worker A's
     * StateMachine node. The pure transition function guarantees the same
     * trajectory, so the replayed final state equals the live one.
     */
    fun scenarioMA3(): MA3Result {
        val json = loadProgramJson(PROGRAM)
        val names = nameMap(json)
        val grant = writeGrant(names, listOf(PATH_A, PATH_B, PATH_C))
        val run = driveGroup(
            grant = grant,
            perWorkerEvents = mapOf(
                "workerA" to listOf(5, 7),
                "workerB" to listOf(10),
                "workerC" to listOf(3),
            ),
        )
        val recorded = run.recordedEvents["workerA"]
        val liveState = run.workers["workerA"]?.finalState

        val program = image(json)
        val host = StrandRuntime(HostPolicy.OPEN)
        val trace = host.runMachine(
            program = program,
            machine = names.getValue("workerA"),
            events = recorded.orEmpty(),
            capabilities = grant,
        )
        return MA3Result(
            recordedEvents = recorded,
            liveFinalState = liveState,
            replayedFinalState = trace.final.finalState,
        )
    }

    // ------------------------------------------------------------------
    // Rendering — the DenialReport as the host surfaces it.
    // ------------------------------------------------------------------

    /**
     * Render a [DenialReport] the way an orchestrating principal sees it.
     * Mirrors the CLI's `strand:denial` line field-for-field (the `:cli`
     * helper is not on `:runtime`'s classpath, so the rendering is reproduced
     * here over the same report fields).
     */
    fun renderDenial(report: DenialReport): String = buildString {
        append("category=").append(report.category)
        append(" requested=").append(report.requested ?: "(withheld)")
        append(" held=").append(report.held ?: "(withheld)")
        append(" instance=").append(report.instanceId?.take(8) ?: "null")
        append(" eventIndex=").append(report.eventIndex?.toString() ?: "null")
        append(" phase=").append(report.phase.wire)
    }

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)

        line("=".repeat(72))
        line("Strand -- supervised multi-agent group")
        line("Concurrent agents as a verified orchestration of coroutine actors,")
        line("each with its own effect bound. A rogue agent is contained at the")
        line("actor boundary; the multi-agent system survives.")
        line("=".repeat(72))
        line()

        // --- MA1 ---
        line("MA1  Verified orchestration -- concurrent agents, expected outputs")
        line("-".repeat(72))
        val ma1 = scenarioMA1()
        line("  Group: supervisor + 3 worker agents as a MachineGroup of")
        line("         coroutine actors wired through content-addressed streams.")
        line("         Each worker folds its Int events into a counter and writes")
        line("         to its own per-worker path (its effect bound). Workers emit")
        line("         their counter to the supervisor via internal streams; the")
        line("         supervisor aggregates into a running total.")
        line("  The orchestration is a content-addressed verified graph, not glue:")
        line("    orchestration verified    = ${ma1.verified}")
        line("    topology validated        = ${ma1.topologyValid}")
        line("  Run under a grant covering every worker's write path:")
        line("    worker A final state      = ${ma1.workerFinalStates["workerA"]}  (5 + 7 = 12)")
        line("    worker B final state      = ${ma1.workerFinalStates["workerB"]}  (10)")
        line("    worker C final state      = ${ma1.workerFinalStates["workerC"]}  (3)")
        line("    any agent denied          = ${ma1.anyDenial}")
        line("    all workers completed     = ${ma1.allWorkersCompleted}")
        line("    supervisor emissions      = ${ma1.supervisorOutputs}")
        line("    supervisor aggregate total= ${ma1.supervisorTotal}  (each worker emits its")
        line("                                running counter every step: 5+12+10+3 = 30)")
        line("  The agents ran concurrently and the supervisor produced the")
        line("  expected aggregate -- the orchestration is verified, not trusted.")
        line()

        // --- MA2 ---
        line("MA2  Supervised isolation -- a rogue agent contained, the group survives")
        line("-".repeat(72))
        val ma2 = scenarioMA2()
        line("  Grant: Filesystem.Write refined to A's and B's paths but NOT C's.")
        line("         Worker C reaches Filesystem.Write{/agent/worker-c.log},")
        line("         which no granted pattern covers.")
        line("  The rogue worker (C) is denied at its actor boundary:")
        if (ma2.rogueDenial != null) {
            line("    worker C halted           = ${ma2.rogueHalted}")
            line("    DenialReport: ${renderDenial(ma2.rogueDenial)}")
        } else {
            line("    (no denial captured -- unexpected)")
        }
        line("  The OTHER agents keep running and complete -- the group survives:")
        line("    worker A final state      = ${ma2.okWorkerFinalStates["workerA"]}  (completed)")
        line("    worker B final state      = ${ma2.okWorkerFinalStates["workerB"]}  (completed)")
        line("    supervisor stayed up      = ${ma2.supervisorOutputs.isNotEmpty()}")
        line("    supervisor emissions      = ${ma2.supervisorOutputs}")
        line("    contained (denied + survived) = ${ma2.contained}")
        line("  A rogue or compromised agent is contained without taking down the")
        line("  multi-agent system. Per-agent harm bounds, enforced at the actor")
        line("  boundary -- not whole-system failure.")
        line()

        // --- MA3 ---
        line("MA3  Per-instance replay determinism")
        line("-".repeat(72))
        val ma3 = scenarioMA3()
        line("  Feed the events worker A actually consumed back into the")
        line("  synchronous runMachine against the same StateMachine node:")
        line("    recorded events (A)       = ${ma3.recordedEvents}")
        line("    live final state          = ${ma3.liveFinalState}")
        line("    replayed final state      = ${ma3.replayedFinalState}")
        line("    trajectories match        = ${ma3.matches}")
        line("  The pure transition function reproduces the trajectory exactly --")
        line("  a worker's run is replayable from its recorded events.")
        line()

        line("=".repeat(72))
        line("What this demonstrates: supervised isolation on the actor-model")
        line("substrate -- concurrent agents as a verified orchestration with")
        line("per-agent harm bounds; a rogue agent denied at the actor boundary")
        line("while the others complete. The agents are state machines (a worker")
        line("could call an LLM); the orchestration / bounds / isolation are the")
        line("subject, NOT literal LLM agents. Programs are hand-authored, NOT")
        line("measured for first-pass correctness or cost.")
        line("=".repeat(72))

        print(out)
    }
}
