# Clean-room demonstration {#clean-room-demo}

**Document:** `demos/clean-room/README.md`
**Status:** Runnable demonstration; built on shipped APIs only
**Last revised:** 2026-06-13

## What this demonstration is

This is the mode-2 positive-proof reframing of containment. The
[`containment-host`](../containment-host/README.md) demonstration shows the host
*bounding harm* — admitting an unfamiliar program, computing its maximum harm from
the artifact, and isolating it at runtime. This demonstration shows the *positive*
face of the same mechanism: the effect closure used as a structural proof of a
negative.

A data clean-room holds a sensitive dataset and admits a partner-submitted
analytic computation over it. Before admitting the computation the host confirms
the computation's declared effect closure contains no egress category —
`Network.*` and `Process.*` are absent — so the computation cannot phone home.
The point is *how* this is known: not because a monitor watches the run for a
suspicious socket, but because the admitted graph cannot *express* the egress at
all. Strand's verifier enforces a mandatory effect-closure rule
(`UncoveredEffects`): no admitted graph performs an effect absent from its
declarations. So "no egress category in the closure" is a proof of the negative,
not an observation of one execution. There is no node in the admitted graph that
opens a socket; an empty egress intersection is a property of the artifact, true
of every run it could ever have.

The host is an ordinary JVM caller of the shipped embedding surface — the Q-054
`StrandRuntime` facade and the Q-067 surfaced effect closure. It introduces no
language feature, no node category, no encoding change, and no verifier rule. It
is a showcase of what shipped, exercised through the published APIs, kept honest:
every property the demonstration claims is one the runtime enforces, and the
assertion net (`CleanRoomDemoTest`) protects each one from silently rotting.

The two submissions are hand-authored stand-ins for partner submissions. They live
as compiled canonical dag-json under [`programs/`](programs/). Hand-authoring them
isolates the clean-room's admission decision — the subject of this demonstration —
from the separate question of how a partner generates a computation, which the
Q-021 cost measurement and the deferred Run 8 dynamic study address.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:cleanRoomDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.CleanRoomDemoTest"
```

The driver `CleanRoomDemo` and the test `CleanRoomDemoTest` live in the `:runtime`
test source set (`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and
share one body of scenario code, so the printed demonstration and the regression
net cannot diverge. They stay in `:runtime` because they compile against the
runtime modules. The driver loads the committed canonical dag-json from
[`programs/`](programs/) through the test classpath (`runtime/build.gradle.kts`
copies the directory in via `processTestResources`), so the artifact the host
admits is the content-addressed graph, not a human-facing projection.

## The scenarios

### CR1 Admit with proof

The clean submission `clean-analytic` reads the host's dataset
(`Fs.Read("dataset.csv")`, declaring `Filesystem.Read`) and reduces it to a byte
count (`Bytes.Length`, a pure reduction). Its declared effect closure is exactly
`{Filesystem.Read}` — a benign read over the host's data, with no network or
process node anywhere in the graph.

The host reads the *surfaced* effect closure off `VerifyResult.Ok` — the
verifier's own Handler-aware computation (Q-067), not a re-derivation — and
intersects it with the egress categories it watches
(`Network.Connect`, `Network.Send`, `Network.Receive`, `Process.Spawn`). The
intersection is empty. That empty intersection is the admission proof: the
admitted graph has no node that can open a socket or spawn a process, so the
computation *cannot* exfiltrate, by construction. The host admits the submission
under a grant of exactly `{Filesystem.Read}` and runs it over a real dataset file
in a SECURE workspace; the analytic result is the dataset's byte count, computed
without any egress edge in the graph to send it anywhere.

The framing the transcript records: a conventional clean-room admits a computation
and *trusts* (or monitors) that it will not exfiltrate. Strand admits it because
the graph it admitted cannot express egress at all — the proof precedes and
replaces the monitor.

### CR2 Reject the exfiltrator

The exfiltrator submission `exfiltrator-analytic` is the same clean analytic plus
a `Net.Connect` egress edge to `collector.evil.example.com`, reachable from the
root through a `Let` binding. Its declared effect closure therefore contains
`Network.Connect` alongside `Filesystem.Read`.

Under the clean-room grant `{Filesystem.Read}` the host computes the admission harm
bound from the artifact alone: `closure(g) ∩ C` permits `{Filesystem.Read}`, but
`beyondGrant` is exactly `{Network.Connect}` — non-empty. An egress category is
structurally present in the closure and lies outside the grant, so the host refuses
admission *before any execution*. The egress cannot be hidden because it is a
declared, reachable graph node; there is no way to express the connect without the
`Network.Connect` category appearing in the closure the host reads.

This is the same harm bound the `containment-host` S1 scenario uses, turned to a
clean-room admission gate: a non-empty `beyondGrant` is a structural refusal, not a
runtime denial.

### CR3 Scope of the proof

The non-exfiltration proof holds for the clean-room *profile*: typed builtins whose
effects are declared and surfaced in the closure. It does not extend to a
`Process.Spawn` shell-out, whose child process's internal effects are opaque to the
closure — which is exactly why `Process.Spawn` is among the watched egress
categories and a clean-room grant of `{Filesystem.Read}` would refuse it. The proof
is about what the admitted graph can *express*, not about what already-trusted host
builtins do internally. Admitting a builtin into the clean-room profile is a
trust decision about that builtin; the closure proof is sound for exactly the
builtins whose effects it can see.

## Transcript

The transcript below is the output of `./gradlew :runtime:cleanRoomDemo -q`.

```
========================================================================
Strand -- data clean-room (proof-of-no-exfiltration demonstration)
The effect closure with no egress category is a structural proof:
the admitted graph cannot express egress, so it cannot exfiltrate.
========================================================================

CR1  Admit with proof -- egress empty by construction
------------------------------------------------------------------------
  Submission: clean-analytic (reads the host dataset, reduces it to a
              byte count -- a pure analytic over Filesystem.Read).
  The host reads the verifier's surfaced effect closure (Q-067):
    surfaced closure (Ok)     = [Filesystem.Read]
    egress categories watched = [Network.Connect, Network.Receive, Network.Send, Process.Spawn]
    egress in closure         = []
  egress in closure = [] -> cannot exfiltrate, by construction.
  The admitted graph has no node that opens a socket or spawns a
  process; empty egress is a proof, not an observation of one run.
  Host decision: ADMITTED under grant {Filesystem.Read}
  Run over the real 23-byte dataset in a SECURE workspace:
    analytic result (byte count) = IntV(v=23)

CR2  Reject the exfiltrator -- egress in closure, beyond the grant
------------------------------------------------------------------------
  Submission: exfiltrator-analytic (the same clean analytic, PLUS a
              Net.Connect egress edge to collector.evil.example.com).
  Host computes the admission harm bound from the artifact alone:
    reachable effect closure  = [Filesystem.Read, Network.Connect]
    clean-room grant (C)      = {Filesystem.Read}
    closure(g) intersect C    = [Filesystem.Read]
    beyond the grant          = [Network.Connect]
  Host decision: REFUSED at admission (before any run)
  Network.Connect is structurally present in the closure and outside
  the clean-room grant, so the host refuses to admit -- the egress
  cannot be hidden, because it is a declared, reachable graph node.

CR3  Scope of the proof (honest caveat)
------------------------------------------------------------------------
  The non-exfiltration proof holds for the clean-room PROFILE: typed
  builtins whose effects are declared and surfaced in the closure. It
  does NOT extend to a Process.Spawn shell-out, whose child process's
  internal effects are opaque to the closure -- which is exactly why
  Process.Spawn is among the watched egress categories and a clean-room
  grant of {Filesystem.Read} would refuse it. The proof is about what
  the admitted graph can EXPRESS, not about what already-trusted host
  builtins do internally.

========================================================================
What this demonstrates: the effect closure as a positive structural
proof of a negative (non-exfiltration) -- the mode-2 reframing of the
Q-044 containment bound. NOT first-pass correctness of the submission,
nor its inference cost. The submissions are hand-authored stand-ins,
isolating admission from the agent-generation question.
========================================================================
```

## What this demonstrates and what it does not

This demonstration shows the effect closure used as a *positive structural proof*:
a submitted computation cannot exfiltrate because the admitted graph cannot express
an egress effect, and that is a property of the artifact decided before any
execution. It shows a clean submission admitted under that proof and run to a real
analytic result, and an exfiltrator refused at admission because its egress
category lands beyond the clean-room grant. Each of these is a property the shipped
runtime enforces, witnessed through the published embedding API.

It does **not** demonstrate the following, by design:

- **First-pass correctness of the submission.** Whether the analytic computes the
  statistic the partner intended is not checked here; the demonstration is about
  what the computation *cannot do* (exfiltrate), not whether it does the right
  thing. First-pass correctness belongs to the deferred Run 8 dynamic measurement.
- **Inference cost.** The tokens a partner agent spends to produce an admissible
  computation are not measured; the submissions are hand-authored precisely so the
  demonstration measures the host's admission decision in isolation from the
  agent-generation question.
- **A proof that survives untyped or opaque foreign code.** The no-exfiltration
  proof requires excluding from the clean-room profile any builtin whose internal
  effects the closure cannot see — chiefly a `Process.Spawn` shell-out, whose child
  process is opaque. The proof is sound for exactly the typed builtins whose
  declared effects surface in the closure (CR3). Admitting a new builtin into the
  profile is a separate trust decision about that builtin.

It is a demonstration of a mechanism, not a soundness proof of the language. As
stated in [`containment-results.md`](../../evaluation/containment-results.md),
soundness is a universal property argued from the mechanisms with executed
witnesses as spot-checks; this companion is one more executed witness, driving the
effect-closure mechanism through the clean-room admission boundary a host would
actually use.

## References

**Outgoing references:**
- [`demos/containment-host/README.md`](../containment-host/README.md) — the mode-1
  containment demonstration this reframes; CR2 reuses the same admission harm bound
  (`closure(g) ∩ C`, `beyondGrant`) that containment's S1 computes, and CR1 reads
  the same Q-067 surfaced closure that containment's S3 reads.
- [`evaluation/containment-results.md`](../../evaluation/containment-results.md) —
  the Q-044 containment measurement; the harm bound `closure(g) ∩ C ∩ B ∩ P` and
  the structural-safety claim this demonstration turns into a positive proof.
- [`open-questions.md`](../../open-questions.md#Q-067) — Q-067, the effect-closure
  surfacing CR1 consumes (`VerifyResult.Ok.rootClosure`) to read the verifier's own
  Handler-aware closure rather than re-deriving it.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade and `HostPolicy` (the SECURE workspace
  sandbox CR1 runs under) this host is built on.
- [`proposals/implemented/foreign-effect-projections.md`](../../proposals/implemented/foreign-effect-projections.md)
  — Q-039, the effect-projection rule that binds each declared `Filesystem.Read` /
  `Network.Connect` refinement to its call argument, so the closure the host reads
  is a faithful account of what the foreign code receives.

**Incoming references:**
- [`demos/README.md`](../README.md) — index entry.
- [`INDEX.md`](../../INDEX.md) — changelog entry (2026-06-13).
