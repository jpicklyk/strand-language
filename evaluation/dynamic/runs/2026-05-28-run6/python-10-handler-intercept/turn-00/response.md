```python
from dataclasses import dataclass
from typing import Callable, Union


@dataclass(frozen=True)
class Handler:
    category: str
    handle: Callable[[], int]


_active_handlers: list[Handler] = []


def now() -> int:
    for handler in reversed(_active_handlers):
        if handler.category == "Time.Now":
            return handler.handle()
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
    inner_handler = Handler(category="Time.Now", handle=lambda: 2)
    outer_handler = Handler(category="Time.Now", handle=lambda: 1)

    def inner_wrapped() -> int:
        return with_handler(inner_handler, body)

    result = with_handler(outer_handler, inner_wrapped)
    print(result)


if __name__ == "__main__":
    main()
```
