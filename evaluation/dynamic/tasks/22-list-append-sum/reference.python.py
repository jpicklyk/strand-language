"""Python parallel of task 22.

Strand probes `UnboundRecursiveSelf` at recursive-value construction
inside the Fixpoint body (append rebuilds Cons cells). Python's
recursive dataclasses have no inner/outer discipline; the parallel
is idiomatic.
"""

from dataclasses import dataclass
from typing import Union

LinkedList = Union["Cons", "Nil"]


@dataclass(frozen=True)
class Nil:
    pass


@dataclass(frozen=True)
class Cons:
    head: int
    tail: "LinkedList"


def append(xs: LinkedList, ys: LinkedList) -> LinkedList:
    match xs:
        case Nil():
            return ys
        case Cons(head, tail):
            return Cons(head, append(tail, ys))
        case _:
            raise AssertionError("unreachable")


def list_sum(zs: LinkedList) -> int:
    match zs:
        case Nil():
            return 0
        case Cons(head, tail):
            return head + list_sum(tail)
        case _:
            raise AssertionError("unreachable")


def main() -> None:
    list12 = Cons(1, Cons(2, Nil()))
    list3 = Cons(3, Nil())
    print(list_sum(append(list12, list3)))


if __name__ == "__main__":
    main()
