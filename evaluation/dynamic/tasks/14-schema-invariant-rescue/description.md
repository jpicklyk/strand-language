# Task 14 — Schema invariant on the boundary

Declare a `PositiveInt` Schema over `Int` with the invariant `x > 0`
(strictly greater than zero — `0` is NOT a valid `PositiveInt`).
Construct a `PositiveInt`-typed value equal to the smallest integer
the invariant admits, and flow it through an identity lambda whose
parameter type is `PositiveInt`.

The reference implementation must:
- Declare an `Invariant` whose body is a pure lambda
  `(x: Int) -> Bool` returning `x > 0` (use `gt`, NOT `ge` —
  zero must be rejected).
- Declare a `Schema` named `PositiveInt` whose `valueType` is `Int`
  and whose `invariants` list contains the above invariant.
- Construct an Int literal equal to the smallest integer that
  satisfies `x > 0`, then flow it through an identity lambda
  `(p: PositiveInt) -> PositiveInt`.

This task exercises: Schema declaration, Invariant on a primitive
type, schema-typed value flow with a boundary literal, and the
Strand verifier's static evaluation of the invariant against the
literal. The boundary `x > 0` distinguishes the smallest valid
integer (`1`) from the boundary value (`0`) that an agent often
emits as a "default" when reading "the smallest integer." A literal
of `0` flowing into the `PositiveInt` parameter trips
`SchemaInvariantViolation` and the agent must distinguish strict
positive from non-negative.

The Python parallel uses a `PositiveInt` dataclass with a
`__post_init__` invariant check raising on violation.
