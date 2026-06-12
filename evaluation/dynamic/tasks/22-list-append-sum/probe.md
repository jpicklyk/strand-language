# Probe metadata — task 22 (list-append-sum)

NOT part of the agent-facing prompt. The harness sends only
`description.md`; this file documents the probe design for operators.

- **Target verifier-error family:** recursive-type construction —
  `UnboundRecursiveSelf` and its construction-site siblings
  (`SumPayloadTypeMismatch`, `ProductFieldValueTypeMismatch`),
  exercised where it is hardest: building NEW recursive values
  (`SV`/`PV` Cons cells) INSIDE a Fixpoint body, not just consuming
  them via Match.
- **Predicted failure mode:** `append` must rebuild a Cons cell per
  recursion step, so the agent needs the outer PRD at a construction
  site inside the Match body while the inner PRD defines the RT.
  The natural single-product emission fails (validated:
  `UnboundRecursiveSelf(at=#3 'listSelf', hint=...inner/outer
  split...)`). Tasks 05/08 historically exhausted retries in this
  area before auto-Outer-PRD synthesis; construction-inside-FIX plus
  a second Fixpoint (`sum`) restores the difficulty the rescue
  removed. Secondary semantic outcomes: type divergence between the
  two Match case bodies, or recursing on the wrong binder.
- **Python-baseline failure shape:** the recursive dataclass append
  is idiomatic; the plausible Python failure is a wrong-value output
  (mis-ordered append) caught by the expected-output diff, or a
  RecursionError at runtime on a missed base case. mypy --strict
  passes either way.
- **Design notes:** two Fixpoints over the same recursive type also
  probe whether the agent re-uses one inner/outer PRD pair correctly
  across functions, the shape Run 5 flagged for compact-LAM binder
  name collisions (`xs` reused across LAMs).
