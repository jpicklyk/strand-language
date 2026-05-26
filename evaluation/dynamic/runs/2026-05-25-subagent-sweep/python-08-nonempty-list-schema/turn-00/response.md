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


def is_nonempty(xs: IntList) -> bool:
    match xs:
        case Cons():
            return True
        case Nil():
            return False


@dataclass(frozen=True)
class NonEmptyList:
    value: IntList

    def __post_init__(self) -> None:
        if not is_nonempty(self.value):
            raise ValueError("NonEmptyList invariant violated: list is empty (Nil)")


def identity(xs: NonEmptyList) -> NonEmptyList:
    return xs


def main() -> None:
    one_element: IntList = Cons(1, Nil())
    wrapped = NonEmptyList(one_element)
    result = identity(wrapped)
    print(result)


if __name__ == "__main__":
    main()
```
