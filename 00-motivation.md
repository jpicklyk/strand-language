# Motivation {#motivation}

**Document:** `00-motivation.md`
**Status:** Stable
**Last revised:** 2026-06-11

## Summary

Strand exists because programming languages currently used for AI-generated code were designed for human authorship. The constraints these languages optimize for — visual readability, sequential parsing, lexical scope, human-meaningful naming — are either irrelevant or actively harmful when the primary author of code is a large language model rather than a human. Strand is a research project to determine whether a language designed around the operational characteristics of LLMs can produce measurably better outcomes on the metrics that matter for AI-generated software: correctness, analyzability, security, distribution, and inference cost.

## The mismatch between text languages and LLM generation {#text-llm-mismatch}

Text-based programming languages serialize program structure into linear character sequences. This serialization exists because humans read linearly, have limited working memory, and benefit from visual layout cues. A compiler's first task on any text source is to discard the linear ordering and reconstruct the underlying graph of definitions, references, types, and control flow. The text representation is a human-oriented serialization format imposed on top of a fundamentally non-linear structure.

For an LLM generating code, this serialization imposes several costs:

**Token overhead for syntactic noise.** Tokens corresponding to braces, semicolons, indentation, keywords, and visual layout consume context window and inference compute without contributing to program semantics. Empirical studies of AI-oriented Python grammars (SimPy, ShortCoder, Token Sugar) demonstrate 10–35% token reductions from purely syntactic simplification, with no change to underlying semantics.

**Autoregressive commitment to syntactic choices.** Token-by-token generation requires committing to syntactic decisions — variable names, brace placement, statement ordering — before the corresponding semantic decisions are fully resolved. This produces a class of errors where the model "paints itself into a corner" syntactically and must either backtrack expensively or produce locally well-formed but globally incorrect code.

**Mismatch between generation order and program structure.** Programs have a natural dependency order (a function definition depends on the types it references), but text source has an arbitrary file order. The LLM must hold both orderings simultaneously, paying attention cost to maintain consistency between them.

**Loss of structural information at generation boundaries.** When an LLM emits text, the structural information it used internally (which token corresponds to which AST role) is discarded. The receiving compiler must reconstruct this information through parsing, which is lossy when the text is malformed. A representation that preserved structure across the generation boundary would eliminate an entire class of failure modes.

## What text languages do not provide {#missing-properties}

Beyond the costs of text representation, conventional languages lack properties that would benefit AI-generated code:

**Mandatory effect declarations.** Whether a function performs IO, mutates state, or accesses the network is typically implicit in conventional languages, recoverable only by reading the function body and the bodies of its transitive callees. AI-generated code is most dangerous in exactly the cases where effects are unobvious — a function that quietly exfiltrates data or modifies global state. Languages with effect systems (Koka, Eff, OCaml 5 effect handlers, Haskell's IO type) make effects explicit, but treat them as opt-in rather than mandatory, and few are used in production AI-generation contexts.

**Content-addressed identity.** Conventional languages identify code by file path and name, requiring an import system, name resolution, and module boundaries. Changes to a function's name or location cascade through dependents. For AI-generated code, where the model frequently refactors and rewrites, this brittleness compounds. Content-addressed identity (as in Unison) eliminates the cascade by making references resolve to immutable hashes.

**Capability-based execution.** Conventional runtimes either trust code fully or sandbox it at coarse boundaries (processes, containers, VMs). Neither matches the threat model of AI-generated code, where individual functions may need to be granted narrow capabilities (read this file, connect to this host) rather than broad permissions (filesystem access, network access). Capability-based execution exists in research languages but is not available in any production language used for AI code generation.

**Structural distribution.** Distributing a program across threads, machines, or hardware accelerators requires either explicit programmer effort (Akka actors, gRPC services, Spark DAGs, CUDA kernels) or framework-imposed structure (TensorFlow graphs, Ray remote functions). The information needed to distribute a program — dependency edges between computations, effects performed by each computation, data flow between them — is already present in the program but obscured by text representation.

## The hypothesis {#hypothesis}

Strand's central hypothesis is that a programming language combining:

1. Graph-structured canonical representation (no canonical text syntax; authoring projections carry no program identity)
2. Mandatory effect declarations as typed edges
3. Content-addressed node identity
4. Capability-based execution
5. First-class state machines and event streams

will produce, when used as the target for AI code generation:

- Higher rates of first-pass correctness due to structural verification at every operation
- Lower inference cost per task due to elimination of syntactic tokens
- Stronger security guarantees due to explicit effects and capability boundaries
- Native distribution to threads, clusters, and heterogeneous hardware without per-program engineering
- Cleaner integration with confidential computing primitives (TEEs, FHE) due to structural partitioning

These claims are empirically testable. The research plan in [`research-plan.md`](research-plan.md) describes the experiments that would establish or refute them.

## Why not modify an existing language {#why-not-modify}

A natural alternative to designing a new language is to modify an existing language toward these properties. Several projects have taken this path:

- SimPy modifies Python's grammar to reduce LLM token consumption while preserving Python's AST.
- QUASAR has LLMs generate a Python subset, then transpiles to a separately-designed execution language with parallelism and security features.
- Pel layers a new orchestration language on top of LLM-friendly Lisp-style syntax.

These approaches have value, but inherit constraints from their host languages. Python's dynamic typing precludes static effect verification; its mutable global state precludes content-addressing; its threading model precludes structural distribution. Modifying Python to support all of Strand's properties would either require breaking changes that disqualify the result as "Python," or layered abstractions that reintroduce the costs they aimed to eliminate.

A clean-slate design accepts the cost of starting without a code corpus in exchange for the freedom to align every design decision with the AI-generation use case. The corpus problem is addressed by training methodology (see [`research-plan.md`](research-plan.md), section on bootstrap), not by language design compromises.

## Why human readability is not a goal {#no-human-readability}

A foundational decision is that Strand programs are not designed for human reading. There is no canonical text syntax, no canonical projection from graph to text, and no expectation that a human will inspect a Strand program directly during normal development. Authoring projections that serve agent emission carry no program identity and no canonical status; the stored, verified form is the graph.

This choice is examined in [`ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md). It is foundational because it determines the size and shape of the project: building a graph-native language without a projection layer is a tractable research project; building one with a high-quality human projection is a multi-year engineering effort. The research framing — testing the hypothesis above — does not require human-readable code, only verifiable, executable, distributable code that AI agents can generate and humans can audit through analysis tooling rather than reading.

For contexts where human inspection is required (failure forensics, security audit, regulatory compliance), Strand will provide *analysis tooling* — graph queries, structured diffs, dependency visualizations — rather than a textual rendering. The position is that these tools are more useful for the inspection use cases that actually matter than a textual rendering would be.

## Scope of this document

This document establishes the rationale for the project. It does not specify language features, evaluate alternatives in depth, or describe implementation strategy. Those concerns are addressed in the documents listed below.

## References

**Outgoing references:**
- [`02-core-thesis.md`](02-core-thesis.md) — the design claims this motivation justifies
- [`01-prior-art.md`](01-prior-art.md) — existing work on AI-oriented languages
- [`ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md) — the readability decision
- [`research-plan.md`](research-plan.md) — empirical evaluation of the hypothesis

**Incoming references:**
- [`README.md`](README.md)
- [`02-core-thesis.md`](02-core-thesis.md)
- [`decisions/ADR-001-graph-not-text.md`](decisions/ADR-001-graph-not-text.md)
- [`decisions/ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md)
- [`research-plan.md`](research-plan.md)
