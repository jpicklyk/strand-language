"""Per-run metric computation, cost estimation, and report formatting.

The orchestrator emits TaskMetrics per (task, config, sample) cell.
This module aggregates those into RunMetrics and renders the per-task
and aggregate tables that land in `dynamic-results.md`.

Pricing notes:

The MODEL_PRICING table maps model identifiers to (input_per_million_tokens,
output_per_million_tokens) in USD. Update this table when Anthropic
publishes price changes; the framework treats it as a static reference
rather than fetching live prices.
"""

from __future__ import annotations

import math
from collections import defaultdict
from statistics import median
from typing import Iterable, Optional

from strand_eval.types import RunMetrics, TaskMetrics

# (input_per_million, output_per_million) in USD.
# Source: proposals/model-api-integration.md sec 6 (Anthropic public pricing).
MODEL_PRICING: dict[str, tuple[float, float]] = {
    "claude-sonnet-4-6": (3.00, 15.00),
    "claude-opus-4-7": (15.00, 75.00),
    "claude-haiku-4-5-20251001": (1.00, 5.00),
}


def _lookup_pricing(model: str) -> tuple[float, float]:
    """Return (input_per_million, output_per_million) for `model`.

    Falls back to Sonnet 4.6 pricing when the model is unknown so an
    unfamiliar model id doesn't drop the cost number silently to zero.
    """
    if model in MODEL_PRICING:
        return MODEL_PRICING[model]
    # Heuristic fallbacks by family-name substring.
    name = model.lower()
    if "opus" in name:
        return MODEL_PRICING["claude-opus-4-7"]
    if "haiku" in name:
        return MODEL_PRICING["claude-haiku-4-5-20251001"]
    return MODEL_PRICING["claude-sonnet-4-6"]


def estimate_cost(metrics: TaskMetrics, model: str) -> float:
    """Estimate dollar cost for a TaskMetrics's accumulated tokens.

    cost = input_tokens / 1e6 * input_price + output_tokens / 1e6 * output_price
    """
    in_price, out_price = _lookup_pricing(model)
    in_cost = metrics.total_input_tokens / 1_000_000.0 * in_price
    out_cost = metrics.total_output_tokens / 1_000_000.0 * out_price
    return round(in_cost + out_cost, 6)


def project_cost(
    num_cells: int,
    avg_input_tokens_per_cell: int,
    avg_output_tokens_per_cell: int,
    model: str,
) -> float:
    """Project total cost for an upcoming run.

    A coarse upper bound the CLI uses to decide whether to ask for budget
    confirmation. Per-cell averages are operator-supplied estimates.
    """
    in_price, out_price = _lookup_pricing(model)
    total_in = num_cells * avg_input_tokens_per_cell
    total_out = num_cells * avg_output_tokens_per_cell
    cost = total_in / 1_000_000.0 * in_price + total_out / 1_000_000.0 * out_price
    return round(cost, 4)


def geomean(values: Iterable[float]) -> float:
    """Geometric mean of strictly positive values.

    Returns 0.0 on an empty iterable. Values <= 0 are skipped with a
    comment in the docstring of the caller; the function itself raises
    ValueError on a non-positive input because geomean is undefined for
    those.
    """
    vs = list(values)
    if not vs:
        return 0.0
    if any(v <= 0 for v in vs):
        raise ValueError(
            "geomean requires strictly positive values; "
            f"got {[v for v in vs if v <= 0]} in {vs}"
        )
    # log-sum then divide then exp avoids overflow when |values| is large.
    log_sum = math.fsum(math.log(v) for v in vs)
    return math.exp(log_sum / len(vs))


def aggregate_run_metrics(
    run_id: str,
    started_at: str,
    finished_at: Optional[str],
    task_metrics: list[TaskMetrics],
) -> RunMetrics:
    """Bucket task metrics by configuration and compute headline totals."""
    bucketed: dict[str, list[TaskMetrics]] = defaultdict(list)
    total_cost = 0.0
    converged = 0
    for tm in task_metrics:
        bucketed[tm.config].append(tm)
        total_cost += tm.total_cost_usd
        if tm.success:
            converged += 1
    return RunMetrics(
        run_id=run_id,
        started_at=started_at,
        finished_at=finished_at,
        config_name_to_task_metrics=dict(bucketed),
        total_cost_usd=round(total_cost, 6),
        total_cells=len(task_metrics),
        converged_cells=converged,
    )


def _median_tokens_per_success(samples: list[TaskMetrics]) -> Optional[float]:
    """Median tokens-per-success across only the converged samples in a cell.

    Returns None when no sample in the cell converged.
    """
    successes = [
        s.total_input_tokens + s.total_output_tokens
        for s in samples
        if s.success
    ]
    if not successes:
        return None
    return float(median(successes))


def _convergence_fraction(samples: list[TaskMetrics]) -> str:
    """Render the n/N convergence fraction for a cell."""
    n = sum(1 for s in samples if s.success)
    return f"{n}/{len(samples)}"


def per_task_table(
    task_id: str,
    config_metrics: dict[str, list[TaskMetrics]],
    baseline_config: str,
) -> str:
    """Render a Markdown table for one task across configurations.

    Columns: Configuration | Convergence | Tokens/success | x vs baseline.
    """
    baseline_samples = [
        tm
        for tm in config_metrics.get(baseline_config, [])
        if tm.task_id == task_id and tm.success
    ]
    if baseline_samples:
        baseline_tokens = float(
            median(s.total_input_tokens + s.total_output_tokens for s in baseline_samples)
        )
    else:
        baseline_tokens = None

    lines: list[str] = [
        f"## {task_id}",
        "",
        f"| Configuration | Convergence | Tokens/success | x vs {baseline_config} |",
        "|---------------|-------------|----------------|------------------------|",
    ]
    for config_name, samples in sorted(config_metrics.items()):
        task_samples = [tm for tm in samples if tm.task_id == task_id]
        if not task_samples:
            continue
        conv = _convergence_fraction(task_samples)
        med_tokens = _median_tokens_per_success(task_samples)
        tokens_str = f"{int(med_tokens):,}" if med_tokens is not None else "n/a"
        if med_tokens is not None and baseline_tokens and baseline_tokens > 0:
            ratio_str = f"{med_tokens / baseline_tokens:.2f}"
        else:
            ratio_str = "n/a"
        lines.append(f"| {config_name} | {conv} | {tokens_str} | {ratio_str} |")
    return "\n".join(lines)


def aggregate_table(
    config_metrics: dict[str, list[TaskMetrics]],
    baseline_config: str,
) -> str:
    """Render the cross-task geomean table.

    For each configuration, compute the geomean of per-task tokens/success
    ratios against the baseline configuration's per-task tokens/success.
    """
    # Collect all task ids in the data.
    task_ids: set[str] = set()
    for samples in config_metrics.values():
        for tm in samples:
            task_ids.add(tm.task_id)

    # Per-task baseline tokens.
    baseline_per_task: dict[str, Optional[float]] = {}
    base_samples = config_metrics.get(baseline_config, [])
    for tid in task_ids:
        cell = [s for s in base_samples if s.task_id == tid]
        baseline_per_task[tid] = _median_tokens_per_success(cell)

    lines: list[str] = [
        "## Aggregate",
        "",
        f"| Configuration | Geomean x vs {baseline_config} | Convergence (all tasks) |",
        "|---------------|--------------------------------|-------------------------|",
    ]
    for config_name in sorted(config_metrics.keys()):
        samples = config_metrics[config_name]
        ratios: list[float] = []
        for tid in task_ids:
            cell = [s for s in samples if s.task_id == tid]
            med = _median_tokens_per_success(cell)
            base = baseline_per_task.get(tid)
            if med is None or base is None or base <= 0:
                continue
            ratios.append(med / base)
        gm_str = f"{geomean(ratios):.2f}" if ratios else "n/a"
        total = len(samples)
        conv = sum(1 for s in samples if s.success)
        lines.append(f"| {config_name} | {gm_str} | {conv}/{total} |")
    return "\n".join(lines)


def render_report(
    run: RunMetrics,
    baseline_config: str = "python-type-hints",
) -> str:
    """Render the full dynamic-results.md-style report for a run.

    The baseline configuration is the column the ratios are computed against
    and defaults to Python+type-hints per proposals/model-api-integration.md
    sec 5.4.
    """
    header = [
        "# Dynamic measurement report",
        "",
        f"Run: {run.run_id}",
        f"Started: {run.started_at}",
    ]
    if run.finished_at:
        header.append(f"Finished: {run.finished_at}")
    header.append(f"Total cost: ${run.total_cost_usd:.2f}")
    header.append(f"Converged cells: {run.converged_cells}/{run.total_cells}")
    header.append("")

    config_to_samples = run.config_name_to_task_metrics
    if not config_to_samples:
        return "\n".join(header + ["(no task metrics recorded)"])

    # Collect all task ids in deterministic order.
    task_ids_set: set[str] = set()
    for samples in config_to_samples.values():
        for tm in samples:
            task_ids_set.add(tm.task_id)
    task_ids = sorted(task_ids_set)

    sections: list[str] = []
    for tid in task_ids:
        sections.append(per_task_table(tid, config_to_samples, baseline_config))
        sections.append("")
    sections.append(aggregate_table(config_to_samples, baseline_config))
    return "\n".join(header + sections)
