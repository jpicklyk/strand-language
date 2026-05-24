# Core Thesis {#core-thesis}

**Document:** `02-core-thesis.md`
**Status:** Stable
**Last revised:** Initial draft

## Summary

Strand is organized around five integrated design claims. Each claim is independently defensible but produces its strongest results in combination with the others. This document states each claim, describes what it entails, and identifies the documents where the claim is examined in detail. The claims are presented in dependency order: each subsequent claim assumes the preceding ones.

## Claim 1: Programs are graphs, not text {#claim-graph-native}

A Strand program is a directed graph of typed, content-addressed nodes. There is no textual source representation. There is no parser, no concrete syntax, no lexer, and no file format that contains program source as character data. Programs are constructed by graph operations: creating nodes, attaching typed edges, and verifying well-formedness.

This claim has several immediate consequences:

- Syntactic errors do not exist as a category. A graph operation either succeeds, producing a well-formed addition to the graph, or fails with a structural reason.
- Source ordering does not exist. Definitions, references, and types form a graph; the concept of "what comes before what in the source" does not apply.
- Names are not primary. Nodes are identified by content hash; human-meaningful names, where present, are metadata edges attached to nodes for tooling purposes, not part of program identity.

The claim is examined in [`ADR-001-graph-not-text.md`](decisions/ADR-001-graph-not-text.md). The detailed node algebra is specified in [`design/node-algebra.md`](design/node-algebra.md).

## Claim 2: Programs are not designed for human reading {#claim-no-projection}

Strand does not provide a canonical projection from graph form to text. Programs are read by humans through analysis tools — graph queries, dependency visualizations, structured diffs — rather than by inspecting a textual rendering. The decision is not that graphs cannot be projected to text, but that providing such a projection as a primary interface is not a design goal and the engineering effort to do so well is not undertaken.

This claim is independent of Claim 1. A graph-native language could provide high-quality human projection; Unison does. Strand's decision to omit projection is a scope choice. It is foundational because it determines the size of the project: omitting projection makes Strand a tractable research project; providing it would make Strand a multi-year engineering effort comparable to building a new IDE.

For contexts where human inspection is genuinely required (failure forensics, security audit, regulatory review), Strand provides analysis tooling that operates on the graph directly. The position is that analysis tools serve these use cases better than textual rendering, particularly for programs of substantial size.

The claim is examined in [`ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md).

## Claim 3: Effects are mandatory edges, not optional annotations {#claim-mandatory-effects}

Every Strand node that performs an effect — interacts with the outside world, with mutable state, with time, or with hardware capabilities — declares its effect through a typed edge in the graph. Effects propagate transitively: a node's effect set is the union of its declared effects and the effects of every node it references.

Effects are not optional. There is no equivalent of "untracked IO." A foreign function binding (see Claim 4) cannot enter the graph without an effect declaration. A node cannot be composed into a context that does not permit its effects. The verifier rejects graphs where effect requirements exceed effect permissions.

The effect system is the foundation for several downstream properties:

- **Security:** A graph's full effect set is computable statically. The runtime can refuse to execute graphs whose effects exceed declared budgets, providing structural rather than behavioral security guarantees.
- **Distribution:** Effects determine placement constraints. Pure subgraphs may run anywhere; subgraphs with capability-bound effects must run where those capabilities are granted.
- **Analysis:** Questions like "can this program access the network" become structural graph queries rather than undecidable problems requiring whole-program analysis.

The claim is examined in [`ADR-004-effects-as-edges.md`](decisions/ADR-004-effects-as-edges.md) and detailed in [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md).

## Claim 4: Identity is content-addressed {#claim-content-addressed}

Every node in a Strand graph is identified by a cryptographic hash of its content — its type, its edges, and the recursive hashes of nodes it references. Nodes are immutable: modifying a node produces a new node with a new hash. References between nodes are by hash, never by mutable name.

Content-addressing has substantial consequences:

- **Refactoring is mechanical.** Renaming a node (changing its name metadata) does not affect any reference, because references resolve by hash, not name. Changing a node's behavior creates a new node with a new hash; old references continue to resolve to the original.
- **No import system.** A reference is a hash. Resolution does not require name resolution, module imports, or visibility checks.
- **Distributed identity is global.** A node with hash H is the same node on every machine that holds it. Cluster coordination, caching, and replication are simpler when identity is global.
- **Tamper resistance.** A node's content cannot be modified without changing its hash. Once a graph references a node by hash, the reference is bound to that exact content.

Content-addressing also enables features that are difficult or impossible in named systems: per-node encryption (Claim 5 below), reproducible execution traces, deterministic replay, and graph-level deduplication.

The claim is examined in [`ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md).

## Claim 5: Execution is capability-mediated {#claim-capability-execution}

Strand graphs execute in a *capability context* — a set of effect tokens the runtime grants to the executing graph. When a node with effect requirements is evaluated, the runtime checks that the context holds the corresponding capabilities. Evaluation halts if it does not.

Capabilities are fine-grained and composable. A capability is not "network access" but "the ability to connect to host X on port Y." A capability is not "filesystem access" but "the ability to read from path P." Capability contexts are constructed explicitly and passed through node references; they are not ambient.

This produces a security model in which:

- The maximum harm any subgraph can cause is bounded by its capability context.
- A graph generated by an agent runs in a context constructed by the agent's principal, not in a context inherited from the agent's environment.
- Per-node encryption (Claim 4 plus capabilities for decryption keys) enables programs whose internal structure is decryptable only by holders of specific keys.
- TEE (Trusted Execution Environment) integration is natural: a TEE is an execution context that holds certain capabilities (typically: cryptographic attestation, sealed storage) that other contexts do not.

The claim is examined in [`design/security-model.md`](design/security-model.md) and [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md).

## Integration: how the claims compose {#integration}

The claims are individually defensible but produce the most distinctive properties of Strand only in combination. Some examples:

**Distribution falls out of (1) + (3) + (4) + (5).** Because programs are graphs (1), the dependency structure for distribution is the source representation. Because effects are mandatory (3), placement constraints are statically determinable. Because identity is content-addressed (4), nodes can move between machines without renaming or re-linking. Because execution is capability-mediated (5), placement decisions reduce to "where is the required capability held."

**Per-node encryption requires (1) + (4) + (5).** Because programs are graphs (1) of discrete nodes, individual nodes are the unit of encryption. Because identity is content-addressed (4), encrypted nodes have identities that do not reveal their plaintext content. Because execution is capability-mediated (5), decryption keys are capabilities granted to execution contexts.

**Static effect verification requires (1) + (3).** Because programs are graphs (1) rather than text, traversal computes effect closures directly. Because effects are mandatory (3), the closure is complete — no unmarked effects can hide in the computation.

**State machines as graph fixpoints requires (1) + (3) + (4).** Transition functions are graph nodes (1) with explicit effect declarations (3) and stable identities (4) that allow long-running machines to reference their transition logic by hash. The detailed design appears in [`design/state-machines.md`](design/state-machines.md).

## What this thesis does not claim {#non-claims}

To avoid overstatement, several things are explicitly not claimed:

- **Strand is not claimed to be human-friendly.** It is claimed to be amenable to AI generation and to provide analysis tooling for human inspection. Direct human authorship is not a goal.
- **Strand is not claimed to be performant in absolute terms.** Initial implementations will prioritize correctness and analyzability over runtime performance. Performance work is deferred to a later phase.
- **Strand is not claimed to be a replacement for existing languages.** It is a research vehicle for testing the hypothesis stated in [`00-motivation.md`](00-motivation.md). Its production viability depends on empirical results.
- **The thesis is not claimed to be proven.** It is the design hypothesis under test. The research plan in [`research-plan.md`](research-plan.md) describes the experiments that would establish or refute it.

## References

**Outgoing references:**
- [`00-motivation.md`](00-motivation.md) — the motivation behind the thesis
- [`01-prior-art.md`](01-prior-art.md) — related work
- [`ADR-001-graph-not-text.md`](decisions/ADR-001-graph-not-text.md) — graph-native decision
- [`ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md) — no-projection decision
- [`ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md) — content-addressing decision
- [`ADR-004-effects-as-edges.md`](decisions/ADR-004-effects-as-edges.md) — effects decision
- [`design/node-algebra.md`](design/node-algebra.md) — node and edge specification
- [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) — effect system specification
- [`design/security-model.md`](design/security-model.md) — security properties and threats
- [`design/state-machines.md`](design/state-machines.md) — long-running computation
- [`research-plan.md`](research-plan.md) — evaluation methodology

**Incoming references:**
- [`README.md`](README.md)
- [`00-motivation.md`](00-motivation.md)
- [`01-prior-art.md`](01-prior-art.md)
- [`decisions/ADR-001-graph-not-text.md`](decisions/ADR-001-graph-not-text.md)
- [`decisions/ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md)
- [`decisions/ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md)
- [`decisions/ADR-004-effects-as-edges.md`](decisions/ADR-004-effects-as-edges.md)
- [`decisions/ADR-006-per-node-encryption.md`](decisions/ADR-006-per-node-encryption.md)
- [`decisions/ADR-007-state-machines.md`](decisions/ADR-007-state-machines.md)
- [`decisions/ADR-008-compilation-target.md`](decisions/ADR-008-compilation-target.md)
- [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md)
- [`design/state-machines.md`](design/state-machines.md)
- [`design/security-model.md`](design/security-model.md)
- [`design/distribution-model.md`](design/distribution-model.md)
- [`research-plan.md`](research-plan.md)
- [`decisions/ADR-009-structured-outputs.md`](decisions/ADR-009-structured-outputs.md) — outputs as the program/non-Strand boundary
- [`design/rendering-and-views.md`](design/rendering-and-views.md) — how programs become user-facing artifacts
