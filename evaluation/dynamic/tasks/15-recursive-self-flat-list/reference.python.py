"""Python parallel of task 15.

Strand probes `UnboundRecursiveSelf` when an agent's emission routes
the inner-PRD-with-`RS` form to a top-level SumValue payload
position. Python uses a frozen dataclass `Cons` and a sentinel `Nil`
plus a recursive `length` function with `match`/`case`.
"""

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


def length(xs: List) -> int:
    match xs:
        case Cons(_, tail):
            return 1 + length(tail)
        case Nil():
            return 0
    raise AssertionError("unreachable")


def main() -> None:
    xs: List = Cons(7, Cons(8, Nil()))
    print(length(xs))


if __name__ == "__main__":
    main()
