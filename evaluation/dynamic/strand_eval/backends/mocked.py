"""Mocked emission backend that replays recorded fixtures.

Looks up a fixture file matching the request signature (hash over model,
messages, and temperature). Raises FixtureNotFound when no fixture
matches — CI runs in mocked mode treat a miss as a hard failure rather
than silently calling the real API.
"""

import hashlib
import json
from pathlib import Path
from typing import Optional

from strand_eval.backends.base import EmissionBackend
from strand_eval.types import EmissionResult, Message


class FixtureNotFound(RuntimeError):
    """Raised when the mocked backend cannot find a fixture for a request."""

    def __init__(self, request_hash: str, fixture_dir: Path):
        super().__init__(
            f"No fixture found for request hash {request_hash} under {fixture_dir}. "
            f"Record fixtures with `strand-eval record ...` or check the fixture "
            f"directory layout."
        )
        self.request_hash = request_hash
        self.fixture_dir = fixture_dir


def hash_request(
    messages: list[Message],
    model: str,
    temperature: float = 0.7,
    system_prompt: Optional[str] = None,
) -> str:
    """Stable hash over request-defining fields.

    Includes the messages, model id, temperature, and optionally an
    explicit system prompt (when not embedded in the messages). The hash
    is the canonical lookup key for fixture files.
    """
    parts: list[str] = []
    if system_prompt is not None:
        parts.append(f"system_prompt:{system_prompt}")
    for msg in messages:
        parts.append(f"{msg.role.value}:{msg.content}")
    parts.append(f"model:{model}")
    parts.append(f"temperature:{temperature}")
    blob = "\n---\n".join(parts).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()[:16]


class MockedBackend(EmissionBackend):
    """Replays recorded responses from a fixture directory.

    Fixture layout: <fixture_dir>/<task>/<config>/<model>/<sample-N>.json

    The backend can be used in two modes:

    - Index-based: caller sets (task, config, model, sample_index) ahead
      of each call and the backend reads the matching sample-N.json file
      in order, one fixture per emit call (one fixture file per emission
      in the retry loop). This is what the orchestrator uses.
    - Hash-based: the backend scans all fixtures and indexes by request
      hash. Useful for ad-hoc replay where the caller doesn't know which
      sample-N file maps to which emission.

    The default is index-based — the orchestrator calls `set_cell(...)`
    before each per-task run, and the backend returns the next attempt's
    fixture per call.
    """

    def __init__(self, fixture_dir: Path):
        self._fixture_dir = Path(fixture_dir)
        self._task: Optional[str] = None
        self._config: Optional[str] = None
        self._model: Optional[str] = None
        self._sample_index: Optional[int] = None
        self._attempt: int = 0

    def set_cell(self, task: str, config: str, model: str, sample_index: int) -> None:
        """Switch to a new (task, config, model, sample) cell.

        Resets the attempt counter to 0.
        """
        self._task = task
        self._config = config
        self._model = model
        self._sample_index = sample_index
        self._attempt = 0

    def emit(self, messages: list[Message], model: str) -> EmissionResult:
        if self._task is None or self._config is None or self._sample_index is None:
            raise RuntimeError(
                "MockedBackend.emit called before set_cell(...). The orchestrator "
                "must announce the (task, config, model, sample) cell before each run."
            )
        fixture_path = self._cell_dir() / f"emission-{self._attempt:02d}.json"
        self._attempt += 1
        if not fixture_path.exists():
            raise FixtureNotFound(
                request_hash=hash_request(messages, model),
                fixture_dir=self._cell_dir(),
            )
        return self._load_fixture(fixture_path, model)

    def _cell_dir(self) -> Path:
        assert self._task and self._config and self._model and self._sample_index is not None
        return (
            self._fixture_dir
            / self._task
            / self._config
            / self._model
            / f"sample-{self._sample_index:02d}"
        )

    @staticmethod
    def _load_fixture(path: Path, model: str) -> EmissionResult:
        data = json.loads(path.read_text(encoding="utf-8"))
        response = data.get("response", {})
        usage = response.get("usage", {})
        # Content may be a list of blocks (Anthropic shape) or a plain string.
        raw_content = response.get("content", "")
        if isinstance(raw_content, list):
            parts: list[str] = []
            for block in raw_content:
                if isinstance(block, dict) and block.get("type") == "text":
                    parts.append(block.get("text", ""))
                elif isinstance(block, str):
                    parts.append(block)
            content = "".join(parts)
        else:
            content = str(raw_content)
        return EmissionResult(
            content=content,
            input_tokens=int(usage.get("input_tokens", 0)),
            output_tokens=int(usage.get("output_tokens", 0)),
            model=data.get("model", model),
            latency_ms=int(response.get("latency_ms", 0)),
            finish_reason=response.get("stop_reason") or response.get("finish_reason") or "stop",
            raw_response=response,
            cache_read_input_tokens=int(usage.get("cache_read_input_tokens", 0) or 0),
            cache_creation_input_tokens=int(usage.get("cache_creation_input_tokens", 0) or 0),
            # Counts are whatever the fixture recorded; fixtures written
            # after source labeling carry their original provenance.
            token_source=response.get("token_source", "fixture"),
        )
