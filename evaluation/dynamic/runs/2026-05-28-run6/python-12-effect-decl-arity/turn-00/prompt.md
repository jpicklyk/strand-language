# Turn 00 of session python-12-effect-decl-arity

Task: `12-effect-decl-arity` | Config: `python-type-hints` | Model: `claude-sonnet-4-7`
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

# Task 12 — Multi-parameter effect: network connect with host + port

Open a network connection to host `"localhost"` on port `8080` using
`strand-builtin:Net.Connect`. Wrap the call in a `Handler` that
intercepts `Network.Connect` and returns a fake socket handle of
`42`. Add 1 to the handle. Final value is `43`.

The reference implementation must:
- Declare an `EffectCategory` named `Network.Connect` parameterized
  by two refinement parameters in order: `(host: String, port: Int)`.
- Declare the foreign builtin `strand-builtin:Net.Connect` of type
  `(String, Int) -> Int` that declares the `Network.Connect` effect.
- Construct the host literal (`"localhost"`) and port literal (`8080`).
- Supply an `EffectDecl` whose parameters list contains the two
  literals in EffectCategory-declaration order: `[host, port]`.
- Apply the builtin with `[host, port]` arguments and the
  EffectDecl, then add 1.
- Wrap the whole `add` expression in a `Handler` that intercepts
  `Network.Connect`. The `handle` is a lambda
  `(host: String, port: Int) -> Int` returning `42` so the program
  produces 43 without making a real network call.

This task exercises: multi-parameter `EffectCategory` declaration,
`EffectDecl` parameter-order discipline (positional binding to the
category's parameter list), `Application.effectInstances` wiring
when an EffectDecl is supplied at the call site, and a Handler whose
`handle` signature matches a multi-parameter intercepted function.
The verifier rejects an EffectDecl whose parameter count or
parameter types do not match the EffectCategory.

The Python parallel uses a stubbed `connect(host, port) -> int` that
returns 42; Python has no effect-decl discipline so the comparison
is on whether the right shape gets emitted first try.


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.