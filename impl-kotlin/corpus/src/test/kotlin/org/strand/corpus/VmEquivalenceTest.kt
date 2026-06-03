package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.bytecode.Lowerer
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Interpreter
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import org.strand.vm.Vm

/**
 * Q-017 step 1 corpus equivalence test. For every Layer 1 + Layer 4
 * corpus program below, the test asserts the bytecode VM produces the
 * same result as the tree-walking interpreter.
 *
 * The fixture list grows as each layer's lowering rules land: it now
 * spans Layer 1 (literals, lambda, application, let, varref, NodeRef,
 * TypeAbstraction, ForeignNode), Layer 3 (capabilities, handlers), and
 * Layer 5 (Match, Fixpoint, Product/SumValue, ConstructorPattern,
 * recursive types). State-machine and async programs are covered by
 * [VmMachineEquivalenceTest] / [VmAsyncMachineEquivalenceTest]; the
 * static schema-invariant dispatch is covered by
 * [VmSchemaEquivalenceTest]. A schema-bearing program is admissible here
 * when its *value path* stays within the lowerer's scope — the Schema /
 * Invariant nodes are reachable only through type edges, which the
 * lowerer erases (see corpus 82 below). The lowerer raises
 * [org.strand.bytecode.LoweringNotImplemented] for any category still out
 * of scope.
 *
 * The corpus programs 08-product-type-decl and 09-sum-type-decl are
 * type-declaration only — their root nodes evaluate to a runtime Value
 * the verifier classifies as a Type, but the interpreter declines to
 * evaluate them. They're excluded here because the interpreter throws.
 */
class VmEquivalenceTest {

    private data class Layer14Pair(val baseName: String)

    private val pairs = listOf(
        Layer14Pair("01-int-literal"),
        Layer14Pair("02-identity-applied"),
        Layer14Pair("03-let-identity"),
        Layer14Pair("04-k-combinator"),
        // 05-s-combinator-typed returns a closure (the S combinator as a
        // value, not applied). The interpreter returns Value.Closure and
        // the VM returns VmClosure — equivalent in behavior but not in
        // Kotlin equality (Value.Closure uses reference equality on the
        // captured environment; VmClosure uses content equality on its
        // captures array — the two are structurally different
        // representations of the same callable). Excluded from the
        // strict equality test; behavioral equivalence is implicitly
        // covered by every Application that calls into a closure.
        Layer14Pair("06-let-polymorphic"),
        Layer14Pair("07-higher-order"),
        Layer14Pair("10-noderef-shared"),
        Layer14Pair("11-higher-rank-apply"),
        Layer14Pair("15-builtin-add"),
        // Layer 3: effect-declared / capability-granted programs.
        // Both runs grant ALL effect categories in the store (mirrors
        // CLI --grant-all). Refinement-bearing programs (33-35) pass
        // because CapabilitySet.ofCategories produces wildcards that
        // cover any refinement, and the VM does category-only checks.
        Layer14Pair("12-effect-declared-and-granted"),
        Layer14Pair("13-capability-scope-narrow-then-call"),
        Layer14Pair("14-multi-effect-lambda"),
        Layer14Pair("14-pure-lambda-with-overdeclared-effect"),
        Layer14Pair("16-builtin-time-now-under-capability"),
        Layer14Pair("17-builtin-compose-pure-and-effectful"),
        Layer14Pair("33-refined-network-connect"),
        Layer14Pair("34-refined-wildcard-port"),
        Layer14Pair("35-refined-logger-authorized-path"),
        // Layer 3: handler-intercepted effectful calls.
        Layer14Pair("36-handler-mock-time-now"),
        Layer14Pair("37-handler-captures-outer-let"),
        Layer14Pair("38-handler-nested-innermost-wins"),
        Layer14Pair("39-handler-itself-performs-effect"),
        Layer14Pair("40-handler-fires-through-fixpoint"),
        // Layer 5 step 1: Match + literal/variable/wildcard patterns.
        Layer14Pair("18-match-int-literal-with-wildcard"),
        Layer14Pair("19-match-on-comparison-result"),
        Layer14Pair("20-match-variable-binding"),
        // Layer 5 step 2: Fixpoint (which uses Match for base cases).
        Layer14Pair("21-fixpoint-factorial"),
        Layer14Pair("22-fixpoint-sum-to-n"),
        // Layer 5 step 3a/3b: ProductValue + SumValue + ProductFieldGet.
        Layer14Pair("23-product-construct-and-access"),
        Layer14Pair("24-product-sum-fields-via-lambda"),
        // Layer 5 — ConstructorPattern over sum values.
        Layer14Pair("25-option-some-unwrap"),
        Layer14Pair("26-option-none-default"),
        Layer14Pair("27-result-ok-or-err"),
        Layer14Pair("28-safe-divide-success"),
        Layer14Pair("29-safe-divide-by-zero"),
        // Layer 4 — additional foreign builtins (String.Concat etc.).
        Layer14Pair("30-string-concat"),
        // Layer 5 — Recursive types (lists) + ConstructorPattern.
        Layer14Pair("31-recursive-list-head"),
        Layer14Pair("32-recursive-list-sum"),
        // Q-047 (Layer 7 step 2) — a schema-bearing program on its value
        // path. The PositiveInt Schema/Invariant nodes are reachable only
        // through the parameter's type edge (erased at lowering and not
        // consulted by the obligation-free interpreter run here), so the
        // value path — identityOfPositiveInt(Int.Sub(5,3)) — lowers and
        // evaluates to IntV(2) identically under both engines. This is the
        // non-violating case: runtime schema enforcement is interpreter-
        // only (the VM erases schemas pre-bytecode), so the *violation*
        // sibling (corpus 83) is deliberately NOT here — it would diverge
        // by design. CorpusRuntimeSchemaTest cross-checks that the VM
        // value agrees with the interpreter-WITH-obligations result for
        // this pass case.
        Layer14Pair("82-runtime-schema-dynamic-pass"),
    )

    @TestFactory
    fun vmEquivalentToInterpreter(): List<DynamicTest> = pairs.map { pair ->
        DynamicTest.dynamicTest(pair.baseName) {
            val canonicalText = loadResource("/corpus/${pair.baseName}.json")
            val ingest = JsonIngest.parse(canonicalText)
            val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
            val verifyResult = Verifier(finalized.store, finalized.hashToNodeId)
                .verify(finalized.root)
            assertTrue(verifyResult is VerifyResult.Ok) {
                "${pair.baseName}: verifier failed: $verifyResult"
            }

            // Grant all EffectCategory NodeIds (mirrors CLI --grant-all)
            // so effect-using programs can run end-to-end. Pure programs
            // are unaffected — they don't consult capabilities.
            val effectCategoryIds = finalized.store.entries()
                .asSequence()
                .filter { it.second is Node.EffectCategory }
                .map { it.first }
                .toSet()

            val interpValue = Interpreter(finalized.store, finalized.hashToNodeId)
                .eval(finalized.root, capabilities = CapabilitySet.ofCategories(effectCategoryIds))
            val table = Lowerer(finalized.store, finalized.hashToNodeId).lower(finalized.root)
            val vmValue = Vm(table).run(initialCaps = effectCategoryIds.map { it.value }.toSet())

            assertEquals(interpValue, vmValue) {
                "${pair.baseName}: VM=${vmValue} interpreter=${interpValue}"
            }
        }
    }

    private fun loadResource(resource: String): String {
        val stream = VmEquivalenceTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }
}
