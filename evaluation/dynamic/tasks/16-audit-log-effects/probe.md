# Probe metadata — task 16 (audit-log-effects)

NOT part of the agent-facing prompt. The harness sends only
`description.md`; this file documents the probe design for operators.

- **Target verifier-error family:** effect-closure violation —
  `UncoveredEffects` (callee effect not covered by the Lambda's
  declared effect row).
- **Predicted failure mode:** the helper is "a logging function", so
  the natural explicit effect row is `[logFx]`. The body also reads
  the clock (`now` → `nowFx`) to build the message, so the full
  closure is `{logFx, nowFx}`. The task requires the row to be
  declared explicitly (no inference), which disables the Elaborator's
  Lambda.effects auto-fill — the rescue that made task 11 converge
  first-pass. Validated wrong-variant error:
  `UncoveredEffects(at=#4 'auditedDouble', missing=[#22 (prelude 'nowFx')])`.
- **Python-baseline failure shape:** none — Python has no effect
  system; `logging` + `time.time()` always run and mypy --strict
  passes. The cell measures the cost of Strand's stricter contract
  against a baseline that cannot fail this way.
- **Design lesson applied from tasks 11/14/15:** the description
  states behavior (log a timestamped entry), not the rule ("a Lambda
  whose body exercises X must list X"), and never names the expected
  error.
