package org.strand.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.IngestError
import org.strand.core.JsonIngest

/**
 * Q-034 gap policy: inference cases the [Elaborator] attempts but cannot
 * resolve must produce structured [ElaborationGap] records instead of
 * failing silently. The records ride on [Authoring.CompileResult] (and on
 * [AuthoringException] for emit-phase failures); the `strand author`
 * driver prints them only when compilation or downstream verification
 * fails.
 */
class ElaborationGapTest {

    @Test
    fun `untypable compact-LAM parameter produces a gap alongside the downstream failure`() {
        // `x` has no `:type` suffix, no separate PRC, no call site, and no
        // usage that determines its type — the compact-LAM inference has
        // nothing to work with. The emitter passes `x` through as a bare
        // ref, so downstream ingest fails with "Unknown node id 'x'",
        // which is meaningless without the gap note.
        val text = """
            @v=1 root=lam
            lam LAM [x] x
        """.trimIndent()

        val compiled = Authoring.compile(text)
        val gap = compiled.elaborationGaps.single()
        assertEquals("lam", gap.nodeId)
        assertEquals("parameter 'x' paramType", gap.field)
        assertEquals("compact-LAM parameter inference", gap.inferenceCase)
        assertEquals(2, gap.line)
        assertTrue("'x'" in gap.reason, "reason should name the parameter: ${gap.reason}")

        // ...and the failure the gap explains actually occurs downstream.
        assertThrows<IngestError> { JsonIngest.parse(compiled.dagJson) }
    }

    @Test
    fun `unresolvable FIX recursionType produces a gap`() {
        // `factT` is undeclared and the body's result type is not
        // inferable (the body references the untyped recursion slot), so
        // FunctionType synthesis cannot fire.
        val text = """
            @v=1 root=fact
            bodyLam LAM [recurse n] (APP recurse [n])
            fact FIX factT bodyLam
        """.trimIndent()

        val compiled = Authoring.compile(text)
        val fixGap = compiled.elaborationGaps.single { it.field == "recursionType" }
        assertEquals("fact", fixGap.nodeId)
        assertEquals(3, fixGap.line)
        assertTrue("factT" in fixGap.reason)
    }

    @Test
    fun `omitted SCS caseType with unresolvable payload produces a gap`() {
        // `someCase` omits its caseType; the only SumValue usage carries a
        // payload whose type cannot be computed (APP of an undeclared,
        // non-prelude function), so the case-7 inference fails.
        val text = """
            @v=1 root=sv
            someCase SCS "Some" _
            opt SUM [someCase]
            mystery APP unknownFn [1]
            sv SV opt "Some" mystery
        """.trimIndent()

        val compiled = Authoring.compile(text)
        val gap = compiled.elaborationGaps.single { it.field == "caseType" }
        assertEquals("someCase", gap.nodeId)
        assertEquals(2, gap.line)
    }

    @Test
    fun `payload-less sum case with omitted caseType is not a gap`() {
        // A `None`-style case legitimately omits its caseType; with no
        // payload-bearing SumValue usage there is nothing to infer and
        // nothing to report.
        val text = """
            @v=1 root=sv
            noneCase SCS "None" _
            intCase SCS "Some" intT
            opt SUM [noneCase intCase]
            sv SV opt "None" _
        """.trimIndent()

        val compiled = Authoring.compile(text)
        assertEquals(emptyList<ElaborationGap>(), compiled.elaborationGaps)
    }

    @Test
    fun `successful document produces no gaps`() {
        // The density-v4 factorial: every annotation the agent omitted is
        // resolvable, the fixed point fills everything in, and the gap
        // scan stays empty.
        val text = """
            @v=1 root=app
            matchBody IF (APP eqInt [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])])
            bodyLam LAM [recurse n] matchBody
            fact FIX factT bodyLam
            app APP fact [5]
        """.trimIndent()

        val compiled = Authoring.compile(text)
        assertEquals(emptyList<ElaborationGap>(), compiled.elaborationGaps)
        // And the document is genuinely healthy: it ingests cleanly.
        JsonIngest.parse(compiled.dagJson)
    }
}
