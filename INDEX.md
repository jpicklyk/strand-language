# Index {#index}

**Document:** `INDEX.md`
**Status:** Living document; updated as corpus grows
**Last revised:** 2026-05-24 (Q-017 bytecode VM step 1 proposed — `proposals/bytecode-vm-step-1.md`. Two-step shipping strategy: step 1 ships a Kotlin reference VM (`:bytecode` + `:vm` modules) that runs every corpus program with byte-identical traces to the tree-walking interpreter; step 2 ports to Rust per ADR-008 and tunes for performance. Path B (Kotlin-first) chosen over Path A (Rust-first) because iteration speed dominates: design validation happens at week 4-6 instead of month 4-6, surfacing surprises cheaply. 28-opcode stack-based instruction set, uniform-boxed value representation, JVM-leaning GC, node-by-node lowering with types/schemas/recursive types erased and state machines remaining as runtime objects consuming bytecode for transition functions. ~70% of step 1's work transfers to step 2's Rust port. Q-017 status moves Open → Proposed.) 2026-05-24 (Layer 6 step 3 slice 3.5 implemented — implicit `StateMachine.Send`/`Receive` verifier enforcement via a new internal `WellKnownEffect` registry. `Verifier.inferStateMachine` step 8: collect EffectCategory names from the machine's declared effects, require `"StateMachine.Receive"` (always) and `"StateMachine.Send"` (when outputs declared); missing entries surface as new `VerifyError.StateMachineMissingImplicitEffect(at, missing: Set<String>)`. All 9 state-machine corpus programs 41-49 updated to declare the well-known categories; 3 corresponding Layer A files (41, 42, 47), the `StateMachineRuntimeTest` fixtures, and the verifier test helper all updated. Two new verifier test cases cover the new error variant. Reusable mechanism ready for future runtime-implicit effects (Spawn/Terminate already in the registry for slice 3.2's consumption). Five other slices of step 3 (3.1 backpressure, 3.2 supervision, 3.3 snapshot/replay, 3.4 metrics, 3.6 fan-in/fan-out) remain pending. 346 tests passing.) 2026-05-24 (Q-034 step 1 extended with minimal Layer C — `Elaborator` ships effect-closure inference for `Lambda.effects`. When Layer A omits a Lambda's `effects` arg, elaboration walks the body's author-id graph and aggregates effects from reachable Application sites following the verifier's `nodeClosures` rules. `Authoring.compileWithElaboration(text)` is the elaborate-then-emit entry; `strand author --elaborate` is the CLI flag. End-to-end demo at `corpus/layer-a/elaborated/17-elided-effects.layer-a`. 5 ElaboratorTest cases pass. Plus 19 more corpus programs translated, bringing the bilingual subset to 33 (3.40× total byte compression across the subset). Other three Layer C inference cases (paramType, typeArguments, effectInstances) deferred. Also: Q-008/Q-015 — Layer 6 step 3 proposal added at `proposals/state-machines-runtime-step-3.md` covering bounded-queue overflow policies, supervisor restart with real spawn/terminate via E-030/E-031, snapshot/replay-from-log, runtime metrics, implicit Send/Receive verifier enforcement via well-known-EffectCategory registry, and fan-in/fan-out on internal streams (six independently-shippable slices). Status: Proposed.) 2026-05-24 (Q-034 step 1 — Layer A authoring projection — extended to **full grammar coverage of the implemented node algebra**: 42 codes spanning Layers 1-7 (literals, types, binding, effects + capability scope, foreign, match + 4 pattern variants, fixpoint, product/sum values, recursive types, handlers, state machines + 3 EventStream variants + Transition, schemas + invariants), variant-bearing categories use a `discriminator` schema field, only N-030 Name and N-031 Provenance unmapped. 14 corpus programs (01-04, 12, 15, 18, 21, 23, 25, 31, 36, 41, 50) now ship in both `.json` and `.layer-a` forms; round-trip hash-equality asserted for each. **Measured 3.39× total byte compression** across the 14-program subset (range 2.82-4.40×), within the proposal's projection-only estimate. Layer B / Layer C / tokenizer alignment / full corpus translation / Q-021 evaluation framework remain pending. Q-034 status stays Proposed.) 2026-05-24 (Q-034 step 1 first slice — Layer A compact text projection + parser + dag-json emitter for Layer 1 node categories — implemented in the Kotlin/JVM reference implementation. New `:authoring` Gradle module, 17 codes covering literals/types/lambda/application/let/varref/NodeRef/TypeAbstraction/ForallType, four corpus programs (01-04) ship in both `.json` and `.layer-a` forms with a round-trip hash-equality test, new `strand author` CLI subcommand. Measured 3-4× byte reduction over canonical dag-json on the subset, consistent with the proposal's §6 projection-only estimate. Layer B grammar-constrained decoding, Layer C bidirectional elaboration, grammar coverage of effects/foreign/match/fixpoint/state-machines/schemas/recursive-types/handlers, and the full Q-021 evaluation framework remain pending. Q-034 status stays Proposed.) 2026-05-24 (Q-026 first blessed output library — JSON — shipped in Layer 7 step 1.5 of the Kotlin/JVM reference implementation; `JsonValue` flat primitives sum + `UniqueKeyJsonObject` Schema with `Fixpoint`+`Match` `unique_keys` invariant; corpus programs 54-56 added; `Bool.And` / `Bool.Or` / `String.Eq` builtins added; proposal at `proposals/implemented/json-blessed-library.md`. Nested-μ JsonArray/JsonObject cases deferred — would need a richer RecursiveSelf protocol that the current "always innermost" semantics blocks. No new node-category, ADR, or effect-category identifiers were required.) 2026-05-24 (Q-034 authoring-layer research proposal added at `proposals/llm-authoring-layer.md`. A four-layer LLM emission stack: compact text projection (Layer A) + grammar-constrained decoding (Layer B) + bidirectional elaboration (Layer C) + existing canonical verifier (Layer D). Estimated 3-8× token-cost reduction over canonical dag-json; final adoption gated on Phase 1 / Q-021 measurements. Tokenizer alignment recognized as future Phase 4 work. Status: Proposed.) 2026-05-24 (Q-033 state machines step 2 — async multi-machine actor runtime — fully implemented; the previously-deferred verifier multi-stream lift, corpus programs 47-49, and `strand group` CLI subcommand all landed alongside the prior runtime infrastructure. Verifier `StateMachineInputStreamCountUnsupported` rule removed; `Verifier.synthesizeInputEventSum` and `Verifier.synthesizeTaggedOutputListType` synthesize the multi-input tagged sum and the recursive tagged-output list shape respectively. Proposal moved to `proposals/implemented/state-machines-runtime-step-2.md`. Implicit StateMachine.Send/Receive verifier enforcement, real spawn/terminate supervisor, and `MachineGroupValidationError`-to-verifier promotion all deferred to step 3.) 2026-05-24 (Q-035 Layer 7 step 1 Schema + Invariant implemented in the Kotlin/JVM reference implementation; proposal moved to `proposals/implemented/schema-and-invariant.md`. New `:schema` Gradle module hosts the verify-time invariant-evaluation phase; the N-032 Schema and N-033 Invariant slots — reserved since Wave 3 design — are now load-bearing. Q-033 state machines step 2 partially landed alongside: runtime infrastructure + corpus 46 in; multi-stream verifier lift, corpus 47-49, and `strand group` CLI deferred. No new node-category, ADR, or effect-category identifiers were required.) 2026-05-24 (Q-035 Layer 7 step 1 Schema + Invariant proposal added; draft proposal at `proposals/schema-and-invariant.md`. First slice of ADR-009 — pure-expression invariants on statically-known values; reference output libraries and ForeignNode-backed checkers deferred. No new node-category, ADR, or effect-category identifiers were required — N-032 Schema and N-033 Invariant slots have been in the registry since Wave 3 design.) 2026-05-24 (Q-034 authoring-layer design for efficient LLM emission added to `open-questions.md` under Tooling and ecosystem; concept index entry for "Authoring layer (LLM emission)" added. No new node-category, ADR, or effect-category identifiers were required.) 2026-05-24 (Q-033 state machines step 2 async multi-machine runtime proposed; draft proposal at `proposals/state-machines-runtime-step-2.md`. Extends Q-008 (high-throughput) with the concrete actor + channel design that step 2 will implement against; integrates with Q-009 (event ordering) via select-based nondeterministic merge as the default policy.) 2026-05-24 (Q-032 state machines runtime step 1 implemented in Layer 6 step 1 of the reference implementation; the previously-reserved N-027 StateMachine, N-028 EventStream, and N-029 Transition node categories are now load-bearing in the Kotlin/JVM prototype; proposal moved to `proposals/implemented/state-machines-runtime.md`. No new identifiers were required — the N-027..N-029 slots had been in the registry since Wave 3 design. 2026-05-23 (Wave 3 complete; rendering-and-views design spec and ADR-009 added; N-034 TypeAbstraction, N-035 ForallType, N-036 CapabilityScope, N-037 ProductValue, N-038 ProductFieldValue, N-039 ProductFieldGet, N-040 SumValue, N-041 RecursiveType, and N-042 RecursiveSelf registered. Q-031 refinement-lattice capability matching implemented in Layer 3 step 2 of the reference implementation; proposal moved to `proposals/implemented/`. No new node-category identifiers were required — the change adds a new field to N-016 Application, not a new node. Q-030 no-continuation effect handlers implemented in Layer 3 step 3; new node category N-043 Handler registered; proposal moved to `proposals/implemented/`.)

## Purpose

This document provides three views of the Strand design corpus for navigation and search: a document tree, an alphabetical concept index, and an identifier registry. It is updated whenever documents are added, removed, or significantly restructured.

## Document tree {#document-tree}

```
strand-design/
├── README.md                          Entry point and reading order
├── INDEX.md                           This document
├── 00-motivation.md                   Why Strand exists
├── 01-prior-art.md                    Survey of related work
├── 02-core-thesis.md                  Central design claims
├── open-questions.md                  Unresolved design questions
├── research-plan.md                   Empirical evaluation strategy
├── decisions/
│   ├── ADR-001-graph-not-text.md      Graph-native representation
│   ├── ADR-002-no-human-projection.md No human projection layer
│   ├── ADR-003-content-addressing.md  Content-addressed identity
│   ├── ADR-004-effects-as-edges.md    Mandatory effect edges
│   ├── ADR-005-foreign-nodes.md       Foreign function model
│   ├── ADR-006-per-node-encryption.md Per-node encryption
│   ├── ADR-007-state-machines.md      State machines as fixpoints
│   ├── ADR-008-compilation-target.md  Compilation targets
│   └── ADR-009-structured-outputs.md  Structured outputs and verifier invariants
├── design/
│   ├── node-algebra.md                Node types and well-formedness
│   ├── effects-and-capabilities.md    Effect system specification
│   ├── security-model.md              Threat model and defenses
│   ├── distribution-model.md          Distribution and placement
│   ├── state-machines.md              Long-running computation
│   ├── encryption-model.md            Encryption and key management
│   └── rendering-and-views.md         Structured outputs, schemas, live views
└── proposals/                         Draft proposals — researched but not yet implemented
    ├── README.md                      Index and reading order
    ├── state-machines-runtime.md
    └── implemented/                   Proposals that have been executed, retained for reference
        ├── effect-handlers.md
        └── refinement-lattice-capability-matching.md
```

**Status legend:**
- No marker — document exists and is complete to current standard
- (No wave placeholders remain; corpus is at Wave 3 completion)

## Concept index {#concept-index}

Alphabetical index of major concepts in the Strand design, with primary references to the document where each is specified.

| Concept | Primary document | Section |
|---------|------------------|---------|
| AI-generation hypothesis | `00-motivation.md` | The hypothesis |
| Ambient authority (absence of) | `02-core-thesis.md` | Capability execution |
| Analysis tooling | `decisions/ADR-002-no-human-projection.md` | Decision |
| Attestation chain | `design/security-model.md` | TEE attestation |
| Authoring layer (LLM emission) | `open-questions.md` | Q-034 |
| Backpressure | `design/state-machines.md` | Backpressure |
| Blessed output libraries | `design/rendering-and-views.md` | Blessed library set |
| Bootstrap corpus | `research-plan.md` | Phase 1 |
| Bytecode VM | `decisions/ADR-008-compilation-target.md` | Decision |
| Capability context | `02-core-thesis.md` | Capability execution |
| Capability delegation | `design/effects-and-capabilities.md` | Delegation |
| Capability-mediated execution | `02-core-thesis.md` | Claim 5 |
| CapabilityScope | `design/effects-and-capabilities.md` | Capabilities |
| Compilation target | `decisions/ADR-008-compilation-target.md` | Decision |
| Confidential execution | `02-core-thesis.md` | Capability execution |
| Confused deputy attack | `design/effects-and-capabilities.md` | Confused deputy |
| Content addressing | `decisions/ADR-003-content-addressing.md` | Decision |
| Corpus problem (training) | `00-motivation.md` | Why not modify |
| Cross-library invariant composition | `design/rendering-and-views.md` | Cross-library composition |
| Differential rendering | `design/rendering-and-views.md` | Live views |
| Distributed execution | `02-core-thesis.md` | Integration |
| Distribution model (placement) | `design/distribution-model.md` | Placement |
| Effect categories | `design/effects-and-capabilities.md` | Effect categories |
| Effect closure | `design/effects-and-capabilities.md` | Effect closure |
| Effect declarations (mandatory) | `decisions/ADR-004-effects-as-edges.md` | Decision |
| Effect handlers | `design/effects-and-capabilities.md` | Effect handlers |
| Effect handlers (implementation) | `proposals/implemented/effect-handlers.md` | (whole document) |
| Effect inference | `design/effects-and-capabilities.md` | Effect inference |
| Effect refinement lattice | `design/effects-and-capabilities.md` | Effect closure |
| Effect refinement lattice (implementation) | `proposals/implemented/refinement-lattice-capability-matching.md` | (whole document) |
| Empirical evaluation | `research-plan.md` | Phase 3 |
| Encryption envelope | `design/encryption-model.md` | Envelope structure |
| Event ordering | `design/state-machines.md` | Event ordering |
| Event streams | `decisions/ADR-007-state-machines.md` | Decision |
| Evaluation metrics | `research-plan.md` | Phase 3 |
| Fault tolerance (scheduler) | `design/distribution-model.md` | Scheduler policy |
| Fixpoint | `design/node-algebra.md` | Iterative computation |
| Foreign binding trust | `design/security-model.md` | Foreign binding trust |
| Foreign function interface | `decisions/ADR-005-foreign-nodes.md` | Decision |
| Foreign nodes | `decisions/ADR-005-foreign-nodes.md` | Decision |
| Graph-native representation | `decisions/ADR-001-graph-not-text.md` | Decision |
| HTML5 schema | `design/rendering-and-views.md` | Blessed library set |
| Hash-based identity | `decisions/ADR-003-content-addressing.md` | Decision |
| Hierarchical state machines | `design/state-machines.md` | Hierarchy |
| Homomorphic encryption | `decisions/ADR-006-per-node-encryption.md` | Decision |
| Hot upgrade | `design/state-machines.md` | Hot upgrade |
| Human projection (absence of) | `decisions/ADR-002-no-human-projection.md` | Decision |
| Identifier registry | This document | Identifier registry |
| Interface declarations (encrypted nodes) | `design/encryption-model.md` | Envelope structure |
| Invariant (node type N-033) | `design/rendering-and-views.md` | Schema mechanism |
| Invariant checker trust | `design/rendering-and-views.md` | Trust model for invariant checkers |
| Iterative computation | `design/node-algebra.md` | Iterative computation |
| Key management | `design/encryption-model.md` | Key lifecycle |
| Key rotation | `design/encryption-model.md` | Key rotation |
| Key revocation | `design/encryption-model.md` | Key revocation |
| Lambda calculus | `design/node-algebra.md` | Functions and binding |
| Layering (rendering pipeline) | `design/rendering-and-views.md` | Layering |
| Live views | `design/rendering-and-views.md` | Live views |
| LLM-text mismatch | `00-motivation.md` | Text-LLM mismatch |
| Locality (placement) | `design/distribution-model.md` | Locality |
| Merkle DAG | `decisions/ADR-003-content-addressing.md` | Decision |
| MLIR dialect | `decisions/ADR-008-compilation-target.md` | Decision |
| Multi-format rendering | `design/rendering-and-views.md` | Multi-format rendering |
| Multi-hash format | `decisions/ADR-003-content-addressing.md` | Decision |
| Multi-recipient encryption | `design/encryption-model.md` | Multi-recipient |
| Node algebra | `design/node-algebra.md` | Node inventory |
| Node fetching | `design/distribution-model.md` | Node fetching |
| Obfuscation | `design/security-model.md` | Obfuscation |
| Output emission (as effect) | `design/rendering-and-views.md` | Output as effect-edge terminus |
| OutputBatch convention (positional, `output_i`) | `proposals/implemented/state-machines-runtime.md` | § 5 Runtime architecture |
| Per-node encryption | `design/encryption-model.md` | (entire document) |
| Placement constraints | `design/distribution-model.md` | Placement |
| Prior art | `01-prior-art.md` | (entire document) |
| Provenance manifest | `design/rendering-and-views.md` | Provenance from output to source |
| Refinement types (future direction) | `decisions/ADR-009-structured-outputs.md` | Alternatives considered |
| Rendering pipeline | `design/rendering-and-views.md` | Layering |
| Replay determinism | `design/state-machines.md` | Conceptual model |
| Research plan | `research-plan.md` | (entire document) |
| Reproducible bindings | `design/security-model.md` | Foreign binding trust |
| Scheduler policy | `design/distribution-model.md` | Scheduler policy |
| Sandbox observation | `design/security-model.md` | Foreign binding trust |
| Schema (node type N-032) | `design/rendering-and-views.md` | Schema mechanism |
| Schema mechanism | `decisions/ADR-009-structured-outputs.md` | Decision |
| Serialization (rendering layer) | `design/rendering-and-views.md` | Layering |
| State machine architecture | `design/state-machines.md` | High-throughput |
| State machine semantics | `design/state-machines.md` | Conceptual model |
| Supervisor (state machine) | `design/state-machines.md` | Termination and supervision |
| Supply chain (foreign bindings) | `design/security-model.md` | Foreign binding trust |
| Structured outputs | `decisions/ADR-009-structured-outputs.md` | Decision |
| Synchronous trace runtime (state machines) | `proposals/implemented/state-machines-runtime.md` | § 3.2 Trace API and the synchronous runtime |
| TEE integration | `design/security-model.md` | TEE attestation |
| Threat model | `design/security-model.md` | Threat model |
| Token efficiency | `00-motivation.md` | Text-LLM mismatch |
| Training methodology | `research-plan.md` | Phase 1 |
| Verification | `design/effects-and-capabilities.md` | Verification algorithm |
| Verifier extension protocol | `design/rendering-and-views.md` | Schema mechanism |
| Versioning of the language | `design/node-algebra.md` | Versioning |
| Worker discovery | `design/distribution-model.md` | Worker discovery |

When more documents are added, this index is updated to reflect the primary specification location for each concept. Concepts may appear in multiple documents; the primary reference is the one where the concept is defined or most thoroughly specified.

## Identifier registry {#identifier-registry}

Stable identifiers used across the corpus. Identifiers do not change once assigned.

### Architectural Decision Records (ADR-NNN)

| ID | Topic | Document |
|----|-------|----------|
| ADR-001 | Graph-native representation, no text source | `decisions/ADR-001-graph-not-text.md` |
| ADR-002 | No human-readable projection layer | `decisions/ADR-002-no-human-projection.md` |
| ADR-003 | Content-addressed node identity | `decisions/ADR-003-content-addressing.md` |
| ADR-004 | Effects as mandatory typed edges | `decisions/ADR-004-effects-as-edges.md` |
| ADR-005 | Foreign function interface via ForeignNode | `decisions/ADR-005-foreign-nodes.md` |
| ADR-006 | Per-node encryption with multi-key support | `decisions/ADR-006-per-node-encryption.md` |
| ADR-007 | State machines as fixpoints over event streams | `decisions/ADR-007-state-machines.md` |
| ADR-008 | Compilation target: bytecode VM and MLIR/LLVM | `decisions/ADR-008-compilation-target.md` |
| ADR-009 | Structured outputs and verifier-checkable invariants | `decisions/ADR-009-structured-outputs.md` |

### Node types (N-NNN)

Inventory specified in [`design/node-algebra.md`](design/node-algebra.md). Current assignments:

| ID | Category | Group |
|----|----------|-------|
| N-001 | IntLit | Literals |
| N-002 | FloatLit | Literals |
| N-003 | StringLit | Literals |
| N-004 | BoolLit | Literals |
| N-005 | UnitLit | Literals |
| N-006 | BytesLit | Literals |
| N-007 | PrimitiveType | Types |
| N-008 | ProductType | Types |
| N-009 | ProductTypeField | Types |
| N-010 | SumType | Types |
| N-011 | SumTypeCase | Types |
| N-012 | FunctionType | Types |
| N-013 | TypeParameter | Types |
| N-014 | Lambda | Functions and binding |
| N-015 | ParameterDecl | Functions and binding |
| N-016 | Application | Functions and binding |
| N-017 | Let | Functions and binding |
| N-018 | VarRef | Functions and binding |
| N-019 | NodeRef | References |
| N-020 | ForeignNode | References |
| N-021 | EffectCategory | Effects and capabilities |
| N-022 | EffectDecl | Effects and capabilities |
| N-023 | Match | Control flow |
| N-024 | MatchCase | Control flow |
| N-025 | Pattern | Control flow |
| N-026 | Fixpoint | Control flow |
| N-027 | StateMachine | State machines |
| N-028 | EventStream | State machines |
| N-029 | Transition | State machines |
| N-030 | Name | Metadata |
| N-031 | Provenance | Metadata |
| N-032 | Schema | Structured outputs |
| N-033 | Invariant | Structured outputs |
| N-034 | TypeAbstraction | Functions and binding |
| N-035 | ForallType | Types |
| N-036 | CapabilityScope | Effects and capabilities |
| N-037 | ProductValue | Composite values |
| N-038 | ProductFieldValue | Composite values |
| N-039 | ProductFieldGet | Composite values |
| N-040 | SumValue | Composite values |
| N-041 | RecursiveType | Types |
| N-042 | RecursiveSelf | Types |
| N-043 | Handler | Effects and capabilities |

New node categories receive higher numbers; existing numbers are not reused.

### Effect categories (E-NNN)

Inventory specified in [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md). Current assignments:

| ID | Category | Group |
|----|----------|-------|
| E-001 | Network.Connect | Network |
| E-002 | Network.Listen | Network |
| E-003 | Network.Send | Network |
| E-004 | Network.Receive | Network |
| E-005 | Network.DNS | Network |
| E-006 | Filesystem.Read | Filesystem |
| E-007 | Filesystem.Write | Filesystem |
| E-008 | Filesystem.Execute | Filesystem |
| E-009 | Filesystem.Watch | Filesystem |
| E-010 | Time.Now | Time |
| E-011 | Time.Sleep | Time |
| E-012 | Time.Schedule | Time |
| E-013 | Process.Spawn | Process |
| E-014 | Process.Signal | Process |
| E-015 | Process.Wait | Process |
| E-016 | Memory.Allocate | Memory |
| E-017 | Memory.MutableState | Memory |
| E-018 | Hardware.GPU | Hardware |
| E-019 | Hardware.NPU | Hardware |
| E-020 | Hardware.Sensor | Hardware |
| E-021 | Crypto.Sign | Crypto |
| E-022 | Crypto.Encrypt | Crypto |
| E-023 | Crypto.Decrypt | Crypto |
| E-024 | Crypto.RandomBytes | Crypto |
| E-025 | Trust.Attestation | Trust |
| E-026 | Trust.SealedStorage | Trust |
| E-027 | Trust.MeasuredLaunch | Trust |
| E-028 | StateMachine.Send | State machines |
| E-029 | StateMachine.Receive | State machines |
| E-030 | StateMachine.Spawn | State machines |
| E-031 | StateMachine.Terminate | State machines |

New effect categories receive higher numbers; existing numbers are not reused.

### Open questions (Q-NNN)

Open questions are catalogued in [`open-questions.md`](open-questions.md). Identifiers Q-001 through Q-035 are currently assigned.

## Cross-reference graph {#cross-references}

The following table summarizes which documents cite which others, providing a navigation graph for the corpus.

| Document | Cites | Cited by |
|----------|-------|----------|
| `README.md` | All overview, both meta | (root) |
| `INDEX.md` | All documents (for indexing) | `README.md` |
| `00-motivation.md` | `02`, `01`, ADR-002, `research-plan.md` | `README.md`, `02` |
| `01-prior-art.md` | `00`, `design/state-machines.md`, `research-plan.md` | `README.md`, `00`, `02` |
| `02-core-thesis.md` | `00`, `01`, ADR-001 through ADR-004, several design docs, `research-plan.md` | `README.md`, `00`, `01`, ADR-001 |
| `decisions/ADR-001-graph-not-text.md` | `00`, `01`, `02`, ADR-002 through ADR-004, `design/node-algebra.md`, `open-questions.md` | `02`, ADR-002 through ADR-008 |
| `decisions/ADR-002-no-human-projection.md` | `00`, `01`, `02`, ADR-001, ADR-006, `open-questions.md` | `00`, `02`, ADR-001, ADR-006 |
| `decisions/ADR-003-content-addressing.md` | `01`, `02`, ADR-001, ADR-004, ADR-006, `design/node-algebra.md`, `open-questions.md` | `02`, ADR-001, ADR-005, ADR-006, ADR-007, ADR-008 |
| `decisions/ADR-004-effects-as-edges.md` | `01`, `02`, ADR-001, ADR-005, `design/effects-and-capabilities.md`, `design/distribution-model.md`, `open-questions.md` | `02`, ADR-001, ADR-003, ADR-005, ADR-006, ADR-007, ADR-008 |
| `decisions/ADR-005-foreign-nodes.md` | `01`, ADR-001, ADR-003, ADR-004, ADR-007, `design/security-model.md`, `open-questions.md` | ADR-004, ADR-006, ADR-007, ADR-008 |
| `decisions/ADR-006-per-node-encryption.md` | `02`, ADR-002, ADR-003, ADR-004, ADR-005, `design/encryption-model.md`, `design/security-model.md`, `open-questions.md` | `02`, ADR-002, ADR-003, ADR-008 |
| `decisions/ADR-007-state-machines.md` | `01`, `02`, ADR-001, ADR-003, ADR-004, ADR-005, `design/state-machines.md`, `design/distribution-model.md`, `open-questions.md` | `02`, ADR-005, ADR-008 |
| `decisions/ADR-008-compilation-target.md` | `02`, ADR-001, ADR-003, ADR-004, ADR-005, ADR-006, ADR-007, `design/node-algebra.md`, `open-questions.md` | `02`, `research-plan.md` |
| `decisions/ADR-009-structured-outputs.md` | `02`, ADR-001, ADR-002, ADR-003, ADR-004, ADR-005, ADR-007, `design/node-algebra.md`, `design/rendering-and-views.md`, `design/security-model.md`, `open-questions.md` | `design/rendering-and-views.md` |
| `design/node-algebra.md` | ADR-001, ADR-003, ADR-004, ADR-005, ADR-007, `effects-and-capabilities.md`, `state-machines.md`, `open-questions.md` | ADR-001, ADR-003, ADR-008, `effects-and-capabilities.md`, `state-machines.md`, `encryption-model.md`, `research-plan.md` |
| `design/effects-and-capabilities.md` | `02`, ADR-004, ADR-005, `node-algebra.md`, `encryption-model.md`, `security-model.md`, `state-machines.md`, `open-questions.md` | ADR-004, `node-algebra.md`, `security-model.md`, `distribution-model.md`, `state-machines.md`, `encryption-model.md`, `research-plan.md` |
| `design/state-machines.md` | `02`, `01`, ADR-003, ADR-004, ADR-007, ADR-008, `node-algebra.md`, `effects-and-capabilities.md`, `distribution-model.md`, `open-questions.md` | ADR-007, `01`, `node-algebra.md`, `distribution-model.md`, `research-plan.md` |
| `design/encryption-model.md` | ADR-003, ADR-006, `effects-and-capabilities.md`, `security-model.md`, `open-questions.md` | ADR-006, `security-model.md`, `research-plan.md` |
| `design/security-model.md` | `02`, ADR-003, ADR-004, ADR-005, ADR-006, `effects-and-capabilities.md`, `encryption-model.md`, `distribution-model.md`, `open-questions.md` | ADR-005, `effects-and-capabilities.md`, `encryption-model.md`, `distribution-model.md`, `research-plan.md` |
| `design/distribution-model.md` | `02`, ADR-001, ADR-003, ADR-004, `effects-and-capabilities.md`, `state-machines.md`, `security-model.md`, `open-questions.md` | ADR-004, ADR-007, `effects-and-capabilities.md`, `state-machines.md`, `security-model.md`, `research-plan.md`, `rendering-and-views.md` |
| `design/rendering-and-views.md` | `02`, ADR-002 through ADR-007, ADR-009, `node-algebra.md`, `effects-and-capabilities.md`, `state-machines.md`, `security-model.md`, `distribution-model.md`, `encryption-model.md`, `research-plan.md`, `open-questions.md` | ADR-009 |
| `research-plan.md` | `00`, `01`, `02`, ADR-002, ADR-005, ADR-008, all design docs, `open-questions.md` | `README.md`, `00`, `01`, `02`, ADR-008, all design docs |
| `open-questions.md` | All design and decision documents | `README.md`, all ADRs, all design docs |

This table is updated as new documents are added.

## Search guidance {#search-guidance}

For text search across the corpus:

- Concept names appear consistently in their canonical form (defined in this index). Search the index first to find the canonical name.
- Identifiers (`ADR-001`, `Q-005`, `N-014`, `E-007`) are unique across the corpus and find exact matches.
- Section anchors (`{#anchor-name}`) are stable and can be used in URLs and cross-references.
- The `References` section at the end of each document lists outgoing and incoming citations.

For AI agents reading this corpus:

- Begin with `README.md` to establish conventions.
- Use this index to locate the primary specification for any concept.
- Follow `References` sections to navigate between related documents.
- Open questions (`open-questions.md`) and decision records (`decisions/`) provide rationale not present in design specifications.

## Maintenance

This document is updated whenever:

- A new document is added to the corpus
- A document's status changes from placeholder to complete
- A new identifier is assigned
- A new concept warrants entry in the concept index

The update is the responsibility of the contributor making the change; reviewers verify the index has been updated as part of the review process.

## References

**Outgoing references:**
- All documents in the corpus (indexed)

**Incoming references:**
- [`README.md`](README.md)
