"""Loop convergence with the synthetic backend.

Uses a FakeLanguage whose verify/run results are scripted in lockstep
with the synthetic backend's canned emissions. Asserts that the loop
converges when verify+run pass on the first attempt and converges later
when the first attempt fails and the second succeeds.
"""

from __future__ import annotations

from pathlib import Path

from strand_eval.backends.synthetic import SyntheticBackend
from strand_eval.languages import Language
from strand_eval.loop import run_task
from strand_eval.types import (
    EmissionResult,
    FeedbackFormat,
    RunResult,
    TaskSpec,
    VerifyResult,
)


class FakeLanguage(Language):
    """A scriptable Language for loop-logic tests.

    Construct with two parallel lists of verify_results and run_results.
    Each call to verify or run pops the head off its list. extract_program
    just returns the model response verbatim.
    """

    name = "fake"

    def __init__(
        self,
        verify_results: list[VerifyResult],
        run_results: list[RunResult],
    ):
        self._verify = list(verify_results)
        self._run = list(run_results)
        self.verify_calls: list[str] = []
        self.run_calls: list[tuple[str, dict]] = []

    def extract_program(self, model_response: str) -> str:
        return model_response

    def verify(self, program_source: str) -> VerifyResult:
        self.verify_calls.append(program_source)
        if not self._verify:
            return VerifyResult(ok=True, canonical_dag_json="{}")
        return self._verify.pop(0)

    def run(self, program_source: str, expected: dict) -> RunResult:
        self.run_calls.append((program_source, expected))
        if not self._run:
            return RunResult(ok=True, actual_output=expected.get("expected_output"))
        return self._run.pop(0)

    def format_feedback(self, verify_result: VerifyResult, fmt: FeedbackFormat) -> str:
        return f"VERIFY FAILED: {verify_result.error_prose}"


def _task() -> TaskSpec:
    return TaskSpec(
        task_id="t1",
        description="Test task",
        expected={"expected_output": 42},
        reference_solutions={},
        directory=Path("."),
    )


def test_converges_on_first_attempt():
    backend = SyntheticBackend(responses=["program-source-A"])
    language = FakeLanguage(
        verify_results=[VerifyResult(ok=True, canonical_dag_json="{}")],
        run_results=[RunResult(ok=True, actual_output=42)],
    )
    metrics = run_task(
        task=_task(),
        config_name="cfg",
        backend=backend,
        language=language,
        model="claude-sonnet-4-6",
        sample_index=0,
        max_retries=5,
        feedback_format=FeedbackFormat.PROSE,
        system_prompt="you are testing",
    )
    assert metrics.success is True
    assert metrics.converged_at_attempt == 0
    assert len(metrics.emissions) == 1
    assert len(metrics.verify_results) == 1
    assert metrics.run_result is not None
    assert metrics.run_result.ok is True
    # System + user + assistant in the transcript on a one-shot success.
    roles = [m.role.value for m in metrics.transcript]
    assert roles == ["system", "user", "assistant"]


def test_converges_after_verify_failure():
    """Verify fails on attempt 0, succeeds on attempt 1."""
    backend = SyntheticBackend(responses=["bad-program", "good-program"])
    language = FakeLanguage(
        verify_results=[
            VerifyResult(ok=False, error_prose="missing root"),
            VerifyResult(ok=True, canonical_dag_json="{}"),
        ],
        run_results=[RunResult(ok=True, actual_output=42)],
    )
    metrics = run_task(
        task=_task(),
        config_name="cfg",
        backend=backend,
        language=language,
        model="claude-sonnet-4-6",
        sample_index=0,
        max_retries=5,
    )
    assert metrics.success is True
    assert metrics.converged_at_attempt == 1
    assert len(metrics.emissions) == 2
    # Loop should have appended feedback as a user message between the two emissions.
    roles = [m.role.value for m in metrics.transcript]
    assert roles == ["user", "assistant", "user", "assistant"]
    assert "VERIFY FAILED" in metrics.transcript[2].content
    assert backend.calls[1][0][-1].content.startswith("VERIFY FAILED")


def test_converges_after_run_failure():
    """Verify passes both times; first run fails, second succeeds."""
    backend = SyntheticBackend(responses=["program-A", "program-B"])
    language = FakeLanguage(
        verify_results=[
            VerifyResult(ok=True),
            VerifyResult(ok=True),
        ],
        run_results=[
            RunResult(ok=False, error="wrong output", actual_output=99, expected_output=42),
            RunResult(ok=True, actual_output=42),
        ],
    )
    metrics = run_task(
        task=_task(),
        config_name="cfg",
        backend=backend,
        language=language,
        model="claude-sonnet-4-6",
        sample_index=0,
    )
    assert metrics.success is True
    assert metrics.converged_at_attempt == 1
    assert metrics.run_result is not None
    assert metrics.run_result.ok is True
    # The first attempt's user feedback should describe the run failure.
    user_feedback = metrics.transcript[2].content
    assert "expected" in user_feedback.lower()
    assert "99" in user_feedback


def test_unconverged_when_all_attempts_fail():
    backend = SyntheticBackend(responses=["x", "y", "z"])
    language = FakeLanguage(
        verify_results=[
            VerifyResult(ok=False, error_prose="err-1"),
            VerifyResult(ok=False, error_prose="err-2"),
            VerifyResult(ok=False, error_prose="err-3"),
        ],
        run_results=[],
    )
    metrics = run_task(
        task=_task(),
        config_name="cfg",
        backend=backend,
        language=language,
        model="claude-sonnet-4-6",
        sample_index=0,
        max_retries=3,
    )
    assert metrics.success is False
    assert metrics.converged_at_attempt is None
    assert len(metrics.emissions) == 3
    assert len(metrics.verify_results) == 3


def test_token_accounting_sums_emissions():
    backend = SyntheticBackend(responses=[
        EmissionResult(
            content="x", input_tokens=100, output_tokens=20,
            model="claude-sonnet-4-6", latency_ms=10, finish_reason="stop",
        ),
        EmissionResult(
            content="y", input_tokens=50, output_tokens=10,
            model="claude-sonnet-4-6", latency_ms=5, finish_reason="stop",
        ),
    ])
    language = FakeLanguage(
        verify_results=[
            VerifyResult(ok=False, error_prose="bad"),
            VerifyResult(ok=True),
        ],
        run_results=[RunResult(ok=True, actual_output=42)],
    )
    metrics = run_task(
        task=_task(),
        config_name="cfg",
        backend=backend,
        language=language,
        model="claude-sonnet-4-6",
        sample_index=0,
    )
    assert metrics.success is True
    assert metrics.total_input_tokens == 150
    assert metrics.total_output_tokens == 30
    # Cost is at Sonnet 4.6 pricing: 150/1e6 * 3 + 30/1e6 * 15
    expected_cost = round(150 / 1_000_000 * 3.0 + 30 / 1_000_000 * 15.0, 6)
    assert abs(metrics.total_cost_usd - expected_cost) < 1e-9
