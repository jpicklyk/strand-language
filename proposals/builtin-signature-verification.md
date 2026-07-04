# Builtin signature and effect verification

**Document:** `proposals/builtin-signature-verification.md`
**Status:** Draft
**Question:** [Q-056](../open-questions.md#Q-056) (resolves)
**Related:** [ADR-010](../decisions/ADR-010-reasoning-surface.md), [ADR-005](../decisions/ADR-005-foreign-nodes.md), [Q-006](../open-questions.md#Q-006), [Q-065](../open-questions.md#Q-065) (the oracle seam this reuses), [Q-039](../open-questions.md#Q-039)
**Drafted:** 2026-07-04

## Problem

A ForeignNode's declared `foreignType` and declared `effects` are taken on faith. [ADR-005](../decisions/ADR-005-foreign-nodes.md) accepts this for genuinely foreign code, whose implementation the verifier cannot inspect ([Q-006](../open-questions.md#Q-006) governs that trust model). For the `strand-builtin:` namespace the truth is co-resident in the same process: the registry knows every builtin's real argument and result shapes and its real effect categories, yet the verifier never cross-checks the declaration against it.

This has two consequences. The first, the original [Q-056](../open-questions.md#Q-056), is a robustness gap: a mis-declared signature verifies cleanly and then fails at runtime — the worst outcome for a verify-first discipline (the untranslated-exception half was closed by the 2026-06-11 `BuiltinContractViolation` work; the declared-signature check remains open). The second is load-bearing for [ADR-010](../decisions/ADR-010-reasoning-surface.md): the reasoning surface reports an effect closure computed from each ForeignNode's declared `effects`, so the closure — and the harm bound, egress set, and self-gate built on it — is only as sound as those declarations. A builtin ForeignNode that under-declares its effects (declares `Filesystem.Read` for a target that also connects to the network, or declares no effects for an effectful target) makes the closure an unsound lower bound on what the program can do, precisely the false negative ADR-010 forbids. Where the truth is in the same process, this should be checked, not trusted.

## Recommended approach

At ForeignNode admission for a `strand-builtin:` target, cross-check both the declared `foreignType` and the declared `effects` against the registry's ground truth, raising a structured `VerifyError` on mismatch.

- Signature: the declared `foreignType` must equal (monomorphic builtins) or conform to (a shape schema for the polymorphic and agent-typed families) the registry's known argument and result shapes.
- Effects: the declared `effects` — resolved to EffectCategory names — must equal the registry's known effect-category set for the target. Under-declaration is the soundness-critical case (it shrinks the closure below the truth); over-declaration is also rejected, matching the N-046 ModuleManifest precedent that certifies an export's effect surface exactly rather than permissively.

The mechanism reuses the [Q-065](../open-questions.md#Q-065) ServiceLoader oracle seam that already crosses the `:verifier` / `:interpreter` boundary without a circular dependency. Q-065 introduced a `BuiltinDeterminismOracle` interface in `:verifier` implemented in `:interpreter` via `META-INF/services`; this proposal adds a `BuiltinSignatureOracle` (or extends the existing seam) exposing, per `strand-builtin:` target, its canonical FunctionType shape and its declared effect-category name set. The verifier queries the oracle at ForeignNode admission and compares; when no oracle is registered (a host embedding the verifier without the builtin registry) the check is skipped, exactly as the determinism oracle degrades. New `VerifyError` variants: `BuiltinSignatureMismatch(at, target, declared, actual)` and `BuiltinEffectMismatch(at, target, declared, actual, missing)`.

The registry's ground truth already exists: the `BuiltinSignatures` table ([Q-060](../open-questions.md#Q-060) M-4) carries per-builtin signatures with effects and Q-039 projections, and the determinism registry ([Q-065](../open-questions.md#Q-065)) already pins effect declarations for consistency. This proposal turns that table from an authoring aid into an admission-time contract.

## Where it lives

The `BuiltinSignatureOracle` interface in `:verifier` alongside `BuiltinDeterminismOracle`; its implementation in `:interpreter` (or `:authoring`, wherever the `BuiltinSignatures` truth is most directly available) registered via `META-INF/services`. The admission check in the verifier's ForeignNode inference. New `VerifyError` variants in `VerifyError.kt`.

## Deferred and open

- Non-builtin foreign targets (`wasm:`, `process:`) remain under [Q-006](../open-questions.md#Q-006)'s trust model — this proposal covers only the co-resident `strand-builtin:` namespace where the truth is inspectable.
- The polymorphic and agent-typed shape schema may start conservative (checking arity and the monomorphic positions) and tighten later; the monomorphic exact-match case is the majority and ships first.

## Scope

Small to medium. An oracle interface plus implementation (reusing the Q-065 pattern), an admission check, two `VerifyError` variants, and tests. Hash-neutral — this reads declarations and rejects mismatches; it changes no node encoding.

## References

**Outgoing references:**
- [`decisions/ADR-010-reasoning-surface.md`](../decisions/ADR-010-reasoning-surface.md) — the closure soundness this protects at the leaves
- [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — the trust boundary this tightens for co-resident builtins
- [`open-questions.md`](../open-questions.md) — Q-056 (resolved), Q-006, Q-065, Q-039, Q-060
