package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.authoring.Authoring
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Round-trip property for the Q-034 step 1 Layer A authoring layer:
 * for every program with both a `<n>-<name>.json` canonical dag-json
 * form and a `<n>-<name>.layer-a` Layer A form, compiling the Layer A
 * form through [Authoring.compileToDagJson] and ingesting it produces a
 * `FinalizedProgram` whose root hash equals the canonical form's root
 * hash. Equality at the root hash level means every node reachable from
 * the root has byte-identical canonical encoding, which is the strongest
 * achievable property without a recursive structural walk.
 *
 * The verifier is then run over both forms; both must verify cleanly
 * with the same inferred root type. This guarantees the Layer A author
 * receives the same well-formedness signal as a hand-author of the
 * canonical JSON.
 *
 * The first slice only covers Layer 1 node categories. Layer A files
 * are added under `src/main/resources/corpus/layer-a/`; the canonical
 * JSON files remain the authoritative form (the Layer A files are an
 * additional projection of the same graph, not a replacement).
 */
class LayerARoundTripTest {

    private data class Pair(val baseName: String)

    /**
     * Each pair is a `<baseName>.json` canonical file + a corresponding
     * `<baseName>.layer-a` projection. Adding a new pair requires:
     *   1. Authoring `corpus/layer-a/<baseName>.layer-a`.
     *   2. Adding the entry below.
     *   3. The existing canonical JSON file is unchanged.
     */
    private val pairs = listOf(
        Pair("01-int-literal"),
        Pair("02-identity-applied"),
        Pair("03-let-identity"),
        Pair("04-k-combinator"),
        Pair("05-s-combinator-typed"),
        Pair("06-let-polymorphic"),
        Pair("07-higher-order"),
        Pair("08-product-type-decl"),
        Pair("09-sum-type-decl"),
        Pair("10-noderef-shared"),
        Pair("11-higher-rank-apply"),
        Pair("12-effect-declared-and-granted"),
        Pair("13-capability-scope-narrow-then-call"),
        Pair("15-builtin-add"),
        Pair("17-builtin-compose-pure-and-effectful"),
        Pair("18-match-int-literal-with-wildcard"),
        Pair("19-match-on-comparison-result"),
        Pair("21-fixpoint-factorial"),
        Pair("22-fixpoint-sum-to-n"),
        Pair("23-product-construct-and-access"),
        Pair("25-option-some-unwrap"),
        Pair("27-result-ok-or-err"),
        Pair("31-recursive-list-head"),
        Pair("32-recursive-list-sum"),
        Pair("35-refined-logger-authorized-path"),
        Pair("36-handler-mock-time-now"),
        Pair("37-handler-captures-outer-let"),
        Pair("41-toggle-machine"),
        Pair("42-counter-machine"),
        Pair("47-async-multi-input-merge"),
        Pair("50-positive-int-schema-pass"),
        Pair("51-positive-int-schema-fail"),
        Pair("52-non-empty-list-schema-pass"),
    )

    @TestFactory
    fun roundTrip(): List<DynamicTest> = pairs.map { pair ->
        DynamicTest.dynamicTest(pair.baseName) {
            val canonicalText = loadResource("/corpus/${pair.baseName}.json")
            val layerAText = loadResource("/corpus/layer-a/${pair.baseName}.layer-a")

            // Compile Layer A to dag-json. Any AuthoringError surfaces here
            // as AuthoringException with a multi-error report.
            val compiledJson = Authoring.compileToDagJson(layerAText)

            // Ingest + finalize both forms and compare root hashes.
            val canonicalFinal = run {
                val ingest = JsonIngest.parse(canonicalText)
                Hasher(ingest.rawStore).finalize(ingest.root)
            }
            val compiledFinal = run {
                val ingest = JsonIngest.parse(compiledJson)
                Hasher(ingest.rawStore).finalize(ingest.root)
            }
            val canonicalRootHash = canonicalFinal.nodeIdToHash.getValue(canonicalFinal.root)
            val compiledRootHash = compiledFinal.nodeIdToHash.getValue(compiledFinal.root)
            assertEquals(
                canonicalRootHash,
                compiledRootHash,
                "${pair.baseName}: canonical root hash $canonicalRootHash differs from " +
                    "Layer A compiled root hash $compiledRootHash — round-trip integrity broken",
            )

            // Verify both forms produce equivalent well-formedness signal.
            val canonicalVerify = Verifier(canonicalFinal.store, canonicalFinal.hashToNodeId)
                .verify(canonicalFinal.root)
            val compiledVerify = Verifier(compiledFinal.store, compiledFinal.hashToNodeId)
                .verify(compiledFinal.root)
            assertTrue(canonicalVerify is VerifyResult.Ok) {
                "${pair.baseName}: canonical form failed verification: $canonicalVerify"
            }
            assertTrue(compiledVerify is VerifyResult.Ok) {
                "${pair.baseName}: Layer A compiled form failed verification: $compiledVerify"
            }
            assertEquals(
                (canonicalVerify as VerifyResult.Ok).rootType,
                (compiledVerify as VerifyResult.Ok).rootType,
                "${pair.baseName}: root types differ between canonical and Layer A forms",
            )
        }
    }

    private fun loadResource(resource: String): String {
        val stream = LayerARoundTripTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }
}
