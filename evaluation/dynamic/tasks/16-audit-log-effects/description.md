# Task 16 — Audited doubling helper

Define a helper function `auditedDouble: (Int) -> Int` that doubles
its argument and writes an audit entry recording when it ran. The
main program applies the helper to `21`, so the final value is `42`.

The reference implementation must:
- Build the audit message by concatenating the prefix `"audit:"`
  with the current clock time in milliseconds rendered as a string
  (prelude `now` and `intToStr`, joined with `concat`).
- Write the audit entry with the prelude `logInfo` builtin and
  discard its Unit result (a `LET` binding works).
- Declare the helper Lambda's effect row explicitly in its `effects`
  slot — do not rely on effect inference to fill it in.
- Return the doubled argument from the helper, and apply the helper
  to `21` as the program result.

The audit message content is not checked (the clock value varies);
only the final value `42` is.

The Python parallel writes the audit entry with the `logging` module
(which reports to stderr) and prints only the final value `42` to
stdout. Python has no effect system, so nothing in the Python
parallel can fail the way the Strand verifier can; the comparison
measures what Strand's stricter contract costs or saves end-to-end.
