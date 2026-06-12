"""Python parallel of task 19.

Strand probes `UnboundRecursiveSelf` on the two-self-field Node
product (the inner/outer PRD split, which auto-synthesis does not
cover for multi-field recursion). Python has no equivalent
discipline; the dataclass tree works directly.
"""

from dataclasses import dataclass
from typing import Union

Tree = Union["Leaf", "Node"]


@dataclass(frozen=True)
class Leaf:
    value: int


@dataclass(frozen=True)
class Node:
    left: "Tree"
    right: "Tree"


def sum_leaves(tree: Tree) -> int:
    match tree:
        case Leaf(value):
            return value
        case Node(left, right):
            return sum_leaves(left) + sum_leaves(right)
        case _:
            raise AssertionError("unreachable")


def main() -> None:
    tree = Node(Leaf(3), Node(Leaf(4), Leaf(5)))
    print(sum_leaves(tree))


if __name__ == "__main__":
    main()
