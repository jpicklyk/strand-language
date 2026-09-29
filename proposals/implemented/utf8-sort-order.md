# UTF-8 Name-Sort Order

**Document:** `proposals/implemented/utf8-sort-order.md`
**Status:** Implemented 2026-07-05
**Question:** [Q-074](../../open-questions.md#Q-074) (registers)
**Related:** [Q-062](../../open-questions.md#Q-062) (encoding epochs), [Q-052](../../open-questions.md#Q-052) (conformance vectors), [ADR-003](../../decisions/ADR-003-content-addressing.md) (content addressing), [ADR-008](../../decisions/ADR-008-compilation-target.md) (compilation target)
**Drafted:** 2026-07-05

## Problem

The canonical encoding sorts three name-keyed edge lists — `ProductType.fields` and `ProductValue.fields` by `fieldName`, `SumType.cases` by `caseName` — into a canonical order before hashing, so two graphs that differ only in declaration order of a product's fields or a sum's cases hash identically. Through epoch 2 that sort was lexicographic over the name's **UTF-16 code units**: the reference encoder wrote Kotlin's `sortedBy { name }`, and the independent Python encoder mirrored it with `s.encode("utf-16-be")`.

UTF-16 code-unit order is a JVM-ism. It is the natural ordering of a Kotlin/JVM string and of no other representation in the encoding: every other place a string appears in the canonical bytes, it appears as UTF-8 (`bytes(utf8(s))`), and every hash-sorted set already sorts by unsigned UTF-8-derived byte value. The two string orders diverge only for names mixing a character in U+E000..U+FFFF with a supplementary character (≥ U+10000): in UTF-16 code-unit order the supplementary character (surrogate pair beginning `0xD800`) sorts before U+FF21, while in UTF-8 byte order (`F0 90 80 80`) it sorts after (`EF BC A1`). The anticipated non-JVM implementations — the Rust bytecode VM of [ADR-008](../../decisions/ADR-008-compilation-target.md) foremost — compare `str`/`&[u8]` as UTF-8 bytes natively; under the epoch-2 rule each would have had to carry a special-case UTF-16 comparison used nowhere else, purely to reproduce this one JVM ordering. That is a latent portability tax on a correctness-critical path, incurred before any second implementation exists to pay it.

## Recommended approach

Switch the three name-keyed sorts to **UTF-8 byte lexicographic order** — equivalently, Unicode code-point order — and ship the change as **encoding epoch 3** under the [Q-062](../../open-questions.md#Q-062) pre-1.0 epoch policy. UTF-8 byte order is the cross-implementation-neutral choice: it matches the encoding the string content already carries, reuses the same unsigned-byte discipline the hash-sorted sets apply to multihashes, and is the order every non-JVM string type produces without special handling. No in-band marker changes; two epochs' encodings of a graph simply hash differently, and the epoch is declared out of band as the top-level `"epoch"` field of the conformance fixtures and the `CanonicalEncoding.EPOCH` / `CANONICAL_ENCODING_EPOCH` constants.

The load-bearing correctness property is that the current corpus is entirely ASCII, where UTF-16 code-unit order and UTF-8 byte order coincide exactly. So no golden hash value moves: only the epoch field advances. The epoch machinery still requires regenerating the conformance fixtures and bumping the constants, but the regeneration is verified to touch nothing but the epoch field (plus the one added divergence-pin program).

## Where it lives

The sort itself is three call sites in `impl-kotlin/hashing/src/main/kotlin/org/strand/hashing/CanonicalEncoder.kt` (`encodeProductType`, `encodeSumType`, `encodeProductValue`), each rewritten from `sortedBy { name }` to `sortedWith(compareBy(utf8LexicographicComparator) { name })` over a single new private `Comparator<String>` that compares `toByteArray(Charsets.UTF_8)` as unsigned bytes (delegating to the existing `byteArrayLexicographicComparator` the effect/capability sorts already use). The epoch constant `CanonicalEncoding.EPOCH` bumps 2 → 3 in the same module. The independent Python encoder (`evaluation/conformance/independent_encoder.py`) mirrors the change in `name_sort_key` (`s.encode("utf-8")`) and `CANONICAL_ENCODING_EPOCH`. The normative rule lives in `design/canonical-encoding.md` § Set-like and positional edge lists, with the epoch-3 entry recorded in that document's Epoch log.

## Scope

Small. A single ordering comparator, an epoch bump mirrored across the Kotlin and Python encoders, a golden and prelude-manifest regeneration that moves no hash, one new verify-only corpus program, and the normative spec text plus this proposal. Hash-neutral for the existing corpus by construction; no verifier, interpreter, VM, runtime, or node-algebra change.

## Implementation note

Built on the agent worktree branch `worktree-agent-ad5d2a4fdd3fdf54c` in `impl-kotlin/`, committed task-by-task.

**Encoder.** `CanonicalEncoder.kt` gained a private `utf8LexicographicComparator: Comparator<String>` (UTF-8 bytes, unsigned, via the pre-existing `byteArrayLexicographicComparator`) and the three name-keyed `sortedBy { name }` calls became `sortedWith(compareBy(utf8LexicographicComparator) { name })`. A grep of the file confirmed there are exactly three name-keyed sorts; the remaining `sorted`/`sortedWith` uses are over 33-byte multihash byte arrays (`Lambda.effects`, `FunctionType.effects`, `ForeignNode.effects`, `StateMachine.effects`, `Application.effectInstances`, `CapabilityScope.capabilities`, `Schema.invariants`, each manifest export's `declaredEffects`) — already unsigned-byte order, so unchanged. `CanonicalEncoding.EPOCH` bumped 2 → 3.

**Python mirror.** `name_sort_key` returns `s.encode("utf-8")` (docstring updated to record that epoch ≤ 2 used UTF-16-BE); `CANONICAL_ENCODING_EPOCH` bumped 2 → 3.

**Zero-hash-moved verification.** `corpus/golden-hashes.json` was regenerated via `CorpusGoldenHashTest -Dstrand.regenerateGoldenHashes=true` and `corpus/prelude-manifest.json` (plus the bundled prelude snapshot) via `PreludeModuleConformanceTest -Dstrand.regeneratePreludeModule=true`, then both tests rerun without the flags. A structured before/after diff of both fixtures confirmed the load-bearing property: the golden file's `"epoch"` advanced 2 → 3, the single entry `92-utf8-sort-divergence.json` was added, and **zero existing hash values moved**; the prelude manifest changed only its `"epoch"` field; the bundled prelude snapshot (`impl-kotlin/authoring/src/main/resources/org/strand/authoring/prelude-module.json`) is epoch-independent dag-json and stayed byte-identical (it was restored to HEAD after the regeneration write touched only its line endings).

**Divergence pins.** Two tests witness the exact case where the orders differ. A new `:hashing` byte-trace in `CanonicalEncodingSpecTest` (`ProductType field sort is UTF-8 byte order, diverging from UTF-16`) builds a ProductType whose field names are `"Ａ"` (U+FF21) and `"𐀀"` (U+10000), sanity-pins that UTF-8 byte order places U+FF21 first while Kotlin's natural (UTF-16 code-unit) order reverses it, and asserts the encoder emits the two field hashes in UTF-8 order. A new verify-only corpus program `92-utf8-sort-divergence.json` — a Lambda over a ProductType with the same two field names — carries a golden entry, so the independent Python encoder (which recomputes every corpus hash) pins cross-implementation agreement on the divergence case. Corpus 92 is registered as a verify-only case in `CorpusTest`.

**Test pins.** `CliFederationTest`'s hard-coded `incHashHex` is all-ASCII (`inc` export), so it kept its epoch-2 value through epoch 3; its comment was updated to record that. The epoch-assertion tests (`CorpusGoldenHashTest`, `PreludeModuleConformanceTest`, `PersistentStoreTest`) read `CanonicalEncoding.EPOCH` rather than a literal, so they tracked the bump without edits. The `CanonicalEncoder`/`CanonicalEncodingSpecTest` comments that mention "epoch 2" describe the still-in-force epoch-2 presence-prefix rule and were left unchanged.

**Conformance run.** `python evaluation/conformance/independent_encoder.py --golden` reports encoding epoch 3, 92 programs checked, 92 matched, 0 mismatches — every golden reproduced independently, including corpus 92.

**Full validation.** Full `.\gradlew.bat test --rerun-tasks --no-build-cache` from `impl-kotlin/`: 2388 tests, 0 failures, 3 skipped (aggregated from the JUnit `build/test-results` XML per the Windows build-cache caveat).

**Deviations.** None from the recommended approach.

## References

**Outgoing references:**
- [`design/canonical-encoding.md`](../../design/canonical-encoding.md) — the normative encoding this proposal amends (§ Set-like and positional edge lists, § Epoch log)
- [`decisions/ADR-003-content-addressing.md`](../../decisions/ADR-003-content-addressing.md) — content addressing and the multihash format the sort feeds
- [`decisions/ADR-008-compilation-target.md`](../../decisions/ADR-008-compilation-target.md) — the non-JVM compilation targets whose native UTF-8 comparison motivates the change
- [`proposals/implemented/encoding-epochs.md`](encoding-epochs.md) — the Q-062 pre-1.0 epoch policy this ships under
- [`evaluation/conformance/independent_encoder.py`](../../evaluation/conformance/independent_encoder.py) — the second implementation that pins cross-implementation agreement
- [`open-questions.md`](../../open-questions.md) — Q-074, Q-062, Q-052
