# Prelude as a Content-Addressed Module

**Document:** `proposals/prelude-as-module.md`
**Status:** Draft proposal
**Date:** 2026-06-11
**Concerns:** [Q-034](../open-questions.md#Q-034) (the implicit prelude), [Q-043](../open-questions.md#Q-043) (ModuleManifest and federation), [Q-052](../open-questions.md#Q-052) (second-implementation conformance), [Q-057](../open-questions.md#Q-057) (authoring parity), [`design/node-algebra.md`](../design/node-algebra.md) N-046, [`proposals/cross-store-federation.md`](cross-store-federation.md)
**Scope:** medium

This proposal materializes the implicit prelude — the reserved names the Layer A elaborator synthesizes on demand — as a real, content-addressed ModuleManifest admitted to the store. The reserved names stop being a private convention between one elaborator and one system prompt and become a verifiable artifact any implementation, agent, or peer store can fetch by hash.

## 1. Problem statement

The prelude is the de facto standard library and it exists nowhere in the language. Its reserved specifications — the primitive type names, the monomorphic builtin FunctionTypes and ForeignNodes with their Q-039 projections, the effect categories — live as a hardcoded table inside the Layer A grammar, synthesized into canonical nodes at compile time and documented only in the agent-facing system prompt. The design corpus does not list them; [`design/node-algebra.md`](../design/node-algebra.md) gives no hint that `intT` or `fsWrite` exist. The consequences compound: a second implementation must reverse-engineer the table to pass conformance (Q-052's validation debt); a model without the Strand system prompt cannot discover the names at all; the 2026-06-11 prelude-projection defect class — grammar table drifting from the documented form, caught only when authored programs finally went end-to-end — is structural, because the table has no authoritative artifact to drift from; and the prompt must carry the whole catalog inline, one of the larger sections the authoring-cost program (Q-060) wants out of the always-loaded core.

Meanwhile the mechanism this needs already shipped: N-046 ModuleManifest bundles exports with verifier-certified effect declarations, the federation runtime resolves hashes across stores, and `strand registry` maps names to hashes. The prelude is the obvious first real module, and not making it one leaves the federation machinery with no permanent resident.

## 2. Recommended approach

Generate the prelude module from the existing reserved-spec table at build time, pin its manifest hash, and make resolution — not synthesis — the way reserved names reach canonical form.

**Generation, not hand-authoring.** The Kotlin reserved-spec table remains the single source of truth in this slice. A build step walks it, emits every reserved node in canonical form, wraps the exports in an N-046 ModuleManifest (displayName per entry; declaredEffects certified by the verifier exactly as Q-043 specified), and admits the whole bundle to a bundled store snapshot. A conformance test asserts the generated manifest hash equals the pinned hash in a new `corpus/prelude-manifest.json` golden file, so the table cannot drift silently — the drift class behind the 2026-06-11 projection-arity defect becomes a test failure.

**Resolution replaces synthesis.** The elaborator resolves a reserved name to the export's node hash through the manifest and emits a reference to the admitted node, rather than re-synthesizing the node inline per program. The synthesized nodes today are already canonical and deterministic, so the resolved nodes are byte-identical to the synthesized ones and **no program hash changes**; this slice is additive and needs no encoding epoch.

**Registry residency.** The default `NameRegistry` ships with `prelude` mapping to the manifest hash and each export reachable through it, so `strand registry resolve fsWrite` answers from a clean checkout. The system prompt's prelude catalog reduces to: the manifest hash, the top-used names (per Q-060's minimal core), and the lookup instruction.

**Evolution by hash.** A stdlib round that adds builtins produces a new manifest with a new hash; the registry's `prelude` name advances; programs are unaffected because they reference export node hashes, not the manifest. Multi-version naming policy stays deferred under Q-043's existing deferral — the registry holding one current prelude hash is sufficient pre-adoption.

## 3. Worked example

Today, authoring `app APP fsWrite [pathLit]` causes `DagJsonEmitter.synthesizeReserved` to inline the `fsWrite` ForeignNode, its FunctionType, and the `writeFx` EffectCategory into the emitted document. After this proposal, the elaborator resolves `fsWrite` through the pinned manifest to the admitted ForeignNode's hash and emits a reference; ingestion pulls the node from the bundled prelude store exactly as a `--peer-store` import resolves today. The verifier sees the same node bytes either way; the program's root hash is unchanged. What is new is that `strand registry list` shows the prelude exports, a peer can fetch the prelude by manifest hash and get a verifier-certifiable bundle, and a second implementation's conformance target includes one pinned manifest hash instead of a re-derived private table.

## 4. Verifier rules

None new. The manifest is admitted under the existing N-046 rules (declared effects certified against each export's closure); the prelude becomes the standing exercise of them.

## 5. Runtime semantics

None new. Resolved prelude nodes evaluate exactly as their synthesized twins do today.

## 6. Test scenarios

1. **Manifest hash pinned** — generation reproduces the hash in `corpus/prelude-manifest.json`; any reserved-spec edit fails the test until the golden is deliberately regenerated.
2. **Synthesis equivalence** — for every reserved name, the resolved node hash equals the hash the legacy synthesis path produced; asserted across the full density-fixture suite.
3. **Program-hash invariance** — all corpus and Layer A fixture root hashes are unchanged with resolution active.
4. **Projection consistency, structurally** — the 2026-06-11 registry-consistency test (every prelude projection's source count equals its category's parameter count) runs against the generated module rather than the in-memory table.
5. **Registry residency** — `strand registry resolve` answers for the manifest name and an export name from a clean checkout.
6. **Federation fetch** — a program in one store resolves a prelude export through `--peer-store` pointing at the bundled prelude snapshot.
7. **Manifest certification** — tampering with a generated export's declaredEffects fails N-046 admission.
8. **Evolution** — adding a synthetic builtin to the table in a test produces a new manifest hash while every pre-existing export hash is unchanged.

## 7. Tradeoffs and open questions

**Deferred intentionally:**

- **Manifest-as-source-of-truth inversion** — eventually the module artifact should be authoritative and the Kotlin table generated from it; in this slice the table stays primary to keep the change mechanical.
- **Multi-version prelude naming** — stays under Q-043's deferral; one current hash in the registry suffices now.
- **Signing** — the manifest is the natural signing surface, blocked on Q-006 as before.
- **Polymorphic builtins** — they have no reserved FunctionTypes today and gain none here; Q-060's registry-wide implicit expansion is the complementary fix at the authoring layer.

**Real research questions:**

- *Bootstrap representation* — whether the bundled prelude snapshot ships as dag-json re-admitted at startup or as a prebuilt store image interacts with Q-058's persistent-store design; dag-json re-admission is the safe default until Q-058 lands.

## 8. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `impl-kotlin/authoring` (new `PreludeModuleGenerator`) | walk reserved specs, emit nodes, build N-046 manifest, write bundled snapshot | Medium |
| `corpus/prelude-manifest.json` (new golden) | pinned manifest hash + per-export hashes | Small |
| `impl-kotlin/authoring/.../Elaborator.kt`, `DagJsonEmitter.kt` | resolve-through-manifest path replacing `synthesizeReserved`; legacy path retained behind a test flag until equivalence suite passes | Medium |
| `impl-kotlin/cli` | default registry entries; bundled-snapshot resolution on all subcommands | Small |
| conformance tests | scenarios 1–8 | Medium |
| `evaluation/dynamic/prompts/strand-system.md` | prelude section reduced to manifest hash + minimal-core names + lookup instruction (joint with Q-060 M-2) | Small |

**Order of work.** Generator and pinned golden first (pure addition); equivalence suite second; flip resolution on; registry and prompt reduction last.

**Not in this slice.** Source-of-truth inversion, signing, multi-version naming, any encoding change.

## References

**Outgoing references:**
- [`design/node-algebra.md`](../design/node-algebra.md) — N-046 ModuleManifest
- [`proposals/cross-store-federation.md`](cross-store-federation.md) — the resolution and registry machinery reused here
- [`proposals/authoring-cost-reduction.md`](authoring-cost-reduction.md) — the prompt-diet measure this feeds
- [`open-questions.md`](../open-questions.md) — Q-034, Q-043, Q-052, Q-057

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-063 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section
- [`ROADMAP.md`](../ROADMAP.md) — Tier 3.5
