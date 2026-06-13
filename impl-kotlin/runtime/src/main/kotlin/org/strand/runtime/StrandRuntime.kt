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

        val schemaObligations: Map<NodeId, TypeExpr.SchemaType> = verify.nodeTypes
            .mapNotNull { (nid, t) -> (t as? TypeExpr.SchemaType)?.let { nid to it } }
            .toMap()
        // Q-054 follow-up: derive a per-invocation HostContext from this
        // runtime's policy and thread it into the interpreter as a value. No
        // singleton install — two runtimes evaluating concurrently each read
        // their own context, so there is nothing to clobber.
        val ctx = org.strand.interpreter.HostContext.fromPolicy(policy, verify.nodeTypes)
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
     * Drive a single verified [machine] over [events] under this runtime's
     * policy. The caller has already verified (or calls [verify]); this method
     * installs the policy and runs the synchronous fold, returning the
     * [Trace]. Mirrors the CLI's `machine` subcommand pipeline minus rendering.
     *
     * [verifierNodeTypes] is the verify result's `nodeTypes` map (the
     * N-044/N-045 LLM schema-projection path reads it); pass
     * `verify(program).asOk()?.nodeTypes` or null.
     */
    fun runMachine(
        program: ProgramImage,
        machine: NodeId,
        events: List<Value>,
        capabilities: CapabilitySet = CapabilitySet.EMPTY,
        verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
    ): Trace {
        val ctx = org.strand.interpreter.HostContext.fromPolicy(policy, verifierNodeTypes)
        val runtime = StateMachineRuntime(program.store, program.hashToNodeId, program.resolveTarget, ctx)
        return runtime.runMachine(machine, events, capabilities, policy.limits)
    }

    /**
     * Spawn a [MachineGroup] under this runtime's policy and return the
     * [MachineGroupHandle]. The policy is projected to a
     * [org.strand.interpreter.HostContext] and bound to every per-actor
     * interpreter, source opener, and feeder the group spawns, so the actors —
     * which evaluate asynchronously after this returns, on `Dispatchers.IO` —
     * read the group's tenant policy with no shared mutable singleton on the
     * path. Two groups can therefore run concurrently under different policies.
     */
    fun runGroup(
        program: ProgramImage,
        group: MachineGroup,
        scope: CoroutineScope,
        verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
    ): MachineGroupHandle {
        // Q-054 follow-up: the policy flows into the runtime as a HostContext
        // value, bound to every per-actor interpreter and feeder. No singleton
        // install — the group runs asynchronously past this return without
        // depending on a mutable process-global being held in place, so two
        // groups can run concurrently under different policies.
        val ctx = org.strand.interpreter.HostContext.fromPolicy(policy, verifierNodeTypes)
        val runtime = StateMachineRuntime(program.store, program.hashToNodeId, program.resolveTarget, ctx)
        return runtime.runGroup(group, scope, policy.limits)
    }

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
    ): Trace {
        val ctx = org.strand.interpreter.HostContext.fromPolicy(policy, verifierNodeTypes)
        val runtime = StateMachineRuntime(program.store, program.hashToNodeId, program.resolveTarget, ctx)
        return runtime.resume(machine, snapshot, additionalEvents, nodeIdToHash, capabilities, policy.limits)
    }

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

/** Convenience: the Ok form of a [VerifyResult], or null. */
fun VerifyResult.asOk(): VerifyResult.Ok? = this as? VerifyResult.Ok
