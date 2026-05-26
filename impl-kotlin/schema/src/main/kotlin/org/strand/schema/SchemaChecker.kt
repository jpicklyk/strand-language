package org.strand.schema

import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.TypeExpr
import org.strand.verifier.VerifyError
import org.strand.verifier.VerifyResult

/**
 * Layer 7 step 1 invariant-evaluation phase.
 *
 * Runs after the verifier's type-checking pass succeeds. For every node
 * the verifier recorded with a [TypeExpr.SchemaType] type, the
 * SchemaChecker attempts to evaluate the value statically (per the
 * recursive "statically known" definition in § 5 of the proposal):
 *
 *  - Literals (IntLit, FloatLit, StringLit, BoolLit, UnitLit, BytesLit)
 *    are statically known.
 *  - A ProductValue is statically known iff every field's value is
 *    statically known.
 *  - A SumValue is statically known iff its payload (if present) is
 *    statically known.
 *  - A Let is statically known iff its body is statically known after
 *    binding its value to its identifier.
 *  - A VarRef is statically known iff its binder is a Let with a
 *    statically-known value.
 *  - All other shapes (Lambda, Application, ForeignNode call, Fixpoint,
 *    Match, Handler, CapabilityScope, function parameters reached via
 *    VarRef into ParameterDecl, etc.) are non-static.
 *
 * For statically-known values, each invariant body is evaluated against
 * the value via [Interpreter.applyCallable]; if any returns
 * [Value.BoolV] `false`, the verifier emits a
 * [VerifyError.SchemaInvariantViolation] and the graph is rejected.
 *
 * For non-static values, the checker emits a
 * [VerifyError.SchemaInvariantDeferred] informational diagnostic. The
 * default policy is to surface but not fail (the graph still verifies).
 *
 * The capability context for invariant evaluation is intentionally
 * empty — pure-expression invariants are by construction effect-free
 * (the verifier rejects ForeignNode-typed bodies and Lambdas with
 * declared effects).
 */
class SchemaChecker(
    private val store: NodeStore,
    private val hashToNodeId: Map<Hash, NodeId> = emptyMap(),
    private val verifyResult: VerifyResult.Ok,
    /**
     * Optional override for invariant-body dispatch. Defaults to the
     * tree-walking interpreter (the production behavior). The `:vm`
     * module's test suite uses a VM-based evaluator here to assert
     * Layer 7 invariant equivalence between interpreter and bytecode VM
     * dispatch (Q-017 step 1 Track A.5).
     *
     * Signature: `(invariantBodyId: NodeId, staticValue: Value) -> Value`.
     * The returned Value must be a [Value.BoolV] (the verifier's
     * SchemaInvariantBodyTypeMismatch rule guarantees this; non-Bool
     * returns surface as an error in [check] either way).
     */
    private val invariantEvaluator: ((NodeId, Value) -> Value)? = null,
) {

    private val interpreter = Interpreter(store, hashToNodeId)

    /**
     * Run the check. Returns a [SchemaCheckResult] with the list of
     * violations and deferred informational diagnostics. The verifier's
     * own [VerifyResult.Ok] is unchanged.
     */
    fun check(): SchemaCheckResult {
        val violations = mutableListOf<VerifyError.SchemaInvariantViolation>()
        val deferred = mutableListOf<VerifyError.SchemaInvariantDeferred>()
        for ((nodeId, type) in verifyResult.nodeTypes) {
            if (type !is TypeExpr.SchemaType) continue
            val staticValue = tryEvaluateStatically(nodeId)
            if (staticValue == null) {
                deferred += VerifyError.SchemaInvariantDeferred(
                    at = nodeId,
                    schema = type.schemaId,
                    reason = "value not statically known"
                )
                continue
            }
            for (invariantId in type.invariants) {
                val invariantNode = store.getOrNull(invariantId) as? Node.Invariant
                    ?: error(
                        "Schema ${type.schemaId} lists invariant $invariantId, but the " +
                            "store does not contain a Node.Invariant at that NodeId. The " +
                            "verifier should have rejected this graph."
                    )
                val verdict = evaluateInvariant(invariantNode.body, staticValue)
                if (verdict !is Value.BoolV) {
                    error(
                        "Invariant $invariantId's body evaluated to a non-Bool value " +
                            "($verdict); the verifier's body-type check should have rejected this."
                    )
                }
                if (!verdict.v) {
                    violations += VerifyError.SchemaInvariantViolation(
                        at = nodeId,
                        schema = type.schemaId,
                        invariant = invariantId,
                        valueDescription = staticValue.toString()
                    )
                }
            }
        }
        return SchemaCheckResult(violations, deferred)
    }

    /**
     * Recursively determine whether the value at [nodeId] is statically
     * known per the rules of § 5 of the proposal. Returns the evaluated
     * [Value] if so, otherwise null.
     *
     * The recursion is bounded by the graph's topology — Let chains
     * walked via case (d) terminate at the final body, ProductValue and
     * SumValue walk their fields once. We do not impose a depth bound;
     * an adversarially deep static graph is bounded by the verifier's
     * own well-formedness pass already accepting it.
     */
    private fun tryEvaluateStatically(nodeId: NodeId): Value? =
        tryEvaluateStaticallyInScope(nodeId, scope = emptyMap())

    private fun tryEvaluateStaticallyInScope(
        nodeId: NodeId,
        scope: Map<NodeId, Value>,
    ): Value? {
        val node = store.getOrNull(nodeId) ?: return null
        return when (node) {
            is Node.IntLit -> Value.IntV(node.value)
            is Node.FloatLit -> Value.FloatV(node.value)
            is Node.StringLit -> Value.StringV(node.value)
            is Node.BoolLit -> Value.BoolV(node.value)
            Node.UnitLit -> Value.UnitV
            is Node.BytesLit -> Value.BytesV(node.value)

            is Node.ProductValue -> {
                val fields = LinkedHashMap<String, Value>(node.fields.size)
                for (fieldId in node.fields) {
                    val fieldNode = store.getOrNull(fieldId) as? Node.ProductFieldValue
                        ?: return null
                    val fieldValue = tryEvaluateStaticallyInScope(fieldNode.value, scope)
                        ?: return null
                    fields[fieldNode.fieldName] = fieldValue
                }
                Value.ProductV(fields)
            }

            is Node.SumValue -> {
                val payloadValue = node.payload?.let {
                    tryEvaluateStaticallyInScope(it, scope) ?: return null
                }
                Value.SumV(node.caseName, payloadValue)
            }

            is Node.Let -> {
                val valueValue = tryEvaluateStaticallyInScope(node.value, scope)
                    ?: return null
                tryEvaluateStaticallyInScope(node.body, scope + (nodeId to valueValue))
            }

            is Node.VarRef -> {
                // Case (e): a VarRef whose binder is a Let with a
                // statically-known value. We can resolve the binder via
                // the static-scope map populated by case (d). If the
                // binder is a ParameterDecl or pattern binder, it has no
                // static binding here and the answer is "non-static".
                scope[node.binder]
            }

            // NodeRef: per the verifier's NodeRefTargetMustBeClosed rule
            // the target is a closed subgraph, so its static-evaluability
            // depends only on the target's shape. Resolve through the
            // reverse map and recurse under an empty scope (the target
            // cannot refer to any binder in our scope).
            is Node.NodeRef -> {
                val targetId = hashToNodeId[node.target] ?: return null
                tryEvaluateStaticallyInScope(targetId, scope = emptyMap())
            }

            // All remaining node categories are non-static. Lambdas,
            // Applications, ForeignNode calls, Fixpoint, Match, Handler,
            // CapabilityScope, ProductFieldGet, TypeAbstraction, etc. all
            // require runtime evaluation. Type / pattern / effect-decl
            // nodes are not values. State-machine nodes are not values.
            // Returning null surfaces the corresponding
            // SchemaInvariantDeferred up the call chain.
            is Node.Lambda,
            is Node.Application,
            is Node.ForeignNode,
            is Node.Fixpoint,
            is Node.Match,
            is Node.Handler,
            is Node.CapabilityScope,
            is Node.ProductFieldGet,
            is Node.TypeAbstraction,
            is Node.PrimitiveType,
            is Node.ProductType,
            is Node.ProductTypeField,
            is Node.SumType,
            is Node.SumTypeCase,
            is Node.FunctionType,
            is Node.TypeParameter,
            is Node.ForallType,
            is Node.ParameterDecl,
            is Node.EffectCategory,
            is Node.EffectDecl,
            is Node.MatchCase,
            is Node.Pattern,
            is Node.ProductFieldValue,
            is Node.RecursiveType,
            is Node.RecursiveSelf,
            is Node.Schema,
            is Node.Invariant,
            is Node.ToolDef,
            is Node.ResponseSchemaSpec,
            is Node.StateMachine,
            is Node.EventStream,
            is Node.Transition -> null
        }
    }

    /**
     * Evaluate an invariant body Lambda on the static [value]. The body
     * is evaluated under an empty capability context (the verifier's
     * SchemaInvariantBodyMustBePure rule guarantees the body is
     * effect-free, so an empty context is sufficient).
     */
    private fun evaluateInvariant(bodyId: NodeId, value: Value): Value {
        // Test-injected evaluator (e.g., VM-based) takes precedence over
        // the default interpreter dispatch. Production code paths never
        // pass [invariantEvaluator]; the override is for cross-engine
        // equivalence tests.
        invariantEvaluator?.let { return it(bodyId, value) }
        val fn = interpreter.eval(bodyId)
        return interpreter.applyCallable(fn = fn, args = listOf(value))
    }
}
