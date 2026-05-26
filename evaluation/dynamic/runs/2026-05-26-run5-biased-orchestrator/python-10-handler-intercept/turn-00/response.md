```python
from typing import Callable


HandlerFn = Callable[[], int]

_active_handlers: list[HandlerFn] = []


def now() -> int:
    if _active_handlers:
        return _active_handlers[-1]()
    raise RuntimeError("Time.Now called without an active handler")


def with_handler(handler: HandlerFn, body: Callable[[], int]) -> int:
    _active_handlers.append(handler)
    try:
        return body()
    finally:
        _active_handlers.pop()


def main() -> None:
    def inner() -> int:
        return 2

    def outer() -> int:
        return 1

    def body() -> int:
        return now()

    def inner_scope() -> int:
        return with_handler(inner, body)

    result: int = with_handler(outer, inner_scope)
    print(result)


if __name__ == "__main__":
    main()
```
