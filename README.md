# Strand

A programming language for AI agents to generate, not for humans to author.

Strand programs are content-addressed graphs of typed nodes with mandatory effect declarations. The artifact of record is the verified graph: there is no canonical text syntax and no character-stream serialization the language treats as source. Agents emit compact text projections — Layer A, and the familiar-shaped Layer F — that lower mechanically to the canonical graph before verification, and these projections carry no program identity. The project is a research investigation into whether a language whose representation is built around how large language models operate produces measurably better outcomes for AI-generated software than text-based languages adapted for AI use.

## What Strand is

Strand is a different *representation* of programs, designed around the operational characteristics of LLMs rather than the visual and ergonomic needs of human readers. The hypothesis is not that Strand is a more compact serialization of code, but that it gives an AI agent structurally different surfaces to reason against.

Syntactic errors are not a category — a graph operation either succeeds or fails with a structural reason. Refactoring is mechanical because references are by content hash, so renaming changes one label rather than every reference site. Effect analysis is a graph traversal rather than a whole-program inference, because every effect a node performs is declared as a typed edge. Distribution and capability requirements fall out of the effect system, so they are statically computable rather than recovered by analyzing function bodies. State machines are graph fixpoints, so long-running programs and pure computation share one algebraic foundation.

Whether these properties produce measurably better correctness, analyzability, security, and inference cost is the question the project is investigating. The five integrated design claims are stated in [`02-core-thesis.md`](02-core-thesis.md); the motivation for each is in [`00-motivation.md`](00-motivation.md).

## Major benefits

These are the concrete consequences of the representational choices above. Each is realized in the reference implementation and has corresponding spec, test, and corpus material.

**The agent-emit / verifier-admit loop.** An LLM emits a graph; the verifier admits or rejects it with structured errors *before* execution; only admitted graphs run. Type errors, capability violations, schema-invariant breaks, and unbound references are not runtime categories — they are admission failures with machine-readable diagnostics the agent can iterate against. The dynamic-cost evaluation framework ([`evaluation/dynamic/`](evaluation/dynamic/)) measures the cost of this loop directly: tokens-per-successful-task across an agent's retry loop, not just first-emission token count.

**Mandatory effect declarations + capability-bounded execution.** Every node declares the effect categories it performs through typed edges. The verifier computes effect closures by graph traversal; programs that exercise effects beyond what the calling context grants are rejected statically. At runtime, the capability context is structured — `Network.Connect{host: "api.example.com", port: 443}` is more specific than `Network.Connect{host: *, port: *}`, and refinement matching enforces least-privilege at every call site. A program can be *provably* incapable of effects outside its declared signature. See [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md).

**Content-addressed program structure.** Programs are Merkle DAGs with BLAKE3 hashes computed over canonical CBOR. Two structurally-identical programs hash identically regardless of authoring path, so refactoring renames one label rather than every reference site, identical subgraphs deduplicate automatically, and programs can be admitted, denied, or attested by hash at policy boundaries. The hash is the program's identity. See [`decisions/ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md).

**Algebraic effect handlers.** A `Handler` node intercepts every Application within its body that performs a declared effect category, replacing the call with a user-supplied function. Innermost handler wins on nesting. The handler's effect closure is computed by subtraction — wrapping a body in a handler that intercepts `Time.Now` *removes* `Time.Now` from the surrounding effect set. This makes test-time mocking, deterministic replay, and capability sandboxing first-class language constructs rather than ad-hoc library patterns. See [`proposals/implemented/effect-handlers.md`](proposals/implemented/effect-handlers.md).

**First-class state machines.** Long-running computation is modeled as event-stream actors via N-027 / N-028 / N-029 nodes. The runtime supplies a synchronous trace executor (for replay-determinism testing), an async multi-machine actor implementation (per-machine coroutine, channel-based stream wiring, select-based multi-input merge), bounded-queue overflow policies, in-band supervision via `StateMachine.Spawn` / `Terminate` effects, content-addressed snapshot and replay-from-log, and broadcast fan-out / multi-producer fan-in. Long-running services and pure computation share one algebraic foundation rather than living in separate frameworks. See [`design/state-machines.md`](design/state-machines.md) and [`proposals/implemented/state-machines-runtime-step-3.md`](proposals/implemented/state-machines-runtime-step-3.md).

**Schemas with verifier-checked invariants.** A `Schema` node attaches predicates to a value type; invariants on statically-known values are evaluated at verify time and surface as `SchemaInvariantViolation` *before* the program runs. `PositiveInt`, `NonEmptyList`, `UniqueKeyJsonObject`, and similar refined types are expressible without dependent types — the invariant body is just a `(T) -> Bool` Lambda that the verifier interprets. Non-static values produce deferred-check diagnostics that an enforcing runtime can pick up. See [`proposals/implemented/schema-and-invariant.md`](proposals/implemented/schema-and-invariant.md).

**Layer A density text for emission cost.** Agents don't emit the verbose canonical dag-json directly; they emit a compact line-oriented text projection (Layer A density v4) that an always-on Elaborator compiles to canonical form, filling in inferable fields like effect closures, type arguments, and parameter types across eleven bidirectional inference cases. The projection is purely an emission convenience — the verifier and runtime only ever see canonical graphs. Static byte cost is **0.81× Python+type-hints** geomean on the three-task evaluation MVP. A second authoring surface, Layer F — a fully typed TypeScript-shaped dialect that lowers to the same canonical form — is shipping incrementally (step 1 landed); it trades byte compactness for model familiarity and is measured against Layer A through the same harness. See [`proposals/implemented/llm-authoring-layer.md`](proposals/implemented/llm-authoring-layer.md), [`proposals/implemented/layer-a-density.md`](proposals/implemented/layer-a-density.md), and [`proposals/familiar-surface-lowering.md`](proposals/familiar-surface-lowering.md).

**Embeddable runtime, durable services, and run-by-hash.** The runtime is a `StrandRuntime` facade taking a per-instance `HostPolicy`, so one host can run multiple agent-generated programs concurrently with isolated sandbox, limits, clock, and credentials — the substrate a multi-tenant host admitting untrusted programs requires. Machine groups run as long-lived services whose state survives a process restart through content-addressed snapshots, governed by per-event budgets rather than a single whole-run limit. An on-disk hash-keyed store provides admit-and-verify-once and run-by-hash, so content addressing pays off across runs and a resolved registry name dereferences to a runnable program. See [`proposals/implemented/embeddable-runtime.md`](proposals/implemented/embeddable-runtime.md), [`proposals/implemented/long-running-groups.md`](proposals/implemented/long-running-groups.md), and [`proposals/implemented/persistent-store.md`](proposals/implemented/persistent-store.md).

## What this is not

**Strand is not designed for human reading.** There is no canonical projection from graph to text. [`ADR-002`](decisions/ADR-002-no-human-projection.md) examines this decision in detail. For human inspection contexts — failure forensics, security audit, regulatory review — Strand provides analysis tooling (graph queries, dependency visualizations, structured diffs) rather than textual rendering. Building a high-quality projection layer is a multi-year engineering effort that the research framing does not require.

**Strand is not a token-reduction grammar for an existing language.** Approaches like SimPy, ShortCoder, and Token Sugar modify Python's surface syntax to reduce LLM token consumption while preserving Python's semantics; these establish a useful lower bound on the benefit of AI-oriented language design. Strand's hypothesis is that the larger improvements come from changing the representation, not from compressing the existing one. The Layer A authoring projection happens to measure below conventional-language baselines on static byte cost for the three programs tested so far, but compactness is a side effect of the representational shift, not the thesis.

**Strand is not claimed to be performant in absolute terms, nor a replacement for existing languages.** Initial implementations prioritize correctness and analyzability over runtime performance. Strand is a research vehicle for testing the hypothesis in [`00-motivation.md`](00-motivation.md); its production viability depends on empirical results.

## Status

The design corpus is substantially complete: nine architectural decision records (ADR-001 through ADR-009) and seven design specifications covering node algebra, effects and capabilities, state machines, encryption, security, distribution, and rendering. The full document tree and identifier registry are in [`INDEX.md`](INDEX.md).

The reference implementation lives under [`impl-kotlin/`](impl-kotlin/). It runs on the JVM as bytecode; a Rust port of the bytecode VM is planned for Milestone 2.3 per [`ADR-008`](decisions/ADR-008-compilation-target.md) and will not change the source-level language. Currently shipped:

| Layer | Scope | Status |
|-------|-------|--------|
| 1 | Pure computation core — literals, types, lambda calculus, System F polymorphism, NodeRef | Complete |
| 2 | Content addressing — BLAKE3 over canonical CBOR, NodeRef-by-hash | Complete |
| 3 | Effects and capabilities — refinement-lattice matching, no-continuation effect handlers | Complete |
| 4 | Foreign function interface — in-process builtins (real filesystem, sockets, HTTP, process, time, hashing, math, string, bytes, regex, logging, OS state, exit); sandboxed bindings deferred to Milestone 2.4 | Step 2 |
| 5 | Match patterns, fixpoint recursion, product and sum values, recursive types | Complete |
| 6 | State machines — sync trace runtime, async multi-machine actors, backpressure, supervision, snapshots, broadcast streams | Complete |
| 7 | Schema with verifier-checkable invariants; JSON (flat + nested-array/object), plain-text, and Markdown blessed libraries | Step 1 |

A bytecode VM under [`impl-kotlin/bytecode/`](impl-kotlin/bytecode/) and [`impl-kotlin/vm/`](impl-kotlin/vm/) achieves interpreter-equivalence on 57 of 58 corpus programs. A four-layer LLM authoring stack under [`impl-kotlin/authoring/`](impl-kotlin/authoring/) — compact text projection, grammar-constrained decoding, bidirectional elaboration with eleven inference cases, and the canonical verifier — closes the gap between agent-friendly emission and the on-disk graph form. The implicit-prelude reserves 76 short names for primitive types, common builtins, and effect categories so agent emissions skip 2-3 nodes per stdlib reference.

The evaluation framework under [`evaluation/`](evaluation/) measures both static and dynamic cost as ratios against the same programs written in Python with type hints. Current results:

- **Static** (bytes-per-emission, three-task MVP): Layer A density v4 at **0.81× Python+type-hints geomean**, below baseline on every task without any tokenizer-side fine-tuning. The canonical dag-json storage form costs 5.82× Python by the same measure — verbose by design, optimized for verification and content-addressing rather than emission; the 5.82× → 0.81× gap quantifies what Layer A and the Elaborator together buy.
- **Dynamic** (tokens-per-successful-task across the agent retry loop, ten-task suite, fresh-context subagents, no caching): **9/10 first-pass / 10/10 converged** vs Python's 10/10 / 10/10, and per-emission output is **16% leaner than Python** (1,180 vs 1,412 tokens across the suite). The headline total-token ratio is 6.56× Strand/Python, but this is **almost entirely the per-cell cost of teaching the language to the model in the system prompt** (10.1k Strand vs 1.5k Python per cell) — the per-emission output cost where Strand already wins is dwarfed by the fixed prompt overhead at N=1. Two amortization paths reduce this to near-zero independently: **prompt caching** (Anthropic's `cache_control` markers, GA on Sonnet 4.7) drops the system-prompt cost ~10× on every second-and-later sample, putting Strand below Python at N≥2; and **LLM training on Strand corpora** would let a future model emit Strand without an in-context language reference at all, collapsing the gap entirely. The 6.56× number is the worst case — a cold, fresh, untrained model on a single sample with no caching. See [`evaluation/dynamic-results.md`](evaluation/dynamic-results.md) for the full per-task breakdown.

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

1. [`impl-kotlin/README.md`](impl-kotlin/README.md) — module layout, layer scope, JSON schema
2. [`design/node-algebra.md`](design/node-algebra.md) — node types and well-formedness rules the verifier implements
3. [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) — the effect system specification

For navigation and current state:

- [`INDEX.md`](INDEX.md) — document tree, alphabetical concept index, identifier registry (ADR-NNN, N-NNN, E-NNN, Q-NNN)
- [`open-questions.md`](open-questions.md) — unresolved design issues
- [`proposals/README.md`](proposals/README.md) — draft and implemented proposals

## Document organization

The corpus is organized into six areas, from most general to most specific:

| Area | Location | Purpose |
|------|----------|---------|
| Overview | Top-level numbered documents (`00-*`, `01-*`, `02-*`) | Motivation, prior art, core thesis |
| Decisions | `decisions/ADR-*.md` | Atomic architectural decisions with rationale |
| Design | `design/*.md` | Detailed specifications of major components |
| Meta | `INDEX.md`, `open-questions.md`, `research-plan.md`, `proposals/` | Navigation, research state, draft proposals |
| Implementation | `impl-kotlin/` | Reference implementation (JVM bytecode) |
| Evaluation | `evaluation/` | Static-cost measurement framework |

Design documents use several conventions to remain navigable. Stable identifiers never change once assigned (ADR-NNN, N-NNN, E-NNN, Q-NNN). Major sections carry explicit anchors (`{#anchor-name}`) for cross-document linking. Each document ends with a References section listing outgoing and incoming citations, maintained in both directions. The master index at [`INDEX.md`](INDEX.md) provides three views of the corpus: an alphabetical concept index, a document tree, and the identifier registry. Unresolved questions live in [`open-questions.md`](open-questions.md) with Q-NNN identifiers and are referenced by id from spec documents, rather than carried as inline caveats.

## Voice and authority

Design documents are written in neutral specification voice — they describe the design as it currently stands, not the process by which it was reached. The design is provisional in the sense that implementation work continues to surface issues; revisions are recorded in each document's history. These conventions apply to documents in the design corpus (the root, `decisions/`, `design/`). They do not apply to implementation code under `impl-kotlin/`, which follows the host language's idioms.

## License

Strand is licensed under the Apache License, Version 2.0. See [LICENSE.md](LICENSE.md) for the full text.

## References

This document is the entry point. Outgoing references: see the Reading order and Document organization sections above. Incoming references: every other document in the corpus links back to this README.
