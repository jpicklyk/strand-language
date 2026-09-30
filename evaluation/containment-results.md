# Containment measurement {#containment-results}

**Document:** `evaluation/containment-results.md`
**Status:** Measurement of record for the structural-safety lead claim (Q-044)
**Last revised:** 2026-09-30 (§ Soundness: the soundness property is now executed as a property test over generated programs; the premises are restated as they hold after the closures the first campaigns forced; two of the test's three stated edges are closed)

## Summary

The core thesis, as re-weighted in [`02-core-thesis.md`](../02-core-thesis.md) § outcome-priority, leads with structural safety: the maximum harm a generated subgraph can cause is computable from the graph before it executes and bounded at execution. This document is the measurement behind that claim. It defines the harm bound as a function of the graph and its capability context, states the soundness property that makes the bound trustworthy, and presents a comparative containment matrix against a conventional AI-generation target.

An executable companion that drives these containment mechanisms through the host embedding boundary — admitting unfamiliar programs, computing the harm bound from the artifact, rejecting or bounding each, and isolating concurrent tenants — is the containment-host demonstration at [`demos/containment-host/README.md`](../demos/containment-host/README.md).

The measurement is the structural-safety counterpart to the Q-021 cost measurement in [`dynamic-results.md`](dynamic-results.md). It differs from Q-021 in kind. Token cost is continuous and noisy, so Q-021 samples and reports confidence intervals. Containment is categorical and deterministic: a given subgraph either is rejected at admission, or is bound at runtime, or is not — a property of the language, not a rate to be sampled. The measurement is therefore an executed demonstration matrix, a soundness argument, and a property test that executes the soundness statement over generated programs; it is not a statistical estimate.

The headline result: across six harm classes that an AI agent can plausibly emit, every class is either rejected at verification time or contained at runtime by construction in Strand, and none is contained by default in the conventional baseline. The conventional-baseline rows are grounded in executed probes ([`containment/python_baseline_probes.py`](containment/python_baseline_probes.py)), not asserted.

## What is measured

The harm bound of a subgraph `g` evaluated under capability context `C`, resource budget `B`, and sandbox policy `P` is the set of effect operations `g` can perform together with their permitted argument values. It is computed as the intersection of four independently enforced constraints.

**Effect closure.** The verifier computes `closure(g)`, the set of effect categories any execution of `g` can reach, by structural induction over the graph. Every function carries a declared `effects` edge; the verifier rejects any subgraph whose declared effects do not cover the effects of its body (the `UncoveredEffects` rule). Effect declaration is mandatory at every composition site, so `closure(g)` is exact: there is no effect `g` can perform that is absent from its declaration. This is the computability half of the claim — `closure(g)` is a pure function of the graph, available before any execution.

**Capability context.** At runtime the context `C` is a structured `CapabilitySet` mapping each granted effect category to a list of refinement patterns, each pattern a per-slot `Wildcard | Concrete(value)`. An effectful operation proceeds only if `C` covers it: the category must be present (else `CapabilityViolation`) and some granted pattern must cover the call's evaluated arguments (else `RefinementViolation`). The reachable harm is therefore bounded by `closure(g) ∩ C`, never by `closure(g)` alone.

**Resource budget.** The budget `B` (`EvaluationLimits`: step count, stack depth, allocation count, wall-clock, plus ingest-time caps on JSON depth, node count, and byte size) is fixed at entry and enforced at ingest, in the tree-walking interpreter, and in the bytecode VM. A subgraph cannot exceed `B`; a breach surfaces as `ResourceExhaustion` rather than a host crash or hang.

**Sandbox policy.** Even within `closure(g) ∩ C`, the policy `P` independently constrains argument values at the foreign-call boundary: filesystem paths are confined to a workspace root, and network destinations are default-denied on loopback, RFC1918, link-local, and cloud-metadata ranges. Violations surface as `SandboxViolation`.

The harm bound is `closure(g) ∩ C ∩ B ∩ P`. Effect closure is computed statically; the other three are enforced at runtime against the statically declared effects. The bound is both computable before execution and enforced during it.

## Soundness

The bound is useful only if execution cannot exceed it. The soundness property is: for every execution of `g` under `(C, B, P)`, every effect operation that occurs lies within `closure(g) ∩ C ∩ B ∩ P`. It rests on four sub-properties, each enforced by a distinct mechanism and spot-checked by tests.

Closure exactness holds because effect declaration is mandatory and the verifier rejects under-declaration; no admitted graph performs an undeclared effect. For the `strand-builtin:` namespace the rejection is checked against the registry's own effect surface (Q-056, with the 2026-09-29 floor that applies without the signature oracle and at dispatch): before that check, a ForeignNode's declaration was taken on faith and an empty row on an `Fs.Write` binding verified with an empty closure, so the premise held only for honest emitters. For bindings outside that namespace the premise still rests on ADR-005 provenance trust (Q-006); indirectly-invoked ToolDef and callback effects are reported in the distinct latent channel rather than the direct closure (Q-070, Q-071). The premise has three further conditions, each a verifier rule since 2026-09-30. Every expression the runtime evaluates is either counted in a closure or required to be effect-free: the refinement parameters of an effect instance, the literal of a literal pattern, and the literal source of a projection are evaluated outside any closure and must perform nothing, and a ToolDef's closure is that of its implementation expression. A Handler subtracts its intercepted category only where the runtime can intercept it, which excludes the row of a nested Handler whose handle is a ForeignNode. And the closure surface is the union of the direct and latent channels; the direct channel alone is not a bound.

Capability confinement holds because every effectful builtin checks `C` before acting, on both backends and on every dispatch path (direct Application, higher-order callback, tool implementation, handler), and because the value the check matches is the value the builtin acts on. Q-039 effect projections bind the two at the binding; for the registry's own I/O builtins the runtime takes the refinement from the argument positions the registry records, whatever the binding, the call site, or the program's declaration of the category says, so an unprojected binding or a parameterless category declaration cannot move or remove the value a host's refined grant is matched against. A dispatch that supplies no refinement for a parameterized category is covered only by an unrefined grant. Budget confinement holds because the counters are threaded through every evaluation site in both backends, including the nested dispatch loops a higher-order builtin's callbacks run in. Argument confinement holds because the sandbox policy is checked inside the relevant builtins, independently of the capability check.

Soundness is a universal property. A finite probe set can witness containment but cannot establish that no program escapes, so the property is argued above and, in addition, executed: the argument is the claim, the hand-written probes below are its witnesses, and the property test searches generated programs for a counterexample to it.

### Property test

`EffectClosureSoundnessFuzzTest` (under `impl-kotlin/corpus/`) generates well-typed programs from a seed, runs every program the verifier admits under a range of grants on both execution backends, and checks five properties. For every admitted program P and every grant G:

| Property | Statement |
|----------|-----------|
| S1 closure soundness | Every effect category performed in a run of P is in the total closure (direct and latent) the reasoning surface reports for P. |
| S2 capability confinement | Every performed effect is permitted by G, including its refinement, and lies inside every dynamically enclosing CapabilityScope. A run under G performs a prefix of what the run under a full grant performs and either ends in a capability denial or reaches the same outcome. |
| S3 guard agreement | The self-gating entry point refuses P, before any effect and any audit record, exactly when the total closure is not within G's categories. |
| S4 backend parity | The bytecode VM reaches the same value or the same denial (variant and category) as the interpreter, performing the same effects and emitting the same audit records. |
| S5 no raw failures | Every stage ends in a value or a structured error, never a raw host exception. |

Three supporting checks keep the oracle honest: every performed effect has an `Allowed` record in the Q-055 effect-audit log at its dispatch, so the log is a complete record of what ran; the value a program returns has the type the verifier assigned and no builtin receives a mistyped argument, since a type hole can be laundered into an effect hole; and no value violating a schema's invariant reaches a parameter typed by that schema.

The generator is type-directed, not mutational. It builds an expression of a requested type from the binders in scope and keeps the admission rules satisfied by construction, so more than nine in ten generated programs are admitted and run. It reaches the shapes in which the 2026-09-29 review found defects: shared DAG nodes, Handlers over polymorphic callees and over bindings whose effect row is declared only on the function type, CapabilityScope nested with Handlers, higher-order builtins with effectful callbacks, ToolDef implementations, NodeRefs in term and type position, Q-039 projections with and without effect instances, Schema positions on shared nodes, and a StateMachine's initial state. It also emits a higher-order builtin that performs an effect of its own, and a pure operation that can fail (division), so that a program can stop on a structured error wherever an expression is evaluated. A small fraction of constructs are generated under-declared on purpose; the verifier is expected to reject them, and one it admits is observed by the properties. Grants are generated too: empty, the exact closure, category-only subsets, and refined subsets.

The oracle is independent of the verifier and of the runtime's capability check. Effectful operations are stand-in builtins whose bodies record that they ran and with which arguments, together with two registry builtins observed through the host: `Time.Now` through the policy clock, and `Fs.Write` as the files a run leaves in a throwaway workspace. The audit log is checked against this record, and the properties are evaluated on both. No generated program reaches a network, a process, or the wall clock.

A failing case is reduced by shrinking the generator's sequence of random choices, which preserves well-typedness, and is reported as the seed, the violated property, the grant, and the program as dag-json. Each distinct failure is preserved as a regression: a [`corpus/negative/`](../corpus/negative/README.md) entry when its fix is a rejection reachable from dag-json, otherwise a case in `SoundnessRegressionTest`.

The first campaigns falsified the property as then implemented: they reported failures of S1, S2, S4 and S5 and of all three supporting checks, among them a file written outside a path-refined grant and effects performed outside the surfaced closure. The closures are recorded in [`security-index.md`](../security-index.md) § Soundness property testing; the premises above are stated as they hold after them. With the closures in place, campaigns of 150,000 programs on each of four seeds report no failure, and the suite runs a fixed-seed campaign of 10,000 on every build.

The first version of the test had three stated edges, and two are closed. A program rooted at a StateMachine is bounded by `ProgramAnalysis.machineClosure`, the declared effect row together with the latent reach of the transition and the initial state, and guard agreement is checked against `runMachineGuarded` ([Q-073](../open-questions.md#Q-073)); the declared row alone is not a bound, since the coverage rule does not extend to callbacks and tool implementations. Runtime schema obligations are enforced, and the schema check evaluated, on every path: the interpreter, the bytecode VM, and machine transitions ([Q-047](../open-questions.md#Q-047)). Every expression-rooted program is compared across the two backends, tool-carrying programs included.

The test does not prove soundness. It samples one generator's programs over a fixed effect vocabulary, so a defect in a construct the generator does not emit is outside its reach; several of the closures above were found by reading the code next to a reported failure, not by the generator. A Handler interception the verifier could not check statically is refused at runtime rather than ruled out at admission ([Q-077](../open-questions.md#Q-077)). Machine groups, the asynchronous runtime, are not generated: the group-shaped closure query and guard are covered by unit tests only.

## Harm-class taxonomy

Six harm classes are measured. Each is an operation an AI agent can plausibly emit by misgeneration or by following an injected instruction, and each maps to a specific Strand mechanism and its witness.

The first four correspond to the 2026-05-26 security-audit findings resolved as Q-039 through Q-042. The fifth and sixth are the effect/capability core that those findings build on: an operation whose effect is undeclared, and an operation for which no capability is granted.

## Containment matrix

For each harm class the matrix records where the harm is stopped in Strand and in the conventional baseline. The stop point is one of: verify — rejected at graph admission; runtime — admitted but bound at execution by capability, budget, or sandbox enforcement with a structured error; none — proceeds to the OS unchecked.

| Harm class | Strand stop point | Mechanism | Witness | Conventional baseline |
|------------|-------------------|-----------|---------|-----------------------|
| Confused deputy (effect-argument drift) | verify / runtime | Q-039 effect projections, with projection drift rejected at the call site; at dispatch the registry's resource projection binds the checked value for `Fs.*` and `Net.Connect` whatever the binding declares | corpus 73, `CorpusProjectionTest`, `ForeignEffectTrustTest` | none — declared intent is not coupled to the call argument |
| Resource exhaustion (DoS) | runtime | Q-040 `EvaluationLimits`; `ResourceExhaustion` | corpus 71, 72; `InterpreterLimitsTest`, `VmLimitsTest` | none — `RecursionError` / unbounded hang, no recoverable budget |
| Path traversal | runtime | Q-041 `FsSandbox`; `SandboxViolation(FsPathEscape)` | corpus 74, `CorpusSandboxTest` | none — `open()` reads outside the workspace |
| SSRF (metadata / internal ranges) | runtime | Q-041 `NetSandbox` default-deny | corpus 75, `SandboxPolicyTest` | none — no allowlist or default-deny on internal ranges |
| Credential leak via error stream | runtime | Q-042 `Credential` + `CredentialScrubber` | `CredentialTest`, `CredentialScrubberTest` | none — bare-string secret flows verbatim into the error message |
| Undeclared effect / ungranted capability | verify / runtime | mandatory effect closure (`UncoveredEffects`); capability check (`CapabilityViolation`) | `CapabilitySetTest`, effect-closure verifier tests, `EffectClosureSoundnessFuzzTest` | none — no effect system; any code performs any effect |

Two classes are caught at verification time, before any execution: confused-deputy drift at a projected binding and undeclared effects are structural properties of the graph that the verifier rejects at admission. A binding that omits the projection is admitted, and its drift is stopped at dispatch instead. The remaining classes are admitted as well-formed but bound at runtime, because their harm depends on values known only at execution — a path string, a host, a step count, a credential in an upstream response. This split is the precise content of "computable before, bounded during": the static half rejects what can be decided structurally, the dynamic half enforces the bound on what cannot.

## Conventional baseline

The baseline is stock Python 3, the densest and most model-familiar conventional AI-generation target measured under Q-021. The baseline rows above are grounded in executed probes; the recorded outcomes under Python 3.13 are:

- Path traversal: `open()` read a file resolving outside the designated workspace; no confinement fired.
- Resource exhaustion: unbounded recursion raised `RecursionError` — a host interpreter limit, not a recoverable language-level budget — and an unbounded loop hangs the process with no wall-clock ceiling.
- Credential leak: a bare-string key flowed verbatim into the exception message; there is no redaction barrier and no secret-bearing type.
- Confused deputy: a function documented to write a fixed log path wrote to an arbitrary argument path instead; no declaration is bound to the call argument.
- SSRF: `urllib.request.urlopen` accepted an internal-style URL and dispatched it to the socket layer; the only failure was at transport, never at a policy layer, because stock Python has no allowlist or default-deny on internal ranges.

These are facts about the language as a default generation target, not claims that Python cannot be sandboxed. OS containers, seccomp, import hooks, and allowlist libraries can approximate each boundary after the fact. The distinction the thesis rests on is that these are bolt-on and external: they cannot be computed from the program text, and they confine the whole process rather than a named subgraph. Strand's bound is intrinsic to the graph and per-subgraph, and the static half is decidable from the graph alone.

## Limits and what this does not measure

This measurement establishes that the harm bound is computable and enforced, and that the conventional baseline enforces none of the six classes by default. It does not measure the following, which are out of scope for the structural claim and noted as follow-ups.

The measurement does not prove soundness. Soundness is argued, with the witnesses as spot-checks, and tested on generated programs (§ Soundness › Property test), which can refute the property but not establish it. A mechanized proof of the effect-closure and capability-confinement properties is a possible future strengthening.

The measurement does not cover effect classes whose enforcement is deferred: WebAssembly sandboxing for foreign code (Q-006), TEE attestation chains (forthcoming in [`design/security-model.md`](../design/security-model.md) § tee-attestation), and signed-manifest verification (Q-006, Q-043). These are predicted future boundaries, not present ones.

The measurement is structural, not behavioral. It measures what the language contains, not how an agent behaves under it. A distinct and larger study — whether mandatory effect declaration makes an agent surface dangerous intent more often than a conventional language buries it — measures intent visibility rather than harm containment and requires agent-emission sampling through the strand-eval harness. It is deferred as a Q-044 follow-up.

## Reproduction

The conventional-baseline probes run under stock Python 3 with no dependencies:

```sh
python evaluation/containment/python_baseline_probes.py
```

The Strand-side witnesses are the corpus programs and unit tests named in the matrix, exercised by `./gradlew test` in `impl-kotlin/`. The same command runs the soundness property test at its fixed seed. A longer campaign, or one on another seed, runs from `impl-kotlin/` with:

```sh
./gradlew :corpus:test --tests "*EffectClosureSoundnessFuzzTest" \
    -Dstrand.soundness.iterations=150000 -Dstrand.soundness.seed=1
```

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — the structural-safety lead claim this measurement substantiates
- [`design/security-model.md`](../design/security-model.md) — the threat model this measurement operationalizes
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — effect closure and refinement-lattice capability matching
- [`dynamic-results.md`](dynamic-results.md) — the cost measurement this parallels (Q-021)
- [`demos/containment-host/README.md`](../demos/containment-host/README.md) — the executable companion that drives these mechanisms through the host embedding boundary
- [`open-questions.md`](../open-questions.md) — Q-044 (this measurement), Q-039 through Q-042 (the resolved findings measured here), Q-047, Q-073 and Q-077 (the stated edges of the property test)
- [`security-index.md`](../security-index.md) — § Soundness property testing, the closures the property test forced
- [`corpus/negative/README.md`](../corpus/negative/README.md) — the negative corpus that preserves the property test's rejections
- [`proposals/implemented/foreign-effect-projections.md`](../proposals/implemented/foreign-effect-projections.md) — Q-039 confused-deputy mechanism
- [`proposals/implemented/interpreter-resource-limits.md`](../proposals/implemented/interpreter-resource-limits.md) — Q-040 resource budget
- [`proposals/implemented/io-builtin-sandboxing.md`](../proposals/implemented/io-builtin-sandboxing.md) — Q-041 path and network sandboxing
- [`proposals/implemented/credential-isolation.md`](../proposals/implemented/credential-isolation.md) — Q-042 credential isolation

**Incoming references:**
- [`02-core-thesis.md`](../02-core-thesis.md)
- [`design/node-algebra.md`](../design/node-algebra.md) — § Well-formedness cites § Soundness for the 2026-09-30 rules
- [`open-questions.md`](../open-questions.md)
- [`INDEX.md`](../INDEX.md)
- [`security-index.md`](../security-index.md)
