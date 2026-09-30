package org.strand.runtime

import kotlinx.coroutines.CoroutineScope
import org.strand.core.Hash
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.schema.SchemaCheckResult
import org.strand.schema.SchemaChecker
import org.strand.verifier.ProgramAnalysis
import org.strand.verifier.TypeExpr
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * Q-054: the published embedding surface. A JVM host constructs a
 * [StrandRuntime] with a [HostPolicy] and calls [verify] / [run] /
 * [runMachine] / [runGroup] to drive a verified program in-process, without
 * hand-replicating the CLI's pipeline.
 *
 * See [`proposals/implemented/embeddable-runtime.md`](../../../../../../../proposals/implemented/embeddable-runtime.md).
 * The two graphs-one-process property the item exists to provide: two
 * [StrandRuntime] instances carrying different [HostPolicy] values run their
 * programs under their own policy with no cross-contamination — one SECURE
 * sandbox and one OPEN, or two different limits, etc.
 *
 * **Concurrent multi-tenant isolation (Q-054 follow-up, completed).** Each
 * evaluating method derives a per-invocation
 * [org.strand.interpreter.HostContext] from this runtime's policy and threads
 * it into the [Interpreter] / [StateMachineRuntime] it builds as an ordinary
 * value. The builtin lambdas read their clock / random / sandbox / credentials
 * / etc. from that context, never from the [org.strand.interpreter.Builtins]
 * process-global singletons. There is no install/restore around a run, so two
 * runtimes evaluating effectful programs *concurrently* in one JVM each see
 * their own policy with nothing to clobber — the property the facade was the
 * structural prerequisite for. Each context also carries its own credential
 * scrubber, so a tenant's credential never appears in another tenant's scrubbed
 * error text.
 *
 * The program is supplied as the canonical [ProgramImage] (store + root +
 * hashToNodeId), not a root hash — run-by-hash is Q-058. The optional
 * `resolveTarget` is the Q-043 cross-store resolution callback, threaded into
 * every backend exactly as the CLI threads its `FederatedProgram::fetchAndAdmit`.
 */
class StrandRuntime(private val policy: HostPolicy) {

    /**
     * Verify [program]. Reads no host-routed singleton (verification is pure
     * type-checking over the store), so this performs no install. Returns the
     * raw [VerifyResult] for the caller to branch on; the facade does not
     * print or exit — rendering is a CLI concern.
     */
    fun verify(program: ProgramImage): VerifyResult =
        Verifier(program.store, program.hashToNodeId, program.resolveTarget).verify(program.root)

    /**
     * Verify-then-schema-check [program] without evaluating. The pipeline
     * `run` runs up to evaluation, returned as structured data. Used by the
     * CLI's `verify` subcommand.
     */
    fun verifyAndCheckSchema(program: ProgramImage): VerifyOutcome {
        val verify = verify(program)
        if (verify is VerifyResult.Failed) return VerifyOutcome.Failed(verify.errors)
        verify as VerifyResult.Ok
        val schema = checkSchema(program, verify)
        return VerifyOutcome.Ok(verify, schema)
    }

    /**
     * Evaluate a pure / Layer 1–5 [program] (and any reachable runtime-schema
     * obligations) under this runtime's policy. Verifies first; on a verify
     * failure or a static schema violation the result carries the diagnostics
     * and no value. On success the result carries the produced [Value].
     *
     * The policy is projected to a per-invocation
     * [org.strand.interpreter.HostContext] and threaded into the interpreter as
     * a value; no process-global singleton is installed, so a concurrent
     * `run` on another [StrandRuntime] under a different policy is unaffected.
     */
    fun run(program: ProgramImage, capabilities: CapabilitySet = CapabilitySet.EMPTY): RunOutcome {
        val verify = verify(program)
        if (verify is VerifyResult.Failed) return RunOutcome.VerifyFailed(verify.errors)
        verify as VerifyResult.Ok
        val schema = checkSchema(program, verify)
        if (schema.hasViolations) return RunOutcome.SchemaViolation(verify, schema)

        // Q-076: every obligation on every node — a shared node reaching
        // several schema positions is checked against each schema.
        val schemaObligations = verify.schemaObligations
        // Q-054 follow-up: derive a per-invocation HostContext from this
        // runtime's policy and thread it into the interpreter as a value. No
        // singleton install — two runtimes evaluating concurrently each read
        // their own context, so there is nothing to clobber.
        val ctx = org.strand.interpreter.HostContext.fromPolicy(
            policy, verify.nodeTypes, verify.verifiedInterceptions,
        )
        val interp = Interpreter(
            program.store,
            program.hashToNodeId,
            resolveTarget = program.resolveTarget,
            schemaObligations = schemaObligations,
            hostContext = ctx,
        )
        val value = interp.eval(program.root, capabilities, policy.limits)
        return RunOutcome.Ok(verify, schema, value)
    }

    /**
     * Q-072 (ADR-010): the third first-class entry point alongside [verify]
     * and [run]. Verify [program], then wrap the verified artifact in a pure
     * [ProgramAnalysis] query surface. On a verify failure the outcome carries
     * the diagnostics and no analysis; the facade never prints or exits.
     *
     * Reads only the verify result and the store — no evaluation, no policy
     * install, hash-neutral. The returned [ProgramAnalysis] answers the
     * machine-facing reasoning queries (effect / latent / total closure,
     * capability requirement, egress set, harm bound, reachability, cross-
     * program diff) as typed data.
     */
    fun analyze(program: ProgramImage): AnalysisOutcome {
        val verify = verify(program)
        if (verify is VerifyResult.Failed) return AnalysisOutcome.VerifyFailed(verify.errors)
        verify as VerifyResult.Ok
        return AnalysisOutcome.Ok(ProgramAnalysis(program.store, verify, program.root))
    }

    /**
     * Convenience: verify [program] and return both the raw [VerifyResult.Ok]
     * and its [ProgramAnalysis] in one call, or the verify diagnostics. A
     * caller that wants the inferred types *and* the reasoning surface avoids
     * verifying twice.
     */
    fun verifyAndAnalyze(program: ProgramImage): AnalysisOutcome = analyze(program)

    /**
     * Q-073 (ADR-010's fourth commitment): the self-gating primitive. Gate
     * [program]'s execution on a declared [budget] BEFORE any effect runs.
     *
     * Verifies [program] first (a verify failure yields
     * [GuardedOutcome.VerifyFailed], mirroring [verify] / [analyze]). On a
     * verifying image, builds a [ProgramAnalysis] exactly as [analyze] does
     * and computes `totalClosure(root)` — the union of the directly-performed
     * and latent effect closures ([ProgramAnalysis.totalClosure], already
     * Handler-aware and latent-channel-aware; not re-derived here). If the
     * total closure contains an EffectCategory [budget] does not grant (i.e.
     * `totalClosure ⊄ budget.grants.keys`), the operation refuses: it returns
     * [GuardedOutcome.Refused] with a [RefusalReport] naming every exceeding
     * category and whether each was reached through the direct channel (in
     * `rootClosure`), the latent channel (in `rootLatentClosure` but not
     * `rootClosure`), or both — WITHOUT invoking [run]. No effect occurs on
     * the refusal path.
     *
     * Otherwise the program is within budget: it runs under [budget] as its
     * [CapabilitySet] (so runtime refinement enforcement remains the
     * backstop for the parameter-level bounds this category-level gate does
     * not statically prove), and the outcome is [GuardedOutcome.Ran] wrapping
     * the ordinary [RunOutcome].
     *
     * The gate is category-level, matching the N-036 CapabilityScope
     * static-proof granularity it generalizes to the whole program at the
     * run boundary. Refinement-level (parameter) pre-execution proof is
     * deferred to Q-068; runtime enforcement covers it for now.
     *
     * This gates the plain [run] entry point. The machine-path gates are
     * [runMachineGuarded] and [runGroupGuarded], which bound by
     * [ProgramAnalysis.machineClosure] / [ProgramAnalysis.groupClosure]
     * instead of `totalClosure(root)`.
     */
    fun runGuarded(program: ProgramImage, budget: CapabilitySet): GuardedOutcome {
        val verify = verify(program)
        if (verify is VerifyResult.Failed) return GuardedOutcome.VerifyFailed(verify.errors)
        verify as VerifyResult.Ok

        val analysis = ProgramAnalysis(program.store, verify, program.root)
        val direct = verify.rootClosure(program.root)
        val latent = verify.rootLatentClosure(program.root)
        val total = analysis.totalClosure(program.root)
        val granted = budget.grants.keys

        val exceeding = total - granted
        if (exceeding.isNotEmpty()) {
            val perCategory = exceeding.associateWith { category ->
                when {
                    category in direct && category in latent -> EffectChannel.BOTH
                    category in direct -> EffectChannel.DIRECT
                    else -> EffectChannel.LATENT
                }
            }
            return GuardedOutcome.Refused(
                RefusalReport(
                    exceeding = perCategory,
                    requested = total,
                    granted = granted,
                ),
            )
        }

        return GuardedOutcome.Ran(run(program, budget))
    }

    /**
     * Q-073, machine path: the self-gating primitive for one StateMachine.
     * Verify [program]; bound what driving [machine] can perform with
     * [ProgramAnalysis.machineClosure] (the declared effects row, which
     * covers the transition, `initialState` and the implicit
     * `StateMachine.Receive` / `.Send`, plus the latent channel reachable
     * from the transition and the initial state); and when that bound is not
     * within [budget]'s categories, return [GuardedMachineOutcome.Refused]
     * before anything is evaluated: no `initialState`, no transition, no
     * audit record. The [RefusalReport] names each exceeding category with
     * its channel ([EffectChannel.DIRECT] for the declared row,
     * [EffectChannel.LATENT] for the latent reach).
     *
     * Otherwise run the verify-time schema pass (a static violation is
     * [GuardedMachineOutcome.SchemaViolation], nothing evaluated) and drive
     * the machine through the [VerifyResult.Ok] form of [runMachine] with
     * [budget] as its [CapabilitySet]: the schema obligations and the
     * verified-interception record are enforced, and runtime capability and
     * refinement checks stay the backstop for the parameter-level bounds this
     * category-level gate does not prove. A violation or denial in
     * `initialState` propagates as an
     * [org.strand.interpreter.InterpretException], as from [runMachine].
     *
     * [machine] must be a StateMachine the verification of [program] reaches
     * (it is typically `program.root`); anything else throws
     * [IllegalArgumentException].
     */
    fun runMachineGuarded(
        program: ProgramImage,
        machine: NodeId,
        events: List<Value>,
        budget: CapabilitySet,
    ): GuardedMachineOutcome {
        val verify = verify(program)
        if (verify is VerifyResult.Failed) return GuardedMachineOutcome.VerifyFailed(verify.errors)
        verify as VerifyResult.Ok

        val bound = analysisOf(program, verify).machineClosure(machine)
        refusalOf(bound.direct, bound.latent, budget)?.let { return GuardedMachineOutcome.Refused(it) }

        val schema = checkSchema(program, verify)
        if (schema.hasViolations) return GuardedMachineOutcome.SchemaViolation(verify, schema)
        return GuardedMachineOutcome.Ran(verify, schema, runMachine(program, machine, events, budget, verify))
    }

    /**
     * Q-073, group path: the self-gating primitive for a [MachineGroup].
     * Verify [program]; bound what running [group] can perform with
     * [ProgramAnalysis.groupClosure] over its machines (every machine's
     * [ProgramAnalysis.machineClosure], widened to every StateMachine in the
     * store when one can spawn, plus each `source`-bound external stream's
     * opener effects and the `Network.Receive` its feeder performs); and when
     * that bound is not within [budget]'s categories, return
     * [GuardedGroupOutcome.Refused] before anything is evaluated or opened:
     * no `initialState`, no source opener, no actor. Source effects are
     * reported on the [EffectChannel.DIRECT] channel; a transport category
     * the store has no node for is named in
     * [RefusalReport.unresolvedCategoryNames].
     *
     * Otherwise run the verify-time schema pass and start the group through
     * the [VerifyResult.Ok] form of [runGroup], with [budget] replacing the
     * group's own `capabilities` (the budget is the grant), returning
     * [GuardedGroupOutcome.Started] with the handle. Group-start failures
     * propagate as from [runGroup].
     *
     * Every machine in [group] must be one the verification of [program]
     * reaches; anything else throws [IllegalArgumentException].
     */
    fun runGroupGuarded(
        program: ProgramImage,
        group: MachineGroup,
        budget: CapabilitySet,
        scope: CoroutineScope,
    ): GuardedGroupOutcome {
        val verify = verify(program)
        if (verify is VerifyResult.Failed) return GuardedGroupOutcome.VerifyFailed(verify.errors)
        verify as VerifyResult.Ok

        val bound = analysisOf(program, verify).groupClosure(group.machines)
        refusalOf(bound.direct, bound.latent, budget, bound.unresolvedCategoryNames)
            ?.let { return GuardedGroupOutcome.Refused(it) }

        val schema = checkSchema(program, verify)
        if (schema.hasViolations) return GuardedGroupOutcome.SchemaViolation(verify, schema)
        val handle = runGroup(program, group.copy(capabilities = budget), scope, verify)
        return GuardedGroupOutcome.Started(verify, schema, handle)
    }

    private fun analysisOf(program: ProgramImage, verify: VerifyResult.Ok): ProgramAnalysis =
        ProgramAnalysis(program.store, verify, program.root, program.hashToNodeId)

    /**
     * The refusal for a bound split into [direct] and [latent] channels
     * against [budget], or null when the bound is within it. [unresolved]
     * names categories required by name that no grant can cover.
     */
    private fun refusalOf(
        direct: Set<NodeId>,
        latent: Set<NodeId>,
        budget: CapabilitySet,
        unresolved: Set<String> = emptySet(),
    ): RefusalReport? {
        val total = direct + latent
        val granted = budget.grants.keys
        val exceeding = total - granted
        if (exceeding.isEmpty() && unresolved.isEmpty()) return null
        return RefusalReport(
            exceeding = exceeding.associateWith { category ->
                when {
                    category in direct && category in latent -> EffectChannel.BOTH
                    category in direct -> EffectChannel.DIRECT
                    else -> EffectChannel.LATENT
                }
            },
            requested = total,
            granted = granted,
            unresolvedCategoryNames = unresolved,
        )
    }

    /**
     * Drive a single verified [machine] over [events] under this runtime's
     * policy. The caller has already verified (or calls [verify]); this method
     * installs the policy and runs the synchronous fold, returning the
     * [Trace]. Mirrors the CLI's `machine` subcommand pipeline minus rendering.
     *
     * [verifierNodeTypes] is the verify result's `nodeTypes` map (the
     * N-044/N-045 LLM schema-projection path reads it); pass
     * `verify(program).asOk()?.nodeTypes` or null. [verifiedInterceptions]
     * is the same result's `verifiedInterceptions`; when passed, the
     * transition refuses a Handler interception the verifier did not check
     * (null leaves that guard off). This form enforces no runtime schema
     * obligations; the [VerifyResult.Ok] overload does.
     */
    fun runMachine(
        program: ProgramImage,
        machine: NodeId,
        events: List<Value>,
        capabilities: CapabilitySet = CapabilitySet.EMPTY,
        verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
        verifiedInterceptions: Map<NodeId, Set<NodeId>>? = null,
    ): Trace = machineRuntime(program, verifierNodeTypes, verifiedInterceptions)
        .runMachine(machine, events, capabilities, policy.limits)

    /**
     * Q-047 (machine path): drive [machine] with everything the machine path
     * reads from the verify result taken from [verified] — its `nodeTypes`
     * (the LLM schema-projection path), its `verifiedInterceptions` (the
     * Handler-interception guard), and its `schemaObligations`. The last is
     * what the loose-parameter form cannot supply: every interpreter the run
     * constructs checks each obligation when it reduces the obligated node to
     * a value, inside `initialState` and inside every transition, as [run]
     * does for a plain expression.
     *
     * A violation inside a transition halts the fold with
     * [HaltReason.SchemaViolation] on the trace's halt record (the steps
     * before it are kept, as for [HaltReason.CapabilityDenial]); a violation
     * in `initialState` fires before any event and propagates as an
     * [org.strand.interpreter.InterpretException] carrying
     * [org.strand.interpreter.InterpretError.SchemaInvariantViolation], as a
     * denial there does. [verified] must be the result of verifying
     * [program] (typically [verify] or [verifyAndCheckSchema]); the
     * verify-time schema pass stays the caller's to run first, as with the
     * other form.
     */
    fun runMachine(
        program: ProgramImage,
        machine: NodeId,
        events: List<Value>,
        capabilities: CapabilitySet,
        verified: VerifyResult.Ok,
    ): Trace = machineRuntime(program, verified).runMachine(machine, events, capabilities, policy.limits)

    /**
     * Spawn a [MachineGroup] under this runtime's policy and return the
     * [MachineGroupHandle]. The policy is projected to a
     * [org.strand.interpreter.HostContext] and bound to every per-actor
     * interpreter, source opener, and feeder the group spawns, so the actors —
     * which evaluate asynchronously after this returns, on `Dispatchers.IO` —
     * read the group's tenant policy with no shared mutable singleton on the
     * path. Two groups can therefore run concurrently under different policies.
     * This form enforces no runtime schema obligations; the [VerifyResult.Ok]
     * overload does.
     */
    fun runGroup(
        program: ProgramImage,
        group: MachineGroup,
        scope: CoroutineScope,
        verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
        verifiedInterceptions: Map<NodeId, Set<NodeId>>? = null,
    ): MachineGroupHandle =
        // Q-054 follow-up: the policy flows into the runtime as a HostContext
        // value, bound to every per-actor interpreter and feeder. No singleton
        // install — the group runs asynchronously past this return without
        // depending on a mutable process-global being held in place, so two
        // groups can run concurrently under different policies.
        machineRuntime(program, verifierNodeTypes, verifiedInterceptions).runGroup(group, scope, policy.limits)

    /**
     * Q-047 (machine path): spawn [group] with `nodeTypes`,
     * `verifiedInterceptions` and `schemaObligations` all taken from
     * [verified] (see the [runMachine] overload of the same shape). Every
     * per-actor interpreter, every dynamically spawned actor, the source-
     * opener interpreter, and any interpreter a
     * [MachineGroup.dispatcherFactory] builds from its [DispatcherWiring]
     * enforce the obligations.
     *
     * A violation inside an actor's transition halts THAT instance with
     * [HaltReason.SchemaViolation] (read it from
     * [MachineGroupHandle.schemaViolations] or the instance's `haltReason`);
     * its siblings keep running, as for any other instance halt. A violation
     * while an initial instance is built (its `initialState`) propagates from
     * this call as an [org.strand.interpreter.InterpretException], as a
     * group-start denial does.
     */
    fun runGroup(
        program: ProgramImage,
        group: MachineGroup,
        scope: CoroutineScope,
        verified: VerifyResult.Ok,
    ): MachineGroupHandle = machineRuntime(program, verified).runGroup(group, scope, policy.limits)

    /**
     * Retained for CLI source compatibility: the `group` subcommand wraps its
     * `runBlocking` body in this to scope the group lifecycle. Since the Q-054
     * follow-up retired the singleton install/restore (policy now flows as a
     * [org.strand.interpreter.HostContext] value), this is simply
     * `block()` — there is nothing to install or restore. The
     * [verifierNodeTypes] parameter is ignored; the per-invocation context is
     * derived inside [runGroup] from the runtime's policy.
     */
    fun <T> withGroupInstalled(
        @Suppress("UNUSED_PARAMETER") verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
        block: () -> T,
    ): T = block()

    /**
     * Q-059: spawn [group] in SERVICE mode and return a [GroupService] the host
     * drives over time. Unlike the batch [runGroup] usage (send routed events,
     * close inputs, drain to halt), the service keeps external inputs open and
     * runs until the host calls `stop()` or a real halt occurs — the server
     * shape that `Http.Listen` / `Http.Accept` invite.
     *
     * Like [runGroup], the policy flows into the spawned actors as a
     * [org.strand.interpreter.HostContext] value bound at spawn, so the service
     * runs asynchronously past this call under its own tenant policy with no
     * singleton install to hold in place.
     *
     * The long-running limits model is host policy: set
     * [org.strand.core.EvaluationLimits.perEventStepBudget] on the runtime's
     * [HostPolicy] so a long-lived actor is bounded per event rather than by the
     * whole-run wall-clock. [serveGroup] does not change the limits — it only
     * declines to close the inputs.
     *
     * [inputStreamIds] / [outputStreamIds] map the host-facing stream names the
     * caller uses with [GroupService.send] / [GroupService.outputs] to the
     * EventStream NodeIds (the CLI passes its ingest author-id name map).
     */
    fun serveGroup(
        program: ProgramImage,
        group: MachineGroup,
        scope: CoroutineScope,
        inputStreamIds: Map<String, NodeId> = emptyMap(),
        outputStreamIds: Map<String, NodeId> = emptyMap(),
        verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
    ): GroupService {
        val handle = runGroup(program, group, scope, verifierNodeTypes)
        return GroupService(handle, inputStreamIds, outputStreamIds)
    }

    /**
     * Q-047 (machine path): [serveGroup] with `nodeTypes`,
     * `verifiedInterceptions` and `schemaObligations` taken from [verified],
     * through the [runGroup] overload of the same shape.
     */
    fun serveGroup(
        program: ProgramImage,
        group: MachineGroup,
        scope: CoroutineScope,
        verified: VerifyResult.Ok,
        inputStreamIds: Map<String, NodeId> = emptyMap(),
        outputStreamIds: Map<String, NodeId> = emptyMap(),
    ): GroupService =
        GroupService(runGroup(program, group, scope, verified), inputStreamIds, outputStreamIds)

    /**
     * Q-059: resume a [machine] from a [snapshot] over [additionalEvents] under
     * this runtime's policy. The synchronous-fold analogue of [runMachine] that
     * starts from a checkpointed state instead of the machine's declared
     * `initialState` — the restart half of the snapshot-persistence story.
     *
     * Threads the runtime's policy in as a [org.strand.interpreter.HostContext]
     * value exactly as [runMachine] does. Delegates to the existing
     * [StateMachineRuntime.resume]; the [SnapshotMachineHashMismatch] integrity
     * check fires if [machine]'s hash (in [nodeIdToHash]) does not match the
     * snapshot's recorded `machineHash`. Returns the [Trace] of the
     * post-snapshot events.
     *
     * [nodeIdToHash] is the forward map from `Hasher.finalize` (the host has it
     * alongside [ProgramImage.hashToNodeId]); it is a parameter rather than a
     * [ProgramImage] field so the Q-054 image shape and its tests are untouched.
     */
    fun resume(
        program: ProgramImage,
        machine: NodeId,
        snapshot: Snapshot,
        additionalEvents: List<Value>,
        nodeIdToHash: Map<NodeId, Hash>,
        capabilities: CapabilitySet = CapabilitySet.EMPTY,
        verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
    ): Trace = machineRuntime(program, verifierNodeTypes, verifiedInterceptions = null)
        .resume(machine, snapshot, additionalEvents, nodeIdToHash, capabilities, policy.limits)

    /**
     * Q-047 (machine path): [resume] with `nodeTypes`,
     * `verifiedInterceptions` and `schemaObligations` taken from [verified].
     * A post-snapshot transition producing a value that violates a schema
     * invariant halts the returned trace with [HaltReason.SchemaViolation].
     * The snapshot state itself is not re-checked: it is a value the
     * machine produced earlier, not a node reduction.
     */
    fun resume(
        program: ProgramImage,
        machine: NodeId,
        snapshot: Snapshot,
        additionalEvents: List<Value>,
        nodeIdToHash: Map<NodeId, Hash>,
        capabilities: CapabilitySet,
        verified: VerifyResult.Ok,
    ): Trace = machineRuntime(program, verified)
        .resume(machine, snapshot, additionalEvents, nodeIdToHash, capabilities, policy.limits)

    /**
     * The [StateMachineRuntime] every machine-path entry point drives: the
     * runtime's policy projected to a per-invocation
     * [org.strand.interpreter.HostContext] (with [verifierNodeTypes] /
     * [verifiedInterceptions]) and the runtime [schemaObligations] bound to
     * every interpreter it constructs.
     */
    private fun machineRuntime(
        program: ProgramImage,
        verifierNodeTypes: Map<NodeId, TypeExpr>?,
        verifiedInterceptions: Map<NodeId, Set<NodeId>>?,
        schemaObligations: Map<NodeId, List<TypeExpr.SchemaType>> = emptyMap(),
    ): StateMachineRuntime = StateMachineRuntime(
        program.store,
        program.hashToNodeId,
        program.resolveTarget,
        org.strand.interpreter.HostContext.fromPolicy(policy, verifierNodeTypes, verifiedInterceptions),
        schemaObligations,
    )

    /** [machineRuntime] with all three verify-result inputs taken from [verified]. */
    private fun machineRuntime(program: ProgramImage, verified: VerifyResult.Ok): StateMachineRuntime =
        machineRuntime(program, verified.nodeTypes, verified.verifiedInterceptions, verified.schemaObligations)

    /**
     * Q-059: serialize [snapshot] to [path] via [SnapshotCodec] so a
     * checkpointed group can survive a process restart. Propagates
     * [ValueCodecError.NotSnapshotable] if the snapshotted state holds a
     * non-serializable runtime-only [Value] (a Closure or a live Resource).
     * No policy install — serialization reads no host-routed singleton.
     */
    fun writeSnapshot(snapshot: Snapshot, path: java.nio.file.Path) {
        java.nio.file.Files.writeString(path, SnapshotCodec.encode(snapshot))
    }

    /** Q-059: deserialize a [Snapshot] from [path] (the inverse of [writeSnapshot]). */
    fun readSnapshot(path: java.nio.file.Path): Snapshot =
        SnapshotCodec.decode(java.nio.file.Files.readString(path))

    // ------------------------------------------------------------------
    // Q-058: persistent store — admit-and-verify-once and run-by-hash.
    // ------------------------------------------------------------------

    /**
     * Q-058: read a [ProgramImage] from [store] by [rootHash], or null when the
     * store does not hold the root. The subgraph is admitted into a fresh
     * [org.strand.core.NodeStore] through the existing Q-043 federation
     * admission ([org.strand.hashing.FederatedProgram.fetchAndAdmit]) over a
     * [org.strand.hashing.DiskStoreResolver]; the admitted root's Merkle re-hash
     * must equal [rootHash], so a corrupted or tampered store entry fails closed
     * (a [org.strand.hashing.NodeResolverIntegrityViolation] propagates).
     *
     * The returned image carries the [org.strand.hashing.DiskStoreResolver] as
     * its `resolveTarget`, so any cross-store NodeRef inside the program also
     * dereferences against the same store. This is the run-by-hash entry point:
     * the resulting image runs through [run] / [runMachine] / [runGroup]
     * identically to one built from a file path — it never re-ingests or
     * re-hashes the authored JSON.
     */
    fun loadImageFromStore(
        store: org.strand.hashing.PersistentStore,
        rootHash: Hash,
    ): ProgramImage? {
        if (!store.hasNode(rootHash)) return null
        val resolver = org.strand.hashing.DiskStoreResolver(store)
        val federated = org.strand.hashing.FederatedProgram(
            store = org.strand.core.NodeStore(),
            root = NodeId(-1),
            nodeIdToHash = HashMap(),
            hashToNodeId = HashMap(),
            resolver = resolver,
        )
        val localRoot = federated.fetchAndAdmit(rootHash) ?: return null
        return ProgramImage(
            store = federated.store,
            root = localRoot,
            hashToNodeId = federated.hashToNodeId,
            resolveTarget = federated::fetchAndAdmit,
        )
    }

    /**
     * Q-058: admit-and-verify-once. Write every reachable hashable node of
     * [finalized] to [store] (dedup: a node already on disk is a no-op) and
     * record the verify verdict keyed by the program's root hash. The first
     * ingest is the one verify — admit-once means verification happened once and
     * was recorded, never that it is skipped on first admission. [verify] is the
     * verdict a prior [StrandRuntime.verify] produced for this program;
     * [nodeIdToHash] is `finalized`'s forward map (used to key the verdict's
     * per-node types by node hash). Returns the number of newly-written node
     * records.
     */
    fun ingestToStore(
        store: org.strand.hashing.PersistentStore,
        finalized: org.strand.hashing.FinalizedProgram,
        verify: VerifyResult,
        nodeIdToHash: Map<NodeId, Hash>,
    ): Int {
        val written = store.writeProgram(finalized)
        val rootHash = nodeIdToHash.getValue(finalized.root)
        store.writeVerdict(rootHash, storedVerdictOf(verify, nodeIdToHash))
        return written
    }

    /**
     * Q-058: the cached verdict for [rootHash], or null when absent (or recorded
     * under a different epoch — see [org.strand.hashing.PersistentStore.readVerdict]).
     * Only consulted when the store also holds the node record for [rootHash];
     * the caller guards on the image being loadable. Reusing this verdict skips
     * the re-verify entirely on the decision path (Ok-with-rootType/warnings, or
     * the recorded failure).
     */
    fun cachedVerdict(
        store: org.strand.hashing.PersistentStore,
        rootHash: Hash,
    ): org.strand.hashing.StoredVerdict? =
        if (store.hasNode(rootHash)) store.readVerdict(rootHash) else null

    private fun storedVerdictOf(
        verify: VerifyResult,
        nodeIdToHash: Map<NodeId, Hash>,
    ): org.strand.hashing.StoredVerdict = when (verify) {
        is VerifyResult.Failed ->
            org.strand.hashing.StoredVerdict.Failed(verify.errors.map { it.toString() })
        is VerifyResult.Ok -> {
            // Key the per-node types by node hash (stable across runs), keeping
            // only nodes that have a standalone hash (bound nodes are absent
            // from nodeIdToHash and carry no independently-meaningful type key).
            val byHash = LinkedHashMap<String, String>()
            for ((nid, t) in verify.nodeTypes) {
                nodeIdToHash[nid]?.let { h -> byHash[h.toString()] = t.toString() }
            }
            org.strand.hashing.StoredVerdict.Ok(
                rootType = verify.rootType.toString(),
                nodeTypesByHash = byHash,
                warnings = verify.warnings.map { it.toString() },
            )
        }
    }

    private fun checkSchema(program: ProgramImage, verify: VerifyResult.Ok): SchemaCheckResult =
        SchemaChecker(
            program.store,
            program.hashToNodeId,
            verify,
            resolveTarget = program.resolveTarget,
            limits = policy.limits,
            // Review H3: invariant bodies run under this runtime's tenant
            // policy, not the process-global Builtins singletons.
            hostContext = org.strand.interpreter.HostContext.fromPolicy(policy, verify.nodeTypes),
        ).check()
}


/**
 * A finalized, canonical program image: the primitives every Strand backend
 * consumes. Constructed by the host from a `Hasher.finalize` result (the CLI
 * passes its `FinalizedProgram`'s fields) or a federated program. Kept here in
 * `:runtime` rather than reusing `:hashing`'s `FinalizedProgram` so the facade
 * does not pull a `:hashing` compile dependency — the facade only needs the
 * store, root, reverse map, and the optional cross-store callback.
 */
data class ProgramImage(
    val store: NodeStore,
    val root: NodeId,
    val hashToNodeId: Map<Hash, NodeId> = emptyMap(),
    /** Q-043 cross-store resolution callback; null for a single-store program. */
    val resolveTarget: ((Hash) -> NodeId?)? = null,
)

/** Outcome of [StrandRuntime.verifyAndCheckSchema]. */
sealed class VerifyOutcome {
    data class Ok(val verify: VerifyResult.Ok, val schema: SchemaCheckResult) : VerifyOutcome()
    data class Failed(val errors: List<org.strand.verifier.VerifyError>) : VerifyOutcome()
}

/** Outcome of [StrandRuntime.run]. */
sealed class RunOutcome {
    /** Evaluation succeeded; [value] is the program's result. */
    data class Ok(
        val verify: VerifyResult.Ok,
        val schema: SchemaCheckResult,
        val value: Value,
    ) : RunOutcome()

    /** Verification failed; no evaluation occurred. */
    data class VerifyFailed(val errors: List<org.strand.verifier.VerifyError>) : RunOutcome()

    /** A statically-evaluable value violated its Schema; no evaluation occurred. */
    data class SchemaViolation(
        val verify: VerifyResult.Ok,
        val schema: SchemaCheckResult,
    ) : RunOutcome()
}

/** Outcome of [StrandRuntime.analyze] / [StrandRuntime.verifyAndAnalyze] (Q-072). */
sealed class AnalysisOutcome {
    /** The program verified; [analysis] is the reasoning query surface over it. */
    data class Ok(val analysis: ProgramAnalysis) : AnalysisOutcome()

    /** Verification failed; no analysis was produced. */
    data class VerifyFailed(val errors: List<org.strand.verifier.VerifyError>) : AnalysisOutcome()
}

/**
 * Outcome of [StrandRuntime.runGuarded] (Q-073): the self-gating primitive.
 * Distinct in kind from a runtime capability denial ([InterpretError] surfaced
 * inside [RunOutcome.Ok]'s value or thrown mid-evaluation) — that fires during
 * execution at the offending call, after the program has begun and possibly
 * performed other effects. [Refused] fires before any effect: the whole
 * program is refused up front, at the run boundary.
 */
sealed class GuardedOutcome {
    /** The program's total closure was within [budget]; it ran. */
    data class Ran(val outcome: RunOutcome) : GuardedOutcome()

    /**
     * The program's total closure exceeded the declared budget. No effect
     * occurred — [run] was never invoked. [report] names the exceeding
     * categories and the channel(s) each was reached through.
     */
    data class Refused(val report: RefusalReport) : GuardedOutcome()

    /** Verification failed; neither the gate nor [run] was evaluated. */
    data class VerifyFailed(val errors: List<org.strand.verifier.VerifyError>) : GuardedOutcome()
}

/**
 * Which channel(s) an exceeding EffectCategory was reached through, within a
 * [RefusalReport]. [DIRECT] means the category is in the program's directly-
 * performed root closure; [LATENT] means it is reachable only indirectly (a
 * ToolDef implementation or a higher-order effectful callback argument, per
 * [org.strand.verifier.VerifyResult.Ok.rootLatentClosure]); [BOTH] means the
 * category is reached through both channels. This distinction is the whole
 * point of gating on `totalClosure` rather than hand-rolling a check over
 * only the directly-performed closure — a naive gate that checks only the
 * direct channel would miss a latent-only over-budget category entirely.
 *
 * For the machine-path gates the direct channel is a machine's declared
 * effects row and, for a group, the effects the runtime performs itself to
 * open and drain `source`-bound streams; the latent channel is the latent
 * reach of the machines' transitions and initial states
 * ([org.strand.verifier.MachineClosure], [org.strand.verifier.GroupClosure]).
 */
enum class EffectChannel { DIRECT, LATENT, BOTH }

/**
 * The structured, self-contained reason [StrandRuntime.runGuarded] refused to
 * run a program: which EffectCategory NodeIds exceeded the declared budget,
 * through which channel each was reached, and the full requested-versus-
 * granted category sets so the report can be acted on (or rendered) without
 * re-deriving anything from the program.
 *
 * [exceeding] is keyed by the exceeding EffectCategory NodeId (structural —
 * not prose) to the [EffectChannel] it was reached through. [requested] is
 * the program's full `totalClosure(root)`; [granted] is the budget's granted
 * categories ([org.strand.interpreter.CapabilitySet.grants]'s keys). Both are
 * carried in full (not just the exceeding subset) so a caller can compute
 * `requested - granted` itself, diff two refusals, or render a complete
 * picture without a second call back into [org.strand.verifier.ProgramAnalysis].
 *
 * [unresolvedCategoryNames] (group gate only) names categories the runtime
 * requires by name for which the program holds no EffectCategory node, so no
 * budget over it can grant them: a `source`-bound stream's `Network.Receive`
 * transport effect in a program that declares no such category. Empty for
 * every other refusal.
 */
data class RefusalReport(
    val exceeding: Map<NodeId, EffectChannel>,
    val requested: Set<NodeId>,
    val granted: Set<NodeId>,
    val unresolvedCategoryNames: Set<String> = emptySet(),
)

/**
 * Outcome of [StrandRuntime.runMachineGuarded] (Q-073, machine path). Flat
 * rather than wrapping an inner outcome as [GuardedOutcome.Ran] wraps
 * [RunOutcome]: [StrandRuntime.runMachine] returns a bare [Trace] and has no
 * outcome type of its own to wrap.
 */
sealed class GuardedMachineOutcome {
    /** The machine's bound was within the budget; it was driven over the events. */
    data class Ran(
        val verify: VerifyResult.Ok,
        val schema: SchemaCheckResult,
        val trace: Trace,
    ) : GuardedMachineOutcome()

    /** The machine's bound exceeded the budget; nothing was evaluated. */
    data class Refused(val report: RefusalReport) : GuardedMachineOutcome()

    /** Within budget, but a statically-evaluable value violated its Schema; nothing was evaluated. */
    data class SchemaViolation(
        val verify: VerifyResult.Ok,
        val schema: SchemaCheckResult,
    ) : GuardedMachineOutcome()

    /** Verification failed; neither the gate nor the machine was evaluated. */
    data class VerifyFailed(val errors: List<org.strand.verifier.VerifyError>) : GuardedMachineOutcome()
}

/** Outcome of [StrandRuntime.runGroupGuarded] (Q-073, group path). */
sealed class GuardedGroupOutcome {
    /** The group's bound was within the budget; it was started under the budget and runs on [handle]. */
    data class Started(
        val verify: VerifyResult.Ok,
        val schema: SchemaCheckResult,
        val handle: MachineGroupHandle,
    ) : GuardedGroupOutcome()

    /** The group's bound exceeded the budget; nothing was evaluated and no source was opened. */
    data class Refused(val report: RefusalReport) : GuardedGroupOutcome()

    /** Within budget, but a statically-evaluable value violated its Schema; nothing was evaluated. */
    data class SchemaViolation(
        val verify: VerifyResult.Ok,
        val schema: SchemaCheckResult,
    ) : GuardedGroupOutcome()

    /** Verification failed; neither the gate nor the group was evaluated. */
    data class VerifyFailed(val errors: List<org.strand.verifier.VerifyError>) : GuardedGroupOutcome()
}

/** Convenience: the Ok form of a [VerifyResult], or null. */
fun VerifyResult.asOk(): VerifyResult.Ok? = this as? VerifyResult.Ok
