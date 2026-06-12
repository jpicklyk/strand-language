"""Fixture record/replay and per-run summary persistence.

Recording captures the full request + response for each emission so a
later mocked-mode run can replay them without touching the API. The
per-run summary captures aggregate metrics for the report subcommand.

Fixture layout (matches strand_eval.backends.mocked.MockedBackend):

    <fixture_dir>/<task>/<config>/<model>/sample-NN/emission-AA.json

Each emission fixture stores both the request (system + messages) and
the response (content, token counts, latency). The MockedBackend reads
these by attempt index, in order.
"""

from __future__ import annotations

import dataclasses
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from strand_eval.types import EmissionResult, Message, RunMetrics, TaskMetrics


FIXTURE_SCHEMA_VERSION = 1


def _message_to_dict(msg: Message) -> dict[str, str]:
    return {"role": msg.role.value, "content": msg.content}


def _emission_to_dict(em: EmissionResult) -> dict[str, Any]:
    """Render an EmissionResult into the fixture JSON shape.

    The shape mirrors the Anthropic API response so MockedBackend reads
    fixtures and live raw_responses with the same parser: token counts
    live under a nested ``usage`` block, stop_reason at the top level.
    """
    return {
        "content": em.content,
        "model": em.model,
        "latency_ms": em.latency_ms,
        "stop_reason": em.finish_reason,
        "token_source": em.token_source,
        "usage": {
            "input_tokens": em.input_tokens,
            "output_tokens": em.output_tokens,
            "cache_read_input_tokens": em.cache_read_input_tokens,
            "cache_creation_input_tokens": em.cache_creation_input_tokens,
        },
    }


def cell_dir(
    fixture_root: Path,
    task: str,
    config: str,
    model: str,
    sample_index: int,
) -> Path:
    """Build the fixture directory for one (task, config, model, sample) cell."""
    return (
        Path(fixture_root)
        / task
        / config
        / model
        / f"sample-{sample_index:02d}"
    )


def write_fixture(
    fixture_root: Path,
    task: str,
    config: str,
    model: str,
    sample_index: int,
    attempt: int,
    request_messages: list[Message],
    response: EmissionResult,
    temperature: float = 0.7,
) -> Path:
    """Persist one emission as a fixture file.

    Returns the path written. Caller is responsible for matching
    `attempt` to the orchestrator's per-turn index so MockedBackend can
    replay them in order.
    """
    cdir = cell_dir(fixture_root, task, config, model, sample_index)
    cdir.mkdir(parents=True, exist_ok=True)
    path = cdir / f"emission-{attempt:02d}.json"
    payload = {
        "schema_version": FIXTURE_SCHEMA_VERSION,
        "recorded_at": datetime.now(timezone.utc).isoformat(),
        "model": response.model,
        "request": {
            "messages": [_message_to_dict(m) for m in request_messages],
            "temperature": temperature,
        },
        "response": _emission_to_dict(response),
    }
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
    return path


def read_fixture(path: Path) -> dict[str, Any]:
    """Load a fixture file as a dict.

    The MockedBackend has its own loader specialized to EmissionResult;
    this helper is for tests and recording-pipeline introspection.
    """
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _serialize_task_metrics(tm: TaskMetrics) -> dict[str, Any]:
    """Render a TaskMetrics as a JSON-safe dict.

    Drops `transcript` and `raw_response` to keep the summary small;
    full transcripts live under `transcripts/` per the proposal. The
    per-attempt ``attempts`` records keep the prompt-cache usage fields
    (``cache_read_input_tokens`` / ``cache_creation_input_tokens``)
    distinct from the uncached ``input_tokens`` count, per attempt.
    Backends without cache telemetry record 0 for both — the fields are
    never estimated.
    """
    return {
        "task_id": tm.task_id,
        "config": tm.config,
        "sample_index": tm.sample_index,
        "model": tm.model,
        "success": tm.success,
        "converged_at_attempt": tm.converged_at_attempt,
        "num_emissions": len(tm.emissions),
        "total_input_tokens": tm.total_input_tokens,
        "total_output_tokens": tm.total_output_tokens,
        "total_cache_read_tokens": tm.total_cache_read_tokens,
        "total_cache_creation_tokens": tm.total_cache_creation_tokens,
        "attempts": [
            {
                "attempt": i,
                "input_tokens": em.input_tokens,
                "output_tokens": em.output_tokens,
                "cache_read_input_tokens": em.cache_read_input_tokens,
                "cache_creation_input_tokens": em.cache_creation_input_tokens,
                "token_source": em.token_source,
            }
            for i, em in enumerate(tm.emissions)
        ],
        "total_cost_usd": tm.total_cost_usd,
        "token_source": tm.token_source,
        "verify_oks": [vr.ok for vr in tm.verify_results],
        "run_ok": tm.run_result.ok if tm.run_result else None,
    }


def _serialize_transcript(tm: TaskMetrics) -> dict[str, Any]:
    """Render the full per-cell transcript for archival."""
    return {
        "task_id": tm.task_id,
        "config": tm.config,
        "sample_index": tm.sample_index,
        "model": tm.model,
        "success": tm.success,
        "converged_at_attempt": tm.converged_at_attempt,
        "transcript": [_message_to_dict(m) for m in tm.transcript],
        "emissions": [_emission_to_dict(em) for em in tm.emissions],
    }


def write_summary(output_dir: Path, run: RunMetrics) -> Path:
    """Write the per-run summary.json with aggregate metrics."""
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    cells: list[dict[str, Any]] = []
    for samples in run.config_name_to_task_metrics.values():
        for tm in samples:
            cells.append(_serialize_task_metrics(tm))
    payload = {
        "run_id": run.run_id,
        "started_at": run.started_at,
        "finished_at": run.finished_at,
        "total_cost_usd": run.total_cost_usd,
        "total_cells": run.total_cells,
        "converged_cells": run.converged_cells,
        "cells": cells,
    }
    path = output_dir / "summary.json"
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
    return path


def write_transcripts(
    output_dir: Path,
    run: RunMetrics,
    only_failures: bool = True,
) -> list[Path]:
    """Write per-cell transcripts.

    By default only failed cells get transcripts written (successful
    runs are uninteresting). Pass ``only_failures=False`` to capture
    every transcript.
    """
    transcripts_dir = Path(output_dir) / "transcripts"
    transcripts_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for samples in run.config_name_to_task_metrics.values():
        for tm in samples:
            if only_failures and tm.success:
                continue
            filename = f"{tm.task_id}-{tm.config}-{tm.model}-sample-{tm.sample_index:02d}.json"
            path = transcripts_dir / filename
            payload = _serialize_transcript(tm)
            path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
            written.append(path)
    return written


def load_summary(summary_path: Path) -> dict[str, Any]:
    """Load a previously-written summary.json for the report subcommand."""
    return json.loads(Path(summary_path).read_text(encoding="utf-8"))


def new_run_dir(runs_root: Path, timestamp: Optional[str] = None) -> Path:
    """Create and return a new timestamped run directory."""
    if timestamp is None:
        timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%d-%H%M%S")
    path = Path(runs_root) / timestamp
    path.mkdir(parents=True, exist_ok=True)
    return path
