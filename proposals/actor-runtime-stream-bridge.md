# Actor-runtime bridge for IO-backed external event streams

**Document:** `proposals/actor-runtime-stream-bridge.md`
**Status:** Draft proposal
**Date:** 2026-06-03
**Concerns:** [`design/state-machines.md`](../design/state-machines.md) (the push-based actor runtime, `External` EventStream, `MachineGroupHandle.externalInputs`, `OverflowPolicy`, `StreamBus`, `EventRecorder`), [`proposals/implemented/streaming-async-io.md`](implemented/streaming-async-io.md) § 8 (the Q-045 pull primitive this builds on, and the deferral that names this question), [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) (E-004 `Network.Receive` vs. the runtime-implicit E-029 `StateMachine.Receive`), [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) (additive field versioning), [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) (mandatory effect declarations), [`decisions/ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md) (atomic pure transitions), [Q-046](../open-questions.md#Q-046), [Q-044](../open-questions.md#Q-044) (the harm bound this preserves), [Q-009](../open-questions.md#Q-009) (event ordering / nondeterministic merge), [Q-015](../open-questions.md#Q-015) (backpressure), [Q-040](../open-questions.md#Q-040) (`streamReceiveTimeoutMillis`), [Q-041](../open-questions.md#Q-041) (sandbox at open), [Q-042](../open-questions.md#Q-042) (error redaction)
**Scope:** medium

This proposal resolves Q-046, the actor-runtime bridge deferred in the Q-045 streaming-I/O proposal § 8. It specifies how a state machine consumes a live socket or LLM stream as ordinary events — inheriting the existing `OverflowPolicy`, `StreamBus` fan-in/out, `EventRecorder` replay, and runtime metrics — by binding an `External` EventStream to an IO source declared in the graph. The proposal covers four coupled sub-questions: the graph surface for the binding, effect accounting for the host-side reads, replay determinism, and feeder lifecycle and backpressure. The recommended slice is minimal by design: a single raw-`Bytes` bridged stream per machine under `BlockProducer` backpressure, with per-instance replay; decoded events, multi-stream group replay, and streaming send are deferred.

## 1. Problem statement

The Q-045 core slice delivers a synchronous *pull* surface: a program calls `<Namespace>.Stream.Receive(handle, maxBytes)` in a `Fixpoint` loop and blocks the calling thread on each chunk. This fits an agent driving one stream at a time, but it is not replayable (the raw drain records nothing) and it cannot interleave with other event sources. The state-machine actor runtime (Layer 6) is the opposite — *push*-based and built for exactly multi-stream, replayable, backpressured consumption: `select`-based nondeterministic merge across input channels (Q-009), per-stream `OverflowPolicy` (Q-015), `StreamBus` fan-in/fan-out, per-instance `EventRecorder` deterministic replay, and runtime metrics. But this machinery is intra-process only. Events reach a machine because host Kotlin code holds the `SendChannel<Value>` that `runGroup` exposes on `MachineGroupHandle.externalInputs` and pushes values in. Nothing in the *graph* declares that a given `External` EventStream is fed by a live IO source. The transition model forbids closing the gap from inside a transition: transitions are atomic and pure (ADR-007), so a blocking `Stream.Receive` pull cannot occur inside one.

The gap is therefore a *bridge*: a host-side coroutine that drains a Q-045 streaming handle (`Stream.Receive` in a loop) and feeds each chunk onto an `External` stream's channel, so a machine consumes a network/LLM stream as ordinary events. The feeder itself is straightforward. The unresolved questions are (a) how to declare the binding at the graph level so the program stays self-describing and the verifier can reason about it; (b) how the host-side reads — which happen outside any transition — are accounted as effects so the Q-044 harm bound stays sound; (c) how a non-deterministic live source becomes replayable; and (d) the feeder's lifecycle and its interaction with backpressure when the runtime does not control the remote producer.

## 2. Prior art

- **Erlang/OTP ports and `gen_statem`** — external I/O is modeled as messages delivered to a process mailbox, never as a synchronous pull inside a state callback. A port owner process drains the external source and forwards messages. This is precisely Strand's actor constraint and the shape of the bridge: the feeder is the port owner, the `External` stream is the mailbox.
- **Akka Streams `Source.queue` / `ActorRef` sources** — a stream materializes into a queue that host code offers elements to, with an explicit `OverflowStrategy` (backpressure / dropHead / dropTail / dropBuffer / fail) chosen per source. Strand already has the analogous `OverflowPolicy`; the open question Akka also faces is what dropping *means* for a given element type, which this proposal answers for byte chunks (dropping is unsafe).
- **Reactive Streams / `java.util.concurrent.Flow`** — demand-driven backpressure: the consumer signals capacity, the producer never outruns it. A bridged stream gets demand propagation only under `BlockProducer` (a full channel suspends the feeder, which stops pulling, which stalls the TCP window). Drop policies break the demand chain and silently discard bytes.
- **Kafka Connect source connectors** — a host-side task polls an external system and produces records into a topic, with offset tracking for replay. The replay analogue here is `EventRecorder`: the bridge records the *delivered events* (the runtime's equivalent of committed offsets), not the raw wire bytes, so replay reproduces what the consumer saw.
- **Deterministic-replay actor systems (e.g. `gen_statem` with `sys` tracing, Unison's abilities)** — replay fidelity is defined over the *messages a process observed*, not over the wire framing that produced them. This motivates recording at the consumer after overflow policy, not at the feeder before it.

## 3. Recommended approach

Bind an `External` EventStream to its IO source with a single optional **`source` edge** on the EventStream node, pointing at the graph node that opens the stream (an `Application` of a `*.CreateStream` LLM builtin, or of `Net.Connect` for a socket). The runtime starts one host-side **feeder coroutine** per `source`-bound stream at `runGroup`: it evaluates the source once outside any actor to obtain the Q-045 handle, then loops calling `Stream.Receive` on a dedicated IO dispatcher and pushes each chunk through the stream's existing `OverflowDispatcher`. The state machine consumes the chunks as ordinary `Bytes` events and never knows they came from a live socket.

The design commits to five positions; rationale and rejected alternatives are in § 8.

- **The binding is a `source` edge on the External EventStream, not a CLI flag or a free-floating binding node.** It keeps the binding in the graph (the program is self-describing; ADR-001), it is additive under ADR-003 (existing programs hash unchanged), it adds no new node category, and it gives the verifier a single typed anchor reachable from the consuming machine. A CLI-only bind would remove the source's effect declaration from the verifier's view and break the harm bound; a separate binding node would add a category and a reachability story for no benefit.
- **Effect accounting is the hybrid: the binding's transport effects are absorbed into the consuming group's effect closure and checked against `group.capabilities` at group start.** The machine still declares E-029 `StateMachine.Receive` (the capability-free honesty marker for runtime-delivered events); the *real, world-touching* transport effects of the source — the open's semantic effect (E-035 `LLM.Generate` or E-001 `Network.Connect`) and the feeder's E-004 `Network.Receive` reads — are the group's responsibility. Treating the bridge "like E-029" (capability-free) would be a category error: E-029 touches nothing real, the feeder touches the network. This keeps the harm bound sound and keeps `CapabilityScope` revocation biting (§ 5, § 6).
- **The bridged event type is raw `Bytes`; EOF is channel closure.** The feeder delivers `BytesV` chunks; when `Stream.Receive` returns `None`, the feeder closes the channel, and the actor's existing producer-halt path terminates the machine. Decoded stream events (a `Chunk | Eof | Failure` sum, an SSE decoder) are deferred — consistent with Q-045 shipping raw bytes and deferring SSE decoding.
- **Byte-chunk streams are `BlockProducer`-only.** A byte chunk is meaningless out of sequence: dropping any chunk removes a run of bytes mid-stream and corrupts every downstream decoder. `DropNewest` / `DropOldest` / `Sample` are rejected at bind time for a `source`-bound byte stream. Only `BlockProducer` propagates real backpressure to the uncontrolled remote (a full channel suspends the feeder → stops pulling → stalls the TCP window). Drop policies become legal only once a framing decoder turns bytes into independently-interpretable records (deferred).
- **Replay records the delivered, post-policy event at the consumer, never the raw chunk at the feeder.** The machine observes only post-`OverflowDispatcher`, post-`wrapEvent` events; chunk-split boundaries are non-deterministic and have no semantic meaning. Recording at the consumer (the existing single `EventRecorder.record` site) reproduces the machine's experience exactly and runs replay with zero live IO.

## 4. Detailed mechanism

No new node category, ADR, or effect-category identifier. The graph change is one optional field on the existing `EventStream` node (N-028). No canonical encoding change for any program that does not use it, so every existing program hash is preserved.

### 4.1 The `source` field on EventStream

`Node.EventStream` gains one optional content field, following the established additive-versioning pattern already used by `bufferSize`, `overflowPolicy`, and `consumerMode` (slice 3.1 / 3.6):

```
data class EventStream(
    val eventType: NodeId,                 // a Type
    val streamKind: StreamKind,
    val bufferSize: Int? = null,
    val overflowPolicy: OverflowPolicy? = null,
    val consumerMode: ConsumerMode? = null,
    val source: NodeId? = null,            // new — the IO-opening node that backs this stream
) : Node()
```

`source` is `null` for every existing stream and for host-pushed `External` streams (the `externalInputs` path stays available unchanged). When non-null, it references — by hash, like every other edge — the node that opens the IO source: an `Application` of a stream-opening builtin (`Anthropic.Messages.CreateStream`, `OpenAI.Chat.CompletionsStream`, `Gemini.GenerateContentStream`, or `Net.Connect`). Because the reference is by content hash, the stream's hash transitively depends on the opener's hash (and thus on the provider, model, and request), exactly as `inputStreams` already depend on stream hashes — so the binding is content-addressed and replay-stable up to the non-deterministic chunk contents.

### 4.2 Canonical encoding

Extend `encodeEventStream` to emit `source` only when non-null, trailing the slice-3.6 fields, under the existing omit-when-default discipline: `source == null` emits no bytes, so existing EventStream encodings are byte-identical and the corpus hashes are untouched (verified by `CorpusHashingTest`). Because `source` is a hash reference (not an enum-with-default), it is independently omittable — a stream that sets `source` but leaves buffer/policy/mode at default emits unambiguously. Streams that *use* the field are new programs, so their hashes are new at no migration cost.

### 4.3 Worked example — a machine consuming a bridged LLM stream

Graph nodes:

- `req` — a `GenerateRequest` product value (the existing Q-045 shape: provider, model, messages). No credential appears in the graph; the host `CredentialProvider` resolves the key at open, exactly as the blocking `*.Create` variant does.
- `open` — an `Application` of the `Anthropic.Messages.CreateStream` ForeignNode on `req`, carrying `effectInstances = [LLM.Generate{provider="anthropic", model="claude-..."}]`. Type `(GenerateRequest) -> Int` (the handle), per Q-045.
- `chunkStream` — an `EventStream { eventType = Bytes, streamKind = External, overflowPolicy = BlockProducer, source = open }`. The `source` edge is the whole binding.
- `summarizer` — a `StateMachine { inputStreams = [chunkStream], outputStreams = [out], transitionFn = ..., initialState = "" }` whose transition appends each `Bytes` chunk to an accumulator and emits on a sentinel. Its declared `effects` include E-029 `StateMachine.Receive` and E-030 `StateMachine.Send` (for `out`), per the existing well-known-effect rules.
- `out` — an `EventStream { eventType = String, streamKind = Output }`.
- The machine group is run with `capabilities` covering `LLM.Generate{provider="anthropic", model="claude-..."}` and `Network.Receive`.

Runtime flow: `runGroup` validates topology, runs the new group-start coverage check (the union `{LLM.Generate{...}, Network.Receive}` from `open.effects ∪ {E-004}` must be covered by `group.capabilities` — it is), evaluates `open` once outside any actor under the group's capability context (this runs the E-035 capability check and opens the SSE response, yielding `Value.Resource(kind="llm_stream")`), and launches a feeder that calls `LLM.Stream.Receive(handle, maxBytes)` in a loop, pushing each `Some(chunk)` through `chunkStream`'s `OverflowDispatcher` and closing the channel on `None`. The `summarizer` actor sees ordinary `Bytes` events. No node hashes change; the program is Layer 4 + Layer 6 graph structure over one new optional edge.

## 5. Verifier rules

Two enforcement points, mirroring the existing split between per-node well-formedness (verifier) and cross-machine topology (`MachineGroup.validateTopology`, runtime-side).

Verify-time well-formedness on a `source`-bound EventStream (new `VerifyError` variants):

- `StreamSourceOnNonExternal` — `source` is only meaningful on `streamKind = External`. Setting it on `Internal` / `Output` is rejected.
- `StreamSourceNotAnOpener` — `source` must resolve to an `Application` of (or reference to) a ForeignNode whose `target` is a registered IO-opening stream builtin. A registry of opener targets (the `*.CreateStream` set plus `Net.Connect`) is added, mirroring `WellKnownEffect`. A `source` pointing at an arbitrary expression is rejected.
- `StreamSourceEffectMismatch` — the opener's declared `effects` must include the expected semantic effect for its kind (E-035 `LLM.Generate` for an LLM open; E-001 `Network.Connect` for a socket open). This guarantees the source is effect-declaring and reachable, so the group-start coverage check below has something to absorb.
- `StreamSourceTypeMismatch` — the stream's `eventType` must be `Bytes` (the raw primitive this slice delivers). A decoded event type is rejected until the framing-decoder layer ships.
- `ByteStreamSourceRequiresBlockProducer` — a `source`-bound byte stream whose `overflowPolicy` is `DropNewest` / `DropOldest` / `Sample` is rejected. Byte chunks cannot be dropped without corrupting the stream; only `BlockProducer` (the default) is admitted for a byte source.

Group-start coverage (new `MachineGroupValidationError` variant, raised in `validateTopology` before any feeder launches):

- `ExternalStreamSourceEffectUncovered(stream, source, missing)` — for each `source`-bound `External` stream consumed by a machine in the group, the union of the opener's declared transport effects and the feeder's drain effect — `open.effects ∪ {E-004 Network.Receive}` — must be covered by `group.capabilities`. A gap raises this error and no socket is opened. This is the revocation gate: a group whose capability context (or an enclosing N-036 `CapabilityScope` narrowing) omits `Network.Receive` fails here, exactly as a direct Q-045 drain fails its Application-site capability check.

The well-known-effect registry is *not* extended: E-004 and E-035 are real value-level transport effects, not runtime-implicit ones; only E-029 `StateMachine.Receive` stays in `WellKnownEffect`. This mirrors the Q-045 § 5 decision that the streaming drain does not enter the registry.

The soundness argument, against the Q-044 harm bound `closure(g) ∩ C ∩ B ∩ P`: a bridged drain causes real `Network.Receive` interactions, so E-004 (and the open's E-035 / E-001) must appear in `closure(g)` *and* be subject to the same capability gate `C` a direct drain is subject to. The `source` edge makes the binding a structural fact the verifier sees; the group-start rule makes "the group's closure absorbs every consumed IO-backed stream's transport effects" an enforced well-formedness condition, so an agent can neither omit nor misdeclare it. The transport effects are then in `closure(g)` by construction, and `group.capabilities` must cover them. Revocation bites at group-start rather than per-receive, which suffices because the feeder is the sole receiver and it cannot launch without the capability.

## 6. Interpreter / runtime semantics

No interpreter change to `Stream.Receive` / `Stream.Close` themselves; the feeder calls the shipped Q-045 builtins, so Q-041 sandbox-at-open and Q-040 `streamReceiveTimeoutMillis` enforcement come for free.

**Feeder lifecycle** — one `Job` per `source`-bound stream, owning the handle's close obligation; states `Start → Draining → (Eof | Failure | Cancelled) → Closed`:

- *Start.* In `runGroup`, after actors and broadcast pumps are spawned (so the consumer's input channel exists), a new pass evaluates each bound `source` once under the group's capability context to obtain the handle, registers the feeder as the producer on the stream's `StreamBus` (producer count 0 → 1, so the bus auto-closes the channel on feeder halt), and `scope.launch`es the feeder.
- *Dispatcher.* `Stream.Receive` is a blocking native read and must never run on the actors' `select` dispatcher. The feeder performs each pull inside `withContext(Dispatchers.IO) { ... }`, while the push (`dispatcher.send`) runs on the structured `scope` so cancellation and overflow policy apply. `Dispatchers.IO` is correct — each feeder blocks at most one IO thread.
- *Draining.* Pull a chunk on IO, wrap `Some(Bytes)` as a `BytesV`, push through the stream's `OverflowDispatcher`. The per-read OS timeout (`streamReceiveTimeoutMillis`) already bounds each read.
- *Close exactly once.* Every exit funnels through a single `finally` that calls `Stream.Close(handle)` once; Q-045's `ResourceTable.remove` is idempotent, so a teardown racing a self-initiated EOF close still closes the transport at most once. A feeder blocked in a native read at `cancel()` time is not coroutine-interruptible, but the read returns within `streamReceiveTimeoutMillis` (and the `finally`'s `Stream.Close` also closes the underlying socket, unblocking the read on most JVMs), so teardown is bounded.

**EOF and transport failure** — at `Stream.Receive == None`, the feeder closes the channel; the bus's producer-halt path drives the actor's `select` to see the closed channel, remove it, and halt with `EventsExhausted` when no inputs remain. The terminal marker is channel closure, not an in-band sentinel — reusing the exact path internal-stream producer-halt already uses. On a transport `IoFailure` (broken pipe, reset, timeout), the feeder records the scrubbed failure kind (Q-042) in per-stream metrics and closes the channel; it never retries in-feeder (a silent reconnect would resume an LLM stream at a non-deterministic offset and break replay) and never fails the whole group (per-instance "let it crash" — one dead source must not tear down siblings). Because the raw-`Bytes` event type cannot carry a typed failure case, a machine that must distinguish failure from clean EOF needs the deferred record-oriented event shape; the raw slice surfaces failure only via metrics and channel closure.

**Backpressure** — the stream's `OverflowPolicy` governs the feeder via the existing `OverflowDispatcher`. Under `BlockProducer` (the only policy admitted for a byte source, § 5), a full channel suspends the feeder's `send`, which stops the pull loop, which stalls the OS read; the kernel/TLS buffer fills and the TCP window closes, propagating real backpressure to the remote. A residual memory bound against a misbehaving peer that ignores TCP backpressure is `streamReceiveTimeoutMillis` plus the OS socket buffer — inherent to bridging an uncontrolled producer, and documented rather than solved.

**Replay** — recording stays at the existing single `EventRecorder.record(wrapEvent(streamId, payload))` site inside the actor loop, which fires *after* the `OverflowDispatcher` has applied its policy and *after* `wrapEvent` tags the event with its input-stream index. The recorder therefore captures the delivered, post-policy, post-tag event in actual `select` arrival order — the linearization of the merge. The feeder records nothing. Consequences: dropped events (under a future framed stream) are absent from the recording, so replay does not resurrect them; chunk-split boundaries do not appear, so replay does not depend on wire framing; multi-stream interleaving is captured as one index-tagged list. Replay re-feeds that list through the synchronous `runMachine`, which touches no `ResourceTable` handle, no socket, no `OverflowDispatcher` — the feeder is simply not started on replay, so replay runs with zero live IO. Per-instance replay is in scope; group-level replay (re-running `runGroup` with recorded inputs and no live feeders) is deferred (§ 8). The drain effect E-004 is incurred by the feeder on the live run and is genuinely absent on replay — honest accounting, noted so the implementer does not attempt to re-incur it.

## 7. Test scenarios

1. **Bridged LLM drain, happy path** — a group with `LLM.Generate` and `Network.Receive` capabilities and a `source`-bound `External` stream over an injected `llmHttpClient` chunk transport (no network); the feeder delivers chunks as events, the machine consumes them, EOF closes the stream and halts the machine.
2. **Adversarial: bridged drain under revoked `Network.Receive`** — same wiring, but `group.capabilities` (or an enclosing N-036 narrowing) omits `Network.Receive`; `runGroup` raises `ExternalStreamSourceEffectUncovered` at start, no handle is opened, no chunk delivered. This is the test that proves the harm bound holds.
3. **Source is not an opener** — `source` points at a non-opener `Application`; rejected at verify with `StreamSourceNotAnOpener`.
4. **Source under-declares its effect** — the opener ForeignNode declares no E-035 / E-001; rejected with `StreamSourceEffectMismatch`, so an IO source cannot launder bytes through an effect-free binding.
5. **`source` on a non-External stream** — `source` set on an `Internal` stream; rejected with `StreamSourceOnNonExternal`.
6. **Byte stream with a drop policy** — a `source`-bound byte stream declaring `DropOldest`; rejected with `ByteStreamSourceRequiresBlockProducer`.
7. **Socket vs LLM source carry distinct effects** — a `Net.Connect`-backed stream requires `Network.Connect` + `Network.Receive`; a group granting only E-035 is rejected for a socket-backed stream (effect honesty across source kinds).
8. **EOF terminal** — the injected transport reaches `None`; the feeder closes the channel exactly once, the machine halts `EventsExhausted`, and `Stream.Close` is observed once (a second close is a no-op).
9. **Cancellation mid-read** — the group is cancelled while the feeder is blocked in a native read; the feeder tears down within `streamReceiveTimeoutMillis` and `Stream.Close` runs exactly once.
10. **Transport failure is local** — the injected transport throws after two chunks; the feeder records the scrubbed failure (Q-042) and closes its stream, sibling machines in the group keep running, and the group does not fail.
11. **Backpressure under a slow consumer** — a fast transport into a small-buffer `BlockProducer` stream; assert the feeder suspends (bounded buffering) rather than growing the heap, and that no chunk is dropped.
12. **Record-then-replay equivalence** — capture `recordedEvents(instance)` from scenario 1; feed it into `runMachine`; assert the async trace's `(before, after, outputs)` sequence equals the sync replay trace, value-for-value, with no network touched on replay (run replay under a block-all `SandboxPolicy` and an empty `ResourceTable`).
13. **Boundary independence** — drive the same logical bytes with two different chunk-split patterns; assert the recorded `Value` lists are equal across both runs (replay does not depend on wire framing).

## 8. Tradeoffs and deferred work

**Deferred intentionally (each with its unblocker):**

- **Decoded stream events.** A record-oriented `Chunk(Bytes) | Eof | Failure(detail)` sum (or a normalized cross-provider `StreamEvent`) so EOF and transport failure are observable inside a transition and drop policies become safe. This needs a *stateful* framing decoder — a `Receive(maxBytes)` chunk can split an SSE frame mid-frame, so a clean decoded experience requires buffering across reads, most naturally a wrapping decoder resource (`Sse.Open(streamHandle) -> sseHandle`) reusing the `ResourceTable` pattern. This is the same decoder Q-045 § 8 defers; it unblocks both the richer event shape and drop policies on framed streams. Until it lands, the raw slice surfaces failure only via metrics + channel closure.
- **Group-level recorded replay.** This slice supports per-instance replay via `runMachine`. Replaying a whole group (rather than one instance) requires a playback mode that re-feeds each instance's recording into its channels with no live feeder attached, since `runGroup` would otherwise re-launch feeders. Unblocked by a `runGroup`-with-recorded-inputs entry point; not specified here.
- **Recording persistence across process restarts.** `EventRecorder` is in-memory today. Bridged replay across restarts requires persisting `snapshot()` — `Value`s are canonically encodable, so this rides the existing snapshot-persistence work, not a determinism question.
- **Multiple bridged streams into one machine.** The recording mechanism (index-tagged interleaving) already supports it, but this slice validates and tests a single `source`-bound stream per machine; the multi-stream interleaving-reproduction test (13’s generalization) is a follow-up.
- **Streaming send / bidirectional bridges.** Receive-only (the dominant agent workload). A feeder that writes a machine's outputs into an IO sink is a separate follow-up.
- **Socket and process sources at parity with LLM.** The mechanism admits `Net.Connect` as a source (scenario 7); process stdout/stderr streaming waits on the `Process.Read` transport effect that Q-045 § 8 also defers.

**Recorded decisions (settled here; alternatives rejected):**

- *Binding surface — `source` edge on EventStream, not a separate binding node or a CLI flag.* A separate node would add a category (N-047) and a group-reachability story for no benefit over an edge the consumer already reaches. A CLI-only bind (`strand group --bind-stream`) removes the source's effect declaration from the verifier's view — the program is no longer self-describing and the harm bound cannot be enforced statically. The edge is additive (zero existing-hash churn) and gives the verifier a local anchor.
- *Effect accounting — group-closure absorption + group-start coverage, not "like E-029".* Charging the bridge as a capability-free runtime-implicit effect (the E-029 analogy) borrows E-029's no-capability-check property for an effect that *does* touch the network — a category error that would make `closure(g)` an unsound bound. The transport effects are absorbed into the group closure and gated by `group.capabilities`.
- *Event type — raw `Bytes`, BlockProducer-only.* Dropping a byte chunk corrupts every downstream decoder; only `BlockProducer` propagates real backpressure. Drop policies are admitted only once a framing decoder produces independently-interpretable records.
- *Replay granularity — delivered post-policy events at the consumer, not raw chunks at the feeder.* Raw chunks would replay an arbitrary, semantically-meaningless segmentation and would resurrect dropped events; recording at the consumer reproduces the machine's experience and keeps the replay path free of any byte decoder.

**Known limitation:** even `BlockProducer` cannot bound memory against a remote that ignores TCP backpressure (a misbehaving peer or a fully-buffering proxy); the ceiling there is `streamReceiveTimeoutMillis` + the OS socket buffer. This residual is inherent to bridging an uncontrolled producer.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `core/Node.kt` | Add `source: NodeId? = null` to `EventStream` + doc, following the slice-3.1/3.6 optional-field pattern | Small |
| JSON ingest (`core` ingest path) | Parse the optional `source` author-id reference into a `NodeId` | Small |
| `hashing/CanonicalEncoder.kt` (`encodeEventStream`) | Emit `source` (hash) only when non-null, trailing the slice-3.6 fields; preserve byte-identity when null | Small |
| `verifier/Verifier.kt` + `VerifyError.kt` | New variants `StreamSourceOnNonExternal`, `StreamSourceNotAnOpener`, `StreamSourceEffectMismatch`, `StreamSourceTypeMismatch`, `ByteStreamSourceRequiresBlockProducer`; an opener-target registry (which builtin `target`s are IO-opens, mirroring `WellKnownEffect`) | Medium |
| `runtime/MachineGroup.kt` / `MachineGroupHandle.kt` | Carry the per-stream binding (resolved source NodeId → opener); `validateTopology` gains the group-start coverage check raising `ExternalStreamSourceEffectUncovered`; `ioBackedExternalStreams()` + transport-effect-union helper | Medium |
| `runtime/ExternalStreamFeeder.kt` (new) | The feeder coroutine: open-once handle, `withContext(Dispatchers.IO)` pull loop, push through the stream's `OverflowDispatcher`, single-`finally` `Stream.Close`, EOF channel-close, scrubbed-failure metric | Medium |
| `runtime/StateMachineRuntime.kt` (`runGroup`) | New pass after actor/broadcast spawn: evaluate each bound `source` once under group capabilities, register the feeder as the stream's producer, `scope.launch` it, add its `Job` to the handle's job list for `await`/`cancel` | Medium |
| `runtime/RuntimeMetrics.kt` | Per-stream `feederFailed` / `feederFailureKind` fields alongside `overflowDrops` | Small |
| `runtime/EventRecorder.kt` / `MachineActor.kt` | No change — the existing consumer-side `record(wrapEvent(...))` site is already the correct post-policy capture. This non-change is the core of the replay story | None |
| `interpreter/` | No change — the feeder reuses the shipped `Stream.Receive` / `Stream.Close`; sandbox + timeout enforcement come for free | None |
| `authoring/LayerAGrammar.kt` | Extend the External-EventStream grammar code to accept an optional trailing `source` reference arg | Small |
| `cli/Main.kt` | No new flag (the win over the CLI-bind alternative); `group` works once the graph carries `source` | None |
| `corpus/` + tests | New verify-and-run exemplar (machine consuming a `source`-bound stream over an injected transport); `BridgedStreamTest` (scenarios 1–11), `BridgedStreamReplayTest` (12–13); `CorpusHashingTest` confirms existing hashes unchanged | Medium |

**Order of work.** (1) `source` field + ingest + encoder + `CorpusHashingTest` green (proves zero hash churn). (2) Verifier well-formedness rules + opener-target registry + verifier tests. (3) `ExternalStreamFeeder` + `runGroup` pass + group-start coverage check + lifecycle/backpressure tests (scenarios 1–11) — the smallest end-to-end slice over an injected transport, no real network. (4) Replay tests (12–13) — confirm the consumer-side recording already replays bridged streams with no feeder change. (5) Layer A grammar + corpus exemplar.

**Not in this slice.** Decoded/record-oriented events, the SSE framing decoder, drop policies on framed streams, group-level recorded replay, recording persistence, multiple bridged streams per machine, streaming send, and process-source streaming. Each is listed in § 8 with its unblocker.

Best executed with `strand-add-node`-adjacent discipline (it touches the encoder, verifier, and corpus across the same six modules) even though it adds a field, not a category — the field is verifier-load-bearing.

## References

**Outgoing references:**
- [`design/state-machines.md`](../design/state-machines.md) — the push-based actor runtime, `External` EventStream, `OverflowPolicy`, `StreamBus`, `EventRecorder`, `MachineGroupHandle.externalInputs`; the machinery the bridge feeds into
- [`proposals/implemented/streaming-async-io.md`](implemented/streaming-async-io.md) — Q-045 § 8 names this bridge and its four sub-questions; the pull primitive (`Stream.Receive` / `Close`, `streamReceiveTimeoutMillis`) the feeder calls
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — E-004 `Network.Receive` vs. the runtime-implicit E-029 `StateMachine.Receive`; the static-set effect closure
- [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — additive optional-field versioning; why `source == null` preserves existing hashes
- [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — mandatory effect declarations; the closure-release discipline the group-start coverage check extends
- [`decisions/ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md) — atomic pure transitions; why a blocking pull cannot live inside a transition
- [`open-questions.md`](../open-questions.md) — Q-046 (this question), Q-044 (the harm bound the soundness argument preserves), Q-009 / Q-015 / Q-040 / Q-041 / Q-042 (ordering, backpressure, timeout, sandbox, redaction)

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-046 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section
