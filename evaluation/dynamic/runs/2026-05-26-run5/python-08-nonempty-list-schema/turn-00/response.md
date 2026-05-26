```python
from dataclasses import dataclass
from typing import Callable, Union


@dataclass(frozen=True)
class Nil:
    pass


@dataclass(frozen=True)
class Cons:
    head: int
    tail: "IntList"


IntList = Union[Nil, Cons]


def is_non_empty(xs: IntList) -> bool:
    match xs:
        case Nil():
            return False
        case Cons(_, _):
            return True


@dataclass(frozen=True)
class NonEmptyList:
    value: IntList

    def __post_init__(self) -> None:
        if not is_non_empty(self.value):
            raise ValueError("NonEmptyList invariant violated: list is empty")


def identity(xs: NonEmptyList) -> NonEmptyList:
    return xs


def main() -> None:
    value: IntList = Cons(1, Nil())
    wrapped = NonEmptyList(value)
    result = identity(wrapped)
    print(result)


if __name__ == "__main__":
    main()
```
