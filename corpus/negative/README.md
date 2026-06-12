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
