# Task — emit Strand Layer A

You are writing a Strand program. Strand is a content-addressed graph-based language for AI agents to generate.

You have access to the **strand-author** skill, which teaches Strand Layer A grammar through a router (SKILL.md) and on-demand cluster references (grammar-core, prelude, density-sugars, foreign-nodes). Invoke the skill to learn the grammar, then load only the references you need for this task.

---

# Task 09 — File write under capability

Compose the effectful builtin `Filesystem.Write` with the pure
builtin `Int.Add`: write to a file path (the reference
implementation's Filesystem.Write is a no-op stub that returns 0
bytes-written), then add 1 to the result.

The reference implementation must:
- Declare an `EffectCategory` named `Filesystem.Write` (a refinement-
  bearing effect with a `String` path parameter).
- Declare the foreign builtin `strand-builtin:Filesystem.Write` of
  type `(String) -> Int` that declares the `Filesystem.Write` effect.
- Call the builtin with the path `"/tmp/strand-eval.log"`, then add 1
  to the result.

This task exercises: `EffectCategory` declaration with refinement
parameter, `ForeignNode` carrying declared effects, `Application`
through an effectful function, effect-closure propagation through
composed pure + effectful Applications. The reference Filesystem.Write
returns 0 bytes, so the final value is `0 + 1 = 1`.

The Python reference uses a regular function call (Python has no
effect system; the "capability" is implicit) and composes the result
with arithmetic. Mapped from corpus program 17 (the same
pure-+-effectful composition pattern, with Filesystem.Write
substituted for the original Time.Now).


Emit a Strand Layer A program (preferably density-v4) that satisfies the requirements above. Output ONLY the Layer A program inside a fenced ```layer-a code block, no commentary.
