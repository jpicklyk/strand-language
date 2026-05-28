"""Python parallel of task 13.

Strand probes `HandlerSignatureMismatch` when a Handler's handle
lambda has a different return type from the intercepted function.
Python uses a stack-based handler shim and mypy --strict for the
type discipline equivalent.
"""

from contextlib import contextmanager
from typing import Callable, Iterator


_handler_stack: list[Callable[[], int]] = []


def now() -> int:
    if _handler_stack:
        return _handler_stack[-1]()
    raise RuntimeError("Time.Now requested but no handler installed")


@contextmanager
def handler(intercept: Callable[[], int]) -> Iterator[None]:
    _handler_stack.append(intercept)
    try:
        yield
    finally:
        _handler_stack.pop()


def body() -> int:
    return now() + 1


def main() -> None:
    with handler(lambda: 99):
        result = body()
    print(result)


if __name__ == "__main__":
    main()
