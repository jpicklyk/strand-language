package org.strand.interpreter

import org.strand.core.Hash
import org.strand.core.NodeId

/**
 * Structured interpretation errors.
 *
 * The interpreter operates only on verified graphs, so most of the failures
 * the verifier catches cannot occur here. The errors below are the residual
 * shapes that remain — defensive checks plus host-runtime failures we choose
 * to surface as language-level errors.
 */
sealed class InterpretError {
    abstract val at: NodeId

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
}

class InterpretException(val error: InterpretError) : RuntimeException(error.toString())
