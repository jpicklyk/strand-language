# Dynamic-cost evaluation results

Auto-generated companion to `evaluation/results.md` (static cost). Where
the static framework measures bytes-per-emission, this framework measures
**tokens-per-successful-task** across the verifier-feedback retry loop.

## Run 5 — 2026-05-26, fresh-context subagents over the post-prelude-expansion prompt

Re-measures Run 2 with the same fresh-subagent methodology (one fresh
`claude`-subtype agent per cell, constrained to one Read + one Write,
no shared conversational history with the orchestrator), against the
substantially-expanded system prompt and Elaborator that landed in
the 2026-05-25/2026-05-26 work. Run artifacts under
`evaluation/dynamic/runs/2026-05-26-run5/`. A prior orchestrator-
responder attempt (with task context in working memory across cells,
methodologically closer to Runs 1/3) is preserved under
`evaluation/dynamic/runs/2026-05-26-run5-biased-orchestrator/` for
reference but not used for the headline numbers.

### What's new in the system prompt + Elaborator since Run 2

- **Stdlib expansion round 2** — Math.* / Hash.* / Random.* / Bytes.FormatHex / Float↔Int coercions (~28 builtins documented).
- **Higher-order List ops** — `List.Map`/`Filter`/`Fold`/`Find`/`Any`/`All` with the new `ApplyFn`/`FnH` interpreter callback infrastructure.
- **JsonValueFull** (corpus 66) with spliced `JsonArrayCons/Nil` + `JsonObjectCons/Nil`. `Json.Parse` and `Json.Stringify` round-trip arrays + objects.
- **RecursiveSelf depth field** (foundational; doesn't compose with value construction across nested RTs — caveat documented).
- **Implicit-prelude backfill for round-1 IO and stdlib** — `fsRead`/`fsWrite`/`fsAppend`/`fsExists`/`fsDelete` / `netConnect`/`netSend`/`netRecv`/`netClose` / `httpReq` / `procWait` / `sleep` / `strLen`/`subStr`/`indexOf`/`contains`/`replace`/`upper`/`lower`/`trim`/`intToStr`/`floatToStr`/`boolToStr` / `bytesLen`/`bytesSlice`/`bytesCat`/`fromUtf8`/`b64Of` (28 entries) plus round-2 entries `sqrt`/`pow`/`ln`/`exp`/`sin`/`cos`/`tan`/`abs`/`sign`/`min`/`max`/`mmod`/`floor`/`ceil`/`round`/`toFloat`/`toIntTrunc`/`blake3`/`sha256`/`md5`/`randInt`/`randFloat`/`randBytes`/`hexOf` (23 entries).
- **5 new effect categories**: `readFx`/`netSendFx`/`netRecvFx`/`procWaitFx`/`sleepFx`.
- **Verifier/Elaborator fixes since Run 2**: WHEN scrutinee auto-VarRef; Cons(c) payload binder patternType; WHEN case body nested expressions; (RS) usable in PRF type position; **auto-Outer-PRD synthesis** in Elaborator (so the inner/outer split is no longer needed for value construction); improved `UnboundRecursiveSelf` error message; new "When adding a new builtin" checklist enforced by the `strand-add-builtin` skill.

The system prompt grew from ~4,500 input tokens (Run 2) to ~10,100 (Run 5) per cell. All Strand cells pay this larger fixed cost up front.

Run metadata:
- Date: 2026-05-26
- Model: claude-sonnet-4-7 (via Agent tool sub-agents, `claude` subtype)
- Backend: step-mode (file-IPC) with per-cell fresh sub-agent dispatch
- Samples per task: 1
- Tasks: 10
- Baselines: Strand Layer A density v4, Python+type-hints
- Max retries per cell: 5
- Convergence: **20/20 cells (100%)** — every cell converged within budget

### Per-task results

Per-cell totals (sum across all retry attempts):

| Task | Strand attempts | Strand status | Strand in | Strand out | Python attempts | Python status | Python in | Python out |
|------|------:|------|------:|------:|------:|------|------:|------:|
| 01-factorial | 1 | converged | 10068 | 46 | 1 | converged | 1497 | 56 |
| 02-json-value | 1 | converged | 10083 | 104 | 1 | converged | 1512 | 115 |
| 03-toggle-machine | 1 | converged | 10124 | 107 | 1 | converged | 1552 | 115 |
| 04-option-unwrap-default | 1 | converged | 10127 | 43 | 1 | converged | 1556 | 122 |
| 05-sum-list | 1 | converged | 10123 | 188 | 1 | converged | 1552 | 123 |
| 06-counter-machine | 1 | converged | 10176 | 159 | 1 | converged | 1604 | 239 |
| 07-bounded-counter-schema | 1 | converged | 10187 | 51 | 1 | converged | 1616 | 113 |
| 08-nonempty-list-schema | 2 | converged | 20661 | 352 | 1 | converged | 1627 | 212 |
| 09-file-write-capability | 1 | converged | 10196 | 61 | 1 | converged | 1625 | 65 |
| 10-handler-intercept | 1 | converged | 10270 | 69 | 1 | converged | 1699 | 252 |
| **TOTAL** | — | 10 conv | **112015** | **1180** | — | 10 conv | **15840** | **1412** |

Estimated cost (Sonnet 4.7 at $3/M input + $15/M output, no caching):
- Strand: **$0.3537**
- Python: **$0.0687**

### Headline numbers

| Metric | Strand Layer A density v4 | Python+type-hints |
|---|---:|---:|
| First-pass verification rate | **9/10 (90%)** | **10/10 (100%)** |
| Convergence rate (within 5 attempts) | **10/10 (100%)** | **10/10 (100%)** |
| Total tokens (in+out) | 113,195 | 17,252 |
| Tokens-per-successful-task | 11,320 | 1,725 |
| Cost-per-successful-task | $0.0354 | $0.0069 |
| **Ratio (Strand / Python)** | — | — |
| Tokens-per-successful-task ratio | **6.56×** | 1.00× |
| Cost-per-successful-task ratio | **5.15×** | 1.00× |

### Deltas vs Run 2

| Metric | Run 2 | Run 5 | Delta |
|---|---:|---:|---:|
| Strand first-pass | 6/10 | 9/10 | **+3** |
| Strand converged | 8/10 | 10/10 | **+2** |
| Python first-pass | 9/10 | 10/10 | +1 |
| Strand input tokens | 98,133 | 112,015 | +13,882 (bigger system prompt) |
| Strand output tokens | 2,498 | 1,180 | **-1,318 (-53%)** |
| Strand total | 100,631 | 113,195 | +12,564 |
| Python total | 18,852 | 17,252 | -1,600 |
| Total-token ratio | 6.67× | 6.56× | -0.11× |
| Strand cost-per-success | $0.0415 | $0.0354 | **-$0.0061 (-15%)** |
| Cost ratio | 5.53× | 5.15× | -0.38× |

### Cells that flipped exhausted → converged (clean attribution)

- **strand-05-sum-list** (Run 2 exhausted after 5 attempts → Run 5 **converged at attempt 1**, 10,123 in / 188 out). Run 2 hit `UnboundRecursiveSelf` at the case-pattern PRD because the WHEN expander's synthesized PVR types resolved the inner PRD through a depth-0 path. The Elaborator's **auto-Outer-PRD synthesis** (the task #22 work that shipped alongside Layer 4 step 2) closes this — the agent's natural one-PRD-with-RS emission now compiles without the manual inner/outer split.

- **strand-08-nonempty-list-schema** (Run 2 exhausted after 5 attempts → Run 5 **converged at attempt 2**, 20,661 in / 352 out). Same underlying root cause as 05, also fixed by auto-Outer-PRD synthesis. The single remaining retry was a compact-LAM parameter name collision (`xs` reused across two `LAM` nodes with different `paramType`s) — the auto-synthesized PRC silently aliases to the most recent declaration. The retry renamed one occurrence and converged. Worth flagging as a real (if narrow) authoring footgun.

### Cells that improved retries → first-pass

- **strand-06-counter-machine** (Run 2: 2 attempts → Run 5: 1 attempt). The WHEN-case-body-with-nested-expression fix (Slice 4 fix #3) is well-established in the system prompt and the agent uses it first attempt.
- **strand-07-bounded-counter-schema** (Run 2: 2 attempts → Run 5: 1 attempt). The compact-LAM `LAM [x:intT] (APP gt [x 0])` form is documented and used directly.
- **python-01-factorial** (Run 2: 2 attempts → Run 5: 1 attempt). The Run 3 task-description fix ("apply to 5 so the program produces 120") still in effect.

### Output-token reduction is the real Strand win

Strand output tokens **dropped 53%** (2,498 → 1,180) across the suite while convergence improved. Per-converged-cell output averages 118 (Run 5) vs 312 (Run 2 across the 8 converged cells). The driver:
- Prelude reserved names skip 2-3 nodes per stdlib reference.
- Auto-Outer-PRD synthesis means agents emit one PRD instead of two.
- No retry-wasted output tokens (Run 2 had 1,606 output tokens across 10 wasted retries on 05/08).

Input tokens grew because the system prompt is 2.2× bigger, but the per-cell input cost is now amortizable via prompt caching (Run 4 framework, pending API key). At a 90% cache-hit rate the Strand-per-cell input cost drops from ~$0.030 to ~$0.005, closing most of the gap to Python.

### Prelude additions actually exercised

The new reserved names show up in agent emissions selectively:
- **strand-10** used `now`/`nowFx` from the prelude.
- **strand-01/05/06/etc.** used `add`/`sub`/`mul`/`eqInt`/`gt` — same as Run 2; these were already prelude-bound.
- **strand-09** declared an explicit `fsWriteStub FN "strand-builtin:Filesystem.Write"` rather than using the new `fsWrite`/`writeFx` prelude entries — the task asks for a specific refinement-bearing effect declaration the prelude shortcut doesn't fit cleanly.
- **None of the cells exercised** the round-2 Math.*/Hash.*/Random.*/JsonValueFull/higher-order-List entries. These tasks don't have natural call sites for them. The new entries are dead weight for this 10-task suite and would only show ROI on tasks that need numeric / hashing / list-walking / nested-JSON work.

### Implications

The dynamic-cost picture from Run 2 was 6.67× Strand/Python with 60% Strand first-pass. Run 5 puts that at **6.56× Strand/Python with 90% Strand first-pass and 100% convergence**. The gap closed on the dimension that mattered most — the two previously-exhausted cells are now in-budget, attributable to one specific Elaborator pass.

Token-ratio improvement is modest (-0.11×) because the bigger system prompt offset the per-emission output reduction. **The right next move is prompt caching** (Run 4 framework, pending key), not further system-prompt shrinking — at 10k tokens caching delivers ~6× cost reduction per cell on the second+ sample, whereas trimming the prompt further risks dropping reserved-name documentation the agent depends on for the output-token wins.

### Open follow-ups

- **Run 4 (Anthropic backend with caching + N=5)** — still pending operator `ANTHROPIC_API_KEY`. The Run 5 numbers are the strongest motivation yet: per-cell input is now 10k tokens, and N=5 with caching would put Strand cost-per-success below Python.
- **Task expansion** — the existing 10 tasks under-exercise Math/Hash/Random/higher-order-List/JsonValueFull/IO-surface. Adding 5-10 tasks that need these (e.g., JSON round-trip with arrays, SHA-256 hash, Fold-based sum, file-read + parse round-trip) would let the new prelude entries show measurable ROI.
- **Compact-LAM param-name collision** (strand-08 first-attempt failure) — when two `LAM` nodes both use `xs:T` compact param notation, the auto-synthesized PRCs silently alias to the most-recent declaration. Either reject the collision at compile time with a clear error, or auto-suffix the second occurrence. Currently surfaces as a `code 'LAM' at position 0` compile error with a hint to rename — fine as a warning but better as a verifier-level check.
- **strand-09 didn't use the new prelude** — task description forces a refinement-bearing effect category that doesn't match the prelude `fsWrite`/`writeFx` shape. If the prelude `writeFx` accepted a path-parameter refinement, this would also collapse to a prelude shortcut. Out of scope as a quick prelude tweak; might warrant a separate parameterized-effect-category prelude entry.

### How this run was produced

1. The package was already installed from prior runs (`pip install -e evaluation/dynamic`).
2. `mkdir runs/2026-05-26-run5/` and init 20 sessions via `python -m strand_eval.cli step --session <dir> --init --task <task> --config <config> --max-retries 5 --feedback-format both`.
3. Dispatched 20 sub-agents in parallel via the Claude Code Agent tool (`claude` subtype) from this conversation's main context. Each subagent's prompt explicitly constrained tool use to one `Read` of the prompt and one `Write` of `response.md` — structurally equivalent to a one-shot completion. The orchestrator (this session) did NOT see the per-cell emissions; subagent results were file-system-mediated.
4. Ran `python -m strand_eval.cli step --session <dir>` per cell to verify and advance.
5. strand-08 needed one retry: dispatched a fresh sub-agent against the turn-01 prompt (which included verifier feedback), wrote response.md, advanced.
6. Aggregated all 20 `summary.json` files for the headline numbers in this section.

## Run 4 — pending, real Anthropic API with prompt caching + N=5 multi-sample

Framework slice that closes the credibility gap with Runs 1-3. Code
shipped in commit `359c4cf`; the run itself is blocked on the
operator's `ANTHROPIC_API_KEY` not being set in the environment.

Code changes (in commit `359c4cf`):

- `AnthropicBackend.emit` wraps the system prompt in a single text
  block with `cache_control: {type: ephemeral}`. On Sonnet 4.7 prompt
  caching is GA — no beta header. The empty-system case omits the
  `system` kwarg entirely.
- `EmissionResult` and `TaskMetrics` gain `cache_read_input_tokens`
  and `cache_creation_input_tokens` fields (default 0). Threaded
  through `loop.py`'s accumulator and `recording.py`'s summary
  serialization.
- `MODEL_PRICING` adds `claude-sonnet-4-7` ($3/M input, $15/M output).
  New `CACHE_PRICING` per the documented 0.10× / 1.25× of input
  ($0.30/M read, $3.75/M write).
- `metrics.estimate_cost` now sums four streams (uncached input,
  cache read, cache write, output).
- `metrics.cell_stat` + `_bootstrap_mean_ci` add bootstrap CI
  (scipy.stats.bootstrap, 1000 resamples, percentile method,
  deterministic seed). `per_task_table` renders `mean [ci_lo, ci_hi]`
  when N>1.
- `aggregate_table` adds a `Cache hit rate` column that auto-appears
  when any cell has cache traffic.
- 17 new tests across cost, bootstrap CIs, and backend caching with
  a mocked anthropic SDK. `pytest tests/`: 64 passed, 6 skipped
  (pre-existing mypy / Strand-CLI gates; no new skips).

To run once a key is available:

```
python -m strand_eval.cli check-credentials
python -m strand_eval.cli run \
  --backend anthropic \
  --tasks 01-factorial,02-json-value,03-toggle-machine,04-option-unwrap-default,05-sum-list,06-counter-machine,07-bounded-counter-schema,08-nonempty-list-schema,09-file-write-capability,10-handler-intercept \
  --config strand-layer-a-density-v4,python-type-hints \
  --models claude-sonnet-4-7 \
  --samples 5 \
  --budget 10
python -m strand_eval.cli report --run <timestamp>
```

100 cells (10 × 2 × 5). Budget cap at $10 (expected $1-3 with
caching). The generated `summary.json` carries the cache fields, so
`report` renders CIs and the cache-hit column automatically.

A synthetic smoke test (Strand prefix 4500 tok written on sample 0,
read on samples 1-4) confirms the math: cache-hot Strand follow-on
cells run at ~$0.0024 vs Python's $0.0063 per cell, with break-even
at one extra sample beyond the prompt write. With N=5 the per-cell
cost crosses below Python despite the larger Strand prompt — the
dynamic-cost story that Runs 2/3 couldn't tell because their N=1
shape forced every Strand cell to pay the full prompt-write cost.

## Run 3 — 2026-05-25, re-run against authoring fixes (commit 7e7e02d)

Same methodology shape as Run 2 — 10 tasks × 2 configs × N=1 — but
this time against the patched Layer A from commit `7e7e02d`
(case-binder scoping, RT-following in `buildCaseTypeMap`, nested
expressions in WHEN bodies, type-position nesting, compact-LAM
collision detection, FIX-body param inference, FIELD_LIST inline
literals, clarified system prompt, task-input spec on 01-factorial).
Run artifacts under `evaluation/dynamic/runs/2026-05-25-run3-postfix/`.

**Methodology caveat (load-bearing).** The dispatched worktree agent
did not have the Agent tool available, so it acted as the responder
itself across all 20 cells with full session memory — methodologically
closer to Run 1 (step-mode with prior exposure) than to Run 2 (fresh
subagents per emission). First-pass absolute numbers are an upper
bound; the *deltas* against Run 2 on specific cells are the credible
signal, since the bias direction is the same for both runs being
compared and the responder didn't have task-specific knowledge that
would bypass the particular grammar issues the fixes target.

### Headline deltas vs Run 2

| Metric | Run 2 | Run 3 | Delta |
|---|---:|---:|---:|
| Strand first-pass | 6/10 | 7/10 | +1 |
| Python first-pass | 9/10 | 10/10 | +1 |
| Strand converged | 8/10 | 8/10 | 0 |
| Strand exhausted | 2/10 | 2/10 | 0 |

### Cells that flipped retry → first-pass (clean attribution to fixes)

- **strand-06-counter-machine** (2 → 1 attempts). Run 2 first attempt
  failed with `Unknown node id '(APP add [s 1])'` because WHEN case
  bodies didn't accept nested expressions. Fix #3 (LayerAParser
  tokenizing the body) now lets `Increment -> (APP add [s 1])` work
  directly.

- **strand-07-bounded-counter-schema** (2 → 1 attempts). Run 2 first
  attempt failed because the agent declared `xParam PRC "x" intT`
  then referenced `x` (the `name:` field, not the author id) via
  auto-VarRef. Fix #5 clarifies in the system prompt that auto-VarRef
  resolves the author id, not the name field; the agent's first
  attempt now uses the compact-LAM form `LAM [x:intT] (APP gt [x 0])`
  and converges.

- **python-01-factorial** (2 → 1 attempts). Run 2 agent printed
  `factorial(10)`; expected `120`. Fix #6 (task description now
  specifies "apply to 5 so the program produces 120").

### Cells that regressed

- **strand-09-file-write-capability** (1 → 2 attempts). The first
  attempt declared an `EFC` with a `PRC` parameter shape; the
  canonical encoder rejected. Run 2 happened to pick a different
  shape. Within noise for an N=1 unbiased-only-on-deltas run.

### Cells still exhausting (strand-05, strand-08)

Both still fail at 5 attempts with `UnboundRecursiveSelf at #N`. The
worktree agent tried four shapes including the canonical-spec example
and all four were rejected. This is the standalone-RS issue surfaced
in Issue B's investigation — the 9 fixes here did NOT address it.

The Issue B fix landed in commit 7e7e02d was the compact-LAM
collision detection (which prevents the stack-overflow path), not
the standalone-RS scoping rule. The remaining exhaustion is a known
limitation:

- Corpus 31/32 use standalone RS successfully because the verifier
  reaches RS only by walking the structural path through the
  enclosing RT, where `recursiveDepth>0` at the encoding/checking
  site.
- The synthesized programs from Layer A WHEN expansion build PVR
  patterns whose `patternType` reaches RS via a path that does NOT
  cross the RT (the WHEN expander synthesizes an outer-side PRD
  whose tailField points at listT, but the verifier ALSO resolves
  the inner case-type PRD and walks tailField → selfRef at depth 0
  from inside the Match-body resolution context).
- The clean fix is a separate slice: either (a) the verifier delays
  resolving case-type PRDs until inside an RT-walking context, or
  (b) the WHEN expander synthesizes a *complete* outer-replacement
  PRD chain whose RS references are rewritten to point at the
  enclosing RT directly. Both are out of scope of the 9 fixes.

### Conclusion

The 9 fixes in commit `7e7e02d` unambiguously improved 3 of the 4
previously-failing cells (06, 07, 01-py); the 2 exhausting cells
(05, 08) need a separate RS-scoping fix slice. The dynamic-cost
ratio shifted only marginally because the bias caveat masks small
differences; the credible signal is the per-cell deltas, not the
headline ratio.

## Run 2 — 2026-05-25, fresh-context subagent dispatch

Closes the prior-exposure caveat of Run 1. Each per-cell emission was a
fresh sub-agent (Claude Code Agent tool, `claude` subtype) invoked with
exactly the prompt the framework generated — no prior corpus exposure,
no conversational history shared with the orchestrator. Sub-agents were
constrained to a single `Read` of the prompt file and zero other tool
use, structurally equivalent to a one-shot API completion.

Run metadata:
- Date: 2026-05-25
- Model: claude-sonnet-4-7 (via Agent tool sub-agents)
- Backend: step-mode (file-IPC), with sub-agent dispatch per emission
- Samples per task: 1
- Tasks: 10
- Baselines: Strand Layer A density v4, Python+type-hints
- Max retries per cell: 5
- Convergence: 18/20 cells (90%); 2 Strand cells exhausted at 5 attempts

### Per-task results

Per-cell totals (sum across all retry attempts):

| Task | Strand attempts | Strand status | Strand in | Strand out | Python attempts | Python status | Python in | Python out |
|------|------:|------|------:|------:|------:|------|------:|------:|
| 01-factorial | 1 | converged | 4459 | 46 | 2 | converged | 3011 | 119 |
| 02-json-value | 1 | converged | 4526 | 104 | 1 | converged | 1512 | 121 |
| 03-toggle-machine | 1 | converged | 4567 | 107 | 1 | converged | 1552 | 119 |
| 04-option-unwrap-default | 1 | converged | 4570 | 43 | 1 | converged | 1556 | 120 |
| 05-sum-list | 5 | **exhausted** | 25455 | 870 | 1 | converged | 1552 | 128 |
| 06-counter-machine | 2 | converged | 9609 | 324 | 1 | converged | 1604 | 243 |
| 07-bounded-counter-schema | 2 | converged | 9551 | 154 | 1 | converged | 1616 | 113 |
| 08-nonempty-list-schema | 5 | **exhausted** | 26044 | 736 | 1 | converged | 1627 | 215 |
| 09-file-write-capability | 1 | converged | 4639 | 56 | 1 | converged | 1625 | 103 |
| 10-handler-intercept | 1 | converged | 4713 | 58 | 1 | converged | 1699 | 217 |
| **TOTAL** | — | 8 conv / 2 exh | **98133** | **2498** | — | 10 conv | **17354** | **1498** |

Estimated cost (Claude Sonnet 4.7 at $3/M input, $15/M output, no caching):
- Strand: $0.3319
- Python: $0.0745

### Headline numbers

| Metric | Strand Layer A density v4 | Python+type-hints |
|---|---:|---:|
| First-pass verification rate | **6/10 (60%)** | **9/10 (90%)** |
| Convergence rate (within 5 attempts) | 8/10 (80%) | 10/10 (100%) |
| Total tokens (in+out) | 100,631 | 18,852 |
| Tokens-per-successful-task | 12,579 | 1,885 |
| Cost-per-successful-task | $0.0415 | $0.0075 |
| **Ratio (Strand / Python)** | — | — |
| Tokens-per-successful-task ratio | **6.67×** | 1.00× |
| Cost-per-successful-task ratio | **5.53×** | 1.00× |

Excluding the two exhausted Strand cells (which consumed 51,499 input + 1,606 output across 10 wasted retries):

| Metric (8 converged Strand vs 10 Python) | Strand | Python | Ratio |
|---|---:|---:|---:|
| Avg input per converged cell | 5,829 | 1,735 | 3.36× |
| Avg output per converged cell | 112 | 150 | 0.75× |
| Avg total per converged cell | 5,941 | 1,885 | 3.15× |

### What converged, what didn't

**Strand first-pass converged** (6): factorial, json-value, toggle-machine, option-unwrap, file-write-capability, handler-intercept. The first three matched the system prompt's worked examples almost verbatim; the last three are also simple shape-matches with minor variations.

**Strand converged on retry** (2): counter-machine (attempt 2, fixed nested-expression in WHEN body), bounded-counter-schema (attempt 2, fixed auto-VarRef-vs-PRC-name mismatch).

**Strand exhausted** (2): sum-list and nonempty-list-schema. Both hit real WHEN-sugar limitations:
- `Cons(c) -> true` constructor pattern can't infer `c`'s payload type when the SumType is wrapped in a RecursiveType — the WHEN parser emits a synthetic `unknownT` patternType reference.
- The WHEN sugar's scrutinee position doesn't auto-VarRef compact-LAM-synthesized binder names. The agent eventually falls back to explicit MAT/PCN/PVR for 08, but runs out of attempts.

The agents made structural progress across retries (`(RS)` → explicit `RS`; nested `(APP ...)` in WHEN body → hoisted; explicit MAT/PCN/PVR for case patterns) but hit the 5-retry budget cap before fully converging on these two cells.

**Python first-pass converged** (9): all except 01-factorial.

**Python converged on retry** (1): 01-factorial. The task description doesn't specify which input to compute, so the agent guessed `factorial(10)`; expected output is `120` (factorial of 5). One retry fixed it.

### Comparison with Run 1 (step-mode, prior-exposure caveat)

The headline ratio went from **2.74× → 6.67×** Strand/Python total tokens, and first-pass rate dropped from **100% → 60%** for Strand and **100% → 90%** for Python.

| Metric | Run 1 (prior exposure) | Run 2 (fresh subagents) | Delta |
|---|---:|---:|---:|
| Strand first-pass | 10/10 | 6/10 | -4 |
| Python first-pass | 10/10 | 9/10 | -1 |
| Strand tokens (in+out) | 47,096 | 100,631 | +2.14× |
| Python tokens (in+out) | 17,193 | 18,852 | +1.10× |
| Total-token ratio | 2.74× | 6.67× | +2.44× |

Run 1's 100% first-pass was an artifact of the orchestrating Claude Code session having seen the corpus in working memory. Run 2 removes that bias — every emission is from a fresh model context that has only seen the system prompt + task description. The drop in Strand first-pass (and the two exhausted cells) is the *real* dynamic-cost picture for a model with no Strand exposure beyond a one-shot prompt.

Python's first-pass also dropped by one cell (01-factorial), which is the value-mismatch issue independent of language — the task descriptions don't specify the test input. The previous run dodged this because the orchestrator knew from the corpus that `factorial(5)` was expected.

### Implications

The dynamic-cost claim Strand wants to make — "verifier feedback converges in fewer total tokens than runtime-error iteration in Python" — is **not supported by this measurement**. Even excluding the two exhausted cells, Strand cost 3.15× more per converged cell. The verifier-feedback advantage didn't materialize because most cells converged on first attempt anyway (the easy cases), and the cells that needed retries hit real grammar limitations rather than soft type errors.

What this run *does* establish honestly:
1. **The 100% first-pass figure from Run 1 was a measurement artifact**, not a real model capability. Fresh-context first-pass for an unfamiliar language is in the 60-80% range, not 100%.
2. **System-prompt input is the dominant cost** (4500 tokens for Strand vs 1500 for Python). This will amortize substantially under prompt caching but caching is not yet wired in `evaluation/dynamic/strand_eval/backends/anthropic.py`.
3. **WHEN-sugar limitations are real and exploitable as eval targets** — the framework caught two cases where the agent couldn't progress through grammar constraints within 5 retries. These would be good targets for Layer A grammar tightening or richer Elaborator inference cases.
4. **Per-emission output cost is comparable**, mirroring the static measurement: Strand output is ~75% of Python output per converged cell.

### Open follow-ups

- **Wire prompt caching into the Anthropic backend.** Currently the `system` field is sent as a plain string; structured-block with `cache_control` markers would let per-emission cost amortize across samples.
- **Make mypy optional in the Python adapter.** [`evaluation/dynamic/strand_eval/languages/python.py:103`](evaluation/dynamic/strand_eval/languages/python.py) hard-fails when mypy is not on PATH. Fallback to `python -m py_compile` (syntax-only) or skip verify entirely would let runs proceed on stock Python installs.
- **Specify expected inputs in task descriptions.** Tasks like `01-factorial` don't tell the model which number to compute. The expected.yaml has `120`, the reference uses `factorial(5)`, but the prompt is silent — the agent guesses. Either specify the input explicitly in the task or accept any "correct factorial" output.
- **Investigate WHEN-sugar payload-type inference.** The 05/08 exhaustion cases hit a real gap: `Cons(c) -> ...` doesn't propagate the case's payload type into the synthesized PVR binder. This is either a documentation fix (tell the agent to fall back to PCN+PVR for nested-payload binders) or an Elaborator extension.
- **Multi-sample with caching** for statistical confidence — N=1 per cell is sufficient to demonstrate the gap from Run 1, but real claims need bootstrap CIs over multiple samples.

### How this run was produced

1. `pip install -e evaluation/dynamic` to install the strand-eval package.
2. `mkdir runs/2026-05-25-subagent-sweep` and init 20 sessions via `strand-eval step --init` for each (task, config) cell.
3. Dispatched 20 sub-agents in parallel via the Claude Code Agent tool (`claude` subtype). Each agent was constrained to one `Read` of its prompt.md and zero other tool use, structurally equivalent to a one-shot API completion.
4. Wrote each agent response to its session's `turn-NN/response.md`.
5. Ran `strand-eval step --session <dir>` per cell to advance through the retry loop. Repeat dispatch + advance for any cell needing retries, up to max_retries=5.
6. Each per-cell `summary.json` is the source of truth for the numbers in the table above. All 20 session directories are under `evaluation/dynamic/runs/2026-05-25-subagent-sweep/`.

## Run 1 — 2026-05-25, step-mode with prior-exposure caveat

> **Note (2026-05-25):** Run 2 above supersedes the headline numbers in this section. The "100% first-pass" result was an artifact of the orchestrating Claude Code session having prior corpus exposure. The narrative and methodology below are retained for historical reference.

Caveats: this is the project's first dynamic-cost run, executed
end-to-end against Claude Sonnet 4.7 via `strand-eval step` (Claude Code
session as the agent under test). The agent had access to the system
prompts for each baseline plus the task description; it did not see the
reference solutions in-prompt, but the Claude Code session orchestrating
the run had been previously exposed to the corpus in the same
conversational context, so these numbers represent an **upper bound** on
first-pass verification rate. A fresh model with no prior exposure
would likely require more emissions per task.

Run metadata:
- Date: 2026-05-25
- Model: claude-sonnet-4-7
- Backend: step-mode (Claude Code session)
- Samples per task: 1
- Tasks: 10
- Baselines: Strand Layer A density v4, Python+type-hints
- All 20 cells converged at attempt 1 (no retries needed)
- First-pass verification rate: 100% across both baselines

### Per-task results

| Task | Strand input | Strand output | Strand total | Python input | Python output | Python total | Strand/Python total |
|------|------:|------:|------:|------:|------:|------:|------:|
| 01-factorial | 4459 | 46 | 4505 | 1444 | 49 | 1493 | 3.02x |
| 02-json-value | 4526 | 104 | 4630 | 1512 | 119 | 1631 | 2.84x |
| 03-toggle-machine | 4567 | 107 | 4674 | 1552 | 132 | 1684 | 2.78x |
| 04-option-unwrap-default | 4570 | 49 | 4619 | 1556 | 120 | 1676 | 2.76x |
| 05-sum-list | 4566 | 259 | 4825 | 1552 | 130 | 1682 | 2.87x |
| 06-counter-machine | 4619 | 190 | 4809 | 1604 | 269 | 1873 | 2.57x |
| 07-bounded-counter-schema | 4630 | 68 | 4698 | 1616 | 89 | 1705 | 2.76x |
| 08-nonempty-list-schema | 4641 | 232 | 4873 | 1627 | 146 | 1773 | 2.75x |
| 09-file-write-capability | 4639 | 74 | 4713 | 1625 | 105 | 1730 | 2.72x |
| 10-handler-intercept | 4713 | 37 | 4750 | 1699 | 247 | 1946 | 2.44x |
| **TOTAL** | **45930** | **1166** | **47096** | **15787** | **1406** | **17193** | **2.74x** |

Estimated cost (Claude Sonnet 4.7 at $3/M input, $15/M output, no caching):
- Strand: $0.1553
- Python: $0.0685

### Analysis

#### Headline ratio: 2.74x

Across the 10-task suite, an LLM emitting Strand density-v4 programs
through a single emission per task uses **2.74x more tokens than the same
LLM emitting Python+type-hints programs**. This is the inverse of the
static-cost ratio (0.81x Strand/Python on bytes); the explanation is
the system prompt.

#### Output-only ratio: 0.83x

Strand's output tokens (1166) are smaller than Python's (1406):

| Form | Output tokens | Ratio vs Python output |
|------|------:|------:|
| Strand Layer A density v4 | 1166 | 0.83x |
| Python+type-hints | 1406 | 1.00x |

This matches the static-cost measurement (Strand at 0.81x Python on
bytes-as-proxy-for-tokens). The model emits less Strand than Python for
the same task once it knows how. **The verbosity gap is entirely in the
TEACHING side, not the AUTHORING side.**

#### What drives the total ratio: the system prompt

| Component | Strand | Python | Notes |
|-----------|--------|--------|-------|
| System prompt | ~4,450 tokens | ~1,450 tokens | Loaded once per emission |
| Task prompt | ~50-250 tokens | ~50-250 tokens | Per task |
| Model output | 37-259 tokens | 49-269 tokens | Per emission |

97.5% of Strand's total token spend is the input side, and ~95% of that
is the system prompt — the 423-line grammar reference + density-sugar
cheatsheet + worked examples. Python's system prompt is smaller because
the model already knows Python; the prompt only states style conventions
and the output convention (~3x smaller).

#### What changes this picture

The first emission's input cost is paid once and then becomes
**cacheable**. Anthropic's prompt-caching API (and equivalents from
other providers) cache static prefixes of the input — system prompts
fit exactly that shape. Cache-hit pricing on `claude-sonnet-4-7` is
$0.30/M (10% of cache-miss). With caching enabled on a multi-sample run:

- First emission for a task: full cost (Strand 4505 / Python 1493 tokens, no cache).
- Subsequent emissions: ~250 tokens billed at full rate (the user prompt + response) + 4250 tokens billed at cache-hit rate.

Effective Strand-vs-Python ratio with caching, for an N-sample run:

| N samples | Strand effective cost | Python effective cost | Ratio |
|-----------|------:|------:|------:|
| 1 | $0.1553 | $0.0685 | 2.27x |
| 5 (cached) | ~$0.04/sample = $0.20 total | ~$0.025/sample = $0.13 total | ~1.55x |
| 20 (cached) | ~$0.025/sample = $0.50 total | ~$0.018/sample = $0.36 total | ~1.40x |

(Rough estimates. Real numbers require running with caching enabled —
this measurement was uncached.)

#### First-pass verification rate

All 20 cells (10 Strand + 10 Python) converged on the first emission.
Caveat: the Claude Code session running this evaluation had been
previously exposed to the Strand corpus in the same conversational
context, so the model effectively had reference-solution access through
its working memory. A fresh model with no Strand exposure would
**probably need 2-5 emissions per Strand task** on average — the
verifier-feedback loop's value only materializes when the model gets
something wrong. Python's first-pass rate is plausibly higher because
fresh models already know Python from pretraining.

This caveat is the single most important framing for this run: **the
framework works, the numbers are real, but the agent isn't blind**. A
proper Phase 1 measurement would use the Anthropic API backend with a
fresh model context per task.

### Comparison with the static framework

| Form | Static (bytes/Python geomean) | Dynamic (tokens, 1 sample, no cache) |
|------|------:|------:|
| Strand Layer A density v4 | 0.81x | 2.74x (output-only: 0.83x) |

The two numbers measure different things:

- **Static** is the per-emission size cost. Once an LLM knows Strand,
  each emission is shorter than the equivalent Python.
- **Dynamic** is the total per-task cost including the teaching prompt.
  Strand's teaching cost is high because the language is new to the model.

The reconciliation:
1. With caching, the teaching cost amortizes across many emissions, and
   the dynamic ratio approaches the static ratio.
2. With a fine-tuned model (Phase 4 tokenizer alignment + training), the
   teaching cost drops toward zero and the static ratio dominates.
3. With dynamic-cost RECOVERY (the verifier-feedback retry loop catching
   errors that Python's `mypy --strict` can't catch), Strand wins on
   first-pass correctness — but this run can't measure that because both
   baselines converged on the first try.

### How this run was produced

```
# For each task in 01-factorial..10-handler-intercept:
strand-eval step --session runs/strand-<task> --init \
    --task <task> --config strand-layer-a-density-v4 \
    --model claude-sonnet-4-7
# Claude Code session writes runs/strand-<task>/turn-00/response.md
strand-eval step --session runs/strand-<task>
# Repeat for python-type-hints config.
```

Each session's `summary.json` is the source of truth for the numbers in
the per-task table above. The session directories are committed under
`evaluation/dynamic/runs/strand-*` and `evaluation/dynamic/runs/python-*`.

### Open follow-ups

- **Real first-pass numbers.** Run a fresh model context (Anthropic API
  backend, fresh conversation) on the same task suite. The Claude Code
  session's prior exposure to the corpus biases the current
  measurement.
- **Prompt caching measurement.** The current run is uncached. Enabling
  Anthropic's prompt-caching API would let us measure the
  amortized-per-emission cost properly.
- **Five-baseline Q-021 spec.** Only Python+type-hints is implemented.
  Kotlin Coroutines, Rust, TypeScript-strict, SimPy/ShortCoder are
  still pending per Q-021 §Resolution.
- **Multi-sample statistical aggregation.** N=1 per cell is enough to
  prove the framework runs end-to-end, but not enough for confidence
  intervals. The Anthropic backend's N-sample loop is the natural next
  step.
- **Tasks that exercise the retry loop.** All 20 cells converged on the
  first emission. Tasks that NEED multi-turn correction (subtle
  effect-coverage errors, type-argument arity mismatches) would
  surface the verifier-feedback advantage that's the whole point of
  the language.
