package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.schema.SchemaChecker
import org.strand.verifier.VerifyError
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Drives the Layer 7 step 1 schema corpus end-to-end:
 *  - every program ingests cleanly,
 *  - every program verifies without errors at the type-checking pass,
 *  - the SchemaChecker pass either produces no violations (pass programs)
 *    or produces a precise SchemaInvariantViolation pointing at the
 *    failing invariant (fail programs).
 *
 * These programs are isolated to `CorpusSchemaTest` rather than added to
 * the main `CorpusTest` driver because they exercise a different end-to-
 * end pipeline (parse → finalize → verify → schema-check, vs. parse →
 * finalize → verify → interpret).
 */
class CorpusSchemaTest {

    private data class Case(
        val resource: String,
        val expectViolation: Boolean,
        /**
         * Author-id of the invariant we expect to see in the violation
         * (only meaningful when [expectViolation] is true).
         */
        val expectedInvariantAuthorId: String? = null,
        val notes: String = "",
    )

    private val cases = listOf(
        Case(
            resource = "/corpus/50-positive-int-schema-pass.json",
            expectViolation = false,
            notes = "IntLit(5) claimed as PositiveInt verifies and the invariant evaluates true.",
        ),
        Case(
            resource = "/corpus/51-positive-int-schema-fail.json",
            expectViolation = true,
            expectedInvariantAuthorId = "positiveInvariant",
            notes = "IntLit(-3) claimed as PositiveInt fails the x_positive invariant.",
        ),
        Case(
            resource = "/corpus/52-non-empty-list-schema-pass.json",
            expectViolation = false,
            notes = "Cons(1, Nil) claimed as NonEmptyList verifies; the Match invariant returns true.",
        ),
        Case(
            resource = "/corpus/53-non-empty-list-schema-fail.json",
            expectViolation = true,
            expectedInvariantAuthorId = "nonEmptyInvariant",
            notes = "Nil claimed as NonEmptyList fails the non_empty invariant — the Cons case does not match.",
        ),
        Case(
            resource = "/corpus/54-json-value-primitives.json",
            expectViolation = false,
            notes = "JsonNumber(42) wrapped in the JsonValue schema (no invariants); first program in the blessed JSON library.",
        ),
        Case(
            resource = "/corpus/55-json-object-unique-keys.json",
            expectViolation = false,
            notes = "Three-entry JsonObject with distinct keys claimed as UniqueKeyJsonObject; the Fixpoint+Match unique_keys invariant returns true.",
        ),
        Case(
            resource = "/corpus/56-json-object-duplicate-keys-fail.json",
            expectViolation = true,
            expectedInvariantAuthorId = "uniqueKeysInvariant",
            notes = "Two-entry JsonObject where both entries use the key 'name'; the unique_keys invariant evaluates false and produces SchemaInvariantViolation.",
        ),
    )

    @TestFactory
    fun corpus(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.resource.substringAfterLast('/')) {
            val stream = CorpusSchemaTest::class.java.getResourceAsStream(case.resource)
                ?: error("missing resource ${case.resource}")
            val text = stream.bufferedReader().readText()
            val ingest = JsonIngest.parse(text)
            val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
            val verifyResult = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
            assertTrue(
                verifyResult is VerifyResult.Ok,
                "verifier failed for ${case.resource}: $verifyResult"
            )
            verifyResult as VerifyResult.Ok

            val checker = SchemaChecker(finalized.store, finalized.hashToNodeId, verifyResult)
            val schemaResult = checker.check()

            if (case.expectViolation) {
                assertTrue(
                    schemaResult.hasViolations,
                    "${case.resource}: expected a SchemaInvariantViolation but got none. " +
                        "Deferred=${schemaResult.deferred}"
                )
                val expectedId = ingest.nameMap[case.expectedInvariantAuthorId]
                assertNotNull(
                    expectedId,
                    "${case.resource}: expected invariant author-id " +
                        "'${case.expectedInvariantAuthorId}' is not in the name map"
                )
                val match = schemaResult.violations.firstOrNull { it.invariant == expectedId }
                assertNotNull(
                    match,
                    "${case.resource}: no violation with invariant=$expectedId; got ${schemaResult.violations}"
                )
            } else {
                assertEquals(
                    emptyList<VerifyError.SchemaInvariantViolation>(),
                    schemaResult.violations,
                    "${case.resource}: expected no violations but got ${schemaResult.violations}"
                )
            }
        }
    }
}
