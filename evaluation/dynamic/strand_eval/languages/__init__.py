"""Per-language verify+execute adapters for strand_eval.

Each language baseline (Strand, Python, etc.) implements the Language
ABC by registering itself with @register_language(name). The orchestrator
looks up the adapter via LANGUAGE_REGISTRY at run start.

Agent P2 owns languages/strand.py; agent P3 owns languages/python.py.
This module ships the ABC and registry; concrete implementations land
in side files.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Callable, Type

from strand_eval.types import FeedbackFormat, RunResult, VerifyResult


class Language(ABC):
    """Contract for a language adapter.

    A concrete adapter knows how to:
      - extract a program from a model's free-text response
      - verify the program (compile + static checks)
      - run the verified program against a task's expected output
      - format a verify failure as model-readable feedback
    """

    name: str = ""  # concrete subclass sets this

    @abstractmethod
    def extract_program(self, model_response: str) -> str:
        """Pull the program out of the model's response.

        Typically looks for a fenced code block; on miss, may return the
        full response as a fallback.
        """
        raise NotImplementedError

    @abstractmethod
    def verify(self, program_source: str) -> VerifyResult:
        """Compile and verify the program. Returns ok=False with error details on failure."""
        raise NotImplementedError

    @abstractmethod
    def run(self, program_source: str, expected: dict) -> RunResult:
        """Execute the verified program and compare to expected output."""
        raise NotImplementedError

    @abstractmethod
    def format_feedback(self, verify_result: VerifyResult, fmt: FeedbackFormat) -> str:
        """Render a verify failure into model-readable feedback."""
        raise NotImplementedError


LANGUAGE_REGISTRY: dict[str, Type[Language]] = {}


def register_language(name: str) -> Callable[[Type[Language]], Type[Language]]:
    """Class decorator that registers a Language subclass under `name`.

    Usage:

        @register_language("strand")
        class StrandLanguage(Language):
            name = "strand"
            ...
    """

    def _wrap(cls: Type[Language]) -> Type[Language]:
        if not issubclass(cls, Language):
            raise TypeError(f"@register_language target must subclass Language: {cls!r}")
        # Set the class attribute if it hasn't been set on the subclass already.
        if not getattr(cls, "name", ""):
            cls.name = name
        LANGUAGE_REGISTRY[name] = cls
        return cls

    return _wrap


def get_language(name: str) -> Language:
    """Look up and instantiate a registered language adapter.

    Raises a clear error if no adapter is registered. P2 and P3 must
    import their language modules so the @register_language decorator
    runs before this is called.
    """
    if name not in LANGUAGE_REGISTRY:
        available = ", ".join(sorted(LANGUAGE_REGISTRY.keys())) or "<none>"
        raise KeyError(
            f"No language adapter registered for {name!r}. "
            f"Available: {available}. "
            f"Ensure the relevant module under strand_eval.languages has been imported."
        )
    return LANGUAGE_REGISTRY[name]()


__all__ = [
    "Language",
    "LANGUAGE_REGISTRY",
    "register_language",
    "get_language",
]


# Auto-register the shipped language adapters on package import. Importing
# the modules runs their `@register_language(...)` decorators so the CLI's
# `get_language` lookup succeeds without callers needing an explicit
# `from strand_eval.languages import python, strand` first.
#
# Imports are placed at the bottom of the file (after LANGUAGE_REGISTRY,
# register_language, and the Language ABC are defined) to avoid a circular
# import: each adapter module does `from strand_eval.languages import
# Language, register_language` at its top.
from strand_eval.languages import python as _python_adapter  # noqa: F401
from strand_eval.languages import strand as _strand_adapter  # noqa: F401
from strand_eval.languages import kotlin as _kotlin_adapter  # noqa: F401
