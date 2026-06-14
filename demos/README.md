# Demonstrations

Runnable demonstrations of shipped Strand capabilities, exercised through the
published APIs. Each demonstration is self-contained in its own subdirectory with
a narrative README, the Strand programs it admits, and the exact command to run
it. Demonstrations are distinct from the `corpus/` conformance programs and the
`evaluation/` measurements: a demonstration shows a capability working end to end,
where the measurements quantify it and the corpus pins its hashes.

**Start here:** [`agent-platform/`](agent-platform/README.md) is the end-to-end
narrative — it walks one untrusted, agent-generated task through the full platform
lifecycle (admit it under a provable harm bound, run it bounded, verify its output,
withstand attacks, audit the run) and draws each guarantee from one of the
demonstrations below. Read it first for the whole story; the rest are the
individual mechanisms it composes.

## Index

### agent-platform

The end-to-end narrative — the spine that ties the suite into a platform. One
untrusted, agent-generated task (a retrieval-backed support agent) is walked
through the full lifecycle of running it on Strand: **admit** it only after
computing its maximum harm from the artifact and confirming it is within the
tenant's grant and free of egress; **run** it bounded, retrieving from a refined
index and calling the model under exactly its declared capabilities; **verify**
the model's structured output at runtime before it is used; **withstand** attacks
— an over-reaching capability is denied, a poisoned input is contained as data,
an out-of-range model response is rejected; and **audit** the run, which is
content-addressed and replays deterministically. Each stage draws its guarantee
from one of the demonstrations below, narrated as a single task's journey. The
Kotlin driver and its assertion test live in the `:runtime` test source set; the
narrative lives under [`agent-platform/`](agent-platform/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:agentPlatformDemo -q
```

Run the assertion-backed test that pins every stage, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.AgentPlatformDemoTest"
```

### containment-host

An untrusted-agent-program host that admits Strand submissions it has never seen,
computes each program's maximum harm from the artifact alone, and runs each one
contained, with concurrent tenants isolated. The executable companion to the Q-044
containment measurement (`evaluation/containment-results.md`). The Kotlin driver
and its assertion test live in the `:runtime` test source set; the tenant programs
and narrative live under [`containment-host/`](containment-host/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:containmentDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.ContainmentDemoTest"
```

### output-by-construction

A correct-by-construction structured-output demonstration: a program produces a
structured document — a `JsonValue` whose array case is a genuine N-048 nested
`List<JsonValue>` (a real `Cons`/`Nil` spine, not the corpus-66 flat splice) —
carrying a Schema whose invariant makes a malformed document unemittable. The
invariant is checked before the value is admitted (verify time, for a
statically-known value, with the Q-035 `SchemaChecker`) or produced (runtime, for
a dynamically-computed value, via the Q-047 obligation), so a malformed artifact
never reaches output: correctness is structural, not a post-hoc lint. It
exercises this session's Q-053 / N-048 `RecursiveProjection` work for the nested
document and the Q-035 / Q-047 schema mechanism for the invariant. The Kotlin
driver and its assertion test live in the `:runtime` test source set; the
document programs and narrative live under
[`output-by-construction/`](output-by-construction/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:outputByConstructionDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.OutputByConstructionDemoTest"
```

### plugin-host

A host application that loads untrusted plugins — agent-generated Strand programs
— and grants each a precisely-scoped slice of its own authority, showing that a
confused or malicious plugin cannot escalate the delegated capability: not by
argument drift (the Q-039 effect projection binds the capability-check value to
the actual foreign-call argument, caught at admission), not by reaching outside
its scope (the Q-031 refined-capability match denies it at the foreign-call
boundary with a Q-064 `DenialReport`), and not by relying on the host's broad
authority (the host attenuates the grant before delegating). Distinct from
`containment-host`, which shows coarse whole-program tenant isolation; this demo
is about fine-grained capability attenuation and delegation within a single host.
The Kotlin driver and its assertion test live in the `:runtime` test source set;
the plugin programs and narrative live under
[`plugin-host/`](plugin-host/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:pluginHostDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.PluginHostDemoTest"
```

### replay-timetravel

A stateful service — an event-sourced ledger — whose lifetime trajectory
replays bit-identically with zero live IO, exposes its exact state at every
event index, and survives a snapshot written to disk, a process restart, and a
resume that lands on the same trajectory as an uninterrupted run. The
distinctive property is soundness: replay is bit-identical because the
transition is pure and the only world it observes arrives as recorded events on
effect edges, grounded in the Q-059 snapshot persistence and the Q-065
determinism guard. The Kotlin driver and its assertion test live in the
`:runtime` test source set; the ledger program and narrative live under
[`replay-timetravel/`](replay-timetravel/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:replayDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.ReplayDemoTest"
```

### agent-workflow

A bounded agent workflow — the demonstration that puts the AI-native primitives
on screen. A state machine drives a tool-using agent loop over a per-provider
LLM ForeignNode (E-035 `LLM.Generate`) with N-047 `Attempt` retry-with-backoff,
made deterministic by a mock `LlmHttpClient` injected through the Q-054
`HostPolicy`. The workflow runs to completion under a grant of exactly the
categories its surfaced effect closure (Q-067 `rootClosure`) declares — useful
work under a visible bound — while an ungranted tool effect is denied at the
foreign-call boundary with a Q-064 `DenialReport`, and the recorded run replays
bit-identically (Q-065 / Q-059). It uses no real network; the agent-generation
question (first-pass correctness, cost) is the deferred Run 8 study, so the
program is hand-authored. The Kotlin driver and its assertion test live in the
`:runtime` test source set; the workflow programs and narrative live under
[`agent-workflow/`](agent-workflow/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:agentWorkflowDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.AgentWorkflowDemoTest"
```

### clean-room

A proof-of-no-exfiltration demonstration: a submitted analytic computation over
a host-held sensitive dataset is admitted only after the host reads its effect
closure (Q-067) and confirms it contains a `Filesystem.Read` and *no* egress
category — `Network.*` / `Process.*` are absent, so the computation *cannot*
exfiltrate, not because a monitor watches for it but because the admitted graph
cannot express it. A sibling submission that adds a `Net.Connect` egress edge has
that category in its closure, lands beyond the clean-room grant, and is refused
before it runs. Distinct from `containment-host` (isolation + denial), this demo
turns the effect closure into a positive structural proof of a negative. The
proof holds for the clean-room profile (typed builtins only — no `Process.Spawn`
shell-out, whose effects would be opaque). The Kotlin driver and its assertion
test live in the `:runtime` test source set; the submissions and narrative live
under [`clean-room/`](clean-room/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:cleanRoomDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.CleanRoomDemoTest"
```

### mcp-tool-manifest

A verifiable tool-capability-manifest demonstration: where an MCP server's
capabilities are prose a client trusts by reading, a Strand-backed tool bundle
ships an N-046 `ModuleManifest` whose per-export declared effects are
*machine-checked* against the code — the verifier admits the manifest only when
each export's declared effects exactly equal its effect surface, so the manifest
is a statement of what each tool *can* do, not a claim. A manifest that
under-declares (a tool that writes but claims only read) is rejected at admission
with `ManifestExportEffectMismatch`, and the content-addressed manifest hash
makes a capability change visibly a different hash — a tamper-evident contract.
The Kotlin driver and its assertion test live in the `:runtime` test source set;
the manifest programs and narrative live under
[`mcp-tool-manifest/`](mcp-tool-manifest/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:mcpToolManifestDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.McpToolManifestDemoTest"
```

### llm-virtualization

An LLM-virtualization demonstration built on the Handler node (N-043) — the one
shipped language primitive no other demonstration uses. A `Handler` intercepting
`LLM.Generate` (E-035) replaces the model call wholesale, and the closure-
subtraction rule (`closureOf(handler) = closureOf(body) − {intercept} ∪ …`) means
the agent's LLM dependency *disappears from its harm bound* when wrapped: the same
agent program surfaces closure `{LLM.Generate}` run normally and an empty (pure)
closure run under a pure substitution handler. This is effect virtualization as a
verified language feature — the mechanism behind deterministic agent testing,
prompt-rewriting policy layers, response caching, and budget enforcement, none of
which is monkey-patching. The Kotlin driver and its assertion test live in the
`:runtime` test source set; the programs and narrative live under
[`llm-virtualization/`](llm-virtualization/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:llmVirtualizationDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.LlmVirtualizationDemoTest"
```

### bounded-rag

A retrieval-augmented-generation demonstration with a refined data-access
capability — the canonical agent pattern, expressed as a verified graph. An
embed → vector-query → generate pipeline surfaces the effect closure
`{LLM.Embed, Vector.Read, LLM.Generate}`, and the vector capability is *refined to
a specific store* (`Vector.Read{store=corp-kb}`) so the agent provably can query
only that index — a query against any other store is denied at the foreign-call
boundary with a `DenialReport`. The data the agent may reach is a declared,
verifiable, refinement-checked property, not a configuration convention. Made
deterministic by mock LLM and vector transports injected through the Q-054
`HostPolicy`; no real network I/O. The Kotlin driver and its assertion test live
in the `:runtime` test source set; the programs and narrative live under
[`bounded-rag/`](bounded-rag/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:boundedRagDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.BoundedRagDemoTest"
```

### skill-workflow

A model-as-policy / graph-as-actuator demonstration — and the one place the
threat model flips. The other demonstrations contain untrusted *code*; this one
contains untrusted *data*: the decisions an LLM emits, flowing into a trusted,
verified, capability-bounded actuator graph. A triage skill splits into the
*policy* (the model classifies items — non-deterministic, unbounded, outside the
graph) and the *actuator* (a verified graph that folds the decisions into a digest
and performs exactly its declared effect, `Filesystem.Write`). Because the model's
output is data the graph consumes, not code the graph runs, the action set is
graph structure fixed at admission: the surfaced closure is identical whatever the
decisions say, a poisoned `note` is contained as digest content with no new effect,
a poisoned output path is denied by the refined `Filesystem.Write` capability, and
the same decisions replay to the identical effect. The design is specified in
[`skill-workflow/SPEC.md`](skill-workflow/SPEC.md). The Kotlin driver and its
assertion test live in the `:runtime` test source set; the programs and narrative
live under [`skill-workflow/`](skill-workflow/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:skillWorkflowDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.SkillWorkflowDemoTest"
```

### multi-agent-supervisor

A supervised multi-agent group — concurrent agents as a verified orchestration,
each with its own effect bound. A supervisor and several worker agents run as a
`MachineGroup` of coroutine actors wired through content-addressed streams; the
orchestration itself is a verified graph (topology validated, each agent
type-checked), not trusted glue code. The distinctive property is supervised
isolation: when one worker reaches an effect beyond the group's grant it is denied
at the actor boundary with a `DenialReport`, that actor halts cleanly, and the
other agents keep running and complete — a rogue or compromised agent is contained
without taking down the multi-agent system. This is the actor-model substrate
(arguably a sounder foundation for concurrent multi-agent than a state-graph
simulation) with per-agent harm bounds. The Kotlin driver and its assertion test
live in the `:runtime` test source set; the programs and narrative live under
[`multi-agent-supervisor/`](multi-agent-supervisor/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:multiAgentSupervisorDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.MultiAgentSupervisorDemoTest"
```

### verified-llm-output

Constrain the model, then verify what it returns — two complementary layers,
because constraining is not guaranteeing. An LLM `Generate` call carries an N-045
`ResponseSchemaSpec` that projects to the provider's structured-output JSON schema
(the constraint on what the model may emit), and the returned value flows into a
Strand `Schema`-typed position whose invariant is checked at runtime (Q-047): a
structurally-typed-but-semantically-invalid response — a constrained model can
still return one — raises `SchemaInvariantViolation` before it reaches output, so
malformed model output never escapes. Deterministic via a mock LLM transport. The
Kotlin driver and its assertion test live in the `:runtime` test source set; the
programs and narrative live under [`verified-llm-output/`](verified-llm-output/README.md).

Run the transcript, from `impl-kotlin/`:

```sh
./gradlew :runtime:verifiedLlmOutputDemo -q
```

Run the assertion-backed test that pins every property, from `impl-kotlin/`:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.VerifiedLlmOutputDemoTest"
```
