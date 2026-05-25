# Reverse projection: canonical dag-json to Layer A

**Document:** `proposals/layer-a-reverse-projection.md`
**Status:** Draft proposal
**Date:** 2026-05-25
**Concerns:** [`proposals/implemented/llm-authoring-layer.md`](implemented/llm-authoring-layer.md), [`proposals/implemented/layer-a-density.md`](implemented/layer-a-density.md), [`impl/authoring/`](../impl/authoring/), [Q-021](../open-questions.md#Q-021), [Q-034](../open-questions.md#Q-034), [Q-036](../open-questions.md#Q-036), [`decisions/ADR-001-graph-not-text.md`](../decisions/ADR-001-graph-not-text.md), [`decisions/ADR-002-no-human-projection.md`](../decisions/ADR-002-no-human-projection.md), [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md)
**Scope:** Medium

Q-034's four-layer emission stack closes the forward direction: an LLM emits Layer A density-v4 text, which the Elaborator and DagJsonEmitter convert into canonical dag-json for the verifier. The reverse direction — taking a canonical dag-json program out of the store and presenting it as Layer A text an LLM can read — was a small `LayerATranslator` utility that the 2026-05-25 cleanup pass retired once it stopped being load-bearing. This proposal specifies a replacement that produces *density-v4* Layer A output, not the verbose canonical form the deleted translator emitted, so that an LLM consuming an existing Strand program sees the same compact representation it would itself emit. The forward direction, the Elaborator, the verifier, and the canonical CBOR encoding are all unchanged.

## 1. Problem statement

Strand's authoring stack today supports one direction. The LLM emits Layer A density-v4 text; `Authoring.compileToDagJson` parses it, runs the eleven-case Elaborator to fill in inferable annotations, and emits canonical dag-json that the verifier consumes. The reverse direction — canonical dag-json back to Layer A — has no current implementation. Three workloads need it:

**Modification.** An agent loads an existing Strand program from the store, modifies a subgraph, and re-emits. Without a reverse projection the agent sees the canonical dag-json (5.82× Python+type-hints geomean) instead of the density-v4 form (0.81× geomean) it would itself emit, paying roughly 7× the input tokens it should and operating on a representation that doesn't match its emission patterns.

**Few-shot examples.** Strand-specific prompting relies on showing the model previously-verified programs as exemplars. The exemplars should be presented in the same form the model emits; canonical dag-json in the prompt is a different lexical distribution than density-v4 in the completion, and the resulting input/output asymmetry teaches the wrong association.

**Inspection.** Agent loops that re-verify, debug, or audit stored programs need a presentation form that matches the model's vocabulary. Same argument as the few-shot case.

The deleted `LayerATranslator.kt` (commit `80d6442`, 2026-05-25) produced canonical-form Layer A — always `MAT` never `IF`, always explicit `PRC` declarations never compact `LAM [x:intT]`, always explicit `PFV` declarations never inline `[k=v ...]`, every field present. It was useful as a bootstrap tool when the corpus's `.layer-a` fixtures were initially mechanized from JSON, but it was never load-bearing in a production reading workflow and didn't survive the density-v4 cleanup pass. A restored reverse direction needs to produce density-v4 output, not the verbose pre-density form.

## 2. Prior art

Three traditions inform the design:

- **Unison's `view` command** ([Unison](https://www.unison-lang.org/)) renders any hashed Unison term as text in Unison's surface syntax. The text is a faithful presentation of the stored graph, suitable for editing in a scratch file that recompiles to the same hashes. This is the closest existing analog to the reverse projection: a bidirectional path between content-addressed storage and a compact text form intended for authoring.

- **Hazel's bidirectional projection** ([Omar et al., 2017](https://hazel.org)) maintains coherence between an underlying typed-AST and a structured-editor surface. Edits in either direction propagate through deterministic projection. The same property the proposal aims for — `projection(forward(text)) == text` byte-identical for canonical-form text — is Hazel's correctness invariant.

- **Pretty-printing in compiler infrastructure** is the standard mechanism. Every production compiler has an "AST → source" pass used for tooling: LSP hover summaries, diagnostic output, debugging. The pass is deterministic, lossy only with respect to formatting (whitespace, comments), and required to round-trip stable on AST that came from parsing canonical source. For Strand, the analog is `canonical dag-json → Layer A text`, with the canonical CBOR hash playing the role of the AST-equivalence check.

The deleted `LayerATranslator` followed the third pattern but stopped at the in-memory `LayerADocument` IR; no text-rendering pass was ever written. The Layer A density v1-v4 work introduced ten grammar sugars that need to be applied during projection for the output to match what the LLM emits.

## 3. Recommended approach

Build the reverse projection as a **two-stage pass producing density-v4 output**, with a **hybrid elaboration-omission strategy** for the eleven inference cases:

**Stage 1 — `LayerATranslator`** walks the canonical dag-json bottom-up, producing a `LayerADocument` IR. During the walk it applies each density sugar (implicit prelude, IF/WHEN, compact-LAM, inline literals, auto-VarRef, anonymous IDs, inline PFV, nested expressions) in a deterministic preference order. Sugar application is decided structurally — every sugar has a canonical-bytes trigger pattern.

**Stage 2 — `LayerARenderer`** formats the `LayerADocument` as Layer A text. Line ordering follows the document's `NodeDecl` order (which the translator constructs in topological order). The renderer is deterministic; equivalent canonical inputs produce byte-identical text outputs.

**Hybrid elaboration-omission:** for the eleven inference cases the Elaborator handles in the forward direction, the projection decides per case whether to omit the inferable field:

- **Always omit (SAFE):** recursion-slot `paramType`. Deterministically reproducible from `FIX.recursionType` by the verifier's Fixpoint shape check. (Step 2 implementation found that `Lambda.effects`, initially proposed SAFE, is actually BORDERLINE because of legal over-declaration — see the case table in §4.2.)
- **Probe-and-fallback (BORDERLINE, 7 cases):** emit the omitted form, run `Authoring.compileToDagJson` on it, compare to the original canonical bytes. If equal, accept; if different, dag-json diff identifies which inference produced a different result, the projection restores that one field explicitly, and the cycle retries. Bounded retry count (4) handles the deepest interaction we measured.
- **Always emit explicit (UNSAFE):** detected by structural inspection of the canonical (the only known case is a `paramType` resolving to a `SchemaType<T>` node). Mechanical to detect.

The round-trip invariant the projection guarantees: `forward_compile(render(translate(canonical))) == canonical` byte-for-byte. This is the test gate; any program for which the projection violates it is a bug.

The alternative considered and rejected is **explicit-everything projection** (the deleted translator's strategy). It is mechanically simpler but produces output that doesn't match the LLM's emission patterns, defeats the few-shot and modification workloads, and would force the user to pay roughly 5.82×/0.81× ≈ 7× the tokens they should. The maintenance burden of the hybrid strategy is bounded by the existing `LayerADensityTest` regression net plus the proposed `LayerAReverseRoundTripTest`.

The alternative also considered and rejected is **pure probe-and-fallback for all eleven cases** (no static classification). It is correct by construction but pays multiple full elaboration cycles per projection on programs with many Lambda or recursion-slot annotations (the SAFE cases fire on most non-trivial programs). Static classification of the two SAFE cases is a small surface area to maintain and a meaningful constant-factor speedup; the rest stay probe-driven so they auto-track Elaborator changes.

## 4. Detailed mechanism

### 4.1 Density sugar projection order

The translator applies sugars in this preference order during a bottom-up walk:

1. **Implicit prelude** (Slice 1). For every node, compare its canonical content against the 49 entries in `LayerAGrammar.reservedNodes`. On a structural match (same `jsonType`, same string fields, same recursively-resolved ref fields), drop the node's local declaration and rewrite its references to the reserved name. Required precondition: the local node carries no `name` or `provenance` metadata beyond the reserved spec's content. Cascade: removing a declaration doesn't change reference counts of other nodes.

2. **IF and WHEN sugars** (Slices 4, 9). Apply to whole Match clusters before per-node sugaring, because the expansion absorbs 6+ wrapper nodes wholesale. A Match collapses to IF iff it has exactly two MatchCases, both Patterns are LiteralPattern over BoolLit with values {true, false}, and the six wrapper nodes (2 BoolLit, 2 Pattern, 2 MatchCase) are each used exactly once. A Match collapses to WHEN iff every Pattern is a ConstructorPattern (with optional VariablePattern payload), the scrutinee resolves to a SumType (requires `nodeTypes` from the verifier — the only sugar that needs it), and each PCN/PVR/MC is used exactly once. The two sugars are mutually exclusive on the same Match.

3. **Compact LAM parameters** (Slice 5). A Lambda's `parameters` PRCs each used exactly once with valid-identifier `name` fields collapse to `LAM [name:typeRef ...]`. Cascade: removing the explicit PRC declarations may enable downstream Slice 10 nesting of the Lambda's body.

4. **Inline ProductFieldValue list** (Slice 8). A ProductValue's PFV children each used exactly once collapse to `PV ofType [name=value ...]`.

5. **Inline literals and auto-VarRef** (Slices 2, 3, subsuming 6). At each value-position arg slot during emission, replace a single-use literal node with an inline token and a single-use VarRef-around-PRC with the PRC id directly. The Slice 3 PRC restriction is preserved — Let binders are not auto-VarRef'd.

6. **Nested expressions** (Slice 10). A value-producing node (per `CodeSchema.producesValue`) used exactly once at a REFERENCE/LIST_REF/NULLABLE_REF slot is inlined as `(CODE args...)` in the parent's arg list. Recursive: the nested node's own children are subject to the same projection rules.

7. **Anonymous `_` with `@last`** (Slice 7). Fallback for single-use nodes whose parent slot doesn't accept Slice 10's nested form. In practice Slice 10 subsumes Slice 7 in nearly all cases; `_` is reserved for the edge case of a single-use node referenced via a structural slot that doesn't admit nesting.

The walk converges in one bottom-up pass with this ordering. Each sugar's trigger condition references only nodes already projected (children) or static information (sibling structure, canonical content, `nodeTypes` for Slice 9 only).

### 4.2 Elaboration-omission table

Drawing from the per-case reversibility analysis in the research:

| # | Inference case | Class | Projection rule |
|---|----------------|-------|-----------------|
| 1 | `Lambda.paramType` (separate PRC, compact LAM single-call-site) | BORDERLINE | Probe: emit without paramType, re-elaborate, compare. |
| 2 | `Application.typeArguments` | BORDERLINE | Probe. |
| 3 | `Lambda.effects` | BORDERLINE | Initially classified SAFE but corpus programs 12, 13, 14 (and the explicit `14-pure-lambda-with-overdeclared-effect`) demonstrate Lambdas legally declaring more effects than the body produces (over-declaration is allowed). The Elaborator's case 1 fires only on non-empty body closures and inserts the *closure*, not the declared set, so static omission changes bytes whenever declared ≠ closure. Handled by probe-and-fallback instead. |
| 4 | `Application.effectInstances` | BORDERLINE | Probe; or static rule "omit iff each callee category has exactly one EffectDecl document-wide." |
| 5 | Recursion-slot `paramType` | **SAFE** | Always omit (or rewrite compact-LAM entry to bare `name`); source is `FIX.recursionType`, deterministic. |
| 6 | `FunctionType` synthesis | BORDERLINE | Probe; cycle risk on bodies whose return type depends on the recursive call. |
| 7 | `SumCaseSchema.caseType` | BORDERLINE | Probe; safe when ≥1 SV usage with unambiguous payload type. |
| 8 | Compact-LAM param via call sites | BORDERLINE | Probe. |
| 9 | Compact-LAM param via reserved builtins | BORDERLINE | Probe; reserved-name table is stable so this is essentially safe but treated borderline for uniformity. |
| 10 | Compact-LAM param via transitionFn / Match / PFV / PFG context | BORDERLINE | Probe. |
| 11 | Compact-LAM param at a `SchemaType<T>` slot | **UNSAFE** | Static rule: if `paramType` resolves to a `SCH` node, always emit explicit. Detection is a single canonical lookup. |

Cases 3, 5 fire on virtually every Lambda and every Fixpoint respectively. Static classification of these two saves one probe cycle per affected node. Cases 1, 2, 4, 6, 7, 8, 9, 10 fire on selected program shapes; the probe cost is bounded and the maintenance benefit of not duplicating the Elaborator's gate logic is meaningful.

### 4.3 Probe-and-fallback algorithm

For each BORDERLINE field on each node:

1. Tentatively omit the field in the `LayerADocument`.
2. Render the document to text.
3. Run `Authoring.compileToDagJson` on the text.
4. Hash the result and compare to the canonical's hash.
5. On match: accept the omission.
6. On mismatch: dag-json diff identifies the specific field where re-elaboration produced a different value. Restore that one field explicitly. Retry the parent node's probe.

The retry budget is 4 attempts per node. Empirically, the deepest known case is the json-value v4 fixture where one `SchemaType` annotation needs to stay explicit; that's a depth-1 fallback. The retry budget exists to bound the worst case, not to handle expected loads.

The probe runs the Elaborator's normal fixed-point loop, so any interaction between inference cases that the Elaborator handles is automatically respected. The reverse projection doesn't need to model inference interactions itself.

### 4.4 Worked example: the factorial fixture

Canonical dag-json: 32 nodes (intT, boolT, factT, subT, sub, mulT, mul, eqT, eq, recurse, n, zero, one, nRef, nIsZero, litTrue, patTrue, caseTrue, litFalse, patFalse, recRef, nMinus1, recCall, mulBody, caseFalse, matchBody, bodyLam, fact, five, app).

After bottom-up projection:

1. Slice 1 removes intT, boolT, factT, subT, sub, mulT, mul, eqT, eq (9 of 32) — all reserved-prelude matches. Their references throughout become `intT`, `boolT`, `sub`, `mul`, `eq` etc.
2. Slice 4 fires on `matchBody` because the two MatchCases over `nIsZero` are LiteralPattern(BoolLit true) and LiteralPattern(BoolLit false). Removes litTrue, patTrue, caseTrue, litFalse, patFalse, caseFalse (6 nodes), absorbs them into `IF nIsZero one mulBody`.
3. Slice 5 fires on `bodyLam`'s `recurse` and `n` parameters (each used as a binder by `bodyLam` only) — collapses to `LAM [recurse:factT n:intT] ...`.
4. Slice 6 is subsumed by Slice 2; Slice 7 is subsumed by Slice 10.
5. Slice 10 fires on `nIsZero`, `recCall`, `nMinus1`, `mulBody` (each used exactly once at a value-position slot), inlining them.
6. Slice 2 inlines `zero`, `one`, `five` at their use sites.
7. Slice 3 implicit auto-VarRef removes `nRef`, `recRef`.

After elaboration-omission probe: case 3 (Lambda.effects on `bodyLam` and `fact`'s body) drops effects (none in this program). Case 5 (recursion-slot paramType) rewrites the compact-LAM entry for `recurse` from `recurse:factT` to bare `recurse` (the type is `FIX.recursionType`). Cases 1 and 6 probe-omit `paramType` on `n` (resolves to `intT` from the `(APP eq [n 0])` call site, matching the canonical) and synthesize `factT` (the body return type derived from the IF's then-branch literal `1` of type `intT`, matched against the recursive call's result).

Final density-v4 output (matching the existing `density-v4` fixture):

```
@v=1 root=app
fact FIX factT (LAM [recurse n] (IF (APP eq [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])])))
app APP fact [5]
```

Two lines instead of thirty-two. Canonical hash unchanged.

### 4.5 Policy decisions

Four operational policies the projection commits to, alongside the structural design above.

**Classification evolution.** New Elaborator inference cases default to BORDERLINE in the projection's classification table. They are promoted to SAFE only when (a) the corpus round-trip test passes on every program where the case fires, AND (b) the inference depends only on sibling structural data, not on values produced by other Elaborator passes, AND (c) the original field cannot legally diverge from the inferred value (i.e., over-declaration must be excluded by the verifier). Recursion-slot `paramType` meets all three; `Lambda.effects` fails (c) because the verifier permits over-declared Lambdas, so it was demoted to BORDERLINE during Step 2 implementation. Demotion is immediate on any round-trip failure. The CI round-trip test is the gate; classification drift between projection and Elaborator surfaces there.

**Author ID preservation.** Canonical dag-json file inputs carry author IDs as JSON object keys (they are stripped by the canonical CBOR encoder but present in the dag-json serialization). The projection preserves these IDs for nodes that survive sugar projection. Nodes eliminated by sugars (Slice 4 IF, Slice 8 inline PFV, Slice 10 nested, etc.) do not appear in output, so their synthesized IDs are not a concern. For nameless single-use survivors, the projection mints short sequential IDs (`a`, `b`, `c`, ...). This yields byte-identical canonical output and preserves debugging correlation when an agent originally emitted with named IDs. Programs read directly from the canonical CBOR store (where author IDs are absent) follow the same minting rule.

**Cross-document references.** NodeRefs whose target hashes resolve outside the current document are rendered as raw hash literals in Layer A (`NRF <hash>`). The Slice 1 implicit prelude handles the forty-nine well-known nodes by reserved-name substitution; for arbitrary cross-document references, the raw hash is the only correct rendering. A future named-import mechanism (`@import <hash> as fh; NRF fh.fact`) is a separate Q-NNN-worthy proposal once distribution-model workflows surface real cross-document use cases.

**Diagnostic annotations.** The projection does not emit annotations marking which fields were elided. Layer A has no comment grammar to host them; the round-trip property guarantees no information is lost. If a debugging workflow requires elision visibility, the projection exposes a programmatic API (`translate(canonical): Pair<LayerADocument, List<EliminationDecision>>`) returning decisions as a structured side channel rather than embedded in the text. Adding this API is a follow-up if needed.

## 5. Verifier rules

Not applicable — the reverse projection produces Layer A text consumed by the existing forward pipeline; no new verifier rules are introduced.

## 6. Interpreter / runtime semantics

Not applicable — the projection is build-time, not runtime.

## 7. Test scenarios

1. **Round-trip every canonical corpus program.** For each `*.json` under `impl/corpus/src/main/resources/corpus/`, project to Layer A text, forward-compile the text, assert canonical hash equality. This is the primary correctness gate.

2. **Round-trip every density-v4 fixture.** For each `*.layer-a` under `density-v4/`, forward-compile to canonical, project back, assert text equality. The density-v4 fixtures are the hand-tuned LLM-emission targets; the projection must reproduce them exactly.

3. **The SchemaType↔T case stays explicit.** For `02-json-value` v4, the projection's output retains the `jv:jsonValueSchema` annotation on the compact-LAM. Asserts the Slice 11 UNSAFE rule fires.

4. **Probe-fallback handles multi-field interaction.** A constructed test program in which omitting two fields together would re-elaborate to a different result, but omitting either alone is fine. The projection should produce the omit-one variant, not fail to converge.

5. **IF and WHEN disambiguation.** A program with two Match nodes — one Bool-patterned, one constructor-patterned — projects to one IF and one WHEN. Asserts Slice 4 vs Slice 9 selection is correct.

6. **Reserved-prelude shadow doesn't fire.** A program that locally declares an `intT` with a `name: "MyInt"` metadata attached projects to the explicit local declaration (the metadata blocks Slice 1).

7. **Single-use vs multi-use literal.** A literal node used at two arg sites projects to an explicit ILT declaration. A literal used at one arg site projects to inline. Asserts Slice 2 reference-count logic.

8. **Cycle-risk FunctionType synthesis.** A FIX whose body's return type structurally depends on the recursive call (e.g., a recursive map whose return type is `List<B>`) is projected; if Slice 6 inference produces a different FNT, the probe restores the explicit FNT declaration.

9. **`nodeTypes` plumbing for WHEN.** The projection requires the verifier's `nodeTypes` map for SumType scrutinee resolution; assert the integration point works on every corpus program with a sum-Match.

10. **CLI: `strand translate <file.json>` produces Layer A on stdout.** Pipes through forward-compile and asserts byte-equal canonical.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Comments and formatting preservation.** Layer A doesn't currently support comments. If comments are added later, round-trip with comment preservation would need a separate mechanism (the canonical dag-json doesn't carry comments). For now: projection produces unannotated text.
- **Pretty-printing options.** The renderer produces one-form output (one node per line, density-v4 sugars applied). A pretty mode with indentation, line breaks within nested expressions, etc., is a follow-up if a human-inspection workflow demands it. ADR-002 still applies — the proposal does not introduce a human-readable projection layer.
- **Sub-grammar reverse for WHEN case lists.** The Slice 9 WHEN case list is currently a quoted-string in Layer A (a parser-simplification choice in the forward direction). The reverse direction emits the same quoted-string form. A stricter sub-grammar with parser-side parsing of `Constructor(binder) -> body | ...` would propagate to the reverse direction at no extra cost; that work is sequenced behind the forward parser change.
- **Pretty-print of nested expressions over multiple lines.** Slice 10 nesting can produce one very long line (the factorial example above is one such case). A heuristic to break lines past a width threshold while preserving canonical hash is a follow-up.
- **Performance optimization.** Probe-and-fallback re-runs the Elaborator per BORDERLINE field; for very large programs the cumulative cost may be material. Caching the Elaborator's per-node outputs is a follow-up if a real workload demands it.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `impl/authoring/src/main/kotlin/org/strand/authoring/LayerATranslator.kt` | NEW — canonical dag-json → `LayerADocument` with density-sugar projection in bottom-up walk. Visit order, sugar dispatch, reference-count map, `nodeTypes` plumbing for Slice 9 WHEN. | Medium-Large (~500 lines) |
| `impl/authoring/src/main/kotlin/org/strand/authoring/LayerARenderer.kt` | NEW — `LayerADocument` → Layer A text. Deterministic line ordering, density-v4 grammar tokens, nested-expression line layout. | Medium (~250 lines) |
| `impl/authoring/src/main/kotlin/org/strand/authoring/ElaborationOmission.kt` | NEW — static SAFE/UNSAFE classification table for the 11 cases plus the probe-and-fallback loop driver. | Small-Medium (~150 lines) |
| `impl/authoring/src/main/kotlin/org/strand/authoring/Authoring.kt` | EXTEND — add public entry `Authoring.projectFromDagJson(canonical: String, nodeTypes: Map<NodeId, TypeExpr>? = null): String` that orchestrates translator + renderer + omission probe. | Small (~50 lines added) |
| `impl/authoring/src/test/kotlin/org/strand/authoring/LayerATranslatorTest.kt` | NEW — unit tests for each density sugar's projection rule (10 sugars × ~3 cases each) plus elaboration-omission unit tests (11 cases × probe/safe/unsafe variants). | Medium (~400 lines) |
| `impl/corpus/src/test/kotlin/org/strand/corpus/LayerAReverseRoundTripTest.kt` | NEW — for every canonical corpus program: project to text, forward-compile, assert canonical hash equality. This is the primary regression net. | Small (~80 lines) |
| `impl/cli/src/main/kotlin/org/strand/cli/Main.kt` | EXTEND — add `strand translate <file.json>` subcommand. Reads JSON, runs verifier to get `nodeTypes`, projects, prints text. | Small (~40 lines added) |
| `impl/CLAUDE.md` | EXTEND — add entry under "Known gaps and design questions" pointing at this proposal. Update authoring-module description. | Trivial |
| `proposals/README.md` | EXTEND — add this proposal to the Current proposals table. | Trivial |
| `open-questions.md` | EXTEND — register Q-036, status `Proposed`, resolution summary points at this document. | Trivial |
| `INDEX.md` | EXTEND — identifier-registry blurb updated to "Q-001 through Q-036". Last-revised line. | Trivial |

**Order of work.**

1. **`LayerARenderer` + simple `LayerATranslator` with no density sugars** — gets the basic JSON-walk + text-emission path working end-to-end. Output matches what the deleted `LayerATranslator` would have produced (verbose canonical form). Round-trip test passes. This is the minimum-viable shell that bounds risk.
2. **Add `ElaborationOmission` with the two SAFE static rules** — projection now strips `Lambda.effects` and recursion-slot paramTypes. Round-trip remains green. Static-rule strategy proven viable on the corpus.
3. **Add probe-and-fallback for the BORDERLINE elaboration cases** — projection now uses re-elaboration to verify per-field omissions. Hits every density-v4 fixture's annotations.
4. **Add density sugars in preference order** — Slice 1 implicit prelude first (biggest single-program impact); then Slices 4/9 IF/WHEN; then Slices 5/8 compact LAM and PFV; then Slices 2/3 inline literals and auto-VarRef; then Slice 10 nested expressions; finally Slice 7 anonymous `_` (likely unused in practice).
5. **CLI `strand translate`** — wraps the projection in a user-facing entry point.
6. **Extend `LayerAReverseRoundTripTest` coverage** — add edge-case programs that stress each sugar's constraints (multi-use literals, metadata-blocking shadow, cycle-risk FNT, etc.).

**Not in this slice.**

- Comments and formatting preservation in Layer A
- Pretty-printer mode for human inspection (ADR-002 boundary)
- WHEN sub-grammar reverse (couples to forward parser change)
- Performance optimization beyond the bounded probe budget
- Cross-document reference rendering beyond hash literals
- A diagnostic mode emitting elaboration-trace annotations

## References

**Outgoing references:**
- [`proposals/implemented/llm-authoring-layer.md`](implemented/llm-authoring-layer.md) — Q-034 step 1 establishes the forward direction and the four-layer stack that this proposal closes the reverse of
- [`proposals/implemented/layer-a-density.md`](implemented/layer-a-density.md) — the 10 density sugars whose reverse-projection rules this proposal specifies; also the cleanup pass that retired the old `LayerATranslator`
- [`decisions/ADR-001-graph-not-text.md`](../decisions/ADR-001-graph-not-text.md) — graph remains the source; Layer A is a tool-layer affordance, not a parallel source language
- [`decisions/ADR-002-no-human-projection.md`](../decisions/ADR-002-no-human-projection.md) — preserved: Layer A is for LLM emission and consumption, not for human authoring; the proposal does not introduce a human-readable projection layer
- [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — canonical CBOR encoding and BLAKE3 hashing are unchanged; round-trip correctness is asserted by hash equality
- [`impl/authoring/`](../impl/authoring/) — the module the new code lives in
- [`open-questions.md`](../open-questions.md) — Q-021, Q-034, Q-036
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — implementation orientation for the next session

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-036 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — Known gaps section will reference this proposal
