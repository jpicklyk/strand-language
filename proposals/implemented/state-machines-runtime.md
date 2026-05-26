# Layer 6: State Machines Runtime Architecture

**Document:** `proposals/implemented/state-machines-runtime.md`
**Status:** Step 1 implemented (Layer 6 step 1 of the Kotlin/JVM reference implementation, 2026-05-24); steps 2 and 3 remain deferred
**Date:** 2026-05-23 (proposed); 2026-05-24 (step 1 implemented)
**Concerns:** [`design/state-machines.md`](../../design/state-machines.md), [`decisions/ADR-007-state-machines.md`](../../decisions/ADR-007-state-machines.md), [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md) § State machine effects, [`design/distribution-model.md`](../../design/distribution-model.md), [Q-008](../../open-questions.md#Q-008), [Q-032](../../open-questions.md#Q-032)
**Scope:** Medium for step 1; subsequent steps medium–large

> **Implementation note (2026-05-24).** Step 1 was executed as described, with three points worth recording. (1) **Corpus-program numbering shifted from 33–37 to 41–45.** The proposal text was written before Q-031 (programs 33–35) and Q-030 (programs 36–40) landed; the state-machines step 1 corpus ships as 41-toggle-machine, 42-counter-machine, 43-counter-with-overflow-output, 44-request-response-echo, 45-bank-account-machine. (2) **OutputBatch convention is positional with field name `output_i`** indexed by position in the StateMachine's `outputStreams` list — the convention is pinned in `runtime/StateMachineRuntime.kt` and documented in `impl-kotlin/CLAUDE.md` as a deliberate "no concrete syntax → the position is the structural identity" choice. Empty OutputBatch (zero output streams) is the empty product `{}`. (3) **Transition function closure cached at instance start.** The runtime evaluates `node.transitionFn` once at `runMachine` entry under the supplied capability context and caches the resulting `Value.Closure` on the `MachineInstance`; per-event dispatch reuses the cached value via `Interpreter.applyCallable`. This avoids re-evaluating a possibly-let-bound transition function on every event and provides the forward-compatible seam for step 2's actor loop. Step 2 (async multi-machine) and step 3 (backpressure + supervision) remain deferred; the relevant Q-008 open question is unchanged. See `impl-kotlin/CLAUDE.md` Layer 6 step 1 notes for JSON schemas, StreamKind values, the runtime module's single public API surface (`StateMachineRuntime.runMachine`), and the EventCodec JSON shape consumed by the `strand machine` CLI subcommand.

This is "Layer 6 step 1" of the reference implementation: a deterministic synchronous trace runtime for state machines. The biggest remaining implementation piece in the milestone plan.

## 1. Reading summary: what the spec gives us vs what we have to decide

The state-machine corpus is the most coherent and architecturally opinionated section of Wave 3. `design/state-machines.md`, ADR-007, and the matching node-algebra entries settle the *what*: a state machine is a content-addressed tuple `(S₀, T, I, O)` where `T : (State, Event) → (State, [Event])` is a pure transition function, `I` and `O` are typed EventStream nodes, and the runtime is responsible for the event loop, scheduling, supervision, snapshotting, and distribution. Q-009 resolves event ordering to FIFO-per-stream with non-deterministic merge by default. Q-015 resolves backpressure to bounded queues with per-stream overflow policies. Q-010 resolves hot upgrade to a two-phase swap.

What the corpus deliberately does *not* settle is the implementation engineering. ADR-007's "Consequences" section is explicit that the runtime story is "substantial and not solved by this ADR" and points at Q-008 (high-throughput communicating-machines) as an open implementation problem with BEAM as the architectural baseline. Q-008 itself is the only state-machine open question still classed Open rather than Proposed.

Several specific points are underdetermined for an implementation:

- **What `[Event]` means as a Strand type.** The transition function's return type is `(State, [Event])`. With recursive types now landed (N-041/N-042), a Strand list `μ. Nil | Cons(head: Event, tail: <self>)` is expressible. Step 1 can either use this OR use the simpler fixed-arity workaround (one optional slot per declared output stream). Recommendation: ship the fixed-arity workaround for step 1 because list-of-events serialized through a Match for each event adds avoidable complexity at the test harness layer; switch to recursive lists in step 2 when multi-stream emit semantics matter.

- **Event polymorphism.** Multiple input streams may carry different event types, but T takes a single `Event` argument. The natural resolution is that the machine's `Event` type is a sum `InputEvent` covering all input stream types, with the runtime tagging the event with which stream it arrived on. Step 1 only supports one input stream so this question doesn't bite yet; step 2 has to answer it.

- **What an output event actually *is* once it leaves the transition function.** Step 1's fixed-arity OutputBatch sidesteps this; step 2 needs a tagged output value.

- **Effect declaration on the StateMachine node itself.** The spec says the StateMachine has an `effect*` edge that is the union of effects performed by T and its referents. The verifier in Layer 3 step 1 already computes effect closures of Lambdas; we extend the rule to require the StateMachine node's declared effects cover the transition function's closure plus the implicit `StateMachine.Send`/`Receive` effects for the stream wiring.

- **N-029 Transition** — the spec calls it "a guarded alternative to Match for cases dispatching on event type." Concretely, the transition function body is typically a Match over the Event sum type, but the algebra offers Transition as an alternative form. Step 1 can support both; programs that use Match work today, programs that use Transition are new. Recommendation: defer N-029 — parse it but don't ship a host that uses it.

## 2. Shipping strategy: split Layer 6 into three steps

### Step 1 — Synchronous trace runtime (this proposal)

In: N-027/N-028/N-029 node ADT, JSON ingest, verifier rules, an in-process synchronous "drive this machine over a fixed event list" runtime, deterministic replay, 3–5 corpus programs.

Out: actual concurrency, multiple machines, multiple input streams, real I/O, backpressure, supervision, hot upgrade.

The defining constraint: `fun runMachine(machine, initialContext, events: List<Value>): Trace`. The interpreter remains synchronous; no executor, no coroutine, no thread. Tests verifier and per-event evaluation correctness without touching concurrency.

### Step 2 — Async multi-machine runtime

In: per-machine actor abstraction using Kotlin coroutines, multiple input streams per machine, FIFO-per-stream + non-deterministic merge, inter-machine wiring, the four state-machine effect categories (E-028..E-031) propagating through the verifier and being checked at runtime, an initial Supervisor pattern as a corpus example.

Out: backpressure overflow policies beyond a hard cap, hot upgrade, distribution across executors.

The defining complexity: `Interpreter.eval` becomes potentially suspending. Either (a) every `eval` returns a `Continuation`-style suspended value, or (b) the per-machine actor calls `eval` purely (T is pure by spec) and the actor itself is the only suspending thing. **Recommend (b)** — dramatically cleaner. The interpreter stays unchanged; the runtime is built around it.

### Step 3 — Backpressure, supervision, persistence

In: bounded queues with the four overflow policies on EventStream, supervisor restart policies as corpus patterns, structured failure events surfaced from T to the supervisor, snapshot/replay-from-log for crash recovery, observable runtime metrics.

Out: hot upgrade (Q-010 — still deferred until multiple runnable graphs in the same process), distribution (separate milestone).

## 3. Layer 6 step 1 — the minimum coherent slice

### 3.1 Node ADT additions

Three new categories under `Node`:

- `Node.EventStream(eventType: NodeId, streamKind: StreamKind)` where `StreamKind = External | Internal | Output` is an enum. The verifier confirms `eventType` resolves to a Type node. No effects on an EventStream itself; purely declarative.

- `Node.StateMachine(transitionFn: NodeId, initialState: NodeId, inputStreams: List<NodeId>, outputStreams: List<NodeId>, effects: List<NodeId>)`. In step 1 we require `inputStreams.size == 1` (well-formedness rule, not algebra). `effects` is the union the runtime expects to need.

- `Node.Transition(guard: NodeId?, body: NodeId)` — parsed and verified but not used in any corpus program. Step 1 ships parsing for forward schema stability.

### 3.2 Trace API and the synchronous runtime

```kotlin
sealed class TraceStep {
    data class Step(val event: Value, val before: Value, val after: Value, val outputs: List<Value>)
    data class Halt(val finalState: Value, val reason: HaltReason)
}

data class Trace(val steps: List<TraceStep.Step>, val final: TraceStep.Halt)

class StateMachineRuntime(private val interpreter: Interpreter, private val store: NodeStore) {
    fun runMachine(
        machine: NodeId,                 // a StateMachine node
        events: List<Value>,             // already-typed event values
        capabilities: Set<NodeId> = emptySet()
    ): Trace
}
```

The runtime walks the events in order. Each step:

1. Look up the transition function value (evaluate `machine.transitionFn` once at start, cache the Closure).
2. Apply the transition function closure to `(currentState, event)` via the existing `applyClosure` machinery, with the supplied capability context.
3. Decompose the result: it's a `Value.ProductV` with two fields named `state` and `outputs`. The `outputs` field is itself a ProductV with one field per declared output stream, each holding either `SumV("Some", payload)` or `SumV("None", null)` — Option-shaped.
4. Append a TraceStep with the event, the before- and after-state, and any non-None outputs.
5. Replace `currentState` with the new state.
6. Continue.

Termination: in step 1, halt when (a) the event list is exhausted (`HaltReason.EventsExhausted`), or (b) the transition function returns a state of a designated `Terminated` sum case. **Recommend EventsExhausted only** in step 1; termination logic comes in step 2 when supervision needs it.

This runtime does not touch threading, coroutines, or queues. It is a deterministic fold over the event list, making the spec's "lifetime trajectory is a fixpoint over the event sequence" framing literal.

### 3.3 Corpus programs

Suggested:

- `33-toggle-machine.json` — State is a `Bool`. Event type is unit (one event: "toggle"). Transition function: `\(s, _) → (not s, none())`. Drive with `[toggle, toggle, toggle]`; trace shows `false → true → false → true`.

- `34-counter-machine.json` — State is `Int`. Event type is a sum `Increment | Decrement | Reset`. Transition uses Match on the event sum. No outputs.

- `35-counter-with-overflow-output.json` — Same counter, but with an output stream. Transition emits `Some(state)` to the output stream whenever the state crosses a threshold; otherwise emits `None`. First program with non-empty outputs.

- `36-request-response-echo.json` — Event type is a product `{requestId: Int, payload: String}`. State is `Unit` (stateless). Output emits a product `{requestId, response: payload}` for every event.

- `37-bank-account-machine.json` — **The capstone.** State is a product `{balance: Int, transactionCount: Int}`. Event type is a sum `Deposit(Int) | Withdraw(Int) | Query`. Transition uses Match + comparison + Option for the failure-on-overdraft case. Combines Match, product state, sum events, conditional outputs.

All five run under an empty capability context — pure transition functions, no effects in step 1.

(Note: corpus numbering starts at 33 because the existing corpus reaches 32 as of this writing — recursive types added 31 and 32. Renumber if intervening work claims any of 33–37.)

## 4. Verifier rules for the three new node types

`VerifyError` gains these cases:

- **EventStream**
  - `MalformedEventStream(at)` — `eventType` does not resolve to a Type node
  - `UnknownStreamKind(at, got)` — JSON ingest carries a string `streamKind`; if not one of `external|internal|output`, ingest rejects

- **StateMachine**
  - `StateMachineRequiresInputStream(at)` — `inputStreams` is empty
  - `StateMachineTransitionFnNotLambda(at, actualCategory)` — `transitionFn` does not resolve to a Lambda. (For step 1, no Fixpoint-wrapped transition function support.)
  - `StateMachineTransitionFnShapeMismatch(at, expected: TypeExpr, actual: TypeExpr)` — the transition Lambda's type does not match the expected `(State, Event) → (State, OutputBatch)` shape. **Load-bearing well-formedness rule.**
  - `StateMachineInitialStateTypeMismatch(at, expected, actual)`
  - `StateMachineInputStreamCountUnsupported(at, count)` — step 1 only: `inputStreams.size != 1`. Removed in step 2.
  - `StateMachineEffectCoverageViolation(at, missing)` — declared effects do not cover the transition function's effect closure
  - `OutputStreamEventTypeMismatch(at, streamIndex, expected, actual)`

- **Transition** (parsed but not yet exercised by any program in step 1)
  - `TransitionGuardNotBoolean(at, actualType)`
  - `TransitionStandalone(at)` — a Transition outside any host context

About a dozen new error cases, plus one new shape-check helper for the transition function signature. No new TypeExpr variants needed.

## 5. Runtime architecture: per-machine actor and event queue

Even in step 1, the data structures anticipate step 2 so we don't paint ourselves into a corner.

Core types:

```kotlin
internal class MachineInstance(
    val instanceId: InstanceId,           // runtime-generated UUID
    val node: Node.StateMachine,          // the machine definition
    val transitionFnValue: Value.Closure, // pre-evaluated transition Lambda
    var currentState: Value,              // mutable: replaced each step
    val capabilities: Set<NodeId>,        // capability context
    val inputQueues: Map<NodeId, ArrayDeque<Value>>,  // one per input stream NodeId
    val outputSinks: Map<NodeId, MutableList<Value>>, // one per output stream NodeId
    var halted: Boolean
)

internal data class InstanceId(val value: String)  // UUID toString
```

In step 1, `inputQueues` always has one entry; we pre-load it with the supplied event list before the run loop starts. `outputSinks` is a list-per-stream that the runtime appends to as the transition function emits; the runtime returns it as part of the `Trace`.

In step 2, `inputQueues` becomes `Map<NodeId, Channel<Value>>` (Kotlin coroutine channels), `outputSinks` becomes channels that connect to other machines' input queues, the run loop becomes a coroutine that `select`s across the input channels. `currentState` mutation, cached transitionFnValue, capability context, and instance identity are exactly the same. **This is the seam step 1 pays attention to.**

A single "transition step":

```kotlin
private fun stepOnce(instance: MachineInstance, event: Value): TraceStep.Step {
    val before = instance.currentState
    val resultValue = interpreter.applyClosure(
        fn = instance.transitionFnValue,
        args = listOf(before, event),
        capabilities = instance.capabilities
    )
    val resultProduct = resultValue as Value.ProductV
    val newState = resultProduct.fields["state"]!!
    val outputBatch = resultProduct.fields["outputs"]!! as Value.ProductV
    instance.currentState = newState
    val emitted = mutableListOf<Value>()
    for ((i, outputStreamId) in instance.node.outputStreams.withIndex()) {
        val slotName = "output_$i"  // by convention
        val slotValue = outputBatch.fields[slotName] as Value.SumV
        if (slotValue.case == "Some") {
            val payload = slotValue.payload!!
            instance.outputSinks[outputStreamId]!!.add(payload)
            emitted.add(payload)
        }
    }
    return TraceStep.Step(event, before, newState, emitted)
}
```

This requires exposing `Interpreter.applyClosure` (currently private) to the `runtime` module — promote it to `internal`. Only seam in `Interpreter` step 1 needs to open.

**Two judgment calls to record in the impl README:**

- **OutputBatch convention.** Output streams index by position in the StateMachine's `outputStreams` list; the convention is that the transition function returns a product whose field at index `i` is `Option<outputStreams[i].eventType>`. Field name is `output_i` for now (positional, ugly, intentional — no human-readable projection). When step 2 lands the recursive-list-based representation, replace with `(State, List<TaggedOutput>)`.

- **Cached closure.** The transition Lambda is evaluated once at instance start and cached. Matters because the transition function may itself be a let-expression whose captured environment is non-trivial to recompute every event.

## 6. Snapshotting and replay determinism

In step 1, replay determinism is trivially achieved: the runtime is a deterministic fold, the interpreter is pure (Layer 3 already enforces effect declarations, step 1 programs run under empty capability context), and the event sequence is supplied verbatim. Add a property test per corpus program: run twice and assert `Trace` equality.

For step 2 and step 3, snapshotting requires:

- A serializable representation of `currentState`. States are Strand `Value`s — built from primitives, products, sums, closures over the graph — and graph nodes are content-addressed (Layer 2). The natural snapshot is `(stateNodeHash, eventLogSinceLastSnapshot)`. Replay reconstructs the state by rehydrating from the hash and re-applying the event log.
- A way to capture the event log itself: each event is also a Value. Record `(eventNodeHash, sourceStream)` per event for a content-addressed log. Cheap because identical events deduplicate.
- For closures captured in the state (step 1 programs don't have but might later), the closure's `lambda` is a node hash and `env` maps NodeId to Value — Values recurse through this same scheme.

None of this needs to land in step 1, but step 1 should not foreclose it. The deterministic-fold-over-fixed-list architecture is exactly the shape replay takes.

## 7. Subsequent steps (sketched)

### Step 2 — Async multi-machine runtime

Three complexity centers:

1. **Coroutine integration.** Each machine instance runs in its own coroutine on a shared `CoroutineDispatcher`. Input streams are `Channel<Value>`. The actor loop is `for (event in inputChannel) { stepOnce(instance, event); ... }`. The transition function call remains synchronous — pure Strand evaluation never suspends. Suspension lives in the actor loop, not in the interpreter.

2. **Stream wiring.** When machine A's output stream is machine B's input stream, the runtime needs a topology builder: given a graph of StateMachine nodes whose EventStreams cross-reference, the runtime spins up channels, spawns coroutines, and connects them. The corpus needs a "ping/pong" two-machine example as the canonical test.

3. **Multi-stream merge.** Per Q-009, default is FIFO-per-stream with non-deterministic merge. Kotlin's `select` on multiple channels gives us this. The transition function's Event argument needs to carry stream provenance — either extend the per-machine Event type to a sum tagged by stream, or each input stream's event type is wrapped in a `(streamTag, payload)` product the runtime synthesizes. Latter is cleaner.

Main complexity is *test infrastructure*. Coroutine-based multi-machine tests need deterministic schedulers (kotlinx-coroutines-test provides one), virtual time for any Time effects, and assertions over eventually-quiescent traces.

### Step 3 — Backpressure and supervision

- **Bounded queues and overflow policies.** Add `bufferSize: Int` and `overflowPolicy: BlockProducer|DropNewest|DropOldest|Sample` content fields to EventStream. Verifier validates the policy name. Runtime instantiates channels of the right capacity. Cross-cutting issue: overflow policies are observable as runtime metrics — need a `RuntimeMetrics` surface.

- **Supervision as a state-machine pattern.** Per spec, supervisors are not a new node category; they are state machines whose state happens to track child instances and whose events include child terminations. Step 3's work is more about (a) defining the failure-event protocol (a child's transition throws, the runtime catches and emits a `ChildFailed(instanceId, reason)` event to the supervisor's input stream) and (b) writing the supervisor patterns as corpus programs.

This is where E-028..E-031 finally bite. Step 1 evaluates pure transitions and emits outputs directly; step 2 wires output streams to other machines but doesn't formally treat emission as an E-028 effect because the runtime, not the user code, is doing the send. Step 3 makes the spawning/terminating of children an effect the supervisor's transition function declares — going through the existing capability-check machinery from Layer 3.

## 8. Open questions you can't resolve from the spec

**OQ-A: List type vs. fixed-arity OutputBatch.** With recursive types landed, the proper `[Event]` is now expressible. The recommendation to use fixed-arity OutputBatch in step 1 is a pragmatic choice for simplicity in the test harness; step 2 should switch.

**OQ-B: Where does N-029 Transition actually appear in a graph?** Recommendation: defer. Don't ship Transition in step 1 except as a parsed-but-unused ADT shape. Take the question to a focused design pass when there's a concrete corpus program that benefits from it.

**OQ-C: Termination signaling.** Spec lists three termination causes: explicit by T, supervisor-initiated, unrecoverable error. The first needs a representation: does T return a special sentinel state? Does it emit a designated termination event? **Recommendation:** punt to step 2 when supervision lands. Step 1 only halts on event-list-exhausted.

**OQ-D: Instance identity in step 1.** Spec's "node identity vs. instance identity" distinction matters in step 2. Step 1 has one instance per `runMachine` call. Generate an `InstanceId` anyway for forward compatibility, but don't expose in the Trace API.

**OQ-E: Capability context during transition function evaluation.** The transition Lambda may declare effects. The spec also says E-028 (StateMachine.Send) is the effect for emitting on an output stream. The runtime is the thing doing the send (after the transition function returns), so the *transition function itself* doesn't need StateMachine.Send capability — the StateMachine *node* does. **Recommendation:** the StateMachine node's `effects` list is the union of (the transition function's closure effects) ∪ (StateMachine.Send for each output stream) ∪ (StateMachine.Receive for each input stream). The verifier checks this. The transition function evaluation runs under the supplied context.

## 9. Implementation sketch

**New module:** `runtime/` parallel to `interpreter/`. Module dependencies: `runtime → interpreter → verifier → core`. The CLI gains a `strand machine <file.json> --events <events.json>` subcommand for driving a state machine from a JSON event list.

```
impl-kotlin/
├── core/                       N-027/N-028/N-029 added to Node ADT, JSON ingest
├── verifier/                   StateMachine, EventStream verification rules
├── interpreter/                applyClosure exposed to runtime; no other changes
├── runtime/                    NEW
│   └── src/main/kotlin/org/strand/runtime/
│       ├── StateMachineRuntime.kt    (the trace API)
│       ├── MachineInstance.kt        (the actor data structure)
│       ├── Trace.kt                  (the result types)
│       └── EventCodec.kt             (decode events from JSON into Values for the CLI)
├── cli/                        strand machine subcommand
└── corpus/
    └── 33..37 state machine programs + descriptions in README
```

**Scope estimate for step 1:**

- **Core (Node ADT + JSON ingest):** Small. ~150 lines.
- **Verifier:** Medium. About a dozen new error cases, the shape-check helper, integration into existing `infer`. ~300–400 lines.
- **Interpreter:** Tiny. Promote `applyClosure` to `internal`. ~5 lines.
- **Runtime module:** Medium. ~250–350 lines.
- **CLI:** Small. ~100 lines.
- **Corpus:** Medium. 5 programs with descriptions, ~80–150 lines apiece. ~700 lines JSON + ~100 lines Kotlin test code.
- **Tests:** Per-program assert-on-trace tests, replay-determinism property test. ~150 lines.

Overall step 1 is **medium scope** — bigger than recent slices but comparable to Layer 5 step 1 (Match + Pattern). Probably a one-batch piece of work given the existing infrastructure.

Step 2 is **large scope** — coroutines, multi-channel select, topology builder, multi-machine test infrastructure. Probably 2–3 batches.

Step 3 is **medium scope** if landed as a tight package.

---

**A note on a non-obvious payoff.** The deterministic trace API in step 1 is not just a stopgap before the real concurrent runtime arrives. It is also the *debugging* and *training-corpus-generation* interface that step 2 and step 3 don't naturally provide. Replay determinism only works if you can supply a fixed event sequence and observe the result, and that is exactly what `runMachine(machine, events): Trace` does. Step 2's coroutine runtime will have a test mode that records the actual events delivered into a sequence and a `replay(machine, recordedSequence): Trace` operation using the step 1 runtime. Step 1's runtime survives forever as the deterministic-fold core that step 2 wraps with async I/O. This is BEAM's `gen_statem` test harness pattern.

## References

**Outgoing references:**
- [`design/state-machines.md`](../../design/state-machines.md) — full state-machine spec
- [`decisions/ADR-007-state-machines.md`](../../decisions/ADR-007-state-machines.md)
- [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md) — § State machine effects (E-028..E-031)
- [`design/distribution-model.md`](../../design/distribution-model.md) — backpressure, distribution
- [`design/node-algebra.md`](../../design/node-algebra.md) — N-027, N-028, N-029 entries
- [`open-questions.md`](../../open-questions.md) — Q-008, Q-009, Q-010, Q-032

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-032 points at this proposal; extends Q-008
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — Layer 6 step 1 notes
