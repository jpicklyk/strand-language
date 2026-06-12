"""Per-run metric computation, cost estimation, and report formatting.

The orchestrator emits TaskMetrics per (task, config, sample) cell.
This module aggregates those into RunMetrics and renders the per-task
and aggregate tables that land in `dynamic-results.md`.

Pricing notes:

The MODEL_PRICING table maps model identifiers to (input_per_million_tokens,
output_per_million_tokens) in USD. Update this table when Anthropic
publishes price changes; the framework treats it as a static reference
rather than fetching live prices.

Cached-prefix pricing lives in CACHE_PRICING, which maps model ids to
(cache_read_per_million, cache_write_per_million). On Sonnet 4.7 reads
are 10% of input price; ephemeral 5-minute writes are 1.25x input price.
A model missing from CACHE_PRICING falls back to its non-cached rate,
which still produces a correct (just less favorable) cost number.

Multi-sample aggregation uses scipy.stats.bootstrap to compute 95% CIs
for tokens, cost, and convergence rate per (task, config) cell. With
N=1 the CI degenerates to the sample value itself and the report
collapses to a single number.
"""

from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass
from statistics import median
from typing import Callable, Iterable, Optional, Sequence

from strand_eval.types import RunMetrics, TaskMetrics

# (input_per_million, output_per_million) in USD for uncached input.
# Source: proposals/model-api-integration.md sec 6 (Anthropic public pricing).
MODEL_PRICING: dict[str, tuple[float, float]] = {
    "claude-sonnet-4-6": (3.00, 15.00),
    "claude-sonnet-4-7": (3.00, 15.00),
    "claude-opus-4-7": (15.00, 75.00),
    "claude-haiku-4-5-20251001": (1.00, 5.00),
}

# (cache_read_per_million, cache_write_per_million) in USD.
# Anthropic prompt-caching pricing (ephemeral 5-minute window):
#   read  = 0.1 × input rate
#   write = 1.25 × input rate
# Source: https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
CACHE_PRICING: dict[str, tuple[float, float]] = {
    "claude-sonnet-4-6": (0.30, 3.75),
    "claude-sonnet-4-7": (0.30, 3.75),
    "claude-opus-4-7": (1.50, 18.75),
    "claude-haiku-4-5-20251001": (0.10, 1.25),
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


def _lookup_cache_pricing(model: str) -> tuple[float, float]:
    """Return (cache_read_per_million, cache_write_per_million) for `model`.

    Falls back to the uncached input rate (read) and 1.25x input (write)
    using the family heuristics from _lookup_pricing.
    """
    if model in CACHE_PRICING:
        return CACHE_PRICING[model]
    name = model.lower()
    if "opus" in name:
        return CACHE_PRICING["claude-opus-4-7"]
    if "haiku" in name:
        return CACHE_PRICING["claude-haiku-4-5-20251001"]
    if "sonnet" in name:
        return CACHE_PRICING["claude-sonnet-4-6"]
    # Last-resort: derive from input rate.
    in_rate, _ = _lookup_pricing(model)
    return (in_rate * 0.10, in_rate * 1.25)


def estimate_cost(metrics: TaskMetrics, model: str) -> float:
    """Estimate dollar cost for a TaskMetrics's accumulated tokens.

    cost = uncached_input * input_rate
         + cache_read     * cache_read_rate
         + cache_write    * cache_write_rate
         + output         * output_rate
    all divided by 1e6.
    """
    in_price, out_price = _lookup_pricing(model)
    cache_read_price, cache_write_price = _lookup_cache_pricing(model)
    in_cost = metrics.total_input_tokens / 1_000_000.0 * in_price
    out_cost = metrics.total_output_tokens / 1_000_000.0 * out_price
    cache_read_cost = (
        metrics.total_cache_read_tokens / 1_000_000.0 * cache_read_price
    )
    cache_write_cost = (
        metrics.total_cache_creation_tokens / 1_000_000.0 * cache_write_price
    )
    return round(in_cost + out_cost + cache_read_cost + cache_write_cost, 6)


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


def _cell_tokens_value(s: TaskMetrics) -> int:
    """Total billable tokens for one cell sample.

    Sums uncached input, cache reads, cache writes, and output. This is
    the value used in tokens-per-successful-task tables; using the sum
    means a fully-cache-hot run still shows its real (small) compute
    rather than appearing as if it used zero input.
    """
    return (
        s.total_input_tokens
        + s.total_cache_read_tokens
        + s.total_cache_creation_tokens
        + s.total_output_tokens
    )


def _median_tokens_per_success(samples: list[TaskMetrics]) -> Optional[float]:
    """Median tokens-per-success across only the converged samples in a cell.

    Returns None when no sample in the cell converged.
    """
    successes = [_cell_tokens_value(s) for s in samples if s.success]
    if not successes:
        return None
    return float(median(successes))


def _convergence_fraction(samples: list[TaskMetrics]) -> str:
    """Render the n/N convergence fraction for a cell."""
    n = sum(1 for s in samples if s.success)
    return f"{n}/{len(samples)}"


# --------------------------------------------------------------------------
# Bootstrap CIs
# --------------------------------------------------------------------------

@dataclass
class CellStat:
    """Mean + 95% CI for one (task, config) cell, plus the sample count."""

    n: int
    mean: float
    ci_lo: float
    ci_hi: float

    def render(self, fmt: str = "{:,.0f}") -> str:
        """Pretty-print as ``mean [ci_lo, ci_hi]`` when N>1, else ``value``."""
        if self.n <= 1:
            return fmt.format(self.mean)
        return f"{fmt.format(self.mean)} [{fmt.format(self.ci_lo)}, {fmt.format(self.ci_hi)}]"


def _bootstrap_mean_ci(
    values: Sequence[float],
    confidence: float = 0.95,
    n_resamples: int = 1000,
    seed: int = 0,
) -> tuple[float, float, float]:
    """Return (mean, ci_lo, ci_hi) using scipy.stats.bootstrap.

    With N<=1 the CI collapses to the mean itself. With N==2 scipy can
    still produce a percentile CI but it will be very wide. We use the
    percentile method (the scipy default) for interpretability.
    """
    if not values:
        return 0.0, 0.0, 0.0
    if len(values) == 1:
        v = float(values[0])
        return v, v, v
    mean = float(sum(values) / len(values))
    # scipy import is local so import-time cost is only paid when CIs run.
    try:
        from scipy.stats import bootstrap  # noqa: WPS433
        import numpy as np  # noqa: WPS433

        rng = np.random.default_rng(seed)
        data = (np.asarray(values, dtype=float),)
        res = bootstrap(
            data,
            statistic=np.mean,
            confidence_level=confidence,
            n_resamples=n_resamples,
            method="percentile",
            random_state=rng,
            vectorized=True,
        )
        return mean, float(res.confidence_interval.low), float(res.confidence_interval.high)
    except Exception:
        # Fall back to mean if scipy hiccups for any reason.
        return mean, mean, mean


def cell_stat(
    samples: list[TaskMetrics],
    extractor: Callable[[TaskMetrics], Optional[float]],
    only_success: bool = True,
) -> Optional[CellStat]:
    """Build a CellStat for one (task, config) cell using `extractor` per sample.

    ``extractor`` may return None for samples that should be excluded
    (e.g. tokens-per-success skips non-converged samples). Returns None
    when no eligible samples remain.
    """
    if only_success:
        eligible = [extractor(s) for s in samples if s.success]
    else:
        eligible = [extractor(s) for s in samples]
    vals = [float(v) for v in eligible if v is not None]
    if not vals:
        return None
    mean, lo, hi = _bootstrap_mean_ci(vals)
    return CellStat(n=len(vals), mean=mean, ci_lo=lo, ci_hi=hi)


def _combine_cell_sources(samples: list[TaskMetrics]) -> str:
    """Aggregate the token-source labels for a group of samples.

    Delegates to strand_eval.tokens.combine_sources; imported lazily to
    keep metrics importable in isolation.
    """
    from strand_eval.tokens import combine_sources

    return combine_sources([s.token_source for s in samples])


def per_task_table(
    task_id: str,
    config_metrics: dict[str, list[TaskMetrics]],
    baseline_config: str,
) -> str:
    """Render a Markdown table for one task across configurations.

    Columns: Configuration | Convergence | Tokens/success (mean [CI]) |
    Token source | Cost/success | x vs baseline. The CI brackets only
    appear when N>1 for that cell; with N=1 the column collapses to a
    single value. The token-source column labels each cell's counting
    provenance ("api" / "byte-proxy" / ...) so figures from different
    scales are never presented as comparable without saying so.
    """
    baseline_samples = [tm for tm in config_metrics.get(baseline_config, []) if tm.task_id == task_id]
    baseline_stat = cell_stat(baseline_samples, _cell_tokens_value)
    baseline_mean = baseline_stat.mean if baseline_stat is not None else None

    lines: list[str] = [
        f"## {task_id}",
        "",
        f"| Configuration | Convergence | Tokens/success | Token source | Cost/success (USD) | x vs {baseline_config} |",
        "|---------------|-------------|----------------|--------------|--------------------|------------------------|",
    ]
    for config_name, samples in sorted(config_metrics.items()):
        task_samples = [tm for tm in samples if tm.task_id == task_id]
        if not task_samples:
            continue
        conv = _convergence_fraction(task_samples)
        tok_stat = cell_stat(task_samples, _cell_tokens_value)
        cost_stat = cell_stat(task_samples, lambda s: s.total_cost_usd)
        tokens_str = tok_stat.render() if tok_stat is not None else "n/a"
        source_str = _combine_cell_sources(task_samples)
        cost_str = cost_stat.render(fmt="${:.4f}") if cost_stat is not None else "n/a"
        if tok_stat is not None and baseline_mean and baseline_mean > 0:
            ratio_str = f"{tok_stat.mean / baseline_mean:.2f}"
        else:
            ratio_str = "n/a"
        lines.append(
            f"| {config_name} | {conv} | {tokens_str} | {source_str} | {cost_str} | {ratio_str} |"
        )
    return "\n".join(lines)


def aggregate_table(
    config_metrics: dict[str, list[TaskMetrics]],
    baseline_config: str,
) -> str:
    """Render the cross-task geomean table.

    For each configuration, compute the geomean of per-task
    mean-tokens-per-success ratios against the baseline configuration's
    mean-tokens-per-success. Aggregate cache-hit rate is reported when
    any cell shows non-zero cache traffic.
    """
    # Collect all task ids in the data.
    task_ids: set[str] = set()
    for samples in config_metrics.values():
        for tm in samples:
            task_ids.add(tm.task_id)

    # Per-task baseline mean tokens.
    baseline_per_task: dict[str, Optional[float]] = {}
    base_samples = config_metrics.get(baseline_config, [])
    for tid in task_ids:
        cell = [s for s in base_samples if s.task_id == tid]
        stat = cell_stat(cell, _cell_tokens_value)
        baseline_per_task[tid] = stat.mean if stat is not None else None

    # Detect whether caching telemetry is meaningfully present.
    show_cache = any(
        tm.total_cache_read_tokens + tm.total_cache_creation_tokens > 0
        for samples in config_metrics.values()
        for tm in samples
    )

    header_cols = [
        "Configuration",
        f"Geomean x vs {baseline_config}",
        "Convergence (all tasks)",
        "Total cost (USD)",
        "Token source",
    ]
    if show_cache:
        header_cols.append("Cache hit rate")
    lines: list[str] = ["## Aggregate", ""]
    lines.append("| " + " | ".join(header_cols) + " |")
    lines.append("|" + "|".join("---" for _ in header_cols) + "|")

    for config_name in sorted(config_metrics.keys()):
        samples = config_metrics[config_name]
        ratios: list[float] = []
        for tid in task_ids:
            cell = [s for s in samples if s.task_id == tid]
            stat = cell_stat(cell, _cell_tokens_value)
            base = baseline_per_task.get(tid)
            if stat is None or base is None or base <= 0:
                continue
            ratios.append(stat.mean / base)
        gm_str = f"{geomean(ratios):.2f}" if ratios else "n/a"
        total = len(samples)
        conv = sum(1 for s in samples if s.success)
        total_cost = sum(s.total_cost_usd for s in samples)
        row = [
            config_name,
            gm_str,
            f"{conv}/{total}",
            f"${total_cost:.4f}",
            _combine_cell_sources(samples),
        ]
        if show_cache:
            cache_in = sum(s.total_cache_read_tokens for s in samples)
            cache_out = sum(s.total_cache_creation_tokens for s in samples)
            uncached = sum(s.total_input_tokens for s in samples)
            total_input = cache_in + cache_out + uncached
            if total_input > 0:
                hit_rate = cache_in / total_input
                row.append(f"{hit_rate * 100:.1f}%")
            else:
                row.append("n/a")
        lines.append("| " + " | ".join(row) + " |")
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
