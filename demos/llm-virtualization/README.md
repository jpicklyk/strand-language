# Handler-based LLM virtualization demonstration {#llm-virtualization-demo}

**Document:** `demos/llm-virtualization/README.md`
**Status:** Executable demonstration of effect virtualization via the N-043 Handler
**Last revised:** 2026-06-13

## What this demonstration is

The N-043 `Handler` is the one shipped language primitive no other demonstration
exercises, and it does something no conventional agent stack can: it intercepts
the effectful model call and replaces it, so the agent's LLM dependency
*disappears* from its effect closure. This is the closure-subtraction rule
`closureOf(handler) = (closureOf(body) − {intercept}) ∪ closureOf(handle) ∪
handleFun.effects` — and `Handler` is the only node category that *removes* an
effect from a closure. This demonstration puts that on screen: effect
virtualization as a verified language feature, the mechanism behind deterministic
agent testing, prompt-rewriting policy layers, response caching, and budget
enforcement.

The subject is one agent body that calls a model under an `LLM.Generate`
EffectCategory, run two ways. Unhandled, its surfaced effect closure (Q-067
`rootClosure`) is `{LLM.Generate}` and the host must grant that category for the
program to run. Handled — the *same* model call wrapped in a `Handler` whose
`handle` is a pure function — the surfaced closure is *empty*, the verifier having
subtracted `LLM.Generate`, so the program runs under `CapabilitySet.EMPTY` and
still produces a result with the model transport never invoked.

The host is an ordinary JVM caller of the shipped embedding surface — the Q-054
`StrandRuntime` facade with its `HostPolicy`, the Q-067 surfaced effect closure,
and the N-043 `Handler` semantics the verifier and interpreter implement. It
introduces no language feature, no node category, no encoding change, and no
verifier rule. Every property the demonstration claims is one the runtime
enforces, and the assertion net (`LlmVirtualizationDemoTest`) protects each one
from silently rotting.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:llmVirtualizationDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.LlmVirtualizationDemoTest"
```

The driver `LlmVirtualizationDemo` and the test `LlmVirtualizationDemoTest` live
in the `:runtime` test source set
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and share one body of
scenario code, so the printed demonstration and the regression net cannot diverge.
They stay in `:runtime` because they compile against the runtime modules. The
driver loads the committed canonical dag-json from [`programs/`](programs/) through
the test classpath (`runtime/build.gradle.kts` copies the directory in via
`processTestResources`), so the artifact the host runs is the content-addressed
graph, not the human-facing projection.

## The model call is a stand-in

The brief permitted two ways to model the intercepted call: the real per-provider
LLM ForeignNode (`Anthropic.Messages.Create`, declaring E-035 `LLM.Generate`,
returning a `GenerateResult` product), or a monomorphic effectful ForeignNode
standing in for the model call. This demonstration uses the **stand-in**:
`strand-builtin:Demo.LlmGenerate`, typed `(String) -> String` under an
EffectCategory named `LLM.Generate`, installed as a host test builtin via
`Builtins.installTestBuiltin`. The pure handle is then the trivially-constructible
`(String) -> String` returning a canned String.

The reason is honest and practical: hand-constructing a real `GenerateResult`
value (a four-field product whose `content` is a recursive list of content blocks)
as a pure handle is impractical and brittle, and would obscure the one point this
demonstration makes. The intercept, the closure-subtraction rule, and the
empty-grant run are all *genuine* with the stand-in — the verifier subtracts an
`LLM.Generate` category whether it backs a real provider call or a monomorphic
one, and the narrative stays "virtualize the model call." Nothing is staged: the
empty surfaced closure is the verifier's own computation, and the empty-grant run
is the real runtime.

## The scenarios

### V1 Unhandled baseline

The agent program `agent-unhandled` calls the model ForeignNode directly on a
prompt string. The host verifies the program and reads its *surfaced effect
closure* off the `VerifyResult.Ok` (Q-067 `rootClosure`): `{LLM.Generate}`. That
set is the machine-checked statement of everything the agent can reach, available
from the artifact before any execution. The host grants a `CapabilitySet` of
exactly that category and runs the program; the model transport is invoked once
and returns its deterministic completion.

The point is the un-virtualized bound: the LLM dependency is present in the
closure and must be granted to run. The agent's harm bound includes the model
call.

### V2 Handled (keystone)

The variant `agent-handled` is the *same* model call — the same ForeignNode, the
same prompt — wrapped in a `Handler` that intercepts the `LLM.Generate`
EffectCategory. The handler's `handle` is a **pure** Lambda `(String) -> String`
returning a canned completion; it matches the intercepted call's value-argument
and result types, so the verifier's signature check (`HandlerSignatureMismatch`)
admits it.

The host reads the verifier's own surfaced closure off the `Ok` result and it is
now **empty**: the closure-subtraction rule removed `LLM.Generate` because the
Handler intercepts it. The transcript renders the two closures side by side — the
unhandled `{LLM.Generate}` against the handled `[]` — and the only difference
between the programs is the `Handler`.

The host then runs the handled program under `CapabilitySet.EMPTY`, granting *no*
`LLM.Generate` at all. A program whose closure still contained `LLM.Generate`
could not run under this grant. This one does: it produces a result,
deterministically, from the pure handle, and the model transport is **never
invoked** — the installed stand-in builtin's call count stays at zero. The
closure subtraction is the real verifier's computation; the empty-grant run is the
real runtime. Virtualization removed the effect from the harm bound.

### V3 Framing

The pure handle is a deterministic test double / policy substitution. This is the
use the keystone enables: a verified program whose model dependency has been
replaced by a pure function runs with that effect gone from its closure entirely —
the mechanism behind deterministic agent testing, prompt-rewriting policy layers,
response caching, and budget enforcement.

The shipped semantics are *no-continuation intercept-and-replace*: the handler
replaces the model call wholesale, and there is no `resume`. This demonstration is
therefore the substitution case, not resumable algebraic effects — a distinction
stated plainly so nothing is overclaimed.

## Transcript

The transcript below is the output of `./gradlew :runtime:llmVirtualizationDemo -q`.

```
========================================================================
Strand -- Handler-based LLM virtualization. The Handler (N-043)
intercepts the model call and replaces it, so the agent's LLM
dependency disappears from its effect closure (closure-subtraction).
The 'model' is a monomorphic stand-in builtin under LLM.Generate.
========================================================================

V1  Unhandled baseline -- the un-virtualized bound
------------------------------------------------------------------------
  Program: agent-unhandled (calls the model ForeignNode directly).
  The bound is read off the verified artifact BEFORE running:
    surfaced closure (Q-067)  = [LLM.Generate]
    granted categories        = [LLM.Generate]
    grant covers exactly it   = true
  Host runs under that grant; the model transport is invoked:
    verified clean            = true
    model calls               = 1
    completed                 = true
    result                    = "The meeting is scheduled for 3pm today."
  The LLM.Generate dependency is in the closure and must be granted
  to run -- the agent's harm bound includes the model call.

V2  Handled (keystone) -- virtualization removes the effect from the bound
------------------------------------------------------------------------
  Program: agent-handled (the SAME model call, wrapped in a Handler
           intercepting LLM.Generate with a PURE handle).
  The verifier's own surfaced closure, the two side by side:
    unhandled closure         = [LLM.Generate]
    handled closure (Q-067)   = []
    LLM.Generate subtracted   = true
  Host runs under CapabilitySet.EMPTY -- NO LLM.Generate granted:
    verified clean            = true
    granted categories        = []
    model transport invoked   = 0 time(s)
    completed                 = true
    result (from the handle)  = "Meeting is at 3pm."
  The wow: the program still produces a result, deterministically,
  under no LLM.Generate grant, with the transport never touched.
  Virtualization removed the effect from the harm bound.

V3  Framing -- the pure handle is a deterministic test double / policy
------------------------------------------------------------------------
  The handle is a pure (String) -> String returning a canned result.
  This is effect virtualization as a verified language feature: the
  mechanism behind deterministic agent testing, prompt-rewriting
  policy layers, response caching, and budget enforcement. The
  shipped semantics are no-continuation intercept-and-replace -- the
  handler replaces the model call wholesale; there is no resume.

========================================================================
What this demonstrates: the Handler subtracts an effect from the
verified closure, so a virtualized model call runs under an empty
grant with no transport. NOT first-pass correctness or inference cost
(the deferred Run 8 study); the model is a monomorphic stand-in
builtin and the programs are hand-authored canonical dag-json.
========================================================================
```

## What this demonstrates and what it does not

This demonstration shows effect virtualization as a verified language feature: a
`Handler` intercepting an `LLM.Generate` EffectCategory subtracts it from the
verifier-computed effect closure, so the virtualized program runs under an empty
capability grant with the model transport never invoked. The surfaced closure is
the verifier's own closure-subtraction-aware computation
(`VerifyResult.Ok.rootClosure`), and the empty-grant run is the real runtime —
each is a property the shipped implementation enforces, witnessed through the
published embedding API.

It does **not** demonstrate the following, recorded so the scope is honest.

It does not demonstrate resumable handlers. The shipped Handler semantics are
no-continuation intercept-and-replace: the handler replaces the intercepted call
wholesale, with no `resume` to return control to the body. V3 frames the
mechanism as substitution for exactly this reason; multi-shot and one-shot
continuations are deferred (see the Layer 3 step 3 status in the implementation
README).

It does not use the real per-provider LLM ForeignNode. The intercepted call is a
**monomorphic stand-in** model builtin (`strand-builtin:Demo.LlmGenerate`, typed
`(String) -> String`) under an `LLM.Generate` EffectCategory, not
`Anthropic.Messages.Create` with its `GenerateResult` product. This is the brief's
sanctioned fallback: it keeps the pure handle trivially constructible while the
intercept and the closure-subtraction it produces stay genuine. The agent-workflow
demonstration exercises the real per-provider LLM ForeignNode under a mock
transport; this one isolates the *virtualization* mechanism.

The programs are **hand-authored** canonical dag-json. Hand-authoring isolates the
virtualization mechanism — the subject of this demonstration — from the separate
question of how an agent generates a program and at what cost, which the Q-021
cost measurement and the deferred Run 8 dynamic study address.

It does not demonstrate first-pass correctness — whether an agent's submission is
the program the agent intended — nor inference cost, the tokens an agent spends to
produce an admissible program. Those belong to the deferred Run 8 dynamic
measurement recorded in [`dynamic-results.md`](../../evaluation/dynamic-results.md),
which requires agent-emission sampling through the model API and is a distinct
study.

## References

**Outgoing references:**
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — the N-043 `Handler`
  semantics (no-continuation intercept-and-replace, the signature check, the
  closure-subtraction rule) this demonstration exercises; the `Handler` section
  and the Layer 3 step 3 status.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade and `HostPolicy` this host drives.
- [`open-questions.md`](../../open-questions.md#Q-067) — Q-067, the surfaced effect
  closure (`VerifyResult.Ok.rootClosure`) V1 and V2 read off the artifact; in V2
  it is empty because the Handler subtracted the intercepted category.
- [`containment-results.md`](../../evaluation/containment-results.md) — the Q-044
  containment measurement whose harm bound `closure(g) ∩ C ∩ B ∩ P` this
  demonstration moves: the Handler shrinks `closure(g)` itself, removing the
  effect from the bound rather than merely declining to grant it.
- [`demos/agent-workflow/README.md`](../agent-workflow/README.md) — the companion
  demonstration that exercises the *real* per-provider LLM ForeignNode (E-035
  `LLM.Generate`) under a mock transport; this demonstration isolates the
  virtualization mechanism with a stand-in model call instead.
- [`dynamic-results.md`](../../evaluation/dynamic-results.md) — the deferred Run 8
  cost measurement this demonstration deliberately does not cover (first-pass
  correctness, inference cost).

**Incoming references:**
- [`demos/README.md`](../README.md) — the demonstrations index, which lists this
  demonstration.
