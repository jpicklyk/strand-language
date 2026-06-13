# Long-running machine groups and snapshot persistence

**Document:** `proposals/implemented/long-running-groups.md`
**Status:** Implemented (2026-06-13 — see implementation note below)
**Date:** 2026-06-13
**Concerns:** [`design/state-machines.md`](../../design/state-machines.md), [`proposals/implemented/state-machines-runtime-step-3.md`](state-machines-runtime-step-3.md) (the `Snapshot` / `EventRecorder` / overflow / supervision machinery extended here), [`proposals/implemented/state-machines-runtime-step-2.md`](state-machines-runtime-step-2.md) (the actor/coroutine/Channel group runtime), [`proposals/implemented/embeddable-runtime.md`](embeddable-runtime.md) (the Q-054 `StrandRuntime` facade and `HostPolicy` this builds on), [`proposals/implemented/streaming-async-io.md`](streaming-async-io.md) (the `streamReceiveTimeoutMillis` precedent for an idle-blocking budget), [Q-059](../../open-questions.md#Q-059), [Q-008](../../open-questions.md#Q-008) (the distributed runtime this is prerequisite to and explicitly distinct from), [Q-058](../../open-questions.md#Q-058) (the persistent store that reuses this codec)
**Scope:** Medium-large

> **Implementation note (2026-06-13).** All three pieces shipped in the Kotlin/JVM reference implementation in three commits, single-process only (multi-node distribution stays Q-008, out of scope), built on the Q-054 `StrandRuntime` facade with no reintroduced process-global state. Full suite **2255 tests green** (2223 baseline + 32 new); zero golden-hash impact (the `Value` codec is runtime-state serialization, strictly separate from the node canonical encoding — `design/canonical-encoding.md` and every program hash are untouched).
>
> **Piece 1 — `ValueCodec` ([`runtime/ValueCodec.kt`](../../impl-kotlin/runtime/src/main/kotlin/org/strand/runtime/ValueCodec.kt)).** A deterministic, reversible JSON serialization of the snapshot-relevant first-order `Value` subset (`IntV` / `FloatV` / `StringV` / `BoolV` / `UnitV` / `BytesV` / `ProductV` / `SumV` / `MapV` / `SetV`, with `Cons`/`Nil` list spines falling out of `SumV`). Determinism: `ProductV` fields sorted by name, `MapV` entries and `SetV` elements sorted by their encoded representation, a fixed non-pretty `Json` config; reversibility: `decode(encode(v)) == v` over the subset. `FloatV` is string-encoded so NaN / ±Inf / signed zero round-trip exactly. The six runtime-only variants — `Closure`, `FixpointFn`, `ForeignFn`, `Resource`, `ToolDefV`, `ResponseSchemaSpecV` — are the documented **non-snapshotable** case: encoding any of them raises a structured `ValueCodecError.NotSnapshotable(variant, reason)` rather than corrupting the snapshot (a transition function's *state* is first-order data; a Closure captures an evaluation environment and a Resource is a live OS handle, neither of which can round-trip). `EventCodec.decodeValue` now **delegates** to `ValueCodec.decode`, subsuming the formerly decode-only codec; the legacy event JSON is a strict subset so existing event files decode unchanged, and per-element malformations are re-wrapped as `EventCodecError` to preserve the CLI's error contract. `:runtime` gained a direct `kotlinx-collections-immutable` dependency (it reached the module only transitively via `:interpreter`'s `implementation` scope). The codec is a self-contained component with no snapshot/group coupling — Q-058's store reuses it. `ValueCodecTest` (18 tests).
>
> **Piece 2 — snapshot persistence ([`runtime/SnapshotCodec.kt`](../../impl-kotlin/runtime/src/main/kotlin/org/strand/runtime/SnapshotCodec.kt) + facade).** A self-describing JSON wire format over the slice-3.3 `Snapshot` (machineHash as the lowercase-hex multihash, `ValueCodec`-encoded `snapshotState`, `processedEventCount`, optional `ValueCodec`-encoded recorder tail); `SnapshotCodecError` for a malformed document, `ValueCodecError.NotSnapshotable` propagated when state holds a non-serializable value. The facade ([`runtime/StrandRuntime.kt`](../../impl-kotlin/runtime/src/main/kotlin/org/strand/runtime/StrandRuntime.kt)) gains `writeSnapshot` / `readSnapshot` (serialization, no policy install) and `resume` (policy-installed restart from a checkpointed state, delegating to the existing `StateMachineRuntime.resume`; the `SnapshotMachineHashMismatch` integrity check is unchanged). `nodeIdToHash` is a `resume` parameter rather than a `ProgramImage` field so the Q-054 image shape and tests stay untouched. Minimal state is persisted — state + cursor + the optional recorder tail (the tail is for reconstructing pre-snapshot *history*, not resume correctness, since transitions are pure); the `EvaluationLimits` budget is deliberately NOT persisted (resume resets it by design). `SnapshotPersistenceTest` (6 tests), including the load-bearing **restart-resume equivalence test**: run a group partway, snapshot, `writeSnapshot` to a temp file, drop the in-memory objects, `readSnapshot` in a fresh `StrandRuntime` (a new `HostPolicy` — the "new process"), `resume` over the remaining events, and assert the post-snapshot trajectory (final state + per-step state-after + outputs) equals an uninterrupted single run.
>
> **Piece 3 — service-mode driver + per-event limits ([`runtime/GroupService.kt`](../../impl-kotlin/runtime/src/main/kotlin/org/strand/runtime/GroupService.kt), [`core/EvaluationLimits.kt`](../../impl-kotlin/core/src/main/kotlin/org/strand/core/EvaluationLimits.kt), [`runtime/MachineActor.kt`](../../impl-kotlin/runtime/src/main/kotlin/org/strand/runtime/MachineActor.kt), facade).** `EvaluationLimits` gained `perEventStepBudget` / `perEventWallClockBudgetMillis` (both default null = the current cumulative-lifetime behavior; additive, runtime-state-only, zero golden impact) plus a `perEvent()` deriver. When `perEventStepBudget` is non-null, `MachineActor.stepOnce` allocates a fresh `EvalCounters` under a per-event-derived `EvaluationLimits` for each event instead of one for the actor's lifetime, so a long-lived service-shaped actor is no longer killed by the whole-run wall-clock / cumulative step budget for being alive; idle blocking `select` waits consume no budget under either model (they advance no step), mirroring the Q-045 `streamReceiveTimeoutMillis` precedent. The synchronous fold (`runMachine` / `resume`) is unchanged — batch by definition. `GroupService` (`StrandRuntime.serveGroup`) keeps external inputs open and runs until the host calls `stop()` (close inputs, await) or a real halt occurs (denial / resource exhaustion / supervised teardown — the existing halt surfaces, unchanged): `send` / `outputs` / `snapshot` / `metrics` / `denials` / `stop` / `stopNow` / `awaitHalt`. The Q-044 harm bound is preserved (per-event budgets still bound each event's work, capabilities still gate every effect, overflow/supervision semantics untouched) and the batch path is byte-identical with `perEventStepBudget = null`. `ServiceModeTest` (7 tests): per-event-vs-cumulative budget contrast, bound-runaway-event, per-actor isolation, the byte-unchanged batch regression, survive-idle-across-virtual-time + `stop()`-drains, and unknown-stream rejection.
>
> **Deviations from the proposal worth recording.** (a) `serveGroup` does not add a CLI `--serve` subcommand — the proposal sketched a service shape but the slice ships the facade surface only; a CLI serve mode is a thin follow-up that any host can already reach through the facade. (b) The `GroupService` rejection test exercises the shared name-resolution guard via the non-suspend `outputs(name)` rather than the suspend `send(name)`, because a nested `runBlocking` deadlocks a `TestScope`; the guard is identical. (c) The test scopes the policy install/restore manually with `Builtins.snapshot()` / `restore()` around the suspend driving body, for the same reason the CLI's `withGroupInstalled` + `runBlocking` nesting cannot be reused inside `runTest`.

A `MachineGroup` today exists for the duration of one blocking host call: the `strand group` CLI and the Q-054 facade `runGroup` both send a routed-events file, close the external inputs, and drain until every actor halts. The default 30-second wall-clock budget terminates anything server-shaped, and the runtime's `Snapshot` captures a live `Value` but no `Value` byte codec exists anywhere in the implementation (`EventCodec` is decode-only), so no snapshot can leave the process and no group survives a restart. This proposal closes the single-process service story in three coordinated pieces — a canonical `Value` codec, snapshot persistence across restart, and a non-batch service-mode group driver with a long-running limits model — built on the Q-054 facade without reintroducing process-global state. Multi-node distribution is Q-008 and explicitly out of scope.

## 1. Problem statement

Three concrete gaps, each load-bearing for running a Strand program as a service rather than a batch task.

**No `Value` codec, so snapshots cannot persist.** Slice 3.3 of the state-machines step-3 work (`proposals/implemented/state-machines-runtime-step-3.md`) shipped an in-memory `Snapshot` data class — `machineHash`, `snapshotState: Value`, `processedEventCount`, optional `recordedEventsPreSnapshot: List<Value>` — whose head comment explicitly records that serialization into bytes is the host's responsibility and that "no persistent wire format for snapshots is specified." The `recorderHash: Hash` field the proposal sketched was dropped because "hashing requires a canonical encoder over runtime `Value`." `runtime/EventCodec.kt` decodes a JSON event format into `Value`s for the `strand machine` CLI but is, by its own doc comment, "decode-only" — "if a future step wires up snapshot/replay-from-log, an `encode` side will land alongside it." That future step is this one. Without an encode side, a `Snapshot` is a heap object that dies with the process; a group cannot be checkpointed to disk and resumed in a fresh JVM.

**The whole-run wall-clock terminates service-shaped programs.** `EvaluationLimits.wallClockBudgetMillis` defaults to 30 seconds and bounds the *entire* evaluation. In the async group path each actor allocates one `Interpreter.EvalCounters` at construction (`MachineActor.evalCounters`) and accumulates `maxSteps`, `maxAllocatedValues`, and the wall-clock budget across *every event for the actor's whole lifetime*. A long-lived actor that processes one small event per second is doing trivial per-event work, yet after 30 wall-clock seconds — or after enough cumulative steps — it trips `ResourceExhaustion` and halts, purely for being alive. The blocking `select` over input channels in `MachineActor.run` advances no interpreter step while idle, so the idle wait is invisible to the step-sampled wall-clock check — but the cumulative budget over active processing still kills the actor. The harm bound (Q-044) is a *per-unit-of-work* property; binding it to a single monotonic whole-process budget conflates "this event did too much work" with "this server has been up too long."

**The group driver is batch-only.** Both `strand group` (CLI) and the facade's `runGroup` usage close all host-feedable external inputs immediately after sending the routed-events file, which causes the consuming actors to see channel EOF and halt. There is no driver that keeps inputs open and accepts events over time — the server shape that `Http.Listen` / `Http.Accept` (which already exist) invite. The facade exposes `runGroup` returning a live `MachineGroupHandle` and `withGroupInstalled` to scope the policy install across the group lifecycle, so the structural seam is present; what is missing is a driver that drives this seam in serve mode and a limits model that does not kill the server.

This question is distinct from and prerequisite to the Q-008 multi-node engineering. Everything here is single-process: one JVM, one `StrandRuntime`, one `HostPolicy`.

## 2. Prior art

- **Akka Persistence** ([Akka docs § Persistence](https://doc.akka.io/docs/akka/current/typed/persistence.html)) — event-sourced actors reconstruct state by replaying a journal; snapshots short-circuit replay. Strand's content-addressed model makes the snapshot's machine identity a hash; the codec here is the serialization Akka delegates to a pluggable serializer. The key Akka discipline imported: a snapshot plus the post-snapshot event suffix is sufficient for deterministic recovery, so the codec need only serialize state plus the recorder tail, not a full mutable actor.
- **Erlang/OTP `sys:get_state` and release handling** ([Erlang `sys`](https://www.erlang.org/doc/man/sys.html)) — a running process's state can be extracted and a successor process resumed from it. The integrity question Strand adds is that the resuming code must be the *same* code; OTP relies on module versioning, Strand on the `machineHash` already recorded in `Snapshot`.
- **gVisor / container checkpoint-restore (CRIU)** ([CRIU](https://criu.org/)) — process-level checkpoint that serializes the full address space. The contrast is instructive: CRIU must serialize *everything* including open file descriptors, and handles the un-serializable (live sockets) by either erroring or requiring application cooperation. Strand's `Value.Resource` is exactly the live-handle case; the codec takes the cooperative-error stance (a live Resource in snapshotted state is a structured non-snapshotable error) rather than attempting to migrate OS handles.
- **CBOR / IPLD dag-cbor** ([RFC 8949](https://www.rfc-editor.org/rfc/rfc8949), [IPLD](https://ipld.io/)) — Strand already uses canonical CBOR over `Node` for content hashing (ADR-003). The `Value` codec is a *separate* serialization with the same deterministic discipline (sorted map keys, fixed variant tags) but a different domain (post-evaluation runtime values, not graph nodes). Reusing the JSON shape `EventCodec` already decodes keeps the two domains legible and avoids coupling the runtime-state format to the node hash.
- **Reactive Streams / per-request timeouts** ([Reactive Streams](https://www.reactive-streams.org/)) and the Q-045 `streamReceiveTimeoutMillis` precedent — a blocking receive that advances no step is bounded at the I/O layer, not by the step sampler. The per-event budget here is the symmetric move on the compute side: each event's processing gets a bounded budget that resets between events, so an idle server consumes no budget and a busy event is still bounded.

## 3. Recommended approach

Three pieces, shippable in order, each on the Q-054 facade.

**Piece 1 — `ValueCodec`: a deterministic, reversible serialization of the snapshot-relevant `Value` subset.** A new `runtime/ValueCodec.kt` with `encode(Value): JsonElement` / `decode(JsonElement): Value` (and `encodeToString` / `decodeFromString` convenience) over the first-order serializable subset: `IntV`, `FloatV`, `StringV`, `BoolV`, `UnitV`, `BytesV`, `ProductV`, `SumV`, `MapV`, `SetV`, and arbitrary nesting of these (lists are the ordinary `Cons`/`Nil` `SumV` spine, so they fall out of `SumV` for free). The format extends the tagged shape `EventCodec` already decodes — `{"tag": "int", "value": N}`, `{"tag": "product", "fields": {...}}`, `{"tag": "sum", "case": C, "payload": ...}` — adding `bytes` (base64), `float`, `map` (a sorted association list), and `set` (a sorted element list). `EventCodec.decodeValue` is refactored to delegate to `ValueCodec.decode` so the two stay in lockstep (the codec subsumes the decode-only `EventCodec`). Determinism: map and set entries are emitted in a canonical order (by their own encoded-bytes ordering), and `JsonObject` field order is fixed, so `encode` is a function of the `Value` alone. Reversibility: `decode(encode(v)) == v` for every value in the subset, asserted by a round-trip property test over a generator.

The non-serializable variants — `Closure`, `ForeignFn`, `FixpointFn`, `Resource`, `ToolDefV`, `ResponseSchemaSpecV` — are *not* in the subset. `encode` on any of them throws a structured `ValueCodecError.NotSnapshotable(value, reason)` naming the variant and why (a Closure captures an evaluation environment that is not content-addressable as data; a Resource is a live OS handle that cannot be re-opened deterministically). This is the documented policy: **a transition function's *state* is first-order data; a Closure or live Resource appearing *in state* is a non-snapshotable case that fails with a structured error rather than silently corrupting the snapshot.** The codec is designed as a reusable component — Q-058's persistent store will reuse it for serializing run results and cached values, so it lives in `:runtime` with no dependency on the snapshot or group machinery.

**Piece 2 — snapshot persistence across restart.** A `SnapshotCodec` (alongside `ValueCodec`) serializes a `Snapshot` to a self-describing JSON document: `machineHash` (multihash string), `snapshotState` (via `ValueCodec`), `processedEventCount`, and `recordedEventsPreSnapshot` (a `ValueCodec`-encoded array, or null). `MachineGroupHandle` gains nothing new for capture (slice 3.3's `snapshot(instance)` already produces the `Snapshot`); the new surface is on the facade: `StrandRuntime.writeSnapshot(snapshot, path)` and `StrandRuntime.readSnapshot(path): Snapshot`, plus `StrandRuntime.resume(program, machine, snapshot, additionalEvents, capabilities)` which threads the policy install and delegates to the existing `StateMachineRuntime.resume`. Restore is deterministic and re-establishes the machine under the supplied `HostPolicy` (the facade owns the install/restore). The integrity check (`SnapshotMachineHashMismatch` when the resumed machine's hash differs) is already in `StateMachineRuntime.resume` and is unchanged. The equivalence test: run a machine partway, snapshot, serialize to a temp file, drop the in-memory objects, deserialize in a *fresh* `StrandRuntime` (new `HostPolicy`), resume over the remaining events, and assert the resulting trajectory equals an uninterrupted single run.

We snapshot the *minimal* state needed for deterministic resume: the current `Value` state plus the processed-event count, and (only when `recordInputs` was on) the pre-snapshot recorder tail. We do *not* persist the `EvalCounters` budget — slice 3.3 already resets it on resume by design, and persisting it would couple the snapshot to the limits model. If a host has `recordInputs` off, the snapshot is state-plus-cursor only; replay from the snapshot point forward is still deterministic because transitions are pure (the recorder tail is for reconstructing pre-snapshot *history*, not for resume correctness). This is the documented choice for keeping the snapshot small: the recorder log is not the snapshot's source of truth; state is.

**Piece 3 — service-mode driver and a per-event limits model.** Two coordinated changes.

*Per-event limits.* A new optional field `EvaluationLimits.perEventStepBudget: Long?` (default null, meaning "use the cumulative whole-actor budget — the current behavior, batch-compatible"). When non-null, the async actor allocates a *fresh* `EvalCounters` per event instead of one for its lifetime, and the wall-clock sub-budget for a single event's processing is `perEventWallClockBudgetMillis` (a sibling optional field, default null). This mirrors `streamReceiveTimeoutMillis`: idle blocking waits in the `select` are not step-budget consumption (they never were — the `select` advances no step), and each event's processing gets a bounded, *resettable* budget, so the group as a whole is not killed at 30 s for being alive. The harm bound is preserved exactly: per-event budgets still bound the work any single event can do, capabilities still gate every effect, and the overflow/supervision semantics are untouched — what changes is only that "total lifetime work" stops being a single capped counter when the host opts into per-event budgeting.

*Service driver.* A `StrandRuntime.serveGroup(program, group, scope, onEvent, ...)`-shaped entry, or more precisely a thin driver object `GroupService` returned by the facade, that does NOT close the external inputs and drain-to-halt. It keeps inputs open, exposes `send(streamName, value)` / `outputs(streamName)` over the live `MachineGroupHandle`, and runs until the host calls `stop()` (which closes inputs and awaits) or a real halt occurs (an actor's denial / resource-exhaustion / supervised termination — the existing halt surfaces are unchanged). The batch path (`strand group`, the existing `runGroup` usage) is untouched: it continues to close-and-drain, and with `perEventStepBudget = null` the limits model is byte-for-byte the current behavior, so the existing corpus and state-machine tests are unchanged.

## 4. Detailed mechanism

### 4.1 `ValueCodec` format

The format is a tagged JSON encoding, one object per value, extending `EventCodec`'s existing tags:

| `tag` | `Value` variant | Payload fields | Notes |
|-------|-----------------|----------------|-------|
| `int` | `IntV` | `value`: JSON number (long) | unchanged from EventCodec |
| `float` | `FloatV` | `value`: string (see below) | string-encoded to round-trip NaN / Inf / signed zero exactly |
| `string` | `StringV` | `value`: JSON string | unchanged |
| `bool` | `BoolV` | `value`: JSON bool | unchanged |
| `unit` | `UnitV` | — | unchanged |
| `bytes` | `BytesV` | `value`: base64 string | new |
| `product` | `ProductV` | `fields`: object of name → encoded value | field order fixed by sorted key on encode; decode is order-insensitive |
| `sum` | `SumV` | `case`: string; `payload`: encoded value or absent | unchanged; payload-less variants (Nil, None) omit `payload` |
| `map` | `MapV` | `entries`: array of `{key, value}` encoded-value pairs | sorted by encoded-key bytes for determinism |
| `set` | `SetV` | `elements`: array of encoded values | sorted by encoded-element bytes for determinism |

`FloatV` is string-encoded (`"NaN"`, `"Infinity"`, `"-Infinity"`, or `Double.toString` otherwise) and decoded with `String.toDouble` / explicit special-case handling, because JSON numbers cannot represent NaN/Inf and `doubleOrNull` loses the distinction between signed zeros in some serializers. This matches the Q-066 determinism discipline (FloatLit hashes raw IEEE 754 bits) at the runtime-state layer.

Determinism rule: `encode` produces a canonical `JsonElement` — `ProductV` fields sorted by name, `MapV` entries and `SetV` elements sorted by their encoded representation's serialized bytes (UTF-8 of `encodeToString`). `encodeToString` uses a fixed `Json { }` configuration with no pretty-printing. Two equal `Value`s in the subset produce byte-identical strings.

### 4.2 Non-snapshotable values

```kotlin
sealed class ValueCodecError(message: String) : RuntimeException(message) {
    class NotSnapshotable(val variant: String, val reason: String) :
        ValueCodecError("cannot snapshot a $variant value: $reason")
    class MalformedEncoding(val detail: String) :
        ValueCodecError("malformed Value encoding: $detail")
}
```

`encode` on `Closure` / `FixpointFn` / `ForeignFn` raises `NotSnapshotable("Closure", "captures an evaluation environment that is not content-addressable as data; ...")`; on `Resource` raises `NotSnapshotable("Resource", "a live OS handle (kind=$kind) cannot be re-opened deterministically across a restart")`; on `ToolDefV` / `ResponseSchemaSpecV` raises `NotSnapshotable` naming the NodeId-identity carrier. The error is uncatchable at the codec layer (it is a host-facing serialization failure, not an in-language `Attempt`-catchable error) and surfaces to the host calling `snapshot` / `writeSnapshot`.

Rationale for the policy: a state machine's *state type* is by construction first-order (the transition function consumes and produces a `(state, event) -> {state, outputs}` value; state is data). A Closure or Resource appearing in state is a program that stored a callable or a handle as state — legal at runtime but not snapshotable, and far better surfaced as a precise error at snapshot time than silently dropped or corrupted. This is the same cooperative-error stance CRIU takes for un-migratable handles.

### 4.3 `SnapshotCodec` and the restart surface

```
{
  "machineHash": "<multihash string>",
  "snapshotState": { ...ValueCodec... },
  "processedEventCount": 500,
  "recordedEventsPreSnapshot": [ { ...ValueCodec... }, ... ]   // or null
}
```

`SnapshotCodec.encode(snapshot): String` and `decode(text): Snapshot`. `machineHash` serializes via the existing `Hash` multihash string form. On `decode`, the `Hash` is parsed back; `StateMachineRuntime.resume` already enforces `machineHash` integrity against `nodeIdToHash[machineId]`.

Facade additions:

```kotlin
fun StrandRuntime.writeSnapshot(snapshot: Snapshot, path: Path)
fun StrandRuntime.readSnapshot(path: Path): Snapshot
fun StrandRuntime.resume(
    program: ProgramImage,
    machine: NodeId,
    snapshot: Snapshot,
    additionalEvents: List<Value>,
    capabilities: CapabilitySet = CapabilitySet.EMPTY,
    nodeIdToHash: Map<NodeId, Hash>,                 // from finalize; carried on ProgramImage already? see below
    verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
): Trace
```

`resume` installs the policy (`policy.withInstalled`), constructs a `StateMachineRuntime` over the program image, and calls the existing `StateMachineRuntime.resume`. `ProgramImage` already carries `store` / `root` / `hashToNodeId`; for the snapshot integrity check we need the forward `nodeIdToHash` map. Rather than widen `ProgramImage` (which would ripple into Q-054's tests), `resume` takes `nodeIdToHash` as a parameter — the host has it from `Hasher.finalize` exactly as it has `hashToNodeId`.

### 4.4 Per-event limits model

`EvaluationLimits` gains two optional fields (additive, default null — zero behavior change for every existing caller and every golden, since these are runtime-state, never node-encoding):

```kotlin
val perEventStepBudget: Long? = null,            // null = cumulative lifetime budget (current behavior)
val perEventWallClockBudgetMillis: Long? = null, // null = cumulative
```

`MachineActor` reads them: when `perEventStepBudget` is non-null, the actor allocates a fresh `EvalCounters` *per event* (inside the loop, before `stepOnce`) carrying a derived `EvaluationLimits` whose `maxSteps = perEventStepBudget` and `wallClockBudgetMillis = perEventWallClockBudgetMillis ?: limits.wallClockBudgetMillis`, instead of the lifetime `evalCounters`. The `select` idle wait is outside the counter entirely (it always was). When both are null, the actor uses the lifetime `evalCounters` exactly as today.

The synchronous `runMachine` / `resume` fold is unchanged (it is batch by definition — a fixed event list — and one logical evaluation with one budget is the right model there). The per-event budget is an async-actor-only concept.

Harm-bound preservation: per-event budgeting *tightens* the bound on any single event (each event independently bounded) while removing the artificial whole-lifetime cap. Capability checks fire on every effect regardless of budget model; overflow policies, supervision, and the Q-046 source-coverage gate are untouched. A malicious or runaway transition still cannot exceed its per-event budget, and the group's effects are still exactly `closure(g) ∩ C ∩ B ∩ P`.

### 4.5 Service driver

```kotlin
class GroupService internal constructor(
    private val handle: MachineGroupHandle,
    private val scope: CoroutineScope,
    /* name→streamId resolution, etc. */
) {
    fun send(streamName: String, value: Value)            // push onto a live external input
    fun outputs(streamName: String): ReceiveChannel<Value> // drain a live external output
    val instances: Map<InstanceId, MachineInstanceHandle>
    fun metrics(): RuntimeMetrics
    fun snapshot(instance: InstanceId): Snapshot
    suspend fun stop()   // close host-feedable inputs, await natural halt
    suspend fun stopNow() // cancel
    suspend fun awaitHalt() // suspend until a real halt (denial / exhaustion / all-actors-done)
}

fun StrandRuntime.serveGroup(
    program: ProgramImage, group: MachineGroup, scope: CoroutineScope,
    verifierNodeTypes: Map<NodeId, TypeExpr>? = null,
): GroupService
```

`serveGroup` installs the policy (the caller scopes it via `withGroupInstalled`, exactly as the batch CLI does), calls `runGroup`, and wraps the handle in a `GroupService` that does *not* close inputs. The host drives it over time. `stop()` closes the host-feedable inputs and awaits; `awaitHalt()` returns when an actor halts on a real reason. This is purely additive on the facade — `runGroup` itself is unchanged, so the batch path is unaffected.

### 4.6 Worked example

A counter machine with one external input (`in`) and one external output (`out`), `recordInputs = true`, `perEventStepBudget = 10_000`. The host `serveGroup`s it, sends three `IntV` events spaced one virtual-minute apart (well past the 30 s whole-run budget that would have killed a batch run), drains `out` after each, then `snapshot(instance)`. The snapshot's `snapshotState` is `IntV(3)` (the accumulated count), `processedEventCount = 3`, `recordedEventsPreSnapshot = [IntV(e0), IntV(e1), IntV(e2)]`. `writeSnapshot` serializes it to `{"machineHash":"bafy...","snapshotState":{"tag":"int","value":3},"processedEventCount":3,"recordedEventsPreSnapshot":[...]}`. A fresh `StrandRuntime` reads it back and `resume`s over two more events; the post-snapshot trace equals events 4–5 of an uninterrupted five-event run.

## 5. Verifier rules

None. Everything here is runtime-state serialization and runtime driving — no node category, no encoding change, no new well-formedness rule. The `Value` codec is deliberately *not* the node canonical encoding (`design/canonical-encoding.md` and golden program hashes are untouched).

## 6. Interpreter / runtime semantics

- `ValueCodec.encode` is total over the serializable subset and raises `ValueCodecError.NotSnapshotable` on the six runtime-only variants. `decode` is total over well-formed encodings and raises `MalformedEncoding` otherwise. Round-trip identity holds on the subset.
- `EventCodec.decodeValue` delegates to `ValueCodec.decode`; the legacy event JSON is a strict subset of the codec format, so every existing event file decodes unchanged.
- `MachineActor`: when `limits.perEventStepBudget != null`, a fresh `EvalCounters` per event under a per-event-derived `EvaluationLimits`; otherwise the lifetime counter (current behavior). No change to the `select`, the recorder, output dispatch, overflow, or halt translation.
- `StateMachineRuntime.resume` is unchanged; the facade wraps it with policy install and snapshot (de)serialization.
- `GroupService` keeps inputs open; `stop()` closes them and `await()`s. Halt surfaces (`HaltReason`, `denialReport`) are unchanged.

## 7. Test scenarios

1. **`ValueCodec` round-trips every primitive** — IntV (incl. Long.MIN/MAX), FloatV (incl. NaN, ±Inf, ±0.0), StringV (incl. unicode), BoolV, UnitV, BytesV (incl. empty) each `decode(encode(v)) == v`.
2. **`ValueCodec` round-trips nested structure** — a ProductV containing a SumV containing a MapV and a SetV and a `Cons`/`Nil` list, deeply nested; round-trips and is byte-deterministic across two encodes.
3. **`ValueCodec` is deterministic for maps/sets** — a MapV / SetV built with different insertion orders encodes to byte-identical strings.
4. **`ValueCodec` rejects non-snapshotable values** — encoding a Closure, a FixpointFn, a Resource, a ToolDefV each raises `ValueCodecError.NotSnapshotable` naming the variant.
5. **`ValueCodec` rejects malformed input** — decoding `{"tag":"bogus"}` / a map missing `entries` / a bytes value with non-base64 raises `MalformedEncoding`.
6. **`EventCodec` still decodes legacy event files** — every existing corpus event JSON decodes to the same `List<Value>` as before (regression).
7. **`SnapshotCodec` round-trips a Snapshot** — encode then decode a `Snapshot` (with and without `recordedEventsPreSnapshot`) reproduces it; `machineHash` survives.
8. **Snapshot survives a simulated restart (equivalence)** — run a counter machine over 5 events, snapshot after 3, `writeSnapshot` to a temp file, drop the objects, `readSnapshot` in a fresh `StrandRuntime`, `resume` over the last 2; assert the post-snapshot trace equals events 4–5 of an uninterrupted 5-event `runMachine`.
9. **Resume integrity check still fires** — resuming a snapshot against a different-hash machine raises `SnapshotMachineHashMismatch` (regression over slice 3.3).
10. **Service-mode survives idle past the whole-run budget** — a `GroupService` with `perEventStepBudget` set processes events separated by virtual time well beyond 30 s (via `TestScope` virtual time) and does not halt with `ResourceExhaustion(WallClock)`; a batch `runGroup` of the same machine with the default limits is unaffected.
11. **Per-event budget still bounds a runaway event** — a transition that would exceed `perEventStepBudget` on one event halts *that* actor with `ResourceExhaustion(Steps)` and does not affect a sibling actor.
12. **Batch path is byte-unchanged** — with `perEventStepBudget = null`, an async group run produces the same trace and the same recorded events as before this change (regression; the existing async corpus 46–49 + 57 stand).
13. **`stop()` closes inputs and awaits** — a `GroupService` driven with two events then `stop()` halts cleanly with `EventsExhausted`-equivalent quiescence and the outputs drained.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Multi-node / distributed execution.** Q-008. Events crossing process boundaries, discovery, placement — all out of scope. This is the single-process service story only.
- **Persisting the whole group, not per-instance snapshots.** This slice snapshots one instance at a time (slice 3.3's surface). A group-level "snapshot all instances atomically" is a follow-up that needs the control-mailbox quiescence protocol slice 3.3 also deferred; per-instance snapshot is sufficient for the restart-resume property.
- **Persisting `EvalCounters` budget across a snapshot.** Deliberately not done — resume resets the budget (slice 3.3 already does), and persisting it would couple the snapshot format to the limits model. A long-lived host that wants cumulative accounting tracks it out-of-band.
- **A CBOR / binary `Value` wire format.** The codec is JSON (dag-json-shaped, reusing `EventCodec`'s vocabulary) for legibility and to subsume the existing decode-only `EventCodec`. A canonical-CBOR `Value` form (for a content-addressed *value* store, paralleling the node hash) is a Q-058 follow-up if that store wants value-level dedup; the JSON codec is the reusable component Q-058 starts from.
- **Snapshot of source-bound (Q-046) external streams.** A live bridged stream's feeder owns a live Resource; snapshotting an instance whose state somehow captured that Resource hits the `NotSnapshotable` path by design. Resuming a *bridged* group (re-opening the source) is a distribution-adjacent concern, deferred.

**Real research questions:**

- *Per-event budget calibration.* What is a sensible default `perEventStepBudget` for an agent-facing service host? Too low rejects legitimate transitions; too high re-admits the runaway. The number wants empirical data from real agent workloads (the same gap Q-040's limits calibration has).
- *Quiescence vs. liveness for `awaitHalt`.* A service with no events pending and all actors idle-blocked in `select` is "quiescent" but not "halted." `awaitHalt` returns only on a real halt; a host wanting "wake me when idle" needs a separate idleness signal (derivable from metrics: no `eventsReceived` delta over a window). Left to the host for now.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `runtime/src/main/kotlin/org/strand/runtime/ValueCodec.kt` | NEW — `encode`/`decode` over the serializable `Value` subset; `ValueCodecError` sealed class; deterministic map/set ordering; float string-encoding | Medium |
| `runtime/src/main/kotlin/org/strand/runtime/EventCodec.kt` | Refactor `decodeValue` to delegate to `ValueCodec.decode` (subsume the decode-only codec); add `bytes`/`map`/`set`/`float` to the recognized tags via the delegation | Small |
| `runtime/src/main/kotlin/org/strand/runtime/SnapshotCodec.kt` | NEW — `Snapshot` ↔ JSON via `ValueCodec`; `Hash` multihash (de)serialization | Small |
| `core/src/main/kotlin/org/strand/core/EvaluationLimits.kt` | Add optional `perEventStepBudget` / `perEventWallClockBudgetMillis` (default null; additive, no golden impact) | Small |
| `runtime/src/main/kotlin/org/strand/runtime/MachineActor.kt` | Per-event fresh `EvalCounters` under a per-event-derived limits when `perEventStepBudget != null`; lifetime counter otherwise | Small |
| `runtime/src/main/kotlin/org/strand/runtime/GroupService.kt` | NEW — live service driver wrapping `MachineGroupHandle` (send/outputs/snapshot/metrics/stop/awaitHalt) without close-and-drain | Small-Medium |
| `runtime/src/main/kotlin/org/strand/runtime/StrandRuntime.kt` | Add `writeSnapshot` / `readSnapshot` / `resume` / `serveGroup` facade methods (policy install via existing `withInstalled` / `withGroupInstalled`) | Small-Medium |
| `runtime/src/test/kotlin/org/strand/runtime/ValueCodecTest.kt` | NEW — round-trip, determinism, non-snapshotable, malformed | Medium |
| `runtime/src/test/kotlin/org/strand/runtime/SnapshotPersistenceTest.kt` | NEW — SnapshotCodec round-trip + the restart-resume equivalence + integrity-check regression | Medium |
| `runtime/src/test/kotlin/org/strand/runtime/ServiceModeTest.kt` | NEW — survive-idle-past-budget, per-event-bound-runaway, batch-unchanged, stop() | Medium |
| `proposals/README.md`, `open-questions.md`, `INDEX.md`, `ROADMAP.md`, `impl-kotlin/CLAUDE.md` | Bookkeeping per the standard pass | Small |

**Order of work.** ValueCodec first (the other two depend on it); then snapshot persistence (codec + facade + equivalence test); then the per-event limits and service driver (facade + actor + service test). Each commits independently.

**Not in this slice.** Distribution (Q-008); group-level atomic snapshot; persisting the budget; CBOR `Value` form; resuming bridged streams; idleness signaling.

## References

**Outgoing references:**
- [`design/state-machines.md`](../../design/state-machines.md) — the conceptual model whose replay-determinism property the snapshot-resume equivalence test exercises
- [`proposals/implemented/state-machines-runtime-step-3.md`](state-machines-runtime-step-3.md) — slice 3.3 `Snapshot` / `EventRecorder`, extended here with persistence and a codec
- [`proposals/implemented/state-machines-runtime-step-2.md`](state-machines-runtime-step-2.md) — the actor/coroutine/Channel group runtime and `runGroup`
- [`proposals/implemented/embeddable-runtime.md`](embeddable-runtime.md) — the Q-054 `StrandRuntime` facade and `HostPolicy` this builds on
- [`proposals/implemented/streaming-async-io.md`](streaming-async-io.md) — `streamReceiveTimeoutMillis`, the precedent for an idle-blocking budget
- [`open-questions.md`](../../open-questions.md) — Q-059 (this question), Q-008 (distribution, out of scope), Q-058 (reuses this codec), Q-044 (the harm bound preserved here)

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-059 points at this proposal
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — Known gaps section
