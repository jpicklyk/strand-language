# Layer 6 step 3: Backpressure, Supervision, Snapshots

**Document:** `proposals/state-machines-runtime-step-3.md`
**Status:** Draft proposal (slice 3.5 implemented 2026-05-24 — see implementation note below; slices 3.1, 3.2, 3.3, 3.4, 3.6 pending)
**Date:** 2026-05-24
**Concerns:** [`design/state-machines.md`](../design/state-machines.md), [`decisions/ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md), [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) § State machine effects (E-028..E-031), [`design/distribution-model.md`](../design/distribution-model.md) § Backpressure, [`proposals/implemented/state-machines-runtime.md`](implemented/state-machines-runtime.md), [`proposals/implemented/state-machines-runtime-step-2.md`](implemented/state-machines-runtime-step-2.md), [Q-008](../open-questions.md#Q-008), [Q-010](../open-questions.md#Q-010), [Q-015](../open-questions.md#Q-015)
**Scope:** Large (multi-feature; can ship as sub-slices)

> **Implementation note (2026-05-24, slice 3.5).** The implicit `StateMachine.Send`/`Receive` verifier enforcement (§4.5, §5, slice 3.5 in §3) has landed. New file `verifier/src/main/kotlin/org/strand/verifier/WellKnownEffect.kt` houses a small internal enum (`StateMachineReceive`, `StateMachineSend`, `StateMachineSpawn`, `StateMachineTerminate`) plus a `WellKnownEffectRegistry.lookup(categoryName)` helper. `Verifier.inferStateMachine` is extended with a new step 8 after the existing closure-coverage check: it collects `EffectCategory.categoryName` strings from the machine's declared `effects`, then requires `"StateMachine.Receive"` (input streams are mandatory) and `"StateMachine.Send"` (when `outputStreams.isNotEmpty()`). Missing entries surface as a new `VerifyError.StateMachineMissingImplicitEffect(at, missing: Set<String>)` (named-set rather than NodeId-set, since the well-known check is by name, not identity). All 9 state-machine corpus programs (41-49) gained `receiveFx` and where relevant `sendFx` EffectCategory nodes; the 3 corresponding Layer A files (41, 42, 47) gained matching declarations; the `StateMachineRuntimeTest` fixtures (`TOGGLE_MACHINE`, `EMITTER_MACHINE`) and `VerifierTest.toggleMachineJson` helper gained the same. The `MachineGroupTest` fixtures don't need updating — those tests build StateMachine nodes via the store API and exercise the topology validator (`MachineGroupValidationError`) without invoking the verifier, so the slice 3.5 check isn't reached. New verifier tests: `StateMachine without Receive declared is rejected as MissingImplicitEffect (slice 3-5)` and `StateMachine with outputs but no Send declared is rejected as MissingImplicitEffect (slice 3-5)`. The five other slices (3.1 backpressure overflow policies, 3.2 supervision via E-030/E-031, 3.3 snapshot/replay, 3.4 runtime metrics, 3.6 fan-in/fan-out) remain pending. The `WellKnownEffect` mechanism shipped here is reusable: future runtime-implicit effects (e.g., snapshot capture, supervisor spawn callbacks) add an enum entry and a corresponding `inferStateMachine` check.

Layer 6 step 2 (`proposals/implemented/state-machines-runtime-step-2.md`) shipped the async multi-machine actor runtime: per-machine Kotlin coroutine actors on `Channel<Value>`, `select`-based multi-stream merge, inter-machine wiring via shared internal streams, tagged-Event and tagged-output-list shapes, per-instance event recorder, topology validation. Five things were intentionally deferred to step 3: bounded-queue overflow policies beyond block-producer; real supervision via the E-030/E-031 spawn/terminate effects; snapshot/replay-from-log for crash recovery; observable runtime metrics; implicit `StateMachine.Send`/`Receive` verifier enforcement. Step 2 also deferred multi-producer fan-in and broadcast fan-out on internal streams. This proposal closes those gaps.

The five sub-features are independently shippable in any order — the proposal is one document because they share design space (channel lifecycle, actor lifecycle, the metric and topology semantics), but the implementation can land in five sub-slices without coupling.

## 1. Problem statement

Step 2's runtime works for the canonical case — a closed graph of state machines wired through internal streams, driven over a finite event stream, with replay determinism via the recorder. It does not survive production use. Specifically:

- **Producer machines that emit faster than a consumer can drain hang the consumer forever.** Step 2 uses `Channel(capacity=1024)` with the default `BlockProducer` overflow behavior; this is correct but insufficient. A slow consumer with a steady producer creates unbounded latency from the consumer's perspective. Real workloads need policies that say "drop the oldest event when the queue is full" or "sample only every Nth event" — Q-015 § 1 names the four canonical policies and step 2 was always going to ship only the default.
- **Workers cannot be restarted when they fail.** Step 2 ships the observational supervisor pattern (corpus 48) where a supervisor watches workers via shared internal streams and observes their halt by detecting closed output channels. The supervisor cannot spawn replacement workers or terminate misbehaving ones, because E-030/E-031 (the spawn/terminate effects) are declared in `design/effects-and-capabilities.md` but no runtime interprets them.
- **Crashes lose state.** An actor that crashes mid-transition or halts on a runtime error leaves no recoverable trace beyond what the host has already drained. The replay-determinism seam shipped in step 2 (`recordedEvents(instanceId) + runMachine`) reproduces the trace from a known starting state, but the starting state itself is not persisted.
- **The runtime is opaque to observers.** There are no counters for events-received, transitions-executed, per-stream queue depth, or transition latency. Production deployments need this to detect starvation, hot machines, and pathological backpressure cascades.
- **The implicit Send/Receive effects are runtime-internal but never reach the verifier.** A user can today declare a StateMachine with input/output streams but omit `StateMachine.Send` / `StateMachine.Receive` from the machine's `effects` list; step 2's `StateMachineEffectCoverageViolation` covers only the transition Lambda's declared effects, not the runtime's implicit ones. This is a soft gap (the runtime works anyway) but a real correctness hole at the policy level.
- **Single-producer single-consumer is the only allowed internal-stream topology.** Step 2 enforces this via `MachineGroupValidationError.InternalStreamMultipleProducers` / `MultipleConsumers`. Real systems need fan-in (multiple producers feed one consumer with nondeterministic merge) and fan-out (one producer feeds many consumers with broadcast).

Each gap is a self-contained improvement. The proposal addresses all five plus the topology relaxations, and pins the shipping order: backpressure first (smallest, unlocks the eventStream-level policy field that subsequent work also uses), then supervision (the biggest substantive feature), then snapshots, then metrics, then verifier-side Send/Receive enforcement, then topology relaxations.

## 2. Prior art

- **Erlang/OTP supervision trees** ([Armstrong, 2003](https://www.erlang.org/doc/design_principles/des_princ.html); `supervisor` behavior) — the canonical reference. Restart strategies (`one_for_one`, `one_for_all`, `rest_for_one`, `simple_one_for_one`); child spec records; max-restart-intensity limits. Strand's supervisor pattern is structurally the same; the "let it crash" discipline maps to Strand's runtime-isolating each actor's failures (already in step 2).
- **Akka Persistence** ([Akka docs § Persistence](https://doc.akka.io/docs/akka/current/typed/persistence.html)) — event sourcing for actors. State is reconstructed by replaying events from a journal; snapshots short-circuit replay. Strand's content-addressed model makes snapshots naturally hash-keyed; the journal can be a Merkle log of event hashes.
- **Reactive Streams overflow policies** ([Lightbend Reactive Streams spec § 3.5–3.7](https://www.reactive-streams.org/)) — `DROP_NEW`, `DROP_OLD`, `DROP_BUFFER`, `LATEST`, `THROW`. Q-015 maps to a four-element subset (BlockProducer, DropNewest, DropOldest, Sample) chosen for legibility.
- **kotlinx-coroutines `BroadcastChannel` / `SharedFlow`** ([kotlinx-coroutines docs](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-broadcast-channel/)) — the deprecated `BroadcastChannel` and its successor `SharedFlow` provide broadcast semantics for fan-out. Strand's fan-out implementation will use `SharedFlow` (the modern replacement) for one-producer-many-consumers; fan-in uses a `select` over multiple `ReceiveChannel`s, mirroring step 2's existing multi-input merge.
- **Prometheus / OpenTelemetry counters and gauges** — the reference shape for runtime metrics: per-instance counters, per-stream gauges, per-transition histograms. Strand exposes a minimal `RuntimeMetrics` snapshot pulled by the host; no embedded export protocol.
- **EventStore / Kafka log compaction** — content-addressed event logs with explicit snapshot markers. Strand's snapshot format is structurally the same: hash of `(instance state, recorder-event-list)` produces a Merkle-DAG node that replay re-instantiates against.

## 3. Recommended approach

A six-slice shipping plan, each slice independently mergeable:

**Slice 3.1 — Bounded queues with overflow policies (Q-015).** Add `bufferSize: Int?` and `overflowPolicy: enum?` content fields to `Node.EventStream` (canonical encoding extends correspondingly). The four policies are `BlockProducer` (the step-2 default), `DropNewest`, `DropOldest`, `Sample`. `MachineGroup` reads the per-stream policy at channel-allocation time and wires the actor's send-loop accordingly.

**Slice 3.2 — Supervision via E-030/E-031.** Make `StateMachine.Spawn` and `StateMachine.Terminate` runtime-interceptable effects: when an actor invokes a `ForeignNode` whose effect closure contains E-030 (Spawn) or E-031 (Terminate), the runtime captures the call and applies the side effect on the actor set (spawn a new `MachineInstance` for the requested StateMachine NodeId; cancel the actor for the requested InstanceId). Supervisor patterns are still state machines; restart policies (`OneForOne`, `OneForAll`, `RestForOne`) are transition-function logic the supervisor implements over child-halt notification streams. Ship one corpus program per restart policy.

**Slice 3.3 — Snapshot and replay-from-log.** A snapshot is a content-addressed `(snapshotState: Value, snapshotEventCursor: Long, recorderTail: List<Value>)` triple. `MachineGroupHandle.snapshot(instance)` returns a `Snapshot` whose hash is the recovery point. `StateMachineRuntime.resume(machine, snapshot, additionalEvents)` rebuilds the per-instance state from the snapshot and continues. The recorder already captures the event sequence the actor consumed; the cursor records how many events were processed pre-snapshot.

**Slice 3.4 — Runtime metrics.** A `RuntimeMetrics` snapshot is exposed via `MachineGroupHandle.metrics()`. Per-instance counters: events-received, transitions-executed, current-state-hash-digest, last-transition-latency-nanos. Per-stream gauges: queue-depth, send-failures-due-to-overflow, oldest-pending-event-age-nanos. Per-group totals derived by summing. No export protocol; the host scrapes and exports via its own metrics infrastructure (Prometheus, OpenTelemetry, structured logging).

**Slice 3.5 — Implicit StateMachine.Send/Receive verifier enforcement.** Introduce a small well-known-EffectCategory registry in the verifier: a map from `categoryName` (the structural identifier on `Node.EffectCategory`) to a `WellKnownEffect` enum value. When the verifier sees a StateMachine declaration, it requires the machine's `effects` list to include EffectCategory nodes whose `categoryName` matches `StateMachine.Send` (if any output streams declared) and `StateMachine.Receive` (always — input streams are mandatory). This is the existing `StateMachineEffectCoverageViolation` check, extended.

**Slice 3.6 — Fan-in and fan-out on internal streams.** Relax `MachineGroupValidationError.InternalStreamMultipleProducers` / `MultipleConsumers`. Multi-producer fan-in works by adding the producers' sends to the same `Channel`; nondeterministic merge falls out of the channel's FIFO semantics across senders. Broadcast fan-out wraps the channel in a `SharedFlow` and each consumer subscribes; emitted events go to all subscribers. The current single-consumer model is preserved as the default (broadcast is opt-in via a new EventStream content field `consumerMode: Single | Broadcast`).

The slices share design space (channel lifecycle, EventStream content fields, actor lifecycle, the metric surface) but no implementation coupling; the order above reflects logical dependency only.

## 4. Detailed mechanism

### 4.1 Backpressure overflow policies (slice 3.1)

`Node.EventStream`'s canonical edges grow to accommodate two optional content fields:

| Field | Multiplicity | Type | Default | Role |
|-------|--------------|------|---------|------|
| `bufferSize` | 0..1 (content) | Int | 1024 | Channel capacity for this stream. |
| `overflowPolicy` | 0..1 (content) | Enum | `BlockProducer` | `BlockProducer \| DropNewest \| DropOldest \| Sample(n)`. |

Canonical encoding: when both fields equal their defaults, they are omitted from the encoded bytes (so pre-step-3 EventStream hashes are unchanged — backward compatible per the additive-versioning rule). When set, they are encoded after `streamKind` in declaration order. `Sample(n)` requires an Int parameter; the encoding is a small tag (0=Block, 1=DropNew, 2=DropOld, 3=Sample) followed by the Int parameter when applicable.

Runtime semantics per policy, per send attempt to a full channel:
- `BlockProducer` (default) — suspend until capacity is available; matches step 2's behavior exactly. Producer's latency is bounded by consumer's processing rate.
- `DropNewest` — discard the incoming event; the channel state is unchanged; producer's `send` returns immediately. The runtime records a metric tick (`OverflowDrop` counter).
- `DropOldest` — discard the oldest queued event, then enqueue the new one. Producer's `send` returns immediately. Metric tick records both the drop and the enqueue.
- `Sample(n)` — drop incoming events that arrive less than `n` nanoseconds after the previous accepted event; otherwise enqueue (blocking if necessary). Coarse rate-limiting at the producer side.

The `MachineActor` send path consults the stream's policy at every dispatch. Implementation: an enum `OverflowDispatcher` per output channel, allocated at group startup from the EventStream node's content fields.

### 4.2 Supervision via E-030/E-031 (slice 3.2)

`StateMachine.Spawn` (E-030) and `StateMachine.Terminate` (E-031) are runtime-interceptable effects: a `ForeignNode` declaring these effects, when called inside an actor's transition function, is captured by the runtime instead of being dispatched normally. The capture is a runtime mechanism, not a user-callable foreign target — the side effect is on the `MachineGroup`'s actor set, not on a value.

Capture protocol:
- A user-authored ForeignNode with target `strand-runtime:StateMachine.Spawn` and `foreignType = (StateMachine, EventStream) -> InstanceId` (or similar) signals "spawn a new instance of the supplied StateMachine NodeId, with the supplied external input stream wired". The runtime allocates a fresh `InstanceId`, builds a `MachineInstance` against the supplied stream channel, spawns a coroutine actor, and returns the new InstanceId as the call's result value.
- `strand-runtime:StateMachine.Terminate` takes an InstanceId argument; the runtime cancels the actor's coroutine and closes its output channels. Returns Unit.
- Both are dispatched through the existing `Interpreter.applyCallable` path; the runtime hook is a special case in the actor's call dispatch that checks the `ForeignNode.target` prefix.

Supervisor restart policies are then transition-function logic over the events the supervisor receives:

- **OneForOne** — when a child halts, restart only that child. The supervisor's input stream carries child-halt notifications (case `ChildHalted(InstanceId, Reason)` in a sum type); the transition matches the case, emits a `StateMachine.Spawn(<sameStateMachineId>, <sameInputStream>)` call as a side effect of the body Lambda, and updates the state to record the new InstanceId.
- **OneForAll** — when any child halts, restart every child. The transition emits `Terminate(id)` for each surviving child, then `Spawn` for each. Bulk operation.
- **RestForOne** — when a child halts, restart that child plus every child started after it (in declaration order). The supervisor's state tracks the spawn order.

Corpus programs: one per policy, each named `<n>-supervisor-<policy>.json` with a companion routed-events file driving worker failures.

### 4.3 Snapshot and replay-from-log (slice 3.3)

A `Snapshot` is content-addressed:

```kotlin
data class Snapshot(
    val machineHash: Hash,                          // the StateMachine NodeId's canonical hash
    val snapshotState: Value,                       // the actor's state at snapshot time
    val processedEventCount: Long,                  // # of events consumed pre-snapshot
    val recorderHash: Hash,                         // hash of the captured event list pre-snapshot
)
```

The snapshot's own hash is `BLAKE3(canonical(Snapshot))` and serves as the recovery point. The host persists snapshots out-of-band (e.g., to disk, S3, content-addressed blob storage); the runtime is agnostic about storage.

`MachineGroupHandle.snapshot(instance: InstanceId): Snapshot` pauses the actor briefly, captures the current state + processed-event count + recorder hash, resumes the actor, returns the snapshot.

`StateMachineRuntime.resume(machineId: NodeId, snapshot: Snapshot, additionalEvents: List<Value>): Trace` rebuilds the synchronous-fold instance using `snapshotState` as the initial state and replays `additionalEvents`. The result is a `Trace` covering only the post-snapshot events; combining with the pre-snapshot trace (recoverable from the recorder hash if archived) reconstructs the full history.

Replay determinism extends: `runMachine(machineId, snapshot.snapshotState, recordedEvents.drop(snapshot.processedEventCount).toList())` produces the same per-step trace as the live actor saw post-snapshot. This is the property `design/state-machines.md` § Conceptual model commits to.

### 4.4 Multi-producer fan-in / broadcast fan-out (slice 3.6)

Fan-in: multiple producer machines list the same internal EventStream in their `outputStreams`. Step 2's `InternalStreamMultipleProducers` rule is relaxed by default. The runtime allocates one `Channel<Value>` per stream; all producers `send` into the same channel; the consumer's `select` over its input channels sees a single combined stream with nondeterministic interleaving. Per-stream FIFO holds within each producer's emissions; across producers, the merge is nondeterministic (Q-009 default).

Broadcast fan-out: a new EventStream content field `consumerMode: Single | Broadcast` (default Single). When `Broadcast`, the runtime wraps the stream in a `SharedFlow<Value>` instead of a `Channel<Value>`; each consumer machine launches its own collection coroutine subscribed to the SharedFlow. Backpressure semantics under broadcast are policy-dependent: with `BlockProducer`, the slowest consumer dictates the producer's pace (`SharedFlow` with `replay=0` and a per-consumer buffer); with `DropOldest`/`DropNewest`, individual consumers drop independently.

Canonical encoding: `consumerMode` is omitted from the canonical bytes when equal to the default `Single` (additive versioning).

### 4.5 Implicit Send/Receive verifier enforcement (slice 3.5)

The verifier gains a small fixed registry of well-known EffectCategory names:

```kotlin
internal enum class WellKnownEffect(val categoryName: String) {
    StateMachineSend("StateMachine.Send"),
    StateMachineReceive("StateMachine.Receive"),
    StateMachineSpawn("StateMachine.Spawn"),
    StateMachineTerminate("StateMachine.Terminate"),
}
```

`inferStateMachine` is extended: the StateMachine's `effects` list must contain EffectCategory nodes whose `categoryName` equals `StateMachine.Receive` (input streams are mandatory) and `StateMachine.Send` (if `outputStreams.isNotEmpty()`). Missing well-known effects surface as `StateMachineMissingImplicitEffect(at, missing)` rather than the generic `StateMachineEffectCoverageViolation`.

The registry is verifier-internal; user code never references the enum. EffectCategory NodeId identity is still the runtime check; the registry is only the "do these required categories exist by name?" verifier-side check.

This is the cleanest mechanism for "the runtime implicitly performs an effect that the user must acknowledge in the machine's surface declaration." Step 3 introduces the registration mechanism; future verifier-side semantic checks (e.g., a registry of well-known Schema names, or well-known builtin targets) can reuse it.

### 4.6 Observable metrics (slice 3.4)

`RuntimeMetrics` is a snapshot data class:

```kotlin
data class RuntimeMetrics(
    val perInstance: Map<InstanceId, InstanceMetrics>,
    val perStream: Map<NodeId, StreamMetrics>,
)

data class InstanceMetrics(
    val eventsReceived: Long,
    val transitionsExecuted: Long,
    val currentStateHash: Hash,  // BLAKE3 of canonical(currentState)
    val lastTransitionLatencyNanos: Long,
    val halted: Boolean,
)

data class StreamMetrics(
    val queueDepth: Int,
    val overflowDrops: Long,
    val oldestPendingEventAgeNanos: Long,
)
```

`MachineGroupHandle.metrics()` returns a fresh snapshot. The host polls at its own cadence; no embedded export. Tests assert on metrics directly. The runtime updates counters under a `kotlinx.atomicfu` cell per counter (atomic, lock-free).

## 5. Verifier rules

### Slice 3.5 (Implicit Send/Receive)
- **`StateMachineMissingImplicitEffect(at, missing)`** — at least one of `StateMachine.Receive` / `StateMachine.Send` is not present in the machine's `effects` list by `categoryName`.

### Slice 3.1 (Overflow policies — well-formedness only)
- **`MalformedOverflowPolicy(at, detail)`** — an EventStream whose `overflowPolicy` field is malformed (e.g., `Sample` with no or negative `n`).

### Slice 3.6 (Fan-in / fan-out — relaxations + new rules)
- The step-2 `InternalStreamMultipleProducers` and `InternalStreamMultipleConsumers` errors are removed from the topology check.
- **`BroadcastStreamWithBlockProducerWarning(at)`** — a non-fatal diagnostic: a broadcast stream with `BlockProducer` policy and N consumers exhibits the slowest-consumer-dominates pattern; production deployments usually want explicit per-consumer drop policies. Diagnostic only.

### Slice 3.2 (Supervision)
- **`SpawnTargetMustBeStateMachineHash(at)`** — a `Spawn` call whose first argument's resolved value at runtime is not the canonical hash of a verified StateMachine node. (Runtime check, not verifier.)
- **`TerminateTargetMustBeKnownInstance(at)`** — a `Terminate` call whose InstanceId argument is not one the current actor has previously spawned. (Runtime; prevents arbitrary process termination.)

No new verifier rules for snapshots or metrics — both are runtime-only surfaces.

## 6. Runtime semantics

### 6.1 Per-stream overflow

Each `Channel<Value>` in `MachineGroup.streamChannels` is wrapped in an `OverflowDispatcher` that holds the policy. `MachineActor.dispatchOutputBatch` / `dispatchTaggedList` calls `dispatcher.send(payload)` instead of `channel.send(payload)`. The dispatcher implements:

```kotlin
internal class OverflowDispatcher(
    val channel: Channel<Value>,
    val policy: OverflowPolicy,
    val metrics: StreamMetricsCounter,
) {
    suspend fun send(value: Value) {
        when (policy) {
            BlockProducer -> channel.send(value)
            DropNewest -> if (channel.trySend(value).isFailure) metrics.recordDrop()
            DropOldest -> sendWithReplace(value)  // try to drain one, then send
            is Sample -> sampleWindow.acceptOrSkip(value)
        }
    }
}
```

### 6.2 Supervisor capture

The actor's `applyTransition` returns a `(newState, outputs)`. Before dispatching `outputs`, the runtime scans the transition's *effect closure* for E-030 / E-031 references and applies the captured semantics. Concretely, the captured calls are recorded as "intent records" returned alongside the outputs; the actor's outer loop sees the intent records and applies them to the `MachineGroup`'s actor set after the transition completes (so the actor itself stays purely functional).

Spawn intent: allocate a `MachineInstance`, hook it into existing channels, launch the actor coroutine. Return the new InstanceId via the spawn call's result value.

Terminate intent: locate the target instance by InstanceId, cancel its coroutine, close its output channels. Return Unit.

### 6.3 Snapshot pause and resume

`snapshot(instance)` does NOT actually pause the actor — it instead captures a consistent point by enqueueing a "snapshot request" into the actor's control mailbox (a separate channel) and waiting for the actor to process it between transitions. The actor at the start of its loop checks the control mailbox; if a snapshot request is present, it computes the snapshot and signals completion via a `CompletableDeferred`.

Resume rebuilds a `MachineInstance` from the snapshot: `currentState = snapshot.snapshotState`, `recorder` initialized to the snapshot's recorder-tail; actor coroutine relaunched. The host then feeds `additionalEvents` through normal channels.

### 6.4 Broadcast fan-out

When `consumerMode = Broadcast`, the runtime replaces the channel with a `MutableSharedFlow<Value>` (kotlinx-coroutines' replacement for `BroadcastChannel`). Each consumer machine's actor subscribes via `sharedFlow.collect { ... }` inside its input-receive loop instead of `select` on a channel. Producers `emit` into the SharedFlow; with `replay = 0`, only currently-subscribed consumers see each event.

Backpressure under broadcast: `MutableSharedFlow(extraBufferCapacity = streamBufferSize)` allows the producer to enqueue up to `streamBufferSize` events without blocking on the slowest consumer. Beyond that, the producer's `emit` suspends until the slowest consumer drains. Per-consumer `DropOldest`/`DropNewest` policies map onto `MutableSharedFlow.tryEmit` with explicit overflow handling.

## 7. Test scenarios

1. **DropNewest under capacity** — producer emits 100 events to a stream with capacity 4 and `DropNewest`; slow consumer (1 event/tick) sees 4 events; 96 drops recorded in metrics.
2. **DropOldest replaces oldest** — same setup with `DropOldest`; slow consumer sees the last 4 events of the 100; 96 drops recorded.
3. **BlockProducer matches step 2 behavior** — regression test; the default policy must produce trace-equal output to step 2.
4. **Sample(N) rate-limits** — producer at 100 events/sec to a Sample(10ms) stream; consumer sees ~10 events/sec.
5. **OneForOne restart** — supervisor watches 3 workers; one worker halts on an injected `NoMatchingCase`; supervisor's transition fires Spawn, new worker takes its place; supervisor's state records the restart count.
6. **OneForAll restart** — supervisor watches 3 workers; one halts; supervisor terminates the other 2 and respawns all 3. Workers' new InstanceIds are different from originals; the workers' shared "session id" state should reset.
7. **RestForOne restart** — supervisor watches workers spawned in order A, B, C. B halts; supervisor terminates C, respawns B and C; A is untouched.
8. **Snapshot before halfway, resume, replay matches** — drive a counter machine for 1000 events; snapshot after event 500; resume from snapshot with the remaining 500 events; assert the final state matches a non-snapshot run.
9. **Snapshot hash is deterministic** — same machine, same first 500 events, two separate runs; the resulting snapshot hashes are equal.
10. **Metrics increment correctly** — drive a multi-machine group for N events; assert `perInstance[id].eventsReceived` sums to N; assert `perStream[s].queueDepth` is bounded by the stream's capacity at all observation points.
11. **Multi-producer fan-in preserves per-producer FIFO** — two producers emit interleaved into a shared stream; consumer asserts per-producer FIFO holds (event N+1 from producer X always arrives after event N from producer X).
12. **Broadcast delivers to all consumers** — one producer, three consumers subscribed to a broadcast stream; each consumer sees every emitted event.
13. **Verifier rejects StateMachine missing Receive** — a machine with input streams but no `StateMachine.Receive` in its effects list is rejected with `StateMachineMissingImplicitEffect`.
14. **Verifier rejects StateMachine with outputs missing Send** — analogous for output streams.
15. **Spawn returns a fresh InstanceId** — supervisor calls Spawn twice on the same StateMachine NodeId; receives two distinct InstanceIds.
16. **Terminate of unknown InstanceId is a runtime error** — supervisor calls Terminate on an instance it never spawned; gets `TerminateTargetMustBeKnownInstance`.
17. **Crash mid-transition is recoverable from snapshot** — actor crashes on event 600; supervisor watches the halt; replay from snapshot (taken at event 500) + the 100 events between snapshot and crash reproduces the pre-crash state.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Hot upgrade (Q-010).** The proposal preserves the seam (the `MachineGroupHandle` exposes the instance set; an upgrade could swap a transition-function reference on a live instance) but does not implement upgrade orchestration. Hot upgrade requires either a quiescence protocol or a code-replacement-during-transition mechanism that interacts with snapshots in non-trivial ways. Deferred to a separate question.
- **Distributed execution.** All actors run on one JVM. Multi-node deployment requires a wire format for events crossing process boundaries, a discovery protocol, and a placement scheduler (per `design/distribution-model.md`). Separate milestone (Milestone 2.5+).
- **Persistent snapshot format spec.** This proposal defines the in-memory `Snapshot` data class but does not pin a wire format (CBOR-vs-protobuf, length-prefix vs framing, etc.). The host is responsible for serializing/deserializing snapshots through its own storage layer. A wire format proposal would be a separate slice.
- **Per-actor backpressure (vs per-stream).** Overflow policies are per-stream. A future extension might allow per-actor "max in-flight transitions" backpressure, but it interacts with the transition-is-atomic invariant and is non-trivial. Out of scope.
- **More restart strategies.** The proposal ships OneForOne, OneForAll, RestForOne. Erlang/OTP also has `simple_one_for_one` (dynamic child sets where the supervisor doesn't know the children upfront); Strand can express this as a transition-function pattern over the spawn-Reply event sum, but no corpus program exercises it in this slice.
- **Real foreign sandboxing for Spawn/Terminate.** E-030/E-031 are runtime-internal in this slice (the runtime intercepts the ForeignNode call). Future work might allow user-defined foreign supervisors that implement custom spawn/terminate semantics (e.g., spawning Wasm sandboxes); the trust model from `design/security-model.md` would gate this.
- **Verifier-side topology constraints for fan-out.** A broadcast stream with `BlockProducer` and N consumers is a known footgun (slowest consumer dominates); the proposal ships this as a diagnostic warning, not a hard reject. A future strictness mode could make it a verifier error.

**Real research questions:**

- **OQ-S3-a: Snapshot-during-transition consistency.** The proposed snapshot mechanism captures between transitions (the actor checks the control mailbox at loop top). What if a transition takes hours (long-running batch transition)? The host blocks on the snapshot CompletableDeferred for hours. An alternative is mid-transition snapshot via the verifier's "transitions are pure" guarantee — but pure doesn't mean cheap. Need empirical data on transition-time distributions.
- **OQ-S3-b: Restart-policy expressibility ceiling.** OneForOne, OneForAll, RestForOne are well-defined. Can every restart strategy be expressed as a supervisor's transition function? Or do some (e.g., probabilistic restart, restart-with-state-migration) need first-class language support? The corpus capstone for slice 3.2 will inform.
- **OQ-S3-c: Sample(N) clock source.** Sample by wall-clock or by event-count? Wall-clock is more useful operationally; event-count is deterministic for replay. The proposal pins wall-clock with `n` as nanoseconds, which breaks replay determinism for Sample streams — a Sample stream should not appear on a code path the host wants to replay deterministically. Convention to be documented.
- **OQ-S3-d: Broadcast consumer late-arrival.** With `replay = 0`, a consumer that subscribes after the producer has emitted N events sees only events from subscription onward. Is this the desired semantics? For most use cases yes (event-driven late subscribers don't replay history); for stateful consumers that need to "catch up", a `replay = bufferSize` variant might be needed. Out of scope.
- **OQ-S3-e: Metric scrape coherence.** `metrics()` returns a snapshot. Are per-instance and per-stream snapshots taken atomically with each other? The simple implementation reads each atomically but the cross-counter view is not consistent (a metric for a producer's drop count and the consumer's receive count might disagree by 1 if read across the boundary). Probably fine for monitoring purposes; might matter for invariant testing.

## 9. Implementation sketch

| File | Change | Slice | Size |
|------|--------|-------|------|
| `core/src/main/kotlin/org/strand/core/Node.kt` | Add optional `bufferSize` and `overflowPolicy` content fields to `Node.EventStream`; add optional `consumerMode` | 3.1, 3.6 | Small |
| `core/src/main/kotlin/org/strand/core/Json.kt` | Extend `EventStream` ingest to accept the new optional fields | 3.1, 3.6 | Small |
| `hashing/src/main/kotlin/org/strand/hashing/CanonicalEncoder.kt` | Extend `encodeEventStream` to gate the new fields on non-default values (additive versioning) | 3.1, 3.6 | Small |
| `runtime/src/main/kotlin/org/strand/runtime/OverflowDispatcher.kt` | NEW — per-policy dispatcher wrapping `Channel<Value>` | 3.1 | Small-Medium |
| `runtime/src/main/kotlin/org/strand/runtime/MachineActor.kt` | Replace `channel.send` with `dispatcher.send`; thread metrics counters | 3.1 | Small |
| `runtime/src/main/kotlin/org/strand/runtime/MachineGroup.kt` | Allocate OverflowDispatchers per stream at startup; relax fan-in/fan-out validation; add broadcast SharedFlow path | 3.1, 3.6 | Medium |
| `runtime/src/main/kotlin/org/strand/runtime/Supervision.kt` | NEW — Spawn/Terminate intent capture and apply; runtime intent dispatcher; restart-policy helper sums | 3.2 | Medium-Large |
| `runtime/src/main/kotlin/org/strand/runtime/Snapshot.kt` | NEW — `Snapshot` data class, snapshot capture protocol, resume entry point | 3.3 | Medium |
| `runtime/src/main/kotlin/org/strand/runtime/RuntimeMetrics.kt` | NEW — metrics data classes; per-instance and per-stream counter cells; snapshot accessor | 3.4 | Small-Medium |
| `runtime/src/main/kotlin/org/strand/runtime/StateMachineRuntime.kt` | Expose `runGroupWithMetrics`, `resume(machine, snapshot, additionalEvents)`, `MachineGroupHandle.snapshot(instance)` | 3.3, 3.4 | Small |
| `interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt` | Register `strand-runtime:StateMachine.Spawn` and `Terminate` as builtin EffectCategory targets (runtime-only, not user-callable directly) | 3.2 | Small |
| `verifier/src/main/kotlin/org/strand/verifier/WellKnownEffect.kt` | NEW — registry enum for well-known EffectCategory names | 3.5 | Trivial |
| `verifier/src/main/kotlin/org/strand/verifier/Verifier.kt` | Extend `inferStateMachine` with WellKnownEffect check; report `StateMachineMissingImplicitEffect` | 3.5 | Small |
| `verifier/src/main/kotlin/org/strand/verifier/VerifyError.kt` | Add `StateMachineMissingImplicitEffect`, `MalformedOverflowPolicy` | 3.5, 3.1 | Small |
| `cli/src/main/kotlin/org/strand/cli/Main.kt` | Optional `--metrics` flag for `strand group`; print metrics after run | 3.4 | Small |
| `corpus/src/main/resources/corpus/57-dropoldest-overflow.json` | Slice 3.1 capstone | 3.1 | Small |
| `corpus/src/main/resources/corpus/58-supervisor-one-for-one-restart.json` | Slice 3.2 capstone (replaces / extends 48) | 3.2 | Medium |
| `corpus/src/main/resources/corpus/59-supervisor-one-for-all-restart.json` | Slice 3.2 capstone | 3.2 | Medium |
| `corpus/src/main/resources/corpus/60-snapshot-resume.json` | Slice 3.3 capstone | 3.3 | Small |
| `corpus/src/main/resources/corpus/61-broadcast-fanout.json` | Slice 3.6 capstone | 3.6 | Small |
| `runtime/src/test/kotlin/org/strand/runtime/OverflowDispatcherTest.kt` | NEW | 3.1 | Medium |
| `runtime/src/test/kotlin/org/strand/runtime/SupervisionTest.kt` | NEW | 3.2 | Medium |
| `runtime/src/test/kotlin/org/strand/runtime/SnapshotTest.kt` | NEW | 3.3 | Medium |
| `runtime/src/test/kotlin/org/strand/runtime/RuntimeMetricsTest.kt` | NEW | 3.4 | Small |
| `runtime/src/test/kotlin/org/strand/runtime/FanInOutTest.kt` | NEW | 3.6 | Medium |
| `verifier/src/test/kotlin/org/strand/verifier/VerifierTest.kt` | Add cases for `StateMachineMissingImplicitEffect`, `MalformedOverflowPolicy` | 3.5, 3.1 | Small |
| `corpus/src/test/kotlin/org/strand/corpus/AsyncCorpusTest.kt` | Register new corpus programs | All | Small |
| `impl/CLAUDE.md` | Layer 6 step 3 status; new state-machines mechanism notes | All | Small |

**Order of work.** The slices can ship in any order, but practical sequencing:

1. **Slice 3.5 (Implicit Send/Receive verifier check)** — smallest, no runtime change, immediate verifier-side correctness improvement. Lands first; this means corpus 41-49 may need to update their `effects` lists to include the well-known categories, which surfaces the existing-corpus impact early.
2. **Slice 3.1 (Backpressure overflow policies)** — second smallest. Adds the EventStream content fields and the dispatcher; preserves block-producer default so existing corpus 41-49 keep working. Sets up the per-stream configurability that slices 3.6 and 3.2 also use.
3. **Slice 3.6 (Fan-in / fan-out)** — relaxes step 2 topology rules and adds broadcast support. Independent of supervision.
4. **Slice 3.2 (Supervision)** — the biggest substantive feature. Builds on the runtime intent-dispatch machinery; the restart policies are corpus-level state-machine patterns rather than runtime features.
5. **Slice 3.3 (Snapshot/replay)** — depends on snapshot-capture coordination with the actor loop; slots in after supervision because a supervisor pattern provides the canonical "long-lived actor we want to snapshot" workload.
6. **Slice 3.4 (Metrics)** — last because the metrics' value is highest when there are real workloads to measure. Implementation can land any time.

**Not in this slice.** Hot upgrade (Q-010); distributed multi-node execution; a persistent snapshot wire-format spec; foreign-sandboxed supervisors; per-actor backpressure; restart strategies beyond the canonical three.

## References

**Outgoing references:**
- [`design/state-machines.md`](../design/state-machines.md) — backpressure, supervisor, snapshot semantics specified there
- [`decisions/ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md) — supervisor is a state-machine pattern, not a new node category
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — § State machine effects (E-028 through E-031)
- [`design/distribution-model.md`](../design/distribution-model.md) — § Backpressure
- [`proposals/implemented/state-machines-runtime.md`](implemented/state-machines-runtime.md) — step 1 (synchronous trace runtime)
- [`proposals/implemented/state-machines-runtime-step-2.md`](implemented/state-machines-runtime-step-2.md) — step 2 (async multi-machine actor runtime) — the previous step in the sequence
- [`open-questions.md`](../open-questions.md) — Q-008 (high-throughput engineering), Q-010 (hot upgrade, deferred), Q-015 (backpressure semantics)

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-008, Q-015 point at this proposal when registered
- [`proposals/README.md`](README.md)
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — Known gaps section
