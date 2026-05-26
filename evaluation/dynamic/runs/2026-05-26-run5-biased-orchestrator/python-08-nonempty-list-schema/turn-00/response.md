```python
from __future__ import annotations
from dataclasses import dataclass
from typing import Union


@dataclass(frozen=True)
class Cons:
    head: int
    tail: "List"


@dataclass(frozen=True)
class Nil:
    pass


List = Union[Cons, Nil]


@dataclass(frozen=True)
class NonEmptyList:
    value: List

    def __post_init__(self) -> None:
        if isinstance(self.value, Nil):
            raise ValueError("NonEmptyList invariant violated: Nil")


def main() -> None:
    xs: List = Cons(1, Nil())
    wrapped: NonEmptyList = NonEmptyList(xs)
    print(wrapped.value)


if __name__ == "__main__":
    main()
```
