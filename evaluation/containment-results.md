# Containment measurement {#containment-results}

**Document:** `evaluation/containment-results.md`
**Status:** Measurement of record for the structural-safety lead claim (Q-044)
**Last revised:** 2026-05-29

## Summary

The core thesis, as re-weighted in [`02-core-thesis.md`](../02-core-thesis.md) § outcome-priority, leads with structural safety: the maximum harm a generated subgraph can cause is computable from the graph before it executes and bounded at execution. This document is the measurement behind that claim. It defines the harm bound as a function of the graph and its capability context, states the soundness property that makes the bound trustworthy, and presents a comparative containment matrix against a conventional AI-generation target.

The measurement is the structural-safety counterpart to the Q-021 cost measurement in [`dynamic-results.md`](dynamic-results.md). It differs from Q-021 in kind. Token cost is continuous and noisy, so Q-021 samples and reports confidence intervals. Containment is categorical and deterministic: a given subgraph either is rejected at admission, or is bound at runtime, or is not — a property of the language, not a rate to be sampled. The measurement is therefore an executed demonstration matrix plus a soundness argument, not a statistical estimate.

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

Closure exactness holds because effect declaration is mandatory and the verifier rejects under-declaration; no admitted graph performs an undeclared effect. Capability confinement holds because every effectful builtin checks `C` before acting, and Q-039 effect projections bind the checked argument values to the actual call arguments by construction, closing the confused-deputy gap where a check could pass on values different from those the foreign code receives. Budget confinement holds because the counters are threaded through every evaluation site in both backends, with VM and interpreter checked for equivalence. Argument confinement holds because the sandbox policy is checked inside the relevant builtins, independently of the capability check.

Soundness is a universal property and is argued, not benchmarked: a finite probe set can witness containment but cannot establish that no program escapes. The probes below are the witnesses; the argument above is the claim. The Strand-side witnesses are the corpus programs and unit tests enumerated in the matrix, all currently passing under `./gradlew test`.

## Harm-class taxonomy

Six harm classes are measured. Each is an operation an AI agent can plausibly emit by misgeneration or by following an injected instruction, and each maps to a specific Strand mechanism and its witness.

The first four correspond to the 2026-05-26 security-audit findings resolved as Q-039 through Q-042. The fifth and sixth are the effect/capability core that those findings build on: an operation whose effect is undeclared, and an operation for which no capability is granted.

## Containment matrix

For each harm class the matrix records where the harm is stopped in Strand and in the conventional baseline. The stop point is one of: verify — rejected at graph admission; runtime — admitted but bound at execution by capability, budget, or sandbox enforcement with a structured error; none — proceeds to the OS unchecked.

| Harm class | Strand stop point | Mechanism | Witness | Conventional baseline |
|------------|-------------------|-----------|---------|-----------------------|
| Confused deputy (effect-argument drift) | verify | Q-039 effect projections; verifier rejects projection drift at the call site | corpus 73, `CorpusProjectionTest` | none — declared intent is not coupled to the call argument |
| Resource exhaustion (DoS) | runtime | Q-040 `EvaluationLimits`; `ResourceExhaustion` | corpus 71, 72; `InterpreterLimitsTest`, `VmLimitsTest` | none — `RecursionError` / unbounded hang, no recoverable budget |
| Path traversal | runtime | Q-041 `FsSandbox`; `SandboxViolation(FsPathEscape)` | corpus 74, `CorpusSandboxTest` | none — `open()` reads outside the workspace |
| SSRF (metadata / internal ranges) | runtime | Q-041 `NetSandbox` default-deny | corpus 75, `SandboxPolicyTest` | none — no allowlist or default-deny on internal ranges |
| Credential leak via error stream | runtime | Q-042 `Credential` + `CredentialScrubber` | `CredentialTest`, `CredentialScrubberTest` | none — bare-string secret flows verbatim into the error message |
| Undeclared effect / ungranted capability | verify / runtime | mandatory effect closure (`UncoveredEffects`); capability check (`CapabilityViolation`) | `CapabilitySetTest`, effect-closure verifier tests | none — no effect system; any code performs any effect |

Two classes are caught at verification time, before any execution: confused-deputy drift and undeclared effects are structural properties of the graph that the verifier rejects at admission. The remaining classes are admitted as well-formed but bound at runtime, because their harm depends on values known only at execution — a path string, a host, a step count, a credential in an upstream response. This split is the precise content of "computable before, bounded during": the static half rejects what can be decided structurally, the dynamic half enforces the bound on what cannot.

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

The measurement does not establish soundness empirically; soundness is argued, with the witnesses as spot-checks. A mechanized proof of the effect-closure and capability-confinement properties is a possible future strengthening.

The measurement does not cover effect classes whose enforcement is deferred: WebAssembly sandboxing for foreign code (Q-006), TEE attestation chains (forthcoming in [`design/security-model.md`](../design/security-model.md) § tee-attestation), and signed-manifest verification (Q-006, Q-043). These are predicted future boundaries, not present ones.

The measurement is structural, not behavioral. It measures what the language contains, not how an agent behaves under it. A distinct and larger study — whether mandatory effect declaration makes an agent surface dangerous intent more often than a conventional language buries it — measures intent visibility rather than harm containment and requires agent-emission sampling through the strand-eval harness. It is deferred as a Q-044 follow-up.

## Reproduction

The conventional-baseline probes run under stock Python 3 with no dependencies:

```sh
python evaluation/containment/python_baseline_probes.py
```

The Strand-side witnesses are the corpus programs and unit tests named in the matrix, exercised by `./gradlew test` in `impl-kotlin/`.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — the structural-safety lead claim this measurement substantiates
- [`design/security-model.md`](../design/security-model.md) — the threat model this measurement operationalizes
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — effect closure and refinement-lattice capability matching
- [`dynamic-results.md`](dynamic-results.md) — the cost measurement this parallels (Q-021)
- [`open-questions.md`](../open-questions.md) — Q-044 (this measurement), Q-039 through Q-042 (the resolved findings measured here)
- [`proposals/implemented/foreign-effect-projections.md`](../proposals/implemented/foreign-effect-projections.md) — Q-039 confused-deputy mechanism
- [`proposals/implemented/interpreter-resource-limits.md`](../proposals/implemented/interpreter-resource-limits.md) — Q-040 resource budget
- [`proposals/implemented/io-builtin-sandboxing.md`](../proposals/implemented/io-builtin-sandboxing.md) — Q-041 path and network sandboxing
- [`proposals/implemented/credential-isolation.md`](../proposals/implemented/credential-isolation.md) — Q-042 credential isolation

**Incoming references:**
- [`02-core-thesis.md`](../02-core-thesis.md)
- [`open-questions.md`](../open-questions.md)
- [`INDEX.md`](../INDEX.md)
- [`security-index.md`](../security-index.md)
