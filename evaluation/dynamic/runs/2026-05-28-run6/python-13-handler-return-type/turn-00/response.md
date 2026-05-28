```python
from __future__ import annotations

from typing import Callable


# Stack of handlers that intercept Time.Now calls. Each handler is a
# zero-argument callable returning an int (matching Time.Now's signature).
_now_handlers: list[Callable[[], int]] = []


def now() -> int:
    """The Time.Now primitive. If a handler is installed, it intercepts
    the call; otherwise this would consult the real clock. For this
    deterministic task the unhandled path is unused."""
    if _now_handlers:
        handler = _now_handlers[-1]
        return handler()
    raise RuntimeError("Time.Now called with no handler installed")


def body() -> int:
    """The handler body: call Time.Now and add 1."""
    return now() + 1


def with_now_handler(handle: Callable[[], int], thunk: Callable[[], int]) -> int:
    """Install `handle` as the active Time.Now interceptor for the
    duration of `thunk()`. Mirrors the Strand Handler node, whose
    intercept is Time.Now (nowFx) and whose handle is a zero-arg lambda
    returning Int."""
    _now_handlers.append(handle)
    try:
        return thunk()
    finally:
        _now_handlers.pop()


def handle_returns_99() -> int:
    """The handle lambda: zero-arg, returns the integer literal 99.
    Its return type () -> int matches Time.Now's () -> int signature."""
    return 99


def main() -> None:
    result: int = with_now_handler(handle_returns_99, body)
    print(result)


if __name__ == "__main__":
    main()
```
