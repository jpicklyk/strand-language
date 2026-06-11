# Prior Art {#prior-art}

**Document:** `01-prior-art.md`
**Status:** Stable
**Last revised:** 2026-06-11

## Summary

Strand exists in a research landscape that includes purpose-built AI agent languages, AI-oriented modifications to existing languages, decode-time structure enforcement for text languages, graph-structured programming environments, effect-typed languages, capability-based systems, and distributed dataflow systems. This document surveys the relevant prior work and identifies where Strand's design diverges from each tradition. The survey is not exhaustive; it focuses on systems whose design decisions directly inform or contrast with Strand's. External systems are cited inline by primary source; the References section tracks corpus-internal citations.

## Languages designed for LLM agent use {#llm-agent-languages}

### Pel {#prior-pel}

Pel is a Lisp-inspired language designed for orchestrating LLM agents. Its design emphasizes a minimal, easily-modifiable grammar suitable for constrained LLM generation, eliminating the need for runtime sandboxing by enabling capability control at the syntax level. It provides a piping mechanism for linear composition, first-class closures, and built-in support for natural-language conditions evaluated by LLMs.

**Relation to Strand:** Pel and Strand share the goal of building a language whose constraints align with LLM generation patterns. They diverge on representation: Pel uses textual S-expressions, preserving readability; Strand abandons textual representation entirely. Pel targets multi-agent orchestration as a domain; Strand targets general-purpose computation. Pel's capability-at-syntax model influenced Strand's decision to make capabilities a first-class language primitive rather than a runtime concern.

### QUASAR {#prior-quasar}

QUASAR is a language for LLM agent actions designed for performance (automated parallelization), reliability (uncertainty quantification via conformal prediction), and security (user validation of actions). Rather than asking LLMs to learn QUASAR directly, the system has LLMs generate a Python subset, which is then transpiled to QUASAR for execution. On the ViperGPT benchmark, this approach reduced execution time by 42% and user-approval interactions by 52% while maintaining task performance.

**Relation to Strand:** QUASAR demonstrates that an execution-time language can be decoupled from the generation-time language an LLM is fluent in. This is a viable alternative bootstrap strategy that Strand may adopt during early implementation: generate in a familiar surface representation, translate to graph form for execution. QUASAR retains Python's general structure and operates in process; Strand restructures the program model entirely and is designed for distributed execution.

### CoRE / AIOS Compiler {#prior-core}

CoRE treats natural language as the programming language and uses an LLM as the interpreter. The system unifies natural-language programs, pseudocode, and flow programs under a single representation, with the LLM responsible for resolving ambiguity and dispatching to external tools.

**Relation to Strand:** CoRE represents an opposite point in the design space. Where Strand makes the program representation maximally structured and verifiable, CoRE makes it maximally unstructured and interprets it at runtime. The two approaches have different threat models, performance characteristics, and use cases. Strand's design rejects the CoRE approach for production code on grounds of verifiability, but CoRE-style natural-language specification may be a useful input to Strand-style graph generation in a layered system.

### Markov (proposed) {#prior-markov}

Markov is a proposed (not implemented) language design that leans into Rust's pattern of human-designed core types with agent-propagated changes via pattern-match exhaustiveness checking. The proposal emphasizes building tools that work alongside LLM characteristics rather than requiring LLMs to adapt to human-centric tools.

**Relation to Strand:** Markov and Strand share the framing that LLM characteristics should inform language design, but Markov preserves human-readable text representation. Strand treats this preservation as the constraint that prevented Markov-like proposals from reaching their potential. The two designs may be complementary at different points in the AI-coding maturity curve.

### MoonBit {#prior-moonbit}

MoonBit is a general-purpose language, initiated in late 2022, that describes itself as AI-friendly by construction ([Fei et al., LLM4Code/ICSE 2024](https://dl.acm.org/doi/10.1145/3643795.3648376)). Its design choices target the mechanics of transformer inference rather than the program model: a flat top-level structure with mandatory type signatures and minimal nesting improves KV-cache reuse during generation, and structural interfaces allow interfaces and their implementations to be generated nearly linearly, reducing cache misses. The toolchain integrates with the sampler directly — a local sampling pass adjusts generated tokens in real time to keep output syntactically valid, and a global sampling pass checks semantic well-formedness.

**Relation to Strand:** MoonBit establishes that inference mechanics — cache locality, sampler integration, token-order linearity — are a design axis of their own, distinct from the program-model axis Strand occupies. MoonBit optimizes the decoding loop for a human-readable text language and retains human authorship as a goal; Strand optimizes the artifact and abandons human authorship. The two are orthogonal enough to compose: a Strand authoring projection could in principle adopt MoonBit-style flatness for cache efficiency. MoonBit's sampler-integrated toolchain is also an early instance of the decode-time enforcement surveyed in [decode-time structure enforcement](#prior-constrained-decoding).

### CodeAct {#prior-codeact}

CodeAct ([Wang et al., ICML 2024](https://arxiv.org/abs/2402.01030)) consolidates LLM agent actions into executable Python code rather than JSON or constrained-text tool-call formats. Across seventeen models on API-Bank and a purpose-built benchmark, code actions achieve up to 20% higher success rates than the structured alternatives, attributed to models' training-corpus fluency in Python and the ability of code to compose tools, branch, and reuse intermediate results within a single action.

**Relation to Strand:** CodeAct is direct counter-evidence to the assumption that structured action formats are preferable for agent emission: when the model must produce a format it has rarely seen, success rates drop relative to Python, and Strand's canonical form is maximally far from the training distribution. The evaluation in [`research-plan.md`](research-plan.md) must treat this as the effect to overcome, not an artifact to design around. Strand's position is that the deficit is a property of unconstrained sampling rather than of structured targets as such — constrained decoding against the authoring grammar removes the format-fluency penalty at emission time — and that verification, containment, and distribution properties of the artifact justify the remaining cost. That position is a hypothesis the empirical phases test; CodeAct defines the baseline it is tested against.

### Zero (Vercel Labs) {#prior-zero}

Zero is an experimental systems language released by Vercel Labs in May 2026 ([github.com/vercel-labs/zerolang](https://github.com/vercel-labs/zerolang)) whose toolchain treats AI agents as the primary consumer. The compiler emits JSON diagnostics with stable error codes and typed repair metadata by default, and companion commands expose machine-readable explanations and fix plans, so an agent's repair loop never parses prose. Functions declare capability-based I/O effects in their signatures, enforced at compile time. Subsequent releases moved Zero to an explicitly graph-native model: the semantic graph is the program database and the compiler input, agents read and modify programs through query and patch commands whose patches are validated before storage, and agents address program elements through explicit handles — symbols, node identifiers, graph hashes, types, effects, and capabilities. Human-readable text files are retained as projections for review rather than as the source of truth.

**Relation to Strand:** Zero is the closest contemporary system to Strand's combination of agent-first toolchain, graph-as-source, and effects declared in the program's interface, and its independent emergence is evidence that this region of the design space is being converged on rather than idiosyncratic. The divergences are the projection layer and the role the graph plays. Zero maintains a human-readable text projection and a human review path, which Strand omits per [`decisions/ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md). Zero is a single-machine systems language compiling to small native binaries; its graph is the working database of a compiler, and its effect declarations are discharged at compile time. Strand's graph is the canonical artifact itself — content-addressed identity, admission-time verification of the effect closure, and runtime capability checks and placement decisions derived from the same edges. Zero is experimental and pre-1.0; its trajectory bears directly on Strand's evaluation and should be tracked.

## AI-oriented modifications to existing languages {#prior-modifications}

### SimPy {#prior-simpy}

SimPy is a syntactic modification of Python that preserves Python's abstract syntax tree while reducing token consumption for LLM generation. NEWLINE/INDENT/DEDENT sequences are replaced with anchor tokens, redundant keywords are stripped, and the result is round-trip-convertible with standard Python. SimPy achieves 13.5% token reduction for CodeLlama and 10.4% for GPT-4 on equivalent tasks.

### ShortCoder {#prior-shortcoder}

ShortCoder applies ten AST-preserving simplification rules to Python, achieving 18.1% token reduction. Unlike SimPy, ShortCoder emphasizes balancing token efficiency against human readability, treating pure AI-oriented grammar as too costly to human-AI collaboration.

### Token Sugar {#prior-token-sugar}

Token Sugar identifies high-frequency code patterns in a corpus and replaces them with reversible shorthand. The approach is complementary to syntactic simplifications like SimPy: 799 pattern-to-shorthand pairs achieve up to 15.1% additional token reduction.

**Relation of all three to Strand:** These projects demonstrate that even purely syntactic modifications to existing languages produce measurable improvements in LLM generation efficiency. They establish a lower bound on the benefit of AI-oriented language design. Strand's hypothesis is that abandoning text representation entirely will produce substantially larger improvements, but this hypothesis must be tested against these baselines, not against unmodified text languages. The research plan accounts for this in its evaluation strategy.

## Decode-time structure enforcement {#prior-constrained-decoding}

Constrained decoding restricts a model's next-token distribution so that emitted text always conforms to a target structure, moving well-formedness guarantees from post-hoc checking into the sampling loop. XGrammar ([Dong et al., arXiv 2411.15100](https://arxiv.org/abs/2411.15100)) makes context-free-grammar-constrained generation practical at serving scale by partitioning the vocabulary into tokens checkable ahead of time and tokens requiring runtime interpretation, reaching near-zero overhead in integrated inference engines. Type-constrained code generation ([Mündler et al., PLDI 2025](https://dl.acm.org/doi/10.1145/3729274)) extends enforcement past syntax: prefix automata combined with a search over inhabitable types soundly guarantee well-typedness of the emitted program during decoding, formalized on a simply-typed calculus and demonstrated on TypeScript.

**Relation to Strand:** This line of work reproduces, for ordinary text languages, the guarantee stated as a consequence of Claim 1 in [`02-core-thesis.md`](02-core-thesis.md): that syntactic errors do not exist as a category. With grammar-constrained decoding a model emitting Python or TypeScript cannot produce a parse error, and with type-constrained decoding it cannot produce a type error, all without abandoning text representation. This bounds the value of syntax-error elimination as a differentiator — the advantage is available to text languages at the cost of inference-stack integration, and Strand itself relies on the same technique for its authoring layer. The boundary of decode-time enforcement is the property class it can decide from the prefix alone. Strand's admission-time verification holds without trusting the generator or its serving stack, applies to artifacts regardless of provenance, and checks properties — the effect closure of a subgraph against the capabilities of an execution context — that depend on the environment the program will run in, which no decode-time mechanism can decide. As enforcement advances from grammars to types, the residual differentiation concentrates in those environment-dependent, artifact-level checks.

## Graph-structured programming environments {#prior-graph-environments}

### Unison {#prior-unison}

Unison is a functional programming language that identifies code by content hash rather than by name. Functions are immutable, refactoring becomes mechanical (renaming changes one node's label without breaking references), and the codebase is a content-addressed graph rather than a directory of files. Unison provides a text-based surface syntax for human authorship but stores and executes programs as graphs.

**Relation to Strand:** Unison is the closest existing system to Strand and the primary inspiration for Strand's content-addressing decision. The major divergences are (1) Unison preserves a human-readable surface syntax, requiring substantial engineering for the projection layer; Strand does not. (2) Unison's effect system (abilities) is opt-in; Strand's is mandatory and integrated with placement. (3) Unison targets distributed computation explicitly but through programmer-directed primitives; Strand makes distribution a consequence of the effect system rather than a programmer concern.

### Hazel {#prior-hazel}

Hazel is a structured editor and language environment where programs are always well-formed expressions, even when incomplete. Edits operate on the AST directly rather than on text, eliminating syntactic errors as a category. Type-checking and evaluation work on incomplete programs.

**Relation to Strand:** Hazel demonstrates that direct AST manipulation can replace text editing without loss of expressiveness. Strand extends this principle from "humans manipulating ASTs" to "agents manipulating graphs," and uses content-addressing where Hazel uses traditional name binding. Hazel's "always well-formed" property is preserved in Strand: every graph operation either produces a well-formed result or is rejected.

### MPS (JetBrains Meta Programming System) {#prior-mps}

MPS is a language workbench based on projectional editing: the underlying program representation is an AST, and editors render it through customizable projections that may include text, tables, diagrams, or any other visual form. MPS is used for domain-specific languages where the projection significantly improves authorship.

**Relation to Strand:** MPS demonstrates that programs can have multiple projections from a single underlying representation. Strand's design choice to omit projections entirely is a simplification — the engineering required for high-quality projections is substantial, and the use case (humans authoring programs) does not apply. The infrastructure MPS provides for projectional editing may inform future Strand tooling for failure forensics or audit.

## Effect-typed languages {#prior-effect-languages}

### Koka and Eff {#prior-koka-eff}

Koka and Eff are research languages with first-class effect handlers. Functions declare their effects in their type signatures, and effects can be handled (intercepted and given semantics) by callers. This enables structured concurrency, exception handling, and mutable state to be expressed within a pure type system.

### OCaml 5 effect handlers {#prior-ocaml-effects}

OCaml 5 introduced effect handlers as a production language feature. The effect system is unchecked (effects are not part of function types) but the runtime supports delimited continuations sufficient to implement structured concurrency, generators, and similar patterns.

### Haskell IO and Monadic Effects {#prior-haskell}

Haskell's IO type marks computations that perform side effects, and monad transformers and effect libraries (mtl, polysemy, fused-effects, effectful) provide more granular effect tracking. The effect tracking is part of the type system and statically enforced.

**Relation of all to Strand:** Effect-typed languages establish the technical feasibility of static effect tracking and demonstrate its benefits for reasoning about program behavior. Strand's effect system draws from this tradition but makes two changes: effects are mandatory rather than optional (no equivalent of "untracked IO"), and effects drive runtime decisions (placement, capability checks, partitioning) rather than serving purely as a static analysis tool.

## Capability-based systems {#prior-capabilities}

### E and Pony {#prior-e-pony}

E and Pony are programming languages built around object capabilities. References are unforgeable, and a program can only act on a resource if it holds a reference to it. This eliminates ambient authority — there are no globally-accessible operations that don't pass through a reference chain.

### seL4 and capability operating systems {#prior-sel4}

seL4 is a formally verified microkernel built on capability-based access control. Every operation requires a capability, capabilities are explicitly delegated, and the kernel enforces that no operation occurs without an appropriate capability.

**Relation to Strand:** The capability tradition establishes the security properties Strand aims for. Strand's contribution is integrating capabilities with effect declarations: a capability is the runtime token corresponding to a static effect declaration. This connection allows static effect analysis to determine which capabilities a graph requires, and the runtime can refuse to evaluate a graph whose effect set exceeds the capabilities held by its execution context.

## Distributed dataflow systems {#prior-dataflow}

### Spark and Ray {#prior-spark-ray}

Apache Spark and Ray are distributed computing frameworks that express computation as DAGs of operations over data. Spark targets batch analytics; Ray targets distributed Python programs including ML training and inference. Both reconstruct dependency graphs from programmer-annotated code embedded in a host language.

### Differential Dataflow {#prior-differential}

Differential Dataflow is a framework for incremental computation over changing datasets. Programs are expressed as dataflow operators, and the runtime efficiently propagates incremental updates through the dataflow graph.

### TensorFlow (graph mode) and PyTorch (compiled) {#prior-tf-pytorch}

TensorFlow's original graph mode and PyTorch's TorchScript / `torch.compile` modes represent computations as graphs for optimization and distribution. The graphs are embedded within or extracted from a host language.

**Relation of all to Strand:** Dataflow systems demonstrate that graph representation is the right shape for distribution. They construct graphs as a secondary representation built from host-language code; Strand makes graph representation primary. This eliminates the impedance mismatch between programmer code and the dataflow graph, but requires the language to handle workloads dataflow systems do not (state machines, long-running services, interactive systems). The state machine design in [`design/state-machines.md`](design/state-machines.md) addresses this.

## Where Strand differs {#strand-differences}

Strand's design occupies a specific point in this landscape that no existing system occupies. The combination of:

1. Graph-native source representation (no text projection)
2. Content-addressed node identity
3. Mandatory effect declarations
4. Effect-driven placement and distribution
5. Capability-based execution tied to static effects
6. First-class state machines as fixpoints over event streams
7. Designed for AI generation rather than human authorship

is, to the authors' knowledge, novel as a combination. Individual elements have precedents in the systems described above. The research contribution is the integration: testing whether these elements compose into a language that performs better than text languages for AI generation on the metrics described in [`00-motivation.md`](00-motivation.md).

## References

**Outgoing references:**
- [`00-motivation.md`](00-motivation.md) — the motivation this prior art informs
- [`design/state-machines.md`](design/state-machines.md) — how Strand handles workloads beyond dataflow
- [`research-plan.md`](research-plan.md) — empirical evaluation against these baselines

**Incoming references:**
- [`README.md`](README.md)
- [`00-motivation.md`](00-motivation.md)
- [`02-core-thesis.md`](02-core-thesis.md)
- [`decisions/ADR-001-graph-not-text.md`](decisions/ADR-001-graph-not-text.md)
- [`decisions/ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md)
- [`decisions/ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md)
- [`decisions/ADR-004-effects-as-edges.md`](decisions/ADR-004-effects-as-edges.md)
- [`decisions/ADR-005-foreign-nodes.md`](decisions/ADR-005-foreign-nodes.md)
- [`decisions/ADR-007-state-machines.md`](decisions/ADR-007-state-machines.md)
- [`design/state-machines.md`](design/state-machines.md)
- [`research-plan.md`](research-plan.md)
