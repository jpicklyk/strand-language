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
            raise ValueError("NonEmptyList invariant violated: list is Nil")


def identity(xs: NonEmptyList) -> NonEmptyList:
    return xs


def main() -> None:
    one_element: IntList = Cons(1, Nil())
    schema_value = NonEmptyList(one_element)
    result = identity(schema_value)
    print(result)


if __name__ == "__main__":
    main()
```
