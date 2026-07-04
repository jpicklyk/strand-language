# Latent effect surfacing

**Document:** `proposals/latent-effect-surfacing.md`
**Status:** Draft
**Question:** [Q-070](../open-questions.md#Q-070) (resolves, adopting candidate direction 1), [Q-071](../open-questions.md#Q-071) (registers the higher-order-callback instance)
**Related:** [ADR-010](../decisions/ADR-010-reasoning-surface.md), [ADR-004](../decisions/ADR-004-effects-as-edges.md), [Q-067](../open-questions.md#Q-067), [Q-044](../open-questions.md#Q-044), N-044 ToolDef, [Q-039](../open-questions.md#Q-039)
**Drafted:** 2026-07-04

## Problem

The static effect closure — `VerifyState.nodeClosures`, surfaced on the verify result as `rootClosure` ([Q-067](../open-questions.md#Q-067)) — is the pre-execution harm bound of [Q-044](../open-questions.md#Q-044) and the value the reasoning surface of [ADR-010](../decisions/ADR-010-reasoning-surface.md) reports. It is sound for directly-invoked effects, which reach the closure through the Application edges the verifier walks. It is incomplete for effects reachable only through indirect invocation, of which there are two instances of one shape.

The first is the N-044 ToolDef ([Q-070](../open-questions.md#Q-070)): a ToolDef bundles a parameter Schema with an implementation the model may choose to invoke during a Generate call. The implementation can carry effects — a tool whose body performs `Filesystem.Write`. Because the tool is invoked by the model at runtime rather than through an Application the verifier walks, the implementation's effects are absent from the program's root closure. A program that constructs a file-writing tool and hands it to Generate has root closure `{LLM.Generate}`, identical to a benign program's.

The second is the higher-order callback, which Q-070's own text names as the recurring shape: an effectful value — a projected ForeignNode, or an effectful Lambda — passed as an argument to a higher-order builtin such as `List.Map`, `List.Fold`, or `List.Filter`, then invoked inside the builtin. Its effects reach no Application the verifier walks, so they too are absent from the closure. This instance has no identifier of its own and is registered here as Q-071.

In both cases the runtime capability check remains sound: the latent effect is denied at dispatch if its category is ungranted, so no run exceeds the enforced bound. What is incomplete is the pre-execution bound — and ADR-010 makes that completeness load-bearing, because a reasoning surface that under-reports what an agent-shaped program can reach is not a conservative approximation a host can gate a decision on, it is a false negative for exactly the constructs agent programs use most.

## Recommended approach

Adopt Q-070 candidate direction (1): a distinct latent-effect channel on the verify result, separate from `rootClosure`, holding the union of effect surfaces reachable only through indirect invocation. Keeping `closure(g)` to mean "directly performed" leaves the Handler closure-subtraction semantics and the existing harm-bound arithmetic undisturbed; the total pre-execution reach an orchestrating principal reasons about is `rootClosure ∪ latentClosure`, with the two kept distinguishable so a host sees both what a program performs and what its latent capabilities could perform if invoked.

Two contributors feed the channel.

ToolDef implementations. For every reachable N-044 ToolDef, the implementation's effect surface is folded into the latent channel. The verifier already types the implementation (the `ToolImplementation*` rules require it to be a function of the parameter type), so its effect surface — the FunctionType effect row for a Lambda implementation, or the declared effects for a ForeignNode implementation — is known at admission.

Higher-order callbacks. Any effectful value passed as an argument (rather than as the applied function) into an Application contributes its effect surface to the latent channel: a value whose inferred type is a FunctionType with a non-empty effect row, or which resolves to a ForeignNode carrying effects, appearing in argument position. This is a sound over-approximation that requires no registry of which builtins are higher-order or which parameters are callback slots — any effectful value in argument position is potentially invoked indirectly and is surfaced. A value that is both passed as data and directly applied elsewhere already contributes its direct use to `rootClosure`; the latent channel adds only its indirect reach.

The surfaced shape mirrors Q-067: `VerifyResult.Ok` gains a `latentClosures` map keyed by NodeId exactly as `nodeClosures` is, with a `rootLatentClosure(root)` accessor and a `totalClosure(root) = rootClosure(root) ∪ rootLatentClosure(root)` convenience. The reasoning API of the next item reads both channels. The addition is additive and hash-neutral: `VerifyResult` is not part of the canonical node encoding, precisely as Q-067's `nodeClosures` addition was.

## Implementation sketch

- Verifier: add a `VerifyState.latentClosures` accumulator alongside `nodeClosures`. At ToolDef admission, record the implementation's effect surface keyed by the ToolDef NodeId and union it into the root's latent set. At Application inference, for each argument whose inferred type carries a non-empty effect row (or which resolves through the existing `resolveProjectedForeignNode` chain to an effect-bearing ForeignNode), record its effect surface into `latentClosures`.
- `VerifyResult.Ok`: add `latentClosures`, `rootLatentClosure(root)`, and `totalClosure(root)`, additive with defaults so every existing construction site and consumer is unaffected.
- Tests: a program handing a `Filesystem.Write` ToolDef to Generate surfaces `{LLM.Generate}` in the root channel and `{Filesystem.Write}` in the latent channel (mirroring the agent-workflow demonstration's A2 shape); a `List.Map` over a projected `fsWrite` callback surfaces `{Filesystem.Write}` in latent; a benign program surfaces an empty latent channel. A `SurfacedEffectClosureTest`-style pin in `:corpus`, plus a verify-only corpus program if a fresh exemplar is warranted.
- No canonical-encoding, golden-hash, or prelude change — the channel lives on the verify result only.

## Deferred and open

- A tighter, less over-approximate higher-order bound (recognizing specific callback positions rather than any effectful argument) is deferred; the sound over-approximation ships first.
- Whether a constructed-but-never-passed-to-Generate ToolDef should count is Q-070's presentation question; the sound default includes it, with provenance tagging (reachable-by-the-model versus merely carried) deferred as a refinement a host can consume later.
- Transitivity through nested ToolDefs or callbacks is captured by folding the full effect surface at each site, so no separate recursive pass is required.
- Runtime enforcement is unchanged; this proposal closes pre-execution completeness only.

## Scope

Small to medium. One accumulator and one additive surfacing on `VerifyResult`, verifier-only, hash-neutral. It discharges the soundness obligation ADR-010 places on the reasoning surface and unblocks the reasoning API.

## References

**Outgoing references:**
- [`decisions/ADR-010-reasoning-surface.md`](../decisions/ADR-010-reasoning-surface.md) — the reasoning surface whose soundness this closes
- [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — the effect closure this extends
- [`open-questions.md`](../open-questions.md) — Q-070 (resolved via direction 1), Q-071 (registered), Q-067, Q-044, Q-039
- [`demos/agent-workflow/README.md`](../demos/agent-workflow/README.md) — the ToolDef over-reach shape this surfaces statically
