# Effect Handlers (No-Continuation Form)

**Document:** `proposals/implemented/effect-handlers.md`
**Status:** Implemented (Layer 3 step 3 of the Kotlin/JVM reference implementation, 2026-05-23)
**Date:** 2026-05-23 (proposed); 2026-05-23 (implemented)
**Concerns:** [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md), [`decisions/ADR-004-effects-as-edges.md`](../../decisions/ADR-004-effects-as-edges.md), [Q-030](../../open-questions.md#Q-030)
**Scope:** Small–medium

> **Implementation note (2026-05-23).** The proposal was executed as-described with three refinements worth recording. (1) The closure rule in § 6.3 was extended from `(closureOf(body) - {intercept}) ∪ closureOf(handle)` to also include the handle function's *declared* effects: `... ∪ handleFun.effects`. Without this addition, a handler that itself performs an effect would have its effects silently absorbed because `closureOf(handle)` of a Lambda is empty (Lambdas only release their effects when called); adding the function's declared effects ensures the surrounding context still has to cover what the handler will exercise. (2) The verifier's per-Application signature-agreement walk descends through nested Handlers, but only into the nested Handler's body if its `intercept` differs from the outer Handler's; same-category nested Handlers shadow the outer (matching the runtime's innermost-wins rule), so calls within the inner's body are not checked against the outer's signature. (3) Scenario 6 ("interaction with Fixpoint") could not be written with the handler installed outside the Fixpoint as the proposal text suggested, because the recursive call has signature `(Int) -> Int` while the leaf effectful call has signature `() -> Int` — uniform-signature enforcement (§ 6.2) rejects this. The corpus program for scenario 6 instead installs the Handler *inside* the body Lambda, so a fresh handler is installed at each recursive level; this exercises handler threading across Fixpoint without violating the uniform-signature rule. The interpreter threads `List<ActiveHandler>` alongside the `CapabilitySet` context introduced by Q-031 (Layer 3 step 2); the public `eval` overloads all start with an empty handlers list. See `impl/CLAUDE.md` Layer 3 step 3 notes for the JSON schema and runtime API surface.

## 1. Problem statement

Strand's effect system today is **declarative and gating**: every effectful node declares the categories it may exercise; at every Application of an effectful function the runtime confirms the calling capability context covers those declarations. `CapabilityScope` (N-036) can *narrow* the runtime context — it removes capabilities from the body's evaluation context. What the system cannot do is **interpose** on an effect. There is no way to:

- Mock `Time.Now` with a fixed timestamp so the same graph evaluates deterministically in test and in production (today `Time.Now` is wired to a single hard-coded replay value inside `Builtins.kt`).
- Redirect `Logging.Info` to a buffered sink for capture-and-assert.
- Bound resource use ("after N calls, the operation returns a sentinel").
- Translate one effect category into another at an abstraction boundary.

`effects-and-capabilities.md` § Effect handlers commits Strand to a "restricted form" of algebraic effect handlers. ADR-004 § Consequences explicitly defers the semantics to that document. The spec text is short:

> A `Handler` node intercepts effects of a specified category within its body expression. The Handler's body executes; when the body performs an effect that matches the handler's category, control transfers to the handler's `handle` clause, which receives the effect's parameters and the body's continuation. The handler may resume the continuation with a value, abort the body, or transform the effect into a different effect (re-raise).

This is the Koka/Eff/Frank framing in classical form (continuations + handlers). The full algebraic-effects calculus is substantial machinery that interacts poorly with a tree-walking interpreter and that AI agents have no obvious need to generate. The test-mocking and resource-bounding workloads are served by a much smaller subset.

## 2. Prior art (brief)

- **Koka** (Leijen): full algebraic effect handlers with multi-shot continuations. Implemented by CPS transformation. Powerful; complex.
- **Eff** (Bauer & Pretnar): handlers parameterized by an effect signature, exposing `resume` as a first-class operation. Single-shot is the default idiom.
- **Frank** (Lindley, McBride & McLaughlin): handlers as functions whose argument list is an effect *invocation*; "shallow" semantics. Closer in spirit to what is proposed here.
- **OCaml 5**: one-shot continuations as the design choice for a production language; multi-shot was deliberately rejected as too costly given OCaml's runtime.

This proposal tracks Frank's "the handler is just a function" framing, restricted further to the **no-continuation** case.

## 3. Recommended scope for "step 3a" (whatever you want to call it)

**No-continuation handlers only.** A `Handler(category C, handle: (C-args...) → R function, body: R)` intercepts every Application within `body` whose callee declares effect `C`. When such an interception fires, the handler function is invoked with the *arguments to that call*, and the value it returns replaces the call's result. The body's evaluation continues from there.

Strictly more limited than algebraic effect handlers:

- A handler cannot resume the original call. No continuation to pass back to.
- A handler cannot abort the body partway and discard later work (no exception-like escape). The body always runs to completion, just with each effectful call rewritten.
- A handler cannot re-raise a different effect. It is just a function that returns a value — it can perform its *own* effects (which flow into its own capability context), but those are not the original effect transformed.

What this *does* cover, and why it is sufficient:

- Mocking `Time.Now` to return a fixed `Int`: handler is `() → 1700000000`.
- Redirecting `Logging.Info` to a sink: handler is `(msg: String) → { sink.append(msg); Unit }`.
- Bounded-call patterns via embedding state into the handler's closure environment: the handler reads a counter from a `ProductValue` passed in lexical scope and returns a sentinel when exhausted. True abort needs continuation machinery and is deferred.

The narrowness is the feature. Adding multi-shot continuations later is an upward extension; the wire format, canonical encoding, and existing semantics don't change.

## 4. Proposed node category

### 4.1 Identifier

**N-043 Handler.** N-041 and N-042 are now taken by the recursive-types work. N-043 is the next free identifier.

### 4.2 Edges and content fields

| Field | Multiplicity | Target | Role |
|-------|------|---------------|------|
| `intercept` | 1 | EffectCategory (N-021) | The effect category to intercept within the body |
| `handle` | 1 | Expression (typically Lambda or VarRef of function type) | The handler function; receives the intercepted call's value arguments |
| `body` | 1 | Expression | The body whose evaluation is observed for `intercept` effects |

No content fields beyond the edges; the node's identity is fully structural.

The handler's type, derivable by the verifier from `intercept` and the surrounding context, is:

```
handle : (P1, ..., Pn) -> R ![E_h]
```

where:

- `P1..Pn` are the *value-argument types* of any Application whose function declares the `intercept` effect. Step 3a is **uniform**: the handler must have the same argument types as every effectful function declaring `intercept` and called from `body`.
- `R` is the result type of those intercepted Applications.
- `E_h` is the handler's *own* declared effect set. The handler may itself be effectful (writing to a sink, for instance). Those effects flow into the surrounding capability context, *not* into the body's capability context.

### 4.3 Why "value-argument types" not "effect parameters"

The current implementation's effect-coverage check operates on EffectCategory identity, not on EffectDecl parameter instances. EffectDecl (N-022) exists in the schema but is unused for matching. The handler design takes the *function's value arguments* as the handler's parameters — what's observable to the interpreter without requiring the refinement-lattice work (a separate proposal) to land first. When refinement matching is implemented, handlers can be extended to also receive the EffectDecl parameters; that is a non-breaking superset.

## 5. Canonical encoding

```
Tag      : 43 (CategoryTag.Handler, 4-byte big-endian)
Fields   :
  hash(intercept)  -- 33-byte multi-hash of EffectCategory child
  hash(handle)     -- 33-byte multi-hash of the handler expression
  hash(body)       -- 33-byte multi-hash of the body expression
```

Each field is a canonical-CBOR byte string. Two handlers with identical `(intercept, handle, body)` triples produce byte-identical encodings and identical BLAKE3 digests.

The `handle` expression is itself a structural child, hashed under the surrounding binder context. It is *not* a binding site in the de Bruijn sense — the handler does not introduce new VarRef binders. This makes `Handler` a strictly simpler encoding than `Lambda` or `Let`.

## 6. Verifier well-formedness rules

The verifier extends `infer` with a `Node.Handler` case.

### 6.1 Edge validity

- `intercept` must be a non-dangling `EffectCategory`. Reuses `validateEffectCategoryEdges`.
- `handle` must type-check to a `TypeExpr.Fun` — that is, a function type. If polymorphic (a `Forall`), reject with a new error: handlers must be monomorphic at the Handler node, to keep matching against intercepted calls deterministic.
- `body` may be any expression.

### 6.2 Handler signature consistency

Let `bodyClosure = closureOf(body)` (already computed). Let `handlerType = TypeExpr.Fun(P, R, E_h)`. For every Application `app` reachable in `body` whose function type's `effects` set contains `intercept`:

- `app`'s value-argument types must structurally equal `P` (parameter-wise).
- `app`'s result type must structurally equal `R`.

If any such application disagrees, report `HandlerSignatureMismatch(at = handlerId, atCall = appId, expected, actual)`.

Edge case: the body might not actually call any effectful function declaring `intercept` (closureOf doesn't contain it). This is **not** an error — the Handler is a no-op in that case.

### 6.3 Effect closure adjustment

**The key novelty of Handler relative to every other node.** Every other node's closure is the union of its children's closures (effects from declared `effects` edges added at Lambda/ForeignNode and released at Application). Handler is the only node that *removes* an effect:

```
closureOf(handler) = (closureOf(body) - {intercept}) ∪ closureOf(handle)
```

The handler's own closure flows into the surrounding capability context — the handler runs there, not in some isolated context. If the handler writes to a file, the surrounding context needs `Filesystem.Write`. The body's `intercept` no longer escapes because it is fully consumed inside the Handler.

This is the property `effects-and-capabilities.md` describes as "the handler removes some of [the body's effects] from the body's effective closure." `CapabilityScope` does not have this property — it narrows the runtime context but its closure equals the body's closure.

New error variants:
- `HandlerSignatureMismatch(at, atCall, expected, actual)`
- `HandlerOverPolymorphicHandle(at, residual)` — `handle` expression has a `Forall` type rather than a `Fun` type
- `HandlerNotAFunction(at, gotType)` — `handle` expression has a non-function type

The existing `UncoveredEffects` does the right thing on the surrounding Lambda once `closureOf(handler)` is in place.

## 7. Interpreter runtime semantics

The current interpreter uses dynamic-scope-style context: `eval(id, env, context)` threads `context: Set<NodeId>` through every recursive call. Effect interception needs the *handler dispatch table* to be similarly threaded — a list of active handlers each carrying an `EffectCategory` and a target callable.

### 7.1 Runtime state

Extend the interpreter signature:

```kotlin
private fun eval(
    id: NodeId,
    env: Map<NodeId, Value>,
    context: Set<NodeId>,
    handlers: List<ActiveHandler>,    // innermost-last
): Value
```

`ActiveHandler` is a small record:

```kotlin
data class ActiveHandler(
    val intercept: NodeId,    // EffectCategory NodeId
    val handler: Value,       // a Closure or FixpointFn or ForeignFn — already evaluated
)
```

### 7.2 The Handler node case

```kotlin
is Node.Handler -> {
    val handlerValue = eval(node.handle, env, context, handlers)
    val newHandlers = handlers + ActiveHandler(node.intercept, handlerValue)
    eval(node.body, env, context, newHandlers)
}
```

The handler is evaluated **once**, at Handler-entry, in the surrounding handler table (so handlers stack: a handler may itself be handled). This matches the lexical-scope reading of the design.

### 7.3 Application interception

In `applyCall`, before dispatching to `applyClosure`/`applyForeign`/`applyFixpoint`, check the handler table:

```kotlin
private fun applyCall(id, app, env, context, handlers): Value {
    val fnValue = eval(app.function, env, context, handlers)
    val fnEffects = effectsOf(fnValue)       // declared effects on the callee
    val activeHandler = handlers.findLast { it.intercept in fnEffects }
    if (activeHandler != null) {
        // Intercept: evaluate the arguments, invoke the handler with them.
        // Do NOT checkCapabilities for the original effect — the handler
        // is replacing the effectful call.
        val args = app.arguments.map { eval(it, env, context, handlers) }
        return applyValue(id, activeHandler.handler, args, env, context, handlers)
    }
    // Normal dispatch.
    return when (fnValue) {
        is Value.Closure -> applyClosure(id, app, fnValue, env, context, handlers)
        is Value.ForeignFn -> applyForeign(id, app, fnValue, env, context, handlers)
        is Value.FixpointFn -> applyFixpoint(id, app, fnValue, env, context, handlers)
        else -> throw InterpretException(InterpretError.NotCallable(...))
    }
}
```

`effectsOf(fnValue)` reads the callee's declared effect list: `Lambda.effects`, `ForeignNode.effects`, or for `FixpointFn`, the body Lambda's effects.

`applyValue` is a small helper that dispatches to whichever apply-function fits the handler's value shape. Crucially, the handler is **evaluated under the surrounding context, not under a context that includes `intercept`**.

### 7.4 Innermost-first interception

The `findLast` is deliberate: the innermost (lexically nearest) Handler for a given EffectCategory wins. `Handler(Time.Now, h1, Handler(Time.Now, h2, body))` — within `body`, `h2` intercepts; `h1` is shadowed.

### 7.5 What the interpreter does *not* do

- No CPS transformation. No `Continuation` value. No `resume`.
- No stack manipulation. No coroutines. Pure tree-walk with a slightly richer context.
- No reentrancy guards: a handler calling another effectful operation that itself is intercepted just nests normally through `applyCall` again.

This is what the no-continuation restriction buys: the implementation footprint is tiny.

## 8. Test scenarios

1. **Mock Time.Now with a fixed timestamp.** Body uses `Time.Now()`; handler returns a constant `Int`. Verify: program runs without `Time.Now` capability; result equals the constant.
2. **Mock Time.Now where the handler itself reads a captured value.** Outer Let binds `t = 1700000000`; handler is `λ() → t`. Confirms the handler's closure environment is captured at Handler-entry.
3. **Two handlers for the same effect, nested.** Innermost wins. Verify: `Handler(Time.Now, h_outer, Handler(Time.Now, h_inner, Time.Now()))` calls `h_inner`.
4. **Handler that itself performs an effect.** `Logging.Info` handler that writes to a `MutableState` sink. The handler declares `Memory.MutableState`; the surrounding context must hold `Memory.MutableState` but does not need `Logging.Info`.
5. **Body that does *not* invoke the intercepted effect.** Handler is a no-op. Verify: program runs unchanged.
6. **Interaction with Fixpoint.** A recursive function that calls `Time.Now` at every step, wrapped in a Handler. Verify: every recursive invocation hits the handler. (Regression test for handler threading.)

Scenarios 1–5 are sufficient for first-cut acceptance. Scenario 6 is the regression test.

## 9. Tradeoffs and open questions

**Deferred intentionally:**

- **Multi-shot continuations.** Required for nondeterministic search, generator-style yielding, transactional rollback. Out of scope.
- **One-shot continuations** ("resume the original call with this value"). Useful for resource bounding. Adding requires a `resume: (R) → S` parameter on the handler signature, which changes the handler's type and canonical encoding. Worth a separate design pass.
- **Re-raise.** With no continuations, not directly expressible.
- **Abort-only handlers.** Useful for timeouts. Distinct primitive.
- **Per-call-site handler dispatch with different signatures.** Today's verifier rule forces all intercepted calls within a Handler to have the same signature. A future refinement could allow per-signature handler families.

**Real research questions:**

- *Q-NNN (proposed): Handler interaction with recursion through Fixpoint.* When a Handler wraps a Fixpoint application, the handler is in scope throughout every recursive call. Is this always what the agent intends? The proposal takes the inclusive reading; the alternative (handlers do *not* cross Fixpoint boundaries) is worth recording.
- *Q-NNN (proposed): Effect coverage for handlers that change effect identity.* If the handler itself performs effect `E_h ≠ intercept`, the surrounding context's coverage check sees `E_h`, not `intercept`. Correct, but agents need to predict this — the effect closure is non-monotonic in handler presence.

## 10. Implementation sketch

**Scope estimate: small to medium.** No CPS, no continuation machinery. The work fans out across the standard verify/interpret/encode/JSON-ingest seams, but each touch is local.

Files in `impl/` that change:

- `core/Node.kt`: add `data class Handler(intercept: NodeId, handle: NodeId, body: NodeId) : Node()`. Add to `categoryName`.
- `core/Json.kt`: add an ingest case for `"Handler"`.
- `verifier/Verifier.kt`: add `inferHandler(id, node, scope, typeParams)`. Validates the EffectCategory edge, infers body's type and closure, infers handler's type and confirms it is a monomorphic `Fun`, walks the body's subtree to confirm signature agreement. Records `closureOf(handler) = (closureOf(body) - {intercept}) ∪ closureOf(handle)`.
- `verifier/VerifyError.kt`: add `HandlerSignatureMismatch`, `HandlerOverPolymorphicHandle`, `HandlerNotAFunction`.
- `interpreter/Interpreter.kt`: thread `handlers: List<ActiveHandler>` through every `eval` and `apply*` function (mechanical, ~20 sites). Add the `is Node.Handler ->` case. Modify `applyCall` to consult the handler table. Add `effectsOf(fnValue)` helper.
- `interpreter/Value.kt`: no changes — handler values reuse Closure/ForeignFn/FixpointFn.
- `hashing/CategoryTag.kt`: add `val Handler = CategoryTag(43)`.
- `hashing/CanonicalEncoder.kt`: add the `Node.Handler` case to `encodeDispatch` and `Hasher.walk`.
- `corpus/...`: add 5 new test programs corresponding to scenarios 1, 2, 3, 4, 6.

Files in the design corpus that change when accepted:

- `INDEX.md`: register N-043 (Handler).
- `design/effects-and-capabilities.md` § Effect handlers: expand from the current prose paragraph into the algebra above (node shape, closure rule, runtime dispatch).
- `design/node-algebra.md` § Effects and capabilities: add an N-043 row to the inventory table.
- `open-questions.md`: update Q-030; possibly add follow-up Qs for the deferred features.

## References

**Outgoing references:**
- [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md) — § Effect handlers
- [`decisions/ADR-004-effects-as-edges.md`](../../decisions/ADR-004-effects-as-edges.md)
- [`design/node-algebra.md`](../../design/node-algebra.md) — N-043 entry in the inventory table
- [`open-questions.md`](../../open-questions.md) — Q-030

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-030 points at this proposal
- [`proposals/README.md`](../README.md)
- [`impl/CLAUDE.md`](../../impl/CLAUDE.md) — Layer 3 step 3 notes
