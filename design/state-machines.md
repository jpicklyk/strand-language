# State Machines {#state-machines}

**Document:** `design/state-machines.md`
**Status:** Wave 3 draft
**Last revised:** 2026-05-23

## Summary

This document specifies the runtime model for long-running stateful computation in Strand. It builds on [ADR-007](../decisions/ADR-007-state-machines.md), which establishes the high-level decision (state machines as fixpoints over event streams) and on the node types defined in [node-algebra.md](node-algebra.md) (StateMachine, EventStream, Transition).

The model rests on a single insight: a long-running computation that responds to an event stream is mathematically a function from event history to behavior. The state at time *t* is determined by the initial state and the events received between time 0 and time *t*. By making this function explicit as a graph construct, Strand reduces stateful service implementation to specifying (a) an initial state, (b) a transition function, and (c) the streams that feed events into the function. Everything else — process supervision, scheduling, hot upgrade, distribution — is managed by the runtime around this core specification.

Resolves [Q-002](../open-questions.md#Q-002) (hierarchical machines), [Q-009](../open-questions.md#Q-009) (event ordering), [Q-010](../open-questions.md#Q-010) (hot upgrade) as proposed designs. [Q-008](../open-questions.md#Q-008) (high-throughput architecture) is addressed at the level of design constraints; the runtime engineering remains open.

## Conceptual model {#conceptual-model}

A state machine M is a tuple (S₀, T, I, O) where:

- S₀ is the initial state, of type State.
- T is the transition function, of type `(State, Event) → (State, [Event])`.
- I is the set of input event streams.
- O is the set of output event streams.

At runtime, the machine maintains a current state, initially S₀. When an event arrives from one of its input streams in I, the runtime computes T(state, event), updates the state, and emits the resulting output events to the streams in O. The process continues indefinitely until the machine is terminated (either by an event that triggers termination logic in T or by an external signal).

The state machine's lifetime trajectory — its sequence of (event, state, outputs) triples — is the fixpoint of T over the input event history. Two runs of the same machine starting from the same initial state with the same event sequence produce identical trajectories, provided T performs no non-deterministic effects. This determinism is the basis for replay debugging ([ADR-008](../decisions/ADR-008-compilation-target.md)) and for fault recovery (restart from saved state, replay events since last snapshot).

The state type S, the event type, and the output event types are part of the StateMachine node's declaration through its `transitionFn` edge (a Lambda whose type signature encodes them). The runtime confirms type compatibility at instantiation.

## Node structure recap {#node-structure}

From [node-algebra.md](node-algebra.md): the relevant node types are StateMachine (N-027), EventStream (N-028), and Transition (N-029). A StateMachine has edges:

- `transitionFn` → Lambda (typed `(State, Event) → (State, [Event])`)
- `initialState` → Expression (typed State)
- `inputStream` → EventStream (one or more)
- `outputStream` → EventStream (zero or more)
- `effect` → EffectCategory (the union of effects the transition function and its referenced nodes declare)

An EventStream node declares its event type and its stream kind (external source, internal between machines, or output sink). A Transition node is a guarded transition rule typically used within a transition function body to avoid the boilerplate of pattern-matching every event type.

## Instances versus nodes {#instances-vs-nodes}

The distinction is essential. A StateMachine *node* is a content-addressed declaration: it specifies what the machine does. A StateMachine *instance* is a runtime occurrence: it has a particular current state, a particular set of running input subscriptions, and a particular history. Two instances of the same node are the same kind of machine but distinct runtime objects.

Instance identity is runtime-assigned (a UUID-equivalent or a stable allocation identifier). References to a running instance are by instance ID, not by node hash. The node hash identifies the *machine definition*; the instance ID identifies the *running occurrence*.

This distinction is consequential for distribution: the same node may be instantiated on multiple machines, each with its own state. The node is shared (and deduplicated); the instances are distinct.

## Hierarchy and composition {#hierarchy}

[Q-002](../open-questions.md#Q-002) asks how hierarchical state machines (Harel statecharts and similar formalisms) map to the node algebra. The chosen design is *compositional flat machines* rather than nested-state nodes.

A hierarchical pattern — a parent machine that has child sub-machines, where the child's transitions occur within a parent state — is implemented by having the parent's transition function delegate to child machines based on the parent's current state. Concretely:

- The parent's State type includes references to active child machine instances.
- The parent's transition function, on receiving an event, examines its current state. If the state designates that a child machine should receive the event, the parent forwards the event to that child (via a stream connecting them) and waits for the child's output.
- Child terminations propagate upward as output events that the parent can interpret.

This pattern is implemented in Erlang's `gen_statem` library and in similar abstractions over BEAM. It preserves the flat StateMachine algebra at the graph level while supporting hierarchical patterns at the application level. The cost is some boilerplate at composition boundaries; the benefit is that the algebra remains uniform and that all the analysis tools (effect closure, capability check, scheduling) operate on a single flat construct.

Parallel regions in statecharts — concurrent sub-machines within a parent — map to multiple child machines instantiated in parallel and consuming the same event streams. History states map to child machines whose state-saving logic is part of their transition function.

A future revision may introduce a `HierarchicalStateMachine` node category as syntactic sugar over this pattern, with the runtime compiling it into the flat composition. Such a node would not change the algebra, only the construction convenience.

## Event ordering and merge policy {#event-ordering}

[Q-009](../open-questions.md#Q-009) asks how events from multiple input streams are interleaved. The chosen design layers from default to opt-in:

**Default: FIFO per stream with non-deterministic merge.** The runtime maintains a FIFO queue per input stream. When the machine is ready to process an event, the runtime selects from the non-empty queues by a policy. The default policy is non-deterministic: any non-empty queue may be selected, with the runtime free to choose based on throughput, fairness, or NUMA locality. The transition function must be designed to tolerate any interleaving consistent with per-stream order.

**Optional: priority-based merge.** A StateMachine may declare priorities among its input streams. The runtime selects from the highest-priority non-empty queue. Priorities are static; per-event priorities require encoding the priority in the event payload.

**Optional: causally-ordered merge with vector clocks.** A StateMachine that requires causal ordering across streams declares this in its node. Each event carries a vector clock; the runtime delivers events in a causally consistent order. This is the most expensive policy and is reserved for distributed protocols that require it.

**Optional: timestamp-based merge.** Events carry timestamps and the runtime delivers in timestamp order, possibly with a bounded delay to admit late arrivals. Appropriate for stream-processing workloads where event time matters.

The default suffices for most workloads; the alternatives are available without changing the algebra. The selection is a property of the StateMachine node, not a separate runtime configuration.

## Backpressure {#backpressure}

[Q-015](../open-questions.md#Q-015) asks how backpressure propagates when events arrive faster than a machine can process them. The chosen design uses bounded queues with declared policies:

**Bounded queues.** Each input stream has a maximum queued depth, declared on the EventStream node. The default is a moderate fixed value (e.g., 1024 events) chosen to balance memory use against transient burst tolerance.

**Per-stream overflow policy.** When the queue is at capacity and a new event arrives, the runtime applies a policy:

- *Block producer* (default): the runtime suspends the upstream producer until the queue has space. This propagates backpressure upstream.
- *Drop newest*: the incoming event is discarded.
- *Drop oldest*: the head of the queue is discarded to make room.
- *Sample*: keep every Nth event, drop the rest.

The policy is declared per stream. The default (block producer) preserves event ordering and propagates pressure; the alternatives are appropriate for workloads where event loss is acceptable.

**Cross-machine backpressure.** A producer machine that hits an overflow on its output stream (because the consumer's input queue is full) suspends its own processing until space is available. This suspension reflects in the producer's own input streams, which begin to fill, and propagates upstream through the topology. Reactive Streams semantics, adapted to graph topology, apply.

**Detection and surface.** Persistent backpressure is observable through runtime metrics. A machine that is blocked on output queues for an extended period is flagged in the runtime's monitoring surface so that operators can identify capacity bottlenecks. This is operational tooling, not algebra; the algebra fixes the semantics, the tooling surfaces the consequences.

## Hot upgrade {#hot-upgrade}

[Q-010](../open-questions.md#Q-010) asks how a running state machine's transition function is updated. The chosen design is a defined two-phase upgrade with compatibility declarations:

**Compatibility declaration.** Replacing a transition function T with T' requires that T' declare its compatibility with T. There are two compatibility levels:

- *Same state type*: T' accepts and produces the same State type as T. The upgrade is straightforward: the runtime swaps the transition function reference; the current state is unchanged.
- *Migrated state type*: T' uses a new State' type. The upgrade requires a migration function M: State → State'. The runtime applies M to the current state at the upgrade boundary; subsequent events are processed by T'.

A third case — *incompatible*: T' accepts events of a different type than T — requires a more involved migration that conceptually reinstantiates the machine. This is treated as termination of the old machine and instantiation of the new, not as an in-place upgrade.

**Two-phase upgrade protocol.** At the upgrade boundary:

1. The runtime stops dispatching new events to the current machine. In-flight events complete on T.
2. The current state is captured. If a migration function is required, it is applied to produce the new state.
3. The transition function reference is replaced with T'. The state is set to the migrated state.
4. Event dispatch resumes with T'.

The boundary is atomic relative to event dispatch; mid-event upgrades do not occur. The cost is a brief pause during which incoming events queue. For most workloads this is acceptable; for latency-critical workloads, the upgrade can be deferred to a known quiescent moment.

**Distribution interaction.** When a machine is distributed across multiple instances (sharded by some key, for example), upgrade applies per instance. A coordinated upgrade across instances requires the runtime's cluster coordination (the topic of [distribution-model.md](distribution-model.md)).

The upgrade mechanism is graph-level: T and T' are content-addressed function nodes with stable distinct identities; the migration function is also content-addressed. The upgrade itself is a runtime operation, recorded in the machine's history for forensics.

## High-throughput architecture {#high-throughput}

[Q-008](../open-questions.md#Q-008) is the question of how to make a Strand state machine runtime efficient enough for high-throughput communicating-machine workloads (distributed consensus, real-time messaging, large-scale event processing). The runtime engineering remains open; this section specifies design constraints that any implementation must satisfy.

**Lightweight scheduling.** Each instance must be cheap to schedule. The runtime should support millions of concurrent instances on a single machine, where each instance's per-event scheduling overhead is on the order of microseconds. Erlang's BEAM achieves this through lightweight green-thread-per-process scheduling with reduction-count-based preemption. Strand's runtime adopts a similar approach: each instance is a green thread scheduled by the runtime's executor pool, with preemption at controllable points (end of transition function evaluation, periodically within long transitions).

**Batch event processing.** Where the workload permits, the runtime batches events for a single instance and applies the transition function over the batch. This reduces dispatch overhead. The transition function is unaware of batching; the runtime maintains the (state, events) input and (state, outputs) output relationship at batch granularity. Strict per-event semantics is preserved.

**Locality.** Instances that communicate frequently should be co-located when possible. The runtime's placement decisions consider message-passing frequency as a placement signal. Where the topology permits (sharded workloads), instances within a shard share an executor.

**State persistence and snapshotting.** For instances whose state must survive runtime restarts, the runtime supports periodic snapshotting of state with replay from event log on recovery. Snapshot intervals and replay policy are configurable. Content-addressing makes snapshots compact: the state is a node hash; the events are a sequence of node hashes.

**Garbage collection.** Per-instance state is reclaimed when the instance terminates. Long-running instances with large state space need incremental garbage collection of intermediate state values. The runtime's GC operates on content-addressed nodes (see [ADR-003](../decisions/ADR-003-content-addressing.md)) and is independent of instance lifetime: a state value referenced only by one instance is reclaimed when the instance no longer references it.

These constraints are necessary; their realization in code is the engineering work that [Q-008](../open-questions.md#Q-008) calls out and that future iterations of the runtime must address. The reference architecture is BEAM: the design adopts BEAM's scheduling model, supervision tree pattern, and message-passing primitives, recast in Strand's graph-native, content-addressed terms.

## Termination and supervision {#termination-supervision}

A state machine instance terminates when (a) its transition function returns a designated termination value, (b) an external supervisor instructs termination, or (c) an unrecoverable error occurs in transition function evaluation.

Supervision is itself a state machine pattern: a *supervisor* is a state machine whose state tracks the running instances of its children, whose events include child termination notifications, and whose outputs include spawn / terminate / restart commands. Restart policies (one-for-one, one-for-all, rest-for-one) are encoded in the supervisor's transition function. The pattern follows Erlang's supervisor tree, recast in Strand's graph terms.

Unrecoverable errors (panics, type errors at runtime, capability violations) terminate the instance. The runtime captures the failure (the failing event, the instance's state at failure, the stack of nested calls) and emits it as an event to the supervisor or, in the absence of a supervisor, to a default failure stream. This is the "let it crash" discipline: per-instance failures do not propagate to other instances, and recovery is the supervisor's responsibility.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — integration section
- [`01-prior-art.md`](../01-prior-art.md) — BEAM and actor model references
- [`ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — content-addressed transitions
- [`ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — effects on transitions
- [`ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md) — state machine decision
- [`ADR-008-compilation-target.md`](../decisions/ADR-008-compilation-target.md) — runtime execution
- [`node-algebra.md`](node-algebra.md) — StateMachine, EventStream node types
- [`effects-and-capabilities.md`](effects-and-capabilities.md) — StateMachine effect categories
- [`distribution-model.md`](distribution-model.md) — distribution of state machines
- [`open-questions.md`](../open-questions.md) — Q-002, Q-008, Q-009, Q-010, Q-015 addressed here

**Incoming references:**
- [`01-prior-art.md`](../01-prior-art.md)
- [`decisions/ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md)
- [`node-algebra.md`](node-algebra.md)
- [`effects-and-capabilities.md`](effects-and-capabilities.md)
- [`distribution-model.md`](distribution-model.md)
- [`research-plan.md`](../research-plan.md)
- [`rendering-and-views.md`](rendering-and-views.md) — live views compose with state machines
