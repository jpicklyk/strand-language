# Task 19 — Sum the leaves of a binary tree

Define a recursive binary-tree type
`Tree = μ. Leaf(Int) | Node({left: Tree, right: Tree})`. Write a
recursive function `sumLeaves: Tree -> Int` that returns the payload
for a `Leaf` and the sum of both subtree sums for a `Node`. Apply it
to the tree `Node(Leaf(3), Node(Leaf(4), Leaf(5)))`. The final value
is `12`.

The reference implementation must:
- Declare the tree type with `RecursiveType` (`RT`) wrapping a
  `SumType` whose `Leaf` case carries an `Int` payload and whose
  `Node` case carries a two-field product `{left, right}` of
  subtrees.
- Define `sumLeaves` via `Fixpoint` (the body Lambda's first
  parameter is the recursive call slot, the second is the tree).
- Match the scrutinee with two `MatchCase`s: a `ConstructorPattern`
  for `Leaf` binding the payload, and a `ConstructorPattern` for
  `Node` binding the product so `left` and `right` can be extracted
  with `ProductFieldGet` for the two recursive calls.
- Construct the three-leaf tree with `SumValue` + `ProductValue` and
  apply `sumLeaves` to it.

The Python parallel uses frozen dataclasses `Leaf(value)` and
`Node(left, right)` with a recursive function using `match`/`case`,
and prints `12`.
