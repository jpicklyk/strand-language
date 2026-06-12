# Determinism Enforcement for Replay-Relevant Evaluation

**Document:** `proposals/implemented/determinism-enforcement.md`
**Status:** Implemented (landed 2026-06-12 in the Kotlin/JVM reference implementation; see the Implementation note)
**Date:** 2026-06-12
**Concerns:** [Q-065](../../open-questions.md#Q-065), [`design/state-machines.md`](../../design/state-machines.md) (replay and snapshot guarantees), [`design/node-algebra.md`](../../design/node-algebra.md) (structural purity), [Q-047](../../open-questions.md#Q-047) (invariant evaluation), [Q-050](../../open-questions.md#Q-050) (the warning channel reused), the `Builtins` registry
**Scope:** small-medium

Replay, snapshot recovery, and audit all assume that re-evaluating a pure expression yields the same value. Purity is structural — the absence of effect edges — while determinism is behavioral, and nothing in the implementation marks or checks the difference. This proposal closes the gap with registry metadata, a verifier guard on replay-relevant closures, and a registry-wide behavioral audit, with no graph surface and no hash impact.

## Implementation note (2026-06-12)

Implemented as proposed, in the proposed order (field and registration sweep; consistency test; audit harness; verifier warning), across three commits. Full suite after the slice: 2120 tests, 0 failures; zero golden-hash changes.

**Registry metadata.** `Builtins.Determinism` (`Deterministic` / `Stateful` / `Nondeterministic`) lands on every entry of both registries via an `Entry<F>` wrapper resolved by `Builtins.resolveRegistration`: an effect-free registration without an explicit declaration is a construction-time `IllegalStateException` naming the target; effect-declaring registrations default `Stateful`. All 218 registrations were swept through the `det` / `fx` / `nondet` (and `detH` / `fxH`) helpers: 165 Deterministic (153 standard + 12 higher-order), 50 Stateful, 3 Nondeterministic (`Random.*`). The consistency sweep (`DeterminismRegistryConsistencyTest`, `:corpus`) additionally cross-checks each entry's registry-side effectful flag against the authoritative graph-side effect surfaces — the prelude `reservedNodes` and the `BuiltinSignatures` table — so the metadata cannot drift from what agents actually declare. New public surface: `Builtins.determinismOf(target)`, `Builtins.entryMetadata()`, and the `installTestBuiltin` / `clearTestBuiltins` overlay seam.

**Behavioral audit.** `BuiltinDeterminismAuditTest` (`:corpus`) double-evaluates all 165 Deterministic entries and compares results bit-exactly (raw `doubleToRawLongBits` for Float results). Coverage is total by construction: 160 entries generate inputs from their signatures (the `BuiltinSignatures` `Sig` shapes for table-covered entries — including real Strand closures applied through `Interpreter.applyCallable` for the twelve higher-order entries, with a genuine `Int.Lt` comparator for `List.Sort`; the reserved prelude FunctionType primitive parameter lists for prelude-covered entries), and 5 carry fixtures — the resource-closing family (`Net.Close`, `LLM.Stream.Close`, `Http.ServerClose`, `Pinecone.Index.Close`, `Chroma.Collection.Close`), whose `Int` opaque-handle surface type hides the `Value.Resource` domain; close-of-unknown-handle is documented idempotent for all five. A Deterministic entry with neither derivable signature nor fixture fails the audit rather than skipping. No nondeterministic effect-free builtin surfaced, as § 2 predicted. Float edge semantics are pinned by `BuiltinsFloatEdgePinningTest` (`:interpreter`): `Float.Div(0,0)` is NaN, division by zero is signed infinity, NaN propagates through arithmetic, every NaN comparison including NaN = NaN is false, and re-evaluation is bit-identical on raw bits.

**Verifier warning.** `VerifyWarning.NondeterministicInReplayContext(machineOrInvariant, builtin)` joins the Q-050 channel, computed by a store-wide pass over each StateMachine transition-function closure and each Invariant body. The verifier resolves registry metadata through a `ServiceLoader` seam (`BuiltinDeterminismOracle` + `ReplayDeterminism` in `:verifier`; the `Builtins`-backed provider in `:interpreter` under `META-INF/services`) because the module dependency direction (`interpreter → verifier`) forbids a direct reference; on a verifier-only classpath the oracle resolves to null and the warning never fires, which is the correct conservative reading. Tested both at the verifier level (fake oracle via the `ReplayDeterminism.override` seam) and end to end (`DeterminismWarningEndToEndTest`, `:corpus`, through the real ServiceLoader chain with an overlay-injected effect-free builtin force-marked Nondeterministic); `CorpusWarningSweepTest` confirms no corpus program or density fixture triggers it.

**Deviations from the proposal text.** First, the warning's scope is the effect-free ForeignNodes only: § 4's "references a registry entry not marked `Deterministic`" read literally would flag effect-declaring ForeignNodes too, but a machine may legitimately declare an effect category and call its builtin inside the transition function (corpus 67 calls `Anthropic.Messages.Create` with `llmGenerateFx` declared on the machine), and such calls are already mediated by the effect and capability machinery — § 2's own rationale. The narrowed scope preserves the two live targets (a structurally-pure-but-nondeterministic builtin past a loosened registration lock, and a ForeignNode under-declaring its effects against a builtin the registry knows is Stateful or Nondeterministic) without double-reporting the effect closure. Second, the audit and consistency tests live in `:corpus` rather than `:interpreter` as § 8 sketched, because input generation reads `BuiltinSignatures` and the prelude table from `:authoring`, which the interpreter module does not depend on; the registration-constraint and Float-pinning tests live in `:interpreter` as sketched. Third, the cross-module oracle (the ServiceLoader seam) is implementation structure § 8 did not anticipate; it exists solely to respect the module dependency direction and carries no design weight.

## 1. Problem statement

A state machine's trajectory is deterministic given its event history because the transition function is pure; the same assumption underlies invariant re-evaluation (Q-047) and snapshot replay. The assumption holds today by inspection, not by mechanism: a builtin registered without an effect category is treated as pure everywhere, but the registry records nothing about whether its host implementation is deterministic. The risk classes are concrete. A future convenience builtin backed by host state (locale-dependent formatting, environment-sensitive defaults) would be structurally pure and behaviorally nondeterministic, silently breaking replay. Floating-point operations carry edge semantics (NaN propagation, signed zero) that are deterministic per IEEE 754 on the JVM but have never been pinned by test as such. Collection-order-dependent operations (`Set.ToList`, map iteration) are deterministic only because the backing structures are insertion-ordered — a property of the current implementation, not a recorded contract.

## 2. Recommended approach

**Registry metadata, not graph surface.** Each builtin registry entry gains a `determinism` field with three values: `Deterministic` (same arguments, same result, always), `Stateful` (result depends on host state or world — every builtin that declares an effect category lands here mechanically), and `Nondeterministic` (random or scheduling-dependent — today only the `Random.*` family, which also declares E-024). The field is metadata in the Kotlin registry; it does not appear in any node, encoding, or hash. Registration enforces the audit shape: an entry with no effect category must explicitly declare `Deterministic` (no default), so adding a structurally pure builtin forces its author to take a position, and a registry-consistency test asserts that every effect-declaring builtin is `Stateful` or `Nondeterministic` and every effect-free builtin is `Deterministic`.

**Verifier guard on replay-relevant closures.** A new informational warning on the Q-050 channel — `VerifyWarning.NondeterministicInReplayContext` — fires when a transition-function closure or invariant body references a builtin not marked `Deterministic`. For effect-declaring builtins this is unreachable through the existing purity rules (transition functions and invariant bodies are already effect-checked); the warning's live target is the future structurally-pure-but-nondeterministic builtin, which today cannot be registered without tripping the registration constraint above. The guard is therefore a two-lock door: the registry constraint stops the mistake at registration, the verifier warning stops it at composition if the first lock is ever loosened. A warning rather than an error because no existing program can trigger it and the channel's contract (Q-050) is informational.

**Behavioral audit.** A registry-wide test double-evaluates every `Deterministic` builtin on representative inputs and asserts result equality. Inputs come from a signature-driven generator (canonical sample values per primitive type, short lists, small products) with an explicit fixture table for builtins whose argument domains the generator cannot derive (parsers wanting well-formed input, higher-order entries needing function values). The test fails if any `Deterministic` builtin lacks coverage, so the audit is total by construction rather than by diligence. Floating-point edge semantics are pinned in the same pass: NaN propagation through `Float.Div(0,0)` and arithmetic, NaN comparison falsity, and bit-identical re-evaluation.

## 3. Worked consequence

Registering a hypothetical `Locale.FormatNumber` with no effect category and no determinism declaration fails at registration. Registering it as `Deterministic` admits it but the double-evaluation audit fails on any host where the result is locale-sensitive. Registering it honestly as `Stateful` requires giving it an effect category, which removes it from transition closures via the existing purity rules — the design pressure lands exactly where it should: nondeterminism must be declared as effect, and only declared-deterministic operations reach replay-relevant positions.

## 4. Verifier rules

`VerifyWarning.NondeterministicInReplayContext(machineOrInvariant: NodeId, builtin: String)` — emitted on the `VerifyResult.Ok.warnings` channel when the effect-free closure of a StateMachine transition function or an Invariant body references a registry entry not marked `Deterministic`. No error variant; no admission change.

## 5. Runtime semantics

None new.

## 6. Test scenarios

1. **Registration constraint** — an effect-free builtin registered without a determinism declaration fails registry construction (asserted via a test-local registry).
2. **Consistency sweep** — every shipping effect-declaring builtin is `Stateful` or `Nondeterministic`; every effect-free builtin is `Deterministic`; `Random.*` are `Nondeterministic`.
3. **Audit totality** — the double-evaluation test covers every `Deterministic` entry; removing a fixture for a parser builtin fails the totality check, not silently skips.
4. **Double-evaluation equality** — all `Deterministic` builtins return equal values on repeated evaluation over generated and fixture inputs.
5. **Float edge pinning** — `Float.Div(0,0)` yields NaN, NaN comparisons are false, repeated evaluation is bit-identical.
6. **Warning fires** — with a test-injected effect-free builtin force-marked non-deterministic, a transition closure referencing it produces `NondeterministicInReplayContext`; an ordinary expression closure does not.
7. **Hash invariance** — no corpus or fixture hash changes (metadata only).

## 7. Tradeoffs and open questions

**Deferred intentionally:**

- **Graph-surface determinism marking** — a per-ForeignNode determinism declaration (paralleling effect declarations) would extend the contract to non-builtin foreign bindings; deferred to the Q-006 trust-model work where binding-author claims are adjudicated.
- **Iteration-order contracts in the spec** — `Set.ToList` and map iteration order are currently pinned by tests; promoting them to normative spec text can ride any later canonical-encoding or stdlib documentation pass.

**Real research questions:**

- *Host-dependence of `Deterministic`* — the audit proves same-process determinism, not cross-platform bit-equality (notably JVM `Math` intrinsics on exotic platforms). Replay today is same-implementation replay, so same-process is the honest contract; cross-implementation determinism falls due with the Rust VM (Q-017 step 2) and the conformance corpus.

## 8. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `impl-kotlin/interpreter/.../Builtins.kt` | `determinism` field on entry types; registration constraint; sweep over all ~130 registrations | Medium |
| `impl-kotlin/verifier` | `NondeterministicInReplayContext` warning on transition/invariant closures | Small |
| `impl-kotlin/interpreter` tests | registration, consistency, audit generator + fixture table, Float pinning | Medium |
| `impl-kotlin/verifier` tests | warning fires / does not fire | Small |

**Order of work.** Field and registration sweep; consistency test; audit harness; verifier warning last.

**Not in this slice.** ForeignNode-level determinism declarations, spec promotion of iteration-order contracts, cross-implementation determinism.

## References

**Outgoing references:**
- [`design/state-machines.md`](../../design/state-machines.md) — the replay guarantee this makes enforceable
- [`design/node-algebra.md`](../../design/node-algebra.md) — structural purity, the property this complements
- [`open-questions.md`](../../open-questions.md) — Q-047, Q-050, Q-065

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-065 points at this proposal
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — Known gaps section
- [`ROADMAP.md`](../../ROADMAP.md) — Tier 3.5 (entry removed on resolution)
