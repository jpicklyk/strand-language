# Multi-agent-supervisor demonstration {#multi-agent-supervisor-demo}

**Document:** `demos/multi-agent-supervisor/README.md`
**Status:** Runnable demonstration; built on shipped APIs only
**Last revised:** 2026-06-14

## What this demonstration is

This demonstration runs a group of concurrent agents as a *verified
orchestration* — a supervisor and three worker agents wired together as a
`MachineGroup` of per-machine coroutine actors over content-addressed streams —
each agent carrying its own effect bound. The orchestration itself is a
content-addressed verified graph (each agent type-checks, the cross-machine
topology validates), not trusted glue code.

The distinctive property is **supervised isolation**. When one worker reaches an
effect beyond the group's grant, it is denied at the actor boundary with a
structured `DenialReport`, that actor halts cleanly, and the other agents keep
running and complete their work. A rogue or compromised agent is contained
without taking down the multi-agent system. This is the actor-model substrate —
per-machine coroutines, `select`-merged inputs, content-addressing-as-wiring —
with per-agent harm bounds enforced where each agent runs.

The host is an ordinary JVM caller of the shipped embedding surface: the Q-054
`StrandRuntime` facade (`verify` / `runGroup` / `runMachine`), the Layer 6 step 2
async actor runtime, the Q-031 refined `CapabilitySet` grant, and the Q-064
per-actor `DenialReport` exposed on `MachineInstanceHandle.denialReport`. It
introduces no language feature, no node category, no encoding change, and no
verifier rule. Every property the demonstration claims is one the shipped runtime
enforces, and the assertion net (`MultiAgentSupervisorDemoTest`) protects each one
from silently rotting.

## The agents are state machines

The "agents" here are state machines, not literal LLM agents — the subject is the
orchestration, the per-agent bounds, and the isolation, not how an agent reasons.
A worker *could* call an LLM inside its transition (the per-provider `LLM.Generate`
ForeignNodes exist and the `agent-workflow` and `bounded-rag` demonstrations
exercise them); here the worker logic is kept deliberately trivial — a running
counter — so the orchestration is the thing on screen. The heaviest machinery in
this demonstration is the concurrency: async coroutine actors, `MachineGroup`
wiring, routed events, per-actor denial halts.

The program lives as compiled canonical dag-json under
[`programs/supervisor-group.json`](programs/supervisor-group.json). It is the
corpus-48 supervisor shape (a top-level `Let` chain keeps every machine reachable
through `Hasher.finalize`):

- **Three worker agents**, each with its own external input stream. A worker's
  transition `(state: Int, event: Int) -> {state, outputs}` performs an effectful
  `Test.EffectfulNoOp` write whose `EffectDecl` refines `Filesystem.Write` with the
  worker's own per-worker path literal (`/agent/worker-a.log` for A, `…-b` for B,
  `…-c` for C) — the per-agent effect bound — then folds the event into its
  counter and emits the new counter on its internal output stream.
- **A supervisor agent** consuming the three workers' emissions over internal
  streams (`chanA` / `chanB` / `chanC`), folding every emission into a running
  total it emits on the external output stream.

The write is sequenced into the worker's state computation (`Int.Add(writeResult,
Int.Add(state, event))`, and the write returns `0`) so the effectful call is on the
data-flow path and runs on every transition — which is what makes the per-worker
`Filesystem.Write` refinement a meaningful, enforced bound.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:multiAgentSupervisorDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.MultiAgentSupervisorDemoTest"
```

The driver `MultiAgentSupervisorDemo` and the test `MultiAgentSupervisorDemoTest`
live in the `:runtime` test source set
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and share one body of
scenario code, so the printed demonstration and the regression net cannot diverge.
They stay in `:runtime` because they compile against the runtime modules. The
driver loads the committed canonical dag-json from [`programs/`](programs/) through
the test classpath (`runtime/build.gradle.kts` copies the directory in via
`processTestResources`), so the artifact the host admits is the content-addressed
graph, not a human-facing projection.

## The scenarios

### MA1 The group runs as a verified orchestration

The host verifies the program (the orchestration is a content-addressed graph,
admitted before it runs), builds the `MachineGroup` from every StateMachine in the
store — mirroring the CLI's `group` subcommand — and validates the cross-machine
topology (every internal stream has a producer and a consumer; no gaps). Then it
runs the group under a `CapabilitySet` granting `Filesystem.Write` refined to all
three workers' paths, routes events to each worker (A gets {5, 7}, B {10}, C {3}),
and drains the supervisor's output.

Every worker folds its events and writes (its bound is satisfied): A ends at 12, B
at 10, C at 3. The supervisor folds every worker emission into a running total —
each worker emits its running counter on every transition, so the supervisor's
final aggregate is 5 + 12 + 10 + 3 = 30. The agents ran concurrently and the
orchestration produced the expected aggregate. *The orchestration is a verified
graph, not trusted glue.*

### MA2 Supervised isolation (keystone)

The host grants `Filesystem.Write` refined to A's and B's paths but **not** C's.
Worker C's transition reaches `Filesystem.Write{/agent/worker-c.log}`, which no
granted pattern covers, so the refinement check denies it **at C's actor boundary**.
The host captures the real `DenialReport` off `MachineInstanceHandle.denialReport`
(requested `/agent/worker-c.log`, held the A+B grant, denying instance, event index
0, phase `transition`); C halts cleanly.

The other agents keep running and complete: A ends at 12, B at 10, and the
supervisor stays up and aggregates the survivors' emissions. The keystone property
is *denied AND survived* — a rogue or compromised agent is contained without taking
down the multi-agent system. Per-agent harm bounds, enforced at the actor boundary,
mean a single agent's over-reach is isolated rather than fatal to the group.

This is the contrast with a conventional multi-agent harness in one line: a worker
that reaches outside its grant is the only thing that stops.

### MA3 Per-instance replay determinism

The events worker A actually consumed in the live group (captured via
`handle.recordedEvents(instanceId)`) are fed back into the synchronous
`StateMachineRuntime.runMachine` against the same StateMachine node. Because the
transition function is pure, the reproduced final state (12) equals the live one — a
worker's run is replayable from its recorded events.

## Transcript

The transcript below is the output of `./gradlew :runtime:multiAgentSupervisorDemo
-q`. The `instance=` UUID prefix varies per run and is shown as `<tmp>`. The
supervisor's *emission list* varies in intermediate order across runs (the actors
interleave nondeterministically per Q-009's default), so the per-element order is
shown as `<varies>`; the **final aggregate** (30) and the per-worker final states
are deterministic — addition commutes, so the running total is order-independent.

```
========================================================================
Strand -- supervised multi-agent group
Concurrent agents as a verified orchestration of coroutine actors,
each with its own effect bound. A rogue agent is contained at the
actor boundary; the multi-agent system survives.
========================================================================

MA1  Verified orchestration -- concurrent agents, expected outputs
------------------------------------------------------------------------
  Group: supervisor + 3 worker agents as a MachineGroup of
         coroutine actors wired through content-addressed streams.
         Each worker folds its Int events into a counter and writes
         to its own per-worker path (its effect bound). Workers emit
         their counter to the supervisor via internal streams; the
         supervisor aggregates into a running total.
  The orchestration is a content-addressed verified graph, not glue:
    orchestration verified    = true
    topology validated        = true
  Run under a grant covering every worker's write path:
    worker A final state      = IntV(v=12)  (5 + 7 = 12)
    worker B final state      = IntV(v=10)  (10)
    worker C final state      = IntV(v=3)  (3)
    any agent denied          = false
    all workers completed     = true
    supervisor emissions      = [<varies>, ..., IntV(v=30)]
    supervisor aggregate total= 30  (each worker emits its
                                running counter every step: 5+12+10+3 = 30)
  The agents ran concurrently and the supervisor produced the
  expected aggregate -- the orchestration is verified, not trusted.

MA2  Supervised isolation -- a rogue agent contained, the group survives
------------------------------------------------------------------------
  Grant: Filesystem.Write refined to A's and B's paths but NOT C's.
         Worker C reaches Filesystem.Write{/agent/worker-c.log},
         which no granted pattern covers.
  The rogue worker (C) is denied at its actor boundary:
    worker C halted           = true
    DenialReport: category=Filesystem.Write requested=[/agent/worker-c.log] held=[Filesystem.Write{/agent/worker-a.log}, Filesystem.Write{/agent/worker-b.log}] instance=<tmp> eventIndex=0 phase=transition
  The OTHER agents keep running and complete -- the group survives:
    worker A final state      = IntV(v=12)  (completed)
    worker B final state      = IntV(v=10)  (completed)
    supervisor stayed up      = true
    supervisor emissions      = [<varies>]
    contained (denied + survived) = true
  A rogue or compromised agent is contained without taking down the
  multi-agent system. Per-agent harm bounds, enforced at the actor
  boundary -- not whole-system failure.

MA3  Per-instance replay determinism
------------------------------------------------------------------------
  Feed the events worker A actually consumed back into the
  synchronous runMachine against the same StateMachine node:
    recorded events (A)       = [IntV(v=5), IntV(v=7)]
    live final state          = IntV(v=12)
    replayed final state      = IntV(v=12)
    trajectories match        = true
  The pure transition function reproduces the trajectory exactly --
  a worker's run is replayable from its recorded events.

========================================================================
What this demonstrates: supervised isolation on the actor-model
substrate -- concurrent agents as a verified orchestration with
per-agent harm bounds; a rogue agent denied at the actor boundary
while the others complete. The agents are state machines (a worker
could call an LLM); the orchestration / bounds / isolation are the
subject, NOT literal LLM agents. Programs are hand-authored, NOT
measured for first-pass correctness or cost.
========================================================================
```

## What makes this distinct

- vs **containment-host**: that shows coarse whole-program tenant isolation (one
  submission per host, concurrent tenants under distinct policies). Here the
  isolation is *within one orchestration* — distinct agents in one `MachineGroup`,
  each with its own bound, where one agent's denial does not halt the others. The
  unit of containment is the actor, not the process.
- vs **plugin-host / skill-workflow**: those bound a single capability-using program
  (a delegated grant, a model-parameterized actuator). Here the bound is *per agent*
  in a concurrent group, and the keystone is survival of the group when one agent
  over-reaches.
- vs the state-machine corpus programs (41–49): those exercise the runtime
  mechanics. This composes them into the multi-agent-supervision story — concurrent
  agents, per-agent harm bounds, a contained rogue — through the published embedding
  API.

## What this demonstrates and what it does not

This demonstration shows supervised isolation on the actor-model substrate:
concurrent agents as a verified orchestration with per-agent effect bounds, where a
rogue agent is denied at the actor boundary while the others complete. Each property
is one the shipped async runtime enforces, witnessed through the published embedding
API.

It does **not** demonstrate the following, by design:

- **Literal LLM agents.** The agents are state machines with trivial counter logic.
  A worker could call an LLM inside its transition — the per-provider `LLM.Generate`
  ForeignNodes exist and other demonstrations exercise them — but the subject here is
  the orchestration / bounds / isolation, not how an agent reasons. Keeping the
  worker logic a counter is deliberate: it puts the concurrency, the wiring, and the
  denial isolation on screen rather than rich behavior.
- **Real I/O.** The per-worker effect is a `Test.EffectfulNoOp` write standing in for
  a real `Fs.Write` (it returns `0` so the worker's arithmetic is unaffected). The
  load-bearing property — that the write is refinement-checked against a per-worker
  path, and a worker whose path is ungranted is denied at the actor boundary — is
  genuine; the builtin is a test stand-in so the demonstration touches no filesystem.
  The same refinement check fires against a real `Fs.Write` (the `skill-workflow` and
  `plugin-host` demonstrations show it on the real builtin).
- **First-pass correctness or inference cost.** Whether an agent's behavior is
  *right*, and the tokens an agent spends to produce admissible programs, are not
  measured here. The program is hand-authored, as every other demonstration
  hand-authors its programs to isolate the agent-generation question; correctness and
  cost belong to the deferred Run 8 dynamic measurement.
- **Dynamic supervision policies.** The supervisor here is the corpus-48
  *observational* framing — it aggregates the workers' emissions. Real
  spawn-and-restart supervision (OneForOne / OneForAll restart policies via
  E-030/E-031) is the Layer 6 step 3 primitive set (`RuntimeContext.spawn` /
  `terminate`, `MachineGroupHandle.spawn`); a restart-on-denial supervisor capstone
  is a corpus-level follow-up built on those primitives, out of scope here.

It is a demonstration of a mechanism, not a soundness proof of the language. As
stated in [`containment-results.md`](../../evaluation/containment-results.md),
soundness is a universal property argued from the mechanisms with executed witnesses
as spot-checks; this companion is one more executed witness, driving concurrent
verified orchestration and per-actor denial isolation through the async runtime and
capability boundaries a host would actually use.

## References

**Outgoing references:**
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — the Layer 6 step 2 async
  actor runtime (`MachineGroup`, `runGroup`, `MachineGroupHandle`, per-instance
  `EventRecorder`) and the corpus-48 supervisor shape this program adapts.
- [`corpus/48-async-supervisor-one-for-one.json`](../../corpus/48-async-supervisor-one-for-one.json)
  — the three-machine supervisor topology (workers wired to a supervisor via internal
  streams, Let-chain root for multi-machine reachability) this program follows.
- [`open-questions.md`](../../open-questions.md#Q-064) — Q-064, the structured
  `DenialReport` MA2 captures off `MachineInstanceHandle.denialReport`; a
  denial-caused actor halt exposes the report with instance / event / phase while the
  other actors keep running.
- [`open-questions.md`](../../open-questions.md#Q-031) — Q-031, the refinement-lattice
  capability matching that bounds each worker's write path; the per-worker
  `Filesystem.Write{path}` refinement is what MA2's grant covers for A and B but not C.
- [`open-questions.md`](../../open-questions.md#Q-009) — Q-009, the nondeterministic
  cross-stream merge default that makes the supervisor's intermediate emission order
  vary across runs (the final aggregate is order-independent).
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade (`verify` / `runGroup` / `runMachine`) and
  `HostPolicy` this host is built on.
- [`evaluation/containment-results.md`](../../evaluation/containment-results.md) — the
  Q-044 containment measurement; the per-agent harm bound `closure(g) ∩ C` this
  demonstration enforces at each actor.

**Incoming references:**
- [`demos/README.md`](../README.md) — index entry.
