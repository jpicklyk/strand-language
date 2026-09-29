# ADR-010: The Verified Artifact as a First-Class Reasoning Surface {#adr-010}

**Document:** `decisions/ADR-010-reasoning-surface.md`
**Status:** Accepted
**Date:** 2026-07-04
**Supersedes:** none
**Superseded by:** none

## Context {#context}

Strand is organized around a language an AI agent can create, run, and reason about. Of these three, reasoning is the capability the graph-native, content-addressed, effect-declared form is most uniquely positioned to provide, and it is the least developed. The founding thesis ([`02-core-thesis.md`](../02-core-thesis.md)) narrows Strand's differentiation, against capability-checked text languages and runtime-mediation approaches alike, to properties of the verified artifact: the harm bound is a function of the stored graph, established at admission over exactly the bytes that execute, and re-derivable later without the producer's toolchain. Each of those properties is a statement about what can be *asked of the artifact* — what effects it may perform, what capabilities it requires, what data it can reach, how one revision's authority differs from another's.

The mechanisms that answer those questions exist but are not exposed as a capability. Effect closures are computed by the verifier ([ADR-004](ADR-004-effects-as-edges.md)) and surfaced on the verify result as `nodeClosures` and `rootClosure` ([Q-067](../open-questions.md#Q-067)). Capability requirements are checked at admission and enforced at dispatch ([`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md)). The clean-room demonstration already computes an egress set by intersecting a closure with a watched effect class. But these are internal computations or one-off demonstration code, not a stable surface an agent or host queries; where a reasoning result is presented at all it is rendered as text for a human, the interface [ADR-002](ADR-002-no-human-projection.md) declines to build as a primary channel. The one open question that names human inspection tooling ([Q-023](../open-questions.md#Q-023)) is a graph editor, deferred to a later production-hardening phase, and is aimed at a human reader rather than an agent making a machine decision.

Two facts make exposing this surface the highest-leverage direction. First, the consumer that matters for Strand's differentiation is not a human reading a rendering but a party — an orchestrating host, or the generating agent itself — that must make a decision from the artifact: admit or reject, grant or withhold, run or refuse. That decision needs typed data, not prose. Second, the reasoning result is only usable as a machine contract if it is sound: a bound that silently omits effects an agent-shaped program can reach is not a conservative approximation a host can rely on, it is a false negative. The static closure today has exactly such gaps for indirectly-invoked effects ([Q-070](../open-questions.md#Q-070) and the higher-order-callback shape it names), which are precisely the constructs agent programs use most.

The question this decision answers is whether reasoning about a verified artifact is a first-class capability of the language, with a stable surface and a soundness obligation, or an incidental byproduct left to demonstrations and deferred tooling.

## Decision {#decision}

The verified artifact exposes a reasoning surface as a first-class capability of the language, on equal footing with executing it. The surface is defined by four commitments.

The verify result carries the analysis facts as typed data. Beyond the type and effect-closure information already surfaced, the verify result exposes, per node and per subgraph, the effect closure, the capability requirement (the grants a caller must hold to run the subgraph), the egress surface (the closure restricted to a caller-named effect class), and the latent effect surface (effects reachable only through indirect invocation, held in a channel distinct from the directly-performed closure). These are structured values, not rendered strings.

The surface is queryable by agents and hosts through a stable API, not only rendered for humans. The queries an orchestrating principal or a generating agent asks of an artifact — what is the harm bound of this subgraph, what capabilities does it require, what can it reach in a given effect class, does this value flow to this effect, how does this revision's authority differ from the previous one — are answerable as typed calls on the verified program, on equal footing with the calls that run it. This is distinct from the human-facing graph-editor tooling of [Q-023](../open-questions.md#Q-023); the audience is a program making a decision.

The reported bound is sound, and closing the completeness gaps that make it unsound is load-bearing rather than optional. The reasoning surface reports a sound over-approximation of what an artifact can do: no run may exceed it. Where the current computation omits reachable effects — indirectly-invoked ToolDef implementations and effectful values passed to higher-order builtins ([Q-070](../open-questions.md#Q-070)) — those omissions are defects in the surface, not accepted limitations, because a host or agent that gates a decision on an unsound bound is not protected by it. The latent effect surface named above is the mechanism by which indirectly-reachable effects are made visible without conflating them with the directly-performed closure.

The generating agent may gate its own execution on the computed bound. Because the harm bound is a value the agent can compute from its own emission before dispatch, an agent (or a supervising agent) can refuse to run a program whose bound exceeds a declared budget. This closes the create-reason-run loop within a single principal: the party that wrote the code checks what the code it wrote is permitted to do, and decides whether to run it, without a human in the loop and without trusting a producer it is not.

The reasoning surface attaches to the graph, consistent with [ADR-001](ADR-001-graph-not-text.md) and [ADR-002](ADR-002-no-human-projection.md). Program identity, verification, and now reasoning attach to the verified graph and to nothing upstream of it. Authoring surfaces ([Q-034](../open-questions.md#Q-034) Layer A, [Q-061](../open-questions.md#Q-061) Layer F) remain disposable projections that carry no reasoning contract; an agent may create in whatever surface it is fluent in, but the artifact it reasons about and runs is the graph. This refines rather than revises the earlier decisions: [ADR-002](ADR-002-no-human-projection.md) declines a projection built for a human reader, and this decision builds a reasoning surface for a machine consumer, which is a different interface with a different audience.

## Alternatives considered {#alternatives}

Four alternatives were evaluated and rejected.

**Reasoning as human-facing tooling only ([Q-023](../open-questions.md#Q-023)).** Human inspection through a graph editor, structured diffs, and subgraph rendering, deferred to a production-hardening phase. This remains worth building for failure forensics and audit, but it is not the primary surface, because the decision Strand's differentiation rests on — admit, grant, run — is made by a program, and a rendering for a human eye is the wrong output shape for a machine decision. The two are complementary: the typed surface this decision adopts can drive the human tooling, but not the reverse.

**Reasoning re-derived by each host structurally.** Leave the verify result as it is and have every host walk the graph to recover the harm bound it needs, as the containment demonstration does for rejected artifacts. This is rejected as the primary path because it duplicates the verifier's own Handler-aware closure computation ([Q-067](../open-questions.md#Q-067) records that a host-side structural walk is looser than the verifier's, because it cannot see the Handler closure-subtraction), and two computations of the same bound with different results is a place the security story drifts. A host re-deriving a bound for an artifact the verifier rejected remains a legitimate fallback, but the success path exposes the verifier's own sound computation.

**Defer reasoning to a later phase, as tooling.** Treat the analysis surface as adoption tooling downstream of real users, the framing [Q-023](../open-questions.md#Q-023) carries. This is rejected because reasoning is the leg of the create-run-reason triad that the graph form is most distinctively able to serve and the one the thesis's surviving differentiation depends on; deferring it defers the differentiation. Running and authoring are comparatively well served by conventional means, and are where the design has already invested; the reasoning surface is the under-built moat.

**Expose the surface without requiring soundness.** Ship the analysis queries over the closure as it is computed today, with its known omissions of indirectly-invoked effects, as a best-effort convenience. This is rejected because a bound presented as a machine contract that a party gates a decision on must not silently under-report: an unsound harm bound is worse than none, because it invites reliance it does not earn. Adopting the surface therefore carries the obligation to close the completeness gaps ([Q-070](../open-questions.md#Q-070)) that would make it unsound for exactly the agent-shaped programs it targets.

## Consequences {#consequences}

The surface names concrete work, sequenced by dependency. The completeness of the reported bound is a prerequisite for the surface being usable, so surfacing indirectly-invoked effects ([Q-070](../open-questions.md#Q-070) and its higher-order-callback sibling) precedes the query API. The query API — the typed analysis calls over the verified artifact — realizes this decision. The self-gating primitive, by which a generating agent refuses to dispatch a program exceeding a declared bound, builds on the query API. Each is specified by its own proposal and open question.

Soundness of the reasoning surface rests on the foreign-declaration trust floor. Every leaf of every closure is a foreign node whose declared effects the verifier takes on faith ([ADR-004](ADR-004-effects-as-edges.md), [ADR-005](ADR-005-foreign-nodes.md)). A reasoning surface reporting a bound computed from those declarations is only as sound as the declarations; cross-checking a builtin's declared effects against its implementation where the truth is co-resident ([Q-056](../open-questions.md#Q-056)), and recording declared-versus-performed effects at runtime ([Q-055](../open-questions.md#Q-055)), are the near-term floor under the surface. The full foreign-binding trust model ([Q-006](../open-questions.md#Q-006)) remains the long-horizon version.

Reasoning becomes a peer of running in the runtime's public surface. The embeddable runtime facade ([Q-054](../open-questions.md#Q-054)) already exposes verify and run; this decision makes analysis queries a third first-class entry point, so that an embedding host and a generating agent reach the reasoning surface through the same facade they reach execution through.

The create leg is freed to be familiar. Because reasoning and identity attach to the graph and not to any authoring surface, the create leg carries no reasoning contract and may optimize for whatever surface an agent emits most reliably. This is the standing argument for the familiar-shaped surface ([Q-061](../open-questions.md#Q-061)) carrying authoring, with the graph as the reasoning and execution substrate.

The differentiation becomes measurable rather than asserted. A first-class reasoning surface is the artifact against which the thesis's lead claim is tested: the containment measurement ([`evaluation/containment-results.md`](../evaluation/containment-results.md)) computes the harm bound this surface exposes, and comparing it against the strongest competing approaches rather than an undefended baseline is what converts the reason-first claim from a design position into a result.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — the artifact-attached, re-derivable harm bound this surface exposes
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — reasoning attaches to the graph, the artifact of record
- [`ADR-002-no-human-projection.md`](ADR-002-no-human-projection.md) — the human-facing projection this decision does not build; the machine surface it does
- [`ADR-004-effects-as-edges.md`](ADR-004-effects-as-edges.md) — the effect closure the reasoning surface reports
- [`ADR-005-foreign-nodes.md`](ADR-005-foreign-nodes.md) — the foreign-declaration trust the surface's soundness rests on
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — capability requirement and refinement the surface reports
- [`design/security-model.md`](../design/security-model.md) — the harm bound and its role in the security story
- [`evaluation/containment-results.md`](../evaluation/containment-results.md) — the harm-bound measurement over this surface
- [`open-questions.md`](../open-questions.md) — Q-023, Q-054, Q-055, Q-056, Q-067, Q-070, Q-061

**Incoming references:**
- (none yet; proposals realizing this decision will cite it)
