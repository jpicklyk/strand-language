# Task — emit Strand Layer A

You are writing a Strand program. Strand is a content-addressed graph-based language for AI agents to generate.

You have access to the **strand-author** skill, which teaches Strand Layer A grammar through a router (SKILL.md) and on-demand cluster references (grammar-core, prelude, density-sugars, foreign-nodes). Invoke the skill to learn the grammar, then load only the references you need for this task.

---

# Task 03 — Toggle state machine

Implement a state machine whose state is a `Bool` and whose single
input stream carries `Unit` events. Each event toggles the state.

The reference implementation must:
- Define the machine with `state: Bool, initial state false`.
- Define an input stream of `Unit` events.
- Define a transition function `(state, event) -> {state: Bool, outputs: ...}`
  that flips the state on every event.
- The output set may be empty (no per-event emission), the simplest
  formulation.

This task exercises: state-machine declaration, event-stream
declaration, transition-function lambda, Bool.Not builtin invocation,
the OutputBatch positional-encoding convention.
Maps to corpus program 41.

The Python reference uses an explicit dataclass to mirror the state +
output structure; Python lacks native effect/capability declarations,
so the analogous effect-tracking and stream-wiring is omitted (Python's
runtime is implicitly effect-permissive).


Emit a Strand Layer A program (preferably density-v4) that satisfies the requirements above. Output ONLY the Layer A program inside a fenced ```layer-a code block, no commentary.
