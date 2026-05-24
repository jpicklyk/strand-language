# ADR-007: State Machines as Fixpoints over Event Streams {#adr-007}

**Document:** `decisions/ADR-007-state-machines.md`
**Status:** Accepted
**Date:** 2026-05-23
**Supersedes:** none
**Superseded by:** none

## Context {#context}

The decisions so far cover computations that have a clear beginning and end: a function takes inputs, performs declared effects, and returns. This model covers transformations, queries, and batch computations. It does not naturally cover long-running computations that maintain state across many invocations: services that handle requests indefinitely, UI loops that respond to user input, daemons that monitor and react to system state, processes that participate in distributed protocols, supervisors that manage other processes.

Several traditions handle these workloads. Erlang's BEAM model runs millions of lightweight processes that communicate by message passing; processes are isolated, can fail independently, and are supervised by other processes. Akka brought this model to the JVM. Functional reactive programming represents time-varying values as first-class entities and composes computations over event streams. Reactive Streams added backpressure semantics for cross-component event flow. Statecharts (Harel, Mealy and Moore machines) provide a formalism for hierarchical state with explicit transitions. Each tradition has solved subsets of the problem; none of them is graph-native, content-addressed, and effect-typed in the way Strand requires.

The question this decision answers is how Strand represents long-running stateful computation while preserving the graph-native, content-addressed, effect-typed properties from the earlier ADRs.

## Decision {#decision}

Strand provides a dedicated node type, StateMachine, that represents a long-running computation as a fixpoint of a transition function over a stream of events. A StateMachine node declares: (a) a transition function as a graph reference to a function node, (b) an initial state as a graph reference to a value node, (c) one or more input event streams as references to EventStream nodes, (d) one or more output event streams as references to EventStream nodes, and (e) effect and capability declarations covering the transitions the machine performs.

The semantic model is straightforward. A state machine in operation maintains a current state. When an event arrives from one of its input streams, the runtime applies the transition function to (current_state, event), producing a new state and a set of output events. The state is replaced with the new state; the output events are emitted to the state machine's output streams. The process continues until the state machine is terminated, either by explicit termination or by an event that triggers a termination transition.

The state machine itself is a content-addressed node. Its identity hashes over the transition function reference, the initial state reference, the stream references, and the effect declarations. Two state machine nodes with identical declarations are the same node and may be deduplicated. Two state machine *instances* (running occurrences of the same machine, each with their own current state) are distinct runtime objects identified by the runtime, not by their node hash. The distinction between node identity (static, content-addressed) and instance identity (dynamic, runtime-assigned) is essential to the model.

Transition functions are content-addressed function nodes. They are pure in the sense that the function from (state, event) to (state, events) is a pure transformation; impurity is moved to the effect edges on the function. A transition function may declare effects, which the runtime enforces against the capability context at the point of transition. A transition function that performs effects must declare them; the verifier propagates them through the effect closure of the state machine node and through any graph that references the state machine.

Event streams are first-class nodes. An EventStream node represents a source or sink for typed events. Sources may originate from foreign nodes (network input, timer ticks, hardware interrupts), from other state machines (output events), or from explicit producer nodes in the graph. Sinks may consume into foreign nodes (network output, log writes), into other state machines (their input), or into explicit consumer nodes. The graph topology defines which streams feed which machines; the runtime implements the flow.

The fixpoint framing has a precise meaning: the state machine's lifetime trajectory is a fixpoint of the equation `state(t+1) = transition(state(t), event(t))` over the event sequence the runtime delivers. The "fixed point" is the steady-state behavior — the function the machine computes from its entire event history. This framing makes the state machine a value in the language (a function over time, content-addressed by its definition) rather than a stateful object outside the language.

Hierarchy, parallelism, and hot upgrade are deferred. Hierarchical state machines ([Q-002](../open-questions.md#Q-002)), event ordering across multiple streams ([Q-009](../open-questions.md#Q-009)), backpressure semantics ([Q-015](../open-questions.md#Q-015)), hot upgrade of transition functions ([Q-010](../open-questions.md#Q-010)), and the runtime engineering for high-throughput communicating machines ([Q-008](../open-questions.md#Q-008)) are open questions handled in [`design/state-machines.md`](../design/state-machines.md) and the referenced design documents. The decision adopted here is about the representation; the runtime engineering is acknowledged as substantial and unresolved.

## Alternatives considered {#alternatives}

Four alternatives were evaluated and rejected.

**Actor model with implicit mailboxes (Erlang/Akka direct port).** Each actor is a process with an inbox; messages arrive, the actor dequeues and processes them, optionally sending messages to other actors. The model is well-validated by Erlang's BEAM at production scale and by Akka in the JVM ecosystem. Adopting it directly is rejected because the mailbox is implicit state outside the graph: the inbox of an actor at time t is not represented as a graph node, only as runtime state in the actor's process. Strand requires that the inputs and outputs of a state machine be first-class, traceable through the graph topology, and subject to the same effect declarations as everything else. EventStream nodes provide explicit, graph-visible queues that play the role of mailboxes while remaining part of the language model.

**Coroutines or async/await with suspended computation.** A long-running computation is expressed as a coroutine that suspends on event waits and resumes when events arrive. The suspension state is hidden in the runtime; the language tracks only the function. This works well for sequential event-driven code (await this, await that), but it makes the suspension state opaque to verification and analysis. The graph cannot answer questions like "what state is this computation in" or "what events is it waiting for" without runtime introspection. Strand's design requires that these be structural properties of the graph.

**Future/promise chains.** A computation that performs many asynchronous steps is expressed as a chain of promises, each completing when its prerequisite completes. This is well-understood in JavaScript, Python, and similar ecosystems. It is rejected because it does not naturally handle continuous event streams or long-running services with bounded state. A web server that processes a million requests is not a million-step promise chain; it is a single state machine processing a million events. Promises are appropriate for sub-tasks within a state machine, not for the long-running computation itself.

**Foreign-object state delegated to the runtime or operating system.** A Strand graph would invoke foreign functions that manage state behind opaque handles (file descriptors, socket handles, database connections). The graph itself remains stateless; state lives in the foreign side. This is the dominant pattern in conventional languages and is partially supported in Strand through ForeignNode ([ADR-005](ADR-005-foreign-nodes.md)). It is not sufficient as the primary mechanism because long-running Strand-native computation needs first-class representation: the state, transitions, and event flow must be analyzable, schedulable, and capability-controllable as graph constructs. Pushing all state into foreign handles abandons the structural guarantees Strand was designed to provide.

## Consequences {#consequences}

Long-running services are first-class graph constructs. A web service, a UI loop, a distributed consensus participant, and a process supervisor are all graphs of StateMachine and EventStream nodes; they are not separate categories of "programs" requiring special infrastructure. The same effect closures, capability checks, and content-addressed identity apply.

Distribution of state machines composes with the rest of the system. A state machine can be placed on any executor that satisfies the capability requirements of its transition function and its input/output streams. The scheduler determines placement using the same effect-driven constraints as for any other graph ([`design/distribution-model.md`](../design/distribution-model.md)). Stateful workloads are distributable without the per-program engineering that conventional distributed systems require.

Hot upgrade has a structural definition. Updating the transition function of a running state machine produces a new state machine node (with a new hash) whose transition function differs. The upgrade procedure is to replace the running instance's transition function reference and to migrate the current state if the state type has changed. The new and old transition functions have stable, distinct content-addressed identities; the upgrade is a graph-level operation. The detailed semantics for state migration and atomicity are open ([Q-010](../open-questions.md#Q-010)).

Replay and debugging are intrinsic. A state machine's behavior is determined by its initial state and its event history. Replaying the same events into the same machine produces the same trajectory, provided the transition function is deterministic. Where the transition function performs effects, replay requires the effects to be either replayed from a captured log or virtualized; the capability system supports this by allowing a debug context to grant virtualized effects in place of real ones.

The runtime engineering for high-throughput communicating machines is substantial and not solved by this ADR. Erlang's BEAM runs millions of processes with sub-microsecond message passing; reaching this level of performance with a graph-native, content-addressed implementation is an open problem ([Q-008](../open-questions.md#Q-008)). The design specification fixes the model; the runtime architecture is to be developed in [`design/state-machines.md`](../design/state-machines.md) with reference to BEAM as the architectural baseline.

Event ordering, merge policy, and backpressure require specification. When a state machine has multiple input streams, the policy for interleaving their events affects correctness and performance. Reactive Streams provides a starting framework; the Strand-specific policy and its interaction with content-addressing and distribution are open ([Q-009](../open-questions.md#Q-009), [Q-015](../open-questions.md#Q-015)).

Hierarchy, parallel regions, and history states are deferred. Real-world state machines are often hierarchical (Harel statecharts), with the convenience of composition and parallel sub-machines. The decision adopted here treats state machines as flat by default; hierarchical compositions are a higher-level construct over flat state machines, with the detailed mapping to be specified in [`design/state-machines.md`](../design/state-machines.md) ([Q-002](../open-questions.md#Q-002)).

The pure-function view of state transitions makes verification tractable. A transition function is a node whose effect closure is computable, whose type is checkable, and whose behavior on (state, event) inputs can be reasoned about as a function. The state machine's whole-program behavior is the iteration of this function; properties of the function (effect set, type signature, capability requirements) lift to properties of the running machine. Verification techniques that apply to functions apply equally to state machines, modulo questions about the event stream.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — integration section discussing state machines as graph fixpoints
- [`01-prior-art.md`](../01-prior-art.md) — actor model, statecharts, reactive streams references
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — graph foundation
- [`ADR-003-content-addressing.md`](ADR-003-content-addressing.md) — content-addressed transition functions
- [`ADR-004-effects-as-edges.md`](ADR-004-effects-as-edges.md) — effect declarations on transitions
- [`ADR-005-foreign-nodes.md`](ADR-005-foreign-nodes.md) — event sources backed by foreign code
- [`design/state-machines.md`](../design/state-machines.md) — detailed runtime architecture
- [`design/distribution-model.md`](../design/distribution-model.md) — placement of state machines
- [`open-questions.md`](../open-questions.md) — Q-002, Q-008, Q-009, Q-010, Q-015

**Incoming references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — references state machines in integration section
- [`ADR-005-foreign-nodes.md`](ADR-005-foreign-nodes.md) — refers to state machines as the sidecar-style mechanism
- [`ADR-008-compilation-target.md`](ADR-008-compilation-target.md) — state machine execution and event flow
- [`design/node-algebra.md`](../design/node-algebra.md) — StateMachine, EventStream node types
- [`design/state-machines.md`](../design/state-machines.md) — detailed runtime architecture
- [`design/distribution-model.md`](../design/distribution-model.md) — state machine placement
- [`ADR-009-structured-outputs.md`](ADR-009-structured-outputs.md) — live views as state machine outputs
- [`design/rendering-and-views.md`](../design/rendering-and-views.md) — live view design
