package org.strand.interpreter

import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore

/**
 * Tree-walking interpreter for verified Strand graphs (Layer 3 step 3 scope).
 *
 * Semantics:
 *  - Literals evaluate to their value.
 *  - Lambdas evaluate to closures that capture the current environment.
 *    The closure also remembers the Lambda's declared effects.
 *  - Application evaluates the function, then its arguments left-to-right
 *    (eager / call-by-value), then checks that the calling capability
 *    context covers every effect the called Lambda declares. If a declared
 *    category is missing from the context entirely, evaluation halts with
 *    [InterpretError.CapabilityViolation]. If the category is present but
 *    no granted [CapabilityPattern] covers the call-site refinement, the
 *    interpreter halts with [InterpretError.RefinementViolation]. When
 *    the Application carries `effectInstances`, the parameter expressions
 *    of each EffectDecl are evaluated before dispatch and supplied to the
 *    refinement check; when it does not (pre-Q-031 call sites), the check
 *    runs against an empty requirement list, which is trivially covered
 *    by [CapabilityPattern]s built by [CapabilitySet.ofCategories].
 *  - Let evaluates its value, binds the Let's id to that value, evaluates
 *    the body.
 *  - VarRef looks up its binder id in the environment.
 *  - NodeRef forwards through to its target.
 *  - CapabilityScope evaluates its body in a narrowed capability context:
 *    [CapabilitySet.intersect] of the surrounding context with the scope's
 *    declared categories. Narrowing cannot add capabilities or tighten
 *    refinements; per-category pattern lists carry through verbatim.
 *  - Handler installs an [ActiveHandler] for its `intercept` category over
 *    its body's evaluation. The `handle` expression is evaluated once at
 *    Handler-entry under the OUTER handlers list (so handlers stack
 *    lexically); the body is evaluated with the new handler appended. At
 *    every Application within the body whose callee declares the
 *    intercepted category, [applyCall] consults the active handler stack
 *    via `findLast` (innermost wins) and invokes the handler with the
 *    intercepted call's value arguments in place of the original
 *    dispatch. The original effectful call is replaced wholesale — the
 *    interpreter does NOT run [checkCapabilities] for the intercepted
 *    effect at that site (the handler subsumes it). The handler's own
 *    declared effects still flow into the surrounding capability
 *    context.
 *
 * The interpreter assumes the input graph passed the
 * [org.strand.verifier.Verifier]; defensive checks remain for the few
 * shapes that could still go wrong (missing nodes, contract violations).
 *
 * The Layer 3 step 2 capability context is a [CapabilitySet]. The pre-Q-031
 * `eval(root, Set<NodeId>)` overload is preserved as a thin wrapper that
 * delegates to `eval(root, CapabilitySet.ofCategories(set))` — every existing
 * test case keeps working without modification. The structured eval API is
 * the one that admits refinement-bearing capability policies. The handlers
 * list is threaded alongside the capability context as a separate stack;
 * the public entry points all start with an empty handlers list.
 */
/**
 * Constructed over a canonical [NodeStore] (produced by `Hasher.finalize`)
 * plus the [hashToNodeId] reverse map for resolving [Node.NodeRef.target]
 * hashes back to local NodeIds. Tests that don't construct NodeRefs may
 * pass an empty map; if the interpreter encounters a NodeRef with an
 * unresolved hash it raises [InterpretError.NodeRefTargetNotInStore].
 */
class Interpreter(
    private val store: NodeStore,
    private val hashToNodeId: Map<Hash, NodeId> = emptyMap(),
    /**
     * Optional foreign-call interceptor consulted before [Builtins.lookup]
     * at every ForeignNode dispatch site. Returning a non-null [Value] uses
     * that result as the call's return; returning null falls through to the
     * standard [Builtins] registry.
     *
     * Used by the runtime to intercept `strand-runtime:StateMachine.Spawn`
     * and `.Terminate` calls (Layer 6 step 3 slice 3.2) inside an actor's
     * transition function — those calls produce side effects on the
     * surrounding [org.strand.runtime.MachineGroup] state that pure builtins
     * can't express. The dispatcher is per-Interpreter (and thus per-actor
     * in the runtime's `runGroup` path), so each actor's Spawn calls are
     * scoped to its own group.
     *
     * Default null: no interceptor, every ForeignNode goes straight to
     * Builtins. Existing call sites that pass only `store` + `hashToNodeId`
     * keep the pre-slice-3.2 behavior unchanged.
     */
    private val foreignDispatcher: ForeignDispatcher? = null,
) {

    /** Top-level evaluation under an empty capability context (pure-only). */
    fun eval(root: NodeId): Value = eval(root, capabilities = CapabilitySet.EMPTY)

    /**
     * Back-compat overload: accept a flat `Set<EffectCategory NodeId>` and
     * delegate to the structured form via [CapabilitySet.ofCategories].
     * The resulting CapabilitySet has wildcards-everywhere patterns, so
     * every refinement check trivially passes — exactly the pre-Q-031
     * set-membership semantics.
     */
    fun eval(root: NodeId, capabilities: Set<NodeId>): Value =
        eval(root, capabilities = CapabilitySet.ofCategories(capabilities))

    /**
     * Top-level evaluation under an explicit structured capability context.
     * This is the API the host runtime calls when it wants refinement-
     * bearing policies — `Filesystem.Write{path: "/var/log/app.log"}` and
     * similar.
     */
    fun eval(root: NodeId, capabilities: CapabilitySet): Value =
        eval(root, env = emptyMap(), context = capabilities, handlers = emptyList())

    /**
     * Apply a pre-evaluated callable [fn] to already-evaluated [args] under
     * the supplied [capabilities] context. This is the entry point the
     * `runtime/` module's [StateMachineRuntime] uses to drive a transition
     * function call from a step: the runtime evaluates the StateMachine's
     * transitionFn once at instance start (caching the [Value.Closure]) and
     * then calls this method per event, supplying `(currentState, event)`
     * as the argument list.
     *
     * The handler stack at the boundary is empty — runtime callers never
     * install effect handlers from outside the graph. The intercept
     * machinery still fires for handlers installed *inside* the transition
     * function's body, if any.
     *
     * The internal [applyValue] does the actual dispatch; this is a thin
     * façade with a stable public signature.
     */
    fun applyCallable(
        fn: Value,
        args: List<Value>,
        capabilities: CapabilitySet = CapabilitySet.EMPTY,
    ): Value = applyValue(
        // The `id` is used in error reporting for arity / not-callable
        // failures. Runtime callers don't have a graph site to blame, so
        // we use NodeId(-1) as a sentinel — appears in error messages as
        // "at NodeId(-1)" which the runtime caller can attribute to "the
        // transition function call".
        id = NodeId(-1),
        callable = fn,
        args = args,
        context = capabilities,
        handlers = emptyList(),
    )

    /**
     * One active [Node.Handler] in the current call stack. The [handler]
     * value is the [handle] expression's runtime result (a [Value.Closure],
     * [Value.ForeignFn], or [Value.FixpointFn]), captured once at
     * Handler-entry so that re-invoking the handler does not re-evaluate
     * the handle expression. The [intercept] is the EffectCategory NodeId
     * the handler watches for. The interpreter's `handlers` list is
     * appended at each Handler-entry, so `findLast` selects the
     * innermost-active (lexically nearest) handler for a given category.
     *
     * Exposed to the `runtime/` module's [StateMachineRuntime] entry point
     * via [applyClosureExternal] — runtime callers don't construct
     * ActiveHandler values directly; they always start with an empty
     * handlers list at the boundary.
     */
    internal data class ActiveHandler(
        val intercept: NodeId,
        val handler: Value,
    )

    private fun eval(
        id: NodeId,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value {
        val node = store.getOrNull(id)
            ?: throw InterpretException(InterpretError.MissingNode(at = id, missing = id))
        return when (node) {
            is Node.IntLit -> Value.IntV(node.value)
            is Node.FloatLit -> Value.FloatV(node.value)
            is Node.StringLit -> Value.StringV(node.value)
            is Node.BoolLit -> Value.BoolV(node.value)
            Node.UnitLit -> Value.UnitV
            is Node.BytesLit -> Value.BytesV(node.value)

            is Node.Lambda -> Value.Closure(lambda = node, env = env, self = id)
            is Node.ForeignNode -> Value.ForeignFn(node = node, self = id)
            is Node.Fixpoint -> {
                // The body must be a Lambda (the verifier confirms this).
                // Capture it now so applyCall can extend the env without
                // re-resolving the body NodeId on every recursive call.
                val bodyLambda = store.getOrNull(node.body) as? Node.Lambda
                    ?: throw InterpretException(InterpretError.MissingNode(at = id, missing = node.body))
                Value.FixpointFn(
                    fixpoint = node,
                    bodyLambda = bodyLambda,
                    env = env,
                    self = id
                )
            }
            is Node.TypeAbstraction -> eval(node.body, env, context, handlers) // erased at runtime
            is Node.Application -> applyCall(id, node, env, context, handlers)
            is Node.Let -> {
                val bound = eval(node.value, env, context, handlers)
                eval(node.body, env + (id to bound), context, handlers)
            }
            is Node.VarRef -> env[node.binder]
                ?: throw InterpretException(InterpretError.UnboundAtRuntime(at = id, binder = node.binder))
            is Node.NodeRef -> {
                // Layer 2 step 2: NodeRef.target is a Hash. Resolve via the
                // reverse map and evaluate the target under an empty
                // environment — the verifier's NodeRefTargetMustBeClosed
                // rule guarantees the target subgraph references no outer
                // binders, so the surrounding env is intentionally dropped.
                val targetId = hashToNodeId[node.target]
                    ?: throw InterpretException(
                        InterpretError.NodeRefTargetNotInStore(at = id, targetHash = node.target)
                    )
                eval(targetId, env = emptyMap(), context = context, handlers = handlers)
            }

            is Node.CapabilityScope -> {
                // Narrow: per-category pattern lists carry through verbatim
                // for retained categories. Refinement-narrowing (tightening
                // patterns within a retained category) is a deferred
                // follow-up; see Q-031 § Tradeoffs and open questions.
                val narrowed = context.intersect(node.capabilities.toSet())
                eval(node.body, env, narrowed, handlers)
            }

            is Node.Handler -> {
                // Evaluate the handle expression ONCE, at Handler-entry, in
                // the OUTER handlers list (so a nested Handler may itself
                // be handled). The result is captured in the ActiveHandler
                // and re-used at every intercepted call site.
                val handlerValue = eval(node.handle, env, context, handlers)
                val newHandlers = handlers + ActiveHandler(node.intercept, handlerValue)
                eval(node.body, env, context, newHandlers)
            }

            is Node.Match -> evalMatch(id, node, env, context, handlers)

            is Node.ProductValue -> evalProductValue(node, env, context, handlers)
            is Node.ProductFieldGet -> evalProductFieldGet(id, node, env, context, handlers)
            is Node.SumValue -> evalSumValue(node, env, context, handlers)

            is Node.ToolDef -> {
                // Eagerly evaluate the implementation expression — typically
                // a Lambda or ForeignNode, which produces a callable value
                // without firing effects (effects are released at the
                // tool-dispatch sites inside the provider's loop, not here).
                val implValue = eval(node.implementation, env, context, handlers)
                Value.ToolDefV(
                    self = id,
                    name = node.name,
                    description = node.description,
                    parameterSchemaId = node.parameterSchema,
                    implementation = implValue,
                )
            }

            // Type, effect-declaration, MatchCase, Pattern, and
            // ProductFieldValue nodes are not standalone expressions. The
            // verifier should have caught this; we report it here
            // defensively. State-machine wiring nodes (StateMachine,
            // EventStream, Transition) are also not expressions — they
            // are consumed by the `runtime/` module's StateMachineRuntime,
            // not by Interpreter.eval. Reaching one here means a graph
            // root was a StateMachine handed to `eval` directly; the
            // verifier rejects such graphs upstream but we defensively
            // report the same shape.
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
            is Node.StateMachine,
            is Node.EventStream,
            is Node.Transition,
            // Schema (N-032) only appears in type position, never as an
            // expression. Invariant (N-033) is reached via Schema.invariants
            // and its body Lambda is what evaluates (which the SchemaChecker
            // drives via Interpreter.applyCallable). Reaching either here
            // means a graph root was a Schema or Invariant handed to eval
            // directly — the verifier rejects such graphs upstream.
            is Node.Schema,
            is Node.Invariant ->
                throw InterpretException(InterpretError.NotCallable(at = id, gotKind = node::class.simpleName ?: "Type"))
        }
    }

    private fun evalProductValue(
        node: Node.ProductValue,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value {
        // Evaluate each field in declaration order. The verifier confirmed
        // type correctness and completeness, so we can trust the structure.
        val fields = LinkedHashMap<String, Value>(node.fields.size)
        for (fieldId in node.fields) {
            val fieldNode = store.get(fieldId) as Node.ProductFieldValue
            fields[fieldNode.fieldName] = eval(fieldNode.value, env, context, handlers)
        }
        return Value.ProductV(fields)
    }

    private fun evalProductFieldGet(
        id: NodeId,
        node: Node.ProductFieldGet,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value {
        val target = eval(node.target, env, context, handlers)
        if (target !is Value.ProductV) {
            throw InterpretException(InterpretError.NotCallable(
                at = id, gotKind = "${target::class.simpleName} (expected ProductV for field access)"
            ))
        }
        return target.fields[node.fieldName]
            ?: error("Field '${node.fieldName}' not present on product value at $id; " +
                "verifier should have rejected this graph.")
    }

    private fun evalSumValue(
        node: Node.SumValue,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value {
        val payload = node.payload?.let { eval(it, env, context, handlers) }
        return Value.SumV(node.caseName, payload)
    }

    private fun evalMatch(
        id: NodeId,
        node: Node.Match,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value {
        val scrutineeValue = eval(node.scrutinee, env, context, handlers)
        for (caseId in node.cases) {
            val case = store.getOrNull(caseId) as? Node.MatchCase
                ?: throw InterpretException(InterpretError.MissingNode(at = id, missing = caseId))
            val pattern = store.getOrNull(case.pattern) as? Node.Pattern
                ?: throw InterpretException(InterpretError.MissingNode(at = caseId, missing = case.pattern))
            val bindings = tryMatch(scrutineeValue, case.pattern, pattern, env, context, handlers)
            if (bindings != null) {
                return eval(case.body, env + bindings, context, handlers)
            }
        }
        throw InterpretException(InterpretError.NoMatchingCase(at = id))
    }

    /**
     * Test [pattern] against [scrutinee]. Returns the bindings (NodeId →
     * Value) to extend the env with for the case body if the pattern
     * matches, or null if it does not. ConstructorPatterns may recurse
     * into nested sub-patterns; bindings from the entire subtree are
     * merged into the returned map.
     */
    private fun tryMatch(
        scrutinee: Value,
        patternId: NodeId,
        pattern: Node.Pattern,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Map<NodeId, Value>? = when (pattern) {
        is Node.Pattern.LiteralPattern -> {
            // Evaluate the literal node and compare by value equality. The
            // verifier confirmed the literal's type matches the pattern's
            // type; the value comparison here is the runtime test.
            val literalValue = eval(pattern.literal, env, context, handlers)
            if (literalValue == scrutinee) emptyMap() else null
        }
        is Node.Pattern.VariablePattern ->
            mapOf(patternId to scrutinee)
        is Node.Pattern.WildcardPattern ->
            emptyMap()
        is Node.Pattern.ConstructorPattern -> {
            // Must be matching a SumV; the verifier confirmed the pattern's
            // patternType is the scrutinee's sum type.
            if (scrutinee !is Value.SumV || scrutinee.case != pattern.caseName) {
                null
            } else {
                val payloadPatternId = pattern.payloadPattern
                if (payloadPatternId == null) {
                    emptyMap()
                } else {
                    val payloadPattern = store.get(payloadPatternId) as Node.Pattern
                    val payloadValue = scrutinee.payload
                        ?: error("ConstructorPattern $patternId expects a payload but " +
                            "scrutinee SumV.${scrutinee.case} has none; verifier should " +
                            "have rejected this.")
                    tryMatch(payloadValue, payloadPatternId, payloadPattern, env, context, handlers)
                }
            }
        }
    }

    private fun applyCall(
        id: NodeId,
        app: Node.Application,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value {
        val fnValue = eval(app.function, env, context, handlers)

        // Effect-handler interception per § 7.3 of the proposal: before the
        // normal dispatch path, check whether any active handler intercepts
        // an effect the callee declares. The innermost (lexically nearest)
        // handler wins, so we scan from the tail (`findLast`).
        //
        // When intercepted, the handler stands in for the entire effectful
        // call: we evaluate the value arguments and invoke the handler with
        // them. We do NOT run checkCapabilities for the intercepted
        // category — the handler replaces the effectful call, and the
        // capability context will be checked at the handler's own effectful
        // calls (if any), with the handler's own declared effects.
        if (handlers.isNotEmpty()) {
            val fnEffects = effectsOf(fnValue)
            if (fnEffects.isNotEmpty()) {
                val activeHandler = handlers.findLast { it.intercept in fnEffects }
                if (activeHandler != null) {
                    val args = app.arguments.map { eval(it, env, context, handlers) }
                    return applyValue(
                        id = id,
                        callable = activeHandler.handler,
                        args = args,
                        context = context,
                        handlers = handlers,
                    )
                }
            }
        }

        return when (fnValue) {
            is Value.Closure -> applyClosure(id, app, fnValue, env, context, handlers)
            is Value.ForeignFn -> applyForeign(id, app, fnValue, env, context, handlers)
            is Value.FixpointFn -> applyFixpoint(id, app, fnValue, env, context, handlers)
            else -> throw InterpretException(
                InterpretError.NotCallable(at = id, gotKind = fnValue::class.simpleName ?: "Value")
            )
        }
    }

    /**
     * Declared effects of a value-level callable. Used by [applyCall] to
     * decide whether any active handler intercepts a particular call.
     *
     *  - Closure: the Lambda's `effects` list.
     *  - ForeignFn: the ForeignNode's `effects` list.
     *  - FixpointFn: the body Lambda's `effects` list. The body's effects
     *    are equal (by verifier construction) to the recursionType's
     *    effects.
     *  - Other shapes (primitives, ProductV, SumV): no callable effects;
     *    return empty. These are unreachable at the call site of a verified
     *    program but we keep the helper total.
     */
    private fun effectsOf(value: Value): Set<NodeId> = when (value) {
        is Value.Closure -> value.lambda.effects.toSet()
        is Value.ForeignFn -> value.node.effects.toSet()
        is Value.FixpointFn -> value.bodyLambda.effects.toSet()
        else -> emptySet()
    }

    /**
     * Invoke a pre-evaluated callable [callable] on already-evaluated
     * [args]. Mirrors [applyCall]'s dispatch but skips the argument
     * evaluation step (the caller — the handler dispatch path — already
     * evaluated arguments once). Capability checks DO run for the handler
     * itself (its own effects must be covered by the surrounding context).
     *
     * The handler is invoked under the SAME handlers list as the
     * intercepted call site: a handler that itself calls into a handled
     * operation nests normally. Re-entrancy through the same handler is
     * permitted (the handler is just a function); for now we do not add
     * a recursion guard, matching § 7.5 of the proposal.
     */
    private fun applyValue(
        id: NodeId,
        callable: Value,
        args: List<Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value = when (callable) {
        is Value.Closure -> {
            if (callable.lambda.parameters.size != args.size) {
                throw InterpretException(InterpretError.ArityMismatch(
                    at = id, expected = callable.lambda.parameters.size, actual = args.size
                ))
            }
            // The handler's own declared effects fire in the surrounding
            // context. We do not have Application.effectInstances here
            // (the call site that supplied them was the intercepted call,
            // whose declared category is now the handler's responsibility);
            // run an instance-free capability check so categories the
            // handler declares need only be category-present in the
            // context, not refinement-matched. (Refinement matching for
            // handler-internal effects would require the handler to carry
            // its own EffectDecls, which the no-continuation form does
            // not.)
            checkCapabilities(id, callable.lambda.effects, emptyMap(), context)
            var callEnv = callable.env
            for ((paramId, value) in callable.lambda.parameters.zip(args)) {
                callEnv = callEnv + (paramId to value)
            }
            eval(callable.lambda.body, callEnv, context, handlers)
        }
        is Value.ForeignFn -> {
            checkCapabilities(id, callable.node.effects, emptyMap(), context)
            foreignDispatcher?.dispatch(callable.node.target, args)?.let { return it }
            val builtin = Builtins.lookup(callable.node.target)
                ?: throw InterpretException(
                    InterpretError.UnknownForeignTarget(at = id, target = callable.node.target)
                )
            try {
                builtin.invoke(args)
            } catch (io: IoFailure) {
                throw InterpretException(
                    InterpretError.IoFailure(at = id, kind = io.kind, detail = io.detail)
                )
            }
        }
        is Value.FixpointFn -> {
            val userArity = callable.bodyLambda.parameters.size - 1
            if (userArity != args.size) {
                throw InterpretException(InterpretError.ArityMismatch(
                    at = id, expected = userArity, actual = args.size
                ))
            }
            checkCapabilities(id, callable.bodyLambda.effects, emptyMap(), context)
            var callEnv = callable.env + (callable.bodyLambda.parameters[0] to callable)
            for ((i, paramId) in callable.bodyLambda.parameters.drop(1).withIndex()) {
                callEnv = callEnv + (paramId to args[i])
            }
            eval(callable.bodyLambda.body, callEnv, context, handlers)
        }
        else -> throw InterpretException(
            InterpretError.NotCallable(at = id, gotKind = callable::class.simpleName ?: "Value")
        )
    }

    private fun applyFixpoint(
        id: NodeId,
        app: Node.Application,
        fn: Value.FixpointFn,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value {
        // The body Lambda has one MORE parameter than the user-facing
        // arity: the first parameter is the recursive call slot, the rest
        // are the actual arguments. So:
        //
        //   user-arity = bodyLambda.parameters.size - 1
        //
        val userArity = fn.bodyLambda.parameters.size - 1
        if (userArity != app.arguments.size) {
            throw InterpretException(InterpretError.ArityMismatch(
                at = id, expected = userArity, actual = app.arguments.size
            ))
        }
        // Capability check uses the body Lambda's declared effects (which
        // by verifier construction equal the recursionType's effects).
        val instances = evalEffectInstances(env, context, handlers, app)
        checkCapabilities(id, fn.bodyLambda.effects, instances, context)
        val args = app.arguments.map { eval(it, env, context, handlers) }
        // Build the call env: capture-time env + (self → this FixpointFn) +
        // (each remaining parameter → corresponding argument).
        var callEnv = fn.env + (fn.bodyLambda.parameters[0] to fn)
        for ((i, paramId) in fn.bodyLambda.parameters.drop(1).withIndex()) {
            callEnv = callEnv + (paramId to args[i])
        }
        return eval(fn.bodyLambda.body, callEnv, context, handlers)
    }

    internal fun applyClosure(
        id: NodeId,
        app: Node.Application,
        fn: Value.Closure,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value {
        if (fn.lambda.parameters.size != app.arguments.size) {
            throw InterpretException(InterpretError.ArityMismatch(
                at = id, expected = fn.lambda.parameters.size, actual = app.arguments.size
            ))
        }
        val instances = evalEffectInstances(env, context, handlers, app)
        checkCapabilities(id, fn.lambda.effects, instances, context)
        val args = app.arguments.map { eval(it, env, context, handlers) }
        var callEnv = fn.env
        for ((paramId, value) in fn.lambda.parameters.zip(args)) {
            callEnv = callEnv + (paramId to value)
        }
        return eval(fn.lambda.body, callEnv, context, handlers)
    }

    private fun applyForeign(
        id: NodeId,
        app: Node.Application,
        fn: Value.ForeignFn,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value {
        // ForeignNodes don't carry parameter NodeIds the way Lambdas do —
        // their arity is determined by their foreignType. Verifier-level
        // arity checking happens via Application's type-check (the
        // function's FunctionType drives parameter count); we trust that
        // and dispatch.
        val instances = evalEffectInstances(env, context, handlers, app)
        checkCapabilities(id, fn.node.effects, instances, context)
        val args = app.arguments.map { eval(it, env, context, handlers) }
        foreignDispatcher?.dispatch(fn.node.target, args)?.let { return it }
        // Higher-order lookup wins over standard lookup; the registries
        // are disjoint so this ordering is conservative.
        val higherOrder = Builtins.lookupHigherOrder(fn.node.target)
        if (higherOrder != null) {
            val apply = Builtins.ApplyFn { callable, callbackArgs ->
                applyValueToArgs(id, callable, callbackArgs, context, handlers)
            }
            return try {
                higherOrder.invoke(args, apply)
            } catch (io: IoFailure) {
                throw InterpretException(
                    InterpretError.IoFailure(at = id, kind = io.kind, detail = io.detail)
                )
            }
        }
        val builtin = Builtins.lookup(fn.node.target)
            ?: throw InterpretException(
                InterpretError.UnknownForeignTarget(at = id, target = fn.node.target)
            )
        return try {
            builtin.invoke(args)
        } catch (io: IoFailure) {
            // Translate runtime IoFailure (thrown by Layer 4 step 2
            // builtins on actual OS failures) into a structured
            // InterpretError carrying the call-site NodeId.
            throw InterpretException(
                InterpretError.IoFailure(at = id, kind = io.kind, detail = io.detail)
            )
        }
    }

    /**
     * Apply a runtime [Value] callable to pre-evaluated [args] without
     * an enclosing Application node. Used by higher-order builtins
     * (Slice 2 of stdlib expansion round 2) to invoke user-supplied
     * lambdas on each element of a collection.
     *
     * Reuses the current [context] and [handlers] from the enclosing
     * higher-order builtin's call site — the verifier guarantees that
     * the surrounding Application's effect declarations cover the
     * callback's effects (the higher-order builtin's signature
     * propagates the callable's effects to its own).
     *
     * Effect-instance evaluation is skipped because the callback has
     * no per-call Application node and therefore no `effectInstances`
     * to evaluate — refinement checking for parameterized effects
     * inside higher-order callbacks is a follow-up (current
     * higher-order builtins only deal with collections of plain
     * values).
     *
     * @param id NodeId of the enclosing higher-order builtin call (used
     *           for error reporting if [callable] is not callable or
     *           has wrong arity).
     */
    private fun applyValueToArgs(
        id: NodeId,
        callable: Value,
        args: List<Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
    ): Value {
        return when (callable) {
            is Value.Closure -> {
                if (callable.lambda.parameters.size != args.size) {
                    throw InterpretException(InterpretError.ArityMismatch(
                        at = id, expected = callable.lambda.parameters.size, actual = args.size,
                    ))
                }
                var callEnv = callable.env
                for ((paramId, value) in callable.lambda.parameters.zip(args)) {
                    callEnv = callEnv + (paramId to value)
                }
                eval(callable.lambda.body, callEnv, context, handlers)
            }
            is Value.FixpointFn -> {
                // Body has args.size + 1 parameters: param[0] is the self-slot.
                val userArity = callable.bodyLambda.parameters.size - 1
                if (userArity != args.size) {
                    throw InterpretException(InterpretError.ArityMismatch(
                        at = id, expected = userArity, actual = args.size,
                    ))
                }
                var callEnv = callable.env + (callable.bodyLambda.parameters[0] to callable)
                for ((i, paramId) in callable.bodyLambda.parameters.drop(1).withIndex()) {
                    callEnv = callEnv + (paramId to args[i])
                }
                eval(callable.bodyLambda.body, callEnv, context, handlers)
            }
            is Value.ForeignFn -> {
                // Dispatch directly to the builtin registry. ForeignFn
                // callbacks (passing e.g. Bool.Not as a List.Map fn) are
                // rare but legitimate — and they don't recurse into the
                // higher-order machinery because Bool.Not is a standard Fn.
                foreignDispatcher?.dispatch(callable.node.target, args)?.let { return it }
                val builtin = Builtins.lookup(callable.node.target)
                    ?: throw InterpretException(
                        InterpretError.UnknownForeignTarget(at = id, target = callable.node.target)
                    )
                try {
                    builtin.invoke(args)
                } catch (io: IoFailure) {
                    throw InterpretException(
                        InterpretError.IoFailure(at = id, kind = io.kind, detail = io.detail)
                    )
                }
            }
            else -> throw InterpretException(
                InterpretError.NotCallable(at = id, gotKind = callable::class.simpleName ?: "Value")
            )
        }
    }

    /**
     * Evaluate every [Node.EffectDecl] in `app.effectInstances` to a
     * `Map<EffectCategory NodeId, List<Value>>` (category → evaluated
     * parameter values for this call site). Returns an empty map when the
     * Application has no effect instances — the [checkCapabilities] check
     * then runs against empty requirements per category, which the
     * back-compat [CapabilityPattern] sentinel from
     * [CapabilitySet.ofCategories] covers trivially.
     *
     * The verifier guarantees every EffectDecl is well-formed (correct
     * effectType, arity, parameter types). The evaluation order is the
     * declared order of the effectInstances list, which is irrelevant
     * because the map keying is by EffectCategory NodeId — two
     * Applications differing only in instance declaration order behave
     * identically at runtime.
     */
    private fun evalEffectInstances(
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
        app: Node.Application,
    ): Map<NodeId, List<Value>> {
        if (app.effectInstances.isEmpty()) return emptyMap()
        val out = LinkedHashMap<NodeId, List<Value>>(app.effectInstances.size)
        for (effectDeclId in app.effectInstances) {
            val effectDecl = store.get(effectDeclId) as Node.EffectDecl
            val paramValues = effectDecl.parameters.map { eval(it, env, context, handlers) }
            out[effectDecl.effectType] = paramValues
        }
        return out
    }

    /**
     * Refinement-lattice capability check per Q-031 § 5. For each
     * EffectCategory in [declared], look up the granted patterns in
     * [context]:
     *   - missing category entirely → CapabilityViolation
     *   - present but no pattern covers the requirement → RefinementViolation
     *
     * The requirement for a category is the evaluated EffectDecl parameter
     * list from [instances]. The refinement check fires ONLY for categories
     * with an explicit EffectDecl at this call site — categories declared
     * by the callee but absent from [instances] are treated as
     * "propagating only at this site" and pass the check after the
     * category-presence check. This is what makes the proposal § 8
     * scenario 9 (confused-deputy) work: the *outer* call (caller →
     * logger) supplies no effectInstances and so does not need to cover
     * the inner write's refinement at this site; the *inner* call
     * (logger → Filesystem.Write) does supply an effectInstance and is
     * checked against the granted pattern.
     *
     * Implicit forwarding (§ Delegation semantics) is preserved: the
     * capability flows down the call chain unchanged, and refinement is
     * checked at the specific call site that actually exercises the
     * effect with concrete arguments. Capability minimization at scope
     * entry (CapabilityScope) and parameter-tagged capabilities are
     * additive mitigations, both expressible.
     *
     * The split between [InterpretError.CapabilityViolation] and
     * [InterpretError.RefinementViolation] is intentional: the policy
     * author sees which kind of denial happened ("I never granted this
     * category" vs "I granted this category but not for this resource").
     */
    private fun checkCapabilities(
        at: NodeId,
        declared: List<NodeId>,
        instances: Map<NodeId, List<Value>>,
        context: CapabilitySet,
    ) {
        // First pass: surface every category that is entirely absent in
        // one error. Mirrors the pre-Q-031 CapabilityViolation shape so
        // existing tests that inspect `missing` keep working.
        val missing = declared.toSet().filter { it !in context.grants }.toSet()
        if (missing.isNotEmpty()) {
            throw InterpretException(InterpretError.CapabilityViolation(at = at, missing = missing))
        }
        // Second pass: per-category refinement check. Only fires when the
        // call site supplied an EffectDecl for the category — categories
        // without an explicit instance pass through (the call is
        // propagating the requirement, not exercising it concretely).
        for (category in declared) {
            val requirement = instances[category] ?: continue
            val grants = context.grants[category]!! // non-null: first pass filtered missing
            val matched = grants.any { covers(it, requirement) }
            if (!matched) {
                throw InterpretException(InterpretError.RefinementViolation(
                    at = at,
                    category = category,
                    requirement = requirement,
                    available = grants,
                ))
            }
        }
    }
}
