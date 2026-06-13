package org.strand.interpreter

import org.strand.core.EvaluationLimits
import org.strand.core.ExhaustionKind
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
    /**
     * Q-043 step 3a cross-store resolution callback, consulted when a NodeRef
     * target hash is not in [hashToNodeId]. In a federated run the caller wires
     * it to `FederatedProgram::fetchAndAdmit`, which fetches the target subgraph
     * from a peer store, re-bases it into the shared [store], extends the shared
     * [hashToNodeId], and returns its local NodeId; evaluation then proceeds
     * into the admitted node. Default null: single-store behavior is preserved
     * (a NodeRef miss is an [InterpretError.NodeRefTargetNotInStore]). A
     * federated caller must pass the same mutable [store] / [hashToNodeId] the
     * callback extends so admitted nodes are visible here.
     */
    private val resolveTarget: ((Hash) -> NodeId?)? = null,
    /**
     * Q-047 (Layer 7 step 2): runtime schema obligations. Maps each
     * value-flow-site NodeId the verifier re-recorded with a
     * [org.strand.verifier.TypeExpr.SchemaType] to that SchemaType. After
     * the interpreter reduces such a NodeId to a [Value], it evaluates the
     * schema's pure-expression invariants against the value and raises
     * [InterpretError.SchemaInvariantViolation] on a `false` verdict —
     * enforcing at runtime the obligations the verify-time
     * [org.strand.schema.SchemaChecker] could only defer for dynamic
     * values.
     *
     * Default empty: a caller that passes no obligations (the runtime
     * module's per-actor interpreters, the SchemaChecker's own internal
     * interpreter, every pre-Q-047 test) enforces nothing — behaviour is
     * unchanged. The CLI `run` path builds the map from
     * `VerifyResult.Ok.nodeTypes` and passes it in. Invariant bodies are
     * evaluated with obligation-checking suppressed (see [inInvariant]) so
     * a predicate's internal structure is never mistaken for the value
     * under test.
     */
    private val schemaObligations: Map<NodeId, org.strand.verifier.TypeExpr.SchemaType> = emptyMap(),
    /**
     * Q-054 follow-up: the host policy this interpreter evaluates under,
     * projected to a [HostContext]. Every builtin invocation receives this
     * context; effectful builtins read their clock / random / sandbox /
     * credentials / etc. from it rather than from the [Builtins] process-global
     * singletons. Two interpreters constructed with different contexts run
     * effectful programs concurrently in one JVM without clobbering each other.
     *
     * Default [HostContext.processDefault] reads the current [Builtins]
     * singletons, so a direct `Interpreter(store, ...)` construction (every
     * pre-Q-054-follow-up test, the SchemaChecker's internal interpreter)
     * behaves exactly as before — including honouring a `Builtins.clock =
     * FixedClock(...)` a test installs before constructing the interpreter.
     */
    private val hostContext: HostContext = HostContext.processDefault(),
) {

    /**
     * Q-047: re-entrancy guard for schema-obligation checking. Set while
     * an invariant body is being evaluated so that obligation sites
     * *inside* the predicate do not fire (the body is verified pure and
     * `(valueType) -> Bool`; its sub-nodes describe the predicate, not a
     * value flowing into a schema position). Single-threaded per
     * Interpreter instance, matching the tree-walker's execution model.
     */
    private var inInvariant: Boolean = false

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
        eval(root, capabilities, EvaluationLimits.DEFAULTS)

    /**
     * Q-040: top-level evaluation under explicit [capabilities] and host-
     * configured [limits]. Allocates a fresh [EvalCounters] for this run;
     * the counters do not survive across [eval] calls.
     */
    fun eval(root: NodeId, capabilities: CapabilitySet, limits: EvaluationLimits): Value =
        eval(
            id = root,
            env = emptyMap(),
            context = capabilities,
            handlers = emptyList(),
            counters = EvalCounters(),
            limits = limits,
        )

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
    ): Value = applyCallable(fn, args, capabilities, EvaluationLimits.DEFAULTS)

    /**
     * Q-040: apply [fn] to [args] with explicit [limits]. The runtime
     * module's [StateMachineRuntime] threads a per-run (or per-actor)
     * [EvalCounters] through the [applyCallable] sequence; the public
     * overload taking [limits] but no counters allocates a fresh
     * [EvalCounters] for this single call.
     */
    fun applyCallable(
        fn: Value,
        args: List<Value>,
        capabilities: CapabilitySet,
        limits: EvaluationLimits,
    ): Value = applyCallable(fn, args, capabilities, EvalCounters(), limits)

    /**
     * Q-040: apply variant that reuses an existing [counters] struct. The
     * state-machine runtime uses this to share one budget across many
     * per-event closure invocations.
     *
     * Public so the `:runtime` module's actor / fold loops can share a
     * counter across events. [EvalCounters] is the carrier; it is
     * deliberately a thin mutable struct rather than a sealed type — the
     * runtime mutates it per call to keep the budget intact across
     * dispatch boundaries.
     */
    fun applyCallable(
        fn: Value,
        args: List<Value>,
        capabilities: CapabilitySet,
        counters: EvalCounters,
        limits: EvaluationLimits,
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
        counters = counters,
        limits = limits,
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

    /**
     * Q-040 per-evaluation counter struct. Allocated at every public entry
     * point and threaded through every recursive descent. The fields are
     * mutated in place to keep the per-step cost at a few increments and
     * compares — the proposal's § 4.3 design.
     *
     *  - [steps]: total `eval` calls during this evaluation. Bumped at the
     *    head of every `eval`. Reaching [EvaluationLimits.maxSteps] raises
     *    [InterpretError.ResourceExhaustion] with [ExhaustionKind.Steps].
     *  - [currentDepth]: dispatch-stack depth at this point. Incremented
     *    at the head of every `eval` and decremented in a `finally` so
     *    the count is correct even when an `InterpretException` unwinds.
     *  - [allocated]: total `Value.*` constructions during this
     *    evaluation. Bumped via [allocV] at each value-construction site.
     *  - [startNanos]: wall-clock anchor captured once at counter
     *    construction. Wall-clock samples compute `(now - startNanos) /
     *    1_000_000` and compare against
     *    [EvaluationLimits.wallClockBudgetMillis].
     */
    data class EvalCounters(
        var steps: Long = 0L,
        var currentDepth: Int = 0,
        var allocated: Long = 0L,
        val startNanos: Long = System.nanoTime(),
    )

    /**
     * Q-042: translate a runtime [IoFailure] thrown by a foreign call into
     * the verifier-translated [InterpretError.IoFailure] surfaced to the
     * agent, honouring the active [ErrorVerbosity].
     *
     *  - [ErrorVerbosity.Redacted] (default) reads the already-scrubbed
     *    `io.detail` field — every registered credential value has been
     *    replaced with its `[REDACTED:<provider>:<key>]` placeholder.
     *  - [ErrorVerbosity.Full] reads `io.unscrubbedDetail` — the raw
     *    interpolated text. Opt-in, dev/debug only.
     *  - [ErrorVerbosity.RedactedWithKindOnly] strips the detail
     *    entirely, surfacing only the structured `kind` discriminator.
     *
     * The verbosity arrives via [EvaluationLimits.errorVerbosity] threaded
     * from the runtime entry point through the existing limits-plumbing.
     */
    private fun translateIoFailure(
        at: NodeId,
        io: IoFailure,
        limits: EvaluationLimits,
    ): InterpretError.IoFailure {
        // Q-054 follow-up: the Redacted detail is scrubbed against THIS
        // interpreter's per-context scrubber, so the redaction uses the active
        // tenant's resolved credentials — never another concurrent tenant's.
        // The scrubber the IoFailure constructor already applied (the
        // process-default) is bypassed here by scrubbing the unscrubbed detail
        // afresh.
        val detail = when (limits.errorVerbosity) {
            org.strand.core.ErrorVerbosity.Redacted -> hostContext.scrubber.scrub(io.unscrubbedDetail)
            org.strand.core.ErrorVerbosity.Full -> io.unscrubbedDetail
            org.strand.core.ErrorVerbosity.RedactedWithKindOnly -> "(detail suppressed)"
        }
        return InterpretError.IoFailure(at = at, kind = io.kind, detail = detail)
    }

    /**
     * Q-041: translate a runtime [SandboxViolation] thrown by
     * [FsSandbox.resolve] or [NetSandbox.checkConnect] (or by a builtin
     * that detects an out-of-policy scheme, etc.) into the
     * verifier-translated [InterpretError.SandboxViolation] surfaced to
     * the agent.
     *
     * Mirrors [translateIoFailure]: same verbosity branching, same
     * scrubbed-vs-unscrubbed detail discipline. The detail strings are
     * built from path arguments and host/port values — none of which
     * should contain credentials — but the verbosity gate is honoured
     * defensively in case a future policy interpolates them.
     */
    private fun translateSandboxViolation(
        at: NodeId,
        sv: SandboxViolation,
        limits: EvaluationLimits,
    ): InterpretError.SandboxViolation {
        val detail = when (limits.errorVerbosity) {
            org.strand.core.ErrorVerbosity.Redacted -> hostContext.scrubber.scrub(sv.unscrubbedDetail)
            org.strand.core.ErrorVerbosity.Full -> sv.unscrubbedDetail
            org.strand.core.ErrorVerbosity.RedactedWithKindOnly -> "(detail suppressed)"
        }
        return InterpretError.SandboxViolation(at = at, kind = sv.kind, detail = detail)
    }

    private fun eval(
        id: NodeId,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Value {
        // Q-040: per-step accounting. Three increments + three compares at
        // the head of every eval call; the wall-clock sample fires every
        // [EvaluationLimits.wallClockSampleEvery] steps.
        counters.steps++
        if (counters.steps > limits.maxSteps) {
            throw InterpretException(InterpretError.ResourceExhaustion(
                at = id,
                kind = ExhaustionKind.Steps,
                current = counters.steps,
                limit = limits.maxSteps,
            ))
        }
        counters.currentDepth++
        if (counters.currentDepth > limits.maxStackDepth) {
            throw InterpretException(InterpretError.ResourceExhaustion(
                at = id,
                kind = ExhaustionKind.StackDepth,
                current = counters.currentDepth.toLong(),
                limit = limits.maxStackDepth.toLong(),
            ))
        }
        if (limits.wallClockSampleEvery > 0 &&
            counters.steps % limits.wallClockSampleEvery == 0L) {
            val elapsedMs = (System.nanoTime() - counters.startNanos) / 1_000_000L
            if (elapsedMs > limits.wallClockBudgetMillis) {
                throw InterpretException(InterpretError.ResourceExhaustion(
                    at = id,
                    kind = ExhaustionKind.WallClock,
                    current = elapsedMs,
                    limit = limits.wallClockBudgetMillis,
                ))
            }
        }
        try {
            val node = store.getOrNull(id)
                ?: throw InterpretException(InterpretError.MissingNode(at = id, missing = id))
            val result = when (node) {
                is Node.IntLit -> allocV(counters, limits, id, Value.IntV(node.value))
                is Node.FloatLit -> allocV(counters, limits, id, Value.FloatV(node.value))
                is Node.StringLit -> allocV(counters, limits, id, Value.StringV(node.value))
                is Node.BoolLit -> allocV(counters, limits, id, Value.BoolV(node.value))
                Node.UnitLit -> Value.UnitV
                is Node.BytesLit -> allocV(counters, limits, id, Value.BytesV(node.value))

                is Node.Lambda -> allocV(counters, limits, id, Value.Closure(lambda = node, env = env, self = id))
                is Node.ForeignNode -> allocV(counters, limits, id, Value.ForeignFn(node = node, self = id))
                is Node.Fixpoint -> {
                    // The body must be a Lambda (the verifier confirms this).
                    // Capture it now so applyCall can extend the env without
                    // re-resolving the body NodeId on every recursive call.
                    val bodyLambda = store.getOrNull(node.body) as? Node.Lambda
                        ?: throw InterpretException(InterpretError.MissingNode(at = id, missing = node.body))
                    allocV(counters, limits, id, Value.FixpointFn(
                        fixpoint = node,
                        bodyLambda = bodyLambda,
                        env = env,
                        self = id,
                    ))
                }
                is Node.TypeAbstraction -> eval(node.body, env, context, handlers, counters, limits) // erased at runtime
                is Node.Attempt -> evalAttempt(id, node, env, context, handlers, counters, limits)
                is Node.Application -> applyCall(id, node, env, context, handlers, counters, limits)
                is Node.Let -> {
                    val bound = eval(node.value, env, context, handlers, counters, limits)
                    eval(node.body, env + (id to bound), context, handlers, counters, limits)
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
                        ?: resolveTarget?.invoke(node.target)
                        ?: throw InterpretException(
                            InterpretError.NodeRefTargetNotInStore(at = id, targetHash = node.target)
                        )
                    eval(targetId, env = emptyMap(), context = context, handlers = handlers, counters = counters, limits = limits)
                }

                is Node.CapabilityScope -> {
                    // Narrow: per-category pattern lists carry through verbatim
                    // for retained categories. Refinement-narrowing (tightening
                    // patterns within a retained category) is a deferred
                    // follow-up; see Q-031 § Tradeoffs and open questions.
                    val narrowed = context.intersect(node.capabilities.toSet())
                    eval(node.body, env, narrowed, handlers, counters, limits)
                }

                is Node.Handler -> {
                    // Evaluate the handle expression ONCE, at Handler-entry, in
                    // the OUTER handlers list (so a nested Handler may itself
                    // be handled). The result is captured in the ActiveHandler
                    // and re-used at every intercepted call site.
                    val handlerValue = eval(node.handle, env, context, handlers, counters, limits)
                    val newHandlers = handlers + ActiveHandler(node.intercept, handlerValue)
                    eval(node.body, env, context, newHandlers, counters, limits)
                }

                is Node.Match -> evalMatch(id, node, env, context, handlers, counters, limits)

                is Node.ProductValue -> evalProductValue(id, node, env, context, handlers, counters, limits)
                is Node.ProductFieldGet -> evalProductFieldGet(id, node, env, context, handlers, counters, limits)
                is Node.SumValue -> evalSumValue(id, node, env, context, handlers, counters, limits)

                is Node.ToolDef -> {
                    // Eagerly evaluate the implementation expression — typically
                    // a Lambda or ForeignNode, which produces a callable value
                    // without firing effects (effects are released at the
                    // tool-dispatch sites inside the provider's loop, not here).
                    val implValue = eval(node.implementation, env, context, handlers, counters, limits)
                    allocV(counters, limits, id, Value.ToolDefV(
                        self = id,
                        name = node.name,
                        description = node.description,
                        parameterSchemaId = node.parameterSchema,
                        implementation = implValue,
                    ))
                }

                is Node.ResponseSchemaSpec -> {
                    // N-045. The wrapper has no inner expression to evaluate
                    // — the only structural content is the Schema reference,
                    // which the runtime carries forward as a NodeId for
                    // later JSON Schema projection at the LLM.Generate
                    // dispatch site. Constructing the carrier exercises no
                    // effects.
                    allocV(counters, limits, id, Value.ResponseSchemaSpecV(
                        self = id,
                        schemaId = node.schema,
                    ))
                }

                is Node.ModuleManifest ->
                    // N-046 is a passive declaration with no runtime evaluation
                    // semantics (proposal § 6). It is not an expression and no
                    // Application targets it; reaching it here means a manifest
                    // was a program root handed to `eval` (e.g. `strand run` on a
                    // published library). Its value is Unit. Value.UnitV is the
                    // shared singleton, so no allocV bump (matching UnitLit).
                    Value.UnitV

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
            is Node.RecursiveProjection,
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
            // Q-047 (Layer 7 step 2): if this NodeId is a schema-typed
            // value-flow site, enforce the schema's invariants against the
            // value we just produced. No-op when no obligations are
            // installed (the common case) or while evaluating an invariant
            // body (see [inInvariant]).
            checkSchemaObligations(id, result, counters, limits)
            return result
        } finally {
            counters.currentDepth--
        }
    }

    /**
     * Q-047: enforce any schema obligation recorded for [id] against the
     * value [v] the interpreter just produced for it. For each invariant
     * the schema declares, evaluate the invariant's pure-expression body
     * on [v]; a `false` verdict raises [InterpretError.SchemaInvariantViolation]
     * blaming [id]. A non-Bool verdict is a defensive internal error (the
     * verifier's `SchemaInvariantBodyTypeMismatch` rule guarantees the body
     * is `(valueType) -> Bool`). Invariants are checked in declaration
     * order; the first failure wins.
     */
    private fun checkSchemaObligations(
        id: NodeId,
        v: Value,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ) {
        if (schemaObligations.isEmpty() || inInvariant) return
        val obligation = schemaObligations[id] ?: return
        for (invariantId in obligation.invariants) {
            val invariantNode = store.getOrNull(invariantId) as? Node.Invariant ?: continue
            val verdict = evaluateInvariantBody(invariantNode.body, v, counters, limits)
            if (verdict !is Value.BoolV) {
                error(
                    "Invariant $invariantId's body evaluated to a non-Bool value " +
                        "($verdict); the verifier's SchemaInvariantBodyTypeMismatch rule should have rejected this."
                )
            }
            if (!verdict.v) {
                throw InterpretException(InterpretError.SchemaInvariantViolation(
                    at = id,
                    schema = obligation.schemaId,
                    invariant = invariantId,
                    valueDescription = v.toString(),
                ))
            }
        }
    }

    /**
     * Q-047: evaluate an invariant body Lambda on [value], with
     * obligation-checking suppressed for the duration so the predicate's
     * own structure is not re-checked. The body is pure (verifier rule),
     * so it runs under an empty capability context; it shares the
     * surrounding run's [counters] / [limits] so a pathological predicate
     * cannot escape the Q-040 budget.
     */
    private fun evaluateInvariantBody(
        bodyId: NodeId,
        value: Value,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Value {
        val prev = inInvariant
        inInvariant = true
        try {
            val fn = eval(bodyId, emptyMap(), CapabilitySet.EMPTY, emptyList(), counters, limits)
            return applyCallable(fn, listOf(value), CapabilitySet.EMPTY, counters, limits)
        } finally {
            inInvariant = prev
        }
    }

    /**
     * Q-040: count one Value.* construction and return [v] unchanged.
     * Wraps every reachable `Value.*` constructor in the dispatch path so
     * an allocation-explosion program (a billion-element list) hits the
     * cap before the JVM raises `OutOfMemoryError`.
     *
     * The check is at-most-once per allocation; the at-construction guard
     * means a partially-constructed allocation cannot survive past the
     * boundary. Increment-then-compare lets the error carry the actual
     * count past the limit (the proposal's § 4.1 contract).
     *
     * Value.UnitV is the one Value.* singleton we don't bump for —
     * sharing a single instance has no per-instance memory cost and the
     * literal-Unit traffic of a typical program would otherwise be the
     * dominant signal under `maxAllocatedValues`.
     */
    private fun allocV(counters: EvalCounters, limits: EvaluationLimits, at: NodeId?, v: Value): Value {
        counters.allocated++
        if (counters.allocated > limits.maxAllocatedValues) {
            throw InterpretException(InterpretError.ResourceExhaustion(
                at = at,
                kind = ExhaustionKind.AllocatedValues,
                current = counters.allocated,
                limit = limits.maxAllocatedValues,
            ))
        }
        return v
    }

    private fun evalProductValue(
        id: NodeId,
        node: Node.ProductValue,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Value {
        // Evaluate each field in declaration order. The verifier confirmed
        // type correctness and completeness, so we can trust the structure.
        val fields = LinkedHashMap<String, Value>(node.fields.size)
        for (fieldId in node.fields) {
            val fieldNode = store.get(fieldId) as Node.ProductFieldValue
            fields[fieldNode.fieldName] = eval(fieldNode.value, env, context, handlers, counters, limits)
        }
        return allocV(counters, limits, id, Value.ProductV(fields))
    }

    private fun evalProductFieldGet(
        id: NodeId,
        node: Node.ProductFieldGet,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Value {
        val target = eval(node.target, env, context, handlers, counters, limits)
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
        id: NodeId,
        node: Node.SumValue,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Value {
        val payload = node.payload?.let { eval(it, env, context, handlers, counters, limits) }
        return allocV(counters, limits, id, Value.SumV(node.caseName, payload))
    }

    /**
     * N-047 Attempt (Q-048, proposals/error-recovery.md § 6.1). Evaluate the
     * body; on success produce `SumV("Ok", v)`; on a *catchable*
     * [InterpretException] (the `isCatchable` taxonomy — [InterpretError.IoFailure]
     * and [InterpretError.SchemaInvariantViolation] only) produce
     * `SumV("Err", {kind, detail})` and continue. Uncatchable errors
     * (program defects, host-policy stops, budget exhaustion, sandbox
     * denials) re-throw unchanged.
     *
     * Only [InterpretException] is inspected: raw JVM exceptions (e.g. the
     * `require(...)` contract failures of [Builtins]) are NOT caught, so
     * anything unstructured remains terminal by default. Capability context,
     * the active-handler list, and the schema-obligation map all thread
     * through the body unchanged — Attempt is transparent to all three.
     * Stack-depth bookkeeping needs no special handling: each `eval` frame
     * decrements `counters.currentDepth` in its `finally`, so unwinding to
     * this catch restores the correct depth.
     */
    private fun evalAttempt(
        id: NodeId,
        node: Node.Attempt,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Value {
        return try {
            val v = eval(node.body, env, context, handlers, counters, limits)
            allocV(counters, limits, id, Value.SumV("Ok", v))
        } catch (e: InterpretException) {
            if (!e.error.isCatchable) throw e
            allocV(counters, limits, id, Value.SumV("Err", errorPayload(e.error, counters, limits, id)))
        }
    }

    /**
     * Build the `ErrorPayload = {kind: String, detail: String}` product for a
     * caught error (proposals/error-recovery.md § 4.2). The strings are taken
     * from the already-translated [InterpretError] — `IoFailure.detail` has
     * passed through the Q-042 credential scrubber and verbosity gate at
     * construction, so the caught detail is exactly the scrubbed form the
     * host would have seen. For [InterpretError.SchemaInvariantViolation] the
     * kind is the fixed tag `"schema-invariant"` and the detail is the
     * value-description (which deliberately excludes the offending value's
     * full structure — only `valueDescription` for diagnostics). The error is
     * always catchable by the time this is called.
     */
    private fun errorPayload(
        error: InterpretError,
        counters: EvalCounters,
        limits: EvaluationLimits,
        at: NodeId,
    ): Value {
        val (kind, detail) = when (error) {
            is InterpretError.IoFailure -> error.kind to error.detail
            is InterpretError.SchemaInvariantViolation -> "schema-invariant" to error.valueDescription
            else -> error("errorPayload called on uncatchable error $error; " +
                "evalAttempt must check isCatchable before constructing a payload.")
        }
        val fields = linkedMapOf<String, Value>(
            "kind" to allocV(counters, limits, at, Value.StringV(kind)),
            "detail" to allocV(counters, limits, at, Value.StringV(detail)),
        )
        return allocV(counters, limits, at, Value.ProductV(fields))
    }

    private fun evalMatch(
        id: NodeId,
        node: Node.Match,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Value {
        val scrutineeValue = eval(node.scrutinee, env, context, handlers, counters, limits)
        for (caseId in node.cases) {
            val case = store.getOrNull(caseId) as? Node.MatchCase
                ?: throw InterpretException(InterpretError.MissingNode(at = id, missing = caseId))
            val pattern = store.getOrNull(case.pattern) as? Node.Pattern
                ?: throw InterpretException(InterpretError.MissingNode(at = caseId, missing = case.pattern))
            val bindings = tryMatch(scrutineeValue, case.pattern, pattern, env, context, handlers, counters, limits)
            if (bindings != null) {
                return eval(case.body, env + bindings, context, handlers, counters, limits)
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
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Map<NodeId, Value>? = when (pattern) {
        is Node.Pattern.LiteralPattern -> {
            // Evaluate the literal node and compare by value equality. The
            // verifier confirmed the literal's type matches the pattern's
            // type; the value comparison here is the runtime test.
            val literalValue = eval(pattern.literal, env, context, handlers, counters, limits)
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
                    tryMatch(payloadValue, payloadPatternId, payloadPattern, env, context, handlers, counters, limits)
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
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Value {
        val fnValue = eval(app.function, env, context, handlers, counters, limits)

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
                    val args = app.arguments.map { eval(it, env, context, handlers, counters, limits) }
                    return applyValue(
                        id = id,
                        callable = activeHandler.handler,
                        args = args,
                        context = context,
                        handlers = handlers,
                        counters = counters,
                        limits = limits,
                    )
                }
            }
        }

        return when (fnValue) {
            is Value.Closure -> applyClosure(id, app, fnValue, env, context, handlers, counters, limits)
            is Value.ForeignFn -> applyForeign(id, app, fnValue, env, context, handlers, counters, limits)
            is Value.FixpointFn -> applyFixpoint(id, app, fnValue, env, context, handlers, counters, limits)
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
        counters: EvalCounters,
        limits: EvaluationLimits,
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
            checkCapabilities(id, callable.lambda.effects, emptyMap(), context, limits)
            var callEnv = callable.env
            for ((paramId, value) in callable.lambda.parameters.zip(args)) {
                callEnv = callEnv + (paramId to value)
            }
            eval(callable.lambda.body, callEnv, context, handlers, counters, limits)
        }
        is Value.ForeignFn -> {
            checkCapabilities(id, callable.node.effects, emptyMap(), context, limits)
            try {
                foreignDispatcher?.dispatch(callable.node.target, args)?.let { return it }
            } catch (io: IoFailure) {
                throw InterpretException(translateIoFailure(id, io, limits))
            } catch (sv: SandboxViolation) {
                throw InterpretException(translateSandboxViolation(id, sv, limits))
            }
            val builtin = Builtins.lookup(callable.node.target)
                ?: throw InterpretException(
                    InterpretError.UnknownForeignTarget(at = id, target = callable.node.target)
                )
            try {
                builtin.invoke(hostContext, args)
            } catch (io: IoFailure) {
                throw InterpretException(translateIoFailure(id, io, limits))
            } catch (sv: SandboxViolation) {
                throw InterpretException(translateSandboxViolation(id, sv, limits))
            } catch (e: IllegalArgumentException) {
                throw InterpretException(InterpretError.BuiltinContractViolation(
                    at = id,
                    target = callable.node.target,
                    detail = e.message ?: "builtin contract violation",
                ))
            } catch (e: ClassCastException) {
                // Q-066: the declared foreignType is graph-supplied and is
                // not checked against the builtin's actual JVM contract, so
                // a hostile graph can hand a builtin type-confused argument
                // values. The cast failure is a contract violation, not an
                // implementation crash.
                throw InterpretException(InterpretError.BuiltinContractViolation(
                    at = id,
                    target = callable.node.target,
                    detail = e.message ?: "builtin argument type confusion",
                ))
            }
        }
        is Value.FixpointFn -> {
            val userArity = callable.bodyLambda.parameters.size - 1
            if (userArity != args.size) {
                throw InterpretException(InterpretError.ArityMismatch(
                    at = id, expected = userArity, actual = args.size
                ))
            }
            checkCapabilities(id, callable.bodyLambda.effects, emptyMap(), context, limits)
            var callEnv = callable.env + (callable.bodyLambda.parameters[0] to callable)
            for ((i, paramId) in callable.bodyLambda.parameters.drop(1).withIndex()) {
                callEnv = callEnv + (paramId to args[i])
            }
            eval(callable.bodyLambda.body, callEnv, context, handlers, counters, limits)
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
        counters: EvalCounters,
        limits: EvaluationLimits,
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
        val instances = evalEffectInstances(env, context, handlers, app, counters, limits)
        checkCapabilities(id, fn.bodyLambda.effects, instances, context, limits)
        val args = app.arguments.map { eval(it, env, context, handlers, counters, limits) }
        // Build the call env: capture-time env + (self → this FixpointFn) +
        // (each remaining parameter → corresponding argument).
        var callEnv = fn.env + (fn.bodyLambda.parameters[0] to fn)
        for ((i, paramId) in fn.bodyLambda.parameters.drop(1).withIndex()) {
            callEnv = callEnv + (paramId to args[i])
        }
        return eval(fn.bodyLambda.body, callEnv, context, handlers, counters, limits)
    }

    internal fun applyClosure(
        id: NodeId,
        app: Node.Application,
        fn: Value.Closure,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Value {
        if (fn.lambda.parameters.size != app.arguments.size) {
            throw InterpretException(InterpretError.ArityMismatch(
                at = id, expected = fn.lambda.parameters.size, actual = app.arguments.size
            ))
        }
        val instances = evalEffectInstances(env, context, handlers, app, counters, limits)
        checkCapabilities(id, fn.lambda.effects, instances, context, limits)
        val args = app.arguments.map { eval(it, env, context, handlers, counters, limits) }
        var callEnv = fn.env
        for ((paramId, value) in fn.lambda.parameters.zip(args)) {
            callEnv = callEnv + (paramId to value)
        }
        return eval(fn.lambda.body, callEnv, context, handlers, counters, limits)
    }

    private fun applyForeign(
        id: NodeId,
        app: Node.Application,
        fn: Value.ForeignFn,
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Value {
        // ForeignNodes don't carry parameter NodeIds the way Lambdas do —
        // their arity is determined by their foreignType. Verifier-level
        // arity checking happens via Application's type-check (the
        // function's FunctionType drives parameter count); we trust that
        // and dispatch.
        //
        // Q-039 reorders the historical evaluation: arguments are
        // evaluated FIRST, then the capability-check parameter map
        // (`instances`) is synthesized from the function's projections
        // plus those evaluated argument values. The proposal's load-
        // bearing invariant is that the value passed to
        // [checkCapabilities] for an ArgRef(j) source is the SAME
        // [Value] passed to the foreign code as argument j — moving
        // argument evaluation up the function eliminates the historical
        // gap by construction.
        //
        // ForeignNodes without projections continue under the legacy
        // path: [evalEffectInstances] evaluates the authored EffectDecls
        // and the capability check fires against those. The historical
        // gap from finding 1 of the 2026-05-26 audit persists for
        // unmigrated bindings, tracked under `security-index.md` until
        // every parameterized-effect ForeignNode declares projections.
        val args = app.arguments.map { eval(it, env, context, handlers, counters, limits) }
        val instances = if (fn.node.effectProjections.isNotEmpty()) {
            synthesizeProjectedInstances(env, context, handlers, fn.node, args, counters, limits)
        } else {
            evalEffectInstances(env, context, handlers, app, counters, limits)
        }
        checkCapabilities(id, fn.node.effects, instances, context, limits)
        try {
            foreignDispatcher?.dispatch(fn.node.target, args)?.let { return it }
        } catch (io: IoFailure) {
            throw InterpretException(translateIoFailure(id, io, limits))
        } catch (sv: SandboxViolation) {
            throw InterpretException(translateSandboxViolation(id, sv, limits))
        }
        // Higher-order lookup wins over standard lookup; the registries
        // are disjoint so this ordering is conservative.
        val higherOrder = Builtins.lookupHigherOrder(fn.node.target)
        if (higherOrder != null) {
            val apply = Builtins.ApplyFn { callable, callbackArgs ->
                applyValueToArgs(id, callable, callbackArgs, context, handlers, counters, limits)
            }
            return try {
                higherOrder.invoke(hostContext, args, apply)
            } catch (io: IoFailure) {
                throw InterpretException(translateIoFailure(id, io, limits))
            } catch (sv: SandboxViolation) {
                throw InterpretException(translateSandboxViolation(id, sv, limits))
            } catch (e: IllegalArgumentException) {
                throw InterpretException(InterpretError.BuiltinContractViolation(
                    at = id,
                    target = fn.node.target,
                    detail = e.message ?: "builtin contract violation",
                ))
            } catch (e: ClassCastException) {
                throw InterpretException(InterpretError.BuiltinContractViolation(
                    at = id,
                    target = fn.node.target,
                    detail = e.message ?: "builtin argument type confusion",
                ))
            }
        }
        val builtin = Builtins.lookup(fn.node.target)
            ?: throw InterpretException(
                InterpretError.UnknownForeignTarget(at = id, target = fn.node.target)
            )
        return try {
            builtin.invoke(hostContext, args)
        } catch (io: IoFailure) {
            // Translate runtime IoFailure (thrown by Layer 4 step 2
            // builtins on actual OS failures) into a structured
            // InterpretError carrying the call-site NodeId. Q-042
            // routes through [translateIoFailure] to honour the active
            // [ErrorVerbosity] in the threaded limits.
            throw InterpretException(translateIoFailure(id, io, limits))
        } catch (sv: SandboxViolation) {
            // Q-041: runtime SandboxViolation thrown by FsSandbox /
            // NetSandbox / Http scheme validation. Translated through
            // the verbosity-aware helper so detail is scrubbed when
            // policy demands.
            throw InterpretException(translateSandboxViolation(id, sv, limits))
        } catch (e: IllegalArgumentException) {
            // Builtin contract violation (e.g. division by zero from
            // Int.Div / Int.Mod / Math.Mod's require(b != 0L) guard).
            // The boundary catch intentionally covers every IAE escaping
            // any builtin — every such IAE is a contract failure.
            throw InterpretException(InterpretError.BuiltinContractViolation(
                at = id,
                target = fn.node.target,
                detail = e.message ?: "builtin contract violation",
            ))
        } catch (e: ClassCastException) {
            // Q-066: builtins cast their argument Values to the shapes the
            // real builtin contract expects, but the declared foreignType is
            // graph-supplied and nothing checks it against that contract —
            // a graph declaring Int.Add at (String, String) -> Int verifies
            // and hands the builtin StringV arguments. The cast failure is
            // a structured contract violation, not an implementation crash.
            throw InterpretException(InterpretError.BuiltinContractViolation(
                at = id,
                target = fn.node.target,
                detail = e.message ?: "builtin argument type confusion",
            ))
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
        counters: EvalCounters,
        limits: EvaluationLimits,
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
                eval(callable.lambda.body, callEnv, context, handlers, counters, limits)
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
                eval(callable.bodyLambda.body, callEnv, context, handlers, counters, limits)
            }
            is Value.ForeignFn -> {
                // Dispatch directly to the builtin registry. ForeignFn
                // callbacks (passing e.g. Bool.Not as a List.Map fn) are
                // rare but legitimate — and they don't recurse into the
                // higher-order machinery because Bool.Not is a standard Fn.
                try {
                    foreignDispatcher?.dispatch(callable.node.target, args)?.let { return it }
                } catch (io: IoFailure) {
                    throw InterpretException(translateIoFailure(id, io, limits))
                } catch (sv: SandboxViolation) {
                    throw InterpretException(translateSandboxViolation(id, sv, limits))
                }
                val builtin = Builtins.lookup(callable.node.target)
                    ?: throw InterpretException(
                        InterpretError.UnknownForeignTarget(at = id, target = callable.node.target)
                    )
                try {
                    builtin.invoke(hostContext, args)
                } catch (io: IoFailure) {
                    throw InterpretException(translateIoFailure(id, io, limits))
                } catch (sv: SandboxViolation) {
                    throw InterpretException(translateSandboxViolation(id, sv, limits))
                } catch (e: IllegalArgumentException) {
                    throw InterpretException(InterpretError.BuiltinContractViolation(
                        at = id,
                        target = callable.node.target,
                        detail = e.message ?: "builtin contract violation",
                    ))
                } catch (e: ClassCastException) {
                    throw InterpretException(InterpretError.BuiltinContractViolation(
                        at = id,
                        target = callable.node.target,
                        detail = e.message ?: "builtin argument type confusion",
                    ))
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
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Map<NodeId, List<Value>> {
        if (app.effectInstances.isEmpty()) return emptyMap()
        val out = LinkedHashMap<NodeId, List<Value>>(app.effectInstances.size)
        for (effectDeclId in app.effectInstances) {
            val effectDecl = store.get(effectDeclId) as Node.EffectDecl
            val paramValues = effectDecl.parameters.map { eval(it, env, context, handlers, counters, limits) }
            out[effectDecl.effectType] = paramValues
        }
        return out
    }

    /**
     * Q-039: synthesize the capability-check `instances` map from a
     * projected [Node.ForeignNode]'s `effectProjections` plus the
     * already-evaluated `argumentValues`. Called from [applyForeign]
     * when the callee's projection list is non-empty.
     *
     * The key invariant for security: for every [ProjectionSource.ArgRef]
     * source the value placed into `instances` is `argumentValues[index]`
     * — the exact [Value] the foreign code will receive — so no drift
     * is possible between the capability check and the foreign-call
     * argument. [ProjectionSource.LiteralNode] sources evaluate the
     * binding-controlled literal node under the same env/context as the
     * call site; literal-like nodes have no side effects so this is
     * safe under any context.
     *
     * Verifier-side admission has already confirmed projection arity
     * (length equals `effects.size`), source arity (length equals each
     * category's parameter count), ArgRef-index validity, and
     * LiteralNode target literal-ness and type. This synthesis trusts
     * those invariants.
     */
    private fun synthesizeProjectedInstances(
        env: Map<NodeId, Value>,
        context: CapabilitySet,
        handlers: List<ActiveHandler>,
        foreignNode: Node.ForeignNode,
        argumentValues: List<Value>,
        counters: EvalCounters,
        limits: EvaluationLimits,
    ): Map<NodeId, List<Value>> {
        val out = LinkedHashMap<NodeId, List<Value>>(foreignNode.effectProjections.size)
        for (projection in foreignNode.effectProjections) {
            val paramValues = projection.sources.map { source ->
                when (source) {
                    is org.strand.core.ProjectionSource.ArgRef -> argumentValues[source.index]
                    is org.strand.core.ProjectionSource.LiteralNode ->
                        eval(source.target, env, context, handlers, counters, limits)
                }
            }
            out[projection.category] = paramValues
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
        limits: EvaluationLimits,
    ) {
        // First pass: surface every category that is entirely absent in
        // one error. Mirrors the pre-Q-031 CapabilityViolation shape so
        // existing tests that inspect `missing` keep working.
        val missing = declared.toSet().filter { it !in context.grants }.toSet()
        if (missing.isNotEmpty()) {
            // Q-064: assemble the denial report from values already in
            // scope. Category names in the callee's declaration order;
            // requested parameters are whatever EffectDecls the call site
            // supplied for the missing categories; held is empty — the
            // category is absent, the context holds nothing for it.
            val missingInOrder = declared.filter { it in missing }.distinct()
            val requestedValues = missingInOrder.flatMap { instances[it].orEmpty() }
            throw InterpretException(InterpretError.CapabilityViolation(
                at = at,
                missing = missing,
                report = buildDenialReport(
                    at = at,
                    categoryName = missingInOrder.joinToString(", ") { categoryNameOf(it) },
                    requested = requestedValues,
                    held = emptyList(),
                    heldCategoryName = "",
                    limits = limits,
                ),
            ))
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
                val name = categoryNameOf(category)
                throw InterpretException(InterpretError.RefinementViolation(
                    at = at,
                    category = category,
                    requirement = requirement,
                    available = grants,
                    report = buildDenialReport(
                        at = at,
                        categoryName = name,
                        requested = requirement,
                        held = grants,
                        heldCategoryName = name,
                        limits = limits,
                    ),
                ))
            }
        }
    }

    /** The EffectCategory's declared name, falling back to the `#N` NodeId rendering. */
    private fun categoryNameOf(id: NodeId): String =
        (store.getOrNull(id) as? Node.EffectCategory)?.categoryName ?: id.toString()

    /**
     * Q-064: construct the [DenialReport] at the denial site. Parameter
     * values are rendered and scrubbed by [DenialReport.renderParameter];
     * under [org.strand.core.ErrorVerbosity.RedactedWithKindOnly] both the
     * requested and held value lists are withheld entirely (the report
     * carries category and node only). The phase is [DenialPhase.Invariant]
     * when the denial fired inside an invariant-body evaluation (the
     * [inInvariant] guard is set), [DenialPhase.Expression] otherwise; the
     * state-machine runtime re-tags transition / group-start denials at
     * halt translation. The `NodeId(-1)` sentinel used by [applyCallable]'s
     * runtime boundary carries no graph site and reports a null node.
     */
    private fun buildDenialReport(
        at: NodeId,
        categoryName: String,
        requested: List<Value>,
        held: List<CapabilityPattern>,
        heldCategoryName: String,
        limits: EvaluationLimits,
    ): DenialReport {
        val kindOnly =
            limits.errorVerbosity == org.strand.core.ErrorVerbosity.RedactedWithKindOnly
        return DenialReport(
            category = categoryName,
            requested = if (kindOnly) null else requested.map { DenialReport.renderParameter(it) },
            held = if (kindOnly) null else held.map { DenialReport.renderGrant(heldCategoryName, it) },
            node = at.takeIf { it.value != -1 },
            instanceId = null,
            eventIndex = null,
            phase = if (inInvariant) DenialPhase.Invariant else DenialPhase.Expression,
        )
    }
}
