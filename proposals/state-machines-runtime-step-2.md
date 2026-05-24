# Layer 6 step 2: Async Multi-Machine Actor Runtime

**Document:** `proposals/state-machines-runtime-step-2.md`
**Status:** Draft proposal
**Date:** 2026-05-24
**Concerns:** [`design/state-machines.md`](../design/state-machines.md), [`decisions/ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md), [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) § State machine effects (E-028..E-031), [`design/distribution-model.md`](../design/distribution-model.md), [`proposals/implemented/state-machines-runtime.md`](implemented/state-machines-runtime.md) (the step 1 proposal that sketched this), [Q-008](../open-questions.md#Q-008), [Q-009](../open-questions.md#Q-009), [Q-033](../open-questions.md#Q-033)
**Scope:** Large

Step 2 of the Layer 6 shipping strategy. Where step 1 evaluates a single state machine over a fixed event list as a deterministic synchronous fold, step 2 introduces concurrency: per-machine Kotlin coroutine actors, multiple input streams with FIFO-per-stream and nondeterministic merge, inter-machine wiring so the output of one machine drives the input of another, tagged-Event sums that preserve stream provenance, and the implicit `StateMachine.Send`/`Receive` effects (E-028, E-029) propagating through the verifier. Step 1's `runMachine(machine, events): Trace` survives unchanged as the deterministic-replay seam.

## 1. Problem statement

Layer 6 step 1 (`proposals/implemented/state-machines-runtime.md`) ships a working state-machine runtime for the single-machine, single-input-stream, finite-event-list case. The current verifier rejects multi-stream machines via `StateMachineInputStreamCountUnsupported`; the runtime has no notion of concurrency, no notion of two machines wired together, and no notion of streams that originate outside a fixed-length list.

The five corpus programs (41–45) demonstrate the single-machine vocabulary — toggle, counter, request/response, bank account — but a Strand program that wants to model a request/response service connected to a logging machine, or a supervisor watching a pool of worker machines, cannot be written today. The synchronous fold's defining constraint (a fixed `List<Value>` of events) is also the constraint that makes inter-machine communication impossible: a producer machine has no way to deliver events into a consumer machine's input list once that list is materialized.

Step 2 closes this gap. The shape of the work is constrained by three things:

1. The interpreter must stay synchronous. Per ADR-007 and the step 1 implementation note, transition functions are pure; only the actor loop around them suspends. This keeps `Interpreter.eval` and `Interpreter.applyCallable` unchanged and lets the verifier reason about transitions exactly as today.
2. Step 1's `runMachine(machine, events): Trace` API is load-bearing for replay determinism and test infrastructure. It must survive as a separate, deterministic entry point. The async runtime is a different entry point with a different result type.
3. The implementation must not foreclose step 3 (backpressure with overflow policies, supervisor restart policies, snapshot/replay-from-log). Step 2's channel sizes, lifecycle hooks, and error propagation paths are designed so step 3 can extend without rework.

## 2. Prior art

The reference architecture is BEAM, confirmed by ADR-007 § Decision and `design/state-machines.md` § High-throughput. Five points of comparison shape this proposal:

- **Erlang/OTP `gen_statem`** — the canonical state-machine actor, with a `handle_event(EventType, Event, State, Data)` callback. Per-process mailbox with selective receive. Supervision trees as the failure-recovery mechanism. The "let it crash" discipline confines failures to one instance.
- **Akka Typed** — actors as typed message recipients on a `Behavior<T>`. The `receive` block is the per-message callback; child supervision is via behaviors that wrap their children. JVM-native; coroutine integration via `actor { }` builders.
- **kotlinx-coroutines `select`** — Kotlin's primitive for nondeterministic multi-channel receive: `select { channel1.onReceive { ... }; channel2.onReceive { ... } }`. Picks any ready channel; if none ready, suspends. This is exactly the multi-stream-merge primitive Q-009 calls for as the default.
- **Reactive Streams (`Flow`, RxJava, Reactor)** — backpressure-aware streaming with bounded buffers. Provides the operational semantics step 3 will draw on for overflow policies; step 2 uses simple bounded channels (`Channel(capacity = N)`) as the precursor.
- **kotlinx-coroutines-test `runTest` + `TestDispatcher`** — virtual-time coroutine testing. Lets the runtime's tests assert eventually-quiescent traces deterministically without wall-clock dependence; critical for the multi-machine corpus.

This proposal tracks the BEAM `gen_statem` + Akka Typed shape, restricted to what one machine + a few canonical compositions need, and built on Kotlin coroutines + `Channel` + `select`.

## 3. Recommended approach

**Per-machine coroutine actor with `Channel<Value>` queues.** Each `MachineInstance` runs in its own `launch { }` coroutine on a shared `CoroutineDispatcher` (default: `Dispatchers.Default` for CPU-bound transitions; tests inject a virtual-time dispatcher). Input streams become `Channel<Value>` of bounded capacity. The actor loop is:

```kotlin
while (!instance.halted) {
    val tagged = receiveNext(instance.inputChannels)   // select across streams
    val step = stepOnce(instance, tagged)              // synchronous; reuses step 1 logic
    emit(step.outputs, instance.outputChannels)
}
```

`stepOnce` is unchanged from step 1 — same `Interpreter.applyCallable` call, same OutputBatch decomposition, same closure cache. The only change is that emitted outputs go into outbound channels rather than into a `MutableList<Value>`.

**Multi-stream merge: `select` for FIFO-per-stream + nondeterministic merge (Q-009 default).** When `inputStreams.size > 1`, `receiveNext` builds a `select { }` block with one `onReceive` clause per channel. Kotlin's `select` semantics guarantee: per-channel FIFO (channels are themselves FIFO), nondeterministic interleaving across ready channels (select picks any one), suspends until at least one is ready. This is verbatim what Q-009 specifies as the default policy. Priority, causal, and timestamp merges remain step-3-or-later opt-in declarations.

**Tagged-Event sums for stream provenance.** When a machine declares multiple input streams `[S₁, S₂, ..., Sₙ]` whose `eventType`s are `[T₁, T₂, ..., Tₙ]`, the transition function's `Event` parameter type is a **runtime-synthesized sum** `InputEvent = stream_0(T₁) | stream_1(T₂) | ... | stream_{n-1}(Tₙ)`. The runtime wraps each delivered event as `SumValue(InputEvent, "stream_i", payload)` where `i` is the index of the input stream in the machine's declaration. The user's transition function destructures via Match against the sum cases. Case names are positional (`stream_0`, `stream_1`, ...) by deliberate choice — the position is the structural identity, matching the OutputBatch convention from step 1.

**OutputBatch → recursive list `(State, List<TaggedOutput>)`.** With recursive types landed (N-041/N-042) and step 1's fixed-arity workaround in production, step 2 replaces OutputBatch with a recursive sum-of-products `List<TaggedOutput>` where `TaggedOutput = output_0(T₁) | output_1(T₂) | ... | output_{n-1}(Tₙ)`. A transition function may emit zero, one, or many outputs per event, and outputs may target any of the declared output streams in any order. The runtime walks the list at each step and dispatches each `TaggedOutput` to the corresponding outbound channel. The empty list is `Nil`; the convention "every step emits zero or more outputs" replaces step 1's "every step emits at most one per output slot" — strictly more expressive.

**Inter-machine wiring via shared EventStream NodeIds.** A program that runs multiple machines declares them as a `MachineGroup` (a new top-level container; see §4.3). When EventStream node E is declared as `streamKind: internal` and is referenced by machine A's `outputStreams` AND machine B's `inputStreams`, the runtime wires them: A's outbound channel for E is the same `Channel<Value>` as B's inbound channel for E. The wiring is computed once at runtime startup from the topology; channel buffer capacity is configurable per stream (default 1024 per the step 1 backpressure-spec preview).

**E-028/E-029 enforcement.** A machine that declares output streams implicitly performs `StateMachine.Send` once per emission; a machine that declares input streams implicitly performs `StateMachine.Receive` once per consumed event. The verifier's existing `StateMachineEffectCoverageViolation` check is extended: the machine's declared `effects` must include `StateMachine.Send` if it has any output streams and `StateMachine.Receive` if it has any input streams (i.e., always, since input streams are mandatory). The transition function itself does *not* declare these — the runtime performs the send/receive at the boundary, not the user code.

**Supervisor as a state-machine pattern (no new node category).** A supervisor is a state machine whose state tracks its child instances, whose input events include child-termination notifications, and whose output events include spawn/terminate/restart commands. Step 2 ships one supervisor corpus program (a one-for-one restarter) as a canonical demonstration; the spawn/terminate effects (E-030, E-031) are wired through the verifier. Restart policies beyond one-for-one (one-for-all, rest-for-one) are step-3 corpus work.

**Step 1's `runMachine` survives.** The synchronous fold-over-events entry point is the deterministic-replay seam: tests record the actual events delivered to a machine in the async runtime, then replay them through `runMachine` to assert determinism. The async runtime exposes a `recordedEvents(): List<Value>` accessor per instance for this purpose.

## 4. Detailed mechanism

### 4.1 Coroutine actor

```kotlin
internal class MachineInstance(
    val instanceId: InstanceId,
    val node: Node.StateMachine,
    val transitionFnValue: Value,             // cached Closure (step 1)
    var currentState: Value,                  // mutated each step
    val capabilities: CapabilitySet,
    val inputChannels: Map<NodeId, Channel<Value>>,
    val outputChannels: Map<NodeId, Channel<Value>>,
    val recorder: EventRecorder?,             // null when the group disables recording
    var halted: Boolean = false,
)
```

The actor coroutine:

```kotlin
suspend fun runActor(instance: MachineInstance) {
    while (!instance.halted) {
        val tagged = receiveNextInput(instance)        // select across input channels
        recorder.record(tagged)
        val (newState, outputs) = applyTransition(instance, tagged)
        instance.currentState = newState
        for (tagged in outputs) dispatchOutput(instance, tagged)
    }
    closeOutputs(instance)
}
```

`receiveNextInput` uses `select`:

```kotlin
suspend fun receiveNextInput(instance: MachineInstance): Value {
    val channels = instance.node.inputStreams.withIndex().toList()
    return select<Value> {
        for ((i, streamId) in channels) {
            instance.inputChannels[streamId]!!.onReceive { payload ->
                Value.SumV(
                    ofType = inputEventTypeOf(instance.node),  // synthesized at startup
                    caseName = "stream_$i",
                    payload = payload,
                )
            }
        }
    }
}
```

`applyTransition` is unchanged from step 1's `stepOnce` modulo result decomposition (list, not fixed-arity batch).

### 4.2 Channel topology and inter-machine wiring

A `MachineGroup` is the unit of async execution:

```kotlin
data class MachineGroup(
    val store: NodeStore,
    val hashToNodeId: Map<Hash, NodeId>,
    val machines: List<NodeId>,                       // each a StateMachine NodeId
    val externalInputs: Map<NodeId, ReceiveChannel<Value>>, // streams with kind=external
    val externalOutputs: Map<NodeId, SendChannel<Value>>,   // streams with kind=output
    val recordInputs: Boolean = true,                 // disable for production workloads
)
```

The `recordInputs` flag toggles per-instance event recording. The default
is `true` so test workflows get replay-determinism support out of the box;
production workloads that don't need it pass `false` to avoid the unbounded
accumulation cost. Each `MachineInstance` constructed by `runGroup` carries
a non-null `EventRecorder` iff the group's flag is on; the actor loop's
`record(...)` call becomes a no-op (skipped via null-check) when off.

At `runGroup` time, the runtime walks the machines and their streams to build the wiring table:

1. For every EventStream referenced by any machine, allocate one `Channel<Value>(capacity = 1024)`. Each stream node is content-addressed, so structurally-identical streams share a channel — that's the wiring.
2. For each `streamKind = external` input stream, expose it via `externalInputs` so the host can feed events in.
3. For each `streamKind = output` output stream, expose it via `externalOutputs` so the host can read emitted events out.
4. For each `streamKind = internal` stream, the channel connects an emitting machine to a receiving machine — no host-side handle.
5. Spawn one coroutine per machine via `launch { runActor(instance) }`. Return a `MachineGroupHandle` with await/cancel semantics.

The wiring is validated by a new verifier rule (§5): every `internal` stream must have exactly one machine listing it as output and at least one machine listing it as input (one-producer, one-or-more-consumers per stream; fan-out is allowed via Kotlin's `Channel`-with-`BroadcastChannel` pattern, but step 2 defaults to single-consumer for simplicity — fan-out lands in step 3 if a corpus program needs it).

### 4.3 Tagged-Event sum synthesis

When the verifier sees a multi-stream machine, it synthesizes the InputEvent sum type internally:

```
InputEvent_<machine-hash> = SumType {
  case stream_0 of inputStreams[0].eventType,
  case stream_1 of inputStreams[1].eventType,
  ...
  case stream_{n-1} of inputStreams[n-1].eventType,
}
```

This synthesized type is what the transition function's Event parameter must match. The verifier's `StateMachineTransitionFnShapeMismatch` check uses this as the expected Event type when `inputStreams.size > 1`. For `inputStreams.size == 1` (the step 1 case), the synthesized sum is bypassed and the Event type is simply `inputStreams[0].eventType` — preserving every step 1 corpus program unchanged.

The synthesized type is part of the verifier's working state, not a graph node — it doesn't get a NodeId and doesn't appear in the NodeStore. Two machines with the same input stream sequence produce structurally-equal synthesized sums under Strand's `TypeExpr.Sum` equality (which ignores `origin`), so caller-side type checking remains consistent.

### 4.4 OutputBatch → tagged list

The step 1 OutputBatch convention (`{output_0: Option<...>, ..., output_{n-1}: Option<...>}`) becomes a list of tagged outputs:

```
TaggedOutput_<machine-hash> = SumType {
  case output_0 of outputStreams[0].eventType,
  case output_1 of outputStreams[1].eventType,
  ...
  case output_{n-1} of outputStreams[n-1].eventType,
}

OutputList = μ. Nil | Cons(head: TaggedOutput, tail: <self>)
```

A transition function's result type is `(State, List<TaggedOutput>)` — strictly more expressive than step 1's "at most one Some per output slot per step." A machine can now emit zero, one, or many events to any output stream in a single transition.

The empty-output case is `Nil`; the verifier accepts this and the runtime treats it identically to step 1's all-None OutputBatch. Programs migrating from step 1's OutputBatch to step 2's tagged list need to rewrite their transition functions; the verifier emits a structured error pointing at the OutputBatch shape so authors know what's expected.

**Backward compatibility for the step-1 corpus.** Step 2 keeps the OutputBatch shape supported for `inputStreams.size == 1` machines so corpus programs 41–45 run unchanged. The verifier checks both shapes and accepts either; the runtime dispatches based on the actual result shape. Programs that opt into multi-stream get the tagged-list shape; single-stream programs are free to use either. This means corpus 41–45 remain pinned to the step 1 OutputBatch convention.

### 4.5 Replay determinism

Each `MachineInstance` carries an `EventRecorder` that captures the tagged input events the actor consumed, in order. After a `MachineGroup` runs to completion (or is canceled), the recorder for each instance contains an ordered `List<Value>` that, when replayed through step 1's `runMachine` against the same machine NodeId, produces a byte-identical `Trace`. This is the replay-determinism property `design/state-machines.md` § Conceptual model commits to.

The recorder's output is itself a corpus-test artifact: snapshot the recorder's event list at the end of an async run, then assert `runMachine(machine, recordedEvents) == expectedTrace` as a per-test invariant. This is the BEAM `gen_statem` test-harness pattern recast for Kotlin.

## 5. Verifier rules

The existing `StateMachineInputStreamCountUnsupported` rule is removed. New and modified rules:

- **`MalformedMachineGroup(at, detail)`** — a MachineGroup whose streams' kinds are inconsistent (e.g., an `internal` stream with no producing machine, or with more than one producer in step 2's single-producer model).
- **`InternalStreamNoProducer(at, streamId)`** — an EventStream declared `internal` has no machine in the group listing it as an output.
- **`InternalStreamMultipleProducers(at, streamId, producers)`** — step 2 enforces single-producer; multiple-producer fan-in lands in step 3.
- **`InternalStreamNoConsumer(at, streamId)`** — an `internal` stream with no consumer; the producer would block on a full buffer forever.
- **`StateMachineTransitionFnShapeMismatch`** — extended: for multi-stream machines, the expected Event type is the synthesized `InputEvent_<...>` sum; for multi-output machines, the expected return type is `(State, List<TaggedOutput>)` where `TaggedOutput` is the synthesized sum over `outputStreams[i].eventType`.
- **`StateMachineEffectCoverageViolation`** — extended: the machine's declared `effects` must include `StateMachine.Receive` (always — input streams are mandatory) and `StateMachine.Send` (if output streams are declared).
- **`StateMachineMissingImplicitEffect(at, missing)`** — a new diagnostic distinguishing the implicit effects from the closure's effects; helps the user see which effects come from the runtime vs from the transition function.

The closure-subtraction story for E-028/E-029 inside Handler bodies is unchanged from Layer 3 step 3 — handlers can intercept Send/Receive just like any other effect category.

## 6. Runtime semantics

### 6.1 Lifecycle

A `MachineGroup` is started via `runGroup(group): MachineGroupHandle`. The handle exposes:

- `suspend fun await()` — completes when every machine in the group halts
- `fun cancel()` — sends cancellation to every actor coroutine; channels close
- `fun recordedEvents(instance: InstanceId): List<Value>` — the captured event sequence for replay
- `val instances: Map<InstanceId, MachineInstance>` — for inspection

Each actor halts when (a) its input channels all close (no upstream producer left), (b) the transition function returns a designated termination value (step 3 work — step 2 only supports (a)), (c) cancellation is signaled.

### 6.2 Select-based input dispatch

`receiveNextInput` (§4.1) uses `select` over the machine's input channels. If all channels are closed, the actor halts; otherwise it suspends until at least one channel has an event ready.

The select-clause ordering in the source has no semantic significance — Kotlin's `select` does not bias toward earlier clauses for ready channels; it makes a fair nondeterministic choice. This matches Q-009's "non-deterministic merge" specification. Priority-merge (Q-009's opt-in) is a step 3 follow-up that wraps select with a priority-ordered probe-then-fall-back loop.

### 6.3 Output dispatch

After `applyTransition`, the runtime walks the returned `List<TaggedOutput>` and for each `Cons(head, tail)`:

- `head` is a `SumValue` whose `caseName` matches one of `output_i` for `i in outputStreams.indices`
- The runtime sends the payload to `outputChannels[outputStreams[i]]` via `channel.send(payload)`
- If the channel is at capacity, `send` suspends — this is the step-2 backpressure mechanism (block-producer default per Q-015); overflow policies land in step 3

For step 1 OutputBatch programs (single-stream machines, see §4.4), the runtime dispatches outputs from the batch fields directly, preserving step 1's per-step semantics.

### 6.4 Inter-machine wiring at startup

```kotlin
fun runGroup(group: MachineGroup): MachineGroupHandle {
    val streamChannels = mutableMapOf<NodeId, Channel<Value>>()
    for (streamId in group.uniqueStreams()) {
        val capacity = capacityFor(streamId)   // step 2: fixed default 1024
        streamChannels[streamId] = Channel(capacity)
    }
    val instances = group.machines.map { machineId ->
        buildInstance(machineId, streamChannels, group.capabilities)
    }
    val jobs = instances.map { instance ->
        scope.launch { runActor(instance) }
    }
    return MachineGroupHandle(instances, jobs)
}
```

The buildup is purely structural — given a topology, the wiring is determined. There is no runtime negotiation; the verifier has already confirmed the topology is well-formed.

### 6.5 Capability context

Each instance receives the supplied `CapabilitySet` at startup. The transition function evaluates under this context, exactly as in step 1. The implicit `StateMachine.Send`/`Receive` effects are not checked against the context — they are runtime mechanisms exposed to the user only through the machine's declared `effects` list (which the verifier already enforces coverage for).

If a transition function calls into user code that exercises a category not in the context, the existing `CapabilityViolation` / `RefinementViolation` paths fire as today.

### 6.6 Failure

A transition that throws (e.g., from a runtime `CapabilityViolation`, an `NoMatchingCase`, an unknown ForeignNode target) currently halts the entire `runMachine` call in step 1. In step 2, a single actor's failure should be confined to that actor: the coroutine catches `Throwable`, marks the instance as halted with a structured failure record, closes its output channels, and continues. Other machines in the group keep running. This is the "let it crash" discipline.

Step 2 does not yet ship a supervisor protocol that *responds* to failures — the supervisor pattern as a corpus program just observes that other machines halted via their output streams closing. Real supervision (spawn/terminate of children, restart policies) lands in step 3 with the E-030/E-031 effects.

## 7. Test scenarios

1. **Two-machine ping/pong** — Machine A emits `ping` on stream X; Machine B receives X, emits `pong` on stream Y. External output captures the alternating sequence. Tests the basic inter-machine wiring path and FIFO-per-stream ordering.
2. **Multi-input merge** — Machine M has two input streams S₁, S₂; the transition function Matches on the tagged sum and writes a single output recording which stream the event came from. Drive with interleaved events on both streams and assert that all events are received in FIFO-per-stream order with any interleaving.
3. **Backpressure under capacity** — Producer machine emits to a stream with capacity 4; consumer machine is slow (sleep 1 unit per event in a Handler). Producer's send suspends after the 4th event. Assert the producer's recorded send count is bounded by the consumer's processed count + 4.
4. **External-input replay determinism** — Run a single-machine async program over an external input source, record the events the actor consumed, then re-run via step 1's `runMachine` with the recorded list. Assert the resulting `Trace` matches the async run's per-step state and outputs.
5. **Internal stream with no consumer rejected** — A MachineGroup declares an internal stream that no machine consumes. Verifier reports `InternalStreamNoConsumer`. (Compile-time well-formedness.)
6. **Single-producer enforcement** — A MachineGroup declares two machines that both list the same internal EventStream in their `outputStreams`. Verifier reports `InternalStreamMultipleProducers`.
7. **Supervisor one-for-one restart (corpus capstone)** — A supervisor machine watches three workers; each worker's input stream comes from the supervisor's `spawn_request` output. When a worker halts (its output channel closes), the supervisor's transition function emits a fresh spawn command. Step 2 implementation of the supervisor pattern; demonstrates E-030/E-031 wiring.
8. **Tagged-output list with mixed cases** — A transition function emits `[Cons(output_0(...), Cons(output_1(...), Nil))]` for a single event. The runtime dispatches both: one to stream 0, one to stream 1. Asserts list-of-outputs semantics.
9. **Cancellation propagates** — `MachineGroupHandle.cancel()` is called while a producer is waiting on a full consumer channel. All actor coroutines complete; channels close in a finite number of steps; no leak.
10. **Closure stays cached across many events** — The producer machine emits 10,000 events through a topology of two machines; assert the cached transition Lambda Value is evaluated once per instance at startup, never re-evaluated mid-loop. (Performance regression test.)

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Bounded-queue overflow policies beyond block-producer** — `DropNewest`, `DropOldest`, `Sample` per Q-015 are step 3 work. Step 2 uses block-producer (Kotlin `Channel.send` suspends on capacity) as the only policy. The `EventStream` JSON schema does not yet accept a `bufferSize` / `overflowPolicy` field — step 3 adds them.
- **Multi-producer fan-in on internal streams** — single-producer is enforced; fan-in (multiple machines producing on the same stream) is a deferred extension. A corpus program needing it would justify lifting the `InternalStreamMultipleProducers` check; until then the simpler topology rule keeps the wiring deterministic.
- **Fan-out / broadcast on internal streams** — single-consumer is the default; broadcast to many consumers is step-3 work and requires Kotlin's `BroadcastChannel` or its replacement. Practical workloads (every consumer sees every event) need this; step 2 punts because no compelling step-2 corpus program needs it.
- **Supervisor restart policies beyond one-for-one** — `one-for-all` and `rest-for-one` are step 3 corpus programs. Step 2 ships the supervisor *infrastructure* (spawn/terminate via E-030/E-031, child-halt notifications via output-channel-closed) but only the one-for-one pattern as a worked example.
- **Snapshot / replay-from-log for crash recovery** — step 3 work. The recorder infrastructure shipped in step 2 (§4.5, §6.1) is the precursor; serializing recorded events to a content-addressed log and rehydrating instance state from a snapshot hash is step 3.
- **Hot upgrade (Q-010)** — explicitly deferred; the design implies that step 2 doesn't even have to think about this — the upgrade primitive is a `MachineGroupHandle` operation that swaps a transition function reference, which step 2's actor-loop architecture supports without modification.
- **Distributed execution across executors** — separate milestone, not part of step 2 or step 3.
- **Priority/causal/timestamp merge policies** — Q-009's opt-in alternatives are step-3-or-later. Step 2 ships only the default nondeterministic merge.
- **The N-029 Transition node** — still parsed-but-unused. Some multi-stream programs might motivate it (a transition function decomposed into per-event-type Transition arms instead of a Match); step 2 keeps the step-1 verifier rules (`TransitionStandalone`) intact and adds no host context for Transition.

**Real research questions:**

- **OQ-2a: When does `select` clause ordering matter?** Kotlin's `select` is documented as fair nondeterministic; in practice the implementation may have biases under heavy load. If priority is needed (Q-009), a wrapping probe-then-fall-back loop is the natural extension, but the boundary between "true nondeterminism" and "biased selection" needs an integration test that asserts the relative distribution of events across streams over a long run.
- **OQ-2b: Should `external` input channels be `ReceiveChannel<Value>` (host produces) or `SendChannel<Value>` (runtime sends)?** Current design says ReceiveChannel — the host owns production. But the host needs to close the channel to signal the input stream is exhausted; whether close-detection should also trigger an external "input-exhausted" event to the machine is open.
- **OQ-2c: Recorder bound when on.** When `recordInputs = true`, the recorder accumulates an unbounded `List<Value>`. Production workloads disable via `recordInputs = false` (the per-group flag introduced in §4.2). Replay-determinism tests keep recording on. The further question — should a `recordInputs = true` recorder bound itself to a sliding window for long-lived machines that need both recording and bounded memory? — is deferred. Step 3's snapshot work needs to decide whether the recorder is the snapshot's source-of-truth or a separate mechanism; the sliding-window question lands with it.
- **OQ-2d: Cancellation semantics for in-flight transitions.** If `cancel()` fires mid-transition (the actor coroutine is in the middle of evaluating the transition Lambda), the standard Kotlin coroutine cancellation cooperative-cancel pattern would interrupt at the next suspension point. But the interpreter is synchronous and never suspends — so a transition currently in progress always runs to completion before the actor sees cancellation. This is probably correct (transitions are atomic) but should be confirmed.
- **OQ-2e: What is the right test infrastructure for nondeterministic merges?** The deterministic replay seam (§4.5, §6.1) reproduces the merged event sequence the runtime observed in a specific run, but doesn't characterize the *space* of legal merges. Tests asserting "any FIFO-per-stream interleaving is acceptable" need a property-test framework (e.g., property-based assertions on a generated event-arrival schedule). Step 2 punts; per-test asserts on observed-recordings is the floor.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `runtime/src/main/kotlin/org/strand/runtime/StateMachineRuntime.kt` | Add `runGroup(group): MachineGroupHandle` entry point alongside existing `runMachine`. The synchronous fold survives unchanged. | Medium |
| `runtime/src/main/kotlin/org/strand/runtime/MachineGroup.kt` | NEW — topology data class, channel allocation, wiring validation | Medium |
| `runtime/src/main/kotlin/org/strand/runtime/MachineGroupHandle.kt` | NEW — async handle with await/cancel/recordedEvents | Small |
| `runtime/src/main/kotlin/org/strand/runtime/MachineActor.kt` | NEW — the per-machine actor coroutine: select loop, transition application, output dispatch, failure isolation | Medium |
| `runtime/src/main/kotlin/org/strand/runtime/MachineInstance.kt` | EXTEND — add `inputChannels`, `outputChannels`, `recorder`, `EventRecorder` type. Existing fields preserved for step 1 path. | Small |
| `runtime/src/main/kotlin/org/strand/runtime/EventRecorder.kt` | NEW — per-instance event log for replay determinism | Small |
| `runtime/build.gradle.kts` | Add `kotlinx-coroutines-core` and `kotlinx-coroutines-test` (test) dependencies | Small |
| `verifier/src/main/kotlin/org/strand/verifier/Verifier.kt` | Multi-stream support: lift `StateMachineInputStreamCountUnsupported`, synthesize `InputEvent` sum for shape check, add internal-stream topology checks (single-producer, no-orphans), accept tagged-list return type for multi-output machines, keep OutputBatch path for single-stream programs | Medium-Large |
| `verifier/src/main/kotlin/org/strand/verifier/VerifyError.kt` | Add the new error variants (`InternalStreamNoProducer`, `InternalStreamMultipleProducers`, `InternalStreamNoConsumer`, `MalformedMachineGroup`, `StateMachineMissingImplicitEffect`); remove `StateMachineInputStreamCountUnsupported` | Small |
| `interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt` | Register `StateMachine.Send` and `StateMachine.Receive` as well-known EffectCategory bindings (no foreign-target impls — these are runtime-internal effects, not user-callable builtins) | Small |
| `cli/src/main/kotlin/org/strand/cli/Main.kt` | Add `strand group <file.json> --events <events.json>` subcommand for driving a MachineGroup. Single-machine `strand machine` continues to use step 1's `runMachine`. | Small-Medium |
| `corpus/src/main/resources/corpus/46-async-ping-pong.json` | Two-machine ping/pong (test 1) | Small |
| `corpus/src/main/resources/corpus/47-async-multi-input-merge.json` | Multi-input-stream machine with tagged Event sum (test 2) | Small |
| `corpus/src/main/resources/corpus/48-async-supervisor-one-for-one.json` | Supervisor capstone (test 7) | Medium |
| `corpus/src/main/resources/corpus/49-async-tagged-output-list.json` | Multi-output emit-list example (test 8) | Small |
| `corpus/src/main/resources/corpus/46..49-...events.json` | Per-program event lists | Small per program |
| `corpus/src/main/resources/corpus/README.md` | Per-program descriptions for 46–49 | Small |
| `corpus/src/test/kotlin/org/strand/corpus/AsyncCorpusTest.kt` | NEW — registers async corpus programs, runs them through MachineGroup, asserts on emitted events from external outputs, tests replay determinism via recorder | Medium |
| `runtime/src/test/kotlin/org/strand/runtime/MachineGroupTest.kt` | NEW — unit tests for the topology builder, wiring validation, failure isolation, cancellation propagation | Medium |
| `impl/CLAUDE.md` | Update Layer 6 step 2 status; document the MachineGroup API surface, the tagged-Event convention, the recorder mechanism, the deferred items | Small |

**Order of work.**

1. **Verifier multi-stream lift** — remove `StateMachineInputStreamCountUnsupported`, synthesize `InputEvent`, accept tagged-list return shape. Most existing tests pass; corpus programs 41–45 continue to verify under the OutputBatch path.
2. **Coroutine actor + recorder + single-machine async** — `runGroup` on a one-machine group with one external input stream, no inter-machine wiring. Verifies the actor loop, select-on-one-channel, recorder.
3. **Inter-machine wiring + multi-input merge** — two-machine ping/pong; multi-input merge with tagged sum. Topology validation rules fire here.
4. **Supervisor pattern + E-030/E-031 wiring** — spawn/terminate effects, child-halt notifications, one-for-one restarter.
5. **Tagged-output list** — replace single-machine OutputBatch with list for multi-stream machines. Migrate any internal helpers; keep OutputBatch path live for step 1 corpus.
6. **CLI `strand group` subcommand** — last because the other pieces are independently testable through corpus tests.

**Not in this slice.** Backpressure overflow policies (Q-015's non-default policies), fan-out/fan-in on internal streams, supervisor restart policies beyond one-for-one, snapshot/replay-from-log persistence (the recorder ships, but snapshot serialization is step 3), hot upgrade (Q-010), distributed execution across executors, priority/causal/timestamp merge policies. Step 1's `runMachine` and the corpus programs 41–45 are preserved without modification.

## References

**Outgoing references:**
- [`design/state-machines.md`](../design/state-machines.md) — full state-machine spec
- [`decisions/ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md)
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — § State machine effects (E-028 through E-031)
- [`design/distribution-model.md`](../design/distribution-model.md) — backpressure, distribution
- [`proposals/implemented/state-machines-runtime.md`](implemented/state-machines-runtime.md) — the step 1 proposal whose § 7 sketches step 2
- [`open-questions.md`](../open-questions.md) — Q-008, Q-009, Q-015 ground this proposal; Q-033 points back at this document

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-033 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — Known gaps section
