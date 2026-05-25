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
    """One model call's output, normalized across backends."""

    content: str
    input_tokens: int
    output_tokens: int
    model: str
    latency_ms: int
    finish_reason: str
    raw_response: Optional[dict[str, Any]] = None


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
    """Per-(task, config, sample) metrics emitted by the orchestrator."""

    task_id: str
    config: str
    sample_index: int
    model: str
    success: bool
    converged_at_attempt: Optional[int]
    emissions: list[EmissionResult] = field(default_factory=list)
    total_input_tokens: int = 0
    total_output_tokens: int = 0
    total_cost_usd: float = 0.0
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
