# Task 22 — Append two lists, then sum the result

Define a recursive linked-list type
`List = μ. Cons(head: Int, tail: List) | Nil`. Write two recursive
functions over it:

- `append: (List, List) -> List` — returns the second list when the
  first is `Nil`; for `Cons` it rebuilds a `Cons` cell whose head is
  the matched head and whose tail is `append(tail, second)`.
- `sum: List -> Int` — returns `0` for `Nil` and
  `head + sum(tail)` for `Cons`.

Apply them to compute `sum(append(Cons(1, Cons(2, Nil)),
Cons(3, Nil)))`. The final value is `6`.

The reference implementation must:
- Declare the recursive list type with `RecursiveType` wrapping a
  `SumType` with `Cons(head, tail)` and `Nil` cases.
- Define both functions via `Fixpoint` (body Lambda's first
  parameter is the recursive call slot).
- In `append`'s `Cons` case, construct the new `Cons` cell with
  `SumValue` + `ProductValue` inside the match body.
- Construct the two input lists and apply
  `sum(append(list12, list3))` as the program result.

The Python parallel uses a frozen dataclass `Cons`, a sentinel
`Nil`, and two recursive functions with `match`/`case`, printing
`6`.
