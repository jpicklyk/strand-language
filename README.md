# Strand

A programming language for AI agents to generate, not for humans to author.

Strand programs are content-addressed graphs of typed nodes with mandatory effect declarations. There is no concrete text syntax, no parser, no file format that contains program source as character data. The project is a research investigation into whether a language whose representation is built around how large language models operate produces measurably better outcomes for AI-generated software than text-based languages adapted for AI use.

## What Strand is

Strand is a different *representation* of programs, designed around the operational characteristics of LLMs rather than the visual and ergonomic needs of human readers. The hypothesis is not that Strand is a more compact serialization of code, but that it gives an AI agent structurally different surfaces to reason against.

Syntactic errors are not a category — a graph operation either succeeds or fails with a structural reason. Refactoring is mechanical because references are by content hash, so renaming changes one label rather than every reference site. Effect analysis is a graph traversal rather than a whole-program inference, because every effect a node performs is declared as a typed edge. Distribution and capability requirements fall out of the effect system, so they are statically computable rather than recovered by analyzing function bodies. State machines are graph fixpoints, so long-running programs and pure computation share one algebraic foundation.

Whether these properties produce measurably better correctness, analyzability, security, and inference cost is the question the project is investigating. The five integrated design claims are stated in [`02-core-thesis.md`](02-core-thesis.md); the motivation for each is in [`00-motivation.md`](00-motivation.md).

## What this is not

**Strand is not designed for human reading.** There is no canonical projection from graph to text. [`ADR-002`](decisions/ADR-002-no-human-projection.md) examines this decision in detail. For human inspection contexts — failure forensics, security audit, regulatory review — Strand provides analysis tooling (graph queries, dependency visualizations, structured diffs) rather than textual rendering. Building a high-quality projection layer is a multi-year engineering effort that the research framing does not require.

**Strand is not a token-reduction grammar for an existing language.** Approaches like SimPy, ShortCoder, and Token Sugar modify Python's surface syntax to reduce LLM token consumption while preserving Python's semantics; these establish a useful lower bound on the benefit of AI-oriented language design. Strand's hypothesis is that the larger improvements come from changing the representation, not from compressing the existing one. The Layer A authoring projection happens to measure below conventional-language baselines on static byte cost for the three programs tested so far, but compactness is a side effect of the representational shift, not the thesis.

**Strand is not claimed to be performant in absolute terms, nor a replacement for existing languages.** Initial implementations prioritize correctness and analyzability over runtime performance. Strand is a research vehicle for testing the hypothesis in [`00-motivation.md`](00-motivation.md); its production viability depends on empirical results.

## Status

The design corpus is substantially complete: nine architectural decision records (ADR-001 through ADR-009) and seven design specifications covering node algebra, effects and capabilities, state machines, encryption, security, distribution, and rendering. The full document tree and identifier registry are in [`INDEX.md`](INDEX.md).

A Kotlin/JVM reference implementation lives under [`impl/`](impl/). Currently shipped:

| Layer | Scope | Status |
|-------|-------|--------|
| 1 | Pure computation core — literals, types, lambda calculus, System F polymorphism, NodeRef | Complete |
| 2 | Content addressing — BLAKE3 over canonical CBOR, NodeRef-by-hash | Complete |
| 3 | Effects and capabilities — refinement-lattice matching, no-continuation effect handlers | Complete |
| 4 | Foreign function interface — in-process builtins; sandboxed bindings deferred to Milestone 2.4 | Step 1 |
| 5 | Match patterns, fixpoint recursion, product and sum values, recursive types | Complete |
| 6 | State machines — sync trace runtime, async multi-machine actors, backpressure, supervision, snapshots, broadcast streams | Complete |
| 7 | Schema with verifier-checkable invariants; JSON, plain-text, and Markdown blessed libraries | Step 1 |

A bytecode VM under [`impl/bytecode/`](impl/bytecode/) and [`impl/vm/`](impl/vm/) achieves interpreter-equivalence on 57 of 58 corpus programs. A four-layer LLM authoring stack under [`impl/authoring/`](impl/authoring/) — compact text projection, grammar-constrained decoding, bidirectional elaboration with eleven inference cases, and the canonical verifier — closes the gap between agent-friendly emission and the on-disk graph form.

A three-task evaluation suite under [`evaluation/`](evaluation/) measures static byte cost as a ratio against the same programs written in Python with type hints, averaged geometrically across the three tasks. The cost an LLM pays at emission is the Layer A cost — Layer A is the compact text projection a model produces, which the Elaborator converts to canonical dag-json on the way into the store. Current results put **Layer A density v4 at 0.81× Python+type-hints geomean**, below baseline on every task in the MVP without any tokenizer-side fine-tuning. The canonical dag-json storage form costs 5.82× Python by the same measure — verbose by design, optimized for verification and content-addressing rather than emission, and what an LLM would produce if it bypassed the projection layer; the 5.82× → 0.81× gap quantifies what Layer A and the Elaborator together buy.

Dynamic metrics — first-pass verification rate, tokens-per-successful-task across an agent's retry loop — remain to be measured and require model-API integration.

The four-phase research plan is documented in [`research-plan.md`](research-plan.md). The current work item is empirical evaluation against the full set of named conventional-language baselines.

## Inspiration and prior art

Strand draws from several research traditions and identifies where it diverges from each. The full survey is in [`01-prior-art.md`](01-prior-art.md); the principal influences are summarized below.

**Content-addressed code.** Unison is the closest existing system and the primary inspiration for Strand's content-addressing decision. The major divergences are that Unison preserves a human-readable surface syntax, requiring substantial engineering for the projection layer, whereas Strand does not; and that Unison's effect system (abilities) is opt-in, whereas Strand's is mandatory and integrated with placement.

**Structured editing.** Hazel demonstrates that direct AST manipulation can replace text editing without loss of expressiveness, with the property that every program is always well-formed. Strand extends this principle from "humans manipulating ASTs" to "agents manipulating graphs," and uses content-addressing where Hazel uses traditional name binding.

**LLM-oriented languages.** Pel is a Lisp-inspired surface for LLM agent orchestration. QUASAR has LLMs generate a Python subset that is then transpiled to a separate execution language designed for parallelism and security. CoRE treats natural language as the program and uses an LLM as the interpreter. These projects share Strand's framing that programming languages can be designed for AI agents rather than adapted for them. Strand differs by abandoning text representation entirely and targeting general-purpose computation rather than orchestration.

**Effect-typed languages.** Koka, Eff, OCaml 5's effect handlers, and Haskell's IO type establish the technical feasibility of static effect tracking and demonstrate its benefits for reasoning about program behavior. Strand draws from this tradition but makes two changes: effects are mandatory rather than optional (there is no equivalent of untracked IO), and effects drive runtime decisions — placement, capability checks, partitioning — rather than serving purely as a static analysis tool.

**Capability-based systems.** E and Pony are programming languages built around object capabilities; seL4 is a formally verified microkernel built on capability-based access control. The capability tradition establishes the security properties Strand aims for. Strand's contribution is integrating capabilities with effect declarations: a capability is the runtime token corresponding to a static effect, which allows the runtime to refuse to evaluate a graph whose effect set exceeds the capabilities held by its execution context.

**Distributed dataflow systems.** Spark, Ray, TensorFlow's graph mode, and Differential Dataflow demonstrate that graph representation is the right shape for distribution. They construct graphs as a secondary representation built from host-language code; Strand makes graph representation primary. This eliminates the impedance mismatch between programmer code and the dataflow graph, but requires the language to handle workloads dataflow systems do not (state machines, long-running services, interactive systems). State machines as graph fixpoints over event streams are how Strand addresses this.

**Token-reduction grammars.** SimPy, ShortCoder, and Token Sugar modify Python's surface syntax to reduce LLM token consumption while preserving Python's semantics, demonstrating that even purely syntactic modifications produce measurable improvements (10–35% token reductions on equivalent tasks). These establish a useful lower bound on the benefit of AI-oriented design. Strand's hypothesis is that abandoning text representation entirely produces substantially larger improvements on the metrics that matter, but this hypothesis must be tested against these baselines rather than against unmodified text languages.

The combination of graph-native source representation, content-addressed node identity, mandatory effect declarations, effect-driven placement, capability-based execution tied to static effects, first-class state machines as fixpoints over event streams, and being designed for AI generation rather than human authorship is, to the project's knowledge, novel as an integrated whole. Individual elements have precedents in the systems described above; the research contribution is the integration and the empirical question of whether it composes into a language that performs better than text languages for AI generation.

## Reading order

For an overview of the design:

1. [`00-motivation.md`](00-motivation.md) — why Strand exists
2. [`02-core-thesis.md`](02-core-thesis.md) — the five central design claims
3. [`01-prior-art.md`](01-prior-art.md) — full survey of related work and Strand's divergences

For the implementation:

1. [`impl/README.md`](impl/README.md) — module layout, layer scope, JSON schema
2. [`design/node-algebra.md`](design/node-algebra.md) — node types and well-formedness rules the verifier implements
3. [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) — the effect system specification

For navigation and current state:

- [`INDEX.md`](INDEX.md) — document tree, alphabetical concept index, identifier registry (ADR-NNN, N-NNN, E-NNN, Q-NNN)
- [`open-questions.md`](open-questions.md) — unresolved design issues
- [`proposals/README.md`](proposals/README.md) — draft and implemented proposals
- [`CONTINUATION.md`](CONTINUATION.md) — project handoff to a new session

## Document organization

The corpus is organized into six areas, from most general to most specific:

| Area | Location | Purpose |
|------|----------|---------|
| Overview | Top-level numbered documents (`00-*`, `01-*`, `02-*`) | Motivation, prior art, core thesis |
| Decisions | `decisions/ADR-*.md` | Atomic architectural decisions with rationale |
| Design | `design/*.md` | Detailed specifications of major components |
| Meta | `INDEX.md`, `open-questions.md`, `research-plan.md`, `proposals/` | Navigation, research state, draft proposals |
| Implementation | `impl/` | Kotlin/JVM reference implementation |
| Evaluation | `evaluation/` | Static-cost measurement framework |

Design documents use several conventions to remain navigable. Stable identifiers never change once assigned (ADR-NNN, N-NNN, E-NNN, Q-NNN). Major sections carry explicit anchors (`{#anchor-name}`) for cross-document linking. Each document ends with a References section listing outgoing and incoming citations, maintained in both directions. The master index at [`INDEX.md`](INDEX.md) provides three views of the corpus: an alphabetical concept index, a document tree, and the identifier registry. Unresolved questions live in [`open-questions.md`](open-questions.md) with Q-NNN identifiers and are referenced by id from spec documents, rather than carried as inline caveats.

## Voice and authority

Design documents are written in neutral specification voice — they describe the design as it currently stands, not the process by which it was reached. The design is provisional in the sense that implementation work continues to surface issues; revisions are recorded in each document's history. These conventions apply to documents in the design corpus (the root, `decisions/`, `design/`). They do not apply to implementation code under `impl/`, which follows standard Kotlin idioms.

## License

Strand is licensed under the Apache License, Version 2.0. See [LICENSE.md](LICENSE.md) for the full text.

## References

This document is the entry point. Outgoing references: see the Reading order and Document organization sections above. Incoming references: every other document in the corpus links back to this README.
