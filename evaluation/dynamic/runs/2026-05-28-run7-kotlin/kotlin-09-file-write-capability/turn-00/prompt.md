# Turn 00 of session kotlin-09-file-write-capability

Task: `09-file-write-capability` | Config: `kotlin` | Model: `claude-sonnet-4-7`
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

# Task 09 — File write under capability

Compose the effectful builtin `Filesystem.Write` with the pure
builtin `Int.Add`: write to a file path (the reference
implementation's Filesystem.Write is a no-op stub that returns 0
bytes-written), then add 1 to the result.

The reference implementation must:
- Declare an `EffectCategory` named `Filesystem.Write` (a refinement-
  bearing effect with a `String` path parameter).
- Declare the foreign builtin `strand-builtin:Filesystem.Write` of
  type `(String) -> Int` that declares the `Filesystem.Write` effect.
- Call the builtin with the path `"/tmp/strand-eval.log"`, then add 1
  to the result.

This task exercises: `EffectCategory` declaration with refinement
parameter, `ForeignNode` carrying declared effects, `Application`
through an effectful function, effect-closure propagation through
composed pure + effectful Applications. The reference Filesystem.Write
returns 0 bytes, so the final value is `0 + 1 = 1`.

The Python reference uses a regular function call (Python has no
effect system; the "capability" is implicit) and composes the result
with arithmetic. Mapped from corpus program 17 (the same
pure-+-effectful composition pattern, with Filesystem.Write
substituted for the original Time.Now).


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.