# strand-eval

Dynamic-cost evaluation framework for the Strand language. Drives a real
LLM through the verifier-feedback retry loop and records tokens-per-task,
convergence rate, and per-task cost so that Strand can be compared with
conventional-language baselines on a uniform basis.

The framework is the implementation of `proposals/model-api-integration.md`.
The static-cost framework at `evaluation/measure.sh` is unchanged; the two
are complementary.

## Layout

```
evaluation/dynamic/
  pyproject.toml          Python package config (name: strand-eval)
  README.md               this file
  strand_eval/
    types.py              shared dataclasses (Message, TaskSpec, RunConfig, ...)
    loop.py               run_task orchestrator
    metrics.py            pricing, geomean, stdlib bootstrap CIs, report tables
    tokens.py             labeled token counting (api / byte-proxy fallback)
    recording.py          fixture record/replay, summary.json writer
    reference_lookup.py   strand:need resolver over prompts/references/
    cli.py                argparse CLI entry point
    backends/
      base.py             EmissionBackend ABC
      anthropic.py        live Anthropic API
      mocked.py           replay from recorded JSON fixtures
      synthetic.py        scripted responses for unit tests
    languages/
      __init__.py         Language ABC + LANGUAGE_REGISTRY + @register_language
      strand.py           Strand adapter (owned by agent P2)
      python.py           Python adapter (owned by agent P3)
  configs/                per-configuration YAML (P2/P3)
  prompts/                system prompt templates; strand-system.md is the
                          minimal core, strand-system-full.md the monolith,
                          references/ the on-demand sections (Q-060 M-2)
  tasks/                  per-task description + expected (P2/P3)
  fixtures/               recorded API responses for mocked mode
  runs/                   timestamped per-run artifacts
  tests/                  pytest suite
```

## Installation

The framework is a Python package. From the repository root:

```
pip install -e evaluation/dynamic
```

Python 3.10 or newer is required. Runtime dependencies are `anthropic`
(for live API calls), `pyyaml` (for task and configuration loaders),
`python-dotenv` (for `.env` loading), and `pytest` (for the test
suite). Bootstrap CI computation and token counting are standard
library only; the step-mode path (`step`, `aggregate`) plus the
Python-baseline adapter run on a stock Python install with only
`pyyaml` added.

The package installs a console script named `strand-eval`. If the script
is not on PATH after install, the module can still be invoked directly:

```
python -m strand_eval.cli --help
```

## Credentials

Live runs require `ANTHROPIC_API_KEY`. The framework reads it from the
environment, falling back to an optional `.env` file under
`evaluation/dynamic/`. The `.env` file is gitignored.

```
strand-eval check-credentials
```

Prints whether the key is present and makes a single low-cost API call
to confirm it works.

## Backends

Three emission backends ship; each implements the same `EmissionBackend`
contract so the orchestrator does not know which is in use.

- `anthropic` - live calls to the Claude API via the official SDK.
  Use this when recording new fixtures or measuring against the real
  model. Refuses to run without `ANTHROPIC_API_KEY`.
- `mocked` - replays recorded responses from
  `evaluation/dynamic/fixtures/<task>/<config>/<model>/sample-NN/`.
  Use this in CI and for reproducible measurement. Raises on a fixture
  miss rather than silently calling the API.
- `synthetic` - scripted responses constructed in-process. Use this in
  unit tests that exercise the loop without touching the API or disk.

The default for `strand-eval run` is `mocked`.

## Smoke command

A minimal end-to-end exercise once configurations and language adapters
have been wired up (P2/P3) and at least one fixture has been recorded:

```
strand-eval run \
    --backend mocked \
    --tasks 01-factorial \
    --config strand-layer-a-density-v4,python-type-hints \
    --models claude-sonnet-4-6 \
    --samples 1
```

The command produces a timestamped directory under
`evaluation/dynamic/runs/<timestamp>/` containing `summary.json` and
per-cell transcripts.

## Subcommands

- `strand-eval check-credentials` - verify `ANTHROPIC_API_KEY` and make
  a sub-cent API call to confirm reachability.
- `strand-eval run --config <names> --tasks <ids> --backend <choice> --samples N --models <m> [--budget D]` -
  drive an evaluation across the configurations, tasks, models, and
  samples specified. The backend, sample count, retry cap, and feedback
  format are configurable per run.
- `strand-eval record --config <name> --tasks <ids> --models <m>` -
  drive the same loop with the anthropic backend and `--record`
  enabled. Fixtures are persisted under `fixtures/`.
- `strand-eval report --run <timestamp>` - render the saved
  `summary.json` from a previous run as the Markdown tables that land
  in `dynamic-results.md`.
- `strand-eval step --session <dir> [--init --task <id> --config <name>
  --sample <i> --max-reference-turns N]` - file-IPC step mode for
  sub-agent dispatch (the primary measurement methodology; see below).
- `strand-eval aggregate --sessions <roots> [--baseline <config>]
  [--out report.md]` - roll up step-mode per-cell `summary.json` files
  into per-task and aggregate tables, with percentile-bootstrap CIs
  when a (task, config) cell has more than one sample.
- `strand-eval lookup <name>... [--references-dir <dir>]` - resolve
  reference topics, builtin signatures, or prelude reserved names
  against `prompts/references/` outside the harness — the same lookup
  the step-mode `strand:need` channel serves (see below). Exit 1 on
  any miss; misses print nearest-match suggestions, never a fabricated
  signature. Also available as `python -m strand_eval lookup <name>`.

## Token counting modes

Historically all step-mode token figures used a `chars / 4` proxy with
unquantified error. Counting is now labeled per figure
(`strand_eval/tokens.py`):

- `api` - real tokenizer counts from the Anthropic
  `/v1/messages/count_tokens` endpoint, called via urllib (no SDK
  needed). Used automatically when `ANTHROPIC_API_KEY` is set. The
  endpoint counts without sampling and is free of charge, so api-mode
  counting does not bill against anything.
- `byte-proxy` - the legacy `(chars + 3) // 4` estimate, bit-identical
  to the arithmetic behind Runs 1-7, used when no key is available or
  after an API failure (a per-process circuit breaker stops retrying
  the network after the first failure).
- `caller` - explicit counts supplied via a turn's
  `response-metadata.json`.

Every emission, cell summary, and report table carries its source
label; aggregates over differing sources render as `mixed(a+b)` so the
two scales can never be silently conflated. Pre-labeling artifacts
surface as `unknown`.

Environment controls: `STRAND_EVAL_TOKEN_COUNT=api|byte-proxy` forces
a mode; `STRAND_EVAL_TOKEN_COUNT_MODEL=<model-id>` sets the model id
sent to the counting endpoint when the run's model label (e.g. a
sub-agent dispatch label) is not a real API model id.

Caveat for comparisons: `api` and `byte-proxy` figures are different
scales. Within one run keep one mode (set
`STRAND_EVAL_TOKEN_COUNT=byte-proxy` explicitly when extending an old
byte-proxy run); across runs compare only like-labeled numbers.

## Prompt caching and cache accounting

The anthropic backend always sends Anthropic `cache_control` blocks
(Q-060 M-1): one ephemeral breakpoint on the system prompt and one on
the first user message (the static task preamble). Within a cell's
retry loop both are byte-identical across attempts, so every retry
after the first pays cache-read rates (10% of the input rate) for the
prompt prefix instead of full input rates; across cells sharing one
system prompt the system-prefix entry is reused too. No flag is
needed — a cached N=5 API run is just:

```
strand-eval run \
    --backend anthropic \
    --tasks 01-factorial,...,22-list-append-sum \
    --config strand-layer-a-density-v4,python-type-hints \
    --models claude-sonnet-4-7 \
    --samples 5 --budget 60
```

Cache accounting flows end to end under distinct labels, never folded
into the plain input count:

- Per attempt: the API usage fields `cache_read_input_tokens` and
  `cache_creation_input_tokens` are recorded on every emission, in
  fixtures, transcripts, and the `attempts` array of each cell summary
  in `summary.json` (alongside the uncached `input_tokens`).
- Per cell: `total_cache_read_tokens` / `total_cache_creation_tokens`
  in `summary.json`; `total_input_tokens` remains uncached input only.
- In reports: when any cell shows cache traffic, per-task tables gain
  `Cache read` / `Cache write` columns (mean tokens per successful
  sample) and the aggregate table gains `Cache read tokens`,
  `Cache write tokens`, and `Cache hit rate` (reads as a fraction of
  all input-side tokens). The `Tokens/success` column is the explicit
  sum of uncached input + cache reads + cache writes + output, so a
  cache-hot run shows its real compute rather than appearing to use
  zero input.
- Cost: cache reads/writes are priced at their own rates
  (`CACHE_PRICING` in `metrics.py` — 0.1x / 1.25x the input rate).

Step mode has no API client, so cache figures exist there only when
the driver relays them from a real API response: add
`cache_read_input_tokens` / `cache_creation_input_tokens` to a turn's
`response-metadata.json` and they flow into the summary and reports
identically. Cached counts are API-sourced by definition. In
byte-proxy mode (no `ANTHROPIC_API_KEY`, no caller metadata) the cache
fields are recorded as zero — neither the `count_tokens` endpoint nor
the chars/4 proxy can observe cache behavior, and the harness never
fabricates cache figures — so byte-proxy reports simply omit the cache
columns.

## Minimal-core prompt and the reference-query channel (Q-060 M-2)

The agent-facing Strand prompt is split in two. `prompts/strand-system.md`
is the minimal always-loaded core (~3,900 byte-proxy token-equivalents):
the grammar shape, the highest-frequency codes and prelude names, every
density sugar in one line each (including the v5 forms), three worked
examples, the error-recovery guide, the Q-063 prelude-manifest hash, and
an index of the on-demand reference sections. Everything else lives in
nine named sections under `prompts/references/` (grammar-codes,
density-sugars, prelude, builtins, effects, llm-vector, formats,
state-machines, errors). The pre-split monolith is retained verbatim as
`prompts/strand-system-full.md` (~21,500 token-equivalents) — it is the
authority document for per-builtin signature text and the full-prompt
arm of the A/B.

**A/B invocation (core vs full prompt).** Two configs select the arms:
`strand-layer-a-density-v4` loads the core, `strand-layer-a-full-prompt`
loads the monolith. The section 2.2 gate of
`proposals/implemented/authoring-cost-reduction.md` is an N=5 step-mode
sweep over both, aggregated with bootstrap CIs:

```
for CFG in strand-layer-a-density-v4 strand-layer-a-full-prompt; do
  for S in 0 1 2 3 4; do
    python -m strand_eval.cli step \
        --session runs/<run-dir>/$CFG-<task>-s$S \
        --init --task <task> --config $CFG --sample $S \
        --model claude-sonnet-4-7 --max-retries 5
  done
done
# ... dispatch + advance per cell as in the Run 8 recipe below ...
python -m strand_eval.cli aggregate --sessions runs/<run-dir> \
    --baseline strand-layer-a-full-prompt
```

The API-backed equivalent is `strand-eval run --config
strand-layer-a-density-v4,strand-layer-a-full-prompt ... --samples 5`.

**Reference-turn protocol.** Under the core prompt, an agent that needs
a catalog section or an exact builtin signature replies — instead of a
program — with a single line as the first non-blank line of the
response, no code fence:

```
strand:need <topic-or-builtin> [<topic-or-builtin> ...]
```

The harness advances the turn by appending a user message containing
only the requested text: a topic name serves the reference section, a
dotted builtin name (`List.Map`, `strand-builtin:Fs.Write`) serves the
signature block verbatim from the reference sections, and a prelude
reserved name (`fsWrite`, `writeFx`) serves its catalog line. An
unknown name is answered with a nearest-match suggestion — never
silence, never a fabricated signature. A response that contains a
fenced code block is always treated as a program, so an emission
mentioning the literal string cannot be swallowed.

Reference turns do not consume emission attempts (`converged_at_attempt`
counts emissions only, so first-pass still means "the first emitted
program verified") but are capped per cell — default 3, set by
`--max-reference-turns` on `step --init` or a `max_reference_turns` key
in the config YAML. Requests past the cap are answered with a
budget-exhausted notice and DO consume an emission attempt, so a
looping agent still terminates through `max_retries`.

**Token attribution.** Every turn lands in the summary's `attempts`
array under a `turn_type` label (`emission` / `reference-request`), and
each served reply's appended text is counted under its own source label
in `reference_requests` (`{turn, requested, served, missed,
appended_tokens, token_source}`). The served text becomes ordinary
conversation input from the next turn on, so the round-trip cost of the
channel is visible in the same totals the A/B gate compares. Summaries
additionally carry `emission_attempts`, `reference_turns`, and
`max_reference_turns` alongside the existing fields.

## Multi-sample runs and bootstrap CIs (step mode)

Every cell in Runs 1-7 was N=1. The N>1 machinery is now end-to-end:
each (task, config) cell is sampled by creating N independent step
sessions distinguished by `--sample`, and `aggregate` computes the
mean and a 95% percentile-bootstrap CI (standard library resampling,
deterministic seed) per cell whenever N>1.

The exact commands for a future measurement session (Run 8 shape,
N=5, both configs, the 22-task suite), from `evaluation/dynamic/`:

```
# 1. Init N sessions per cell (task x config x sample):
for TASK in 01-factorial ... 22-list-append-sum; do
  for CFG in strand-layer-a-density-v4 python-type-hints; do
    for S in 0 1 2 3 4; do
      python -m strand_eval.cli step \
          --session runs/2026-MM-DD-run8/$CFG-$TASK-s$S \
          --init --task $TASK --config $CFG --sample $S \
          --model claude-sonnet-4-7 --max-retries 5 \
          --feedback-format both
    done
  done
done

# 2. Per session: dispatch a fresh sub-agent (Claude Code Agent tool,
#    one Read of turn-NN/prompt.md, one Write of turn-NN/response.md),
#    then advance:
python -m strand_eval.cli step --session runs/2026-MM-DD-run8/<cell>
#    exit 0 = retry needed (new prompt written), 1 = converged,
#    2 = exhausted. Repeat dispatch+advance until exit 1 or 2.

# 3. Aggregate everything into the report tables:
python -m strand_eval.cli aggregate \
    --sessions runs/2026-MM-DD-run8 \
    --baseline python-type-hints \
    --out runs/2026-MM-DD-run8/report.md
```

Sub-agent dispatch (step 2) must be driven from a main Claude Code
session via the Agent tool - it cannot run from inside this harness.
Do not start a full sweep casually: 22 tasks x 2 configs x 5 samples
is 220 sub-agent dispatches before retries. `aggregate` also works on
historical runs (e.g. `--sessions runs/2026-05-28-run6` reproduces the
published Run 6 ratios).

The API-backed path (`strand-eval run --samples N`) uses the same
`cell_stat` / CI machinery internally and needs no extra steps.

## Configurations

A configuration YAML at `configs/<name>.yaml` declares:

```yaml
language: strand                  # entry in LANGUAGE_REGISTRY
system_prompt_path: prompts/strand-system.md
feedback_format: prose            # prose | json | both
max_retries: 5
description: Strand Layer A density v4 (recommended target)
```

The initial configurations (per proposal sec 5.1) are
`strand-canonical`, `strand-layer-a`, `strand-layer-a-density-v4`, and
`python-type-hints`. Their YAML files are owned by P2 and P3.
`strand-layer-a-full-prompt` (Q-060 M-2) selects the pre-split
monolithic prompt as the full-prompt A/B arm; an optional
`max_reference_turns` key caps `strand:need` turns per cell
(default 3).

## Tasks

A task directory at `tasks/<task-id>/` contains a `description.md` (the
prompt body), an `expected.yaml` (the success-check shape, per proposal
sec 3.5), and one or more `reference.<lang>.<ext>` reference solutions.

The suite has 22 tasks. 01-10 are the original shapes (recursion, sum
types, state machines, schemas, effects, handlers). 11-15 are the
first retry-loop probes (2026-05-28); Run 6 showed three of them
converging first-pass because their descriptions named the rule under
test and the Elaborator auto-fills rescued the rest. 16-22
(2026-06-11) are semantic-error probes engineered around that failure:
descriptions state behavior only, and each trap sits where no
Elaborator auto-fill applies. Each carries a `probe.md` documenting
the target verifier-error family, the validated wrong-variant error,
and the Python-baseline failure shape; `probe.md` is operator
documentation and is never sent to the agent.

| ID | Task | Target error family |
|----|------|---------------------|
| 16 | audit-log-effects | `UncoveredEffects` (explicit partial effect row) |
| 17 | handler-config-read | `HandlerSignatureMismatch` (Bytes vs String handle) |
| 18 | schema-username-truncate | runtime `SchemaInvariantViolation` on a dynamic value (Q-047) |
| 19 | tree-sum-leaves | `UnboundRecursiveSelf` (two-self-field product, no auto-rescue) |
| 20 | connect-effect-decl | `ProjectionMismatch` / `EffectDecl*` (Q-039 node identity) |
| 21 | capability-scoped-write | `CapabilityScopeUnsatisfiable` (CAP narrows below closure) |
| 22 | list-append-sum | `UnboundRecursiveSelf` at construction inside a Fixpoint body |

## Tests

```
pytest evaluation/dynamic/tests/
```

The suite runs without external dependencies. Loop logic is exercised
via the synthetic backend; recording and replay use temporary
directories and the mocked backend.

## Notes on budget

The framework refuses to start in `anthropic` mode without
`ANTHROPIC_API_KEY` and refuses to start when a projected cost exceeds
the operator-provided `--budget`. Per-cell cost is dominated by the
input-token bill: at Sonnet pricing a smoke run is around two dollars
and the full Q-021 sweep is around sixty dollars with prompt caching.
