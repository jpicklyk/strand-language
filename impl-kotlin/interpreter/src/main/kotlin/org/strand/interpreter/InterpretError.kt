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

    /** A reference resolved to a missing node at runtime. */
    data class MissingNode(override val at: NodeId, val missing: NodeId) : InterpretError()

    /** A non-closure value appeared in function position. */
    data class NotCallable(override val at: NodeId, val gotKind: String) : InterpretError()

    /** Arity mismatch at call time. (Unreachable on verified graphs but defensive.) */
    data class ArityMismatch(override val at: NodeId, val expected: Int, val actual: Int) : InterpretError()

    /** A VarRef pointed to a binder not present in the runtime environment. */
    data class UnboundAtRuntime(override val at: NodeId, val binder: NodeId) : InterpretError()

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
     */
    data class CapabilityViolation(
        override val at: NodeId,
        val missing: Set<NodeId>
    ) : InterpretError()

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
     */
    data class RefinementViolation(
        override val at: NodeId,
        val category: NodeId,
        val requirement: List<Value>,
        val available: List<CapabilityPattern>,
    ) : InterpretError()

    /**
     * A ForeignNode's `target` identifier was not registered in the
     * [Builtins] table. Either the binding is for a sandbox/runtime not yet
     * supported, or the agent generated a target name with no implementation.
     */
    data class UnknownForeignTarget(
        override val at: NodeId,
        val target: String
    ) : InterpretError()

    /**
     * A Match evaluation exhausted all cases without any pattern matching
     * the scrutinee's value. Layer 5 step 1 does not enforce exhaustiveness
     * at verify time; future layers may add a coverage analysis that turns
     * this into a verify-time error.
     */
    data class NoMatchingCase(
        override val at: NodeId
    ) : InterpretError()

    /**
     * A [org.strand.core.Node.NodeRef]'s target [Hash] is not in the local
     * `hashToNodeId` reverse map. Cross-store fetches are deferred to a
     * later layer; until then, every NodeRef target must be present in the
     * same store as the NodeRef itself.
     */
    data class NodeRefTargetNotInStore(
        override val at: NodeId,
        val targetHash: Hash
    ) : InterpretError()

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
    ) : InterpretError()

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
    ) : InterpretError()

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
    ) : InterpretError()
}

class InterpretException(val error: InterpretError) : RuntimeException(error.toString())
