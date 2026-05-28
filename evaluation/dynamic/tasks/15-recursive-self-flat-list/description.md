# Task 15 — Length of a recursive linked list

Define a recursive linked-list type `List = μ. Cons(head: Int, tail:
List) | Nil`. Write a recursive function `length: List -> Int` that
returns `0` for `Nil` and `1 + length(tail)` for `Cons`. Apply it to
the list `Cons(7, Cons(8, Nil))`. The final value is `2`.

The reference implementation must:
- Declare the recursive list type using `RecursiveType` (`RT`) wrapping
  a `SumType` with `Cons(head: Int, tail: <RecursiveSelf>)` and `Nil`
  cases.
- Declare `length` via `Fixpoint` over a `() -> Int`-shaped recursive
  call slot. The body Lambda's first parameter is the recursive call;
  remaining parameters are the user-facing arguments.
- Match the list scrutinee using `Match` with two `MatchCase`s — a
  `ConstructorPattern` for `Cons` with a `VariablePattern` payload
  binder and a `ConstructorPattern` for `Nil`.
- The `Cons` body extracts no payload field; it just returns `1 +
  length(tail)`. The `Nil` body returns `0`.
- Construct the two-element list `Cons(7, Cons(8, Nil))` using
  `SumValue` + `ProductValue`.

This task exercises: `RecursiveType` declaration, `RecursiveSelf`
positioning, `Fixpoint` recursive function definition, constructor
pattern with payload binder, `ProductFieldGet` through the matched
binder (to reach `tail` for the recursive call), and value
construction of a sum-of-product recursive shape. The probe target
is `UnboundRecursiveSelf` — a structurally common authoring slip
when the agent routes the inner-PRD-with-`RS` form to a top-level
SumValue payload. The verifier's `UnboundRecursiveSelf` error hint
(structured English with a corpus pointer) is the rescue path.

The Python parallel uses a frozen dataclass `Cons` and a sentinel
`Nil` plus a recursive `length` function with `match`/`case`.
