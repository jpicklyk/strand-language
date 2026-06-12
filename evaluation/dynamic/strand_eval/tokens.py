"""Token counting with a labeled fallback chain. Standard library only.

The harness has historically estimated token counts as ``chars / 4``
(the "byte proxy"), with unquantified error. This module wires in real
tokenizer counts from the Anthropic ``count_tokens`` endpoint while
keeping the proxy as an always-available fallback, and labels EVERY
count with its source so downstream reports can never silently mix the
two scales.

Modes (resolution order):

1. ``STRAND_EVAL_TOKEN_COUNT`` env var, when set to ``api`` or
   ``byte-proxy``, forces that mode.
2. Otherwise ``api`` when ``ANTHROPIC_API_KEY`` is present in the
   environment, ``byte-proxy`` when it is not.

The ``api`` mode POSTs to ``/v1/messages/count_tokens`` via urllib —
no third-party dependency, and the endpoint itself is free of charge
(it counts; it does not sample). Any API failure (bad key, offline,
unknown model id) trips a per-process circuit breaker: the counter
falls back to the byte proxy for the rest of the process and labels
those counts ``byte-proxy``, so a half-failed run is still honestly
labeled per count.

Model ids: measurement runs label cells with ids like
``claude-sonnet-4-7``. When that label is not a valid API model id,
set ``STRAND_EVAL_TOKEN_COUNT_MODEL`` to a real model id (token
vocabularies are shared across the current Claude family, so any
current id gives the right scale).

Source labels used across the harness:

- ``api``         — Anthropic count_tokens endpoint (real tokenizer).
- ``byte-proxy``  — ``(chars + 3) // 4`` heuristic (legacy scale).
- ``caller``      — explicit counts supplied via response-metadata.json.
- ``fixture``     — counts replayed from a recorded fixture file.
- ``synthetic``   — scripted test backend; counts are placeholders.
- ``mixed(...)``  — an aggregate combined more than one of the above.
- ``unknown``     — legacy data recorded before sources were labeled.
"""

from __future__ import annotations

import hashlib
import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Callable, Optional

SOURCE_API = "api"
SOURCE_BYTE_PROXY = "byte-proxy"
SOURCE_CALLER = "caller"
SOURCE_FIXTURE = "fixture"
SOURCE_SYNTHETIC = "synthetic"
SOURCE_UNKNOWN = "unknown"

ANTHROPIC_COUNT_TOKENS_URL = "https://api.anthropic.com/v1/messages/count_tokens"
ANTHROPIC_VERSION = "2023-06-01"


@dataclass(frozen=True)
class TokenCount:
    """One token count plus the provenance label for reporting."""

    tokens: int
    source: str


def byte_proxy(text: str) -> int:
    """The legacy ``chars / 4`` estimate, kept bit-identical to the
    original step-mode arithmetic so byte-proxy-labeled numbers stay
    comparable with all pre-labeling runs (Runs 1-7)."""
    return (len(text) + 3) // 4


def combine_sources(sources: list[str]) -> str:
    """Collapse per-count sources into one aggregate label.

    A uniform list collapses to its single label; anything else becomes
    ``mixed(a+b)`` so a report cell can never pass off mixed-scale
    numbers as a single scale. An empty list is ``unknown``.
    """
    uniq = sorted(set(sources))
    if not uniq:
        return SOURCE_UNKNOWN
    if len(uniq) == 1:
        return uniq[0]
    return "mixed(" + "+".join(uniq) + ")"


class TokenCounter:
    """Counts tokens for message stacks and bare text with labeling.

    ``opener`` is the urllib-compatible callable used for the HTTP POST;
    injectable for tests. Counts for identical payloads are memoized
    in-process (the system prompt re-counts on every retry turn
    otherwise).
    """

    def __init__(
        self,
        mode: Optional[str] = None,
        api_key: Optional[str] = None,
        count_model: Optional[str] = None,
        timeout: float = 15.0,
        url: str = ANTHROPIC_COUNT_TOKENS_URL,
        opener: Optional[Callable] = None,
    ):
        self._api_key = api_key or os.environ.get("ANTHROPIC_API_KEY") or ""
        env_mode = os.environ.get("STRAND_EVAL_TOKEN_COUNT", "").strip().lower()
        chosen = (mode or env_mode or "").strip().lower()
        if chosen not in (SOURCE_API, SOURCE_BYTE_PROXY, ""):
            raise ValueError(
                f"Unknown token-count mode {chosen!r}; expected 'api' or 'byte-proxy'."
            )
        if not chosen:
            chosen = SOURCE_API if self._api_key else SOURCE_BYTE_PROXY
        if chosen == SOURCE_API and not self._api_key:
            # api was forced but there is no key; degrade immediately
            # (and visibly: every count will carry the byte-proxy label).
            chosen = SOURCE_BYTE_PROXY
        self.mode = chosen
        self._count_model = count_model or os.environ.get(
            "STRAND_EVAL_TOKEN_COUNT_MODEL"
        )
        self._timeout = timeout
        self._url = url
        self._opener = opener or urllib.request.urlopen
        self._cache: dict[str, int] = {}
        # First API failure message; once set, all subsequent counts use
        # the byte proxy without re-attempting the network.
        self.api_failure: Optional[str] = None

    # ------------------------------------------------------------------
    # Public counting surface
    # ------------------------------------------------------------------

    def count_messages(self, messages: list[dict], model: str) -> TokenCount:
        """Count a chat-message stack (``[{"role", "content"}, ...]``).

        System messages move to the API's ``system`` field; user and
        assistant messages pass through. Mirrors what an actual
        /v1/messages call would be billed for, minus sampling.
        """
        if self.mode == SOURCE_API and self.api_failure is None:
            payload = self._build_payload(messages, model)
            if payload is not None:
                n = self._count_api(payload)
                if n is not None:
                    return TokenCount(n, SOURCE_API)
        chars = sum(len(m.get("content", "")) for m in messages)
        return TokenCount((chars + 3) // 4, SOURCE_BYTE_PROXY)

    def count_text(self, text: str, model: str) -> TokenCount:
        """Count a bare text (an emission, a prompt file, ...).

        The API has no raw-text endpoint, so the text is wrapped as a
        single user message; the few tokens of message framing are noise
        at the sizes the harness measures.
        """
        if not text:
            return TokenCount(0, self.mode)
        return self.count_messages([{"role": "user", "content": text}], model)

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _build_payload(self, messages: list[dict], model: str) -> Optional[dict]:
        system_parts: list[str] = []
        api_messages: list[dict] = []
        for m in messages:
            role = m.get("role", "")
            content = m.get("content", "")
            if not content:
                continue
            if role == "system":
                system_parts.append(content)
            elif role in ("user", "assistant"):
                api_messages.append({"role": role, "content": content})
        if not api_messages:
            if not system_parts:
                return None
            # A system-only stack: count it as a single user message —
            # same vocabulary, near-identical framing overhead.
            api_messages = [{"role": "user", "content": "\n\n".join(system_parts)}]
            system_parts = []
        payload: dict = {
            "model": self._count_model or model,
            "messages": api_messages,
        }
        if system_parts:
            payload["system"] = "\n\n".join(system_parts)
        return payload

    def _count_api(self, payload: dict) -> Optional[int]:
        body = json.dumps(payload, sort_keys=True, ensure_ascii=False).encode("utf-8")
        cache_key = hashlib.sha256(body).hexdigest()
        if cache_key in self._cache:
            return self._cache[cache_key]
        request = urllib.request.Request(
            self._url,
            data=body,
            method="POST",
            headers={
                "content-type": "application/json",
                "x-api-key": self._api_key,
                "anthropic-version": ANTHROPIC_VERSION,
            },
        )
        try:
            with self._opener(request, timeout=self._timeout) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            n = int(data["input_tokens"])
        except urllib.error.HTTPError as e:
            detail = ""
            try:
                detail = e.read().decode("utf-8", errors="replace")[:500]
            except Exception:
                pass
            self.api_failure = f"HTTP {e.code} from count_tokens: {detail}"
            return None
        except Exception as e:  # noqa: BLE001 - any failure degrades to proxy
            self.api_failure = f"count_tokens call failed: {e!r}"
            return None
        self._cache[cache_key] = n
        return n


_default_counter: Optional[TokenCounter] = None


def default_counter() -> TokenCounter:
    """Process-wide counter so the memo cache spans calls within one
    CLI invocation. Tests construct their own instances."""
    global _default_counter
    if _default_counter is None:
        _default_counter = TokenCounter()
    return _default_counter


__all__ = [
    "TokenCount",
    "TokenCounter",
    "byte_proxy",
    "combine_sources",
    "default_counter",
    "SOURCE_API",
    "SOURCE_BYTE_PROXY",
    "SOURCE_CALLER",
    "SOURCE_FIXTURE",
    "SOURCE_SYNTHETIC",
    "SOURCE_UNKNOWN",
]
