# ADR-009: Structured Outputs and Verifier-Checkable Invariants {#adr-009}

**Document:** `decisions/ADR-009-structured-outputs.md`
**Status:** Accepted
**Date:** 2026-05-23
**Supersedes:** none
**Superseded by:** none

## Context {#context}

Strand programs produce outputs that humans and other systems consume: HTML pages, SVG diagrams, PDF reports, JSON API responses, audio frames, screen-rendered UI, database mutations, signed transcripts. The decision in [ADR-002](ADR-002-no-human-projection.md) that programs themselves are not projected to humans does not extend to program outputs. Outputs are the surface across which a Strand graph meets a non-Strand world, and they must be of a quality appropriate to that world.

The core thesis ([02-core-thesis.md](../02-core-thesis.md)) and the existing Wave 3 specifications do not state how output artifacts are constructed, what guarantees the language makes about them, or where the boundary between in-graph structure and out-of-graph serialization falls. The existing node algebra ([node-algebra.md](../design/node-algebra.md)) provides primitive value types (literals, products, sums, functions) and the ForeignNode mechanism ([ADR-005](ADR-005-foreign-nodes.md)) for crossing language boundaries, but it does not establish how a value of, for example, an HtmlElement type can be constructed in such a way that the verifier can guarantee the resulting serialized HTML is well-formed, accessible, or compliant with a content security policy.

Three positions are available. The first is to make structured output formats part of the language itself: HTML, SVG, and similar are primitive node categories with their own well-formedness rules baked into the algebra. The second is to treat outputs as opaque `Bytes` values constructed by ForeignNode calls into libraries that the verifier cannot reason about. The third is intermediate: outputs are library types built from the existing algebra, but the algebra is extended with a *schema mechanism* that allows libraries to declare verifier-checkable invariants over their types, so that the verifier can statically prove properties of output values before any byte is emitted.

The choice has consequences for the language's scope, for the trust model that applies to output artifacts, and for the kinds of guarantees agents can rely on when generating user-facing software. A choice that defers all output reasoning to libraries leaves the verifier blind to a class of bugs (malformed HTML, missing alt text, unsanitized injection) that are particularly common in agent-generated UI. A choice that pulls every output format into the language core inflates the algebra and conflates language design with library curation.

The question this decision answers is what mechanism Strand provides for constructing structured outputs and what guarantees the verifier makes about them.

## Decision {#decision}

Strand provides a *schema mechanism* that allows libraries to declare verifier-checkable invariants over value types. Output formats such as HTML, SVG, and PDF are implemented as libraries that use this mechanism; they are not primitives of the language. The verifier is extended with a registration protocol for invariant checkers: a library supplies a set of predicates that the verifier evaluates at graph-construction time over nodes claimed to satisfy a schema.

The algebra is extended with two new node categories. *Schema* (N-032) declares a named contract that a class of values must satisfy: a structural shape (typically a ProductType or SumType built from existing types), a set of invariant identifiers, and metadata identifying the library that owns the schema. *Invariant* (N-033) declares a single verifier-checkable predicate: an identifier, a target schema, and a body that is either a pure Strand expression (verifiable by the standard type and effect checks) or a registered checker name resolved by the verifier through the library's binding.

Output emission remains an effect under the existing categories. A pure subgraph constructs a value of a schema-bearing type. A subsequent node serializes the value to `Bytes` (a pure operation, typically a library function declared on the schema). An effectful node emits the bytes through one of the existing effect categories — `Network.Send` for HTTP responses, `Filesystem.Write` for files on disk, `Hardware.GPU` for screen rendering, `StateMachine.Send` for event-stream outputs. No new effect category is introduced for "rendering" as such; rendering is a composition of pure construction, pure serialization, and an existing effect.

The schema mechanism does not displace ForeignNode. Libraries that wrap external rendering engines (a PDF generator written in C, a browser engine that consumes HTML and produces pixels) continue to use ForeignNode for the cross-language call. The schema mechanism applies to the in-graph value that is passed to the foreign call: if the value is well-formed by the verifier's check, the foreign call receives a value that the binding's contract guarantees it can process.

The detailed specification — what predicates are admissible, how the verifier resolves library-supplied checkers, what the initial set of blessed output libraries is, how invariants compose across libraries — is recorded in [`design/rendering-and-views.md`](../design/rendering-and-views.md). The decision adopted here fixes the mechanism and the layering; the specific predicates and library selections are deferred.

## Alternatives considered {#alternatives}

Three alternatives were evaluated and rejected.

**Output formats as language primitives.** Strand provides built-in node categories for HTML elements, SVG elements, PDF operations, and other common structured outputs, with their well-formedness rules baked into the verifier. This is the most opinionated option. It is rejected because the set of structured output formats the world cares about is open and growing: any choice of primitives leaves out formats that future agents will need to generate. Pulling each new format into the core inflates the algebra, makes the language version-coupled to the formats it knows about, and confuses language design with library curation. The number of node categories required would dominate the rest of the algebra. The schema mechanism captures the same guarantees through a uniform extension point.

**Outputs as opaque Bytes via ForeignNode.** Strand provides no specific support for structured outputs. Libraries build values of whatever shape they prefer using existing types, serialize them through ForeignNode calls into external libraries, and emit the resulting `Bytes`. This is the most restrained option. It is rejected because the verifier becomes blind to output structure: a graph that constructs malformed HTML, omits required ARIA attributes, or includes unsanitized user input in a position that permits script injection cannot be rejected at construction time. These properties must instead be enforced by the rendering library at runtime, which means the failure mode is "the page renders incorrectly" rather than "the graph does not pass verification." For agent-generated UI, where the agent's contract with the human is precisely that the output is correct, the runtime-only enforcement is too late. The schema mechanism enables the verifier to reject malformed outputs before they exist.

**Type refinements without library extension.** Strand adopts a refinement-type system in which arbitrary predicates can be attached to existing types, evaluated by the verifier through SMT solving or a similar decision procedure. This subsumes the schema mechanism and is more expressive. It is rejected because the implementation cost is substantial: a competitive refinement-type system requires a decision procedure that handles the language's full type structure, and the verifier becomes a non-trivial theorem prover. The scope is appropriate for a future research direction but exceeds what the reference implementation can support. The schema mechanism is a constrained subset of refinement typing: invariants are pure Strand expressions over the schema's structure or named checkers supplied by libraries, evaluated by the standard verifier with library-supplied dispatch. The full refinement-type generalization is preserved as a possible future extension and is recorded as an open question.

## Consequences {#consequences}

The node algebra grows by two categories (N-032, N-033). This is a conservative additive extension under the versioning policy in [node-algebra.md](../design/node-algebra.md): older graphs remain valid, older runtimes refuse graphs that use the new categories. The schema mechanism is opt-in: a graph that does not reference Schema or Invariant nodes is unaffected, and the verifier's existing well-formedness rules apply unchanged.

Verifier extension points are now part of the language surface. A library that supplies invariant checkers is making a contract with the verifier, analogous to the contract a ForeignNode binding makes about effects. The trust model for invariant checkers parallels the trust model for foreign bindings: a checker that wrongly accepts an invalid value breaks the verifier's guarantees for every downstream graph that uses the schema. The provenance and trust mechanisms specified in [`design/security-model.md`](../design/security-model.md) for ForeignNode bindings extend to invariant checkers; the specific extension is part of [`design/rendering-and-views.md`](../design/rendering-and-views.md).

Output libraries become a privileged ecosystem layer. The reference distribution includes a small set of blessed schemas — HTML5, SVG, JSON, and PDF are the natural initial set — whose invariant checkers are part of the runtime. Third-party schemas can be introduced through the standard library-loading mechanism, but the trust model requires explicit provenance for the checkers they install. Curation of which schemas are included by default is a deployment policy, not a language decision.

Agents generating UI receive structural guarantees they cannot otherwise get from language design. An agent that constructs an HTML page through the HTML5 schema cannot, by construction, emit a page that violates the schema's invariants: the verifier rejects the graph at construction time. The class of bugs this catches includes malformed nesting (lists with non-`li` children, paragraphs inside paragraphs), missing required attributes (alt text on `img`, labels on form controls), and structural injection (script content where text content was declared). The verifier does not catch every output bug — semantic bugs (the page renders, but the data shown is wrong) remain — but the structural floor is meaningfully higher than for languages whose verifier sees only `Bytes`.

The effect closure of a rendering pipeline is determined by the pipeline, not by the rendering. Pure construction of an `HtmlElement` value has no effects. Pure serialization to bytes has no effects. The emission of those bytes carries the effect appropriate to its destination (network, filesystem, screen, stream). A graph that constructs a page but does not emit it has no effects at all; this is useful for previewing, hashing, snapshotting, and differential rendering, because the construction can be performed in pure contexts that need no capabilities.

Live and interactive outputs compose naturally with state machines ([ADR-007](ADR-007-state-machines.md)). A state machine whose state is or contains a schema-bearing value, with an output stream whose events carry serialized renderings, is a live view: each transition produces a new rendering that the receiver applies. The verifier's invariant guarantees hold across every state the machine can reach. The design of this composition is in [`design/rendering-and-views.md`](../design/rendering-and-views.md).

Provenance of output artifacts is recoverable. Because every node in the constructed output is content-addressed ([ADR-003](ADR-003-content-addressing.md)), and serialization is a pure function of the input value, the serialized bytes can be annotated with a mapping from output positions back to source node hashes. This enables "why is this number 42" questions to be answered by walking the graph, and supports debugging tooling that bridges rendered output and source structure. The provenance encoding is the subject of an open question.

This ADR does not specify the rendering effect, the schema invariant language, the initial blessed library set, or the verifier extension protocol in detail. These are recorded in [`design/rendering-and-views.md`](../design/rendering-and-views.md). The decision adopted here is the mechanism and the layering; the specific designs are deferred.

The schema mechanism is potentially useful beyond output formats. Any value whose validity is structurally describable (configuration data, message protocols, state-machine event types, query results) can be schema-typed and benefit from verifier-checked invariants. The rendering use case drives the initial design, but the mechanism is general; the design should not foreclose other uses. This is recorded in the consequences of [`design/rendering-and-views.md`](../design/rendering-and-views.md) as a possible future direction.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — programs as graphs; outputs as the boundary to non-Strand worlds
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — graph foundation that output values inherit
- [`ADR-002-no-human-projection.md`](ADR-002-no-human-projection.md) — program-vs-output distinction
- [`ADR-003-content-addressing.md`](ADR-003-content-addressing.md) — provenance of output artifacts
- [`ADR-004-effects-as-edges.md`](ADR-004-effects-as-edges.md) — emission as effect, not a new effect category
- [`ADR-005-foreign-nodes.md`](ADR-005-foreign-nodes.md) — foreign rendering engines; trust-model parallel
- [`ADR-007-state-machines.md`](ADR-007-state-machines.md) — live views as state-machine outputs
- [`design/node-algebra.md`](../design/node-algebra.md) — N-032, N-033 extend this algebra
- [`design/rendering-and-views.md`](../design/rendering-and-views.md) — detailed specification of the mechanism
- [`design/security-model.md`](../design/security-model.md) — trust model for invariant checkers
- [`open-questions.md`](../open-questions.md) — Q-025, Q-026, Q-027, Q-028

**Incoming references:**
- [`design/rendering-and-views.md`](../design/rendering-and-views.md) — references this ADR as its architectural basis
