# ADR-008: Compilation Target — Bytecode VM with MLIR Lowering Path {#adr-008}

**Document:** `decisions/ADR-008-compilation-target.md`
**Status:** Accepted
**Date:** 2026-05-23
**Supersedes:** none
**Superseded by:** none

## Context {#context}

A Strand graph must eventually execute. The graph itself is the source representation ([ADR-001](ADR-001-graph-not-text.md)); something downstream of the graph turns it into instructions a processor performs. The set of viable targets includes a custom bytecode virtual machine, native code via a traditional compiler, native code via MLIR/LLVM, WebAssembly, a managed runtime such as the JVM or CLR, or a tree-walking interpreter that consumes the graph directly.

The choice has consequences across several dimensions. Implementation cost varies by an order of magnitude between alternatives. Debugging and replay support is much easier in some targets than others. Effect and capability metadata must survive every lowering stage; some targets preserve metadata naturally, others lose it. Performance differs by another order of magnitude between interpretive and compiled execution. Portability differs between targets that produce platform-native code and those that produce platform-independent output. The choice also constrains the future: a target that loses effect metadata cannot be the basis for capability checks, regardless of how fast it runs.

The research phase has different needs than a production deployment. For research, the priority is correctness, observability, and the ability to evaluate the design's claims; performance is secondary. For production, performance becomes important but observability and effect preservation remain non-negotiable. The compilation target chosen for the research phase should not foreclose the production path.

The question this decision answers is what compilation strategy the reference implementation adopts and how the production path is structured.

## Decision {#decision}

The reference implementation uses a custom bytecode virtual machine as the initial execution target. The bytecode is a stack-based instruction set with explicit support for the operations Strand requires: node evaluation, capability checks, effect-edge dispatch, foreign-node invocation, state machine transition, and event-stream operations. The bytecode preserves effect and capability information as first-class metadata, not as auxiliary annotations the runtime might or might not check.

A second compilation path lowers Strand graphs to an MLIR dialect for ahead-of-time native compilation. The MLIR dialect carries effect and capability operations through to a low-level representation, with progressive lowering passes that translate Strand-specific operations into a combination of LLVM IR and platform-specific runtime calls. The MLIR path is intended for production deployments where performance matters; the bytecode path remains the canonical execution semantics. Both paths must produce equivalent observable behavior for the same graph and the same capability context.

WebAssembly is a third potential target for portable distribution. A Strand graph compiled to WebAssembly can execute in sandboxed environments where native compilation is not viable. The WebAssembly path can be reached either as an MLIR lowering destination or as a direct bytecode-to-WebAssembly translation; both options are open and not fixed by this ADR.

The bytecode specification itself is open ([Q-017](../open-questions.md#Q-017)). The instruction set, value representation, calling convention, and garbage collection model require detailed design before implementation. The MLIR dialect specification is also open ([Q-018](../open-questions.md#Q-018)). The decision adopted here fixes the strategy and the layering; the specific designs are deferred.

Iterative computation has a representation question that interacts with compilation ([Q-019](../open-questions.md#Q-019)). The bytecode and the MLIR dialect must both support some form of looping or fixpoint construct that LLMs can generate and that the verifier can analyze. Whether this is a Fixpoint node type, recursion through named indirection, or imperative loops gated by effects affects what the bytecode and the dialect need to encode.

## Alternatives considered {#alternatives}

Five alternatives were evaluated and rejected, or accepted as components of the chosen strategy.

**Tree-walking interpreter directly over the graph.** The simplest possible execution model: the runtime traverses the graph and evaluates each node by dispatch on its type. This is straightforward to implement, easy to debug, and trivially preserves effect metadata. It is rejected as the long-term target because the performance is poor: every node evaluation incurs interpretation overhead, and the dispatch is not amortizable across calls. It is acceptable as a development aid during early bytecode-VM construction (cross-checking bytecode results against direct interpretation) but is not the primary target.

**Native compilation only, via LLVM directly.** Skip the bytecode VM and compile graphs directly to native code via LLVM. This achieves the best performance but at substantial complexity cost: every change to the language semantics requires changing the compiler, debugging is hard, instrumenting for effect tracking is intrusive, and there is no portable execution form for the research phase. Rejected because the research phase requires a more observable execution platform.

**JIT compilation as the initial execution model.** Compile bytecode (or graph) to native code on demand at runtime. This is the V8 / HotSpot / LuaJIT model and offers a good balance of startup time and steady-state performance. It is rejected as the initial target because the implementation complexity of a competitive JIT is substantial, and the research-phase priority is correctness and observability rather than performance. JIT compilation may be added as a layer above the bytecode VM in a later phase.

**JVM or CLR as the runtime target.** Compile Strand to JVM bytecode or CIL. These runtimes are mature, well-instrumented, and have substantial library ecosystems. They are rejected because the effect-system mismatch is too severe: JVM and CLR have no built-in concept of mandatory effect declarations, capability mediation must be implemented entirely above the runtime, and the runtime's own operations (e.g., implicit allocation, implicit IO via library calls) escape Strand's effect tracking. Building Strand on top of these runtimes would require reimplementing the security guarantees at the language level, defeating the purpose.

**WebAssembly as the primary target.** WebAssembly is portable, sandboxed, has a typed instruction set, and has a Component Model that aligns with Strand's ForeignNode mechanism. It is attractive enough that it is preserved as a *secondary* target rather than rejected. It is not chosen as the primary execution model because the WebAssembly instruction set was designed for general-purpose compiled languages and does not naturally encode Strand's effect/capability operations. A Strand-on-WebAssembly target requires the same lowering effort as the MLIR path, with less control over the lowering. The WebAssembly target may be reached via MLIR or via direct compilation; that decision is deferred.

## Consequences {#consequences}

The reference implementation has a tractable path. A bytecode VM can be built by a small team in a reasonable time, can be instrumented for debugging and effect observation, and can serve as the execution platform for the research evaluation. The performance is sufficient for evaluating design claims about correctness, security, and distribution; it is not sufficient for production workloads, but production performance is not a research-phase requirement.

Effect and capability metadata are preserved by construction. The bytecode is designed to carry these as first-class data; the MLIR dialect is designed to preserve them through lowering. The runtime checks for effects and capabilities are part of the instruction semantics, not bolted on later. This rules out a class of bugs where security checks are bypassed by an optimization that the language designer did not foresee.

Two execution paths exist with the same observable semantics. The bytecode VM and the MLIR-compiled native target must produce the same results for the same inputs. This duality enables differential testing: the same graph executed on both paths should agree, and disagreement indicates a compilation bug. The cost is the engineering investment to maintain two execution paths in lockstep; the benefit is robustness in the production transition.

The MLIR dialect is a substantial design problem in its own right ([Q-018](../open-questions.md#Q-018)). Existing MLIR dialects (the standard dialect, the LLVM dialect, the SCF dialect for structured control flow, the affine dialect for polyhedral optimization) provide building blocks but do not cover effect-typed graph operations directly. The Strand dialect must define operations for node evaluation, capability checks, and effect propagation that lower into combinations of existing dialects plus runtime calls. The design of this dialect requires substantial MLIR expertise and may benefit from collaboration with the MLIR community.

Bytecode portability decouples Strand graphs from execution targets. A Strand graph compiled to bytecode runs on any platform with the bytecode VM. The graph itself is platform-independent; the bytecode is platform-independent; only the VM is platform-specific. This provides a useful pivot: when the runtime characteristics of a target platform change (a new TEE technology, a new accelerator), only the VM must be updated to expose new capabilities; the bytecode and graphs above do not change.

Garbage collection model is open. Strand's content-addressing implies that nodes are immutable, which suggests a tracing collector over the live root set. State machines complicate this because their state changes over time; the state itself is a sequence of content-addressed nodes, but the current-state pointer is mutable. The collector must handle both pure (immutable) and stateful (transitioning) heaps. The specific algorithm is part of [Q-017](../open-questions.md#Q-017) and the bytecode specification.

Replay determinism is supported by the bytecode design. The bytecode VM can record events, capabilities granted, and foreign-node invocations during execution; replaying the same record produces the same results, provided foreign nodes are deterministic or are replayed from the captured log. This is the foundation of the debugging story: a failed graph can be re-executed with full visibility into how the failure occurred. The MLIR-compiled path may not support replay as cheaply; investigating whether MLIR-compiled binaries can produce comparable traces is part of the production path development.

Iterative computation primitives must be designed to fit both compilation paths ([Q-019](../open-questions.md#Q-019)). A Fixpoint node type encodes loops as graph nodes with explicit termination conditions; this is verifiable and analyzable. Recursion through named indirection is a more flexible alternative that admits more programs at the cost of harder static analysis. Imperative loops gated by effects (declaring "this loop performs effect E up to N times") encode resource bounds explicitly. The choice affects what the bytecode and the MLIR dialect must encode and is connected to the node algebra ([Q-001](../open-questions.md#Q-001)).

The compilation target choice does not block other decisions. The bytecode and MLIR paths are downstream of every other ADR; changes to effect categories, encryption, state machine semantics, or foreign node trust models propagate through to the execution layer but do not constrain it. This isolation is intentional: the execution layer should be the most replaceable part of the system, because any specific target may turn out to be inadequate and may need replacement.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — execution semantics for all claims
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — graph as the source compilation reads
- [`ADR-003-content-addressing.md`](ADR-003-content-addressing.md) — content-addressed nodes for cache and GC
- [`ADR-004-effects-as-edges.md`](ADR-004-effects-as-edges.md) — effect metadata to preserve
- [`ADR-005-foreign-nodes.md`](ADR-005-foreign-nodes.md) — foreign-node invocation in the runtime
- [`ADR-006-per-node-encryption.md`](ADR-006-per-node-encryption.md) — encrypted-node handling in the VM
- [`ADR-007-state-machines.md`](ADR-007-state-machines.md) — state machine execution and event flow
- [`design/node-algebra.md`](../design/node-algebra.md) — node types the bytecode must support
- [`open-questions.md`](../open-questions.md) — Q-017, Q-018, Q-019

**Incoming references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — execution model referenced from integration section
- [`design/state-machines.md`](../design/state-machines.md) — runtime execution requirements
- [`research-plan.md`](../research-plan.md) — VM and MLIR milestones
