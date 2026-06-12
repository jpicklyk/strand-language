# Probe metadata — task 20 (connect-effect-decl)

NOT part of the agent-facing prompt. The harness sends only
`description.md`; this file documents the probe design for operators.

- **Target verifier-error family:** effect-projection / argument
  mismatch (Q-039) — `ProjectionMismatch`, with
  `EffectDeclParameterTypeMismatch` and `EffectDeclArityMismatch` as
  sibling outcomes of the same authoring decision. The prelude
  `netConnect` carries projections pinning `connectFx{host, port}` to
  ArgRef(0)/ArgRef(1); an explicitly-supplied EffectDecl must bind
  the EXACT argument nodes, in category parameter order.
- **Predicted failure modes (all validated against the verifier):**
  - duplicate value-equal literals (EFD parameters authored as fresh
    `STR`/`ILT` nodes instead of reusing the argument nodes):
    `ProjectionMismatch(at=#5 'callConnect', categoryIndex=0,
    sourceIndex=0, expected=ArgRef(index=0), actualParam=#2 'declHost')`
  - swapped parameter order `[port host]`:
    `EffectDeclParameterTypeMismatch(at=#2 'cd', parameterIndex=0,
    expected=String, actual=Int)`
  - missing parameter `[host]`:
    `EffectDeclArityMismatch(at=#2 'cd', expected=2, actual=1)`
- **Python-baseline failure shape:** none — Python has no effect
  declarations; the stubbed connect just runs. mypy --strict passes.
- **Design notes:** differs from task 12 (custom EffectCategory,
  arity discipline) by exercising the PRELUDE projection path — the
  node-identity rule (capability-check values must be the same nodes
  the call consumes) is content-addressing-specific and has no
  conventional-language analogue, making it the sharpest probe of
  whether structured feedback can teach a model a rule it cannot
  know from training data. The Handler keeps the cell deterministic
  (no real socket); its signature must match netConnect's
  `(String, Int) -> Int`, so handler-signature slips remain a
  secondary semantic outcome.
