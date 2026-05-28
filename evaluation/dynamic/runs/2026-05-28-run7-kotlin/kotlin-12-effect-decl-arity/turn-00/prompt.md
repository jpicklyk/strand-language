# Turn 00 of session kotlin-12-effect-decl-arity

Task: `12-effect-decl-arity` | Config: `kotlin` | Model: `claude-sonnet-4-7`
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

# Task 12 — Multi-parameter effect: network connect with host + port

Open a network connection to host `"localhost"` on port `8080` using
`strand-builtin:Net.Connect`. Wrap the call in a `Handler` that
intercepts `Network.Connect` and returns a fake socket handle of
`42`. Add 1 to the handle. Final value is `43`.

The reference implementation must:
- Declare an `EffectCategory` named `Network.Connect` parameterized
  by two refinement parameters in order: `(host: String, port: Int)`.
- Declare the foreign builtin `strand-builtin:Net.Connect` of type
  `(String, Int) -> Int` that declares the `Network.Connect` effect.
- Construct the host literal (`"localhost"`) and port literal (`8080`).
- Supply an `EffectDecl` whose parameters list contains the two
  literals in EffectCategory-declaration order: `[host, port]`.
- Apply the builtin with `[host, port]` arguments and the
  EffectDecl, then add 1.
- Wrap the whole `add` expression in a `Handler` that intercepts
  `Network.Connect`. The `handle` is a lambda
  `(host: String, port: Int) -> Int` returning `42` so the program
  produces 43 without making a real network call.

This task exercises: multi-parameter `EffectCategory` declaration,
`EffectDecl` parameter-order discipline (positional binding to the
category's parameter list), `Application.effectInstances` wiring
when an EffectDecl is supplied at the call site, and a Handler whose
`handle` signature matches a multi-parameter intercepted function.
The verifier rejects an EffectDecl whose parameter count or
parameter types do not match the EffectCategory.

The Python parallel uses a stubbed `connect(host, port) -> int` that
returns 42; Python has no effect-decl discipline so the comparison
is on whether the right shape gets emitted first try.


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.