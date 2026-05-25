# Bytecode VM step 1: Kotlin reference VM

**Document:** `proposals/bytecode-vm-step-1.md`
**Status:** Layers 1, 3, 4, 5 fully implemented 2026-05-24 — 36 of 58 corpus programs pass `interpreter == VM` equivalence end-to-end. Layers 6 (state-machine runtime dispatch via VM) and 7 (SchemaChecker dispatch via VM) require runtime/schema module integration (architectural switch from interpreter-dispatch to vm-dispatch) and remain to extend the same scaffold; the value-level lowering and VM dispatch they need is in place. See implementation notes.
**Date:** 2026-05-24

> **Implementation note (2026-05-24, Layer 3 full enforcement).** Beyond the foundational slice, the VM now correctly enforces effect handlers and capability scopes. New constants `Constant.EffectsC(effectIds: IntArray)` and `Constant.SumCaseC` / `Constant.ProductFieldsC` ride alongside the existing pool. `MAKE_CLOSURE` / `MAKE_FIXPOINT` / `MAKE_FOREIGN` opcodes gained an extra operand for the callable's declared effects-list constant; the VM stores effects on `VmClosure` / `VmFixpoint` / `VmForeign`. New opcodes `CAP_PUSH` / `CAP_POP` / `HANDLER_PUSH` / `HANDLER_POP` maintain a per-VM capability stack and active-handler list. At every CALL site, the VM first scans the active handler list for an intercept matching any of the callee's declared effects (innermost wins — same rule as the interpreter), dispatching to the handler if so; otherwise it checks that every effect in the callee's list is in the current capability set, throwing `VmCapabilityViolation` if not. `Vm.run(initialCaps: Set<Int>)` accepts a starting capability set (mirrors `Interpreter.eval(root, capabilities)`). New helper opcodes `EQ` / `SUM_CASE_IS` / `SUM_PAYLOAD` / `THROW_NO_MATCH` plus JUMP backpatching support Match dispatch through scrutinee-into-local + per-case test/JUMP_IF_FALSE chains. The VmEquivalenceTest now covers 36 corpus programs: all Layer 1/4 (literals, lambda, application, let, varref, NodeRef, type abstractions, ForeignNode), Layer 3 (CapabilityScope, Handler — both narrowing and intercept), Layer 5 (Match with all 4 pattern variants including nested constructor patterns, Fixpoint, ProductValue, SumValue, ProductFieldGet) — including refinement-bearing programs (33-35) and handler corpus 36-40. Three programs use 22 remaining corpus programs (state-machine 41-49, schema 50-56, async 57) await Layer 6/7 runtime/schema integration. **Remaining for step 1 fully done:** (a) `Vm.applyCallable(chunkIdx, args, caps)` public API for runtime/schema callers; (b) `StateMachineRuntime`'s actor loop dispatches transition functions through the VM instead of through `Interpreter.applyCallable`; (c) `SchemaChecker` dispatches invariant bodies through the VM. Each is localized — the VM has the value-level mechanism; what's needed is the architectural switch in the runtime/schema modules. Tracked in `CONTINUATION.md`.

> **Implementation note (2026-05-24).** The foundational slice has landed: new `:bytecode` and `:vm` Gradle modules with the 28-opcode [`Opcode`](../impl/bytecode/src/main/kotlin/org/strand/bytecode/Opcode.kt) enum, [`Chunk`](../impl/bytecode/src/main/kotlin/org/strand/bytecode/Chunk.kt) + `Constant` + `ChunkTable` data shapes, [`Lowerer`](../impl/bytecode/src/main/kotlin/org/strand/bytecode/Lowerer.kt) covering Layer 1 (literals, Lambda, Application, Let, VarRef, NodeRef, TypeAbstraction erasure) plus Layer 4 (ForeignNode dispatch via the existing `Builtins` registry), [`MutableChunk`](../impl/bytecode/src/main/kotlin/org/strand/bytecode/MutableChunk.kt) builder with operand-pool interning, and [`Vm`](../impl/vm/src/main/kotlin/org/strand/vm/Vm.kt) dispatch loop with `Frame` and the `VmClosure` / `VmForeign` callable representations. The VM stack and locals are `Any?`-typed (not `Value?`) because `VmClosure` / `VmForeign` can flow through higher-order applications and let-bound callables — `Value` is sealed in the `:interpreter` module, so the callable types live as separate JVM classes. Equivalence test pass: 8 VmTest unit cases + 9 corpus programs in `VmEquivalenceTest` (01, 02, 03, 04, 06, 07, 10, 11, 15) all assert `interpreter.eval(p) == vm.run(lower(p))`. Layer 5 nodes (Match) correctly hit `LoweringNotImplemented` — that's the slice-1 boundary; the test asserts the failure as a positive signal. **Deviations from the proposal §4 sketch worth recording:** (a) the operand stack uses fixed-width 4-byte little-endian operands rather than LEB128 — variable-length encoding is reserved for the Rust step 2; (b) the `LOAD_HASH` opcode runs the resolved sub-chunk as a fresh frame via the same `frames.addLast` machinery (matching the proposal's "LOAD_HASH resolves to the precompiled chunk" intent — the chunk is lowered lazily into the chunk table on first reference); (c) program 05-s-combinator-typed is excluded from the corpus equivalence test because it returns a closure value — `Value.Closure` (interpreter) and `VmClosure` (VM) are structurally different representations of the same callable and don't satisfy Kotlin's `equals`, even though they behave identically under all Application call sites; behavioral equivalence is implicitly covered by every test that calls into a returned closure. **Remaining work for step 1:** extend `Lowerer.lowerExpr` and `Vm.run`'s `when` to cover Layer 3 (CapabilityScope, Handler — opcodes already defined), Layer 5 (Match, Pattern, Fixpoint, ProductValue, ProductFieldGet, SumValue), Layer 6 (StateMachine — the VM consumes transitionFn chunks; the runtime in `:runtime` calls into the VM instead of into the interpreter), and Layer 7 (the SchemaChecker invokes invariant bodies via VM). Each layer is a localized extension; the test pattern in `VmEquivalenceTest` mechanically extends — add the program to the pairs list, extend the lowering rules, run.
**Concerns:** [`decisions/ADR-008-compilation-target.md`](../decisions/ADR-008-compilation-target.md), [`design/node-algebra.md`](../design/node-algebra.md), [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md), [`design/state-machines.md`](../design/state-machines.md), [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md), [`research-plan.md`](../research-plan.md), [Q-017](../open-questions.md#Q-017)
**Scope:** Large (multi-step shipping; this proposal scopes step 1 only)

Step 1 of the bytecode-VM shipping strategy. Q-017's deferred half — the actual instruction set, value representation, calling convention, garbage-collection algorithm, and node-to-bytecode lowering scheme — is specified here. Step 1 ships a **Kotlin reference VM** that runs every corpus program with bytewise-equivalent behavior to the tree-walking interpreter. Step 2 (a separate proposal) ports the same design to Rust per ADR-008 and tunes for performance. Step 3 (optional) is the MLIR/LLVM path (Milestone 2.8).

The shipping strategy mirrors the project's established pattern: smallest viable thing first (e.g., Layer 6 step 1 synchronous fold before step 2 actor runtime; Layer 7 step 1 verify-time invariants before live views). Path A (Rust-first) and Path B (Kotlin-first) are evaluated in §2 and the recommendation is Path B.

## 1. Problem statement

The tree-walking interpreter is "explicitly the slow one" (per `impl/CLAUDE.md` and `CONTINUATION.md`). It evaluates Strand programs by recursive descent over the canonical NodeStore, allocating closure environments on every Lambda entry and dispatching effects through a `CapabilitySet` + active-handler list at every Application. For Layer 1-5 demonstration programs this is fine; for any non-trivial workload — a state machine processing 10⁵ events, a long-running supervisor, a refinement-checked Schema invariant body that walks a recursive list of 10⁴ elements — the per-event cost is dominated by the interpreter's dispatch overhead rather than the actual work.

ADR-008 settles the strategic question: Strand's production runtime is a bytecode VM, with MLIR/LLVM as an optional second target. The decision was made in Wave 2 specifically to make Layer 1's Kotlin-interpreter-first choice viable — the interpreter was always understood to be the staging ground for the eventual VM. Q-017 records the open question and pins down the architecture and constraints: stack-based bytecode, first-class effect/capability operations, content-addressed identity for nodes and references, support for state machines and event streams as runtime primitives, replay determinism as a property of the bytecode bytes (not just the interpreter).

What Q-017 does NOT yet specify is the actual instruction set, value representation, calling convention, garbage-collection algorithm, and lowering scheme. Until those are settled, "Strand has a bytecode VM in the roadmap" is a direction, not a plan. This proposal closes that gap.

## 2. Path A vs Path B — shipping strategy

ADR-008 and the research plan name Milestone 2.3 as "production-grade reference runtime in Rust." A literal reading commits to Path A: design the VM, implement directly in Rust, port the verifier-interpreter pipeline as a separate layer. A 4-6 month commitment to a from-scratch Rust runtime.

The alternative — Path B — is to build the bytecode compiler and a reference VM in **Kotlin first**, ship it alongside the existing tree-walking interpreter, prove every corpus program produces identical traces, and use that artifact as the executable specification for the eventual Rust port (Milestone 2.3 done as a step 2). This matches the pattern Layer 6 used (synchronous fold first, async actors second) and Layer 7 used (verify-time only first, live views second).

Both paths terminate at the same place — Rust VM in production. The question is whether the design validation happens in Rust at month 4-6 or in Kotlin at week 4-6.

### 2.1 Path A — Rust production VM in one pass

**Strengths.** Aligns with ADR-008's stated intent. Avoids throwaway code. Production performance from day one. Forces hard decisions (GC algorithm, memory layout, calling convention, FFI to the existing Kotlin verifier) early, before they accumulate as unspecified-default debt. Once shipped, downstream milestones (distribution, MLIR codegen, encryption) build directly on the production runtime.

**Weaknesses.** First time every corpus program can run through the bytecode VM is month 4-6. Long iteration cycle for design validation: if the instruction set is wrong, the calling convention is wrong, or the GC is the wrong shape, the discovery cost is months. Requires Rust expertise as the dominant development surface — the project to date has been Kotlin throughout, and the current contributor base reflects that. Cross-language coupling (Kotlin verifier produces canonical NodeStore; Rust VM consumes it) introduces FFI design problems that are independent of the bytecode design itself. Higher risk per dollar of work because validation is back-loaded.

**Hidden cost.** ADR-008 was decided when the design corpus was small. Since then, multiple design surprises have appeared: the nested-μ limitation in JSON, runtime-side vs verifier-side topology rules, the Schema↔Invariant hash cycle, the `WellKnownEffect` registration pattern. Each was discoverable only when the implementation revealed it. The bytecode VM is the largest single piece of infrastructure remaining; the probability of similar discoveries is non-trivial. Discovering them in Rust at month 4-6 is significantly more expensive than discovering them in Kotlin at week 4-6.

### 2.2 Path B — Kotlin reference VM first, Rust as step 2

**Strengths.** Iteration speed: first complete run of every corpus program through the bytecode VM is week 4-6, not month 4-6. Validates the lowering scheme + instruction set + calling convention + effect bytecode before committing to Rust. Every corpus program serves as a regression test of `interpreter(p) == vm(p)`; differential testing catches design flaws cheaply. Stays in Kotlin, where the rest of the project lives — no FFI design problem, no language switch tax. Matches the project's established shipping pattern.

**Weaknesses.** The Kotlin VM gets thrown away (in part — see "transferable work" below). Doesn't achieve performance goals; a Kotlin bytecode interpreter is not faster than a Kotlin tree-walking interpreter unless heavily tuned, and step 1 explicitly defers tuning. ADR-008 doesn't anticipate a step in Kotlin — Path B is a project-level shipping decision the ADR didn't predict.

**Mitigating "throwaway" claim.** The Kotlin reference VM is roughly 30% throwaway, 70% transferable to the Rust step 2:
- *Transferable:* instruction set design, value-tag layout, calling convention, lowering rules (one per Node category), the entire test corpus (`vm_program == interp_program` assertions port directly), the verifier integration points, the effect/capability bytecode semantics.
- *Throwaway:* the actual op-dispatch loop (Kotlin `when` vs Rust `match`), the GC algorithm (Kotlin uses JVM GC for free; Rust needs its own), memory representation details (Kotlin's reference-everywhere vs Rust's owned-vs-borrowed).

The Rust step 2 begins with the Kotlin VM's test suite passing as a specification: any Rust implementation that produces equivalent traces is correct. Most of the design risk is captured in the first pass; the Rust pass is engineering, not research.

### 2.3 Independent recommendation

**Path B.** Specifically:

1. **Step 1 (this proposal): Kotlin reference VM.** ~4-6 weeks of focused work. Ships a complete bytecode compiler + interpreter in `:bytecode` and `:vm` Gradle modules. Every existing corpus program runs through both the tree-walker and the VM; tests assert equivalence. No performance target other than "correct."
2. **Step 2 (separate future proposal): Rust port + performance tuning.** ADR-008's intent realized. The Kotlin VM's test corpus is the specification; the Rust VM passes the same tests with measurably better performance. ~3-6 months when started.
3. **Step 3 (optional, Milestone 2.8): MLIR/LLVM path.** Out of scope here.

The reasoning that drives Path B over Path A, in order of weight:

- **Iteration speed dominates.** The instruction set design will almost certainly be revised once the first complete program runs end-to-end. Doing the revision in Kotlin at week 6 costs days; doing it in Rust at month 6 costs months.
- **The design corpus has surprised the project before.** The nested-μ limitation and the Schema↔Invariant hash cycle were not predicted in Wave 2. Locking in a Rust runtime before the Kotlin VM has exercised every Node category is betting that no similar surprises remain. The base rate of such surprises is not zero.
- **The project lives in Kotlin.** Every contributor read of the codebase, every commit, every test is Kotlin-resident. Adding Rust before there's a working specification in the project's primary language inverts the dependency: the Rust VM would need to track changes to the Kotlin verifier through FFI, in the worst case re-doing FFI work each time an ADR clarifies a previously-loose corner of the design. With Path B, the Kotlin VM moves in lockstep with the verifier.
- **The "throwaway" cost is overstated.** 70% of the Kotlin VM work transfers as-is to the Rust port. The Rust step starts from a working executable specification rather than from a written proposal.
- **No performance pressure today.** The current corpus runs in milliseconds even on the tree-walker. Performance work is justified when there's a workload that demands it; today there isn't. Building production performance before there's a production workload is premature optimization at the milestone level.

Path A's strongest counter-argument is "you'll write the VM twice." That counter-argument holds only if (a) the instruction set design is already nailed (which it isn't — Q-017 explicitly defers it), (b) the design corpus is complete (which it isn't — the nested-μ work and Layer 6 step 3 slices 3.2/3.3 are still in flight), and (c) Rust development is as fast as Kotlin development for the same contributor (which it generally isn't, regardless of contributor). At least one of those conditions fails today, and the failure mode of Path A under any of them is months-not-weeks of lost work.

The remainder of this proposal specifies the step 1 Kotlin reference VM.

## 3. Prior art

- **JVM bytecode + HotSpot** ([Lindholm et al., *The Java Virtual Machine Specification*](https://docs.oracle.com/javase/specs/jvms/se21/html/)) — stack-based bytecode with explicit type information per instruction. The reference standard for "verified bytecode dispatched from a stack machine." Strand's instruction set borrows the stack-discipline and the explicit-type-per-op pattern.
- **BEAM (Erlang VM)** ([Armstrong, *Programming Erlang*](https://pragprog.com/titles/jaerlang2/programming-erlang/)) — register-based bytecode with first-class processes and selective receive. The reference for state-machine-runtime bytecode operations; Strand's Layer 6 ops are spiritually BEAM-shaped (`SEND`, `RECEIVE`, `SPAWN`, `LINK`). Differs from JVM in that processes (state-machine actors) are runtime primitives, not library abstractions.
- **CPython bytecode** ([Python language reference, `dis` module](https://docs.python.org/3/library/dis.html)) — stack-based, simple instruction set, easy-to-implement reference. The Path B target for "smallest VM that runs every corpus program correctly" — CPython's reference interpreter is the existence proof that a non-fast VM can ship in months and serve as the specification for faster implementations (PyPy, Cinder).
- **Lua 5 VM** ([Ierusalimschy et al., *The Implementation of Lua 5.0*](https://www.lua.org/doc/jucs05.pdf)) — register-based, ~40 opcodes total, full GC, hand-tuned for performance. Reference for "small instruction set + small VM image"; informs the Step 1 design's choice to keep the instruction set narrow (~25-30 opcodes target).
- **WebAssembly + Wasmtime** ([WebAssembly Core Specification](https://webassembly.github.io/spec/core/)) — stack-based, sandboxed, structured control flow. Strand will eventually have a sandboxed-foreign-binding target (Milestone 2.4) and Wasm is the leading candidate; the step 1 VM's instruction set is consciously *not* Wasm-compatible (Strand's effect/capability ops have no Wasm analog), but the Wasm sandboxing protocol is a forward reference.
- **MoarVM and RPython** ([6guts.wordpress.com, *Bytecode generation*](https://6guts.wordpress.com/2015/05/03/bytecode-generation/); [PyPy interpreter, `interpreter` module](https://doc.pypy.org/en/latest/interpreter.html)) — meta-interpreters that JIT-compile bytecode at runtime. Out of scope for Strand step 1 but referenced as future-direction for the Rust step 2 if AOT compilation proves insufficient.

The instruction set design proposed here is closest to CPython/Lua in shape (small stack-based op set) with Layer 6 ops borrowed from BEAM and effect/capability ops native to Strand.

## 4. Step 1 — Kotlin reference VM

### 4.1 Lowering scheme — canonical NodeStore → bytecode chunks

A Strand program in canonical form is a `NodeStore` keyed by hash. The compiler walks the store in topological order (dependencies-first) and emits one *bytecode chunk* per node category, with a top-level entry chunk corresponding to the program's root.

Each chunk is a `(name, code: ByteArray, constants: List<Constant>, locals: Int)` tuple. The `code` is the bytecode itself; `constants` is a per-chunk pool of literal values, hash references, and EffectCategory NodeIds; `locals` is the number of local-variable slots the frame needs.

The lowering rules per node category:

| Node | Lowers to |
|------|-----------|
| `IntLit` | `PUSH_INT <constant-index>` |
| `FloatLit` / `StringLit` / `BoolLit` / `BytesLit` | `PUSH_<TAG> <constant-index>` |
| `UnitLit` | `PUSH_UNIT` |
| `PrimitiveType` / `ProductType` / `SumType` / `FunctionType` / `TypeParameter` / `ForallType` / `RecursiveType` / `RecursiveSelf` | erased (types do not appear at runtime) |
| `ProductTypeField` / `SumTypeCase` | erased (consumed by parent type) |
| `Lambda` | emit body as a separate chunk; `MAKE_CLOSURE <chunk-index> <capture-count>` followed by `CAPTURE` ops for each captured local |
| `ParameterDecl` | erased; consumed by enclosing Lambda's frame layout |
| `Application` | push function, push args left-to-right, `CALL <arity>`. Type arguments and effect instances are erased — substitution happened at verify time. |
| `Let` | push value, `STORE <local-index>`, emit body |
| `VarRef` | `LOAD_LOCAL <index>` or `LOAD_CAPTURE <index>` depending on binder location |
| `NodeRef` | `LOAD_HASH <constant-index>` — resolves to the precompiled chunk for the target subgraph |
| `ForeignNode` | `MAKE_FOREIGN <target-constant-index>` — wraps the builtin target string in a callable closure value |
| `EffectCategory` / `EffectDecl` | erased (verifier-consumed) |
| `Match` | push scrutinee, `MATCH_DISPATCH <case-table-index>` — branches by structural shape |
| `MatchCase` / `Pattern` | encoded in the parent Match's case table |
| `Fixpoint` | emit body as a separate chunk; `MAKE_FIXPOINT <chunk-index> <capture-count>` |
| `ProductValue` | push every field value left-to-right, `PRODUCT_NEW <product-type-index> <field-count>` |
| `ProductFieldValue` | encoded inline at the parent ProductValue |
| `ProductFieldGet` | push target, `PRODUCT_GET <field-name-index>` |
| `SumValue` | push payload (or UNIT if nullary), `SUM_NEW <sum-type-index> <case-name-index>` |
| `CapabilityScope` | `CAP_PUSH <capability-list-index>`, emit body, `CAP_POP` |
| `Handler` | `HANDLER_PUSH <intercept-effect-index> <handle-closure-index>`, emit body, `HANDLER_POP` |
| `TypeAbstraction` | erased; the type abstraction is a thin wrapper around the underlying expression and the body's bytecode is what runs |
| `StateMachine` | not lowered to expression bytecode; emits a `MachineDescriptor` runtime object with bytecode chunks for `transitionFn` and `initialState` |
| `EventStream` / `Transition` | runtime metadata, not bytecode |
| `Schema` / `Invariant` | erased (verifier-consumed; schema checking happens before bytecode runs) |
| `Name` / `Provenance` | erased (metadata) |

The lowering pass is a single forward traversal — no second pass needed because Strand's algebra has no forward references at the bytecode level (NodeRef is resolved through the precompiled chunk table). Lambdas and Fixpoints generate sub-chunks recursively; the main chunk references them by index.

### 4.2 Instruction set

Twenty-eight opcodes in step 1. Each is a single byte; operands follow in variable-length encoding (LEB128 for indices > 127, single byte otherwise). The set:

```
Stack manipulation
  POP                                — pop one value
  DUP                                — duplicate top

Literals
  PUSH_INT <const>                   — push Int value from constant pool
  PUSH_FLOAT <const>
  PUSH_STRING <const>
  PUSH_BOOL <const>                  — operand is 0 or 1
  PUSH_UNIT
  PUSH_BYTES <const>

Variables
  LOAD_LOCAL <slot>                  — push local from current frame
  LOAD_CAPTURE <slot>                — push captured value from closure
  STORE_LOCAL <slot>                 — pop into local slot
  LOAD_HASH <const>                  — push the chunk for a NodeRef hash

Calls and returns
  CALL <arity>                       — pop fn, pop arity args, invoke
  CALL_FIXPOINT <arity>              — like CALL but rebinds self in callee frame
  CALL_FOREIGN <arity>               — pops a foreign closure and invokes the builtin
  RET                                — pop top, return to caller

Closures
  MAKE_CLOSURE <chunk> <captures>    — pop N captures, push a closure value
  MAKE_FIXPOINT <chunk> <captures>   — like MAKE_CLOSURE but for Fixpoint
  MAKE_FOREIGN <target>              — push a foreign closure for a builtin target

Composite values
  PRODUCT_NEW <type> <fields>        — pop N values, push a ProductV
  PRODUCT_GET <name>                 — pop ProductV, push the named field
  SUM_NEW <type> <case>              — pop payload (or skip if nullary), push SumV

Control flow
  MATCH_DISPATCH <table>             — pop scrutinee, branch to the matching case body
  JUMP <offset>
  JUMP_IF_FALSE <offset>

Effects and capabilities
  CAP_PUSH <caps>                    — narrow the capability context
  CAP_POP                            — restore the previous context
  HANDLER_PUSH <intercept> <handle>  — install an active handler
  HANDLER_POP                        — uninstall

Halt
  HALT                               — terminate program (only at the root chunk's end)
```

That covers every expressible Strand expression. State machines and event streams are not expression bytecode — they're handled by the runtime's `MachineGroup` infrastructure (already shipped in step 2), which calls into the appropriate transitionFn chunk per event.

### 4.3 Value representation

Step 1 uses a uniform boxed representation. Every runtime value is a Kotlin `sealed class VmValue` with variants matching the existing `Value` ADT one-to-one:

```kotlin
sealed class VmValue {
    data class IntV(val v: Long) : VmValue()
    data class FloatV(val v: Double) : VmValue()
    data class StringV(val v: String) : VmValue()
    data class BoolV(val v: Boolean) : VmValue()
    object UnitV : VmValue()
    data class BytesV(val v: ByteArray) : VmValue()
    data class ClosureV(val chunkIndex: Int, val captures: Array<VmValue>) : VmValue()
    data class FixpointV(val chunkIndex: Int, val captures: Array<VmValue>) : VmValue()
    data class ForeignV(val target: String) : VmValue()
    data class ProductV(val type: TypeIndex, val fields: Map<String, VmValue>) : VmValue()
    data class SumV(val type: TypeIndex, val case: String, val payload: VmValue?) : VmValue()
}
```

This is intentionally not optimized. Int is boxed; Bool is boxed; etc. Step 2's Rust port introduces NaN-boxing or pointer-tagging for the small-value types; step 1 keeps the representation transparent to make differential testing easy (`vm.IntV(5).v == interp.IntV(5).v`).

### 4.4 Calling convention

Stack-based, left-to-right argument order. `CALL N` pops N values + the callee from the operand stack, allocates a new frame with N local slots pre-populated, jumps to the callee's chunk. The new frame's captured values are passed via the closure's `captures` array.

For `MAKE_FIXPOINT`, the rebinding semantics are: when a `FixpointV` is the callee at a `CALL_FIXPOINT` instruction, the runtime prepends the FixpointV itself as the first local of the new frame (matching the existing interpreter's "the body Lambda's first parameter is the recursive call slot" convention).

Type arguments and effect instances are erased before bytecode reaches the VM. The verifier consumes them; the lowering pass discards them. The VM never sees a `typeArguments` field at runtime.

### 4.5 Garbage collection

Step 1 uses the **JVM's GC**. Every `VmValue` is a JVM heap allocation; references are JVM references; cycles (rare in Strand — only Fixpoint creates them, and even there the cycle is bounded) are collected by the JVM's mark-sweep. No custom GC.

This is a deliberate Path B choice. The interesting GC design questions for Strand (content-addressed dedup, hash-keyed retention, structural sharing across instances) are step 2 concerns; step 1 punts on them by leaning on the JVM.

Step 2's Rust port will introduce reference-counting on hash-keyed entries with cycle-breaking at Fixpoint boundaries, but that's a separate design pass.

### 4.6 Effects and capabilities at the bytecode level

The active-handler list and `CapabilitySet` carry through into bytecode as runtime structures referenced by the executing VM. The bytecode itself contains four effect-related opcodes:

- `CAP_PUSH <const-index>` narrows the surrounding capability context to the listed EffectCategories before entering a CapabilityScope's body
- `CAP_POP` restores the previous capability context
- `HANDLER_PUSH <intercept> <handle>` pushes an ActiveHandler onto the per-fiber handler stack
- `HANDLER_POP` removes the top handler

At `CALL`, the VM:
1. Inspects the callee's chunk metadata for the callee's declared effects.
2. For each declared effect, checks whether an active handler intercepts it. If yes, replaces the call with an invocation of the handler's `handle` closure (passing the intercepted call's args).
3. Otherwise, checks whether the current capability context grants the effect. If not, throws `CapabilityViolation` (or `RefinementViolation` if the call site has an `EffectDecl` whose pattern doesn't match any granted capability).
4. Proceeds with the normal call.

This is the same logic the tree-walking interpreter performs; the bytecode VM just runs it from a more compact representation.

### 4.7 State machines at the bytecode level

State machines are not expression bytecode. The lowering pass emits a `MachineDescriptor`:

```kotlin
data class MachineDescriptor(
    val transitionFn: ChunkIndex,    // bytecode for the transition Lambda
    val initialState: VmValue,       // precomputed at compile time
    val inputStreams: List<EventStreamSpec>,
    val outputStreams: List<EventStreamSpec>,
    val effects: Set<EffectCategoryName>,  // for the slice 3.5 check
)
```

The existing `MachineGroup` / `MachineActor` infrastructure (Layer 6 step 2) is the consumer. It already calls `Interpreter.applyCallable(transitionFn, args, capabilities)` per event; in the bytecode VM this becomes `vm.invokeClosure(transitionFn, args, capabilities)`. The actor loop, channel wiring, topology validation, and recorder are unchanged.

This is a clean seam: the VM doesn't need to know about state machines, channels, or actors. It just needs to dispatch the transition function's bytecode given a (state, event) input. Everything else stays in the existing `:runtime` module.

### 4.8 Schemas, recursive types, and other erased categories

Schemas (N-032) and Invariants (N-033) are verifier-consumed and never reach bytecode. The `SchemaChecker` runs after the verifier and before the VM (matching today's `:cli` pipeline). When the SchemaChecker invokes invariant bodies via `Interpreter.applyCallable`, in the bytecode-VM-future it invokes them via `vm.invokeClosure` — same seam as the state machine runtime.

Recursive types (N-041 RecursiveType, N-042 RecursiveSelf) are pure type-level constructs. They don't appear at the value level; SumValue with case `Cons` over a recursive list type is still just a `SumV("Cons", ProductV(...))` at runtime. The bytecode never references recursive-type structure.

TypeAbstraction (N-034) and ForallType (N-035) are erased after verifier consumption. A TypeAbstraction's body's bytecode is what runs; the type abstraction itself contributes nothing at runtime.

This is a substantial simplification over languages like Java where type information is preserved at runtime for reflection / dispatch. Strand's bytecode VM has no reflection, no class lookup, no type-keyed dispatch — types do their work entirely at compile time.

## 5. Step 2 — Rust port + perf tuning (sketch)

Not specified in detail here; this proposal scopes step 1. A future `proposals/bytecode-vm-step-2.md` covers:

- Rust crate `strand-vm` with the same instruction set as step 1
- NaN-boxing / pointer-tagging for primitive values
- Reference-counted heap with hash-keyed dedup
- FFI boundary: serializing `NodeStore` + chunk index to a wire format the Rust VM consumes
- Performance benchmarking against the Kotlin VM and the tree-walker
- Distribution: stable wire format for shipping bytecode between nodes (per `design/distribution-model.md`)
- Snapshot/replay built on the Rust VM's heap layout (interacts with Layer 6 step 3 slice 3.3)

The Rust port's correctness criterion is: every step 1 test passes byte-identically. The performance criterion is: substantial speedup over the Kotlin VM on the corpus's larger programs (fixpoint-heavy: factorial of 20+, recursive-list-sum over 10⁴ elements, state machine processing 10⁵ events).

## 6. Test scenarios

1. **Round-trip equivalence per corpus program.** For every program in `corpus/`, assert `vm.run(p) == interpreter.eval(p)`. The pre-existing CorpusTest, CorpusMachineTest, AsyncCorpusTest, CorpusSchemaTest all extend with VM-equivalent runs.
2. **Closure capture by value, not reference.** A Lambda that captures a let-bound local must see the value at capture time, not the cell. Differential test against the interpreter's behavior.
3. **Fixpoint recursion through self-binding.** Factorial of 20 produces the right Int. The fixpoint's self slot resolves through `CALL_FIXPOINT`'s rebinding.
4. **Handler dispatch innermost-wins.** Nested handlers for the same effect category: the innermost wins, as in the interpreter.
5. **Capability context narrowing under CAP_PUSH / CAP_POP.** A CapabilityScope's body sees only the narrowed set; restoring after the scope yields the original set.
6. **Refinement-violation surfaces at CALL.** An Application with `effectInstances` whose pattern doesn't match the granted capability raises `RefinementViolation` at the CALL instruction, same as the interpreter.
7. **State machine transition runs in the VM.** A corpus state machine's transition function runs as VM bytecode; the per-step trace matches the interpreter's.
8. **Schema invariant evaluates via the VM.** The SchemaChecker pass invokes invariant bodies as bytecode; results match the interpreter.
9. **NodeRef resolution through the chunk table.** A program with shared subgraphs via NodeRef produces identical traces between VM and interpreter.
10. **Foreign call passes args correctly.** `Int.Add(2, 3) == 5` via `CALL_FOREIGN`; matches the interpreter's builtin dispatch.
11. **Match exhaustiveness at MATCH_DISPATCH.** A constructor-pattern match with all cases covered always succeeds; missing cases produce `NoMatchingCase` at runtime, same as the interpreter.
12. **Round-trip determinism.** Compiling and running the same program twice produces byte-identical traces. This is the bytecode-level replay determinism property.

## 7. Tradeoffs and open questions

**Deferred intentionally:**

- **Step 2 (Rust port).** Out of scope. A separate proposal once step 1 ships and the instruction set is validated empirically.
- **JIT compilation.** Tree-interpreted bytecode in step 1; no JIT. PyPy-style meta-tracing is a step 3+ direction if AOT compilation in step 2 proves insufficient.
- **Bytecode-level distribution wire format.** The Kotlin VM doesn't serialize bytecode for cross-process transport; step 1's chunk table lives in-memory only. Step 2 introduces a wire format for distribution (per `design/distribution-model.md`).
- **Encrypted-node decryption at runtime.** ADR-006 says encrypted nodes carry interfaces visible to the VM. Step 1 punts because no corpus program uses encrypted nodes; the bytecode design accommodates `LOAD_HASH` extending to handle encryption envelopes once the encryption module ships.
- **Snapshot/replay-from-log integration with the VM heap.** Layer 6 step 3 slice 3.3 will need a story for snapshotting VM-runtime state; that's a coordination point with step 1 but doesn't change the bytecode design itself.
- **Hot upgrade of running bytecode.** Q-010. Out of scope.
- **Inline caching of foreign calls.** A common optimization (JS engines, JVM); deferred to step 2 perf tuning.

**Real research questions:**

- **OQ-VM1-a: Constant pool de-duplication granularity.** Chunks have local constant pools. Should there be a global pool for cross-chunk literals (e.g., the same EffectCategory NodeId appears in 50 chunks; storing it once globally saves space and matches the hash-keyed-dedup intuition)? Probably yes; step 1 ships local pools, step 2 adds a global pool if profiling shows the benefit.
- **OQ-VM1-b: Match dispatch table representation.** A 2-case match (Some/None) is a single conditional. A 10-case constructor match wants a hash table or jump table. Step 1 uses a linear case scan because the corpus has no >10-case matches; step 2 can switch to a perfect-hash dispatch when the workload demands.
- **OQ-VM1-c: Capability-check cost amortization.** Today every CALL checks the active-handler stack and the capability context. For hot loops (factorial of 20+), this is 20+ checks. A future opt could hoist invariant checks out of loops, or cache the resolved dispatch target per call site. Step 2 perf concern; step 1 ships the naive check.
- **OQ-VM1-d: Foreign call ABI.** Step 1's `CALL_FOREIGN` dispatches through `Builtins.lookup(target)` — a `Map<String, Fn>`. This works for in-process Kotlin builtins. Step 2 needs an extensible foreign-target protocol for `wasm:`, `process:`, etc. The design hook in step 1 is to make `CALL_FOREIGN` consult an extensible dispatcher table rather than a hard-coded map.
- **OQ-VM1-e: How much should the VM trust the verifier?** Today the interpreter's runtime checks duplicate some verifier checks (it re-checks capability presence at every CALL, for example). The bytecode VM can either trust the verifier (and remove the runtime check) or distrust it (and keep the check). Step 1 keeps the runtime check for safety; step 2 measures and decides whether to drop it.

## 8. Implementation sketch

| File | Change | Step | Size |
|------|--------|------|------|
| `bytecode/` | NEW Gradle module under `bytecode → verifier → core`. Houses the compiler. | 1 | Module-level |
| `bytecode/src/main/kotlin/org/strand/bytecode/Opcode.kt` | NEW — enum of the 28 opcodes + operand encoding helpers | 1 | Small-Medium |
| `bytecode/src/main/kotlin/org/strand/bytecode/Chunk.kt` | NEW — `data class Chunk(val name: String, val code: ByteArray, val constants: ConstantPool, val locals: Int)` and `ChunkTable` for the top-level program | 1 | Small |
| `bytecode/src/main/kotlin/org/strand/bytecode/Lowerer.kt` | NEW — single-pass `NodeStore → ChunkTable` compiler with per-Node lowering rules per §4.1 | 1 | Large |
| `bytecode/src/main/kotlin/org/strand/bytecode/MachineDescriptor.kt` | NEW — runtime descriptor for StateMachine nodes per §4.7 | 1 | Small |
| `vm/` | NEW Gradle module under `vm → bytecode → interpreter → verifier → core`. Houses the VM. | 1 | Module-level |
| `vm/src/main/kotlin/org/strand/vm/VmValue.kt` | NEW — uniform boxed value representation per §4.3 | 1 | Small |
| `vm/src/main/kotlin/org/strand/vm/Frame.kt` | NEW — call frame with operand stack, locals, captures, return-PC | 1 | Small |
| `vm/src/main/kotlin/org/strand/vm/Vm.kt` | NEW — the main dispatch loop; one `when` over `Opcode` | 1 | Large |
| `vm/src/main/kotlin/org/strand/vm/EffectDispatch.kt` | NEW — handler-stack walking + capability check at CALL per §4.6 | 1 | Medium |
| `vm/src/main/kotlin/org/strand/vm/ForeignBridge.kt` | NEW — extensible dispatcher table; step 1 wraps existing `Builtins` | 1 | Small |
| `vm/src/main/kotlin/org/strand/vm/VmException.kt` | NEW — typed VM errors (parallel to `InterpretError`) | 1 | Small |
| `runtime/src/main/kotlin/org/strand/runtime/MachineActor.kt` | EXTEND — accept either an `Interpreter` or a `Vm` as the per-step dispatcher; configurable per MachineGroup | 1 | Small |
| `cli/src/main/kotlin/org/strand/cli/Main.kt` | EXTEND — `strand run --vm <file>` flag to use the bytecode VM instead of the tree-walker | 1 | Small |
| `corpus/src/test/kotlin/org/strand/corpus/VmCorpusTest.kt` | NEW — for every corpus program, assert `vm.run == interpreter.eval` | 1 | Medium |
| `bytecode/src/test/kotlin/org/strand/bytecode/LowererTest.kt` | NEW — per-Node-category lowering tests | 1 | Medium |
| `vm/src/test/kotlin/org/strand/vm/VmTest.kt` | NEW — unit tests for the dispatch loop, frame management, handler dispatch | 1 | Medium |
| `impl/CLAUDE.md` | EXTEND — document the bytecode + vm modules, the strand run --vm flag | 1 | Small |
| `impl/settings.gradle.kts` | EXTEND — `include("bytecode")`, `include("vm")` | 1 | Trivial |
| `open-questions.md` | EXTEND — Q-017 status moves Proposed → Partial (step 1 spec landed) | 1 | Small |
| `INDEX.md` | EXTEND — Last revised | 1 | Trivial |

**Order of work for step 1.**

1. **Opcode + Chunk + ChunkTable types** — the data model. No semantics yet.
2. **Lowerer for Layer 1 nodes** (literals, types, lambda, application, let, varref, type abstractions). Smallest viable subset.
3. **Vm dispatch loop for Layer 1 opcodes.** Run corpus 01-11 end-to-end; assert equivalence with the interpreter.
4. **Extend Lowerer + Vm to Layer 3 (effects + capability scope + handlers)** — corpus 12-14, 33-40.
5. **Extend to Layer 4 (foreign nodes)** — corpus 15-17, 30, 33-35.
6. **Extend to Layer 5 (match patterns + fixpoint + product/sum values + recursive types)** — corpus 18-32.
7. **Extend to Layer 6 (state machines via MachineDescriptor)** — corpus 41-49.
8. **Extend to Layer 7 (schemas)** — corpus 50-56.
9. **CLI `strand run --vm` + documentation.**
10. **Final corpus equivalence pass:** every program (~60) runs through both pipelines with byte-identical traces.

Each step takes 2-5 days of focused work; total ~4-6 weeks for step 1.

**Not in this slice.** Rust port (step 2); JIT compilation; constant pool deduplication; perfect-hash match dispatch; capability-check hoisting; encrypted node decryption; distribution wire format; snapshot/replay heap integration.

## References

**Outgoing references:**
- [`decisions/ADR-008-compilation-target.md`](../decisions/ADR-008-compilation-target.md) — strategic commitment to bytecode VM
- [`design/node-algebra.md`](../design/node-algebra.md) — Node categories the Lowerer dispatches on
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — effect/capability semantics the VM implements
- [`design/state-machines.md`](../design/state-machines.md) — state-machine runtime the VM defers to
- [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — hash-keyed identity preserved through bytecode
- [`research-plan.md`](../research-plan.md) — Milestone 2.3 is where step 2 (Rust) lives
- [`open-questions.md`](../open-questions.md) — Q-017 (this proposal closes the deferred half)

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-017 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — Known gaps section
