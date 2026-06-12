# ADR-002: No Human-Readable Projection Layer {#adr-002}

**Document:** `decisions/ADR-002-no-human-projection.md`
**Status:** Accepted
**Date:** 2026-05-23
**Last revised:** 2026-06-11 (Clarifying paragraph in the Decision section: the Q-034 Layer A authoring projection and its Q-036 reverse direction are tool-layer emission and consumption surfaces for agents, not the human-readable projection this record declines to provide. The decision itself is unchanged.)
**Supersedes:** none
**Superseded by:** none

## Context {#context}

[ADR-001](ADR-001-graph-not-text.md) establishes that Strand programs are directed graphs of typed, content-addressed nodes, and that the graph is the canonical source representation. ADR-001 does not specify whether a textual projection from graph to character stream is provided for human inspection. The two questions are independent: a graph-native language can provide a high-quality projection (Unison does), or none (a hypothetical pure graph language does not), or many partial projections for specific views (MPS-style projectional editing).

Several use cases drive demand for some form of human-readable rendering. Code review requires inspecting changes. Security audit requires examining specific subgraphs. Failure forensics requires reading a program at the point of an incident. Regulatory compliance in some domains requires that audited code be presentable in a human-comprehensible form. New contributors to a project benefit from being able to browse the code. None of these are well served by a binary graph blob.

The question this decision answers is whether Strand provides a textual projection as a primary interface, or treats human inspection as a problem solved by other means.

## Decision {#decision}

Strand does not provide a canonical textual projection from graph to text. There is no pretty-printer that emits a designated surface syntax. There is no bidirectional mapping between a text form and the graph that the language considers authoritative.

Human inspection is supported, but through analysis tooling that operates directly on the graph: structured queries, dependency visualizations, structured diffs between graphs, and subgraph renderings selected by purpose (a security audit view, a control flow view, a dependency view). These tools may produce text, tables, or diagrams as output for specific purposes, but no single rendering is treated as the canonical view of a program.

The decision is scoped to human-facing projection. The Layer A authoring surface ([Q-034](../open-questions.md#Q-034)), through which agents emit programs, and its reverse direction ([Q-036](../open-questions.md#Q-036)), through which agents read existing ones, are tool-layer forms optimized for model emission and consumption, not renderings designed for human reading, and neither holds canonical status: no hash, verification result, or program identity attaches to Layer A text, and the projection may be revised or retired without affecting any stored program. What this record declines to provide remains undelivered — a textual surface designed for human reading, maintained as a primary interface, and treated by the language as the rendering of a program.

This decision is a scope choice, not a fundamental impossibility. Nothing in Strand's design prevents a future projection layer from being built as separate tooling, and a community-maintained projection may emerge if the language reaches sufficient adoption. The decision adopted here is that the core language, the reference implementation, and the research evaluation do not depend on a projection layer existing.

## Alternatives considered {#alternatives}

Four alternatives were evaluated and rejected.

**Full bidirectional projection (Unison pattern).** Unison provides a high-quality textual surface syntax for human authorship and review, with bidirectional mapping between text and the underlying content-addressed graph. The projection is canonical in the sense that any program has a well-defined textual rendering. Building such a projection well is a substantial engineering investment: a surface syntax must be designed, a pretty-printer must produce stable output across versions, an editor toolchain must understand the projection, and the bidirectional mapping must preserve enough information that round-trips are lossless. Unison has invested years in this effort and continues to refine the surface syntax. The research hypothesis stated in [`00-motivation.md`](../00-motivation.md#hypothesis) is testable without this investment, so the engineering cost is not justified.

**Multiple projections through projectional editing (MPS pattern).** JetBrains MPS supports many concurrent projections from one underlying AST: textual, tabular, diagrammatic, or domain-specific. This is technically attractive — the AST is canonical and projections are pluggable — but the engineering cost is comparable to a full projection layer, multiplied by the number of supported projections. The MPS-style framework itself is a substantial software product that took years to build. Adopting MPS directly is not viable because Strand's graphs are not MPS ASTs and the integration would be more work than building a single projection. Building Strand-native projectional editing is the same scope problem as the full projection alternative.

**On-demand canonical rendering with no editing.** A weaker variant: provide a one-way function from graph to text that produces readable output for inspection, but do not support editing the text back to the graph. This avoids the bidirectional-mapping problem but still requires a surface syntax design. The surface syntax will not be a good language to read for substantial programs because Strand's graph structure is not optimized for linear presentation. The rendering would be useful for small subgraphs (a single function, a specific transition) but not for whole programs. Targeted subgraph rendering is in scope as analysis tooling (see below); the alternative being rejected is a *canonical whole-program* textual rendering.

**Analysis tooling only, with text rendering produced ad hoc for specific subgraphs.** The chosen approach. Tooling supports queries ("show me the effect closure of this function"), structured diffs ("how does this graph differ from the previous version"), and visualizations (dependency graphs, capability flow, effect propagation). For inspection workflows that benefit from text — reviewing a single function, examining a state transition — tooling may produce a textual rendering of the specific subgraph, but no single text format is canonical and no rendering is the authoritative form of the program.

## Consequences {#consequences}

The engineering scope of the project is bounded. No surface syntax must be designed. No pretty-printer must be written and maintained across language versions. No editor integration must be developed. The cost of these things at production quality is substantial; the cost of avoiding them is zero on the design side and is paid instead on the analysis-tooling side.

Analysis tooling becomes load-bearing. The minimum tooling required to make Strand usable for audit and forensics is an open question ([Q-023](../open-questions.md#Q-023)). At minimum, the runtime must support queries over the graph, structured diff between graphs, and rendering of named subgraphs for inspection. Failure forensics in particular requires a workflow for examining a graph at the point of failure, including the relevant call stack, capability context, and event history.

The adoption barrier is higher than for text languages. Developers cannot read Strand code with their existing tools (editors, code review interfaces, search engines, training corpora). For the research phase, this is acceptable because the user is an AI agent. For eventual production adoption, the analysis tooling must serve the audit and review use cases well enough that the lack of textual code is not a disqualifying constraint.

Certain inspection tasks become more powerful than their text-language equivalents. Questions like "every code path from input to a network effect," "all functions that hold capability C," or "the set of nodes whose effect set includes filesystem write" become structural graph queries with deterministic answers. The equivalent in a text language requires either manual code reading or static-analysis tooling built on a reconstructed graph — exactly the graph Strand uses as source. The hypothesis here is that for the inspection tasks that matter for security and audit, the analysis-tool approach is strictly more capable than text reading. This hypothesis is testable as part of the broader research program.

Certain inspection tasks become harder than their text-language equivalents. Casual browsing — "read through the code to get a feel for what it does" — does not translate. Cross-codebase pattern search via grep does not apply. Familiarity transfer from one codebase to another is reduced because there is no surface syntax to internalize.

The decision interacts with later decisions on encryption. Per-node encryption ([ADR-006](ADR-006-per-node-encryption.md)) is more natural when projection is not required: an encrypted node need not have a textual rendering, only a hash and an opaque blob. With projection, encrypted nodes would either need to render as opaque placeholders (uninformative) or expose decryption to the renderer (defeating the purpose). Without projection, the encrypted-node case is treated uniformly with the unencrypted case at the rendering layer (both go through analysis tooling, which respects capability context).

The decision is reversible at a future cost. If at some future point a projection layer is required, it can be built as a separate tool that consumes the graph and emits text. The graph is canonical, so a projection added later does not introduce a second source of truth. The cost of the future addition is the cost of building the projection well; the cost is not paid by the current scope.

## References

**Outgoing references:**
- [`00-motivation.md`](../00-motivation.md) — research hypothesis testable without projection
- [`02-core-thesis.md`](../02-core-thesis.md) — Claim 2, no projection
- [`01-prior-art.md`](../01-prior-art.md) — Unison, Hazel, MPS comparisons
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — graph-native foundation
- [`ADR-006-per-node-encryption.md`](ADR-006-per-node-encryption.md) — interaction with encryption
- [`open-questions.md`](../open-questions.md) — Q-023 analysis tooling; Q-034, Q-036 authoring-projection status

**Incoming references:**
- [`00-motivation.md`](../00-motivation.md) — cites this ADR from the no-human-readability section
- [`02-core-thesis.md`](../02-core-thesis.md) — cites this ADR from Claim 2
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — defers projection scope to this ADR
- [`ADR-006-per-node-encryption.md`](ADR-006-per-node-encryption.md) — interaction with encrypted-node visibility in analysis tooling
- [`research-plan.md`](../research-plan.md) — analysis tooling commitments for Phase 4
- [`ADR-009-structured-outputs.md`](ADR-009-structured-outputs.md) — distinguishes program (not projected) from outputs (renderable to humans)
