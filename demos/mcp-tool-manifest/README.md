# Verifiable tool-capability manifest demonstration {#mcp-tool-manifest-demo}

**Document:** `demos/mcp-tool-manifest/README.md`
**Status:** Executable demonstration of a machine-checked tool-capability manifest
**Last revised:** 2026-06-13

## What this demonstration is

An MCP server advertises its capabilities as prose: a description a client reads
and trusts. A Strand-backed tool bundle instead ships an N-046 `ModuleManifest`
whose per-export declared effects are *machine-checked against the code*. The
verifier admits the manifest only when each export's declared effects exactly
equal that export's effect surface — the function's declared effect row for a
function export, the construction closure for a value export. The distinctive
property this demonstration shows is that the manifest is a *verified statement*
of what each tool can do, not a claim: a tool that writes the filesystem cannot
ship inside a manifest that says it only reads, and a tool that does nothing
effectful cannot be made to look as though it does. Because the manifest is
content-addressed, a change to any export's declared capability is visibly a
different hash.

This is distinct from the existing capability demonstrations. The plugin-host
demonstration ([`demos/plugin-host/`](../plugin-host/README.md)) shows a host
attenuating and enforcing a *grant* at admission and at runtime; the containment
demonstration ([`demos/containment-host/`](../containment-host/README.md)) shows
whole-program harm bounding and isolation. This one is narrower and earlier in
the lifecycle: it is about the *manifest as a contract* — the verified statement
a publisher attaches to a tool bundle, certified at admission, before any grant
or run is in question.

The host is an ordinary JVM caller of the shipped embedding surface — the Q-054
`StrandRuntime` facade. It introduces no language feature, no node category, no
encoding change, and no verifier rule: the N-046 ModuleManifest certification it
exercises already ships. Every property the demonstration claims is one the
verifier enforces, and the assertion net (`McpToolManifestDemoTest`) protects
each one: the M2 rejection is the structured `ManifestExportEffectMismatch` the
verifier actually produces, and the M3 hashes are the ones the canonical encoder
actually computes.

The manifest programs are hand-authored canonical dag-json. Layer A has no
MFT/MEX code for `ModuleManifest` yet (the inline export sub-object list has no
one-line-per-node representation), so the programs are authored directly, exactly
as the corpus exemplars 79 and 80 are. Hand-authoring them isolates the
*verifier's* manifest certification — the subject of this demonstration — from
the separate question of how an agent generates programs, which the Q-021 cost
measurement and the deferred Run 8 dynamic study address.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:mcpToolManifestDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.McpToolManifestDemoTest"
```

The driver `McpToolManifestDemo` and the test `McpToolManifestDemoTest` live in
the `:runtime` test source set
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and share one body of
scenario code, so the printed demonstration and the regression net cannot
diverge. They stay in `:runtime` because they compile against the runtime
modules. The driver loads the committed canonical dag-json from
[`programs/`](programs/) through the test classpath (`runtime/build.gradle.kts`
copies the directory in via `processTestResources`), so the artifact the verifier
admits is the content-addressed graph, not a human-facing projection.

## The scenarios

### M1 Manifest as machine-checked contract

The bundle `manifest-tools` exports two tools: a read-only `fs.lookup` (a Lambda
wrapping `strand-builtin:Fs.Read`, declaring `Filesystem.Read`) and a pure
`text.format` (a Lambda that declares no effects). The manifest declares
`fs.lookup` as `{Filesystem.Read}` and `text.format` as `{}`. It verifies
precisely because each export's declared effects exactly equal its effect
surface: the verifier infers each export under an empty scope, computes the
surface (the function's declared effect row), and requires set-equality. The host
then renders, per export, the displayName and the verified effect set — a line
the form of "this tool can only do X". Because the manifest verified, that line is
not a claim a reader has to trust; it is the export's actual capability, checked
against the code. The contrast the transcript records: an MCP server's
capabilities are prose; here they are derived from the code and the manifest is
admitted only when they match.

### M2 Honesty enforced — under-declaration rejected

The bundle `manifest-underdeclared` is the same `fs.lookup` export — its code
still reaches `Fs.Read` — but the manifest declares its effects as `{}`. The
exact-equality admission rule rejects this: the declared set `{}` does not equal
the actual surface `{Filesystem.Read}`, so the verifier reports
`ManifestExportEffectMismatch` and the manifest is not admitted. The host refuses
to publish a manifest whose declared capabilities are purer than the code's. The
asymmetry the rule defends is the point — a consumer reading the manifest must not
be able to believe an export is more constrained than it is, because that belief
is what a capability decision would rest on. Over-declaration (claiming an effect
the code does not incur) is rejected symmetrically for precision;
`ModuleManifestVerifierTest` covers that direction, and corpus 80 is the
under-declaration exemplar this scenario adapts.

### M3 Hash-pinned contract — a capability change is a different hash

The host pins the root hash of `manifest-tools` and contrasts it with
`manifest-tools-elevated`, a manifest that differs only in the `fs.lookup` tool's
declared capability: that tool now performs `Filesystem.Write` instead of
`Filesystem.Read`, and the manifest honestly declares the change. Both manifests
verify — both are honest about their code — so this is not a rejection scenario;
it is a content-addressing scenario. The two manifests' root hashes differ,
because the declared capability set is part of the manifest's canonical encoding.
A capability change is therefore visibly a different hash: an auditor who pins a
manifest hash will see any later change to what a tool can do as a hash mismatch,
without inspecting the bundle's contents. (The export `displayName` is metadata
and is excluded from the hash, so a rename alone does not move the hash; the
declared effect set and the export targets do.)

## Transcript

The transcript below is the output of `./gradlew :runtime:mcpToolManifestDemo -q`.
The two M3 root hashes are content-addressed and stable across runs.

```
========================================================================
Strand -- verifiable tool-capability manifest (MCP-tool demonstration)
An N-046 ModuleManifest whose per-export declared effects are
machine-checked against the code: the manifest is a verified
statement of what each tool CAN do, not prose a client must trust.
========================================================================

M1  Manifest as machine-checked contract
------------------------------------------------------------------------
  Bundle: manifest-tools (a read-only lookup tool + a pure format tool).
  Host decision: ADMITTED (verified)
  The manifest verified precisely because each export's declaredEffects
  exactly equals its effect surface -- so each line below is a verified
  capability statement, checked against the code, not a claim:
    fs.lookup -> can only: Filesystem.Read
    text.format -> pure (no effects)
  Contrast: an MCP server advertises its capabilities as prose a client
  trusts by reading. Here the capabilities are derived from the code and
  the manifest is admitted only when they match.

M2  Honesty enforced -- under-declaration rejected
------------------------------------------------------------------------
  Bundle: manifest-underdeclared (the lookup tool's code surface is
          {Filesystem.Read}, but the manifest claims {} -- it under-
          declares, hiding what the tool can do).
  Host decision: REJECTED at admission (verify)
    verifier: ManifestExportEffectMismatch(at=#13, exportIndex=0, target=1eca9df60ae0edbb9a3177b333304697f0e5322f93548df9bb47af894354cedaa6, declared=[], actual=[#2])
  The host refuses to publish a manifest whose declared capabilities do
  not match the code. A consumer reading the manifest cannot be misled
  into believing an export is purer than it is.

M3  Hash-pinned contract -- a capability change is a different hash
------------------------------------------------------------------------
  manifest-tools          lookup effects = [Filesystem.Read]
    root hash             = 1eb3fb1e8048c3e097f7a155...
  manifest-tools-elevated lookup effects = [Filesystem.Write]
    root hash             = 1ebdd7741b5728d532ad58cc...
  both verify (both honest) = true
  hashes differ             = true
  The two manifests differ only in the lookup tool's declared
  capability set; the content-addressed manifest hash makes that change
  visibly a different hash -- tamper-evident by construction.

========================================================================
What this demonstrates: a manifest whose declared per-export effects are
machine-checked to equal the code's effect surface, with capability
changes surfaced as hash changes. What it does NOT: it checks the
DECLARED surface equals the code's surface -- it does not bound the
transitive effects of a tool that shells out (Process.Spawn is opaque);
displayName is metadata, not hashed; the manifests are hand-authored;
and it is NOT first-pass correctness or cost.
========================================================================
```

## What this demonstrates and what it does not

This demonstration shows a tool-capability manifest that is *verified*, not
merely *declared*: the verifier admits the manifest only when each export's
declared effects exactly equal that export's effect surface, so the manifest is a
machine-checked statement of what each tool can do. It shows under-declaration
rejected at admission, and it shows that a change to a tool's declared capability
is surfaced as a change to the content-addressed manifest hash.

It does not demonstrate the following, and the limits are stated honestly so the
claim is neither more nor less than the mechanism delivers.

The manifest checks that an export's *declared* effect surface equals its *code's*
effect surface within the verified graph. It does not bound the transitive
effects of a tool that escapes the graph. A tool whose surface is `{Process.Spawn}`
declares exactly that, and the manifest certifies the declaration — but
`Process.Spawn` is an opaque boundary: what the spawned process does is outside
Strand's effect closure, so the manifest does not and cannot describe it. The
guarantee is "the declaration matches the code's effect surface", not "the tool's
real-world effects are bounded by the declaration" once the code shells out. This
is the same boundary the effect system has throughout: the closure is sound over
what the graph expresses, and a foreign call that leaves the graph is a leaf the
closure cannot see past.

The export `displayName` is metadata and is excluded from the canonical encoding.
A manifest's hash pins its export targets and their declared effect sets, not
their human-facing names — renaming `fs.lookup` to `lookup` alone does not move
the hash. The names are for the reader; the verified content is the effects and
the targets.

The manifest programs are hand-authored canonical dag-json, not agent-generated.
Hand-authoring isolates the verifier's certification — the subject here — from the
agent-generation question. This demonstration claims nothing about first-pass
correctness (whether a generated manifest is the one its author intended) or
inference cost (the tokens an agent spends to produce an admissible manifest);
those belong to the deferred Run 8 dynamic measurement recorded in
[`evaluation/dynamic-results.md`](../../evaluation/dynamic-results.md).

## References

**Outgoing references:**
- [`proposals/cross-store-federation.md`](../../proposals/cross-store-federation.md)
  — Q-043, the cross-store-federation proposal whose § 4.3–4.4 / § 5.4–5.5
  introduce N-046 `ModuleManifest` and the per-export effect-surface admission
  rule this demonstration exercises.
- [`corpus/79-module-manifest-with-effects.json`](../../corpus/79-module-manifest-with-effects.json)
  — the N-046 ModuleManifest accept exemplar (pure + effectful exports whose
  declared effects match) this demonstration's M1 adapts.
- [`corpus/80-manifest-effect-mismatch-rejected.json`](../../corpus/80-manifest-effect-mismatch-rejected.json)
  — the N-046 ModuleManifest reject exemplar (an under-declaring export rejected
  with `ManifestExportEffectMismatch`) this demonstration's M2 adapts.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade this host is built on.
- [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md)
  — the effect-category and effect-closure model the manifest's declared effects
  are checked against.

**Incoming references:**
- [`demos/README.md`](../README.md) — the demonstrations index.
- [`INDEX.md`](../../INDEX.md) — changelog entry (2026-06-13).
