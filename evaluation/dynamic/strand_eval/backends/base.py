"""Abstract base class for emission backends."""

from abc import ABC, abstractmethod

from strand_eval.types import EmissionResult, Message


class EmissionBackend(ABC):
    """One model call produces one EmissionResult.

    Concrete implementations live in this package alongside this file.
    """

    @abstractmethod
    def emit(self, messages: list[Message], model: str) -> EmissionResult:
        """Emit a single model response for the given conversation."""
        raise NotImplementedError
