# Strengthened containment matrix

**Document:** `proposals/strengthened-containment-matrix.md`
**Status:** Draft (design of record; execution deferred)
**Question:** [Q-044](../open-questions.md#Q-044) (extends — the deferred strong-baseline follow-up)
**Related:** [ADR-010](../decisions/ADR-010-reasoning-surface.md), [`02-core-thesis.md`](../02-core-thesis.md) (§strongest-alternatives), [`01-prior-art.md`](../01-prior-art.md), [`implemented/reasoning-api.md`](implemented/reasoning-api.md) (Q-072), [`evaluation/containment-results.md`](../evaluation/containment-results.md)
**Drafted:** 2026-07-04

## Problem

The [Q-044](../open-questions.md#Q-044) containment matrix scores six harm classes against stock Python 3 with no sandbox — the unconfined baseline. The thesis's own methodology ([`02-core-thesis.md`](../02-core-thesis.md) §strongest-alternatives) states that the lead claim is "tested most honestly against the strongest competing approaches to that goal, not only against the unconfined conventional baseline." CaMeL, a sandboxed-subprocess execution environment, and Scala capture-checking appear in the prose comparison but not in the executed matrix. The result "every class contained in Strand, none in Python by default" is true and proves little the thesis needs, because the live competitors contain most of those classes too — the measurement executed is not the comparison the thesis stakes. This document is the design of record for the strengthened matrix: it fixes the baselines, the per-harm-class scoring, and the honesty guards so a future execution slice builds and runs the comparison rather than re-deciding the methodology.

## The baselines to add

Beyond stock Python, retained as the floor, the matrix adds the two strongest competing approaches the thesis already names, and records the published results of a third that cannot be re-run in-harness.

1. **Sandboxed-subprocess baseline.** Agent-generated code (Python or TypeScript) run inside a capability-scoped execution environment — container or seccomp-style egress filtering plus filesystem allowlisting — the pattern [`02-core-thesis.md`](../02-core-thesis.md) §strongest-alternatives names. The operative harm bound is the environment's grant, not a property of the code.
2. **CaMeL-style baseline.** The restricted-interpreter approach (arXiv 2503.18813, cited in [`01-prior-art.md`](../01-prior-art.md)): capabilities attached to data values, agent-emitted code executed in a restricted interpreter, bounding what an injected instruction can cause without changing the emitted language.
3. **Scala capture-checking (published results).** The Caprese work (Odersky et al., "Tracking Capabilities for Safer Agents," arXiv 2603.00991, cited in the thesis) where a live re-run is impractical; the matrix records, per harm class, what the published static bounds cover rather than re-deriving them.

## Scoring: the six harm classes per baseline

For each of the six Q-044 harm classes, each baseline is scored on one scale — contained by construction, contained by a runtime sandbox, partial, or uncontained by default. The purpose is to show precisely where Strand's containment is distinctive and where a hardened alternative also contains, which is the honest comparison rather than the stacked-deck one against unconfined Python. The expected shape of the result is that the hardened alternatives contain most classes; the interesting rows are the ones where they do not, and the discriminators below.

## The four discriminators the matrix must also record

The harm-class matrix alone understates the thesis, because a baseline can match Strand on a harm class while lacking the properties that make Strand's bound distinctive. The strengthened comparison records, per baseline, the four properties [`02-core-thesis.md`](../02-core-thesis.md) §strongest-alternatives identifies as what the graph form buys and the alternatives do not: the guarantee is established over the exact received bytes at admission; it is re-derivable later without the producer's toolchain; verified identity is stable across federation; and encryption and placement are node-granular. A sandboxed subprocess may contain a network egress class as well as Strand does, yet its bound is a property of the sandbox configuration the consumer runs, not of the artifact — re-deriving it later means reproducing that configuration, not reading the artifact. The matrix should make that visible, so the comparison reads as "who contains what" and "whose bound has these four properties," not harm classes alone.

## The reasoning surface as the distinguishing artifact

[ADR-010](../decisions/ADR-010-reasoning-surface.md) and the reasoning API ([Q-072](../open-questions.md#Q-072)) supply what no baseline offers: an artifact-attached, re-derivable, machine-queryable harm bound. The strengthened matrix should foreground this — the harm bound each row scores is, for Strand, a value `ProgramAnalysis` returns off the verified artifact at any later time, whereas for every alternative it is a property of an execution environment or a compilation the consumer must reproduce. That is the property to measure the comparison against, not the harm-class delta against undefended Python.

## Honesty guards

- The same probe set (the Q-044 probes) and the same threat model across every baseline.
- Where a baseline contains a class, say so plainly; the honest result is that hardened alternatives contain most classes, and Strand's distinction is the four discriminators plus the reasoning surface.
- Report the comparison the thesis stakes (§strongest-alternatives), not the unconfined-Python delta, as the headline.

## Where it lives and execution

This proposal is the design of record. The execution slice — building the sandboxed-subprocess and CaMeL-style baselines in the `evaluation/dynamic` harness, running the six-harm-class probes across all baselines, and producing the strengthened [`evaluation/containment-results.md`](../evaluation/containment-results.md) matrix with the four-discriminator columns — is a separate, week-scale evaluation build with no language change, scoped by this document. Q-044 stays open until it is executed.

## Scope

Design of record only (this proposal). The execution — two comparison baselines in the Python evaluation harness, a re-run of the probes, and the strengthened results document — is deferred and explicitly not built here.

## References

**Outgoing references:**
- [`decisions/ADR-010-reasoning-surface.md`](../decisions/ADR-010-reasoning-surface.md) — the artifact-attached bound that is the distinguishing property to foreground
- [`02-core-thesis.md`](../02-core-thesis.md) — the strongest-alternatives comparison this operationalizes
- [`01-prior-art.md`](../01-prior-art.md) — CaMeL, Scala capture-checking, the sandboxed-execution pattern
- [`evaluation/containment-results.md`](../evaluation/containment-results.md) — the matrix this strengthens
- [`open-questions.md`](../open-questions.md) — Q-044 (extended), Q-072
