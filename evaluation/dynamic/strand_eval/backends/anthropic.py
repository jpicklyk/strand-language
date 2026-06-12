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
        api_messages: list[dict] = []
        for msg in messages:
            if msg.role == Role.SYSTEM:
                # If multiple system messages, concatenate.
                system_text = msg.content if not system_text else system_text + "\n\n" + msg.content
            elif msg.role == Role.USER:
                api_messages.append({"role": "user", "content": msg.content})
            elif msg.role == Role.ASSISTANT:
                api_messages.append({"role": "assistant", "content": msg.content})

        # Wrap the system prompt as a list-of-blocks with ephemeral cache_control
        # so subsequent calls within the 5-minute cache window pay cache-read
        # rates ($0.30/M on Sonnet 4.7) instead of full input rates ($3/M).
        # Cache writes ($3.75/M) happen on the first call of a window.
        # On Sonnet 4.7 prompt caching is GA — no beta header needed.
        system_blocks = self._build_system_blocks(system_text)

        # Second breakpoint: the first user message is the static task
        # preamble (the task description), byte-identical across every retry
        # within a cell. Marking it extends the cached prefix to
        # system + task, so a retry's uncached input is only the feedback
        # turns. Two of the API's four allowed breakpoints are used: the
        # system block (shared across cells with the same prompt) and this
        # one (shared across retries within the cell). Prefixes below the
        # model's minimum cacheable size silently don't cache — no error.
        self._mark_task_preamble(api_messages)
        api_kwargs: dict = {
            "model": model,
            "max_tokens": self._max_tokens,
            "temperature": self._temperature,
            "messages": api_messages,
        }
        if system_blocks:
            api_kwargs["system"] = system_blocks

        t0 = time.monotonic()
        response = self._client.messages.create(**api_kwargs)
        latency_ms = int((time.monotonic() - t0) * 1000)

        # Concatenate text blocks; tool-use blocks are ignored at this layer.
        text_parts: list[str] = []
        for block in response.content:
            block_type = getattr(block, "type", None)
            if block_type == "text":
                text_parts.append(block.text)
        content = "".join(text_parts)

        usage = response.usage
        # input_tokens excludes cache reads/writes on the Anthropic API. The
        # cached-block token counts come back as separate fields that may be
        # None when the request didn't touch the cache.
        input_tokens = getattr(usage, "input_tokens", 0) or 0
        output_tokens = getattr(usage, "output_tokens", 0) or 0
        cache_read_input_tokens = getattr(usage, "cache_read_input_tokens", 0) or 0
        cache_creation_input_tokens = getattr(usage, "cache_creation_input_tokens", 0) or 0
        stop_reason = response.stop_reason or "unknown"

        # The raw response is captured for fixture recording. We dump the
        # response via model_dump() if available (pydantic v2) and fall back
        # to a hand-built dict.
        raw: dict
        if hasattr(response, "model_dump"):
            try:
                raw = response.model_dump()  # type: ignore[assignment]
            except Exception:
                raw = self._fallback_dump(
                    response,
                    content,
                    input_tokens,
                    output_tokens,
                    cache_read_input_tokens,
                    cache_creation_input_tokens,
                )
        else:
            raw = self._fallback_dump(
                response,
                content,
                input_tokens,
                output_tokens,
                cache_read_input_tokens,
                cache_creation_input_tokens,
            )

        return EmissionResult(
            content=content,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            model=model,
            latency_ms=latency_ms,
            finish_reason=stop_reason,
            raw_response=raw,
            cache_read_input_tokens=cache_read_input_tokens,
            cache_creation_input_tokens=cache_creation_input_tokens,
            # Counts come from the API usage block: real tokenizer counts.
            token_source="api",
        )

    @staticmethod
    def _mark_task_preamble(api_messages: list[dict]) -> None:
        """Add a cache breakpoint on the first user message, in place.

        Converts the first user message's string content into the
        list-of-blocks form with ephemeral cache_control. Later messages
        (retry feedback, assistant emissions) are left as plain strings —
        they vary per turn and would only churn cache entries.
        """
        for msg in api_messages:
            if msg["role"] == "user":
                if isinstance(msg["content"], str):
                    msg["content"] = [
                        {
                            "type": "text",
                            "text": msg["content"],
                            "cache_control": {"type": "ephemeral"},
                        }
                    ]
                return

    @staticmethod
    def _build_system_blocks(system_text: str) -> list[dict]:
        """Wrap the system prompt in a single text block with ephemeral caching.

        Returns an empty list when there is no system text so the
        `system` kwarg can be omitted entirely (the API rejects an empty
        list with cache_control set).
        """
        if not system_text:
            return []
        return [
            {
                "type": "text",
                "text": system_text,
                "cache_control": {"type": "ephemeral"},
            }
        ]

    @staticmethod
    def _fallback_dump(
        response,
        content: str,
        input_tokens: int,
        output_tokens: int,
        cache_read_input_tokens: int = 0,
        cache_creation_input_tokens: int = 0,
    ) -> dict:
        return {
            "id": getattr(response, "id", None),
            "model": getattr(response, "model", None),
            "stop_reason": getattr(response, "stop_reason", None),
            "content": [{"type": "text", "text": content}],
            "usage": {
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "cache_read_input_tokens": cache_read_input_tokens,
                "cache_creation_input_tokens": cache_creation_input_tokens,
            },
        }
