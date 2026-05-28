# Task — emit Strand Layer A

You are writing a Strand program. Strand is a content-addressed graph-based language for AI agents to generate.

You have access to the **strand-author** skill, which teaches Strand Layer A grammar through a router (SKILL.md) and on-demand cluster references (grammar-core, prelude, density-sugars, foreign-nodes). Invoke the skill to learn the grammar, then load only the references you need for this task.

---

# Task 11 — Helper function calls an effectful builtin

Define a helper function `appendLog: () -> Int` that calls
`Filesystem.Write` internally (writing to a fixed log path) and adds
1 to the bytes-written result. The main program applies the helper
and adds 10. `Filesystem.Write` is stubbed to return 0 in the eval
environment, so the final value is `((0 + 1) + 10) = 11`.

The reference implementation must:
- Declare an `EffectCategory` named `Filesystem.Write` parameterized
  by a `String` path.
- Declare the foreign builtin `strand-builtin:Filesystem.Write` of
  type `(String) -> Int` that declares the `Filesystem.Write` effect.
- Define a `Lambda` `appendLog` of type `() -> Int` whose body calls
  the write builtin with a fixed path and then adds 1.
- The lambda body uses an effectful call, so the lambda itself must
  surface the `Filesystem.Write` effect on its declaration. A Lambda
  whose body exercises an effect category must list that category in
  the Lambda's own `effects` slot — otherwise the verifier rejects
  with `UncoveredEffects`.
- The main program applies `appendLog` and adds 10 to the result.

This task exercises: a Lambda surfacing an effect from its body, the
effect-closure rule (`closureOf(LAM) ⊇ closureOf(body)` so a body
with effect X requires X in the LAM's declared effects), and
composition of an effectful function call with pure arithmetic. The
program runs under `--grant-all` so the top-level capability is
granted; the verifier-time check on the Lambda is the gate this task
probes. Python has no effect system; the parallel uses a helper
function and a `write_log()` stub returning 0.


Emit a Strand Layer A program (preferably density-v4) that satisfies the requirements above. Output ONLY the Layer A program inside a fenced ```layer-a code block, no commentary.
