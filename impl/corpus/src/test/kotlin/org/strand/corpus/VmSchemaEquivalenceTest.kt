package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.bytecode.Lowerer
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Value
import org.strand.schema.SchemaChecker
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import org.strand.vm.Vm

/**
 * Q-017 step 1 Track A.5: schema invariant dispatch through the VM.
 *
 * For every corpus program with at least one Schema-typed value, the
 * test runs the [SchemaChecker] twice — once with the default
 * interpreter-backed invariant evaluator, once with a VM-backed one —
 * and asserts the two [SchemaCheckResult]s match (same violation set,
 * same deferred-diagnostic set).
 *
 * The VM-backed evaluator follows the same protocol as the interpreter's
 * default: for each (invariantBodyId, staticValue), lower the body via
 * [Lowerer] (the body is a Lambda — the verifier's SchemaInvariantBody
 * rules guarantee this), evaluate the lowered chunk to a VmClosure,
 * apply that closure to the static value. The Bool the closure returns
 * is the verdict.
 *
 * Programs covered: every schema corpus program (50-56) including the
 * two designed-to-fail programs (51 PositiveInt with -1, 53 NonEmptyList
 * with Nil, 56 duplicate JSON keys) — the violations must match across
 * both engines.
 */
class VmSchemaEquivalenceTest {

    private data class SchemaPair(val baseName: String)

    private val schemas = listOf(
        SchemaPair("50-positive-int-schema-pass"),
        SchemaPair("51-positive-int-schema-fail"),
        SchemaPair("52-non-empty-list-schema-pass"),
        SchemaPair("53-non-empty-list-schema-fail"),
        SchemaPair("54-json-value-primitives"),
        SchemaPair("55-json-object-unique-keys"),
        SchemaPair("56-json-object-duplicate-keys-fail"),
        // Q-026 blessed-library expansion — PlainTextDocument + NonEmptyText.
        SchemaPair("58-plain-text-document"),
        SchemaPair("59-non-empty-text-pass"),
        SchemaPair("60-non-empty-text-fail"),
        // Q-026 blessed-library expansion — MarkdownDocument + NonEmptyMarkdown.
        SchemaPair("61-markdown-document"),
        SchemaPair("62-non-empty-markdown-pass"),
        SchemaPair("63-non-empty-markdown-fail"),
    )

    @TestFactory
    fun vmEvaluatorMatchesInterpreterEvaluator(): List<DynamicTest> = schemas.map { pair ->
        DynamicTest.dynamicTest(pair.baseName) {
            val text = loadResource("/corpus/${pair.baseName}.json")
            val ingest = JsonIngest.parse(text)
            val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
            val verifyResult = Verifier(finalized.store, finalized.hashToNodeId)
                .verify(finalized.root)
            assertTrue(verifyResult is VerifyResult.Ok) {
                "${pair.baseName}: verifier failed: $verifyResult"
            }

            // Reference: interpreter-backed schema check.
            val interpResult = SchemaChecker(
                store = finalized.store,
                hashToNodeId = finalized.hashToNodeId,
                verifyResult = verifyResult as VerifyResult.Ok,
            ).check()

            // Mirror: VM-backed schema check. The invariantEvaluator hook
            // lowers each invariant body once on first call (memoized
            // per-bodyId), then applies the resulting VmClosure to the
            // static value.
            val vmTableCache = HashMap<Int, Pair<Vm, Any>>()
            val vmResult = SchemaChecker(
                store = finalized.store,
                hashToNodeId = finalized.hashToNodeId,
                verifyResult = verifyResult,
                invariantEvaluator = { bodyId, value ->
                    val (vm, callable) = vmTableCache.getOrPut(bodyId.value) {
                        val table = Lowerer(finalized.store, finalized.hashToNodeId).lower(bodyId)
                        val vm = Vm(table)
                        val callable = vm.evaluate()
                        vm to callable
                    }
                    vm.applyClosure(callable, listOf(value), caps = emptySet())
                },
            ).check()

            assertEquals(
                interpResult.violations.toSet(),
                vmResult.violations.toSet(),
            ) {
                "${pair.baseName}: schema violations differ between interpreter and VM"
            }
            assertEquals(
                interpResult.deferred.toSet(),
                vmResult.deferred.toSet(),
            ) {
                "${pair.baseName}: schema deferred diagnostics differ between interpreter and VM"
            }
        }
    }

    private fun loadResource(resource: String): String {
        val stream = VmSchemaEquivalenceTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }
}
