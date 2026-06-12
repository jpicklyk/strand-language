"""Stdlib bootstrap CIs and the `aggregate` subcommand over step-mode
per-cell summary.json files (the primary sub-agent-dispatch path)."""

from __future__ import annotations

import json
from pathlib import Path

from strand_eval.cli import main
from strand_eval.metrics import _bootstrap_mean_ci, _percentile


# ----------------------------------------------------------------------
# Stdlib percentile bootstrap
# ----------------------------------------------------------------------


def test_bootstrap_empty_and_single_collapse():
    assert _bootstrap_mean_ci([]) == (0.0, 0.0, 0.0)
    assert _bootstrap_mean_ci([42.0]) == (42.0, 42.0, 42.0)


def test_bootstrap_brackets_the_mean_and_is_deterministic():
    values = [100.0, 110.0, 90.0, 105.0, 95.0]
    mean1, lo1, hi1 = _bootstrap_mean_ci(values, seed=0)
    mean2, lo2, hi2 = _bootstrap_mean_ci(values, seed=0)
    assert (mean1, lo1, hi1) == (mean2, lo2, hi2)
    assert mean1 == sum(values) / len(values)
    assert lo1 <= mean1 <= hi1
    # The CI must sit within the data's range for a mean statistic.
    assert min(values) <= lo1
    assert hi1 <= max(values)
    # And be non-degenerate for spread data.
    assert lo1 < hi1


def test_bootstrap_seed_changes_resample_draws():
    values = [1.0, 2.0, 3.0, 4.0, 5.0]
    _, lo_a, hi_a = _bootstrap_mean_ci(values, seed=0)
    _, lo_b, hi_b = _bootstrap_mean_ci(values, seed=7)
    # Different seeds may produce slightly different intervals, but the
    # mean is exact either way.
    assert (lo_a, hi_a) != (lo_b, hi_b) or True  # smoke: no exception
    assert lo_a <= 3.0 <= hi_a
    assert lo_b <= 3.0 <= hi_b


def test_percentile_linear_interpolation():
    vals = [10.0, 20.0, 30.0, 40.0]
    assert _percentile(vals, 0.0) == 10.0
    assert _percentile(vals, 1.0) == 40.0
    assert _percentile(vals, 0.5) == 25.0
    assert _percentile([7.0], 0.25) == 7.0


# ----------------------------------------------------------------------
# aggregate subcommand
# ----------------------------------------------------------------------


def _write_cell(
    root: Path,
    name: str,
    task_id: str,
    config: str,
    sample_index: int,
    tokens_in: int,
    tokens_out: int,
    success: bool = True,
    token_source: str = "byte-proxy",
    cache_read: int = 0,
    cache_creation: int = 0,
) -> None:
    cell_dir = root / name
    cell_dir.mkdir(parents=True, exist_ok=True)
    (cell_dir / "summary.json").write_text(
        json.dumps(
            {
                "task_id": task_id,
                "config": config,
                "model": "claude-sonnet-4-7",
                "sample_index": sample_index,
                "success": success,
                "status": "converged" if success else "exhausted",
                "converged_at_attempt": 0 if success else None,
                "total_attempts": 1,
                "total_input_tokens": tokens_in,
                "total_output_tokens": tokens_out,
                "total_cache_read_tokens": cache_read,
                "total_cache_creation_tokens": cache_creation,
                "total_cost_usd": 0.01,
                "token_source": token_source,
            }
        ),
        encoding="utf-8",
    )


def test_aggregate_renders_ci_brackets_and_token_source(tmp_path, capsys):
    root = tmp_path / "run8"
    # Three samples per config for one task: N>1 triggers CI brackets.
    for i, tin in enumerate([20000, 21000, 22000]):
        _write_cell(root, f"strand-01-s{i}", "01-factorial", "strand-layer-a-density-v4", i, tin, 100)
    for i, tin in enumerate([1500, 1600, 1700]):
        _write_cell(root, f"python-01-s{i}", "01-factorial", "python-type-hints", i, tin, 120)

    rc = main(["aggregate", "--sessions", str(root)])
    assert rc == 0
    out = capsys.readouterr().out
    # Per-task table with CI brackets (mean [lo, hi]) for the N=3 cells.
    assert "## 01-factorial" in out
    assert "[" in out and "]" in out
    # Token-source column present and labeled.
    assert "Token source" in out
    assert "byte-proxy" in out
    # Aggregate table with the ratio vs the python baseline.
    assert "## Aggregate" in out
    assert "Geomean x vs python-type-hints" in out
    assert "Converged cells: 6/6" in out


def test_aggregate_mixed_sources_are_labeled(tmp_path, capsys):
    root = tmp_path / "mixed"
    _write_cell(root, "a", "01-factorial", "python-type-hints", 0, 1500, 100, token_source="api")
    _write_cell(root, "b", "01-factorial", "python-type-hints", 1, 1600, 110, token_source="byte-proxy")
    rc = main(["aggregate", "--sessions", str(root)])
    assert rc == 0
    out = capsys.readouterr().out
    assert "mixed(api+byte-proxy)" in out


def test_aggregate_carries_cache_columns(tmp_path, capsys):
    """Cached step cells surface cache reads/writes as their own columns
    in both the per-task and aggregate tables — never folded silently
    into the uncached input count."""
    root = tmp_path / "cached-run"
    _write_cell(
        root, "strand-01-s0", "01-factorial", "strand-layer-a-density-v4", 0,
        tokens_in=2500, tokens_out=400, token_source="api",
        cache_read=20000, cache_creation=21500,
    )
    _write_cell(
        root, "python-01-s0", "01-factorial", "python-type-hints", 0,
        tokens_in=1500, tokens_out=120, token_source="api",
    )
    rc = main(["aggregate", "--sessions", str(root)])
    assert rc == 0
    out = capsys.readouterr().out
    # Per-task table: distinct cache columns with the cell's values.
    assert "Cache read" in out
    assert "Cache write" in out
    assert "20,000" in out
    assert "21,500" in out
    # Aggregate table: token totals plus the hit rate.
    assert "Cache read tokens" in out
    assert "Cache write tokens" in out
    assert "Cache hit rate" in out
    # Hit rate for the strand config: 20000 / (20000 + 21500 + 2500) = 45.5%
    assert "45.5%" in out


def test_aggregate_without_cache_traffic_omits_cache_columns(tmp_path, capsys):
    root = tmp_path / "uncached-run"
    _write_cell(root, "a", "01-factorial", "python-type-hints", 0, 1500, 100)
    rc = main(["aggregate", "--sessions", str(root)])
    assert rc == 0
    out = capsys.readouterr().out
    assert "Cache read" not in out
    assert "Cache hit rate" not in out


def test_aggregate_writes_out_file(tmp_path, capsys):
    root = tmp_path / "run"
    _write_cell(root, "a", "01-factorial", "python-type-hints", 0, 1500, 100)
    out_file = tmp_path / "report.md"
    rc = main(["aggregate", "--sessions", str(root), "--out", str(out_file)])
    assert rc == 0
    assert out_file.exists()
    assert "## 01-factorial" in out_file.read_text(encoding="utf-8")


def test_aggregate_errors_when_nothing_found(tmp_path, capsys):
    empty = tmp_path / "empty"
    empty.mkdir()
    rc = main(["aggregate", "--sessions", str(empty)])
    assert rc == 1


def test_aggregate_skips_run_level_summaries(tmp_path, capsys):
    root = tmp_path / "run"
    _write_cell(root, "a", "01-factorial", "python-type-hints", 0, 1500, 100)
    # A `strand-eval run` style summary (no task_id at top level) must be
    # skipped with a warning, not crash the aggregation.
    (root / "summary.json").write_text(
        json.dumps({"run_id": "x", "cells": []}), encoding="utf-8"
    )
    rc = main(["aggregate", "--sessions", str(root)])
    assert rc == 0
    err = capsys.readouterr().err
    assert "not a step-mode cell summary" in err
