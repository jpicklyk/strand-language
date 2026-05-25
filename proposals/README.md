# Proposals

Design proposals for Strand language features that have been researched but not yet implemented. Each proposal is at the "ready to implement" level of detail: problem statement, recommended approach, implementation sketch, deferred concerns, scope estimate. The next session can advance any of them by reading the corresponding document, confirming the recommendation with the project lead, and executing.

Proposals here are **drafts**, not accepted decisions. They have not been promoted to ADR status. A future session that implements a proposal should:

1. Read the proposal and the related design documents (cited in each proposal's header).
2. Confirm or revise the recommendation with the project lead.
3. Implement against the proposal.
4. Either delete the proposal file (once implemented) or update it to note "Implemented in <commit/milestone>" and move to a `proposals/implemented/` subdirectory.
5. Update `open-questions.md` to mark the corresponding Q-NNN as Resolved.

## Current proposals

(All Wave-3+ implementation proposals have landed and live in `implemented/`. The next drafted proposal will appear here when registered.)

The four Wave-3 implementation proposals (Q-030 effect handlers, Q-031 refinement-lattice capability matching, Q-032 state machines runtime step 1, Q-035 schema + invariant step 1) have all landed and live in `implemented/`.

## Implemented proposals

Moved to `proposals/implemented/` once executed. Each retains its full text plus an "Implementation note" header recording any deviations and the link into the implementation.

| File | Topic | Question | Implemented |
|------|-------|----------|-------------|
| [`implemented/refinement-lattice-capability-matching.md`](implemented/refinement-lattice-capability-matching.md) | Layer 3 step 2: parameterized effect matching with wildcard capabilities | Q-031 | 2026-05-23 |
| [`implemented/effect-handlers.md`](implemented/effect-handlers.md) | Layer 3 step 3: no-continuation effect handlers (N-043 Handler) | Q-030 | 2026-05-23 |
| [`implemented/state-machines-runtime.md`](implemented/state-machines-runtime.md) | Layer 6 step 1: synchronous trace runtime for state machines (steps 2 and 3 still deferred) | Q-032 (step 1; Q-008 unchanged) | 2026-05-24 |
| [`implemented/schema-and-invariant.md`](implemented/schema-and-invariant.md) | Layer 7 step 1: N-032 Schema + N-033 Invariant nodes; pure-expression invariants on statically-known values; new `:schema` Gradle module; synthetic PositiveInt / NonEmptyList corpus | Q-035 | 2026-05-24 |
| [`implemented/state-machines-runtime-step-2.md`](implemented/state-machines-runtime-step-2.md) | Layer 6 step 2: async multi-machine actor runtime via Kotlin coroutines + Channel<Value> + select; tagged-Event sums; inter-machine wiring; multi-input supervisor pattern; recursive-list output; `strand group` CLI | Q-033 | 2026-05-24 |
| [`implemented/state-machines-runtime-step-3.md`](implemented/state-machines-runtime-step-3.md) | Layer 6 step 3: all six slices — bounded-queue overflow policies (Q-015), in-band supervision via E-030/E-031 + host-driven spawn/terminate, content-addressed snapshot/replay, runtime metrics + `--metrics` CLI, implicit Send/Receive verifier enforcement via WellKnownEffect registry, multi-producer fan-in + broadcast fan-out via ConsumerMode + StreamBus | Q-008 (part), Q-015 | 2026-05-24 |
| [`implemented/llm-authoring-layer.md`](implemented/llm-authoring-layer.md) | Q-034 step 1: four-layer LLM emission stack — full-coverage Layer A grammar + parser + dag-json emitter, all four Layer C inference cases (Lambda effects, effectInstances defaulting, Application typeArguments, Lambda paramType), Layer B GBNF constraint grammar via `strand grammar`, JSON→Layer A translator (`strand translate`); all 58 corpus programs ship in Layer A form with hash-equality round-trip (56 in the strict test set; 53 + 56 schema-fail programs excluded by the test's verifier-pass invariant) | Q-034 step 1 | 2026-05-24 |
| [`implemented/bytecode-vm-step-1.md`](implemented/bytecode-vm-step-1.md) | Q-017 step 1: Kotlin reference bytecode VM. `:bytecode` + `:vm` Gradle modules, 28-opcode stack-based instruction set, uniform-boxed value representation with VmClosure/VmFixpoint/VmForeign callables carrying effects, capability stack + active handler list, EQ/SUM_CASE_IS/SUM_PAYLOAD/THROW_NO_MATCH helpers, JUMP backpatching for Match dispatch. `Vm.applyClosure` + `Vm.evaluate` public APIs enable runtime/schema dispatch through the VM. **52 of 58 corpus programs pass `interpreter == VM` equivalence** across VmEquivalenceTest (37 Layers 1/3/4/5 programs), VmMachineEquivalenceTest (7 single-input state machines), VmSchemaEquivalenceTest (7 schemas). Multi-input/async actor-loop integration (corpus 47/48/49) is a follow-up; the value-level mechanism is in place. Step 2 (Rust port per ADR-008) is the next milestone. | Q-017 step 1 | 2026-05-24 |
| [`implemented/plaintext-and-markdown-libraries.md`](implemented/plaintext-and-markdown-libraries.md) | Q-026 blessed-library expansion: PlainTextDocument (String + NonEmptyText variant using `Bool.Not(String.Eq(s, ""))`) and MarkdownDocument (four-variant MarkdownBlock sum + recursive-list document + NonEmptyMarkdown invariant using `Match(Cons→true, Nil→false)`). Six new corpus programs (58-63) covered by CorpusSchemaTest + VmSchemaEquivalenceTest; four ship in Layer A form. HTML5 + SVG + PDF deferred (nested-μ blocker for HTML/SVG; binary format for PDF). | Q-026 (part) | 2026-05-24 |
| [`implemented/json-blessed-library.md`](implemented/json-blessed-library.md) | Layer 7 step 1.5: first blessed output library — `JsonValue` (flat primitives sum) + `UniqueKeyJsonObject` Schema with `Fixpoint`+`Match` `unique_keys` invariant; three new corpus programs; `Bool.And`/`Bool.Or`/`String.Eq` builtins | Q-026 (first slice) | 2026-05-24 |
| [`implemented/layer-a-density.md`](implemented/layer-a-density.md) | Layer A density v1 → v3: nine slices of Layer A authoring-grammar sugar (implicit prelude, inline literals, auto-VarRef, IF, compact LAM params, inline literal patterns, anonymous nodes + @last, inline ProductFieldValue list, WHEN) cutting the three-task evaluation geomean from 2.28× Python+type-hints to 1.05×. All-additive grammar extensions; verifier and canonical CBOR encoder unchanged; existing 56-program LayerARoundTripTest plus new LayerADensityTest both green. | Q-034 follow-up | 2026-05-25 |

## Reading order recommendation

The four implemented proposals (refinement-lattice, effect handlers, state machines step 1, schema + invariant) are retained in `implemented/` for reference. The active step 2 proposal builds on the step 1 implementation note — read `implemented/state-machines-runtime.md` before `state-machines-runtime-step-2.md` for context. State machines step 3 (backpressure overflow policies, supervisor restart policies, snapshot/replay-from-log) remains on the roadmap and is not yet drafted as a separate proposal — it will be once step 2 lands.

## Sequencing notes

- Q-030 / Q-031 / Q-032-step-1 / Q-035-step-1 are independent of each other and of the recursive-types work.
- The state machines step 1 uses a fixed-arity `OutputBatch` workaround in place of a list-of-events return type. Step 2 (Q-033) switches to a recursive-list-based `(State, List<TaggedOutput>)` representation now that recursive types are landed; the OutputBatch path is preserved for single-stream machines so corpus programs 41–45 keep working unchanged.
- Identifier coordination: Q-030's effect-handlers work allocated `N-043`. Q-031's refinement-matching work allocated no new node category. Q-032's state-machines step 1 occupied the long-reserved `N-027`/`N-028`/`N-029` slots (already in the registry before the proposal). No new identifiers from step 1. Q-033 (state machines step 2) allocates no new node category — the runtime is the work, the algebra is unchanged. Q-035 (schema + invariant) occupied the long-reserved `N-032`/`N-033` slots.
- Corpus-program numbering: Q-031 used 33–35; Q-030 used 36–40; Q-032 step 1 used 41–45; Q-035 step 1 used 50–53. Q-033 will use 46–49 if implemented.
