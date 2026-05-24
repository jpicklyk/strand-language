# Open Questions {#open-questions}

**Document:** `open-questions.md`
**Status:** Living document; updated as questions are opened or resolved
**Last revised:** 2026-05-24 (Q-035 Layer 7 step 1 Schema + Invariant marked Resolved after implementation in the Kotlin/JVM reference implementation; proposal moved to `proposals/implemented/schema-and-invariant.md`. The N-032 Schema and N-033 Invariant slots, reserved since Wave 3 design, are now load-bearing. Q-033 state machines step 2 partially landed alongside (runtime infrastructure + corpus 46 in; multi-stream verifier lift, corpus 47-49, and `strand group` CLI deferred). 2026-05-24 (Q-035 Layer 7 step 1 Schema + Invariant proposal added with proposed resolution in `proposals/schema-and-invariant.md`. First slice of ADR-009: pure-expression invariants, verify-time evaluation on statically-known values, new `:schema` Gradle module; blessed output libraries, ForeignNode-backed checkers, provenance, and live views explicitly deferred. 2026-05-24 (Q-034 authoring-layer design for efficient LLM emission registered as Open in the Tooling and ecosystem section; resolution deferred until the Kotlin/JVM reference implementation can host an agent-driven authoring loop. 2026-05-24 (Q-033 state machines step 2 — async multi-machine actor runtime — added with proposed resolution in `proposals/state-machines-runtime-step-2.md`. Extends Q-008 with the concrete actor + channel design and integrates Q-009 via select-based nondeterministic merge. 2026-05-24 (Q-032 state machine runtime architecture marked Resolved at the strategy level after step 1 implementation in Layer 6 step 1 of the Kotlin/JVM reference implementation; steps 2 and 3 deferred. 2026-05-23 (Wave 3 resolutions added; rendering questions Q-025 through Q-028 added; Q-029 recursive types added with proposed resolution; Q-030 effect handler algebra, Q-031 refinement-lattice capability matching, Q-032 state machine runtime architecture added — all with proposed resolutions in `proposals/`. Q-031 marked Resolved after Layer 3 step 2 implementation. Q-030 marked Resolved after Layer 3 step 3 implementation.))

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

**Status:** Open (design constraints specified; runtime engineering remains)
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

**Status:** Open (partial proposal; topology obfuscation deferred)
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

**Status:** Open (architecture and constraints specified; instruction set design remains)
**Concerns:** [`decisions/ADR-008-compilation-target.md`](decisions/ADR-008-compilation-target.md), [`design/node-algebra.md`](design/node-algebra.md)
**Partial resolution:** The VM is a stack-based bytecode with first-class effect/capability operations, content-addressed identity for nodes and references, support for state machines and event streams, and replay determinism. The full instruction set, value representation, calling convention, and GC algorithm are deferred to implementation (Milestone 2.3 in [`research-plan.md`](research-plan.md)).

The decision to use a bytecode VM as the initial execution target requires a bytecode specification: instruction set, value representation, calling convention, garbage collection model. None of this has been specified.

### Q-018: MLIR dialect design {#Q-018}

**Status:** Open (strategy specified; dialect design remains)
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

**Status:** Proposed
**Concerns:** [`research-plan.md`](research-plan.md)
**Resolution:** Three task suites (reproduction, effects, distribution); five baselines (Python+type-hints, Kotlin Coroutines, Rust, TypeScript-strict, SimPy/ShortCoder); seven metrics (first-pass correctness, tokens per successful task, verification feedback cost, effect declaration accuracy, capability minimization score, distribution overhead, replay determinism). Hypothesis supported if at least three of five claims meet statistical significance. Proposed in [`research-plan.md`](research-plan.md).

The hypothesis stated in [`00-motivation.md`](00-motivation.md) is empirically testable. The specific metrics (correctness rates, tokens per task, error-recovery latency, distribution overhead) and baselines (Python with manual annotations, Kotlin with Coroutines, Rust with explicit threading) have not been specified.

### Q-022: Confidentiality of agent intent {#Q-022}

**Status:** Deferred
**Concerns:** [`design/security-model.md`](design/security-model.md) (forthcoming)

Defending against AI agents themselves retaining detailed knowledge of generated programs (model providers learning from usage, future models inferring patterns from historical interactions) is interesting but speculative. Deferred to future research phase. Partial defenses include compositional opacity (multiple agents seeing only slices), differential privacy in agent traces, and semantic encryption of the problem domain presented to the agent.

## Tooling and ecosystem {#questions-tooling}

### Q-023: Graph editor for human inspection {#Q-023}

**Status:** Open (requirements identified; tooling specification deferred to Phase 4)
**Concerns:** Tooling
**Partial resolution:** The minimum required tooling — graph queries, structured diffs, subgraph rendering — is identified in [`decisions/ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md) consequences and in [`design/security-model.md`](design/security-model.md). Detailed tooling specification is part of Phase 4 (production hardening and adoption) in [`research-plan.md`](research-plan.md), not part of the core language design.

The decision to omit a textual projection layer ([`ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md)) implies that human inspection occurs through analysis tooling. The minimum viable tooling — graph queries, dependency visualizations, structured diffs — has not been specified. For failure forensics specifically, a way to render a failing subgraph for human inspection is required.

### Q-024: Versioning and migration of the language itself {#Q-024}

**Status:** Proposed
**Concerns:** [`design/node-algebra.md`](design/node-algebra.md), all design documents
**Resolution:** Conservative additive versioning. Node category IDs are stable; new categories receive higher numbers; existing IDs never reused. Schema changes are new category IDs, not modifications of existing ones. Effect categories extend in the same pattern. Older graphs remain valid because their hashes are stable; older runtimes reject graphs that use newer categories. Proposed in [`design/node-algebra.md`](design/node-algebra.md).

The Strand specification will evolve. How existing graphs migrate when the node algebra changes, how effects can be added without invalidating older graphs, and how tooling tracks language versions have not been specified.

### Q-034: Authoring-layer design for efficient LLM emission {#Q-034}

**Status:** Open
**Concerns:** [`00-motivation.md`](00-motivation.md), [`decisions/ADR-001-graph-not-text.md`](decisions/ADR-001-graph-not-text.md), [`decisions/ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md), [`design/node-algebra.md`](design/node-algebra.md), [`research-plan.md`](research-plan.md)

The canonical authoring format — dag-json with full hash references, mandatory type annotations at every function boundary, and explicit effect declarations at every composition site — is verbose at the surface. An LLM emitting it node-by-node likely consumes substantially more tokens than the equivalent program in a conventional source language; informal estimates place the multiplier at roughly 2-5× for non-trivial programs before any tooling intervention. This pressures the AI-first framing in [`00-motivation.md`](00-motivation.md): if generation cost dominates retry cost, the verifier's correctness wins may not pay for themselves on a per-program token basis.

The graph remains the source of truth per [ADR-001](decisions/ADR-001-graph-not-text.md); [ADR-002](decisions/ADR-002-no-human-projection.md) rejects a *human*-readable projection. Neither decision speaks to an authoring layer optimized for LLM emission that compiles to canonical form before reaching the verifier. The question is what that authoring layer is and how much of the verbosity it can absorb.

Several candidate techniques are recognized, none yet adopted or proposed as the resolution.

**Authoring-layer projection.** A compact serialization (symbolic builtin references, operator-like sugar, positional encoding) that tooling expands to canonical dag-json. Distinct from a human-readable projection in audience and intent.

**Inference passes on the authoring layer.** Type inference and effect propagation that fill in derivable annotations before the graph reaches the verifier. The verifier still sees a fully-annotated graph; the LLM omits what the tooling can recover.

**Tokenizer alignment.** Domain-specific tokenizers that treat node category tags, edge labels, common builtin target strings, and base-N hash digests as single tokens.

**Constrained generation.** Grammar-driven decoding against the closed inventory of node categories and per-category edge schemas, reducing the model's commitment cost at each position.

**Session-scoped handles.** Short local identifiers for recently-introduced nodes within an authoring session, resolved to canonical hashes at commit.

**Tool-call assembly.** The LLM constructs the graph through tool invocations rather than serialized output, permitting incremental validation and shorter per-step output.

Resolution requires a working verifier and an agent generating against it. Meaningful evaluation pairs candidate techniques with measured tokens-per-successful-task across a representative task suite, against both raw canonical emission and the conventional-language baselines named in [Q-021](#Q-021). Investigation is deferred until the Kotlin/JVM reference implementation can host an agent-driven authoring loop.

## Rendering and structured outputs {#questions-rendering}

### Q-025: Schema mechanism scope {#Q-025}

**Status:** Proposed
**Concerns:** [`design/rendering-and-views.md`](design/rendering-and-views.md), [`decisions/ADR-009-structured-outputs.md`](decisions/ADR-009-structured-outputs.md)
**Resolution:** Two new node categories (Schema N-032, Invariant N-033) extend the algebra. Invariants are pure Strand expressions over a value's structure or ForeignNode-backed checkers registered with the verifier. The mechanism is initially scoped to output formats but is general enough to apply to configuration data, message protocols, and other structured values. Generalization beyond outputs is recognized in [`design/rendering-and-views.md`](design/rendering-and-views.md) as a future direction; it is not specified in the current design. Proposed in [`design/rendering-and-views.md`](design/rendering-and-views.md).

The schema mechanism introduced by [ADR-009](decisions/ADR-009-structured-outputs.md) is the primary mechanism by which the verifier reasons about structured outputs. The extent to which it generalizes (refinement types, dependent types, arbitrary predicates with SMT-backed decision procedures) determines how much of the verifier's design must accommodate it. The current design adopts a constrained subset; whether to extend further is open.

### Q-026: Blessed output library set {#Q-026}

**Status:** Proposed
**Concerns:** [`design/rendering-and-views.md`](design/rendering-and-views.md)
**Resolution:** Six schemas in the reference distribution: HTML5 (with `Html5Document`, `Html5AccessibleAA`, `Html5StrictCSP` layered variants), SVG (`SvgDocument`), JSON (`JsonValue`), PDF (`PdfDocument` targeting PDF/A-2u), plain text (`PlainTextDocument`), and Markdown (`MarkdownDocument`). Curation criteria: widespread relevance, well-defined structural invariants, maintained reference implementation. Additional formats are introduced through the standard library-loading mechanism. Proposed in [`design/rendering-and-views.md`](design/rendering-and-views.md).

Which structured output formats are included as blessed schemas in the reference distribution affects what agents can rely on as available without additional library configuration. Including too few leaves agents working with raw `Bytes` for common formats; including too many ties the language distribution to format curation and audit. The selection criteria and the initial set have been specified; the boundary may move as the ecosystem develops.

### Q-027: Provenance encoding for output artifacts {#Q-027}

**Status:** Open (mechanism specified; format details deferred)
**Concerns:** [`design/rendering-and-views.md`](design/rendering-and-views.md)
**Partial resolution:** Serializers may emit a provenance manifest mapping output byte ranges to source node hashes. The manifest is content-addressed and opt-in. The uniform format is a tree of byte ranges paired with node hashes; format-specific extensions (source-map-compatible output for HTML/SVG) are allowed where they integrate with existing tooling. Detailed format specification is part of the reference implementation work. Proposed in [`design/rendering-and-views.md`](design/rendering-and-views.md).

Tracing a position in rendered output back to the source nodes that produced it is the foundation for debugging, audit, differential rendering, and event routing. The encoding format determines how cheap this tracing is in practice and whether existing tooling (browser dev tools, PDF viewers) can consume the provenance directly. The exact format for each blessed schema is open.

### Q-028: Cross-library invariant composition {#Q-028}

**Status:** Open (current design defers conflict detection to the agent's construction loop)
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
**Resolution:** A three-step shipping strategy for Layer 6. Step 1 is a deterministic synchronous trace runtime: `runMachine(machine, events): Trace` drives a single-input-stream machine over a supplied event list in the calling thread. The interpreter remains synchronous; the runtime is a pure fold; replay determinism is guaranteed by construction. Step 1 shipped in the Kotlin/JVM reference implementation 2026-05-24 (`impl/runtime/`), with five corpus programs (41-toggle-machine through 45-bank-account-machine), an OutputBatch positional convention (field name `output_i`), and a cached transition-function closure at instance start. Step 2 introduces per-machine Kotlin coroutine actors with channel-based input/output streams, multi-stream FIFO+nondeterministic-merge, and inter-machine wiring; the transition function stays synchronous (T is pure by spec), only the actor loop suspends. Step 3 adds bounded queues with overflow policies (per Q-015), supervisor patterns as a corpus idiom (per ADR-007 — supervisors are not a new node category), and snapshot/replay-from-log persistence. Hot upgrade (Q-010) remains deferred. The deterministic trace API from step 1 survives forever as the debugging and training-corpus-generation interface that step 2 wraps with async I/O. Detailed proposal in [`proposals/implemented/state-machines-runtime.md`](proposals/implemented/state-machines-runtime.md).

ADR-007 calls out the runtime engineering as "substantial and not solved by this ADR" and points at Q-008 as the open implementation problem with BEAM as the architectural baseline. This question extends Q-008 with a concrete step-by-step plan; step 1 is now executed, steps 2 and 3 remain — they address the high-throughput multi-machine engineering Q-008 calls out, which remains Open until step 2 lands.

### Q-033: State machines step 2 — async multi-machine actor runtime {#Q-033}

**Status:** Proposed
**Concerns:** [`design/state-machines.md`](design/state-machines.md), [`decisions/ADR-007-state-machines.md`](decisions/ADR-007-state-machines.md), [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) § State machine effects (E-028..E-031), [`design/distribution-model.md`](design/distribution-model.md), Q-008, Q-009, Q-032
**Resolution:** A per-machine Kotlin coroutine actor model running on a shared dispatcher. Each `MachineInstance` runs in its own `launch { }` coroutine; input streams become `Channel<Value>` of bounded capacity (default 1024); the actor loop uses Kotlin's `select` over the machine's input channels to implement FIFO-per-stream + nondeterministic merge (Q-009's default). Multi-input machines see events wrapped in a runtime-synthesized tagged-Event sum (`InputEvent = stream_0(T₁) | stream_1(T₂) | ...`); multi-output machines return `(State, List<TaggedOutput>)` using recursive types (N-041/N-042). Inter-machine wiring is structural — EventStreams declared `streamKind: internal` are shared `Channel<Value>` instances between the producing and consuming machines, validated by new verifier rules (single-producer, no-orphan). Implicit `StateMachine.Send`/`Receive` effects (E-028, E-029) propagate through the verifier; the machine's declared `effects` must cover them. Supervisor patterns are state machines (no new node category); step 2 ships a one-for-one restarter as a corpus capstone, with E-030/E-031 spawn/terminate effects wired. The interpreter stays synchronous (transitions are pure); only the actor loop suspends. Step 1's `runMachine(machine, events): Trace` survives unchanged as the deterministic-replay seam — async runs record their consumed events via a per-instance recorder, then replay through `runMachine` for trace-equality assertions. Detailed proposal in [`proposals/state-machines-runtime-step-2.md`](proposals/state-machines-runtime-step-2.md).

Deferred to step 3 or later: bounded-queue overflow policies beyond block-producer, multi-producer fan-in and broadcast fan-out on internal streams, supervisor restart policies beyond one-for-one, snapshot/replay-from-log persistence, priority/causal/timestamp merges (Q-009 alternatives), distributed execution. Hot upgrade (Q-010) remains a separate question entirely.

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
