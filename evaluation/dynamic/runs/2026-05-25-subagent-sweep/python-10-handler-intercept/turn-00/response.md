```python
from dataclasses import dataclass
from typing import Callable


@dataclass(frozen=True)
class Handler:
    category: str
    handle: Callable[[], int]


_active_handlers: list[Handler] = []


def now() -> int:
    for h in reversed(_active_handlers):
        if h.category == "Time.Now":
            return h.handle()
    raise RuntimeError("No handler for Time.Now")


def with_handler(handler: Handler, body: Callable[[], int]) -> int:
    _active_handlers.append(handler)
    try:
        return body()
    finally:
        _active_handlers.pop()


def body() -> int:
    return now()


def main() -> None:
    inner = Handler(category="Time.Now", handle=lambda: 2)
    outer = Handler(category="Time.Now", handle=lambda: 1)
    result = with_handler(outer, lambda: with_handler(inner, body))
    print(result)


if __name__ == "__main__":
    main()
```
