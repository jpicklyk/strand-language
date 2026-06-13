# Replay and time-travel demonstration {#replay-timetravel-demo}

**Document:** `demos/replay-timetravel/README.md`
**Status:** Executable companion to the Q-059 snapshot-persistence and Q-065 determinism-enforcement work
**Last revised:** 2026-06-13

## What this demonstration is

A Strand state machine's lifetime trajectory — its sequence of (event, state,
outputs) triples — replays bit-identically with zero live IO, because the
transition function is pure and every effect the machine touches is a graph
edge realized as a recorded event. This demonstration drives that property end
to end on a small running service and pins each claim with assertions.

The subject is an event-sourced ledger: a state machine whose state is a
running balance over a sequence of deposit and withdrawal events. State is the
product `{balance, lastStamp, count}`; each input event is the product
`{amount, stamp}` where `amount` is the signed delta (positive deposit,
negative withdrawal) and `stamp` is a recorded IO value — a timestamp the
*driver* reads from the host clock at the moment the event is admitted and
stamps into the event payload before feeding it to the machine. The transition
calls only `Int.Add` (a Q-065 `Deterministic` builtin), reads no clock of its
own, and threads the recorded timestamp through state as ordinary data.

That purity is the soundness basis. Because the transition references only
deterministic builtins and the only world it observes arrives as data on an
event — the timestamp is a recorded input on an effect edge, not a `Time.Now`
call inside the transition — re-folding the recorded event sequence reproduces
the trajectory bit-identically with zero live IO. The Q-065 verifier guard
checks this: a transition that referenced a structurally-pure-but-nondeterministic
builtin would raise `NondeterministicInReplayContext`. The ledger machine raises
no such warning, which the demonstration confirms before running any scenario.

The driver is an ordinary JVM caller of the shipped runtime: the Q-054
`StrandRuntime` facade (`runMachine` for the synchronous fold, `runGroup` for
the live actor group, `writeSnapshot` / `readSnapshot` / `resume` for the
restart path), the Q-059 `ValueCodec`-backed snapshot persistence, and the
slice-3.3 `Snapshot` / `EventRecorder`. It introduces no language feature, no
node category, no encoding change, and no verifier rule. Every property the
demonstration claims is one the runtime enforces, and the assertion net
(`ReplayDemoTest`) protects each one from silently rotting.

The ledger program is hand-authored as Layer A source plus compiled canonical
dag-json under [`programs/`](programs/). Authoring it directly isolates the
demonstration's replay property from the separate question of how an agent
generates the program.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:replayDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.ReplayDemoTest"
```

The driver `ReplayDemo` and the test `ReplayDemoTest` live in the `:runtime`
test source set (`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and
share one body of scenario code, so the printed demonstration and the
regression net cannot diverge. They stay in `:runtime` because they compile
against the runtime modules. The driver loads the committed canonical dag-json
from [`programs/`](programs/) through the test classpath
(`runtime/build.gradle.kts` copies the directory in via `processTestResources`),
so the artifact the driver runs is the content-addressed graph, not the
human-facing projection.

## The scenarios

### R1 Deterministic replay

The driver runs the ledger over a sequence of six events, recording them as it
goes: it reads the host clock once per event and stamps the value into the
event payload, so the recorded stamps are genuinely sourced from live IO on
this run. It then replays the recorded events through `runMachine` under a
clock that throws if read, and asserts the full state trajectory and the
per-step outputs are bit-identical to the live run. The replay completes
cleanly, so the replay path touched no live clock — the zero-live-IO property
the proposals claim, made concrete: the recorded timestamps travel as data, the
synchronous fold reads no clock, and the transition is pure, so there is nothing
live to touch.

To make the "live IO would differ but replay does not" point undeniable, the
driver runs a fresh live pass under a *different* clock (starting nine million
milliseconds later, stepping by a different interval). That run produces a
different trajectory — its `lastStamp` field differs at every step — proving a
second live pass genuinely diverges. Replaying the first run's record reproduces
the first run regardless. A conventional service cannot promise this: its
effects are implicit and interleaved, so a second pass reads fresh clocks and IO
results and diverges from the first.

### R2 Time-travel inspection

From the recorded run's `Trace`, the driver reads `steps[i].after` — the exact
state-after the runtime computed at event index `i`. Landing on any index and
reading its state is the "step back to event N" capability, served entirely
from the recorded trajectory with no re-execution of live effects. The
demonstration prints the state at every index, confirms the balance at each
index is the running sum to that point, and confirms each state records
`count == i+1` so the trajectory is coherent rather than approximate.

### R3 Snapshot, restart, resume

The driver runs the first three of the six events as a live actor group, takes
a `Snapshot` of the instance, and writes it to disk through the facade's
`ValueCodec`-backed `writeSnapshot`. It then drops the Process-A objects and,
from a fresh `StrandRuntime` under a new `HostPolicy` — the new process — reads
the snapshot back with `readSnapshot` and resumes over the remaining three
events. The post-snapshot trajectory, its per-step outputs, and the final state
all equal the matching tail of an uninterrupted single run over the full six
events. This reuses the `SnapshotPersistenceTest` technique (run partway,
snapshot, drop, restore in a fresh runtime, resume, assert identical
trajectory), packaged on the ledger machine as a watchable artifact. The
snapshot's machine-hash integrity check (`SnapshotMachineHashMismatch`) is the
same one `StateMachineRuntime.resume` enforces and is left intact.

## Transcript

The transcript below is the output of `./gradlew :runtime:replayDemo -q`.

```
========================================================================
Strand -- sound deterministic replay, time-travel, restart-resume
Subject: an event-sourced ledger (a running balance over events).
========================================================================

Soundness basis (Q-065): the transition references only deterministic
builtins (Int.Add) and reads no clock of its own -- the recorded
timestamp arrives as DATA on the event, not via a Time.Now call.
  verifier NondeterministicInReplayContext warnings = 0

R1  Deterministic replay -- bit-identical trajectory, zero live IO
------------------------------------------------------------------------
  Live run: the driver read the host clock to stamp each event.
    recorded stamps          = [1717200000000, 1717200001000, 1717200002000, 1717200003000, 1717200004000, 1717200005000]
    live trajectory          = [bal=100 stamp=1717200000000 count=1, bal=70 stamp=1717200001000 count=2, bal=120 stamp=1717200002000 count=3, bal=320 stamp=1717200003000 count=4, bal=200 stamp=1717200004000 count=5, bal=225 stamp=1717200005000 count=6]
  Replay: re-fed the recorded events under a clock that THROWS if read.
    replay touched no live IO = true
    replay trajectory        = [bal=100 stamp=1717200000000 count=1, bal=70 stamp=1717200001000 count=2, bal=120 stamp=1717200002000 count=3, bal=320 stamp=1717200003000 count=4, bal=200 stamp=1717200004000 count=5, bal=225 stamp=1717200005000 count=6]
    trajectories identical   = true
    outputs identical        = true
  Contrast: a FRESH live run under a DIFFERENT clock genuinely differs.
    contrast trajectory      = [bal=100 stamp=1717209000000 count=1, bal=70 stamp=1717209007000 count=2, bal=120 stamp=1717209014000 count=3, bal=320 stamp=1717209021000 count=4, bal=200 stamp=1717209028000 count=5, bal=225 stamp=1717209035000 count=6]
    replay == live != contrast = true
  A conventional service cannot promise this: effects are implicit and
  interleaved, so a second pass reads fresh clocks/IO and diverges.

R2  Time-travel inspection -- read the exact state at any event index
------------------------------------------------------------------------
  From the recorded run, the Trace exposes the state-after at each
  event index. Step back to any point in history and read it:
    after event 0  ->  bal=100 stamp=1717200000000 count=1
    after event 1  ->  bal=70 stamp=1717200001000 count=2
    after event 2  ->  bal=120 stamp=1717200002000 count=3
    after event 3  ->  bal=320 stamp=1717200003000 count=4
    after event 4  ->  bal=200 stamp=1717200004000 count=5
    after event 5  ->  bal=225 stamp=1717200005000 count=6
    balances by index        = [100, 70, 120, 320, 200, 225]
    counts are consecutive   = true
    final state              = bal=225 stamp=1717200005000 count=6

R3  Snapshot -> restart -> resume -- survives a process boundary
------------------------------------------------------------------------
  Process A ran the first 3 of 6 events, snapshotted, and wrote the
  snapshot to disk through the ValueCodec-backed facade.
    snapshot file            = ledger.snapshot.json
    snapshot state           = bal=120 stamp=1717200002000 count=3
    processed event count    = 3
  Process B (fresh StrandRuntime + new HostPolicy) read it and resumed:
    resumed trajectory       = [bal=320 stamp=1717200003000 count=4, bal=200 stamp=1717200004000 count=5, bal=225 stamp=1717200005000 count=6]
    uninterrupted tail       = [bal=320 stamp=1717200003000 count=4, bal=200 stamp=1717200004000 count=5, bal=225 stamp=1717200005000 count=6]
    trajectories identical   = true
    outputs identical        = true
    final states identical   = true
    resumed final            = bal=225 stamp=1717200005000 count=6

========================================================================
What this demonstrates: SOUND deterministic replay, time-travel, and
restart-resume -- grounded in pure transitions + effects-as-edges +
Q-065 determinism enforcement. NOT first-pass correctness or cost.
========================================================================
```

## What this demonstrates and what it does not

This demonstration shows sound deterministic replay, time-travel state
inspection, and restart-resume. The soundness is not an accident of the example:
it is grounded in the structure Strand enforces — pure transition functions,
effects modeled as graph edges and realized as recorded events, and the Q-065
determinism guard that flags a transition referencing a nondeterministic
builtin. Replay reproduces the recorded trajectory bit-for-bit with zero live
IO; the runtime exposes the exact state-after at every event index; and a
snapshot written through the codec restores in a fresh process under a new host
policy and resumes to a trajectory identical to an uninterrupted run.

A conventional service cannot promise bit-identical replay, because its effects
are implicit and interleaved with computation. A clock read, a database row, a
network response, a random draw — each is a fresh observation on the second
pass, with nothing recording which value the first pass saw or guaranteeing the
computation depended on nothing else. Strand makes the dependency structural: a
transition that wants the world must take it as a recorded event, and the
verifier refuses to call a transition deterministic-for-replay if it can reach a
nondeterministic builtin. That is the difference the demonstration exists to
show.

It does not demonstrate first-pass correctness — whether the ledger is the
program its author intended — nor execution cost. The recorded timestamps are
the same-implementation, same-process determinism the Q-065 audit establishes;
cross-implementation bit-equality is a separate property that falls due with the
Rust VM (Q-017 step 2) and the conformance corpus. The ledger program is
hand-authored precisely so the demonstration measures the runtime's replay
property in isolation from the agent-generation question.

It is a demonstration, not a proof. The replay, time-travel, and restart-resume
properties are argued from the mechanisms — purity, effect edges, the
determinism guard, the snapshot codec — with these executed scenarios as
spot-checks driving the mechanisms through the embedding surface a host would
actually use.

## References

**Outgoing references:**
- [`proposals/implemented/long-running-groups.md`](../../proposals/implemented/long-running-groups.md)
  — Q-059, the `ValueCodec` / `SnapshotCodec` / facade `writeSnapshot` /
  `readSnapshot` / `resume` this demonstration's R3 packages, and the
  `SnapshotPersistenceTest` restart-resume technique it reuses.
- [`proposals/implemented/determinism-enforcement.md`](../../proposals/implemented/determinism-enforcement.md)
  — Q-065, the soundness basis: transition closures reference only deterministic
  builtins, and the `NondeterministicInReplayContext` guard the demonstration
  confirms does not fire for the ledger.
- [`proposals/implemented/actor-runtime-stream-bridge.md`](../../proposals/implemented/actor-runtime-stream-bridge.md)
  — Q-046, the "replay records at the consumer, zero live IO per-instance
  replay" property this demonstration's R1 exercises in its synchronous form.
- [`proposals/implemented/state-machines-runtime-step-2.md`](../../proposals/implemented/state-machines-runtime-step-2.md)
  — the `EventRecorder` and the `runMachine` replay-determinism seam R2 reads.
- [`proposals/implemented/state-machines-runtime-step-3.md`](../../proposals/implemented/state-machines-runtime-step-3.md)
  — slice 3.3 `Snapshot` and the `StateMachineRuntime.resume` integrity check R3
  depends on.
- [`design/state-machines.md`](../../design/state-machines.md) — the conceptual
  model: the trajectory is the fixpoint of the transition over the event
  history, deterministic when the transition performs no nondeterministic
  effects.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade and `HostPolicy` (the injectable clock R1
  uses to source and then forbid live timestamps) the driver is built on.

**Incoming references:**
- [`demos/README.md`](../README.md) — index entry.
- [`INDEX.md`](../../INDEX.md) — changelog entry (2026-06-13).
