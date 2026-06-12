"""Step-mode reference-query channel (Q-060 M-2).

A response whose first non-blank line is ``strand:need <names...>`` is a
reference request: the harness appends only the requested reference
text, the turn is labeled and token-attributed separately, reference
turns do not consume emission attempts, and a per-cell cap stops a
looping agent. Everything runs on the file-IPC step path with a
pass-through language — no API, no network.
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
    """Always verifies and runs successfully; converges on any emission."""

    name = "step-ref-test"

    def extract_program(self, model_response: str) -> str:
        return model_response

    def verify(self, program_source: str) -> VerifyResult:
        return VerifyResult(ok=True, canonical_dag_json="{}")

    def run(self, program_source: str, expected: dict) -> RunResult:
        return RunResult(ok=True, actual_output=expected.get("expected_output"))

    def format_feedback(self, verify_result: VerifyResult, fmt: FeedbackFormat) -> str:
        return "VERIFY FAILED"


@pytest.fixture
def references_dir(tmp_path) -> Path:
    """A tiny controlled reference set: one topic file with one builtin
    signature and one prelude-style catalog line."""
    refs = tmp_path / "refs"
    refs.mkdir()
    (refs / "prelude.md").write_text(
        "# Reference: prelude — test fixture\n"
        "\n"
        "MARKER-PRELUDE-SECTION\n"
        "\n"
        "    fsWrite   — Fs.Write test catalog line\n",
        encoding="utf-8",
    )
    (refs / "builtins.md").write_text(
        "# Reference: builtins — test fixture\n"
        "\n"
        "    strand-builtin:List.Map(list, fn: A -> B) -> List<B>\n"
        "        -- MARKER-MAP-COMMENT\n",
        encoding="utf-8",
    )
    return refs


@pytest.fixture
def make_session(tmp_path, monkeypatch, references_dir):
    """Factory for fresh step sessions wired to the fixture references
    and a forced byte-proxy counter (no network regardless of env)."""
    monkeypatch.setitem(LANGUAGE_REGISTRY, "step-ref-test", _PassLanguage)
    monkeypatch.setenv("STRAND_EVAL_TOKEN_COUNT", "byte-proxy")
    monkeypatch.setattr(tokens_mod, "_default_counter", None)

    counter = {"n": 0}

    def _make(max_retries: int = 3, max_reference_turns: int = 3, refs=None):
        counter["n"] += 1
        session_dir = tmp_path / f"cell-{counter['n']}"
        step_init(
            session_dir=session_dir,
            task_id="t-ref",
            config_name="cfg",
            model="claude-sonnet-4-7",
            language_name="step-ref-test",
            system_prompt="core system prompt",
            task_prompt="the task",
            expected={"expected_output": 1},
            max_retries=max_retries,
            sample_index=0,
            max_reference_turns=max_reference_turns,
            references_dir=refs if refs is not None else references_dir,
        )
        return session_dir

    return _make


def _respond(session_dir: Path, turn: int, text: str) -> None:
    (session_dir / f"turn-{turn:02d}" / "response.md").write_text(
        text, encoding="utf-8"
    )


def _summary(session_dir: Path) -> dict:
    return json.loads((session_dir / "summary.json").read_text(encoding="utf-8"))


# --------------------------------------------------------------------------
# need -> emit smoke (the two-turn cell)
# --------------------------------------------------------------------------


def test_two_turn_need_then_emit_cell(make_session):
    session = make_session()

    # Turn 0: the agent requests a topic instead of emitting.
    _respond(session, 0, "strand:need prelude\n")
    state = step_advance(session)
    assert state.status == "needs-response"
    assert state.reference_turns == 1

    # The next prompt carries the served reference text and only that
    # (no verifier feedback, no fabricated content).
    prompt = (session / "turn-01" / "prompt.md").read_text(encoding="utf-8")
    assert "[strand reference response]" in prompt
    assert "MARKER-PRELUDE-SECTION" in prompt
    assert "VERIFY FAILED" not in prompt
    assert "Reference turns used: 1 of 3" in prompt

    # Turn 1: the agent emits; the pass language converges.
    _respond(session, 1, "the program\n")
    state = step_advance(session)
    assert state.status == "converged"

    summary = _summary(session)
    assert summary["total_attempts"] == 2
    assert summary["emission_attempts"] == 1
    assert summary["reference_turns"] == 1
    # First-pass is preserved: the first EMISSION converged.
    assert summary["converged_at_attempt"] == 0
    assert [a["turn_type"] for a in summary["attempts"]] == [
        "reference-request",
        "emission",
    ]


def test_builtin_signature_request_served_verbatim(make_session):
    session = make_session()
    _respond(session, 0, "strand:need List.Map\n")
    step_advance(session)
    prompt = (session / "turn-01" / "prompt.md").read_text(encoding="utf-8")
    assert "strand-builtin:List.Map(list, fn: A -> B) -> List<B>" in prompt
    assert "MARKER-MAP-COMMENT" in prompt


# --------------------------------------------------------------------------
# miss behavior
# --------------------------------------------------------------------------


def test_unknown_name_gets_suggestion_never_fabrication(make_session):
    session = make_session()
    _respond(session, 0, "strand:need Lst.Mapp\n")
    state = step_advance(session)
    assert state.status == "needs-response"
    # The miss still consumes a reference turn (the agent got an answer).
    assert state.reference_turns == 1

    prompt = (session / "turn-01" / "prompt.md").read_text(encoding="utf-8")
    assert "Unknown reference name 'Lst.Mapp'" in prompt
    assert "never fabricates" in prompt
    assert "Did you mean" in prompt
    assert "strand-builtin:Lst.Mapp" not in prompt

    record = state.reference_requests[0]
    assert record["requested"] == ["Lst.Mapp"]
    assert record["served"] == []
    assert record["missed"] == ["Lst.Mapp"]


# --------------------------------------------------------------------------
# cap enforcement
# --------------------------------------------------------------------------


def test_cap_blocks_further_references_and_consumes_attempts(make_session):
    session = make_session(max_retries=2, max_reference_turns=1)

    # Turn 0: first reference request — served (1 of 1).
    _respond(session, 0, "strand:need prelude\n")
    state = step_advance(session)
    assert state.status == "needs-response"
    assert state.reference_turns == 1

    # Turn 1: second request — over the cap: budget notice, consumes an
    # emission attempt (1 of 2 used).
    _respond(session, 1, "strand:need builtins\n")
    state = step_advance(session)
    assert state.status == "needs-response"
    assert state.reference_turns == 1  # NOT incremented past the cap
    prompt = (session / "turn-02" / "prompt.md").read_text(encoding="utf-8")
    assert "Reference budget exhausted" in prompt
    # Nothing served past the cap: the over-cap builtins request must not
    # have appended its content (the turn-0 prelude text legitimately
    # remains in the rendered conversation history).
    assert "MARKER-MAP-COMMENT" not in prompt

    # Turn 2: a looping agent requests again — second emission attempt
    # consumed, max_retries=2 reached, cell exhausts. Termination holds.
    _respond(session, 2, "strand:need builtins\n")
    state = step_advance(session)
    assert state.status == "exhausted"

    summary = _summary(session)
    assert summary["success"] is False
    assert summary["reference_turns"] == 1
    assert summary["total_attempts"] == 3
    # Over-cap requests are still labeled by what the agent did.
    assert [a["turn_type"] for a in summary["attempts"]] == [
        "reference-request",
        "reference-request",
        "reference-request",
    ]
    assert summary["emission_attempts"] == 0


def test_reference_turns_do_not_consume_emission_retries(make_session):
    """Three reference turns then max_retries emissions must all be
    available: the cap and the retry budget are independent."""
    session = make_session(max_retries=1, max_reference_turns=2)
    _respond(session, 0, "strand:need prelude\n")
    assert step_advance(session).status == "needs-response"
    _respond(session, 1, "strand:need builtins\n")
    assert step_advance(session).status == "needs-response"
    # Both reference turns used; the single emission attempt remains.
    _respond(session, 2, "the program\n")
    state = step_advance(session)
    assert state.status == "converged"
    assert state.converged_at_attempt == 0


# --------------------------------------------------------------------------
# token attribution
# --------------------------------------------------------------------------


def test_reference_turn_token_attribution_labels(make_session):
    session = make_session()
    _respond(session, 0, "strand:need prelude\n")
    state = step_advance(session)

    record = state.reference_requests[0]
    assert record["turn"] == 0
    assert record["served"] == ["prelude"]
    assert record["appended_tokens"] > 0
    assert record["token_source"] == "byte-proxy"

    _respond(session, 1, "the program\n")
    step_advance(session)
    summary = _summary(session)

    # The summary relays the per-reference accounting and labels.
    assert summary["reference_requests"][0]["appended_tokens"] > 0
    assert summary["reference_requests"][0]["token_source"] == "byte-proxy"
    ref_attempt, emit_attempt = summary["attempts"]
    assert ref_attempt["turn_type"] == "reference-request"
    assert ref_attempt["token_source"] == "byte-proxy"
    assert emit_attempt["turn_type"] == "emission"
    # The emission turn's input includes the served reference text — the
    # round-trip cost is real input, not hidden.
    assert emit_attempt["input_tokens"] > ref_attempt["input_tokens"]


def test_caller_metadata_applies_to_reference_turns(make_session):
    """A driver relaying real API usage attaches it to reference turns
    exactly like emission turns."""
    session = make_session()
    _respond(session, 0, "strand:need prelude\n")
    (session / "turn-00" / "response-metadata.json").write_text(
        json.dumps({"input_tokens": 777, "output_tokens": 9, "token_source": "api"}),
        encoding="utf-8",
    )
    state = step_advance(session)
    assert state.emissions[0]["input_tokens"] == 777
    assert state.emissions[0]["token_source"] == "api"
    assert state.emissions[0]["turn_type"] == "reference-request"


# --------------------------------------------------------------------------
# protocol edges
# --------------------------------------------------------------------------


def test_fenced_program_always_wins_over_request_line(make_session):
    """A response carrying a fenced code block is a program even if a
    strand:need line appears — a program mentioning the literal string
    can never be swallowed by the reference channel."""
    session = make_session()
    _respond(
        session,
        0,
        "strand:need prelude\n```layer-a\n@v=1 root=x\nx ILT 1\n```\n",
    )
    state = step_advance(session)
    assert state.status == "converged"  # pass language verified it
    assert state.reference_turns == 0
    assert state.emissions[0]["turn_type"] == "emission"


def test_request_not_on_first_line_is_an_emission(make_session):
    session = make_session()
    _respond(session, 0, "Here is my plan.\nstrand:need prelude\n")
    state = step_advance(session)
    assert state.status == "converged"
    assert state.reference_turns == 0


def test_empty_request_serves_topic_index(make_session):
    session = make_session()
    _respond(session, 0, "strand:need\n")
    state = step_advance(session)
    assert state.status == "needs-response"
    assert state.reference_turns == 1
    prompt = (session / "turn-01" / "prompt.md").read_text(encoding="utf-8")
    assert "requires at least one topic" in prompt


def test_default_references_dir_serves_real_sections(make_session):
    """Without an explicit references_dir the packaged
    prompts/references sections are served."""
    session = make_session(refs=False)  # falsy -> step.py default
    _respond(session, 0, "strand:need grammar-codes\n")
    state = step_advance(session)
    assert state.status == "needs-response"
    prompt = (session / "turn-01" / "prompt.md").read_text(encoding="utf-8")
    assert "full Layer A code table" in prompt
