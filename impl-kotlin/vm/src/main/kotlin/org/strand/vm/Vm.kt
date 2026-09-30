package org.strand.vm

import org.strand.bytecode.Chunk
import org.strand.bytecode.ChunkTable
import org.strand.bytecode.Constant
import org.strand.bytecode.Opcode
import org.strand.core.EvaluationLimits
import org.strand.core.ExhaustionKind
import org.strand.core.NodeId
import org.strand.interpreter.AuditOutcome
import org.strand.interpreter.AuditRecord
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.covers
import org.strand.interpreter.DenialPhase
import org.strand.interpreter.DenialReport
import org.strand.interpreter.HostContext
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.IoFailure
import org.strand.interpreter.SandboxViolation
import org.strand.interpreter.Value
import org.strand.interpreter.invoke
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Strand bytecode VM (Q-017 step 1 § 4 — Kotlin reference VM).
 *
 * Stack-based dispatch loop over a [ChunkTable]. Step 1 ships the Layer
 * 1 opcode subset: literal pushes, local / capture access, calls, closures,
 * and returns. Layers 3 / 4 / 5 / 6 opcodes (CAP_PUSH, CALL_FOREIGN,
 * MATCH_DISPATCH, MAKE_FIXPOINT, ...) are wired into the dispatch loop
 * as those layers reach the VM.
 *
 * Value representation reuses [org.strand.interpreter.Value] so existing
 * corpus tests can compare interpreter vs VM results with the same equality.
 * The VM never re-runs verification — it trusts its input has been
 * verified by the same verifier the interpreter trusts.
 *
 * Invocation: `Vm(table).run()` returns the top-of-stack value when the
 * root chunk hits `HALT`. The root chunk is at index 0 by convention.
 */
class Vm(
    private val table: ChunkTable,
    /**
     * Q-054 follow-up: the host policy this VM evaluates under, projected to a
     * [HostContext]. Threaded into every foreign-builtin dispatch so effectful
     * builtins read their clock / random / sandbox / credentials / etc. from
     * the context rather than the [Builtins] process-global singletons. Default
     * [HostContext.processDefault] reads the current singletons, so every
     * `Vm(table)` construction (the equivalence tests, the runtime's VM
     * dispatcher) behaves exactly as before.
     */
    private val hostContext: HostContext = HostContext.processDefault(),
) {

    // Layer 3 runtime state: capability stack + active handler list.
    // CAP_PUSH stashes the current caps onto [capStack] and replaces
    // [currentCaps] with the narrowed set; CAP_POP restores from the
    // stack. HANDLER_PUSH appends to [handlers]; HANDLER_POP removes
    // the last entry. Both lists are shared across all frames — caps
    // and handlers are lexical-scope concerns within the program, not
    // per-frame call state.
    //
    // Review H2: the capability context is a full refinement-bearing
    // [CapabilitySet] (not a set of category ids), so CALL performs the
    // interpreter's two-pass check — category presence, then the Q-031
    // refinement match against the call site's EffectDecl parameters or the
    // callee's Q-039 projections.
    private var currentCaps: CapabilitySet = CapabilitySet.EMPTY
    private val capStack: ArrayDeque<CapabilitySet> = ArrayDeque()
    private val handlers: MutableList<VmActiveHandler> = mutableListOf()

    // Error recovery — N-047 Attempt (Q-048). Each ATTEMPT_PUSH records a
    // marker; on a catchable failure the unwinder truncates `frames` and the
    // marker frame's operand stack to the recorded depths, restores the
    // capability/handler depths and saved caps, pushes the ErrorPayload
    // ProductV, and resumes at the recorded err-label pc.
    private val attemptStack: ArrayDeque<AttemptMarker> = ArrayDeque()

    // Q-040 per-evaluation budget. Fields rather than dispatch-loop locals
    // because a higher-order builtin runs each callback in a nested dispatch
    // loop ([applyNested]) that must spend the same budget: [outerDepth] is
    // the frame count of the loops a nested run is suspended under, so the
    // stack-depth cap sees the whole call chain. [resetBudget] runs at each
    // public entry point.
    private var steps = 0L
    private var allocated = 0L
    private var startNanos = 0L
    private var outerDepth = 0

    /**
     * Q-047: set while an invariant body is being evaluated for
     * `CHECK_SCHEMA`, so obligation sites inside the predicate do not fire
     * (the interpreter's `inInvariant` guard), and so a denial inside the
     * predicate reports [DenialPhase.Invariant].
     */
    private var inInvariant = false

    private fun resetBudget() {
        steps = 0L
        allocated = 0L
        startNanos = System.nanoTime()
        outerDepth = 0
    }

    /**
     * Execute the bytecode and return the top-of-stack value at HALT.
     * The HALT instruction's top-of-stack is required to be a [Value]
     * (no in-flight VmClosure can be returned to the caller).
     *
     * [initialCaps] grants the listed EffectCategory NodeId .values at
     * program entry. Defaults to empty (pure-only execution; any
     * effectful call without a matching active handler throws
     * [InterpretException] carrying [InterpretError.CapabilityViolation],
     * matching the interpreter's `Interpreter.eval` default).
     */
    fun run(initialCaps: Set<Int> = emptySet()): Value =
        run(initialCaps, EvaluationLimits.DEFAULTS)

    /**
     * Review H2: run under a refinement-bearing [capabilities] set — the
     * same [CapabilitySet] the interpreter's `eval` takes. A category-id
     * set (the [run] overloads taking `Set<Int>`) is the wildcard grant
     * [CapabilitySet.ofCategories] of those categories.
     */
    fun run(capabilities: CapabilitySet, limits: EvaluationLimits = EvaluationLimits.DEFAULTS): Value {
        val raw = evaluate(capabilities, limits)
        return raw as? Value
            ?: error("HALT expected a Value at top of stack, got ${raw::class.simpleName}")
    }

    /**
     * Q-040: VM run under explicit [limits]. Breaches surface as
     * [InterpretError.ResourceExhaustion] (with `atNode = null` —
     * opcodes do not carry NodeIds in slice 1).
     */
    fun run(initialCaps: Set<Int>, limits: EvaluationLimits): Value =
        run(categoryGrant(initialCaps), limits)

    /**
     * Run the root chunk and return whatever's at the top of the stack at
     * HALT — including non-Value callables (VmClosure, VmFixpoint, VmForeign).
     * Used by [applyClosure]'s setup phase: callers that want to invoke
     * a closure multiple times pre-evaluate it once via [evaluate] and
     * then apply it per call.
     *
     * The return type is [Any] because the top-of-stack at HALT may be
     * any of the VM's runtime representations; callers cast appropriately.
     */
    fun evaluate(initialCaps: Set<Int> = emptySet()): Any =
        evaluate(initialCaps, EvaluationLimits.DEFAULTS)

    /**
     * Q-040: evaluate under explicit [limits]. Allocates a fresh
     * [VmCounters]; same shape as [Interpreter]'s `EvalCounters` but
     * uses `frames.size` for stack-depth accounting.
     */
    fun evaluate(initialCaps: Set<Int>, limits: EvaluationLimits): Any =
        evaluate(categoryGrant(initialCaps), limits)

    /** Review H2: evaluate under a refinement-bearing [capabilities] set. */
    fun evaluate(capabilities: CapabilitySet, limits: EvaluationLimits = EvaluationLimits.DEFAULTS): Any {
        currentCaps = capabilities
        capStack.clear()
        handlers.clear()
        attemptStack.clear()
        resetBudget()
        val frame = Frame(chunk = table.root, captures = emptyArray())
        val frames = ArrayDeque<Frame>()
        frames.addLast(frame)
        return runLoopMapped(frames, limits)
    }

    /**
     * Apply a previously-evaluated [closure] (typically VmClosure or
     * VmFixpoint from [evaluate]) to [args] under [caps]. Returns the
     * result Value. Used by `:runtime`'s MachineActor to dispatch
     * transition functions through the VM (Layer 6 integration) and by
     * `:schema`'s SchemaChecker to evaluate invariant bodies through the
     * VM (Layer 7 integration).
     *
     * The closure's declared effects are checked category-only against
     * [caps] before the body runs (the interpreter's `applyCallable` rule);
     * [caps] is the wildcard grant of the listed categories and is the
     * capability context for the apply duration.
     */
    fun applyClosure(closure: Any, args: List<Value>, caps: Set<Int>): Value =
        applyClosure(closure, args, caps, EvaluationLimits.DEFAULTS)

    /**
     * Q-040: applyClosure under explicit [limits].
     */
    fun applyClosure(closure: Any, args: List<Value>, caps: Set<Int>, limits: EvaluationLimits): Value =
        applyClosure(closure, args, categoryGrant(caps), limits)

    /**
     * Review H2: applyClosure under a refinement-bearing [capabilities] set.
     * Mirrors the interpreter's `applyCallable`: the callable's own declared
     * effects are checked against [capabilities] before the body runs (there
     * is no call-site EffectDecl at this boundary — see
     * [checkAppliedCallable]), and the refinement checks fire at the
     * effectful call sites inside the body.
     */
    fun applyClosure(
        closure: Any,
        args: List<Value>,
        capabilities: CapabilitySet,
        limits: EvaluationLimits = EvaluationLimits.DEFAULTS,
    ): Value {
        currentCaps = capabilities
        val closure = unbox(closure)
        // The capability and handler stacks are shared across calls on this
        // Vm. Review (Low): record their depths and truncate back in
        // `finally`, so an exception escaping mid-body (inside a
        // CapabilityScope or Handler) cannot leave a stale scope or handler
        // active for the next applyClosure on the same Vm.
        val savedCapDepth = capStack.size
        val savedHandlerDepth = handlers.size
        //
        // The attempt-marker stack, by contrast, IS isolated per applyClosure
        // run: markers record frame depths into the fresh [frames] deque this
        // call builds, so a marker from an outer evaluation must not be
        // consulted here. Snapshot and restore around the run.
        val savedAttempts = ArrayDeque(attemptStack)
        attemptStack.clear()
        resetBudget()
        val frames = ArrayDeque<Frame>()
        try {
            try {
                checkAppliedCallable(RUNTIME_BOUNDARY, closure, args, depth = 0, limits = limits)
            } catch (e: VmResourceExhaustion) {
                // A projected literal's schema check runs a predicate here,
                // outside any dispatch loop.
                throw exhaustion(e)
            }
            when (closure) {
                is VmClosure -> {
                    val sub = table[closure.chunkIndex]
                    val frame = Frame(chunk = sub, captures = closure.captures)
                    for ((i, arg) in args.withIndex()) frame.locals[i] = arg
                    frames.addLast(frame)
                }
                is VmFixpoint -> {
                    val sub = table[closure.chunkIndex]
                    val frame = Frame(chunk = sub, captures = closure.captures)
                    frame.locals[0] = closure  // recursive-self slot
                    for ((i, arg) in args.withIndex()) frame.locals[i + 1] = arg
                    frames.addLast(frame)
                }
                is VmForeign -> {
                    // Dispatch directly via Builtins; no frame setup. There
                    // is no dispatch loop around this call, so the failures
                    // the loop would translate are translated here.
                    return try {
                        dispatchForeign(null, closure, args, depth = 0, limits = limits)
                    } catch (e: VmResourceExhaustion) {
                        throw exhaustion(e)
                    } catch (io: IoFailure) {
                        throw InterpretException(translateVmIoFailure(io, limits))
                    } catch (sv: SandboxViolation) {
                        throw InterpretException(translateVmSandboxViolation(sv, limits))
                    }
                }
                else -> error("applyClosure: $closure is not callable (got ${closure::class.simpleName})")
            }
            val raw = runLoopMapped(frames, limits)
            return raw as? Value
                ?: error("applyClosure: callable returned ${raw::class.simpleName}, expected a Value")
        } finally {
            attemptStack.clear()
            attemptStack.addAll(savedAttempts)
            while (capStack.size > savedCapDepth) capStack.removeLast()
            while (handlers.size > savedHandlerDepth) handlers.removeLast()
        }
    }

    /**
     * Q-040: [runLoop] wrapper that maps the VM-internal
     * [VmResourceExhaustion] to the shared
     * [InterpretError.ResourceExhaustion] shape so host callers do not
     * have to catch two distinct exception types. The mapping uses
     * `atNode = null` because slice 1 of the bytecode VM does not
     * carry source NodeIds on opcodes (Q-017 step 2 source-mapping is
     * a follow-up).
     */
    private fun runLoopMapped(frames: ArrayDeque<Frame>, limits: EvaluationLimits): Any =
        try {
            runLoop(frames, limits)
        } catch (e: VmResourceExhaustion) {
            throw exhaustion(e)
        }

    private fun exhaustion(e: VmResourceExhaustion): InterpretException =
        InterpretException(InterpretError.ResourceExhaustion(
            at = e.atNode,
            kind = e.kind,
            current = e.current,
            limit = e.limit,
        ))

    /**
     * The dispatch loop, extracted so [run], [evaluate], and [applyClosure]
     * can share it. Pops/processes opcodes until the frame stack is
     * empty (RET to the bottom) or HALT fires. Returns the top-of-stack
     * value at termination (may be Value or VmClosure or other).
     */
    private fun runLoop(frames: ArrayDeque<Frame>, limits: EvaluationLimits): Any {
        // Q-040 per-evaluation counters ([steps], [allocated], [startNanos])
        // are fields shared with nested callback loops. Stack depth is
        // [outerDepth] plus frames.size at each step; wall clock samples
        // System.nanoTime() every [EvaluationLimits.wallClockSampleEvery]
        // steps from the anchor.
        // Allocation guard. Called from every Value-construction site
        // (PUSH_*, MAKE_FOREIGN, PRODUCT_NEW, SUM_NEW, EQ, SUM_CASE_IS,
        // SUM_PAYLOAD, MAKE_CLOSURE, MAKE_FIXPOINT). Increment-then-
        // compare so the error reports the actual count past the cap.
        fun bumpAllocation() {
            allocated++
            if (allocated > limits.maxAllocatedValues) {
                throw VmResourceExhaustion(
                    kind = ExhaustionKind.AllocatedValues,
                    atNode = null,
                    current = allocated,
                    limit = limits.maxAllocatedValues,
                )
            }
        }
        while (true) {
            // Per-step guards. Increment-then-compare matches the
            // interpreter's contract for the error count value.
            steps++
            if (steps > limits.maxSteps) {
                throw VmResourceExhaustion(
                    kind = ExhaustionKind.Steps,
                    atNode = null,
                    current = steps,
                    limit = limits.maxSteps,
                )
            }
            val depth = outerDepth + frames.size
            if (depth > limits.maxStackDepth) {
                throw VmResourceExhaustion(
                    kind = ExhaustionKind.StackDepth,
                    atNode = null,
                    current = depth.toLong(),
                    limit = limits.maxStackDepth.toLong(),
                )
            }
            if (limits.wallClockSampleEvery > 0 &&
                steps % limits.wallClockSampleEvery == 0L) {
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
                if (elapsedMs > limits.wallClockBudgetMillis) {
                    throw VmResourceExhaustion(
                        kind = ExhaustionKind.WallClock,
                        atNode = null,
                        current = elapsedMs,
                        limit = limits.wallClockBudgetMillis,
                    )
                }
            }
            val current = frames.last()
            val op = Opcode.fromByte(current.code[current.pc])
            current.pc++
            try {
            when (op) {
                Opcode.POP -> current.stack.removeLast()
                Opcode.DUP -> current.stack.add(current.stack.last())

                Opcode.PUSH_INT -> {
                    val c = current.constant() as Constant.IntC
                    bumpAllocation()
                    current.stack.add(Value.IntV(c.value))
                }
                Opcode.PUSH_FLOAT -> {
                    val c = current.constant() as Constant.FloatC
                    bumpAllocation()
                    current.stack.add(Value.FloatV(c.value))
                }
                Opcode.PUSH_STRING -> {
                    val c = current.constant() as Constant.StringC
                    bumpAllocation()
                    current.stack.add(Value.StringV(c.value))
                }
                Opcode.PUSH_BOOL -> {
                    val c = current.constant() as Constant.BoolC
                    bumpAllocation()
                    current.stack.add(Value.BoolV(c.value))
                }
                Opcode.PUSH_UNIT -> current.stack.add(Value.UnitV)
                Opcode.PUSH_BYTES -> {
                    val c = current.constant() as Constant.BytesC
                    bumpAllocation()
                    current.stack.add(Value.BytesV(c.value))
                }

                Opcode.LOAD_LOCAL -> {
                    val slot = current.operand()
                    current.stack.add(current.locals[slot]
                        ?: error("LOAD_LOCAL: slot $slot not set"))
                }
                Opcode.LOAD_CAPTURE -> {
                    val slot = current.operand()
                    current.stack.add(current.captures[slot])
                }
                Opcode.STORE_LOCAL -> {
                    val slot = current.operand()
                    // STORE_LOCAL accepts both Value (literals, computed
                    // results) and VmClosure (let-bound lambdas — higher-
                    // order patterns store the callable into a local then
                    // load it back).
                    current.locals[slot] = current.stack.removeLast()
                }
                Opcode.LOAD_HASH -> {
                    val c = current.constant() as Constant.ChunkRefC
                    // LOAD_HASH evaluates a closed subgraph: we run the
                    // sub-chunk as a fresh frame, then push its result.
                    runSubChunk(c.chunkIndex, frames)
                }

                Opcode.MAKE_FOREIGN -> {
                    val targetC = current.constant() as Constant.ForeignTargetC
                    val effectsC = current.constant() as Constant.EffectsC
                    val projectionsC = current.constant() as Constant.ProjectionsC
                    bumpAllocation()
                    current.stack.add(VmForeign(targetC.target, effectsC.effectIds, projectionsC.projections))
                }

                Opcode.CAP_PUSH -> {
                    val effectsC = current.constant() as Constant.EffectsC
                    capStack.addLast(currentCaps)
                    // Narrow: keep only the listed categories, carrying their
                    // refinement patterns through verbatim (the interpreter's
                    // CapabilitySet.intersect).
                    currentCaps = currentCaps.intersect(effectsC.effectIds.mapTo(HashSet()) { NodeId(it) })
                }
                Opcode.CAP_POP -> {
                    currentCaps = capStack.removeLast()
                }
                Opcode.HANDLER_PUSH -> {
                    val interceptC = current.constant() as Constant.IntC
                    val handlerNode = current.operand()
                    val handlerValue = current.stack.removeLast()
                    handlers += VmActiveHandler(interceptC.value.toInt(), handlerValue, handlerNode)
                }
                Opcode.HANDLER_POP -> {
                    handlers.removeLast()
                }

                Opcode.ATTEMPT_PUSH -> {
                    // N-047 (Q-048). The operand is a JUMP-style relative
                    // offset to the err-label; after reading it, current.pc
                    // points at the first body instruction, so the absolute
                    // err-label pc is `current.pc + offset`. Record the
                    // current depths + caps so the unwinder can restore them.
                    val offset = current.operand()
                    attemptStack.addLast(AttemptMarker(
                        frameDepth = frames.size,
                        stackDepth = current.stack.size,
                        capStackDepth = capStack.size,
                        handlerDepth = handlers.size,
                        savedCaps = currentCaps,
                        errPc = current.pc + offset,
                    ))
                }
                Opcode.ATTEMPT_POP -> {
                    // Success path: the body produced a value with no
                    // catchable failure; drop the marker. The Ok SUM_NEW
                    // follows in the bytecode stream.
                    attemptStack.removeLast()
                }

                Opcode.PRODUCT_NEW -> {
                    // Layer 5 step 3a: pop N values, build ProductV with
                    // the field names from the constant.
                    val c = current.constant() as Constant.ProductFieldsC
                    val count = current.operand()
                    val startIdx = current.stack.size - count
                    val fields = LinkedHashMap<String, Value>(count)
                    for (i in 0 until count) {
                        fields[c.names[i]] = box(current.stack[startIdx + i])
                    }
                    repeat(count) { current.stack.removeLast() }
                    bumpAllocation()
                    current.stack.add(Value.ProductV(fields))
                }

                Opcode.PRODUCT_GET -> {
                    // Layer 5 step 3a: pop ProductV, push the named field.
                    val c = current.constant() as Constant.StringC
                    val product = current.stack.removeLast() as Value.ProductV
                    val field = product.fields[c.value]
                        ?: error("PRODUCT_GET: field '${c.value}' not present in ${product.fields.keys}")
                    current.stack.add(unbox(field))
                }

                Opcode.SUM_NEW -> {
                    // Layer 5 step 3b: build a SumV. Pop payload if the
                    // case has one; otherwise emit a nullary SumV.
                    val c = current.constant() as Constant.SumCaseC
                    val payload = if (c.hasPayload) box(current.stack.removeLast()) else null
                    bumpAllocation()
                    current.stack.add(Value.SumV(c.caseName, payload))
                }

                Opcode.EQ -> {
                    val rhs = current.stack.removeLast()
                    val lhs = current.stack.removeLast()
                    bumpAllocation()
                    current.stack.add(Value.BoolV(lhs == rhs))
                }

                Opcode.SUM_CASE_IS -> {
                    val c = current.constant() as Constant.StringC
                    val sum = current.stack.removeLast() as? Value.SumV
                        ?: error("SUM_CASE_IS: top of stack is not a SumV")
                    bumpAllocation()
                    current.stack.add(Value.BoolV(sum.case == c.value))
                }

                Opcode.SUM_PAYLOAD -> {
                    val sum = current.stack.removeLast() as? Value.SumV
                        ?: error("SUM_PAYLOAD: top of stack is not a SumV")
                    current.stack.add(sum.payload?.let { unbox(it) } ?: Value.UnitV)
                }

                Opcode.THROW_NO_MATCH -> {
                    // Review M5: the interpreter's typed, uncatchable error at
                    // the Match NodeId the lowerer recorded as the operand.
                    throw InterpretException(InterpretError.NoMatchingCase(at = NodeId(current.operand())))
                }

                Opcode.CHECK_SCHEMA -> {
                    // Q-047: the value the node just produced stays on the
                    // stack; a failing invariant raises the catchable
                    // SchemaInvariantViolation, which the per-opcode catch
                    // below unwinds to an enclosing Attempt as the
                    // interpreter's does.
                    val check = current.constant() as Constant.SchemaCheckC
                    checkSchema(check, current.stack.last(), frames.size, limits)
                }

                Opcode.JUMP -> {
                    val offset = current.operand()
                    current.pc += offset
                }

                Opcode.JUMP_IF_FALSE -> {
                    val offset = current.operand()
                    val test = current.stack.removeLast() as? Value.BoolV
                        ?: error("JUMP_IF_FALSE: top of stack is not a BoolV")
                    if (!test.v) current.pc += offset
                }

                Opcode.MAKE_FIXPOINT -> {
                    // Same shape as MAKE_CLOSURE but produces a VmFixpoint.
                    val chunkIdx = (current.constant() as Constant.ChunkRefC).chunkIndex
                    val captureCount = current.operand()
                    val effectsC = current.constant() as Constant.EffectsC
                    val captureArray = Array<Any>(captureCount) { Value.UnitV }
                    val startIdx = current.stack.size - captureCount
                    for (i in 0 until captureCount) {
                        captureArray[i] = current.stack[startIdx + i]
                    }
                    repeat(captureCount) { current.stack.removeLast() }
                    bumpAllocation()
                    current.stack.add(VmFixpoint(chunkIdx, captureArray, effectsC.effectIds))
                }

                Opcode.MAKE_CLOSURE -> {
                    val chunkIdx = (current.constant() as Constant.ChunkRefC).chunkIndex
                    val captureCount = current.operand()
                    val effectsC = current.constant() as Constant.EffectsC
                    // Pop captures in DECLARATION order. Captures may
                    // include VmClosure values (higher-order patterns
                    // where a captured binder points at a lambda), so
                    // the array is Any-typed.
                    val captureArray = Array<Any>(captureCount) { Value.UnitV }
                    val startIdx = current.stack.size - captureCount
                    for (i in 0 until captureCount) {
                        captureArray[i] = current.stack[startIdx + i]
                    }
                    repeat(captureCount) { current.stack.removeLast() }
                    bumpAllocation()
                    current.stack.add(VmClosure(chunkIdx, captureArray, effectsC.effectIds))
                }

                Opcode.CALL -> {
                    val arity = current.operand()
                    val site = current.constant() as Constant.CallSiteC
                    // EffectDecl parameter values sit above the arguments.
                    val instanceParams = ArrayList<Value>(site.totalParams)
                    val paramStart = current.stack.size - site.totalParams
                    for (i in 0 until site.totalParams) instanceParams += current.stack[paramStart + i] as Value
                    repeat(site.totalParams) { current.stack.removeLast() }
                    val args = Array<Any>(arity) { Value.UnitV }
                    val argStart = current.stack.size - arity
                    for (i in 0 until arity) {
                        args[i] = current.stack[argStart + i]
                    }
                    repeat(arity) { current.stack.removeLast() }
                    val fn = unbox(current.stack.removeLast())
                    val effects: IntArray = when (fn) {
                        is VmClosure -> fn.effects
                        is VmForeign -> fn.effects
                        is VmFixpoint -> fn.effects
                        else -> throw InterpretException(InterpretError.NotCallable(
                            at = NodeId(site.site), gotKind = fn::class.simpleName ?: "Value",
                        ))
                    }
                    // Layer 3 — active handler check: innermost handler
                    // whose intercept matches any of the callee's effects
                    // wins. Dispatch to it instead of the callee; the
                    // handler stands in for the entire effectful call.
                    val intercept = findInterceptingHandler(effects)
                    if (intercept != null) {
                        // Replace the callee with the handler's stored
                        // value; we already have `args` ready. The
                        // handler's OWN declared effects fire in the
                        // surrounding context with no EffectDecls reaching
                        // it — the interpreter's applyValue rule (review H2),
                        // including a foreign handler's performing check.
                        // The interpreter's verified-interception guard.
                        val verified = hostContext.verifiedInterceptions
                        if (verified != null && NodeId(intercept.node) !in verified[NodeId(site.site)].orEmpty()) {
                            throw InterpretException(InterpretError.UnverifiedInterception(
                                at = NodeId(site.site),
                                handler = NodeId(intercept.node),
                                category = NodeId(intercept.intercept),
                            ))
                        }
                        val handler = unbox(intercept.handlerValue)
                        checkAppliedCallable(NodeId(site.site), handler, args.asList(), frames.size, limits)
                        invokeCallable(NodeId(site.site), handler, args, frames, current, limits)
                        continue
                    }
                    // Capability check (review H2): the interpreter's
                    // two-pass rule — every declared category present, then
                    // each category the site instantiates (EffectDecls, or
                    // the callee's Q-039 projections synthesized from the
                    // evaluated arguments) covered by a granted pattern; a
                    // foreign callee is a performing site, so an
                    // instance-free parameterized category needs an
                    // unrefined grant. Denials raise the shared, uncatchable
                    // CapabilityViolation / RefinementViolation, so the
                    // per-opcode InterpretException catch declines to unwind
                    // them to any attempt marker. A foreign callee's
                    // effect floor is re-checked first, as the interpreter's
                    // dispatchForeign does.
                    if (fn is VmForeign) checkForeignFloor(NodeId(site.site), fn)
                    val instances = if (fn is VmForeign && fn.projections.isNotEmpty()) {
                        synthesizeProjectedInstances(fn, args.asList(), frames.size, limits)
                    } else {
                        siteInstances(site, instanceParams)
                    }
                    val resource = if (fn is VmForeign) resourceInstances(fn, args.asList()) else emptyMap()
                    checkCapabilities(
                        NodeId(site.site), effects.map { NodeId(it) }, instances + resource, limits,
                        performs = fn is VmForeign && performs(fn), registryBound = resource.keys,
                    )
                    invokeCallable(NodeId(site.site), fn, args, frames, current, limits)
                }

                Opcode.RET -> {
                    val result = current.stack.removeLast()
                    frames.removeLast()
                    if (frames.isEmpty()) {
                        // Empty frame stack — return whatever's on top.
                        // [run] checks Value-ness; [evaluate] returns Any.
                        return result
                    }
                    frames.last().stack.add(result)
                }

                Opcode.HALT -> {
                    // Same: return raw top-of-stack; the public entry
                    // point ([run] vs [evaluate]) decides whether to
                    // enforce Value-ness.
                    return current.stack.removeLast()
                }

                // Step-1 unimplemented opcodes — the lowerer doesn't emit
                // them yet. CALL_FIXPOINT and CALL_FOREIGN are reserved
                // for explicit-dispatch variants the current Lowerer
                // doesn't need (the polymorphic CALL site handles both).
                // MATCH_DISPATCH was an early design that the JUMP-based
                // Match lowering supersedes.
                Opcode.CALL_FIXPOINT, Opcode.CALL_FOREIGN, Opcode.MATCH_DISPATCH -> {
                    throw VmOpcodeNotImplemented(op)
                }
            }
            } catch (io: IoFailure) {
                // Parity with the interpreter's translateIoFailure: a raw
                // IoFailure escaping a builtin is translated to the structured
                // InterpretError.IoFailure (honouring the verbosity gate) and
                // then handled by the attempt-unwind path. Closes the
                // pre-existing VM/interpreter parity gap (proposals/
                // error-recovery.md § 6.3).
                val translated = InterpretException(translateVmIoFailure(io, limits))
                if (!unwindToAttempt(frames, translated.error)) throw translated
            } catch (sv: SandboxViolation) {
                // A raw SandboxViolation is likewise translated for parity, but
                // it is UNCATCHABLE — translate to its terminal InterpretError
                // form and rethrow. The marker stack is never consulted.
                throw InterpretException(translateVmSandboxViolation(sv, limits))
            } catch (ie: InterpretException) {
                // An already-structured InterpretException (e.g. a future
                // catchable variant). Unwind only if catchable AND a marker is
                // available; otherwise propagate. The uncatchable filter is the
                // error's own `isCatchable` property.
                if (!unwindToAttempt(frames, ie.error)) throw ie
            }
        }
    }

    /**
     * N-047 (Q-048) unwind. If [error] is catchable and an attempt marker is
     * available, truncate [frames] and the marker frame's operand stack to the
     * recorded depths, restore the capability/handler stacks and saved caps,
     * push the `{kind, detail}` ErrorPayload ProductV, and resume at the
     * recorded err-label pc. Returns true when the unwind was performed; false
     * when [error] is uncatchable or no marker is active (the caller rethrows).
     */
    private fun unwindToAttempt(frames: ArrayDeque<Frame>, error: InterpretError): Boolean {
        if (!error.isCatchable) return false
        if (attemptStack.isEmpty()) return false
        val marker = attemptStack.removeLast()
        // Truncate the frame stack back to the marker's frame.
        while (frames.size > marker.frameDepth) frames.removeLast()
        val markerFrame = frames.last()
        // Truncate the marker frame's operand stack to the recorded depth.
        while (markerFrame.stack.size > marker.stackDepth) markerFrame.stack.removeLast()
        // Restore capability + handler scopes.
        while (capStack.size > marker.capStackDepth) capStack.removeLast()
        currentCaps = marker.savedCaps
        while (handlers.size > marker.handlerDepth) handlers.removeLast()
        // Push the ErrorPayload {kind, detail}; the err-label's SUM_NEW wraps
        // it as Err(payload).
        markerFrame.stack.add(buildErrorPayload(error))
        // Resume at the err-label.
        markerFrame.pc = marker.errPc
        return true
    }

    /**
     * Build the `{kind: String, detail: String}` ErrorPayload product for a
     * caught error. Field order matches the verifier's synthesized payload and
     * the interpreter's `errorPayload` builder.
     */
    private fun buildErrorPayload(error: InterpretError): Value.ProductV {
        val (kind, detail) = when (error) {
            is InterpretError.IoFailure -> error.kind to error.detail
            is InterpretError.SchemaInvariantViolation -> "schema-invariant" to error.valueDescription
            else -> error("buildErrorPayload called on uncatchable error $error")
        }
        return Value.ProductV(linkedMapOf(
            "kind" to Value.StringV(kind),
            "detail" to Value.StringV(detail),
        ))
    }

    /**
     * Translate a raw [IoFailure] to the structured [InterpretError.IoFailure],
     * honouring [EvaluationLimits.errorVerbosity] exactly as the interpreter's
     * `translateIoFailure` does. `at` is null — VM opcodes carry no NodeIds.
     */
    private fun translateVmIoFailure(io: IoFailure, limits: EvaluationLimits): InterpretError.IoFailure {
        // Q-054 follow-up: scrub the Redacted detail against this VM's
        // per-context scrubber (the active tenant's credentials), mirroring the
        // interpreter's translateIoFailure.
        val detail = when (limits.errorVerbosity) {
            org.strand.core.ErrorVerbosity.Redacted -> hostContext.scrubber.scrub(io.unscrubbedDetail)
            org.strand.core.ErrorVerbosity.Full -> io.unscrubbedDetail
            org.strand.core.ErrorVerbosity.RedactedWithKindOnly -> "(detail suppressed)"
        }
        // IoFailure.at is non-null; the VM carries no NodeIds, so use a
        // sentinel. (The Err payload excludes `at` entirely — § 4.2.)
        return InterpretError.IoFailure(at = NodeId(-1), kind = io.kind, detail = detail)
    }

    /**
     * Translate a raw [SandboxViolation] to the terminal
     * [InterpretError.SandboxViolation] (uncatchable). Mirrors the
     * interpreter's `translateSandboxViolation`.
     */
    private fun translateVmSandboxViolation(sv: SandboxViolation, limits: EvaluationLimits): InterpretError.SandboxViolation {
        val detail = when (limits.errorVerbosity) {
            org.strand.core.ErrorVerbosity.Redacted -> hostContext.scrubber.scrub(sv.unscrubbedDetail)
            org.strand.core.ErrorVerbosity.Full -> sv.unscrubbedDetail
            org.strand.core.ErrorVerbosity.RedactedWithKindOnly -> "(detail suppressed)"
        }
        return InterpretError.SandboxViolation(at = NodeId(-1), kind = sv.kind, detail = detail)
    }

    /** A category-id grant as the wildcard [CapabilitySet] (the pre-H2 VM semantics). */
    private fun categoryGrant(ids: Set<Int>): CapabilitySet =
        CapabilitySet.ofCategories(ids.mapTo(HashSet()) { NodeId(it) })

    private fun effectsOf(callable: Any): List<NodeId> {
        val ids = when (callable) {
            is VmClosure -> callable.effects
            is VmForeign -> callable.effects
            is VmFixpoint -> callable.effects
            else -> IntArray(0)
        }
        return ids.map { NodeId(it) }
    }

    /** The call site's EffectDecl parameters keyed by category (interpreter `evalEffectInstances`). */
    private fun siteInstances(site: Constant.CallSiteC, params: List<Value>): Map<NodeId, List<Value>> {
        if (site.categories.isEmpty()) return emptyMap()
        val out = LinkedHashMap<NodeId, List<Value>>(site.categories.size)
        var cursor = 0
        for (i in site.categories.indices) {
            val n = site.paramCounts[i]
            out[NodeId(site.categories[i])] = params.subList(cursor, cursor + n).toList()
            cursor += n
        }
        return out
    }

    /**
     * Q-039: the capability-check parameters synthesized from [fn]'s
     * projections — an ArgRef source is the exact argument value the builtin
     * receives (interpreter `synthesizeProjectedInstances`). The interpreter
     * evaluates a literal source's node here, so a literal carrying schema
     * obligations is checked here too (Q-047); [depth] is the frame count of
     * the dispatch loop this call suspends.
     */
    private fun synthesizeProjectedInstances(
        fn: VmForeign,
        args: List<Any>,
        depth: Int,
        limits: EvaluationLimits,
    ): Map<NodeId, List<Value>> {
        val out = LinkedHashMap<NodeId, List<Value>>(fn.projections.size)
        for (projection in fn.projections) {
            out[NodeId(projection.category)] = projection.sources.map { src ->
                when (src) {
                    is Constant.ProjectionSourceC.ArgRef -> args[src.index] as Value
                    is Constant.ProjectionSourceC.Literal -> constantValue(src.value).also { v ->
                        src.check?.let { checkSchema(it, v, depth, limits) }
                    }
                }
            }
        }
        return out
    }

    private fun constantValue(c: Constant): Value = when (c) {
        is Constant.IntC -> Value.IntV(c.value)
        is Constant.FloatC -> Value.FloatV(c.value)
        is Constant.StringC -> Value.StringV(c.value)
        is Constant.BoolC -> Value.BoolV(c.value)
        Constant.UnitC -> Value.UnitV
        is Constant.BytesC -> Value.BytesV(c.value)
        else -> error("projection literal constant $c is not a literal")
    }

    /**
     * Review H2: the interpreter's refinement-lattice capability check
     * (`Interpreter.checkCapabilities`, Q-031 § 5) over [currentCaps].
     * First pass: every [declared] category absent from the context raises
     * one [InterpretError.CapabilityViolation]. Second pass: each category
     * the site instantiated (present in [instances]) must be covered by a
     * granted pattern, else [InterpretError.RefinementViolation]. Categories
     * declared but not instantiated at this site propagate (confused-deputy
     * semantics) — except at a [performs] site (a foreign dispatch), where an
     * instance-free parameterized category needs an unrefined grant
     * ([checkUnrefinedGrant]). Reports are built exactly as the interpreter
     * builds them, with category names from [ChunkTable.categoryNames] and the
     * call-site NodeId carried by the lowered [Constant.CallSiteC].
     */
    private fun checkCapabilities(
        at: NodeId,
        declared: List<NodeId>,
        instances: Map<NodeId, List<Value>>,
        limits: EvaluationLimits,
        performs: Boolean = false,
        registryBound: Set<NodeId> = emptySet(),
    ) {
        if (declared.isEmpty()) return
        val context = currentCaps
        val missing = declared.toSet().filter { it !in context.grants }.toSet()
        if (missing.isNotEmpty()) {
            val missingInOrder = declared.filter { it in missing }.distinct()
            val requestedValues = missingInOrder.flatMap { instances[it].orEmpty() }
            val report = denialReport(
                at = at,
                categoryName = missingInOrder.joinToString(", ") { categoryNameOf(it) },
                requested = requestedValues,
                held = emptyList(),
                heldCategoryName = "",
                limits = limits,
            )
            // Q-055: the denied audit record reuses the Q-064 report.
            emitAudit(auditRecordFor(report, AuditOutcome.Denied(report)))
            throw InterpretException(InterpretError.CapabilityViolation(
                at = at,
                missing = missing,
                report = report,
            ))
        }
        for (category in declared) {
            val requirement = instances[category]
            if (requirement == null) {
                if (performs) {
                    checkUnrefinedGrant(at, category, context, limits)
                    // Q-055: an uninstantiated performing dispatch is recorded
                    // with no parameters (the interpreter's record shape).
                    emitAudit(AuditRecord(
                        callSiteNodeId = at.takeIf { it != RUNTIME_BOUNDARY },
                        effectCategory = categoryNameOf(category),
                        refinementParameters = emptyList(),
                        outcome = AuditOutcome.Allowed,
                        phase = currentPhase(),
                    ))
                }
                continue
            }
            val grants = context.grants.getValue(category)
            val name = categoryNameOf(category)
            // A registry-bound requirement has the registry's arity; a
            // pattern with no concrete slot is unrefined at any arity.
            val matched = grants.any { covers(it, requirement) } ||
                (category in registryBound && grants.any { p -> p.arguments.all { it is CapabilityArgument.Wildcard } })
            if (!matched) {
                val report = denialReport(
                    at = at,
                    categoryName = name,
                    requested = requirement,
                    held = grants,
                    heldCategoryName = name,
                    limits = limits,
                )
                emitAudit(auditRecordFor(report, AuditOutcome.Denied(report)))
                throw InterpretException(InterpretError.RefinementViolation(
                    at = at,
                    category = category,
                    requirement = requirement,
                    available = grants,
                    report = report,
                ))
            }
            // Q-055: an Allowed record for each category the site concretely
            // exercised, its refinement values rendered and scrubbed through
            // the per-context scrubber (the interpreter's record shape).
            emitAudit(AuditRecord(
                callSiteNodeId = at.takeIf { it != RUNTIME_BOUNDARY },
                effectCategory = name,
                refinementParameters = requirement.map { renderAuditParameter(it) },
                outcome = AuditOutcome.Allowed,
                phase = currentPhase(),
            ))
        }
    }

    /**
     * The interpreter's `checkUnrefinedGrant`: a performing call exercises
     * [category] with no instance. When the category is parameterized
     * ([ChunkTable.categoryParamCounts]) the grant must hold an unrefined
     * pattern (non-empty arguments, all wildcards), else a
     * [InterpretError.RefinementViolation] whose report renders the request
     * as `*` per parameter.
     */
    private fun checkUnrefinedGrant(
        at: NodeId,
        category: NodeId,
        context: CapabilitySet,
        limits: EvaluationLimits,
    ) {
        val paramCount = table.categoryParamCounts[category.value] ?: return
        val grants = context.grants[category] ?: return // absent categories already raised
        // A parameterless category is not covered by a grant made only of
        // refined patterns either (the interpreter's rule).
        val unrefined = if (paramCount > 0) {
            grants.any { p -> p.arguments.isNotEmpty() && p.arguments.all { it is CapabilityArgument.Wildcard } }
        } else {
            grants.isEmpty() || grants.any { p -> p.arguments.all { it is CapabilityArgument.Wildcard } }
        }
        if (unrefined) return
        val name = categoryNameOf(category)
        val baseReport = denialReport(
            at = at,
            categoryName = name,
            requested = emptyList(),
            held = grants,
            heldCategoryName = name,
            limits = limits,
        )
        val report = baseReport.copy(
            requested = baseReport.requested?.let { List(paramCount) { "*" } },
        )
        emitAudit(auditRecordFor(report, AuditOutcome.Denied(report)))
        throw InterpretException(InterpretError.RefinementViolation(
            at = at,
            category = category,
            requirement = emptyList(),
            available = grants,
            report = report,
        ))
    }

    /**
     * The capability check for [callable] applied to pre-evaluated [args]
     * with no call-site EffectDecls: a handler standing in for an intercepted
     * call, or the [applyClosure] runtime boundary (the interpreter's
     * `applyValue`). A [VmForeign] is a performing site whose projected
     * instances are synthesized from [args] (`dispatchForeign` with null
     * instances); a closure or fixpoint is checked instance-free.
     */
    private fun checkAppliedCallable(
        at: NodeId,
        callable: Any,
        args: List<Any>,
        depth: Int,
        limits: EvaluationLimits,
    ) {
        if (callable is VmForeign) {
            checkForeignFloor(at, callable)
            val instances = if (callable.projections.isNotEmpty()) {
                synthesizeProjectedInstances(callable, args, depth, limits)
            } else {
                emptyMap()
            }
            val resource = resourceInstances(callable, args)
            checkCapabilities(
                at, effectsOf(callable), instances + resource, limits,
                performs = performs(callable), registryBound = resource.keys,
            )
        } else {
            checkCapabilities(at, effectsOf(callable), emptyMap(), limits)
        }
    }

    /**
     * The interpreter's `resourceInstances`: the refinement the registry
     * assigns to this dispatch, from
     * [org.strand.core.BuiltinEffectTable.resourceProjection] and the
     * argument values, overriding the graph's own instances.
     */
    private fun resourceInstances(fn: VmForeign, args: List<Any>): Map<NodeId, List<Value>> {
        val projection = org.strand.core.BuiltinEffectTable.resourceProjection(fn.target) ?: return emptyMap()
        val out = LinkedHashMap<NodeId, List<Value>>()
        for (effect in fn.effects) {
            val category = NodeId(effect)
            val indices = projection[categoryNameOf(category)] ?: continue
            if (indices.any { it >= args.size || args[it] !is Value }) continue
            out[category] = indices.map { args[it] as Value }
        }
        return out
    }

    /**
     * A foreign dispatch performs its effects unless the target is a
     * higher-order builtin, whose row is the latent row of its callbacks (the
     * interpreter's `dispatchForeign` `performs` flag).
     */
    private fun performs(fn: VmForeign): Boolean = Builtins.lookupHigherOrder(fn.target) == null

    /**
     * The interpreter's `checkForeignFloor` (defence in depth for review
     * finding 1): before dispatching a registry target with an effect floor,
     * confirm the callee's declared row covers it. The verifier's Q-056
     * `BuiltinEffectMismatch` rule rejects such graphs at admission; this
     * re-check holds for tables lowered from unverified stores.
     */
    private fun checkForeignFloor(at: NodeId, fn: VmForeign) {
        val required = foreignEffectFloor(fn.target) ?: return
        if (required.isEmpty()) return
        val declaredNames = fn.effects.map { categoryNameOf(NodeId(it)) }.toSet()
        val missing = required - declaredNames
        if (missing.isNotEmpty()) {
            throw InterpretException(InterpretError.BuiltinContractViolation(
                at = at,
                target = fn.target,
                detail = "ForeignNode under-declares the target's effects; missing ${missing.sorted()}",
            ))
        }
    }

    /**
     * The effect categories a registry target really exercises — the
     * interpreter's `foreignEffectFloor`: the Q-056 signature oracle's name
     * set when resolvable and the target is known to it, else the core
     * [org.strand.core.BuiltinEffectTable] floor (null for exempt targets).
     */
    private fun foreignEffectFloor(target: String): Set<String>? {
        if (org.strand.core.BuiltinEffectTable.isExempt(target)) return null
        if (target.startsWith("strand-builtin:")) {
            org.strand.verifier.BuiltinSignatures.effectNamesFor(target)?.let { return it }
        }
        return org.strand.core.BuiltinEffectTable.requiredCategories(target)
    }

    /**
     * Review M5: VM callables ([VmClosure], [VmFixpoint], [VmForeign]) are not
     * [Value]s ([Value] is sealed in `:interpreter`), so a callable stored in a
     * record field or a sum payload is boxed as a [Value.Resource] of kind
     * [VM_CALLABLE_KIND] whose id indexes this Vm's box table, and unboxed
     * again when projected (PRODUCT_GET, SUM_PAYLOAD) or called. Inside the VM
     * the behaviour matches the interpreter's first-class closures; a boxed
     * callable that escapes to the host shows as that Resource (the
     * interpreter would return a Value.Closure — the same representational
     * difference as a bare closure result). [callable] unboxes for hosts.
     */
    private val callableBoxes = ArrayList<Any>()

    private fun box(x: Any): Value {
        if (x is Value) return x
        callableBoxes += x
        return Value.Resource(id = (callableBoxes.size - 1).toLong(), kind = VM_CALLABLE_KIND)
    }

    private fun unbox(x: Any): Any =
        if (x is Value.Resource && x.kind == VM_CALLABLE_KIND) {
            callableBoxes.getOrNull(x.id.toInt())
                ?: error("VM callable box ${x.id} does not belong to this Vm")
        } else {
            x
        }

    /**
     * Resolve a [Value] produced by this Vm to the callable it boxes (for
     * [applyClosure]); a non-boxed value is returned unchanged.
     */
    fun callable(value: Value): Any = unbox(value)

    private fun categoryNameOf(id: NodeId): String = table.categoryNames[id.value] ?: id.toString()

    /**
     * Q-055: emit [record] to this VM's per-context audit sink
     * ([HostContext.auditSink]; the default no-op sink discards it).
     */
    private fun emitAudit(record: AuditRecord) {
        hostContext.auditSink.record(record)
    }

    /** Q-055: the denied [AuditRecord] built from the reused Q-064 [DenialReport]. */
    private fun auditRecordFor(report: DenialReport, outcome: AuditOutcome): AuditRecord =
        AuditRecord(
            callSiteNodeId = report.node,
            effectCategory = report.category,
            refinementParameters = report.requested ?: emptyList(),
            outcome = outcome,
            instanceId = report.instanceId,
            eventIndex = report.eventIndex,
            phase = report.phase,
        )

    /**
     * Q-055: one refinement parameter rendered for an allowed audit record and
     * scrubbed through the per-context scrubber — the interpreter's
     * `renderAuditParameter`.
     */
    private fun renderAuditParameter(v: Value): String = hostContext.scrubber.scrub(
        when (v) {
            is Value.StringV -> v.v
            is Value.IntV -> v.v.toString()
            is Value.FloatV -> v.v.toString()
            is Value.BoolV -> v.v.toString()
            Value.UnitV -> "()"
            is Value.BytesV -> "bytes[${v.v.size}]"
            else -> v.toString()
        }
    )

    private fun denialReport(
        at: NodeId,
        categoryName: String,
        requested: List<Value>,
        held: List<CapabilityPattern>,
        heldCategoryName: String,
        limits: EvaluationLimits,
    ): DenialReport {
        val kindOnly = limits.errorVerbosity == org.strand.core.ErrorVerbosity.RedactedWithKindOnly
        return DenialReport(
            category = categoryName,
            requested = if (kindOnly) null else requested.map { DenialReport.renderParameter(it) },
            held = if (kindOnly) null else held.map { DenialReport.renderGrant(heldCategoryName, it) },
            node = at.takeIf { it != RUNTIME_BOUNDARY },
            instanceId = null,
            eventIndex = null,
            phase = currentPhase(),
        )
    }

    /**
     * Run a sub-chunk as a standalone frame and push its result onto the
     * surrounding frame's stack. Used by LOAD_HASH for content-addressed
     * NodeRef target resolution.
     */
    private fun runSubChunk(chunkIndex: Int, frames: ArrayDeque<Frame>) {
        val sub = table[chunkIndex]
        val nextFrame = Frame(chunk = sub, captures = emptyArray())
        frames.addLast(nextFrame)
        // The sub-chunk will RET back to the current frame, pushing its
        // result onto the surrounding frame's stack. The dispatch loop
        // handles the RET via the normal frames-list pop.
    }

    /**
     * Search active handlers for one whose intercept category is in the
     * callee's effects. Innermost (last-pushed) wins — the same rule the
     * tree-walking interpreter applies.
     */
    private fun findInterceptingHandler(effects: IntArray): VmActiveHandler? {
        if (handlers.isEmpty() || effects.isEmpty()) return null
        for (i in handlers.indices.reversed()) {
            val h = handlers[i]
            for (effId in effects) {
                if (h.intercept == effId) return h
            }
        }
        return null
    }

    /**
     * Apply [fn] to [args] either by dispatching to a builtin (foreign)
     * or by pushing a fresh frame (closure / fixpoint). Used by the CALL
     * opcode after the handler-and-cap checks pass.
     */
    private fun invokeCallable(
        at: NodeId,
        fn: Any,
        args: Array<Any>,
        frames: ArrayDeque<Frame>,
        current: Frame,
        limits: EvaluationLimits,
    ) {
        when (fn) {
            is VmClosure -> {
                val sub = table[fn.chunkIndex]
                val nextFrame = Frame(chunk = sub, captures = fn.captures)
                for ((i, arg) in args.withIndex()) {
                    nextFrame.locals[i] = arg
                }
                frames.addLast(nextFrame)
            }
            is VmForeign ->
                current.stack.add(dispatchForeign(at, fn, args.asList(), depth = frames.size, limits = limits))
            is VmFixpoint -> {
                val sub = table[fn.chunkIndex]
                val nextFrame = Frame(chunk = sub, captures = fn.captures)
                nextFrame.locals[0] = fn
                for ((i, arg) in args.withIndex()) {
                    nextFrame.locals[i + 1] = arg
                }
                frames.addLast(nextFrame)
            }
            else -> error("invokeCallable: $fn is not callable (got ${fn::class.simpleName})")
        }
    }

    /**
     * Run the builtin [fn] binds on [args]: the dispatch half of the
     * interpreter's `dispatchForeign`, after the capability check. A
     * higher-order builtin (`List.Map`, `List.Fold`, ...) receives an
     * [Builtins.ApplyFn] that runs each callback through [applyNested]
     * under the capability context and handler stack of this call site, so
     * a callback is checked exactly as the interpreter's `applyValue` checks
     * it. Callable arguments cross the builtin boundary boxed ([box]).
     *
     * [at] is the dispatching call site (null at the [applyClosure]
     * boundary) and [depth] the frame count of the loop this call suspends.
     * An unregistered target is the interpreter's typed
     * [InterpretError.UnknownForeignTarget]; an `IllegalArgumentException`
     * or `ClassCastException` out of the builtin is a contract violation
     * (a `require` guard such as division by zero, or a type-confused
     * argument list under a graph-supplied foreignType, Q-066), reported
     * with `at = null` as VM builtin failures are.
     */
    private fun dispatchForeign(
        at: NodeId?,
        fn: VmForeign,
        args: List<Any>,
        depth: Int,
        limits: EvaluationLimits,
    ): Value {
        val site = at ?: RUNTIME_BOUNDARY
        val valueArgs = args.map(::box)
        return try {
            val higherOrder = Builtins.lookupHigherOrder(fn.target)
            if (higherOrder != null) {
                val apply = Builtins.ApplyFn { callable, callbackArgs ->
                    applyNested(site, unbox(callable), callbackArgs, depth, limits)
                }
                higherOrder.invoke(hostContext, valueArgs, apply)
            } else {
                val builtin = Builtins.lookup(fn.target)
                    ?: throw InterpretException(InterpretError.UnknownForeignTarget(at = site, target = fn.target))
                builtin.invoke(hostContext, valueArgs)
            }
        } catch (e: IllegalArgumentException) {
            throw InterpretException(InterpretError.BuiltinContractViolation(
                at = null,
                target = fn.target,
                detail = e.message ?: "builtin contract violation",
            ))
        } catch (e: ClassCastException) {
            throw InterpretException(InterpretError.BuiltinContractViolation(
                at = null,
                target = fn.target,
                detail = e.message ?: "builtin argument type confusion",
            ))
        }
    }

    /**
     * Apply [callable] to [args] on behalf of a higher-order builtin: the
     * interpreter's `applyValueToArgs`. The callable's own effects are
     * checked first ([checkAppliedCallable]: a closure or fixpoint
     * category-only, a foreign callable as a performing site with its
     * projected instances synthesized from [args]); a closure or fixpoint
     * body then runs in a nested dispatch loop that shares this Vm's
     * capability context, handler stack and budget. Attempt markers are
     * isolated for the nested run, as in [applyClosure], because they index
     * a specific frame deque; an uncaught catchable failure propagates to
     * the enclosing loop, whose own markers then apply.
     */
    private fun applyNested(
        at: NodeId,
        callable: Any,
        args: List<Any>,
        depth: Int,
        limits: EvaluationLimits,
    ): Value {
        checkAppliedCallable(at, callable, args, depth, limits)
        val frame = when (callable) {
            is VmForeign -> return dispatchForeign(at, callable, args, depth, limits)
            is VmClosure -> Frame(chunk = table[callable.chunkIndex], captures = callable.captures).also { f ->
                for ((i, arg) in args.withIndex()) f.locals[i] = arg
            }
            is VmFixpoint -> Frame(chunk = table[callable.chunkIndex], captures = callable.captures).also { f ->
                f.locals[0] = callable
                for ((i, arg) in args.withIndex()) f.locals[i + 1] = arg
            }
            else -> throw InterpretException(
                InterpretError.NotCallable(at = at, gotKind = callable::class.simpleName ?: "Value")
            )
        }
        return box(runNested(frame, depth, limits))
    }

    /**
     * Run [frame] to its RET in a nested dispatch loop suspended under a loop
     * of [depth] frames, sharing this Vm's capability context, handler stack
     * and budget, and return the raw result. Attempt markers are isolated for
     * the nested run because they index a specific frame deque; the
     * capability and handler stacks are restored afterwards whether the run
     * returns or throws.
     */
    private fun runNested(frame: Frame, depth: Int, limits: EvaluationLimits): Any {
        val frames = ArrayDeque<Frame>()
        frames.addLast(frame)
        val savedAttempts = ArrayDeque(attemptStack)
        val savedCaps = currentCaps
        val savedCapDepth = capStack.size
        val savedHandlerDepth = handlers.size
        val savedOuterDepth = outerDepth
        attemptStack.clear()
        outerDepth += depth
        try {
            return runLoop(frames, limits)
        } finally {
            outerDepth = savedOuterDepth
            attemptStack.clear()
            attemptStack.addAll(savedAttempts)
            while (capStack.size > savedCapDepth) capStack.removeLast()
            currentCaps = savedCaps
            while (handlers.size > savedHandlerDepth) handlers.removeLast()
        }
    }

    /**
     * Q-047: the interpreter's `checkSchemaObligations` for one node. Each
     * invariant of [check], in order, is evaluated on [value] (the value the
     * node produced); the first `false` verdict raises
     * [InterpretError.SchemaInvariantViolation] at the node. A no-op while an
     * invariant is already being evaluated. [depth] is the frame count of the
     * dispatch loop the evaluation suspends.
     */
    private fun checkSchema(check: Constant.SchemaCheckC, value: Any, depth: Int, limits: EvaluationLimits) {
        if (inInvariant) return
        for (c in check.checks) {
            val verdict = evaluateInvariant(c.chunkIndex, value, depth, limits)
            if (verdict !is Value.BoolV) {
                error(
                    "Invariant #${c.invariant}'s body evaluated to a non-Bool value " +
                        "($verdict); the verifier's SchemaInvariantBodyTypeMismatch rule should have rejected this."
                )
            }
            if (!verdict.v) {
                throw InterpretException(InterpretError.SchemaInvariantViolation(
                    at = NodeId(check.site),
                    schema = NodeId(c.schema),
                    invariant = NodeId(c.invariant),
                    valueDescription = describeChecked(value),
                ))
            }
        }
    }

    /**
     * Q-047: the interpreter's `evaluateInvariantBody`. The invariant's body
     * sub-chunk runs to its predicate callable, which is then applied to
     * [value] as the interpreter's `applyCallable` applies it at the runtime
     * boundary. Both run in nested dispatch loops under an empty capability
     * context and an empty handler stack, with obligation checking suppressed,
     * spending the run's budget; the surrounding context is restored after.
     */
    private fun evaluateInvariant(chunkIndex: Int, value: Any, depth: Int, limits: EvaluationLimits): Any {
        val savedCaps = currentCaps
        val savedHandlers = handlers.toList()
        val savedFlag = inInvariant
        currentCaps = CapabilitySet.EMPTY
        handlers.clear()
        inInvariant = true
        try {
            val predicate = runNested(Frame(chunk = table[chunkIndex], captures = emptyArray()), depth, limits)
            return applyNested(RUNTIME_BOUNDARY, unbox(predicate), listOf(value), depth, limits)
        } finally {
            inInvariant = savedFlag
            handlers.clear()
            handlers.addAll(savedHandlers)
            currentCaps = savedCaps
        }
    }

    /**
     * A violation's `valueDescription`: the value's `toString`, as the
     * interpreter renders it. A VM callable has no [Value] form, so it (and a
     * callable boxed inside a record) renders as the VM's own representation,
     * not the interpreter's `Value.Closure` text.
     */
    private fun describeChecked(value: Any): String =
        if (value is Value) value.toString() else "<${value::class.simpleName}>"

    private fun currentPhase(): DenialPhase = if (inInvariant) DenialPhase.Invariant else DenialPhase.Expression
}

/**
 * One active effect handler. Mirrors the tree-walking interpreter's
 * `Interpreter.ActiveHandler`: an `intercept` EffectCategory NodeId
 * value plus the handler's runtime callable value (typically VmClosure).
 */
internal data class VmActiveHandler(val intercept: Int, val handlerValue: Any, val node: Int)

/**
 * The `NodeId(-1)` sentinel the interpreter's `applyCallable` uses for the
 * runtime boundary (no graph site); a denial there reports a null node.
 */
private val RUNTIME_BOUNDARY = NodeId(-1)

/**
 * N-047 (Q-048) attempt marker. Recorded at every `ATTEMPT_PUSH`; consumed by
 * the unwinder on a catchable failure (popped on the success path by
 * `ATTEMPT_POP`). [frameDepth] / [stackDepth] are the `frames.size` and the
 * marker frame's operand-stack size at push time; [capStackDepth] /
 * [handlerDepth] are the capability- and handler-stack depths; [savedCaps] is
 * the `currentCaps` to restore; [errPc] is the absolute pc (in the marker
 * frame) of the err-label where the unwinder resumes after pushing the
 * ErrorPayload.
 */
internal data class AttemptMarker(
    val frameDepth: Int,
    val stackDepth: Int,
    val capStackDepth: Int,
    val handlerDepth: Int,
    val savedCaps: CapabilitySet,
    val errPc: Int,
)

// Q-064: the former `VmCapabilityViolation` exception is gone — a CALL-site
// category denial now raises the shared `InterpretException` carrying
// `InterpretError.CapabilityViolation` (with a coarse `DenialReport`), the
// same unification at the InterpretError boundary that VmResourceExhaustion
// receives in `runLoopMapped`. See `Vm.vmCapabilityDenial`.

/**
 * Per-call frame state. Each frame owns its operand stack, its locals
 * array, and its captures (passed by the caller via [VmClosure]).
 */
internal class Frame(
    val chunk: Chunk,
    val captures: Array<Any>,
) {
    val code: ByteArray get() = chunk.code
    var pc: Int = 0

    // Operand stack and locals both hold Any: literal pushes produce
    // Value; MAKE_CLOSURE / MAKE_FOREIGN produce VmClosure / VmForeign.
    // Higher-order programs store callables into locals (the let-bound
    // lambda pattern) and into captures (closures over outer lambdas).
    // The mixed-type stack is required because VmClosure / VmForeign are
    // NOT subclasses of Value (Value is sealed in the :interpreter module).
    val stack: ArrayDeque<Any> = ArrayDeque()

    val locals: Array<Any?> = arrayOfNulls(chunk.locals)

    /**
     * Read the next 4-byte little-endian operand at the current pc,
     * advancing pc past it.
     */
    fun operand(): Int {
        val bb = ByteBuffer.wrap(code, pc, 4).order(ByteOrder.LITTLE_ENDIAN)
        pc += 4
        return bb.int
    }

    /**
     * Read the next operand and use it as a constant-pool index. Returns
     * the [Constant] at that index.
     */
    fun constant(): Constant = chunk.constants[operand()]
}

/**
 * VM closure value. Captured environment is an immutable array of [Value]s
 * in the order the lowerer collected them; the chunk's `LOAD_CAPTURE`
 * opcodes index into this array.
 *
 * NOT a subclass of [Value] (which is sealed in the :interpreter module).
 * The VM's operand stack holds `Any` so it can carry both [Value] (for
 * the primitive results corpus tests compare against the interpreter)
 * and [VmClosure] (the VM-internal callable representation that only the
 * VM produces and consumes).
 */
internal class VmClosure(
    val chunkIndex: Int,
    val captures: Array<Any>,
    val effects: IntArray = IntArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is VmClosure && chunkIndex == other.chunkIndex && captures.contentEquals(other.captures)
    override fun hashCode(): Int = 31 * chunkIndex + captures.contentHashCode()
}

/**
 * VM foreign callable. Holds the foreign target string ("strand-builtin:
 * Int.Add" etc.); CALL sites dispatch via [org.strand.interpreter.Builtins]
 * to get the host-side function and apply it to the popped arguments.
 *
 * Every CALL of a foreign callable runs the interpreter's refinement-lattice
 * capability check over its declared [effects] (review H2): the parameters
 * come from the call site's EffectDecls, or — when [projections] is
 * non-empty — are synthesized from the evaluated arguments (Q-039).
 */
internal class VmForeign(
    val target: String,
    val effects: IntArray = IntArray(0),
    /** Q-039 effect projections (review H2); empty for unprojected bindings. */
    val projections: List<Constant.ProjectionC> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = other is VmForeign && target == other.target
    override fun hashCode(): Int = target.hashCode()
}

/**
 * VM Fixpoint callable. Like [VmClosure] but a CALL site prepends the
 * FixpointV itself as the new frame's first local — the body Lambda's
 * first parameter is the recursive-call slot, matching the tree-walking
 * interpreter's `applyCallable` Fixpoint branch.
 */
internal class VmFixpoint(
    val chunkIndex: Int,
    val captures: Array<Any>,
    val effects: IntArray = IntArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is VmFixpoint && chunkIndex == other.chunkIndex && captures.contentEquals(other.captures)
    override fun hashCode(): Int = 31 * chunkIndex + captures.contentHashCode()
}

/**
 * Thrown when the VM hits an opcode whose handler hasn't been
 * implemented yet. Used by the slice-1 dispatch loop to surface Layer 3+
 * gaps cleanly when corpus programs use features the VM doesn't cover.
 */
class VmOpcodeNotImplemented(val op: Opcode) :
    RuntimeException("VM slice 1 has not implemented opcode $op")

/** [Value.Resource] kind marking a boxed VM callable (see `Vm.box`). */
const val VM_CALLABLE_KIND: String = "strand-vm:callable"

/**
 * Q-040 VM-internal exception. Raised by [Vm.runLoop] when a resource
 * cap is breached and translated to the shared
 * [InterpretError.ResourceExhaustion] at the [Vm.runLoopMapped] public
 * boundary so host callers see a single error shape regardless of
 * which backend evaluated.
 *
 * [atNode] is always null in slice 1 because opcodes do not carry
 * NodeIds; a Q-017 step 2 source-mapping follow-up may fill this in.
 * The interpreter's `InterpretError.ResourceExhaustion.at` accepts
 * null precisely so the VM-mapped variant has a place to land.
 */
class VmResourceExhaustion(
    val kind: ExhaustionKind,
    val atNode: NodeId?,
    val current: Long,
    val limit: Long,
) : RuntimeException(
    "VM resource exhausted: kind=$kind current=$current limit=$limit at=$atNode"
)
