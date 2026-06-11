package org.strand.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
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

    // -----------------------------------------------------------------------
    // Regression tests for the effectsOfOrdered IndexOutOfBoundsException
    // (Q-034 gap-scan feature, commit aed2db2).
    //
    // Root cause: effectsOfOrdered accessed node.args[1] directly for LAM
    // nodes when args.size < 3.  A compact LAM whose body is an Arg.Nested
    // expression (e.g. `LAM [x:intT] (APP mul [x 2])`) has args[1] as
    // Arg.Nested, not Arg.Bare.  The `as? Arg.Bare` cast returns null and
    // the `?: return emptyList()` safe-call short-circuits correctly — BUT
    // only after args[1] is successfully retrieved.  If the node had been
    // synthesized with < 2 args the retrieve itself would throw IOOB.
    // The fix uses getOrNull(1) throughout effectsOfOrdered and the
    // companion effectsOf / closureOf* helpers.
    // -----------------------------------------------------------------------

    @Test
    fun `compact LAM with inline APP of prelude builtin as body compiles and ingests cleanly`() {
        // This is the crashing shape: a compact LAM whose body is a nested
        // (APP mul ...) expression. Before the fix, elaborateWithGaps would
        // crash with IndexOutOfBoundsException in effectsOfOrdered when the
        // LAM was the callee of the outer APP node.
        val text = """
            @v=1 root=result
            double LAM [x:intT] (APP mul [x 2])
            five ILT 5
            result APP double [five]
        """.trimIndent()

        val compiled = assertDoesNotThrow { Authoring.compile(text) }
        assertEquals(emptyList<ElaborationGap>(), compiled.elaborationGaps)
        // Must also ingest cleanly downstream.
        assertDoesNotThrow { JsonIngest.parse(compiled.dagJson) }
    }

    @Test
    fun `compact LAM with inline APP of prelude add builtin compiles cleanly`() {
        // Same shape as the mul case — covers the add builtin explicitly.
        val text = """
            @v=1 root=result
            increment LAM [x:intT] (APP add [x 1])
            three ILT 3
            result APP increment [three]
        """.trimIndent()

        val compiled = assertDoesNotThrow { Authoring.compile(text) }
        assertEquals(emptyList<ElaborationGap>(), compiled.elaborationGaps)
        assertDoesNotThrow { JsonIngest.parse(compiled.dagJson) }
    }

    @Test
    fun `compact LAM passed as higher-order argument with prelude-builtin body compiles cleanly`() {
        // The List.Map scenario from evaluation/dynamic/prompts/strand-system.md.
        // The compact LAM `double` has a nested (APP mul ...) body and is
        // passed as a value argument to a higher-order FN (mapFn).  This was
        // the reported trigger: elaborateApplication on `mapped APP mapFn ...`
        // calls effectsOfOrdered on mapFn (no crash), but a different
        // elaboration ordering could reach the LAM's effectsOfOrdered path.
        val text = """
            @v=1 root=mapped
            selfRef RS
            headInner PRF "head" intT
            tailInner PRF "tail" selfRef
            consInner PRD [headInner tailInner]
            consCase SCS "Cons" consInner
            nilCase SCS "Nil" _
            listSum SUM [consCase nilCase]
            listT RT listSum
            headOuter PRF "head" intT
            tailOuter PRF "tail" listT
            consOuter PRD [headOuter tailOuter]
            nilV SV listT "Nil" _
            five SV listT "Cons" (PV consOuter [head=5 tail=nilV])
            list SV listT "Cons" (PV consOuter [head=3 tail=five])
            double LAM [x:intT] (APP mul [x 2])
            fnT FNT [intT] intT
            mapT FNT [listT fnT] listT
            mapFn FN "strand-builtin:List.Map" mapT
            mapped APP mapFn [list double]
        """.trimIndent()

        val compiled = assertDoesNotThrow { Authoring.compile(text) }
        assertEquals(emptyList<ElaborationGap>(), compiled.elaborationGaps)
        // The full program must also ingest cleanly — this is the List.Map
        // example from strand-system.md that MUST keep compiling.
        assertDoesNotThrow { JsonIngest.parse(compiled.dagJson) }
    }
}
