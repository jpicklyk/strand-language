"""Shared dataclasses for the strand_eval framework.

These types form the stable interface between the orchestrator, the
emission backends, and the per-language adapters. Agents P2 (Strand
language adapter) and P3 (Python language adapter) consume this module.
"""

from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Optional


class BackendChoice(str, Enum):
    """Selects which EmissionBackend the orchestrator uses."""

    ANTHROPIC = "anthropic"
    MOCKED = "mocked"
    SYNTHETIC = "synthetic"


class FeedbackFormat(str, Enum):
    """How verifier failures are surfaced to the model on retry."""

    PROSE = "prose"
    JSON = "json"
    BOTH = "both"


class Role(str, Enum):
    """Message role in a chat-style conversation."""

    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"


@dataclass
class Message:
    """One message in the conversation history."""

    role: Role
    content: str


@dataclass
class EmissionResult:
    """One model call's output, normalized across backends.

    ``input_tokens`` is the *uncached* input token count for the call
    (what the Anthropic API also calls "input_tokens"). Cached prefix
    tokens that hit the prompt cache appear under
    ``cache_read_input_tokens``; tokens written into the cache on the
    first call of a window appear under ``cache_creation_input_tokens``.
    Backends that don't support caching populate both as 0.

    ``token_source`` labels where the counts came from (see
    ``strand_eval.tokens``): "api" for real tokenizer counts,
    "byte-proxy" for the chars/4 heuristic, "caller" for explicit
    metadata, "fixture" / "synthetic" for replayed or scripted
    backends. "unknown" marks legacy data recorded before labeling.
    """

    content: str
    input_tokens: int
    output_tokens: int
    model: str
    latency_ms: int
    finish_reason: str
    raw_response: Optional[dict[str, Any]] = None
    cache_read_input_tokens: int = 0
    cache_creation_input_tokens: int = 0
    token_source: str = "unknown"


@dataclass
class VerifyResult:
    """Result of compiling and verifying a program."""

    ok: bool
    error_prose: Optional[str] = None
    error_json: Optional[dict[str, Any]] = None
    canonical_dag_json: Optional[str] = None


@dataclass
class RunResult:
    """Result of executing a verified program against expected output."""

    ok: bool
    error: Optional[str] = None
    actual_output: Optional[Any] = None
    expected_output: Optional[Any] = None


@dataclass
class TaskSpec:
    """One task loaded from disk."""

    task_id: str
    description: str
    expected: dict[str, Any]
    reference_solutions: dict[str, Path]
    directory: Path


@dataclass
class RunConfig:
    """Parameters that drive a single run of the harness."""

    tasks: list[str]
    configurations: list[str]
    models: list[str]
    backend: BackendChoice
    samples_per_cell: int = 5
    max_retries: int = 5
    feedback_format: FeedbackFormat = FeedbackFormat.PROSE
    record_fixtures: bool = False
    output_dir: Optional[Path] = None
    budget_dollars: Optional[float] = None


@dataclass
class TaskMetrics:
    """Per-(task, config, sample) metrics emitted by the orchestrator.

    ``total_input_tokens`` tracks only uncached input. ``total_cache_read_tokens``
    and ``total_cache_creation_tokens`` track the prompt-cache token flow
    separately; the cost calculator in `metrics.py` prices them at their
    respective rates ($0.30/M read, $3.75/M write on Sonnet 4.7).

    ``token_source`` is the aggregate provenance label over the cell's
    emissions ("api", "byte-proxy", ... or "mixed(a+b)"); see
    ``strand_eval.tokens.combine_sources``.
    """

    task_id: str
    config: str
    sample_index: int
    model: str
    success: bool
    converged_at_attempt: Optional[int]
    emissions: list[EmissionResult] = field(default_factory=list)
    total_input_tokens: int = 0
    total_output_tokens: int = 0
    total_cache_read_tokens: int = 0
    total_cache_creation_tokens: int = 0
    total_cost_usd: float = 0.0
    token_source: str = "unknown"
    verify_results: list[VerifyResult] = field(default_factory=list)
    run_result: Optional[RunResult] = None
    transcript: list[Message] = field(default_factory=list)


@dataclass
class RunMetrics:
    """Aggregate metrics across all (task, config, sample) cells in a run."""

    run_id: str
    started_at: str
    finished_at: Optional[str]
    config_name_to_task_metrics: dict[str, list[TaskMetrics]] = field(default_factory=dict)
    total_cost_usd: float = 0.0
    total_cells: int = 0
    converged_cells: int = 0
