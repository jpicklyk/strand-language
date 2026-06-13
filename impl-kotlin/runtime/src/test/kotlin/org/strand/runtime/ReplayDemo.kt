package org.strand.runtime

import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.VerifyWarning
import java.nio.file.Files
import java.nio.file.Path

/**
 * Sound deterministic replay, time-travel state inspection, and
 * snapshot/restart-resume, demonstrated.
 *
 * The subject is an **event-sourced ledger** (`demos/replay-timetravel/programs/ledger.json`):
 * a state machine whose state is a running balance over a sequence of
 * deposit/withdraw events. State is `{balance, lastStamp, count}`; each input
 * event is `{amount, stamp}` where `amount` is the signed delta and `stamp` is
 * a recorded IO value — a timestamp the *driver* reads from the host clock at
 * the moment the event is admitted and stamps into the event payload before
 * feeding it to the machine. The transition is pure: it calls only `Int.Add`
 * (a Q-065 `Deterministic` builtin), reads no clock of its own, and threads the
 * recorded timestamp through state as ordinary data.
 *
 * That purity is the soundness basis (Q-065). Because the transition references
 * only deterministic builtins and the only "world" it sees arrives *as data on
 * an effect edge* (the recorded event), re-folding the recorded event sequence
 * reproduces the trajectory bit-identically with zero live IO. The demonstration
 * makes the distinction concrete: the live run's stamps come from the clock, a
 * *second* live run under a *different* clock produces a *different* trajectory,
 * yet replaying the first run's record reproduces the first run's trajectory
 * exactly — no clock read, no filesystem, no network on the replay path.
 *
 * Three scenarios, each exercising a real shipped property:
 *
 *  - **R1 Deterministic replay.** Run the machine over a recorded event sequence
 *    (the live run, whose stamps the driver read from the clock), then replay
 *    from the recorded log and assert the full state trajectory is bit-identical
 *    — and that replay performs zero live IO: the replay re-feeds the recorded
 *    events through the synchronous `runMachine` under a clock that *throws if
 *    read*, so any live clock/IO touch would fail the run. A contrast clock shows
 *    a fresh live run would differ; replay does not.
 *  - **R2 Time-travel inspection.** From the recorded run, inspect the machine
 *    state AT each event index via the per-step state-after the `Trace` exposes
 *    (`Trace.steps[i].after`). Land on any point in history and read its exact
 *    state — "step back to event N".
 *  - **R3 Snapshot -> restart -> resume.** Run partway, snapshot through the
 *    facade's `ValueCodec`-backed `writeSnapshot`, drop the in-memory runtime,
 *    restore in a FRESH `StrandRuntime` under a new `HostPolicy` (the "new
 *    process"), resume over the remaining events, and assert the post-snapshot
 *    trajectory equals an uninterrupted single run. This is the
 *    `SnapshotPersistenceTest` technique packaged as a watchable artifact.
 *
 * The scenario methods return structured results that both [main] (the
 * transcript) and `ReplayDemoTest` (the assertions) consume, so the printed
 * demonstration and the regression net share one body of code — the same
 * discipline `ContainmentDemo` follows.
 */
object ReplayDemo {

    // ------------------------------------------------------------------
    // Program loading — the canonical dag-json ledger artifact.
    // ------------------------------------------------------------------

    /**
     * Load the committed ledger program (canonical dag-json) from the classpath.
     * The source of truth is `demos/replay-timetravel/programs/<name>.json`; the
     * `:runtime` build copies it onto the test classpath under `/demo/programs/`
     * (see `runtime/build.gradle.kts`).
     */
    fun loadProgramJson(name: String): String =
        ReplayDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    /** The finalized ledger: store + maps the runtime/facade consume. */
    data class Ledger(
        val image: ProgramImage,
        val machineId: NodeId,
        val nodeIdToHash: Map<NodeId, org.strand.core.Hash>,
        val verify: VerifyResult,
    )

    fun loadLedger(): Ledger {
        val ingest = JsonIngest.parse(loadProgramJson("ledger"))
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val image = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        val verify = StrandRuntime(HostPolicy.OPEN).verify(image)
        return Ledger(image, finalized.root, finalized.nodeIdToHash, verify)
    }

    // ------------------------------------------------------------------
    // Event construction — the ledger's input shape.
    // ------------------------------------------------------------------

    /** One admitted ledger event: a signed `amount` plus a recorded `stamp`. */
    fun event(amount: Long, stamp: Long): Value = Value.ProductV(
        linkedMapOf("amount" to Value.IntV(amount), "stamp" to Value.IntV(stamp)),
    )

    /** The signed deltas the demonstration's six events carry, in order. */
    val AMOUNTS: List<Long> = listOf(100L, -30L, 50L, 200L, -120L, 25L)

    /**
     * The recorded run's events: the *driver* reads `clock.nowMillis()` once per
     * event and stamps it into the payload — modeling an admission timestamp the
     * host attaches as the event enters. The clock is a host-policy input, so the
     * stamps are genuinely live-IO-sourced on this run.
     */
    fun recordEventsFromClock(clock: Builtins.Clock): List<Value> =
        AMOUNTS.map { amount -> event(amount, clock.nowMillis()) }

    /** The running balance trajectory the AMOUNTS produce (for the transcript). */
    fun expectedBalances(): List<Long> =
        AMOUNTS.runningFold(0L) { acc, a -> acc + a }.drop(1)

    // ==================================================================
    // R1 — Deterministic replay (and zero live IO).
    // ==================================================================

    data class R1Result(
        /** The recorded events from the live run (stamps read from the live clock). */
        val recordedEvents: List<Value>,
        /** The live run's per-step state-after trajectory. */
        val liveTrajectory: List<Value>,
        /** The replayed run's per-step state-after trajectory. */
        val replayTrajectory: List<Value>,
        /** Per-step emitted outputs on the live run. */
        val liveOutputs: List<List<Value>>,
        /** Per-step emitted outputs on the replayed run. */
        val replayOutputs: List<List<Value>>,
        /** A FRESH live run under a DIFFERENT clock — the "live IO would differ" contrast. */
        val contrastTrajectory: List<Value>,
        /** True if the replay touched no live clock (the throwing clock was never read). */
        val replayTouchedNoLiveIo: Boolean,
    ) {
        val trajectoriesIdentical: Boolean get() = liveTrajectory == replayTrajectory
        val outputsIdentical: Boolean get() = liveOutputs == replayOutputs
        /** Replay reproduces the live stamps; the contrast (fresh clock) differs. */
        val replayMatchesLiveButContrastDiffers: Boolean
            get() = liveTrajectory == replayTrajectory && liveTrajectory != contrastTrajectory
    }

    /**
     * A clock that fails if read — installed for the replay run to prove replay
     * performs zero live IO. If the pure transition (or the replay driver) ever
     * touched the clock, the run would throw; it does not, so replay is clean.
     */
    private class ForbiddenClock : Builtins.Clock {
        override fun nowMillis(): Long =
            error("replay touched the live clock — replay must perform zero live IO")
        override fun sleep(millis: Long): Unit =
            error("replay touched the live clock — replay must perform zero live IO")
    }

    /**
     * R1: record a live run (stamps from a live clock), replay from the record
     * under a clock that throws if read, and contrast against a fresh live run
     * under a different clock.
     *
     *  - The live run's stamps come from `liveClock` (1_000 ms apart).
     *  - Replay re-feeds the *recorded* events through `runMachine`; the recorded
     *    stamps travel as data, so the replay reproduces the live trajectory
     *    bit-identically — and because the synchronous fold reads no clock and
     *    the transition is pure, the `ForbiddenClock` is never touched.
     *  - The contrast run reads a *different* clock (starting 9_000_000 ms later),
     *    so its `lastStamp` field differs from the live run's — proving a fresh
     *    live run genuinely would differ, while replay does not.
     */
    fun scenarioR1(): R1Result {
        val ledger = loadLedger()

        // --- Live run: the driver reads the live clock to stamp each event. ---
        val liveClock = SteppingClock(start = 1_717_200_000_000L, stepMillis = 1_000L)
        val recorded = recordEventsFromClock(liveClock)
        val liveRuntime = StrandRuntime(HostPolicy.OPEN.copy(clock = liveClock))
        val liveTrace = liveRuntime.runMachine(ledger.image, ledger.machineId, recorded)

        // --- Replay: re-feed the recorded events under a clock that THROWS if
        //     read. A clean run proves replay performed zero live IO. ---
        val replayRuntime = StrandRuntime(HostPolicy.OPEN.copy(clock = ForbiddenClock()))
        var touchedNoLiveIo = true
        val replayTrace = try {
            replayRuntime.runMachine(ledger.image, ledger.machineId, recorded)
        } catch (e: IllegalStateException) {
            touchedNoLiveIo = false
            throw e
        }

        // --- Contrast: a FRESH live run under a DIFFERENT clock genuinely differs. ---
        val contrastClock = SteppingClock(start = 1_717_209_000_000L, stepMillis = 7_000L)
        val contrastEvents = recordEventsFromClock(contrastClock)
        val contrastRuntime = StrandRuntime(HostPolicy.OPEN.copy(clock = contrastClock))
        val contrastTrace = contrastRuntime.runMachine(ledger.image, ledger.machineId, contrastEvents)

        return R1Result(
            recordedEvents = recorded,
            liveTrajectory = liveTrace.steps.map { it.after },
            replayTrajectory = replayTrace.steps.map { it.after },
            liveOutputs = liveTrace.steps.map { it.outputs },
            replayOutputs = replayTrace.steps.map { it.outputs },
            contrastTrajectory = contrastTrace.steps.map { it.after },
            replayTouchedNoLiveIo = touchedNoLiveIo,
        )
    }

    /** A clock that returns a fixed start then advances by a step on each read. */
    class SteppingClock(start: Long, private val stepMillis: Long) : Builtins.Clock {
        private var current = start
        override fun nowMillis(): Long {
            val v = current
            current += stepMillis
            return v
        }
        override fun sleep(millis: Long) = Unit
    }

    // ==================================================================
    // R2 — Time-travel inspection.
    // ==================================================================

    data class R2Result(
        val recordedEvents: List<Value>,
        /** state-after at each event index, exactly as the Trace exposes it. */
        val stateAtEachIndex: List<Value>,
        /** balance read out of the state product at each index (for the transcript). */
        val balanceAtEachIndex: List<Long>,
        val finalState: Value,
    ) {
        /** The state at index i has count == i+1 (a coherence check). */
        val countsAreConsecutive: Boolean
            get() = stateAtEachIndex.withIndex().all { (i, s) ->
                (s as Value.ProductV).fields["count"] == Value.IntV((i + 1).toLong())
            }
    }

    /**
     * R2: from the recorded run, read the machine state AT each event index. The
     * synchronous `Trace` exposes `steps[i].after` — the exact state-after the
     * runtime computed at step `i`. Landing on any index and reading its state is
     * the "step back to event N" capability, served entirely from the recorded
     * trajectory with no re-execution of live effects.
     */
    fun scenarioR2(): R2Result {
        val ledger = loadLedger()
        val clock = SteppingClock(start = 1_717_200_000_000L, stepMillis = 1_000L)
        val recorded = recordEventsFromClock(clock)
        val trace = StrandRuntime(HostPolicy.OPEN.copy(clock = clock))
            .runMachine(ledger.image, ledger.machineId, recorded)

        val states = trace.steps.map { it.after }
        val balances = states.map { (it as Value.ProductV).fields.getValue("balance").let { b -> (b as Value.IntV).v } }
        return R2Result(
            recordedEvents = recorded,
            stateAtEachIndex = states,
            balanceAtEachIndex = balances,
            finalState = trace.final.finalState,
        )
    }

    // ==================================================================
    // R3 — Snapshot -> restart -> resume.
    // ==================================================================

    data class R3Result(
        val snapshotFile: Path,
        /** The state captured in the snapshot (after the partway run). */
        val snapshotState: Value,
        val processedEventCount: Long,
        /** The resumed (Process B) post-snapshot trajectory. */
        val resumedTrajectory: List<Value>,
        /** The matching tail of an uninterrupted single run. */
        val uninterruptedTail: List<Value>,
        val resumedFinalState: Value,
        val uninterruptedFinalState: Value,
        /** Per-step outputs over the post-snapshot overlap. */
        val resumedOutputs: List<List<Value>>,
        val uninterruptedTailOutputs: List<List<Value>>,
    ) {
        val trajectoriesIdentical: Boolean get() = resumedTrajectory == uninterruptedTail
        val outputsIdentical: Boolean get() = resumedOutputs == uninterruptedTailOutputs
        val finalStatesIdentical: Boolean get() = resumedFinalState == uninterruptedFinalState
    }

    /**
     * R3: run the first half of the six events as a live group, snapshot the
     * instance, write the snapshot to disk through the facade (`ValueCodec`-backed
     * `writeSnapshot`), drop the Process-A objects, read the snapshot in a FRESH
     * `StrandRuntime` under a NEW `HostPolicy` (the "new process"), and resume over
     * the remaining events. The post-snapshot trajectory equals the matching tail
     * of an uninterrupted single run — the restart-resume equivalence property.
     *
     * This packages the `SnapshotPersistenceTest` technique on the ledger machine.
     */
    suspend fun scenarioR3(scope: kotlinx.coroutines.CoroutineScope, tmp: Path): R3Result {
        val ledger = loadLedger()
        val clock = SteppingClock(start = 1_717_200_000_000L, stepMillis = 1_000L)
        val recorded = recordEventsFromClock(clock)
        val half = recorded.size / 2  // 3 of 6

        // --- Process A: run the first half as a live group, then snapshot. ---
        val runtimeA = StateMachineRuntime(ledger.image.store, ledger.image.hashToNodeId)
        val group = MachineGroup(
            store = ledger.image.store,
            hashToNodeId = ledger.image.hashToNodeId,
            machines = listOf(ledger.machineId),
            nodeIdToHash = ledger.nodeIdToHash,
        )
        val handle = runtimeA.runGroup(group, scope)
        val input = handle.externalInputs.values.single()
        val output = handle.externalOutputs.values.single()
        for (i in 0 until half) input.send(recorded[i])
        repeat(half) { output.receive() }
        val snapshot = handle.snapshot(handle.instances.keys.single())
        input.close()
        handle.await()

        // Persist through the facade. From here the test never touches the
        // in-memory snapshot object again — only the file: the process boundary.
        val facadeA = StrandRuntime(HostPolicy.OPEN.copy(clock = clock))
        val snapshotFile = tmp.resolve("ledger.snapshot.json")
        facadeA.writeSnapshot(snapshot, snapshotFile)

        // --- Process B: a fresh runtime + fresh HostPolicy reads the snapshot
        //     and resumes over the remaining events. ---
        val runtimeB = StrandRuntime(HostPolicy.OPEN)
        val imageB = ProgramImage(ledger.image.store, ledger.image.root, ledger.image.hashToNodeId)
        val restored = runtimeB.readSnapshot(snapshotFile)
        val remaining = recorded.drop(half)
        val resumedTrace = runtimeB.resume(
            program = imageB,
            machine = ledger.machineId,
            snapshot = restored,
            additionalEvents = remaining,
            nodeIdToHash = ledger.nodeIdToHash,
        )

        // --- Equivalence baseline: an uninterrupted single run over all events. ---
        val uninterrupted = runtimeB.runMachine(imageB, ledger.machineId, recorded)

        return R3Result(
            snapshotFile = snapshotFile,
            snapshotState = restored.snapshotState,
            processedEventCount = restored.processedEventCount,
            resumedTrajectory = resumedTrace.steps.map { it.after },
            uninterruptedTail = uninterrupted.steps.drop(half).map { it.after },
            resumedFinalState = resumedTrace.final.finalState,
            uninterruptedFinalState = uninterrupted.final.finalState,
            resumedOutputs = resumedTrace.steps.map { it.outputs },
            uninterruptedTailOutputs = uninterrupted.steps.drop(half).map { it.outputs },
        )
    }

    // ------------------------------------------------------------------
    // Rendering helpers.
    // ------------------------------------------------------------------

    /** Render a ledger state product compactly: `bal=N stamp=T count=C`. */
    fun renderState(v: Value): String {
        val p = v as Value.ProductV
        val bal = (p.fields.getValue("balance") as Value.IntV).v
        val stamp = (p.fields.getValue("lastStamp") as Value.IntV).v
        val count = (p.fields.getValue("count") as Value.IntV).v
        return "bal=$bal stamp=$stamp count=$count"
    }

    /** Whether the verifier flagged any NondeterministicInReplayContext warning. */
    fun nondeterminismWarnings(verify: VerifyResult): List<VerifyWarning> =
        (verify as? VerifyResult.Ok)?.warnings
            ?.filterIsInstance<VerifyWarning.NondeterministicInReplayContext>()
            ?: emptyList()

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val tmp = Files.createTempDirectory("strand-replay-demo")
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)

        line("=".repeat(72))
        line("Strand -- sound deterministic replay, time-travel, restart-resume")
        line("Subject: an event-sourced ledger (a running balance over events).")
        line("=".repeat(72))
        line()

        // Soundness preamble: the transition is pure (Q-065), so the verifier
        // raises no NondeterministicInReplayContext warning — the basis for
        // bit-identical replay.
        val ledger = loadLedger()
        val ndWarnings = nondeterminismWarnings(ledger.verify)
        line("Soundness basis (Q-065): the transition references only deterministic")
        line("builtins (Int.Add) and reads no clock of its own -- the recorded")
        line("timestamp arrives as DATA on the event, not via a Time.Now call.")
        line("  verifier NondeterministicInReplayContext warnings = ${ndWarnings.size}")
        line()

        // --- R1 ---
        line("R1  Deterministic replay -- bit-identical trajectory, zero live IO")
        line("-".repeat(72))
        val r1 = scenarioR1()
        line("  Live run: the driver read the host clock to stamp each event.")
        line("    recorded stamps          = ${r1.recordedEvents.map { stampOf(it) }}")
        line("    live trajectory          = ${r1.liveTrajectory.map { renderState(it) }}")
        line("  Replay: re-fed the recorded events under a clock that THROWS if read.")
        line("    replay touched no live IO = ${r1.replayTouchedNoLiveIo}")
        line("    replay trajectory        = ${r1.replayTrajectory.map { renderState(it) }}")
        line("    trajectories identical   = ${r1.trajectoriesIdentical}")
        line("    outputs identical        = ${r1.outputsIdentical}")
        line("  Contrast: a FRESH live run under a DIFFERENT clock genuinely differs.")
        line("    contrast trajectory      = ${r1.contrastTrajectory.map { renderState(it) }}")
        line("    replay == live != contrast = ${r1.replayMatchesLiveButContrastDiffers}")
        line("  A conventional service cannot promise this: effects are implicit and")
        line("  interleaved, so a second pass reads fresh clocks/IO and diverges.")
        line()

        // --- R2 ---
        line("R2  Time-travel inspection -- read the exact state at any event index")
        line("-".repeat(72))
        val r2 = scenarioR2()
        line("  From the recorded run, the Trace exposes the state-after at each")
        line("  event index. Step back to any point in history and read it:")
        for ((i, s) in r2.stateAtEachIndex.withIndex()) {
            line("    after event $i  ->  ${renderState(s)}")
        }
        line("    balances by index        = ${r2.balanceAtEachIndex}")
        line("    counts are consecutive   = ${r2.countsAreConsecutive}")
        line("    final state              = ${renderState(r2.finalState)}")
        line()

        // --- R3 ---
        line("R3  Snapshot -> restart -> resume -- survives a process boundary")
        line("-".repeat(72))
        val r3 = kotlinx.coroutines.runBlocking { scenarioR3(this, tmp) }
        line("  Process A ran the first 3 of 6 events, snapshotted, and wrote the")
        line("  snapshot to disk through the ValueCodec-backed facade.")
        line("    snapshot file            = ${r3.snapshotFile.fileName}")
        line("    snapshot state           = ${renderState(r3.snapshotState)}")
        line("    processed event count    = ${r3.processedEventCount}")
        line("  Process B (fresh StrandRuntime + new HostPolicy) read it and resumed:")
        line("    resumed trajectory       = ${r3.resumedTrajectory.map { renderState(it) }}")
        line("    uninterrupted tail       = ${r3.uninterruptedTail.map { renderState(it) }}")
        line("    trajectories identical   = ${r3.trajectoriesIdentical}")
        line("    outputs identical        = ${r3.outputsIdentical}")
        line("    final states identical   = ${r3.finalStatesIdentical}")
        line("    resumed final            = ${renderState(r3.resumedFinalState)}")
        line()

        line("=".repeat(72))
        line("What this demonstrates: SOUND deterministic replay, time-travel, and")
        line("restart-resume -- grounded in pure transitions + effects-as-edges +")
        line("Q-065 determinism enforcement. NOT first-pass correctness or cost.")
        line("=".repeat(72))

        print(out)
    }

    /** The recorded stamp out of an event product (transcript helper). */
    private fun stampOf(event: Value): Long =
        ((event as Value.ProductV).fields.getValue("stamp") as Value.IntV).v
}
