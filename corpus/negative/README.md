# Negative Corpus

Curated near-miss programs for the adversarial verification battery (Q-066,
[`proposals/implemented/adversarial-verification.md`](../../proposals/implemented/adversarial-verification.md)).
Where the positive corpus witnesses what the pipeline admits, this directory
witnesses what it rejects — and that every rejection is a structured error, not
a raw exception. Entries are hand-written near-misses plus any trigger the
mutation fuzzer (`CorpusMutationFuzzTest`) discovers, preserved here as
permanent regressions.

## Convention

Each entry is a pair:

- `NN-name.json` — the input document. Usually dag-json; ingest-stage entries
  may be deliberately invalid JSON (for example `01-truncated-json.json`).
- `NN-name.expected.json` — the expected outcome:

```
{
  "stage": "ingest" | "verify" | "evaluate",
  "family": "<error class simple name>",
  "exhaustionKind": "<ExhaustionKind name>",   (optional; ResourceExhaustion only)
  "notes": "<what the entry demonstrates>"
}
```

`stage` names where the pipeline must reject. `family` is the simple class
name of the expected structured error at that stage: an `IngestError` subclass
(`Malformed`, `ResourceExhaustion`) for `ingest`, a `VerifyError` subclass for
`verify`, an `InterpretError` subclass for `evaluate`. Evaluation-stage entries
must ingest and verify cleanly, then fail when evaluated under an empty
capability context with `EvaluationLimits.DEFAULTS`.

`CorpusNegativeTest` (`impl-kotlin/corpus`) drives every pair: it asserts the
expected family at the expected stage, that no earlier stage rejects first, and
that every `.json` has its `.expected.json` (and vice versa).

## Exclusions

Negative entries carry no golden hashes — they are defined by their rejection,
so `golden-hashes.json` and `CorpusGoldenHashTest` exclude this directory
entirely (see the exclusion rule in [`../README.md`](../README.md)). The
positive-corpus drivers (`CorpusTest`, `CorpusHashingTest`,
`CorpusWarningSweepTest`) likewise do not look inside `negative/`.

## Coverage intent

The directory is curated, not exhaustive. Ingest entries cover the
representative malformed shapes (truncated text, wrong-typed fields, a depth
bomb caught by the Q-040 caps, unknown node types, dangling author ids).
Verify entries cover one representative per major `VerifyError` family that
the positive corpus does not already witness at corpus level (the positive
corpus witnesses `SchemaInvariantViolation`, `ProjectionMismatch`, and
`ManifestExportEffectMismatch`; everything else here was unit-test-only
before Q-066). Evaluate entries cover the runtime error families a verified
graph can still hit: capability denial, nested-pattern match failure, unbound
foreign targets. New `VerifyError` families added to the verifier should
gain an entry here when they are reachable from dag-json input.

## Entries added in the 2026-09-29 review wave

The independent code review of 2026-09-29 reproduced several admission and
runtime gaps with hand-built programs; each is preserved here as a permanent
regression in the same pair convention. Number ranges were reserved per work
stream, so gaps in the numbering are deliberate.

| Entry | Stage / family | What it demonstrates |
|-------|----------------|----------------------|
| 33-foreign-effect-under-declared | verify / `ForeignEffectUnderDeclared` | A `ForeignNode` binding `strand-builtin:Fs.Write` with an empty effect row. Before the builtin effect table it verified with an empty closure and wrote a file under an empty capability grant (review finding 1). The declared row must now cover the table's categories for every `strand-builtin:` target. |
| 34-type-noderef-open-target | verify / `NodeRefTargetMustBeClosed` | A NodeRef in type position whose target contains a `RecursiveSelf` bound outside it. Type-position NodeRefs used to inherit the caller's context and skip the closedness rule, so open fragments hashed to context-dependent values. |
| 36-duplicate-sum-case | ingest / `Malformed` | A `SumType` with two cases named `A`. The Sum analogue of entry 57; the verifier-level `DuplicateCaseName` rule remains for programmatically built stores. |
| 37-type-parameter-rebound | verify / `TypeParameterRebound` | An inner `TypeAbstraction` rebinding an in-scope `TypeParameter`, which let an identity function type as `String → Int` through capture-unsafe substitution (review verifier C2). |
| 38-manifest-latent-effects | verify / `ManifestExportEffectMismatch` | A `ModuleManifest` exporting a record whose field is an effectful wrapper while declaring no effects. The export surface now includes latent effects inside records and curried function rows. |
| 39-machine-effectful-initial-state | verify / `StateMachineEffectCoverageViolation` | A `StateMachine` whose `initialState` performs `Time.Now` while the machine declares only `StateMachine.Receive`; the coverage check now counts the initial state. |
| 40-handler-effects-narrowed-scope | verify / `CapabilityScopeUnsatisfiable` | A `Handler` whose handle function performs `Log.Write` wrapping a `CapabilityScope` narrowed to `Time.Now`. The interpreter runs the handler inside the narrowed context, so the verifier now charges the handler's effects there. |
| 43-string-repeat-allocation-bomb | evaluate / `ResourceExhaustion` (`AllocatedValues`) | An effect-free `String.Repeat` asking for a 2e9-character string under an empty grant. A single JVM allocation is invisible to the per-value counter, so the builtin checks the requested length against the host's per-call byte cap (`BuiltinLimits.maxBuiltinBytes`) before allocating. |
| 44-string-padleft-allocation-bomb | evaluate / `ResourceExhaustion` (`AllocatedValues`) | The same shape through `String.PadLeft`, whose output width is argument-chosen and built in one `StringBuilder`. |
| 53-lone-surrogate-string | ingest / `Malformed` | A `StringLit` whose value is the JSON escape for an unpaired UTF-16 high surrogate. It has no UTF-8 encoding; the JVM's lenient encoder used to substitute `?`, so the node hashed identically to `StringLit("?")` while being a different runtime value. |
| 54-graph-depth-let-chain | ingest / `ResourceExhaustion` (`GraphDepth`) | A chain of 600 nested Lets in flat JSON. The JSON depth cap never fires, but the encoder, hash walk, verifier and interpreter all recurse once per graph level and a chain of about 1,000 overflowed the JVM stack. Ingest now bounds graph depth (`EvaluationLimits.maxGraphDepth`, default 512). |
| 55-quoted-int-literal | ingest / `Malformed` | An `IntLit` whose value is the JSON string `"40"`. Ingest used to coerce quoted scalars and numeric references silently; every scalar and reference field is now checked for its JSON type. |
| 56-unknown-hash-prefix | ingest / `Malformed` | A cross-store NodeRef whose `targetHash` carries an unassigned multihash prefix. The prefix lookup used to escape as a raw `IllegalStateException`. |
| 57-duplicate-product-field | ingest / `Malformed` | `ProductType{x: Int, x: String}` with a matching `ProductValue`, read through `ProductFieldGet` into `Int.Add`. The verifier checked the value against one duplicate and typed the read against the other, so a verified program handed a String to `Int.Add`. Duplicate field and case names are now rejected at ingest and, for programmatically built stores, by the verifier. |
| 58-eventstream-zero-buffersize | ingest / `Malformed` | An `EventStream` declaring `bufferSize` 0. The canonical encoding uses 0 as the unset sentinel, so an explicit 0 hashed identically to an absent field while the verifier rejected it; ingest now rejects `bufferSize <= 0`. |
