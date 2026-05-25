# Task 02 — JSON value

Construct a typed JSON value: `JsonNumber(42)`.

The reference implementation must:
- Define a `JsonValue` sum type with at least four cases:
  `JsonNull`, `JsonBool(Bool)`, `JsonNumber(Int)`, `JsonString(String)`.
- Construct the value `JsonNumber(42)`.
- Wrap the value in a schema named `JsonValue` (or equivalent typed
  alias) so downstream consumers receive a tagged constructor, not a
  raw integer.

This task exercises: sum-type declaration, constructor invocation,
schema declaration without invariants (the type IS the contract), the
schema-typed value-flow path. Maps to corpus program 54.

The Python reference uses `dataclasses` to define a sum-like
discriminated union. (Python doesn't have native sum types; the
dataclass-+-Union encoding is the idiomatic equivalent.)
