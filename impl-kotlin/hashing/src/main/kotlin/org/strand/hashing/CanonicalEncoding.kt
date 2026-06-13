package org.strand.hashing

/**
 * Identity of the canonical encoding this implementation produces.
 *
 * Pre-1.0, breaking changes to the canonical encoding are batched into named
 * *epochs* (Q-062, `design/canonical-encoding.md` § Epoch log). The encoding
 * carries no in-band epoch marker — two epochs' encodings of the same graph
 * simply produce different hashes — so the epoch is declared out of band:
 * here, as the implementation's single source of truth, and as the top-level
 * `"epoch"` field of the conformance fixtures (`corpus/golden-hashes.json`,
 * `corpus/prelude-manifest.json`). Conformance tests assert that the fixture
 * epoch equals [EPOCH]; regeneration writes [EPOCH] into the fixtures.
 *
 * The independent Python encoder (`evaluation/conformance/independent_encoder.py`)
 * declares the mirrored constant `CANONICAL_ENCODING_EPOCH`; the two must
 * advance together when an epoch ships.
 *
 * Bump this constant only as part of an epoch commit: a dedicated proposal
 * naming every bundled encoding change, regenerated golden vectors, and an
 * Epoch log entry in `design/canonical-encoding.md`, all in the same pass.
 */
object CanonicalEncoding {

    /**
     * The encoding epoch implemented by [CanonicalEncoder] / [Hasher].
     *
     * Epoch 2 (2026-06-13) is the first deliberate break: the two gated
     * optional fields — FunctionType / ForeignNode `effectProjections`
     * (Q-039) and EventStream `source` (Q-046) — were normalized from their
     * epoch-1 gated-omit special cases to the uniform presence-prefix rule,
     * so every FunctionType, ForeignNode, and EventStream hash moved. N-048
     * RecursiveProjection tag 48, added additively under epoch 1, carries
     * forward unchanged; the Q-049 `TypeParameter.bound` decision was
     * deliberately not bundled and rides a future epoch.
     */
    const val EPOCH: Int = 2
}
