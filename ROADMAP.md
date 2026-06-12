# Strand implementation roadmap {#roadmap}

**Document:** `ROADMAP.md`
**Status:** Living document; enumerates the implementation work remaining to make Strand a usable language

## Purpose

This document enumerates the capabilities Strand must still implement to become a usable language, ordered by priority, with each item pointing to the authoritative definition of the work. A session can be directed to a tier or an item by number and will find here what the work is, why it sits where it does, and which design document, open question, or proposal specifies it.

The file defines what must be built and in what order. It does not track completion status. Whether an item has shipped is derived from its referenced identifier: an open question's status in [`open-questions.md`](open-questions.md), and whether the corresponding proposal sits in [`proposals/`](proposals/README.md) as a draft or in `proposals/implemented/` as shipped. Keeping status out of this file is deliberate — status is the high-frequency, parallel-contended state, and it already has an authoritative home.

## Maintenance under parallel work

This file is designed so that concurrent work streams do not contend on it.

- Status is derived, not stored. A session checks whether an item is done by reading its referenced Q-NNN and proposal location, never a status field here.
- Items are independent and individually scoped. Two sessions advancing different items edit different lines, so changes merge without conflict.
- The only edit an item warrants is removal, performed when the work ships, in the same pass that marks the Q-NNN Resolved and moves the proposal to `proposals/implemented/` — mirroring the existing convention that `INDEX.md` is updated in the same pass that assigns an identifier. Completed work is recorded by that Resolved status and that proposal location, not re-enumerated here.

A new capability gap discovered during work is added as an item under the appropriate tier, with a pointer to where it is specified, or a note that it needs an open question registered on pickup.

## Ordering rationale

The tier order follows the thesis outcome-priority in [`02-core-thesis.md`](02-core-thesis.md) (section outcome-priority). The lead claims are structural safety and first-pass correctness. Structural safety is already substantiated at the measurement level by the Q-044 containment matrix; first-pass correctness is not yet measured. Inference cost is treated as a constraint to be bounded, not a headline result. Native distribution and confidential computing are long-horizon claims that depend on later runtime and hardware work. The tiers descend from validating the remaining lead claim, through the expressiveness needed to make that validation representative, to the long-horizon foundations and opportunistic ecosystem breadth.

## Tier 1 — Validate the remaining lead claim: first-pass correctness

Structural safety, the first lead claim, is measured ([Q-044](open-questions.md#Q-044)). First-pass correctness, the second, is not. Both ride the same harness, so this is the highest-leverage remaining work.

- **Dynamic measurement of first-pass correctness and bounded cost.** Defined by [Q-021](open-questions.md#Q-021), [Q-034](open-questions.md#Q-034) (dynamic half), and [`proposals/model-api-integration.md`](proposals/model-api-integration.md). Real model-API integration drives an agent through the verifier-feedback loop and reports first-pass verification rate (the lead-claim metric), tokens-per-successful-task (the bounded constraint), and the Q-044 intent-visibility follow-up. The harness, two backends, a Python adapter, twenty-two tasks (seven semantic-error probes added 2026-06-11), real tokenizer counting, and N>1 bootstrap aggregation have shipped; remaining are executing the multi-sample sweep, the prompt-caching measurement, and the four other baselines (Kotlin Coroutines, Rust, TypeScript-strict, SimPy).

## Tier 1.5 — Authoring economics

The Tier 1 measurement is only as meaningful as the authoring pipeline it measures: the current cost column reflects an unoptimized teaching prompt, and the first-pass column reflects a maximally unfamiliar surface. These items reshape the cost side of the same harness; each is gated on its N>1 statistics, so they sharpen rather than confound the Tier 1 result.

- **Authoring-cost reduction program.** Defined by [Q-060](open-questions.md#Q-060) and [`proposals/authoring-cost-reduction.md`](proposals/authoring-cost-reduction.md). Prompt caching in the harness, a minimal-core system prompt with on-demand references, grammar-constrained decoding over the shipped Layer B GBNF, and Layer A density v5 — projected to bound tokens-per-successful-task within a 2.5–3.5× multiple of the Kotlin baseline without fine-tuning.
- **Familiar-shaped authoring surface.** Defined by [Q-061](open-questions.md#Q-061) and [`proposals/familiar-surface-lowering.md`](proposals/familiar-surface-lowering.md). Layer F, a fully typed familiar-syntax dialect lowering mechanically to the canonical graph with cross-surface hash equality against Layer A as the correctness property; the Q-021 A/B against Layer A and the conventional baselines is the exit criterion in either direction.

## Tier 2 — Expressiveness ceilings that make the measurement representative

First-pass correctness is measured over a task suite; the suite is only as representative as the programs Strand can express. The module-system ceiling is closed ([Q-043](open-questions.md#Q-043)), as are streaming I/O ([Q-045](open-questions.md#Q-045)), the actor-runtime stream bridge ([Q-046](open-questions.md#Q-046)), and in-language error recovery ([Q-048](open-questions.md#Q-048), N-047 Attempt).

- **Runtime schema enforcement extensions (Layer 7 step 2 continuation).** Defined by the deferred scope of [Q-047](open-questions.md#Q-047) and [`proposals/implemented/runtime-schema-enforcement.md`](proposals/implemented/runtime-schema-enforcement.md) (the foundational slice landed 2026-06-03): ForeignNode-backed checkers (trust model via Q-006), the HTML5/SVG/PDF blessed-library set (HTML5 and SVG blocked on [Q-053](open-questions.md#Q-053)), bytecode-VM enforcement parity (a `CHECK_SCHEMA` lowering), and schema-typed state-machine validation.
- **Pre-1.0 encoding epochs.** Defined by [Q-062](open-questions.md#Q-062) and [`proposals/encoding-epochs.md`](proposals/encoding-epochs.md). Replaces the implicit hash-invariance success criterion with batched, named breaking-change epochs shipped with regenerated conformance vectors. The epoch-2 charter carries the encoding budget for the Q-053 fix below, optional-field normalization, and the Q-049 bound decision.
- **Nested recursive types at value-construction sites.** Defined by [Q-053](open-questions.md#Q-053). Composite recursive structures (`μX. ... List<X> ...`) cannot be constructed directly; the splice workaround loses type precision. Blocks the HTML5/SVG blessed libraries and any agent-authored AST or tree-of-lists shape.
- **Layer A grammar parity.** Defined by [Q-057](open-questions.md#Q-057). Six implemented surfaces (effect projections, module manifests, the EventStream source edge, Sample overflow policy, RecursiveSelf depth, external-hash NodeRefs) are unauthorable in Layer A and therefore unreachable through the documented agent interface; the suite cannot exercise what agents cannot author.

## Tier 3 — Data-dependent structural decisions

These are deliberately deferred until Tier 1 emission data shows what agents actually reach for. Each is a decision as much as an implementation, and the data should drive it.

- **Mutable-state philosophy revisit (E-017).** Defined by the agent-workload analysis recorded under [Q-037](open-questions.md#Q-037) and the deferral note in [`proposals/stdlib-future-builtins.md`](proposals/stdlib-future-builtins.md). Decide whether to expose `Memory.MutableState` directly, keep routing all state through state machines, or introduce scoped mutable cells. Currently settled as absent by design; reopening needs its own proposal and open question.
- **Multi-shot continuations for the Handler node (N-043).** Defined by the deferred follow-ups in [`proposals/implemented/effect-handlers.md`](proposals/implemented/effect-handlers.md) (the no-continuation form is shipped). Extends handlers beyond single-shot intercept-and-replace to multi-shot continuations, enabling generators-via-effects and choice nondeterminism. No dedicated open question is registered yet.
- **Designed-but-deferred effect categories.** Defined by [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) and tracked in [`proposals/stdlib-future-builtins.md`](proposals/stdlib-future-builtins.md). Wire builtins for Crypto.Sign/Encrypt/Decrypt (E-021, E-022, E-023), Filesystem.Watch (E-009), Process.Signal (E-014), and Time.Schedule (E-012). The categories are reserved; the Crypto set carries open design questions (AEAD choice, signature scheme, key derivation) and needs a dedicated proposal.
- **Structural subtyping decision.** Defined by [Q-049](open-questions.md#Q-049). The specification asserted structural subtyping; the verifier has always used strict structural equality (spec corrected 2026-06-10). Decide among effect-set inclusion alone, full structural subtyping, or strict equality with the `bound` field removed. Tier 1 emission data showing where agents hit effect-exact function equality should inform the choice.

## Tier 3.5 — Single-process operational substrate

The 2026-06-10 independent review found the layer between the measurement instrument (Tiers 1–2) and the distributed long horizon (Tier 4) untracked: the work that makes a single-process Strand runtime durable, embeddable, and observable. These items are individually scoped and largely independent; several are prerequisites for Tier 4.

- **Embeddable runtime and per-instance host policy.** Defined by [Q-054](open-questions.md#Q-054). Replace the CLI-only entry point and the process-global `Builtins` singletons with a runtime facade and per-instance policy threading. Prerequisite for any server, daemon, or multi-tenant host, and shapes the Rust VM's host API (Q-017 step 2).
- **Effect-audit log.** Defined by [Q-055](open-questions.md#Q-055). Record declared-versus-performed effects per foreign dispatch, closing the divergence between `design/security-model.md`'s monitoring claims and the implementation, and making the Q-044 containment bound continuously observable.
- **Builtin signature verification.** Defined by [Q-056](open-questions.md#Q-056). Cross-check declared `foreignType` against the in-process registry's known shapes at admission and translate argument-shape failures into structured errors, closing the verified-then-crashes failure shape.
- **Persistent local store and run-by-hash.** Defined by [Q-058](open-questions.md#Q-058). An on-disk hash-keyed store with admit-and-verify-once semantics, making content addressing operative across runs and completing the registry mechanism.
- **Long-running machine groups.** Defined by [Q-059](open-questions.md#Q-059). A canonical `Value` codec, snapshot persistence, a non-batch group driver, and a limits model for long-lived processes — the single-process service story Tier 4's distribution work presumes.
- **Prelude as a content-addressed module.** Defined by [Q-063](open-questions.md#Q-063) and [`proposals/prelude-as-module.md`](proposals/prelude-as-module.md). The implicit prelude generated into a hash-pinned N-046 ModuleManifest with elaborator resolution replacing synthesis — zero hash impact. Removes the prompt-and-elaborator-only existence of the de facto standard library and gives the federation machinery its first permanent resident.
- **Capability-denial observability.** Defined by [Q-064](open-questions.md#Q-064). A structured host-boundary denial outcome (category, refinement parameters, node, instance) for orchestrating principals, preserving in-language uncatchability; likely lands on the Q-054 embedding API and the Q-055 audit-log record kinds.
- **Determinism enforcement.** Defined by [Q-065](open-questions.md#Q-065). Per-builtin determinism markers and verifier checks on transition-function and invariant closures, closing the purity-versus-determinism gap beneath the replay and snapshot claims.

## Tier 4 — Long-horizon claims: production foundations

These underpin the distribution and confidential-computing claims, which the thesis holds as predicted advantages under test rather than present results. They are large in scope and low in research leverage relative to the tiers above.

- **Sandboxed foreign bindings.** Defined by [Q-006](open-questions.md#Q-006), ADR-005, and [`design/security-model.md`](design/security-model.md); a Milestone 2.4 line item. Replaces the in-process `strand-builtin:` trust assumption with WASM, then seccomp- or TEE-backed isolation, so untrusted third-party code can run inside a graph. Q-041 already supplies runtime sandbox observation; the isolation boundary itself remains.
- **Rust production VM.** Defined by [Q-017](open-questions.md#Q-017) step 2 and ADR-008. Ports the shipped Kotlin reference bytecode VM (step 1) to Rust for production performance, inheriting the test corpus as its correctness specification.
- **MLIR dialect.** Defined by [Q-018](open-questions.md#Q-018) and ADR-008; a Milestone 2.8 line item, downstream of the bytecode VM. Defines a Strand dialect that preserves effect and capability information through lowering to native code.
- **Distributed runtime.** Defined by [Q-008](open-questions.md#Q-008), [Q-014](open-questions.md#Q-014), [Q-015](open-questions.md#Q-015), [Q-016](open-questions.md#Q-016), [`design/distribution-model.md`](design/distribution-model.md), and [`design/state-machines.md`](design/state-machines.md). Adds placement-aware scheduling, cross-machine backpressure, network-failure handling during graph fetches, and BEAM-scale lightweight actor spawning across nodes. The synchronous and single-JVM async runtimes are shipped; the multi-node engineering is Milestone 2.5+.

## Tier 5 — Ecosystem breadth

Each item is independent and small, and several can proceed in parallel with any other tier. Pull from here when a specific Tier 1 task or research demonstration needs it.

- **LLM provider breadth.** [Q-037](open-questions.md#Q-037) Phase 2+. Beyond the shipped Anthropic, OpenAI, and Gemini providers: local models (Ollama, llama.cpp), Cohere, Mistral.
- **Vector-store breadth.** [Q-038](open-questions.md#Q-038) Phase 2+. Beyond the shipped Pinecone and Chroma: pgvector, FAISS, Weaviate, Qdrant.
- **Output-format libraries.** Overlaps Tier 2 runtime schema enforcement: HTML5, SVG, and PDF as blessed schemas.
- **Standard-library rounds.** Tracked in [`proposals/stdlib-future-builtins.md`](proposals/stdlib-future-builtins.md): string formatting, set and map extensions, CSV/TSV, URL, compression, and later rounds, each mechanical via the `strand-add-builtin` skill.
- **Retrieval-augmented-generation reference corpus.** [Q-038](open-questions.md#Q-038) Phase 3. A set of reference agent programs composing the LLM and vector-store primitives, with agent-pattern documentation.

## References

**Outgoing references:**
- [`02-core-thesis.md`](02-core-thesis.md) — outcome-priority ordering that the tier order follows
- [`open-questions.md`](open-questions.md) — authoritative status of every referenced Q-NNN
- [`proposals/README.md`](proposals/README.md) — draft and implemented proposals that define the items
- [`INDEX.md`](INDEX.md) — identifier registry and document tree
- [`impl-kotlin/README.md`](impl-kotlin/README.md) — implementation state the items advance

**Incoming references:**
- [`CLAUDE.md`](CLAUDE.md)
- [`INDEX.md`](INDEX.md)
