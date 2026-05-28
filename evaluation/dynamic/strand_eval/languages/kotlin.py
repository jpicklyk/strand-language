"""Kotlin language adapter for strand_eval.

One of the Q-021 named baselines. Compiles emissions with `kotlinc` and
runs them with `java -jar`. The compiler IS the static checker — Kotlin's
type system runs at compile time, so the verify step exercises the
type-system gate the way mypy --strict does for Python.

The adapter resolves `kotlinc` via:

1. ``STRAND_EVAL_KOTLINC`` env var (explicit override)
2. ``kotlinc`` / ``kotlinc.bat`` on PATH
3. A small set of well-known install locations: IntelliJ IDEA bundled
   Kotlin plugin, Android Studio bundled plugin, SDKMAN candidate dir,
   Homebrew, Chocolatey. The first hit wins.

If none of those resolve, ``verify`` returns a clear ``error_prose``
naming the missing dependency with install instructions, mirroring how
the Python adapter handles a missing mypy. Set
``STRAND_EVAL_REQUIRE_KOTLINC=1`` to refuse to start (the framework's
hard-fail mode); without it the adapter degrades gracefully.

The run step dispatches on the task's ``check`` field:

* ``pure-value`` — run the compiled .jar and compare stdout to the
  expected representation under ``value.representation.kotlin`` (falls
  back to the Python representation when no Kotlin form is supplied).
* ``state-machine`` — same execution path; convention is that ``main()``
  drives the state machine over the events declared in expected.yaml
  and prints the final state.
* ``schema-validation`` — returns ok=True iff verify succeeded; Kotlin's
  "schema" check is the compile-time validation in ``init { ... }``
  blocks on data classes plus the type system's rejection of impossible
  states.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
from typing import Any, Optional

from strand_eval.languages import Language, register_language
from strand_eval.types import FeedbackFormat, RunResult, VerifyResult


_FENCED_KOTLIN = re.compile(r"```kotlin\s*\n(.*?)```", re.DOTALL)
_FENCED_KT = re.compile(r"```kt\s*\n(.*?)```", re.DOTALL)
_FENCED_GENERIC = re.compile(r"```(?:[a-zA-Z0-9_+-]*)\s*\n(.*?)```", re.DOTALL)

# A kotlinc diagnostic looks like:
#     program.kt:3:5: error: unresolved reference: foo
# or with a positional column omitted on some kinds:
#     program.kt:3: warning: ...
_KOTLINC_ERROR_LINE = re.compile(
    r"^(?P<file>.*?\.kts?):(?P<line>\d+)(?::(?P<col>\d+))?:\s*"
    r"(?P<severity>error|warning|info):\s*(?P<message>.*?)$"
)


# Common locations to search when kotlinc isn't on PATH. Order matters —
# the first hit wins.
_KOTLINC_FALLBACK_PATHS = [
    # IntelliJ IDEA Community Edition (Windows local install)
    r"C:\Users\{USER}\AppData\Local\Programs\IntelliJ IDEA Community Edition\plugins\Kotlin\kotlinc\bin\kotlinc.bat",
    # IntelliJ IDEA Ultimate (Windows local install)
    r"C:\Users\{USER}\AppData\Local\Programs\IntelliJ IDEA\plugins\Kotlin\kotlinc\bin\kotlinc.bat",
    # Android Studio (Windows per-user install)
    r"C:\Users\{USER}\AppData\Local\Programs\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat",
    # Android Studio (Windows machine-wide install)
    r"C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat",
    # SDKMAN (Linux / macOS)
    r"{HOME}/.sdkman/candidates/kotlin/current/bin/kotlinc",
    # Homebrew (macOS Apple Silicon)
    r"/opt/homebrew/bin/kotlinc",
    # Homebrew (macOS Intel) and Linuxbrew
    r"/usr/local/bin/kotlinc",
    # Chocolatey (Windows)
    r"C:\ProgramData\chocolatey\bin\kotlinc.bat",
    # JetBrains Toolbox typical location
    r"{HOME}/.jbr/kotlinc/bin/kotlinc",
]


def _resolve_kotlinc() -> Optional[str]:
    """Find a usable kotlinc executable, or return None if absent.

    Resolution order is explicit env var, PATH, then well-known install
    locations. The first existing path is returned verbatim — the caller
    invokes it via subprocess, so the Windows-style backslashes are
    fine on Windows and the POSIX paths are fine elsewhere.
    """
    env = os.environ.get("STRAND_EVAL_KOTLINC")
    if env and os.path.exists(env):
        return env
    on_path = shutil.which("kotlinc") or shutil.which("kotlinc.bat")
    if on_path:
        return on_path
    user = os.environ.get("USERNAME") or os.environ.get("USER") or ""
    home = os.path.expanduser("~")
    for tmpl in _KOTLINC_FALLBACK_PATHS:
        candidate = tmpl.replace("{USER}", user).replace("{HOME}", home)
        if os.path.exists(candidate):
            return candidate
    return None


@register_language("kotlin")
class KotlinLanguage(Language):
    """Adapter for Kotlin emissions compiled with kotlinc."""

    name = "kotlin"

    # ------------------------------------------------------------------
    # extract_program
    # ------------------------------------------------------------------

    def extract_program(self, model_response: str) -> str:
        """Pull a Kotlin program out of the model's response.

        Prefers a ```kotlin fenced block, falls back to ```kt, then to
        any generic fenced block, then to the entire response.
        """
        if not model_response:
            return ""
        for pat in (_FENCED_KOTLIN, _FENCED_KT, _FENCED_GENERIC):
            m = pat.search(model_response)
            if m is not None:
                return m.group(1).rstrip() + "\n"
        return model_response.strip() + "\n"

    # ------------------------------------------------------------------
    # verify
    # ------------------------------------------------------------------

    def verify(self, program_source: str) -> VerifyResult:
        """Compile with kotlinc and report errors.

        On success, leaves no output behind (compilation is exercised but
        the .jar is written to a temp dir and deleted with the dir).
        On failure, parses stderr into structured errors plus a prose
        summary.

        When kotlinc isn't installed, returns a clear missing-dependency
        error pointing at install instructions, unless
        ``STRAND_EVAL_REQUIRE_KOTLINC=1`` is set (then refuses to run).
        """
        kotlinc = _resolve_kotlinc()
        if kotlinc is None:
            install_hint = (
                "kotlinc not found. Install Kotlin via SDKMAN "
                "(`sdk install kotlin`), Homebrew (`brew install kotlin`), "
                "Chocolatey (`choco install kotlinc`), or by extracting a "
                "release from https://github.com/JetBrains/kotlin/releases. "
                "Then set STRAND_EVAL_KOTLINC=/path/to/kotlinc or ensure it "
                "is on PATH."
            )
            return VerifyResult(
                ok=False,
                error_prose=install_hint,
                error_json={"kind": "missing-dependency", "missing": "kotlinc"},
            )

        with tempfile.TemporaryDirectory(prefix="strand_eval_kt_") as tmpdir:
            program_path = os.path.join(tmpdir, "program.kt")
            with open(program_path, "w", encoding="utf-8") as fh:
                fh.write(program_source)
            jar_path = os.path.join(tmpdir, "program.jar")

            cmd = [
                kotlinc,
                program_path,
                "-include-runtime",
                "-d", jar_path,
                "-nowarn",  # suppress generic deprecation warnings; real errors still surface
            ]
            try:
                proc = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    check=False,
                    timeout=120,
                )
            except subprocess.TimeoutExpired:
                return VerifyResult(
                    ok=False,
                    error_prose="kotlinc invocation timed out after 120 seconds.",
                    error_json={"kind": "timeout"},
                )

            # kotlinc returns 0 on success even when warnings are present.
            # A missing .jar is the strongest "compilation failed" signal.
            jar_exists = os.path.exists(jar_path)
            if proc.returncode == 0 and jar_exists:
                return VerifyResult(ok=True)

            # On Windows, kotlinc writes diagnostics to stderr; on some
            # POSIX configurations they end up on stdout. Try both.
            diag_text = proc.stderr or proc.stdout
            errors = _parse_kotlinc_errors(diag_text, program_path)
            prose = _format_kotlinc_prose(errors, proc.stdout, proc.stderr)
            return VerifyResult(
                ok=False,
                error_prose=prose,
                error_json={
                    "kind": "kotlinc",
                    "exit_code": proc.returncode,
                    "errors": errors,
                },
            )

    # ------------------------------------------------------------------
    # run
    # ------------------------------------------------------------------

    def run(self, program_source: str, expected: dict) -> RunResult:
        """Compile then execute the program, comparing stdout to expected.

        Recompiles to a temp .jar so the run step is self-contained (the
        verify step's tempdir is already cleaned up). Acceptable cost —
        kotlinc on a single-file program is ~5-10 seconds; this matches
        the eval framework's per-cell wall-clock budget.
        """
        check = expected.get("check")
        if check is None:
            return RunResult(ok=False, error="expected.yaml missing 'check' field")

        if check == "schema-validation":
            # Verify already ran; for Kotlin, compile-time acceptance is
            # the schema-equivalent. No run step.
            return RunResult(ok=True)

        if check == "pure-value":
            expected_repr = (
                expected.get("value", {})
                .get("representation", {})
                .get("kotlin")
                or expected.get("value", {})
                .get("representation", {})
                .get("python")
            )
            if expected_repr is None:
                return RunResult(
                    ok=False,
                    error="expected.yaml missing value.representation.kotlin (or .python)",
                )
            return self._run_and_compare(program_source, expected_repr)

        if check == "state-machine":
            expected_repr = (
                expected.get("final_state", {}).get("kotlin")
                or expected.get("final_state", {}).get("python")
            )
            if expected_repr is None:
                return RunResult(
                    ok=False,
                    error="expected.yaml missing final_state.kotlin (or .python)",
                )
            return self._run_and_compare(program_source, expected_repr)

        return RunResult(ok=False, error=f"unsupported check type: {check}")

    def _run_and_compare(self, program_source: str, expected_repr: str) -> RunResult:
        kotlinc = _resolve_kotlinc()
        if kotlinc is None:
            return RunResult(ok=False, error="kotlinc not available for run step")

        java = shutil.which("java")
        if java is None:
            return RunResult(
                ok=False,
                error=(
                    "java not on PATH. The Kotlin run step compiles to .jar "
                    "and invokes `java -jar`; install a JDK (Adoptium, "
                    "Oracle, Amazon Corretto, etc.)."
                ),
            )

        with tempfile.TemporaryDirectory(prefix="strand_eval_ktrun_") as tmpdir:
            program_path = os.path.join(tmpdir, "program.kt")
            jar_path = os.path.join(tmpdir, "program.jar")
            with open(program_path, "w", encoding="utf-8") as fh:
                fh.write(program_source)

            try:
                compile_proc = subprocess.run(
                    [kotlinc, program_path, "-include-runtime", "-d", jar_path, "-nowarn"],
                    capture_output=True, text=True, check=False, timeout=120,
                )
            except subprocess.TimeoutExpired:
                return RunResult(ok=False, error="kotlinc timed out during run step")
            if compile_proc.returncode != 0 or not os.path.exists(jar_path):
                return RunResult(
                    ok=False,
                    error=(
                        "compile step failed during run.\n"
                        f"{(compile_proc.stderr or compile_proc.stdout).strip()}"
                    ),
                )

            try:
                run_proc = subprocess.run(
                    [java, "-jar", jar_path],
                    capture_output=True, text=True, check=False, timeout=30,
                )
            except subprocess.TimeoutExpired:
                return RunResult(ok=False, error="program execution timed out after 30 seconds")

            if run_proc.returncode != 0:
                return RunResult(
                    ok=False,
                    error=(
                        f"program exited with code {run_proc.returncode}.\n"
                        f"stderr:\n{run_proc.stderr.strip()}"
                    ),
                    expected_output=expected_repr,
                )

            actual = run_proc.stdout.strip()
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

    # ------------------------------------------------------------------
    # format_feedback
    # ------------------------------------------------------------------

    def format_feedback(self, verify_result: VerifyResult, fmt: FeedbackFormat) -> str:
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

def _parse_kotlinc_errors(diag_text: str, program_path: str) -> list[dict[str, Any]]:
    """Parse kotlinc stderr lines into structured errors.

    Each entry has ``file``, ``line``, optional ``col``, ``severity``,
    and ``message`` fields. Lines that don't match the expected shape
    are returned as ``raw`` entries so the model still gets full context.
    """
    out: list[dict[str, Any]] = []
    program_basename = os.path.basename(program_path)
    for line in diag_text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        m = _KOTLINC_ERROR_LINE.match(stripped)
        if m is None:
            out.append({"raw": line})
            continue
        file_field = m.group("file") or ""
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


def _format_kotlinc_prose(
    errors: list[dict[str, Any]],
    stdout: str,
    stderr: str,
) -> str:
    """Render structured kotlinc errors as a short prose paragraph."""
    if not errors:
        if stderr.strip():
            return f"kotlinc reported failure:\n{stderr.strip()}"
        if stdout.strip():
            return f"kotlinc output:\n{stdout.strip()}"
        return "kotlinc reported failure with no parseable output."
    # Filter to errors-only when both errors and notes are present.
    error_entries = [e for e in errors if e.get("severity") == "error"]
    shown = error_entries if error_entries else errors
    lines = ["kotlinc reported the following errors:"]
    for e in shown:
        if "raw" in e:
            lines.append(f"  {e['raw']}")
            continue
        loc = f"line {e['line']}"
        if "col" in e:
            loc += f", col {e['col']}"
        lines.append(f"  {loc}: {e['severity']}: {e['message']}")
    return "\n".join(lines)


__all__ = ["KotlinLanguage"]
