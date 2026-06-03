package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.TypeExpr
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * Q-047 (Layer 7 step 2) — runtime schema enforcement end-to-end.
 *
 * The verify-time [org.strand.schema.SchemaChecker] (step 1) only decides
 * invariants on statically-known values; a value computed at runtime
 * (here, an `Int.Sub` Application flowing into a `PositiveInt` parameter)
 * is surfaced as a non-failing `SchemaInvariantDeferred` and never
 * checked. Step 2 threads the verifier's `SchemaType` value-flow
 * obligations into the interpreter so the invariant is enforced when the
 * value materialises.
 *
 * Both corpus programs **verify** (the dynamic value defers at verify
 * time). With obligations installed, the interpreter enforces at runtime:
 * the pass program runs to the checked value; the violation program
 * raises [InterpretError.SchemaInvariantViolation]. Driven here rather
 * than in [CorpusTest] because [CorpusTest] constructs its interpreter
 * without obligations (the pre-Q-047 behaviour), so it would not exercise
 * runtime enforcement.
 */
class CorpusRuntimeSchemaTest {

    private data class Loaded(
        val root: NodeId,
        val store: org.strand.core.NodeStore,
        val hashToNodeId: Map<org.strand.core.Hash, NodeId>,
        val obligations: Map<NodeId, TypeExpr.SchemaType>,
    )

    private fun load(resource: String): Loaded {
        val text = CorpusRuntimeSchemaTest::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.readText()
            ?: error("missing resource $resource")
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok, "verifier failed for $resource: $verify")
        verify as VerifyResult.Ok
        // Mirror the CLI run path: build runtime schema obligations from the
        // verifier's recorded SchemaType entries.
        val obligations = verify.nodeTypes.mapNotNull { (nid, t) ->
            (t as? TypeExpr.SchemaType)?.let { nid to it }
        }.toMap()
        return Loaded(finalized.root, finalized.store, finalized.hashToNodeId, obligations)
    }

    @Test
    fun `dynamic schema-checked value that satisfies the invariant runs clean`() {
        val p = load("/corpus/82-runtime-schema-dynamic-pass.json")
        // The obligation is recorded (a SchemaType flows into the PositiveInt
        // parameter from a dynamic Int.Sub argument).
        assertTrue(p.obligations.isNotEmpty(), "expected at least one runtime schema obligation")
        val interp = Interpreter(p.store, p.hashToNodeId, schemaObligations = p.obligations)
        val value = interp.eval(p.root, CapabilitySet.EMPTY)
        // Int.Sub(5, 3) = 2; PositiveInt invariant (n > 0) holds; the
        // schema erases at runtime to the underlying Int value.
        assertEquals(Value.IntV(2), value)
    }

    @Test
    fun `dynamic schema-checked value that violates the invariant raises at runtime`() {
        val p = load("/corpus/83-runtime-schema-dynamic-violation.json")
        assertTrue(p.obligations.isNotEmpty(), "expected at least one runtime schema obligation")
        val interp = Interpreter(p.store, p.hashToNodeId, schemaObligations = p.obligations)
        val ex = assertThrows<InterpretException> {
            interp.eval(p.root, CapabilitySet.EMPTY)
        }
        val err = ex.error
        assertTrue(
            err is InterpretError.SchemaInvariantViolation,
            "expected SchemaInvariantViolation, got $err",
        )
        err as InterpretError.SchemaInvariantViolation
        // Int.Sub(3, 5) = -2 violates PositiveInt (n > 0); the violation
        // blames the dynamic value-flow site and names the failing value.
        assertTrue(err.valueDescription.contains("-2"), "value description should name the offending value: ${err.valueDescription}")
    }

    @Test
    fun `without obligations installed the violating program runs unchecked`() {
        // Regression guard documenting the pre-Q-047 behaviour: an
        // interpreter constructed without obligations (CorpusTest's path)
        // does not enforce — the violating value flows through silently.
        val p = load("/corpus/83-runtime-schema-dynamic-violation.json")
        val interp = Interpreter(p.store, p.hashToNodeId)
        val value = interp.eval(p.root, CapabilitySet.EMPTY)
        assertEquals(Value.IntV(-2), value)
    }
}
