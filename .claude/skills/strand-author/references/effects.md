# Effects, capabilities, and handlers

Strand's effect system tracks which Filesystem / Network / Time / etc. operations a program performs. Every function whose body exercises an effect must surface that effect in its declared `effects` list — this is the closure rule.

## The codes

- `EFC categoryName:String [parameters:[refs]]` — EffectCategory. Defines a category like `"Filesystem.Write"` with optional refinement parameter types. `writeFx EFC "Filesystem.Write" [stringT]`
- `EFD effectType:ref parameters:[refs]` — EffectDecl. Instantiates an EffectCategory with concrete parameter values at a call site. `writeDecl EFD writeFx [path]`
- `CAP capabilities:[refs] body:ref` — CapabilityScope. Wraps a body with granted capabilities; the body and its sub-expressions can use those capabilities. `scope CAP [writeDecl] inner`
- `H intercept:ref handle:ref body:ref` — Handler. Replaces every Application in `body` whose called function declares `intercept` with a call to `handle` instead. Innermost-wins for nested same-category handlers.

## The closure rule

A Lambda's `effects` list must be a superset of its body's effects. If the body calls an effectful builtin, the Lambda must declare that effect:

```layer-a
helperBody APP add [(APP fsWrite [path bytes]) 1]      -- body uses writeFx
helper LAM [] helperBody [writeFx]                     -- LAM declares writeFx
```

If the LAM is missing the effect, the verifier returns:
```
UncoveredEffects(at=#helper, missing={#writeFx})
```

This is the most common authoring slip when wrapping an effectful call in a Lambda. Always declare the effects on the LAM that surrounds an effectful body.

## EffectDecl parameter discipline

When an `EffectCategory` is parameterized (e.g., `Network.Connect{host: String, port: Int}`), every `EffectDecl` must supply parameters in declaration order:

```layer-a
connectFx EFC "Network.Connect" [stringT intT]
connectDecl EFD connectFx [host port]                  -- positional: host first, port second
```

Verifier errors on mismatch:
- `EffectDeclArityMismatch` — wrong number of parameters
- `EffectDeclParameterTypeMismatch` — right count, wrong types

## When to use the prelude vs explicit declarations

The implicit prelude has reserved names like `fsWrite`, `netConnect`, `now` that carry effects and Q-039 projections automatically. BUT the prelude's parameterless `writeFx` / `readFx` / etc. (and their projections) only work in specific patterns — typically when you call the builtin directly at the top level with the prelude effect category.

**Recommended pattern for effectful builtins inside Lambdas or when an explicit EffectDecl is needed: declare locally.**

For a Lambda body that calls Filesystem.Write:

```layer-a
-- Declare locally (mirrors corpus 17 / task 09):
writeFx EFC "Filesystem.Write" [stringT]
writeT FNT [stringT] intT [writeFx]
write FN "strand-builtin:Filesystem.Write" writeT [writeFx]

-- Use it:
path STR "/tmp/example.log"
writeDecl EFD writeFx [path]
callWrite APP write [path] [] [writeDecl]
```

The bytes argument: the real builtin signature is `(String, Bytes) -> Int`. Above is a stub-friendly form using only the path; if your task needs the real signature, declare the FNT with both parameters and pass both arguments. The eval framework's `Filesystem.Write` stub returns 0 regardless.

For Network.Connect with the full 2-parameter category:

```layer-a
connectFx EFC "Network.Connect" [stringT intT]
connectT FNT [stringT intT] intT [connectFx]
connect FN "strand-builtin:Net.Connect" connectT [connectFx]
host STR "localhost"
port ILT 8080
connectDecl EFD connectFx [host port]
callConnect APP connect [host port] [] [connectDecl]
```

## Common effect-category names (declare locally if not in the prelude)

- `"Filesystem.Read"` — Fs.Read, Fs.Exists. Refinement: `[stringT]` (path).
- `"Filesystem.Write"` — Fs.Write, Fs.Append, Fs.Delete. Refinement: `[stringT]`.
- `"Network.Connect"` — Net.Connect, Http.Request. Refinement: `[stringT intT]` (host, port).
- `"Network.Send"` — Net.Send, Http.Request. No refinement.
- `"Network.Receive"` — Net.Receive, Http.Request. No refinement.
- `"Time.Now"` — now. No refinement.
- `"Time.Sleep"` — sleep. No refinement.
- `"StateMachine.Receive"` — required for any state machine with inputs. No refinement.
- `"StateMachine.Send"` — required for any state machine with outputs. No refinement.

## Handlers (N-043)

A Handler intercepts every Application inside its body whose called function declares the intercepted effect category. The `handle` is evaluated once at Handler-entry and must produce a function value whose signature matches the intercepted function.

```layer-a
-- Body that calls Time.Now (effectful):
callNow APP now []
body APP add [callNow 1]

-- Handler intercepts Time.Now and replaces it with a fixed value:
handleLam LAM [] 42
program H nowFx handleLam body
```

Critical: the `intercept` argument is the **EffectCategory** (`nowFx`), NOT the ForeignNode (`now`). The verifier returns `NonEffectCategoryInEffectList` if you pass a ForeignNode where an EffectCategory belongs.

The handler's `handle` signature must match the intercepted call:
- `Time.Now` is `() -> Int`, so `handle` is `LAM [] <Int-producing-expr>`.
- `Net.Connect` is `(String, Int) -> Int`, so `handle` is `LAM [h:stringT p:intT] <Int-producing-expr>`.

Mismatch returns `HandlerSignatureMismatch(at=#handler, expected=..., actual=...)`.

Innermost handler wins for nested same-category handlers:

```layer-a
innerLam LAM [] 2
inner H nowFx innerLam callNow      -- innermost; intercepts callNow
outerLam LAM [] 1
outer H nowFx outerLam inner        -- outer; covers inner's residual closure
```

## CapabilityScope (rare in practice)

`CAP [capabilities] body` declares capabilities for the body. Real programs usually rely on the runtime-granted capability context (`--grant-all` in the eval framework, an explicit `CapabilitySet` in production). Use CAP only when you need to narrow capabilities for a specific subtree.

## Effect closure verification — common errors and fixes

- `UncoveredEffects(at=#lam, missing={...})` → add the named EffectCategory ids to the LAM's effects list: `LAM [params] body [fx1 fx2]`.
- `EffectInstanceCoverageMismatch` → an Application's `effectInstances` list doesn't match the callee's declared effects 1:1. Supply one EFD per declared effect category, in matching order.
- `EffectDeclArityMismatch` / `EffectDeclParameterTypeMismatch` → fix the EFD's parameter list to match the EffectCategory's declared parameter types.
- `NonEffectCategoryInEffectList` → the `intercept` arg to a Handler (or an entry in an `effects` list) must be an EffectCategory NodeId. Don't pass a ForeignNode there.
- `HandlerSignatureMismatch` → the handle Lambda's type doesn't match the intercepted function. Match parameter types and the return type exactly.
