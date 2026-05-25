# Task 07 — Bounded counter (Schema + Invariant on Int)

Define a Schema named `PositiveInt` over the `Int` value type whose
invariant is `x > 0`. Construct a value of this Schema (any positive
integer literal will do; the reference uses 5).

The reference implementation must:
- Declare an `Invariant` whose body is a pure lambda
  `(x: Int) -> Bool` returning `x > 0`.
- Declare a `Schema` named `PositiveInt` whose `valueType` is `Int`
  and whose `invariants` list contains the above invariant.
- Construct a `PositiveInt`-typed value by flowing an Int literal
  (e.g., 5) through an identity lambda whose parameter type is the
  Schema. The lambda body is the parameter reference.

This task exercises: Schema declaration, Invariant on a primitive
type, schema-typed value flow (Int → SchemaType<Int> at value-flow
sites), pure-expression invariant body checked statically at verify
time. Maps to corpus program 50.

The Python reference uses `@dataclass(frozen=True)` with a
`__post_init__` invariant check that raises `ValueError` on
violation. Python lacks a static schema mechanism; the success check
for the Python baseline is that mypy --strict accepts the
construction and that the program runs without raising.
