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


def sum_list(xs: List) -> int:
    match xs:
        case Nil():
            return 0
        case Cons(head=h, tail=t):
            return h + sum_list(t)


def main() -> None:
    xs: List = Cons(1, Cons(2, Cons(3, Nil())))
    print(sum_list(xs))


if __name__ == "__main__":
    main()
