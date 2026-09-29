package org.strand.vm

import org.strand.bytecode.Chunk
import org.strand.bytecode.ChunkTable
import org.strand.bytecode.Constant
import org.strand.bytecode.Opcode
import org.strand.core.EvaluationLimits
import org.strand.core.ExhaustionKind
import org.strand.core.NodeId
import org.strand.interpreter.Builtins
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
     * effects are checked category-only against [capabilities] before the
     * body runs (there is no call-site EffectDecl at this boundary), and the
     * refinement checks fire at the effectful call sites inside the body.
     */
    fun applyClosure(
        closure: Any,
        args: List<Value>,
        capabilities: CapabilitySet,
        limits: EvaluationLimits = EvaluationLimits.DEFAULTS,
    ): Value {
        currentCaps = capabilities
        // Note: we do NOT clear capStack / handlers here; the caller may
        // be invoking this from inside an active CapabilityScope or Handler
        // context (e.g., a transition function called from a CapabilityScope
        // body). For top-level callers, both stacks should be empty.
        //
        // The attempt-marker stack, by contrast, IS isolated per applyClosure
        // run: markers record frame depths into the fresh [frames] deque this
        // call builds, so a marker from an outer evaluation must not be
        // consulted here. Snapshot and restore around the run.
        val savedAttempts = ArrayDeque(attemptStack)
        attemptStack.clear()
        val frames = ArrayDeque<Frame>()
        try {
            checkCapabilities(RUNTIME_BOUNDARY, effectsOf(closure), emptyMap(), limits)
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
                    // Dispatch directly via Builtins; no frame setup.
                    val builtin = Builtins.lookup(closure.target)
                        ?: error("applyClosure: no Builtins entry for foreign target '${closure.target}'")
                    return try {
                        builtin.invoke(hostContext, args)
                    } catch (e: IllegalArgumentException) {
                        throw InterpretException(InterpretError.BuiltinContractViolation(
                            at = null,
                            target = closure.target,
                            detail = e.message ?: "builtin contract violation",
                        ))
                    } catch (e: ClassCastException) {
                        // Q-066: graph-supplied foreignType is unchecked
                        // against the builtin's real argument contract; a
                        // type-confused argument list is a contract
                        // violation, not an implementation crash.
                        throw InterpretException(InterpretError.BuiltinContractViolation(
                            at = null,
                            target = closure.target,
                            detail = e.message ?: "builtin argument type confusion",
                        ))
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
            throw InterpretException(InterpretError.ResourceExhaustion(
                at = e.atNode,
                kind = e.kind,
                current = e.current,
                limit = e.limit,
            ))
        }

    /**
     * The dispatch loop, extracted so [run], [evaluate], and [applyClosure]
     * can share it. Pops/processes opcodes until the frame stack is
     * empty (RET to the bottom) or HALT fires. Returns the top-of-stack
     * value at termination (may be Value or VmClosure or other).
     */
    private fun runLoop(frames: ArrayDeque<Frame>, limits: EvaluationLimits): Any {
        // Q-040 per-evaluation counters: steps + allocations only. Stack
        // depth is read from frames.size at each step (no separate
        // counter needed); wall clock samples System.nanoTime() every
        // [EvaluationLimits.wallClockSampleEvery] steps from this anchor.
        var steps = 0L
        var allocated = 0L
        val startNanos = System.nanoTime()
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
            if (frames.size > limits.maxStackDepth) {
                throw VmResourceExhaustion(
                    kind = ExhaustionKind.StackDepth,
                    atNode = null,
                    current = frames.size.toLong(),
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
                    val handlerValue = current.stack.removeLast()
                    handlers += VmActiveHandler(interceptC.value.toInt(), handlerValue)
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
                        fields[c.names[i]] = current.stack[startIdx + i] as Value
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
                    current.stack.add(field)
                }

                Opcode.SUM_NEW -> {
                    // Layer 5 step 3b: build a SumV. Pop payload if the
                    // case has one; otherwise emit a nullary SumV.
                    val c = current.constant() as Constant.SumCaseC
                    val payload = if (c.hasPayload) current.stack.removeLast() as Value else null
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
                    current.stack.add(sum.payload ?: Value.UnitV)
                }

                Opcode.THROW_NO_MATCH -> {
                    throw VmNoMatchingCase()
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
                    val fn = current.stack.removeLast()
                    val effects: IntArray = when (fn) {
                        is VmClosure -> fn.effects
                        is VmForeign -> fn.effects
                        is VmFixpoint -> fn.effects
                        else -> error("CALL: callee is not callable (got ${fn::class.simpleName})")
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
                        // surrounding context, checked category-only (no
                        // EffectDecls reach the handler) — the interpreter's
                        // applyValue rule (review H2).
                        checkCapabilities(NodeId(site.site), effectsOf(intercept.handlerValue), emptyMap(), limits)
                        invokeCallable(intercept.handlerValue, args, frames, current)
                        continue
                    }
                    // Capability check (review H2): the interpreter's
                    // two-pass rule — every declared category present, then
                    // each category the site instantiates (EffectDecls, or
                    // the callee's Q-039 projections synthesized from the
                    // evaluated arguments) covered by a granted pattern.
                    // Denials raise the shared, uncatchable
                    // CapabilityViolation / RefinementViolation, so the
                    // per-opcode InterpretException catch declines to unwind
                    // them to any attempt marker.
                    val instances = if (fn is VmForeign && fn.projections.isNotEmpty()) {
                        synthesizeProjectedInstances(fn, args)
                    } else {
                        siteInstances(site, instanceParams)
                    }
                    checkCapabilities(NodeId(site.site), effects.map { NodeId(it) }, instances, limits)
                    invokeCallable(fn, args, frames, current)
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
     * receives (interpreter `synthesizeProjectedInstances`).
     */
    private fun synthesizeProjectedInstances(fn: VmForeign, args: Array<Any>): Map<NodeId, List<Value>> {
        val out = LinkedHashMap<NodeId, List<Value>>(fn.projections.size)
        for (projection in fn.projections) {
            out[NodeId(projection.category)] = projection.sources.map { src ->
                when (src) {
                    is Constant.ProjectionSourceC.ArgRef -> args[src.index] as Value
                    is Constant.ProjectionSourceC.Literal -> constantValue(src.value)
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
     * semantics). Reports are built exactly as the interpreter builds them,
     * with category names from [ChunkTable.categoryNames] and the call-site
     * NodeId carried by the lowered [Constant.CallSiteC].
     */
    private fun checkCapabilities(
        at: NodeId,
        declared: List<NodeId>,
        instances: Map<NodeId, List<Value>>,
        limits: EvaluationLimits,
    ) {
        if (declared.isEmpty()) return
        val context = currentCaps
        val missing = declared.toSet().filter { it !in context.grants }.toSet()
        if (missing.isNotEmpty()) {
            val missingInOrder = declared.filter { it in missing }.distinct()
            val requestedValues = missingInOrder.flatMap { instances[it].orEmpty() }
            throw InterpretException(InterpretError.CapabilityViolation(
                at = at,
                missing = missing,
                report = denialReport(
                    at = at,
                    categoryName = missingInOrder.joinToString(", ") { categoryNameOf(it) },
                    requested = requestedValues,
                    held = emptyList(),
                    heldCategoryName = "",
                    limits = limits,
                ),
            ))
        }
        for (category in declared) {
            val requirement = instances[category] ?: continue
            val grants = context.grants.getValue(category)
            if (grants.none { covers(it, requirement) }) {
                val name = categoryNameOf(category)
                throw InterpretException(InterpretError.RefinementViolation(
                    at = at,
                    category = category,
                    requirement = requirement,
                    available = grants,
                    report = denialReport(
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

    private fun categoryNameOf(id: NodeId): String = table.categoryNames[id.value] ?: id.toString()

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
            phase = DenialPhase.Expression,
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
        fn: Any,
        args: Array<Any>,
        frames: ArrayDeque<Frame>,
        current: Frame,
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
            is VmForeign -> {
                val builtin = Builtins.lookup(fn.target)
                    ?: error("CALL: no Builtins entry for foreign target '${fn.target}'")
                val valueArgs = args.map { arg ->
                    arg as? Value
                        ?: error("CALL_FOREIGN: arg is ${arg::class.simpleName}, not a Value")
                }
                val result = try {
                    builtin.invoke(hostContext, valueArgs)
                } catch (e: IllegalArgumentException) {
                    // Builtin contract violation (e.g. division by zero from
                    // Int.Div / Int.Mod / Math.Mod). Translated to a structured,
                    // uncatchable InterpretError. `at = null` because VM opcodes
                    // do not carry NodeIds in slice 1 (matching Q-040 precedent
                    // for ResourceExhaustion.at in the VM path).
                    throw InterpretException(InterpretError.BuiltinContractViolation(
                        at = null,
                        target = fn.target,
                        detail = e.message ?: "builtin contract violation",
                    ))
                } catch (e: ClassCastException) {
                    // Q-066: type-confused builtin arguments (see the
                    // interpreter's parallel catch) surface structured.
                    throw InterpretException(InterpretError.BuiltinContractViolation(
                        at = null,
                        target = fn.target,
                        detail = e.message ?: "builtin argument type confusion",
                    ))
                }
                current.stack.add(result)
            }
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
}

/**
 * One active effect handler. Mirrors the tree-walking interpreter's
 * `Interpreter.ActiveHandler`: an `intercept` EffectCategory NodeId
 * value plus the handler's runtime callable value (typically VmClosure).
 */
internal data class VmActiveHandler(val intercept: Int, val handlerValue: Any)

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

/**
 * Thrown when a Match's THROW_NO_MATCH opcode runs — every case's
 * pattern test returned false. Mirrors the tree-walking interpreter's
 * `InterpretError.NoMatchingCase` at the value level (the test asserts
 * the message; the structural type comparison happens at the test layer
 * because [Value] is sealed in `:interpreter`).
 */
class VmNoMatchingCase : RuntimeException("VM: no Match case matched the scrutinee")

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
