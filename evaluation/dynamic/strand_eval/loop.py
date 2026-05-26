"""Retry-loop orchestrator.

`run_task` drives the emit -> verify -> revise cycle for one
(task, config, model, sample) cell. The loop is language-agnostic
and backend-agnostic; the orchestrator dispatches verification through
the Language adapter and emission through the EmissionBackend.

The semantics described in proposals/model-api-integration.md (sec 5.2)
are implemented here:

- A turn begins with backend.emit(messages, model).
- The emission is appended to the conversation as an assistant message.
- The program is extracted and verified.
- On verify failure, formatted feedback is appended as a user message
  and the loop continues.
- On verify success, the program is executed. On runtime failure,
  feedback is appended and the loop continues.
- On runtime success the loop records `converged_at_attempt` and returns.
- After `max_retries` failed attempts the loop returns with
  `success=False` and `converged_at_attempt=None`.
"""

from __future__ import annotations

import json
from typing import TYPE_CHECKING

from strand_eval.backends.base import EmissionBackend
from strand_eval.metrics import estimate_cost
from strand_eval.types import (
    FeedbackFormat,
    Message,
    Role,
    RunResult,
    TaskMetrics,
    TaskSpec,
    VerifyResult,
)

if TYPE_CHECKING:
    from strand_eval.languages import Language


def _format_run_feedback(run_result: RunResult) -> str:
    """Render a runtime failure as model-readable user feedback."""
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


def _format_verify_feedback(
    language: "Language",
    verify_result: VerifyResult,
    fmt: FeedbackFormat,
) -> str:
    """Delegate verify feedback formatting to the Language adapter.

    The Language adapter is the authoritative source for how to phrase
    a verifier error to its model. The orchestrator hands it the
    requested format (prose / json / both) and uses what it returns.
    """
    return language.format_feedback(verify_result, fmt)


def _render_task_user_prompt(task: TaskSpec, template: str) -> str:
    """Fill the task prompt template.

    The template supports the placeholders ``{description}`` and
    ``{expected_json}``. Extra placeholders are ignored (str.format would
    raise KeyError; we use a safe substitution).
    """
    description = task.description
    expected_json = json.dumps(task.expected, indent=2, sort_keys=True) if task.expected else ""
    out = template
    out = out.replace("{description}", description)
    out = out.replace("{expected_json}", expected_json)
    return out


def run_task(
    task: TaskSpec,
    config_name: str,
    backend: EmissionBackend,
    language: "Language",
    model: str,
    sample_index: int,
    max_retries: int = 5,
    feedback_format: FeedbackFormat = FeedbackFormat.PROSE,
    system_prompt: str = "",
    task_prompt_template: str = "{description}",
) -> TaskMetrics:
    """Drive one (task, config, model, sample) retry loop.

    Returns a populated TaskMetrics regardless of outcome. The caller is
    responsible for aggregating across samples and writing the result.
    """
    metrics = TaskMetrics(
        task_id=task.task_id,
        config=config_name,
        sample_index=sample_index,
        model=model,
        success=False,
        converged_at_attempt=None,
    )

    # Build initial conversation.
    messages: list[Message] = []
    if system_prompt:
        messages.append(Message(role=Role.SYSTEM, content=system_prompt))
    user_prompt = _render_task_user_prompt(task, task_prompt_template)
    messages.append(Message(role=Role.USER, content=user_prompt))
    metrics.transcript.extend(messages)

    for attempt in range(max_retries):
        emission = backend.emit(messages, model)
        metrics.emissions.append(emission)
        metrics.total_input_tokens += emission.input_tokens
        metrics.total_output_tokens += emission.output_tokens
        metrics.total_cache_read_tokens += emission.cache_read_input_tokens
        metrics.total_cache_creation_tokens += emission.cache_creation_input_tokens

        assistant_msg = Message(role=Role.ASSISTANT, content=emission.content)
        messages.append(assistant_msg)
        metrics.transcript.append(assistant_msg)

        program_source = language.extract_program(emission.content)
        verify_result = language.verify(program_source)
        metrics.verify_results.append(verify_result)

        if not verify_result.ok:
            feedback = _format_verify_feedback(language, verify_result, feedback_format)
            user_msg = Message(role=Role.USER, content=feedback)
            messages.append(user_msg)
            metrics.transcript.append(user_msg)
            continue

        run_result = language.run(program_source, task.expected)
        metrics.run_result = run_result
        if not run_result.ok:
            feedback = _format_run_feedback(run_result)
            user_msg = Message(role=Role.USER, content=feedback)
            messages.append(user_msg)
            metrics.transcript.append(user_msg)
            # Clear run_result so a subsequent successful attempt overwrites it cleanly.
            metrics.run_result = None
            continue

        metrics.success = True
        metrics.converged_at_attempt = attempt
        break

    # Finalize cost estimate (rough; the metrics module's table-formatter
    # uses the same MODEL_PRICING table for the per-task report).
    metrics.total_cost_usd = estimate_cost(metrics, model)
    return metrics
