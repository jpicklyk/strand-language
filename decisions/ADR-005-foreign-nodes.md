# ADR-005: Foreign Function Interface via ForeignNode {#adr-005}

**Document:** `decisions/ADR-005-foreign-nodes.md`
**Status:** Accepted
**Date:** 2026-05-23
**Supersedes:** none
**Superseded by:** none

## Context {#context}

Strand programs need to interact with code written in other languages. The existing software ecosystem — operating system services, hardware drivers, machine learning runtimes, network stacks, databases, ML model interpreters, vendor SDKs — represents a substantial body of code that cannot reasonably be rewritten in Strand. A Strand-only world is not a viable research framing; some path between Strand graphs and existing code is required.

The conventional approach is a foreign function interface: a mechanism for declaring functions in language A that can be called from language B, with a translation layer between the calling conventions, type systems, and memory models of the two languages. Most languages provide an FFI (Python's `ctypes`, Java's JNI, Rust's `extern "C"`, Haskell's FFI, Erlang's NIFs). The WebAssembly Component Model and its WIT interface format represent the current state of the art for typed cross-language interfaces with rich effect-aware boundaries.

Strand's graph-native, content-addressed, effect-typed structure imposes constraints that conventional FFI mechanisms do not address. A Strand graph cannot embed arbitrary foreign code as data without losing the structural properties that make Strand worthwhile. Foreign calls must be reachable through the graph topology, not as out-of-band escapes. Effect declarations must extend across the foreign boundary, with declared effects propagating through the closure as if the foreign code were a Strand subgraph. The verifier and the runtime must treat foreign calls as part of the program's effect surface.

The question this decision answers is how foreign code enters a Strand graph and what guarantees the language makes about effect propagation across the boundary.

## Decision {#decision}

Strand provides a designated node type, ForeignNode, that wraps a callable target in another language. A ForeignNode declares (a) the foreign target (language, library identifier, symbol or interface name), (b) the function signature in Strand types, (c) the effect annotations that describe what the foreign code does at runtime, (d) the capability requirements derived from the effects, and (e) provenance metadata identifying the source and version of the binding.

The Strand verifier treats the declared effects on a ForeignNode as authoritative for purposes of effect closure: a graph containing the ForeignNode is treated as performing the declared effects whenever the ForeignNode is reachable from a root. The verifier does not attempt to confirm the declarations by analyzing the foreign code; the declarations are accepted as a contract between the binding author and the Strand runtime.

ForeignNodes are content-addressed like any other node. The hash covers the declaration content: target identifier, signature, effects, capabilities, provenance. Two ForeignNodes with the same declaration hash to the same identity; two ForeignNodes targeting the same foreign function with different effect declarations are distinct nodes. The provenance metadata is part of the hash, so a re-signed or re-published binding produces a different ForeignNode.

The runtime invokes foreign code in a capability-restricted execution context. The capability context for a foreign call is the intersection of the capabilities declared by the ForeignNode and the capabilities held by the calling graph at the point of invocation. If the foreign code attempts to perform an effect outside its declared set — observable through sandbox boundaries where the platform supports observation — the runtime halts the call and raises a contract violation. The platforms where this observation is possible include WebAssembly modules (where syscalls are explicit), seccomp-restricted processes, and TEE-isolated executions; on platforms without such isolation, the contract is enforced only at the boundary by the binding's effect declarations.

The trust model for foreign bindings is established by [Q-006](../open-questions.md#Q-006) and is the subject of [`design/security-model.md`](../design/security-model.md). The decision adopted here is that bindings carry provenance metadata identifying their origin and that the runtime may be configured to reject bindings whose provenance does not match a trusted source. The specific mechanism — signature verification, reproducible-binding-generation comparison, curated-registry membership, or a combination — is not fixed by this ADR.

## Alternatives considered {#alternatives}

Four alternatives were evaluated and rejected.

**No FFI; Strand-only ecosystem.** The most restrictive option: Strand provides primitive nodes for fundamental operations (arithmetic, comparison, basic control flow) but cannot interact with any existing code. Building useful systems requires either rewriting all dependencies in Strand or providing services through network-distant interfaces (HTTP, gRPC) that themselves require some implementation. The cost is prohibitive: every operating system service, every device driver, every numerical library would need a Strand reimplementation before any useful program could be built. The research evaluation requires foreign interoperability to compare Strand against existing languages on realistic tasks.

**Inline foreign source code embedded as data in the graph.** A ForeignNode could carry the source code of its foreign function as a string, with the runtime compiling and linking the code at load time. This avoids the binding-registration step but introduces serious problems: the foreign source becomes part of the graph hash (so any source change creates a different node), the graph contains opaque binary data that cannot be analyzed, and the runtime must include compilers for every supported foreign language. The approach is rejected because it conflates the graph-as-source property (Strand programs are graphs) with foreign code that is not graph-shaped.

**Sidecar processes communicating via IPC.** Foreign code runs in a separate process; Strand graphs send messages to the process and receive results. This is well-understood and has strong isolation properties, but the overhead per call is prohibitive for fine-grained interactions (microseconds of IPC latency dwarf the cost of most function calls). The model is appropriate for long-running services where the cost is amortized across many calls — and Strand state machines provide exactly this for service-like foreign components ([ADR-007](ADR-007-state-machines.md)) — but it is not adequate as the primary FFI mechanism.

**Header-derived bindings with no effect annotations.** Tools like `bindgen` (Rust) and SWIG (multi-language) generate FFI bindings automatically from header files. This solves the binding-creation problem but produces bindings with no effect information, since C and most ABI-level languages do not declare effects. A header-derived binding for a function whose effects are unknown must default to either "no effects" (unsafe — the binding may lie by omission) or "all effects" (useless — the binding can do anything, so capability mediation grants nothing useful). Header-derived binding generation is an input to ForeignNode creation, not a substitute for effect declarations. Effect inference for unannotated foreign code is open ([Q-007](../open-questions.md#Q-007)).

## Consequences {#consequences}

Strand can interoperate with existing libraries provided that bindings exist. The binding ecosystem is itself a substantial project: each foreign library targeted requires bindings, each binding requires effect annotations, and each annotation requires understanding of the foreign code's actual behavior. The research evaluation must select a small set of foreign libraries for initial bindings (likely: a POSIX shim for filesystem and network, a tensor library, a database client) rather than attempting comprehensive coverage.

The verifier's correctness depends on the truthfulness of ForeignNode declarations. A ForeignNode that misdeclares its effects breaks the security guarantees of every graph that uses it. The trust model is therefore not optional. Without a trust model, the effect system provides defense in depth only against honest accidents, not against malicious bindings.

The Strand runtime must support sandboxed foreign invocation. The required sandboxing depends on the target platform: WebAssembly modules can be invoked with a runtime that enforces declared imports; native libraries require process-level isolation, system call interposition, or hardware-assisted isolation. The reference implementation should support at least one sandboxed target (WebAssembly is the natural choice given its component model and WIT) and one native-library target with weaker but acceptable isolation for development use.

Foreign provenance is part of the graph's identity. Two graphs that reference the same foreign function through differently-signed bindings are distinct graphs. This produces some duplication when a library has multiple binding sources, but the alternative — making bindings equivalent up to their target — destroys the property that the graph's hash determines its full execution semantics.

The WIT/Component Model integration is natural. WIT is a typed interface description format with effect-aware imports and exports; a ForeignNode can be generated from a WIT signature with effect annotations that map to Strand's effect categories. Adopting WIT as the primary binding format reduces the per-binding work and aligns Strand with an existing cross-language standard. This is a tooling decision rather than a language decision, but it shapes the practical experience of building Strand programs.

Effect inference for unannotated foreign code is a research question. For foreign code with declared effects (WIT, Erlang specifications, well-documented Rust crates), bindings are straightforward. For undeclared code (most C libraries, most Android SDK methods), the binding author must either supply declarations manually, rely on static analysis of the foreign code, or accept conservative worst-case declarations that may make the binding unusable in fine-grained capability contexts. The default policy and tooling support are open ([Q-007](../open-questions.md#Q-007)).

Confused-deputy attacks are not eliminated by the ForeignNode mechanism. A foreign function called with capabilities sufficient for its declared effects can still be manipulated by a less-privileged Strand caller into operating on data the caller chose. This is the standard confused-deputy problem and applies to any capability system. Mitigation is the subject of [Q-005](../open-questions.md#Q-005) and is not solved by this ADR.

## References

**Outgoing references:**
- [`01-prior-art.md`](../01-prior-art.md) — FFI references including WebAssembly Component Model
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — graph foundation
- [`ADR-003-content-addressing.md`](ADR-003-content-addressing.md) — ForeignNodes are content-addressed
- [`ADR-004-effects-as-edges.md`](ADR-004-effects-as-edges.md) — effect declarations the binding makes
- [`ADR-007-state-machines.md`](ADR-007-state-machines.md) — sidecar-style foreign services
- [`design/security-model.md`](../design/security-model.md) — trust model for bindings
- [`open-questions.md`](../open-questions.md) — Q-005, Q-006, Q-007

**Incoming references:**
- [`ADR-004-effects-as-edges.md`](ADR-004-effects-as-edges.md) — defers foreign effect trust model to this ADR
- [`ADR-006-per-node-encryption.md`](ADR-006-per-node-encryption.md) — interface declarations follow the same trust pattern
- [`ADR-007-state-machines.md`](ADR-007-state-machines.md) — foreign event sources
- [`ADR-008-compilation-target.md`](ADR-008-compilation-target.md) — foreign-node invocation in the runtime
- [`ADR-009-structured-outputs.md`](ADR-009-structured-outputs.md) — foreign rendering engines and invariant-checker bindings
- [`design/node-algebra.md`](../design/node-algebra.md) — ForeignNode category
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — foreign effect declarations
- [`design/security-model.md`](../design/security-model.md) — foreign trust model
- [`design/state-machines.md`](../design/state-machines.md) — foreign event sources
- [`research-plan.md`](../research-plan.md) — Milestone 2.4 binding work
