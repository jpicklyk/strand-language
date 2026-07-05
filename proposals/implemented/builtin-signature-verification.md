# Builtin signature and effect verification

**Document:** `proposals/implemented/builtin-signature-verification.md`
**Status:** Implemented 2026-07-04 (commit `1472304` on `reason-first`); the effect cross-check found zero mis-declarations, monomorphic signature exact-check ships, polymorphic/agent-typed signature check deferred
**Question:** [Q-056](../../open-questions.md#Q-056) (resolves)
**Related:** [ADR-010](../../decisions/ADR-010-reasoning-surface.md), [ADR-005](../../decisions/ADR-005-foreign-nodes.md), [Q-006](../../open-questions.md#Q-006), [Q-065](../../open-questions.md#Q-065) (the oracle seam this reuses), [Q-039](../../open-questions.md#Q-039)
**Drafted:** 2026-07-04

## Problem

A ForeignNode's declared `foreignType` and declared `effects` are taken on faith. [ADR-005](../../decisions/ADR-005-foreign-nodes.md) accepts this for genuinely foreign code, whose implementation the verifier cannot inspect ([Q-006](../../open-questions.md#Q-006) governs that trust model). For the `strand-builtin:` namespace the truth is co-resident in the same process: the registry knows every builtin's real argument and result shapes and its real effect categories, yet the verifier never cross-checks the declaration against it.

This has two consequences. The first, the original [Q-056](../../open-questions.md#Q-056), is a robustness gap: a mis-declared signature verifies cleanly and then fails at runtime — the worst outcome for a verify-first discipline (the untranslated-exception half was closed by the 2026-06-11 `BuiltinContractViolation` work; the declared-signature check remains open). The second is load-bearing for [ADR-010](../../decisions/ADR-010-reasoning-surface.md): the reasoning surface reports an effect closure computed from each ForeignNode's declared `effects`, so the closure — and the harm bound, egress set, and self-gate built on it — is only as sound as those declarations. A builtin ForeignNode that under-declares its effects (declares `Filesystem.Read` for a target that also connects to the network, or declares no effects for an effectful target) makes the closure an unsound lower bound on what the program can do, precisely the false negative ADR-010 forbids. Where the truth is in the same process, this should be checked, not trusted.

## Recommended approach

At ForeignNode admission for a `strand-builtin:` target, cross-check both the declared `foreignType` and the declared `effects` against the registry's ground truth, raising a structured `VerifyError` on mismatch.

- Signature: the declared `foreignType` must equal (monomorphic builtins) or conform to (a shape schema for the polymorphic and agent-typed families) the registry's known argument and result shapes.
- Effects: the declared `effects` — resolved to EffectCategory names — must equal the registry's known effect-category set for the target. Under-declaration is the soundness-critical case (it shrinks the closure below the truth); over-declaration is also rejected, matching the N-046 ModuleManifest precedent that certifies an export's effect surface exactly rather than permissively.

The mechanism reuses the [Q-065](../../open-questions.md#Q-065) ServiceLoader oracle seam that already crosses the `:verifier` / `:interpreter` boundary without a circular dependency. Q-065 introduced a `BuiltinDeterminismOracle` interface in `:verifier` implemented in `:interpreter` via `META-INF/services`; this proposal adds a `BuiltinSignatureOracle` (or extends the existing seam) exposing, per `strand-builtin:` target, its canonical FunctionType shape and its declared effect-category name set. The verifier queries the oracle at ForeignNode admission and compares; when no oracle is registered (a host embedding the verifier without the builtin registry) the check is skipped, exactly as the determinism oracle degrades. New `VerifyError` variants: `BuiltinSignatureMismatch(at, target, declared, actual)` and `BuiltinEffectMismatch(at, target, declared, actual, missing)`.

The registry's ground truth already exists: the `BuiltinSignatures` table ([Q-060](../../open-questions.md#Q-060) M-4) carries per-builtin signatures with effects and Q-039 projections, and the determinism registry ([Q-065](../../open-questions.md#Q-065)) already pins effect declarations for consistency. This proposal turns that table from an authoring aid into an admission-time contract.

## Where it lives

The `BuiltinSignatureOracle` interface in `:verifier` alongside `BuiltinDeterminismOracle`; its implementation in `:interpreter` (or `:authoring`, wherever the `BuiltinSignatures` truth is most directly available) registered via `META-INF/services`. The admission check in the verifier's ForeignNode inference. New `VerifyError` variants in `VerifyError.kt`.

## Deferred and open

- Non-builtin foreign targets (`wasm:`, `process:`) remain under [Q-006](../../open-questions.md#Q-006)'s trust model — this proposal covers only the co-resident `strand-builtin:` namespace where the truth is inspectable.
- The polymorphic and agent-typed shape schema may start conservative (checking arity and the monomorphic positions) and tighten later; the monomorphic exact-match case is the majority and ships first.

## Scope

Small to medium. An oracle interface plus implementation (reusing the Q-065 pattern), an admission check, two `VerifyError` variants, and tests. Hash-neutral — this reads declarations and rejects mismatches; it changes no node encoding.

## References

**Outgoing references:**
- [`decisions/ADR-010-reasoning-surface.md`](../../decisions/ADR-010-reasoning-surface.md) — the closure soundness this protects at the leaves
- [`decisions/ADR-005-foreign-nodes.md`](../../decisions/ADR-005-foreign-nodes.md) — the trust boundary this tightens for co-resident builtins
- [`open-questions.md`](../../open-questions.md) — Q-056 (resolved), Q-006, Q-065, Q-039, Q-060

## Implementation note

Landed in `impl-kotlin/` on the `reason-first` branch, mirroring the Q-065 ServiceLoader oracle seam.

Anchors:
- `verifier/BuiltinSignatureOracle.kt` — new `BuiltinSignatureOracle` interface (alongside `BuiltinDeterminismOracle`), the `BuiltinShape` structural-comparison ADT, and the `BuiltinSignatures` resolution object (`override` test seam + lazy ServiceLoader load + `oracleAvailable()`), a direct copy of the `ReplayDeterminism` shape.
- `authoring/BuiltinsSignatureOracle.kt` — the table-backed implementation, registered via `authoring/src/main/resources/META-INF/services/org.strand.verifier.BuiltinSignatureOracle`. Truth is assembled from the two authoring-side registries the elaborator already treats as authoritative (the implicit-prelude reserved ForeignNodes in `LayerAGrammar.reservedNodes` and the polymorphic `BuiltinSignatures.table`). A new `implementation(project(":verifier"))` main dependency was added to `authoring/build.gradle.kts` (`authoring -> verifier -> core` is acyclic — the verifier does not depend on authoring).
- `verifier/Verifier.kt` — `inferForeignNode` now calls `checkBuiltinSignatureAndEffects(...)`, which resolves the declared `effects` EffectCategory NodeIds to their category names and requires equality with the oracle's name set (under- and over-declaration both rejected, per the N-046 exact-surface precedent), and for monomorphic pure targets requires the declared `foreignType` (canonicalized via `builtinShapeOf`) to structurally equal the oracle's shape. Degrades to skip when the target is not `strand-builtin:`, no oracle is registered, or the target is unknown to the oracle.
- `verifier/VerifyError.kt` — new `BuiltinEffectMismatch(at, target, declared, actual, missing)` and `BuiltinSignatureMismatch(at, target, declared, actual)`.

Scope of the signature exact-check: exposed only for **pure** builtins (empty effect set) whose parameters and result are all primitive types — the majority monomorphic case (arithmetic, comparison, Math.*, Hash.*, String.Concat/Eq, Float.*, ...), each with exactly one honest signature. Effectful and agent-typed families (Fs.*, Net.*, LLM.*, Vector.*) are **signature-deferred**: they are legitimately wrapped in structured request/response product types at their call sites (an `Anthropic.Messages.Create` typed `(GenerateRequest) -> GenerateResult`, an `Fs.Write` used as a structured effect stand-in), so the opaque reserved `bytesT` surface is not a single canonical shape. For those the **effect** cross-check (the ADR-010-load-bearing, soundness-critical dimension) still runs; only the signature exact-check is deferred, with parameter arity left to the standard Application `ArityMismatch` rule.

Pre-existing mis-declaration caught: **none**. An initial, broader signature check (exact-checking every all-primitive monomorphic reserved shape, including effectful ones) flagged 9 pre-existing programs — corpus `67-llm-state-machine-with-tool.json` and hand-authored analysis fixtures in `LatentEffectClosureTest` / `ProgramAnalysisTest`. All 9 were **case (i) false positives**: agent-typed LLM builtins and `Fs.Write`-as-stand-in carrying legitimate structured signatures against an opaque reserved surface. The check was narrowed (pure-only signature exact-check, as above) to admit them rather than loosened to hide a genuine mismatch; the effect cross-check found zero mismatches anywhere in the corpus. The change is purely additive and **hash-neutral** — `CorpusGoldenHashTest` (117) passes with no regeneration; no golden hash moved.

Tests (14 total): `corpus/BuiltinSignatureVerificationTest` (9, end-to-end through the real `:authoring` oracle: well-formed pure + effectful admit; under- and over-declared effects rejected with `BuiltinEffectMismatch`; wrong-signature and wrong-arity pure builtin rejected with `BuiltinSignatureMismatch`; unknown `strand-builtin:` target, `wasm:` target, and an empty-override oracle all degrade to skip) and `verifier/BuiltinSignatureOracleTest` (5, the mechanism in isolation via the `override` seam plus the no-oracle degrade-to-skip default of the `:verifier` classpath).
