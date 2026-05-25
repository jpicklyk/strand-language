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
    metrics.py            pricing, geomean, per-task and aggregate tables
    recording.py          fixture record/replay, summary.json writer
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
  prompts/                system prompt templates (P2/P3)
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
`python-dotenv` (for `.env` loading), `pytest` (for the test suite), and
`scipy` (reserved for bootstrap CI computation in later metrics work).

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

## Tasks

A task directory at `tasks/<task-id>/` contains a `description.md` (the
prompt body), an `expected.yaml` (the success-check shape, per proposal
sec 3.5), and one or more `reference.<lang>.<ext>` reference solutions.
The 10-task initial suite is owned by P3.

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
