package org.strand.interpreter

import org.strand.core.ExhaustionKind
import org.strand.core.Hash
import org.strand.core.NodeId

/**
 * Structured interpretation errors.
 *
 * The interpreter operates only on verified graphs, so most of the failures
 * the verifier catches cannot occur here. The errors below are the residual
 * shapes that remain — defensive checks plus host-runtime failures we choose
 * to surface as language-level errors.
 *
 * The [at] field is nullable on a single variant ([ResourceExhaustion]) because
 * the bytecode VM does not currently carry source-mapping from opcodes back to
 * NodeIds; every other variant carries a non-null NodeId attributing the error
 * to a specific graph site.
 */
sealed class InterpretError {
    abstract val at: NodeId?

    /**
     * Whether an enclosing `Attempt` node (N-047, Q-048) may observe this
     * error as an `Err({kind, detail})` value rather than letting it
     * terminate evaluation. Implements the catchable/uncatchable taxonomy of
     * `proposals/error-recovery.md` § 4.3.
     *
     * The organizing principle: **catchable errors are failures of the
     * world; uncatchable errors are defects of the program or decisions of
     * the host.** Only [IoFailure] (every transport/filesystem/process/
     * provider failure — the operation was authorized and attempted, the
     * world declined) and [SchemaInvariantViolation] (dynamic data failing
     * validation) are catchable. Every other variant — infrastructure
     * defects, program defects, host-policy stops (capability, refinement,
     * sandbox, budget) — is terminal regardless of any surrounding Attempt.
     *
     * This is an `abstract val` on the sealed hierarchy (not a `when` table)
     * so a future InterpretError variant cannot be added without explicitly
     * taking a position on its catchability.
     */
    abstract val isCatchable: Boolean

    /** A reference resolved to a missing node at runtime. */
    data class MissingNode(override val at: NodeId, val missing: NodeId) : InterpretError() {
        override val isCatchable: Boolean get() = false
    }

    /** A non-closure value appeared in function position. */
    data class NotCallable(override val at: NodeId, val gotKind: String) : InterpretError() {
        override val isCatchable: Boolean get() = false
    }

    /** Arity mismatch at call time. (Unreachable on verified graphs but defensive.) */
    data class ArityMismatch(override val at: NodeId, val expected: Int, val actual: Int) : InterpretError() {
        override val isCatchable: Boolean get() = false
    }

    /** A VarRef pointed to a binder not present in the runtime environment. */
    data class UnboundAtRuntime(override val at: NodeId, val binder: NodeId) : InterpretError() {
        override val isCatchable: Boolean get() = false
    }

    /**
     * An Application of a Lambda or ForeignNode whose declared effects are
     * not all present in the calling capability context. [missing] is the
     * EffectCategory NodeIds the callee demands but the context does not
     * grant.
     *
     * Distinguished from [RefinementViolation]: a `CapabilityViolation`
     * means the category is entirely absent from the context (the policy
     * author granted nothing for this category); a `RefinementViolation`
     * means the category is present but no pattern covers the concrete
     * arguments (the policy granted *something* but not for these
     * resources). The split tells the policy author which kind of denial
     * happened.
     *
     * Q-064: [report] is the structured denial outcome for the
     * orchestrating principal — see [DenialReport]. [at] follows the
     * [ResourceExhaustion] precedent: non-null when the tree-walking
     * interpreter raises, null when the bytecode VM raises (opcodes carry
     * no NodeIds in slice 1; the VM's category-only check is the same
     * denial decision, unified at this InterpretError boundary).
     */
    data class CapabilityViolation(
        override val at: NodeId?,
        val missing: Set<NodeId>,
        val report: DenialReport,
    ) : InterpretError() {
        // Host policy: observable denial would turn the capability context
        // into a probeable oracle (proposals/error-recovery.md § 4.3).
        // Q-064's report surfaces at the host boundary only.
        override val isCatchable: Boolean get() = false
    }

    /**
     * An Application's call-site requirement (the evaluated EffectDecl
     * parameter values) was not covered by any granted [CapabilityPattern]
     * for the requested [category]. The category itself was present in the
     * context (otherwise the runtime raises [CapabilityViolation]).
     *
     * The classic confused-deputy test case: a logger holds
     * `Filesystem.Write{path: "/var/log/app.log"}`; a caller passes
     * `path: "/etc/passwd"` through the logger; the runtime denies the
     * write at the call site because no granted pattern covers the
     * requirement.
     *
     * Q-064: [report] is the structured denial outcome for the
     * orchestrating principal — the rendered, scrubbed view of
     * [requirement] versus [available]. See [DenialReport].
     */
    data class RefinementViolation(
        override val at: NodeId,
        val category: NodeId,
        val requirement: List<Value>,
        val available: List<CapabilityPattern>,
        val report: DenialReport,
    ) : InterpretError() {
        // Host policy: the refinement lattice is the finer-grained half of
        // the capability-denial surface (proposals/error-recovery.md § 4.3).
        override val isCatchable: Boolean get() = false
    }

    /**
     * A ForeignNode's `target` identifier was not registered in the
     * [Builtins] table. Either the binding is for a sandbox/runtime not yet
     * supported, or the agent generated a target name with no implementation.
     */
    data class UnknownForeignTarget(
        override val at: NodeId,
        val target: String
    ) : InterpretError() {
        // Deployment defect: the binding is host configuration; the agent
        // regenerates against a supported target (proposals/error-recovery.md
        // § 4.3).
        override val isCatchable: Boolean get() = false
    }

    /**
     * A Match evaluation exhausted all cases without any pattern matching
     * the scrutinee's value. Layer 5 step 1 does not enforce exhaustiveness
     * at verify time; future layers may add a coverage analysis that turns
     * this into a verify-time error.
     */
    data class NoMatchingCase(
        override val at: NodeId
    ) : InterpretError() {
        // Program defect (latent type error): the recovery path is the agent
        // adding the missing case, not the program absorbing it
        // (proposals/error-recovery.md § 4.3).
        override val isCatchable: Boolean get() = false
    }

    /**
     * A [org.strand.core.Node.NodeRef]'s target [Hash] is not in the local
     * `hashToNodeId` reverse map. Cross-store fetches are deferred to a
     * later layer; until then, every NodeRef target must be present in the
     * same store as the NodeRef itself.
     */
    data class NodeRefTargetNotInStore(
        override val at: NodeId,
        val targetHash: Hash
    ) : InterpretError() {
        // Infrastructure: cross-store resolution failure is Q-016/Q-043
        // territory; recoverable remote-fetch wants its own retry mechanism,
        // not a silent reclassification here (proposals/error-recovery.md
        // § 4.3).
        override val isCatchable: Boolean get() = false
    }

    /**
     * An IO builtin (Layer 4 step 2 — Filesystem, Network, Process,
     * Time) failed. [kind] is a short tag like "filesystem-read",
     * "network-connect", "resource-not-found"; [detail] is the
     * underlying error message (typically a JVM IOException's text).
     *
     * Per the Layer 4 step 2 design call, IO failures surface as
     * exceptions rather than Result-typed values — matches the
     * existing pattern for Int.Div by zero, NoMatchingCase, etc.
     * Future work can introduce a blessed Result<T, IoError> sum
     * type with explicit error handling.
     */
    data class IoFailure(
        override val at: NodeId,
        val kind: String,
        val detail: String,
    ) : InterpretError() {
        // Failure of the world: the prime catchable class. The operation was
        // authorized and attempted; the world declined
        // (proposals/error-recovery.md § 4.3).
        override val isCatchable: Boolean get() = true
    }

    /**
     * Q-040: a host-configured resource cap was exceeded at evaluation
     * time. The interpreter and the bytecode VM both raise this shape;
     * the runtime maps to it from `VmResourceExhaustion` at the public
     * boundary so host code sees a single error variant regardless of
     * which backend evaluated.
     *
     * [kind] discriminates the dimension. [at] is non-null when the
     * tree-walking interpreter raises (it tracks the current NodeId in
     * its dispatch loop); it is null when the bytecode VM raises
     * because opcodes do not carry NodeIds in slice 1 (a Q-017 step 2
     * source-mapping follow-up may fill this in).
     *
     * [current] is the observed counter value at the moment of
     * exhaustion; [limit] is the configured cap. Both are encoded as
     * `Long` so step / allocation / wall-clock / stack-depth values
     * share a uniform shape (the stack-depth value upcasts from Int).
     */
    data class ResourceExhaustion(
        override val at: NodeId?,
        val kind: ExhaustionKind,
        val current: Long,
        val limit: Long,
    ) : InterpretError() {
        // Host policy (budget): the counters are evaluation-global, so a
        // catch handler would itself run with the budget already exhausted —
        // allowing the catch would let a program convert the host's hard stop
        // into a soft, absorbable event (proposals/error-recovery.md § 4.3).
        override val isCatchable: Boolean get() = false
    }

    /**
     * Q-041: a `Fs.*`, `Net.Connect`, or `Http.Request` builtin saw an
     * argument that the active [SandboxPolicy] forbids. Distinct from
     * [CapabilityViolation] (the category itself was not granted) and
     * [RefinementViolation] (the granted category did not cover the
     * requirement). Sandbox violations indicate that the capability
     * permits the operation but the runtime refuses because the
     * argument names an out-of-policy resource (path traversal,
     * cloud-metadata IP, etc.).
     *
     * [kind] discriminates the gate that rejected the argument; [detail]
     * is a human-readable description of what was tried. The detail
     * string has been run through [CredentialScrubber.scrub] at
     * runtime-exception construction so it cannot leak registered
     * credentials even if a future policy interpolates them into a
     * path or hostname.
     */
    data class SandboxViolation(
        override val at: NodeId,
        val kind: SandboxViolationKind,
        val detail: String,
    ) : InterpretError() {
        // Host policy (Q-041 boundary): a program that can catch sandbox
        // denials can map the workspace boundary or blocked-IP ranges
        // silently. The "learn what was rejected and retry within policy"
        // goal is feedback to the agent across generations via the host's
        // error report, not observability within an evaluation
        // (proposals/error-recovery.md § 4.3).
        override val isCatchable: Boolean get() = false
    }

    /**
     * A builtin's internal contract was violated — for example, division by
     * zero in [org.strand.interpreter.Builtins] `Int.Div`, `Int.Mod`, or
     * `Math.Mod`. This is a program defect: the agent is responsible for
     * guarding the divisor before calling the builtin; the runtime surfaces
     * the raw `require(...)` failure as a structured, attributable error
     * rather than a bare JVM `IllegalArgumentException`.
     *
     * [at] follows the [ResourceExhaustion] precedent: non-nullable when the
     * tree-walking interpreter raises (it tracks the current NodeId) and
     * nullable when the bytecode VM raises (opcodes do not carry NodeIds in
     * slice 1, matching Q-040's Q-017 source-mapping deferral). The sealed
     * base declares `at: NodeId?` so this variant uses `NodeId?` throughout
     * for VM parity.
     *
     * Deliberately NOT catchable by [org.strand.core.Node.Attempt]: the
     * program should never reach a zero divisor if it is correct; catching
     * a contract failure would turn a defect into a silently absorbable
     * event, which is contrary to the catchable/uncatchable taxonomy's
     * organizing principle (proposals/error-recovery.md § 4.3).
     */
    data class BuiltinContractViolation(
        override val at: NodeId?,
        val target: String,
        val detail: String,
    ) : InterpretError() {
        // Program defect: the agent guards the divisor; deliberately NOT
        // catchable by Attempt (proposals/error-recovery.md § 4.3).
        override val isCatchable: Boolean get() = false
    }

    /**
     * Q-047 (Layer 7 step 2): a schema-typed value computed at runtime
     * failed one of its schema's invariants. This is the runtime analogue
     * of [org.strand.verifier.VerifyError.SchemaInvariantViolation]: the
     * verifier's [org.strand.schema.SchemaChecker] catches violations on
     * statically-known values at verify time, but defers values it cannot
     * fold statically (function parameters, Application results, Match
     * branches, foreign-call returns) as `SchemaInvariantDeferred`. This
     * variant enforces those deferred obligations when the value
     * materialises: at every value-flow site the verifier re-recorded
     * with a [org.strand.verifier.TypeExpr.SchemaType], the interpreter
     * evaluates each pure-expression invariant against the produced value
     * and raises this on a `false` verdict.
     *
     * The combined discipline is sound and complete-at-runtime: verify
     * time rejects statically-provable violations early; runtime rejects
     * the rest. A schema violation is never silently emitted.
     *
     * [at] is the obligation site (the value-flow position the verifier
     * typed as the schema); [schema] is the Schema node; [invariant] is
     * the specific Invariant that failed; [valueDescription] is the
     * offending value's `toString` for diagnostics.
     */
    data class SchemaInvariantViolation(
        override val at: NodeId?,
        val schema: NodeId,
        val invariant: NodeId,
        val valueDescription: String,
    ) : InterpretError() {
        // Failure of the world (data): dynamic data failing validation is the
        // normal case in agent workloads (reject one record, continue). The
        // Err payload excludes the offending value (§ 4.2), so the
        // never-silently-emitted schema discipline is not subverted
        // (proposals/error-recovery.md § 4.3).
        override val isCatchable: Boolean get() = true
    }
}

class InterpretException(val error: InterpretError) : RuntimeException(error.toString())
