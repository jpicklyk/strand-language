"""Geomean and aggregation tests for the metrics module."""

from __future__ import annotations

import math

import pytest

from strand_eval.metrics import (
    aggregate_run_metrics,
    aggregate_table,
    geomean,
    per_task_table,
)
from strand_eval.types import (
    EmissionResult,
    RunResult,
    TaskMetrics,
    VerifyResult,
)


def test_geomean_of_one_two_four_is_two():
    assert geomean([1.0, 2.0, 4.0]) == pytest.approx(2.0)


def test_geomean_of_single_value_equals_that_value():
    assert geomean([7.5]) == pytest.approx(7.5)


def test_geomean_of_empty_iterable_is_zero():
    assert geomean([]) == 0.0


def test_geomean_handles_logarithm_safety():
    # Ten values of 10 should give geomean 10 (no overflow / underflow).
    assert geomean([10.0] * 10) == pytest.approx(10.0)


def test_geomean_rejects_non_positive():
    with pytest.raises(ValueError):
        geomean([1.0, 0.0, 2.0])
    with pytest.raises(ValueError):
        geomean([-1.0, 1.0])


def _make_metrics(task_id: str, config: str, sample: int, success: bool, tokens: int) -> TaskMetrics:
    tm = TaskMetrics(
        task_id=task_id,
        config=config,
        sample_index=sample,
        model="claude-sonnet-4-6",
        success=success,
        converged_at_attempt=0 if success else None,
    )
    tm.total_input_tokens = tokens // 2
    tm.total_output_tokens = tokens - tm.total_input_tokens
    tm.total_cost_usd = 0.01
    tm.verify_results = [VerifyResult(ok=success)]
    if success:
        tm.run_result = RunResult(ok=True)
        tm.emissions = [
            EmissionResult(
                content="",
                input_tokens=tm.total_input_tokens,
                output_tokens=tm.total_output_tokens,
                model="claude-sonnet-4-6",
                latency_ms=0,
                finish_reason="stop",
            )
        ]
    return tm


def test_aggregate_run_metrics_buckets_by_config():
    cells = [
        _make_metrics("t1", "A", 0, True, 100),
        _make_metrics("t1", "A", 1, True, 200),
        _make_metrics("t1", "B", 0, True, 50),
        _make_metrics("t1", "B", 1, False, 30),
    ]
    run = aggregate_run_metrics(
        run_id="run-1",
        started_at="2026-05-25T00:00:00Z",
        finished_at="2026-05-25T00:01:00Z",
        task_metrics=cells,
    )
    assert run.total_cells == 4
    assert run.converged_cells == 3
    assert set(run.config_name_to_task_metrics.keys()) == {"A", "B"}
    assert len(run.config_name_to_task_metrics["A"]) == 2


def test_per_task_table_uses_baseline_ratio():
    cells = [
        _make_metrics("t1", "baseline", 0, True, 1000),
        _make_metrics("t1", "candidate", 0, True, 2000),
    ]
    by_cfg = {"baseline": cells[:1], "candidate": cells[1:]}
    md = per_task_table("t1", by_cfg, baseline_config="baseline")
    # Candidate should appear with 2.00 ratio.
    assert "2.00" in md
    # Baseline row should be 1.00.
    assert "1.00" in md


def test_aggregate_table_computes_cross_task_geomean():
    # Two tasks: baseline 1000/1000, candidate 2000/8000.
    # Ratios: 2.0, 8.0  -> geomean = 4.0
    cells = [
        _make_metrics("t1", "baseline", 0, True, 1000),
        _make_metrics("t2", "baseline", 0, True, 1000),
        _make_metrics("t1", "candidate", 0, True, 2000),
        _make_metrics("t2", "candidate", 0, True, 8000),
    ]
    by_cfg: dict[str, list] = {"baseline": cells[:2], "candidate": cells[2:]}
    md = aggregate_table(by_cfg, baseline_config="baseline")
    assert "candidate" in md
    assert "4.00" in md or "4.0" in md


def test_aggregate_table_emits_na_for_no_converged_samples():
    cells = [
        _make_metrics("t1", "baseline", 0, True, 1000),
        _make_metrics("t1", "broken", 0, False, 0),
    ]
    by_cfg: dict[str, list] = {"baseline": cells[:1], "broken": cells[1:]}
    md = aggregate_table(by_cfg, baseline_config="baseline")
    assert "n/a" in md
