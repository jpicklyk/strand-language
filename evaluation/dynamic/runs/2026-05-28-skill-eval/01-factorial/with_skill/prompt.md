# Task — emit Strand Layer A

You are writing a Strand program. Strand is a content-addressed graph-based language for AI agents to generate.

You have access to the **strand-author** skill, which teaches Strand Layer A grammar through a router (SKILL.md) and on-demand cluster references (grammar-core, prelude, density-sugars, foreign-nodes). Invoke the skill to learn the grammar, then load only the references you need for this task.

---

# Task 01 — Factorial

Implement the factorial function for non-negative integers and apply
it to `5` so the program produces `120`.

```
factorial(0) = 1
factorial(n) = n * factorial(n - 1)    (n > 0)
```

The reference implementation must:
- Accept a non-negative integer argument.
- Return its factorial.
- Recurse via the language's standard fixpoint mechanism (no iteration).
- Match on `n == 0` for the base case.
- The program's final value is `factorial(5) = 120`. For Python, `main()`
  should `print(factorial(5))`. For Strand, the root Application is
  `APP fact [5]`.

This task exercises: function definition, recursion / fixpoint, integer
literals, Match dispatch, conditional dispatch on a primitive value.
Maps to corpus program 21.


Emit a Strand Layer A program (preferably density-v4) that satisfies the requirements above. Output ONLY the Layer A program inside a fenced ```layer-a code block, no commentary.
