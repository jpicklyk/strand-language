# Containment demonstration {#containment-demo}

**Document:** `demos/containment-host/README.md`
**Status:** Executable companion to the Q-044 containment measurement
**Last revised:** 2026-06-13

## What this demonstration is

[`containment-results.md`](../../evaluation/containment-results.md) measures the
structural-safety lead claim: the maximum harm a generated subgraph can cause is
computable from
the graph before it executes and bounded at execution, expressed as the harm
bound `closure(g) ∩ C ∩ B ∩ P`. That document is the measurement of record. This
document is its executable companion. It describes a small running host that
performs the act the measurement describes: it admits programs it has never seen,
computes each program's harm from the artifact alone, and runs each one contained,
with concurrent tenants provably isolated.

The host is an ordinary JVM caller of the shipped embedding surface — the Q-054
`StrandRuntime` facade, the Q-054 follow-up `HostContext` per-tenant isolation,
the Q-064 `DenialReport`, and the Q-058 `PersistentStore`. It introduces no
language feature, no node category, no encoding change, and no verifier rule. It
is a showcase of what shipped, exercised through the published APIs, kept honest:
every property the demonstration claims is one the runtime enforces, and the
assertion net (`ContainmentDemoTest`) protects each one from silently rotting.

The tenant programs are hand-authored stand-ins for agent submissions. They live
as Layer A source plus compiled canonical dag-json under
[`programs/`](programs/). Hand-authoring them isolates the host's containment
— the subject of this demonstration — from the separate question of how an agent
generates programs, which the Q-021 cost measurement and the deferred Run 8
dynamic study address.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:containmentDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.ContainmentDemoTest"
```

The driver `ContainmentDemo` and the test `ContainmentDemoTest` live in the
`:runtime` test source set
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and share one body of
scenario code, so the printed demonstration and the regression net cannot diverge.
They stay in `:runtime` because they compile against the runtime modules. The
driver loads the committed canonical dag-json from [`programs/`](programs/) through
the test classpath (`runtime/build.gradle.kts` copies the directory in via
`processTestResources`), so the artifact the host admits is the content-addressed
graph, not the human-facing projection.

## The scenarios

Each scenario maps to one or more of the six Q-044 harm classes and exercises the
specific Strand mechanism that contains it.

### S1 Admission rejection

The over-reaching submission `overreach-projection-drift` declares, through its
authored `EffectDecl`, that it writes to the benign path `/workspace/out.txt`, but
the actual call argument is `/etc/shadow`. The declared intent has drifted from
the value the foreign code would receive — the confused-deputy shape. Before
admitting the program the host computes its harm bound from the artifact alone:
the declared effect closure is `{Filesystem.Write}`, the host's grant for this
tenant permits only `{Time.Now}`, so the write is beyond the grant. The host then
verifies, and the Q-039 effect-projection rule rejects the program at admission
with the structured `ProjectionMismatch` error. The harm is bounded by structure,
before any execution.

This maps to the confused-deputy harm class (caught at verify) and to the
ungranted-capability class (the harm bound shows `Filesystem.Write` is outside the
grant). The contrast the transcript records: a conventional runtime, having no
effect declaration to check the call argument against, would have run this and
written `/etc/shadow`.

A note on what the host computes, kept honest. The harm bound shown is the
declared effect closure — the set of `EffectCategory` names reachable from the
root by structural induction over the graph — intersected with the granted
categories. This is a sound upper bound on `closure(g)`: the verifier's mandatory
effect-closure rule (`UncoveredEffects`) guarantees no admitted graph performs an
effect absent from its declarations, so every effect a run can reach corresponds
to an `EffectCategory` reachable in the walk. The host re-derives this from the
artifact by structural walk because this submission is *rejected* at verification
(`ProjectionMismatch`), and the verifier's own effect closure is populated only on
a successful `VerifyResult.Ok` — a rejected program has no verifier closure to
read. The walk is the sound pre-admission substitute for exactly this case; the
clean-verifying scenario S3 instead reads the surfaced closure directly (see
Q-067 and Follow-up gaps). The number is computed, not hardcoded; the test
asserts the artifact reaches `Filesystem.Write` and that it lies beyond the
`{Time.Now}` grant.

### S2 Concurrent isolation

Two mutually-distrusting tenants, `tenant-a-cross-read` and `tenant-b-cross-read`,
run concurrently under distinct `HostPolicy` values — separate workspace
sandboxes, separate credentials. The runs are launched as parallel coroutines on
`Dispatchers.Default` and rendezvous on a `CyclicBarrier` inside the effectful
path, so both are provably executing the effectful operation at the same instant
when the policy-sourced value is read. This reuses the technique of
`ConcurrentMultiTenantIsolationTest`: isolation is proven under genuine
concurrency, not inferred from sequential runs.

The sandbox half: each tenant attempts to read the other's workspace through a
relative escape (`../tenant-b/secret.txt` from tenant A, the mirror from tenant
B). Under each tenant's own workspace-rooted SECURE sandbox the path resolves
outside the workspace, so the runtime denies it with `SandboxViolation(FsPathEscape)`.
Neither tenant can reach the other's files even while both are inside `Fs.Read`
simultaneously. This maps to the path-traversal harm class, bounded at runtime.

The credential half: each tenant resolves its own credential — registering it into
that tenant's per-context `Scrubber` — then both throw an identical error text
embedding both tenants' keys. Each per-context scrubber redacts only the key its
own provider resolved; the other tenant's key, which that context's scrubber never
saw, survives verbatim. The asymmetry under identical error text is the isolation
proof: a shared scrubber would redact both keys for both tenants. This maps to the
credential-leak harm class, bounded at runtime by the Q-064 follow-up per-context
scrubber. The credential probe is triggered by a host-installed test builtin that
exercises the real per-context scrubber; the builtin is scaffolding to reach the
mechanism, not a substitute for it, because no pure-Strand builtin both resolves a
credential and emits it into an error.

### S3 Runtime denial

The submission `runtime-denial-write` declares `Filesystem.Write` and writes to
`/tenant/secret.log`. Its declared refinement matches its call argument, so Q-039
projection agreement holds and the program passes verification — whether it is
harmful depends on a value (the path) and on the grant the host hands it, a
runtime question rather than a structural one. The host grants `Filesystem.Write`
refined to a different path, `/tenant/out.log`. At the foreign-call boundary the
runtime evaluates the requested path against the held grant; no granted pattern
covers `/tenant/secret.log`, so the call is denied with a `RefinementViolation`
carrying a structured `DenialReport`. The report names the denied category, the
requested parameters versus the held grant, the denying node, and the phase. A
benign co-tenant submitted in the same batch completes to its correct result —
one tenant's denial does not affect the other. This maps to the
ungranted-capability harm class, bounded at runtime.

Because this program verifies clean, S3 also exercises the Q-067 success path:
the host reads the verifier's own effect closure off `VerifyResult.Ok`
(`rootClosure`) rather than re-deriving it, displaying `{Filesystem.Write}`. The
program has no Handler, so the surfaced closure equals what the structural walk
would compute — but it came for free from the verifier, which is the point. The
surfaced value is the closure the verifier actually enforced (Handler-aware: a
Handler subtracts the category it intercepts), so for programs with handlers it
is a tighter bound than the structural walk; that distinction does not bite here
but is why the host prefers the surfaced value whenever the artifact verifies.

### S4 Run-by-hash

The benign submission `benign-sum` is finalized, verified once, and run once; then
admit-and-verify-once into a `PersistentStore` writes its nodes and records the
verify verdict keyed by the root hash. The host re-runs the program by root hash:
it loads the program image from the store (no re-ingest, no re-hash), the recorded
verdict is present and reused (no re-verification), and the by-hash run produces
the identical value. This is not a harm-class row; it demonstrates that the
admit-once operational property holds on the same facade the containment scenarios
use, so containment and the persistent-store workflow compose.

## Transcript

The transcript below is the output of `./gradlew :runtime:containmentDemo -q`.

```
========================================================================
Strand -- untrusted agent-program host (containment demonstration)
Executable companion to evaluation/containment-results.md (Q-044).
========================================================================

S1  Admission rejection -- harm bounded before execution
------------------------------------------------------------------------
  Submission: overreach-projection-drift (declares write to
              /workspace/out.txt, call argument is /etc/shadow).
  Host computes the harm bound from the artifact alone:
    reachable effect closure  = [Filesystem.Write]
    granted categories (C)    = {Time.Now}
    closure(g) intersect C    = []
    beyond the grant          = [Filesystem.Write]
  Host decision: REJECTED at admission (verify)
    verifier: ProjectionMismatch(at=#9, categoryIndex=0, sourceIndex=0, expected=ArgRef(index=0), actualParam=#5)
  Contrast: a conventional runtime, with no effect declaration to
  check the call argument against, would have run this and written
  /etc/shadow. Strand stops it structurally, before execution.

S2  Concurrent isolation -- two distrusting tenants, forced overlap
------------------------------------------------------------------------
  Two tenants run concurrently under distinct policies, each
  rendezvoused on a barrier INSIDE the effectful path.
  Sandbox: tenant A reads ../tenant-b/secret.txt; tenant B reads
           ../tenant-a/secret.txt -- each escapes its own workspace.
    tenant A denial = FsPathEscape
    tenant B denial = FsPathEscape
    sandbox-isolated = true
  Credentials: both tenants throw the SAME error text embedding both
           keys; each per-context scrubber redacts only its own key.
    tenant A error: upstream rejected probeA=[REDACTED:anthropic:api_key] probeB=sk-ant-tenant-B-bbbbbbbbbbbbbbbb
    tenant B error: upstream rejected probeA=sk-ant-tenant-A-aaaaaaaaaaaaaaaa probeB=[REDACTED:anthropic:api_key]
    credential-isolated = true

S3  Runtime denial -- contained at the foreign-call boundary
------------------------------------------------------------------------
  Submission: runtime-denial-write (verifies clean; writes
              /tenant/secret.log). Host grants Filesystem.Write
              refined to /tenant/out.log -- a DIFFERENT path.
  This program verifies clean, so the host reads the verifier's own
  effect closure (Q-067) off the Ok result instead of re-deriving it:
    surfaced closure (Ok)     = [Filesystem.Write]
    host structural walk      = [Filesystem.Write]
    surfaced == walk          = true
  Host decision: DENIED at runtime (RefinementViolation)
    DenialReport: category=Filesystem.Write requested=[/tenant/secret.log] held=[Filesystem.Write{/tenant/out.log}] node=#8 phase=expression
  Co-tenant submitted in the same batch (benign-sum): value = IntV(v=42) (completed normally)

S4  Run-by-hash -- admit-and-verify-once, re-run by hash
------------------------------------------------------------------------
  Submission: benign-sum, ingested into a persistent store.
    root hash               = 1edd80a345f9e4791bca...
    verdict cached at ingest= true
    first run value         = IntV(v=42)
    run-by-hash value       = IntV(v=42)
    identical result        = true
  The by-hash run reused the recorded verdict -- no re-verification,
  no re-hash -- and produced the identical value.

========================================================================
What this demonstrates: structural containment -- the measured Q-044
benefit. NOT first-pass correctness or inference cost (the deferred
Run 8 measurement, evaluation/dynamic-results.md). Tenant programs are
hand-authored stand-ins for agent submissions, isolating the host's
containment from the separate agent-generation question.
========================================================================
```

## What this demonstrates and what it does not

This demonstration shows structural containment — the measured Q-044 benefit. It
shows a host admitting an unfamiliar program, computing its harm from the artifact
before execution, rejecting what can be decided structurally, bounding what cannot,
isolating concurrent tenants under genuine overlap, and surfacing a structured
denial outcome to the orchestrating principal. Each of these is a property the
shipped runtime enforces, witnessed under the published embedding API.

It does not demonstrate first-pass correctness — whether an agent's submission is
the program the agent intended — nor inference cost, the tokens an agent spends to
produce an admissible program. Those belong to the deferred Run 8 dynamic
measurement recorded in [`dynamic-results.md`](../../evaluation/dynamic-results.md), which requires
agent-emission sampling through the model API and is a distinct study. The tenant
programs here are hand-authored precisely so the demonstration measures the host's
containment in isolation from the agent-generation question.

It is a demonstration matrix, not a soundness proof. As stated in
[`containment-results.md`](../../evaluation/containment-results.md), soundness is a universal
property argued from the mechanisms, with executed witnesses as spot-checks; this
companion is one more set of executed witnesses, driving the containment
mechanisms through the host boundary an orchestrating principal would actually use.

## Effect-closure surfacing (Q-067, resolved at the success-path level)

[Q-067](../../open-questions.md#Q-067) surfaced while building this
demonstration: the verifier computes a per-node effect closure internally
(`VerifyState.nodeClosures`, the Handler-aware computation) but did not expose it
on `VerifyResult.Ok`, so a host wanting the exact declared closure had to
re-derive it by walking the graph.

It is now surfaced. `VerifyResult.Ok` carries a `nodeClosures` map keyed by
NodeId exactly as `nodeTypes` is, with a `rootClosure(root)` accessor returning
the program root's `EffectCategory` set — `closure(g)` in the Q-044 harm bound.
This was an additive change to the verify-result shape with no language, encoding,
or hash impact (`VerifyResult` is not part of the canonical node encoding). The
surfaced value is the verifier's own closure-subtraction-aware computation, so it
is Handler-aware: a Handler that intercepts an effect subtracts it, where a
structural walk would not. Scenario S3 consumes the surfaced closure directly — it
verifies clean, so the host reads `rootClosure` off the `Ok` result rather than
walking.

The pre-admission case is documented rather than mechanized, deliberately.
Scenario S1 needs the harm bound for an over-reaching submission the verifier
*rejects* (`ProjectionMismatch`), and the verifier's closure is populated only on
a successful `Ok`. The closure computation cannot be cleanly separated to run on a
rejected artifact: `VerifyState.nodeClosures` is filled only as a side-effect of
full type inference (the Application closure adds the *resolved* callee's effect
row; the Handler subtraction depends on resolved signatures), and verification
aborts before the root closure is recorded on the first hard error — so a rejected
program has no verifier closure, sound or otherwise. For that case the host
re-derives a sound structural upper bound in `ContainmentDemo.declaredEffectClosure`,
guaranteed sound by the mandatory `UncoveredEffects` rule (no admitted graph
performs an effect absent from its declarations). Being Handler-unaware, the walk
is a *looser* bound than the surfaced value where a Handler subtracts an effect —
acceptable for an over-approximation, and exactly why S3 prefers the surfaced
closure when the artifact verifies. Q-067 is resolved at the success-path level
with this pre-admission re-derivation documented as the host's responsibility.

## References

**Outgoing references:**
- [`containment-results.md`](../../evaluation/containment-results.md) — the Q-044 containment
  measurement this demonstration executes; the harm bound `closure(g) ∩ C ∩ B ∩ P`
  and the six harm classes this maps onto.
- [`dynamic-results.md`](../../evaluation/dynamic-results.md) — the deferred Run 8 cost measurement
  this demonstration deliberately does not cover (first-pass correctness, inference
  cost).
- [`containment/python_baseline_probes.py`](../../evaluation/containment/python_baseline_probes.py)
  — the conventional-baseline probes the containment matrix contrasts against.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade and the per-tenant `HostContext` isolation
  this host is built on.
- [`proposals/implemented/persistent-store.md`](../../proposals/implemented/persistent-store.md)
  — Q-058, the `PersistentStore` and admit-and-verify-once / run-by-hash path of S4.
- [`proposals/implemented/capability-denial-observability.md`](../../proposals/implemented/capability-denial-observability.md)
  — Q-064, the `DenialReport` captured in S3.
- [`open-questions.md`](../../open-questions.md#Q-067) — Q-067, the effect-closure
  surfacing resolved at the success-path level: S3 reads the surfaced
  `rootClosure`; S1's rejected artifact re-derives a sound upper bound in
  `declaredEffectClosure`.

**Incoming references:**
- [`containment-results.md`](../../evaluation/containment-results.md) — points at this demonstration
  as its executable companion.
- [`INDEX.md`](../../INDEX.md) — changelog entry (2026-06-13).
