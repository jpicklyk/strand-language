# Runtime schema enforcement (Layer 7 step 2)

**Document:** `proposals/implemented/runtime-schema-enforcement.md`
**Status:** Implemented (foundational slice landed 2026-06-03; § 8 deferred items remain)
**Date:** 2026-06-03 (drafted and foundational slice implemented)
**Concerns:** [`decisions/ADR-009-structured-outputs.md`](../../decisions/ADR-009-structured-outputs.md), [`design/rendering-and-views.md`](../../design/rendering-and-views.md) § schema-mechanism / § trust-model / § blessed-libraries, [`proposals/implemented/schema-and-invariant.md`](schema-and-invariant.md) (Layer 7 step 1), Q-035 (deferred scope), Q-026 (blessed libraries), Q-006 (foreign-binding trust), Q-040 (`EvaluationLimits`), Q-044 (containment framing)
**Scope:** small (foundational slice — runtime pure-expression invariant enforcement in the interpreter); medium-large if ForeignNode-backed checkers or the HTML5/SVG/PDF blessed-library set are taken up in the same pass

This proposal is the second step of Layer 7. Layer 7 step 1 ([`schema-and-invariant.md`](schema-and-invariant.md), Q-035) shipped the Schema (N-032) and Invariant (N-033) node categories, the first-class `SchemaType` type, and a verify-time `SchemaChecker` that evaluates pure-expression invariants over *statically-known* values. This proposal lifts that evaluation to *runtime on dynamic values*, closing the gap step 1 records as the non-enforcing `SchemaInvariantDeferred` diagnostic. It does not add a node category, an ADR, or an effect-category identifier.

## 1. Problem statement

The step-1 `SchemaChecker` decides invariants only for values it can fold statically — literals, and product/sum/`Let`/`VarRef`/`NodeRef` towers over literals (the recursive "statically known" definition in `SchemaChecker.tryEvaluateStaticallyInScope`). Every other schema-typed value — a function parameter, an `Application` result, a `Match` branch, a foreign-call return, anything computed at runtime — is surfaced as a `VerifyError.SchemaInvariantDeferred` informational diagnostic whose default disposition is *surface but do not fail*. The graph verifies; the invariant is never checked.

The consequence: the schema mechanism today guarantees nothing about a value a program computes at runtime, which is precisely the class of value an agent's generated program most often flows into a schema position. A program that constructs a `PositiveInt`-schema'd value from `Int.Sub(a, b)` where `b > a`, or builds a `UniqueKeyJsonObject` from entries assembled in a `Fixpoint`, passes verification and produces an invariant-violating value with no error. ADR-009 § Decision and `rendering-and-views.md` § schema-mechanism describe the verifier rejecting malformed values "at graph-construction time" and a runtime checker-dispatch protocol; step 1 implements neither for dynamic values. This is the runtime half.

The verifier already does the load-bearing bookkeeping: when a plain-`T` value flows into a `SchemaType<T>` position, it re-records that value's NodeId with the `SchemaType` (the `typesCompatible` relaxation at `Application` argument, `ProductFieldValue`, and `SumValue` payload sites — see step 1 § verifier integration). The obligation sites are therefore already identified in `VerifyResult.Ok.nodeTypes`; what is missing is a runtime that *acts* on them.

## 2. Prior art

- **Refinement types (Liquid Haskell, F\*, Dafny).** Predicates attached to types, discharged statically by an SMT solver. ADR-009 § Alternatives rejected full refinement typing as exceeding the reference implementation's scope; the schema mechanism is the constrained subset (pure Strand predicates, no decision procedure). The present step keeps that boundary: invariants are evaluated, not solved.
- **Contracts / `assert` at boundaries (Eiffel, Racket contracts, Clojure `:pre`/`:post`).** A predicate checked when a value crosses a boundary (function entry/exit, module interface), failing at runtime with a structured blame report. Runtime schema enforcement is exactly this shape — the "boundary" is the verifier-identified value-flow site, the "blame" is the `at` NodeId — but the predicate set is declared once on the Schema rather than per call site, and the static half (step 1) discharges what it can ahead of time.
- **Validated parsing / "parse, don't validate."** A value of a refined type is constructed through a checked smart-constructor so the refinement holds by construction. Strand's content-addressed values have no constructors to hook, so the check attaches to the *use site* (the schema position) rather than the construction site — the verifier's re-recording is what makes the use sites enumerable.
- **JSON Schema / OpenAPI runtime validators.** Structural predicates over a document, evaluated at an API boundary. The blessed-library extension (§ 8) targets this directly; the foundational slice provides the evaluation engine those libraries' invariants run on.
- **Sound-but-incomplete static analysis with a runtime backstop (gradual typing, TypeScript `--strict` + runtime guards).** The exact discipline here: the static pass (step 1) is sound but incomplete — it rejects what it can prove false and passes the rest — and a runtime check makes the combined system complete at runtime. A schema violation is therefore caught at verify time or at runtime, never silently emitted.

## 3. Recommended approach

Thread the verifier's schema obligations into the interpreter and evaluate them when the obligated value materialises. Concretely:

1. The host derives a **schema-obligation map** from `VerifyResult.Ok.nodeTypes`: every NodeId whose recorded type is a `TypeExpr.SchemaType` maps to that `SchemaType` (carrying `schemaId` and the invariant NodeId list). This is the set of value-flow sites the verifier already re-recorded.
2. The interpreter is constructed with this map. At the single exit of its node-dispatch `eval` — after a NodeId has been reduced to a `Value` — it consults the map; if the NodeId carries an obligation, it evaluates each of the schema's pure-expression invariant bodies against the produced `Value`.
3. An invariant returning `Value.BoolV(false)` raises a new recoverable `InterpretError.SchemaInvariantViolation(at, schema, invariant, valueDescription)` — the runtime analogue of the verifier's `VerifyError.SchemaInvariantViolation`, carrying the obligation-site NodeId for blame.

Invariant bodies are evaluated exactly as the step-1 `SchemaChecker` evaluates them — looked up as `Node.Invariant`, the body `eval`'d to a callable, applied to the value via `applyCallable` under an **empty** capability context (the body is pure by the step-1 `SchemaInvariantBodyMustBePure` rule) and the run's `EvaluationLimits` (Q-040). The interpreter and the `SchemaChecker` thus share one evaluation semantics; the only difference is *when* (verify time over a static value vs. runtime over the actual value) and *what is done on failure* (a verify error that rejects the graph vs. a runtime error that halts the run).

This is opinionated on five points; rationale and rejected alternatives are in § 8:

- **The check fires at the verifier-identified value-flow site, not at a new explicit cast node.** Reuses step 1's re-recording; no new surface for the agent to remember to insert.
- **Invariant bodies stay pure; ForeignNode-backed checkers are a deferred slice.** The foundational slice changes no verifier rule.
- **Enforcement is interpreter-only in this slice; the bytecode VM is a deferred slice.** The VM erases schemas pre-bytecode (Q-017), so VM parity needs a lowering, not just a flag.
- **Static sites are re-checked at runtime (redundantly) rather than skipped.** A verify-passing program has already passed every static obligation, so the redundant runtime check always succeeds; skipping it is a pure optimization deferred to a later pass that threads the `SchemaChecker`'s statically-resolved set.
- **A runtime violation halts with a structured, recoverable error**, surfaced like `IoFailure` / `SandboxViolation` — agents see `InterpretError.SchemaInvariantViolation`, not a crash.

## 4. Detailed mechanism

No new node category, ADR, or effect-category identifier. No canonical-encoding change (schema obligations are derived from the verifier's type map, which is not part of any node's content), so every existing program hash is preserved.

### 4.1 Schema-obligation map

A `Map<NodeId, TypeExpr.SchemaType>` built by filtering `VerifyResult.Ok.nodeTypes` to its `SchemaType` entries. `TypeExpr.SchemaType` already carries `schemaId: NodeId`, `valueType: TypeExpr`, and `invariants: List<NodeId>` (Invariant NodeIds). The map keys are the value-flow-site NodeIds the verifier re-recorded. The interpreter module already references `TypeExpr` (the dependency `interpreter → verifier → core` holds, and `Builtins.verifierNodeTypes: Map<NodeId, TypeExpr>?` already exists), so no new module wiring is needed.

### 4.2 Interpreter threading

`Interpreter` gains an optional constructor parameter `schemaObligations: Map<NodeId, TypeExpr.SchemaType> = emptyMap()`, alongside the existing `store` / `hashToNodeId` / `foreignDispatcher` / `resolveTarget`. Default empty preserves every existing call site (the runtime, schema, and test constructors that pass no obligations enforce nothing — unchanged behaviour).

At the exit of the internal node-dispatch `eval(id, env, context, handlers, counters, limits)`, after the `Value` for `id` is computed, the interpreter checks `schemaObligations[id]`. If present, for each `invariantId` in the obligation's `invariants`: resolve `store.get(invariantId)` as `Node.Invariant`, `eval` its `body` to a callable under `CapabilitySet.EMPTY` + the threaded `counters`/`limits`, `applyValue` it to the just-computed value, and if the result is `Value.BoolV(false)` throw `InterpretError.SchemaInvariantViolation`. A non-`Bool` result is an internal invariant (the verifier's `SchemaInvariantBodyTypeMismatch` rule guarantees `(valueType) -> Bool`), handled with the same defensive `error(...)` the `SchemaChecker` uses.

To avoid unbounded re-entrancy (an invariant body is itself a graph evaluated through the same `eval`, and its sub-nodes could in principle carry obligations), invariant-body evaluation runs with obligation-checking suppressed — the body is verified pure and `(valueType) -> Bool`, and re-checking obligations inside it would conflate the predicate's internal structure with the value under test. The suppression is a thread of an `inInvariant: Boolean` flag (or, equivalently, evaluating the body through an interpreter view with an empty obligation map).

### 4.3 Error variant

```
InterpretError.SchemaInvariantViolation(
    at: NodeId?,            // the obligation site (value-flow position)
    schema: NodeId,         // the Schema whose invariant failed
    invariant: NodeId,      // the specific Invariant
    valueDescription: String,  // the offending value's toString, scrubbed via Q-042's discipline if it reaches an IO surface
)
```

Recoverable, mirroring `VerifyError.SchemaInvariantViolation`. Surfaced through the same `InterpretException` path as `IoFailure` / `SandboxViolation` / `ResourceExhaustion`, so existing CLI and runtime catch sites report it uniformly.

### 4.4 Host wiring

The CLI `run` path (and, by extension, `machine` / `group` for schema-typed machine values in a later slice) builds the obligation map from the `VerifyResult.Ok` it already holds and passes it to the `Interpreter` constructor. The `runSchemaCheck` verify-time pass is unchanged — it still rejects statically-provable violations before any evaluation, so the runtime check only ever fires for the dynamic remainder.

### 4.5 Worked example

A program with a `PositiveInt` schema (`valueType = Int`, one invariant `(n) -> Int.Gt(n, 0)`), a function `f: (Int, Int) -> PositiveInt` whose body is `Int.Sub(a, b)`, applied at `f(3, 5)`:

1. Verification: the `Application` argument flowing into the `PositiveInt` parameter is dynamic (`Int.Sub` is non-static), so step 1 records a `SchemaInvariantDeferred` and the graph verifies. The verifier re-records the `Int.Sub` Application's result position with `SchemaType(PositiveInt)`.
2. The host builds the obligation map: the relevant NodeId → `SchemaType(PositiveInt)`.
3. Runtime: the interpreter evaluates the body to `IntV(-2)`. At the obligation site it evaluates `(n) -> Int.Gt(n, 0)` on `IntV(-2)` → `BoolV(false)` → raises `InterpretError.SchemaInvariantViolation(at = <site>, schema = PositiveInt, invariant = <pos>, valueDescription = "IntV(-2)")`.

The same program with `f(5, 3)` evaluates the invariant on `IntV(2)` → `BoolV(true)` → runs clean to the `PositiveInt`-typed result.

## 5. Verifier rules

The foundational slice adds **no** new verifier rule and changes none. The step-1 rules stand: `SchemaInvariantBodyMustBePure`, `SchemaInvariantBodyMustBeMonomorphic`, `SchemaInvariantBodyTypeMismatch`, `SchemaInvariantViolation` (static), `SchemaInvariantDeferred` (now backed by runtime enforcement rather than being purely informational — its prose disposition is updated to "enforced at runtime by the interpreter; informational at verify time"). The deferred ForeignNode-backed-checker slice (§ 8) is where `SchemaInvariantBodyMustBePure` is relaxed to an admitted-checker form, with its own admission rule and trust check.

## 6. Interpreter / runtime semantics

- **Obligation fire:** at `eval` exit for an obligation-site NodeId, evaluate each invariant against the produced value; `false` → `InterpretError.SchemaInvariantViolation`. Multiple invariants on one schema are checked in declaration order; the first failure blames its invariant.
- **Purity / capabilities:** invariant bodies evaluate under `CapabilitySet.EMPTY` (pure by verifier rule). No capability is consumed by an invariant check.
- **Resource limits (Q-040):** invariant evaluation runs under the same threaded `EvalCounters` and `EvaluationLimits` as the surrounding run, so a pathological invariant body cannot escape the budget. (The `SchemaChecker` uses a fresh counter per invariant at verify time; at runtime the surrounding run owns the budget, so sharing it is the correct accounting.)
- **Determinism / replay:** invariant checks are pure and deterministic, so they do not affect replay determinism — a state-machine trace is unchanged by the presence of obligation checks (a violation halts the run identically on replay).
- **Performance:** one map lookup per `eval` (a single `HashMap.get`), plus one predicate evaluation per obligation-site reduction. Non-schema programs (empty obligation map) pay only the lookup, which is below the dispatch noise floor.

## 7. Test scenarios

1. **Dynamic violation caught at runtime** — a schema-typed value computed via `Application` (`Int.Sub` to a negative) raises `InterpretError.SchemaInvariantViolation`; step 1 had deferred it.
2. **Dynamic pass runs clean** — the same program with arguments that satisfy the invariant returns the schema-typed value with no error.
3. **Function-parameter obligation** — a function with a `SchemaType` parameter, applied to a dynamic argument that violates: the violation blames the argument's value-flow site.
4. **Product-field obligation** — a `ProductValue` whose field is declared `SchemaType<T>` and whose field value is dynamic and violating raises at the field site.
5. **Sum-payload obligation** — a `SumValue` whose case payload is `SchemaType<T>` and dynamic-violating raises at the payload site.
6. **Multiple invariants** — a schema with two invariants; a value violating the second raises blaming the second invariant.
7. **Static violation still rejected at verify time** — a statically-known violating value is still caught by the step-1 `SchemaChecker` (verify fails; runtime never reached) — regression guard that step 2 does not weaken step 1.
8. **No obligations, no overhead** — a non-schema program runs identically (behavioural regression guard over the corpus).
9. **Invariant body honours resource limits** — a deliberately expensive invariant body surfaces `ResourceExhaustion` rather than running unbounded.
10. **Corpus exemplar** — a runnable program that constructs a dynamic schema-checked value and runs to the checked result (pass case), plus a sibling that raises the runtime violation (fail case).

## 8. Tradeoffs, recorded decisions, and deferred work

**Deferred intentionally (under this identifier):**

- **ForeignNode-backed checkers.** `rendering-and-views.md` § schema-mechanism specifies invariant bodies that are registered host checkers, for predicates needing arithmetic, external knowledge, or whole-tree traversal awkward as a pure body. This relaxes `SchemaInvariantBodyMustBePure` to an admitted-checker form and inherits the Q-006 foreign-binding trust model (signed provenance, reproducible builds, sandboxed execution — `rendering-and-views.md` § trust-model). Deferred because it carries a trust-surface design of its own; the pure-expression engine ships first and the foreign form layers on top.
- **Blessed-library extension (HTML5, SVG, PDF).** JSON / PlainText / Markdown already landed under Q-026. HTML5 and SVG are blocked on the nested-recursive-self pattern (the same blocker recorded for the JSON nested-μ case in `impl-kotlin/CLAUDE.md`) — an `HtmlElement` tree is a mutually-recursive type that the current single-μ RecursiveSelf cannot express at value-construction sites — and wait on a richer recursive-binder protocol or the single-big-sum workaround. PDF is a binary serialization target deferred separately. These are library work on top of the runtime engine this slice provides.
- **Bytecode-VM runtime enforcement.** The VM erases schemas pre-bytecode (Q-017 step 1), so the foundational slice enforces invariants in the tree-walking interpreter only. A program that violates an invariant at runtime therefore fails under the interpreter but not the VM — a bounded divergence (only runtime-violating programs, which are error cases) documented as a known limitation. VM parity needs a `CHECK_SCHEMA`-style opcode emitted at obligation sites during lowering, or a verifier-injected check; either is a follow-up. VM-equivalence corpus tests avoid runtime-violating programs until then.
- **Schema-typed state-machine state and events.** `rendering-and-views.md` § live-views describes a live view as a machine whose per-transition rendering satisfies a schema on every reachable state. Runtime validation of machine state/output values is a follow-up that threads obligations through the `runtime/` actor path (the `machine` / `group` CLI commands), built on this slice's interpreter enforcement.

**Recorded decisions (settled here; alternative rejected):**

- *Check at the verifier-identified value-flow site, not a new explicit cast node.* Rejected adding a `CheckSchema(value, schema)` node or builtin: it would duplicate the information the verifier's re-recording already carries and would require the agent to remember to insert it, defeating the "the verifier rejects malformed outputs by construction" property ADR-009 § Consequences promises. The use-site model attaches the obligation exactly where the type system says the value must satisfy the schema.
- *Static sites re-checked at runtime, not skipped.* A verify-passing program has already passed every statically-decided obligation, so the runtime re-check is redundant-but-always-true; skipping it requires threading the `SchemaChecker`'s resolved set into the interpreter, a pure optimization deferred until profiling shows it matters.
- *Interpreter-only enforcement in this slice.* The VM schema-erasure (Q-017) means VM parity is a lowering problem, not a flag; bundling it would expand the slice past its load-bearing core (runtime enforcement at all).

**Known limitation:**

- *Interpreter/VM divergence on runtime violations* until the VM `CHECK_SCHEMA` lowering lands — a program that violates an invariant at runtime fails under the interpreter and runs under the VM. Bounded to error cases; documented and guarded by keeping runtime-violating programs out of the VM-equivalence corpus set.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `interpreter/InterpretError.kt` (or wherever the sealed hierarchy lives) | Add `SchemaInvariantViolation(at: NodeId?, schema: NodeId, invariant: NodeId, valueDescription: String)` | Small |
| `interpreter/Interpreter.kt` | Add `schemaObligations: Map<NodeId, TypeExpr.SchemaType> = emptyMap()` constructor param; obligation check at `eval` exit; `inInvariant` suppression flag; helper mirroring `SchemaChecker.evaluateInvariant` | Medium |
| `cli/Main.kt` | Build the obligation map from `VerifyResult.Ok.nodeTypes` and pass it to the `run`-path `Interpreter` | Small |
| `interpreter` tests (`RuntimeSchemaEnforcementTest`) | Scenarios 1–9 | Medium |
| `corpus/<NN>-runtime-schema-*` (+ a pass/fail pair) | Scenario 10; `CorpusTest` + a dedicated runtime-violation test | Small |

**Order of work.** (1) `InterpretError.SchemaInvariantViolation`. (2) `Interpreter` obligation threading + check + suppression. (3) CLI wiring. (4) Tests + corpus. (5) Doc updates (`impl-kotlin/CLAUDE.md`, `proposals/README.md`, move to `implemented/`, mark Q-047 resolved-at-foundational-slice). The deferred slices (ForeignNode checkers, blessed libraries, VM enforcement, machine values) are out of this slice, each listed in § 8 with its unblocker.

This is best executed with the `strand-add-node`-adjacent workflow only insofar as it touches the interpreter and verifier modules; it adds **no** node category, so neither `strand-add-node` nor `strand-add-builtin` applies cleanly — it is a focused interpreter/error-hierarchy extension.

## Implementation note (2026-06-03)

The foundational slice landed in the Kotlin/JVM reference implementation. Files touched: `interpreter/InterpretError.kt` (new `SchemaInvariantViolation(at, schema, invariant, valueDescription)` variant), `interpreter/Interpreter.kt` (new `schemaObligations: Map<NodeId, TypeExpr.SchemaType> = emptyMap()` constructor param, an `inInvariant` re-entrancy guard, the `checkSchemaObligations` / `evaluateInvariantBody` helpers, and the obligation check hooked at the single `eval` exit), `cli/Main.kt` (the `run` path builds the obligation map from `VerifyResult.Ok.nodeTypes` and passes it to the interpreter). New corpus 82 (`runtime-schema-dynamic-pass`, runnable → `IntV(2)`) and 83 (`runtime-schema-dynamic-violation`, runtime `SchemaInvariantViolation`). New `CorpusRuntimeSchemaTest` (3 cases: dynamic pass enforced clean, dynamic violation raises, and the no-obligations-installed regression guard). Full `gradle test --rerun-tasks --no-build-cache` green: 1154 tests, 0 failures, 1 skipped (the pre-existing Windows symlink fixture). Hash invariance preserved — no node encoding changed; obligations derive from the verifier's type map, which is not part of any node's content.

Deviations and notes worth recording:

1. **The obligation check fires at the generic `eval` exit, keyed on the verifier's re-recorded NodeIds.** The `eval` `return when (node) {...}` was restructured to `val result = when (...); checkSchemaObligations(id, result, ...); return result` inside the existing `try { } finally { currentDepth-- }`. One `HashMap.get` per `eval` when obligations are installed; nothing when they are not (the default-empty map short-circuits). This reuses step 1's value-flow re-recording rather than introducing a cast node (§ 8 recorded decision).
2. **Static obligation sites are re-checked at runtime (redundantly).** A verify-passing program has already cleared every statically-decided obligation, so the runtime re-check on those sites always succeeds; skipping them would need the `SchemaChecker`'s resolved set threaded in, deferred as a pure optimization (§ 8).
3. **Invariant bodies evaluate with obligation-checking suppressed** via the `inInvariant` flag, so a predicate's internal structure (or a nested schema reached inside it) is never mistaken for the value under test. The body runs under `CapabilitySet.EMPTY` (pure by the step-1 `SchemaInvariantBodyMustBePure` rule) and shares the surrounding run's `EvalCounters` / `EvaluationLimits` (Q-040), so a pathological predicate cannot escape the budget.
4. **Interpreter-only enforcement; the bytecode VM is unchanged.** The VM erases schemas pre-bytecode (Q-017), so a runtime-violating program raises under the interpreter but runs under the VM — a bounded divergence (error cases only), documented in § 8. The non-violating value path *is* engine-equivalent: corpus 82's `PositiveInt` Schema/Invariant nodes are reachable only through the parameter's type edge (erased at lowering), so the value path lowers and runs to `IntV(2)` under both engines. Corpus 82 is therefore added to `VmEquivalenceTest`, and `CorpusRuntimeSchemaTest` cross-checks that the interpreter-WITH-obligations result agrees with the VM for the pass case and pins the violation-case divergence (the VM runs corpus 83 to `IntV(-2)` — a guard that will fail and prompt an update once a `CHECK_SCHEMA` lowering closes the gap). The *runtime-violating* program (corpus 83) stays out of `VmEquivalenceTest` by design.
5. **`CorpusTest` is unchanged and installs no obligations.** Corpus 82 runs there as an ordinary program (to `IntV(2)`); the enforcement path is exercised only by `CorpusRuntimeSchemaTest`, which builds the obligation map exactly as the CLI `run` path does. Corpus 83 is intentionally absent from `CorpusTest` (it is the violation case, meaningful only with obligations).
6. **The step-1 `SchemaChecker` and verifier rules are untouched.** Verify-time still rejects statically-provable violations early (the corpus 51/53/56 reject cases stand); `SchemaInvariantBodyMustBePure` / `…Monomorphic` / `…TypeMismatch` are unchanged. The `SchemaInvariantDeferred` diagnostic is now backed by runtime enforcement rather than being purely informational.

The § 8 deferred extensions — ForeignNode-backed checkers (trust model via Q-006), the HTML5 / SVG / PDF blessed-library set (HTML5 / SVG blocked on nested-recursive-self), bytecode-VM runtime enforcement, and schema-typed state-machine validation — remain open under Q-047.

## References

**Outgoing references:**
- [`decisions/ADR-009-structured-outputs.md`](../../decisions/ADR-009-structured-outputs.md) — the schema mechanism and the construction-time-rejection promise this slice extends to runtime
- [`design/rendering-and-views.md`](../../design/rendering-and-views.md) — verifier extension protocol, ForeignNode-checker dispatch, trust model, blessed-library set, live views
- [`proposals/implemented/schema-and-invariant.md`](schema-and-invariant.md) — Layer 7 step 1, the static `SchemaChecker` and the `SchemaInvariantDeferred` disposition this resolves
- [`open-questions.md`](../../open-questions.md) — Q-035 (deferred scope), Q-026 (blessed libraries), Q-006 (foreign-binding trust), Q-040 (`EvaluationLimits`), Q-044 (containment framing)

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-047 points at this proposal
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — Known gaps / drafted proposals
