# Authoring-Cost Reduction Program

**Document:** `proposals/implemented/authoring-cost-reduction.md`
**Status:** Implemented (all four measures landed 2026-06-11 through 2026-06-12; see the Implementation note — the gating measurement runs remain to be executed under Q-021 Run 8)
**Date:** 2026-06-11
**Concerns:** [Q-021](../../open-questions.md#Q-021) (dynamic cost), [Q-034](../../open-questions.md#Q-034) (authoring layer), [Q-056](../../open-questions.md#Q-056) (hand-declared builtin signatures), [Q-057](../../open-questions.md#Q-057) (grammar parity), [`02-core-thesis.md`](../../02-core-thesis.md) § outcome-priority, [`evaluation/dynamic-results.md`](../../evaluation/dynamic-results.md)
**Scope:** medium (four independent measures, individually small to medium)

This proposal defines the near-term program for bringing Strand's tokens-per-successful-task within a practical multiple of conventional baselines, inside the existing Layer A surface. It is the engineering counterpart to the thesis position that inference cost is a constraint to be bounded, not a headline claim. The strategic alternative — replacing the agent-facing surface entirely — is the separate [`familiar-surface-lowering.md`](../familiar-surface-lowering.md) (Q-061); the two tracks are independent and the measures here remain useful under either surface.

## 1. Problem statement

Run 7 of the dynamic-cost measurement ([`evaluation/dynamic-results.md`](../../evaluation/dynamic-results.md), all figures byte-proxy) puts Strand at 28,092 tokens per successful task against 1,792 for Python and 1,255 for Kotlin — a 15.7–22.4× multiple. The decomposition shows the cost is not in the language's emissions: per-emission output is already smaller than Python's, and the static measurement has Layer A density v4 at 0.81× Python. The cost concentrates in two places. First, the agent-facing system prompt (`evaluation/dynamic/prompts/strand-system.md`, 1,858 lines, ~86 KB, roughly 21,500 token-equivalents) is re-sent on every attempt; Python's equivalent is ~1,500 tokens because the model already knows Python. Second, retries re-send that prompt in full: in Run 6, four retry-affected cells accounted for roughly 83,000 tokens, and the dominant retry cause was a Layer A grammar slip, not a semantic error.

The bounded-cost constraint in [`02-core-thesis.md`](../../02-core-thesis.md) § outcome-priority names prompt caching and skill-mediated emission as the bounding mechanisms; neither is implemented in the headline measurement path. This proposal makes the constraint operational.

## 2. Measures

Four measures, ordered by leverage per unit of effort. Each carries an acceptance gate measured through the Q-021 harness, which as of 2026-06-11 supports N>1 sampling with bootstrap confidence intervals and source-labeled token counts.

### 2.1 M-1: Prompt caching in the measurement harness

Wrap the system prompt (and the static task preamble) in a provider cache block in the `strand-eval` Anthropic backend. Cache reads price at one tenth of uncached input; cache writes at 1.25×. At N=5 samples per cell the projected per-task input cost falls by roughly two thirds; across a 22-task run sharing one system prompt, total input cost falls by roughly 80 percent. The infrastructure was sketched in [`model-api-integration.md`](../model-api-integration.md) and never wired into a headline run.

No language or prompt content changes. This measure alone closes the largest share of the cost gap and is a precondition for honest cost reporting on every later measure.

**Gate:** a cached N=5 run reports cache-read token counts separately, and the measured per-task cost falls within 20 percent of the projection.

### 2.2 M-2: Minimal-core system prompt with on-demand references

Restructure the agent-facing prompt from a monolithic catalog into a minimal always-loaded core plus on-demand reference sections, mirroring the routing the `strand-author` skill already uses. The core retains approximately: the fifteen highest-frequency grammar codes, the twenty most-used prelude names, a one-line-per-sugar density summary, three worked examples, and the error-recovery guide — an estimated 3,900 token-equivalents against the current 21,500. The remaining material (full code table, full prelude and builtin catalogs, effect-projection detail, per-provider LLM and vector sections, format libraries) moves to named reference sections the agent requests by topic.

The lookup channel is a harness-provided reference query: the agent asks for a named section or a builtin signature and receives only that text. An unknown builtin name returns a nearest-match suggestion rather than silence. This also serves the Q-056 failure shape: an agent that must hand-declare a polymorphic builtin's signature can fetch the authoritative one instead of reconstructing it.

**Gate:** an A/B run (full prompt versus core-plus-references) shows first-pass verification rate within the bootstrap confidence interval of the full-prompt rate, at materially lower total tokens.

### 2.3 M-3: Grammar-constrained decoding

Layer B already emits GBNF for the full Layer A grammar (`strand grammar`). Wire it into emission for backends that support constrained decoding (local models now; hosted APIs when grammar constraints become available). Run 6/7 retry forensics attribute roughly 60 percent of retry cost to grammar slips that a decode-time mask makes unrepresentable; the remaining semantic errors are the verifier-feedback loop working as designed and are not this measure's target.

**Gate:** the historical slip form (`APP fn args _` in an optional-list slot) is unrepresentable under the emitted grammar, demonstrated by a constrained-decode test on a local model or by a grammar-level rejection test if no constrained backend is available.

### 2.4 M-4: Layer A density v5

Two grammar-level slices, both surface-only — the canonical encoding and all existing hashes are untouched.

**Slice a — registry-wide implicit builtins.** Today the implicit prelude covers monomorphic builtins only; polymorphic and Option-returning builtins (the `List.*`, `Map.*`, `Set.*` families and others) require a hand-declared FNT and FRN at every use site — the largest residual boilerplate class and the direct source of the Q-056 misdeclaration failure shape. The Elaborator gains an inference case: a bare registry builtin name in callee position expands to its ForeignNode and FunctionType, with polymorphic signatures instantiated from the already-known argument types at the application site. This is local instantiation from known types, not unification, and stays inside the Q-034 § 6 no-unification boundary. Where argument types underdetermine the instantiation, elaboration fails with an `ElaborationGap` naming the needed annotation.

**Slice b — opt-in effect-declaration synthesis.** An `@auto` marker in an Application's effect-instances slot directs the Elaborator to synthesize the EffectDecl list from the callee's declared effects and projections. Opt-in only: explicit declarations remain the default, and the interaction between synthesized declarations and Handler interception must be pinned by test before the marker is documented agent-facing.

A third candidate — replacing prelude names with short content-hash references — is deferred: it saves little, costs agent legibility, and is superseded by [`implemented/prelude-as-module.md`](prelude-as-module.md) making the prelude addressable properly.

**Gate:** every density-v5 fixture compiles to a canonical graph byte-identical to its explicit-form counterpart; corpus golden hashes unchanged.

## 3. Projected combined effect

Sequenced effects on the Run 7 baseline (byte-proxy arithmetic, to be re-grounded in API token counts as runs adopt the 2026-06-11 source-labeled counting): M-1 at N=5 brings 28,092 toward ~9,000; M-2 brings the residual prompt share down to land near ~4,500; M-3 removes most retry re-sends; M-4 trims emissions already below baseline size. The combined projection lands in the 3,000–4,500 range — a 2.5–3.5× multiple of the Kotlin baseline — without fine-tuning, tokenizer alignment, or surface replacement. Projections are planning numbers, not claims; each gate produces the measured figure.

## 4. Test scenarios

1. **Cache accounting** — a cached N=5 run on one task reports write-once/read-many cache token counts; aggregate report labels them distinctly from uncached input.
2. **Prompt A/B non-regression** — core-plus-references first-pass rate within the full-prompt confidence interval across the 22-task suite.
3. **Reference lookup hit** — querying a known builtin returns its registry signature, effects, and projection note verbatim.
4. **Reference lookup miss** — querying a misspelled name returns a nearest-match suggestion and no fabricated signature.
5. **GBNF slip rejection** — the historical `APP _` slip form is rejected at decode or grammar level.
6. **Implicit builtin equivalence** — a program calling `List.Map` with no hand-declared FNT/FRN produces the identical canonical graph as the explicit form.
7. **Implicit builtin underdetermination** — an application whose argument types cannot fix the instantiation fails with an `ElaborationGap` naming the annotation needed.
8. **`@auto` equivalence** — an `@auto` application produces the identical canonical graph as its explicit-EffectDecl form.
9. **`@auto` under interception** — behavior of synthesized declarations at a Handler-intercepted call site is pinned (whichever semantics is chosen, a test asserts it).
10. **Hash invariance** — all corpus and density fixtures hash unchanged after every slice.

## 5. Tradeoffs and open questions

**Deferred intentionally:**

- **Fine-tuning and tokenizer alignment** — Phase 4 per [`research-plan.md`](../../research-plan.md) and Q-034 § 3.3; this program is the pre-fine-tuning bound.
- **Hash-indexed prelude references** — superseded by Q-063.
- **Hosted-API constrained decoding** — blocked on provider support; the grammar artifact is ready.

**Real research questions:**

- *Reference-query round-trip cost* — on-demand lookups trade prompt size for extra turns; the A/B gate measures the net, but the break-even point on harder task mixes is unknown.
- *`@auto` and Handler interception* — synthesized EffectDecls must not silently change which applications a Handler intercepts; scenario 9 forces the decision but the right semantics is genuinely open.

## 6. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `evaluation/dynamic/strand_eval/` (Anthropic backend) | cache_control wrapping, cache-read accounting in summaries | Medium |
| `evaluation/dynamic/prompts/strand-system.md` | restructure into core + named reference sections | Medium |
| `evaluation/dynamic/strand_eval/` (step/dispatch path) | reference-query channel with nearest-match misses | Medium |
| `impl-kotlin/authoring/.../Elaborator.kt` | registry-wide implicit-builtin inference case with local instantiation | Medium |
| `impl-kotlin/authoring/.../LayerAGrammar.kt`, parser | `@auto` marker in effect-instances slot | Small |
| `impl-kotlin/corpus` density fixtures + tests | v5 fixtures, equivalence and gap tests | Small |
| `evaluation/dynamic/README.md` | run commands for cached and A/B modes | Small |

**Order of work.** M-1 first (no behavior change, fixes the economics of every later measurement), then M-2 with its A/B gate, then M-4 slice a, then M-3 and M-4 slice b in either order.

**Not in this slice.** Surface replacement (Q-061), prelude materialization (Q-063), any canonical-encoding change.

## Implementation note (2026-06-12)

All four measures are implemented: M-1 and M-3 landed 2026-06-11, M-4 and M-2 landed 2026-06-12, each as the per-measure record below. The implementation side of the program is complete; the section 2.1 and 2.2 **measurement gates remain to be executed** — the cached N=5 run and the core-versus-full-prompt A/B are the Q-021 Run 8 follow-up, with the exact invocations recorded in `evaluation/dynamic/README.md`. Until those runs produce figures, the section 3 projections remain planning numbers.

**M-1 landed (commit "Q-060 M-1").** The `strand-eval` Anthropic backend sends two ephemeral `cache_control` breakpoints — the system prompt block and the static task preamble (first user message) — so retries within a cell reuse the full prompt prefix at cache-read rates and cells sharing one system prompt reuse the system prefix. Cache accounting flows end to end under distinct labels: the API usage fields (`cache_read_input_tokens`, `cache_creation_input_tokens`) are recorded per attempt in both summary.json writers (run mode and step mode, the latter via `response-metadata.json` relay), cell totals are carried separately from uncached input, and the `aggregate`/`report` tables surface cache reads and writes as their own columns plus a hit rate. Byte-proxy sessions record zeros — neither counting fallback can observe cache behavior, and the harness never fabricates cache figures. Cost estimation prices cache traffic at its own rates (0.1x read / 1.25x write). Validation was by mocked-client and fixture tests; the cached N=5 measurement run itself remains the section 2.1 gate to execute.

**M-2 landed (commits "Q-060 M-2 prompt restructure" and "Q-060 M-2 reference-query channel").** The agent-facing prompt split per section 2.2: `evaluation/dynamic/prompts/strand-system.md` is now the minimal always-loaded core — 313 lines, 15.6 KB, 3,879 byte-proxy token-equivalents against the monolith's ~21,500 — carrying the grammar shape, roughly twenty-two highest-frequency codes, the most-used prelude names, a one-line-per-sugar density summary that documents the M-4 v5 forms agent-facing for the first time (bare dotted registry builtins, `@auto` effect synthesis, the FN projection-DSL string), the three worked examples, the error-recovery guide, the Q-063 prelude-manifest hash with a one-line lookup instruction (discharging the hand-off recorded in [`prelude-as-module.md`](prelude-as-module.md)), and an index of the reference sections. Everything else moved to nine named sections under `prompts/references/` (grammar-codes, density-sugars, prelude, builtins, effects, llm-vector, formats, state-machines, errors), with the per-builtin signature text preserved verbatim and the v4-era guidance updated where v5 superseded it; the builtins section records the v5 signature-table coverage (218 registry targets: 129 prelude, 83 table, 6 excluded with reasons). The pre-split monolith is retained byte-for-byte (plus a header note) as `prompts/strand-system-full.md` — the full-prompt A/B arm (config `strand-layer-a-full-prompt`) and the authority document for the signature text. The reference-query channel extends step mode: a response whose first non-blank line is `strand:need <topic-or-builtin> [...]` (no fenced code block — a program always wins) advances the turn by appending only the requested text; topics serve sections, dotted names serve signature blocks, prelude reserved names serve catalog lines, and unknown names get a stdlib-difflib nearest-match suggestion, never silence or a fabricated signature — the section 2.2 Q-056 service. Reference turns are capped per cell (default 3, `--max-reference-turns` / config `max_reference_turns`); requests past the cap consume emission attempts so a looping agent still exhausts. Token accounting labels every turn (`turn_type` in the summary attempts array), records each served reply's appended tokens under its own source label, and keeps `converged_at_attempt` an emission-attempt index so first-pass means the first emitted program verified — the reference round-trip cost lands in the token totals, which is where the gate measures it. The same lookup ships standalone as `python -m strand_eval lookup <name>`. Validation: 25 new pytest cases (lookup hits and misses, cap enforcement and termination, attribution labels, protocol edges, and a fixture-mode two-turn need-then-emit smoke), suite 127 passed against the 102 baseline, no API runs.

**M-3 landed at the grammar-rejection-test level (commit "Q-060 M-3").** No hosted constrained-decode backend is available, so the section 2.3 gate ships as its documented fallback: `ConstraintGrammarSlipGateTest` in the `:authoring` module drives a minimal fully-backtracking GBNF matcher over the grammar `strand grammar` emits and proves the historical slip form (`APP fn args _` in either optional list slot, including the skip-middle variant) is not derivable, while the bracketed-list and omission forms are and `_` remains derivable at genuine nullable slots. A structural pin on the generated `node_APP`/`optional_APP` rules backs the matcher. One factual correction surfaced during implementation: the post-Run-6 parser change accepts the slip as sugar for `[]` rather than rejecting it, so the regression pin asserts sugar-equivalence (slip compiles byte-identically to the explicit-`[]` form) — the two layers together close the Run 6 retry driver from both sides. Hosted constrained decoding remains pending provider support; the grammar artifact is ready.

**M-4 landed (commits "Q-060 M-4 slice a/b").** Both density-v5 slices ship, surface-only as specified — every pre-existing corpus golden hash and all 14 density fixtures are byte-unchanged; the golden file gained only the eight new v5 fixture entries.

*Slice a — registry-wide implicit builtins.* The Layer A spelling is the dotted canonical builtin name in callee position (`mapped APP List.Map [list double]`); author ids cannot contain dots, so the form cannot collide with user declarations. The authoritative Kotlin-side signature table (`impl-kotlin/authoring/.../BuiltinSignatures.kt`) expresses 83 non-prelude registry signatures parameterized over type variables, with structural macros for the canonical `List<T>` / `Option<T>` shapes, the corpus-66 JsonValueFull tower, and the corpus-61 MarkdownDocument tower; `Map<K,V>` / `Set<T>` follow the documented opaque-`bytesT` surface convention. The Elaborator's new inference case (`ImplicitBuiltinExpansion`, in the existing fixed-point loop) instantiates a signature by matching argument types against parameter shapes positionally — local instantiation only, inside the Q-034 § 6 no-unification boundary — synthesizes the FNT + FN pair (effects and Q-039 projections included), rewrites the callee, and reuses matched user type towers so the result is byte-identical to the hand-declared counterpart (content-address dedup closes any residual difference). The matching is bidirectional in one useful direction: a lambda argument whose parameter annotations the signature determines gets them pushed in (`double LAM [x] ...` becomes `x:intT` under `List.Map` once the list argument binds `A`). Underdetermined instantiations (`List.Empty`'s element, `Map.Get`'s `V`, an unannotated `Map.Merge` conflict lambda) fail with an `ElaborationGap` naming the annotation needed — scenario 7's gate — and never guess. Registry coverage is total and pinned by `BuiltinRegistrySweepTest`: of 218 registry targets, 129 are prelude-covered, 83 table-covered, and 6 excluded with documented reasons — the two legacy Q-031 reference stubs (`Filesystem.Write`, `Network.Connect`, superseded by `Fs.Write` / `Net.Connect`), the test-only `Test.EffectfulNoOp`, and the three streaming-LLM opens (`Anthropic.Messages.CreateStream`, `OpenAI.Chat.CompletionsStream`, `Gemini.GenerateContentStream`), which take the agent-shaped `GenerateRequest` product (the expected bytesT-payload exclusion class). Two supporting grammar extensions ride along: the FN code gained an optional Q-039 projection-DSL string (`"connectFx:0,1;netSendFx:;netRecvFx:"`) so hand-authored Layer A ForeignNodes can finally carry `effectProjections` (previously impossible from Layer A), and nested `(LAM ...)` expressions now bind their own parameter names inside their bodies (a latent emitter gap the higher-order builtins made load-bearing).

*Slice b — opt-in `@auto` effect synthesis.* An `@auto` marker in an Application's effect-instances slot (or standing in for the whole optional tail: `APP fsWrite [p d] @auto`) directs the Elaborator (`AutoEffectSynthesis`) to synthesize the EffectDecl list from the callee's declared effects and projections — `ArgRef(i)` sources project the call's own value arguments; parameterless categories synthesize parameterless declarations; a parameterized category without a projection is a gap, never a guess. Explicit declarations remain the default. The Handler interaction is pinned by test (`AutoEffectsHandlerEquivalenceTest`): N-043 interception keys on the callee's declared effect categories, never on the effect-instances list, and the synthesized EffectDecls are structurally identical to the explicit ones, so an `@auto` call site under a Handler compiles byte-identical to — and therefore evaluates identically to — the explicit form (scenarios 8 and 9). The constrained-decoding grammar tracks both slices: `identifier` admits dots (documented overapproximation), the effect-instances slot derives `list_ref | "@auto"`, and the M-3 slip gate re-verifies that `_` remains unrepresentable in both optional APP slots.

Section 2.4's gate is met by `LayerADensityTest.densityV5RoundTrip` over four new fixture pairs under `corpus/layer-a/density-v5/` (each density form against its explicit hand-declared counterpart: `List.Map` with annotation push, `String.Split`/`String.Join` tower reuse, the `@auto` file-write shape, and `Fs.List` combining both slices), plus per-family equivalence tests in `:authoring` covering `List.Fold`, `Set.Union`, `Json.Parse`, `Map.Merge`, `Http.Request`, and `Fs.List`. One deliberate judgment call to revisit if it grates: `Process.EnvVar` is table-typed under E-033 `OS.Read` (the round-3 host-environment category) rather than an undeclared `Process.*` name — the system prompt's "conventionally Process.*, registry doesn't enforce" note left this open, and reusing the reserved category keeps the bare name usable without inventing an unregistered E-NNN. The agent-facing documentation of the new forms is the M-2 pass's job and is intentionally not part of this slice.

**Recorded deviations (M-2; M-1/M-3/M-4 deviations are inline in their records above):**

1. **The lookup resolves more name kinds than section 2.2 specified.** Beyond named sections and builtin signatures, prelude reserved names and effect-category names (`fsWrite`, `writeFx`) resolve to their catalog lines — free given the prelude section's line format, and the shape agents actually misremember.
2. **The reference-turn cap and its over-cap semantics are additions.** Section 2.2 did not bound the channel; the implementation caps reference turns per cell (default 3) and charges over-cap requests against the emission-retry budget, so termination is guaranteed by construction rather than by agent good behavior.
3. **First-pass accounting excludes reference turns.** `converged_at_attempt` counts emission attempts only. The alternative (a reference round-trip voiding first-pass status) would structurally penalize the core arm on the gate's non-regression metric while double-counting a cost the token totals already carry; the section 5 round-trip-cost question is answered by the A/B's token column.
4. **The full-prompt A/B arm predates density v5.** `strand-system-full.md` is the monolith byte-for-byte (plus a header note) — the v4-era teaching baseline Run 7 measured — so the A/B isolates prompt structure; the v5 forms are documented only in the core and reference sections. If a v5-taught monolith arm is ever wanted, it must be authored deliberately, not assumed.

**Out of scope, recorded for a future pass:** the `.claude/skills/strand-author` skill carries its own per-cluster grammar references predating this split. The skill's references and `prompts/references/` should converge on a single source; that unification is intentionally not part of this slice.

## References

**Outgoing references:**
- [`evaluation/dynamic-results.md`](../../evaluation/dynamic-results.md) — the Run 6/7 cost decomposition this program targets
- [`02-core-thesis.md`](../../02-core-thesis.md) — the bounded-cost constraint made operational here
- [`proposals/model-api-integration.md`](../model-api-integration.md) — the harness this extends
- [`proposals/familiar-surface-lowering.md`](../familiar-surface-lowering.md) — the strategic alternative track
- [`proposals/implemented/prelude-as-module.md`](prelude-as-module.md) — supersedes the hash-indexing candidate
- [`open-questions.md`](../../open-questions.md) — Q-021, Q-034, Q-056, Q-057

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-060 points at this proposal
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — Known gaps section
- [`ROADMAP.md`](../../ROADMAP.md) — Tier 1 (the remaining measurement sweep names the Q-060 gates)
