# Task 04 — Option unwrap with default

Define an Option-like sum type and a function (or match expression)
that unwraps a `Some(n)` value, returning the inner integer, and
returns 0 for the `None` case. Apply the unwrap to `Some(42)` so the
program produces 42.

The reference implementation must:
- Define an `Option` sum type with two cases: `Some(Int)` and `None`.
- Construct the value `Some(42)`.
- Pattern-match on the value: the `Some(n)` arm returns the inner `n`;
  the `None` arm returns the default `0`.
- The program's final value is `42`.

This task exercises: sum-type declaration, constructor pattern
matching with a variable payload binder, variable extraction from a
sum payload, fallback / default arm. Maps to corpus program 25.

The Python reference uses `@dataclass(frozen=True)` cases joined by a
`Union` alias and a `match`/`case` block to dispatch. The Strand
reference uses `Pattern.kind = constructor` with a nested
`VariablePattern` for the `Some` payload.
