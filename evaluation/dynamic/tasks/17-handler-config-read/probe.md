# Probe metadata — task 17 (handler-config-read)

NOT part of the agent-facing prompt. The harness sends only
`description.md`; this file documents the probe design for operators.

- **Target verifier-error family:** handler signature mismatch —
  `HandlerSignatureMismatch` (handle lambda's parameter list or
  return type differs from the intercepted call's signature).
- **Predicted failure mode:** the task says the handler "supplies the
  fixed configuration text", which invites a handle that returns a
  String — but `fsRead` is `(String) -> Bytes`, so the handle must be
  `(String) -> Bytes` (e.g., `fromUtf8` of the text). Two natural
  slips, both validated against the verifier:
  - zero-arg handle: `HandlerSignatureMismatch(... expected=() ->
    Bytes, actual=(String) -> ...Bytes)`
  - String-returning handle: `HandlerSignatureMismatch(... expected=
    (String) -> String, actual=(String) -> ...Bytes)`
- **Python-baseline failure shape:** silent success — the natural
  Python slip (a fake read returning `str` instead of `bytes`) still
  passes because `len()` works on both, and mypy --strict only flags
  it when the fake's annotation contradicts its body. The cell
  contrasts Strand's loud structural rejection with Python's silent
  type drift.
- **Design lesson applied from task 13:** task 13 spelled out the
  required handle type ("the lambda type must be () -> Int") and the
  cell converged once the unrelated grammar slip cleared; this
  description states only the interception behavior and lets the
  signature discipline be discovered through the verifier.
