# Bounded agent workflow demonstration {#agent-workflow-demo}

**Document:** `demos/agent-workflow/README.md`
**Status:** Executable demonstration of the AI-native primitives under a checked effect bound
**Last revised:** 2026-06-13

## What this demonstration is

This is the keystone demonstration: it puts Strand's AI-native primitives on
screen as a working tool-using agent loop running under a *visible,
machine-checked effect bound*. A Strand program calls a per-provider LLM
ForeignNode (`Anthropic.Messages.Create`, declaring E-035 `LLM.Generate`)
carrying a tool the model may invoke. The program runs to completion and
produces its result; a variant whose tool reaches an ungranted effect is
contained at the foreign-call boundary. The bound on everything the agent can
reach is read off the artifact *before* the run.

The host is an ordinary JVM caller of the shipped embedding surface — the Q-054
`StrandRuntime` facade with its `HostPolicy`, the Q-037 per-provider LLM
ForeignNodes and N-044 `ToolDef`, the Q-067 surfaced effect closure, and the
Q-064 `DenialReport`. It introduces no language feature, no node category, no
encoding change, and no verifier rule. Every property the demonstration claims
is one the runtime enforces, and the assertion net (`AgentWorkflowDemoTest`)
protects each one from silently rotting.

The LLM transport is a deterministic mock `LlmHttpClient` injected through the
`HostPolicy`, so the run reads canned provider responses and performs no real
network I/O. The agent program is hand-authored canonical dag-json (it lives
under [`programs/`](programs/)). Both choices isolate the *host's* containment of
useful agent work — the subject of this demonstration — from the separate
question of how an agent generates a program and at what cost, which the Q-021
cost measurement and the deferred Run 8 dynamic study address.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:agentWorkflowDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.AgentWorkflowDemoTest"
```

The driver `AgentWorkflowDemo` and the test `AgentWorkflowDemoTest` live in the
`:runtime` test source set
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and share one body of
scenario code, so the printed demonstration and the regression net cannot
diverge. They stay in `:runtime` because they compile against the runtime
modules. The driver loads the committed canonical dag-json from
[`programs/`](programs/) through the test classpath (`runtime/build.gradle.kts`
copies the directory in via `processTestResources`), so the artifact the host
runs is the content-addressed graph, not the human-facing projection.

The mock transport is reimplemented inside the driver (`MockLlmTransport`)
because the interpreter module's `RecordingHttpClient` is `internal` and not on
`:runtime`'s classpath; it returns canned Anthropic Messages API JSON, one
response per call, with no real network I/O. This is the same injection seam the
per-provider LLM provider tests use — `HostPolicy.OPEN.copy(llmHttpClient =
mock)` — exercised through the facade rather than the singleton.

## The scenarios

### A1 Bounded run

The agent program `agent-loop` calls `Anthropic.Messages.Create` with a request
carrying one tool, `get_weather`, whose implementation is a pure Lambda. The host
verifies the program and reads its *surfaced effect closure* off the
`VerifyResult.Ok` (Q-067 `rootClosure`): `{LLM.Generate}`. That set is the
machine-checked statement of everything the agent can reach, available from the
artifact before any execution. The host then grants a `CapabilitySet` of exactly
those categories and runs the program under the deterministic mock. The model
returns a single text completion (no tool call), and the program produces its
`GenerateResult`.

The point is the tightness and the ordering: the bound is computed by the
verifier, equals the grant exactly (not a superset), and is known before the run.
Useful agent work proceeds under a bound readable from the artifact.

### A2 Over-reach contained

The variant `agent-loop-overreach` has the same shape, but `get_weather`'s
implementation reaches `Filesystem.Write` on `/etc/shadow` — the prompt-injection
shape, where a tool the model is told to call performs an effect the principal
never intended.

The instructive part is what the static bound shows. The over-reach program
*still* surfaces a root closure of `{LLM.Generate}` and verifies clean: an N-044
`ToolDef`'s implementation effects are deliberately not part of the program's
static effect closure (the verifier records an empty closure for the ToolDef
node), because those effects fire only at the tool-dispatch sites inside the
provider's loop and are checked there against the live grant. So the static bound
of the over-reaching program is indistinguishable from the benign one.

The host grants `{LLM.Generate}` only. The mock drives the model to invoke the
tool (a `tool_use` response), which triggers the builtin's tool-dispatch loop.
When the loop invokes the tool's implementation, the implementation's
`Filesystem.Write` call reaches the interpreter's capability check under the same
grant; the category is absent, so the runtime denies the call with a
`CapabilityViolation` carrying a structured Q-064 `DenialReport`. The host
captures and renders the report: the denied category (`Filesystem.Write`), the
denying node, an empty held-grant summary (nothing was granted for that
category), and the `expression` phase.

This is the prompt-injection-contained story expressed against the real runtime:
a tool reaching for an ungranted effect is stopped at the foreign-call boundary,
regardless of what the model was persuaded to do. The denial is the one the
interpreter actually constructs at the denial site — nothing is staged.

### A3 Deterministic re-run (scoped)

The mock transport is fully deterministic, so running A1 twice under the same
injected transport yields a bit-identical `GenerateResult`. The workflow is a
pure function of (program, grant, mock transport).

This is the *scoped* form of the replay claim, stated narrowly on purpose. It
demonstrates same-mock determinism — that the bounded run reproduces exactly
under a fixed transport — and nothing more. It is **not** a full state-machine
snapshot, time-travel, or restart-resume; sound deterministic replay over a
*stateful* machine is the separate `replay-timetravel` demonstration's R1, which
records a live run and replays the recorded log under a clock that throws if read.
A3 is the honest claim this fixture can land reliably without overstating it.

## Transcript

The transcript below is the output of `./gradlew :runtime:agentWorkflowDemo -q`.

```
========================================================================
Strand -- a bounded agent workflow (tool-using agent under an
effect bound readable from the artifact before it runs).
LLM transport is a deterministic mock; no real network I/O.
========================================================================

A1  Bounded run -- useful agent work under a visible, checked bound
------------------------------------------------------------------------
  Program: agent-loop (calls Anthropic.Messages.Create carrying a
           pure get_weather tool the model may invoke).
  The bound is read off the verified artifact BEFORE running:
    surfaced closure (Q-067)  = [LLM.Generate]
    granted categories        = [LLM.Generate]
    grant covers exactly it   = true
  Host runs under that grant with the deterministic mock LLM:
    verified clean            = true
    LLM turns                 = 1
    completed                 = true
    result (first text block) = "It is sunny and 72F."
  Everything the agent can reach is the surfaced closure -- a
  machine-checked statement, not a promise. The grant is tight.

A2  Over-reach contained -- ungranted tool effect denied at dispatch
------------------------------------------------------------------------
  Program: agent-loop-overreach (same shape, but get_weather's
           implementation reaches Filesystem.Write on /etc/shadow).
  The static bound looks identical to A1 -- the ToolDef's effects
  are NOT in the root closure; they fire at dispatch and are checked
  against the live grant:
    surfaced closure (Q-067)  = [LLM.Generate]
    verified clean            = true
  Host grants {LLM.Generate} only. The mock drives the model to
  invoke the tool; the tool's write is beyond the grant:
    LLM turns before denial   = 1
    denied                    = true (CapabilityViolation)
    DenialReport: category=Filesystem.Write requested=[] held=[] node=#35 phase=expression
  This is the prompt-injection-contained story: a tool reaching for
  an ungranted effect is stopped at the foreign-call boundary.

A3  Deterministic re-run (scoped) -- pure function of program+grant+mock
------------------------------------------------------------------------
  Running A1 twice under the same deterministic mock transport
  reproduces the GenerateResult bit-identically:
    result 1 == result 2      = true
    result (first text block) = "It is sunny and 72F."
  Scope: this is same-mock determinism, NOT a state-machine
  snapshot/replay -- that is the replay-timetravel R1 demonstration.

========================================================================
What this demonstrates: a tool-using agent loop runs useful work
under a machine-checked effect bound readable from the artifact, and
an over-reaching tool is contained at the foreign-call boundary. NOT
first-pass correctness or inference cost (the deferred Run 8 study);
the LLM transport is a mock and the program is hand-authored.
========================================================================
```

## What this demonstrates and what it does not

This demonstration shows a tool-using agent loop running useful work under a
machine-checked effect bound that is readable from the artifact before the run,
and an over-reaching tool contained at the foreign-call boundary with a
structured denial. Each of these is a property the shipped runtime enforces,
witnessed through the published embedding API.

It does not demonstrate first-pass correctness — whether an agent's submission is
the program the agent intended — nor inference cost, the tokens an agent spends to
produce an admissible program. Those belong to the deferred Run 8 dynamic
measurement recorded in [`dynamic-results.md`](../../evaluation/dynamic-results.md),
which requires agent-emission sampling through the model API and is a distinct
study. The program here is hand-authored precisely so the demonstration measures
the host's containment in isolation from the agent-generation question.

The LLM transport is a deterministic mock, not a real provider call. This is a
deliberate scoping choice: it makes the run reproducible and removes the network
and the cost from the measured surface, so what remains is exactly the
containment properties (the surfaced bound, the tight grant, the runtime denial).
The surfaced bound shown in A1 and A2 is the verifier's own effect closure
(`VerifyResult.Ok.rootClosure`), not a re-derivation — it is the real closure the
verifier enforced. The denial in A2 is the `DenialReport` the interpreter
actually constructs at the denial site. Nothing is staged.

A3's replay claim is scoped to same-mock determinism on purpose. A full
state-machine snapshot/time-travel/restart-resume demonstration is the separate
`replay-timetravel` demonstration; A3 deliberately makes the narrower claim its
fixture can support honestly rather than overstating it.

## Simplifications worth recording

Two simplifications relative to the fullest framing of an agent workflow are
recorded here so the demonstration's scope is honest.

The workflow is driven through `StrandRuntime.run` (an ordinary expression
evaluation of an `Application` that calls the LLM ForeignNode), not through a
`StateMachine` driven by `runMachine`. The LLM-call-plus-tool shape is what
carries the load-bearing properties — the surfaced bound, the grant, and the
runtime denial inside the tool-dispatch loop — and it exercises them against the
real runtime without the additional state-machine scaffolding. Expressing the
same loop as a verified `StateMachine` and replaying it through `runMachine`
(corpus 67 is the verify-only state-machine-with-tool exemplar) is a natural
extension; the `replay-timetravel` demonstration already covers sound replay over
a stateful machine, so A3 here makes the narrower same-mock-determinism claim
rather than duplicating it.

The over-reach in A2 uses the legacy `strand-builtin:Filesystem.Write` stub as
the ungranted effect. The stub declares E-007 `Filesystem.Write` and performs no
real disk write — but the capability check fires *before* the builtin body runs,
so the denial is genuine and the demonstration touches no real filesystem. The
target path (`/etc/shadow`) is illustrative of the harm a successful over-reach
would cause; the point the demonstration makes is that the write never reaches
the builtin at all.

## References

**Outgoing references:**
- [`proposals/implemented/agent-native-capabilities.md`](../../proposals/implemented/agent-native-capabilities.md)
  — Q-037, the per-provider LLM ForeignNodes (E-035 `LLM.Generate`) and N-044
  `ToolDef` the agent loop is built on.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade and `HostPolicy` (the `llmHttpClient`
  injection seam) this host drives.
- [`proposals/implemented/capability-denial-observability.md`](../../proposals/implemented/capability-denial-observability.md)
  — Q-064, the `DenialReport` captured in A2.
- [`open-questions.md`](../../open-questions.md#Q-067) — Q-067, the surfaced
  effect closure (`VerifyResult.Ok.rootClosure`) A1 and A2 read off the artifact
  as the machine-checked bound.
- [`containment-results.md`](../../evaluation/containment-results.md) — the Q-044
  containment measurement whose harm bound `closure(g) ∩ C ∩ B ∩ P` this
  workflow exhibits: the surfaced closure is `closure(g)`, the grant is `C`, and
  A2 is the case where the run reaches beyond `C`.
- [`dynamic-results.md`](../../evaluation/dynamic-results.md) — the deferred Run 8
  cost measurement this demonstration deliberately does not cover (first-pass
  correctness, inference cost).

**Incoming references:**
- [`demos/README.md`](../README.md) — the demonstrations index, which lists this
  demonstration.
