# Turn 00 of session kotlin-14-schema-invariant-rescue

Task: `14-schema-invariant-rescue` | Config: `kotlin` | Model: `claude-sonnet-4-7`
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


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.