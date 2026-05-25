"""Python+type-hints language adapter for strand_eval.

The adapter is one of the two initial-slice baselines (alongside the
Strand adapter). It implements the Language ABC defined in
strand_eval.languages.__init__:

    extract_program(model_response)  -> str
    verify(program_source)           -> VerifyResult
    run(program_source, expected)    -> RunResult
    format_feedback(verify_result, fmt) -> str

The verification step shells out to ``mypy --strict`` against a temp
file. If mypy is not on PATH the verify step returns a clear error
explaining the missing dependency (the orchestrator's tests skip
mypy-requiring cases via ``pytest.mark.skipif``).

The run step dispatches on the task's ``check`` field:

* ``pure-value`` runs ``python <file>`` and compares stdout to the
  expected representation under ``value.representation.python``.
* ``state-machine`` runs ``python <file>`` and compares stdout to the
  expected representation under ``final_state.python``. The convention
  for state-machine references is that ``main()`` runs the machine
  over the events declared in ``expected.yaml`` and prints the final
  state (see ``evaluation/tasks/03-toggle-machine/reference.python.py``).
* ``schema-validation`` returns ok=True if verify already passed; the
  Python "schema" check is mypy's type acceptance.

The feedback formatting collapses verifier output into one of three
shapes (prose, json, both) so the retry loop can A/B-test what the
model sees on failure.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any

from strand_eval.languages import Language, register_language
from strand_eval.types import FeedbackFormat, RunResult, VerifyResult


_FENCED_PYTHON = re.compile(r"```python\s*\n(.*?)```", re.DOTALL)
_FENCED_GENERIC = re.compile(r"```(?:[a-zA-Z0-9_+-]*)\s*\n(.*?)```", re.DOTALL)
_MYPY_ERROR_LINE = re.compile(
    # Match a path (which on Windows may begin with a drive letter like
    # `C:`), followed by ``:<line>[:<col>]: <severity>: <message>``. The
    # path matches lazily up to the first ``:digit`` so a Windows
    # `C:\...` prefix is preserved as part of the file field rather than
    # being split on the drive-letter colon.
    r"^(?P<file>(?:[A-Za-z]:)?[^:]*):(?P<line>\d+):(?:(?P<col>\d+):)?\s*"
    r"(?P<severity>error|note|warning):\s*(?P<message>.*?)$"
)


@register_language("python")
class PythonLanguage(Language):
    """Adapter for Python+type-hints emissions."""

    name = "python"

    # ------------------------------------------------------------------
    # extract_program
    # ------------------------------------------------------------------

    def extract_program(self, model_response: str) -> str:
        """Pull a Python program out of the model's response.

        Looks for a fenced ```python``` block first; falls back to the
        first generic fenced block; falls back to the entire response
        if nothing fenced is present. The prompt instructs the model to
        emit one fenced ```python``` block, so the first case is the
        happy path.
        """
        if not model_response:
            return ""
        m = _FENCED_PYTHON.search(model_response)
        if m is not None:
            return m.group(1).rstrip() + "\n"
        m = _FENCED_GENERIC.search(model_response)
        if m is not None:
            return m.group(1).rstrip() + "\n"
        return model_response.strip() + "\n"

    # ------------------------------------------------------------------
    # verify
    # ------------------------------------------------------------------

    def verify(self, program_source: str) -> VerifyResult:
        """Run ``mypy --strict`` over the program and report errors.

        Returns ok=True iff mypy exits cleanly. On non-zero exit, the
        mypy stdout is parsed into structured ``error_json`` entries
        and a prose summary. If mypy is not on PATH, returns ok=False
        with a clear note so the orchestrator (and tests) can skip.
        """
        mypy_path = shutil.which("mypy")
        if mypy_path is None:
            return VerifyResult(
                ok=False,
                error_prose=(
                    "mypy is not on PATH. The Python language adapter requires "
                    "mypy for static verification. Install it with "
                    "`pip install mypy` and ensure it is callable from PATH."
                ),
                error_json={
                    "kind": "missing-dependency",
                    "missing": "mypy",
                },
            )

        with tempfile.TemporaryDirectory(prefix="strand_eval_py_") as tmpdir:
            program_path = os.path.join(tmpdir, "program.py")
            with open(program_path, "w", encoding="utf-8") as fh:
                fh.write(program_source)

            cmd = [
                mypy_path,
                "--strict",
                "--no-incremental",
                "--no-error-summary",
                "--show-column-numbers",
                "--hide-error-context",
                program_path,
            ]
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                check=False,
            )

            if proc.returncode == 0:
                return VerifyResult(ok=True)

            errors = _parse_mypy_errors(proc.stdout, program_path)
            prose = _format_mypy_prose(errors, proc.stdout, proc.stderr)
            return VerifyResult(
                ok=False,
                error_prose=prose,
                error_json={
                    "kind": "mypy",
                    "exit_code": proc.returncode,
                    "errors": errors,
                },
            )

    # ------------------------------------------------------------------
    # run
    # ------------------------------------------------------------------

    def run(self, program_source: str, expected: dict) -> RunResult:
        """Execute the program and compare stdout to the expected value.

        Dispatches on ``expected["check"]``:

        * ``pure-value`` — ``python program.py`` and compare stdout to
          ``expected["value"]["representation"]["python"]``.
        * ``state-machine`` — the Python reference's ``main()`` is
          expected to print the final state. Compares stdout to
          ``expected["final_state"]["python"]``.
        * ``schema-validation`` — verify already gates schema acceptance
          for Python (mypy is the static checker). Returns ok=True.
        """
        check = expected.get("check")
        if check is None:
            return RunResult(
                ok=False,
                error="expected.yaml missing 'check' field",
            )

        if check == "schema-validation":
            return RunResult(ok=True)

        if check == "pure-value":
            expected_repr = (
                expected.get("value", {})
                .get("representation", {})
                .get("python")
            )
            if expected_repr is None:
                return RunResult(
                    ok=False,
                    error="expected.yaml missing value.representation.python",
                )
            return _run_and_compare(program_source, expected_repr)

        if check == "state-machine":
            expected_repr = (
                expected.get("final_state", {}).get("python")
            )
            if expected_repr is None:
                return RunResult(
                    ok=False,
                    error="expected.yaml missing final_state.python",
                )
            return _run_and_compare(program_source, expected_repr)

        return RunResult(
            ok=False,
            error=f"unsupported check type: {check}",
        )

    # ------------------------------------------------------------------
    # format_feedback
    # ------------------------------------------------------------------

    def format_feedback(
        self,
        verify_result: VerifyResult,
        fmt: FeedbackFormat,
    ) -> str:
        """Render a verify failure as model-readable feedback.

        Three modes:

        * PROSE — just the prose summary.
        * JSON — just the structured JSON (pretty-printed).
        * BOTH — prose, then a JSON block.
        """
        if verify_result.ok:
            return "Verification succeeded."

        prose = verify_result.error_prose or "Verification failed."
        if fmt == FeedbackFormat.PROSE:
            return prose
        if fmt == FeedbackFormat.JSON:
            payload = verify_result.error_json or {"prose": prose}
            return json.dumps(payload, indent=2)
        if fmt == FeedbackFormat.BOTH:
            payload = verify_result.error_json or {}
            json_block = json.dumps(payload, indent=2)
            return (
                f"{prose}\n\n"
                f"Structured error data:\n"
                f"```json\n{json_block}\n```"
            )
        return prose


# ----------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------


def _parse_mypy_errors(stdout: str, program_path: str) -> list[dict[str, Any]]:
    """Parse mypy stdout lines into structured errors.

    Each entry has ``line``, ``col``, ``severity``, and ``message``
    fields. Lines that don't match the expected shape are returned in a
    fallback ``raw`` entry so the model still gets the full context.
    """
    out: list[dict[str, Any]] = []
    program_basename = os.path.basename(program_path)
    for line in stdout.splitlines():
        if not line.strip():
            continue
        m = _MYPY_ERROR_LINE.match(line)
        if m is None:
            out.append({"raw": line})
            continue
        file_field = m.group("file") or ""
        # Normalize the file field so the model sees a stable name
        # rather than the temp-dir path.
        if file_field.endswith(program_basename):
            file_field = program_basename
        entry: dict[str, Any] = {
            "file": file_field,
            "line": int(m.group("line")),
            "severity": m.group("severity"),
            "message": m.group("message").strip(),
        }
        col = m.group("col")
        if col is not None:
            entry["col"] = int(col)
        out.append(entry)
    return out


def _format_mypy_prose(
    errors: list[dict[str, Any]],
    stdout: str,
    stderr: str,
) -> str:
    """Render structured mypy errors as a short prose paragraph."""
    if not errors:
        # No structured matches — fall back to raw output.
        if stdout.strip():
            return f"mypy reported errors:\n{stdout.strip()}"
        if stderr.strip():
            return f"mypy invocation failed:\n{stderr.strip()}"
        return "mypy --strict reported failure with no parseable output."
    lines = ["mypy --strict reported the following errors:"]
    for e in errors:
        if "raw" in e:
            lines.append(f"  {e['raw']}")
            continue
        loc = f"line {e['line']}"
        if "col" in e:
            loc += f", col {e['col']}"
        lines.append(f"  {loc}: {e['severity']}: {e['message']}")
    return "\n".join(lines)


def _run_and_compare(program_source: str, expected_repr: str) -> RunResult:
    """Run a Python program and compare stripped stdout to expected_repr.

    Both sides are stripped of trailing whitespace before comparison.
    """
    with tempfile.TemporaryDirectory(prefix="strand_eval_pyrun_") as tmpdir:
        program_path = os.path.join(tmpdir, "program.py")
        with open(program_path, "w", encoding="utf-8") as fh:
            fh.write(program_source)
        try:
            proc = subprocess.run(
                [sys.executable, program_path],
                capture_output=True,
                text=True,
                check=False,
                timeout=30,
            )
        except subprocess.TimeoutExpired:
            return RunResult(
                ok=False,
                error="program execution timed out after 30 seconds",
            )

        if proc.returncode != 0:
            return RunResult(
                ok=False,
                error=(
                    f"program exited with code {proc.returncode}.\n"
                    f"stderr:\n{proc.stderr.strip()}"
                ),
                expected_output=expected_repr,
            )

        actual = proc.stdout.strip()
        expected_stripped = expected_repr.strip()
        if actual != expected_stripped:
            return RunResult(
                ok=False,
                error=(
                    "program output did not match expected value.\n"
                    f"expected: {expected_stripped!r}\n"
                    f"actual:   {actual!r}"
                ),
                actual_output=actual,
                expected_output=expected_stripped,
            )

        return RunResult(
            ok=True,
            actual_output=actual,
            expected_output=expected_stripped,
        )


__all__ = ["PythonLanguage"]
