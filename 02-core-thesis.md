# Core Thesis {#core-thesis}

**Document:** `02-core-thesis.md`
**Status:** Stable
**Last revised:** 2026-06-13

## Summary

Strand is organized around five integrated design claims. Each claim is independently defensible but produces its strongest results in combination with the others. This document states each claim, describes what it entails, and identifies the documents where the claim is examined in detail. The claims are presented in dependency order: each subsequent claim assumes the preceding ones.

## Claim 1: Programs are graphs, not text {#claim-graph-native}

A Strand program is a directed graph of typed, content-addressed nodes, and the graph is the canonical form: it is what is stored, what is verified, what is hashed, and what executes. No text form carries program identity. There is no canonical concrete syntax and no character-stream serialization that the language treats as source. Programs are constructed by graph operations: creating nodes, attaching typed edges, and verifying well-formedness.

The claim concerns the artifact of record, not the absence of characters from the toolchain. The implemented authoring stack has agents emit Layer A, a compact line-oriented text projection compiled to canonical dag-json before any node reaches the verifier ([Q-034](open-questions.md#Q-034)); a second authoring surface, Layer F, a familiar-shaped typed dialect, lowers to the same canonical form ([Q-061](open-questions.md#Q-061)). Authoring surfaces of this kind are disposable projections with no canonical status: no hash is computed over them, verification never operates on them, and they may change or be replaced without affecting the identity of any program. The distinctive property this claim asserts is that the verified graph is the program; every downstream guarantee (Claims 3 through 5) attaches to the graph and to nothing upstream of it.

This claim has several immediate consequences:

- Syntactic errors do not exist as a category of the canonical form. A graph operation either succeeds, producing a well-formed addition to the graph, or fails with a structural reason. An authoring projection may fail to parse, but that failure is a failure to produce a graph; nothing syntactically malformed can be stored, addressed, or verified.
- Source ordering does not exist. Definitions, references, and types form a graph; the concept of "what comes before what in the source" does not apply.
- Names are not primary. Nodes are identified by content hash; human-meaningful names, where present, are metadata edges attached to nodes for tooling purposes, not part of program identity.

The claim is examined in [`ADR-001-graph-not-text.md`](decisions/ADR-001-graph-not-text.md). The detailed node algebra is specified in [`design/node-algebra.md`](design/node-algebra.md).

## Claim 2: Programs are not designed for human reading {#claim-no-projection}

Strand does not provide a canonical projection from graph form to text. Programs are read by humans through analysis tools — graph queries, dependency visualizations, structured diffs — rather than by inspecting a textual rendering. The decision is not that graphs cannot be projected to text, but that providing such a projection as a primary interface is not a design goal and the engineering effort to do so well is not undertaken.

The claim is about canonical status and audience, not about whether text appears in the toolchain. The Layer A authoring projection of Claim 1 exists for agent emission and agent reading, not for human review, and it holds no privileged position: no rendering of a program — Layer A included — is authoritative, none participates in verification or identity, and any of them can be regenerated from the graph or discarded. What Strand omits is a projection designed and maintained as a primary interface for human reading.

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

## The strongest alternatives {#strongest-alternatives}

The lead claim — that the maximum harm of agent-generated code is computable and bounded before execution — is not unique to Strand as a goal. The thesis is tested most honestly against the strongest competing approaches to that goal, not only against the unconfined conventional baseline measured in [`evaluation/containment-results.md`](evaluation/containment-results.md). Two families come closest; both are surveyed in [`01-prior-art.md`](01-prior-art.md).

**Capability-checked readable languages.** Scala 3's capture checking (the Caprese project) brings capability tracking into the type system of a mainstream language, and Odersky et al. apply it directly to the agent threat model, deriving static effect and leakage bounds over agent-generated code ("Tracking Capabilities for Safer Agents," arXiv 2603.00991, 2026). Flix makes effect tracking mandatory rather than opt-in for every function. These systems deliver static effect bounds and capability confinement — a substantial fraction of what Claims 3 and 5 provide — inside languages with deep training-corpus presence, mature toolchains, and a surface syntax a human reviewer reads directly.

**Runtime capability mediation without a new language.** CaMeL (arXiv 2503.18813) attaches capabilities to data values and executes agent-emitted code in a restricted-Python interpreter, bounding what an injected instruction can cause a program to do without changing the language the model emits. The sandboxed code-execution pattern generalizes the same move: agent-generated TypeScript or Python runs inside a capability-scoped execution environment, and the operative harm bound is the environment's grant rather than any property of the code.

Both families reach much of the containment the conventional baseline lacks, and they retain what Strand gives up: the model emits a language it is already fluent in, the existing library and tooling ecosystem applies, and the generated artifact is text a human can review directly when review is wanted. Against these alternatives, the graph-native, content-addressed form must buy something specific. The thesis holds that it buys four properties.

First, verification operates on the artifact itself at admission. A Strand store verifies the graph it received: well-formedness and the effect closure are recomputed from the received bytes before any node is admitted, so the consumer of a program establishes the guarantee over exactly the artifact it will execute, with no trust required in the producer's compiler, build configuration, or toolchain version. In a text language the corresponding guarantee is a property of a compilation run the consumer did not witness; relying on it means re-running the toolchain over the source, trusting an attestation of someone else's run, or trusting the producer.

Second, the harm bound is computable from the stored artifact at any later time. The bound defined in [`evaluation/containment-results.md`](evaluation/containment-results.md) is a function of the graph and its capability context, per subgraph, evaluable whenever the question is asked — at admission, at audit, at an incident postmortem — without the source, the build environment, or the producer's cooperation. In the text-language alternatives the analogous bound is established at the original compile; recovering it later requires recovering the toolchain and the exact source that produced the deployed artifact.

Third, content addressing makes the verified artifact's identity stable across federation. A subgraph verified once is identified by its hash on every store that holds it ([`ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md)), which is what makes admit-once semantics and signed-manifest distribution coherent ([Q-043](open-questions.md#Q-043)): the artifact a signature attests is byte-identical to the artifact every consumer holds and can re-verify. Verified fragments of a text-language program have no comparable identity — names, versions, and build products vary independently of content.

Fourth, per-node encryption and effect-driven placement — the long-horizon claims of [§integration](#integration) — have no equivalent in either family. Both depend on programs being graphs of discretely identified, individually effect-annotated nodes: the unit of encryption and the unit of placement is the node, and neither a text-language compilation unit nor a sandboxed script decomposes that way.

What the alternatives provide and Strand does not is equally part of the comparison. Model fluency is the largest item: a model emits Scala, TypeScript, or Python at training-corpus fluency with no in-context grammar to teach, and the measured cost asymmetry in [`evaluation/dynamic-results.md`](evaluation/dynamic-results.md) is dominated by exactly that gap. Ecosystem is the second: decades of libraries, editors, and analysis tooling apply to the readable alternatives, and none of it applies to Strand. Human review is the third: where a human chooses to read the generated code, the alternatives produce an artifact made for reading. The comparison the thesis stakes is therefore narrow: where the consumer of generated code is not its producer, where the bound must be re-derived from the artifact long after generation, and where verified components must retain identity across distribution, the graph-native form provides properties that neither capability-checked text languages nor runtime mediation provides. Where those conditions do not hold, the alternatives are stronger on every remaining axis.

## Outcome claims and evaluation priority {#outcome-priority}

The five design claims above are predicted to produce five outcome advantages for AI-generated code, enumerated in [`00-motivation.md`](00-motivation.md): higher first-pass correctness, lower inference cost per task, stronger security guarantees, native distribution, and cleaner confidential-computing integration. These outcomes are not weighted equally as evaluation targets. The ordering below reflects which advantages derive most directly from the design claims and which are most distinctive relative to the conventional languages used for AI generation.

**Lead claims: structural safety and first-pass correctness.** The most distinctive advantages are those a conventional language cannot reproduce after the fact. Static effect verification and capability-mediated execution (Claims 3 and 5) make the maximum harm of a generated subgraph computable and bounded before it executes; structural verification at every operation (Claims 1 and 3) is the basis for first-pass correctness. A program an agent generates and a runtime executes without human review is contained by construction, rather than by a sandbox imposed around opaque code after generation. No conventional language used for AI generation provides a sound equivalent: effect tracking and capability confinement are absent or opt-in, and post-hoc static analysis of generated text cannot recover the guarantees soundly. These are the outcomes against which Strand is primarily evaluated; the Q-044 measurement ([`evaluation/containment-results.md`](evaluation/containment-results.md)) defines the harm bound, argues its soundness, and shows the conventional baseline containing none of the measured harm classes by default. The two lead outcomes are not equally supported by present measurement. The containment matrix is an executed result; first-pass correctness is not: the Run 7 measurement in [`evaluation/dynamic-results.md`](evaluation/dynamic-results.md) recorded Strand first-pass verification at 11 of 15 tasks against 15 of 15 for both conventional baselines, with the predicted verifier-feedback advantage not yet isolated by the task suite. First-pass correctness therefore remains a claim under test, not a result.

**Constraint, not lead claim: inference cost.** Lower token cost per task is the least distinctive of the predicted advantages. The Q-021 measurement ([`evaluation/dynamic-results.md`](evaluation/dynamic-results.md)) establishes why. A token-cost comparison against conventional languages is confounded by model familiarity: a model carries extensive pretraining exposure to those languages and none to Strand, so the comparison measures prior exposure as much as representational density, and the observed gap is dominated by the cost of teaching the language in context. Even where the teaching cost is amortized toward zero, the per-emission density advantage over the densest conventional baselines is marginal. Inference cost is therefore treated as a constraint to be bounded — kept within a practical multiple through prompt caching and skill-mediated emission — not as a headline result.

**Long-horizon claims: distribution and confidential computing.** Native distribution and confidential-computing integration follow from the integrated claims (see [§integration](#integration)) but depend on runtime and hardware work scheduled for later milestones. They remain predicted advantages under test, not present results.

## What this thesis does not claim {#non-claims}

To avoid overstatement, several things are explicitly not claimed:

- **Strand is not claimed to be human-friendly.** It is claimed to be amenable to AI generation and to provide analysis tooling for human inspection. Direct human authorship is not a goal.
- **Strand is not claimed to be performant in absolute terms.** Initial implementations will prioritize correctness and analyzability over runtime performance. Performance work is deferred to a later phase.
- **Low inference cost is not claimed as a lead advantage.** Token cost per task is treated as a constraint to be bounded rather than a headline result; see [§outcome-priority](#outcome-priority). The distinctive claims are structural safety and first-pass correctness.
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
- [`evaluation/dynamic-results.md`](evaluation/dynamic-results.md) — dynamic-cost measurement underlying the inference-cost re-weighting (Q-021)
- [`evaluation/containment-results.md`](evaluation/containment-results.md) — containment measurement substantiating the structural-safety lead claim (Q-044)
- [`open-questions.md`](open-questions.md) — Q-034 (authoring projection), Q-043 (federation and manifests)

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
- [`evaluation/containment-results.md`](evaluation/containment-results.md) — measurement of the structural-safety lead claim
