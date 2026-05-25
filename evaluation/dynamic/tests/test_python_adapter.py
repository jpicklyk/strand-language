"""Unit tests for the Python language adapter.

Tests that require mypy on PATH are gated behind ``pytest.mark.skipif``
so the suite passes in environments without it (CI is one such
environment; contributors without a Python toolchain installed are
another). The skipped tests are still useful regression coverage when
the toolchain IS present.
"""

from __future__ import annotations

import shutil
import textwrap
from pathlib import Path

import pytest

from strand_eval.languages import get_language
from strand_eval.languages.python import PythonLanguage  # noqa: F401 — registers @register_language
from strand_eval.types import FeedbackFormat, VerifyResult


_HAS_MYPY = shutil.which("mypy") is not None


# ----------------------------------------------------------------------
# extract_program
# ----------------------------------------------------------------------


def test_extract_program_fenced() -> None:
    """A fenced ```python block is the happy path the prompt instructs."""
    adapter = PythonLanguage()
    response = textwrap.dedent(
        """
        Sure, here is the program.

        ```python
        def main() -> None:
            print(120)


        if __name__ == "__main__":
            main()
        ```

        Let me know if you need anything else.
        """
    ).strip()
    extracted = adapter.extract_program(response)
    assert "def main()" in extracted
    assert "print(120)" in extracted
    assert "Let me know" not in extracted
    assert "Sure, here" not in extracted


def test_extract_program_fenced_generic_fallback() -> None:
    """A bare fenced block (no language tag) is still extracted."""
    adapter = PythonLanguage()
    response = textwrap.dedent(
        """
        ```
        print(42)
        ```
        """
    ).strip()
    extracted = adapter.extract_program(response)
    assert "print(42)" in extracted


def test_extract_program_no_fence_returns_whole() -> None:
    """When no fence is present, the response is taken as-is."""
    adapter = PythonLanguage()
    response = "def main() -> None:\n    print(0)\n"
    extracted = adapter.extract_program(response)
    assert "def main()" in extracted


def test_extract_program_empty() -> None:
    """An empty response yields an empty program."""
    adapter = PythonLanguage()
    assert adapter.extract_program("") == ""


# ----------------------------------------------------------------------
# registry
# ----------------------------------------------------------------------


def test_python_adapter_registered() -> None:
    """The @register_language decorator wires the adapter under "python"."""
    adapter = get_language("python")
    assert isinstance(adapter, PythonLanguage)
    assert adapter.name == "python"


# ----------------------------------------------------------------------
# verify
# ----------------------------------------------------------------------


def test_verify_reports_missing_mypy_clearly() -> None:
    """If mypy is missing, verify returns a clear ok=False with guidance.

    This is the path operators see in CI without mypy installed. The
    error_prose mentions mypy and points at installation; the
    error_json carries a stable ``kind`` so the orchestrator can branch
    on it if needed.
    """
    adapter = PythonLanguage()
    if _HAS_MYPY:
        pytest.skip("mypy IS on PATH; this test only meaningful when it is not")
    result = adapter.verify("def main() -> None:\n    pass\n")
    assert result.ok is False
    assert result.error_prose is not None
    assert "mypy" in result.error_prose.lower()
    assert result.error_json is not None
    assert result.error_json.get("kind") == "missing-dependency"
    assert result.error_json.get("missing") == "mypy"


@pytest.mark.skipif(not _HAS_MYPY, reason="mypy not on PATH")
def test_verify_valid() -> None:
    """A well-typed program passes mypy --strict cleanly."""
    adapter = PythonLanguage()
    program = textwrap.dedent(
        """
        def factorial(n: int) -> int:
            if n == 0:
                return 1
            return n * factorial(n - 1)


        def main() -> None:
            print(factorial(5))


        if __name__ == "__main__":
            main()
        """
    ).lstrip()
    result = adapter.verify(program)
    assert result.ok is True, result.error_prose


@pytest.mark.skipif(not _HAS_MYPY, reason="mypy not on PATH")
def test_verify_type_error() -> None:
    """Feeding mypy a type error yields ok=False with structured details."""
    adapter = PythonLanguage()
    program = textwrap.dedent(
        """
        def main() -> None:
            x: int = "string"
            print(x)


        if __name__ == "__main__":
            main()
        """
    ).lstrip()
    result = adapter.verify(program)
    assert result.ok is False
    assert result.error_prose is not None
    assert "error" in result.error_prose.lower()
    assert result.error_json is not None
    errors = result.error_json.get("errors", [])
    # At least one structured error entry with a line number.
    assert any("line" in e and "message" in e for e in errors), errors


# ----------------------------------------------------------------------
# run
# ----------------------------------------------------------------------


def test_run_pure_value() -> None:
    """Running the factorial reference yields the expected pure value."""
    adapter = PythonLanguage()
    program = textwrap.dedent(
        """
        def factorial(n: int) -> int:
            if n == 0:
                return 1
            return n * factorial(n - 1)


        def main() -> None:
            print(factorial(5))


        if __name__ == "__main__":
            main()
        """
    ).lstrip()
    expected = {
        "check": "pure-value",
        "value": {
            "type": "int",
            "representation": {"python": "120", "strand": "IntV(120)"},
        },
    }
    result = adapter.run(program, expected)
    assert result.ok is True, result.error
    assert result.actual_output == "120"


def test_run_pure_value_mismatch() -> None:
    """A program whose output does not match returns ok=False."""
    adapter = PythonLanguage()
    program = "def main() -> None:\n    print(999)\n\n\nif __name__ == '__main__':\n    main()\n"
    expected = {
        "check": "pure-value",
        "value": {
            "type": "int",
            "representation": {"python": "120", "strand": "IntV(120)"},
        },
    }
    result = adapter.run(program, expected)
    assert result.ok is False
    assert result.actual_output == "999"
    assert result.expected_output == "120"


def test_run_state_machine() -> None:
    """State-machine reference: final state after 3 unit events is True."""
    adapter = PythonLanguage()
    # tests/ -> dynamic/ -> tasks/03-toggle-machine/reference.python.py
    program_path = (
        Path(__file__).resolve().parent.parent
        / "tasks"
        / "03-toggle-machine"
        / "reference.python.py"
    )
    if not program_path.exists():
        # Fallback to the static-tasks copy when the dynamic-side suite
        # has not been retrofitted yet (older clones, intermediate states
        # during P3-side development).
        program_path = (
            Path(__file__).resolve().parent.parent.parent
            / "tasks"
            / "03-toggle-machine"
            / "reference.python.py"
        )
    program = program_path.read_text(encoding="utf-8")
    expected = {
        "check": "state-machine",
        "events": [{"tag": "unit"}, {"tag": "unit"}, {"tag": "unit"}],
        "final_state": {"python": "True", "strand": "BoolV(true)"},
    }
    result = adapter.run(program, expected)
    assert result.ok is True, result.error


def test_run_schema_validation_returns_ok() -> None:
    """For schema-validation, verify is the gate; run is a no-op."""
    adapter = PythonLanguage()
    expected = {"check": "schema-validation"}
    result = adapter.run("def main() -> None:\n    print('ok')\n", expected)
    assert result.ok is True


# ----------------------------------------------------------------------
# format_feedback
# ----------------------------------------------------------------------


def test_format_feedback_prose() -> None:
    """PROSE mode returns the prose summary unchanged."""
    adapter = PythonLanguage()
    vr = VerifyResult(
        ok=False,
        error_prose="mypy --strict reported the following errors:\n  line 3, col 5: error: Bad type",
        error_json={
            "kind": "mypy",
            "exit_code": 1,
            "errors": [
                {"file": "program.py", "line": 3, "col": 5, "severity": "error", "message": "Bad type"},
            ],
        },
    )
    out = adapter.format_feedback(vr, FeedbackFormat.PROSE)
    assert "Bad type" in out
    assert "{" not in out  # No JSON in prose mode.


def test_format_feedback_json() -> None:
    """JSON mode returns a pretty-printed JSON object."""
    adapter = PythonLanguage()
    vr = VerifyResult(
        ok=False,
        error_prose="prose summary",
        error_json={"kind": "mypy", "exit_code": 1, "errors": []},
    )
    out = adapter.format_feedback(vr, FeedbackFormat.JSON)
    assert '"kind": "mypy"' in out


def test_format_feedback_both() -> None:
    """BOTH mode includes prose and a JSON block."""
    adapter = PythonLanguage()
    vr = VerifyResult(
        ok=False,
        error_prose="prose summary",
        error_json={"kind": "mypy", "exit_code": 1, "errors": []},
    )
    out = adapter.format_feedback(vr, FeedbackFormat.BOTH)
    assert "prose summary" in out
    assert "```json" in out


def test_format_feedback_ok() -> None:
    """A successful VerifyResult yields a positive message."""
    adapter = PythonLanguage()
    vr = VerifyResult(ok=True)
    out = adapter.format_feedback(vr, FeedbackFormat.PROSE)
    assert "succeeded" in out.lower()
