```python
from dataclasses import dataclass
from typing import Union


@dataclass(frozen=True)
class Nil:
    pass


@dataclass(frozen=True)
class Cons:
    head: int
    tail: "IntList"


IntList = Union[Cons, Nil]


@dataclass(frozen=True)
class NonEmptyList:
    value: IntList

    def __post_init__(self) -> None:
        if isinstance(self.value, Nil):
            raise ValueError("NonEmptyList invariant violated: list is Nil")


def main() -> None:
    lst: IntList = Cons(1, Nil())
    wrapped = NonEmptyList(lst)
    print(wrapped)


if __name__ == "__main__":
    main()
```
