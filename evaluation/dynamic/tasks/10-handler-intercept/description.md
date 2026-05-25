# Task 10 — Effect handler intercepts logger

Nested effect handlers for the same effect category (`Time.Now`) —
the innermost handler wins. The body calls `now()`; the inner handler
intercepts and returns `2`; the outer handler intercepts and returns
`1`. The interpreter's innermost-wins semantics selects the inner
handler. The program produces `2`.

The reference implementation must:
- Declare an `EffectCategory` named `Time.Now`.
- Declare a `ForeignNode` for `strand-builtin:Time.Now` (declaring the
  effect), and a body that calls it.
- Wrap the body in a `Handler` that intercepts `Time.Now` and whose
  `handle` is a zero-arg lambda returning `2`.
- Wrap that handler in an outer `Handler` that intercepts the same
  category and whose `handle` returns `1`.
- The program runs under empty capabilities — the closure-subtraction
  rule (`closureOf(handler) = (closureOf(body) - {intercept}) ∪ ...`)
  removes `Time.Now` from the surrounding requirements.

This task exercises: `Handler` declaration, intercept dispatch on a
declared effect, nested handler stack (innermost wins via `findLast`
over the active-handler stack), closure-subtraction semantics that
removes the intercepted effect from the body's required capabilities.
Maps to corpus program 38.

Python has no effect-handler primitive. The reference uses a global
list of "active handlers" (a stack) and a `now()` shim that consults
the top of the stack instead of producing a real timestamp. The
match for Strand's innermost-wins semantics is the stack's last-in-
first-out behavior.
