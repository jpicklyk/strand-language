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
