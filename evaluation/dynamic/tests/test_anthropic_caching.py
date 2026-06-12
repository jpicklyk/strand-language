"""Unit tests for the AnthropicBackend's prompt-cache wiring.

These tests use a mock anthropic client to assert the system block
shape and confirm cache_read / cache_creation token counts flow through
to the EmissionResult. The real anthropic SDK is not exercised.
"""

from __future__ import annotations

import sys
import types
from dataclasses import dataclass
from typing import Optional

import pytest


# --------------------------------------------------------------------------
# Mock the anthropic module so AnthropicBackend can be constructed without
# a real install or API key.
# --------------------------------------------------------------------------

@dataclass
class _MockUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_input_tokens: Optional[int] = None
    cache_creation_input_tokens: Optional[int] = None


@dataclass
class _MockTextBlock:
    text: str
    type: str = "text"


@dataclass
class _MockResponse:
    content: list
    usage: _MockUsage
    stop_reason: str = "end_turn"
    id: str = "msg_test"
    model: str = "claude-sonnet-4-7"

    def model_dump(self) -> dict:
        return {
            "id": self.id,
            "model": self.model,
            "stop_reason": self.stop_reason,
            "content": [{"type": b.type, "text": b.text} for b in self.content],
            "usage": {
                "input_tokens": self.usage.input_tokens,
                "output_tokens": self.usage.output_tokens,
                "cache_read_input_tokens": self.usage.cache_read_input_tokens,
                "cache_creation_input_tokens": self.usage.cache_creation_input_tokens,
            },
        }


class _MockMessages:
    """Mimics anthropic.Anthropic().messages with a single capture-and-respond."""

    def __init__(self):
        self.last_kwargs: dict = {}
        self.response = _MockResponse(
            content=[_MockTextBlock(text="ok")],
            usage=_MockUsage(
                input_tokens=42,
                output_tokens=7,
                cache_read_input_tokens=1234,
                cache_creation_input_tokens=4567,
            ),
        )

    def create(self, **kwargs):  # noqa: ANN003 - mirror sdk
        self.last_kwargs = kwargs
        return self.response


class _MockAnthropic:
    """Stand-in for the anthropic.Anthropic client."""

    def __init__(self, api_key: Optional[str] = None):
        self.api_key = api_key
        self.messages = _MockMessages()


@pytest.fixture
def install_mock_anthropic(monkeypatch):
    """Inject a mock anthropic module into sys.modules for the test."""
    mod = types.ModuleType("anthropic")
    mod.Anthropic = _MockAnthropic  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "anthropic", mod)
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-real")
    yield mod


def test_system_text_is_wrapped_in_ephemeral_cache_block(install_mock_anthropic):
    from strand_eval.backends.anthropic import AnthropicBackend
    from strand_eval.types import Message, Role

    backend = AnthropicBackend()
    messages = [
        Message(role=Role.SYSTEM, content="long static prompt"),
        Message(role=Role.USER, content="hello"),
    ]
    backend.emit(messages, model="claude-sonnet-4-7")

    captured = backend._client.messages.last_kwargs  # type: ignore[attr-defined]
    assert "system" in captured, "system kwarg must be passed when system text is present"
    blocks = captured["system"]
    assert isinstance(blocks, list), "system must be a list of blocks for cache_control to apply"
    assert len(blocks) == 1
    block = blocks[0]
    assert block["type"] == "text"
    assert block["text"] == "long static prompt"
    assert block["cache_control"] == {"type": "ephemeral"}


def test_no_system_block_when_no_system_message(install_mock_anthropic):
    from strand_eval.backends.anthropic import AnthropicBackend
    from strand_eval.types import Message, Role

    backend = AnthropicBackend()
    messages = [Message(role=Role.USER, content="hi")]
    backend.emit(messages, model="claude-sonnet-4-7")

    captured = backend._client.messages.last_kwargs  # type: ignore[attr-defined]
    # When no system text is given the kwarg should be omitted entirely so
    # we don't pass an empty list with cache_control to the API.
    assert "system" not in captured


def test_cache_tokens_flow_through_to_emission_result(install_mock_anthropic):
    from strand_eval.backends.anthropic import AnthropicBackend
    from strand_eval.types import Message, Role

    backend = AnthropicBackend()
    msgs = [
        Message(role=Role.SYSTEM, content="sys"),
        Message(role=Role.USER, content="u"),
    ]
    result = backend.emit(msgs, model="claude-sonnet-4-7")

    assert result.input_tokens == 42
    assert result.output_tokens == 7
    assert result.cache_read_input_tokens == 1234
    assert result.cache_creation_input_tokens == 4567


def test_missing_cache_fields_default_to_zero(install_mock_anthropic, monkeypatch):
    from strand_eval.backends.anthropic import AnthropicBackend
    from strand_eval.types import Message, Role

    backend = AnthropicBackend()
    # Override the mock to return None for both cache fields (older SDKs).
    backend._client.messages.response = _MockResponse(  # type: ignore[attr-defined]
        content=[_MockTextBlock(text="ok")],
        usage=_MockUsage(
            input_tokens=10,
            output_tokens=2,
            cache_read_input_tokens=None,
            cache_creation_input_tokens=None,
        ),
    )

    msgs = [Message(role=Role.USER, content="u")]
    result = backend.emit(msgs, model="claude-sonnet-4-7")
    assert result.cache_read_input_tokens == 0
    assert result.cache_creation_input_tokens == 0


def test_first_user_message_gets_cache_breakpoint(install_mock_anthropic):
    """The static task preamble (first user message) carries its own
    ephemeral cache_control block so retries within a cell extend the
    cached prefix to system + task."""
    from strand_eval.backends.anthropic import AnthropicBackend
    from strand_eval.types import Message, Role

    backend = AnthropicBackend()
    messages = [
        Message(role=Role.SYSTEM, content="sys"),
        Message(role=Role.USER, content="task preamble"),
    ]
    backend.emit(messages, model="claude-sonnet-4-7")

    captured = backend._client.messages.last_kwargs  # type: ignore[attr-defined]
    first_user = captured["messages"][0]
    assert first_user["role"] == "user"
    assert isinstance(first_user["content"], list)
    assert first_user["content"][0] == {
        "type": "text",
        "text": "task preamble",
        "cache_control": {"type": "ephemeral"},
    }


def test_later_turns_are_not_cache_marked(install_mock_anthropic):
    """Only the first user message (the static preamble) is marked; retry
    feedback and assistant emissions vary per turn and stay plain strings."""
    from strand_eval.backends.anthropic import AnthropicBackend
    from strand_eval.types import Message, Role

    backend = AnthropicBackend()
    messages = [
        Message(role=Role.SYSTEM, content="sys"),
        Message(role=Role.USER, content="task preamble"),
        Message(role=Role.ASSISTANT, content="emission 1"),
        Message(role=Role.USER, content="verifier feedback"),
    ]
    backend.emit(messages, model="claude-sonnet-4-7")

    captured = backend._client.messages.last_kwargs  # type: ignore[attr-defined]
    api_messages = captured["messages"]
    assert isinstance(api_messages[0]["content"], list)  # preamble: marked
    assert api_messages[1]["content"] == "emission 1"  # plain string
    assert api_messages[2]["content"] == "verifier feedback"  # plain string


def test_preamble_breakpoint_without_system_prompt(install_mock_anthropic):
    """The preamble breakpoint does not depend on a system prompt existing."""
    from strand_eval.backends.anthropic import AnthropicBackend
    from strand_eval.types import Message, Role

    backend = AnthropicBackend()
    backend.emit([Message(role=Role.USER, content="only turn")], model="claude-sonnet-4-7")
    captured = backend._client.messages.last_kwargs  # type: ignore[attr-defined]
    assert "system" not in captured
    assert captured["messages"][0]["content"][0]["cache_control"] == {"type": "ephemeral"}


def test_multiple_system_messages_concatenate(install_mock_anthropic):
    from strand_eval.backends.anthropic import AnthropicBackend
    from strand_eval.types import Message, Role

    backend = AnthropicBackend()
    msgs = [
        Message(role=Role.SYSTEM, content="part one"),
        Message(role=Role.SYSTEM, content="part two"),
        Message(role=Role.USER, content="u"),
    ]
    backend.emit(msgs, model="claude-sonnet-4-7")
    captured = backend._client.messages.last_kwargs  # type: ignore[attr-defined]
    blocks = captured["system"]
    assert blocks[0]["text"] == "part one\n\npart two"
    assert blocks[0]["cache_control"] == {"type": "ephemeral"}
