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

## Implementation note

Landed 2026-07-04 on branch `reason-first`. `MEX` and `MFT` `CodeSchema`s in
`LayerAGrammar.codes` (`impl-kotlin/authoring/src/main/kotlin/org/strand/authoring/LayerAGrammar.kt`):
`MEX <target> <declaredEffects> <displayName>` and `MFT <exports> [<signature>]`,
matching the recommended-approach shape exactly.

One deviation from the recommended approach's framing, discovered at Step 0
(reading the corpus JSON and `:core` ingest before writing any code): the
recommended approach describes `ManifestExport` as "a separate raw node that
the manifest references," parallel to `RawNodeRef`. That is not what the
implementation carries. `org.strand.core.StoredNode.RawManifestExport` and
the canonical `org.strand.core.Node.ManifestExport` are plain data records
embedded directly in `RawModuleManifest.exports` / `ModuleManifest.exports`
— they never occupy their own slot in the `RawNodeStore`, never get their
own `NodeId`, and never appear as a `"type": "ManifestExport"` entry in the
`nodes` map. Corpus 79 and 80's hand-authored JSON confirm this: `exports`
is an array of inline `{target, declaredEffects, displayName}` objects
nested inside the single `ModuleManifest` entry, not a list of ids pointing
at sibling nodes. So `MEX` **inlines** rather than becoming a node: the new
`DagJsonEmitter.emitNode` dispatch returns `null` for a top-level `MEX`
line (arity is still validated, but it contributes nothing to the emitted
`nodes` map on its own), and a new `expandManifestSugar` function — invoked
only when a `MFT` line is emitted — looks up each of `MFT`'s `exports` ids
as `MEX` `NodeDecl`s in the parsed document, resolves their three fields
through the same argument-resolution helpers ordinary REFERENCE/LIST_REF/
STRING slots use (so inline literals and `@last` compose inside a `MEX`
line), and builds the inline export object directly inside the
`ModuleManifest`'s `exports` array. This still "sidesteps inline object
arrays" the way the recommended approach intended (no new inline-object
`ArgKind` was added) — it just does so by inlining at the code-dispatch
level rather than by `ManifestExport` being ingest-visible as a node.

A second, narrower deviation: `MEX`'s `target` field resolves as a
*structural* reference (matching `NRF`'s `target`, `isValuePositionRefSlot`'s
`else -> false` branch) rather than through the value-position
`resolveExpressionRef` helper IF/WHEN/RES sugar uses — auto-VarRef
intentionally does not fire on a MEX `target`, since the field is a
NodeRef-style content-hash edge, not an evaluated value. This has no
observable effect on the two corpus fixtures (their targets are Lambda
ids, never PRC/LET binders), but is the semantically correct choice for
the general case.

Byte-identity result: `impl-kotlin/corpus/src/test/kotlin/org/strand/corpus/LayerAManifestTest.kt`
authors corpus 79 (`module-manifest-with-effects`) and corpus 80
(`manifest-effect-mismatch-rejected`) through the new `MFT`/`MEX` codes
(`corpus/layer-a/manifest/79-module-manifest-with-effects.layer-a` and
`corpus/layer-a/manifest/80-manifest-effect-mismatch-rejected.layer-a`) and
asserts the compiled root hash equals the corresponding golden hash from
`corpus/golden-hashes.json`. Both are hash-equal, byte-identical — confirmed
independently via a manual JSON diff during development (the only
divergence found before the fixtures matched was an initially-omitted
Q-039 `effectProjections` DSL string on the `writeFn` ForeignNode line, an
unrelated existing feature, not a defect in the new MFT/MEX emission path).
The two new Layer A fixtures were added to `corpus/golden-hashes.json`'s
`layerA` section (additive only — every pre-existing entry, including
corpus 79/80's own `programs` section entries, is unchanged; verified by
diff). No change to `CanonicalEncoder`, the epoch, the N-046 tag-46
encoding, or `JsonIngest`. Full test suite: 2448 tests, 0 failures, 0
errors, 3 pre-existing skips, across all modules.

Docs added: a "Capability manifests" section in
`.claude/skills/strand-author/references/grammar-core.md` and
`.claude/skills/strand-author/references/foreign-nodes.md` (mirroring the
adjacent ToolDef/ResponseSchemaSpec sections), and in
`evaluation/dynamic/prompts/references/grammar-codes.md` (the agent-facing
on-demand full code table `strand-system.md` already points to as
`grammar-codes`— no change needed to the topic index itself, since its
existing "every Layer A code" description already covers the addition).

## References

**Outgoing references:**
- [`decisions/ADR-010-reasoning-surface.md`](../decisions/ADR-010-reasoning-surface.md) — the create-to-reason loop this restores for the manifest construct
- [`cross-store-federation.md`](cross-store-federation.md) — N-046 ModuleManifest, the construct being made authorable
- [`open-questions.md`](../open-questions.md) — Q-057 (advanced), Q-034, Q-036
