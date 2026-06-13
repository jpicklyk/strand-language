package org.strand.runtime

import kotlinx.coroutines.CoroutineScope
import org.strand.core.Hash
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.interpreter.Builtins
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
 * hand-replicating the CLI's pipeline and without participating in the
 * [Builtins] singleton mutation protocol directly.
 *
 * See [`proposals/embeddable-runtime.md`](../../../../../../../proposals/embeddable-runtime.md).
 * The two graphs-one-process property the item exists to provide: two
 * [StrandRuntime] instances carrying different [HostPolicy] values run their
 * programs under their own policy with no cross-contamination — one SECURE
 * sandbox and one OPEN, or two different limits, etc. The facade installs the
 * policy's host-routed fields onto the [Builtins] singletons for the duration
 * of each run and restores them in a `finally`, so the install/restore
 * discipline lives in exactly one place ([withInstalled]) rather than
 * scattered across CLI subcommands.
 *
 * **Scope decision (the central tradeoff).** The published surface is
 * value-threaded — policy is a constructor argument and is passed explicitly
 * into the [Interpreter] / [StateMachineRuntime] this facade builds and into
 * their [org.strand.core.EvaluationLimits] arguments. But the fields read by
 * bare name inside builtin lambdas (clock, random, sandbox, …) are still
 * installed onto the [Builtins] singletons around the run rather than threaded
 * into each lambda. This makes the facade correct for *sequential* embedding
 * and for the CLI — and centralizes the install/restore — but the install is
 * process-global for the run's duration, so true *concurrent* multi-tenant
 * isolation in one JVM is gated on the documented follow-up that removes the
 * singleton reads. This facade is that follow-up's structural prerequisite.
 *
 * The program is supplied as the canonical [store] + [root] + [hashToNodeId]
 * (the primitives the verifier / interpreter / runtime already consume), not
 * as a root hash — run-by-hash is Q-058 and out of scope. The optional
 * [resolveTarget] is the Q-043 cross-store resolution callback, threaded into
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
     * The host-routed singletons are installed for the duration of the
     * evaluation and restored afterward (including on a thrown
     * [org.strand.interpreter.InterpretException], which propagates after the
     * restore).
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
        val value = policy.withInstalled(verify.nodeTypes) {
            val interp = Interpreter(
                program.store,
                program.hashToNodeId,
                resolveTarget = program.resolveTarget,
                schemaObligations = schemaObligations,
            )
            interp.eval(program.root, capabilities, policy.limits)
        }
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
    ): Trace = policy.withInstalled(verifierNodeTypes) {
        val runtime = StateMachineRuntime(program.store, program.hashToNodeId, program.resolveTarget)
        runtime.runMachine(machine, events, capabilities, policy.limits)
    }

    /**
     * Spawn a [MachineGroup] under this runtime's policy and return the
     * [MachineGroupHandle]. The host-routed singletons are installed *before*
     * `runGroup` so the group-start path (initial spawns, source openers) sees
     * the policy; the caller must keep the runtime's policy installed for the
     * lifetime of the group (use [withGroupInstalled] to scope it), since the
     * actors evaluate asynchronously after this returns.
     *
     * Unlike the synchronous paths, this method does NOT restore on return —
     * the group is still live. Callers that want the install/restore scoped
     * around the entire group lifecycle use [withGroupInstalled].
     */
    fun runGroup(
        program: ProgramImage,
        group: MachineGroup,
        scope: CoroutineScope,
        verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
    ): MachineGroupHandle {
        // Install without restoring — the group runs asynchronously past this
        // return. The caller scopes the restore via [withGroupInstalled].
        Builtins.install(policy, verifierNodeTypes)
        val runtime = StateMachineRuntime(program.store, program.hashToNodeId, program.resolveTarget)
        return runtime.runGroup(group, scope, policy.limits)
    }

    /**
     * Scope the policy install/restore around an entire group lifecycle.
     * Installs the policy, runs [block] (which spawns and drives the group to
     * completion via [runGroup] + the handle's await/drain), and restores the
     * singletons afterward. The CLI's `group` subcommand uses this to wrap its
     * `runBlocking` body so the singletons are restored once the group halts.
     */
    fun <T> withGroupInstalled(
        verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
        block: () -> T,
    ): T = policy.withInstalled(verifierNodeTypes, block)

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
 * Q-054: the host-routed install/restore extension on [HostPolicy]. Captures
 * the [Builtins] singletons, installs the policy plus the per-program
 * verifier node-types, runs [block], and restores in a `finally`. This is the
 * single place the install/restore discipline lives — it subsumes the CLI's
 * former `withProgramEvaluationContext` helper.
 */
internal fun <T> HostPolicy.withInstalled(
    verifierNodeTypes: Map<NodeId, TypeExpr>?,
    block: () -> T,
): T {
    val prior = Builtins.snapshot()
    Builtins.install(this, verifierNodeTypes)
    try {
        return block()
    } finally {
        Builtins.restore(prior)
    }
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
