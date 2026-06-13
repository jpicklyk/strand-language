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
