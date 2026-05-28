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


def sum_list(lst: List) -> int:
    match lst:
        case Nil():
            return 0
        case Cons(head=h, tail=t):
            return h + sum_list(t)


def main() -> None:
    lst: List = Cons(1, Cons(2, Cons(3, Nil())))
    print(sum_list(lst))


if __name__ == "__main__":
    main()
```
