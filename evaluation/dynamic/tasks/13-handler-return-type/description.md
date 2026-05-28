# Task 13 — Handler returns the wrong type

Install a `Handler` that intercepts `Time.Now` and returns the fixed
integer `99`. The handler's body calls `Time.Now` then adds 1. The
handler replaces the `Time.Now` call, so the program produces
`99 + 1 = 100`.

The reference implementation must:
- Use the prelude `now`, `nowT`, and `nowFx` (Time.Now) reserved
  names.
- Write a body that calls `Time.Now` and adds 1.
- Wrap the body in a `Handler` whose `intercept` is `nowFx` and
  whose `handle` is a zero-arg lambda returning the **integer**
  literal `99`. The lambda type must be `() -> Int` to match
  `Time.Now`'s signature; a handle returning a different type
  (`String`, `Bool`, a product wrapping the int) trips the
  verifier.

This task exercises: Handler signature discipline. The verifier
rejects a Handler whose handle lambda has a different return type
from the intercepted function via `HandlerSignatureMismatch`. The
slip the task probes is the natural confusion between "99 as a
number" and "99 as a string" when an agent writes a Lambda body —
inline literals make the type visible, so the verifier can name the
mismatch precisely.

The Python parallel uses a stack-based handler shim returning `99`
and the same `now() + 1` body; Python's static type system catches
the equivalent error via mypy only if the function is annotated.
