# Reference: effects — foreign effect projections (Q-039) and the FN projection DSL

When a `ForeignNode` declares parameterized effect categories (e.g.,
`Filesystem.Write{path}`, `Network.Connect{host, port}`,
`LLM.Generate{provider, model}`), the security model needs the
capability-check parameter values to be the same values the foreign
code actually consumes. Strand expresses this binding via an optional
`effectProjections` field on `ForeignNode` (and symmetrically on
`FunctionType`).

Each entry in `effectProjections` covers one of the function's
declared effect categories positionally — entry `i` projects
`effects[i]`. The projection lists one `ProjectionSource` per
EffectCategory parameter; the runtime synthesizes the capability-
check value from that source plus the actual evaluated argument
values.

Two source variants in V1:

- `{"kind": "ArgRef", "index": N}` — the parameter value is the
  function's positional argument at index `N`. The interpreter passes
  `argumentValues[N]` straight to the capability check, so the
  capability-check value IS the value the foreign code receives. No
  drift possible. Used for `path` on `Fs.Write` (`ArgRef(0)`), for
  `(host, port)` on `Net.Connect` (`[ArgRef(0), ArgRef(1)]`), and so
  on.
- `{"kind": "LiteralNode", "target": "<author-id>"}` — the parameter
  value is the binding-controlled literal node at `target`. Used to
  pin a `provider` slot on per-provider LLM/Vector bindings (e.g.,
  `LiteralNode("anthropicLit")` where `anthropicLit` is a
  `StringLit("anthropic")`). The agent cannot spoof a different
  provider via an authored EffectDecl — the verifier rejects any
  EffectDecl whose corresponding parameter does not canonical-hash-
  equal the pinned literal.

Canonical dag-json shape for a projected `Fs.Write`:

```
{
  "type": "ForeignNode",
  "target": "strand-builtin:Fs.Write",
  "foreignType": "writeT",
  "effects": ["writeFx"],
  "effectProjections": [
    {
      "category": "writeFx",
      "sources": [{"kind": "ArgRef", "index": 0}]
    }
  ]
}
```

## The FN projection DSL (Layer A surface, density v5)

The FN code's optional fourth argument is a quoted string of
semicolon-separated per-category entries:

    "<categoryRef>:<source>,<source>;<categoryRef>:..."

where each source is either a non-negative integer (an `ArgRef(index)`
into the foreign function's positional arguments) or `@<authorId>` (a
`LiteralNode` reference to a literal node declared in the document). A
category whose EffectCategory declares no parameters takes an empty
source list — `<categoryRef>:` with nothing after the colon.

Example — the seven-arg `Http.Request` pinning `Network.Connect`'s
`{host, port}` to arguments 0 and 1:

    reqFn FN "strand-builtin:Http.Request" reqT [connectFx netSendFx netRecvFx] "connectFx:0,1;netSendFx:;netRecvFx:"

When a ForeignNode carries any projection it must carry one entry per
declared effect, in the same order as the `effects` list; the verifier
enforces this at admission. Before density v5 the field was reachable
only from canonical dag-json; the DSL string makes hand-authored Layer
A ForeignNodes carry projections directly. ForeignNodes synthesized by
the v5 bare-dotted-name expansion carry their table projections
automatically.

## Call-site semantics

`Application.effectInstances` is optional at every call of a
projected ForeignNode. When omitted (or replaced by `@auto`, which
synthesizes the explicit list), the interpreter synthesizes
capability-check values from the projection plus the evaluated
arguments. When supplied, the verifier requires the authored
EffectDecl to match the projection structurally:

- `ArgRef(j)` source → EffectDecl parameter at the same position
  must be the exact same NodeId as `Application.arguments[j]`. A
  drift attempt — fresh literal with the same value but a different
  NodeId — raises `ProjectionMismatch`. This is the load-bearing
  Q-039 verifier rule.
- `LiteralNode(t)` source → EffectDecl parameter must be a literal
  node whose canonical-form bytes equal `t`'s canonical-form bytes.

Reserved prelude entries (`fsRead`, `fsWrite`, `fsAppend`, `fsExists`,
`fsDelete`, `netConnect`) carry their projections automatically.
Programs that use these names by reserved id inherit the security
property — the agent does not need to author `effectProjections`
manually.

## Verifier rules

At admission of a ForeignNode with projections:

- `ProjectionArityMismatch` — projection list length does not equal
  `effects.size`.
- `ProjectionCategoryMismatch` — the projection at position `i`
  declares a different `category` from `effects[i]`.
- `ProjectionSourceArityMismatch` — the projection's `sources` list
  length does not equal the EffectCategory's parameter count.
- `ProjectionArgRefOutOfRange` — an `ArgRef(i)` references an index
  outside the function's parameter range.
- `ProjectionLiteralNotConstant` — a `LiteralNode` target does not
  resolve to a literal node (IntLit/FloatLit/StringLit/BoolLit/
  UnitLit/BytesLit/ProductValue/SumValue over literals).
- `ProjectionLiteralTypeMismatch` — a `LiteralNode` target's type
  does not structurally equal the EffectCategory parameter type at
  the same position.

At every Application of a projected ForeignNode with non-empty
`effectInstances`:

- `ProjectionMismatch` — an EffectDecl parameter does not match the
  projection's source at the same position.

## Migration status

Migrated bindings (initial Q-039 slice): `Fs.Read`, `Fs.Write`,
`Fs.Append`, `Fs.Exists`, `Fs.Delete`, `Net.Connect`; the density-v5
signature table carries projections for its table-covered builtins. The
per-provider LLM and Vector bindings, `Process.Spawn`, and
`Crypto.Sign/Encrypt/Decrypt` are deferred to follow-up slices — their
signatures need redesign or EffectCategory parameter changes that
exceed the scope of the initial security-restoration slice.
ForeignNodes without `effectProjections` continue under legacy Q-031
semantics; the security gap on those bindings persists until migration
completes.
