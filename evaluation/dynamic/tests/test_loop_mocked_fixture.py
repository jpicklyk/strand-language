"""End-to-end fixture record + replay.

Writes a fixture via the recording module, then loads it through
MockedBackend and runs the loop. Asserts that the replayed emission
matches the recorded content and token counts.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from strand_eval.backends.mocked import FixtureNotFound, MockedBackend
from strand_eval.languages import Language
from strand_eval.loop import run_task
from strand_eval.recording import write_fixture
from strand_eval.types import (
    EmissionResult,
    FeedbackFormat,
    Message,
    Role,
    RunResult,
    TaskSpec,
    VerifyResult,
)


class TrivialLanguage(Language):
    """Always verifies and runs successfully; returns the response verbatim."""

    name = "trivial"

    def extract_program(self, model_response: str) -> str:
        return model_response

    def verify(self, program_source: str) -> VerifyResult:
        return VerifyResult(ok=True, canonical_dag_json="{}")

    def run(self, program_source: str, expected: dict) -> RunResult:
        return RunResult(ok=True, actual_output=expected.get("expected_output"))

    def format_feedback(self, verify_result: VerifyResult, fmt: FeedbackFormat) -> str:
        return "(unused)"


def test_recording_then_replay_round_trip(tmp_path: Path):
    fixture_root = tmp_path / "fixtures"
    task_id = "t1"
    config = "cfg"
    model = "claude-sonnet-4-6"
    sample_index = 0

    # Write one fixture for attempt 0.
    request_messages = [
        Message(role=Role.SYSTEM, content="sys"),
        Message(role=Role.USER, content="user-prompt"),
    ]
    canned_response = EmissionResult(
        content="program-body",
        input_tokens=123,
        output_tokens=45,
        model=model,
        latency_ms=200,
        finish_reason="end_turn",
    )
    fixture_path = write_fixture(
        fixture_root=fixture_root,
        task=task_id,
        config=config,
        model=model,
        sample_index=sample_index,
        attempt=0,
        request_messages=request_messages,
        response=canned_response,
    )
    assert fixture_path.exists(), f"fixture should be persisted at {fixture_path}"

    # Replay via MockedBackend.
    backend = MockedBackend(fixture_dir=fixture_root)
    backend.set_cell(task_id, config, model, sample_index)

    task = TaskSpec(
        task_id=task_id,
        description="user-prompt",
        expected={"expected_output": "ok"},
        reference_solutions={},
        directory=tmp_path,
    )
    metrics = run_task(
        task=task,
        config_name=config,
        backend=backend,
        language=TrivialLanguage(),
        model=model,
        sample_index=sample_index,
        max_retries=2,
        system_prompt="sys",
        task_prompt_template="user-prompt",
    )

    assert metrics.success is True
    assert metrics.converged_at_attempt == 0
    assert len(metrics.emissions) == 1
    em = metrics.emissions[0]
    assert em.content == "program-body"
    assert em.input_tokens == 123
    assert em.output_tokens == 45
    assert em.finish_reason == "end_turn"
    assert metrics.total_input_tokens == 123
    assert metrics.total_output_tokens == 45


def test_mocked_backend_raises_on_missing_fixture(tmp_path: Path):
    backend = MockedBackend(fixture_dir=tmp_path / "fixtures")
    backend.set_cell("never-recorded", "cfg", "model", 0)
    with pytest.raises(FixtureNotFound):
        backend.emit([Message(role=Role.USER, content="hi")], model="model")


def test_set_cell_resets_attempt_counter(tmp_path: Path):
    """Two cells in a row should not bleed attempt indices."""
    fixture_root = tmp_path / "fixtures"
    for cell_idx, content in enumerate(["one", "two"]):
        write_fixture(
            fixture_root=fixture_root,
            task=f"task{cell_idx}",
            config="cfg",
            model="m",
            sample_index=0,
            attempt=0,
            request_messages=[Message(role=Role.USER, content="u")],
            response=EmissionResult(
                content=content,
                input_tokens=1,
                output_tokens=1,
                model="m",
                latency_ms=0,
                finish_reason="stop",
            ),
        )

    backend = MockedBackend(fixture_dir=fixture_root)
    backend.set_cell("task0", "cfg", "m", 0)
    em0 = backend.emit([Message(role=Role.USER, content="u")], model="m")
    backend.set_cell("task1", "cfg", "m", 0)
    em1 = backend.emit([Message(role=Role.USER, content="u")], model="m")
    assert em0.content == "one"
    assert em1.content == "two"
