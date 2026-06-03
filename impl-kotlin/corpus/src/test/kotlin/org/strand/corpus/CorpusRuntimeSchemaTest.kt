package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.bytecode.Lowerer
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
import org.strand.vm.Vm

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

    @Test
    fun `pass case agrees between interpreter-with-obligations and the bytecode VM`() {
        // VM equivalence on the non-violating value path. The interpreter
        // WITH obligations enforces the PositiveInt invariant (satisfied
        // by 2) and yields IntV(2); the VM erases the schema at lowering
        // and computes the same underlying value. Both engines agree on
        // the pass case — the property that makes the interpreter-only
        // enforcement safe for non-violating programs.
        val p = load("/corpus/82-runtime-schema-dynamic-pass.json")
        val interpValue = Interpreter(p.store, p.hashToNodeId, schemaObligations = p.obligations)
            .eval(p.root, CapabilitySet.EMPTY)
        val table = Lowerer(p.store, p.hashToNodeId).lower(p.root)
        val vmValue = Vm(table).run(initialCaps = emptySet())
        assertEquals(Value.IntV(2), interpValue)
        assertEquals(interpValue, vmValue, "interpreter-with-obligations and VM disagree on the pass case")
    }

    @Test
    fun `the bytecode VM does not enforce runtime schema invariants (documented divergence)`() {
        // Q-047 known limitation: the VM erases schemas pre-bytecode
        // (Q-017), so it carries no runtime obligation. The interpreter
        // WITH obligations raises on corpus 83 (asserted above); the VM
        // runs the same program to the unchecked underlying value. This
        // bounded divergence (error cases only) is why corpus 83 is kept
        // out of VmEquivalenceTest; this test pins the behaviour so a
        // future CHECK_SCHEMA lowering that closes the gap will fail here
        // and prompt an update.
        val p = load("/corpus/83-runtime-schema-dynamic-violation.json")
        val table = Lowerer(p.store, p.hashToNodeId).lower(p.root)
        val vmValue = Vm(table).run(initialCaps = emptySet())
        assertEquals(Value.IntV(-2), vmValue)
    }
}
