"""Token counting: byte-proxy fallback, API path, labeling, circuit breaker.

The API path is exercised with an injected opener stub — no network, no
third-party dependency. The byte-proxy path must stay bit-identical to
the legacy chars/4 estimate so labeled byte-proxy figures remain
comparable with Runs 1-7.
"""

from __future__ import annotations

import io
import json
import urllib.error

import pytest

from strand_eval.tokens import (
    SOURCE_API,
    SOURCE_BYTE_PROXY,
    TokenCount,
    TokenCounter,
    byte_proxy,
    combine_sources,
)


# ----------------------------------------------------------------------
# Opener stubs
# ----------------------------------------------------------------------


class _FakeResponse:
    def __init__(self, payload: dict):
        self._body = json.dumps(payload).encode("utf-8")

    def read(self) -> bytes:
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class _RecordingOpener:
    """Captures requests and returns a fixed input_tokens count."""

    def __init__(self, tokens: int = 123):
        self.tokens = tokens
        self.requests: list = []

    def __call__(self, request, timeout=None):
        self.requests.append(request)
        return _FakeResponse({"input_tokens": self.tokens})


class _FailingOpener:
    def __init__(self):
        self.calls = 0

    def __call__(self, request, timeout=None):
        self.calls += 1
        raise urllib.error.HTTPError(
            url=request.full_url,
            code=401,
            msg="unauthorized",
            hdrs=None,  # type: ignore[arg-type]
            fp=io.BytesIO(b'{"error": "invalid x-api-key"}'),
        )


# ----------------------------------------------------------------------
# byte proxy + label combination
# ----------------------------------------------------------------------


def test_byte_proxy_matches_legacy_arithmetic():
    assert byte_proxy("") == 0
    assert byte_proxy("a") == 1
    assert byte_proxy("abcd") == 1
    assert byte_proxy("abcde") == 2
    # The exact expression the pre-labeling step mode used.
    text = "x" * 12345
    assert byte_proxy(text) == (len(text) + 3) // 4


def test_combine_sources_uniform_and_mixed():
    assert combine_sources([]) == "unknown"
    assert combine_sources(["api", "api"]) == "api"
    assert combine_sources(["byte-proxy"]) == "byte-proxy"
    assert combine_sources(["api", "byte-proxy"]) == "mixed(api+byte-proxy)"
    # Order-insensitive and deduplicated.
    assert combine_sources(["byte-proxy", "api", "api"]) == "mixed(api+byte-proxy)"


# ----------------------------------------------------------------------
# Byte-proxy mode
# ----------------------------------------------------------------------


def test_forced_byte_proxy_mode_counts_and_labels():
    counter = TokenCounter(mode="byte-proxy")
    msgs = [
        {"role": "system", "content": "s" * 40},
        {"role": "user", "content": "u" * 20},
    ]
    count = counter.count_messages(msgs, "claude-sonnet-4-6")
    assert count == TokenCount(tokens=15, source=SOURCE_BYTE_PROXY)
    text_count = counter.count_text("abcdefgh", "claude-sonnet-4-6")
    assert text_count == TokenCount(tokens=2, source=SOURCE_BYTE_PROXY)


def test_api_mode_without_key_degrades_to_byte_proxy(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.delenv("STRAND_EVAL_TOKEN_COUNT", raising=False)
    counter = TokenCounter(mode="api", api_key=None)
    assert counter.mode == SOURCE_BYTE_PROXY


def test_default_mode_follows_api_key_presence(monkeypatch):
    monkeypatch.delenv("STRAND_EVAL_TOKEN_COUNT", raising=False)
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    assert TokenCounter().mode == SOURCE_BYTE_PROXY
    monkeypatch.setenv("ANTHROPIC_API_KEY", "k")
    assert TokenCounter().mode == SOURCE_API


def test_env_var_forces_byte_proxy_even_with_key(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "k")
    monkeypatch.setenv("STRAND_EVAL_TOKEN_COUNT", "byte-proxy")
    assert TokenCounter().mode == SOURCE_BYTE_PROXY


def test_unknown_mode_rejected():
    with pytest.raises(ValueError):
        TokenCounter(mode="guess")


# ----------------------------------------------------------------------
# API mode (stubbed opener)
# ----------------------------------------------------------------------


def test_api_mode_counts_and_labels():
    opener = _RecordingOpener(tokens=321)
    counter = TokenCounter(mode="api", api_key="test-key", opener=opener)
    msgs = [
        {"role": "system", "content": "be terse"},
        {"role": "user", "content": "hello"},
        {"role": "assistant", "content": "hi"},
        {"role": "user", "content": "count this"},
    ]
    count = counter.count_messages(msgs, "claude-sonnet-4-6")
    assert count == TokenCount(tokens=321, source=SOURCE_API)
    assert len(opener.requests) == 1
    sent = json.loads(opener.requests[0].data.decode("utf-8"))
    assert sent["system"] == "be terse"
    assert [m["role"] for m in sent["messages"]] == ["user", "assistant", "user"]
    assert sent["model"] == "claude-sonnet-4-6"
    assert opener.requests[0].get_header("X-api-key") == "test-key"


def test_api_mode_caches_identical_payloads():
    opener = _RecordingOpener()
    counter = TokenCounter(mode="api", api_key="test-key", opener=opener)
    msgs = [{"role": "user", "content": "same payload"}]
    counter.count_messages(msgs, "claude-sonnet-4-6")
    counter.count_messages(msgs, "claude-sonnet-4-6")
    assert len(opener.requests) == 1
    counter.count_messages([{"role": "user", "content": "different"}], "claude-sonnet-4-6")
    assert len(opener.requests) == 2


def test_count_model_override():
    opener = _RecordingOpener()
    counter = TokenCounter(
        mode="api", api_key="k", count_model="claude-sonnet-4-5", opener=opener
    )
    counter.count_text("hello", "claude-sonnet-4-7")
    sent = json.loads(opener.requests[0].data.decode("utf-8"))
    assert sent["model"] == "claude-sonnet-4-5"


def test_api_failure_falls_back_and_trips_circuit_breaker():
    opener = _FailingOpener()
    counter = TokenCounter(mode="api", api_key="bad-key", opener=opener)
    first = counter.count_text("abcdefgh", "claude-sonnet-4-6")
    assert first == TokenCount(tokens=2, source=SOURCE_BYTE_PROXY)
    assert counter.api_failure is not None
    assert "401" in counter.api_failure
    # Subsequent counts must not retry the network.
    second = counter.count_text("more text here", "claude-sonnet-4-6")
    assert second.source == SOURCE_BYTE_PROXY
    assert opener.calls == 1


def test_system_only_stack_counts_as_user_message():
    opener = _RecordingOpener()
    counter = TokenCounter(mode="api", api_key="k", opener=opener)
    counter.count_messages(
        [{"role": "system", "content": "only system"}], "claude-sonnet-4-6"
    )
    sent = json.loads(opener.requests[0].data.decode("utf-8"))
    assert "system" not in sent
    assert sent["messages"] == [{"role": "user", "content": "only system"}]


def test_empty_text_counts_zero_without_api_call():
    opener = _RecordingOpener()
    counter = TokenCounter(mode="api", api_key="k", opener=opener)
    assert counter.count_text("", "claude-sonnet-4-6").tokens == 0
    assert opener.requests == []
