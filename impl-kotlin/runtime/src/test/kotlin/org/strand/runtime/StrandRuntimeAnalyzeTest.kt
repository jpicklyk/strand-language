package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher
import org.strand.interpreter.HostPolicy
import org.strand.verifier.RefinementRequirement

/**
 * Q-072 (ADR-010): the [StrandRuntime.analyze] facade — the third first-class
 * entry point alongside verify/run. It wraps the verified artifact in a pure
 * [org.strand.verifier.ProgramAnalysis] query surface, returning a structured
 * [AnalysisOutcome] (never printing or exiting). A verifying program yields
 * [AnalysisOutcome.Ok] carrying the analysis; a non-verifying program yields
 * [AnalysisOutcome.VerifyFailed] carrying the diagnostics and no analysis.
 */
class StrandRuntimeAnalyzeTest {

    private fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    private fun categoryNames(store: NodeStore, ids: Set<NodeId>): Set<String> =
        ids.mapNotNull { (store.getOrNull(it) as? Node.EffectCategory)?.categoryName }
            .toSortedSet()

    @Test
    fun `analyze returns the analysis surface for a verifying effectful program`() {
        val prog = image(TIME_NOW)
        val rt = StrandRuntime(HostPolicy.OPEN)

        val outcome = rt.analyze(prog)
        val ok = assertInstanceOf(AnalysisOutcome.Ok::class.java, outcome)
        val a = ok.analysis

        assertEquals(
            setOf("Time.Now"), categoryNames(prog.store, a.totalClosure()),
            "the analysis reports the program's total effect closure",
        )
        val req = a.capabilityRequirement()
        assertEquals(1, req.categories.size, "one category is required")
        // Time.Now is zero-arity; its requirement is a wildcard.
        assertTrue(
            req.perCategory.values.all {
                it == RefinementRequirement.Wildcard ||
                    (it is RefinementRequirement.Refined && it.patterns.all { p -> p.isEmpty() })
            },
        )
    }

    @Test
    fun `verifyAndAnalyze mirrors analyze for a verifying program`() {
        val prog = image(TIME_NOW)
        val rt = StrandRuntime(HostPolicy.OPEN)
        val outcome = rt.verifyAndAnalyze(prog)
        assertInstanceOf(AnalysisOutcome.Ok::class.java, outcome)
    }

    @Test
    fun `analyze returns an error outcome for a non-verifying program`() {
        val prog = image(ILL_TYPED)
        val rt = StrandRuntime(HostPolicy.OPEN)

        val outcome = rt.analyze(prog)
        val failed = assertInstanceOf(AnalysisOutcome.VerifyFailed::class.java, outcome)
        assertTrue(failed.errors.isNotEmpty(), "the verify diagnostics are carried on the failure outcome")
    }

    companion object {
        // Time.Now under a granted capability — verifies, reaches {Time.Now}.
        val TIME_NOW = """
        {
          "version": 1,
          "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "nowFx":    { "type": "EffectCategory", "categoryName": "Time.Now", "parameters": [] },
            "nowT":     { "type": "FunctionType", "parameters": [], "result": "intT", "effects": ["nowFx"] },
            "nowFn":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                          "foreignType": "nowT", "effects": ["nowFx"] },
            "nowDecl":  { "type": "EffectDecl", "effectType": "nowFx", "parameters": [] },
            "app":      { "type": "Application", "function": "nowFn", "arguments": [],
                          "effectInstances": ["nowDecl"] }
          }
        }
        """.trimIndent()

        // An Application of a non-function (an IntLit) — fails verification with NotAFunction.
        val ILL_TYPED = """
        {
          "version": 1,
          "root": "app",
          "nodes": {
            "notFn":  { "type": "IntLit", "value": 7 },
            "arg":    { "type": "IntLit", "value": 3 },
            "app":    { "type": "Application", "function": "notFn", "arguments": ["arg"] }
          }
        }
        """.trimIndent()
    }
}
