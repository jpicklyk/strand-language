package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.VerifyError
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * N-046 ModuleManifest end-to-end corpus exercises (Q-043 step 3b):
 *  - corpus 79 (`module-manifest-with-effects`) verifies cleanly: a manifest
 *    exporting a pure `Int.identity` Lambda (`declaredEffects: []`) and an
 *    effectful `Fs.writeFile` Lambda (`declaredEffects: ["Filesystem.Write"]`),
 *    each with declared effects exactly matching its effect surface.
 *  - corpus 80 (`manifest-effect-mismatch-rejected`) is a negative program:
 *    the same effectful export is under-declared (`declaredEffects: []`). The
 *    verifier raises [VerifyError.ManifestExportEffectMismatch] before any
 *    run — exact-equality admission rejects under-declaration for safety.
 *
 * The end-to-end accept path (verify + run to Unit) and the hash-determinism
 * path for corpus 79 are covered by [CorpusTest] and [CorpusHashingTest];
 * this suite pins the precise error variant for the rejection direction.
 */
class CorpusManifestTest {

    private fun verifyCorpus(resource: String): VerifyResult {
        val stream = CorpusManifestTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        val text = stream.bufferedReader().readText()
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    @Test
    fun `corpus 79 module-manifest-with-effects verifies cleanly`() {
        val verify = verifyCorpus("/corpus/79-module-manifest-with-effects.json")
        assertTrue(verify is VerifyResult.Ok,
            "expected Ok verification for corpus 79, got $verify")
    }

    @Test
    fun `corpus 80 manifest-effect-mismatch is rejected with ManifestExportEffectMismatch`() {
        val verify = verifyCorpus("/corpus/80-manifest-effect-mismatch-rejected.json")
        val failed = verify as? VerifyResult.Failed
            ?: error("expected Failed verification for corpus 80, got $verify")
        val mismatch = failed.errors.firstOrNull { it is VerifyError.ManifestExportEffectMismatch }
            as? VerifyError.ManifestExportEffectMismatch
            ?: error("expected ManifestExportEffectMismatch in errors, got ${failed.errors}")
        assertEquals(0, mismatch.exportIndex, "the single export is at index 0")
        // Under-declaration: declared is empty, actual carries the write effect.
        assertTrue(mismatch.declared.isEmpty(),
            "expected empty declared set, got ${mismatch.declared}")
        assertEquals(1, mismatch.actual.size,
            "expected exactly one actual effect (Filesystem.Write), got ${mismatch.actual}")
    }
}
