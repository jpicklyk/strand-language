"""Strand language adapter for strand_eval.

Shells out to the `strand` CLI binary built by `./gradlew :cli:installDist`.
Each `Language` method maps to one or more CLI subcommand invocations and
normalizes the CLI's text output (stdout for success, stderr for errors)
back into the framework's shared dataclasses.

The adapter is intentionally subprocess-based: per the model-API-integration
proposal §3.2, shell-out trades per-call latency (a JVM startup per verify)
for trivial integration correctness — the framework consumes the same CLI
surface a real agent would consume, so the contract under test is exactly
the operator-facing one.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

from strand_eval.languages import Language, register_language
from strand_eval.types import FeedbackFormat, RunResult, VerifyResult


# Default path to the gradle-installed CLI. Operators can override via the
# STRAND_CLI env var when the binary lives elsewhere.
_DEFAULT_CLI_RELATIVE = Path("impl/cli/build/install/cli/bin/cli")


def _repo_root() -> Path:
    """Locate the strand-language repository root.

    Walks up from this file until a `CLAUDE.md` and `impl/` sibling appear,
    which is the project-root signature.
    """
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "CLAUDE.md").exists() and (parent / "impl").exists():
            return parent
    # Fallback: three levels up from this file
    # (.../evaluation/dynamic/strand_eval/languages/strand.py → repo root)
    return here.parents[4]


def _resolve_strand_cli() -> Path:
    """Return the path to the strand CLI binary, or a Path that does not exist.

    Resolution order:
      1. `STRAND_CLI` env var, if set.
      2. `<repo-root>/impl/cli/build/install/cli/bin/cli`.
      3. `cli` on PATH (e.g., from a system install).

    On Windows we prefer the `.bat` wrapper when both exist.
    """
    env = os.environ.get("STRAND_CLI")
    if env:
        candidate = Path(env)
        if candidate.exists():
            return candidate
    repo = _repo_root()
    bin_dir = repo / _DEFAULT_CLI_RELATIVE.parent
    # Try Windows .bat first when on Windows, plain `cli` otherwise.
    if os.name == "nt":
        bat = bin_dir / "cli.bat"
        if bat.exists():
            return bat
    cli = bin_dir / "cli"
    if cli.exists():
        return cli
    # Last resort: PATH lookup.
    on_path = shutil.which("strand") or shutil.which("cli")
    return Path(on_path) if on_path else cli


STRAND_CLI: Path = _resolve_strand_cli()


# --------------------------------------------------------------------- parsing


_LAYER_A_FENCED_BLOCK = re.compile(
    r"```(?:layer-a|strand)\s*\n(.*?)```",
    re.DOTALL,
)

# `strand author` reports its compilation errors in the shape:
#     Layer A compilation failed:
#       line N: <message>
#       line N: <message>
_AUTHOR_LINE_ERROR = re.compile(r"^\s*line\s+(\d+):\s*(.*)$")

# `strand verify`/`run`/`machine` report verifier errors in the shape:
#     verification failed:
#       ErrorName(at=#N, ...details...)
# Or for `strand author` post-compile verify:
#     verification failed for <path> (after Layer A compile):
_VERIFIER_LINE_ERROR = re.compile(
    r"^\s*([A-Z][A-Za-z0-9_]+)\((.*)\)\s*$"
)

# Extract `at=#N` (or `at=N`) from a verifier error's details payload.
_AT_NODE = re.compile(r"\bat=#?(\d+)")


def _parse_author_errors(stderr: str) -> tuple[list[dict[str, Any]], str]:
    """Pull `line N: <detail>` entries out of `strand author` stderr.

    Returns (structured_errors, prose). The prose is a human-readable
    concatenation of the line messages preserved in their original order.
    """
    structured: list[dict[str, Any]] = []
    prose_lines: list[str] = []
    for raw in stderr.splitlines():
        m = _AUTHOR_LINE_ERROR.match(raw)
        if m:
            line_no = int(m.group(1))
            detail = m.group(2).strip()
            structured.append({
                "phase": "compile",
                "line": line_no,
                "error_class": "LayerACompileError",
                "detail": detail,
            })
            prose_lines.append(f"line {line_no}: {detail}")
    prose = "\n".join(prose_lines) if prose_lines else stderr.strip()
    return structured, prose


def _parse_verifier_errors(stderr: str) -> tuple[list[dict[str, Any]], str]:
    """Pull verifier errors out of stderr.

    Verifier errors are emitted one per line in the form
    `ErrorName(at=#N, field=value, ...)`. Lines that don't match the
    pattern are passed through to the prose verbatim.
    """
    structured: list[dict[str, Any]] = []
    prose_lines: list[str] = []
    for raw in stderr.splitlines():
        stripped = raw.strip()
        if not stripped:
            continue
        if stripped.startswith("verification failed"):
            continue
        m = _VERIFIER_LINE_ERROR.match(stripped)
        if m:
            error_class = m.group(1)
            details_text = m.group(2)
            at_match = _AT_NODE.search(details_text)
            structured.append({
                "phase": "verify",
                "error_class": error_class,
                "at": at_match.group(1) if at_match else None,
                "detail": details_text.strip(),
            })
            head = f"{error_class}"
            if at_match:
                head += f" at #{at_match.group(1)}"
            prose_lines.append(f"{head}: {details_text}")
        else:
            prose_lines.append(stripped)
    prose = "\n".join(prose_lines) if prose_lines else stderr.strip()
    return structured, prose


# --------------------------------------------------------------------- comparison


@dataclass
class _StrandValue:
    """Parsed `value: ...` line from `strand run`.

    `repr_str` is the raw textual value (e.g., `IntV(v=120)`); `kind` is
    the head constructor when recognizable; `payload` is the head's
    argument when extractable. The framework primarily compares against
    `repr_str`; `kind`/`payload` are conveniences for the success check.
    """

    repr_str: str
    kind: Optional[str] = None
    payload: Optional[str] = None


_VALUE_LINE = re.compile(r"^value:\s*(.*)$")
_VALUE_HEAD = re.compile(r"^([A-Z][A-Za-z0-9_]*)V\((.*)\)$")


def _parse_run_value(stdout: str) -> Optional[_StrandValue]:
    """Extract the value from `strand run` stdout.

    `strand run` emits two lines: `type: <Type>` and `value: <Value>`. We
    return the parsed `value` line. None is returned if no `value:` line
    is present (e.g., a schema-validation program that prints `type:`
    only).
    """
    for line in stdout.splitlines():
        m = _VALUE_LINE.match(line.strip())
        if not m:
            continue
        rest = m.group(1).strip()
        head = _VALUE_HEAD.match(rest)
        if head:
            return _StrandValue(repr_str=rest, kind=head.group(1), payload=head.group(2))
        return _StrandValue(repr_str=rest)
    return None


# Toggle-machine and other state-machine programs emit a trace whose
# tail is a `halt:` line. We extract the final state and any output
# events for the run-result comparison.
_HALT_LINE = re.compile(r"^halt:\s*reason=(\S+)\s+final=(.*)$")
_STEP_OUTPUTS = re.compile(r"outputs=(.*)$")


def _parse_machine_trace(stdout: str) -> dict[str, Any]:
    """Parse a `strand machine` trace into a structured dict.

    Captures `final_state`, `halt_reason`, and `outputs` (the concatenated
    per-step outputs list, useful for tasks that expect specific emissions).
    """
    final_state: Optional[str] = None
    halt_reason: Optional[str] = None
    outputs: list[str] = []
    for line in stdout.splitlines():
        m = _HALT_LINE.match(line.strip())
        if m:
            halt_reason = m.group(1)
            final_state = m.group(2).strip()
            continue
        om = _STEP_OUTPUTS.search(line)
        if om:
            outputs.append(om.group(1).strip())
    return {
        "final_state": final_state,
        "halt_reason": halt_reason,
        "outputs": outputs,
    }


# --------------------------------------------------------------------- adapter


@register_language("strand")
class StrandLanguage(Language):
    """Strand Layer A adapter. Shells out to the `strand` CLI."""

    name = "strand"

    def __init__(self, cli_path: Optional[Path] = None):
        self.cli_path: Path = Path(cli_path) if cli_path else STRAND_CLI

    # ----------------------------------------------------------------- extract

    def extract_program(self, model_response: str) -> str:
        """Pull the Layer A program out of the model's response.

        Looks for a fenced ```layer-a or ```strand code block. Falls back
        to returning the whole response trimmed when no fence is present
        — the model may have ignored the fenced-block instruction.
        """
        m = _LAYER_A_FENCED_BLOCK.search(model_response)
        if m:
            return m.group(1).rstrip() + "\n"
        return model_response.strip() + "\n"

    # ----------------------------------------------------------------- verify

    def verify(self, program_source: str) -> VerifyResult:
        """Compile and verify a Layer A program.

        Writes the source to a temp `.layer-a` file. The `strand author`
        CLI compiles Layer A to canonical dag-json AND runs the verifier
        on the result (without `--emit-json`); `--emit-json` short-circuits
        to compile-only. So the verification step is two CLI calls:

          1. `strand author <file>` — compile + verify; non-zero exit
             surfaces compile errors as `Layer A compilation failed:`
             stderr or verifier errors as `verification failed:` stderr.
          2. On success, `strand author <file> --emit-json` — re-compile
             and capture the canonical dag-json on stdout.

        The two-call cost is one extra JVM startup; acceptable at the
        per-task latencies the retry loop already pays for the model API.
        A future "verifier daemon" mode would amortize it.
        """
        with tempfile.NamedTemporaryFile(
            mode="w",
            suffix=".layer-a",
            delete=False,
            encoding="utf-8",
        ) as f:
            f.write(program_source)
            program_path = Path(f.name)

        try:
            # Step 1: compile + verify. Without --emit-json this runs
            # the full pipeline (compile → ingest → verify) and exits
            # non-zero on any failure.
            verify_run = subprocess.run(
                [str(self.cli_path), "author", str(program_path)],
                capture_output=True,
                text=True,
                timeout=60,
            )

            if verify_run.returncode != 0:
                stderr = verify_run.stderr or ""
                if "Layer A compilation failed" in stderr:
                    structured, prose = _parse_author_errors(stderr)
                    return VerifyResult(
                        ok=False,
                        error_prose=prose or stderr.strip(),
                        error_json={"phase": "compile", "errors": structured},
                    )
                if "verification failed" in stderr:
                    structured, prose = _parse_verifier_errors(stderr)
                    return VerifyResult(
                        ok=False,
                        error_prose=prose or stderr.strip(),
                        error_json={"phase": "verify", "errors": structured},
                    )
                # Schema-check or unexpected error path.
                return VerifyResult(
                    ok=False,
                    error_prose=stderr.strip() or "strand CLI exited non-zero with empty stderr",
                    error_json={
                        "phase": "unknown",
                        "stderr": stderr,
                        "returncode": verify_run.returncode,
                    },
                )

            # Step 2: compile-emit to capture the canonical JSON. Since
            # step 1 succeeded we expect this to succeed too. On the rare
            # chance it doesn't, fall back to ok=True with no JSON.
            emit_run = subprocess.run(
                [str(self.cli_path), "author", str(program_path), "--emit-json"],
                capture_output=True,
                text=True,
                timeout=60,
            )
            canonical = emit_run.stdout.strip() if emit_run.returncode == 0 else None
            return VerifyResult(
                ok=True,
                canonical_dag_json=canonical,
            )
        except subprocess.TimeoutExpired as e:
            return VerifyResult(
                ok=False,
                error_prose=f"strand CLI timed out after {e.timeout}s",
                error_json={"phase": "timeout", "command": e.cmd},
            )
        except FileNotFoundError:
            return VerifyResult(
                ok=False,
                error_prose=(
                    f"strand CLI not found at {self.cli_path}. "
                    f"Build it with `./gradlew :cli:installDist` from impl/."
                ),
                error_json={"phase": "missing_cli", "path": str(self.cli_path)},
            )
        finally:
            try:
                program_path.unlink()
            except OSError:
                pass

    # ----------------------------------------------------------------- run

    def run(self, program_source: str, expected: dict) -> RunResult:
        """Execute a verified program and compare to expected output.

        Dispatches on `expected["check"]`:
          * `pure-value`     → `strand run` + value comparison
          * `state-machine`  → `strand machine` + final-state comparison
          * `schema-validation` → returns ok=True (verify already passed)
        """
        check = expected.get("check")
        if check == "schema-validation":
            return RunResult(
                ok=True,
                actual_output="<schema-validation: no run step>",
                expected_output="<schema-validation: no run step>",
            )
        if check == "pure-value":
            return self._run_pure_value(program_source, expected)
        if check == "state-machine":
            return self._run_state_machine(program_source, expected)
        return RunResult(
            ok=False,
            error=f"unknown expected.check value: {check!r}",
            actual_output=None,
            expected_output=None,
        )

    def _run_pure_value(self, program_source: str, expected: dict) -> RunResult:
        """`strand run` and compare its `value:` line to expected."""
        with tempfile.NamedTemporaryFile(
            mode="w",
            suffix=".layer-a",
            delete=False,
            encoding="utf-8",
        ) as f:
            f.write(program_source)
            program_path = Path(f.name)

        json_path: Optional[Path] = None
        try:
            # Compile to JSON first (verify pipeline runs again here but
            # we already know it passes — this gives us the JSON file we
            # need to invoke `strand run` against).
            emit = subprocess.run(
                [str(self.cli_path), "author", str(program_path), "--emit-json"],
                capture_output=True,
                text=True,
                timeout=60,
            )
            if emit.returncode != 0:
                return RunResult(
                    ok=False,
                    error=f"compile-step failed during run: {emit.stderr.strip()}",
                )
            with tempfile.NamedTemporaryFile(
                mode="w",
                suffix=".json",
                delete=False,
                encoding="utf-8",
            ) as jf:
                jf.write(emit.stdout)
                json_path = Path(jf.name)

            run = subprocess.run(
                [str(self.cli_path), "run", str(json_path), "--grant-all"],
                capture_output=True,
                text=True,
                timeout=60,
            )
            if run.returncode != 0:
                return RunResult(
                    ok=False,
                    error=run.stderr.strip() or "strand run exited non-zero",
                )

            actual = _parse_run_value(run.stdout)
            if actual is None:
                return RunResult(
                    ok=False,
                    error=f"could not parse `value:` from strand run stdout: {run.stdout!r}",
                )

            expected_strand = (
                expected.get("value", {}).get("representation", {}).get("strand")
            )
            if expected_strand is None:
                # Fallback: accept any non-empty value when no explicit Strand
                # representation is supplied. Useful for smoke runs against
                # a partial expected.yaml.
                return RunResult(
                    ok=True,
                    actual_output=actual.repr_str,
                    expected_output=None,
                )

            ok = actual.repr_str == expected_strand
            return RunResult(
                ok=ok,
                error=None if ok else (
                    f"value mismatch: expected {expected_strand!r}, got {actual.repr_str!r}"
                ),
                actual_output=actual.repr_str,
                expected_output=expected_strand,
            )
        except subprocess.TimeoutExpired as e:
            return RunResult(ok=False, error=f"strand run timed out after {e.timeout}s")
        except FileNotFoundError:
            return RunResult(
                ok=False,
                error=f"strand CLI not found at {self.cli_path}",
            )
        finally:
            try:
                program_path.unlink()
            except OSError:
                pass
            if json_path is not None:
                try:
                    json_path.unlink()
                except OSError:
                    pass

    def _run_state_machine(self, program_source: str, expected: dict) -> RunResult:
        """`strand machine` and compare the parsed trace to expected."""
        events = expected.get("events", [])
        with tempfile.NamedTemporaryFile(
            mode="w",
            suffix=".layer-a",
            delete=False,
            encoding="utf-8",
        ) as f:
            f.write(program_source)
            program_path = Path(f.name)
        json_path: Optional[Path] = None
        events_path: Optional[Path] = None
        try:
            emit = subprocess.run(
                [str(self.cli_path), "author", str(program_path), "--emit-json"],
                capture_output=True,
                text=True,
                timeout=60,
            )
            if emit.returncode != 0:
                return RunResult(
                    ok=False,
                    error=f"compile-step failed during run: {emit.stderr.strip()}",
                )
            with tempfile.NamedTemporaryFile(
                mode="w",
                suffix=".json",
                delete=False,
                encoding="utf-8",
            ) as jf:
                jf.write(emit.stdout)
                json_path = Path(jf.name)
            with tempfile.NamedTemporaryFile(
                mode="w",
                suffix=".events.json",
                delete=False,
                encoding="utf-8",
            ) as ef:
                json.dump({"events": events}, ef)
                events_path = Path(ef.name)

            run = subprocess.run(
                [
                    str(self.cli_path),
                    "machine",
                    str(json_path),
                    "--events",
                    str(events_path),
                    "--grant-all",
                ],
                capture_output=True,
                text=True,
                timeout=60,
            )
            if run.returncode != 0:
                return RunResult(
                    ok=False,
                    error=run.stderr.strip() or "strand machine exited non-zero",
                )

            trace = _parse_machine_trace(run.stdout)
            expected_final = expected.get("expected_final_state", {}).get(
                "representation", {}
            ).get("strand")
            actual_final = trace["final_state"]

            if expected_final is None:
                return RunResult(
                    ok=True,
                    actual_output=trace,
                    expected_output=None,
                )

            ok = actual_final == expected_final
            return RunResult(
                ok=ok,
                error=None if ok else (
                    f"final state mismatch: expected {expected_final!r}, "
                    f"got {actual_final!r}"
                ),
                actual_output=trace,
                expected_output=expected_final,
            )
        except subprocess.TimeoutExpired as e:
            return RunResult(ok=False, error=f"strand machine timed out after {e.timeout}s")
        except FileNotFoundError:
            return RunResult(
                ok=False,
                error=f"strand CLI not found at {self.cli_path}",
            )
        finally:
            try:
                program_path.unlink()
            except OSError:
                pass
            for p in (json_path, events_path):
                if p is not None:
                    try:
                        p.unlink()
                    except OSError:
                        pass

    # ----------------------------------------------------------------- feedback

    def format_feedback(
        self, verify_result: VerifyResult, fmt: FeedbackFormat
    ) -> str:
        """Render a verify failure as model-readable feedback.

        PROSE — short narrative pulled from the parsed `line N: ...` or
        verifier error lines.
        JSON — structured dump of error_json.
        BOTH — prose followed by the JSON block.
        """
        prose = verify_result.error_prose or "(no prose error available)"
        if fmt == FeedbackFormat.PROSE:
            return f"Your program failed to compile. The error:\n{prose}"
        if fmt == FeedbackFormat.JSON:
            payload = verify_result.error_json or {}
            return (
                "Your program failed to compile. Structured error:\n"
                f"{json.dumps(payload, indent=2)}"
            )
        # BOTH
        payload = verify_result.error_json or {}
        return (
            "Your program failed to compile. The error:\n"
            f"{prose}\n\n"
            "Structured error:\n"
            f"{json.dumps(payload, indent=2)}"
        )


__all__ = ["StrandLanguage", "STRAND_CLI"]
