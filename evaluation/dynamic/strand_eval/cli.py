"""Command-line interface for strand-eval.

Subcommands:

- ``check-credentials`` - verify ANTHROPIC_API_KEY is present and a
  minimal API call succeeds.
- ``run`` - drive a full (tasks x configs x models x samples) evaluation
  using a chosen backend.
- ``record`` - drive a run with the anthropic backend and persist every
  emission as a fixture file.
- ``report`` - render a previously-completed run's summary.json as the
  Markdown table format used in dynamic-results.md.

The CLI is exposed as the console script ``strand-eval`` defined in
``pyproject.toml``. It can also be invoked directly:

    python -m strand_eval.cli --help
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from strand_eval.backends import (
    AnthropicBackend,
    EmissionBackend,
    MockedBackend,
    SyntheticBackend,
)
from strand_eval.loop import run_task
from strand_eval.metrics import (
    MODEL_PRICING,
    aggregate_run_metrics,
    project_cost,
    render_report,
)
from strand_eval.recording import (
    load_summary,
    new_run_dir,
    write_fixture,
    write_summary,
    write_transcripts,
)
from strand_eval.types import (
    BackendChoice,
    FeedbackFormat,
    RunConfig,
    TaskSpec,
)

# YAML loader: pyyaml is a hard dependency in pyproject.toml. We import
# inside the helper so module import doesn't crash on environments where
# only the type-checking surface is consumed.

DYNAMIC_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONFIGS_DIR = DYNAMIC_ROOT / "configs"
DEFAULT_TASKS_DIR = DYNAMIC_ROOT / "tasks"
DEFAULT_FIXTURES_DIR = DYNAMIC_ROOT / "fixtures"
DEFAULT_RUNS_DIR = DYNAMIC_ROOT / "runs"


# --------------------------------------------------------------------------
# YAML helpers
# --------------------------------------------------------------------------

def _load_yaml(path: Path) -> dict:
    import yaml  # noqa: WPS433 - intentional lazy import
    return yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}


# --------------------------------------------------------------------------
# Configuration loading
# --------------------------------------------------------------------------

def load_run_configuration(name: str, configs_dir: Path) -> dict:
    """Load one configuration YAML by name.

    Expected fields: ``language``, ``system_prompt_path``,
    ``feedback_format``, ``max_retries``, ``description``.
    """
    path = configs_dir / f"{name}.yaml"
    if not path.exists():
        raise FileNotFoundError(
            f"Configuration {name!r} not found at {path}. "
            f"Available: {sorted(p.stem for p in configs_dir.glob('*.yaml'))}"
        )
    cfg = _load_yaml(path)
    cfg.setdefault("name", name)
    cfg.setdefault("feedback_format", "prose")
    cfg.setdefault("max_retries", 5)
    cfg.setdefault("description", "")
    return cfg


def load_task(task_id: str, tasks_dir: Path) -> TaskSpec:
    """Load one task directory as a TaskSpec.

    The directory layout (per proposal sec 3.10):

        <tasks_dir>/<task_id>/
            description.md
            expected.yaml
            reference.<lang>.<ext>   (any number of reference solutions)
    """
    tdir = tasks_dir / task_id
    if not tdir.is_dir():
        raise FileNotFoundError(
            f"Task {task_id!r} not found at {tdir}. "
            f"Available: {sorted(p.name for p in tasks_dir.iterdir() if p.is_dir())}"
        )
    desc_path = tdir / "description.md"
    description = desc_path.read_text(encoding="utf-8") if desc_path.exists() else ""

    expected_path = tdir / "expected.yaml"
    if expected_path.exists():
        expected = _load_yaml(expected_path)
    else:
        expected = {}

    references: dict[str, Path] = {}
    for ref in tdir.glob("reference.*"):
        # Key: everything after "reference.", with the extension dropped.
        # e.g. "reference.python.py" -> "python", "reference.layer-a" -> "layer-a"
        stem = ref.name[len("reference.") :]
        # Drop final ".py" / ".json" extension if present.
        for ext in (".py", ".json", ".kt", ".ts", ".rs"):
            if stem.endswith(ext):
                stem = stem[: -len(ext)]
                break
        references[stem] = ref

    return TaskSpec(
        task_id=task_id,
        description=description,
        expected=expected,
        reference_solutions=references,
        directory=tdir,
    )


# --------------------------------------------------------------------------
# Backend construction
# --------------------------------------------------------------------------

def make_backend(
    choice: BackendChoice,
    fixtures_dir: Optional[Path] = None,
    synthetic_responses: Optional[list[str]] = None,
) -> EmissionBackend:
    """Construct the requested backend.

    For ``BackendChoice.MOCKED`` the caller is expected to call
    ``backend.set_cell(...)`` before each per-task run.
    """
    if choice == BackendChoice.ANTHROPIC:
        return AnthropicBackend()
    if choice == BackendChoice.MOCKED:
        if fixtures_dir is None:
            fixtures_dir = DEFAULT_FIXTURES_DIR
        return MockedBackend(fixture_dir=fixtures_dir)
    if choice == BackendChoice.SYNTHETIC:
        return SyntheticBackend(responses=synthetic_responses or [])
    raise ValueError(f"Unknown backend choice: {choice}")


# --------------------------------------------------------------------------
# Subcommand: check-credentials
# --------------------------------------------------------------------------

def cmd_check_credentials(_args: argparse.Namespace) -> int:
    """Verify ANTHROPIC_API_KEY is set and usable.

    Makes a single one-token API call so the operator pays ~$0.001 in
    exchange for confidence the credentials work before a long run.
    """
    # Try to load .env without failing if dotenv is absent.
    try:
        import dotenv  # noqa: WPS433
        dotenv.load_dotenv()
    except ImportError:
        pass

    key = os.environ.get("ANTHROPIC_API_KEY")
    if not key:
        print(
            "ERROR: ANTHROPIC_API_KEY is not set in the environment.\n"
            "Set it in your shell, in a .env file under evaluation/dynamic/, "
            "or via your provider's secret manager.",
            file=sys.stderr,
        )
        return 1
    print(f"ANTHROPIC_API_KEY present (length {len(key)}, prefix {key[:7]}...)")
    try:
        backend = AnthropicBackend(api_key=key, max_tokens=8)
    except ImportError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2
    from strand_eval.types import Message, Role
    msgs = [Message(role=Role.USER, content="Reply with the single word OK.")]
    try:
        result = backend.emit(msgs, model="claude-haiku-4-5-20251001")
    except Exception as e:  # noqa: BLE001 - we want to report any failure
        print(f"ERROR: API call failed: {e}", file=sys.stderr)
        return 3
    print(f"API responded: {result.content!r} ({result.input_tokens} in / {result.output_tokens} out)")
    return 0


# --------------------------------------------------------------------------
# Subcommand: run
# --------------------------------------------------------------------------

def _check_budget(run_config: RunConfig, models: list[str]) -> None:
    """Refuse to start if projected cost exceeds the budget.

    Uses coarse heuristics: 5,000 input tokens per cell, 1,500 output
    tokens per cell, average across the (model)s requested. The
    proposal sec 6 calibrates these numbers; they are conservative.
    """
    if run_config.budget_dollars is None:
        return
    num_cells = (
        len(run_config.tasks)
        * len(run_config.configurations)
        * len(models)
        * run_config.samples_per_cell
    )
    avg_in = 5000
    avg_out = 1500
    # Use the most expensive model for a conservative bound.
    most_expensive = max(
        models,
        key=lambda m: sum(MODEL_PRICING.get(m, (3.0, 15.0))),
    )
    projected = project_cost(num_cells, avg_in, avg_out, most_expensive)
    if projected > run_config.budget_dollars:
        raise RuntimeError(
            f"Projected cost ${projected:.2f} exceeds budget ${run_config.budget_dollars:.2f}. "
            f"Reduce --tasks, --configs, --samples, or raise --budget."
        )
    print(f"Projected cost: ${projected:.4f} (budget ${run_config.budget_dollars:.2f})")


def cmd_run(args: argparse.Namespace) -> int:
    """Drive a (tasks x configs x models x samples) run end-to-end."""
    try:
        backend_choice = BackendChoice(args.backend)
    except ValueError:
        print(f"ERROR: unknown backend {args.backend!r}", file=sys.stderr)
        return 1
    feedback_format = FeedbackFormat(args.feedback_format)

    tasks = [t.strip() for t in args.tasks.split(",") if t.strip()]
    configurations = [c.strip() for c in args.config.split(",") if c.strip()]
    models = [m.strip() for m in args.models.split(",") if m.strip()]
    if not tasks or not configurations or not models:
        print("ERROR: --tasks, --config, and --models are all required.", file=sys.stderr)
        return 1

    run_config = RunConfig(
        tasks=tasks,
        configurations=configurations,
        models=models,
        backend=backend_choice,
        samples_per_cell=args.samples,
        max_retries=args.max_retries,
        feedback_format=feedback_format,
        record_fixtures=args.record,
        output_dir=Path(args.output_dir) if args.output_dir else None,
        budget_dollars=args.budget,
    )

    if backend_choice == BackendChoice.ANTHROPIC and not os.environ.get("ANTHROPIC_API_KEY"):
        # Try .env as a courtesy.
        try:
            import dotenv  # noqa: WPS433
            dotenv.load_dotenv()
        except ImportError:
            pass
        if not os.environ.get("ANTHROPIC_API_KEY"):
            print(
                "ERROR: anthropic backend requires ANTHROPIC_API_KEY. "
                "Run `strand-eval check-credentials` first.",
                file=sys.stderr,
            )
            return 1

    try:
        _check_budget(run_config, models)
    except RuntimeError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1

    # Defer real run execution to a thin orchestrator. The framework needs
    # the language adapters (P2/P3) to exist before this loop can do real
    # work; we still wire the structure so once adapters land the loop is
    # one-line ready.
    from strand_eval.languages import LANGUAGE_REGISTRY, get_language

    runs_root = Path(args.runs_dir) if args.runs_dir else DEFAULT_RUNS_DIR
    output_dir = run_config.output_dir or new_run_dir(runs_root)
    output_dir.mkdir(parents=True, exist_ok=True)

    configs_dir = Path(args.configs_dir) if args.configs_dir else DEFAULT_CONFIGS_DIR
    tasks_dir = Path(args.tasks_dir) if args.tasks_dir else DEFAULT_TASKS_DIR

    if not LANGUAGE_REGISTRY:
        print(
            "WARNING: no language adapters are registered. The orchestrator "
            "structure is in place but cannot run tasks until P2/P3 land "
            "strand_eval/languages/strand.py and strand_eval/languages/python.py.",
            file=sys.stderr,
        )

    all_metrics = []
    started_at = datetime.now(timezone.utc).isoformat()

    backend = make_backend(backend_choice, fixtures_dir=DEFAULT_FIXTURES_DIR)
    for config_name in configurations:
        try:
            cfg = load_run_configuration(config_name, configs_dir)
        except FileNotFoundError as e:
            print(f"WARNING: {e}", file=sys.stderr)
            continue
        language_name = cfg.get("language")
        if not language_name:
            print(f"WARNING: config {config_name} missing 'language'; skipping", file=sys.stderr)
            continue
        try:
            language = get_language(language_name)
        except KeyError as e:
            print(f"WARNING: {e}", file=sys.stderr)
            continue

        system_prompt = ""
        sp_path = cfg.get("system_prompt_path")
        if sp_path:
            sp_file = (configs_dir.parent / sp_path).resolve()
            if sp_file.exists():
                system_prompt = sp_file.read_text(encoding="utf-8")

        feedback_fmt = FeedbackFormat(cfg.get("feedback_format", feedback_format.value))
        max_retries = int(cfg.get("max_retries", run_config.max_retries))

        for task_id in tasks:
            try:
                task = load_task(task_id, tasks_dir)
            except FileNotFoundError as e:
                print(f"WARNING: {e}", file=sys.stderr)
                continue
            for model in models:
                for sample_index in range(run_config.samples_per_cell):
                    if isinstance(backend, MockedBackend):
                        backend.set_cell(task_id, config_name, model, sample_index)
                    metrics = run_task(
                        task=task,
                        config_name=config_name,
                        backend=backend,
                        language=language,
                        model=model,
                        sample_index=sample_index,
                        max_retries=max_retries,
                        feedback_format=feedback_fmt,
                        system_prompt=system_prompt,
                    )
                    if run_config.record_fixtures:
                        # Re-derive the per-attempt request messages by
                        # replaying the transcript prefix per emission.
                        prefix: list = []
                        attempt = 0
                        for msg in metrics.transcript:
                            if msg.role.value == "assistant":
                                if attempt < len(metrics.emissions):
                                    write_fixture(
                                        fixture_root=DEFAULT_FIXTURES_DIR,
                                        task=task_id,
                                        config=config_name,
                                        model=model,
                                        sample_index=sample_index,
                                        attempt=attempt,
                                        request_messages=list(prefix),
                                        response=metrics.emissions[attempt],
                                    )
                                    attempt += 1
                                # Append the assistant message after writing
                                # so subsequent fixtures see it as context.
                                prefix.append(msg)
                            else:
                                prefix.append(msg)
                    all_metrics.append(metrics)

    finished_at = datetime.now(timezone.utc).isoformat()
    run_id = output_dir.name
    run = aggregate_run_metrics(
        run_id=run_id,
        started_at=started_at,
        finished_at=finished_at,
        task_metrics=all_metrics,
    )
    summary_path = write_summary(output_dir, run)
    transcripts = write_transcripts(output_dir, run, only_failures=not args.full_transcripts)
    print(f"summary: {summary_path}")
    print(f"transcripts: {len(transcripts)} written under {output_dir / 'transcripts'}")
    print(f"converged: {run.converged_cells}/{run.total_cells}")
    print(f"total cost: ${run.total_cost_usd:.4f}")
    return 0


# --------------------------------------------------------------------------
# Subcommand: record
# --------------------------------------------------------------------------

def cmd_record(args: argparse.Namespace) -> int:
    """Convenience wrapper for ``run --backend anthropic --record``."""
    args.backend = "anthropic"
    args.record = True
    return cmd_run(args)


# --------------------------------------------------------------------------
# Subcommand: report
# --------------------------------------------------------------------------

def cmd_report(args: argparse.Namespace) -> int:
    runs_root = Path(args.runs_dir) if args.runs_dir else DEFAULT_RUNS_DIR
    run_dir = runs_root / args.run
    summary_path = run_dir / "summary.json"
    if not summary_path.exists():
        print(f"ERROR: no summary at {summary_path}", file=sys.stderr)
        return 1
    payload = load_summary(summary_path)
    # Reconstruct a minimal RunMetrics for report rendering. The summary
    # is sufficient for the table format; full TaskMetrics objects would
    # require re-parsing emissions which the summary intentionally drops.
    from strand_eval.types import RunMetrics, TaskMetrics, RunResult, VerifyResult

    config_to_samples: dict[str, list[TaskMetrics]] = {}
    for cell in payload.get("cells", []):
        tm = TaskMetrics(
            task_id=cell["task_id"],
            config=cell["config"],
            sample_index=cell["sample_index"],
            model=cell["model"],
            success=cell["success"],
            converged_at_attempt=cell["converged_at_attempt"],
            total_input_tokens=cell["total_input_tokens"],
            total_output_tokens=cell["total_output_tokens"],
            total_cost_usd=cell["total_cost_usd"],
        )
        tm.verify_results = [VerifyResult(ok=ok) for ok in cell.get("verify_oks", [])]
        if cell.get("run_ok") is not None:
            tm.run_result = RunResult(ok=cell["run_ok"])
        config_to_samples.setdefault(tm.config, []).append(tm)
    run = RunMetrics(
        run_id=payload["run_id"],
        started_at=payload["started_at"],
        finished_at=payload.get("finished_at"),
        config_name_to_task_metrics=config_to_samples,
        total_cost_usd=payload["total_cost_usd"],
        total_cells=payload["total_cells"],
        converged_cells=payload["converged_cells"],
    )
    print(render_report(run, baseline_config=args.baseline))
    return 0


# --------------------------------------------------------------------------
# argparse plumbing
# --------------------------------------------------------------------------

def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="strand-eval",
        description="Dynamic-cost evaluation framework for Strand.",
    )
    subs = parser.add_subparsers(dest="command", required=True)

    # check-credentials
    p_cred = subs.add_parser("check-credentials", help="Verify ANTHROPIC_API_KEY")
    p_cred.set_defaults(func=cmd_check_credentials)

    # run
    p_run = subs.add_parser("run", help="Run an evaluation")
    p_run.add_argument("--config", required=True, help="Comma-separated configuration names")
    p_run.add_argument("--tasks", required=True, help="Comma-separated task ids, or 'all'")
    p_run.add_argument("--backend", default="mocked", choices=[b.value for b in BackendChoice])
    p_run.add_argument("--samples", type=int, default=5)
    p_run.add_argument("--models", required=True, help="Comma-separated model identifiers")
    p_run.add_argument("--max-retries", type=int, default=5, dest="max_retries")
    p_run.add_argument(
        "--feedback-format",
        default="prose",
        choices=[f.value for f in FeedbackFormat],
        dest="feedback_format",
    )
    p_run.add_argument("--record", action="store_true", help="Save fixtures while running")
    p_run.add_argument("--budget", type=float, default=None, help="Refuse to start if projected cost exceeds this (USD)")
    p_run.add_argument("--output-dir", default=None, dest="output_dir")
    p_run.add_argument("--runs-dir", default=None, dest="runs_dir")
    p_run.add_argument("--configs-dir", default=None, dest="configs_dir")
    p_run.add_argument("--tasks-dir", default=None, dest="tasks_dir")
    p_run.add_argument("--full-transcripts", action="store_true", dest="full_transcripts")
    p_run.set_defaults(func=cmd_run)

    # record
    p_rec = subs.add_parser("record", help="Run with anthropic backend and save fixtures")
    p_rec.add_argument("--config", required=True)
    p_rec.add_argument("--tasks", required=True)
    p_rec.add_argument("--samples", type=int, default=1)
    p_rec.add_argument("--models", required=True)
    p_rec.add_argument("--max-retries", type=int, default=5, dest="max_retries")
    p_rec.add_argument(
        "--feedback-format",
        default="prose",
        choices=[f.value for f in FeedbackFormat],
        dest="feedback_format",
    )
    p_rec.add_argument("--budget", type=float, default=None)
    p_rec.add_argument("--output-dir", default=None, dest="output_dir")
    p_rec.add_argument("--runs-dir", default=None, dest="runs_dir")
    p_rec.add_argument("--configs-dir", default=None, dest="configs_dir")
    p_rec.add_argument("--tasks-dir", default=None, dest="tasks_dir")
    p_rec.add_argument("--full-transcripts", action="store_true", dest="full_transcripts")
    p_rec.set_defaults(func=cmd_record)

    # report
    p_rep = subs.add_parser("report", help="Render a saved run as Markdown tables")
    p_rep.add_argument("--run", required=True, help="Run directory name (timestamp)")
    p_rep.add_argument("--runs-dir", default=None, dest="runs_dir")
    p_rep.add_argument("--baseline", default="python-type-hints")
    p_rep.set_defaults(func=cmd_report)

    return parser


def main(argv: Optional[list[str]] = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    func = getattr(args, "func", None)
    if func is None:
        parser.print_help()
        return 1
    return int(func(args) or 0)


if __name__ == "__main__":
    raise SystemExit(main())
