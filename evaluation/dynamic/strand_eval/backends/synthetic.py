"""Synthetic emission backend for unit tests.

Returns scripted responses regardless of request. Used by the test suite
to exercise loop logic without depending on the API or fixture files.
"""

from typing import Union

from strand_eval.backends.base import EmissionBackend
from strand_eval.types import EmissionResult, Message


class SyntheticBackend(EmissionBackend):
    """Returns scripted responses in order.

    Construct with a list of either EmissionResult objects (used as-is) or
    plain strings (wrapped in a minimal EmissionResult with zero token
    counts). After all canned responses are consumed, a subsequent call
    raises RuntimeError.
    """

    def __init__(self, responses: list[Union[EmissionResult, str]]):
        self._responses: list[EmissionResult] = []
        for r in responses:
            if isinstance(r, EmissionResult):
                self._responses.append(r)
            elif isinstance(r, str):
                self._responses.append(
                    EmissionResult(
                        content=r,
                        input_tokens=0,
                        output_tokens=0,
                        model="synthetic",
                        latency_ms=0,
                        finish_reason="stop",
                        token_source="synthetic",
                    )
                )
            else:
                raise TypeError(
                    f"SyntheticBackend responses must be EmissionResult or str, got {type(r)}"
                )
        self._index = 0
        self.calls: list[tuple[list[Message], str]] = []

    def emit(self, messages: list[Message], model: str) -> EmissionResult:
        # Record the call for test assertions.
        self.calls.append((list(messages), model))
        if self._index >= len(self._responses):
            raise RuntimeError(
                f"SyntheticBackend exhausted: requested response #{self._index + 1} "
                f"but only {len(self._responses)} were provided"
            )
        result = self._responses[self._index]
        self._index += 1
        # If the canned response left model="synthetic", patch in the requested model.
        if result.model == "synthetic":
            result = EmissionResult(
                content=result.content,
                input_tokens=result.input_tokens,
                output_tokens=result.output_tokens,
                model=model,
                latency_ms=result.latency_ms,
                finish_reason=result.finish_reason,
                raw_response=result.raw_response,
                token_source=result.token_source,
            )
        return result
