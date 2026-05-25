# Task 06 — Counter state machine

Implement a state machine whose state is an `Int` counter and whose
single input stream carries a sum-typed event with three cases:
`Increment`, `Decrement`, `Reset`. Each event updates the counter:
`Increment` adds 1, `Decrement` subtracts 1, `Reset` returns the
counter to 0.

The reference implementation must:
- Define the machine with `state: Int, initial state 0`.
- Define an event type as a sum `Increment | Decrement | Reset` with
  no payloads.
- Define a transition function `(state, event) -> {state: Int, outputs: ...}`
  that dispatches on the event case.
- The output set may be empty (no per-event emission).

Apply the machine to the sequence `[Increment, Increment, Decrement,
Reset, Increment]`. The expected final state is `1`.

This task exercises: state-machine declaration, sum-typed events,
event-stream declaration, transition-function lambda with Match on
the event, Int arithmetic builtins, OutputBatch positional-encoding
convention. Maps to corpus program 42 (synchronous version).

The Python reference uses a sum-shaped `Union` over three frozen
dataclasses for the event type and dispatches with `match`/`case`.
