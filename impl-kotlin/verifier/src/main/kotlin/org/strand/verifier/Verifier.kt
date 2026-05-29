package org.strand.verifier

import org.strand.core.EffectProjection
import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.core.ProjectionSource

/**
 * Layer 1 verifier.
 *
 * Performs two cooperating checks:
 *
 *  1. Well-formedness. Outgoing references resolve, child node categories
 *     match expectations, multiplicities are satisfied, VarRef binders are in
 *     scope, and Layer >= 2 node kinds are absent.
 *
 *  2. Explicit-instantiation type checking (System F). A polymorphic term is
 *     a [Node.TypeAbstraction] whose type is a [TypeExpr.Forall]. Every
 *     Application of a polymorphic value supplies a `typeArguments` list of
 *     length equal to the function's quantified type parameters. The
 *     verifier substitutes positionally into the ForallType's body, requires
 *     the result to be a [TypeExpr.Fun] (partial instantiation is rejected),
 *     and then requires structural equality between substituted parameter
 *     types and argument types. A monomorphic call (one whose function's
 *     type is already a plain [TypeExpr.Fun]) supplies an empty
 *     `typeArguments` list. No unification, no occurs-check, no
 *     let-generalization is performed.
 *
 *  TypeParameter nodes are bound by an enclosing [Node.TypeAbstraction] (in
 *  term position) or [Node.ForallType] (in type position). A TypeParameter
 *  referenced outside any such binder is reported as
 *  [VerifyError.UnboundTypeParameter]. In particular, a Lambda whose
 *  parameter type mentions a TypeParameter not in scope is ill-formed.
 *
 *  Effects are deliberately out of scope (Layer 3).
 */
/**
 * Constructed over a canonical [NodeStore] (produced by `Hasher.finalize`)
 * plus the [hashToNodeId] reverse map for resolving [Node.NodeRef.target]
 * hashes back to local NodeIds. Tests and consumers that never construct
 * NodeRefs may pass an empty map; if a NodeRef is encountered with a hash
 * the map doesn't cover, the verifier reports
 * [VerifyError.NodeRefTargetNotFound].
 */
class Verifier(
    private val store: NodeStore,
    private val hashToNodeId: Map<Hash, NodeId> = emptyMap(),
) {

    fun verify(root: NodeId): VerifyResult {
        val state = VerifyState()
        if (!store.contains(root)) {
            return VerifyResult.Failed(listOf(
                VerifyError.DanglingReference(at = root, missing = root, fromField = "<root>")
            ))
        }
        val rootType = try {
            state.infer(root, scope = emptyMap(), typeParams = emptySet())
        } catch (_: VerifyAbort) {
            return VerifyResult.Failed(state.errors)
        }
        // N-046 (Q-043): certify every ModuleManifest admitted to the store.
        // Each export's declaredEffects must exactly equal its target's effect
        // closure. Runs whether or not a manifest is reachable from `root` — a
        // published library's root may be the manifest itself, or a manifest
        // may sit alongside the program it documents.
        state.checkManifests()
        if (state.errors.isNotEmpty()) return VerifyResult.Failed(state.errors)
        return VerifyResult.Ok(rootType, state.nodeTypes.toMap())
    }

    /** Thrown internally to abort checking once a fatal local error has been recorded. */
    private class VerifyAbort : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Per-verification mutable state. */
    private inner class VerifyState {
        val errors = mutableListOf<VerifyError>()
        val nodeTypes = mutableMapOf<NodeId, TypeExpr>()

        /**
         * Effect closure of each reached expression node: the set of
         * EffectCategory NodeIds the node's evaluation may exercise. Layer 3
         * step 1 matches capabilities by EffectCategory identity (NodeId);
         * refinement-lattice matching is deferred.
         */
        val nodeClosures = mutableMapOf<NodeId, Set<NodeId>>()

        /**
         * Q-039: per-verification structural-equality cache for
         * [ProjectionSource.LiteralNode] targets and the EffectDecl
         * literal parameters they're matched against. Keyed by the
         * unordered pair of NodeIds (smaller first); avoids re-walking
         * the same literal tower across multiple call sites that share a
         * pinned-literal projection (e.g., the `"anthropic"` literal in
         * a chain of LLM-tool callers).
         */
        private val literalEqualityCache = mutableMapOf<Pair<NodeId, NodeId>, Boolean>()

        fun record(id: NodeId, t: TypeExpr) {
            nodeTypes[id] = t
        }

        fun recordClosure(id: NodeId, c: Set<NodeId>) {
            nodeClosures[id] = c
        }

        /**
         * Type-compatibility for value-flow positions (Application argument
         * into parameter type, ProductFieldValue into field type, SumValue
         * payload into case payload type). Plain structural equality plus
         * one extra rule for Layer 7 step 1:
         *
         *  - A value of type T can flow into a position of type
         *    `SchemaType(_, T, ...)`. The schema's invariants are deferred
         *    to the SchemaChecker pass (which iterates [nodeTypes] looking
         *    for SchemaType entries) — see [recordSchemaCheck].
         *  - A value of type `SchemaType(_, T, ...)` can flow into a
         *    position of type T. The verifier already knows the schema-
         *    typed value satisfies T structurally.
         *
         * Where both sides are SchemaType, structural equality (which
         * compares valueType and the invariant set) is the test —
         * different schemas with the same underlying T are NOT compatible
         * without an explicit schema-strip operation that step 1 does not
         * provide. The two single-direction relaxations above are the
         * only relaxations needed for the corpus programs.
         *
         * Direct `==` comparison is preserved at sites where structural
         * type equivalence (not assignment-compatibility) matters: Match
         * case body type divergence, Fixpoint body shape, Handler
         * signature agreement, state-machine signature shape. Those use
         * `!=` directly on [TypeExpr] and are intentionally strict.
         */
        fun typesCompatible(expected: TypeExpr, actual: TypeExpr): Boolean {
            if (expected == actual) return true
            // SchemaType-into-plain-T direction.
            if (actual is TypeExpr.SchemaType && actual.valueType == expected) return true
            // Plain-T-into-SchemaType direction (and SchemaType-into-
            // SchemaType is the `expected == actual` branch above).
            if (expected is TypeExpr.SchemaType && expected.valueType == actual) return true
            return false
        }

        fun closureOf(id: NodeId): Set<NodeId> =
            nodeClosures[id] ?: emptySet()

        /**
         * N-046 (Q-043) admission: certify every [Node.ModuleManifest] in the
         * store. The pass scans the whole store rather than walking from the
         * root, so a manifest is certified whether or not it is reachable as
         * an expression (a published library's root may itself be the
         * manifest, or a manifest may sit alongside the program it documents).
         */
        fun checkManifests() {
            for ((id, node) in store.entries()) {
                if (node is Node.ModuleManifest) checkManifest(id, node)
            }
        }

        /**
         * Certify one manifest: for every export, resolve its `target` hash to
         * a local NodeId via [hashToNodeId] (absent → [VerifyError.ManifestExportTargetUnresolvable]),
         * infer the target under an empty scope to populate its effect closure
         * (export targets are closed published nodes, like NodeRef targets),
         * and require the export's `declaredEffects` set to *exactly* equal
         * that closure (mismatch → [VerifyError.ManifestExportEffectMismatch]).
         * Inferring an ill-formed target records its own errors; the export's
         * effect comparison is then skipped.
         */
        private fun checkManifest(manifestId: NodeId, node: Node.ModuleManifest) {
            node.exports.forEachIndexed { index, export ->
                val targetId = hashToNodeId[export.target]
                if (targetId == null) {
                    report(VerifyError.ManifestExportTargetUnresolvable(
                        at = manifestId, exportIndex = index, target = export.target,
                    ))
                } else {
                    val surface: Set<NodeId>? = try {
                        val targetType = infer(targetId, scope = emptyMap(), typeParams = emptySet())
                        exportEffectSurface(targetId, targetType)
                    } catch (_: VerifyAbort) {
                        null // target ill-formed; its errors are already recorded
                    }
                    if (surface != null && surface != export.declaredEffects.toSet()) {
                        report(VerifyError.ManifestExportEffectMismatch(
                            at = manifestId,
                            exportIndex = index,
                            target = export.target,
                            declared = export.declaredEffects.toSet(),
                            actual = surface,
                        ))
                    }
                }
            }
        }

        /**
         * The effect surface a consumer incurs by *using* a manifest export.
         *
         * For a function-typed export (the common case) this is the function's
         * declared effect row — releasing those effects is exactly what calling
         * the export does. A bare Lambda has an empty *closure* (constructing a
         * closure value is pure; effects release at call sites, see
         * [inferApplication]), so `closureOf` would wrongly report no effects
         * for an effectful function. For a non-function (value) export, the
         * surface is the node's construction closure.
         *
         * This clarifies proposal § 5.4's "closure of the target": for function
         * exports the meaningful quantity is the function's effect row, not the
         * always-empty closure of the Lambda value. Polymorphic functions
         * (a Forall over a Fun) use the inner Fun's effect row.
         */
        private fun exportEffectSurface(targetId: NodeId, targetType: TypeExpr): Set<NodeId> =
            when (targetType) {
                is TypeExpr.Fun -> targetType.effects
                is TypeExpr.Forall ->
                    (targetType.body as? TypeExpr.Fun)?.effects ?: closureOf(targetId)
                else -> closureOf(targetId)
            }

        /**
         * After verifying a NodeRef's target subgraph under an empty scope,
         * fold any [VerifyError.UnboundVariable] / [VerifyError.UnboundTypeParameter]
         * errors raised between [errorsBefore] and now into a single
         * [VerifyError.NodeRefTargetMustBeClosed] report. Closure-check errors
         * are removed; any unrelated errors raised during the recursive verify
         * are kept untouched.
         */
        fun wrapClosureErrors(refId: NodeId, targetId: NodeId, errorsBefore: Int) {
            if (errorsBefore >= errors.size) return
            val window = errors.subList(errorsBefore, errors.size)
            val openRefs = window.mapNotNull { err ->
                when (err) {
                    is VerifyError.UnboundVariable -> err.binder
                    is VerifyError.UnboundTypeParameter -> err.typeParameter
                    else -> null
                }
            }
            if (openRefs.isEmpty()) return
            // Strip the closure-related errors; keep any others.
            val kept = window.filter { err ->
                err !is VerifyError.UnboundVariable && err !is VerifyError.UnboundTypeParameter
            }
            window.clear()
            errors += kept
            errors += VerifyError.NodeRefTargetMustBeClosed(
                at = refId, target = targetId, openReferences = openRefs
            )
        }

        /**
         * Tracks the number of enclosing `RecursiveType` binders during
         * `resolveType`. A `RecursiveSelf` is well-formed only when this is
         * positive. Mirrors `CanonicalEncoder.currentRecDepth` — they share
         * the same lexical structure and must stay in lock-step.
         */
        var recursiveDepth: Int = 0

        fun reportFatal(err: VerifyError): Nothing {
            errors += err
            throw VerifyAbort()
        }

        fun report(err: VerifyError) {
            errors += err
        }

        /**
         * Compute the type of the expression at [id] under [scope] and
         * [typeParams]. [scope] maps term-binder NodeIds (ParameterDecls and
         * Lets) to their types. [typeParams] is the set of TypeParameter
         * NodeIds currently bound by enclosing TypeAbstractions; type-position
         * checks consult this set when resolving TypeParameter references.
         */
        fun infer(id: NodeId, scope: Map<NodeId, TypeExpr>, typeParams: Set<NodeId>): TypeExpr {
            val node = store.getOrNull(id)
                ?: reportFatal(VerifyError.DanglingReference(at = id, missing = id, fromField = "<resolve>"))

            val t = when (node) {
                is Node.IntLit -> TypeExpr.Prim(Primitive.Int)
                is Node.FloatLit -> TypeExpr.Prim(Primitive.Float)
                is Node.StringLit -> TypeExpr.Prim(Primitive.String)
                is Node.BoolLit -> TypeExpr.Prim(Primitive.Bool)
                Node.UnitLit -> TypeExpr.Prim(Primitive.Unit)
                is Node.BytesLit -> TypeExpr.Prim(Primitive.Bytes)

                is Node.Lambda -> inferLambda(id, node, scope, typeParams)
                is Node.TypeAbstraction -> inferTypeAbstraction(id, node, scope, typeParams)
                is Node.Application -> inferApplication(id, node, scope, typeParams)
                is Node.Let -> inferLet(id, node, scope, typeParams)
                is Node.VarRef -> inferVarRef(id, node, scope)
                is Node.NodeRef -> {
                    // Layer 2 step 2: NodeRef carries a Hash. Resolve to a
                    // local NodeId via the reverse map, then verify the
                    // target subgraph under an *empty* scope and typeParams
                    // — content-addressed references must be closed terms
                    // so their hash is context-independent (ADR-003). Any
                    // resulting UnboundVariable / UnboundTypeParameter
                    // errors are folded into a single
                    // NodeRefTargetMustBeClosed report.
                    val targetId = hashToNodeId[node.target]
                        ?: reportFatal(
                            VerifyError.NodeRefTargetNotFound(at = id, targetHash = node.target)
                        )
                    val errorsBefore = errors.size
                    val targetType = try {
                        infer(targetId, scope = emptyMap(), typeParams = emptySet())
                    } catch (e: VerifyAbort) {
                        wrapClosureErrors(id, targetId, errorsBefore)
                        throw e
                    }
                    wrapClosureErrors(id, targetId, errorsBefore)
                    recordClosure(id, closureOf(targetId))
                    targetType
                }
                is Node.ForeignNode -> inferForeignNode(id, node, typeParams)
                is Node.CapabilityScope -> inferCapabilityScope(id, node, scope, typeParams)
                is Node.Match -> inferMatch(id, node, scope, typeParams)
                is Node.Fixpoint -> inferFixpoint(id, node, scope, typeParams)
                is Node.ProductValue -> inferProductValue(id, node, scope, typeParams)
                is Node.ProductFieldGet -> inferProductFieldGet(id, node, scope, typeParams)
                is Node.SumValue -> inferSumValue(id, node, scope, typeParams)
                is Node.Handler -> inferHandler(id, node, scope, typeParams)
                is Node.ToolDef -> inferToolDef(id, node, scope, typeParams)
                is Node.ResponseSchemaSpec -> inferResponseSchemaSpec(id, node, typeParams)
                is Node.ModuleManifest -> {
                    // N-046 is a passive declaration, not a value-producing
                    // expression. When it is the program root (or is otherwise
                    // reached via infer) it types as Unit and carries no
                    // effects of its own — the export's declared effects
                    // describe the exported nodes, not the manifest's own
                    // evaluation. The per-export effect-closure certification
                    // runs in the store-wide manifest admission pass
                    // (checkManifests), so every manifest in the store is
                    // certified whether or not it is reachable from the root.
                    recordClosure(id, emptySet())
                    TypeExpr.Prim(Primitive.Unit)
                }

                is Node.StateMachine -> inferStateMachine(id, node, scope, typeParams)
                is Node.Transition -> {
                    // A Transition is not an expression — it is a structural
                    // piece of a transition-function host context. Step 1
                    // parses Transition but no corpus program reaches one
                    // through infer(); the rule is recorded for forward
                    // schema stability.
                    report(VerifyError.TransitionStandalone(at = id))
                    throw VerifyAbort()
                }
                is Node.EventStream -> {
                    // EventStream is also not an expression. In step 1 it is
                    // reachable only via StateMachine.inputStreams/outputStreams,
                    // which inferStateMachine resolves directly without
                    // calling infer(). A standalone EventStream as a value
                    // is ill-formed.
                    report(VerifyError.CategoryMismatch(
                        at = id, field = "<expression position>",
                        expectedCategory = "Expression",
                        actualCategory = "EventStream",
                    ))
                    throw VerifyAbort()
                }

                // Type and effect-declaration nodes are not expressions.
                // MatchCase, Pattern, ProductFieldValue are structural
                // pieces of other nodes, not standalone expressions.
                // Schema and Invariant (N-032, N-033) only appear in type
                // position (Schema) or as Schema-declared invariant edges
                // (Invariant) — never as standalone expressions.
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
                is Node.Invariant ->
                    reportFatal(VerifyError.CategoryMismatch(
                        at = id,
                        field = "<expression position>",
                        expectedCategory = "Expression",
                        actualCategory = categoryName(node)
                    ))
            }
            record(id, t)
            return t
        }

        private fun inferLambda(
            id: NodeId,
            node: Node.Lambda,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>
        ): TypeExpr {
            val paramTypes = mutableListOf<TypeExpr>()
            val extendedScope = scope.toMutableMap()
            for (paramId in node.parameters) {
                val paramNode = store.getOrNull(paramId)
                if (paramNode == null) {
                    report(VerifyError.DanglingReference(at = id, missing = paramId, fromField = "Lambda.parameters"))
                    throw VerifyAbort()
                }
                if (paramNode !is Node.ParameterDecl) {
                    report(VerifyError.CategoryMismatch(
                        at = id, field = "Lambda.parameters",
                        expectedCategory = "ParameterDecl",
                        actualCategory = categoryName(paramNode)
                    ))
                    throw VerifyAbort()
                }
                val pt = resolveType(paramNode.paramType, typeParams)
                paramTypes += pt
                extendedScope[paramId] = pt
                record(paramId, pt)
            }

            // Validate effect edges: each must be an EffectCategory node.
            val declaredEffects = validateEffectCategoryEdges(id, node.effects, "Lambda.effects")

            val bodyType = infer(node.body, extendedScope, typeParams)
            val bodyClosure = closureOf(node.body)

            // Coverage: declared effects must include every effect in the
            // body's closure. Over-declaration (declaring effects the body
            // doesn't exercise) is allowed; under-declaration is not.
            val uncovered = bodyClosure - declaredEffects
            if (uncovered.isNotEmpty()) {
                report(VerifyError.UncoveredEffects(at = id, missing = uncovered))
                throw VerifyAbort()
            }

            // Creating a Lambda does not itself exercise effects; the effects
            // are released at call sites (handled in inferApplication).
            recordClosure(id, emptySet())
            return TypeExpr.Fun(paramTypes, bodyType, declaredEffects)
        }

        private fun inferTypeAbstraction(
            id: NodeId,
            node: Node.TypeAbstraction,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>
        ): TypeExpr {
            // Every entry of typeParameters must be a TypeParameter node.
            for ((i, tpId) in node.typeParameters.withIndex()) {
                val tpNode = store.getOrNull(tpId)
                if (tpNode == null) {
                    report(VerifyError.DanglingReference(
                        at = id, missing = tpId, fromField = "TypeAbstraction.typeParameters[$i]"
                    ))
                    throw VerifyAbort()
                }
                if (tpNode !is Node.TypeParameter) {
                    report(VerifyError.CategoryMismatch(
                        at = id, field = "TypeAbstraction.typeParameters[$i]",
                        expectedCategory = "TypeParameter",
                        actualCategory = categoryName(tpNode)
                    ))
                    throw VerifyAbort()
                }
            }
            val extendedTypeParams = typeParams + node.typeParameters
            val bodyType = infer(node.body, scope, extendedTypeParams)
            // TypeAbstraction is transparent for effect-closure purposes: its
            // closure equals the body's closure. In practice the body of a
            // TypeAbstraction is a Lambda whose closure is empty, so this
            // typically resolves to ∅.
            recordClosure(id, closureOf(node.body))
            return TypeExpr.Forall(node.typeParameters, bodyType)
        }

        private fun inferApplication(
            id: NodeId,
            node: Node.Application,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>
        ): TypeExpr {
            val fnType = infer(node.function, scope, typeParams)

            // Resolve every type-argument id in the current scope of bound
            // TypeParameters. Done up front so we can use them whether the
            // function's type is a Forall (to substitute) or a plain Fun
            // (where we expect zero type arguments).
            val resolvedTypeArgs = node.typeArguments.map { resolveType(it, typeParams) }

            val funType: TypeExpr.Fun = when (fnType) {
                is TypeExpr.Fun -> {
                    if (node.typeArguments.isNotEmpty()) {
                        report(VerifyError.TypeArgumentArityMismatch(
                            at = id, expected = 0, actual = node.typeArguments.size
                        ))
                        throw VerifyAbort()
                    }
                    fnType
                }
                is TypeExpr.Forall -> {
                    if (resolvedTypeArgs.size != fnType.typeParameters.size) {
                        report(VerifyError.TypeArgumentArityMismatch(
                            at = id,
                            expected = fnType.typeParameters.size,
                            actual = resolvedTypeArgs.size
                        ))
                        throw VerifyAbort()
                    }
                    val subst = HashMap<NodeId, TypeExpr>(fnType.typeParameters.size)
                    for ((i, tp) in fnType.typeParameters.withIndex()) {
                        subst[tp] = resolvedTypeArgs[i]
                    }
                    val instantiated = substitute(fnType.body, subst)
                    when (instantiated) {
                        is TypeExpr.Fun -> instantiated
                        is TypeExpr.Forall -> {
                            report(VerifyError.PartialTypeInstantiation(at = id, residual = instantiated))
                            throw VerifyAbort()
                        }
                        else -> {
                            report(VerifyError.NotAFunction(at = id, gotType = instantiated))
                            throw VerifyAbort()
                        }
                    }
                }
                else -> {
                    report(VerifyError.NotAFunction(id, fnType))
                    throw VerifyAbort()
                }
            }

            if (funType.parameters.size != node.arguments.size) {
                report(VerifyError.ArityMismatch(
                    id, expected = funType.parameters.size, actual = node.arguments.size
                ))
                throw VerifyAbort()
            }

            val argTypes = node.arguments.map { infer(it, scope, typeParams) }
            for (i in funType.parameters.indices) {
                val expected = funType.parameters[i]
                val actual = argTypes[i]
                if (!typesCompatible(expected, actual)) {
                    report(VerifyError.ParameterTypeMismatch(
                        at = id,
                        parameterIndex = i,
                        expected = expected,
                        actual = actual
                    ))
                    throw VerifyAbort()
                }
                // Layer 7: when a plain-T value flows into a SchemaType
                // position, re-record the argument's NodeId as the
                // SchemaType so the SchemaChecker pass (which walks
                // nodeTypes for SchemaType entries) picks it up. We
                // intentionally OVERWRITE the prior `record(argId, T)` from
                // `infer` — the SchemaChecker needs the SchemaType
                // identity, and downstream consumers that want the plain
                // valueType can read SchemaType.valueType.
                if (expected is TypeExpr.SchemaType && actual !is TypeExpr.SchemaType) {
                    record(node.arguments[i], expected)
                }
            }

            // Q-031: validate effectInstances against the callee's declared
            // effects. Empty effectInstances are permitted only when the
            // callee has no declared effects (pre-Q-031 back-compat: every
            // existing corpus call site that didn't supply effect instances
            // continues to verify cleanly). When non-empty, every EffectDecl
            // must be well-formed (shape, arity, parameter types) AND the
            // set of EffectCategories covered must equal the callee's
            // FunctionType.effects set exactly.
            if (node.effectInstances.isNotEmpty()) {
                val coveredCategories = LinkedHashSet<NodeId>()
                for ((i, effectDeclId) in node.effectInstances.withIndex()) {
                    val effectDeclNode = store.getOrNull(effectDeclId)
                        ?: run {
                            report(VerifyError.DanglingReference(
                                at = id, missing = effectDeclId,
                                fromField = "Application.effectInstances[$i]"
                            ))
                            throw VerifyAbort()
                        }
                    if (effectDeclNode !is Node.EffectDecl) {
                        report(VerifyError.CategoryMismatch(
                            at = id, field = "Application.effectInstances[$i]",
                            expectedCategory = "EffectDecl",
                            actualCategory = categoryName(effectDeclNode)
                        ))
                        throw VerifyAbort()
                    }
                    val categoryId = inferEffectDecl(effectDeclId, effectDeclNode, scope, typeParams)
                    coveredCategories += categoryId
                }
                val missing = funType.effects - coveredCategories
                val extra = coveredCategories - funType.effects
                if (missing.isNotEmpty() || extra.isNotEmpty()) {
                    report(VerifyError.EffectInstanceCoverageMismatch(
                        at = id, missing = missing, extra = extra
                    ))
                    throw VerifyAbort()
                }

                // Q-039: when the callee resolves to a projected
                // ForeignNode and the Application carries authored
                // effectInstances, the authored shape must agree with
                // the projection. The runtime synthesizes the same
                // capability-check values from the projection plus
                // actual arguments — drift between an authored
                // EffectDecl and the projection is rejected here so the
                // verifier-level security property holds even when the
                // agent over-specifies the call site.
                val projectedForeign = resolveProjectedForeignNode(node.function)
                if (projectedForeign != null) {
                    validateProjectionMatch(
                        at = id,
                        app = node,
                        projectedFn = projectedForeign,
                    )
                }
            }

            // Effect closure of an Application: union of (a) the function
            // expression's own closure, (b) each argument's closure, and
            // (c) the function's declared effects, which are "released" at
            // the call site. (a) and (b) cover effects exercised while
            // *evaluating* the call's components; (c) covers what the called
            // function may do.
            val closure = mutableSetOf<NodeId>()
            closure += closureOf(node.function)
            for (argId in node.arguments) closure += closureOf(argId)
            closure += funType.effects
            recordClosure(id, closure)

            return funType.result
        }

        /**
         * Validate one [Node.EffectDecl]: confirm its `effectType` resolves
         * to an [Node.EffectCategory], its parameter list matches the
         * category's declared arity, and each parameter expression's
         * inferred type structurally equals the category's parameter type
         * at the same position. Returns the underlying EffectCategory
         * NodeId on success (used by the caller to build the coverage set).
         *
         * Reports the corresponding `EffectDecl*` variant on failure and
         * aborts. EffectDecls are not expressions: they never appear in
         * expression position. The Layer 3 step 1 verifier rejected any
         * EffectDecl reachable via `infer()`. Layer 3 step 2 (Q-031) calls
         * this helper from `inferApplication` for each entry in
         * `Application.effectInstances`; the central `infer()` dispatch
         * still rejects EffectDecl as a standalone expression.
         */
        private fun inferEffectDecl(
            id: NodeId,
            node: Node.EffectDecl,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>
        ): NodeId {
            val effectTypeNode = store.getOrNull(node.effectType)
                ?: run {
                    report(VerifyError.DanglingReference(
                        at = id, missing = node.effectType,
                        fromField = "EffectDecl.effectType"
                    ))
                    throw VerifyAbort()
                }
            if (effectTypeNode !is Node.EffectCategory) {
                report(VerifyError.EffectDeclTypeMismatch(
                    at = id, effectTypeId = node.effectType,
                    actualCategory = categoryName(effectTypeNode)
                ))
                throw VerifyAbort()
            }
            // Arity check.
            if (node.parameters.size != effectTypeNode.parameters.size) {
                report(VerifyError.EffectDeclArityMismatch(
                    at = id,
                    expected = effectTypeNode.parameters.size,
                    actual = node.parameters.size
                ))
                throw VerifyAbort()
            }
            // Per-parameter type check. The category's parameter list is
            // resolved at the top level (no enclosing TypeAbstraction in
            // effect on EffectCategory declarations); the EffectDecl's
            // parameter expressions are checked under the call site's
            // current scope and typeParams so they can refer to enclosing
            // ParameterDecls (the typical case: an EffectDecl parameter
            // is a VarRef into one of the Application's arguments).
            for (i in node.parameters.indices) {
                val expectedType = resolveType(effectTypeNode.parameters[i], emptySet())
                val actualType = infer(node.parameters[i], scope, typeParams)
                if (expectedType != actualType) {
                    report(VerifyError.EffectDeclParameterTypeMismatch(
                        at = id, parameterIndex = i,
                        expected = expectedType, actual = actualType
                    ))
                    throw VerifyAbort()
                }
            }
            return node.effectType
        }

        private fun inferLet(
            id: NodeId,
            node: Node.Let,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>
        ): TypeExpr {
            val valType = infer(node.value, scope, typeParams)
            val extended = scope + (id to valType)
            val bodyType = infer(node.body, extended, typeParams)
            // Let's closure is the union of its value's and body's closures.
            recordClosure(id, closureOf(node.value) + closureOf(node.body))
            return bodyType
        }

        private fun inferMatch(
            id: NodeId,
            node: Node.Match,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>
        ): TypeExpr {
            if (node.cases.isEmpty()) {
                report(VerifyError.EmptyMatch(at = id))
                throw VerifyAbort()
            }

            val scrutineeType = infer(node.scrutinee, scope, typeParams)
            val closure = mutableSetOf<NodeId>()
            closure += closureOf(node.scrutinee)

            var commonBodyType: TypeExpr? = null
            for ((i, caseId) in node.cases.withIndex()) {
                val caseNode = store.getOrNull(caseId)
                    ?: run {
                        report(VerifyError.DanglingReference(
                            at = id, missing = caseId, fromField = "Match.cases[$i]"
                        ))
                        throw VerifyAbort()
                    }
                if (caseNode !is Node.MatchCase) {
                    report(VerifyError.CategoryMismatch(
                        at = id, field = "Match.cases[$i]",
                        expectedCategory = "MatchCase",
                        actualCategory = categoryName(caseNode)
                    ))
                    throw VerifyAbort()
                }
                val bodyType = inferMatchCase(caseId, caseNode, scrutineeType, scope, typeParams)
                closure += closureOf(caseNode.body)

                if (commonBodyType == null) {
                    commonBodyType = bodyType
                } else if (commonBodyType != bodyType) {
                    report(VerifyError.MatchCaseBodyTypeDivergence(
                        at = id,
                        firstType = commonBodyType,
                        divergentType = bodyType,
                        caseIndex = i
                    ))
                    throw VerifyAbort()
                }
            }

            recordClosure(id, closure)
            return commonBodyType!!  // guaranteed non-null because cases.isNotEmpty()
        }

        /**
         * Type-check a single MatchCase against the enclosing Match's
         * scrutinee type. Returns the case body's type. Records the case
         * body's closure (used by inferMatch to compute the Match's
         * overall closure). Also records the pattern's type and the body's
         * type via record() so downstream consumers can find them.
         */
        private fun inferMatchCase(
            caseId: NodeId,
            caseNode: Node.MatchCase,
            scrutineeType: TypeExpr,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>
        ): TypeExpr {
            val patternNode = store.getOrNull(caseNode.pattern)
                ?: run {
                    report(VerifyError.DanglingReference(
                        at = caseId, missing = caseNode.pattern, fromField = "MatchCase.pattern"
                    ))
                    throw VerifyAbort()
                }
            if (patternNode !is Node.Pattern) {
                report(VerifyError.CategoryMismatch(
                    at = caseId, field = "MatchCase.pattern",
                    expectedCategory = "Pattern",
                    actualCategory = categoryName(patternNode)
                ))
                throw VerifyAbort()
            }

            // Recursively type-check the pattern tree against scrutineeType
            // and collect every binder it introduces. Bindings extend the
            // case body's scope; all other shape checks (literal type
            // match, sum case validity, etc.) happen inside.
            val bindings = mutableMapOf<NodeId, TypeExpr>()
            checkPatternAgainstType(caseId, caseNode.pattern, patternNode, scrutineeType, scope, typeParams, bindings)

            val extendedScope = scope + bindings
            val bodyType = infer(caseNode.body, extendedScope, typeParams)
            record(caseId, bodyType)
            return bodyType
        }

        /**
         * Type-check [pattern] against [expectedType], the type of the
         * value the pattern will be matched against. Records the pattern's
         * type via [record]. Collects bindings introduced by VariablePatterns
         * (including those nested inside ConstructorPatterns) into [bindings].
         * The [at] NodeId is used for error reporting.
         */
        private fun checkPatternAgainstType(
            at: NodeId,
            patternId: NodeId,
            pattern: Node.Pattern,
            expectedType: TypeExpr,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>,
            bindings: MutableMap<NodeId, TypeExpr>,
        ) {
            // Every pattern declares its own patternType; check it matches
            // the expected type. (For constructor patterns this is the
            // sum type itself; for nested payload patterns it is the
            // case's caseType.)
            val patternType = resolveType(pattern.patternType, typeParams)
            if (patternType != expectedType) {
                report(VerifyError.PatternTypeMismatch(
                    at = at,
                    scrutineeType = expectedType,
                    patternType = patternType
                ))
                throw VerifyAbort()
            }
            record(patternId, patternType)

            when (pattern) {
                is Node.Pattern.LiteralPattern -> {
                    val literalType = infer(pattern.literal, scope, typeParams)
                    if (literalType != patternType) {
                        report(VerifyError.PatternTypeMismatch(
                            at = at, scrutineeType = literalType, patternType = patternType
                        ))
                        throw VerifyAbort()
                    }
                }
                is Node.Pattern.VariablePattern -> {
                    bindings[patternId] = patternType
                }
                is Node.Pattern.WildcardPattern -> Unit
                is Node.Pattern.ConstructorPattern -> {
                    // The pattern type must be a Sum type — or a Recursive
                    // over a Sum, in which case we unfold to inspect cases.
                    val sumType: TypeExpr.Sum = when (patternType) {
                        is TypeExpr.Sum -> patternType
                        is TypeExpr.Recursive -> {
                            val unfolded = unfoldRecursive(patternType)
                            if (unfolded !is TypeExpr.Sum) {
                                report(VerifyError.CategoryMismatch(
                                    at = at, field = "ConstructorPattern.patternType (after unfold)",
                                    expectedCategory = "SumType",
                                    actualCategory = unfolded::class.simpleName ?: "?"
                                ))
                                throw VerifyAbort()
                            }
                            unfolded
                        }
                        else -> {
                            report(VerifyError.CategoryMismatch(
                                at = at, field = "ConstructorPattern.patternType",
                                expectedCategory = "SumType",
                                actualCategory = patternType::class.simpleName ?: "?"
                            ))
                            throw VerifyAbort()
                        }
                    }
                    val matchingCase = sumType.cases.firstOrNull { it.name == pattern.caseName }
                        ?: run {
                            report(VerifyError.UnknownSumCase(
                                at = at, caseName = pattern.caseName,
                                declared = sumType.cases.map { it.name }.toSet()
                            ))
                            throw VerifyAbort()
                        }
                    val payloadPatternId = pattern.payloadPattern
                    val expectedPayloadType = matchingCase.type
                    when {
                        expectedPayloadType == null && payloadPatternId != null ->
                            reportAndAbort(VerifyError.UnexpectedSumPayload(
                                at = at, caseName = pattern.caseName
                            ))
                        expectedPayloadType != null && payloadPatternId == null ->
                            reportAndAbort(VerifyError.MissingSumPayload(
                                at = at, caseName = pattern.caseName,
                                expectedType = expectedPayloadType
                            ))
                        expectedPayloadType != null && payloadPatternId != null -> {
                            val payloadPattern = store.getOrNull(payloadPatternId) as? Node.Pattern
                                ?: run {
                                    report(VerifyError.CategoryMismatch(
                                        at = at, field = "ConstructorPattern.payloadPattern",
                                        expectedCategory = "Pattern",
                                        actualCategory = categoryName(store.getOrNull(payloadPatternId))
                                    ))
                                    throw VerifyAbort()
                                }
                            checkPatternAgainstType(
                                at, payloadPatternId, payloadPattern,
                                expectedPayloadType, scope, typeParams, bindings
                            )
                        }
                        else -> Unit  // both null: OK
                    }
                }
            }
        }

        private fun inferProductValue(
            id: NodeId,
            node: Node.ProductValue,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>,
        ): TypeExpr {
            // 1. ofType must resolve to a Product type.
            val ofType = resolveType(node.ofType, typeParams)
            if (ofType !is TypeExpr.Product) {
                report(VerifyError.CategoryMismatch(
                    at = id, field = "ProductValue.ofType",
                    expectedCategory = "ProductType",
                    actualCategory = ofType::class.simpleName ?: "?"
                ))
                throw VerifyAbort()
            }
            val declared = ofType.fields.associate { it.name to it.type }

            // 2. Each ProductFieldValue: resolves cleanly, has a fieldName
            //    in the declared set, and its value's type matches.
            val seen = LinkedHashSet<String>()
            val closure = mutableSetOf<NodeId>()
            for ((i, fieldId) in node.fields.withIndex()) {
                val fieldNode = store.getOrNull(fieldId)
                    ?: run {
                        report(VerifyError.DanglingReference(
                            at = id, missing = fieldId, fromField = "ProductValue.fields[$i]"
                        ))
                        throw VerifyAbort()
                    }
                if (fieldNode !is Node.ProductFieldValue) {
                    report(VerifyError.CategoryMismatch(
                        at = id, field = "ProductValue.fields[$i]",
                        expectedCategory = "ProductFieldValue",
                        actualCategory = categoryName(fieldNode)
                    ))
                    throw VerifyAbort()
                }
                if (fieldNode.fieldName in seen) {
                    report(VerifyError.DuplicateProductValueField(
                        at = id, fieldName = fieldNode.fieldName
                    ))
                    throw VerifyAbort()
                }
                seen += fieldNode.fieldName

                val expectedType = declared[fieldNode.fieldName]
                if (expectedType == null) {
                    report(VerifyError.UnknownProductValueField(
                        at = id, fieldName = fieldNode.fieldName,
                        declared = declared.keys
                    ))
                    throw VerifyAbort()
                }
                val actualType = infer(fieldNode.value, scope, typeParams)
                if (!typesCompatible(expectedType, actualType)) {
                    report(VerifyError.ProductFieldValueTypeMismatch(
                        at = id,
                        fieldName = fieldNode.fieldName,
                        expected = expectedType,
                        actual = actualType
                    ))
                    throw VerifyAbort()
                }
                closure += closureOf(fieldNode.value)
                // Record the ProductFieldValue's own "type" as the field
                // type so downstream consumers can inspect it if they walk
                // the verifier's nodeTypes map.
                record(fieldId, expectedType)
                // Layer 7: when a plain-T value flows into a Schema-typed
                // field, re-record the inner value's NodeId as SchemaType
                // so the SchemaChecker picks it up (mirrors the rule in
                // inferApplication).
                if (expectedType is TypeExpr.SchemaType && actualType !is TypeExpr.SchemaType) {
                    record(fieldNode.value, expectedType)
                }
            }

            // 3. Completeness: every declared field must appear.
            val missing = declared.keys - seen
            if (missing.isNotEmpty()) {
                report(VerifyError.MissingProductValueFields(
                    at = id, missing = missing
                ))
                throw VerifyAbort()
            }

            recordClosure(id, closure)
            return ofType
        }

        private fun inferSumValue(
            id: NodeId,
            node: Node.SumValue,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>,
        ): TypeExpr {
            // 1. ofType must resolve to a Sum type — or to a Recursive over
            //    a Sum, in which case we unfold one step to inspect the
            //    case structure.
            val declaredType = resolveType(node.ofType, typeParams)
            val ofType = when (declaredType) {
                is TypeExpr.Sum -> declaredType
                is TypeExpr.Recursive -> {
                    val unfolded = unfoldRecursive(declaredType)
                    if (unfolded !is TypeExpr.Sum) {
                        report(VerifyError.CategoryMismatch(
                            at = id, field = "SumValue.ofType (after unfold)",
                            expectedCategory = "SumType",
                            actualCategory = unfolded::class.simpleName ?: "?"
                        ))
                        throw VerifyAbort()
                    }
                    unfolded
                }
                else -> {
                    report(VerifyError.CategoryMismatch(
                        at = id, field = "SumValue.ofType",
                        expectedCategory = "SumType",
                        actualCategory = declaredType::class.simpleName ?: "?"
                    ))
                    throw VerifyAbort()
                }
            }
            // 2. caseName must match a declared case.
            val matchingCase = ofType.cases.firstOrNull { it.name == node.caseName }
                ?: run {
                    report(VerifyError.UnknownSumCase(
                        at = id, caseName = node.caseName,
                        declared = ofType.cases.map { it.name }.toSet()
                    ))
                    throw VerifyAbort()
                }
            // 3. Payload presence + type must match the case's declaration.
            val payload = node.payload
            val expectedPayloadType = matchingCase.type
            when {
                expectedPayloadType == null && payload != null ->
                    reportAndAbort(VerifyError.UnexpectedSumPayload(
                        at = id, caseName = node.caseName
                    ))
                expectedPayloadType != null && payload == null ->
                    reportAndAbort(VerifyError.MissingSumPayload(
                        at = id, caseName = node.caseName,
                        expectedType = expectedPayloadType
                    ))
                expectedPayloadType != null && payload != null -> {
                    val actualType = infer(payload, scope, typeParams)
                    if (!typesCompatible(expectedPayloadType, actualType)) {
                        reportAndAbort(VerifyError.SumPayloadTypeMismatch(
                            at = id, caseName = node.caseName,
                            expected = expectedPayloadType,
                            actual = actualType
                        ))
                    }
                    // Layer 7: re-record SchemaType at the payload's
                    // NodeId so SchemaChecker can find it (same rule as
                    // for Application arguments and ProductFieldValues).
                    if (expectedPayloadType is TypeExpr.SchemaType && actualType !is TypeExpr.SchemaType) {
                        record(payload, expectedPayloadType)
                    }
                }
                // both null: nullary case with no payload — OK.
                else -> Unit
            }
            recordClosure(id, payload?.let { closureOf(it) } ?: emptySet())
            // Return the *declared* type (which may be the Recursive form),
            // not the unfolded one — equirecursive equality treats them as
            // equal, but the declared form is the canonical representative.
            return declaredType
        }

        private fun reportAndAbort(err: VerifyError): Nothing {
            report(err)
            throw VerifyAbort()
        }

        private fun inferProductFieldGet(
            id: NodeId,
            node: Node.ProductFieldGet,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>,
        ): TypeExpr {
            val targetType = infer(node.target, scope, typeParams)
            if (targetType !is TypeExpr.Product) {
                report(VerifyError.CategoryMismatch(
                    at = id, field = "ProductFieldGet.target type",
                    expectedCategory = "ProductType",
                    actualCategory = targetType::class.simpleName ?: "?"
                ))
                throw VerifyAbort()
            }
            val field = targetType.fields.firstOrNull { it.name == node.fieldName }
                ?: run {
                    report(VerifyError.UnknownProductValueField(
                        at = id,
                        fieldName = node.fieldName,
                        declared = targetType.fields.map { it.name }.toSet()
                    ))
                    throw VerifyAbort()
                }
            recordClosure(id, closureOf(node.target))
            return field.type
        }

        private fun inferFixpoint(
            id: NodeId,
            node: Node.Fixpoint,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>,
        ): TypeExpr {
            // 1. recursionType must resolve to a FunctionType.
            val recT = resolveType(node.recursionType, typeParams)
            if (recT !is TypeExpr.Fun) {
                report(VerifyError.CategoryMismatch(
                    at = id, field = "Fixpoint.recursionType",
                    expectedCategory = "FunctionType",
                    actualCategory = recT::class.simpleName ?: "?"
                ))
                throw VerifyAbort()
            }

            // 2. body must be a Lambda.
            val bodyNode = store.getOrNull(node.body)
                ?: reportFatal(VerifyError.DanglingReference(
                    at = id, missing = node.body, fromField = "Fixpoint.body"
                ))
            if (bodyNode !is Node.Lambda) {
                report(VerifyError.CategoryMismatch(
                    at = id, field = "Fixpoint.body",
                    expectedCategory = "Lambda",
                    actualCategory = categoryName(bodyNode)
                ))
                throw VerifyAbort()
            }

            // 3. Infer the body's type and confirm it matches the expected
            //    shape: ((recursionType, ...recursionType.parameters) ->
            //    recursionType.result) ![recursionType.effects]. The body
            //    Lambda's first parameter is the self/recursion slot.
            val bodyType = infer(node.body, scope, typeParams)
            if (bodyType !is TypeExpr.Fun) {
                report(VerifyError.CategoryMismatch(
                    at = id, field = "Fixpoint.body type",
                    expectedCategory = "FunctionType",
                    actualCategory = bodyType::class.simpleName ?: "?"
                ))
                throw VerifyAbort()
            }
            val expected = TypeExpr.Fun(
                parameters = listOf(recT) + recT.parameters,
                result = recT.result,
                effects = recT.effects,
            )
            if (bodyType != expected) {
                report(VerifyError.FixpointBodyShapeMismatch(
                    at = id,
                    expected = expected,
                    actual = bodyType,
                ))
                throw VerifyAbort()
            }

            // Constructing a Fixpoint exercises no effects (same as Lambda).
            // Effects are released when the Fixpoint's value is applied —
            // handled in inferApplication via the function's type effects.
            recordClosure(id, emptySet())
            return recT
        }

        private fun inferForeignNode(
            id: NodeId,
            node: Node.ForeignNode,
            typeParams: Set<NodeId>
        ): TypeExpr {
            // The ForeignNode's value-level type is its foreignType (which
            // must be a FunctionType). Its declared effects are authoritative
            // per ADR-005 and override any effects the foreignType itself
            // happens to carry — agents typically declare effects at the
            // ForeignNode level and leave the FunctionType signature purely
            // for parameters/result.
            val fType = resolveType(node.foreignType, typeParams)
            if (fType !is TypeExpr.Fun) {
                report(VerifyError.CategoryMismatch(
                    at = id, field = "ForeignNode.foreignType",
                    expectedCategory = "FunctionType",
                    actualCategory = fType::class.simpleName ?: "?"
                ))
                throw VerifyAbort()
            }
            val declaredEffects = validateEffectCategoryEdges(id, node.effects, "ForeignNode.effects")
            // Q-039: validate the optional effectProjections against the
            // declared effects list and the signature's parameter shape.
            // Empty list is the legacy path (Q-031 semantics retained).
            validateProjections(
                at = id,
                effects = node.effects,
                effectProjections = node.effectProjections,
                signatureParameterTypes = fType.parameters,
            )
            // Evaluating a ForeignNode produces a callable value — no effects
            // fire at construction. Effects release at call sites (handled
            // in inferApplication via the function's type effects).
            recordClosure(id, emptySet())
            return TypeExpr.Fun(
                parameters = fType.parameters,
                result = fType.result,
                effects = fType.effects + declaredEffects,
            )
        }

        private fun inferCapabilityScope(
            id: NodeId,
            node: Node.CapabilityScope,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>
        ): TypeExpr {
            // Each entry in `capabilities` must be an EffectCategory node.
            val narrowedCapabilities =
                validateEffectCategoryEdges(id, node.capabilities, "CapabilityScope.capabilities")

            val bodyType = infer(node.body, scope, typeParams)
            val bodyClosure = closureOf(node.body)

            // Well-formedness: the body's closure must be a subset of the
            // narrowed capability set. If the body would need an effect the
            // narrowed scope does not permit, the runtime would always fail —
            // the verifier flags it statically.
            val missing = bodyClosure - narrowedCapabilities
            if (missing.isNotEmpty()) {
                report(VerifyError.CapabilityScopeUnsatisfiable(at = id, missing = missing))
                throw VerifyAbort()
            }

            // The CapabilityScope's closure is the body's closure — the scope
            // narrows the *runtime* context but does not change which effects
            // a caller of this expression should expect to see exercised.
            recordClosure(id, bodyClosure)
            return bodyType
        }

        /**
         * Type and closure-check a [Node.Handler] per the proposal § 6.
         *
         * 1. `intercept` must be a non-dangling EffectCategory.
         * 2. The `handle` expression must type-check to a monomorphic
         *    [TypeExpr.Fun]; a [TypeExpr.Forall] is rejected as
         *    [VerifyError.HandlerOverPolymorphicHandle]; any other type
         *    as [VerifyError.HandlerNotAFunction].
         * 3. Every Application reachable in the `body` whose callee's
         *    FunctionType.effects contains `intercept` must have
         *    value-argument types structurally equal to the handler's
         *    parameters and result type equal to the handler's result.
         *    Mismatches surface as [VerifyError.HandlerSignatureMismatch].
         * 4. Closure subtraction: `closureOf(handler) = (closureOf(body)
         *    - {intercept}) ∪ closureOf(handle)`. This is the novel
         *    property — Handler is the only node category that removes an
         *    effect from a closure. The handler's own closure flows into
         *    the surrounding context (so a handler that writes to a sink
         *    still requires Memory.MutableState there); the intercepted
         *    effect is consumed inside.
         *
         * The Handler's type at value position is the body's type — the
         * intercepted calls return values of the body's effective result
         * type, which is unchanged by handler installation.
         */
        private fun inferHandler(
            id: NodeId,
            node: Node.Handler,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>
        ): TypeExpr {
            // 1. Validate the intercept edge points at a real EffectCategory.
            validateEffectCategoryEdges(id, listOf(node.intercept), "Handler.intercept")

            // 2. Type the handler expression. Must be a monomorphic Fun.
            val handleType = infer(node.handle, scope, typeParams)
            val handleFun: TypeExpr.Fun = when (handleType) {
                is TypeExpr.Fun -> handleType
                is TypeExpr.Forall -> {
                    report(VerifyError.HandlerOverPolymorphicHandle(at = id, residual = handleType))
                    throw VerifyAbort()
                }
                else -> {
                    report(VerifyError.HandlerNotAFunction(at = id, gotType = handleType))
                    throw VerifyAbort()
                }
            }

            // 3. Type the body. We need its type AND we need to walk every
            //    reachable Application to find ones intercepted by this
            //    handler. (closureOf(body) tells us *whether* the intercept
            //    fires anywhere, but we still need the per-call traversal
            //    to verify signature agreement.)
            val bodyType = infer(node.body, scope, typeParams)
            val bodyClosure = closureOf(node.body)

            // 4. Per-Application signature check. Walk the body's structural
            //    subtree; for every Application whose callee declares the
            //    intercepted category, confirm the value-argument types and
            //    result type match the handler's signature.
            if (node.intercept in bodyClosure) {
                checkHandlerSignatureAgreement(id, node.intercept, handleFun, node.body)
            }

            // 5. Closure subtraction. Per § 6.3 of the proposal:
            //    closureOf(handler) = (closureOf(body) - {intercept})
            //                       ∪ closureOf(handle)
            //                       ∪ <effects the handle function declares>
            //
            // The static `closureOf(handle)` covers effects exercised while
            // *evaluating* the handle expression (typically empty — handle
            // is a Lambda or VarRef, both of which build values without
            // exercising effects). The handler function's declared effects
            // are added separately because the handler WILL be invoked at
            // every intercepted call site, so its effects flow into the
            // surrounding context just as if the call site directly
            // declared them. Without this, a handler that itself performs
            // an effect would have its effects silently absorbed and the
            // surrounding context would not need to cover them — exactly
            // the inverse of the desired semantics.
            val newClosure = (bodyClosure - node.intercept) +
                closureOf(node.handle) +
                handleFun.effects
            recordClosure(id, newClosure)

            // The Handler's value type is the body's type — installing a
            // handler does not change what value the body produces.
            return bodyType
        }

        /**
         * Walk the subtree rooted at [bodyId] and, for every Application
         * whose function type declares the intercepted [intercept]
         * category, confirm the call's parameter types and result type
         * match [expected]. Uses the verifier's `nodeTypes` map (already
         * populated by `infer`) to look up types without re-inferring.
         */
        private fun checkHandlerSignatureAgreement(
            handlerId: NodeId,
            intercept: NodeId,
            expected: TypeExpr.Fun,
            bodyId: NodeId,
        ) {
            val visited = HashSet<NodeId>()
            fun visit(id: NodeId) {
                if (!visited.add(id)) return
                val node = store.getOrNull(id) ?: return
                when (node) {
                    is Node.Application -> {
                        // Walk children first (function expression, args).
                        // Then check this Application itself.
                        visit(node.function)
                        node.arguments.forEach(::visit)
                        node.typeArguments.forEach(::visit)
                        node.effectInstances.forEach(::visit)

                        val fnType = nodeTypes[node.function] ?: return
                        val fnFun: TypeExpr.Fun = when (fnType) {
                            is TypeExpr.Fun -> fnType
                            else -> return  // not a directly-typed function call here
                        }
                        if (intercept !in fnFun.effects) return

                        // This Application would be intercepted by the
                        // handler. Confirm signature agreement.
                        val argTypes = node.arguments.mapNotNull { nodeTypes[it] }
                        val actual = TypeExpr.Fun(
                            parameters = argTypes,
                            result = fnFun.result,
                            effects = emptySet(),  // not relevant to signature match
                        )
                        val expectedShape = TypeExpr.Fun(
                            parameters = expected.parameters,
                            result = expected.result,
                            effects = emptySet(),
                        )
                        if (actual != expectedShape) {
                            report(VerifyError.HandlerSignatureMismatch(
                                at = handlerId,
                                atCall = id,
                                expected = expected,
                                actual = fnFun,
                            ))
                            throw VerifyAbort()
                        }
                    }
                    is Node.Lambda -> {
                        // Walk the body; handlers cross Lambda boundaries —
                        // a Lambda inside the Handler's body may itself
                        // invoke intercepted operations. Don't walk into
                        // parameter types; those are type-position children.
                        visit(node.body)
                    }
                    is Node.Let -> {
                        visit(node.value)
                        visit(node.body)
                    }
                    is Node.TypeAbstraction -> visit(node.body)
                    is Node.CapabilityScope -> visit(node.body)
                    is Node.NodeRef -> {
                        val targetId = hashToNodeId[node.target] ?: return
                        visit(targetId)
                    }
                    is Node.Match -> {
                        visit(node.scrutinee)
                        node.cases.forEach(::visit)
                    }
                    is Node.MatchCase -> {
                        // Don't descend into the pattern: patterns don't
                        // contain Applications. Just walk the body.
                        visit(node.body)
                    }
                    is Node.Fixpoint -> visit(node.body)
                    is Node.ProductValue -> node.fields.forEach(::visit)
                    is Node.ProductFieldValue -> visit(node.value)
                    is Node.ProductFieldGet -> visit(node.target)
                    is Node.SumValue -> node.payload?.let(::visit)
                    is Node.Handler -> {
                        // A nested Handler shadows the outer one for its
                        // own intercept. Per § 7.4 of the proposal, the
                        // innermost wins. The OUTER walk still applies to
                        // calls in the nested Handler's body — but only
                        // for the OUTER handler's intercept category, and
                        // only if the nested Handler doesn't intercept
                        // the same category. If the nested Handler
                        // intercepts the same category, calls within its
                        // body are consumed by the inner handler, not the
                        // outer one. Closure subtraction at the nested
                        // Handler already excluded such calls from the
                        // outer body's closure, so they shouldn't surface
                        // here either: only descend into the nested
                        // Handler's body when the nested Handler does
                        // NOT intercept the same category. The nested
                        // Handler's `handle` expression IS in the outer
                        // body's surrounding context (it runs alongside
                        // the outer body), so we visit it.
                        visit(node.handle)
                        if (node.intercept != intercept) {
                            visit(node.body)
                        }
                    }
                    // Leaf / non-recurring nodes. State-machine wiring
                    // nodes (StateMachine/EventStream/Transition) cannot
                    // appear inside a Handler's body in step 1 — the
                    // verifier rejects them as expressions upstream — but
                    // we list them for the exhaustiveness check.
                    is Node.VarRef,
                    is Node.IntLit, is Node.FloatLit, is Node.StringLit,
                    is Node.BoolLit, Node.UnitLit, is Node.BytesLit,
                    is Node.ForeignNode,
                    is Node.PrimitiveType, is Node.ProductType, is Node.ProductTypeField,
                    is Node.SumType, is Node.SumTypeCase, is Node.FunctionType,
                    is Node.TypeParameter, is Node.ForallType, is Node.ParameterDecl,
                    is Node.EffectCategory, is Node.EffectDecl, is Node.Pattern,
                    is Node.RecursiveType, is Node.RecursiveSelf,
                    is Node.StateMachine, is Node.EventStream, is Node.Transition,
                    is Node.Schema, is Node.Invariant, is Node.ToolDef,
                    is Node.ResponseSchemaSpec,
                    is Node.ModuleManifest -> Unit
                }
            }
            visit(bodyId)
        }

        /**
         * Type-check a ToolDef (N-044) per `agent-native-capabilities.md`
         * § 3.8 and `node-algebra.md` § Agent-native capabilities.
         *
         * Rules:
         *  1. `parameterSchema` must resolve to a [Node.Schema] (else
         *     [VerifyError.CategoryMismatch]).
         *  2. The Schema's `valueType` (the type inputs the tool accepts
         *     at the provider boundary) must project to JSON Schema via
         *     [JsonSchemaProjection.project]. On `Rejected`, report
         *     [VerifyError.ToolParamTypeUnsupported] with the rejection
         *     reason — this is the static enforcement of proposal § 3.8.1.
         *  3. `implementation` must type-check to a monomorphic function
         *     `parameterSchema.valueType -> R` for some result type `R`
         *     (a [TypeExpr.Forall] is rejected as
         *     `ToolImplementationOverPolymorphic`; any other shape as
         *     `ToolImplementationNotAFunction`; a function whose first
         *     parameter type doesn't equal the schema's valueType as
         *     `ToolImplementationParameterTypeMismatch`).
         *
         * The ToolDef's own value-level type is opaque (returned as
         * `Bytes`) — matching the existing convention for
         * runtime-only structures like `Value.Resource` and `Value.MapV`.
         * Agents declare `tools: List<ToolDef>` in the GenerateRequest
         * using `bytesT` as the surface placeholder; at runtime, the
         * LLM.Generate builtin walks the list looking for ToolDef
         * NodeRef shapes via [Value.ToolDefV].
         *
         * Effect closure of a ToolDef is empty — constructing the
         * declaration does not exercise any effect. The implementation's
         * declared effects are released only at the tool's call site
         * during the provider's tool-use loop, where the surrounding
         * capability context governs.
         */
        private fun inferToolDef(
            id: NodeId,
            node: Node.ToolDef,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>
        ): TypeExpr {
            // 1. parameterSchema must point at a Schema.
            val schemaNode = store.getOrNull(node.parameterSchema)
                ?: reportFatal(VerifyError.DanglingReference(
                    at = id, missing = node.parameterSchema,
                    fromField = "ToolDef.parameterSchema"
                ))
            if (schemaNode !is Node.Schema) {
                report(VerifyError.CategoryMismatch(
                    at = id, field = "ToolDef.parameterSchema",
                    expectedCategory = "Schema",
                    actualCategory = categoryName(schemaNode)
                ))
                throw VerifyAbort()
            }
            // Resolve the Schema in type position to obtain its SchemaType.
            val schemaType = resolveType(node.parameterSchema, typeParams)
            if (schemaType !is TypeExpr.SchemaType) {
                // resolveSchema returns SchemaType so this should not
                // happen — defensive guard.
                report(VerifyError.CategoryMismatch(
                    at = id, field = "ToolDef.parameterSchema (resolved)",
                    expectedCategory = "SchemaType",
                    actualCategory = schemaType::class.simpleName ?: "?"
                ))
                throw VerifyAbort()
            }

            // 2. The schema's valueType must project to JSON Schema.
            //    This is the static enforcement of ToolParamTypeUnsupported
            //    promised by proposal § 3.8.1 / § 5.
            when (val projection = JsonSchemaProjection.project(schemaType.valueType)) {
                is JsonSchemaProjection.Result.Success -> Unit
                is JsonSchemaProjection.Result.Rejected -> {
                    report(VerifyError.ToolParamTypeUnsupported(
                        at = id,
                        toolDefId = id,
                        rejectedType = projection.type,
                        reason = projection.reason.name,
                    ))
                    throw VerifyAbort()
                }
            }

            // 3. implementation must have type `valueType -> R`. We accept
            //    any callable shape (Lambda, ForeignNode, FixpointFn) by
            //    requiring the implementation expression's type to be a
            //    monomorphic Fun whose first parameter equals valueType.
            //    Polymorphic implementations (TypeAbstraction whose body is
            //    a Lambda) must be monomorphized at the ToolDef construction
            //    site — they are rejected here.
            val implType = infer(node.implementation, scope, typeParams)
            val implFun: TypeExpr.Fun = when (implType) {
                is TypeExpr.Fun -> implType
                is TypeExpr.Forall -> {
                    report(VerifyError.ToolImplementationOverPolymorphic(
                        at = id, residual = implType
                    ))
                    throw VerifyAbort()
                }
                else -> {
                    report(VerifyError.ToolImplementationNotAFunction(
                        at = id, gotType = implType
                    ))
                    throw VerifyAbort()
                }
            }
            if (implFun.parameters.size != 1) {
                report(VerifyError.ToolImplementationArityMismatch(
                    at = id, expected = 1, actual = implFun.parameters.size
                ))
                throw VerifyAbort()
            }
            if (!typesCompatible(schemaType.valueType, implFun.parameters[0])) {
                report(VerifyError.ToolImplementationParameterTypeMismatch(
                    at = id,
                    expected = schemaType.valueType,
                    actual = implFun.parameters[0],
                ))
                throw VerifyAbort()
            }

            // Constructing a ToolDef declaration exercises no effects.
            // The implementation's effects fire only at tool-dispatch
            // sites during the provider's loop; the surrounding
            // capability context covers them there.
            recordClosure(id, emptySet())
            // Surface type: opaque Bytes (Strand-side opaque-handle
            // convention, matching Resource / MapV).
            return TypeExpr.Prim(Primitive.Bytes)
        }

        /**
         * Type-check a ResponseSchemaSpec (N-045) per
         * `agent-native-capabilities.md` § 3.7 and `node-algebra.md`
         * § Agent-native capabilities.
         *
         * Rules:
         *  1. `schema` must resolve to a [Node.Schema] (else
         *     [VerifyError.CategoryMismatch]).
         *  2. The Schema's `valueType` (the type the provider's
         *     constrained-decoding pass must produce) must project to JSON
         *     Schema via [JsonSchemaProjection.project]. On `Rejected`,
         *     report [VerifyError.ResponseSchemaTypeUnsupported] with the
         *     rejection reason — this is the static enforcement that
         *     proposal § 3.7's "translate the Schema's valueType into JSON
         *     Schema" step is well-defined for every Schema reaching a
         *     responseSchema position.
         *
         * Symmetric to [inferToolDef] (N-044) — both wrappers admit a
         * Schema reference into a value position whose runtime carrier
         * the LLM.Generate builtin consumes. ResponseSchemaSpec is the
         * thinner of the two: no implementation to dispatch and no
         * metadata to forward, so steps 1 and 2 cover the entire rule.
         *
         * The wrapper's value-level type is opaque (returned as `Bytes`),
         * matching the ToolDef convention. Agents declare
         * `responseSchema: Option<ResponseSchemaSpec>` in the
         * GenerateRequest using `bytesT` as the Some-payload placeholder;
         * the LLM.Generate builtin walks the request looking for a
         * `Value.ResponseSchemaSpecV` in the Some payload.
         *
         * Effect closure is empty — constructing the wrapper exercises
         * no effects.
         */
        private fun inferResponseSchemaSpec(
            id: NodeId,
            node: Node.ResponseSchemaSpec,
            typeParams: Set<NodeId>
        ): TypeExpr {
            // 1. schema must point at a Schema.
            val schemaNode = store.getOrNull(node.schema)
                ?: reportFatal(VerifyError.DanglingReference(
                    at = id, missing = node.schema,
                    fromField = "ResponseSchemaSpec.schema"
                ))
            if (schemaNode !is Node.Schema) {
                report(VerifyError.CategoryMismatch(
                    at = id, field = "ResponseSchemaSpec.schema",
                    expectedCategory = "Schema",
                    actualCategory = categoryName(schemaNode)
                ))
                throw VerifyAbort()
            }
            // Resolve the Schema in type position to obtain its SchemaType.
            val schemaType = resolveType(node.schema, typeParams)
            if (schemaType !is TypeExpr.SchemaType) {
                // Defensive guard — resolveSchema returns SchemaType so
                // this should not happen on a well-formed graph.
                report(VerifyError.CategoryMismatch(
                    at = id, field = "ResponseSchemaSpec.schema (resolved)",
                    expectedCategory = "SchemaType",
                    actualCategory = schemaType::class.simpleName ?: "?"
                ))
                throw VerifyAbort()
            }

            // 2. The schema's valueType must project to JSON Schema.
            //    This is the static enforcement of the proposal § 3.7
            //    constrained-decoding contract: the provider library
            //    cannot submit a JSON Schema for a TypeExpr variant
            //    JsonSchemaProjection rejects.
            when (val projection = JsonSchemaProjection.project(schemaType.valueType)) {
                is JsonSchemaProjection.Result.Success -> Unit
                is JsonSchemaProjection.Result.Rejected -> {
                    report(VerifyError.ResponseSchemaTypeUnsupported(
                        at = id,
                        specId = id,
                        rejectedType = projection.type,
                        reason = projection.reason.name,
                    ))
                    throw VerifyAbort()
                }
            }

            recordClosure(id, emptySet())
            // Surface type: opaque Bytes (Strand-side opaque-handle
            // convention, matching ToolDef / Resource / MapV). Agents
            // place the wrapper into the GenerateRequest's
            // `responseSchema: Option<bytesT>` Some payload at the call
            // site.
            return TypeExpr.Prim(Primitive.Bytes)
        }

        /**
         * Type-check a StateMachine (N-027) per § 4 of the proposal.
         *
         * Layer 6 step 2 well-formedness rules (step 1 in parentheses where
         * the rule was tightened or extended):
         *
         * 1. `inputStreams.size >= 1` (step 1 also required exactly 1; step 2
         *    lifts that bound — multi-stream machines are now well-formed).
         *    Zero input streams remains [VerifyError.StateMachineRequiresInputStream].
         * 2. Each entry of `inputStreams` and `outputStreams` resolves to an
         *    `EventStream` node.
         * 3. `transitionFn` resolves to a Lambda (Fixpoint-wrapped transitions
         *    are step 3) whose type is `(State, Event) -> (State, Outputs)`.
         * 4. The State half of the transition signature equals the inferred
         *    type of `initialState`.
         * 5. The Event half equals the input streams' joined eventType.
         *    For single-input machines this is `inputStreams[0].eventType`
         *    (preserves corpus 41–45 unchanged). For multi-input machines
         *    the verifier synthesizes the InputEvent sum
         *    `stream_0(T_0) | stream_1(T_1) | ... | stream_{n-1}(T_{n-1})`
         *    and requires structural equality — matches the runtime's
         *    `MachineActor.wrapEvent` payload-wrapping convention.
         * 6. The Outputs half can take either of two shapes (the runtime
         *    dispatches based on the produced value, so either is accepted):
         *    - **OutputBatch** (step-1 fixed-arity product): a ProductType
         *      with field `output_i: Option<outputStreams[i].eventType>` at
         *      each position. Empty `outputStreams` ⇒ the empty product `{}`.
         *    - **Tagged-list** (step-2 recursive list): the recursive type
         *      `μ. Cons({head: TaggedOutput, tail: <self>}) | Nil` where
         *      `TaggedOutput = output_0(T_0) | ... | output_{n-1}(T_{n-1})`.
         *      Strictly more expressive — a transition may emit zero, one,
         *      or many events per step, to any combination of output streams.
         * 7. Declared effects cover the transition Lambda's effect closure.
         *    The implicit StateMachine.Send/Receive effects (E-028/E-029)
         *    remain deferred — the runtime performs send/receive at the
         *    boundary, not the user code, so the existing coverage check is
         *    sufficient.
         *
         * StateMachine is not an expression — it does not evaluate to a
         * Value. The verifier returns `Prim(Unit)` as a placeholder so
         * callers that look up the node's type in `nodeTypes` find something
         * sensible; the runtime consumes the StateMachine via `runtime/`'s
         * API, not via `Interpreter.eval`.
         */
        private fun inferStateMachine(
            id: NodeId,
            node: Node.StateMachine,
            scope: Map<NodeId, TypeExpr>,
            typeParams: Set<NodeId>,
        ): TypeExpr {
            // 1. inputStreams arity check. Step 2 lifts the step-1 ==1
            //    bound; zero remains ill-formed.
            if (node.inputStreams.isEmpty()) {
                report(VerifyError.StateMachineRequiresInputStream(at = id))
                throw VerifyAbort()
            }

            // 2. inputStreams / outputStreams must resolve to EventStream
            //    nodes. Validate them and extract event types.
            val inputStreamNodes = node.inputStreams.mapIndexed { i, streamId ->
                resolveEventStream(id, streamId, "StateMachine.inputStreams[$i]", typeParams)
            }
            val outputStreamNodes = node.outputStreams.mapIndexed { i, streamId ->
                resolveEventStream(id, streamId, "StateMachine.outputStreams[$i]", typeParams)
            }
            val inputEventTypes = inputStreamNodes.map { it.second }
            val outputEventTypes = outputStreamNodes.map { it.second }
            val eventType: TypeExpr = if (inputEventTypes.size == 1) {
                inputEventTypes[0]
            } else {
                synthesizeInputEventSum(inputEventTypes)
            }

            // 3. transitionFn must be a Lambda.
            val transitionFnNode = store.getOrNull(node.transitionFn)
                ?: reportFatal(VerifyError.DanglingReference(
                    at = id, missing = node.transitionFn,
                    fromField = "StateMachine.transitionFn"
                ))
            if (transitionFnNode !is Node.Lambda) {
                report(VerifyError.StateMachineTransitionFnNotLambda(
                    at = id, actualCategory = categoryName(transitionFnNode)
                ))
                throw VerifyAbort()
            }
            val transitionFnType = infer(node.transitionFn, scope, typeParams)
            if (transitionFnType !is TypeExpr.Fun) {
                report(VerifyError.StateMachineTransitionFnShapeMismatch(
                    at = id,
                    expected = TypeExpr.Prim(Primitive.Unit),  // best-effort placeholder
                    actual = transitionFnType,
                ))
                throw VerifyAbort()
            }

            // 4. initialState type — infer first; the State type derives from
            //    it (we then check the transition function's first parameter
            //    matches).
            val initialStateType = infer(node.initialState, scope, typeParams)

            // 5. + 6. Build both expected transition signatures. The runtime
            //    accepts either OutputBatch (step-1 fixed-arity) or
            //    tagged-list (step-2 recursive list) for the `outputs` field;
            //    the verifier matches the structurally-equivalent shape and
            //    accepts the transition if it conforms to either.
            val expectedOutputBatchT = expectedOutputBatchType(id, outputEventTypes)
            val expectedTaggedListT = synthesizeTaggedOutputListType(outputEventTypes)
            val expectedResultOutputBatch = TypeExpr.Product(
                origin = id,  // synthetic — origin is ignored by equality
                fields = listOf(
                    TypeExpr.Product.Field("state", initialStateType),
                    TypeExpr.Product.Field("outputs", expectedOutputBatchT),
                ),
            )
            val expectedResultTaggedList = TypeExpr.Product(
                origin = id,
                fields = listOf(
                    TypeExpr.Product.Field("state", initialStateType),
                    TypeExpr.Product.Field("outputs", expectedTaggedListT),
                ),
            )
            val expectedTransitionOutputBatch = TypeExpr.Fun(
                parameters = listOf(initialStateType, eventType),
                result = expectedResultOutputBatch,
                effects = transitionFnType.effects,
            )
            val expectedTransitionTaggedList = TypeExpr.Fun(
                parameters = listOf(initialStateType, eventType),
                result = expectedResultTaggedList,
                effects = transitionFnType.effects,
            )
            if (transitionFnType != expectedTransitionOutputBatch &&
                transitionFnType != expectedTransitionTaggedList) {
                // Pinpoint failures. First the cheaper checks: argument
                // arity, then State parameter equality.
                if (transitionFnType.parameters.size != 2) {
                    report(VerifyError.StateMachineTransitionFnShapeMismatch(
                        at = id,
                        expected = expectedTransitionOutputBatch,
                        actual = transitionFnType,
                    ))
                    throw VerifyAbort()
                }
                val tfState = transitionFnType.parameters[0]
                if (tfState != initialStateType) {
                    report(VerifyError.StateMachineInitialStateTypeMismatch(
                        at = id,
                        expected = tfState,
                        actual = initialStateType,
                    ))
                    throw VerifyAbort()
                }
                // Parameters OK; result shape diverges from both expected
                // shapes. If the actual result is a Product whose `outputs`
                // field is itself a Product (the OutputBatch attempt path),
                // use the per-slot output_i diagnostic so the user can
                // locate the wrong slot.
                val tfResult = transitionFnType.result
                if (tfResult is TypeExpr.Product) {
                    val actualOutputs = tfResult.fields
                        .firstOrNull { it.name == "outputs" }
                        ?.type
                    if (actualOutputs is TypeExpr.Product) {
                        for ((i, expectedEvtType) in outputEventTypes.withIndex()) {
                            val slotName = "output_$i"
                            val actualSlot = actualOutputs.fields
                                .firstOrNull { it.name == slotName }
                                ?.type
                            val expectedSlot = optionTypeOf(expectedEvtType)
                            if (actualSlot != null && actualSlot != expectedSlot) {
                                report(VerifyError.OutputStreamEventTypeMismatch(
                                    at = id,
                                    streamIndex = i,
                                    expected = expectedSlot,
                                    actual = actualSlot,
                                ))
                                throw VerifyAbort()
                            }
                        }
                    }
                }
                // Fall-through: shape mismatch we cannot pinpoint further.
                // Prefer the tagged-list expected for multi-input machines
                // (those are most likely to be opting into the new shape);
                // prefer OutputBatch for single-input (preserves the
                // diagnostics step 1 corpus authors are used to).
                val preferredExpected = if (inputEventTypes.size > 1)
                    expectedTransitionTaggedList else expectedTransitionOutputBatch
                report(VerifyError.StateMachineTransitionFnShapeMismatch(
                    at = id,
                    expected = preferredExpected,
                    actual = transitionFnType,
                ))
                throw VerifyAbort()
            }

            // 7. Effect-coverage check. Declared effects must cover the
            //    transition Lambda's closure.
            val declaredEffects = validateEffectCategoryEdges(
                id, node.effects, "StateMachine.effects"
            )
            val missing = transitionFnType.effects - declaredEffects
            if (missing.isNotEmpty()) {
                report(VerifyError.StateMachineEffectCoverageViolation(
                    at = id, missing = missing
                ))
                throw VerifyAbort()
            }

            // 8. Implicit Send/Receive check (Layer 6 step 3 slice 3.5).
            //    The runtime performs StateMachine.Receive at every input
            //    pull and StateMachine.Send at every output push; the
            //    machine's `effects` list must acknowledge these by
            //    declaring EffectCategory nodes with the well-known names.
            //    The check looks at the categoryName of each declared
            //    EffectCategory, not the NodeId — multiple machines may
            //    share or duplicate the EffectCategory node.
            val declaredNames: Set<String> = declaredEffects.mapNotNull { effectId ->
                (store.getOrNull(effectId) as? Node.EffectCategory)?.categoryName
            }.toSet()
            val missingImplicit = mutableSetOf<String>()
            if (WellKnownEffect.StateMachineReceive.categoryName !in declaredNames) {
                missingImplicit += WellKnownEffect.StateMachineReceive.categoryName
            }
            if (node.outputStreams.isNotEmpty() &&
                WellKnownEffect.StateMachineSend.categoryName !in declaredNames
            ) {
                missingImplicit += WellKnownEffect.StateMachineSend.categoryName
            }
            if (missingImplicit.isNotEmpty()) {
                report(VerifyError.StateMachineMissingImplicitEffect(
                    at = id, missing = missingImplicit
                ))
                throw VerifyAbort()
            }

            // StateMachine is not an expression. We record a placeholder
            // type (Unit) so downstream nodeTypes lookups find something
            // sensible; closure is empty (the StateMachine node itself does
            // not release effects — the runtime drives the transition
            // function and capability checks fire at the runtime boundary).
            recordClosure(id, emptySet())
            return TypeExpr.Prim(Primitive.Unit)
        }

        /**
         * Synthesize the InputEvent sum type for a multi-input machine.
         * Cases are positionally indexed by input-stream position:
         * `stream_0(T_0)`, `stream_1(T_1)`, ..., `stream_{n-1}(T_{n-1})`.
         * Matches the runtime's [org.strand.runtime.MachineActor.wrapEvent]
         * payload-wrapping convention.
         *
         * Origin is synthetic ([NodeId(-1)]); structural equality on
         * [TypeExpr.Sum] ignores `origin`, so a user-declared sum with the
         * same case set in the same order compares equal.
         */
        private fun synthesizeInputEventSum(inputEventTypes: List<TypeExpr>): TypeExpr.Sum =
            TypeExpr.Sum(
                origin = NodeId(-1),
                cases = inputEventTypes.mapIndexed { i, t ->
                    TypeExpr.Sum.Case("stream_$i", t)
                }
            )

        /**
         * Synthesize the tagged-output list type for a machine's outputs.
         * Shape: `μ. Cons({head: TaggedOutput, tail: <self>}) | Nil` where
         * `TaggedOutput = output_0(T_0) | output_1(T_1) | ... | output_{n-1}(T_{n-1})`.
         *
         * The case order `Cons | Nil` and the field order `head, tail` match
         * the corpus 31/32 (recursive-list-head, recursive-list-sum)
         * convention. The runtime's
         * [org.strand.runtime.MachineActor.dispatchTaggedList] tolerates
         * either case order (it dispatches by string name); the verifier
         * uses strict structural equality, so the canonical order pinned
         * here is what users must match.
         *
         * For machines with zero output streams, the TaggedOutput sum is
         * empty (no cases) — `Cons` cannot be constructed, only `Nil` —
         * which makes the tagged-list shape unusable in practice. Such
         * machines should use the OutputBatch shape (an empty product `{}`)
         * instead; both are accepted by §6.
         */
        private fun synthesizeTaggedOutputListType(outputEventTypes: List<TypeExpr>): TypeExpr.Recursive {
            val taggedSum = TypeExpr.Sum(
                origin = NodeId(-1),
                cases = outputEventTypes.mapIndexed { i, t ->
                    TypeExpr.Sum.Case("output_$i", t)
                }
            )
            val consPayload = TypeExpr.Product(
                origin = NodeId(-1),
                fields = listOf(
                    TypeExpr.Product.Field("head", taggedSum),
                    TypeExpr.Product.Field("tail", TypeExpr.RecursiveSelf()),
                )
            )
            val listBody = TypeExpr.Sum(
                origin = NodeId(-1),
                cases = listOf(
                    TypeExpr.Sum.Case("Cons", consPayload),
                    TypeExpr.Sum.Case("Nil", null),
                )
            )
            return TypeExpr.Recursive(listBody)
        }

        /**
         * Resolve [streamId] as an [Node.EventStream] reachable from a
         * StateMachine. Returns (the EventStream node, the resolved eventType
         * as a TypeExpr). Reports and aborts on dangling refs, category
         * mismatches, or malformed eventTypes.
         */
        private fun resolveEventStream(
            atStateMachine: NodeId,
            streamId: NodeId,
            fieldLabel: String,
            typeParams: Set<NodeId>,
        ): Pair<Node.EventStream, TypeExpr> {
            val streamNode = store.getOrNull(streamId)
                ?: run {
                    report(VerifyError.DanglingReference(
                        at = atStateMachine, missing = streamId,
                        fromField = fieldLabel,
                    ))
                    throw VerifyAbort()
                }
            if (streamNode !is Node.EventStream) {
                report(VerifyError.CategoryMismatch(
                    at = atStateMachine, field = fieldLabel,
                    expectedCategory = "EventStream",
                    actualCategory = categoryName(streamNode),
                ))
                throw VerifyAbort()
            }
            // Validate the eventType resolves to a real type. resolveType
            // already reports MalformedType / CategoryMismatch / dangling
            // ref errors on unresolvable types and aborts. We let it do so
            // — its error is more specific than MalformedEventStream would
            // be. MalformedEventStream is reserved for shape problems we
            // catch *before* falling through to resolveType (currently
            // none, since the EventStream's eventType field type is
            // type-position by construction).
            val eventType = resolveType(streamNode.eventType, typeParams)
            // Slice 3.1: well-formedness on the optional bufferSize /
            // overflowPolicy fields. JSON ingest and the OverflowPolicy.Sample
            // `init` block already reject negative or zero Sample intervals;
            // this check covers any code path that constructs an EventStream
            // programmatically and the bufferSize <= 0 case.
            val bufferSize = streamNode.bufferSize
            if (bufferSize != null && bufferSize <= 0) {
                report(VerifyError.MalformedOverflowPolicy(
                    at = streamId,
                    detail = "bufferSize must be > 0, got $bufferSize",
                ))
                throw VerifyAbort()
            }
            val policy = streamNode.overflowPolicy
            if (policy is org.strand.core.OverflowPolicy.Sample && policy.intervalNanos <= 0) {
                report(VerifyError.MalformedOverflowPolicy(
                    at = streamId,
                    detail = "Sample.intervalNanos must be > 0, got ${policy.intervalNanos}",
                ))
                throw VerifyAbort()
            }
            return streamNode to eventType
        }

        /**
         * The expected OutputBatch type for a transition function whose host
         * StateMachine declares the given output stream event types in
         * order. Per § 5 of the proposal, the OutputBatch is a Product whose
         * field at index `i` is `output_i: Option<outputStreams[i].eventType>`.
         *
         * Special case: zero output streams ⇒ empty product `{}`. This
         * matches the convention that a stateless / output-less machine
         * returns `(new_state, {})` from its transition function.
         */
        private fun expectedOutputBatchType(
            stateMachineId: NodeId,
            outputEventTypes: List<TypeExpr>,
        ): TypeExpr.Product {
            val fields = outputEventTypes.mapIndexed { i, evtType ->
                TypeExpr.Product.Field("output_$i", optionTypeOf(evtType))
            }
            return TypeExpr.Product(origin = stateMachineId, fields = fields)
        }

        /**
         * The expected `Option<T>` shape for an OutputBatch slot. Strand has
         * no built-in Option; the proposal pins the convention to a Sum
         * with cases `Some(T)` and `None`. Because Sum equality ignores
         * `origin`, this synthetic Sum compares equal to any user-authored
         * Option<T> Sum with the same case set.
         */
        private fun optionTypeOf(t: TypeExpr): TypeExpr.Sum =
            TypeExpr.Sum(
                origin = NodeId(-1),  // synthetic — origin is ignored by equality
                cases = listOf(
                    TypeExpr.Sum.Case("Some", t),
                    TypeExpr.Sum.Case("None", null),
                ),
            )

        /**
         * Validate a list of NodeIds claiming to be EffectCategory references.
         * Returns the set of validated EffectCategory NodeIds. Reports and
         * aborts on the first non-EffectCategory entry.
         */
        /**
         * Q-039: validate `effectProjections` at admission of a
         * [Node.ForeignNode] (or [Node.FunctionType] symmetrically).
         *
         * Rules per proposal § 5:
         * 1. Empty list → accept (legacy path).
         * 2. Length must equal `effects.size`.
         * 3. Each projection's `category` must equal `effects[i]`.
         * 4. `sources.size` must equal the EffectCategory's parameter arity.
         * 5. Each [ProjectionSource.ArgRef] index must be within
         *    `signatureParameterCount`.
         * 6. Each [ProjectionSource.LiteralNode] target must resolve to a
         *    literal node whose type structurally equals the category's
         *    parameter type at the same position.
         *
         * Reports the corresponding `Projection*` VerifyError on failure
         * and aborts.
         */
        private fun validateProjections(
            at: NodeId,
            effects: List<NodeId>,
            effectProjections: List<EffectProjection>,
            signatureParameterTypes: List<TypeExpr>,
        ) {
            if (effectProjections.isEmpty()) return

            // Rule 2: length parity with effects.
            if (effectProjections.size != effects.size) {
                report(VerifyError.ProjectionArityMismatch(
                    at = at, expected = effects.size, actual = effectProjections.size
                ))
                throw VerifyAbort()
            }

            for ((i, projection) in effectProjections.withIndex()) {
                // Rule 3: per-position category match.
                if (projection.category != effects[i]) {
                    report(VerifyError.ProjectionCategoryMismatch(
                        at = at, index = i,
                        declaredCategory = effects[i],
                        projectedCategory = projection.category,
                    ))
                    throw VerifyAbort()
                }

                // Look up the EffectCategory to get its parameter shape.
                // validateEffectCategoryEdges already confirmed the edges
                // are EffectCategory nodes; we still defensively check.
                val effectNode = store.getOrNull(projection.category)
                    ?: run {
                        report(VerifyError.DanglingReference(
                            at = at, missing = projection.category,
                            fromField = "effectProjections[$i].category"
                        ))
                        throw VerifyAbort()
                    }
                if (effectNode !is Node.EffectCategory) {
                    report(VerifyError.NonEffectCategoryInEffectList(
                        at = at, offendingChild = projection.category,
                        actualCategory = categoryName(effectNode)
                    ))
                    throw VerifyAbort()
                }

                // Rule 4: sources arity equals the EffectCategory's parameter count.
                if (projection.sources.size != effectNode.parameters.size) {
                    report(VerifyError.ProjectionSourceArityMismatch(
                        at = at, categoryIndex = i,
                        expected = effectNode.parameters.size,
                        actual = projection.sources.size,
                    ))
                    throw VerifyAbort()
                }

                // Rule 5 + 6: per-source validation.
                for ((j, source) in projection.sources.withIndex()) {
                    when (source) {
                        is ProjectionSource.ArgRef -> {
                            if (source.index < 0 || source.index >= signatureParameterTypes.size) {
                                report(VerifyError.ProjectionArgRefOutOfRange(
                                    at = at, categoryIndex = i, sourceIndex = j,
                                    requested = source.index,
                                    maxAvailable = signatureParameterTypes.size - 1,
                                ))
                                throw VerifyAbort()
                            }
                            // We do not type-check ArgRef against the
                            // category parameter type here because the
                            // EffectCategory parameters are resolved at the
                            // top level (no enclosing TypeAbstraction), and
                            // resolveType lookups inside this admission
                            // helper would not handle generic argument
                            // positions cleanly. Type compatibility between
                            // arg(i) and category param i is enforced at
                            // every Application via validateProjectionMatch
                            // (the EffectDecl shape check) and ultimately
                            // by the runtime capability check on the
                            // synthesized values. A future tightening can
                            // add static argType ≤ categoryParamType here.
                        }
                        is ProjectionSource.LiteralNode -> {
                            val targetNode = store.getOrNull(source.target)
                                ?: run {
                                    report(VerifyError.DanglingReference(
                                        at = at, missing = source.target,
                                        fromField = "effectProjections[$i].sources[$j].target"
                                    ))
                                    throw VerifyAbort()
                                }
                            if (!isLiteralLikeNode(targetNode)) {
                                report(VerifyError.ProjectionLiteralNotConstant(
                                    at = at, categoryIndex = i, sourceIndex = j,
                                    target = source.target,
                                ))
                                throw VerifyAbort()
                            }
                            // Type-check the literal against the category
                            // parameter type. The literal is inferable in
                            // an empty scope/typeParams; if it isn't, the
                            // recursive infer call surfaces the structural
                            // problem under the same `at`.
                            val expectedType = resolveType(effectNode.parameters[j], emptySet())
                            val actualType = infer(source.target, emptyMap(), emptySet())
                            if (!typesCompatible(expectedType, actualType)) {
                                report(VerifyError.ProjectionLiteralTypeMismatch(
                                    at = at, categoryIndex = i, sourceIndex = j,
                                    expected = expectedType, actual = actualType,
                                ))
                                throw VerifyAbort()
                            }
                        }
                    }
                }
            }
        }

        /**
         * Q-039: at every [Node.Application] whose callee resolves to a
         * projected [Node.ForeignNode] (non-empty `effectProjections`),
         * verify that every authored [Node.EffectDecl] in
         * [Node.Application.effectInstances] matches the projection
         * structurally. Per proposal § 5:
         *
         *  * `ArgRef(j)` source → EffectDecl.parameters[k] must be the
         *    exact same NodeId as `Application.arguments[j]`.
         *  * `LiteralNode(t)` source → EffectDecl.parameters[k] must be a
         *    literal node whose canonical-form bytes equal `t`'s
         *    canonical-form bytes.
         *
         * When `effectInstances` is empty the synthesis path runs at
         * runtime — the verifier accepts (no authored shape to check).
         *
         * The hash-equality check on literals is performed by encoding
         * both candidate and projection target under an empty binder
         * stack via [Hasher.hashClosedSubgraph]; if hashes match, the
         * canonical bytes match by ADR-003 (BLAKE3 is collision-resistant
         * for the corpus sizes we care about).
         */
        private fun validateProjectionMatch(
            at: NodeId,
            app: Node.Application,
            projectedFn: Node.ForeignNode,
        ) {
            if (app.effectInstances.isEmpty()) return
            if (projectedFn.effectProjections.isEmpty()) return

            // Build a category → projection map for fast lookup. The
            // verifier's outer coverage check (in inferApplication) has
            // already established that effectInstances covers exactly the
            // declared effect categories, so missing/extra cases are
            // already rejected.
            val projectionByCategory: Map<NodeId, EffectProjection> =
                projectedFn.effectProjections.associateBy { it.category }

            for ((i, declId) in app.effectInstances.withIndex()) {
                val declNode = store.getOrNull(declId) as? Node.EffectDecl
                    ?: continue  // already validated by inferApplication
                val projection = projectionByCategory[declNode.effectType]
                    ?: continue  // category not projected — synthesis path skips it

                val categoryIndex = projectedFn.effectProjections.indexOf(projection)

                // The EffectDecl's parameter list arity equals the
                // category's arity (inferEffectDecl already enforced this).
                // The projection's sources length equals the same arity
                // (validateProjections enforced this). So both have the
                // same length; pair them positionally.
                for ((j, source) in projection.sources.withIndex()) {
                    val actualParamId = declNode.parameters[j]
                    val matches = when (source) {
                        is ProjectionSource.ArgRef -> {
                            if (source.index < 0 || source.index >= app.arguments.size) {
                                false  // out-of-range — admission rejected
                            } else {
                                actualParamId == app.arguments[source.index]
                            }
                        }
                        is ProjectionSource.LiteralNode -> {
                            // Canonical-form equality on literal towers.
                            // Both sides must be closed literal-like
                            // nodes; non-literal EffectDecl parameters
                            // (a VarRef, a function call) cannot match
                            // a LiteralNode projection by construction.
                            val actualIsLiteral =
                                store.getOrNull(actualParamId)?.let { isLiteralLikeNode(it) } == true
                            if (!actualIsLiteral) false
                            else literalSubgraphsEqual(actualParamId, source.target)
                        }
                    }
                    if (!matches) {
                        report(VerifyError.ProjectionMismatch(
                            at = at,
                            categoryIndex = categoryIndex,
                            sourceIndex = j,
                            expected = source,
                            actualParam = actualParamId,
                        ))
                        throw VerifyAbort()
                    }
                }
                // Loop continues to next effect instance — multi-category
                // call sites verify all category projections in order.
                // [i] is used as the loop index for diagnostics in
                // future tightenings; intentionally kept for clarity.
                @Suppress("UNUSED_EXPRESSION") i
            }
        }

        /**
         * Q-039 call-site discovery: walk `functionExprId` through
         * straightforward indirections — [Node.NodeRef] (resolved via the
         * `hashToNodeId` reverse map), [Node.VarRef] (followed when its
         * binder is a [Node.Let] whose value is itself a projected callee),
         * and [Node.Let] (returned by inspecting `.value`) — and return
         * the underlying [Node.ForeignNode] if non-null projections are
         * declared. Returns null when:
         *  * the chain bottoms out at a non-ForeignNode (e.g., a Lambda,
         *    FixpointFn, or higher-order parameter);
         *  * the ForeignNode has empty projections (legacy callee);
         *  * any step in the chain is unresolvable.
         *
         * The walk is intentionally conservative — when in doubt return
         * null. The verifier still rejects EffectDecl shape drift via the
         * existing coverage check; Q-039's value-level binding fires
         * statically only at the call sites where the syntactic
         * resolution succeeds. Indirect call sites (passing a projected
         * ForeignNode as a callback into List.Map, etc.) miss the static
         * check; the runtime synthesis path remains the security boundary
         * there. A future Lambda-level projection slice (proposal § 8)
         * tightens this.
         */
        private fun resolveProjectedForeignNode(functionExprId: NodeId): Node.ForeignNode? {
            var current: NodeId = functionExprId
            // Bound to prevent unexpected cycles; deeper chains than this
            // are not seen in practice.
            repeat(64) {
                val node = store.getOrNull(current) ?: return null
                when (node) {
                    is Node.ForeignNode ->
                        return if (node.effectProjections.isNotEmpty()) node else null
                    is Node.NodeRef -> {
                        val targetId = hashToNodeId[node.target] ?: return null
                        current = targetId
                    }
                    is Node.VarRef -> {
                        val binderNode = store.getOrNull(node.binder) ?: return null
                        // Only Let binders carry a value expression we can
                        // chase. ParameterDecl / VariablePattern binders
                        // bind run-time-supplied values — the verifier
                        // cannot resolve them statically.
                        if (binderNode is Node.Let) {
                            current = binderNode.value
                        } else {
                            return null
                        }
                    }
                    is Node.Let -> current = node.body  // unusual but handle it
                    else -> return null
                }
            }
            return null
        }

        /**
         * Q-039: structural equality of two literal-like subgraphs,
         * proxying the canonical-form equality requirement of proposal
         * § 5 step 2. The verifier doesn't depend on the `:hashing`
         * module (which would create a layering inversion), so we
         * compare the in-memory [Node] trees directly. Two literal-like
         * subgraphs in the canonical store hash to the same bytes iff
         * they are structurally equal under this comparator — the
         * canonical encoder is a pure function of structure on closed
         * literal towers, with no binder-stack dependence.
         *
         * Cycles are impossible here because literal-like nodes never
         * reference VarRef / RecursiveSelf and the verifier rejects
         * cycles before this comparator runs. We still bound depth
         * defensively in case of an ill-formed input that slipped past
         * earlier checks.
         */
        private fun literalSubgraphsEqual(a: NodeId, b: NodeId): Boolean {
            if (a == b) return true
            val cacheKey = if (a.value < b.value) a to b else b to a
            literalEqualityCache[cacheKey]?.let { return it }
            val result = literalSubgraphsEqualInternal(a, b, depth = 0)
            literalEqualityCache[cacheKey] = result
            return result
        }

        private fun literalSubgraphsEqualInternal(a: NodeId, b: NodeId, depth: Int): Boolean {
            if (depth > 256) return false  // defensive bound
            val na = store.getOrNull(a) ?: return false
            val nb = store.getOrNull(b) ?: return false
            return when {
                na is Node.IntLit && nb is Node.IntLit -> na.value == nb.value
                na is Node.FloatLit && nb is Node.FloatLit -> na.value == nb.value
                na is Node.StringLit && nb is Node.StringLit -> na.value == nb.value
                na is Node.BoolLit && nb is Node.BoolLit -> na.value == nb.value
                na is Node.UnitLit && nb is Node.UnitLit -> true
                na is Node.BytesLit && nb is Node.BytesLit -> na.value.contentEquals(nb.value)
                na is Node.ProductValue && nb is Node.ProductValue -> {
                    // ProductValue fields are unordered (the canonical
                    // encoder sorts by fieldName). Compare by name
                    // associations.
                    val aFields = na.fields.mapNotNull { fid ->
                        (store.getOrNull(fid) as? Node.ProductFieldValue)?.let { it.fieldName to it.value }
                    }.toMap()
                    val bFields = nb.fields.mapNotNull { fid ->
                        (store.getOrNull(fid) as? Node.ProductFieldValue)?.let { it.fieldName to it.value }
                    }.toMap()
                    if (aFields.keys != bFields.keys) false
                    else aFields.all { (name, valId) ->
                        literalSubgraphsEqualInternal(valId, bFields[name]!!, depth + 1)
                    } && literalSubgraphsEqualInternal(na.ofType, nb.ofType, depth + 1).let { ofTypeEq ->
                        // For ProductValue, the ofType also contributes
                        // to canonical equality. ofType is a *type* node
                        // (ProductType), not a value tower, so we
                        // compare by NodeId equality only — equirecursive
                        // type equality at this depth would require the
                        // full Type comparator. For Q-039 V1 the
                        // simplest correct check: same ofType NodeId
                        // (post-finalize dedup makes structurally-equal
                        // types share a NodeId by construction). Note
                        // we still need the field walk above for the
                        // value-tower contents.
                        ofTypeEq || na.ofType == nb.ofType
                    }
                }
                na is Node.SumValue && nb is Node.SumValue -> {
                    if (na.caseName != nb.caseName) false
                    else if (na.ofType != nb.ofType) false  // same simplification as ProductValue
                    else {
                        val aPay = na.payload
                        val bPay = nb.payload
                        when {
                            aPay == null && bPay == null -> true
                            aPay != null && bPay != null ->
                                literalSubgraphsEqualInternal(aPay, bPay, depth + 1)
                            else -> false
                        }
                    }
                }
                else -> false
            }
        }

        /**
         * Recognize the set of node categories the V1 [ProjectionSource.LiteralNode]
         * vocabulary admits. Per proposal § 4.1: primitive literals
         * (IntLit/FloatLit/StringLit/BoolLit/UnitLit/BytesLit) and
         * ProductValue/SumValue towers over literals. The recursive case
         * is checked lazily — at admission we only confirm the outermost
         * shape is a value-producing literal-like node; per-field
         * structural literal-ness is verified through type compatibility
         * in [validateProjections] and confirmed at runtime by the
         * evaluator (literal-like nodes evaluate without side effects
         * under any context).
         */
        private fun isLiteralLikeNode(node: Node?): Boolean = when (node) {
            is Node.IntLit, is Node.FloatLit, is Node.StringLit,
            is Node.BoolLit, Node.UnitLit, is Node.BytesLit -> true
            is Node.ProductValue -> true
            is Node.SumValue -> true
            else -> false
        }

        private fun validateEffectCategoryEdges(
            at: NodeId,
            edges: List<NodeId>,
            fieldLabel: String,
        ): Set<NodeId> {
            val seen = LinkedHashSet<NodeId>()
            for ((i, effectId) in edges.withIndex()) {
                val effectNode = store.getOrNull(effectId)
                    ?: run {
                        report(VerifyError.DanglingReference(
                            at = at, missing = effectId, fromField = "$fieldLabel[$i]"
                        ))
                        throw VerifyAbort()
                    }
                if (effectNode !is Node.EffectCategory) {
                    report(VerifyError.NonEffectCategoryInEffectList(
                        at = at, offendingChild = effectId,
                        actualCategory = categoryName(effectNode)
                    ))
                    throw VerifyAbort()
                }
                // Validate the EffectCategory's parameter list (each must be a
                // well-formed Type). We don't use the result here; we just
                // ensure the EffectCategory is itself coherent. The empty
                // typeParams set is correct: an EffectCategory is declared at
                // module level, not inside any TypeAbstraction.
                for (paramId in effectNode.parameters) {
                    resolveType(paramId, emptySet())
                }
                seen += effectId
            }
            return seen
        }

        private fun inferVarRef(id: NodeId, node: Node.VarRef, scope: Map<NodeId, TypeExpr>): TypeExpr {
            val binderNode = store.getOrNull(node.binder)
                ?: reportFatal(VerifyError.DanglingReference(at = id, missing = node.binder, fromField = "VarRef.binder"))
            // Valid binding occurrences in Layer 5 step 1: ParameterDecl
            // (in a Lambda), Let, and Pattern.VariablePattern (in a Match
            // case). WildcardPattern and LiteralPattern bind nothing.
            val ok = binderNode is Node.ParameterDecl
                || binderNode is Node.Let
                || binderNode is Node.Pattern.VariablePattern
            if (!ok) {
                reportFatal(VerifyError.IllegalBinder(
                    at = id, binder = node.binder,
                    binderCategory = categoryName(binderNode)
                ))
            }
            return scope[node.binder]
                ?: reportFatal(VerifyError.UnboundVariable(at = id, binder = node.binder))
        }

        /**
         * Resolve a type-node id into a [TypeExpr] under the in-scope
         * TypeParameter set [typeParams]. A TypeParameter reference is
         * accepted only if its NodeId is in [typeParams]; otherwise the
         * verifier records [VerifyError.UnboundTypeParameter] and aborts.
         *
         * A TypeParameter node has node identity: every reference to the same
         * TypeParameter NodeId resolves to the same [TypeExpr.Param]. The
         * substitution machinery keys on the TypeParameter NodeId.
         */
        private fun resolveType(typeId: NodeId, typeParams: Set<NodeId>): TypeExpr {
            val node = store.getOrNull(typeId)
                ?: reportFatal(VerifyError.DanglingReference(at = typeId, missing = typeId, fromField = "type"))
            return when (node) {
                is Node.PrimitiveType -> TypeExpr.Prim(node.kind)
                is Node.FunctionType -> {
                    val paramTypes = node.parameters.map { resolveType(it, typeParams) }
                    val resultType = resolveType(node.result, typeParams)
                    val resolvedEffects = validateEffectCategoryEdges(typeId, node.effects, "FunctionType.effects")
                    // Q-039 symmetry: even though only ForeignNode is the V1
                    // security boundary the verifier enforces at call sites,
                    // FunctionType.effectProjections is validated at
                    // admission so a future Lambda-level slice can populate
                    // and trust the field without re-checking shape.
                    validateProjections(
                        at = typeId,
                        effects = node.effects,
                        effectProjections = node.effectProjections,
                        signatureParameterTypes = paramTypes,
                    )
                    TypeExpr.Fun(
                        parameters = paramTypes,
                        result = resultType,
                        effects = resolvedEffects,
                    )
                }
                is Node.ProductType -> {
                    val fields = node.fields.map { fieldId ->
                        val f = store.getOrNull(fieldId)
                        if (f !is Node.ProductTypeField) {
                            report(VerifyError.MalformedType(
                                at = typeId,
                                detail = "ProductType field expected ProductTypeField, got ${categoryName(f)}"
                            ))
                            throw VerifyAbort()
                        }
                        TypeExpr.Product.Field(f.fieldName, resolveType(f.fieldType, typeParams))
                    }
                    TypeExpr.Product(origin = typeId, fields = fields)
                }
                is Node.SumType -> {
                    val cases = node.cases.map { caseId ->
                        val c = store.getOrNull(caseId)
                        if (c !is Node.SumTypeCase) {
                            report(VerifyError.MalformedType(
                                at = typeId,
                                detail = "SumType case expected SumTypeCase, got ${categoryName(c)}"
                            ))
                            throw VerifyAbort()
                        }
                        TypeExpr.Sum.Case(c.caseName, c.caseType?.let { resolveType(it, typeParams) })
                    }
                    TypeExpr.Sum(origin = typeId, cases = cases)
                }
                is Node.TypeParameter -> {
                    if (typeId !in typeParams) {
                        report(VerifyError.UnboundTypeParameter(at = typeId, typeParameter = typeId))
                        throw VerifyAbort()
                    }
                    TypeExpr.Param(origin = typeId, name = node.name)
                }
                is Node.ForallType -> {
                    // Each entry of typeParameters must be a TypeParameter node.
                    for ((i, tpId) in node.typeParameters.withIndex()) {
                        val tpNode = store.getOrNull(tpId)
                        if (tpNode == null) {
                            report(VerifyError.DanglingReference(
                                at = typeId, missing = tpId, fromField = "ForallType.typeParameters[$i]"
                            ))
                            throw VerifyAbort()
                        }
                        if (tpNode !is Node.TypeParameter) {
                            report(VerifyError.CategoryMismatch(
                                at = typeId, field = "ForallType.typeParameters[$i]",
                                expectedCategory = "TypeParameter",
                                actualCategory = categoryName(tpNode)
                            ))
                            throw VerifyAbort()
                        }
                    }
                    val inner = typeParams + node.typeParameters
                    val body = resolveType(node.body, inner)
                    TypeExpr.Forall(node.typeParameters, body)
                }
                is Node.NodeRef -> {
                    val targetId = hashToNodeId[node.target]
                        ?: reportFatal(
                            VerifyError.NodeRefTargetNotFound(at = typeId, targetHash = node.target)
                        )
                    resolveType(targetId, typeParams)
                }
                is Node.RecursiveType -> {
                    recursiveDepth++
                    val body = try {
                        resolveType(node.body, typeParams)
                    } finally {
                        recursiveDepth--
                    }
                    // Contractivity: every path from the binder to a
                    // RecursiveSelf must traverse at least one type
                    // constructor. Reject `μ. RecursiveSelf` etc.
                    if (!isContractive(body)) {
                        report(VerifyError.NonContractiveRecursiveType(at = typeId))
                        throw VerifyAbort()
                    }
                    TypeExpr.Recursive(body)
                }
                is Node.RecursiveSelf -> {
                    if (node.depth < 0 || node.depth >= recursiveDepth) {
                        report(VerifyError.UnboundRecursiveSelf(at = typeId))
                        throw VerifyAbort()
                    }
                    // Depth is a de Bruijn index against the
                    // recursive-binder stack. 0 = innermost; depth N > 0
                    // resolves to the N-th outer enclosing RecursiveType.
                    // The post-stdlib-round-2 JsonValue uses depth = 1 to
                    // reach the outer `jv` binder from inside the inner
                    // List<JsonValue> binder.
                    TypeExpr.RecursiveSelf(depth = node.depth)
                }
                is Node.Schema -> resolveSchema(typeId, node, typeParams)
                else -> {
                    report(VerifyError.CategoryMismatch(
                        at = typeId,
                        field = "<type position>",
                        expectedCategory = "Type",
                        actualCategory = categoryName(node)
                    ))
                    throw VerifyAbort()
                }
            }
        }

        /**
         * Resolve a [Node.Schema] reached in type position into a
         * [TypeExpr.SchemaType]. Performs Schema well-formedness checks at
         * the same time:
         *
         *  - `valueType` must resolve to a real type (delegated to
         *    [resolveType]).
         *  - Each invariant must be a real [Node.Invariant] whose
         *    `targetSchema` points back at this Schema (defensive
         *    topology check — [VerifyError.InvariantTargetMismatch]).
         *  - Each invariant's `body` must type-check to a monomorphic
         *    `Fun(valueType) -> Bool` with no declared effects. Rejected
         *    otherwise as [VerifyError.SchemaInvariantBodyTypeMismatch],
         *    [VerifyError.SchemaInvariantBodyMustBePure], or
         *    [VerifyError.SchemaInvariantBodyMustBeMonomorphic].
         *
         * The resulting [TypeExpr.SchemaType] carries the schema NodeId and
         * the list of invariant NodeIds; the SchemaChecker (in the
         * `:schema` module) reads it to drive verify-time invariant
         * evaluation. Cached in `nodeTypes` so the SchemaChecker can find
         * every schema-typed position by walking the verifier's map.
         */
        private fun resolveSchema(
            schemaId: NodeId,
            node: Node.Schema,
            typeParams: Set<NodeId>
        ): TypeExpr {
            val valueType = resolveType(node.valueType, typeParams)
            val expectedBodyType = TypeExpr.Fun(
                parameters = listOf(valueType),
                result = TypeExpr.Prim(Primitive.Bool),
                effects = emptySet(),
            )
            for ((i, invariantId) in node.invariants.withIndex()) {
                val invariantNode = store.getOrNull(invariantId)
                    ?: run {
                        report(VerifyError.DanglingReference(
                            at = schemaId, missing = invariantId,
                            fromField = "Schema.invariants[$i]"
                        ))
                        throw VerifyAbort()
                    }
                if (invariantNode !is Node.Invariant) {
                    report(VerifyError.CategoryMismatch(
                        at = schemaId, field = "Schema.invariants[$i]",
                        expectedCategory = "Invariant",
                        actualCategory = categoryName(invariantNode)
                    ))
                    throw VerifyAbort()
                }
                // Topological consistency: the invariant's targetSchema must
                // point back at this Schema. Catches the obvious mistake of
                // wiring an invariant from one schema into another's
                // invariants list.
                if (invariantNode.targetSchema != schemaId) {
                    report(VerifyError.InvariantTargetMismatch(
                        at = schemaId, invariant = invariantId,
                        targetSchema = invariantNode.targetSchema,
                        declaringSchema = schemaId
                    ))
                    throw VerifyAbort()
                }
                // Body purity: ForeignNode-typed bodies are rejected in
                // step 1 (the SchemaInvariantBodyMustBePure rule loosens
                // when the security model for checker bindings lands).
                // The body may be either a Lambda, a TypeAbstraction
                // wrapping one (rejected anyway by monomorphism), a
                // VarRef into a Let-bound Lambda, or a ForeignNode — only
                // the ForeignNode case fails the purity rule at the
                // *node* level. The effects check after type-inference
                // catches Lambdas with non-empty effects lists.
                val bodyNode = store.getOrNull(invariantNode.body)
                if (bodyNode is Node.ForeignNode) {
                    report(VerifyError.SchemaInvariantBodyMustBePure(
                        at = schemaId, invariant = invariantId
                    ))
                    throw VerifyAbort()
                }
                // Now type-check the invariant body in an empty scope
                // (invariant bodies must be self-contained — they can
                // reference their own parameter only, not outer binders).
                val bodyType = infer(invariantNode.body, scope = emptyMap(), typeParams = typeParams)
                if (bodyType is TypeExpr.Forall) {
                    report(VerifyError.SchemaInvariantBodyMustBeMonomorphic(
                        at = schemaId, invariant = invariantId, residual = bodyType
                    ))
                    throw VerifyAbort()
                }
                if (bodyType !is TypeExpr.Fun) {
                    report(VerifyError.SchemaInvariantBodyTypeMismatch(
                        at = schemaId, schema = schemaId, invariant = invariantId,
                        expectedType = expectedBodyType, actualType = bodyType
                    ))
                    throw VerifyAbort()
                }
                // Effects must be empty — a pure-expression invariant is
                // by construction effect-free. A Lambda with declared
                // effects fails this check even before the body itself is
                // examined.
                if (bodyType.effects.isNotEmpty()) {
                    report(VerifyError.SchemaInvariantBodyMustBePure(
                        at = schemaId, invariant = invariantId
                    ))
                    throw VerifyAbort()
                }
                // Shape: must be (valueType) -> Bool. Compare directly
                // against the constructed expected shape (which has empty
                // effects, matching the purity rule above).
                if (bodyType != expectedBodyType) {
                    report(VerifyError.SchemaInvariantBodyTypeMismatch(
                        at = schemaId, schema = schemaId, invariant = invariantId,
                        expectedType = expectedBodyType, actualType = bodyType
                    ))
                    throw VerifyAbort()
                }
            }
            return TypeExpr.SchemaType(
                schemaId = schemaId,
                valueType = valueType,
                invariants = node.invariants,
            )
        }

        /**
         * Check that a `TypeExpr` body is contractive: every path from the
         * enclosing recursive binder to a [TypeExpr.RecursiveSelf] reference
         * must traverse at least one type constructor (Fun, Product, Sum,
         * or a nested Recursive — which encapsulates its own self-binder).
         * Forall is not a guard; recurse into its body. Prim and Param are
         * trivially contractive (no recursive references).
         */
        private fun isContractive(t: TypeExpr): Boolean = when (t) {
            is TypeExpr.RecursiveSelf -> false       // bare reference — not contractive
            is TypeExpr.Recursive -> true            // nested μ encapsulates its own binder
            is TypeExpr.Fun, is TypeExpr.Product, is TypeExpr.Sum -> true  // constructors guard
            is TypeExpr.Forall -> isContractive(t.body)
            is TypeExpr.Prim, is TypeExpr.Param -> true
            // A SchemaType is just a refined valueType — contractivity is
            // a property of the underlying structural shape, so delegate
            // to the valueType. (In practice schemas appear at the value
            // level and not as a recursive-type body, but the encoder is
            // total so we cover this case.)
            is TypeExpr.SchemaType -> isContractive(t.valueType)
        }

        /**
         * Equirecursive one-step unfold: given `μ. body`, return the body
         * with every top-level (depth-0) RecursiveSelf replaced by `μ.
         * body` itself. Used by [inferSumValue] and the constructor-pattern
         * verifier when the declared `ofType` is a Recursive — the SumValue
         * is matched against the unfolded body, which is then expected to
         * be a Sum.
         */
        private fun unfoldRecursive(rec: TypeExpr.Recursive): TypeExpr =
            substituteSelf(rec.body, target = rec, currentDepth = 0)

        private fun substituteSelf(
            t: TypeExpr,
            target: TypeExpr.Recursive,
            currentDepth: Int,
        ): TypeExpr = when (t) {
            is TypeExpr.RecursiveSelf -> if (t.depth == currentDepth) target else t
            is TypeExpr.Recursive -> TypeExpr.Recursive(
                substituteSelf(t.body, target, currentDepth + 1)
            )
            is TypeExpr.Fun -> TypeExpr.Fun(
                parameters = t.parameters.map { substituteSelf(it, target, currentDepth) },
                result = substituteSelf(t.result, target, currentDepth),
                effects = t.effects,
            )
            is TypeExpr.Product -> TypeExpr.Product(
                origin = t.origin,
                fields = t.fields.map {
                    TypeExpr.Product.Field(it.name, substituteSelf(it.type, target, currentDepth))
                }
            )
            is TypeExpr.Sum -> TypeExpr.Sum(
                origin = t.origin,
                cases = t.cases.map {
                    TypeExpr.Sum.Case(it.name, it.type?.let { ty -> substituteSelf(ty, target, currentDepth) })
                }
            )
            is TypeExpr.Forall -> TypeExpr.Forall(
                t.typeParameters,
                substituteSelf(t.body, target, currentDepth),
            )
            is TypeExpr.Prim, is TypeExpr.Param -> t
            // SchemaType: substitute through the underlying valueType.
            // Invariants are NodeIds and unaffected by μ-unfolding.
            is TypeExpr.SchemaType -> TypeExpr.SchemaType(
                schemaId = t.schemaId,
                valueType = substituteSelf(t.valueType, target, currentDepth),
                invariants = t.invariants,
            )
        }
    }
}
