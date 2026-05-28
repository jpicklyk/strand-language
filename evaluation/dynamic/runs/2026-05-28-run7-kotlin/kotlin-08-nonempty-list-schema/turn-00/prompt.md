# Turn 00 of session kotlin-08-nonempty-list-schema

Task: `08-nonempty-list-schema` | Config: `kotlin` | Model: `claude-sonnet-4-7`
Attempt: 1 / 5

---

## SYSTEM

# Kotlin reference

You are emitting Kotlin programs targeting JVM bytecode. Programs are compiled
with `kotlinc -include-runtime -d program.jar program.kt` and executed with
`java -jar program.jar`. The Kotlin compiler is the static checker — it
enforces the type system, immutability rules, exhaustiveness on sealed-class
matches, and null safety. Compile-time failures surface as structured
diagnostics in the form `program.kt:LINE:COL: error: <message>`.

## Output conventions

- Provide a complete program with a `fun main()` entry point.
- The program prints its final value to stdout. The eval framework compares
  stdout (whitespace-stripped) to the task's expected output.
- Use idiomatic Kotlin: `data class` for records, `sealed class` (or `sealed
  interface`) for sum types, `init { require(...) }` for invariant
  enforcement, `when` for pattern matching.
- The Kotlin version targeted is 1.9.x or later; assume a recent stdlib.
- Do not depend on third-party libraries — the compile command uses
  `-include-runtime` and the Kotlin stdlib only.

## Output format

Place the program inside a fenced `kotlin` code block:

```
```kotlin
fun main() {
    // your program
    println(/* result */)
}
```
```

No commentary outside the fence is needed.

## What counts as a "schema" in Kotlin

Tasks that ask for a Schema/Invariant pattern map naturally to a Kotlin
`data class` with an `init` block enforcing the constraint:

```kotlin
data class PositiveInt(val value: Int) {
    init { require(value > 0) { "PositiveInt requires value > 0, got $value" } }
}
```

The compile-time gate (mypy-equivalent) is the type system; the runtime gate
(SchemaInvariantViolation-equivalent) is the `require` call in `init`.

## What counts as a "state machine" in Kotlin

Tasks that ask for a state machine driving over an event list map to a small
class with `var` state and a `step(event): Output` method, plus a `main()`
that folds a fixed event list through it and prints the final state:

```kotlin
class Toggle {
    var state: Boolean = false
    fun step(event: Unit) { state = !state }
}

fun main() {
    val m = Toggle()
    repeat(3) { m.step(Unit) }
    println(m.state)
}
```

## What counts as "effects" in Kotlin

Kotlin has no first-class effect system, so effect-heavy tasks translate
loosely. For tasks that exercise Strand's effect-coverage rule (a Lambda
whose body uses Filesystem.Write must declare the effect), the Kotlin
parallel is a regular function call — the framework treats compile-time
acceptance as the "effect check" since Kotlin can't express the constraint
the verifier checks. Just make the program compile and produce the expected
output.

For tasks that exercise an effect handler (intercept/replace pattern), use a
plain function call with a stubbed implementation that returns the expected
value:

```kotlin
fun now(): Int = 42  // stubs Time.Now
fun main() { println(now() + 1) }  // produces 43
```

## Error recovery

If your program fails to compile, you receive feedback shaped like:

```
kotlinc reported the following errors:
  line N, col M: error: <message>
```

Use the line/col to locate the failing token. Kotlin error messages are
usually precise — "unresolved reference: foo" means foo isn't declared in
scope; "type mismatch: required Int, found String" means a type conversion
is missing.


## USER

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


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.