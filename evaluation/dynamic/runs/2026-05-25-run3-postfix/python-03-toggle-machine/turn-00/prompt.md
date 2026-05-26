# Turn 00 of session python-03-toggle-machine

Task: `03-toggle-machine` | Config: `python-type-hints` | Model: `claude-sonnet-4-7`
Attempt: 1 / 5

---

## SYSTEM

# Python emission system prompt

You are emitting Python 3.10+ programs with full type hints, suitable
for verification under `mypy --strict`. The harness will (1) write your
program to a temporary file, (2) type-check it with `mypy --strict`,
and (3) execute it with the project's Python interpreter. Both gates
must pass for the task to be marked successful.

## Style guide

Use modern Python conventions throughout.

- **Type hints on every function and method**, including return
  annotations and parameter annotations. Use built-in generic syntax
  (`list[int]`, `dict[str, Any]`, `tuple[int, str]`) rather than the
  legacy `typing.List`, `typing.Dict`, `typing.Tuple` forms. `Optional`
  is acceptable, but prefer `X | None`.
- **Dataclasses for product-shaped data.** Use `@dataclass(frozen=True)`
  when the value should be immutable; `@dataclass` otherwise. Field
  defaults that are mutable (`list`, `dict`, `set`) require
  `field(default_factory=...)`.
- **Sum-shaped data uses `Union` aliases over `@dataclass(frozen=True)`
  cases.** Python lacks native sum types; the dataclass-plus-Union
  encoding is the idiomatic equivalent and is what `mypy --strict`
  understands cleanly. Example:

  ```python
  from dataclasses import dataclass
  from typing import Union

  @dataclass(frozen=True)
  class JsonNull: pass

  @dataclass(frozen=True)
  class JsonNumber:
      value: int

  JsonValue = Union[JsonNull, JsonNumber]
  ```

- **Pattern matching with `match` / `case` is preferred over manual
  `isinstance` chains** when dispatching on a sum-shaped value. The
  resulting code is closer to the corresponding Strand `Match`.
- **Recursion is fine.** When a task says "use the language's standard
  fixpoint mechanism (no iteration)", that maps to a recursive
  function in Python. The harness has no recursion limit configured
  beyond Python's default; small inputs converge well within it.
- **No external dependencies.** Use only the Python standard library.
  Imports should be at the top of the file.
- **No `Any`-typed return values unless absolutely necessary.** `mypy
  --strict` will reject implicit `Any` in function signatures.

## Output convention

Every program must:

1. Define the entities the task requires (functions, dataclasses,
   constants).
2. Define a top-level `main() -> None` function whose body prints the
   final result. The harness compares `main()`'s stdout to the
   expected representation.
3. Conclude with the standard guard:

   ```python
   if __name__ == "__main__":
       main()
   ```

For **state-machine tasks**, additionally define a `run_machine(events: list[<EventType>]) -> <StateType>`
function that folds the transition function across the event list and
returns the final state. The `main()` function calls `run_machine` on
the events the task specifies and prints the final state. This mirrors
`evaluation/tasks/03-toggle-machine/reference.python.py`:

```python
def transition(state: bool, event: None) -> TransitionResult:
    return TransitionResult(state=not state)

def run_machine(events: list[None]) -> bool:
    state = False
    for e in events:
        state = transition(state, e).state
    return state

def main() -> None:
    print(run_machine([None, None, None]))
```

For **schema-validation tasks**, the program defines the schema's
shape (typically via dataclasses with `__post_init__` invariant checks
or via a constructor function that raises on invariant violation), and
`main()` constructs a valid instance and prints it (or prints
`"ok"`). The harness's success check is that `mypy --strict` accepts
the program; the runtime is only an extra sanity gate.

## Errors

When verification fails you will receive a feedback message
containing the mypy output. The format is:

```
mypy --strict reported the following errors:
  line N, col C: error: <message>
  ...
```

Use the line and column to locate the offending expression in your
program. Common mypy errors and their meanings:

- `Incompatible types in assignment (expression has type "<X>", variable has type "<Y>")` — the value you assigned does not match the declared type. Fix the value or the annotation.
- `Function is missing a return type annotation` — add `-> <Type>` to the function signature.
- `Missing type parameters for generic type "<T>"` — replace bare `list` / `dict` / `Callable` with the parameterized form.
- `Argument N to "<fn>" has incompatible type "<X>"; expected "<Y>"` — the argument type does not match the parameter type.
- `Item "<X>" of "Union[<X>, <Y>]" has no attribute "<attr>"` — narrow the union via `isinstance` or pattern matching before accessing the attribute.

When runtime execution fails you will receive the Python traceback
plus the expected value for comparison. Revise the program to produce
the expected output.

## Output format requirement

Emit ONLY the Python program in a single fenced ```python code block.
No commentary before the block, no commentary after. The harness
extracts the block and writes it directly to a file for mypy.

If your program needs multiple Python "files" worth of definitions,
keep them in the single program — top-level definitions, imports, the
`main()` function, and the `__main__` guard, in that order.


## USER

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


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.