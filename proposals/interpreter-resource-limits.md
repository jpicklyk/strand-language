# Interpreter Resource Limits and DoS Resistance

**Document:** `proposals/interpreter-resource-limits.md`
**Status:** Draft proposal
**Date:** 2026-05-26
**Concerns:** [`design/security-model.md`](../design/security-model.md), [`security-index.md`](../security-index.md) § Finding 2, [Q-040](../open-questions.md#Q-040), [`impl-kotlin/core/src/main/kotlin/org/strand/core/Json.kt`](../impl-kotlin/core/src/main/kotlin/org/strand/core/Json.kt), [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt), [`impl-kotlin/vm/src/main/kotlin/org/strand/vm/Vm.kt`](../impl-kotlin/vm/src/main/kotlin/org/strand/vm/Vm.kt), [`impl-kotlin/runtime/src/main/kotlin/org/strand/runtime/StateMachineRuntime.kt`](../impl-kotlin/runtime/src/main/kotlin/org/strand/runtime/StateMachineRuntime.kt)
**Scope:** Medium

This proposal closes the implementation-level denial-of-service gap surfaced by the 2026-05-26 security audit (recorded as [Q-040](../open-questions.md#Q-040) and Finding 2 in [`security-index.md`](../security-index.md)). It introduces a unified `EvaluationLimits` policy honored at both admission time (JSON ingest) and evaluation time (the tree-walking interpreter and the bytecode VM), structured `ResourceExhaustion` errors that fire before the JVM raises `StackOverflowError` or hangs, and a host-configurable budget vocabulary that production deployments may tighten and benchmarking harnesses may loosen.

## 1. Problem statement

Finding 2 in [`security-index.md`](../security-index.md) records concrete hostile-graph attack shapes verified against the cited sources:

1. **Hostile JSON nesting.** [`JsonIngest.parse`](../impl-kotlin/core/src/main/kotlin/org/strand/core/Json.kt) hands raw input to `kotlinx.serialization.json.Json.parseToJsonElement`. That parser is recursive and exposes no depth cap. An input with one million `{"v":{"v":...}}` levels exhausts the JVM stack before any Strand-side code runs.

2. **Fixpoint with no base case.** [`Interpreter.eval`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt) constructs a `Value.FixpointFn` whose body is invoked through `applyFixpoint`. A body that unconditionally calls its recursive slot — `λ rec x. rec(x)` — drives the JVM stack through `eval → applyCall → applyFixpoint → eval → …` until `StackOverflowError` aborts the process. No host-side recovery.

3. **Pathological Application chain.** A deeply-nested Application graph recurses through `Interpreter.eval` for each curried call; JVM stack frames accumulate one per Application node. The VM ([`Vm.runLoop`](../impl-kotlin/vm/src/main/kotlin/org/strand/vm/Vm.kt)) is iterative on opcodes but allocates a `Frame` on every `CALL`, so a million-deep chain exhausts heap rather than stack — `OutOfMemoryError` instead of `StackOverflowError`, same outcome.

4. **Productive non-termination.** A Fixpoint that always produces a value before recursing — `λ rec acc. rec(acc + 1)` — runs indefinitely; the host process hangs or terminates without a language-level error.

For a language whose **primary author is an AI agent that may misgenerate**, this is not an accidental-crash problem. The threat model in [`design/security-model.md`](../design/security-model.md) § Threat model lists "malicious AI agent" as the first adversary; an agent emitting a Fixpoint with no base case currently halts the host process with an unrecoverable exception. The verifier accepts the graph (it is well-formed), the interpreter starts evaluating, and the process dies. The host cannot return a structured "too large / too deep / too many steps" error because none exists. The fix is to bound every recursive structure with a counter + threshold and surface breaches as recoverable errors.

## 2. Prior art

- **EOSIO / Wasmer fuel-based metering** — every Wasm opcode debits a configurable fuel cost from a per-call counter; reaching zero traps with `Trap::OutOfFuel`. The embedder catches the trap and reports the failure without process termination.
- **Solidity / EVM gas** — each instruction has a fixed gas cost; the transaction declares `gasLimit` at entry and reverts on exhaustion. Identical shape to what Strand needs: a budget set at the boundary, deducted per step, raised as a structured error.
- **JVM `-Xss` and `-Xmx`** — process-level coarse limits, surfaced as `StackOverflowError` and `OutOfMemoryError`. Strand needs finer granularity (per-evaluation) and structured errors instead of JVM-internal exceptions.
- **Erlang's reductions counter** — every process is preemptively scheduled after a fixed number of reductions. The Strand analog is a per-evaluation step counter, single-process rather than scheduler-integrated.
- **Lua's `lua_sethook` with `LUA_MASKCOUNT`** — the closest analog. The debug hook fires every N instructions; the C host installs a callback that decides whether to abort. Application-controlled, exactly the shape this proposal adopts.

The cross-cut: every system that bounds program execution does so with a counter incremented per step plus a host-supplied budget. Strand currently has neither.

## 3. Recommended approach

Introduce a unified `EvaluationLimits` data class in `:core` that bundles every per-evaluation budget. Thread it through every public evaluator entry point — `Interpreter.eval`, `Interpreter.applyCallable`, `Vm.evaluate`, `Vm.applyClosure`, `StateMachineRuntime.runMachine`, `runGroup`, `resume`. Both evaluator backends consult the same shape so a program that succeeds under the tree-walker also succeeds under the VM at the same budget.

Add ingest-side limits to `JsonIngest.parse` (`maxJsonDepth`, `maxNodeCount`, `maxIngestBytes`) checked before any node is materialized.

Add two structured error categories: `InterpretError.ResourceExhaustion(kind, atNode, current, limit)` at evaluation and `IngestError.ResourceExhaustion(kind, current, limit)` at admission. `ExhaustionKind` is a shared enum (`Steps | StackDepth | AllocatedValues | WallClock | JsonDepth | NodeCount | IngestBytes`) so host code can branch on the failure mode without parsing strings.

| # | Decision | Recommendation |
|---|----------|----------------|
| D1 | Shape across backends | One `EvaluationLimits` in `:core`, consumed identically by `:interpreter` and `:vm`. Per-backend specialization stays inside the dispatch loops. |
| D2 | Memory budget vocabulary | `maxAllocatedValues: Long` (one counter increment per `Value.*` construction site). Defer `maxBytesAllocated` (precise but requires per-value size accounting) to follow-up. |
| D3 | Wall-clock granularity | Sample `System.nanoTime()` every `wallClockSampleEvery` dispatch steps (default 1024). Sub-millisecond budgets are not the target. |
| D4 | Limit mutability | Fixed at entry. The host may not change limits during a running evaluation. |
| D5 | Default values | Sized for the existing corpus plus three-orders-of-magnitude headroom (see § 4.4). The corpus runs unchanged under defaults. |
| D6 | Verifier role | None new. Ingest-side checks raise `IngestError`; the verifier itself is not a configurable-budget enforcement point (would break determinism). |
| D7 | New node category or ADR | None. Pure host-side runtime/admission policy. The graph is unchanged. |

## 4. Detailed mechanism

### 4.1 The shared `EvaluationLimits` shape

New file `core/EvaluationLimits.kt`:

```kotlin
data class EvaluationLimits(
    val maxSteps: Long = 10_000_000L,
    val maxStackDepth: Int = 4096,
    val maxAllocatedValues: Long = 1_000_000L,
    val wallClockBudgetMillis: Long = 30_000L,
    val wallClockSampleEvery: Int = 1024,
    val maxJsonDepth: Int = 512,
    val maxNodeCount: Int = 100_000,
    val maxIngestBytes: Long = 64L * 1024L * 1024L,
) {
    companion object {
        val DEFAULTS = EvaluationLimits()
        val PERMISSIVE = EvaluationLimits(/* all Long.MAX_VALUE / Int.MAX_VALUE */)
    }
}

enum class ExhaustionKind {
    Steps, StackDepth, AllocatedValues, WallClock,
    JsonDepth, NodeCount, IngestBytes,
}
```

Primitive fields keep the per-step check at one compare-and-branch.

### 4.2 Ingest-time enforcement

`JsonIngest.parse(text, limits = EvaluationLimits.DEFAULTS)`. Three checks fire before pass 1's NodeId allocation:

1. **Byte cap.** `text.length > maxIngestBytes` → `IngestError.ResourceExhaustion(IngestBytes)`.
2. **JSON depth cap.** A linear pre-scan of `{` / `[` nesting runs before invoking kotlinx-serialization (which exposes no depth hook). Counting unescaped braces inside strings conservatively is sufficient for the threat model. Exceeding `maxJsonDepth` raises `IngestError.ResourceExhaustion(JsonDepth)`.
3. **Node count cap.** After parsing the top-level JsonObject: `nodesObj.entries.size > maxNodeCount` → `IngestError.ResourceExhaustion(NodeCount)`.

### 4.3 Tree-walker and VM enforcement

Per-evaluation counter struct, allocated at every public entry point:

```kotlin
internal data class EvalCounters(
    var steps: Long = 0L,
    var currentDepth: Int = 0,
    var allocated: Long = 0L,
    val startNanos: Long = System.nanoTime(),
)
```

The interpreter threads `(counters, limits)` through `eval`, `applyCall`, `applyValue`, `applyClosure`, `applyFixpoint`, `applyForeign`, `evalMatch`, `evalProductValue`, `evalSumValue`. The guard at the head of every `eval`:

```kotlin
counters.steps++
if (counters.steps > limits.maxSteps) throw InterpretException(
    InterpretError.ResourceExhaustion(ExhaustionKind.Steps, id, counters.steps, limits.maxSteps))
counters.currentDepth++
if (counters.currentDepth > limits.maxStackDepth) throw InterpretException(
    InterpretError.ResourceExhaustion(ExhaustionKind.StackDepth, id,
        counters.currentDepth.toLong(), limits.maxStackDepth.toLong()))
if (counters.steps % limits.wallClockSampleEvery == 0L) {
    val elapsedMs = (System.nanoTime() - counters.startNanos) / 1_000_000L
    if (elapsedMs > limits.wallClockBudgetMillis) throw InterpretException(
        InterpretError.ResourceExhaustion(ExhaustionKind.WallClock, id, elapsedMs, limits.wallClockBudgetMillis))
}
try { /* existing when-dispatch */ } finally { counters.currentDepth-- }
```

A helper `counters.allocV(v: Value, limits): Value` wraps each `Value.*` constructor reachable from dispatch — increments `allocated`, raises `ResourceExhaustion(AllocatedValues)` on breach, returns `v`.

The VM's `runLoop` adopts the same guard, using `frames.size` for `StackDepth`. Allocation counting wraps stack `add(Value.*)` sites and closure / fixpoint / foreign construction. The VM's `atNode = null` (opcodes do not carry NodeIds; Q-017 step 2 source-mapping is a follow-up). A new `VmResourceExhaustion(kind, atNode, current, limit)` exception maps at the public boundary to the shared `InterpretError.ResourceExhaustion`. Constant cost per dispatch step is three increments and three compares — near-zero overhead in the non-exhausted hot path.

### 4.4 Defaults and justification

Defaults must (a) pass every existing corpus program under `EvaluationLimits.DEFAULTS`, (b) catch the hostile-graph shapes from § 1, (c) impose no observable performance penalty.

- `maxSteps = 10_000_000` — corpus uses <10K steps; largest async state-machine ~100K. Three orders of magnitude headroom.
- `maxStackDepth = 4096` — corpus depths are <50; default `-Xss` tolerates 4096 dispatch frames.
- `maxAllocatedValues = 1_000_000` — corpus allocations <10K.
- `wallClockBudgetMillis = 30_000` — 30s covers slow production paths (LLM round-trips, async backpressure) while halting indefinite hangs.
- `maxJsonDepth = 512` — deepest legitimate document has ~30 levels.
- `maxNodeCount = 100_000` — corpus programs have <250 nodes each.
- `maxIngestBytes = 64 MB` — typical Strand JSON is KB-scale.

### 4.5 State-machine runtime inheritance

`StateMachineRuntime.runMachine(machine, events, capabilities, limits)` threads `limits` into every per-event `Interpreter.applyCallable` call. The counter is reused across events — one logical run, one budget. `runGroup` allocates one `EvalCounters` per actor (independent per-instance budgets). `resume` allocates a fresh counter (snapshot-resume resets the budget by design).

`SchemaChecker.check` threads `limits` into its invariant-body invocations, defaulting to `EvaluationLimits.DEFAULTS`.

## 5. Verifier rules

**No new verifier rules.** The verifier is admission-time but is not the right enforcement point for resource limits. Two reasons: (1) the verifier must be deterministic across hosts for content-addressing consistency — making verifier behavior depend on host-configurable budgets breaks this; (2) a too-large graph is not malformed, it is too large for the host's budget — the right enforcement layer is the one that holds the budget, outside the verifier.

Ingest-time checks raise the new `IngestError.ResourceExhaustion`. The proposal promotes `IngestError` from its current single-class form (`class IngestError(message: String) : RuntimeException`) to a sealed class with `IngestError.Malformed(message)` (carrying every existing string-based ingest failure) and `IngestError.ResourceExhaustion(kind, current, limit)` for the new shape.

## 6. Interpreter / runtime semantics

Counter threading through every recursive descent is purely additive: existing semantic rules hold unchanged; the counter terminates evaluation with `ResourceExhaustion` whenever a budget is breached. Handler dispatch (`applyValue` invoked from `applyCall` on intercept) participates in the same counter — handler bodies are not separately budgeted. CapabilityScope narrowing does not touch counters.

The bytecode VM's `runLoop` adopts identical semantics at the opcode boundary. Both backends raise the same `InterpretError.ResourceExhaustion` shape on breach; cross-backend equivalence under matched limits is a load-bearing test.

State-machine runtime semantics: `runMachine` shares one `EvalCounters` across its event fold. The per-event closure may exhaust mid-event, in which case the trace ends with `TraceStep.Halt` carrying a new `HaltReason.ResourceExhaustion(kind, atEventIndex)` variant. `runGroup` actors get independent counters per instance. `resume` starts fresh.

## 7. Test scenarios

1. **JSON 100K-deep nesting** → `IngestError.ResourceExhaustion(JsonDepth)`. Reject before any node materializes.
2. **Million-node payload** → `IngestError.ResourceExhaustion(NodeCount)`. Reject at pass 0.
3. **100 MB document body** → `IngestError.ResourceExhaustion(IngestBytes)`. Reject before kotlinx-serialization parses.
4. **Fixpoint loop with no base case** → `InterpretError.ResourceExhaustion(Steps)`. `λ rec _. rec(unit)`; step counter fires before stack overflow.
5. **Pathological Application chain** → `InterpretError.ResourceExhaustion(StackDepth)`. Depth 100K Application nesting; depth counter fires before native stack exhaustion.
6. **Wall-clock exhaustion on productive non-termination.** `λ rec acc. rec(acc + 1)` with `wallClockBudgetMillis = 100`; expect `ResourceExhaustion(WallClock)` near 100ms.
7. **Allocation explosion** → `InterpretError.ResourceExhaustion(AllocatedValues)`. A program that builds a billion-element list; counter fires before `OutOfMemoryError`.
8. **Legitimate factorial(20) under defaults succeeds.** Counter values well under all defaults.
9. **All existing corpus programs pass under `EvaluationLimits.DEFAULTS`.** Zero regressions; load-bearing equivalence test.
10. **`PERMISSIVE` limits accept a benchmarking program that exhausts defaults.** factorial(1000) under `PERMISSIVE`; expect success.
11. **Cross-backend equivalence.** A program allocating 2 million values; both interpreter and VM raise `ResourceExhaustion(AllocatedValues)` under matched defaults.
12. **State-machine runtime honors limits across events.** A 100-event run whose cumulative steps exceed `maxSteps = 1000`; expect exhaustion at the breaching event; final trace carries `HaltReason.ResourceExhaustion`.
13. **Snapshot-resume resets the wall-clock counter.** Snapshot mid-evaluation; resume with the same budget; the resume receives a fresh budget.
14. **Async-group per-actor budgets are independent.** Three actors, one exhausts; the other two continue successfully.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **`maxBytesAllocated` (precise memory tracking).** Requires per-`Value` size accounting at every constructor. The value-count proxy catches the same class of attacks for known-bounded value sizes; bytes-precise tracking lands in a follow-up if measurement shows value-count is insufficient.
- **Dynamic mid-evaluation limit adjustment.** The host cannot adjust the budget once `eval` is running. A clean upgrade path exists by passing a `LimitProvider` rather than `EvaluationLimits`.
- **Per-event budget reset for state-machine runtime.** A future `StateMachineLimits` shape could nest `perEvent` and `perRun`; current design ships `perRun` only.
- **Speculative-execution rollback.** A `ResourceExhaustion` mid-evaluation leaves already-committed side-effecting builtins permanently committed. Transactional rollback over effects is out of scope.
- **Sub-millisecond wall-clock granularity.** Sampling every `wallClockSampleEvery = 1024` steps is the floor; tightening costs more `System.nanoTime()` calls.
- **VM source-node attribution.** The VM reports `atNode = null` because opcodes do not carry NodeIds. A Q-017 step 2 follow-up can add source-mapping.

**Real research questions:**

- *Cost calibration for agent-generated programs at scale.* Defaults are calibrated against the current corpus. Phase 2 evaluation may show systematically different cost profiles. A measurement-driven recalibration after the dynamic-cost harness lands ([`proposals/model-api-integration.md`](model-api-integration.md)) is appropriate.
- *Counter attribution across handlers.* A no-continuation handler replaces an effectful call wholesale; its body's evaluation is on the same step counter. Whether to track handler-attributed and original-call-attributed depth separately is open.
- *Snapshot persistence of counter state.* `resume` currently resets counters. Carrying counter state in `Snapshot` lets the host enforce a continuous budget across resume boundaries; the tradeoff is snapshot-format growth.
- *Defense against time-clock manipulation.* `System.nanoTime()` is monotonic but coarsely-resolved on some JVMs. Finer anti-tampering is out of scope; the threat model assumes the JVM platform is trustworthy.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `core/EvaluationLimits.kt` | New file. `EvaluationLimits` data class, `ExhaustionKind` enum, `DEFAULTS` and `PERMISSIVE` companions. | Small |
| `core/Json.kt` | New `parse(text, limits)` overload. Helper `validateJsonDepth(text, maxDepth)` (linear pre-scan). Promote `IngestError` to a sealed class with `Malformed(message)` and `ResourceExhaustion(kind, current, limit)` variants; migrate every existing `throw IngestError(...)` call to `IngestError.Malformed(...)`. Add the three new ingest-time checks before pass 1. | Medium |
| `interpreter/InterpretError.kt` | New `ResourceExhaustion(at: NodeId?, kind, current, limit)` variant. `at` is nullable to accommodate the VM's missing source-mapping. | Small |
| `interpreter/Interpreter.kt` | Thread `counters` and `limits` through `eval` and every `apply*` / `eval*` helper. Add `EvalCounters.bump` and `allocV` guards. New public overloads `eval(root, capabilities, limits)` and `applyCallable(fn, args, capabilities, limits)`; existing overloads delegate to defaults. | Large |
| `vm/Vm.kt` | Thread `limits` into `runLoop`. Step counter, depth check (`frames.size`), wall-clock sample. Allocation counter at stack `add(Value.*)` and closure / fixpoint / foreign construction. New `VmResourceExhaustion` exception mapped to the shared interpreter error. `evaluate(initialCaps, limits)` and `applyClosure(closure, args, caps, limits)` overloads. | Medium |
| `runtime/StateMachineRuntime.kt` | Thread `limits` into `runMachine`, `runGroup`, `resume`. Per-actor counter allocation in `runGroup`. Add `HaltReason.ResourceExhaustion(kind, atEventIndex)` variant; per-event closure invocation catches `InterpretException` carrying `ResourceExhaustion` and surfaces it as the halt reason. | Small-medium |
| `schema/SchemaChecker.kt` | Thread `limits` into the `Interpreter.applyCallable` calls that evaluate invariant bodies. Default `EvaluationLimits.DEFAULTS`. | Small |
| `cli/` | New flags `--max-steps`, `--max-stack-depth`, `--max-allocated-values`, `--wall-clock-ms`, `--max-json-depth`, `--max-node-count`, `--max-ingest-bytes` on the `run`, `machine`, `group` subcommands. Default to `EvaluationLimits.DEFAULTS`. | Small-medium |
| `core/test/JsonIngestLimitsTest.kt` | New file. Scenarios 1-3 + ingest-defaults pass-through. | Medium |
| `interpreter/test/InterpreterLimitsTest.kt` | New file. Scenarios 4-8 + existing-corpus pass-through. | Medium |
| `vm/test/VmLimitsTest.kt` | New file. Scenario 11 + VM-interpreter equivalence under matched limits. | Medium |
| `runtime/test/StateMachineRuntimeLimitsTest.kt` | New file. Scenarios 12-14 (per-run budgets across events, snapshot-resume reset, async per-actor independence). | Medium |
| `corpus/` | Two negative corpus programs: 69 (Fixpoint with no base case, expects `ResourceExhaustion(Steps)`) and 70 (deeply-nested Application chain, expects `ResourceExhaustion(StackDepth)`). Hash-invariance test: all existing corpus programs hash identically — the proposal touches no node encoding. | Small |

**Order of work.** (1) `EvaluationLimits` + `ExhaustionKind` in `:core`. (2) Promote `IngestError` to sealed class; add ingest-time checks. (3) `InterpretError.ResourceExhaustion` + thread limits through the interpreter. (4) Add limits to the VM. (5) Thread through `StateMachineRuntime` and `SchemaChecker`. (6) CLI flags. (7) Negative corpus programs + test coverage. (8) Validate full corpus passes under `EvaluationLimits.DEFAULTS` with zero regressions.

**Not in this slice.**

- `maxBytesAllocated` (precise memory tracking).
- Dynamic mid-evaluation limit adjustment.
- Per-event budget reset semantics for state-machine runtime.
- Speculative-execution rollback on resource exhaustion.
- VM source-node attribution for diagnostics (waits on Q-017 step 2 source-mapping).
- Snapshot persistence of counter state across `resume` boundaries.
- Cross-machine global budgets in `runGroup`.

## References

**Outgoing references:**
- [`design/security-model.md`](../design/security-model.md) — § Threat model identifies the malicious-AI-agent adversary class this proposal addresses
- [`security-index.md`](../security-index.md) — Finding 2, the audit entry that motivated this proposal
- [`impl-kotlin/core/src/main/kotlin/org/strand/core/Json.kt`](../impl-kotlin/core/src/main/kotlin/org/strand/core/Json.kt) — ingest pass that gains depth, byte, and node-count caps
- [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt) — tree-walking dispatch that gains the step / depth / allocation / wall-clock counters
- [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/InterpretError.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/InterpretError.kt) — new `ResourceExhaustion` variant
- [`impl-kotlin/vm/src/main/kotlin/org/strand/vm/Vm.kt`](../impl-kotlin/vm/src/main/kotlin/org/strand/vm/Vm.kt) — bytecode dispatch loop honors the same limits
- [`impl-kotlin/runtime/src/main/kotlin/org/strand/runtime/StateMachineRuntime.kt`](../impl-kotlin/runtime/src/main/kotlin/org/strand/runtime/StateMachineRuntime.kt) — async actor runtime inherits limits per actor
- [`proposals/foreign-effect-projections.md`](foreign-effect-projections.md) — Q-039, the companion audit-surfaced proposal; both share the 2026-05-26 audit motivation
- [`proposals/model-api-integration.md`](model-api-integration.md) — dynamic-cost evaluation harness whose measurements may recalibrate the default budgets
- [`open-questions.md`](../open-questions.md) — Q-040

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-040 points at this proposal
- [`proposals/README.md`](README.md)
- [`security-index.md`](../security-index.md) — Q-040 row links here
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section
