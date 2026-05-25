# Dynamic-cost evaluation results

Auto-generated companion to `evaluation/results.md` (static cost). Where
the static framework measures bytes-per-emission, this framework measures
**tokens-per-successful-task** across the verifier-feedback retry loop.

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

## Per-task results

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

## Analysis

### Headline ratio: 2.74x

Across the 10-task suite, an LLM emitting Strand density-v4 programs
through a single emission per task uses **2.74x more tokens than the same
LLM emitting Python+type-hints programs**. This is the inverse of the
static-cost ratio (0.81x Strand/Python on bytes); the explanation is
the system prompt.

### Output-only ratio: 0.83x

Strand's output tokens (1166) are smaller than Python's (1406):

| Form | Output tokens | Ratio vs Python output |
|------|------:|------:|
| Strand Layer A density v4 | 1166 | 0.83x |
| Python+type-hints | 1406 | 1.00x |

This matches the static-cost measurement (Strand at 0.81x Python on
bytes-as-proxy-for-tokens). The model emits less Strand than Python for
the same task once it knows how. **The verbosity gap is entirely in the
TEACHING side, not the AUTHORING side.**

### What drives the total ratio: the system prompt

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

### What changes this picture

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

### First-pass verification rate

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

## Comparison with the static framework

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

## How this run was produced

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

## Open follow-ups

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
