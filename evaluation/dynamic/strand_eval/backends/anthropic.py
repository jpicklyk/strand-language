"""Anthropic SDK emission backend.

Wraps the official `anthropic` Python SDK. Reads ANTHROPIC_API_KEY from
the environment and refuses to run without it.
"""

import os
import time
from typing import Optional

from strand_eval.backends.base import EmissionBackend
from strand_eval.types import EmissionResult, Message, Role


class AnthropicBackend(EmissionBackend):
    """Live Claude API calls via the anthropic Python SDK."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        max_tokens: int = 4096,
        temperature: float = 0.7,
    ):
        key = api_key or os.environ.get("ANTHROPIC_API_KEY")
        if not key:
            raise RuntimeError(
                "ANTHROPIC_API_KEY is not set. Set it in your environment "
                "or .env file before running the anthropic backend."
            )
        # Import inside __init__ so importing this module without the SDK
        # installed does not crash; users get a clear ImportError only when
        # they actually try to use the backend.
        try:
            import anthropic  # type: ignore
        except ImportError as e:
            raise ImportError(
                "The anthropic SDK is required for the AnthropicBackend. "
                "Install with: pip install anthropic"
            ) from e
        self._anthropic = anthropic
        self._client = anthropic.Anthropic(api_key=key)
        self._max_tokens = max_tokens
        self._temperature = temperature

    def emit(self, messages: list[Message], model: str) -> EmissionResult:
        # The Anthropic Messages API takes the system prompt separately from
        # the user/assistant turn list.
        system_text = ""
        api_messages: list[dict[str, str]] = []
        for msg in messages:
            if msg.role == Role.SYSTEM:
                # If multiple system messages, concatenate.
                system_text = msg.content if not system_text else system_text + "\n\n" + msg.content
            elif msg.role == Role.USER:
                api_messages.append({"role": "user", "content": msg.content})
            elif msg.role == Role.ASSISTANT:
                api_messages.append({"role": "assistant", "content": msg.content})

        t0 = time.monotonic()
        response = self._client.messages.create(
            model=model,
            max_tokens=self._max_tokens,
            temperature=self._temperature,
            system=system_text,
            messages=api_messages,
        )
        latency_ms = int((time.monotonic() - t0) * 1000)

        # Concatenate text blocks; tool-use blocks are ignored at this layer.
        text_parts: list[str] = []
        for block in response.content:
            block_type = getattr(block, "type", None)
            if block_type == "text":
                text_parts.append(block.text)
        content = "".join(text_parts)

        usage = response.usage
        input_tokens = getattr(usage, "input_tokens", 0) or 0
        output_tokens = getattr(usage, "output_tokens", 0) or 0
        stop_reason = response.stop_reason or "unknown"

        # The raw response is captured for fixture recording. We dump the
        # response via model_dump() if available (pydantic v2) and fall back
        # to a hand-built dict.
        raw: dict
        if hasattr(response, "model_dump"):
            try:
                raw = response.model_dump()  # type: ignore[assignment]
            except Exception:
                raw = self._fallback_dump(response, content, input_tokens, output_tokens)
        else:
            raw = self._fallback_dump(response, content, input_tokens, output_tokens)

        return EmissionResult(
            content=content,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            model=model,
            latency_ms=latency_ms,
            finish_reason=stop_reason,
            raw_response=raw,
        )

    @staticmethod
    def _fallback_dump(response, content: str, input_tokens: int, output_tokens: int) -> dict:
        return {
            "id": getattr(response, "id", None),
            "model": getattr(response, "model", None),
            "stop_reason": getattr(response, "stop_reason", None),
            "content": [{"type": "text", "text": content}],
            "usage": {
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
            },
        }
