# Self-gating execution

**Document:** `proposals/implemented/self-gating-execution.md`
**Status:** Implemented 2026-07-04 (commit `e066909` on `reason-first`) for the plain `run` path; `runMachine`/`runGroup` gating and the `strand run --budget` CLI mode are deferred
**Question:** [Q-073](../../open-questions.md#Q-073) (registers)
**Related:** [ADR-010](../../decisions/ADR-010-reasoning-surface.md), [`reasoning-api.md`](reasoning-api.md) (Q-072), [Q-044](../../open-questions.md#Q-044), [Q-054](../../open-questions.md#Q-054), N-036 CapabilityScope, [Q-068](../../open-questions.md#Q-068), [Q-064](../../open-questions.md#Q-064)
**Drafted:** 2026-07-04

## Problem

The fourth commitment of [ADR-010](../../decisions/ADR-010-reasoning-surface.md) is that a generating agent may gate its own execution on the computed bound — the write, reason, decide, run loop closed within a single principal. The reasoning API ([Q-072](../../open-questions.md#Q-072)) supplies `harmBound` and `totalClosure`; the run facade ([Q-054](../../open-questions.md#Q-054)) executes; but nothing joins them into a single operation that computes the pre-execution bound and refuses to dispatch a program whose bound exceeds a declared budget. A host must currently call `analyze`, compare by hand, and conditionally `run` — which is easy to get subtly wrong, most consequentially by forgetting the latent channel, so that a tool-carrying program whose latent reach exceeds budget runs anyway and is stopped only mid-execution by the runtime capability check. The self-gate makes the pre-execution refusal a first-class, atomic facade operation, and includes the latent closure so a program is refused before execution when what it could do — directly or through its tools and callbacks — exceeds budget.

## Recommended approach

A facade operation on `StrandRuntime`: `runGuarded(image, policy, budget): GuardedOutcome`.

The `budget` is the permitted effect surface, expressed as the existing `CapabilitySet` grant type (categories plus refinement patterns), so the same value both bounds the pre-execution check and, if the program runs, serves as the runtime capability context — the enforcement backstop.

The pre-execution gate verifies the image, computes `totalClosure = rootClosure ∪ rootLatentClosure` through `ProgramAnalysis`, and compares its EffectCategory set against the budget's granted categories. If the total closure contains a category the budget does not grant, the operation refuses: it returns `Refused(RefusalReport)` without executing anything. The `RefusalReport` names the exceeding categories, marks whether each was reached through the direct or the latent channel, and records requested-versus-budget — the structured reason an agent or host acts on.

If the program is within budget, it runs under the budget as its `CapabilitySet`, with runtime refinement enforcement remaining the backstop for the parameter-level bounds the category gate does not statically prove, and returns `Ran(RunOutcome)`. The outcome type is `GuardedOutcome = Ran(RunOutcome) | Refused(RefusalReport) | VerifyFailed(errors)`.

The pre-execution refusal is distinct in kind from the runtime capability denial ([Q-064](../../open-questions.md#Q-064)): the denial fires during execution at the offending call, after the program has begun and possibly performed other effects; the self-gate refuses the whole program up front, before any effect. It generalizes the N-036 CapabilityScope static check — `CapabilityScopeUnsatisfiable` proves a subgraph's effect closure exceeds a retained set at admission — to the whole program at the run boundary, and extends it to the latent channel so indirectly-invoked reach is gated as well. The gate is category-level, matching the CapabilityScope static-proof granularity; refinement-level parameter bounds stay with runtime enforcement. A generating agent gates its own emission by calling `runGuarded` with a budget it declares for itself, which is the closed loop ADR-010 names.

## Where it lives

The `runGuarded` method on the `StrandRuntime` facade (`:runtime`), built on `ProgramAnalysis` (`:verifier`) and the existing run path, with new `GuardedOutcome` and `RefusalReport` result types. An optional `strand run --budget <categories>` CLI mode that refuses an over-budget program before execution is recommended, but the facade operation is the deliverable and the CLI is a thin client over it.

## Deferred and open

- Refinement-level (parameter) pre-execution proof beyond category gating needs either the CapabilityScope refinement-narrowing work ([Q-068](../../open-questions.md#Q-068)) or a dedicated static refinement proof; runtime enforcement backstops it for now.
- A budget expressed as [Q-044](../../open-questions.md#Q-044) harm classes rather than effect categories is a presentation layer over the same computation and is deferred.
- In-language self-gating — a program branching on its own bound as a first-class `Value` — is explicitly not pursued. Capabilities and bounds are host-mediated, not in-language values, consistent with Q-064's deliberate non-observability of denials in-language; the gate is a host/facade operation the agent calls, not a node category.

## Scope

Small. A facade method and two outcome types over the existing analysis and run paths, plus tests. Hash-neutral; no runtime-semantics, encoding, or node-algebra change.

## References

**Outgoing references:**
- [`decisions/ADR-010-reasoning-surface.md`](../../decisions/ADR-010-reasoning-surface.md) — the self-gating commitment this realizes
- [`implemented/reasoning-api.md`](reasoning-api.md) — the `harmBound` / `totalClosure` the gate consumes
- [`open-questions.md`](../../open-questions.md) — Q-073 (registered), Q-044, Q-054, Q-068, Q-064

## Implementation note

Built on branch `reason-first` in `impl-kotlin/`, on top of the just-landed
`ProgramAnalysis` reasoning API. A facade composition over verify + analyze +
run; hash-neutral and side-effect-free on the refusal path. The interpreter,
VM, canonical encoder, epoch constant, prelude, and `ProgramAnalysis`'s query
logic are untouched. `CorpusGoldenHashTest` moved no hash (117 tests, 0
failures).

Method (`runtime/src/main/kotlin/org/strand/runtime/StrandRuntime.kt`, in
`:runtime`):

```
fun StrandRuntime.runGuarded(program: ProgramImage, budget: CapabilitySet): GuardedOutcome
```

`budget` mirrors the existing `run(program, capabilities: CapabilitySet)`
convention — `StrandRuntime` already carries the `HostPolicy` as a
constructor field, so unlike the proposal's illustrative `(image, policy,
budget)` sketch, no separate `policy` parameter is needed; the method takes
exactly the two parameters `analyze` and `run` each take, one of image and one
of capability/query input.

Outcome and report types (same file, alongside `RunOutcome` / `AnalysisOutcome`):

```
sealed class GuardedOutcome {
    data class Ran(val outcome: RunOutcome) : GuardedOutcome()
    data class Refused(val report: RefusalReport) : GuardedOutcome()
    data class VerifyFailed(val errors: List<VerifyError>) : GuardedOutcome()
}

enum class EffectChannel { DIRECT, LATENT, BOTH }

data class RefusalReport(
    val exceeding: Map<NodeId, EffectChannel>,
    val requested: Set<NodeId>,
    val granted: Set<NodeId>,
)
```

`exceeding` is keyed by the exceeding EffectCategory NodeId (structural, not
prose) to the channel it was reached through — `DIRECT` (in `rootClosure`),
`LATENT` (in `rootLatentClosure` but not `rootClosure`), or `BOTH`. `requested`
is the program's full `totalClosure(root)` and `granted` is `budget.grants.keys`,
both carried in full (not just the exceeding subset) so the report is
self-contained: a caller can recompute `requested - granted`, diff two
refusals, or render a complete picture without a second call into
`ProgramAnalysis`.

Implementation: verify the image (the existing `verify` path); on failure
return `VerifyFailed`. On success, build a `ProgramAnalysis` exactly as
`analyze` does and read `rootClosure` / `rootLatentClosure` from the
`VerifyResult.Ok` plus `totalClosure(root)` from the `ProgramAnalysis` (used
verbatim, not re-derived). Compare `totalClosure - budget.grants.keys`; a
non-empty difference returns `Refused` with the per-category channel
classification, *before* `run` is invoked — no effect occurs. An empty
difference calls the existing `run(program, budget)` path, so runtime
refinement enforcement remains the backstop for parameter-level bounds this
category-level gate does not statically prove, and returns `Ran`.

Deviations:
- **Only the plain `run` entry point is guarded.** `runMachine` / `runGroup`
  gating is deferred — each has a materially different shape (an explicit
  `machine` NodeId plus an event list, or an async `MachineGroup` with a
  `CoroutineScope`) where "the whole program" is ambiguous (gate one machine?
  the whole group's reachable closure?) and is more than the small addition
  the proposal's own scope calls for. `runGuarded` composes with `run` alone;
  a state-machine-shaped self-gate is future work under its own item.
- **Category-level only**, per the proposal: no refinement-level (parameter)
  pre-execution proof. Refinement enforcement stays with the runtime
  (`RefinementViolation`) as the mid-execution backstop; Q-068 is the
  refinement-narrowing extension this would need.

Tests: `runtime/src/test/kotlin/org/strand/runtime/StrandRuntimeRunGuardedTest.kt`
(4 tests) — (a) a `Time.Now` program within budget runs and returns `Ran` with
its `IntV` value; (b) a program that directly performs `Filesystem.Write`
(reaches the walked `Application`) under an empty budget is `Refused` before
execution with the category marked `DIRECT`, and the test asserts the target
file was never created; (c) the effectful-ToolDef fixture (shared shape with
`LatentEffectClosureTest`/`ProgramAnalysisTest`) — a `Generate` call handed a
`Filesystem.Write`-implementing `ToolDef` — under a budget granting only
`LLM.Generate` is `Refused` with `Filesystem.Write` marked `LATENT`, the key
case a direct-closure-only gate would miss; (d) a non-verifying program
(`Application` of a non-function) returns `VerifyFailed`. All 4 pass.

Full suite after this change: 2419 tests, 3 skipped, 0 failures (up from 2415
in the reasoning-api implementation note by exactly these 4 new tests). No
golden hash moved.
