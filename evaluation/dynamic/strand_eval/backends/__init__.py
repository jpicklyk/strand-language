"""Emission backends for strand_eval.

A backend implements EmissionBackend.emit and is the seam through which
the orchestrator obtains a model response. Three implementations ship:

- AnthropicBackend: live Claude API calls
- MockedBackend: replays recorded fixtures from disk
- SyntheticBackend: scripted responses for unit tests
"""

from strand_eval.backends.base import EmissionBackend
from strand_eval.backends.anthropic import AnthropicBackend
from strand_eval.backends.mocked import MockedBackend, FixtureNotFound
from strand_eval.backends.synthetic import SyntheticBackend

__all__ = [
    "EmissionBackend",
    "AnthropicBackend",
    "MockedBackend",
    "SyntheticBackend",
    "FixtureNotFound",
]
