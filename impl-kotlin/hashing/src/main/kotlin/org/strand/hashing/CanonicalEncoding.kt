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

    /** The encoding epoch implemented by [CanonicalEncoder] / [Hasher]. */
    const val EPOCH: Int = 1
}
