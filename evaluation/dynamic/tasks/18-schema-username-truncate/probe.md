# Probe metadata — task 18 (schema-username-truncate)

NOT part of the agent-facing prompt. The harness sends only
`description.md`; this file documents the probe design for operators.

- **Target verifier-error family:** schema invariant violation on a
  DYNAMIC value — runtime `SchemaInvariantViolation` via the Q-047
  deferred-obligation path. The username is built with `concat` (an
  Application), so the verifier cannot evaluate it statically: verify
  passes with `SchemaInvariantDeferred` diagnostics and the invariant
  is enforced when `strand run` evaluates the value. This complements
  task 14, which probes the STATIC path on a boundary literal.
- **Predicted failure mode:** the agent flows the 12-character
  concatenation straight into the `ShortName`-typed parameter
  (missing that 6 + 6 > 8), or truncates with wrong `subStr` bounds
  (`(s, 1, 8)` / `(s, 0, 9)`). Validated wrong-variant error (at run
  time, after a clean verify):
  `SchemaInvariantViolation(at=#3 'full', schema=#2 'shortName',
  invariant=#1 'nameInv', valueDescription=StringV(v=strand_agent))`.
- **Python-baseline failure shape:** runtime `ValueError` traceback
  from the dataclass `__post_init__` invariant check — the canonical
  "find out when it crashes" feedback this comparison is about. mypy
  --strict does not catch it.
- **Design lesson applied from task 14:** task 14's expected.yaml
  steered construction to an arithmetic that folds to a passing value
  and the description named the boundary rule; here the conflict
  (12 > 8) is implicit in the data and the resolution (keep the first
  8 characters) is stated as a business rule, not as schema guidance.
