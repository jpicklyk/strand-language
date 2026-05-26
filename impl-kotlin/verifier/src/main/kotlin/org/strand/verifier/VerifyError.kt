package org.strand.verifier

import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId

/**
 * Structured verification errors.
 *
 * Every error carries the [NodeId] of the offending node (or a closely related
 * one) plus structured reason data. Per the project conventions, errors are
 * sealed-class data types, not strings.
 */
sealed class VerifyError {
    abstract val at: NodeId

    /** A [NodeId] reference does not exist in the store. */
    data class DanglingReference(
        override val at: NodeId,
        val missing: NodeId,
        val fromField: String
    ) : VerifyError()

    /** A node references another node of the wrong category for the position. */
    data class CategoryMismatch(
        override val at: NodeId,
        val field: String,
        val expectedCategory: String,
        val actualCategory: String
    ) : VerifyError()

    /** A VarRef binder is not in scope at the VarRef's position. */
    data class UnboundVariable(
        override val at: NodeId,
        val binder: NodeId
    ) : VerifyError()

    /** A VarRef points to something that is not a valid binding occurrence. */
    data class IllegalBinder(
        override val at: NodeId,
        val binder: NodeId,
        val binderCategory: String
    ) : VerifyError()

    /** The number of value arguments at an Application disagrees with the function's arity. */
    data class ArityMismatch(
        override val at: NodeId,
        val expected: Int,
        val actual: Int
    ) : VerifyError()

    /**
     * The number of type arguments at an Application disagrees with the number
     * of type parameters the function abstracts over.
     */
    data class TypeArgumentArityMismatch(
        override val at: NodeId,
        val expected: Int,
        val actual: Int
    ) : VerifyError()

    /**
     * An argument's type at an Application does not match the substituted
     * parameter type after explicit instantiation. Structural equality only;
     * no subtyping.
     */
    data class ParameterTypeMismatch(
        override val at: NodeId,
        val parameterIndex: Int,
        val expected: TypeExpr,
        val actual: TypeExpr
    ) : VerifyError()

    /** An Application's function expression has a non-function type. */
    data class NotAFunction(
        override val at: NodeId,
        val gotType: TypeExpr
    ) : VerifyError()

    /** A type node is malformed (e.g., ProductType with non-ProductTypeField member). */
    data class MalformedType(
        override val at: NodeId,
        val detail: String
    ) : VerifyError()

    /** A multiplicity constraint is violated (e.g., FunctionType with no result). */
    data class MultiplicityViolation(
        override val at: NodeId,
        val field: String,
        val detail: String
    ) : VerifyError()

    /** An unsupported node type was encountered (a Layer >= 2 node). */
    data class UnsupportedNodeKind(
        override val at: NodeId,
        val kind: String
    ) : VerifyError()

    /**
     * A TypeParameter is referenced from a position that is not inside any
     * TypeAbstraction or ForallType binder that lists it. Lambda alone does
     * not bind TypeParameters; polymorphism is expressed by an enclosing
     * TypeAbstraction.
     */
    data class UnboundTypeParameter(
        override val at: NodeId,
        val typeParameter: NodeId
    ) : VerifyError()

    /**
     * Application of a polymorphic value yielded a residual ForallType after
     * type-argument substitution. Partial instantiation is not supported in
     * Layer 1: every Application of a Forall must supply enough type
     * arguments to reduce the function's type to a plain FunctionType.
     */
    data class PartialTypeInstantiation(
        override val at: NodeId,
        val residual: TypeExpr
    ) : VerifyError()

    // ----- Layer 2 step 2: NodeRef-by-hash -----

    /**
     * A [Node.NodeRef]'s `target` [Hash] is not present in the reverse map
     * passed to the verifier. The reverse map is produced by `Hasher.finalize`
     * and binds every reachable node's hash to its local [NodeId]; a missing
     * entry means the referenced subgraph is not in the local [NodeStore].
     * Cross-store fetches are deferred to a later layer, so this currently
     * indicates an ill-formed graph.
     */
    data class NodeRefTargetNotFound(
        override val at: NodeId,
        val targetHash: Hash
    ) : VerifyError()

    /**
     * A [Node.NodeRef]'s target subgraph is not closed: it contains free
     * [Node.VarRef]s or [Node.TypeParameter] references that resolve to
     * binders outside the target's own subgraph. Per ADR-003's content-
     * addressing invariant, a NodeRef's hash must be context-independent;
     * an open target's hash would depend on the surrounding binder stack,
     * breaking that invariant.
     */
    data class NodeRefTargetMustBeClosed(
        override val at: NodeId,
        val target: NodeId,
        val openReferences: List<NodeId>
    ) : VerifyError()

    // ----- Layer 3: effects and capabilities -----

    /**
     * A Lambda or FunctionType declares an effect edge whose target is not
     * an EffectCategory node.
     */
    data class NonEffectCategoryInEffectList(
        override val at: NodeId,
        val offendingChild: NodeId,
        val actualCategory: String
    ) : VerifyError()

    /**
     * A Lambda's declared effects do not cover the effect closure of its
     * body. [missing] is the set of EffectCategory NodeIds the body needs
     * that the Lambda failed to declare.
     */
    data class UncoveredEffects(
        override val at: NodeId,
        val missing: Set<NodeId>
    ) : VerifyError()

    /**
     * A CapabilityScope narrows the capability context such that the body's
     * effect closure cannot be satisfied. [missing] is the set of
     * EffectCategory NodeIds in the body's closure but not in the
     * CapabilityScope's `capabilities` list.
     */
    data class CapabilityScopeUnsatisfiable(
        override val at: NodeId,
        val missing: Set<NodeId>
    ) : VerifyError()

    /**
     * An EffectDecl's `effectType` does not reference an EffectCategory.
     */
    data class EffectDeclTypeMismatch(
        override val at: NodeId,
        val effectTypeId: NodeId,
        val actualCategory: String
    ) : VerifyError()

    /**
     * An EffectDecl's parameter list does not match the declared parameter
     * arity of its EffectCategory.
     */
    data class EffectDeclArityMismatch(
        override val at: NodeId,
        val expected: Int,
        val actual: Int
    ) : VerifyError()

    /**
     * An EffectDecl's parameter expression does not match the corresponding
     * EffectCategory parameter type.
     */
    data class EffectDeclParameterTypeMismatch(
        override val at: NodeId,
        val parameterIndex: Int,
        val expected: TypeExpr,
        val actual: TypeExpr
    ) : VerifyError()

    /**
     * The set of EffectCategory NodeIds covered by an Application's
     * `effectInstances` (one EffectDecl per category) is not equal to the set
     * of EffectCategory NodeIds declared in the callee's `FunctionType.effects`.
     *
     * [missing] are categories the callee declares but the Application does
     * not supply EffectDecls for; [extra] are categories the Application
     * supplies EffectDecls for that the callee does not declare. Empty
     * effectInstances are permitted only when the callee declares no effects;
     * any non-empty list must cover the declared set exactly.
     */
    data class EffectInstanceCoverageMismatch(
        override val at: NodeId,
        val missing: Set<NodeId>,
        val extra: Set<NodeId>
    ) : VerifyError()

    // ----- Layer 5: Match -----

    /** A Match node with no cases. The algebra requires at least one case. */
    data class EmptyMatch(
        override val at: NodeId
    ) : VerifyError()

    /**
     * A pattern's declared [patternType] does not match the enclosing
     * Match's scrutinee type. Layer 5 step 1 uses strict structural
     * equality; width subtyping is deferred.
     */
    data class PatternTypeMismatch(
        override val at: NodeId,
        val scrutineeType: TypeExpr,
        val patternType: TypeExpr
    ) : VerifyError()

    /**
     * Two case bodies of a Match have incompatible types. The Match's
     * result type is the common case-body type; if the cases diverge, the
     * Match is ill-formed.
     */
    data class MatchCaseBodyTypeDivergence(
        override val at: NodeId,
        val firstType: TypeExpr,
        val divergentType: TypeExpr,
        val caseIndex: Int
    ) : VerifyError()

    /**
     * A Fixpoint's body Lambda does not have the expected type for a
     * fixed-point body — specifically, the body must be a Lambda whose
     * first parameter type equals the Fixpoint's `recursionType` (the
     * recursive call slot), and whose remaining parameters and result
     * match the recursionType's parameters and result.
     */
    data class FixpointBodyShapeMismatch(
        override val at: NodeId,
        val expected: TypeExpr,
        val actual: TypeExpr
    ) : VerifyError()

    // ----- Layer 5 step 3a: composite values -----

    /** A ProductValue lists the same fieldName twice. */
    data class DuplicateProductValueField(
        override val at: NodeId,
        val fieldName: String
    ) : VerifyError()

    /**
     * A ProductValue or ProductFieldGet references a fieldName that is not
     * declared by the relevant ProductType.
     */
    data class UnknownProductValueField(
        override val at: NodeId,
        val fieldName: String,
        val declared: Set<String>
    ) : VerifyError()

    /** A ProductValue does not cover every field declared by its ProductType. */
    data class MissingProductValueFields(
        override val at: NodeId,
        val missing: Set<String>
    ) : VerifyError()

    /**
     * A ProductFieldValue's value expression has a type that does not
     * match the corresponding ProductTypeField's declared type.
     */
    data class ProductFieldValueTypeMismatch(
        override val at: NodeId,
        val fieldName: String,
        val expected: TypeExpr,
        val actual: TypeExpr
    ) : VerifyError()

    // ----- Layer 5 step 3b: sum values + constructor patterns -----

    /**
     * A SumValue or ConstructorPattern references a case name that is
     * not declared by the relevant SumType.
     */
    data class UnknownSumCase(
        override val at: NodeId,
        val caseName: String,
        val declared: Set<String>
    ) : VerifyError()

    /**
     * A SumValue or ConstructorPattern omitted a payload, but the
     * corresponding SumTypeCase declared one.
     */
    data class MissingSumPayload(
        override val at: NodeId,
        val caseName: String,
        val expectedType: TypeExpr
    ) : VerifyError()

    /**
     * A SumValue or ConstructorPattern supplied a payload, but the
     * corresponding SumTypeCase is nullary (no caseType declared).
     */
    data class UnexpectedSumPayload(
        override val at: NodeId,
        val caseName: String
    ) : VerifyError()

    /**
     * A SumValue's payload expression (or a ConstructorPattern's payload
     * sub-pattern) has a type that does not match the corresponding
     * SumTypeCase's caseType.
     */
    data class SumPayloadTypeMismatch(
        override val at: NodeId,
        val caseName: String,
        val expected: TypeExpr,
        val actual: TypeExpr
    ) : VerifyError()

    // ----- Recursive types (N-041, N-042) -----

    /**
     * A `RecursiveSelf` node was reached in a position outside any
     * enclosing `RecursiveType` binder. Analogous to
     * `UnboundTypeParameter` for `TypeParameter`.
     *
     * The [hint] field carries the most common cause and resolution as
     * actionable prose. Since data-class `toString` includes property
     * values, the hint surfaces automatically in the CLI's prose
     * feedback the agent sees on retry.
     */
    data class UnboundRecursiveSelf(
        override val at: NodeId,
        val hint: String = COMMON_HINT,
    ) : VerifyError() {
        companion object {
            const val COMMON_HINT: String =
                "RecursiveSelf reached at a resolution site outside any " +
                "RecursiveType binder. The most common cause: a ProductType " +
                "or SumType that contains RecursiveSelf (declared inside an " +
                "RT body's reference chain) is being used at a top-level " +
                "construction site such as a ProductValue.ofType, " +
                "SumValue.ofType, or ParameterDecl.paramType. The Strand " +
                "convention for recursive types is the inner/outer split " +
                "(see corpus program 31, recursive-list-head): declare " +
                "TWO ProductType nodes — an 'inner' product whose recursive " +
                "field references RecursiveSelf (used by the SumTypeCase " +
                "INSIDE the RecursiveType body), and an 'outer' product " +
                "whose same field references the RecursiveType node itself " +
                "(used by ProductValue / SumValue / function parameter types " +
                "AT TOP-LEVEL value-construction sites). Both products " +
                "compare equirecursively-equal under the verifier and the " +
                "canonical encoder, so the program's hash is unaffected by " +
                "which one is used where, but the inner form is only valid " +
                "inside an RT walk and surfaces as this error when used " +
                "outside one."
        }
    }

    /**
     * A `RecursiveType`'s body is not contractive: it is, or reduces to,
     * a `RecursiveSelf` reference without traversing any type constructor.
     * The textbook example is `μX. X`, which would unfold to itself
     * indefinitely. Strand requires that every path from the binder to a
     * `RecursiveSelf` traverse at least one type constructor (Function,
     * Product, Sum, or a nested Recursive).
     */
    data class NonContractiveRecursiveType(
        override val at: NodeId
    ) : VerifyError()

    // ----- Effect handlers (N-043) -----

    /**
     * A `Handler`'s `handle` expression has a function type whose
     * parameter or result types do not match an intercepted Application
     * within the body. Every intercepted call (an Application whose
     * callee's `FunctionType.effects` contains the Handler's `intercept`)
     * must have value-argument types and result type structurally equal
     * to the handler's parameters and result.
     *
     * The first such mismatch is reported and verification aborts; later
     * mismatches inside the same Handler are not surfaced. Re-run after
     * the first fix to find them.
     */
    data class HandlerSignatureMismatch(
        override val at: NodeId,
        val atCall: NodeId,
        val expected: TypeExpr,
        val actual: TypeExpr
    ) : VerifyError()

    /**
     * A `Handler`'s `handle` expression has a `Forall` type rather than
     * a monomorphic `Fun` type. Step 3a handlers must be monomorphic so
     * that matching against intercepted calls is deterministic; a
     * polymorphic handler would have no canonical instantiation at
     * Handler-entry without an explicit type-argument list (which the
     * Handler node does not carry).
     */
    data class HandlerOverPolymorphicHandle(
        override val at: NodeId,
        val residual: TypeExpr
    ) : VerifyError()

    /**
     * A `Handler`'s `handle` expression has a non-function type. The
     * handler must be a callable that the interpreter can dispatch at
     * each intercepted call site; non-function values cannot serve.
     */
    data class HandlerNotAFunction(
        override val at: NodeId,
        val gotType: TypeExpr
    ) : VerifyError()

    // ----- State machines (N-027..N-029) -----

    /**
     * An EventStream's [eventType] field does not resolve to a Type node.
     */
    data class MalformedEventStream(
        override val at: NodeId,
        val detail: String,
    ) : VerifyError()

    /**
     * An EventStream's optional `bufferSize` or `overflowPolicy` content
     * field is malformed. Layer 6 step 3 slice 3.1 specifies a small set of
     * well-formedness rules over the new fields:
     *
     *  * `bufferSize`, if set, must be > 0 (a zero-capacity channel cannot
     *    hold any pending event).
     *  * `overflowPolicy = Sample(n)` requires n > 0 (the JSON ingest and
     *    [org.strand.core.OverflowPolicy.Sample]'s `init` block both reject
     *    n <= 0, so this case typically never reaches the verifier — present
     *    for defense-in-depth).
     */
    data class MalformedOverflowPolicy(
        override val at: NodeId,
        val detail: String,
    ) : VerifyError()

    /**
     * A StateMachine declares zero input streams. Every state machine must
     * have at least one input stream (the runtime needs *something* to
     * drive transitions). Multi-input machines are well-formed as of
     * Layer 6 step 2; the verifier synthesizes a tagged InputEvent sum
     * for the transition function's Event parameter — see
     * [Verifier.synthesizeInputEventSum].
     */
    data class StateMachineRequiresInputStream(
        override val at: NodeId
    ) : VerifyError()

    /**
     * A StateMachine's [transitionFn] does not resolve to a Lambda.
     * Fixpoint-wrapped transition functions remain step-3 work.
     */
    data class StateMachineTransitionFnNotLambda(
        override val at: NodeId,
        val actualCategory: String,
    ) : VerifyError()

    /**
     * A StateMachine's transition function does not have the expected shape
     * `(State, Event) -> (State, Outputs)`. The State type is taken from
     * the inferred `initialState` type; the Event type is taken from the
     * input streams (single input: `inputStreams[0].eventType`; multi-input:
     * synthesized `stream_0(T_0) | ... | stream_{n-1}(T_{n-1})` sum); the
     * Outputs field accepts either the fixed-arity OutputBatch
     * (`{output_i: Option<outputStreams[i].eventType>}` product) or the
     * recursive tagged list (`μ. Cons({head, tail}) | Nil` over the
     * `output_i(T_i)` TaggedOutput sum). The error reports whichever of
     * the two expected shapes is closer to the actual.
     */
    data class StateMachineTransitionFnShapeMismatch(
        override val at: NodeId,
        val expected: TypeExpr,
        val actual: TypeExpr,
    ) : VerifyError()

    /**
     * A StateMachine's [initialState] expression has a type that does not
     * match the State half of the transition function's signature.
     */
    data class StateMachineInitialStateTypeMismatch(
        override val at: NodeId,
        val expected: TypeExpr,
        val actual: TypeExpr,
    ) : VerifyError()

    /**
     * A StateMachine's declared effects do not cover the transition
     * function's effect closure. Separate from
     * [StateMachineMissingImplicitEffect] which covers the runtime-
     * implicit Send/Receive/Spawn/Terminate effects: this one is about
     * the user's transition function's own declared closure.
     */
    data class StateMachineEffectCoverageViolation(
        override val at: NodeId,
        val missing: Set<NodeId>,
    ) : VerifyError()

    /**
     * A StateMachine's declared `effects` list is missing one or more
     * runtime-implicit effects the machine WILL perform — at minimum
     * `StateMachine.Receive` (input streams are mandatory), and
     * `StateMachine.Send` whenever `outputStreams.isNotEmpty()`. The
     * runtime performs these effects on the user's behalf at each
     * channel boundary; declaring them in the machine's effects list
     * is how the user acknowledges that fact.
     *
     * Layer 6 step 3 slice 3.5 introduces this check via a small
     * `WellKnownEffect` registry that the verifier consults by
     * `EffectCategory.categoryName`. Adding an EffectCategory node
     * named `"StateMachine.Receive"` (or `"StateMachine.Send"`) and
     * listing it in the machine's `effects` satisfies the check.
     */
    data class StateMachineMissingImplicitEffect(
        override val at: NodeId,
        val missing: Set<String>,
    ) : VerifyError()

    /**
     * An output stream's eventType does not match the slot at position
     * [streamIndex] of the transition function's OutputBatch (which must be
     * `Option<outputStreams[streamIndex].eventType>`).
     */
    data class OutputStreamEventTypeMismatch(
        override val at: NodeId,
        val streamIndex: Int,
        val expected: TypeExpr,
        val actual: TypeExpr,
    ) : VerifyError()

    /**
     * A Transition's optional [guard] expression has a type that is not
     * `PrimitiveType(Bool)`.
     */
    data class TransitionGuardNotBoolean(
        override val at: NodeId,
        val actualType: TypeExpr,
    ) : VerifyError()

    /**
     * A Transition node appears in an expression position. Transitions are
     * structural pieces of a transition-function host context, not standalone
     * expressions. Step 1 ships parsing but no host that uses Transition
     * appears in any corpus program, so any Transition reached via `infer()`
     * is ill-formed.
     */
    data class TransitionStandalone(
        override val at: NodeId
    ) : VerifyError()

    // ----- Layer 7 step 1: Schema and Invariant (N-032, N-033) -----

    /**
     * A statically-known value typed against a Schema failed one of the
     * Schema's invariants. [at] is the node introducing the schema claim
     * (typically an Application argument position or a Let body whose value
     * is the value being checked). [schema] and [invariant] identify the
     * failed predicate; [value] is the verified-evaluated runtime
     * [org.strand.interpreter.Value] that triggered the failure, captured
     * as a string for error reporting (the verifier itself does not
     * depend on the interpreter's Value type — the SchemaChecker formats
     * it before reporting).
     */
    data class SchemaInvariantViolation(
        override val at: NodeId,
        val schema: NodeId,
        val invariant: NodeId,
        val valueDescription: String
    ) : VerifyError()

    /**
     * A value's type structurally disagrees with the schema's declared
     * `valueType`. This specializes the diagnostic produced by the
     * standard type-check when the actual type does not match a
     * SchemaType-wrapped expected type's underlying valueType.
     */
    data class SchemaValueTypeMismatch(
        override val at: NodeId,
        val schema: NodeId,
        val expectedValueType: TypeExpr,
        val actualType: TypeExpr
    ) : VerifyError()

    /**
     * An Invariant's `body` does not have function type
     * `(schema.valueType) -> Bool`. Caught at Schema construction time when
     * the verifier reaches the Schema in a type position and validates its
     * invariants list.
     */
    data class SchemaInvariantBodyTypeMismatch(
        override val at: NodeId,
        val schema: NodeId,
        val invariant: NodeId,
        val expectedType: TypeExpr,
        val actualType: TypeExpr
    ) : VerifyError()

    /**
     * An Invariant's body is a ForeignNode or a Lambda whose effects list
     * is non-empty. Step 1 ships pure-expression invariants only; this
     * rule is loosened in step 2 when the security-model trust model is
     * extended to checker bindings.
     */
    data class SchemaInvariantBodyMustBePure(
        override val at: NodeId,
        val invariant: NodeId
    ) : VerifyError()

    /**
     * An Invariant's body has type `Forall(...)`. Invariants must be
     * monomorphic functions over the schema's specific `valueType`;
     * polymorphic invariants would need a separate type-application
     * protocol that step 1 does not provide.
     */
    data class SchemaInvariantBodyMustBeMonomorphic(
        override val at: NodeId,
        val invariant: NodeId,
        val residual: TypeExpr
    ) : VerifyError()

    /**
     * An Invariant's `targetSchema` does not match the Schema that lists
     * it in its `invariants` edge. Defensive — ensures graph topology is
     * consistent. Construction-time check.
     */
    data class InvariantTargetMismatch(
        override val at: NodeId,
        val invariant: NodeId,
        val targetSchema: NodeId,
        val declaringSchema: NodeId
    ) : VerifyError()

    /**
     * Informational, non-fatal: a value whose static evaluability cannot
     * be determined at verify time was typed against a Schema. The
     * invariant checks are deferred — step 1 only evaluates invariants on
     * statically-known values (literals, ProductValues / SumValues whose
     * sub-values are statically known, Let chains terminating in static
     * values). Non-static cases (function parameters, function results,
     * anything depending on a runtime computation) emit this diagnostic
     * via [VerifyResult.Ok.deferredChecks]. The default disposition is to
     * surface but not fail; a deployment may want to reject all graphs
     * with deferred checks via its own policy layer.
     */
    data class SchemaInvariantDeferred(
        override val at: NodeId,
        val schema: NodeId,
        val reason: String
    ) : VerifyError()

    // ----- Q-037 Phase 1: agent-native LLM ForeignNodes -----

    /**
     * A `ToolDef.parameterSchema`'s `valueType` cannot be projected to
     * JSON Schema for the provider boundary (proposal § 3.8.1). The
     * provider-side constrained-decoding requires the parameter type
     * to be in the irreducible JSON-Schema-expressible subset:
     * `FunctionType`, `ForallType`, and unbound `TypeParameter`
     * references are rejected because JSON Schema has no representation
     * for callbacks, parametric polymorphism, or free type variables.
     *
     * [at] is the call-site NodeId (the LLM.Generate Application);
     * [toolDefId] is reserved for a future static toolset projection
     * to identify which ToolDef contained the offending type — when
     * tools are computed at runtime (a List value), the verifier
     * cannot pinpoint a specific source node and [toolDefId] equals
     * [at] (the Application itself). [reason] mirrors
     * [org.strand.interpreter.JsonSchemaProjection.Result.Reason].
     */
    data class ToolParamTypeUnsupported(
        override val at: NodeId,
        val toolDefId: NodeId,
        val rejectedType: TypeExpr,
        val reason: String,
    ) : VerifyError()
}

/** Outcome of verification: either a successful inference or one or more structured errors. */
sealed class VerifyResult {
    /**
     * Successful verification. [rootType] is the inferred type of the root
     * node; [nodeTypes] maps every reached node to its inferred type;
     * [deferredChecks] lists informational diagnostics for schema-typed
     * positions whose value was not statically known at verify time. An
     * empty [deferredChecks] is the typical case for graphs that do not
     * use Schema (or that only use Schema with statically-known values).
     */
    data class Ok(
        val rootType: TypeExpr,
        val nodeTypes: Map<NodeId, TypeExpr>,
        val deferredChecks: List<VerifyError.SchemaInvariantDeferred> = emptyList(),
    ) : VerifyResult()
    data class Failed(val errors: List<VerifyError>) : VerifyResult()
}

/** Convenience: human-friendly category name for diagnostics. */
internal fun categoryName(node: Node?): String = when (node) {
    null -> "(missing)"
    is Node.IntLit -> "IntLit"
    is Node.FloatLit -> "FloatLit"
    is Node.StringLit -> "StringLit"
    is Node.BoolLit -> "BoolLit"
    Node.UnitLit -> "UnitLit"
    is Node.BytesLit -> "BytesLit"
    is Node.PrimitiveType -> "PrimitiveType"
    is Node.ProductType -> "ProductType"
    is Node.ProductTypeField -> "ProductTypeField"
    is Node.SumType -> "SumType"
    is Node.SumTypeCase -> "SumTypeCase"
    is Node.FunctionType -> "FunctionType"
    is Node.TypeParameter -> "TypeParameter"
    is Node.ForallType -> "ForallType"
    is Node.Lambda -> "Lambda"
    is Node.TypeAbstraction -> "TypeAbstraction"
    is Node.ParameterDecl -> "ParameterDecl"
    is Node.Application -> "Application"
    is Node.Let -> "Let"
    is Node.VarRef -> "VarRef"
    is Node.NodeRef -> "NodeRef"
    is Node.EffectCategory -> "EffectCategory"
    is Node.EffectDecl -> "EffectDecl"
    is Node.CapabilityScope -> "CapabilityScope"
    is Node.ForeignNode -> "ForeignNode"
    is Node.Match -> "Match"
    is Node.MatchCase -> "MatchCase"
    is Node.Pattern.LiteralPattern -> "LiteralPattern"
    is Node.Pattern.VariablePattern -> "VariablePattern"
    is Node.Pattern.WildcardPattern -> "WildcardPattern"
    is Node.Pattern.ConstructorPattern -> "ConstructorPattern"
    is Node.Fixpoint -> "Fixpoint"
    is Node.ProductValue -> "ProductValue"
    is Node.ProductFieldValue -> "ProductFieldValue"
    is Node.ProductFieldGet -> "ProductFieldGet"
    is Node.SumValue -> "SumValue"
    is Node.RecursiveType -> "RecursiveType"
    is Node.RecursiveSelf -> "RecursiveSelf"
    is Node.Handler -> "Handler"
    is Node.StateMachine -> "StateMachine"
    is Node.EventStream -> "EventStream"
    is Node.Transition -> "Transition"
    is Node.Schema -> "Schema"
    is Node.Invariant -> "Invariant"
}
