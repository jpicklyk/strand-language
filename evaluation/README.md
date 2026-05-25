# Evaluation framework (Q-021)

This directory hosts the empirical-measurement framework for the open
research questions Q-021 (evaluation metrics and baselines) and Q-034
(authoring-layer token-cost validation). It is **deliberately outside
`impl/`** because it spans multiple languages (Python, Strand canonical
dag-json, Strand Layer A) and is not part of the reference runtime; the
proposal at [`proposals/implemented/llm-authoring-layer.md`](../proposals/implemented/llm-authoring-layer.md)
§7 places this work in Phase 1.

The MVP shipped here covers: 3 tasks × 3 baselines × 3 measurable
metrics. Real model-API integration (tokens-per-successful-task across
the agent's retry loop, first-pass verification rate) is a follow-up
that needs API access to GPT/Claude/etc.; this MVP measures the
**static** per-emission cost of each representation. The dynamic cost
(retry economics under verifier feedback) needs the static cost as its
baseline anyway.

## Layout

```
evaluation/
├── README.md                  this file
├── tasks/
│   ├── 01-factorial/          pure recursion + Match + Fixpoint
│   ├── 02-json-value/         schema-typed value construction
│   └── 03-toggle-machine/     state machine transition function
├── measure.sh                 produces results.md from the task files
└── results.md                 auto-generated measurement report
```

Each task directory has:
* `description.md` — natural-language statement of what the program does
* `reference.python.py` — hand-authored Python+type-hints reference
* `reference.layer-a` — corresponding Strand Layer A form (the
  proposed LLM-emission target)
* `reference.canonical-json.json` — corresponding canonical dag-json
  form (today's verifier input)

The three Strand forms are taken verbatim from the existing corpus
where possible (so the measurement matches what the reference
implementation actually verifies).

## Metrics

| Metric | What it measures | How |
|--------|------------------|-----|
| **bytes** | Raw on-disk size of the source | `wc -c` |
| **lines** | Line count (a proxy for human-readable footprint) | `wc -l` |
| **tokens (est.)** | Approximate BPE-tokenizer count | `bytes / 4` (rule-of-thumb GPT/Claude tokenizer ratio) |

The token estimate is intentionally crude — real tokenizer-specific
counts vary by model family (GPT-4 vs Claude vs CodeLlama have
different vocabularies and ratios). Reporting one number with a clear
caveat is more honest than reporting per-tokenizer numbers under the
implication they generalize. The Phase 1 follow-up replaces this
heuristic with API-driven tokenization once a model integration is
available.

## What this MVP doesn't measure

These need a working model integration and are tracked as Phase 1
follow-up:

* **First-pass verification rate** — how often a model's emission
  verifies on the first try (per baseline + configuration). Requires
  running an actual model against the prompts.
* **Tokens-per-successful-task** — the integral over the agent's retry
  loop. The static per-emission cost reported here is the input; the
  retry rate is the multiplier.
* **Wall-clock latency** — decoding + elaboration + verification time.
* **Elaboration gap rate** — proportion of Layer A emissions where the
  elaborator can't resolve some annotation.

The static cost numbers reported here are necessary but not sufficient
for the full Q-034 resolution. They tell us "how big each form is";
they don't tell us "how often each form succeeds." Together they
determine "tokens per successful task" — Q-021's headline metric.

## Re-running the report

```
./measure.sh > results.md
```

The script enumerates every task directory, runs `wc` on each
reference file, computes the metrics, and emits a markdown table.
Committing `results.md` keeps the latest numbers reviewable without
re-running.

## Sequencing

The intent: when model API access is available, add `runners/` for
each baseline + configuration that issues prompts and captures
emissions. Then add `metrics/` computations for the dynamic numbers.
The static measurements here keep being useful as the baseline against
which configuration changes are measured.
