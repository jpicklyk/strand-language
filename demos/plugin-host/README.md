# Plugin host demonstration {#plugin-host-demo}

**Document:** `demos/plugin-host/README.md`
**Status:** Executable demonstration of fine-grained capability attenuation and the confused-deputy defense
**Last revised:** 2026-06-13

## What this demonstration is

A host application loads untrusted plugins — agent-generated Strand programs it
has never seen — and grants each plugin a precisely-scoped slice of its own
authority. The distinctive property this demonstration shows is that a confused
or malicious plugin cannot escalate the delegated capability, by construction:
neither by argument drift, nor by reaching outside its scope, nor by relying on
the host's broad authority.

This is distinct from the existing containment demonstration
([`demos/containment-host/`](../containment-host/README.md)), which shows coarse
whole-program tenant isolation — a separate sandbox and capability set per
tenant. Here a single host that holds broad authority delegates a narrowed slice
to one plugin and the plugin provably cannot climb back up. The subject is
fine-grained capability attenuation and delegation, not isolation between
mutually-distrusting tenants.

The host is an ordinary JVM caller of the shipped embedding surface — the Q-054
`StrandRuntime` facade, the Q-031 refined `CapabilitySet` grant, and the Q-064
`DenialReport`. It introduces no language feature, no node category, no encoding
change, and no verifier rule. Every property the demonstration claims is one the
runtime enforces, and the assertion net (`PluginHostDemoTest`) protects each one:
every asserted denial or rejection is the one the verifier or interpreter
actually produces, not a staged stand-in.

The plugin programs are hand-authored stand-ins for agent submissions. They live
as Layer A source plus compiled canonical dag-json under [`programs/`](programs/).
Hand-authoring them isolates the host's attenuation — the subject of this
demonstration — from the separate question of how an agent generates programs,
which the Q-021 cost measurement and the deferred Run 8 dynamic study address.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:pluginHostDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.PluginHostDemoTest"
```

The driver `PluginHostDemo` and the test `PluginHostDemoTest` live in the
`:runtime` test source set
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and share one body of
scenario code, so the printed demonstration and the regression net cannot
diverge. They stay in `:runtime` because they compile against the runtime
modules. The driver loads the committed canonical dag-json from
[`programs/`](programs/) through the test classpath
(`runtime/build.gradle.kts` copies the directory in via `processTestResources`),
so the artifact the host admits is the content-addressed graph, not the
human-facing projection.

## The scenarios

Each scenario exercises one way a plugin might try to escalate a delegated
capability, and the specific Strand mechanism that prevents it.

### C1 Legitimate scoped operation

The plugin `plugin-a-scoped-write` is granted `Filesystem.Write` to its own
directory and writes its state file there. Its authored `EffectDecl` pins the
write refinement to the same value the call passes, so the Q-039 projection
agreement holds and the program admits cleanly; the grant covers exactly the
path the plugin writes, so the write succeeds and the file lands inside the
plugin's own directory. This is the happy path of delegation — authority handed
to the plugin and used exactly as intended. The transcript writes a real file
into a host-assigned temporary directory so the success is observable, not
asserted.

### C2 Argument-drift confused deputy, blocked at verification

The plugin `plugin-drift-escalation` attempts the classic confused-deputy drift.
Its authored `EffectDecl` declares the benign path `/plugins/A/state.json` — the
value a policy auditor or a capability check would inspect — while the value it
actually hands the foreign `Fs.Write` call is a sensitive host path,
`/etc/shadow`. In a conventional host the privileged file writer would be tricked
into using its authority on the attacker-chosen target: the checked value and the
consumed value diverge.

This is the gap Q-039 closed. The effect projection on the `Fs.Write` binding
pins the `Filesystem.Write` refinement to the call's argument by construction
(`sources: [ArgRef(0)]`), so the verifier requires the authored `EffectDecl`'s
parameter to be the *same node* as `arguments[0]`. They differ here, so the host
rejects the program at admission with the structured `ProjectionMismatch` error
— before any execution. The checked value cannot diverge from the value the
foreign code receives.

### C3 Out-of-scope escalation, denied at runtime

The plugin `plugin-a-out-of-scope-write` is granted `Filesystem.Write` to its own
slice (`/plugins/A`) and is honest about its effect — the authored `EffectDecl`
pins the refinement to the same literal the call passes, so it admits cleanly.
But the path it targets, `/plugins/B/secret.json`, lies outside its granted slice
— it reaches into another plugin's directory. Whether that is harmful depends on
a value (the path) and the grant the host handed it: a runtime question, not a
structural one. At the foreign-call boundary the runtime evaluates the requested
path against the held grant; no granted pattern covers `/plugins/B/secret.json`,
so the call is denied with a `RefinementViolation` carrying a structured
`DenialReport` (category, requested versus held, denying node, phase). Because
the projection is honest, the value the capability check sees is the value the
foreign code would receive — the denial is on the real target.

### C4 Attenuation below the host

The plugin `plugin-a-reach-sibling` writes to a sibling plugin's directory — a
path the host itself is fully authorized to write, because the host holds broad
authority over the whole plugin tree. The point of C4 is that the host does not
hand the plugin its own broad authority: it runs the plugin under a grant
narrowed to the plugin's own slice. The transcript first shows the host, with its
broad wildcard grant, genuinely writing the sibling path (a real file), so the
attenuation that follows is real and not host incapacity. It then runs the same
plugin under the narrowed grant; the sibling write is denied with a
`RefinementViolation` at the capability check, before any IO. Least privilege is
enforced by the grant the runtime applies, not by the plugin's good behavior.

**How the narrowing is achieved, stated precisely.** The narrowing in C4 is done
at the grant / policy level: the host constructs a narrower `CapabilitySet` and
hands it to `StrandRuntime.run`. It is *not* the N-036 `CapabilityScope` node.
`CapabilityScope` narrows which effect *categories* survive into a subgraph — its
`intersect` preserves each retained category's refinement patterns verbatim — so
it cannot tighten a wildcard-path `Filesystem.Write` grant down to a
single-subtree one. Refinement-narrowing `CapabilityScope` is the deferred Q-031
follow-up. The C4 claim is therefore specific: least-privilege delegation of a
*refined* capability is enforced by the grant the host applies.

The category-level attenuation `CapabilityScope` genuinely provides is shown
separately as C4'. A plugin subgraph wrapped in a `CapabilityScope` that retains
only the Read category, but whose body performs a Write, is rejected at admission
with `CapabilityScopeUnsatisfiable`: the verifier proves the body's effect closure
exceeds the narrowed scope, so the program cannot even be admitted. This is the
N-036 node operating exactly as it ships — category-level narrowing, enforced
structurally before execution, which is a stronger guarantee than a runtime
denial. It is still category-level: the scope removes the Write category
wholesale, not a path refinement.

## Transcript

The transcript below is the output of `./gradlew :runtime:pluginHostDemo -q`. The
host-assigned temporary paths in C1 and C4 vary per run and are abbreviated here
as `<tmp>/...`; the logical paths in C2 and C3 are fixed in the committed plugin
artifacts.

```
========================================================================
Strand -- plugin host (fine-grained capability attenuation &
confused-deputy defense)
A host delegates a precisely-narrowed capability to an untrusted
plugin; the plugin provably cannot escalate it.
========================================================================

C1  Legitimate scoped operation -- the happy path of delegation
------------------------------------------------------------------------
  Plugin: plugin-a-scoped-write (writes its own state file).
  Host grant: Filesystem.Write confined to the plugin's directory.
    plugin admitted (verified) = true
    write authorized + ran     = value IntV(v=14)
    file landed in own dir     = true
    path                       = <tmp>/plugins/A/state.json
  Delegated authority used exactly as intended.

C2  Argument-drift confused deputy -- blocked at verification
------------------------------------------------------------------------
  Plugin: plugin-drift-escalation. Its authored EffectDecl declares
          the benign path /plugins/A/state.json (the value a
          capability check inspects) while the call argument is
          /etc/shadow (the value Fs.Write would receive).
  Host decision: REJECTED at admission (verify)
    verifier: ProjectionMismatch(at=#9, categoryIndex=0, sourceIndex=0, expected=ArgRef(index=0), actualParam=#5)
  This is the gap Q-039 closed: the effect projection binds the
  checked value to the call argument by construction, so the checked
  value cannot diverge from the value the foreign code receives. A
  conventional host -- a privileged file writer with no projection --
  would have been tricked into writing the attacker-chosen target.

C3  Out-of-scope escalation -- denied at the foreign-call boundary
------------------------------------------------------------------------
  Plugin: plugin-a-out-of-scope-write. Honest projection (admits
          cleanly), but targets /plugins/B/secret.json -- another
          plugin's directory. Host grant: Write to /plugins/A only.
    plugin admitted (verified) = true
  Host decision: DENIED at runtime (RefinementViolation)
    DenialReport: category=Filesystem.Write requested=[/plugins/B/secret.json] held=[Filesystem.Write{/plugins/A/state.json}, Filesystem.Write{/plugins/A/log.txt}] node=#8 phase=expression
  The refined capability does not cover the requested path, so the
  plugin cannot reach outside its slice.

C4  Attenuation below the host -- least-privilege delegation
------------------------------------------------------------------------
  Plugin: plugin-a-reach-sibling. Targets a sibling plugin's dir.
    host HOLDS broad authority  = [Filesystem.Write{*}  (host could write <tmp>/c4-plugins/B/config.json)]
    host DID reach the sibling  = true (real write)
    plugin RUNS under grant     = [Filesystem.Write{<tmp>/c4-plugins/A/state.json}]
  Host decision: DENIED at runtime (RefinementViolation)
    DenialReport: category=Filesystem.Write requested=[<tmp>/c4-plugins/B/config.json] held=[Filesystem.Write{<tmp>/c4-plugins/A/state.json}] node=#9 phase=expression
  The host could write the sibling dir, but the plugin -- handed a
  grant narrowed to its own slice -- cannot. The denial fires at the
  capability check, BEFORE any IO. Least privilege is enforced by the
  grant the runtime applies, not by the plugin behaving.

  Narrowing technique, stated honestly: this is done at the
  GRANT/POLICY level (the host constructs a narrower CapabilitySet),
  NOT via the N-036 CapabilityScope node. CapabilityScope narrows
  which effect CATEGORIES survive into a subgraph, not a path
  refinement -- refinement-narrowing CapabilityScope is tracked as
  Q-068 (the deferred Q-031 follow-up). The category-level narrowing
  CapabilityScope does provide is shown next.

C4'  Companion -- N-036 CapabilityScope category-level narrowing
------------------------------------------------------------------------
  Plugin: a writer wrapped in a CapabilityScope that retains only
          Filesystem.Read, but whose body performs a Write.
  Host decision: REJECTED at admission (verify)
    verifier: CapabilityScopeUnsatisfiable(at=#11, missing=[#4])
  The CapabilityScope removes the Write CATEGORY from the inner
  subgraph; the verifier proves the body's effect closure exceeds the
  retained set and rejects the program BEFORE execution. This is the
  N-036 node operating as shipped -- category-level narrowing,
  enforced structurally, stronger than a runtime denial.

========================================================================
What this demonstrates: a delegated capability cannot be escalated by
a confused or malicious plugin -- not by argument drift (C2, Q-039),
not by reaching outside its slice (C3, Q-031 refinement match), not
by relying on the host's broad authority (C4, grant-level narrowing;
C4' N-036 category-level CapabilityScope). It does NOT show first-pass
correctness or cost. Plugin programs are hand-authored stand-ins for
agent submissions.
========================================================================
```

## What this demonstrates and what it does not

This demonstration shows that delegated authority cannot be escalated by a
confused or malicious plugin, by construction. It shows a host handing a plugin a
precisely-narrowed capability and the plugin failing to climb back up by every
route the threat model admits: argument drift is caught at admission by the Q-039
projection check, an out-of-scope request is denied at the foreign-call boundary
by the Q-031 refinement match, and a request for a path the host itself can reach
is denied because the host attenuated the grant before delegating. Each is a
property the shipped runtime enforces, witnessed under the published embedding
API, with the real verifier error or `DenialReport` captured.

The contrast with the conventional confused deputy is the point of C2. The
classic confused deputy is a privileged helper tricked into using its authority
on an attacker-chosen target — a compiler that writes a billing file because the
caller named it as the output path, an `Fs.Write` helper induced to write
`/etc/shadow` because the caller chose that argument. The vulnerability lives in
the gap between the value the helper checks against its policy and the value it
actually acts on. Q-039 makes that gap structurally impossible at the
foreign-call boundary: the capability-check value is *derived from* the actual
call argument by the binding's projection, so the two cannot diverge. The helper
cannot be confused because there is no separate "declared" value to confuse it
with.

It does not demonstrate first-pass correctness — whether an agent's submission is
the program the agent intended — nor inference cost, the tokens an agent spends
to produce an admissible program. Those belong to the deferred Run 8 dynamic
measurement recorded in
[`evaluation/dynamic-results.md`](../../evaluation/dynamic-results.md), which
requires agent-emission sampling through the model API and is a distinct study.
The plugin programs here are hand-authored precisely so the demonstration
measures the host's attenuation in isolation from the agent-generation question.

The C4 distinction is load-bearing and stated honestly throughout: the
refined-capability narrowing in C4 is grant-level (a narrower `CapabilitySet`),
not the N-036 `CapabilityScope` node, because `CapabilityScope` narrows effect
*categories* rather than refinements within a category. The category-level
narrowing `CapabilityScope` does provide is shown separately as C4' and is
enforced at admission. The demonstration claims neither more nor less than the
mechanisms deliver.

## References

**Outgoing references:**
- [`proposals/implemented/foreign-effect-projections.md`](../../proposals/implemented/foreign-effect-projections.md)
  — Q-039, the effect-projection rule that closes the argument-drift confused-deputy
  gap (C2): the verifier requires authored EffectDecls at projected call sites to
  match structurally, and the interpreter synthesizes the capability-check value
  from the actual evaluated argument.
- [`proposals/implemented/refinement-lattice-capability-matching.md`](../../proposals/implemented/refinement-lattice-capability-matching.md)
  — Q-031, how a refined capability matches or denies a concrete request (C3, C4).
- [`open-questions.md`](../../open-questions.md#Q-068)
  — Q-068, the deferred refinement-narrowing `CapabilityScope` extension the C4
  narrative distinguishes from grant-level narrowing.
- [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md)
  — N-036 `CapabilityScope` (category-level narrowing, C4') and the confused-deputy
  mitigation framing.
- [`proposals/implemented/capability-denial-observability.md`](../../proposals/implemented/capability-denial-observability.md)
  — Q-064, the structured `DenialReport` captured in C3 and C4.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade and per-instance `HostPolicy` this host is
  built on.
- [`demos/containment-host/README.md`](../containment-host/README.md) — the coarse
  whole-program tenant-isolation demonstration this one is deliberately distinct
  from.

**Incoming references:**
- [`demos/README.md`](../README.md) — the demonstrations index.
- [`INDEX.md`](../../INDEX.md) — changelog entry (2026-06-13).
