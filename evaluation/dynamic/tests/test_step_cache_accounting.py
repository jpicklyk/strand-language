"""Step-mode prompt-cache accounting.

Step mode has no API client of its own; cache figures arrive only when
the caller relays a real API response's usage block via
response-metadata.json. These tests pin both directions:

- metadata WITH cache fields -> per-attempt records and cell totals in
  summary.json, priced at the cache read/write rates;
- no metadata (byte-proxy counting) -> cache fields present and zero,
  never estimated.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

import strand_eval.tokens as tokens_mod
from strand_eval.languages import LANGUAGE_REGISTRY, Language
from strand_eval.step import step_advance, step_init
from strand_eval.types import FeedbackFormat, RunResult, VerifyResult


class _PassLanguage(Language):
    """Always verifies and runs successfully; converges on attempt 0."""

    name = "step-cache-test"

    def extract_program(self, model_response: str) -> str:
        return model_response

    def verify(self, program_source: str) -> VerifyResult:
        return VerifyResult(ok=True, canonical_dag_json="{}")

    def run(self, program_source: str, expected: dict) -> RunResult:
        return RunResult(ok=True, actual_output=expected.get("expected_output"))

    def format_feedback(self, verify_result: VerifyResult, fmt: FeedbackFormat) -> str:
        return "VERIFY FAILED"


@pytest.fixture
def step_session(tmp_path, monkeypatch):
    """A fresh step session with a registered pass-through language and a
    forced byte-proxy counter (no network, regardless of env keys)."""
    monkeypatch.setitem(LANGUAGE_REGISTRY, "step-cache-test", _PassLanguage)
    monkeypatch.setenv("STRAND_EVAL_TOKEN_COUNT", "byte-proxy")
    monkeypatch.setattr(tokens_mod, "_default_counter", None)

    session_dir = tmp_path / "cell"
    step_init(
        session_dir=session_dir,
        task_id="t-cache",
        config_name="cfg",
        model="claude-sonnet-4-7",
        language_name="step-cache-test",
        system_prompt="big static system prompt",
        task_prompt="the task",
        expected={"expected_output": 1},
        max_retries=3,
        sample_index=0,
    )
    return session_dir


def _summary(session_dir: Path) -> dict:
    return json.loads((session_dir / "summary.json").read_text(encoding="utf-8"))


def test_caller_metadata_cache_fields_flow_to_summary(step_session):
    turn_dir = step_session / "turn-00"
    (turn_dir / "response.md").write_text("program", encoding="utf-8")
    (turn_dir / "response-metadata.json").write_text(
        json.dumps(
            {
                "input_tokens": 100,
                "output_tokens": 50,
                "cache_read_input_tokens": 20000,
                "cache_creation_input_tokens": 1500,
                "token_source": "api",
            }
        ),
        encoding="utf-8",
    )
    state = step_advance(step_session)
    assert state.status == "converged"

    summary = _summary(step_session)
    # Cell totals carry the cache flow under distinct labels.
    assert summary["total_input_tokens"] == 100
    assert summary["total_output_tokens"] == 50
    assert summary["total_cache_read_tokens"] == 20000
    assert summary["total_cache_creation_tokens"] == 1500
    # Per-attempt records keep the API usage fields distinct.
    assert summary["attempts"] == [
        {
            "attempt": 0,
            "turn_type": "emission",
            "input_tokens": 100,
            "output_tokens": 50,
            "cache_read_input_tokens": 20000,
            "cache_creation_input_tokens": 1500,
            "token_source": "api",
        }
    ]
    assert summary["token_source"] == "api"


def test_caller_metadata_cache_cost_priced_at_cache_rates(step_session):
    turn_dir = step_session / "turn-00"
    (turn_dir / "response.md").write_text("program", encoding="utf-8")
    (turn_dir / "response-metadata.json").write_text(
        json.dumps(
            {
                "input_tokens": 1_000_000,
                "output_tokens": 0,
                "cache_read_input_tokens": 1_000_000,
                "cache_creation_input_tokens": 1_000_000,
                "token_source": "api",
            }
        ),
        encoding="utf-8",
    )
    step_advance(step_session)
    summary = _summary(step_session)
    # Sonnet 4.7: $3.00/M uncached input + $0.30/M cache read + $3.75/M
    # cache write. Cache traffic must NOT be billed at the input rate.
    assert summary["total_cost_usd"] == pytest.approx(3.00 + 0.30 + 3.75)


def test_byte_proxy_mode_reports_zero_cache_fields(step_session):
    """Without caller metadata there is no API usage block to read; the
    byte-proxy counter cannot observe cache behavior, so the fields are
    recorded as zero rather than estimated."""
    turn_dir = step_session / "turn-00"
    (turn_dir / "response.md").write_text("program", encoding="utf-8")
    state = step_advance(step_session)
    assert state.status == "converged"

    summary = _summary(step_session)
    assert summary["token_source"] == "byte-proxy"
    assert summary["total_cache_read_tokens"] == 0
    assert summary["total_cache_creation_tokens"] == 0
    assert len(summary["attempts"]) == 1
    attempt = summary["attempts"][0]
    assert attempt["cache_read_input_tokens"] == 0
    assert attempt["cache_creation_input_tokens"] == 0
    assert attempt["token_source"] == "byte-proxy"


def test_metadata_without_cache_fields_defaults_to_zero(step_session):
    """A caller that supplies plain counts but no cache fields gets zeros,
    not an error and not a fabricated estimate."""
    turn_dir = step_session / "turn-00"
    (turn_dir / "response.md").write_text("program", encoding="utf-8")
    (turn_dir / "response-metadata.json").write_text(
        json.dumps({"input_tokens": 10, "output_tokens": 5}), encoding="utf-8"
    )
    step_advance(step_session)
    summary = _summary(step_session)
    assert summary["total_cache_read_tokens"] == 0
    assert summary["total_cache_creation_tokens"] == 0
    assert summary["token_source"] == "caller"
