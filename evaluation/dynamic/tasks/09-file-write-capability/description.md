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
