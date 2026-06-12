# Authoring-Cost Reduction Program

**Document:** `proposals/authoring-cost-reduction.md`
**Status:** Draft proposal
**Date:** 2026-06-11
**Concerns:** [Q-021](../open-questions.md#Q-021) (dynamic cost), [Q-034](../open-questions.md#Q-034) (authoring layer), [Q-056](../open-questions.md#Q-056) (hand-declared builtin signatures), [Q-057](../open-questions.md#Q-057) (grammar parity), [`02-core-thesis.md`](../02-core-thesis.md) § outcome-priority, [`evaluation/dynamic-results.md`](../evaluation/dynamic-results.md)
**Scope:** medium (four independent measures, individually small to medium)

This proposal defines the near-term program for bringing Strand's tokens-per-successful-task within a practical multiple of conventional baselines, inside the existing Layer A surface. It is the engineering counterpart to the thesis position that inference cost is a constraint to be bounded, not a headline claim. The strategic alternative — replacing the agent-facing surface entirely — is the separate [`familiar-surface-lowering.md`](familiar-surface-lowering.md) (Q-061); the two tracks are independent and the measures here remain useful under either surface.

## 1. Problem statement

Run 7 of the dynamic-cost measurement ([`evaluation/dynamic-results.md`](../evaluation/dynamic-results.md), all figures byte-proxy) puts Strand at 28,092 tokens per successful task against 1,792 for Python and 1,255 for Kotlin — a 15.7–22.4× multiple. The decomposition shows the cost is not in the language's emissions: per-emission output is already smaller than Python's, and the static measurement has Layer A density v4 at 0.81× Python. The cost concentrates in two places. First, the agent-facing system prompt (`evaluation/dynamic/prompts/strand-system.md`, 1,858 lines, ~86 KB, roughly 21,500 token-equivalents) is re-sent on every attempt; Python's equivalent is ~1,500 tokens because the model already knows Python. Second, retries re-send that prompt in full: in Run 6, four retry-affected cells accounted for roughly 83,000 tokens, and the dominant retry cause was a Layer A grammar slip, not a semantic error.

The bounded-cost constraint in [`02-core-thesis.md`](../02-core-thesis.md) § outcome-priority names prompt caching and skill-mediated emission as the bounding mechanisms; neither is implemented in the headline measurement path. This proposal makes the constraint operational.

## 2. Measures

Four measures, ordered by leverage per unit of effort. Each carries an acceptance gate measured through the Q-021 harness, which as of 2026-06-11 supports N>1 sampling with bootstrap confidence intervals and source-labeled token counts.

### 2.1 M-1: Prompt caching in the measurement harness

Wrap the system prompt (and the static task preamble) in a provider cache block in the `strand-eval` Anthropic backend. Cache reads price at one tenth of uncached input; cache writes at 1.25×. At N=5 samples per cell the projected per-task input cost falls by roughly two thirds; across a 22-task run sharing one system prompt, total input cost falls by roughly 80 percent. The infrastructure was sketched in [`model-api-integration.md`](model-api-integration.md) and never wired into a headline run.

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

A third candidate — replacing prelude names with short content-hash references — is deferred: it saves little, costs agent legibility, and is superseded by [`implemented/prelude-as-module.md`](implemented/prelude-as-module.md) making the prelude addressable properly.

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

- **Fine-tuning and tokenizer alignment** — Phase 4 per [`research-plan.md`](../research-plan.md) and Q-034 § 3.3; this program is the pre-fine-tuning bound.
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

## Implementation progress

Two of the four measures landed on 2026-06-12; the proposal remains a draft until M-2 and M-4 complete and the Q-060 gates produce measured figures.

**M-1 landed (commit "Q-060 M-1").** The `strand-eval` Anthropic backend sends two ephemeral `cache_control` breakpoints — the system prompt block and the static task preamble (first user message) — so retries within a cell reuse the full prompt prefix at cache-read rates and cells sharing one system prompt reuse the system prefix. Cache accounting flows end to end under distinct labels: the API usage fields (`cache_read_input_tokens`, `cache_creation_input_tokens`) are recorded per attempt in both summary.json writers (run mode and step mode, the latter via `response-metadata.json` relay), cell totals are carried separately from uncached input, and the `aggregate`/`report` tables surface cache reads and writes as their own columns plus a hit rate. Byte-proxy sessions record zeros — neither counting fallback can observe cache behavior, and the harness never fabricates cache figures. Cost estimation prices cache traffic at its own rates (0.1x read / 1.25x write). Validation was by mocked-client and fixture tests; the cached N=5 measurement run itself remains the section 2.1 gate to execute.

**M-3 landed at the grammar-rejection-test level (commit "Q-060 M-3").** No hosted constrained-decode backend is available, so the section 2.3 gate ships as its documented fallback: `ConstraintGrammarSlipGateTest` in the `:authoring` module drives a minimal fully-backtracking GBNF matcher over the grammar `strand grammar` emits and proves the historical slip form (`APP fn args _` in either optional list slot, including the skip-middle variant) is not derivable, while the bracketed-list and omission forms are and `_` remains derivable at genuine nullable slots. A structural pin on the generated `node_APP`/`optional_APP` rules backs the matcher. One factual correction surfaced during implementation: the post-Run-6 parser change accepts the slip as sugar for `[]` rather than rejecting it, so the regression pin asserts sugar-equivalence (slip compiles byte-identically to the explicit-`[]` form) — the two layers together close the Run 6 retry driver from both sides. Hosted constrained decoding remains pending provider support; the grammar artifact is ready.

## References

**Outgoing references:**
- [`evaluation/dynamic-results.md`](../evaluation/dynamic-results.md) — the Run 6/7 cost decomposition this program targets
- [`02-core-thesis.md`](../02-core-thesis.md) — the bounded-cost constraint made operational here
- [`proposals/model-api-integration.md`](model-api-integration.md) — the harness this extends
- [`proposals/familiar-surface-lowering.md`](familiar-surface-lowering.md) — the strategic alternative track
- [`proposals/implemented/prelude-as-module.md`](implemented/prelude-as-module.md) — supersedes the hash-indexing candidate
- [`open-questions.md`](../open-questions.md) — Q-021, Q-034, Q-056, Q-057

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-060 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section
- [`ROADMAP.md`](../ROADMAP.md) — Tier 1.5
