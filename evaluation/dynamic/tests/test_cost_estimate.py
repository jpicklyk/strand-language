"""Cost estimation against the MODEL_PRICING table."""

from __future__ import annotations

import pytest

from strand_eval.metrics import (
    MODEL_PRICING,
    estimate_cost,
    project_cost,
)
from strand_eval.types import TaskMetrics


def _metrics(input_tokens: int, output_tokens: int, model: str = "claude-sonnet-4-6") -> TaskMetrics:
    return TaskMetrics(
        task_id="t",
        config="c",
        sample_index=0,
        model=model,
        success=True,
        converged_at_attempt=0,
        total_input_tokens=input_tokens,
        total_output_tokens=output_tokens,
    )


def test_sonnet_1m_tokens_input_costs_three_dollars():
    cost = estimate_cost(_metrics(1_000_000, 0), "claude-sonnet-4-6")
    assert cost == pytest.approx(3.0, abs=1e-6)


def test_sonnet_1m_tokens_output_costs_fifteen_dollars():
    cost = estimate_cost(_metrics(0, 1_000_000), "claude-sonnet-4-6")
    assert cost == pytest.approx(15.0, abs=1e-6)


def test_opus_pricing():
    in_per_m, out_per_m = MODEL_PRICING["claude-opus-4-7"]
    assert in_per_m == 15.0
    assert out_per_m == 75.0
    # 100K input + 50K output
    cost = estimate_cost(_metrics(100_000, 50_000), "claude-opus-4-7")
    expected = 100_000 / 1e6 * 15.0 + 50_000 / 1e6 * 75.0
    assert cost == pytest.approx(expected, abs=1e-6)


def test_haiku_pricing():
    in_per_m, out_per_m = MODEL_PRICING["claude-haiku-4-5-20251001"]
    assert in_per_m == 1.0
    assert out_per_m == 5.0
    cost = estimate_cost(_metrics(1_000_000, 1_000_000), "claude-haiku-4-5-20251001")
    assert cost == pytest.approx(6.0, abs=1e-6)


def test_unknown_model_falls_back_by_family():
    # An unknown "opus" name should pick up opus pricing.
    cost = estimate_cost(_metrics(1_000_000, 0), "claude-opus-future-version")
    assert cost == pytest.approx(15.0, abs=1e-6)


def test_project_cost_scales_with_cells():
    one = project_cost(num_cells=1, avg_input_tokens_per_cell=5000, avg_output_tokens_per_cell=1500, model="claude-sonnet-4-6")
    ten = project_cost(num_cells=10, avg_input_tokens_per_cell=5000, avg_output_tokens_per_cell=1500, model="claude-sonnet-4-6")
    assert ten == pytest.approx(one * 10, rel=1e-6)


def test_zero_tokens_costs_zero():
    assert estimate_cost(_metrics(0, 0), "claude-sonnet-4-6") == 0.0
