# Turn 00 of session kotlin-15-recursive-self-flat-list

Task: `15-recursive-self-flat-list` | Config: `kotlin` | Model: `claude-sonnet-4-7`
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


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.