# Pre-1.0 Encoding Epochs

**Document:** `proposals/encoding-epochs.md`
**Status:** Draft proposal
**Date:** 2026-06-11
**Concerns:** [`design/canonical-encoding.md`](../design/canonical-encoding.md), [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md), [Q-024](../open-questions.md#Q-024) (versioning), [Q-049](../open-questions.md#Q-049) (subtyping), [Q-052](../open-questions.md#Q-052) (conformance vectors), [Q-053](../open-questions.md#Q-053) (nested recursive types)
**Scope:** small-medium (a policy plus regeneration tooling; the spend items carry their own scopes)

This proposal replaces the implicit rule that canonical-encoding changes must preserve all existing hashes with an explicit pre-1.0 policy: breaking encoding changes are permitted, batched into named epochs, each shipped with regenerated conformance vectors. It exists because hash invariance has been operating as a design constraint while delivering no present value, and at least one fundamental expressiveness fix is partly blocked behind it.

## 1. Problem statement

The corpus treats hash invariance as a per-change success criterion: recent work records "all existing hashes preserved" as a shipping requirement, and the encoding has accumulated shape-compromises to honor it — fields gated on non-empty or non-null so that pre-existing programs hash byte-identically (`effectProjections` under Q-039, the EventStream `source` edge under Q-046), rather than the uniform presence-prefix rule [`design/canonical-encoding.md`](../design/canonical-encoding.md) defines elsewhere. Q-053, the nested-recursive-type ceiling that blocks composite recursive structures and the HTML5/SVG libraries, is a case where the right fix plausibly changes `RecursiveSelf`/`RecursiveType` encoding semantics, and the invariance rule raises its apparent cost.

The invariance rule protects nothing today. No external party holds Strand hashes; no federation peer exists outside this repository; the golden vectors in [`corpus/golden-hashes.json`](../corpus/golden-hashes.json) are regression fixtures with a documented regeneration flag, not contracts. The cost of breaking every hash is one regeneration commit; the cost of designing around invariance is permanent encoding complexity and deferred expressiveness. Q-024's conservative additive versioning is the right policy for a language with users; it is premature for a language whose only store is rebuilt from dag-json on every run.

## 2. Recommended approach

**Policy.** Until a declared stability point (1.0, or the first external artifact holder, whichever comes first), the canonical encoding may change incompatibly. Changes are batched into **epochs**. Each epoch: is defined by its own proposal naming every encoding change it bundles; regenerates `corpus/golden-hashes.json` and all hash-bearing fixtures in the same commit; updates the normative encoding specification in the same pass; and is recorded in an Epoch log section added to [`design/canonical-encoding.md`](../design/canonical-encoding.md) and in `INDEX.md`. Between epochs, the invariance discipline continues to apply — the point is to make breaking changes deliberate and batched, not casual.

**No in-band epoch marker.** The encoding carries no epoch byte. Two epochs' encodings of the same graph simply produce different hashes, and an implementation accepts exactly the epoch it implements; a mixed-epoch peer store fails closed on hash mismatch. The epoch number lives in the spec's Epoch log and as a top-level `"epoch"` field in `golden-hashes.json`. An in-band marker buys nothing while no two epochs coexist in deployment, and would itself be a permanent encoding commitment; revisit only if cross-epoch federation becomes real before 1.0 (recorded in § 5).

**Epoch 2 charter.** The current encoding is retroactively epoch 1. The first spend, gathered under one epoch rather than three invariance-contorted patches:

1. **The Q-053 fix.** Whatever encoding or resolution-semantics change the value-construction resolution of nested recursive types requires, designed on its merits rather than around invariance. The fix itself is specified under Q-053's own proposal when taken up; this epoch is its encoding budget.
2. **Optional-field normalization.** Replace the ad-hoc gated trailing fields (`effectProjections`, EventStream `source`) with the spec's uniform presence-prefix rule, removing the two special cases from the conformance surface.
3. **The Q-049 `bound` decision, if encoding-touching.** If Q-049 resolves to removing the unenforced `TypeParameter.bound` field, the removal rides this epoch; if it resolves verifier-side only, nothing changes here.

Items 2 and 3 are cleanup that would never justify breaking hashes alone; bundling them with the Q-053 fix is the batching policy working as intended.

**Stability point.** At 1.0 or first external adoption, the policy flips to Q-024's additive-only discipline, and the then-current epoch becomes the long-term encoding. ADR-003's multihash prefix already covers the orthogonal hash-function-migration case.

## 3. Worked consequence

After epoch 2, every root hash in `golden-hashes.json` changes, the `"epoch"` field reads 2, and `CanonicalEncodingSpecTest`'s byte traces are updated alongside the spec prose they validate. Authored programs are unaffected: dag-json and Layer A are encoding-independent, and every graph re-derives under the new epoch by re-ingestion — which is also the migration story while stores are rebuilt per run (Q-058's persistent store, once it exists, records the epoch and re-admits on mismatch).

## 4. Test scenarios

1. **Regeneration completeness** — the epoch-2 commit leaves `CorpusGoldenHashTest`, the density-fixture round-trips, `VmEquivalenceTest`, and `CanonicalEncodingSpecTest` green with zero stale vectors.
2. **Epoch metadata** — `golden-hashes.json` carries the epoch number; the conformance test asserts it matches the implementation's declared epoch.
3. **Surface stability** — every Layer A fixture and corpus dag-json file compiles unchanged; only hashes move.
4. **Normalized optionals** — a ForeignNode without projections and an EventStream without a source encode through the uniform presence rule; the gated-encoding paths are deleted, not retained.
5. **Mixed-epoch rejection** — resolving a NodeRef against a peer store hashed under epoch 1 fails with the existing integrity-violation error, not silent acceptance.

## 5. Tradeoffs and open questions

**Deferred intentionally:**

- **Cross-epoch federation** — no mechanism for resolving across epochs; fail-closed is correct while every store is local. Unblocked by a real multi-party deployment, at which point an in-band marker or envelope metadata gets its own proposal.
- **Epoch-2 execution** — this proposal establishes the policy and charter; the Q-053 design work remains its own research pass.

**Real research questions:**

- *Policy discipline* — the risk is epochs becoming casual. The mitigation is structural: an epoch requires its own proposal and INDEX registration, so each one is a deliberate, reviewed event.

## 6. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `design/canonical-encoding.md` | Epoch log section; epoch-1 baseline entry; normalization spec for epoch 2 | Small |
| `corpus/golden-hashes.json` + regeneration path | top-level `"epoch"` field; regeneration includes it | Small |
| `impl-kotlin` conformance tests | assert epoch match; epoch-2 vector regeneration when executed | Small |
| `decisions/ADR-003-content-addressing.md` | one clarifying paragraph: pre-1.0 epoch policy, additive discipline thereafter | Small |
| Epoch-2 encoding changes (Q-053 fix, optional normalization, bound removal) | per their own proposals | Medium-Large |

**Order of work.** Adopt the policy (spec + metadata + ADR paragraph) immediately; execute epoch 2 only when the Q-053 design is ready, so the epoch ships with its justifying payload.

**Not in this slice.** The Q-053 design itself; any post-1.0 migration tooling.

## References

**Outgoing references:**
- [`design/canonical-encoding.md`](../design/canonical-encoding.md) — the normative encoding this policy governs
- [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — content addressing and the multihash migration path
- [`corpus/golden-hashes.json`](../corpus/golden-hashes.json) — the conformance vectors regenerated per epoch
- [`open-questions.md`](../open-questions.md) — Q-024, Q-049, Q-052, Q-053

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-062 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section
- [`ROADMAP.md`](../ROADMAP.md) — Tier 2
