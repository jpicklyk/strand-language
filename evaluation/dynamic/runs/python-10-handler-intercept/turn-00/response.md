```python
from contextlib import contextmanager
from typing import Callable, Iterator


# Approximate Strand's effect-handler stack: a list of intercept
# callables, scanned from the top (last) on every now() invocation.
_handler_stack: list[Callable[[], int]] = []


def now() -> int:
    if _handler_stack:
        # Innermost wins (matches Strand's findLast semantics).
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
    return now()


def main() -> None:
    # Nested handlers: outer returns 1, inner returns 2. Innermost
    # (the inner block, installed last) wins.
    with handler(lambda: 1):
        with handler(lambda: 2):
            result = body()
    print(result)


if __name__ == "__main__":
    main()
```
