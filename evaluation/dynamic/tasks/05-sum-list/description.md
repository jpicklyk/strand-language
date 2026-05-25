# Task 05 — Sum a list of integers

Implement a recursive function that sums the elements of a linked
list of integers. Apply it to the list `[1, 2, 3]` so the program
produces 6.

The reference implementation must:
- Define a `List` recursive sum type with two cases: `Cons(head: Int,
  tail: List)` and `Nil`.
- Define a recursive function `sum: List -> Int` whose `Cons` arm
  returns `head + sum(tail)` and whose `Nil` arm returns `0`.
- Recurse via the language's standard fixpoint mechanism (no
  iteration).
- Construct the list `Cons(1, Cons(2, Cons(3, Nil)))` and apply
  `sum` to it.

This task exercises: recursive sum type (`μ. Cons(...) | Nil`),
Fixpoint, Match with a `Cons` constructor pattern that nests a
product-pattern binder, list construction, recursive value.
Maps to corpus program 32.

The Python reference uses `@dataclass(frozen=True)` plus a `Union`
alias for the list type, and a recursive function with a
`match`/`case` block to dispatch.
