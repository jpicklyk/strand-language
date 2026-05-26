package org.strand.authoring

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the Slice 9 WHEN/constructor-pattern sugar over
 * recursive sum types. These cover three failure shapes observed during
 * the 2026-05-25 fresh-context dynamic-cost subagent run:
 *
 *  1. `Cons(c) -> ...` over `μ. Cons({head: Int, tail: <self>}) | Nil`
 *     used to synthesize a binder PVR with `patternType: "unknownT"`
 *     because [DagJsonEmitter.buildCaseTypeMap] looked for the named
 *     SumType directly rather than following its enclosing RT wrapper.
 *     Fix: [DagJsonEmitter.buildCaseTypeMap] unwraps RT chains up to
 *     depth 8 before reading the SUM's cases.
 *
 *  2. `WHEN lst listT "..."` where `lst` was a compact-LAM-synthesized
 *     binder used to fail with `Unknown node id 'lst'` from the
 *     scrutinee slot because the WHEN scrutinee path didn't apply the
 *     auto-VarRef rule. Fix: scope-aware [DagJsonEmitter.resolveBinder]
 *     drives every binder lookup, so any reference to a PRC or to a
 *     case binder gets wrapped consistently.
 *
 *  3. WHEN over a deeply-nested RT chain still resolves the caseType:
 *     [DagJsonEmitter.buildCaseTypeMap] follows up to 8 RT wrappers
 *     before reading the underlying SUM, so a SumType that's been
 *     wrapped twice (`RT (RT listSum)`) is still found.
 */
class WhenSugarTest {

    /**
     * Walk the patternType subgraph and confirm it contains no
     * RecursiveSelf reference (which would be unbound outside an RT).
     * Crosses through PRD / PRF / SUM / SCS / FNT but stops at RT
     * boundaries since RT introduces its own RS binder.
     */
    private fun assertPatternTypeIsClosed(
        nodes: Map<String, kotlinx.serialization.json.JsonElement>,
        rootId: String,
    ) {
        val seen = mutableSetOf<String>()
        fun walk(id: String) {
            if (!seen.add(id)) return
            val node = nodes[id]?.jsonObject ?: return
            val type = node["type"]?.jsonPrimitive?.content ?: return
            if (type == "RecursiveSelf") {
                throw AssertionError(
                    "patternType subgraph at $rootId contains an unbound " +
                        "RecursiveSelf at $id — the WHEN sugar must " +
                        "rewrite RS references to the enclosing RT id"
                )
            }
            if (type == "RecursiveType") return  // RT introduces a new binder
            for ((_, value) in node) {
                when (value) {
                    is kotlinx.serialization.json.JsonPrimitive ->
                        if (value.isString && value.content in nodes) walk(value.content)
                    is kotlinx.serialization.json.JsonArray ->
                        for (item in value) {
                            if (item is kotlinx.serialization.json.JsonPrimitive &&
                                item.isString && item.content in nodes
                            ) {
                                walk(item.content)
                            }
                        }
                    else -> {}
                }
            }
        }
        walk(rootId)
    }

    @Test
    fun `WHEN payload binder over RT-wrapped sum resolves patternType to the SCS caseType`() {
        // μ. Cons({head: Int, tail: <self>}) | Nil — the canonical
        // recursive linked-list shape. The case `Cons(c) -> 42` writes
        // a binder `c` whose type should be `consPayloadT` (the SCS's
        // caseType), NOT the `unknownT` placeholder.
        val text = """
            @v=1 root=invBody
            nilCase SCS "Nil" _
            consHead PRF "head" intT
            consTail PRF "tail" selfRef
            selfRef RS
            consPayloadT PRD [consHead consTail]
            consCase SCS "Cons" consPayloadT
            listBody SUM [consCase nilCase]
            listT RT listBody
            invBody WHEN xs listT "Cons(c) -> 42 | Nil -> 0"
            invLam LAM [xs:listT] invBody
        """.trimIndent()

        val json = Authoring.compileToJsonObject(text)
        val nodes = json["nodes"]!!.jsonObject

        val bindNode = nodes["__when0_bind_c"]
        assertNotNull(bindNode, "expected synthesized PVR node __when0_bind_c; got nodes ${nodes.keys}")
        val bind = bindNode!!.jsonObject
        assertEquals("Pattern", bind["type"]!!.jsonPrimitive.content)
        assertEquals("variable", bind["kind"]!!.jsonPrimitive.content)
        val patternTypeId = bind["patternType"]!!.jsonPrimitive.content
        assertTrue(
            patternTypeId != "unknownT",
            "WHEN binder over RT-wrapped sum must NOT carry the 'unknownT' placeholder",
        )
        // The pattern subgraph must be closed: no RS references reachable
        // without crossing an RT boundary, because the PVR sits outside
        // any RT scope.
        assertPatternTypeIsClosed(nodes, patternTypeId)
        // For a recursive sum like this one, substitution kicks in and
        // the patternType is the synthesized outer-equivalent PRD.
        assertEquals("__when0_outer_consPayloadT", patternTypeId)

        // The PCN should reference the binder PVR through payloadPattern.
        val pcn = nodes["__when0_pat_Cons"]!!.jsonObject
        assertEquals("__when0_bind_c", pcn["payloadPattern"]!!.jsonPrimitive.content)
        assertEquals("listT", pcn["patternType"]!!.jsonPrimitive.content)
        assertEquals("Cons", pcn["caseName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `WHEN binder is visible inside the case body via auto-VarRef`() {
        // `Cons(c) -> (PFG c "head")` — the binder `c` reaches into the
        // nested body and should auto-VarRef to the PVR id, not pass
        // through as a literal "c" reference (which would resolve to no
        // node and fail JsonIngest).
        val text = """
            @v=1 root=invBody
            nilCase SCS "Nil" _
            consHead PRF "head" intT
            consTail PRF "tail" selfRef
            selfRef RS
            consPayloadT PRD [consHead consTail]
            consCase SCS "Cons" consPayloadT
            listBody SUM [consCase nilCase]
            listT RT listBody
            invBody WHEN xs listT "Cons(c) -> (PFG c \"head\") | Nil -> 0"
            invLam LAM [xs:listT] invBody
        """.trimIndent()

        val json = Authoring.compileToJsonObject(text)
        val nodes = json["nodes"]!!.jsonObject

        // The nested (PFG c "head") synthesizes one __expr child whose
        // `target` is a VarRef that binds to __when0_bind_c.
        val expr = nodes["__expr0"]!!.jsonObject
        assertEquals("ProductFieldGet", expr["type"]!!.jsonPrimitive.content)
        assertEquals("head", expr["fieldName"]!!.jsonPrimitive.content)
        val targetId = expr["target"]!!.jsonPrimitive.content
        val targetNode = nodes[targetId]!!.jsonObject
        assertEquals(
            "VarRef",
            targetNode["type"]!!.jsonPrimitive.content,
            "WHEN case body should auto-VarRef the binder name; got target $targetNode",
        )
        assertEquals("__when0_bind_c", targetNode["binder"]!!.jsonPrimitive.content)
    }

    @Test
    fun `WHEN scrutinee position auto-VarRefs a compact-LAM-synthesized binder`() {
        // strand-05 turn-02 shape: `lst` is declared inline as a
        // compact-LAM parameter (`LAM [lst:listT] ...`) and referenced
        // directly as the WHEN scrutinee. The scrutinee position must
        // apply the same auto-VarRef rule that other value-position
        // slots do.
        val text = """
            @v=1 root=invLam
            nilCase SCS "Nil" _
            consHead PRF "head" intT
            consTail PRF "tail" selfRef
            selfRef RS
            consPayloadT PRD [consHead consTail]
            consCase SCS "Cons" consPayloadT
            listBody SUM [consCase nilCase]
            listT RT listBody
            invBody WHEN lst listT "Cons(p) -> 1 | Nil -> 0"
            invLam LAM [lst:listT] invBody
        """.trimIndent()

        val json = Authoring.compileToJsonObject(text)
        val nodes = json["nodes"]!!.jsonObject

        val invBody = nodes["invBody"]!!.jsonObject
        assertEquals("Match", invBody["type"]!!.jsonPrimitive.content)
        val scrutId = invBody["scrutinee"]!!.jsonPrimitive.content
        val scrut = nodes[scrutId]!!.jsonObject
        assertEquals(
            "VarRef",
            scrut["type"]!!.jsonPrimitive.content,
            "WHEN scrutinee referencing a compact-LAM-synthesized binder must auto-VarRef",
        )
        assertEquals("lst", scrut["binder"]!!.jsonPrimitive.content)
    }

    @Test
    fun `WHEN compiles end-to-end on the strand-05 sum-list shape`() {
        // Reproduces the strand-05 sum-list pattern: a fixpoint sum over
        // a recursive list, with WHEN destructuring inside the body.
        // This used to fail with `Unknown node id 'unknownT'` from the
        // binder's patternType slot AND `Unknown node id 'lst'` from
        // the scrutinee slot. Both paths now go through the
        // RT-unwrapping caseType lookup and the scope-aware binder
        // resolver, so the program compiles cleanly.
        val text = """
            @v=1 root=app
            nilCase SCS "Nil" _
            consPayloadHead PRF "head" intT
            consPayloadTail PRF "tail" selfRef
            selfRef RS
            consPayloadT PRD [consPayloadHead consPayloadTail]
            consCase SCS "Cons" consPayloadT
            listBody SUM [consCase nilCase]
            listT RT listBody
            sumT FNT [listT] intT
            nilv SV listT "Nil" _
            sumBody WHEN lst listT "Cons(p) -> (PFG p \"head\") | Nil -> 0"
            sumLam LAM [recurse lst] sumBody
            sumFn FIX sumT sumLam
            app APP sumFn [nilv]
        """.trimIndent()

        val json = Authoring.compileToJsonObject(text)
        val nodes = json["nodes"]!!.jsonObject

        // Binder PVR has substituted patternType (RS rewritten to listT).
        val bind = nodes["__when0_bind_p"]!!.jsonObject
        val patternTypeId = bind["patternType"]!!.jsonPrimitive.content
        assertTrue(patternTypeId != "unknownT")
        assertPatternTypeIsClosed(nodes, patternTypeId)

        // Scrutinee in sumBody auto-VarRefs `lst`.
        val sumBody = nodes["sumBody"]!!.jsonObject
        val scrutId = sumBody["scrutinee"]!!.jsonPrimitive.content
        val scrut = nodes[scrutId]!!.jsonObject
        assertEquals("VarRef", scrut["type"]!!.jsonPrimitive.content)
        assertEquals("lst", scrut["binder"]!!.jsonPrimitive.content)

        // Nested (PFG p "head") in case body auto-VarRefs `p` to the PVR.
        val pfgExpr = nodes.entries
            .map { (_, value) -> value.jsonObject }
            .first {
                it["type"]?.jsonPrimitive?.content == "ProductFieldGet" &&
                    it["fieldName"]?.jsonPrimitive?.content == "head"
            }
        val pfgTargetId = pfgExpr["target"]!!.jsonPrimitive.content
        val pfgTarget = nodes[pfgTargetId]!!.jsonObject
        assertEquals("VarRef", pfgTarget["type"]!!.jsonPrimitive.content)
        assertEquals("__when0_bind_p", pfgTarget["binder"]!!.jsonPrimitive.content)
    }

    @Test
    fun `WHEN over deeply nested RT chain still resolves caseType`() {
        // Defensive: two RT wrappers in a row. buildCaseTypeMap caps
        // RT-following at depth 8, so a couple of hops is still fine
        // and should produce the same patternType outcome.
        val text = """
            @v=1 root=invBody
            nilCase SCS "Nil" _
            consHead PRF "head" intT
            consTail PRF "tail" selfRef
            selfRef RS
            consPayloadT PRD [consHead consTail]
            consCase SCS "Cons" consPayloadT
            listBody SUM [consCase nilCase]
            listMid RT listBody
            listT RT listMid
            invBody WHEN xs listT "Cons(c) -> 1 | Nil -> 0"
            invLam LAM [xs:listT] invBody
        """.trimIndent()

        val json = Authoring.compileToJsonObject(text)
        val nodes = json["nodes"]!!.jsonObject

        val bind = nodes["__when0_bind_c"]!!.jsonObject
        val patternTypeId = bind["patternType"]!!.jsonPrimitive.content
        assertTrue(
            patternTypeId != "unknownT",
            "buildCaseTypeMap must follow multiple RT wrappers",
        )
        assertPatternTypeIsClosed(nodes, patternTypeId)
    }

    @Test
    fun `non-recursive sum case preserves the original caseType id`() {
        // Option-style sum with an Int payload — no RS reachable from
        // the caseType. The substitution walker should memoize the
        // intT node as unchanged and return the original caseType id
        // (intT). This preserves canonical-hash equality for the
        // existing density-v3 25-option-some-unwrap-when fixture.
        val text = """
            @v=1 root=result
            someCase SCS "Some" intT
            noneCase SCS "None" _
            optionT SUM [someCase noneCase]
            someValue SV optionT "Some" 42
            result WHEN someValue optionT "Some(n) -> n | None -> 0"
        """.trimIndent()

        val json = Authoring.compileToJsonObject(text)
        val nodes = json["nodes"]!!.jsonObject

        val bind = nodes["__when0_bind_n"]!!.jsonObject
        assertEquals(
            "intT",
            bind["patternType"]!!.jsonPrimitive.content,
            "non-recursive sum case should preserve the original caseType id — " +
                "substitution must be a no-op when no RS is reachable",
        )
    }
}
