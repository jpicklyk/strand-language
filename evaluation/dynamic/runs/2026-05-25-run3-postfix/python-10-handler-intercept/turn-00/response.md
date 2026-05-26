```python
from typing import Callable


_handlers: list[Callable[[], int]] = []


def now() -> int:
    if _handlers:
        return _handlers[-1]()
    raise RuntimeError("Time.Now used without a handler")


def main() -> None:
    outer: Callable[[], int] = lambda: 1
    inner: Callable[[], int] = lambda: 2
    _handlers.append(outer)
    _handlers.append(inner)
    try:
        print(now())
    finally:
        _handlers.pop()
        _handlers.pop()


if __name__ == "__main__":
    main()
```
