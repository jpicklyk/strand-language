```python
from dataclasses import dataclass
from typing import Union


@dataclass(frozen=True)
class Nil:
    pass


@dataclass(frozen=True)
class Cons:
    head: int
    tail: "List"


List = Union[Cons, Nil]


def length(lst: List) -> int:
    match lst:
        case Nil():
            return 0
        case Cons(_, tail):
            return 1 + length(tail)


def main() -> None:
    lst: List = Cons(7, Cons(8, Nil()))
    print(length(lst))


if __name__ == "__main__":
    main()
```
