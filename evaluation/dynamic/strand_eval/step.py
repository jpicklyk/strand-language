"""Step-mode orchestrator for Claude Code (or any external) driver.

Where `loop.run_task` drives the full retry loop synchronously against a
programmatic `EmissionBackend` (Anthropic API, mocked fixtures, scripted
synthetic), step-mode externalizes the loop across multiple CLI
invocations so a human or a host like Claude Code can author each
emission directly. The framework writes a `prompt.md` per turn; the
caller writes a `response.md` per turn; the framework reads it on the
next invocation and advances.

This sidesteps both the Anthropic API key and the synchronous-blocking
problem of having Claude Code wait inside a Bash subprocess. Each step
is a discrete invocation — read state, advance, write next prompt or
summary, exit.

Session layout::

    <session_dir>/
        session.json                # serialized state across turns
        turn-00/
            prompt.md               # rendered messages stack for the model
            request.json            # structured form for tools
            response.md             # the caller writes their emission here
            response-metadata.json  # optional explicit token counts
            verify.json             # emitter's verify result (after advance)
        turn-01/...
        summary.json                # written on convergence / exhaustion

State transitions::

    init      ->  needs-response   (prompt-00 written)
    needs-response (with response.md) -> needs-response (next prompt)
                                       OR converged (summary written)
                                       OR exhausted (summary written)

Exit codes::

    0  needs-response  — caller should write response.md and re-invoke
    1  converged       — summary.json has the final metrics
    2  exhausted       — summary.json has the partial metrics
"""

from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Optional

from strand_eval.languages import Language, get_language
from strand_eval.metrics import estimate_cost
from strand_eval.types import (
    EmissionResult,
    FeedbackFormat,
    Message,
    Role,
    RunResult,
    TaskMetrics,
    VerifyResult,
)


@dataclass
class StepState:
    """Persisted across step invocations as session.json."""

    session_dir: str
    task_id: str
    config_name: str
    model: str
    sample_index: int
    language_name: str
    system_prompt: str
    task_prompt: str
    feedback_format: str  # FeedbackFormat.value
    max_retries: int
    expected: dict  # task.expected for run-result comparison
    messages: list[dict] = field(default_factory=list)  # serialized Message
    attempt: int = 0  # next attempt number; the prompt written most recently is turn-<attempt>
    emissions: list[dict] = field(default_factory=list)
    verify_results: list[dict] = field(default_factory=list)
    run_result: Optional[dict] = None
    success: bool = False
    converged_at_attempt: Optional[int] = None
    status: str = "needs-response"  # needs-response | converged | exhausted

    def save(self) -> None:
        Path(self.session_dir, "session.json").write_text(
            json.dumps(asdict(self), indent=2), encoding="utf-8"
        )

    @classmethod
    def load(cls, session_dir: Path) -> "StepState":
        data = json.loads((session_dir / "session.json").read_text(encoding="utf-8"))
        return cls(**data)


# --------------------------------------------------------------------------
# Public entry points (called from cli.cmd_step)
# --------------------------------------------------------------------------


def step_init(
    session_dir: Path,
    task_id: str,
    config_name: str,
    model: str,
    language_name: str,
    system_prompt: str,
    task_prompt: str,
    expected: dict,
    feedback_format: FeedbackFormat = FeedbackFormat.PROSE,
    max_retries: int = 5,
    sample_index: int = 0,
) -> StepState:
    """Initialize a new step session. Writes turn-00/prompt.md and exits."""
    session_dir = Path(session_dir).resolve()
    session_dir.mkdir(parents=True, exist_ok=True)

    messages: list[dict] = []
    if system_prompt:
        messages.append({"role": Role.SYSTEM.value, "content": system_prompt})
    messages.append({"role": Role.USER.value, "content": task_prompt})

    state = StepState(
        session_dir=str(session_dir),
        task_id=task_id,
        config_name=config_name,
        model=model,
        sample_index=sample_index,
        language_name=language_name,
        system_prompt=system_prompt,
        task_prompt=task_prompt,
        feedback_format=feedback_format.value,
        max_retries=max_retries,
        expected=expected,
        messages=messages,
        attempt=0,
        status="needs-response",
    )

    _write_prompt_for_turn(state)
    state.save()
    return state


def step_advance(session_dir: Path) -> StepState:
    """Read response.md from the most-recent turn, verify+run, advance.

    Writes turn-(N+1)/prompt.md on retry, or summary.json on convergence
    or exhaustion. Returns the updated state.

    The caller (CLI) inspects state.status to set the exit code.
    """
    session_dir = Path(session_dir).resolve()
    state = StepState.load(session_dir)
    if state.status != "needs-response":
        # Caller is calling advance again after we already finished. Idempotent.
        return state

    turn_dir = session_dir / f"turn-{state.attempt:02d}"
    response_path = turn_dir / "response.md"
    if not response_path.exists():
        raise FileNotFoundError(
            f"Expected response.md at {response_path}. "
            "Write your emission there and re-run `strand-eval step`."
        )
    response_text = response_path.read_text(encoding="utf-8")

    # Optional explicit token counts from the caller. If absent, estimate
    # from byte counts using the static framework's bytes/4 heuristic.
    metadata_path = turn_dir / "response-metadata.json"
    if metadata_path.exists():
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        input_tokens = int(metadata.get("input_tokens", 0))
        output_tokens = int(metadata.get("output_tokens", 0))
        latency_ms = int(metadata.get("latency_ms", 0))
    else:
        # Estimate. Input ~= every message in the stack so far. Output ~= response.
        input_chars = sum(len(m["content"]) for m in state.messages)
        input_tokens = (input_chars + 3) // 4
        output_tokens = (len(response_text) + 3) // 4
        latency_ms = 0

    emission = EmissionResult(
        content=response_text,
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        model=state.model,
        latency_ms=latency_ms,
        finish_reason="end_turn",
    )

    # Append the assistant turn.
    state.messages.append({"role": Role.ASSISTANT.value, "content": response_text})
    state.emissions.append(_emission_to_dict(emission))

    # Verify + run via the language adapter.
    language = get_language(state.language_name)
    program_source = language.extract_program(response_text)
    verify_result = language.verify(program_source)
    state.verify_results.append(_verify_to_dict(verify_result))

    # Save the verifier output for debugging.
    (turn_dir / "verify.json").write_text(
        json.dumps(_verify_to_dict(verify_result), indent=2), encoding="utf-8"
    )

    if not verify_result.ok:
        feedback = language.format_feedback(
            verify_result, FeedbackFormat(state.feedback_format)
        )
        state.messages.append({"role": Role.USER.value, "content": feedback})
        state.attempt += 1
        if state.attempt >= state.max_retries:
            state.status = "exhausted"
            _write_summary(state)
        else:
            _write_prompt_for_turn(state)
        state.save()
        return state

    run_result = language.run(program_source, state.expected)
    if not run_result.ok:
        feedback = _format_run_feedback(run_result)
        state.messages.append({"role": Role.USER.value, "content": feedback})
        state.run_result = None  # clear; the failed run shouldn't pollute final metrics
        state.attempt += 1
        if state.attempt >= state.max_retries:
            state.status = "exhausted"
            _write_summary(state)
        else:
            _write_prompt_for_turn(state)
        state.save()
        return state

    # Converged.
    state.success = True
    state.converged_at_attempt = state.attempt
    state.run_result = _run_to_dict(run_result)
    state.status = "converged"
    _write_summary(state)
    state.save()
    return state


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------


def _write_prompt_for_turn(state: StepState) -> None:
    turn_dir = Path(state.session_dir) / f"turn-{state.attempt:02d}"
    turn_dir.mkdir(parents=True, exist_ok=True)

    # Render the message stack as readable Markdown for the human-facing
    # caller, plus a structured request.json for tooling that wants the
    # raw form (e.g., to forward to the Anthropic SDK later).
    md_lines: list[str] = [
        f"# Turn {state.attempt:02d} of session {Path(state.session_dir).name}",
        "",
        f"Task: `{state.task_id}` | Config: `{state.config_name}` | Model: `{state.model}`",
        f"Attempt: {state.attempt + 1} / {state.max_retries}",
        "",
        "---",
        "",
    ]
    for msg in state.messages:
        role_label = msg["role"].upper()
        md_lines.append(f"## {role_label}")
        md_lines.append("")
        md_lines.append(msg["content"])
        md_lines.append("")
    md_lines.append("---")
    md_lines.append("")
    md_lines.append(
        "Write your response in `response.md` in this turn directory, "
        "then re-run `strand-eval step --session <session_dir>` to advance."
    )
    (turn_dir / "prompt.md").write_text("\n".join(md_lines), encoding="utf-8")

    (turn_dir / "request.json").write_text(
        json.dumps(
            {
                "task_id": state.task_id,
                "config_name": state.config_name,
                "model": state.model,
                "attempt": state.attempt,
                "messages": state.messages,
            },
            indent=2,
        ),
        encoding="utf-8",
    )


def _write_summary(state: StepState) -> None:
    # Build a TaskMetrics-shaped JSON for compatibility with the existing
    # metrics aggregators. We reconstruct EmissionResult and VerifyResult
    # dataclasses from the persisted dicts so estimate_cost() can run.
    emissions = [_emission_from_dict(d) for d in state.emissions]
    verify_results = [_verify_from_dict(d) for d in state.verify_results]
    run_result = _run_from_dict(state.run_result) if state.run_result else None

    metrics = TaskMetrics(
        task_id=state.task_id,
        config=state.config_name,
        sample_index=state.sample_index,
        model=state.model,
        success=state.success,
        converged_at_attempt=state.converged_at_attempt,
        emissions=emissions,
        total_input_tokens=sum(e.input_tokens for e in emissions),
        total_output_tokens=sum(e.output_tokens for e in emissions),
        verify_results=verify_results,
        run_result=run_result,
    )
    metrics.total_cost_usd = estimate_cost(metrics, state.model)

    summary = {
        "task_id": state.task_id,
        "config": state.config_name,
        "model": state.model,
        "sample_index": state.sample_index,
        "success": state.success,
        "status": state.status,
        "converged_at_attempt": state.converged_at_attempt,
        "total_attempts": len(state.emissions),
        "total_input_tokens": metrics.total_input_tokens,
        "total_output_tokens": metrics.total_output_tokens,
        "total_cost_usd": metrics.total_cost_usd,
        "timestamp": int(time.time()),
    }
    (Path(state.session_dir) / "summary.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8"
    )


def _format_run_feedback(run_result: RunResult) -> str:
    lines: list[str] = ["The program verified but did not produce the expected output."]
    if run_result.error:
        lines.append("")
        lines.append(f"Error: {run_result.error}")
    if run_result.actual_output is not None:
        lines.append("")
        lines.append(f"Actual output: {run_result.actual_output!r}")
    if run_result.expected_output is not None:
        lines.append(f"Expected output: {run_result.expected_output!r}")
    lines.append("")
    lines.append("Revise the program so it produces the expected output.")
    return "\n".join(lines)


def _emission_to_dict(e: EmissionResult) -> dict:
    return {
        "content": e.content,
        "input_tokens": e.input_tokens,
        "output_tokens": e.output_tokens,
        "model": e.model,
        "latency_ms": e.latency_ms,
        "finish_reason": e.finish_reason,
    }


def _emission_from_dict(d: dict) -> EmissionResult:
    return EmissionResult(
        content=d["content"],
        input_tokens=d["input_tokens"],
        output_tokens=d["output_tokens"],
        model=d["model"],
        latency_ms=d.get("latency_ms", 0),
        finish_reason=d.get("finish_reason", "end_turn"),
    )


def _verify_to_dict(v: VerifyResult) -> dict:
    return {
        "ok": v.ok,
        "error_prose": v.error_prose,
        "error_json": v.error_json,
        "canonical_dag_json": v.canonical_dag_json,
    }


def _verify_from_dict(d: dict) -> VerifyResult:
    return VerifyResult(
        ok=d["ok"],
        error_prose=d.get("error_prose"),
        error_json=d.get("error_json"),
        canonical_dag_json=d.get("canonical_dag_json"),
    )


def _run_to_dict(r: RunResult) -> dict:
    return {
        "ok": r.ok,
        "error": r.error,
        "actual_output": r.actual_output,
        "expected_output": r.expected_output,
    }


def _run_from_dict(d: Optional[dict]) -> Optional[RunResult]:
    if d is None:
        return None
    return RunResult(
        ok=d["ok"],
        error=d.get("error"),
        actual_output=d.get("actual_output"),
        expected_output=d.get("expected_output"),
    )


__all__ = ["StepState", "step_init", "step_advance"]
