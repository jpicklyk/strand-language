# Open Questions {#open-questions}

**Document:** `open-questions.md`
**Status:** Living document; updated as questions are opened or resolved
**Last revised:** 2026-05-26 (Q-037 + Q-038 Phase 1 implemented in two parallel-worktree commits, merged into main at `3c8271b`. Per-provider ForeignNodes (Anthropic/OpenAI/Gemini for LLM, Pinecone/Chroma for Vector) under operation-shaped effect categories E-035..E-038. 58 new tests all pass. Both proposals moved to `proposals/implemented/`; status flips Proposed → Resolved. Five Q-037 + four Q-038 deviations recorded in the implementation notes. Phase 2+ remain.) 2026-05-26 (Q-037 + Q-038 second revision after a prior-art check on per-provider effect categories. Effect categories revert to operation-shaped with `provider` as a refinement parameter, consistent with Strand's existing E-001..E-034 pattern and effect-systems literature: Q-037 uses E-035 LLM.Generate{provider, model} + E-036 LLM.Embed{provider, model}; Q-038 uses E-037 Vector.Read{provider, store} + E-038 Vector.Write{provider, store}. Per-provider ForeignNodes retained as the load-bearing piece for content addressing and provenance trust.) 2026-05-26 (Q-037 first revision after five-call analysis pass; Q-038 added as a sibling draft. First revision proposed per-provider effect categories E-035 Anthropic.Generate through E-040 Gemini.Embed and E-041..E-048 for vector stores; superseded by the second revision recorded above. Tool-parameter Schema translator gains an irreducible-subset definition and a new `ToolParamTypeUnsupported` verifier rule. Bytes-for-embeddings upgrade gate hardened with the [`nested-recursive-self-depth`](proposals/implemented/nested-recursive-self-depth.md) precedent. State-machine modeling confirmed against eight agent workloads; E-017 Memory.MutableState stays absent.) 2026-05-26 (Q-037 agent-native capabilities for AI agents added as Proposed with the original dispatch-on-string surface; revised the same day per the analysis pass recorded above.) 2026-05-25 (Q-036 reverse projection marked Resolved after implementation in the Kotlin/JVM reference implementation across five git commits per the proposal's §9 shipping order: Step 1 canonical-form translator + renderer (`dc5c3e4`), Step 1 round-trip coverage extended to all 64 corpus programs (`aea52ed`), Step 2 static SAFE elaboration omission for recursion-slot `paramType` (`75accdd`), Step 3 probe-and-fallback for BORDERLINE inference cases (`24c58b6`), Step 4 density-sugar projection across all 10 slices (`8fc6a56`, `a4d84ab`, `df6d015`), and Step 5 the `strand translate` CLI subcommand (`4f015d4`). Proposal moved to [`proposals/implemented/layer-a-reverse-projection.md`](proposals/implemented/layer-a-reverse-projection.md). Three implementation deviations recorded in the proposal's Implementation note: `ElaborationOmission.kt` folded into `LayerATranslator` as private methods, `Lambda.effects` demoted SAFE→BORDERLINE because corpus 12/13/14 legitimately over-declare effects, and a new `FORCE_ALL_OPTIONALS = setOf("APP")` rule added to handle out-of-scope EffectDecl picks in corpus 33-35.) 2026-05-25 (Q-036 reverse projection from canonical dag-json to Layer A added as Proposed, with proposed resolution in [`proposals/layer-a-reverse-projection.md`](proposals/layer-a-reverse-projection.md). Closes the agent-reading-existing-Strand-code side of Q-034; the forward Q-034 stack ships density-v4 from LLM → canonical, this proposal specifies canonical → density-v4 so the model sees the same vocabulary it emits. Two-stage translator + renderer with hybrid elaboration-omission. 2026-05-25 (Q-034 authoring-layer design moved to "Resolved at static-cost level; dynamic-cost validation remains Phase 1 follow-up" after the full four-layer emission stack shipped in the Kotlin/JVM reference implementation. Layer A density v1 through v4 (ten slices total: implicit prelude, inline literals, auto-VarRef, IF/Match-on-Bool sugar, compact LAM params, anonymous nodes plus @last, inline ProductFieldValue list, WHEN/constructor-pattern sugar, nested expressions, deeper Elaborator type inference) cut the three-task evaluation geomean from 2.28× Python+type-hints to 0.81× — well below the §6 projection floor of 1.30× for non-tokenizer-aligned stacks. Canonical reference is [`proposals/implemented/layer-a-density.md`](proposals/implemented/layer-a-density.md). Q-021 evaluation metrics moved to "Resolved at MVP level; dynamic metrics remain Phase 1 follow-up" with the `evaluation/` framework as the static-cost MVP — three tasks × four forms × three metrics shipped, the five-baseline Q-021 spec and dynamic metrics (first-pass verification rate, tokens-per-successful-task) remain as Phase 1 follow-up requiring real model-API integration. 2026-05-24 (Q-035 Layer 7 step 1 Schema + Invariant marked Resolved after implementation in the Kotlin/JVM reference implementation; proposal moved to `proposals/implemented/schema-and-invariant.md`. The N-032 Schema and N-033 Invariant slots, reserved since Wave 3 design, are now load-bearing. Q-033 state machines step 2 partially landed alongside (runtime infrastructure + corpus 46 in; multi-stream verifier lift, corpus 47-49, and `strand group` CLI deferred). 2026-05-24 (Q-035 Layer 7 step 1 Schema + Invariant proposal added with proposed resolution in `proposals/schema-and-invariant.md`. First slice of ADR-009: pure-expression invariants, verify-time evaluation on statically-known values, new `:schema` Gradle module; blessed output libraries, ForeignNode-backed checkers, provenance, and live views explicitly deferred. 2026-05-24 (Q-034 authoring-layer design for efficient LLM emission registered as Open in the Tooling and ecosystem section; resolution deferred until the Kotlin/JVM reference implementation can host an agent-driven authoring loop. 2026-05-24 (Q-033 state machines step 2 — async multi-machine actor runtime — added with proposed resolution in `proposals/state-machines-runtime-step-2.md`. Extends Q-008 with the concrete actor + channel design and integrates Q-009 via select-based nondeterministic merge. 2026-05-24 (Q-032 state machine runtime architecture marked Resolved at the strategy level after step 1 implementation in Layer 6 step 1 of the Kotlin/JVM reference implementation; steps 2 and 3 deferred. 2026-05-23 (Wave 3 resolutions added; rendering questions Q-025 through Q-028 added; Q-029 recursive types added with proposed resolution; Q-030 effect handler algebra, Q-031 refinement-lattice capability matching, Q-032 state machine runtime architecture added — all with proposed resolutions in `proposals/`. Q-031 marked Resolved after Layer 3 step 2 implementation. Q-030 marked Resolved after Layer 3 step 3 implementation.))

## Purpose

This document tracks unresolved design questions and known gaps in the Strand specification. Specification documents describe the current design without inline caveats about uncertainty; questions about the design live here. Each question references the specific documents and sections it concerns.

Questions are organized by topic and assigned stable identifiers (`Q-001`, `Q-002`, etc.) that do not change once assigned. When a question is resolved, its entry is updated with the resolution and a reference to the document where the resolution is recorded; the entry is not removed.

## Status legend

- **Open** — no proposed resolution
- **Proposed** — a resolution has been suggested but not adopted
- **Resolved** — resolution adopted; entry retained for historical reference
- **Deferred** — out of scope for current research phase

## Node algebra {#questions-node-algebra}

### Q-001: Exact node type inventory {#Q-001}

**Status:** Proposed
**Concerns:** [`design/node-algebra.md`](design/node-algebra.md)
**Resolution:** Inventory of 31 node types (N-001 through N-031) across eight categories (literals, types, functions and binding, references, effects and capabilities, control flow, state machines, metadata), with edge schemas and well-formedness rules, proposed in [`design/node-algebra.md`](design/node-algebra.md). Validation depends on implementation; the inventory is open to extension under the versioning rules.

The core thesis and supporting documents reference node types (Function, Application, Lambda, TypeDef, ForeignNode, StateMachine, EventStream, etc.) without formalizing the complete inventory. The exact set of node types, their edge schemas, and their well-formedness rules has not been specified. A formal node algebra is required before implementation can begin.

**Estimated complexity:** Moderate. Drawing from typed lambda calculus with row-polymorphic effects, the core node set is likely 15–25 types. The work is in specifying edge schemas and well-formedness rules precisely.

### Q-002: Treatment of hierarchical and nested state machines {#Q-002}

**Status:** Proposed
**Concerns:** [`design/state-machines.md`](design/state-machines.md)
**Resolution:** Compositional flat machines, as proposed in [`design/state-machines.md`](design/state-machines.md). Hierarchical patterns are implemented by parent machines that delegate to child machines based on their state. A future `HierarchicalStateMachine` syntactic-sugar node category may be added later without changing the underlying algebra.

Real-world state machines are often hierarchical (e.g., Harel statecharts). The current design treats state machines as single transition functions over flat state. How nested machines, parallel regions, and history states map to the node algebra has not been determined. Options include (1) representing hierarchy through composition of independent StateMachine nodes, (2) introducing dedicated node types for hierarchical state, (3) adopting a statechart-derived formalism wholesale.

## Effects and capabilities {#questions-effects}

### Q-003: Effect category granularity {#Q-003}

**Status:** Proposed
**Concerns:** [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md)
**Resolution:** 31 effect categories (E-001 through E-031) across eight groups (Network, Filesystem, Time, Process, Memory, Hardware, Crypto, Trust, StateMachine), with parameterization through structured fields and a refinement lattice for capability matching, proposed in [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md).

Effect categories must be granular enough to be useful for security policies (`Network.Connect{host=X, port=Y}` rather than `Network`) but coarse enough to be tractable for the verifier and for human reasoning about policies. The exact categorization scheme, including how parameterized effects are represented in the type system, has not been specified.

### Q-004: Capability delegation semantics {#Q-004}

**Status:** Proposed
**Concerns:** [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md)
**Resolution:** Hybrid discipline. Capabilities flow implicitly through in-graph calls (ambient within a trust domain). A `CapabilityScope` node narrows capabilities at trust boundaries. Foreign and encrypted-node boundaries require explicit re-grant. Proposed in [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md).

When a node holding capability C invokes another node that requires C, must C be explicitly passed as an argument (strict capability discipline) or does the runtime forward C implicitly (ambient within the scope)? Strict discipline is more secure but requires every effectful function to receive capabilities as arguments, adding boilerplate. Implicit forwarding is easier but reintroduces ambient authority that capability systems aim to eliminate.

### Q-005: Confused deputy mitigation {#Q-005}

**Status:** Proposed
**Concerns:** [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md), [`design/security-model.md`](design/security-model.md)
**Resolution:** Three-layer mitigation: parameter-tagged capabilities (the primary defense), capability minimization at scope entry via CapabilityScope, and optional argument-provenance checks. Proposed in [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) and synthesized in [`design/security-model.md`](design/security-model.md). The language makes careful authority design expressible; it does not enforce its use.

When a privileged subgraph accepts arguments from a less-privileged caller, the caller can potentially manipulate the privileged graph into performing operations on its behalf — the classic confused deputy attack. The graph design makes capability passing explicit, which reduces ambient authority, but does not automatically prevent confused deputy attacks. The mitigation strategy has not been specified.

## Foreign function interface {#questions-ffi}

### Q-006: Trust and signing model for foreign bindings {#Q-006}

**Status:** Proposed
**Concerns:** [`design/security-model.md`](design/security-model.md), [`decisions/ADR-005-foreign-nodes.md`](decisions/ADR-005-foreign-nodes.md)
**Resolution:** Four complementary mechanisms: signed provenance, reproducible binding generation, curated registries, and runtime sandbox observation. Proposed in [`design/security-model.md`](design/security-model.md). Deployments combine mechanisms according to their sensitivity policy.

ForeignNode declarations make claims about foreign function effects that the Strand verifier cannot independently verify. A compromised or malicious binding can lie about effects, breaking the security guarantees of every downstream graph that uses it. The mitigation requires a trust model for foreign bindings: signed bindings, reproducible binding generation, a curated registry, or some combination. The exact model has not been specified.

### Q-007: Effect inference for unannotated foreign code {#Q-007}

**Status:** Proposed
**Concerns:** [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md), foreign function interface
**Resolution:** Native graph nodes always declare effects; the language does not infer. For foreign code, the source determines the approach: WIT-typed sources translate automatically, Erlang/Elixir specs translate with author confirmation, native C/C++/Rust requires manual annotation with tool-assisted proposals. When effects cannot be determined, the default policy refuses the binding rather than admitting an unusable conservative annotation. Proposed in [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md).

When auto-generating ForeignNode declarations from existing libraries (e.g., the Android SDK), effect annotations are not available from the foreign source. The default behavior — assume worst-case effects, refuse to bind, attempt static analysis of foreign code, observe behavior at runtime — has not been determined.

## State machines {#questions-state-machines}

### Q-008: High-throughput communicating state machine architecture {#Q-008}

**Status:** Deferred until Milestone 2.5+ (distributed multi-node runtime engineering)
**Deferred until:** Milestone 2.5+. Reason: step 1 synchronous runtime and step 2 single-JVM async actor runtime have both shipped (Q-032, Q-033 Resolved); step 3 (`proposals/state-machines-runtime-step-3.md`) has slices 3.1, 3.5, 3.6 landed and slices 3.2/3.3/3.4 explicitly deferred in [`CONTINUATION.md`](CONTINUATION.md). The high-throughput multi-node engineering this question names — BEAM-scale lightweight scheduling across nodes, locality-aware placement, snapshotting in a content-addressed setting — is post-Milestone 2.5 work and depends on the distribution model in [`design/distribution-model.md`](design/distribution-model.md).
**Concerns:** [`design/state-machines.md`](design/state-machines.md)
**Partial resolution:** Design constraints are specified in [`design/state-machines.md`](design/state-machines.md): lightweight scheduling, batch event processing, locality-aware placement, snapshotting, content-addressed garbage collection. BEAM is the reference architecture. The constraints are necessary; the implementation engineering is open and is a Phase 2 milestone in [`research-plan.md`](research-plan.md).

The state machine design handles distributed consensus, real-time messaging, and similar workloads in principle: each participant is a state machine, communication is event streams between machines. The runtime engineering required to make thousands of communicating machines efficient is substantial. Erlang's BEAM VM provides a reference architecture but adopting it directly may not match Strand's graph-native execution model. The runtime architecture for high-throughput state machine systems has not been specified.

### Q-009: Event ordering across multiple streams {#Q-009}

**Status:** Proposed
**Concerns:** [`design/state-machines.md`](design/state-machines.md)
**Resolution:** Layered policies: FIFO-per-stream with non-deterministic merge as default; optional priority-based, causally-ordered (vector clocks), and timestamp-based merges as opt-in declarations on the StateMachine node. Proposed in [`design/state-machines.md`](design/state-machines.md).

State machines that receive events from multiple streams need a merge policy. Options include first-come-first-served, priority-based ordering, causally-ordered (vector clocks), and timestamp-based ordering. Each has tradeoffs in correctness, performance, and complexity. The default policy and the set of available alternatives have not been specified.

### Q-010: Hot upgrade semantics for transition functions {#Q-010}

**Status:** Proposed
**Concerns:** [`design/state-machines.md`](design/state-machines.md)
**Resolution:** Two-phase atomic upgrade. The new transition function must declare compatibility (same state type, or migration function provided). The runtime pauses event dispatch, applies the migration, swaps the function reference, and resumes. Cluster-wide coordinated upgrade uses the cluster coordinator. Proposed in [`design/state-machines.md`](design/state-machines.md).

Updating the transition function of a running state machine requires a defined upgrade procedure, including state migration when the state type changes. Erlang's `code_change` callback provides a reference, but its semantics in a content-addressed setting (where the old and new transition functions have stable distinct identities) require specification.

## Encryption and security {#questions-encryption}

### Q-011: Per-node encryption key management {#Q-011}

**Status:** Proposed
**Concerns:** [`design/encryption-model.md`](design/encryption-model.md)
**Resolution:** Detailed envelope format, four key types (principal, session, attestation-bound, symmetric-wrap), multi-recipient encryption protocol, BLAKE3 + AES-256-GCM + X25519 algorithm selection, generation/distribution/rotation/revocation lifecycles, and revocation Merkle tree. Hash is over plaintext canonical form (preserves deduplication); optional nonce mitigates guess-verify for low-entropy plaintexts. Proposed in [`design/encryption-model.md`](design/encryption-model.md).

Per-node encryption with multiple private keys requires a key management story: how keys are generated, distributed, rotated, and revoked. How encrypted nodes interact with content addressing (does the hash cover the ciphertext, the plaintext, or both?) has implications for caching, replay, and verification that have not been specified.

### Q-012: TEE attestation chain integration {#Q-012}

**Status:** Proposed
**Concerns:** [`design/security-model.md`](design/security-model.md), [`design/encryption-model.md`](design/encryption-model.md)
**Resolution:** Four-level attestation chain: platform identity → launched-workload measurement → Strand-runtime identity → graph identity. Attestation results are presented as `Trust.Attestation` capabilities. Cross-platform abstraction follows IETF RATS evidence/attestation-result distinction. Proposed in [`design/security-model.md`](design/security-model.md), with key-handling details in [`design/encryption-model.md`](design/encryption-model.md).

Trusted Execution Environment integration requires mapping Strand's content-addressed graph identity to the TEE's attestation primitives (measured launch, sealed storage, remote attestation). The specific protocol — what is attested, what the verifier checks, how attestations are presented as capabilities — has not been specified.

### Q-013: Obfuscation guarantees and limits {#Q-013}

**Status:** Deferred — topology obfuscation is a future research direction beyond Milestone 2.x
**Deferred until:** Future research phase (post-Phase-2). Reason: per-node encryption already provides cryptographic confidentiality of node *contents*, which is the load-bearing security property today. Stronger obfuscation through structural transformations (decoy nodes, splitting, merging) is identified as a future research direction and not on any Milestone 2.x line item; revisiting it requires either an adversary model that today's deployments demand or a research agenda separate from the production runtime.
**Concerns:** [`design/security-model.md`](design/security-model.md)
**Partial resolution:** Per-node encryption provides cryptographic confidentiality of node contents to non-key-holders. Graph *topology* (which nodes reference which, type-level interface declarations, effect category presence) is not protected and remains observable. Stronger obfuscation through structural transformations (decoy nodes, splitting, merging) is identified as a future research direction; not specified in current design. Documented in [`design/security-model.md`](design/security-model.md).

The security model claims that subgraph encryption, hash-only metadata stripping, and semantic-preserving graph transformations provide meaningful obfuscation against reverse engineering. The exact strength of these guarantees, the attack scenarios they defend against, and the residual analysis possible against obfuscated graphs have not been formalized.

## Distribution {#questions-distribution}

### Q-014: Scheduler policy under competing constraints {#Q-014}

**Status:** Proposed
**Concerns:** [`design/distribution-model.md`](design/distribution-model.md)
**Resolution:** Four primary policies (minimize data movement, minimize critical-path latency, balance load, maximize fault tolerance) combinable via weighted scoring. Default is hybrid (data movement primary, latency secondary, fault tolerance respected). Per-graph overrides via `Placement` annotations. Policies are themselves programmable as `SchedulerPolicy` nodes. Proposed in [`design/distribution-model.md`](design/distribution-model.md).

The distribution model describes placement as constraint satisfaction over effects and capabilities. When multiple valid placements exist, the scheduler must choose. The policy — minimize latency, minimize data movement, balance load, maximize fault tolerance — has not been specified. Different workloads benefit from different policies; whether the policy is fixed, configurable, or programmable per-graph is open.

### Q-015: Backpressure semantics for distributed event streams {#Q-015}

**Status:** Proposed
**Concerns:** [`design/distribution-model.md`](design/distribution-model.md), [`design/state-machines.md`](design/state-machines.md)
**Resolution:** Bounded queues per stream with per-stream overflow policy (block-producer default, drop-newest, drop-oldest, sample as alternatives). Cross-machine backpressure communicated via protocol `Backpressure` signal. Multi-hop propagation is straightforward but operationally requires monitoring to detect starvation. Proposed in [`design/state-machines.md`](design/state-machines.md) and [`design/distribution-model.md`](design/distribution-model.md).

When events arrive faster than a state machine can process them, the backpressure response must propagate to upstream sources. In a distributed setting, this propagation crosses machine boundaries and may involve multiple upstream sources with different policies. The standard backpressure policies (Reactive Streams, etc.) provide a starting point but their integration with Strand's distribution model has not been specified.

### Q-016: Network failure handling during graph fetches {#Q-016}

**Status:** Proposed
**Concerns:** [`design/distribution-model.md`](design/distribution-model.md)
**Resolution:** Retry with exponential backoff (3 retries default, 100 ms initial), fallback to alternative peers from the peer directory, bounded per-fetch timeout (30 s default, 5 s on critical path), failure propagation to the scheduler which may re-place or abort. Proposed in [`design/distribution-model.md`](design/distribution-model.md).

When a worker references a node by hash that requires fetching from a different machine, the fetch can fail due to network conditions. Retry policy, timeout, fallback to alternative replicas, and the propagation of unrecoverable failures up to the scheduler have not been specified.

## Compilation and runtime {#questions-compilation}

### Q-017: Bytecode VM specification {#Q-017}

**Status:** Proposed (step 1 — Kotlin reference VM — specified in `proposals/bytecode-vm-step-1.md`, 2026-05-24; step 2 — Rust production VM per ADR-008 — to be drafted as a separate proposal when step 1 lands)
**Concerns:** [`decisions/ADR-008-compilation-target.md`](decisions/ADR-008-compilation-target.md), [`design/node-algebra.md`](design/node-algebra.md)
**Resolution:** Two-step shipping strategy. Step 1 ships a Kotlin reference bytecode VM (`:bytecode` + `:vm` Gradle modules) that runs every corpus program with bytewise-equivalent behavior to the tree-walking interpreter. Step 2 ports the same design to Rust per ADR-008 and tunes for performance. The instruction set (28 opcodes), uniform-boxed value representation, stack-based calling convention with closure captures, JVM-leaning GC, and node-by-node lowering scheme are fully specified for step 1; step 2 inherits the test suite as its correctness specification. Path B (Kotlin-first) chosen over Path A (Rust-first) because iteration speed dominates: design validation happens at week 4-6 instead of month 4-6, surfacing any design surprises (the project has had several) cheaply. ~70% of step 1's work transfers to step 2 (instruction set, lowering, test corpus, verifier integration); ~30% is Kotlin-specific (op-dispatch loop, GC). Effect/capability bytecode operations (`CAP_PUSH`/`CAP_POP`/`HANDLER_PUSH`/`HANDLER_POP`) are first-class per ADR-008; types, schemas, and recursive types are erased pre-bytecode; state machines remain runtime objects consuming bytecode for their transition functions. Detailed proposal in [`proposals/bytecode-vm-step-1.md`](proposals/bytecode-vm-step-1.md).

The decision to use a bytecode VM as the initial execution target requires a bytecode specification: instruction set, value representation, calling convention, garbage collection model. The step 1 specification closes the deferred half; step 2 carries the design into Rust for production performance.

### Q-018: MLIR dialect design {#Q-018}

**Status:** Deferred until Milestone 2.8 (MLIR/LLVM compilation path)
**Deferred until:** Milestone 2.8. Reason: production-quality dialect design is the Milestone 2.8 line item in [`research-plan.md`](research-plan.md) and is downstream of the bytecode VM (Q-017, Milestone 2.3). Strategy and constraints are specified; dialect design proceeds when the bytecode VM ships and the MLIR community collaboration begins.
**Concerns:** [`decisions/ADR-008-compilation-target.md`](decisions/ADR-008-compilation-target.md)
**Partial resolution:** The strategy and constraints are specified: the dialect carries node-evaluation, capability-check, and effect operations; lowering passes translate these to combinations of standard MLIR dialects plus runtime calls. Production-quality dialect design is a Phase 2 milestone (Milestone 2.8) in [`research-plan.md`](research-plan.md) and may require collaboration with the MLIR community.

The MLIR-based compilation path requires defining a Strand dialect that preserves effect and capability information through lowering. The dialect operations, types, and lowering passes have not been specified.

### Q-019: Iterative computation primitives {#Q-019}

**Status:** Proposed
**Concerns:** [`design/node-algebra.md`](design/node-algebra.md), runtime
**Resolution:** `Fixpoint` node type (N-026): a lambda referencing itself through a designated parameter slot, with the runtime supplying the recursive call at evaluation time. Iteration is expressible as recursion through Fixpoint; no separate primitive loop construct. Proposed in [`design/node-algebra.md`](design/node-algebra.md).

Programs that need to iterate (gradient descent, fixpoint computation, loops) require explicit support in the language. Options include a Fixpoint node type, recursion through content-addressed self-reference, or imperative loops gated behind effects. The choice affects analyzability, performance, and ease of generation by LLMs.

## Training and evaluation {#questions-training}

### Q-020: Bootstrap training corpus {#Q-020}

**Status:** Proposed
**Concerns:** [`research-plan.md`](research-plan.md)
**Resolution:** Multi-stage bootstrap: hand-authored seed corpus (50-200 programs), translation corpus from Python (10k-100k), synthetic corpus via strong-model teacher with verifier feedback (100k-1M), student model fine-tuning, self-improvement loop with verifier as reward signal. Phase 1 gating criterion: 70% first-pass verification on held-out task set. Proposed in [`research-plan.md`](research-plan.md).

LLMs are not currently fluent in Strand graph operations because no corpus of Strand programs exists. The bootstrap strategy — translate existing languages, generate synthetic programs with verification feedback, use a strong model to generate and a weaker model to evaluate — has not been specified in detail.

### Q-021: Evaluation metrics and baselines {#Q-021}

**Status:** Resolved at MVP level; dynamic metrics remain Phase 1 follow-up (static-cost MVP shipped 2026-05-24, headline numbers updated 2026-05-25 after Layer A density v4)
**Concerns:** [`research-plan.md`](research-plan.md)
**Resolution:** Three task suites (reproduction, effects, distribution); five baselines (Python+type-hints, Kotlin Coroutines, Rust, TypeScript-strict, SimPy/ShortCoder); seven metrics (first-pass correctness, tokens per successful task, verification feedback cost, effect declaration accuracy, capability minimization score, distribution overhead, replay determinism). Hypothesis supported if at least three of five claims meet statistical significance. Proposed in [`research-plan.md`](research-plan.md).

A static-cost MVP of the framework has shipped under a top-level `evaluation/` directory. Three tasks (factorial, json-value, toggle-machine) × four reference forms (Python+type-hints, Strand canonical dag-json, Strand Layer A, plus the Layer A density v1 → v4 progression) × three measurable metrics (bytes, lines, estimated tokens) are reported in [`evaluation/results.md`](evaluation/results.md) and regenerated by `evaluation/measure.sh`. The headline measurements: Strand canonical dag-json runs at 5.82× Python+type-hints geomean across the three-task MVP; Strand Layer A density v4 runs at 0.81× geomean (factorial 0.87×, json-value 0.81×, toggle-machine 0.76×). Both canonical and Layer A measurements come in worse than [Q-034](#Q-034) §6's projections on small programs because per-program structural overhead amortizes poorly, but Layer A density v4 nonetheless lands below the §6 projection floor of 1.30× for non-tokenizer-aligned stacks.

The remaining Q-021 surface — the other four baselines (Kotlin Coroutines, Rust, TypeScript-strict, SimPy/ShortCoder), the effects and distribution task suites, and the seven dynamic metrics (first-pass correctness, tokens per successful task, verification feedback cost, effect declaration accuracy, capability minimization score, distribution overhead, replay determinism) — remains Phase 1 follow-up and depends on real model-API integration. Static cost is the multiplicand under "tokens-per-successful-task = static × emissions-per-success"; the static MVP measures the first factor, and the model-API integration measures the second.

The hypothesis stated in [`00-motivation.md`](00-motivation.md) is empirically testable. The specific metrics (correctness rates, tokens per task, error-recovery latency, distribution overhead) and baselines (Python with manual annotations, Kotlin with Coroutines, Rust with explicit threading) have not been specified.

### Q-022: Confidentiality of agent intent {#Q-022}

**Status:** Deferred
**Concerns:** [`design/security-model.md`](design/security-model.md) (forthcoming)

Defending against AI agents themselves retaining detailed knowledge of generated programs (model providers learning from usage, future models inferring patterns from historical interactions) is interesting but speculative. Deferred to future research phase. Partial defenses include compositional opacity (multiple agents seeing only slices), differential privacy in agent traces, and semantic encryption of the problem domain presented to the agent.

## Tooling and ecosystem {#questions-tooling}

### Q-023: Graph editor for human inspection {#Q-023}

**Status:** Deferred until Phase 4 (production hardening and adoption tooling)
**Deferred until:** Phase 4. Reason: graph-editor and structured-diff tooling is part of Phase 4 (production hardening and adoption) in [`research-plan.md`](research-plan.md), not part of the core language design. Minimum required tooling (graph queries, structured diffs, subgraph rendering) is identified in [ADR-002](decisions/ADR-002-no-human-projection.md) consequences; building it is downstream of having real users and real failure forensics workloads.
**Concerns:** Tooling
**Partial resolution:** The minimum required tooling — graph queries, structured diffs, subgraph rendering — is identified in [`decisions/ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md) consequences and in [`design/security-model.md`](design/security-model.md). Detailed tooling specification is part of Phase 4 (production hardening and adoption) in [`research-plan.md`](research-plan.md), not part of the core language design.

The decision to omit a textual projection layer ([`ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md)) implies that human inspection occurs through analysis tooling. The minimum viable tooling — graph queries, dependency visualizations, structured diffs — has not been specified. For failure forensics specifically, a way to render a failing subgraph for human inspection is required.

### Q-024: Versioning and migration of the language itself {#Q-024}

**Status:** Proposed
**Concerns:** [`design/node-algebra.md`](design/node-algebra.md), all design documents
**Resolution:** Conservative additive versioning. Node category IDs are stable; new categories receive higher numbers; existing IDs never reused. Schema changes are new category IDs, not modifications of existing ones. Effect categories extend in the same pattern. Older graphs remain valid because their hashes are stable; older runtimes reject graphs that use newer categories. Proposed in [`design/node-algebra.md`](design/node-algebra.md).

The Strand specification will evolve. How existing graphs migrate when the node algebra changes, how effects can be added without invalidating older graphs, and how tooling tracks language versions have not been specified.

### Q-034: Authoring-layer design for efficient LLM emission {#Q-034}

**Status:** Resolved at static-cost level; dynamic-cost validation remains Phase 1 follow-up (full four-layer emission stack landed 2026-05-24, Layer A density v1 through v4 landed 2026-05-25)
**Concerns:** [`00-motivation.md`](00-motivation.md), [`decisions/ADR-001-graph-not-text.md`](decisions/ADR-001-graph-not-text.md), [`decisions/ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md), [`design/node-algebra.md`](design/node-algebra.md), [`research-plan.md`](research-plan.md)
**Resolution:** A four-layer emission stack sitting upstream of the verifier: a compact text projection (Layer A) the LLM emits, grammar-constrained decoding (Layer B) that masks invalid emissions at decode time, bidirectional elaboration (Layer C) that recovers derivable type and effect annotations, and the existing canonical dag-json + verifier (Layer D) unchanged. Tool-call assembly is an alternative interaction interface that maps to the same Layer C elaboration. Tokenizer alignment is recognized as Phase 4 follow-up. Estimated compounded token-cost savings of 3-8× over canonical dag-json, projected to bring Strand within 1.3× of conventional-language baselines on the Q-021 evaluation suite. Detailed proposal in [`proposals/implemented/llm-authoring-layer.md`](proposals/implemented/llm-authoring-layer.md).

The full stack has shipped in the Kotlin/JVM reference implementation. Layer A is a 51-code grammar (the original 42 codes from step 1 plus nine additional codes added during the density work for the IF / WHEN / inline-PFV-list / compact-LAM / nested-expression sugars). Layer B emits GBNF derived dynamically from `LayerAGrammar.codes` via `ConstraintGrammar` and is exposed through the `strand grammar` CLI command. Layer C elaboration is always-on as of the 2026-05-25 cleanup pass that removed the explicit-only translator path; eleven inference cases ship, the four original cases from §5.3 of the original proposal (Lambda effects, effectInstances defaulting, Application typeArguments, Lambda paramType) plus seven cases added by the density work (recursion-slot paramType, FunctionType synthesis, SumCaseSchema caseType from SumValue, and compact-LAM-param paramType from each of: call sites, builtin signatures, StateMachine transition-function context, Match scrutinee, ProductFieldValue, and ProductGetField). Layer D is unchanged.

The Layer A density follow-up to step 1 layered nine grammar sugars and the v4 nested-expression form on top of the base Layer A grammar: implicit prelude (well-known type and builtin shorthands), inline literals at argument positions, auto-VarRef binding for unambiguous PRC-binder references, IF as Match-on-Bool sugar, compact Lambda parameter declarations, inline literal patterns, anonymous `_` declarations addressable through `@last`, inline `[k=v ...]` ProductFieldValue list form, WHEN as constructor-pattern sugar, and nested expressions inside argument positions. The combined ten-slice progression cuts the three-task evaluation suite geomean from 2.28× Python+type-hints (Layer A baseline) to 0.81× Python+type-hints (per-task: factorial 0.87×, json-value 0.81×, toggle-machine 0.76×). All ten increments preserved the canonical CBOR encoding and verifier behavior — the work lives entirely in `impl-kotlin/authoring/` and its corpus fixtures. Detailed implementation note in [`proposals/implemented/layer-a-density.md`](proposals/implemented/layer-a-density.md).

The measured 0.81× geomean lands below §6's projected floor of 1.30× for stacks without tokenizer alignment, closing the static-cost half of the Q-034 hypothesis. The dynamic-cost half — first-pass verification rate, tokens-per-successful-task across the agent's retry loop, retry-loop economics under verifier feedback — remains Phase 1 follow-up and requires real model-API integration. Tokenizer alignment (§3.3) and tool-call assembly (§3.6) are deliberately deferred; they would compound on the measured static-cost win but are not needed to close the static-cost hypothesis. Bidirectional inference for SchemaType↔T subtyping is also deferred and remains the reason that json-value's compact-LAM param must stay explicitly annotated.

The canonical authoring format — dag-json with full hash references, mandatory type annotations at every function boundary, and explicit effect declarations at every composition site — is verbose at the surface. An LLM emitting it node-by-node likely consumes substantially more tokens than the equivalent program in a conventional source language; informal estimates place the multiplier at roughly 2-5× for non-trivial programs before any tooling intervention. This pressures the AI-first framing in [`00-motivation.md`](00-motivation.md): if generation cost dominates retry cost, the verifier's correctness wins may not pay for themselves on a per-program token basis.

The graph remains the source of truth per [ADR-001](decisions/ADR-001-graph-not-text.md); [ADR-002](decisions/ADR-002-no-human-projection.md) rejects a *human*-readable projection. Neither decision speaks to an authoring layer optimized for LLM emission that compiles to canonical form before reaching the verifier. The question is what that authoring layer is and how much of the verbosity it can absorb.

Several candidate techniques are recognized, none yet adopted or proposed as the resolution.

**Authoring-layer projection.** A compact serialization (symbolic builtin references, operator-like sugar, positional encoding) that tooling expands to canonical dag-json. Distinct from a human-readable projection in audience and intent.

**Inference passes on the authoring layer.** Type inference and effect propagation that fill in derivable annotations before the graph reaches the verifier. The verifier still sees a fully-annotated graph; the LLM omits what the tooling can recover.

**Tokenizer alignment.** Domain-specific tokenizers that treat node category tags, edge labels, common builtin target strings, and base-N hash digests as single tokens.

**Constrained generation.** Grammar-driven decoding against the closed inventory of node categories and per-category edge schemas, reducing the model's commitment cost at each position.

**Session-scoped handles.** Short local identifiers for recently-introduced nodes within an authoring session, resolved to canonical hashes at commit.

**Tool-call assembly.** The LLM constructs the graph through tool invocations rather than serialized output, permitting incremental validation and shorter per-step output.

Resolution requires a working verifier and an agent generating against it. Meaningful evaluation pairs candidate techniques with measured tokens-per-successful-task across a representative task suite, against both raw canonical emission and the conventional-language baselines named in [Q-021](#Q-021). The static-cost half has been settled: Layer A density v4 measures at 0.81× Python+type-hints geomean across the three-task MVP, below the §6 1.30× projection floor for stacks without tokenizer alignment. The dynamic-cost half — first-pass verification rate, tokens-per-successful-task across the retry loop — remains Phase 1 follow-up under the framework tracked in [Q-021](#Q-021).

### Q-036: Reverse projection from canonical dag-json to Layer A {#Q-036}

**Status:** Resolved (implemented 2026-05-25 across five git commits per the proposal's §9 shipping order; proposal moved to [`proposals/implemented/layer-a-reverse-projection.md`](proposals/implemented/layer-a-reverse-projection.md))
**Concerns:** [Q-021](#Q-021), [Q-034](#Q-034), [`proposals/implemented/llm-authoring-layer.md`](proposals/implemented/llm-authoring-layer.md), [`proposals/implemented/layer-a-density.md`](proposals/implemented/layer-a-density.md), [`decisions/ADR-001-graph-not-text.md`](decisions/ADR-001-graph-not-text.md), [`decisions/ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md), [`decisions/ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md)
**Resolution:** A two-stage projection pipeline (`LayerATranslator` walks the canonical dag-json bottom-up producing a `LayerADocument` IR with density sugars applied in preference order; `LayerARenderer` formats the IR as Layer A text deterministically) plus a hybrid elaboration-omission strategy (static SAFE rule for recursion-slot `paramType`; probe-and-fallback for the BORDERLINE cases that re-runs the Elaborator to verify per-field omissions; static UNSAFE rule for `paramType` resolving to a `SchemaType<T>` node). The 10 density-v4 sugars (implicit prelude, IF/Match-on-Bool, WHEN/constructor-pattern, compact LAM params, inline literals, auto-VarRef, inline ProductFieldValue list, nested expressions, anonymous `_` with @last, plus the Slice 6 inline-literal-pattern path subsumed by Slice 2) all have detectable trigger patterns in canonical dag-json — most mechanical, only WHEN needs `nodeTypes` from the verifier for SumType scrutinee resolution. Correctness invariant: `forward_compile(render(translate(canonical))) == canonical` byte-for-byte, asserted by `LayerAReverseRoundTripTest` against all 64 canonical corpus programs. Three implementation deviations from the original proposal worth recording: (1) the planned standalone `ElaborationOmission.kt` module ships as private methods on `LayerATranslator` (single linear `LayerADocument → LayerADocument` pipeline kept the data flow obvious); (2) `Lambda.effects` was demoted from SAFE to BORDERLINE during Step 2 because corpus programs 12/13/14 legitimately over-declare effects beyond the body's closure (the §4.5 classification-evolution policy fired exactly as written); (3) a `FORCE_ALL_OPTIONALS = setOf("APP")` rule was added that wasn't in the plan — Application's effectInstances defaulting can pick out-of-scope EffectDecls (corpus programs 33-35), so the translator always emits the field explicitly to short-circuit the Elaborator inference (canonical CBOR gates effectInstances on non-empty, so byte equality holds). Original proposal moved to [`proposals/implemented/layer-a-reverse-projection.md`](proposals/implemented/layer-a-reverse-projection.md).

The Q-034 stack closes the forward direction: an LLM emits Layer A density-v4 text → Elaborator + DagJsonEmitter → canonical dag-json → verifier. The reverse direction — canonical dag-json back to Layer A — was a small `LayerATranslator` utility deleted in the 2026-05-25 cleanup pass once it stopped being load-bearing. The deletion was identified as a gap because three workloads need a reverse path: agent modification of existing programs (read existing dag-json, modify, re-emit), few-shot examples in agent prompts (exemplars should be in the same form the model emits, not in canonical dag-json which has 7× the byte cost), and inspection/audit workflows. The deleted translator produced canonical-form Layer A (always MAT, never IF/WHEN; always explicit PRC declarations; every field present), so restoring it as-is would have given the verbose baseline instead of the density-v4 form a model expects. ADR-002 is preserved — Layer A remains a tool-layer LLM-emission/consumption form, not a human-readable projection.

## Rendering and structured outputs {#questions-rendering}

### Q-025: Schema mechanism scope {#Q-025}

**Status:** Proposed
**Concerns:** [`design/rendering-and-views.md`](design/rendering-and-views.md), [`decisions/ADR-009-structured-outputs.md`](decisions/ADR-009-structured-outputs.md)
**Resolution:** Two new node categories (Schema N-032, Invariant N-033) extend the algebra. Invariants are pure Strand expressions over a value's structure or ForeignNode-backed checkers registered with the verifier. The mechanism is initially scoped to output formats but is general enough to apply to configuration data, message protocols, and other structured values. Generalization beyond outputs is recognized in [`design/rendering-and-views.md`](design/rendering-and-views.md) as a future direction; it is not specified in the current design. Proposed in [`design/rendering-and-views.md`](design/rendering-and-views.md).

The schema mechanism introduced by [ADR-009](decisions/ADR-009-structured-outputs.md) is the primary mechanism by which the verifier reasons about structured outputs. The extent to which it generalizes (refinement types, dependent types, arbitrary predicates with SMT-backed decision procedures) determines how much of the verifier's design must accommodate it. The current design adopts a constrained subset; whether to extend further is open.

### Q-026: Blessed output library set {#Q-026}

**Status:** Proposed (three of six libraries shipped — JSON in Layer 7 step 1.5 (2026-05-24); PlainTextDocument + NonEmptyText and MarkdownDocument + NonEmptyMarkdown landed alongside in [`proposals/implemented/plaintext-and-markdown-libraries.md`](proposals/implemented/plaintext-and-markdown-libraries.md), 2026-05-24. HTML5, SVG, PDF remain to ship — HTML5 and SVG share a nested-μ recursive-list-inside-recursive-element blocker; PDF is a binary format that needs a separate engineering pass)
**Concerns:** [`design/rendering-and-views.md`](design/rendering-and-views.md)
**Resolution:** Six schemas in the reference distribution: HTML5 (with `Html5Document`, `Html5AccessibleAA`, `Html5StrictCSP` layered variants), SVG (`SvgDocument`), JSON (`JsonValue` + `UniqueKeyJsonObject` — **shipped**, see [`proposals/implemented/json-blessed-library.md`](proposals/implemented/json-blessed-library.md)), PDF (`PdfDocument` targeting PDF/A-2u), plain text (`PlainTextDocument`), and Markdown (`MarkdownDocument`). Curation criteria: widespread relevance, well-defined structural invariants, maintained reference implementation. Additional formats are introduced through the standard library-loading mechanism. Proposed in [`design/rendering-and-views.md`](design/rendering-and-views.md). The JSON implementation deviated from the literal proposal in one substantive way: it omits the recursive `JsonArray(List<JsonValue>)` and `JsonObject(List<JsonEntry>)` cases that would make JsonValue a true JSON model, because Strand's RecursiveSelf always resolves to the innermost μ binder and there is no protocol for an inner Recursive (a list) to reference an outer Recursive (JsonValue). Nested-μ for arbitrarily-nested JSON is a separate Q-NNN-worthy open question.

Which structured output formats are included as blessed schemas in the reference distribution affects what agents can rely on as available without additional library configuration. Including too few leaves agents working with raw `Bytes` for common formats; including too many ties the language distribution to format curation and audit. The selection criteria and the initial set have been specified; the boundary may move as the ecosystem develops.

### Q-027: Provenance encoding for output artifacts {#Q-027}

**Status:** Deferred until per-format reference serializers land
**Deferred until:** When a non-JSON blessed output library ships (HTML5, SVG, or PDF — the formats whose tooling can directly consume provenance). Reason: the uniform manifest mechanism is specified; format-specific extensions (e.g., source-map-compatible output for HTML/SVG) only make sense alongside a real serializer for that format. JSON (the first blessed library shipped in Layer 7 step 1.5) doesn't have an established source-map ecosystem to integrate with, so the format-details work waits.
**Concerns:** [`design/rendering-and-views.md`](design/rendering-and-views.md)
**Partial resolution:** Serializers may emit a provenance manifest mapping output byte ranges to source node hashes. The manifest is content-addressed and opt-in. The uniform format is a tree of byte ranges paired with node hashes; format-specific extensions (source-map-compatible output for HTML/SVG) are allowed where they integrate with existing tooling. Detailed format specification is part of the reference implementation work. Proposed in [`design/rendering-and-views.md`](design/rendering-and-views.md).

Tracing a position in rendered output back to the source nodes that produced it is the foundation for debugging, audit, differential rendering, and event routing. The encoding format determines how cheap this tracing is in practice and whether existing tooling (browser dev tools, PDF viewers) can consume the provenance directly. The exact format for each blessed schema is open.

### Q-028: Cross-library invariant composition {#Q-028}

**Status:** Deferred — tool-assisted conflict diagnosis is a future tooling direction
**Deferred until:** Phase 4 (tooling and ecosystem). Reason: the verifier already checks all invariants from all claimed schemas; the only thing missing is *diagnostic* tooling that identifies which invariants are in tension when construction fails repeatedly. That diagnosis tool is Phase 4 work (alongside Q-023's graph-editor tooling), not core language design. The current "agent's construction loop observes the conflict by failing to construct any concrete value" behavior is acceptable until production workloads demand the diagnostic.
**Concerns:** [`design/rendering-and-views.md`](design/rendering-and-views.md)
**Partial resolution:** When multiple schemas apply to the same value, the verifier checks all invariants from all claimed schemas. Conflicting invariants (whose conjunction is unsatisfiable) are not detected by the verifier as such; the construction loop observes the conflict by failing to construct any concrete value. Future work may add diagnostic tooling that identifies which invariants are in tension when construction fails repeatedly. Documented in [`design/rendering-and-views.md`](design/rendering-and-views.md).

Cross-library composition is necessary for organizations that layer custom invariants on top of standard schemas. The current design relies on the verifier being a sound checker and on the agent to choose compatible schemas; whether stronger tool-assisted conflict diagnosis should be part of the language is open.

### Q-035: Layer 7 step 1 — Schema and Invariant node categories (N-032, N-033) {#Q-035}

**Status:** Resolved (implemented in Layer 7 step 1 of the Kotlin/JVM reference implementation, 2026-05-24)
**Concerns:** [`decisions/ADR-009-structured-outputs.md`](decisions/ADR-009-structured-outputs.md), [`design/rendering-and-views.md`](design/rendering-and-views.md), [`design/node-algebra.md`](design/node-algebra.md), Q-025, Q-026, Q-027, Q-028
**Resolution:** The first implementation slice of ADR-009 has landed. N-032 Schema and N-033 Invariant are in the node ADT with canonical encoding; Schema appears in any type position as a `TypeExpr.SchemaType(schemaId, valueType, invariants)` refinement of its declared `valueType`. Pure-expression invariants only (ForeignNode-backed checkers deferred to step 2 when the security-model trust model is extended); verify-time evaluation on statically-known values (literals, ProductValues / SumValues whose sub-values are statically known, Let chains terminating in static values, VarRefs into Let-bound static values, NodeRefs to closed static subgraphs); non-static cases produce a `SchemaInvariantDeferred` informational diagnostic via `VerifyResult.Ok.deferredChecks` rather than rejecting the graph. A new `:schema` Gradle module hosts the invariant-evaluation phase, which runs after the verifier's type-checking pass and before the interpreter's top-level `eval`. Two demonstration corpus schemas — a synthetic `PositiveInt` (Int + `x > 0`) and a `NonEmptyList` over the recursive list type (`non_empty` Match-based invariant) — exercise the mechanism end-to-end through corpus programs 50–53. One implementation deviation from the literal proposal: `targetSchema` is excluded from Invariant's canonical encoding to avoid a Schema↔Invariant hash cycle (the field is retained on the in-memory ADT for the verifier's `InvariantTargetMismatch` topology check); this matches the N-009 ProductTypeField precedent where parent identity is recoverable from the parent's child-list, not from the child's encoding. Original proposal moved to [`proposals/implemented/schema-and-invariant.md`](proposals/implemented/schema-and-invariant.md).

Deferred to subsequent Layer 7 steps: any of the six blessed output libraries (HTML5, SVG, JSON, PDF, plain text, Markdown — each is its own shipping step), ForeignNode-backed invariant checkers (await the security-model extension for checker bindings), symbolic / non-static invariant evaluation (await refinement-type generalization or per-invariant reasoners), provenance manifests (Q-027 — couples to serializer work), live-view composition with state machines (depends on Layer 6 step 2/3), differential rendering, interaction with encrypted nodes (ADR-006), and the schema-strip operation that would allow Schema-typed values to be assigned into plain-Type positions.

## Implementation proposals {#questions-implementation-proposals}

### Q-030: Effect handler algebra and runtime semantics {#Q-030}

**Status:** Resolved (implemented in Layer 3 step 3 of the Kotlin/JVM reference implementation, 2026-05-23)
**Concerns:** [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) § Effect handlers, [`decisions/ADR-004-effects-as-edges.md`](decisions/ADR-004-effects-as-edges.md)
**Resolution:** A `Handler` node category (N-043) intercepts a specified effect category within its body expression and invokes a handler function with the intercepted call's arguments, replacing the call's result with the handler's return value. Restricted to the **no-continuation** form (the handler is just a function, no `resume`, no captured continuation, no abort), which covers test-mocking and effect-redirection without requiring CPS transformation in the tree-walking interpreter. The key novel verifier rule is the effect-closure subtraction `closureOf(handler) = (closureOf(body) - {intercept}) ∪ closureOf(handle) ∪ <handle function's declared effects>` — `Handler` is the only node category that *removes* effects from a closure (`CapabilityScope` narrows the runtime context but does not change the static closure). Verifier additionally enforces signature agreement: every intercepted Application's value-argument and result types must structurally equal the handler's, and the handler must be monomorphic (a `Forall`-typed handle is rejected as `HandlerOverPolymorphicHandle`). Runtime dispatch threads an active-handler stack alongside the capability context; `findLast` selects the innermost handler, matching the lexical-scope reading of nested Handlers. Multi-shot continuations, one-shot continuations, re-raise, and abort-only handlers remain deferred as separate follow-up slices that this design does not foreclose. Original proposal moved to [`proposals/implemented/effect-handlers.md`](proposals/implemented/effect-handlers.md).

`effects-and-capabilities.md` § Effect handlers originally described handlers in prose ("the Handler's body executes; when the body performs an effect that matches the handler's category, control transfers to the handler's `handle` clause...") without pinning the node algebra, closure rule, or interpreter dispatch. ADR-004 § Consequences explicitly deferred the semantics to that document; § Effect handlers has been expanded to carry the full algebra.

### Q-031: Refinement-lattice capability matching {#Q-031}

**Status:** Resolved (implemented in Layer 3 step 2 of the Kotlin/JVM reference implementation, 2026-05-23)
**Concerns:** [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) § Effect closure semantics, § Capability mechanism, § Confused deputy mitigation; [`decisions/ADR-004-effects-as-edges.md`](decisions/ADR-004-effects-as-edges.md); Q-003, Q-005
**Resolution:** `EffectDecl` (N-022) is load-bearing at the call site via a new `Application.effectInstances: List<EffectDecl>` edge that supplies concrete parameter values for the called function's declared effects. The runtime's `Set<EffectCategory NodeId>` capability context is replaced with a structured `CapabilitySet(grants: Map<NodeId, List<CapabilityPattern>>)` where each pattern has `Wildcard | Concrete(Value)` per parameter slot. Matching algorithm: a capability covers a requirement iff each capability slot covers the corresponding requirement slot (wildcard covers anything, concrete covers by value equality). New runtime error `RefinementViolation` is distinguished from `CapabilityViolation` so policy authors see which kind of denial happened. The refinement check fires only for categories with an explicit EffectDecl at the call site; categories declared by the callee but absent from `effectInstances` pass the check after the category-presence check (so a caller forwarding a capability down the chain is not denied at its own call). Confused-deputy mitigation (Q-005) is enabled by parameter-tagged capabilities falling out of this design. The pre-Q-031 `Interpreter.eval(root, Set<NodeId>)` API is preserved as a thin wrapper over `CapabilitySet.ofCategories(...)`. Sub-string and glob wildcards on strings remain deferred; refinement-narrowing on `CapabilityScope` remains deferred behind a separate follow-up question. Original proposal moved to [`proposals/implemented/refinement-lattice-capability-matching.md`](proposals/implemented/refinement-lattice-capability-matching.md).

Today's (Layer 3 step 1) capability matching was by `EffectCategory` NodeId identity only. The design corpus (`design/effects-and-capabilities.md` § Effect closure semantics) called for matching by parameter refinement (`Network.Connect{host: *, port: 443}` covering `Network.Connect{host: "api.com", port: 443}`). Layer 3 step 2 closes that gap end-to-end.

### Q-032: State machine runtime architecture and shipping strategy {#Q-032}

**Status:** Resolved as a three-step strategy; step 1 implemented (Layer 6 step 1 of the Kotlin/JVM reference implementation, 2026-05-24). Steps 2 and 3 remain to be drafted as separate proposals when implementation begins; the high-throughput engineering question they address is tracked under Q-008.
**Concerns:** [`design/state-machines.md`](design/state-machines.md), [`decisions/ADR-007-state-machines.md`](decisions/ADR-007-state-machines.md), [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) § State machine effects, [`design/distribution-model.md`](design/distribution-model.md), Q-008, Q-009, Q-010
**Resolution:** A three-step shipping strategy for Layer 6. Step 1 is a deterministic synchronous trace runtime: `runMachine(machine, events): Trace` drives a single-input-stream machine over a supplied event list in the calling thread. The interpreter remains synchronous; the runtime is a pure fold; replay determinism is guaranteed by construction. Step 1 shipped in the Kotlin/JVM reference implementation 2026-05-24 (`impl-kotlin/runtime/`), with five corpus programs (41-toggle-machine through 45-bank-account-machine), an OutputBatch positional convention (field name `output_i`), and a cached transition-function closure at instance start. Step 2 introduces per-machine Kotlin coroutine actors with channel-based input/output streams, multi-stream FIFO+nondeterministic-merge, and inter-machine wiring; the transition function stays synchronous (T is pure by spec), only the actor loop suspends. Step 3 adds bounded queues with overflow policies (per Q-015), supervisor patterns as a corpus idiom (per ADR-007 — supervisors are not a new node category), and snapshot/replay-from-log persistence. Hot upgrade (Q-010) remains deferred. The deterministic trace API from step 1 survives forever as the debugging and training-corpus-generation interface that step 2 wraps with async I/O. Detailed proposal in [`proposals/implemented/state-machines-runtime.md`](proposals/implemented/state-machines-runtime.md).

ADR-007 calls out the runtime engineering as "substantial and not solved by this ADR" and points at Q-008 as the open implementation problem with BEAM as the architectural baseline. This question extends Q-008 with a concrete step-by-step plan; step 1 is now executed, steps 2 and 3 remain — they address the high-throughput multi-machine engineering Q-008 calls out, which remains Open until step 2 lands.

### Q-033: State machines step 2 — async multi-machine actor runtime {#Q-033}

**Status:** Resolved (implemented in Layer 6 step 2 of the Kotlin/JVM reference implementation, 2026-05-24)
**Concerns:** [`design/state-machines.md`](design/state-machines.md), [`decisions/ADR-007-state-machines.md`](decisions/ADR-007-state-machines.md), [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) § State machine effects (E-028..E-031), [`design/distribution-model.md`](design/distribution-model.md), Q-008, Q-009, Q-032
**Resolution:** A per-machine Kotlin coroutine actor model running on a shared dispatcher. Each `MachineInstance` runs in its own `launch { }` coroutine; input streams become `Channel<Value>` of bounded capacity (default 1024); the actor loop uses Kotlin's `select` over the machine's input channels to implement FIFO-per-stream + nondeterministic merge (Q-009's default). Multi-input machines see events wrapped in a runtime-synthesized tagged-Event sum (`InputEvent = stream_0(T₁) | stream_1(T₂) | ...`); multi-output machines return `(State, List<TaggedOutput>)` using recursive types (N-041/N-042). Inter-machine wiring is structural — EventStreams declared `streamKind: internal` are shared `Channel<Value>` instances between the producing and consuming machines, validated by new verifier rules (single-producer, no-orphan). Implicit `StateMachine.Send`/`Receive` effects (E-028, E-029) propagate through the verifier; the machine's declared `effects` must cover them. Supervisor patterns are state machines (no new node category); step 2 ships a one-for-one restarter as a corpus capstone, with E-030/E-031 spawn/terminate effects wired. The interpreter stays synchronous (transitions are pure); only the actor loop suspends. Step 1's `runMachine(machine, events): Trace` survives unchanged as the deterministic-replay seam — async runs record their consumed events via a per-instance recorder, then replay through `runMachine` for trace-equality assertions. Three deviations from the literal proposal worth recording: (1) the new internal-stream topology rules listed in the proposal's § 5 ship as **runtime-side** `MachineGroupValidationError` variants rather than verifier rules, because the per-machine verifier reasons about one StateMachine at a time and cannot reason across cross-machine stream connections; promoting these to true verifier rules would require a new "GroupVerifier" pass; (2) the implicit `StateMachine.Send` / `Receive` effects (E-028/E-029) remain runtime-internal and unenforced by the verifier, deferred to step 3 along with a registration mechanism for "well-known" EffectCategory nodes; (3) the supervisor corpus program (48) implements the proposal's § 6.6 observational supervisor framing (multi-input merge wired via internal streams) rather than the § 7 spawn-and-restart framing, which needs real dynamic spawn/terminate via E-030/E-031 and lands in step 3. Original proposal moved to [`proposals/implemented/state-machines-runtime-step-2.md`](proposals/implemented/state-machines-runtime-step-2.md).

Deferred to step 3 or later: bounded-queue overflow policies beyond block-producer, multi-producer fan-in and broadcast fan-out on internal streams, supervisor restart policies beyond one-for-one with real spawn/terminate via E-030/E-031, snapshot/replay-from-log persistence, priority/causal/timestamp merges (Q-009 alternatives), implicit StateMachine.Send/Receive verifier enforcement via a well-known-EffectCategory registration mechanism, distributed execution. Hot upgrade (Q-010) remains a separate question entirely.

### Q-037: Agent-native LLM capabilities as first-class builtins {#Q-037}

**Status:** Resolved (Phase 1 implemented 2026-05-26; proposal moved to [`proposals/implemented/agent-native-capabilities.md`](proposals/implemented/agent-native-capabilities.md). Phase 2 — agent-pattern documentation and reference corpus — remains.)
**Concerns:** [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md), [`design/state-machines.md`](design/state-machines.md), [`design/rendering-and-views.md`](design/rendering-and-views.md), [`design/security-model.md`](design/security-model.md), [`decisions/ADR-004-effects-as-edges.md`](decisions/ADR-004-effects-as-edges.md), [`decisions/ADR-005-foreign-nodes.md`](decisions/ADR-005-foreign-nodes.md), [`decisions/ADR-009-structured-outputs.md`](decisions/ADR-009-structured-outputs.md), Q-031 (refinement-lattice), Q-033 (async actor runtime), Q-035 (schema + invariant), Q-038 (vector stores)
**Resolution:** Per-provider ForeignNodes under **operation-shaped** effect categories for language-model generation and embedding. Initial categories: E-035 LLM.Generate (parameters: `provider: String, model: String`) and E-036 LLM.Embed (same parameters). Provider identity lives at the binding layer (each provider has its own ForeignNode — `strand-builtin:Anthropic.Messages.Create`, `strand-builtin:OpenAI.Chat.Completions`, `strand-builtin:Gemini.GenerateContent`, etc. — content-addressed independently, signed and reviewed per binding); the category captures the kind-of-side-effect. The `provider` and `model` refinement parameters scope capabilities at the lattice level via Q-031, exactly like `Network.Connect{host, port}` scopes by host: `LLM.Generate{provider: "anthropic", model: *}` authorizes any Anthropic model, `LLM.Generate{provider: *, model: *}` authorizes any provider, `LLM.Generate{provider: "anthropic", model: "claude-opus-4-7"}` authorizes exactly one. The operation-shaped category is consistent with Strand's existing E-001..E-034 (`Filesystem.Read{path}`, `Network.Connect{host, port}`), with WIT / WASI (one `wasi:http` interface with per-runtime implementations), and with effect-systems research (Koka, Eff, Frank, OCaml 5 effects — all operation-shaped). No central LLM dispatch table at runtime — provider choice is structural in the graph (different ForeignNode = different graph hash). Multi-provider workflows compose via graph-level Match Lambdas over a `Provider` SumValue; the verifier sees the union of all provider EffectDecls in the closure with their distinct `provider` refinement values. No new node categories. Long-running agents map onto the existing Layer 6 state-machine model — E-017 Memory.MutableState confirmed absent after walking eight agent workloads against state-machine modeling. Tools are Strand callables (Lambdas or ForeignNodes) bound tightly to a `parameterSchema: Schema`; the translator supports an irreducible JSON-Schema-expressible subset of TypeExpr (Primitive, Product, Sum with discriminator, RecursiveType via $ref, Option<T>) and explicitly rejects FunctionType, ForallType, and non-JSON-Schema-expressible invariant bodies via a new `ToolParamTypeUnsupported` verifier rule. Two-tier validation: provider constrains decoding to the translated JSON Schema; Strand re-validates with the full Schema (including non-translatable invariants) after parse. Structured-output calls integrate with N-032 Schema and N-033 Invariant directly via `GenerateRequest.responseSchema`. Embeddings return Bytes (IEEE 754 float32 LE) with documented upgrade path to an unparameterized `TypeExpr.Vector` primitive gated on (a) vector-math stdlib landing AND (b) ≥3 real dimension-mismatch bugs from agent corpora — the [`nested-recursive-self-depth`](proposals/implemented/nested-recursive-self-depth.md) precedent informs this conservative stance. Conversation handles are opt-in `Value.Resource(kind: "llm_conversation")` for provider-side prompt caching or thread state; stateless calls are the default. API credentials live outside the graph in a host-supplied `CredentialProvider` so graphs are portable across runtimes with rotating keys. Vector storage and retrieval is split into a sibling proposal [Q-038](#Q-038) because the LLM.Embed → Vector.* coupling is weaker than originally framed (embeddings are Bytes; any consumer takes them) and Vector.* has substantial independent API-shape questions. Two shippable phases: Phase 1 per-provider Generate + Embed for Anthropic / OpenAI / Gemini, Phase 2 agent-pattern documentation and corpus. Streaming, typed-Resource refinement types, multi-modal capability variants, and mandatory-handle alternatives deferred. Detailed proposal in [`proposals/agent-native-capabilities.md`](proposals/agent-native-capabilities.md).

The original 2026-05-26 draft proposed a single `LLM.*` + `Vector.*` namespace with `provider: String` as a refinement parameter on unified effect categories and a runtime dispatch-on-string registry. A five-call analysis pass flipped the design to per-provider ForeignNodes *and* per-provider effect categories (the first revision). A follow-up prior-art check found the per-provider-effect-categories part of that revision unsupported by both effect-systems research (Koka, Eff, WIT — all operation-shaped) and Strand's own E-001..E-034 precedent; the second revision reverted to operation-shaped categories while keeping per-provider ForeignNodes (the load-bearing piece for content addressing and provenance trust). The existing builtins (~110 across 18 namespaces after stdlib expansion rounds 1–3) cover the conventional stdlib surface — arithmetic, strings, filesystem, network, processes, HTTP, JSON. Agents writing Strand programs that *do* agent work today must either reach for `Http.Request` (treating Anthropic's API as a raw HTTP endpoint and hand-assembling JSON) or have the host inject per-provider ForeignNodes at every binding boundary. The companion tracking catalog [`proposals/stdlib-future-builtins.md`](proposals/stdlib-future-builtins.md) flags the dependency: the agent-native shape may influence which Round 5+ stdlib slices are picked.

### Q-038: Agent-native vector stores {#Q-038}

**Status:** Resolved (Phase 1 implemented 2026-05-26; proposal moved to [`proposals/implemented/agent-native-vector-stores.md`](proposals/implemented/agent-native-vector-stores.md). Phases 2 (pgvector + FAISS) and 3 (Weaviate + Qdrant + RAG-demo corpus) remain.)
**Concerns:** [Q-037](#Q-037), [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md), [`design/security-model.md`](design/security-model.md), [`decisions/ADR-005-foreign-nodes.md`](decisions/ADR-005-foreign-nodes.md), Q-031 (refinement-lattice), Q-035 (schema + invariant — typed metadata is a future extension)
**Resolution:** Per-provider ForeignNodes under **operation-shaped** effect categories for vector storage and similarity search, consistent with [Q-037](#Q-037)'s second revision. Initial categories: E-037 Vector.Read (parameters: `provider: String, store: String`) and E-038 Vector.Write (same parameters). Read / Write split mirrors `Filesystem.Read` / `Filesystem.Write` (E-006 / E-007) so capability minimization can grant query-only access without grant-everything. Per-provider ForeignNodes live at the binding layer with their own provenance, credentials, and HTTP/auth shape: `strand-builtin:Pinecone.Index.Open/Close/Upsert/Query/Delete/Fetch`, `strand-builtin:Chroma.Collection.*`, `strand-builtin:Pgvector.Connection.* / Insert / Query / Delete`, etc. Each provider's open / close pair manages a Resource handle with a provider-specific `kind` (`pinecone_index`, `chroma_collection`, `pgvector_connection`, etc.). The cross-provider intersection (open-handle / bulk-upsert / top-k-query / delete-by-id) is shared in a common shape; provider-specific divergence is honest at the surface (Pinecone hides ANN index types; pgvector exposes them; metric grain is per-collection for managed services and per-query for pgvector). The seven open API design questions Vector.* raised get explicit answers: metric grain per-collection except pgvector which is per-query; index-type opacity follows the underlying provider; filter expression language is loose JsonValue for the initial slice with a typed `FilterExpr` sum as a follow-up; metadata shape is loose JsonValue with Schema-typed metadata as a follow-up; batching is bulk by default; pagination is deferred (single-shot queries up to k=10000); idempotency is upsert semantics across the board. Embeddings produced by [Q-037](#Q-037)'s `*.Embed` builtins flow directly into `*.Upsert` and `*.Query` as Bytes. Multi-provider dispatch via graph-level Match Lambda over a `VectorStore` SumValue, just as [Q-037](#Q-037) handles multi-LLM-provider dispatch — the verifier sees EffectDecls under `Vector.Read` / `Vector.Write` with distinct `provider` refinement values. Three shippable phases: Phase 1 Pinecone + Chroma (managed + simple OSS), Phase 2 pgvector + FAISS (SQL-shaped + in-process), Phase 3 Weaviate + Qdrant (richer feature sets) plus agent-pattern documentation. No new node categories. Detailed proposal in [`proposals/agent-native-vector-stores.md`](proposals/agent-native-vector-stores.md).

Vector stores were originally scoped inside Q-037 as Phase 2. The five-call analysis pass found the LLM.Embed → Vector.Insert coupling weaker than the original framing (once embeddings are Bytes, every downstream consumer is independent) and Vector.* has substantial independent API-shape questions that warrant dedicated treatment. The split keeps each proposal's review surface coherent. The first revision of Q-038 proposed per-provider effect categories (E-041 Pinecone.Read through E-048 Pgvector.Write); the second revision aligns with Q-037's operation-shaped-category direction. A typed `FilterExpr` and Schema-typed metadata are flagged as strong follow-up proposals once the cross-provider filter and metadata stories are settled.

## Recursive types {#questions-recursive-types}

### Q-029: Recursive types and termination of canonical hashing {#Q-029}

**Status:** Proposed
**Concerns:** [`design/node-algebra.md`](design/node-algebra.md), [`decisions/ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md)
**Resolution:** A μ-binder mechanism via two new node categories: `RecursiveType` (N-041) and `RecursiveSelf` (N-042). RecursiveType introduces a positional self-binder over its body; RecursiveSelf inside the body resolves by de Bruijn depth, not by hash. This is exactly analogous to how `Lambda`/`VarRef` and `ForallType`/`TypeParameter` handle their respective binders. Equirecursive equality is decided by hash equality of the canonical encoding — no separate coinductive equality algorithm at the language level. ADR-003 is reinforced rather than revised: the new node pair is precisely the "fixed-point indirection" ADR-003 endorses for term-level recursion, extended to types. The body must be contractive (every path from binder to a RecursiveSelf reference traverses at least one type constructor) to guarantee termination of any monotone fold. Proposed in [`design/node-algebra.md`](design/node-algebra.md) § Recursive types.

How can a recursive type (`List = Nil | Cons(head: Int, tail: List)`) be content-addressed when its hash would depend on itself? ADR-003 explicitly rules out self-referential hashes ("recursion through a node's own hash is undecidable"). node-algebra.md originally said "recursive types must go through Fixpoint or NodeRef" but neither mechanism was sufficient — Fixpoint is term-level, NodeRef walks to its target during hashing. Mutually recursive type families are encodable via single-product `RecursiveType` + projection; higher-arity recursive binders are a possible future extension but not required by the proposal.

## Question lifecycle

When a question is resolved:

1. The question's entry is updated with the resolution and a reference to the document recording the resolution.
2. The question's status changes to "Resolved."
3. The entry is retained in this document for historical reference.

When a new question is identified:

1. A new entry is added with the next available identifier.
2. The relevant specification documents are updated to reference the new question if appropriate.
3. The question is assigned to the appropriate topic section.

When a question is deferred:

1. The question's status changes to "Deferred."
2. The rationale for deferral is recorded in the entry.
3. The entry remains in this document for future reconsideration.

## References

**Outgoing references:**
- All design and decision documents

**Incoming references:**
- [`README.md`](README.md)
- All design and decision documents that have unresolved questions
