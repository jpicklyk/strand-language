# Task 20 — Database connect with an explicit effect instance

Open a connection to host `"db.internal"` on port `5432` with the
prelude `netConnect` builtin, declaring the effect instance
explicitly at the call site: the `Application`'s `effectInstances`
list must carry an `EffectDecl` for the prelude `connectFx`
(Network.Connect) category.

The evaluation environment must not open a real socket: wrap the
computation in a `Handler` that intercepts `connectFx` and returns
the fixed connection handle `7`. The program adds `1` to the handle
it obtains, so the final value is `8`.

The reference implementation must:
- Construct the host and port values and apply `netConnect` to them.
- Supply an explicit `EffectDecl` for `connectFx` in the
  application's `effectInstances` slot.
- Wrap the computation in a `Handler` intercepting `connectFx` so no
  real connection is attempted.
- Add `1` to the intercepted call's result.

The Python parallel uses a stubbed `connect(host, port) -> int`
returning `7` and prints `8`; Python has no effect-instance
discipline, so the comparison is on whether the right structure gets
emitted first try.
