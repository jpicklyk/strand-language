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
 * Reverse-projection round-trip test for Q-036, step 1.
 *
 * For each corpus dag-json program: translate to a [LayerADocument] via
 * [Authoring.projectFromDagJson], compile the rendered Layer A text back
 * through the forward pipeline, and assert canonical root-hash equality
 * with the original. This is the primary correctness gate the proposal
 * specifies — `forward_compile(render(translate(canonical))) == canonical`.
 *
 * Step 1 scope: every program in the canonical seed corpus that the Layer A
 * grammar can express. Sugar codes (IF, WHEN), compact list forms
 * (PARAM_LIST `name:typeRef`, FIELD_LIST `name=ref`), and elaboration-aware
 * field omission are not yet exercised — those land in subsequent steps.
 */
class LayerAReverseRoundTripTest {

    /**
     * Every canonical seed-corpus program (excluding `.events.json` event
     * inputs for state-machine drivers, which are not standalone programs).
     */
    private val corpusResources = listOf(
        "/corpus/01-int-literal.json",
        "/corpus/02-identity-applied.json",
        "/corpus/03-let-identity.json",
        "/corpus/04-k-combinator.json",
        "/corpus/05-s-combinator-typed.json",
        "/corpus/06-let-polymorphic.json",
        "/corpus/07-higher-order.json",
        "/corpus/08-product-type-decl.json",
        "/corpus/09-sum-type-decl.json",
        "/corpus/10-noderef-shared.json",
        "/corpus/11-higher-rank-apply.json",
        "/corpus/12-effect-declared-and-granted.json",
        "/corpus/13-capability-scope-narrow-then-call.json",
        "/corpus/14-multi-effect-lambda.json",
        "/corpus/14-pure-lambda-with-overdeclared-effect.json",
        "/corpus/15-builtin-add.json",
        "/corpus/16-builtin-time-now-under-capability.json",
        "/corpus/17-builtin-compose-pure-and-effectful.json",
        "/corpus/18-match-int-literal-with-wildcard.json",
        "/corpus/19-match-on-comparison-result.json",
        "/corpus/20-match-variable-binding.json",
        "/corpus/21-fixpoint-factorial.json",
        "/corpus/22-fixpoint-sum-to-n.json",
        "/corpus/23-product-construct-and-access.json",
        "/corpus/24-product-sum-fields-via-lambda.json",
        "/corpus/25-option-some-unwrap.json",
        "/corpus/26-option-none-default.json",
        "/corpus/27-result-ok-or-err.json",
        "/corpus/28-safe-divide-success.json",
        "/corpus/29-safe-divide-by-zero.json",
        "/corpus/30-string-concat.json",
        "/corpus/31-recursive-list-head.json",
        "/corpus/32-recursive-list-sum.json",
        "/corpus/33-refined-network-connect.json",
        "/corpus/34-refined-wildcard-port.json",
        "/corpus/35-refined-logger-authorized-path.json",
        "/corpus/36-handler-mock-time-now.json",
        "/corpus/37-handler-captures-outer-let.json",
        "/corpus/38-handler-nested-innermost-wins.json",
        "/corpus/39-handler-itself-performs-effect.json",
        "/corpus/40-handler-fires-through-fixpoint.json",
        "/corpus/41-toggle-machine.json",
        "/corpus/42-counter-machine.json",
        "/corpus/43-counter-with-overflow-output.json",
        "/corpus/44-request-response-echo.json",
        "/corpus/45-bank-account-machine.json",
        "/corpus/46-async-single-machine-counter.json",
        "/corpus/47-async-multi-input-merge.json",
        "/corpus/48-async-supervisor-one-for-one.json",
        "/corpus/49-async-tagged-output-list.json",
        "/corpus/50-positive-int-schema-pass.json",
        "/corpus/51-positive-int-schema-fail.json",
        "/corpus/52-non-empty-list-schema-pass.json",
        "/corpus/53-non-empty-list-schema-fail.json",
        "/corpus/54-json-value-primitives.json",
        "/corpus/55-json-object-unique-keys.json",
        "/corpus/56-json-object-duplicate-keys-fail.json",
        "/corpus/57-dropoldest-overflow.json",
        "/corpus/58-plain-text-document.json",
        "/corpus/59-non-empty-text-pass.json",
        "/corpus/60-non-empty-text-fail.json",
        "/corpus/61-markdown-document.json",
        "/corpus/62-non-empty-markdown-pass.json",
        "/corpus/63-non-empty-markdown-fail.json",
    )

    @TestFactory
    fun `every corpus program round-trips through Layer A reverse projection`(): List<DynamicTest> =
        corpusResources.map { resource ->
            DynamicTest.dynamicTest(resource.substringAfterLast('/')) {
                val canonicalText = loadResource(resource)

                // Forward direction: canonical text → translator → renderer → text.
                val layerAText = Authoring.projectFromDagJson(canonicalText)

                // Re-compile the rendered Layer A back to dag-json.
                val recompiledJson = Authoring.compileToDagJson(layerAText)

                // Both must hash-equal at the root.
                val originalHash = ingestHash(canonicalText, resource)
                val recompiledHash = ingestHash(recompiledJson, "$resource (recompiled)")
                assertEquals(originalHash, recompiledHash) {
                    "$resource: round-trip changed canonical root hash.\n" +
                        "  original: $originalHash\n" +
                        "  recompiled: $recompiledHash\n" +
                        "  rendered Layer A:\n$layerAText"
                }
            }
        }

    private fun ingestHash(text: String, label: String): org.strand.core.Hash {
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "$label failed verification: $verify" }
        return finalized.nodeIdToHash.getValue(finalized.root)
    }

    private fun loadResource(resource: String): String {
        val stream = LayerAReverseRoundTripTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }
}
