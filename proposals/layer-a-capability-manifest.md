# Layer A authoring for capability manifests (MFT / MEX)

**Document:** `proposals/layer-a-capability-manifest.md`
**Status:** Draft
**Question:** [Q-057](../open-questions.md#Q-057) (advances — closes the N-046 ModuleManifest / ManifestExport authoring gap; the other four gaps stay open)
**Related:** [ADR-010](../decisions/ADR-010-reasoning-surface.md), N-046 ModuleManifest ([`implemented/cross-store-federation` / `cross-store-federation.md`](cross-store-federation.md)), [Q-034](../open-questions.md#Q-034) (Layer A), [`implemented/reasoning-api.md`](implemented/reasoning-api.md) (Q-072)
**Drafted:** 2026-07-04

## Problem

[ADR-010](../decisions/ADR-010-reasoning-surface.md) makes reasoning a first-class capability, and the create-to-reason loop requires that an agent can author, through the documented Layer A interface, the constructs the reason layer analyzes. The N-046 ModuleManifest is the capability-statement construct: it bundles exports with per-export declared effects, the machine-checked statement of what a module can do (the subject of the mcp-tool-manifest demonstration, and the reason a client can trust a module's capabilities by verification rather than by reading prose). Yet per [Q-057](../open-questions.md#Q-057) it is unauthorable in Layer A — modules require hand-written canonical dag-json, and corpus 79/80 and the mcp-tool-manifest demo manifests are all hand-authored. An agent cannot create a bounded, effect-declaring capability manifest through the interface it is taught, so the create end of the loop is broken for exactly the reason-relevant construct. Q-057 names the blocker precisely: ManifestExport is an inline sub-object list, and no current Layer A ArgKind emits inline object arrays.

## Recommended approach

Add two Layer A codes, using the one-node-per-line model that already governs the grammar, which sidesteps inline object arrays entirely:

- `MEX` (ManifestExport): a node line `<id> MEX <target> <declaredEffects> <displayName>`, where `target` is a reference to the exported node, `declaredEffects` a list of EffectCategory references, and `displayName` a string. Each export is its own node line.
- `MFT` (ModuleManifest): `<id> MFT <exports> <signature?>`, where `exports` is a list of MEX author-ids referenced by id and `signature` the optional `manifestSignature` bytes.

This mirrors the algebra's own model: `ManifestExport` is a separate raw node that the manifest references (the JSON ingest's `RawModuleManifest` / `RawManifestExport` path), so the Layer A form maps one-to-one onto the existing ingest, and the emitter produces the same `RawModuleManifest` / `RawManifestExport` records the hand-authored JSON produces. The list-of-ids shape for `exports` reuses the same ArgKind the grammar already uses for other node-id lists (`CapabilityScope.capabilities`, `StateMachine.inputStreams`), so no new inline-array machinery is needed.

The defining correctness property is byte-identity: authoring corpus 79 and 80 (and the mcp-tool-manifest demo manifests) through the new `MFT` / `MEX` codes must compile to canonical dag-json that hash-equals the existing hand-written goldens. No golden is regenerated; the manifest's canonical encoding (N-046 tag 46) is untouched — this adds only an authoring path onto it.

## Where it lives

New `MFT` and `MEX` `CodeSchema`s in `LayerAGrammar`; `DagJsonEmitter` support emitting the `RawModuleManifest` / `RawManifestExport` shape (the metadata `displayName` and `manifestSignature` handled as the ingest already handles them); the Layer A grammar reference and the agent-facing system-prompt documentation so agents learn the codes. A byte-identity regression that compiles corpus 79/80 from Layer A and asserts hash-equality with the golden dag-json.

## Deferred and open

- The other four Q-057 gaps — the EventStream `source` edge, `OverflowPolicy.Sample`, the external-hash NodeRef `targetHash` form, and `RecursiveProjection` — stay open under Q-057; this slice closes only the manifest gap, the one most directly load-bearing for the reason-first create-to-reason loop.
- Reverse projection (canonical dag-json to `MFT` / `MEX`, per Q-036) is a natural companion and can follow.
- The Q-057 standing convention that new node surface ships with its Layer A form in the same change is a broader policy this slice exemplifies but does not itself install.

## Scope

Small to medium. Two grammar codes, emitter support for the raw-manifest shape, a byte-identity test authoring corpus 79/80, and system-prompt documentation. Hash-neutral — an authoring path onto an unchanged encoding.

## References

**Outgoing references:**
- [`decisions/ADR-010-reasoning-surface.md`](../decisions/ADR-010-reasoning-surface.md) — the create-to-reason loop this restores for the manifest construct
- [`cross-store-federation.md`](cross-store-federation.md) — N-046 ModuleManifest, the construct being made authorable
- [`open-questions.md`](../open-questions.md) — Q-057 (advanced), Q-034, Q-036
