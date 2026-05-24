# Proposals

Design proposals for Strand language features that have been researched but not yet implemented. Each proposal is at the "ready to implement" level of detail: problem statement, recommended approach, implementation sketch, deferred concerns, scope estimate. The next session can advance any of them by reading the corresponding document, confirming the recommendation with the project lead, and executing.

Proposals here are **drafts**, not accepted decisions. They have not been promoted to ADR status. A future session that implements a proposal should:

1. Read the proposal and the related design documents (cited in each proposal's header).
2. Confirm or revise the recommendation with the project lead.
3. Implement against the proposal.
4. Either delete the proposal file (once implemented) or update it to note "Implemented in <commit/milestone>" and move to a `proposals/implemented/` subdirectory.
5. Update `open-questions.md` to mark the corresponding Q-NNN as Resolved.

## Current proposals

| File | Topic | Question | Scope |
|------|-------|----------|-------|
| [`state-machines-runtime-step-2.md`](state-machines-runtime-step-2.md) | Layer 6 step 2: async multi-machine actor runtime via Kotlin coroutines + Channel<Value> + select; tagged-Event sums; inter-machine wiring; supervisor pattern; recursive-list output | [Q-033](../open-questions.md#Q-033) | Large |

The four Wave-3 implementation proposals (Q-030 effect handlers, Q-031 refinement-lattice capability matching, Q-032 state machines runtime step 1, Q-035 schema + invariant step 1) have all landed and live in `implemented/`.

## Implemented proposals

Moved to `proposals/implemented/` once executed. Each retains its full text plus an "Implementation note" header recording any deviations and the link into the implementation.

| File | Topic | Question | Implemented |
|------|-------|----------|-------------|
| [`implemented/refinement-lattice-capability-matching.md`](implemented/refinement-lattice-capability-matching.md) | Layer 3 step 2: parameterized effect matching with wildcard capabilities | Q-031 | 2026-05-23 |
| [`implemented/effect-handlers.md`](implemented/effect-handlers.md) | Layer 3 step 3: no-continuation effect handlers (N-043 Handler) | Q-030 | 2026-05-23 |
| [`implemented/state-machines-runtime.md`](implemented/state-machines-runtime.md) | Layer 6 step 1: synchronous trace runtime for state machines (steps 2 and 3 still deferred) | Q-032 (step 1; Q-008 unchanged) | 2026-05-24 |
| [`implemented/schema-and-invariant.md`](implemented/schema-and-invariant.md) | Layer 7 step 1: N-032 Schema + N-033 Invariant nodes; pure-expression invariants on statically-known values; new `:schema` Gradle module; synthetic PositiveInt / NonEmptyList corpus | Q-035 | 2026-05-24 |

## Reading order recommendation

The four implemented proposals (refinement-lattice, effect handlers, state machines step 1, schema + invariant) are retained in `implemented/` for reference. The active step 2 proposal builds on the step 1 implementation note — read `implemented/state-machines-runtime.md` before `state-machines-runtime-step-2.md` for context. State machines step 3 (backpressure overflow policies, supervisor restart policies, snapshot/replay-from-log) remains on the roadmap and is not yet drafted as a separate proposal — it will be once step 2 lands.

## Sequencing notes

- Q-030 / Q-031 / Q-032-step-1 / Q-035-step-1 are independent of each other and of the recursive-types work.
- The state machines step 1 uses a fixed-arity `OutputBatch` workaround in place of a list-of-events return type. Step 2 (Q-033) switches to a recursive-list-based `(State, List<TaggedOutput>)` representation now that recursive types are landed; the OutputBatch path is preserved for single-stream machines so corpus programs 41–45 keep working unchanged.
- Identifier coordination: Q-030's effect-handlers work allocated `N-043`. Q-031's refinement-matching work allocated no new node category. Q-032's state-machines step 1 occupied the long-reserved `N-027`/`N-028`/`N-029` slots (already in the registry before the proposal). No new identifiers from step 1. Q-033 (state machines step 2) allocates no new node category — the runtime is the work, the algebra is unchanged. Q-035 (schema + invariant) occupied the long-reserved `N-032`/`N-033` slots.
- Corpus-program numbering: Q-031 used 33–35; Q-030 used 36–40; Q-032 step 1 used 41–45; Q-035 step 1 used 50–53. Q-033 will use 46–49 if implemented.
