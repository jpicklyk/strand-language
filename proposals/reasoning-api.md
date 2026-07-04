# Reasoning API

**Document:** `proposals/reasoning-api.md`
**Status:** Draft
**Question:** [Q-072](../open-questions.md#Q-072) (registers)
**Related:** [ADR-010](../decisions/ADR-010-reasoning-surface.md), [Q-067](../open-questions.md#Q-067), [`implemented/latent-effect-surfacing.md`](implemented/latent-effect-surfacing.md) (Q-070/Q-071), [Q-044](../open-questions.md#Q-044), [Q-054](../open-questions.md#Q-054), [Q-031](../open-questions.md#Q-031), [Q-023](../open-questions.md#Q-023) (human tooling, distinct)
**Drafted:** 2026-07-04

## Problem

[ADR-010](../decisions/ADR-010-reasoning-surface.md) commits the verified artifact to exposing a queryable reasoning surface as typed data for a machine consumer — an orchestrating host or the generating agent — making a decision from the artifact. The underlying facts now exist: the directly-performed effect closure (`rootClosure`, [Q-067](../open-questions.md#Q-067)), the latent indirectly-invoked closure (`latentClosures`, the latent-effect-surfacing work), the capability requirements the verifier checks at admission, and the node type map. What does not exist is a stable, agent-callable surface that returns these as structured queries. They are internal fields on `VerifyResult.Ok`, computed ad hoc in individual demonstrations (the clean-room demo hand-rolls an egress intersection), or reachable only through the CLI's human-oriented text output. The one open question naming inspection tooling ([Q-023](../open-questions.md#Q-023)) is a human graph editor, deferred to a later phase and aimed at a human reader. This proposal defines the machine-facing query surface ADR-010 requires, registered as Q-072.

## Recommended approach

A pure `ProgramAnalysis` value computed from `(store, hashToNodeId, VerifyResult.Ok)`, exposing typed per-subgraph queries, reachable through the `StrandRuntime` facade ([Q-054](../open-questions.md#Q-054)) as a third first-class entry point alongside `verify` and `run` — the ADR-010 consequence that reasoning becomes a peer of running in the runtime's public surface.

The queries, each keyed by a subgraph NodeId and defaulting to the program root:

- `effectClosure(node)` — the directly-performed EffectCategory set (from Q-067 `nodeClosures`).
- `latentEffectClosure(node)` — the indirectly-invoked effect surface (from `latentClosures`): ToolDef implementations and effectful values passed to higher-order builtins.
- `totalClosure(node)` — the union; the complete pre-execution effect reach.
- `capabilityRequirement(node)` — the categories and refinement parameters a caller must grant to run the subgraph, derived from the closure and the EffectDecls at reachable call sites, as a structured value (category to required refinement patterns, or wildcard where the site is refinement-free).
- `egressSet(node, watched)` — `totalClosure(node)` intersected with a caller-named effect class; the clean-room "can this exfiltrate" query, generalized so it is a library call rather than demonstration code.
- `harmBound(node, grant)` — `totalClosure(node)` intersected with a given `CapabilitySet`: what survives a specific grant, the admission-decision input `closure(g) ∩ C` of [Q-044](../open-questions.md#Q-044).
- `reachesEffect(node, category)` — boolean reachability of a category.
- `capabilityDiff(other)` — the categories and refinements this program requires that another does not; the query that makes iterative agent editing safe by showing what new authority a revision demands.

Every result is a typed data class; nothing is a rendered string. The facade gains `analyze(image): AnalysisOutcome` wrapping `ProgramAnalysis`, plus a `verifyAndAnalyze` convenience. The surface is Handler-aware by construction because it reads the verifier's own closure, which already applies the Handler closure-subtraction, so it is sound where a host-side structural walk is looser ([ADR-010](../decisions/ADR-010-reasoning-surface.md), Q-067).

## Where it lives

`ProgramAnalysis` and its result types live in `:verifier` — they need only `TypeExpr`, `VerifyResult`, `NodeStore`, and the EffectCategory nodes, all already present there, with no new module dependency. The facade entry point lives in `:runtime`'s `StrandRuntime`. A `strand analyze <file>` CLI subcommand emitting the structured analysis as machine-readable JSON — distinct from the existing human-oriented text output — is recommended so the surface is reachable from the command line, but the facade API is the deliverable and the CLI is a thin client over it.

## Distinct from Q-023

[Q-023](../open-questions.md#Q-023) is a human graph editor with structured-diff and subgraph-rendering, deferred to Phase 4 and aimed at a human reader performing failure forensics. This proposal is the machine-facing typed query surface: the audience is a program making an admit/grant/run decision, and the output shape is typed data rather than a rendering. The two are complementary — the human tooling of Q-023 can be built on top of this API — but they are different interfaces with different audiences, which is why this is registered separately as Q-072 rather than folded into Q-023.

## Deferred and open

- Value-level taint (`flowsTo(node, effect)`: does this specific value reach this effect) beyond category reachability is a dataflow query; the closure-based reachability ships first and fine-grained taint is deferred.
- `capabilityDiff` across federated multi-store programs is deferred; the single-image case ships first.
- The self-gating primitive that consumes `harmBound` to let a generating agent refuse its own over-budget dispatch is the next item, under its own question.

## Scope

Medium. A pure query layer over data the verify result already carries, a facade entry point, tests, and an optional CLI subcommand. Hash-neutral; no runtime, encoding, or golden-hash change.

## References

**Outgoing references:**
- [`decisions/ADR-010-reasoning-surface.md`](../decisions/ADR-010-reasoning-surface.md) — the reasoning surface this realizes
- [`open-questions.md`](../open-questions.md) — Q-072 (registered), Q-067, Q-044, Q-054, Q-031, Q-023
- [`implemented/latent-effect-surfacing.md`](implemented/latent-effect-surfacing.md) — the latent-effect channel the total closure reads
- [`evaluation/containment-results.md`](../evaluation/containment-results.md) — the harm bound `harmBound` computes
