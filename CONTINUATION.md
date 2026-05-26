# Continuation: Strand Project Handoff

**Purpose:** This document hands off an in-progress project to a new Claude session (Cowork). Read this first, then read the documents it references. The goal is for you to be able to continue the work without requiring the user to re-explain everything.

**Read this document fully before doing anything else.** It contains context, conventions, and judgment calls that aren't in the formal documentation.

---

## What this project is

Strand is a research project to design and build a programming language for AI agents to generate, rather than for humans to author. The central design choice is that programs are content-addressed graphs of typed nodes with mandatory effect declarations, not text. There is no concrete syntax. There is no human-readable projection layer.

The project moved from design phase into implementation phase on 2026-05-23. The design corpus is substantially complete; the active work is now building the reference implementation against that corpus.

The user (Jeff) is working with Anthropic's Claude on this research personally. The project is separate from his commercial work (Android networking, tactical edge, log analysis). It does not need to integrate with or replace any existing system.

## What exists right now

**Wave 1 — high-level framing (complete):**
- `README.md`, `INDEX.md`, `00-motivation.md`, `01-prior-art.md`, `02-core-thesis.md`, `open-questions.md`

**Wave 2 — architectural decision records (complete):**
- `decisions/ADR-001-graph-not-text.md` through `decisions/ADR-009-structured-outputs.md`

**Wave 3 — design specifications (complete):**
- `design/node-algebra.md`, `design/effects-and-capabilities.md`, `design/state-machines.md`, `design/encryption-model.md`, `design/security-model.md`, `design/distribution-model.md`, `design/rendering-and-views.md`
- `research-plan.md` at the root — four-phase empirical evaluation strategy

The full corpus is roughly 2,500 lines of markdown. Read `README.md` and `INDEX.md` first to get the document tree, then read the documents most relevant to whatever task is in front of you. The identifier registry in `INDEX.md` is authoritative for what's been assigned: ADR-001 through ADR-009, N-001 through N-033, E-001 through E-031, Q-001 through Q-028.

**Implementation:** under `impl/`. Whether this directory exists yet depends on when you're reading. If it does, its own README describes current state. If it doesn't, Milestone 2.1 has not yet been scaffolded.

## What needs to happen next

The project is now executing Phase 2 of the research plan. Milestone 2.1 (verifier and reference interpreter) is the active work item. It gates every subsequent milestone and also gates Phase 1 Stage 1.3 (the synthetic corpus generation loop), since that loop requires a verifier to admit or reject candidate graphs.

The implementation plan, in order:

1. **Pure computation core.** Node types N-001 through N-019 (literals, types, lambda, application, let, varref, noderef). Verifier checks well-formedness and infers types. Interpreter evaluates closed expressions. No effects, no foreign nodes, no state machines yet. This is the smallest slice that demonstrates the graph-native idea end-to-end.

2. **Content-addressing and the graph store.** BLAKE3 over canonical CBOR encoding, recursive hashing per ADR-003, an in-memory store keyed by hash, deduplication.

3. **Effects and capabilities.** N-021/N-022, effect closure computation per `design/effects-and-capabilities.md`, capability context in the interpreter. Effects are declared and checked; no IO yet.

4. **ForeignNode (N-020) and a minimal IO surface.** A small set of trusted built-ins in the host language. WASM-sandboxed bindings (Milestone 2.4) come later.

5. **State machines, schemas, invariants.** N-027 through N-033 layered on once 1–4 are stable.

The Stage 1.1 hand-authored seed corpus is built as the test suite for each layer, not as a separate effort. Every test program is also a candidate seed-corpus entry paired with a natural-language description.

## Implementation decisions

These were decided on 2026-05-23 after the design corpus was declared substantially complete. They override or extend the language suggestion in `research-plan.md` for the prototype specifically.

**Implementation language: Kotlin/JVM for the prototype.** The research plan suggests Rust for the production runtime, and that suggestion still stands for the eventual bytecode VM and MLIR work (Milestone 2.3 onward). For Milestone 2.1 specifically, Kotlin was chosen for iteration speed. The work is recursive sum types, pattern matching, arena-style stores, and a tree-walking interpreter, all of which Kotlin handles cleanly via sealed classes and `when`. Performance is not a Milestone 2.1 concern — the reference interpreter is explicitly the slow one. When Milestone 2.3 starts, the runtime is expected to be rewritten in Rust; that rewrite is healthy and was anticipated by this decision.

**Authoring and serialization format: JSON for authoring, canonical CBOR for hashing.** This is the IPLD pattern (dag-json ↔ dag-cbor). JSON is the on-disk and on-wire form because human authors of the seed corpus need readability and LLM generators emit JSON more reliably than s-expressions or canonical-JSON variants. Canonical CBOR is computed on demand by walking the in-memory representation with a fixed per-type encoding (type tag, ordered field list, child references as BLAKE3 hashes). The hash is taken over the canonical CBOR bytes. Children appear by hash, not inlined — this is the Merkle DAG specified in ADR-003.

**Reasoning on the Kotlin choice and Android conventions.** Kotlin here is plain JVM Kotlin, organized around language-runtime patterns. Strand's implementation is not an Android project. Do not import Android SDK conventions: no MVVM, no lifecycle, no Activity/Fragment patterns. The "do not conflate Strand with Jeff's Android work" guidance still applies; the language choice is about iteration speed and the user's existing fluency, not about Strand becoming mobile-shaped.

## Conventions to maintain

These are non-negotiable; they exist because the user reviewed and approved them. Do not change them without asking.

**Voice in design documents.** Neutral specification voice ("Strand uses content-addressed nodes because..."). Not narrative ("We decided that..."). Not exploratory ("One approach might be..."). The documents describe the design as it currently stands.

**Open questions are separate.** Specification documents do not contain inline caveats about uncertainty. When something is unresolved, it goes in `open-questions.md` with a Q-NNN identifier, and the specification document references the question by identifier.

**Stable identifiers.** ADR-NNN, N-NNN, E-NNN, Q-NNN. Once assigned, identifiers never change. New identifiers extend the sequence; nothing is reused or renumbered.

**Section anchors.** Major sections within documents have explicit anchor IDs in the form `{#anchor-name}` after the heading.

**Reference sections.** Every design document ends with a "References" section listing outgoing citations and incoming citations from other documents. Both directions are maintained.

**INDEX.md is updated with every change.** When a document is added or an identifier assigned, INDEX.md is updated in the same pass.

**No emoji, no headers ending in punctuation, no bullet points where prose works.** The corpus documents read like research papers, not blog posts.

**For implementation code.** Standard Kotlin idioms. Multi-module Gradle. JUnit 5 tests. No Android dependencies. Production-grade structured errors — verifier and interpreter errors are typed data, not strings. Clean-code principles, but no ceremony where simplicity suffices.

## Judgment calls the user has already made

The following are decided and should not be re-litigated:

1. **No human readability.** ADR-002. Do not propose adding a projection layer. Analysis tooling, not text rendering.

2. **Graph-native, not graph-as-IR.** ADR-001. The graph is the source representation. AI agents emit graph operations directly.

3. **Content-addressing by cryptographic hash.** ADR-003. BLAKE3 over canonical CBOR is the default; SHA-256 is permitted via multihash.

4. **Effects are mandatory.** ADR-004. Every effectful operation enters the graph through a node with an explicit effect declaration.

5. **Documentation lives in markdown files** at the project root and in `decisions/` and `design/`. Cross-references use relative paths.

6. **Kotlin/JVM for Milestone 2.1; Rust expected at Milestone 2.3.** See "Implementation decisions" above.

7. **JSON for authoring and on-wire; canonical CBOR for hashing.** See "Implementation decisions" above.

8. **Implementation lives under `impl/`** at the project root, separate from the design corpus.

## Things flagged but not resolved by design alone

These are noted in `open-questions.md` and remain open. Most will be answered by the implementation itself, not by further design work.

- **Q-008 (high-throughput state machine engineering).** Acknowledged as a runtime engineering problem. Erlang's BEAM is the reference architecture.
- **Q-013 (topology obfuscation).** Open.
- **Q-017 (full bytecode VM spec).** To be resolved at Milestone 2.3.
- **Q-018 (full MLIR dialect).** To be resolved at Milestone 2.8 if pursued.
- **Q-020 (training corpus bootstrap).** The research plan proposes the strategy; the test is Phase 1.
- **Q-022, Q-023, Q-027, Q-028.** Open; resolved by tooling work in Phase 4.

## How to work with Jeff

Things observed about Jeff's preferences:

- He asks pointed questions and expects substantive engagement, not validation. Push back when you disagree.
- He prefers concrete artifacts over discussion. The pattern is: explore an idea briefly, then commit it to code or documentation.
- He works on Android (Kotlin, MVVM, multi-module architecture) commercially. The Strand project is research and is distinct from that work.
- He uses neutral specification voice in technical documents and notices voice drift.
- For Strand specifically, he prefers batched work with independent thinking rather than per-step review gates. The auto-memory in your context records this; trust it.

## What I would do first if I were you

If you're picking this up at the implementation stage:

1. Read `README.md` and `INDEX.md` to get the corpus map.
2. Read `design/node-algebra.md` and `design/effects-and-capabilities.md` carefully — these are what the verifier implements.
3. Read ADR-001 through ADR-004 — these are the load-bearing decisions for Milestone 2.1.
4. Look at `impl/` to see what's already built. The README there describes current state.
5. Find the open work item and continue from there, or ask Jeff what's next.

If you're picking this up at the design stage instead (something new came up that needs a design decision):

1. Locate the relevant existing design document via `INDEX.md`.
2. Read it and the documents it references.
3. Propose a change as either a new ADR or an extension to the relevant design spec.

## What not to do

- Do not regenerate or restructure the design corpus without being asked. It is settled.
- Do not introduce a concrete syntax. The graph is the source.
- Do not add a human-readable projection layer (ADR-002).
- Do not let the Kotlin implementation drift toward Android patterns.
- Do not skip the verifier — every node entering the store must verify first. The verifier is the keystone of the research hypothesis.
- Do not skip canonical encoding when computing hashes. Two implementations must produce identical hashes for identical graphs.
- Do not invent prior art. Citations in design documents are based on actual research; new claims about related work should be verified, not invented.

## A note on the project's research status

The hypothesis stated in `00-motivation.md` makes five specific predictions about Strand's expected advantages over text languages. These predictions are on record and must eventually be empirically tested. The research plan describes the experiments. The design documents present claims in declarative voice because that is the appropriate voice for a specification, not because the design is proven correct. Be honest about what is established and what is still being tested.

## File locations

```
strand-language/
├── README.md
├── INDEX.md
├── CONTINUATION.md
├── 00-motivation.md
├── 01-prior-art.md
├── 02-core-thesis.md
├── open-questions.md
├── research-plan.md
├── decisions/                     (ADR-001 through ADR-009)
├── design/                        (node-algebra, effects, state machines, etc.)
└── impl/                          (Kotlin/JVM reference implementation)
    ├── README.md
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── core/                      (node types, store, canonical encoding)
    ├── verifier/                  (well-formedness, type inference, effect closure)
    ├── interpreter/               (tree-walking evaluator)
    ├── cli/                       (command-line entry point)
    └── corpus/                    (seed corpus — paired natural language + graph JSON)
```

The `impl/` subtree is created when Milestone 2.1 scaffolding begins. The design corpus at the root is stable and should not be reorganized.

## Deferred draft proposals

These proposals are written to the "ready to implement" level of detail and sit under `proposals/` (not `implemented/`). They are explicitly deferred — not because of design uncertainty, but because each represents a discrete shipping unit whose scope exceeds the available session budget. They remain on the roadmap; the deferrals are sequencing, not abandonment.

- **`proposals/model-api-integration.md`** — partially shipped 2026-05-25. The `strand-eval` Python framework, Anthropic + step-mode (Claude Code) backends, Python language adapter, 10 tasks, and a first end-to-end dynamic-cost measurement run (recorded in [`evaluation/dynamic-results.md`](evaluation/dynamic-results.md)) all ship. Outstanding follow-ups: (1) an Anthropic-backend run with fresh model contexts — the first run used step-mode against a session that had prior corpus exposure, so first-pass=100% is an upper bound, not a real measurement; (2) prompt-caching measurement so the per-emission cost amortizes properly; (3) multi-sample statistical aggregation (N>1 per cell with bootstrap CIs); (4) retry-loop-exercising tasks (all 20 cells converged on attempt 1, making the verifier-feedback advantage invisible); (5) the remaining four Q-021 baselines (Kotlin Coroutines, Rust, TypeScript-strict, SimPy/ShortCoder).

(All Wave-3+ implementation proposals have landed and moved to `proposals/implemented/`. Only the partially-shipped model-API-integration proposal remains in `proposals/`.)

These deferrals are recorded here, not in `proposals/<topic>.md` headers, so the proposal files remain "ready to implement" at the slice level when their gating milestones arrive.

## Empirical evaluation framework (Q-021 MVP)

A top-level `evaluation/` directory hosts the static-cost measurement
framework for Q-021/Q-034. Three tasks × four reference forms
(Python+type-hints, Strand canonical dag-json, Strand Layer A baseline,
plus the Layer A density v1 → v4 progression) × three metrics (bytes,
lines, estimated tokens). `evaluation/measure.sh` regenerates
`evaluation/results.md`. The framework lives outside `impl/` because it
spans multiple languages and is not part of the reference runtime.

Headline numbers as of 2026-05-25: Strand canonical dag-json runs at
**5.82×** Python+type-hints geomean across the three-task MVP; Strand
Layer A density v4 runs at **0.81×** geomean (factorial 0.87×, json-value
0.81×, toggle-machine 0.76×). The density-v4 figure sits below Q-034 §6's
projection floor of 1.30× for stacks without tokenizer alignment,
closing the static-cost half of the Q-034 hypothesis. The remaining
Q-021 surface — the four other baselines (Kotlin Coroutines, Rust,
TypeScript-strict, SimPy/ShortCoder), the effects and distribution task
suites, and the dynamic metrics (first-pass verification rate,
tokens-per-successful-task across the agent's retry loop) — needs
model-API integration and is tracked as Phase 1 follow-up.

## Layer A density work landed 2026-05-25

The full four-layer LLM emission stack from Q-034 has shipped. Layer A
is a 51-code grammar; Layer B emits GBNF derived from
`LayerAGrammar.codes` via `ConstraintGrammar` (CLI: `strand grammar`);
Layer C elaboration is always-on as of the 2026-05-25 cleanup pass and
ships eleven inference cases (the four original cases from the proposal
plus seven added by the density work, including recursion-slot
paramType, FunctionType synthesis, SumCaseSchema caseType from SumValue,
and six routes for compact-LAM-param paramType inference); Layer D is
unchanged. Ten density slices layered on top of step 1's base grammar:
implicit prelude, inline literals, auto-VarRef, IF/Match-on-Bool sugar,
compact Lambda parameter declarations, inline literal patterns,
anonymous `_` plus `@last`, inline `[k=v ...]` ProductFieldValue list,
WHEN/constructor-pattern sugar, and nested expressions inside argument
positions. All work lives in `impl/authoring/`; the verifier, canonical
CBOR encoder, and runtime are untouched. See
[`proposals/implemented/layer-a-density.md`](proposals/implemented/layer-a-density.md)
for the implementation record.

## Q-036 reverse projection landed 2026-05-25

The reverse direction of the Layer A authoring stack — canonical
dag-json back to Layer A density-v4 text — shipped across five git
commits per the proposal's §9 shipping order: Step 1 canonical-form
translator + renderer, Step 1 round-trip coverage extended to all 64
corpus programs, Step 2 static SAFE elaboration omission for the
recursion-slot `paramType` case, Step 3 probe-and-fallback for the
BORDERLINE inference cases, Step 4 density-sugar projection across all
10 slices, Step 5 the `strand translate <file.json>` CLI subcommand.
`LayerAReverseRoundTripTest` asserts
`forward_compile(render(translate(canonical))) == canonical`
byte-for-byte across the entire 64-program corpus. Three deviations
from the literal proposal are recorded in the implementation note at
[`proposals/implemented/layer-a-reverse-projection.md`](proposals/implemented/layer-a-reverse-projection.md):
`ElaborationOmission.kt` folded into `LayerATranslator` as private
methods, `Lambda.effects` demoted SAFE→BORDERLINE because corpus 12/13/14
legitimately over-declare effects, and a new `FORCE_ALL_OPTIONALS =
setOf("APP")` rule added to short-circuit the Elaborator's effectInstances
defaulting on out-of-scope EffectDecl picks in corpus 33-35. This closes
the agent-reading-existing-Strand-code half of Q-034 — agents loading,
modifying, or inspecting existing programs now see the same compact
representation they emit.

The items that remain open after this work:

- Tokenizer alignment (Q-034 §3.3, Phase 4 work). Compounds on the
  measured static-cost win but is not needed to close the static-cost
  half of the hypothesis.
- Full Q-021 evaluation framework against the five named conventional
  baselines (Kotlin Coroutines, Rust, TypeScript-strict, SimPy, etc.).
  Only Python+type-hints is implemented in the MVP today.
- Tool-call assembly as alternative emission interface (Q-034 §3.6).
  Maps to the same Layer C elaboration; engineering rather than design.
- Nested constructor patterns and or-patterns in WHEN. Both require an
  explicit `MC` + `PCN` tower in the canonical form today; the WHEN
  sugar handles the flat case only.
- Bidirectional type inference for `SchemaType` ↔ `T` subtyping. The
  json-value compact-LAM parameter annotation must stay explicit because
  the call site passes a value of type `jsonValueT` while the LAM's
  declared `paramType` is `jsonValueSchema`; the Elaborator gives up
  rather than commit a hash-divergent inference, because the resolution
  requires unification.
- Q-017 bytecode VM step 2 (Rust port per ADR-008). Separate from the
  authoring-layer work but remains on the roadmap.

## Final note

The design corpus represents honest thinking, not ceremony. The implementation should reflect the same standard. Push back when something doesn't fit. Flag uncertainty when you have it. Produce code you would be willing to defend on its merits.

Good luck.
