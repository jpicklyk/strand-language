# Task 08 — NonEmpty list (Schema + Invariant on recursive type)

Define a Schema named `NonEmptyList` over the recursive list type
`μ. Cons(head: Int, tail: <self>) | Nil` whose invariant rejects the
empty (`Nil`) list. Construct a one-element list `Cons(1, Nil)` and
flow it through the Schema.

The reference implementation must:
- Declare the recursive list type (using `RecursiveType` /
  `RecursiveSelf`).
- Declare an `Invariant` whose body is a pure lambda
  `(xs: List) -> Bool` returning `true` when the input is `Cons(...)`
  and `false` when it is `Nil`.
- Declare a `Schema` named `NonEmptyList` whose `valueType` is the
  recursive list type and whose `invariants` list contains the above
  invariant.
- Construct `Cons(1, Nil)` and flow it through an identity lambda
  whose parameter type is the Schema.

This task exercises: Schema declaration over a recursive type, pure
invariant body that pattern-matches on the recursive value, schema-
typed value flow at value-construction sites, the
"inner/outer" ProductType pattern that recursive sum types require.
Maps to corpus program 52.

The Python reference uses a `@dataclass(frozen=True)`-plus-`Union`
encoding of the list and a `__post_init__` check at the Schema
wrapper that raises on `Nil` input.
