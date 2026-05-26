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


def sum_list(xs: List) -> int:
    match xs:
        case Cons(head=h, tail=t):
            return h + sum_list(t)
        case Nil():
            return 0


def main() -> None:
    xs: List = Cons(1, Cons(2, Cons(3, Nil())))
    print(sum_list(xs))


if __name__ == "__main__":
    main()
```
