"""Unit tests for the Strand language adapter.

Tests that require the `strand` CLI on disk are gated behind a
``pytest.mark.skipif`` keyed on the resolved CLI path. The CLI is built
by ``./gradlew :cli:installDist`` from ``impl/`` and lands at
``impl/cli/build/install/cli/bin/cli`` (or ``cli.bat`` on Windows).
Suites running without the JVM-side build skip the live tests but still
exercise the program-extraction and feedback-formatting paths via
in-memory ``VerifyResult`` fixtures.
"""

from __future__ import annotations

import json
import textwrap
from pathlib import Path

import pytest

from strand_eval.languages import get_language
from strand_eval.languages.strand import (  # noqa: F401 — registers @register_language
    STRAND_CLI,
    StrandLanguage,
)
from strand_eval.types import FeedbackFormat, VerifyResult


_HAS_STRAND_CLI = STRAND_CLI.exists()


# A known-good density-v4 program: identity factorial. We construct it
# from primitive Layer A density-v4 syntax so the test doesn't depend
# on the corpus file system layout.
_FACTORIAL_DENSITY_V4 = textwrap.dedent(
    """
    @v=1 root=app
    matchBody IF (APP eqInt [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])])
    bodyLam LAM [recurse n] matchBody
    fact FIX factT bodyLam
    app APP fact [5]
    """
).strip() + "\n"


# An intentionally broken program: undeclared root.
_BROKEN_NO_ROOT = textwrap.dedent(
    """
    @v=1 root=missingRoot
    other ILT 42
    """
).strip() + "\n"


# A program with an arity mismatch — `add` takes two args, given one.
_BROKEN_ARITY = textwrap.dedent(
    """
    @v=1 root=expr
    expr APP add [42]
    """
).strip() + "\n"


# ---------------------------------------------------------------------------
# extract_program
# ---------------------------------------------------------------------------


def test_extract_program_fenced() -> None:
    """A fenced ```layer-a block is the happy path."""
    adapter = StrandLanguage()
    response = textwrap.dedent(
        """
        Sure, here is the program.

        ```layer-a
        @v=1 root=app
        app ILT 42
        ```

        Let me know if you need anything else.
        """
    ).strip()
    extracted = adapter.extract_program(response)
    assert "@v=1 root=app" in extracted
    assert "app ILT 42" in extracted
    # The fence content itself shouldn't include the surrounding commentary.
    assert "Sure, here" not in extracted
    assert "Let me know" not in extracted


def test_extract_program_strand_fence_accepted() -> None:
    """A fenced ```strand block is also accepted as a fallback fence label."""
    adapter = StrandLanguage()
    response = textwrap.dedent(
        """
        ```strand
        @v=1 root=app
        app ILT 1
        ```
        """
    ).strip()
    extracted = adapter.extract_program(response)
    assert "@v=1 root=app" in extracted


def test_extract_program_unfenced() -> None:
    """When no fence is present, the whole response is returned."""
    adapter = StrandLanguage()
    raw = "@v=1 root=app\napp ILT 99\n"
    extracted = adapter.extract_program(raw)
    assert "@v=1 root=app" in extracted
    assert "app ILT 99" in extracted


# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------


def test_strand_adapter_registered() -> None:
    """The @register_language decorator wires the adapter under "strand"."""
    adapter = get_language("strand")
    assert isinstance(adapter, StrandLanguage)
    assert adapter.name == "strand"


# ---------------------------------------------------------------------------
# verify (live; require the CLI)
# ---------------------------------------------------------------------------


@pytest.mark.skipif(
    not _HAS_STRAND_CLI,
    reason=f"strand CLI not found at {STRAND_CLI}; run `./gradlew :cli:installDist`",
)
def test_verify_valid_program() -> None:
    """A known-good density-v4 factorial compiles and verifies."""
    adapter = StrandLanguage()
    result = adapter.verify(_FACTORIAL_DENSITY_V4)
    assert result.ok, f"expected ok=True, got error: {result.error_prose!r}"
    assert result.canonical_dag_json is not None
    # The emitted JSON should at least be parseable.
    parsed = json.loads(result.canonical_dag_json)
    assert "root" in parsed
    assert "nodes" in parsed


@pytest.mark.skipif(
    not _HAS_STRAND_CLI,
    reason=f"strand CLI not found at {STRAND_CLI}",
)
def test_verify_syntax_error_reports_line() -> None:
    """A broken program produces a non-ok result with a line number in the prose."""
    adapter = StrandLanguage()
    result = adapter.verify(_BROKEN_NO_ROOT)
    assert not result.ok
    assert result.error_prose is not None
    assert result.error_json is not None
    # The undeclared-root error contains a `line N:` prefix.
    assert "line" in result.error_prose.lower() or "root" in result.error_prose.lower()


@pytest.mark.skipif(
    not _HAS_STRAND_CLI,
    reason=f"strand CLI not found at {STRAND_CLI}",
)
def test_verify_verifier_error_reports_class() -> None:
    """An arity mismatch surfaces as a verifier error with the class name."""
    adapter = StrandLanguage()
    result = adapter.verify(_BROKEN_ARITY)
    assert not result.ok
    assert result.error_prose is not None
    assert result.error_json is not None
    # ArityMismatch should appear in either the prose or the structured payload.
    blob = (result.error_prose or "") + json.dumps(result.error_json or {})
    assert "ArityMismatch" in blob


# ---------------------------------------------------------------------------
# run (live; require the CLI)
# ---------------------------------------------------------------------------


@pytest.mark.skipif(
    not _HAS_STRAND_CLI,
    reason=f"strand CLI not found at {STRAND_CLI}",
)
def test_run_pure_value_factorial() -> None:
    """Run factorial(5) and confirm `value: IntV(v=120)` is reported."""
    adapter = StrandLanguage()
    expected = {
        "check": "pure-value",
        "value": {"representation": {"strand": "IntV(v=120)"}},
    }
    result = adapter.run(_FACTORIAL_DENSITY_V4, expected)
    assert result.ok, (
        f"expected ok=True. error={result.error!r}, actual={result.actual_output!r}"
    )
    assert result.actual_output == "IntV(v=120)"


# ---------------------------------------------------------------------------
# format_feedback
# ---------------------------------------------------------------------------


def test_format_feedback_prose() -> None:
    """A populated verify_result formats to a human-readable prose string."""
    adapter = StrandLanguage()
    vr = VerifyResult(
        ok=False,
        error_prose="line 3: code 'XYZ' is unknown",
        error_json={"phase": "compile", "errors": []},
    )
    formatted = adapter.format_feedback(vr, FeedbackFormat.PROSE)
    assert "failed to compile" in formatted.lower()
    assert "line 3" in formatted
    assert "XYZ" in formatted


def test_format_feedback_json() -> None:
    """JSON format produces a parseable structured payload header."""
    adapter = StrandLanguage()
    vr = VerifyResult(
        ok=False,
        error_prose="prose summary",
        error_json={
            "phase": "verify",
            "errors": [
                {"error_class": "ArityMismatch", "at": "0", "detail": "expected=2"},
            ],
        },
    )
    formatted = adapter.format_feedback(vr, FeedbackFormat.JSON)
    assert "Structured error" in formatted
    # The body should embed the JSON; pull it out and parse.
    json_start = formatted.find("{")
    assert json_start != -1
    parsed = json.loads(formatted[json_start:])
    assert parsed["phase"] == "verify"
    assert parsed["errors"][0]["error_class"] == "ArityMismatch"


def test_format_feedback_both_contains_prose_and_json() -> None:
    """BOTH format contains both the prose and the JSON payload."""
    adapter = StrandLanguage()
    vr = VerifyResult(
        ok=False,
        error_prose="something bad",
        error_json={"phase": "compile", "errors": []},
    )
    formatted = adapter.format_feedback(vr, FeedbackFormat.BOTH)
    assert "something bad" in formatted
    assert "Structured error" in formatted
    assert "\"phase\": \"compile\"" in formatted
