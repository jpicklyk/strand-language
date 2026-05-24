# Research Plan {#research-plan}

**Document:** `research-plan.md`
**Status:** Wave 3 draft
**Last revised:** 2026-05-23

## Summary

This document specifies the empirical research program that will establish or refute the hypothesis stated in [`00-motivation.md`](00-motivation.md). The plan covers the bootstrap problem (how to obtain a training corpus when no Strand programs exist), the implementation phases (what software must be built, in what order, with what go/no-go criteria), and the evaluation methodology (what metrics will be measured, against what baselines, on what tasks).

The plan is structured as four phases with explicit gating criteria. The phases are sequential at the level of strategic risk: the bootstrap phase resolves the corpus risk, the implementation phase resolves the runtime risk, the generation phase resolves the language-model risk, and the evaluation phase resolves the hypothesis-validation risk. A failure at any phase requires either a redesign of subsequent phases or a re-assessment of the project's scope.

Resolves [Q-020](open-questions.md#Q-020) (corpus bootstrap) and [Q-021](open-questions.md#Q-021) (metrics and baselines) as proposed designs. Acknowledges several research risks explicitly.

## Hypothesis recap {#hypothesis-recap}

[`00-motivation.md`](00-motivation.md) states the hypothesis: a programming language designed around the operational characteristics of LLMs — graph-structured source representation, mandatory effect declarations, content-addressed identity, capability-based execution, first-class state machines — will produce, when used as the target for AI code generation:

1. Higher rates of first-pass correctness due to structural verification at every operation.
2. Lower inference cost per task due to elimination of syntactic tokens.
3. Stronger security guarantees due to explicit effects and capability boundaries.
4. Native distribution to threads, clusters, and heterogeneous hardware.
5. Cleaner integration with confidential computing primitives.

Each claim is empirically testable. The plan below describes the experiments.

## Phase 1: Bootstrap and corpus generation {#phase-1-bootstrap}

[Q-020](open-questions.md#Q-020) is the most important research question for project viability. No Strand training corpus exists, and an LLM unfamiliar with Strand cannot generate fluent Strand programs. The bootstrap strategy is the path from no corpus to a corpus sufficient for fluent generation.

The strategy is staged. Each stage produces a corpus that seeds the next stage; quality and volume increase across stages.

**Stage 1.1: Hand-authored seed corpus.** A small set of Strand programs is hand-constructed by the research team, covering the basic node algebra patterns: literals, function definitions and applications, type declarations, effect-mediated IO, state machines, foreign bindings. Target volume is 50 to 200 programs of varying complexity, with both natural-language specifications and Strand graph forms paired. The seed corpus also serves as a worked-example reference for the design's coherence: every program in the seed corpus must verify and run.

**Stage 1.2: Translation corpus.** A corpus of programs in conventional languages — initially Python, given its volume and breadth — is translated to Strand graphs. Translation is automated for the structural transformation (Python AST → Strand graph) with manual or semi-automated effect declaration. The translated corpus produces a large baseline volume (target: 10,000 to 100,000 programs from open-source Python sources) but with imperfect effect declarations: where the source code's effects are clear (well-typed Rust, typed Python, WIT-described WebAssembly), declarations are precise; where they are opaque (untyped Python, FFI-heavy code), declarations are conservative or absent. The corpus is annotated with quality flags so downstream training can weight examples.

**Stage 1.3: Synthetic corpus from a strong model.** A capable LLM (Claude, GPT, or a successor) is prompted with the Strand specification, the seed corpus, and a task description; it produces a candidate Strand program. The verifier admits or rejects; admitted programs are added to the corpus; rejected programs are returned to the model with the failure reason for retry. The loop continues until the corpus reaches target volume (target: 100,000 to 1,000,000 programs covering a representative task distribution). The strong model serves as a *teacher*: a high-cost generator whose outputs train a lower-cost student model.

**Stage 1.4: Student model fine-tuning.** A smaller model is fine-tuned on the combined corpus from stages 1.1 through 1.3, with verifier-rejected examples included as negative training signal. The student model becomes the *generator* for downstream evaluation. The student model's success rate, measured by the verifier, is the gating criterion for proceeding to evaluation.

**Stage 1.5: Self-improvement loop.** The student model is used to generate further programs; successful generations are added to the training set; the model is fine-tuned again. This is the standard reinforcement-from-verification pattern; the verifier serves as the reward signal. Strand's structural verification makes this loop particularly tractable: rewards are computed without human labeling, and the verification cost is much lower than the cost of compiling and testing equivalent text-language programs.

**Gating criterion for Phase 1:** the student model achieves a 70% first-pass verification rate on a held-out task set of 500 representative tasks. If this threshold is not met, the bootstrap strategy is revisited (additional stage 1.2 volume, alternative architecture for the student, or scope reduction).

## Phase 2: Implementation milestones {#phase-2-implementation}

Building Strand requires several pieces of software. The order is determined by the dependency structure of the research program: subsequent phases need the artifacts from earlier ones.

**Milestone 2.1: Verifier and reference interpreter.** A reference implementation of the verifier that confirms well-formedness ([node-algebra.md](design/node-algebra.md)) and computes effect closures ([effects-and-capabilities.md](design/effects-and-capabilities.md)). A tree-walking interpreter that evaluates verified graphs. This milestone provides the basic infrastructure for stage 1.3 (the synthetic corpus loop): graphs can be admitted, checked, and run.

**Milestone 2.2: Storage and content-addressed graph store.** A persistence layer for content-addressed graphs, supporting the operations described in [ADR-003](decisions/ADR-003-content-addressing.md): node insertion with hash computation, lookup by hash, garbage collection by reachability. The store supports the in-process working set and the cross-process / cross-machine corpus.

**Milestone 2.3: Bytecode VM.** A custom bytecode VM ([ADR-008](decisions/ADR-008-compilation-target.md)) that executes verified graphs at higher throughput than the reference interpreter. This milestone is the execution target for Phase 3 evaluation.

**Milestone 2.4: Foreign function interface and WebAssembly integration.** Support for ForeignNode bindings ([ADR-005](decisions/ADR-005-foreign-nodes.md)), with WebAssembly modules as the initial sandboxed target. Initial bindings cover a POSIX-like interface (filesystem, basic networking, time) sufficient for representative evaluation tasks.

**Milestone 2.5: Encryption support.** Implementation of the encryption envelope ([encryption-model.md](design/encryption-model.md)) including BLAKE3, AES-256-GCM, X25519, and Ed25519 primitives. Per-node encryption and decryption integrated with the runtime's capability mediation.

**Milestone 2.6: Distributed runtime.** A multi-machine deployment with scheduler, coordinator, peer discovery, and node fetching, per [distribution-model.md](design/distribution-model.md). Supports the distributed-execution claim in the hypothesis.

**Milestone 2.7: TEE integration (optional for hypothesis evaluation).** Integration with at least one TEE platform for attestation-bound capabilities. Supports the confidential-computing claim in the hypothesis but is not required for the other claims; deferred if Phase 2 timeline pressure requires.

**Milestone 2.8: MLIR lowering (optional for hypothesis evaluation).** The MLIR dialect and lowering passes for AOT native compilation. Demonstrates the production path but is not required to evaluate the hypothesis.

The reference implementation language is Rust, chosen for its memory safety, performance characteristics, and existing MLIR/LLVM bindings. The implementation team's expertise determines the actual choice; the design does not require Rust specifically.

**Gating criterion for Phase 2:** Milestones 2.1 through 2.6 are complete and pass the implementation's own test suite, including correctness tests on the corpus from Phase 1.

## Phase 3: Empirical evaluation {#phase-3-evaluation}

The evaluation phase compares Strand against baselines on a set of representative tasks, using the metrics specified below. The comparison is conducted with the student model from Phase 1 as the Strand generator and standard LLMs as the baseline generators.

**Tasks.** The evaluation uses three task suites:

- A *reproduction suite* of programming tasks drawn from existing benchmarks (HumanEval, MBPP, ARC, SWE-bench equivalent benchmarks). These provide comparability with existing language model evaluations.
- An *effects suite* of tasks that emphasize correct effect handling, including security-sensitive operations (file access with restricted paths, network access with restricted hosts, cryptographic operations with restricted keys). These tasks test the hypothesis specifically.
- A *distribution suite* of tasks that emphasize concurrent and distributed execution, including state machine implementations, event-driven services, and parallel data processing. These tasks test the distribution and state machine claims.

The total task volume is approximately 5,000 to 10,000 tasks across the three suites.

**Baselines.** Strand is compared against:

- Python with type hints and manual effect annotations (added by the generator on instruction).
- Kotlin with Coroutines as a representative of concurrent baseline.
- Rust as a representative memory-safe baseline.
- TypeScript with strict typing.
- SimPy and ShortCoder where available, as representatives of AI-oriented Python modifications.

The same LLM serves as the baseline generator (with appropriate prompting per language) and as the teacher for Strand's student model (per Phase 1). This holds model capability constant across the comparison.

**Metrics.**

- *First-pass correctness rate.* The fraction of generated programs that pass the task's correctness check on the first generation, without retries. This measures the structural-verification claim.
- *Tokens per successful task.* The total tokens consumed by the generator (including retries) to produce a passing program. This measures the token-efficiency claim.
- *Verification feedback cost.* For Strand, the number of verification round-trips per successful program. This measures the practical cost of the feedback loop.
- *Effect declaration accuracy.* The fraction of generated programs whose declared effects match their actual runtime effects. Measures whether the mandatory-effect design is generation-tractable.
- *Capability minimization score.* The breadth of capabilities the generated program requires, normalized against a minimum baseline determined by an oracle or by the task specification. Measures the security claim.
- *Distribution overhead.* For distribution-suite tasks, the ratio of placement, fetch, and coordination overhead to total compute time. Measures the native-distribution claim.
- *Replay determinism.* For tasks involving stateful computation, the fraction of programs whose execution is deterministically replayable. Measures the content-addressing replay claim.

Each metric is reported per language, per task suite, with statistical analysis appropriate to the comparison.

**Gating criterion for hypothesis claims.** Each of the five claims in [`00-motivation.md`](00-motivation.md) is supported, refuted, or unresolved by the corresponding metrics. The hypothesis as a whole is supported if (a) at least three of the five claims are supported with statistical significance, and (b) the claims that are refuted have plausible mitigations identified.

## Phase 4: Production hardening and adoption {#phase-4-adoption}

This phase is contingent on Phase 3 results being positive. If the hypothesis is supported, the project proceeds to:

- Production hardening of the runtime: performance optimization, robustness under adversarial input, completeness of the standard library and bindings.
- Tooling investment: graph editors (analysis-oriented, per [ADR-002](decisions/ADR-002-no-human-projection.md)), debugger, profiler, supply-chain auditing tools.
- Adoption pilots: small-scale deployments with collaborative partners, learning what additional features production users require.
- Specification finalization: publishing a stable language specification that downstream implementations can target.

If Phase 3 results are mixed or negative, the project re-assesses scope. Possible directions include: focusing on a subset of the hypothesis claims that did hold; redesigning the design choices that did not hold; abandoning the project with the design and evaluation results published as research contribution.

## Risks and mitigations {#risks}

The research program has several principal risks. Each is identified with its mitigation strategy.

**Risk: bootstrap corpus does not yield fluent generation.** Mitigation: invest more in stage 1.2 (translation corpus volume); apply RLHF-style training in stage 1.5; if the student model plateaus below threshold, scope down to a smaller language subset that may be more tractable.

**Risk: LLMs cannot reliably generate structured graph operations.** Mitigation: investigate alternative interaction modes (graph diffs rather than full graphs, hierarchical generation that decomposes large graphs, mixed text-and-graph generation during transition). The risk corresponds to the deepest question in the research program; partial fallback would significantly weaken the hypothesis but not invalidate the design.

**Risk: verification feedback loop too slow to support stage 1.3 and 1.5 at scale.** Mitigation: amortize verification by batching, cache partial verifications, prioritize incremental verification optimizations in Milestone 2.1.

**Risk: distribution model does not scale to real workloads.** Mitigation: target single-machine multi-core execution first, treat multi-machine as a Phase 4 extension. The distribution claim is contingent on Phase 3 distribution-suite results; a negative result narrows the hypothesis without invalidating it.

**Risk: TEE integration impractical due to platform limitations.** Mitigation: support a single TEE platform (likely Intel TDX or AWS Nitro Enclaves) initially; treat broader platform support as Phase 4. The confidential-computing claim is the most aspirational; partial support is acceptable for the initial hypothesis evaluation.

**Risk: timeline expansion.** Mitigation: the phases are designed to produce useful artifacts even if later phases are deferred. Phase 1 alone produces a Strand corpus and a verifier; Phase 2 alone produces a working runtime; Phase 3 alone produces evaluation results. Each phase's deliverable is valuable independently.

**Risk: design problem not surfaced until late.** Mitigation: the design specifications in Wave 3 are deliberately detailed to surface inconsistencies early. The reference implementation (Milestone 2.1) further surfaces design problems: a verifier that cannot be built is a design problem regardless of the elegance of the specification.

## References

**Outgoing references:**
- [`00-motivation.md`](00-motivation.md) — hypothesis under test
- [`02-core-thesis.md`](02-core-thesis.md) — claims to be evaluated
- [`01-prior-art.md`](01-prior-art.md) — baselines and comparisons
- [`decisions/ADR-002-no-human-projection.md`](decisions/ADR-002-no-human-projection.md) — analysis tooling commitments for Phase 4
- [`decisions/ADR-005-foreign-nodes.md`](decisions/ADR-005-foreign-nodes.md) — Milestone 2.4 binding work
- [`decisions/ADR-008-compilation-target.md`](decisions/ADR-008-compilation-target.md) — VM and MLIR milestones
- [`design/node-algebra.md`](design/node-algebra.md) — verifier requirements
- [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) — verifier requirements
- [`design/encryption-model.md`](design/encryption-model.md) — Milestone 2.5
- [`design/distribution-model.md`](design/distribution-model.md) — Milestone 2.6
- [`design/state-machines.md`](design/state-machines.md) — distribution-suite tasks
- [`open-questions.md`](open-questions.md) — Q-020, Q-021 addressed here

**Incoming references:**
- [`README.md`](README.md)
- [`00-motivation.md`](00-motivation.md)
- [`02-core-thesis.md`](02-core-thesis.md)
- [`01-prior-art.md`](01-prior-art.md)
