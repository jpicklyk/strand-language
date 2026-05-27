package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.EvaluationLimits
import org.strand.core.ExhaustionKind
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-040 negative-runtime corpus tests. Corpus programs 71 and 72 are
 * verified-OK Strand graphs whose evaluation raises
 * [InterpretError.ResourceExhaustion] under appropriately-tightened
 * [EvaluationLimits]. They exist to make the hostile-graph defense
 * observable in the shared corpus rather than only in per-module unit
 * tests.
 *
 *  - **71-fixpoint-no-base-case**: `λ rec _. rec(unit)`. Step counter
 *    or stack-depth counter fires under tight limits — both are
 *    structurally correct outcomes per the proposal's contract.
 *  - **72-deep-application-chain**: an 800-deep `id(id(id(...x)))` chain.
 *    Stack-depth counter fires under tight limits.
 *
 * Tests use tightened limits (rather than DEFAULTS) because the JVM's
 * own native stack tolerance (with Gradle's default test settings) is
 * lower than the default `maxStackDepth = 4096`. The Q-040 contract is
 * "host-side cap fires before native crash" given enough JVM stack;
 * tightening the cap below the JVM tolerance makes that observable in
 * a portable test.
 */
class CorpusLimitsTest {

    @Test
    fun `corpus 71 - Fixpoint with no base case raises ResourceExhaustion`() {
        val text = loadResource("/corpus/71-fixpoint-no-base-case.json")
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "verifier failed: $verify" }

        val interpreter = Interpreter(finalized.store, finalized.hashToNodeId)
        // Tight limits so the host-side cap fires before the JVM stack
        // overflows. Steps fires first with maxSteps = 1000 because
        // each recursion is ~4 steps.
        val limits = EvaluationLimits.DEFAULTS.copy(
            maxSteps = 1000L,
            maxStackDepth = 500,
        )
        val err = assertThrows<InterpretException> {
            interpreter.eval(finalized.root, CapabilitySet.EMPTY, limits)
        }
        val re = err.error
        assertTrue(re is InterpretError.ResourceExhaustion) {
            "expected ResourceExhaustion, got $re"
        }
        re as InterpretError.ResourceExhaustion
        // Either Steps or StackDepth — the proposal's "Steps" framing
        // matches a Wasmer-style per-instruction fuel; our per-eval
        // step count plus the constant-per-recursion budget means
        // StackDepth can race past Steps. Both are structured.
        assertTrue(
            re.kind == ExhaustionKind.Steps || re.kind == ExhaustionKind.StackDepth,
            "expected Steps or StackDepth, got ${re.kind}"
        )
    }

    @Test
    fun `corpus 72 - deep Application chain raises ResourceExhaustion(StackDepth)`() {
        val text = loadResource("/corpus/72-deep-application-chain.json")
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "verifier failed: $verify" }

        val interpreter = Interpreter(finalized.store, finalized.hashToNodeId)
        // Tight stack-depth cap; the program nests 800 Applications, so
        // a 100 stack-depth limit fires comfortably before the JVM
        // native stack runs out.
        val limits = EvaluationLimits.DEFAULTS.copy(maxStackDepth = 100)
        val err = assertThrows<InterpretException> {
            interpreter.eval(finalized.root, CapabilitySet.EMPTY, limits)
        }
        val re = err.error
        assertTrue(re is InterpretError.ResourceExhaustion) {
            "expected ResourceExhaustion, got $re"
        }
        re as InterpretError.ResourceExhaustion
        assertEquals(ExhaustionKind.StackDepth, re.kind)
    }

    private fun loadResource(path: String): String {
        val stream = CorpusLimitsTest::class.java.getResourceAsStream(path)
            ?: error("missing resource $path")
        return stream.bufferedReader().readText()
    }
}
