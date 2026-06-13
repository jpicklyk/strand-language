# ADR-001: Graph-Native Representation {#adr-001}

**Document:** `decisions/ADR-001-graph-not-text.md`
**Status:** Accepted
**Date:** 2026-05-23
**Last revised:** 2026-06-13 (Decision-section "no parser, no lexer" and the consequences paragraph's "absence of a parser" scoped to the canonical form: the Layer A and Layer F authoring projections have parsers and lexers but yield no authoritative source form, so the absolute wording was made precise. The decision itself is unchanged. Prior revision 2026-06-11: clarifying paragraph that the decision fixes the canonical form, not the absence of text from the toolchain; the Q-034 Layer A authoring projection carries no canonical status.)
**Supersedes:** none
**Superseded by:** none

## Context {#context}

A programming language requires a source representation: the form in which programs are constructed, stored, transmitted between tools, and submitted to a compiler or runtime. The conventional choice is a textual concrete syntax — a stream of characters parsed into an abstract syntax tree, which is then lowered through successive intermediate representations to executable code. Every mainstream language in production use, and almost every research language, adopts this choice.

Strand's design rationale (see [`00-motivation.md`](../00-motivation.md), section [Text-LLM mismatch](../00-motivation.md#text-llm-mismatch)) identifies costs that text representation imposes on AI generation: token overhead for syntactic noise, autoregressive commitment to syntactic choices, mismatch between text order and program dependency order, and loss of structural information at the generation boundary. These costs accumulate per token, per generation, across every program an agent emits.

The structural content a compiler reconstructs from text — typed nodes, references, control flow, dependencies — already exists in the program's semantic model. Text serialization exists because humans read linearly. For a language whose generator is a large language model and whose immediate audience is a runtime, the serialization step solves no problem the design must solve.

A choice is therefore required between accepting the costs of text representation in exchange for compatibility with existing tooling and human inspection, or abandoning text representation in favor of a structural source form.

## Decision {#decision}

Strand programs are directed graphs of typed, content-addressed nodes. The graph is the source representation. There is no concrete text syntax for the canonical form, no parser or lexer that yields it, and no canonical character-stream serialization of program source.

Programs are constructed by graph operations: creating typed nodes, attaching typed edges, and verifying well-formedness. Each operation either succeeds, producing a well-formed addition to the program, or fails with a structural reason. Agents generating Strand programs target graph construction; any text an agent's emission passes through is an authoring surface compiled to the graph before anything enters the store, never a source form the language treats as authoritative.

The graph is canonical. Any human-facing rendering, any on-disk storage format, any wire encoding is a view or projection of the underlying graph; none of these is privileged over the others, and none is the "real" source. The graph itself is identified by the content hashes of its nodes (see [ADR-003](ADR-003-content-addressing.md)).

The scope of the decision is canonical status. The implemented authoring stack ([Q-034](../open-questions.md#Q-034)) has agents emit Layer A, a compact line-oriented text projection that an elaborator compiles to canonical dag-json before any node reaches the verifier. Layer A occupies the role the bootstrap-expedient alternative below anticipates, constrained so that it cannot become authoritative in practice: no hash is computed over Layer A text, verification never operates on it, it is not stored as program source, and it may change or be replaced without affecting the identity of any program. The decision's content is that the artifact of record is the verified graph — not that an agent's emission never passes through characters.

This decision determines the shape of subsequent decisions. The absence of a parser for the canonical form eliminates a class of failure modes but creates engineering work, since no off-the-shelf editor, syntax highlighter, or diff tool applies to the graph. The absence of source ordering forces the language to express all relationships as edges (see [ADR-004](ADR-004-effects-as-edges.md)). The absence of name binding for identity admits content-addressing (see [ADR-003](ADR-003-content-addressing.md)).

## Alternatives considered {#alternatives}

Four alternatives were evaluated and rejected.

**Text source compiled to an internal graph IR.** The standard architecture. Programs are authored as text, parsed into an AST, and lowered through graph IRs (SSA, MLIR) for optimization and code generation. This is the dominant pattern and benefits from decades of tooling investment. It is rejected because it reintroduces every text-LLM mismatch cost the project exists to eliminate, and because the parser becomes a permanent failure surface that the design cannot remove. The conventional pattern is appropriate when the primary author is human; Strand's primary author is not.

**Text source as a bootstrap expedient with graphs as canonical form.** The pattern adopted by QUASAR ([`01-prior-art.md`](../01-prior-art.md#prior-quasar)): LLMs generate a familiar text-language subset, the system transpiles to a structured execution language. This pattern is acceptable during early implementation, when no Strand training corpus exists and a graph-fluent LLM does not yet exist. It is not acceptable as a permanent feature: a persistent text-source layer becomes the authoritative representation in practice, regardless of design intent, because tooling concentrates around what users actually edit. Bootstrap transpilation is an implementation expedient; the design target is direct graph emission.

**Structured editor on an AST (Hazel- or MPS-style).** Programs are stored as ASTs and edited through projectional editors that may render text, tables, or diagrams ([`01-prior-art.md`](../01-prior-art.md#prior-hazel), [`01-prior-art.md`](../01-prior-art.md#prior-mps)). This approach removes the parser and eliminates syntactic errors as a category, both properties Strand requires. It is rejected because it remains anchored on the assumption of an editing human and on name-based identity for AST nodes. Strand's primary author is an agent that does not need an editor, and Strand's identity scheme is content-addressed rather than nominal.

**Graph as canonical form with one or more textual surface languages.** The Unison pattern ([`01-prior-art.md`](../01-prior-art.md#prior-unison)): programs are content-addressed graphs at storage and execution time, but a high-quality text surface syntax exists for human authorship. This is the closest alternative to the chosen design. It is rejected on scope grounds. A high-quality projection layer is a multi-year engineering investment, and the research hypothesis stated in [`00-motivation.md`](../00-motivation.md#hypothesis) is testable without one. The decision to omit the projection layer is examined separately in [ADR-002](ADR-002-no-human-projection.md).

## Consequences {#consequences}

Structural consequences follow directly.

Syntactic errors cease to exist as a category. There is no parse step; every operation that produces a node either yields a well-typed result or is rejected. Error reports describe missing edges, type mismatches, or violations of well-formedness rules, not unexpected characters or unmatched braces.

Source ordering ceases to exist. The notion of "what appears before what in the file" is undefined in a graph. Dependency order, evaluation order, and declaration order are distinct relationships that must each be expressed as explicit edges where the language requires them.

Distribution is direct. The dependency graph used by a scheduler to place computation is the same graph that constitutes the program; no extraction or annotation step is required (see [`02-core-thesis.md`](../02-core-thesis.md#integration)).

Conventional language tooling does not apply. Editors, formatters, syntax highlighters, diff tools, version control hooks, and code review interfaces all assume textual source. Strand requires graph-native replacements for each. The minimum viable tooling for human-facing workflows is an open question ([Q-023](../open-questions.md#Q-023)).

Human inspection requires analysis tools rather than reading. The choice to omit textual rendering is foundational and is examined separately ([ADR-002](ADR-002-no-human-projection.md)); the immediate consequence is that any forensic, audit, or review workflow operates on the graph through queries and structured renderings.

The training corpus problem becomes acute. No existing Strand programs exist for an LLM to learn from, and there is no surface syntax onto which an existing corpus can be retargeted. The bootstrap strategy is an open question ([Q-020](../open-questions.md#Q-020)) and a primary risk to the research program. The decision adopted here makes this risk explicit rather than hidden behind a familiar surface syntax.

The node algebra becomes load-bearing. Because the graph is the source representation, the inventory of node types, the schemas of edges between them, and the rules for well-formedness collectively define what a Strand program is. The formal algebra is an open question ([Q-001](../open-questions.md#Q-001)) and is required before implementation can begin.

## References

**Outgoing references:**
- [`00-motivation.md`](../00-motivation.md) — rationale for abandoning text representation
- [`02-core-thesis.md`](../02-core-thesis.md) — Claim 1, graph-native representation
- [`01-prior-art.md`](../01-prior-art.md) — alternative representations evaluated
- [`ADR-002-no-human-projection.md`](ADR-002-no-human-projection.md) — separate decision on projection
- [`ADR-003-content-addressing.md`](ADR-003-content-addressing.md) — identity scheme
- [`ADR-004-effects-as-edges.md`](ADR-004-effects-as-edges.md) — edges as the relationship mechanism
- [`design/node-algebra.md`](../design/node-algebra.md) — formal algebra of nodes and edges
- [`open-questions.md`](../open-questions.md) — Q-001, Q-020, Q-023, Q-034

**Incoming references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — cites this ADR from Claim 1
- [`ADR-002-no-human-projection.md`](ADR-002-no-human-projection.md) — graph foundation
- [`ADR-003-content-addressing.md`](ADR-003-content-addressing.md) — graph foundation
- [`ADR-004-effects-as-edges.md`](ADR-004-effects-as-edges.md) — graph foundation
- [`ADR-005-foreign-nodes.md`](ADR-005-foreign-nodes.md) — graph foundation
- [`ADR-007-state-machines.md`](ADR-007-state-machines.md) — graph foundation
- [`ADR-008-compilation-target.md`](ADR-008-compilation-target.md) — graph foundation
- [`design/node-algebra.md`](../design/node-algebra.md) — graph foundation for node algebra
- [`design/distribution-model.md`](../design/distribution-model.md) — graph as dependency structure
