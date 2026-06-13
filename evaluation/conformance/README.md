# Independent Canonical-Encoder Conformance

This directory holds the Q-066 independent implementation of Strand's canonical
encoding ([`proposals/implemented/adversarial-verification.md`](../../proposals/implemented/adversarial-verification.md)
§ 2.3): a stock-Python-3, stdlib-only encoder that ingests corpus dag-json,
performs the full canonical encoding — framing, category tags, canonical CBOR,
presence prefixes and default gating, de Bruijn binder references, name-keyed
and hash-sorted edge lists, metadata exclusion — and recomputes root content
hashes, checked against the committed conformance vectors in
[`corpus/golden-hashes.json`](../../corpus/golden-hashes.json).

Its existence discharges the encoding half of Q-052's second-implementation
validation: the claim "the canonical encoding is implementable from its
specification" is backed by a second implementation agreeing on every shipped
hash, not by the absence of counterexamples.

## Files

- [`independent_encoder.py`](independent_encoder.py) — the encoder and driver.
- [`blake3_reference.py`](blake3_reference.py) — vendored pure-Python BLAKE3
  (CC0; provenance in the file header). The encoder validates it against four
  official BLAKE3 test vectors at startup, before trusting it; the full
  35-vector battery was run at vendoring time.

## Invocation

From anywhere (paths are resolved relative to the script):

```
python evaluation/conformance/independent_encoder.py --golden
```

recomputes every entry in the golden file's `programs` section and exits
nonzero naming each mismatching program with both hashes. A specific corpus
directory may be passed after `--golden`. Single files print their root hash:

```
python evaluation/conformance/independent_encoder.py corpus/01-int-literal.json
```

The golden file's `layerA` section is not recomputed: those entries pin the
authoring pipeline (Layer A text to canonical form), which is a Kotlin-side
concern; their compiled canonical graphs exercise the same encoder paths that
the `programs` section covers.

The golden file's top-level `epoch` field (Q-062, pre-1.0 encoding epochs) is
checked before any hash comparison: the encoder declares
`CANONICAL_ENCODING_EPOCH`, mirroring the Kotlin constant
`org.strand.hashing.CanonicalEncoding.EPOCH`, and exits nonzero on a mismatch
rather than reporting cross-epoch hashes as failures. The two constants
advance together when an epoch ships; the Python side is then extended from
the revised specification text per the independence rule below.

## The independence rule

`independent_encoder.py` was written from
[`design/canonical-encoding.md`](../../design/canonical-encoding.md) alone —
the normative prose and its documented byte traces — without reading the
Kotlin encoder sources (`impl-kotlin/hashing/CanonicalEncoder.kt`,
`Hasher.kt`). That is the point of the artifact: it witnesses that the
specification, not the reference implementation, is the contract. Maintenance
must preserve the rule. When the encoding changes, extend this encoder from
the revised specification text; do not port Kotlin code into it. A maintainer
who has read the Kotlin encoder should treat changes here with the same care
as a cleanroom reimplementation.

## The spec-defect protocol

When this encoder and the reference implementation disagree on a hash:

1. Re-read the relevant section of `design/canonical-encoding.md`. If the
   Python code misreads an unambiguous sentence, fix the Python.
2. If the prose genuinely underdetermines a byte — two reasonable readings
   produce different bytes — that is a **specification defect**, regardless
   of which implementation is "right". Correct the prose in
   `design/canonical-encoding.md` in the same pass (the Kotlin behavior is
   normative per the document's status line), then fix the Python to the
   clarified text, and record the correction.
3. Black-box probing is permitted: author minimal programs through the CLI
   (`strand verify`, which prints nothing about bytes but admits/rejects) or
   inspect `golden-hashes.json` for narrower programs that isolate the
   divergent construct. Reading the Kotlin encoder is not.

The count of spec corrections is a headline result of the exercise either
way. **Initial implementation (2026-06-12): all 87 golden program hashes
reproduced with zero specification corrections** — the encoder was written
against the spec as committed at `design/canonical-encoding.md` revision
2026-06-10 and agreed on the first complete run.

## Results

| Date | Spec revision | Programs checked | Mismatches | Spec corrections |
|------|---------------|------------------|------------|------------------|
| 2026-06-12 | 2026-06-10 | 87 | 0 | 0 |
