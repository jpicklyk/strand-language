# Probe metadata — task 21 (capability-scoped-write)

NOT part of the agent-facing prompt. The harness sends only
`description.md`; this file documents the probe design for operators.

- **Target verifier-error family:** capability-scope narrowing —
  `CapabilityScopeUnsatisfiable` (a CAP narrows the capability
  context below the body's effect closure).
- **Predicted failure mode:** the scoped expression is "a file
  write", so the natural narrowed set is `[writeFx]`. But the path
  being written is derived from the clock (`now` → `nowFx`) INSIDE
  the scope, so the body's closure is `{writeFx, nowFx}`. The CAP
  cannot be inferred (no Elaborator auto-fill exists for CAP), so the
  partial grant fails statically. Validated wrong-variant error:
  `CapabilityScopeUnsatisfiable(at=#5 'scoped', missing=[#18 (prelude
  'nowFx')])`. A legitimate alternative fix — hoisting the timestamp
  computation outside the scope — also satisfies the verifier; either
  recovery counts as convergence.
- **Python-baseline failure shape:** runtime `PermissionError` from
  an allowlist shim, if the agent writes one — but the natural Python
  program has no capability machinery at all and succeeds first try.
  mypy --strict passes.
- **Design notes:** "narrows the capability context to exactly the
  effects the scoped expression exercises" forces enumeration without
  naming the answer; the clock dependency is buried in the
  path-derivation requirement. Uses the legacy stub
  `strand-builtin:Filesystem.Write` (returns 0, no real IO) so the
  cell is deterministic; the value never depends on the clock.
