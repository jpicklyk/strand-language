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


# --------------------------------------------------------------------------
# Bootstrap CI / CellStat
# --------------------------------------------------------------------------

def test_cell_stat_single_sample_no_brackets():
    from strand_eval.metrics import cell_stat, _cell_tokens_value

    cell = [_make_metrics("t1", "c", 0, True, 1000)]
    stat = cell_stat(cell, _cell_tokens_value)
    assert stat is not None
    assert stat.n == 1
    assert stat.mean == 1000.0
    # render should not contain CI brackets
    rendered = stat.render()
    assert "[" not in rendered
    assert "1,000" in rendered


def test_cell_stat_multiple_samples_render_includes_brackets():
    from strand_eval.metrics import cell_stat, _cell_tokens_value

    cells = [
        _make_metrics("t1", "c", 0, True, 900),
        _make_metrics("t1", "c", 1, True, 1000),
        _make_metrics("t1", "c", 2, True, 1100),
    ]
    stat = cell_stat(cells, _cell_tokens_value)
    assert stat is not None
    assert stat.n == 3
    assert stat.mean == pytest.approx(1000.0, abs=1e-3)
    rendered = stat.render()
    assert "[" in rendered and "]" in rendered


def test_cell_stat_only_success_filter():
    from strand_eval.metrics import cell_stat, _cell_tokens_value

    cells = [
        _make_metrics("t1", "c", 0, True, 1000),
        _make_metrics("t1", "c", 1, False, 50000),  # excluded
    ]
    stat = cell_stat(cells, _cell_tokens_value, only_success=True)
    assert stat is not None
    assert stat.n == 1  # only the converged sample
    assert stat.mean == 1000.0


def test_cell_stat_no_eligible_samples_returns_none():
    from strand_eval.metrics import cell_stat, _cell_tokens_value

    cells = [_make_metrics("t1", "c", 0, False, 100)]
    stat = cell_stat(cells, _cell_tokens_value, only_success=True)
    assert stat is None


def test_per_task_table_shows_ci_for_multi_sample():
    cells = [
        _make_metrics("t1", "candidate", i, True, 1000 + 50 * i)
        for i in range(5)
    ] + [_make_metrics("t1", "baseline", 0, True, 1000)]
    by_cfg = {"baseline": cells[-1:], "candidate": cells[:-1]}
    md = per_task_table("t1", by_cfg, baseline_config="baseline")
    # Multi-sample candidate row must include CI brackets.
    cand_line = [line for line in md.splitlines() if line.startswith("| candidate")][0]
    assert "[" in cand_line and "]" in cand_line
    # Single-sample baseline row must NOT.
    base_line = [line for line in md.splitlines() if line.startswith("| baseline")][0]
    assert "[" not in base_line


def test_per_task_table_shows_cost_column():
    cells = [_make_metrics("t1", "baseline", 0, True, 1000)]
    by_cfg = {"baseline": cells}
    md = per_task_table("t1", by_cfg, baseline_config="baseline")
    assert "Cost/success" in md
    # the helper sets total_cost_usd=0.01 so the rendered cost ends in "0100"
    assert "$0.0100" in md


def test_aggregate_table_shows_cache_hit_rate_when_present():
    cells_baseline = [_make_metrics("t1", "baseline", 0, True, 1000)]
    cand = _make_metrics("t1", "candidate", 0, True, 1000)
    cand.total_cache_read_tokens = 4000
    cand.total_cache_creation_tokens = 0
    # _make_metrics splits 1000 into input/output as 500/500.
    # Total input across config "candidate" = 500 uncached + 4000 cache_read = 4500
    # Cache hit = 4000/4500 = 88.9%
    by_cfg = {"baseline": cells_baseline, "candidate": [cand]}
    md = aggregate_table(by_cfg, baseline_config="baseline")
    assert "Cache hit rate" in md
    assert "88.9%" in md


def test_aggregate_table_shows_cache_token_totals_as_columns():
    cells_baseline = [_make_metrics("t1", "baseline", 0, True, 1000)]
    cand = _make_metrics("t1", "candidate", 0, True, 1000)
    cand.total_cache_read_tokens = 4000
    cand.total_cache_creation_tokens = 2000
    by_cfg = {"baseline": cells_baseline, "candidate": [cand]}
    md = aggregate_table(by_cfg, baseline_config="baseline")
    assert "Cache read tokens" in md
    assert "Cache write tokens" in md
    cand_line = [line for line in md.splitlines() if line.startswith("| candidate")][0]
    assert "4,000" in cand_line
    assert "2,000" in cand_line


def test_per_task_table_shows_cache_columns_when_present():
    base = _make_metrics("t1", "baseline", 0, True, 1000)
    cand = _make_metrics("t1", "candidate", 0, True, 1000)
    cand.total_cache_read_tokens = 4000
    cand.total_cache_creation_tokens = 2000
    by_cfg = {"baseline": [base], "candidate": [cand]}
    md = per_task_table("t1", by_cfg, baseline_config="baseline")
    assert "Cache read" in md
    assert "Cache write" in md
    cand_line = [line for line in md.splitlines() if line.startswith("| candidate")][0]
    assert "4,000" in cand_line
    assert "2,000" in cand_line
    # The uncached-input-based Tokens/success total includes the cache
    # components explicitly (500 in + 500 out + 4000 read + 2000 write).
    assert "7,000" in cand_line


def test_per_task_table_omits_cache_columns_when_no_cache_traffic():
    cells = [_make_metrics("t1", "baseline", 0, True, 1000)]
    md = per_task_table("t1", {"baseline": cells}, baseline_config="baseline")
    assert "Cache read" not in md
    assert "Cache write" not in md


def test_aggregate_table_omits_cache_column_when_no_cache_traffic():
    cells = [
        _make_metrics("t1", "baseline", 0, True, 1000),
        _make_metrics("t1", "candidate", 0, True, 2000),
    ]
    by_cfg = {"baseline": cells[:1], "candidate": cells[1:]}
    md = aggregate_table(by_cfg, baseline_config="baseline")
    assert "Cache hit rate" not in md
