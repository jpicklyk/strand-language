# Distribution Model {#distribution-model}

**Document:** `design/distribution-model.md`
**Status:** Wave 3 draft
**Last revised:** 2026-05-23

## Summary

This document specifies how Strand graphs execute across multiple machines: how computations are placed on workers, how the scheduler chooses among feasible placements, how data and capabilities flow across machine boundaries, and how failures are handled. The design rests on two prior choices: graphs are the dependency representation ([ADR-001](../decisions/ADR-001-graph-not-text.md)), so the scheduler operates on the program's actual structure rather than on a separately-derived plan; and effects determine placement constraints ([ADR-004](../decisions/ADR-004-effects-as-edges.md)), so placement is a constraint-satisfaction problem over the effect closure of each subgraph.

The model is designed for heterogeneous clusters where executors differ in capabilities (some have GPU access, some have TEE attestation, some have specific filesystem mounts) and trust levels (some are fully trusted, others are partially trusted). Placement assigns each subgraph to an executor that holds the capabilities the subgraph requires; the scheduler chooses among feasible placements according to a policy that may emphasize latency, throughput, fault tolerance, or data locality.

Resolves [Q-014](../open-questions.md#Q-014) (scheduler policy), [Q-015](../open-questions.md#Q-015) (backpressure), and [Q-016](../open-questions.md#Q-016) (network failure during fetches) as proposed designs.

## Placement as constraint satisfaction {#placement}

Each subgraph in a Strand program has an effect closure (computed by the verifier; see [effects-and-capabilities.md](effects-and-capabilities.md)) that names the effects it may perform. Each available executor declares a capability set: the effects it can satisfy. A *feasible placement* assigns each subgraph to an executor whose capability set covers the subgraph's effect closure.

For a graph with N subgraphs and M executors, the placement problem has up to M^N candidate assignments. In practice the structure of effect closures and the constraints between subgraphs (data flow, ordering) reduce the search space dramatically. The scheduler operates as follows:

1. Decompose the graph into subgraphs at scheduling boundaries (top-level function applications, state machine boundaries, explicit `Placement` annotations).
2. For each subgraph, compute its effect closure and identify the set of executors whose capability sets are sufficient.
3. Identify data-flow dependencies between subgraphs (which subgraph's output is which subgraph's input).
4. Apply the policy (below) to choose among feasible placements, respecting dependency constraints.
5. Emit a placement plan: a mapping from subgraph identity to executor identity.

The plan is itself a content-addressed graph, so it can be checkpointed, replayed for debugging, and inspected by audit tools. Re-execution with the same graph and the same executor capabilities produces the same plan, assuming the policy is deterministic.

## Scheduler policy {#scheduler-policy}

[Q-014](../open-questions.md#Q-014) asks what the scheduler optimizes for when multiple placements are feasible. The chosen design admits multiple policies, configurable per scheduler instance and overridable per graph through explicit annotations.

The available primary policies are:

**Minimize data movement.** Place each subgraph on the executor closest to the data it consumes. This is the policy appropriate for analytics-like workloads where moving data is the dominant cost. Closeness is measured by the runtime's topology graph (link bandwidth, latency, monetary cost of cross-region transfers).

**Minimize critical-path latency.** Place each subgraph on the fastest executor for its operation, even at the cost of more data movement. This is appropriate for latency-sensitive workloads where end-to-end response time matters more than total resource use.

**Balance load.** Distribute subgraphs across executors to keep utilization roughly uniform. Appropriate for systems serving many concurrent graphs that share an executor pool, where head-of-line blocking from any one large graph would degrade overall service quality.

**Maximize fault tolerance.** Place subgraphs to maximize the number of independent failure domains (different machines, different racks, different regions). Replicate where the graph permits. Appropriate for high-availability deployments.

The four policies are not mutually exclusive; a multi-objective scheduler can combine them with weighted scores. The default is a hybrid: minimize data movement subject to keeping the critical path within a threshold, with fault tolerance respected at the level of avoiding single-point-of-failure placements.

Per-graph overrides are expressed as `Placement` annotations on nodes. An annotation may pin a node to a specific executor, exclude it from specific executors, require co-placement with another node, or require explicit dispersion across failure domains. Annotations are graph-level constraints that further restrict feasibility; the policy still applies among the remaining feasible placements.

The policy is itself part of the runtime configuration and may be Strand graph code: a `SchedulerPolicy` node specifies a function that scores candidate placements. This is consistent with the design's overall principle that policies are programmable rather than hard-coded.

## Capability flow across executors {#capability-flow-distribution}

Capabilities are runtime tokens held by executors, not data carried in graphs. When a subgraph is placed on an executor, the executor must hold the capabilities the subgraph requires; otherwise the placement is not feasible.

When a subgraph A (placed on executor X) calls a subgraph B (placed on executor Y), the call crosses an executor boundary. The capabilities held by X do not automatically flow to Y; Y must hold its own capabilities. Two cases arise:

**Y has its own capabilities sufficient to evaluate B.** This is the common case: the scheduler chose Y because it has B's required capabilities. The call proceeds; Y evaluates B in its capability context.

**B requires a capability that only X holds.** This is the *capability delegation* case. X must delegate the capability to Y for the duration of B's evaluation. The mechanism: X constructs a *delegation token* — a signed message that says "Y is authorized to act with capability C on behalf of X for the duration of this evaluation" — and sends it with the request to evaluate B. Y verifies the delegation token, instantiates the capability locally, evaluates B, and discards the capability when B completes. The delegation is scoped, time-bounded, and observable.

Delegation tokens are themselves Strand graph constructs: signed nodes that carry the capability specification and the principal's signature. A token's authority is bounded by the principal's authority; an executor cannot delegate authority it does not hold.

## Node fetching across the network {#node-fetching}

A subgraph placed on an executor may reference nodes by hash that are not locally present. The executor fetches missing nodes from a peer that holds them.

The fetch protocol:

1. The executor identifies the needed hash and consults a peer directory to find executors known to hold the node.
2. The executor issues a request to one or more candidate peers. The request includes the hash and an authentication token if access requires authorization.
3. A responding peer returns the node's serialized form (envelope if encrypted; canonical encoding if not).
4. The executor verifies that the returned bytes hash to the requested value. If not, the response is rejected and another peer is queried.
5. The fetched node is admitted to the executor's local store.

Fetches are content-addressed: the executor knows what it expects, and verification is local. Tampering or substitution by intermediate parties is detected.

[Q-016](../open-questions.md#Q-016) asks how network failures during fetches are handled. The chosen design:

**Retry with backoff.** A failed fetch is retried with exponential backoff, up to a configurable maximum. The default is three retries with backoff starting at 100 ms.

**Fall back to alternative peers.** If a particular peer is unresponsive, the executor queries other peers known to hold the same hash. The peer directory is updated to reflect observed liveness.

**Bounded timeout per fetch.** Each fetch has a deadline; if no peer responds before the deadline, the fetch fails. The deadline is configurable; default is 30 seconds for nodes outside a critical path and 5 seconds for nodes on a critical path.

**Failure propagation.** A fetch that ultimately fails is propagated up to the scheduler. The scheduler may attempt re-placement (assigning the subgraph to an executor that holds the needed nodes locally), may abort the subgraph evaluation (returning a failure result), or may apply a workload-specific recovery policy. The decision is the scheduler's, parameterized by the graph's policy annotations.

## Backpressure {#backpressure-distribution}

[Q-015](../open-questions.md#Q-015) is addressed at the state-machine level in [state-machines.md](state-machines.md). The distribution-model perspective adds the cross-machine propagation story.

When a consumer machine's input queue is full, the producer is blocked (per the bounded-queue policy). When producer and consumer are on different executors, the block is communicated across the network: the producer's send-attempt returns a `Backpressure` signal, the producer pauses, and downstream effects propagate. The signal is part of the protocol between executors and is not visible at the language level except as a slowing of the producer.

For multi-hop chains (machine A feeds machine B feeds machine C), backpressure at C propagates through B to A. The propagation is straightforward at the protocol level but creates a fairness concern: a slow consumer at the end of a chain can starve every upstream stage. The runtime's monitoring surface should detect this pattern and the operational policy should respond (provisioning more consumer capacity, applying a drop policy at some point in the chain, etc.).

Backpressure interacts with placement: a chain that is consistently bottlenecked at C suggests C is overloaded; re-placing C or scaling C's executor capacity is the response. The scheduler may be configured to autonomously rebalance when persistent backpressure is observed.

## Worker discovery {#worker-discovery}

A Strand cluster is a set of executors that know each other. New executors join by announcing themselves to a known coordinator; existing executors register their capabilities and current load.

The coordinator is itself a Strand state machine. Its state is the membership and capability registry; its events are join, leave, capability update, and heartbeat messages. Its transition function applies these events to the registry, computes derived views (which executors hold which capabilities, which executors are alive), and emits these views as output for other executors to consume.

A cluster with a single coordinator has a single point of failure; the design supports multiple coordinators arranged in a consensus protocol (Raft is the reference; the protocol is itself implemented as state machines per [state-machines.md](state-machines.md)). For development use, a single coordinator suffices; for production, multi-coordinator clusters are recommended.

Discovery between executors uses the coordinator's published views: an executor needing to fetch a node consults its local copy of the view to find candidate peers. The view is updated periodically; stale views may direct fetches to peers that no longer hold the requested node, which the fetch protocol handles by failing and retrying.

## Failure handling {#failure-handling}

The runtime classifies failures into several categories:

**Transient.** Network blips, temporary peer unavailability, garbage collection pauses. Recovery is automatic: retry the operation, often after a brief delay.

**Persistent but recoverable.** An executor crash, a network partition, a misbehaving peer. Recovery requires runtime action: redirect work to another executor, partition the cluster and continue with the working subset, or quarantine the misbehaving peer.

**Unrecoverable.** A corrupt node (whose hash does not match its content), a fundamental capability mismatch (the cluster lacks any executor capable of performing a required effect), or a structural invariant violation (a graph that the verifier should have rejected). These are not recovery problems; they are correctness problems that the runtime surfaces to operators.

The runtime emits structured failure events for each. State machine instances handle their own failures through supervision (per [state-machines.md](state-machines.md)); non-state-machine computations propagate failures back to the requester, who may retry or abandon.

Cluster-level failures (a majority of coordinators down, persistent network partitions) are operational concerns that the design does not solve algorithmically. The standard mitigations apply: multi-region deployment, quorum-based decision making, and operator-driven recovery procedures.

## Locality and co-placement {#locality}

A graph often has natural locality: chains of operations that pass data between consecutive steps benefit from being co-placed on the same executor to avoid network transfer. The scheduler's `minimize data movement` policy captures this directly; explicit co-placement annotations let the programmer assert locality that the scheduler might not infer.

For state machines, locality has additional considerations. A state machine instance has state that lives on its executor. Migrating an instance to a different executor requires moving the state, which is expensive for large state. The scheduler's default is to keep an instance on its initial executor unless rebalancing is required; explicit migration commands move an instance and its state to a target executor.

For computations that span GPU/CPU boundaries (a model inference subgraph that requires GPU, with surrounding data preparation that runs on CPU), the scheduler may split the placement: the GPU-required subgraph on a GPU executor, the surrounding code on a colocated CPU executor. The split is automatic when the effect closure indicates the dependency; explicit pinning is available for finer control.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — integration and distributed-execution claims
- [`ADR-001-graph-not-text.md`](../decisions/ADR-001-graph-not-text.md) — graph as dependency structure
- [`ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — content addressing for fetches
- [`ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — effect-driven placement
- [`effects-and-capabilities.md`](effects-and-capabilities.md) — capability delegation rules
- [`state-machines.md`](state-machines.md) — state machine placement, backpressure
- [`security-model.md`](security-model.md) — defenses across worker boundaries
- [`open-questions.md`](../open-questions.md) — Q-014, Q-015, Q-016 addressed here

**Incoming references:**
- [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md)
- [`decisions/ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md)
- [`effects-and-capabilities.md`](effects-and-capabilities.md)
- [`state-machines.md`](state-machines.md)
- [`security-model.md`](security-model.md)
- [`research-plan.md`](../research-plan.md)
- [`rendering-and-views.md`](rendering-and-views.md) — placement of server-rendered vs. client-interactive subgraphs
