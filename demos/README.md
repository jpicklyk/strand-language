# Demonstrations

Runnable demonstrations of shipped Strand capabilities, exercised through the
published APIs. Each demonstration is self-contained in its own subdirectory with
a narrative README, the Strand programs it admits, and the exact command to run
it. Demonstrations are distinct from the `corpus/` conformance programs and the
`evaluation/` measurements: a demonstration shows a capability working end to end,
where the measurements quantify it and the corpus pins its hashes.

## Index

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
