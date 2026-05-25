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


def sum_list(xs: IntList) -> int:
    match xs:
        case Cons(head=h, tail=t):
            return h + sum_list(t)
        case Nil():
            return 0


def main() -> None:
    xs: IntList = Cons(1, Cons(2, Cons(3, Nil())))
    print(sum_list(xs))


if __name__ == "__main__":
    main()
