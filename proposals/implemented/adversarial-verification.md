# Adversarial Verification Corpus

**Document:** `proposals/implemented/adversarial-verification.md`
**Status:** Implemented (landed 2026-06-12 in the Kotlin/JVM reference implementation; see the Implementation note)
**Date:** 2026-06-12
**Concerns:** [Q-066](../../open-questions.md#Q-066), [Q-044](../../open-questions.md#Q-044) (soundness argued, not benchmarked), [Q-052](../../open-questions.md#Q-052) (second-implementation conformance), [`design/canonical-encoding.md`](../../design/canonical-encoding.md), the verifier
**Scope:** medium

Every test in the suite is spec-aligned golden path; the verifier — the admission boundary the Q-044 harm bound rests on — has never been exercised by hostile input. This proposal adds three artifacts: a deterministic mutation fuzzer over the corpus, a curated negative corpus, and an independent canonical encoder in Python that recomputes the golden hashes from the normative specification alone. Together they convert the soundness argument's spot-check witnesses into systematic ones and discharge Q-052's second-implementation validation early.

## Implementation note (2026-06-12)

Implemented as proposed, in the proposed order (negative corpus, fuzzer, independent encoder) plus one follow-up commit. Full suite after the slice: 1586 tests, 0 failures, 2 pre-existing assumption skips.

**Negative corpus.** [`corpus/negative/`](../../corpus/negative/README.md) shipped with 26 paired `NN-name.json` / `NN-name.expected.json` entries: six malformed-ingest shapes (truncated JSON, wrong-typed scalar and object fields, a Q-040 depth bomb, unknown node type, dangling author id), thirteen verify-stage witnesses for `VerifyError` families that were unit-test-only, three evaluate-stage entries (`CapabilityViolation`, `NoMatchingCase`, `UnknownForeignTarget`), and four fuzzer-found triggers. `CorpusNegativeTest` asserts the expected family at the expected stage plus bidirectional pairing. Negative entries carry no golden hashes; `GoldenHashes.enumerateProgramFiles` and the golden file's exclusion rule exclude the directory.

**Fuzzer.** `CorpusMutationFuzzTest` implements the eight proposed operators under `kotlin.random.Random` re-seeded per program with the fixed seed 20260612; 25 mutants per program in CI, `-Dstrand.fuzzIterations` for deeper campaigns; validated clean at 1000 iterations per program (87,000 mutants). Mutant evaluation is contained (fixed clock, empty credential provider, temp-rooted filesystem sandbox, network default-deny, throwing exit handler) and runs on a 64 MiB-stack worker thread so the Q-040 `maxStackDepth` cap fires before the JVM native stack. Seven defect classes were found and fixed, each with its trigger preserved: (1) raw kotlinx `SerializationException` / `IllegalArgumentException` escaping ingest on truncated or wrong-shaped JSON, translated to `IngestError.Malformed`; (2) structural category contracts the canonical encoder depends on (binder positions, name-keyed children, `MatchCase.pattern`) crashing the hash walk, enforced as ingest pass 2.5; (3) `ParameterDecl` referenced from non-binder positions (including effect-projection categories) hitting the encoder's no-standalone-encoding error, covered by the same pass; (4) reference cycles crashing the hash walk with `StackOverflowError`, rejected by ingest pass 2.6 over the hash-relevant edge set (`childNodeIds` minus `VarRef.binder` back-edges, plus raw NodeRef and manifest targets and inline-hashed projection categories); (5) negative `EventStream.bufferSize` crashing `encodeUint` (the field is a CBOR uint per the specification), rejected at ingest; (6) local NodeRefs with root-unreachable or intrinsic (`TypeParameter`, `RecursiveSelf`) targets crashing finalize, rejected by ingest pass 2.7; (7) type-confused builtin arguments — the graph-supplied `foreignType` is not checked against the builtin's real argument contract — crashing evaluation with raw `ClassCastException`, now translated to `BuiltinContractViolation` at all four interpreter dispatch sites and both VM sites, parallel to the Q-048 `IllegalArgumentException` precedent. One find was held under a documented expected-failure exclusion rather than fixed at the boundary: a local NodeRef in a type position crashed the encoder's type-position walk even though the specification defines `T(c)` as `H(c)` for that case and the verifier resolves through it; the `CanonicalEncoder` fix landed in a follow-up commit only after the independent encoder shipped (preserving the independence rule), the exclusion list returned to empty, and negative entry 26 pins the trigger. No existing hash changed.

**Independent encoder.** [`evaluation/conformance/independent_encoder.py`](../../evaluation/conformance/independent_encoder.py) implements the full canonical encoding from [`design/canonical-encoding.md`](../../design/canonical-encoding.md) prose alone. All 87 golden program hashes were reproduced on the first complete run. Specification corrections required: **zero** — the spec as committed at revision 2026-06-10 fully determines the bytes. The perturbed-golden disagreement path was verified loud (nonzero exit naming the program and both hashes). The golden file's `layerA` section is not recomputed (Layer A compilation is an authoring-pipeline concern; the compiled forms exercise the same encoder through the equivalent `.json` programs); the independence rule and the spec-defect protocol are documented in [`evaluation/conformance/README.md`](../../evaluation/conformance/README.md).

**Deviations from the proposal text.** First, `BLAKE3-team/BLAKE3` ships no `reference_impl/reference_impl.py`; the vendored implementation is `oconnor663/pure_python_blake3` (CC0-1.0, commit `3fde72e`), the Python port designated by the official repository's README, validated against all 35 official test vectors at vendoring time and re-validated on four vectors at every encoder startup. Second, the duplicate-node-id operator inserts a copy of a node under a fresh key rather than emitting a textual duplicate JSON key, because the JSON object model silently keeps the last duplicate — a text-level duplicate is unobservable past the parser. Third, the "hang" half of the mutant invariant is bounded by the Q-040 wall-clock budget under `DEFAULTS` rather than by a test-side timeout.

## 1. Problem statement

The Q-044 containment result rests on the verifier rejecting or bounding everything it admits, and the conformance result (Q-052) rests on the canonical encoding being implementable from its specification. Both are supported today exclusively by curated positive evidence: ~1,470 tests and 87 corpus programs, all authored to be correct. No fuzzed graph, no malformed dag-json document, and no second encoder has ever tested either boundary. The failure modes this leaves open are precisely the ones that matter for an admission boundary: a malformed input that crashes ingest with a raw exception instead of a structured error (an agent-facing reliability defect and a denial-of-service primitive), a mutation the verifier admits whose evaluation then violates an invariant the type system was supposed to guarantee (an unsoundness), and a specification ambiguity that only surfaces when someone implements the encoding without reading the Kotlin source.

## 2. Recommended approach

### 2.1 Deterministic mutation fuzzer

A Kotlin test (`CorpusMutationFuzzTest`) that, for every corpus program, applies a fixed number of single mutations to the dag-json document under a seeded `kotlin.random.Random` — fixed seed in CI for reproducibility, iteration count overridable via a system property for deeper local runs. Mutation operators: delete a field; replace a node reference with another program node's id; replace a node reference with a dangling id; corrupt a category or type-name string; truncate a list; perturb an integer literal; duplicate a node id; introduce a reference cycle. The invariant asserted for every mutant: ingest-plus-verify either rejects with a structured `IngestError`/`VerifyError`, or admits — and an admitted mutant must evaluate under `DEFAULTS` limits with full capabilities to either a `Value` or a structured `InterpretError`. Any other `Throwable`, hang (bounded by the existing wall-clock limit), or stack overflow is a test failure. Every failure found during development is fixed and its trigger preserved in the negative corpus, so the fuzzer's discoveries become permanent regressions.

### 2.2 Negative corpus

A `corpus/negative/` directory of curated near-miss programs — hand-written and fuzzer-discovered — each a dag-json file paired with an expected outcome (error family at ingest, verify, or evaluation). A `CorpusNegativeTest` drives them exactly as `CorpusTest` drives the positive corpus. Seed contents: one representative per existing `VerifyError` family that lacks a corpus-level witness (most are unit-tested but not corpus-tested), the malformed-ingest shapes (truncated JSON, wrong-typed fields, depth-bomb within Q-040's ingest caps), and every fuzzer find. Negative programs carry no golden hashes — they are defined by their rejection.

### 2.3 Independent canonical encoder

A Python implementation (`evaluation/conformance/independent_encoder.py`, stock Python 3, no third-party dependencies) that ingests corpus dag-json, performs the full canonical encoding — framing, category tags, presence prefixes, de Bruijn frames, metadata exclusion, name-keyed sorts — and recomputes every root hash in `corpus/golden-hashes.json`, exiting nonzero on any mismatch. BLAKE3 comes from the official reference implementation (`reference_impl.py`, CC0/public-domain), vendored with provenance noted in its header. The implementation rule is the point of the exercise: the encoder is written **from [`design/canonical-encoding.md`](../../design/canonical-encoding.md) alone, without reading the Kotlin encoder**. Where the prose underdetermines a byte, the ambiguity is a specification defect: the spec is corrected in the same pass and the correction recorded. Success means Q-052's "second-implementation validation falls due with Q-017 step 2" is discharged years early at the encoding layer, where the portability hazards live; the Rust VM then validates evaluation semantics, not bytes.

## 3. Worked consequence

After this lands, the claim "the verifier rejects malformed and hostile graphs with structured errors" is backed by a reproducible adversarial battery rather than by the absence of counterexamples, and the claim "the canonical encoding is implementable from its specification" is backed by a second implementation agreeing on every shipped hash. Both claims feed directly into the corpus's lead-claim posture: Q-044's soundness section can cite the fuzzer invariant and the negative corpus as systematic witnesses.

## 4. Verifier rules

None new by design. Any rule change this work forces is a found defect, fixed under its own justification with its trigger preserved in the negative corpus.

## 5. Runtime semantics

None new.

## 6. Test scenarios

1. **Fuzzer invariant holds** — the full seeded battery over all corpus programs produces only structured rejections, bounded evaluations, or structured runtime errors.
2. **Fuzzer reproducibility** — same seed, same mutants, same outcomes; the failure report names program, seed, and operator for direct replay.
3. **Negative corpus drives** — every `corpus/negative/` entry produces its expected error family at its expected stage.
4. **Ingest hostility** — truncated JSON, wrong-typed fields, and a depth bomb within the Q-040 caps each yield structured `IngestError`s.
5. **Encoder agreement** — the Python encoder reproduces every root hash in `golden-hashes.json`.
6. **Encoder disagreement is loud** — a deliberately perturbed local golden file makes the encoder exit nonzero naming the program and the divergent hash.
7. **Spec sufficiency** — any prose ambiguity met during encoder development is resolved by a spec correction committed in the same pass (recorded in the implementation note; zero is the ideal count and a meaningful result either way).

## 7. Tradeoffs and open questions

**Deferred intentionally:**

- **Hostile Layer A fuzzing** — grammar-level fuzzing of the authoring surface is worthwhile but a different harness (text mutation against the parser); deferred so this slice stays at the admission boundary. The Layer F work (Q-061) should inherit the same deferral note.
- **Coverage-guided fuzzing** — structure-aware random mutation is the right first tool; instrumentation-guided fuzzing (JQF-style) is a heavier dependency decision left for after the cheap battery has been mined.
- **Continuous deep-fuzz** — CI runs the fixed-seed battery; long-running randomized campaigns are a manual invocation documented alongside the iteration-count property.

**Real research questions:**

- *Admitted-mutant semantics* — a mutant that verifies and evaluates is not necessarily a defect (many mutations produce different-but-valid programs). The invariant deliberately checks boundedness and structured outcomes, not semantic equivalence; defining a stronger oracle for "admitted but wrong" is open and probably needs the Q-021 task framing.

## 8. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `impl-kotlin/corpus/.../CorpusMutationFuzzTest.kt` | seeded mutation operators + invariant driver, iteration property | Medium |
| `corpus/negative/` + `CorpusNegativeTest.kt` | curated entries with expected outcomes; fuzzer finds appended | Medium |
| `evaluation/conformance/independent_encoder.py` | spec-only canonical encoder + dag-json ingest + golden recomputation | Large |
| `evaluation/conformance/blake3_reference.py` | vendored CC0 reference implementation with provenance header | Small |
| `evaluation/conformance/README.md` | invocation, independence rule, spec-defect protocol | Small |
| `design/canonical-encoding.md` | corrections for any prose ambiguity found (count recorded) | Small |

**Order of work.** Negative-corpus scaffolding first (the fuzzer files findings into it), fuzzer second, encoder third (it is independent and the longest pole; its only coupling is the spec-correction protocol).

**Not in this slice.** Layer A/Layer F text fuzzing, coverage-guided fuzzing, evaluation-semantics differential testing against a second runtime.

## References

**Outgoing references:**
- [`design/canonical-encoding.md`](../../design/canonical-encoding.md) — the normative target the independent encoder implements from
- [`evaluation/containment-results.md`](../../evaluation/containment-results.md) — the soundness argument this systematizes
- [`corpus/golden-hashes.json`](../../corpus/golden-hashes.json) — the conformance vectors recomputed
- [`open-questions.md`](../../open-questions.md) — Q-044, Q-052, Q-066

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-066 points at this proposal
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — Known gaps section
- [`corpus/negative/README.md`](../../corpus/negative/README.md)
- [`evaluation/conformance/README.md`](../../evaluation/conformance/README.md)
