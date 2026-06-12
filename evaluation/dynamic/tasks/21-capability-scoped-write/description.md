# Task 21 — Audit write inside a narrowed capability scope

Write an audit entry using the stub builtin
`strand-builtin:Filesystem.Write` (type `(String) -> Int`, declares
the prelude `writeFx` category; in the evaluation environment the
stub performs no real IO and returns `0`).

The audit file's path is derived from the clock: concatenate the
prefix `"audit-"` with the current time in milliseconds rendered as
a string (prelude `now`, `intToStr`, `concat`).

The whole write expression — including deriving the path — must run
inside a `CapabilityScope` (`CAP`) that narrows the capability
context to exactly the effects the scoped expression exercises.

The program adds `7` to the stub's result, so the final value is
`7`.

The reference implementation must:
- Declare the stub `ForeignNode` with type `(String) -> Int` and the
  prelude `writeFx` effect.
- Build the timestamped path and apply the stub to it inside the
  `CAP` body.
- Narrow the `CAP`'s capability list to exactly what the body needs.
- Add `7` to the scope's value as the program result.

The Python parallel uses a `write_audit(path)` stub returning `0`,
derives the path from `time.time()`, and prints `7`. Python has no
capability scopes, so the parallel cannot fail the way the Strand
verifier can.
