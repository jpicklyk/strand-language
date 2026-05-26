from dataclasses import dataclass
from typing import Callable


@dataclass(frozen=True)
class EffectCategory:
    name: str


@dataclass(frozen=True)
class Handler:
    category: EffectCategory
    handle: Callable[[], int]


TIME_NOW = EffectCategory(name="Time.Now")

_active_handlers: list[Handler] = []


def now() -> int:
    for handler in reversed(_active_handlers):
        if handler.category == TIME_NOW:
            return handler.handle()
    raise RuntimeError("No handler for Time.Now")


def with_handler(handler: Handler, body: Callable[[], int]) -> int:
    _active_handlers.append(handler)
    try:
        return body()
    finally:
        _active_handlers.pop()


def main() -> None:
    def body() -> int:
        return now()

    def inner_wrapped() -> int:
        return with_handler(Handler(category=TIME_NOW, handle=lambda: 2), body)

    result = with_handler(Handler(category=TIME_NOW, handle=lambda: 1), inner_wrapped)
    print(result)


if __name__ == "__main__":
    main()
