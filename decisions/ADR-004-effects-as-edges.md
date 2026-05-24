# ADR-004: Effects as Mandatory Typed Edges {#adr-004}

**Document:** `decisions/ADR-004-effects-as-edges.md`
**Status:** Accepted
**Date:** 2026-05-23
**Supersedes:** none
**Superseded by:** none

## Context {#context}

Most programming languages treat effects — interactions with the outside world, mutable state, time, hardware — as implicit. A function that writes to disk has the same type signature as a function that performs only arithmetic. Knowing whether a function has effects requires reading its body and the bodies of its transitive callees. This is acceptable when the author is a human who is also responsible for reasoning about effects, but it is exactly the wrong default when the author is an AI agent and the code will be executed on inputs the author does not control.

The research literature offers several alternatives. Effect-typed languages (Koka, Eff, Helium, Frank, Effekt) annotate effects in type signatures, often using row polymorphism to track sets of effects. Algebraic effect handlers (OCaml 5, Koka) allow effects to be intercepted and given semantics by callers. Capability-based languages (E, Pony) require references to be unforgeable and grant authority through reference passing. Each approach is a partial solution: effect types are usually opt-in, handlers tend to be limited to control-flow effects rather than security-relevant effects, and capability systems do not provide static effect tracking.

Strand requires complete effect coverage for two reasons. First, the security model promises that a graph's full effect set is statically computable; this fails the moment any untracked effects can hide in the graph. Second, distribution decisions are driven by effects: placement is determined by which executors can satisfy which effects. An untracked effect means an undecidable placement, which means the runtime cannot make distribution decisions without runtime introspection.

The question this decision answers is how effects are represented in the graph and what guarantees are made about their completeness.

## Decision {#decision}

Every Strand node that performs an effect declares it through one or more typed effect edges in the graph. Effect edges connect a node to one or more effect-category nodes, each of which describes a kind of interaction with the outside world or with execution state. Effect declarations are part of the graph topology, not a separate type-system overlay.

Effects propagate by graph closure. A node's effect set is the union of (a) the effects it declares directly through outgoing effect edges and (b) the effects of every node it references through other edges. The closure is computed by traversal of the graph; it is exact and decidable in time linear in the size of the graph.

Effect declarations are mandatory. A node that performs an effect cannot exist in a well-formed graph without an effect edge for that effect. There is no untracked-effect mechanism, no `unsafe` escape hatch in the core language, and no implicit ambient effects (no `print`, no `currentTime`, no implicit network or filesystem access). The only way for an effect to enter the graph is through a node that declares it.

Foreign nodes are a special case: a ForeignNode declaration includes effect annotations that describe the effects the foreign code performs. The verifier treats these annotations as authoritative for purposes of effect closure, while acknowledging that the annotations are claims by the binding author that the Strand verifier cannot independently confirm. The trust model for foreign bindings is the subject of [ADR-005](ADR-005-foreign-nodes.md).

Effect categories are typed and parameterized. A network effect is not just "Network" but "Network.Connect{host: H, port: P}"; a filesystem effect is "Filesystem.Read{path: P}". This granularity is required to enable fine-grained capability policies. The exact categorization and parameterization scheme is an open question ([Q-003](../open-questions.md#Q-003)); the design fixes that effects are categorized and parameterized but defers the inventory.

Execution is capability-mediated. The runtime holds a capability context — a set of effect tokens granted to the executing graph. When a node with an effect requirement is evaluated, the runtime checks that the context holds a matching capability. Evaluation halts with a capability error if it does not. The verifier may pre-check effect-versus-capability compatibility before execution begins, refusing graphs whose required effects exceed the capabilities the context will hold.

## Alternatives considered {#alternatives}

Four alternatives were evaluated and rejected.

**Effects as type-system annotations (Koka, Eff, Effekt, Helium).** Functions carry effect annotations in their types; the type checker enforces that callers handle or propagate the effects their callees perform. This is the most developed academic approach and has demonstrated that effect tracking is feasible for production languages. It is not chosen because the encoding adds a second representational layer (the type) on top of the graph. The advantage of effects-as-edges is that they live in the same representation as everything else; no separate type-system reasoning is required, and graph queries answer effect questions directly. Effect-types are also typically opt-in in their host languages (the type system tolerates `IO`-like wildcards), which Strand explicitly rejects.

**Monadic effect encoding (Haskell IO, the `mtl` stack, free monads).** Effects are tracked through monadic types that distinguish pure computations from effectful ones. This is a well-understood technique but inherits Haskell's whole-program type-class resolution costs. More fundamentally, the monadic encoding is implicit: a function that returns `IO a` performs unspecified effects, recoverable only by reading the implementation. Effect granularity requires monad transformers or effect libraries that reintroduce the row-types complexity from the previous alternative. Monadic encoding does not give the runtime the information needed for capability-mediated execution.

**Opt-in effect tracking with an untracked default.** Most languages with any effect system (Java's checked exceptions, Rust's `unsafe` blocks, TypeScript's strict modes) treat effect tracking as a discipline that can be turned off when convenient. This is rejected because the security guarantees Strand offers depend on every effect being tracked; an opt-in system collapses to no system once any code uses the escape hatch.

**Pure capability discipline with no static effect information (E, Pony, object capability calculi).** Capabilities are unforgeable references; a function can only perform an action by holding the corresponding reference. Static effect tracking is not needed because the runtime enforces that a function cannot act without the relevant capability. This is elegant but does not support distribution decisions before execution: the scheduler cannot know which placements satisfy a graph's needs without traversing the graph to discover what capabilities it requires. Strand combines the capability mechanism (at runtime) with effect declarations (at the graph level) so that placement decisions are pre-computable while the runtime check remains the source of truth.

## Consequences {#consequences}

Effect closures are computable in linear time over the graph. The runtime, the verifier, and analysis tools can answer "what effects does this subgraph require" by graph traversal, with no whole-program analysis and no undecidable type inference.

Security policies become structural. A policy that says "this graph may not access the network" is enforced by rejecting any graph whose effect closure contains a network-category effect. The check is exact, not approximate. The same policy expressed against a conventional language requires either static analysis with false positives and false negatives, or runtime sandboxing that imposes broad coarse-grained restrictions.

Placement decisions are driven by effects. A pure subgraph (no effect edges anywhere in its closure) is placeable on any executor. A subgraph whose closure contains an effect requiring a specific capability must run where that capability is granted. The scheduler's constraint problem reduces to assigning subgraphs to executors such that every effect requirement is met by some capability in scope. This connects to [design/distribution-model.md](../design/distribution-model.md) and the scheduling policy questions ([Q-014](../open-questions.md#Q-014)).

Boilerplate cost is paid in declarations. Every effectful node must declare its effects, every foreign node must declare what its foreign code does, and every composition must permit the effects it transitively requires. For agent-generated graphs this is the natural overhead: declaring the effects is part of generating the node, not an additional step. For graphs translated from other languages, effect inference tools may help propose declarations, but the declarations must be present and validated before the graph is admitted.

The verifier must support effect-category logic. The set of admissible effect categories, their parameterization, and the rules for combining and refining them require formal specification ([Q-003](../open-questions.md#Q-003)). Until that question is resolved, the verifier cannot be fully implemented. The design fixes that effects are typed and parameterized; the specific algebra is deferred.

Effect handlers as a control-flow mechanism are not part of this decision. Koka-style effect handlers (where a caller can install a handler that intercepts an effect and provides its meaning) are a powerful pattern that may or may not be adopted by Strand. The graph-edge representation accommodates handlers naturally (an edge to a handler node alongside the edge to the effect category) but handler semantics is a node-algebra question, not a graph-representation question. The decision adopted here is about representation; handler semantics is decided in [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md).

Effects unify static analysis and runtime enforcement. The same effect edges that drive placement and security policy are the basis for the runtime's capability checks. The graph carries both the static information (what the node will require) and the runtime contract (what the node may do at execution time). The two representations cannot drift apart because they are the same representation.

The foreign-effect trust problem is not eliminated. A foreign binding can lie about its effects, and the Strand verifier cannot independently confirm the binding's claims. This is a real attack surface and is the subject of [ADR-005](ADR-005-foreign-nodes.md) and [Q-006](../open-questions.md#Q-006). The decision adopted here is that the verifier treats declared effects as authoritative under a separate trust model for the source of declarations.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — Claim 3, mandatory effects
- [`01-prior-art.md`](../01-prior-art.md) — effect-typed and capability-based languages
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — graph foundation that admits effect edges
- [`ADR-005-foreign-nodes.md`](ADR-005-foreign-nodes.md) — foreign effect trust model
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — handler semantics and full algebra
- [`design/distribution-model.md`](../design/distribution-model.md) — effect-driven placement
- [`open-questions.md`](../open-questions.md) — Q-003, Q-006, Q-014

**Incoming references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — cites this ADR from Claim 3
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — defers effect-edge mechanics to this ADR
- [`ADR-003-content-addressing.md`](ADR-003-content-addressing.md) — references capability constraints from this ADR
- [`ADR-005-foreign-nodes.md`](ADR-005-foreign-nodes.md) — foreign nodes declare effects per this ADR
- [`ADR-006-per-node-encryption.md`](ADR-006-per-node-encryption.md) — capability mediation for decryption
- [`ADR-007-state-machines.md`](ADR-007-state-machines.md) — transition functions declare effects per this ADR
- [`ADR-008-compilation-target.md`](ADR-008-compilation-target.md) — effect metadata preserved through compilation
- [`design/node-algebra.md`](../design/node-algebra.md) — effect edges in the algebra
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — full effect-system specification
- [`design/state-machines.md`](../design/state-machines.md) — effects on transitions
- [`design/security-model.md`](../design/security-model.md) — effect mediation as primary defense
- [`design/distribution-model.md`](../design/distribution-model.md) — effect-driven placement
- [`ADR-009-structured-outputs.md`](ADR-009-structured-outputs.md) — output emission uses existing effect categories
- [`design/rendering-and-views.md`](../design/rendering-and-views.md) — emission as the only effectful step in rendering
