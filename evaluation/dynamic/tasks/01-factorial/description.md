# Task 01 — Factorial

Implement the factorial function for non-negative integers.

```
factorial(0) = 1
factorial(n) = n * factorial(n - 1)    (n > 0)
```

The reference implementation must:
- Accept a non-negative integer argument.
- Return its factorial.
- Recurse via the language's standard fixpoint mechanism (no iteration).
- Match on `n == 0` for the base case.

This task exercises: function definition, recursion / fixpoint, integer
literals, Match dispatch, conditional dispatch on a primitive value.
Maps to corpus program 21.
