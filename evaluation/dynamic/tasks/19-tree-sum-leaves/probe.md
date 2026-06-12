# Probe metadata — task 19 (tree-sum-leaves)

NOT part of the agent-facing prompt. The harness sends only
`description.md`; this file documents the probe design for operators.

- **Target verifier-error family:** recursive-type construction —
  `UnboundRecursiveSelf` (a RecursiveSelf-bearing ProductType used at
  a top-level construction or pattern site outside an RT walk).
- **Predicted failure mode:** the agent declares ONE product
  `{left: <self>, right: <self>}` and uses it everywhere — at the
  SumTypeCase inside the RT body AND at PVR pattern types and
  SumValue/ProductValue construction sites. With a single-tail list
  (task 15) the Elaborator's auto-Outer-PRD synthesis rescued that
  emission; with TWO RecursiveSelf fields the rescue does not apply
  and the natural one-product emission fails. Validated wrong-variant
  error: `UnboundRecursiveSelf(at=#3 'treeSelf', hint=...inner/outer
  split, see corpus program 31...)` — the long structured hint is the
  rescue path under measurement.
- **Python-baseline failure shape:** none specific — the dataclass
  tree and recursive match/case work first try for a competent agent;
  the plausible Python failure is an output mismatch or a missed
  base case surfacing as RecursionError at runtime.
- **Design lesson applied from task 15:** task 15 named the probe
  machinery in the description ("the inner-PRD-with-RS form...") and
  was auto-rescued anyway; this description states only the type
  shape and arithmetic, and uses a two-self-field product the
  Elaborator cannot synthesize around.
