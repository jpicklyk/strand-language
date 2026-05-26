package org.strand.authoring

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for Slice 10 (Layer A density v4) — nested expressions
 * `(CODE args...)`. Verifies:
 *
 *  * Lexical: `(...)` parses as [Arg.Nested], composes with `[...]`
 *    lists and `"..."` strings.
 *  * Semantic: nested-value codes synthesize children with `__expr<n>`
 *    ids; nested type/structural codes are rejected with structured
 *    errors.
 *  * Composition: works with reserved names (Slice 1), inline literals
 *    (Slice 2), and auto-VarRef (Slice 3) when the nested args include
 *    PRC binder references.
 *  * FIELD_LIST extension: `name=(NESTED)` synthesizes a PFV pointing
 *    at the nested expression's id.
 */
class NestedExpressionTest {

    @Test
    fun `nested APP inside an outer APP arguments synthesizes one __expr child`() {
        val text = """
            @v=1 root=outer
            intT PRM Int
            addT FNT [intT intT] intT
            add FN "strand-builtin:Int.Add" addT
            outer APP add [1 (APP add [2 3])]
        """.trimIndent()

        val json = Authoring.compileToJsonObject(text)
        val nodes = json.jsonObject["nodes"]!!.jsonObject
        // The user wrote one outer APP. The Slice 10 emitter should have
        // synthesized one __expr0 (inner APP) plus inline literal nodes.
        assertTrue(nodes.containsKey("outer"))
        assertTrue(nodes.containsKey("__expr0")) { "expected nested expression child __expr0; got nodes ${nodes.keys}" }
        // outer.arguments[1] should point at __expr0.
        val outer = nodes["outer"]!!.jsonObject
        val outerArgs = outer["arguments"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("__expr0", outerArgs[1])
        // __expr0 should itself be an Application with function=add.
        val inner = nodes["__expr0"]!!.jsonObject
        assertEquals("Application", inner["type"]!!.jsonPrimitive.content)
        assertEquals("add", inner["function"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deeply nested APPs produce sequential __expr ids`() {
        // factorial body: (APP mul [n (APP recurse [(APP sub [n 1])])])
        val text = """
            @v=1 root=app
            factT FNT [intT] intT
            matchBody IF (APP eqInt [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])])
            bodyLam LAM [recurse:factT n:intT] matchBody
            fact FIX factT bodyLam
            app APP fact [5]
        """.trimIndent()

        val json = Authoring.compileToJsonObject(text)
        val nodes = json.jsonObject["nodes"]!!.jsonObject
        // We expect at least __expr0, __expr1, __expr2, __expr3 from the
        // four nested APPs (eqInt, mul, recurse, sub).
        for (i in 0 until 4) {
            assertTrue(nodes.containsKey("__expr$i")) {
                "missing __expr$i; got ${nodes.keys}"
            }
        }
    }

    @Test
    fun `nested type code (PRM) at value position is rejected with a structured error`() {
        val text = """
            @v=1 root=outer
            intT PRM Int
            addT FNT [intT intT] intT
            add FN "strand-builtin:Int.Add" addT
            outer APP add [1 (PRM Int)]
        """.trimIndent()

        val ex = assertThrows(AuthoringException::class.java) {
            Authoring.compileToJsonObject(text)
        }
        val shapeErrors = ex.errors.filterIsInstance<AuthoringError.ArgShapeMismatch>()
        assertTrue(shapeErrors.isNotEmpty()) {
            "expected ArgShapeMismatch errors; got ${ex.errors}"
        }
        assertTrue(shapeErrors.any { "PRM" in it.expectedKind }) {
            "expected the error to mention PRM as type-only; got ${shapeErrors.map { it.expectedKind }}"
        }
    }

    @Test
    fun `nested PRC code at value position is rejected with a structured error`() {
        val text = """
            @v=1 root=outer
            intT PRM Int
            addT FNT [intT intT] intT
            add FN "strand-builtin:Int.Add" addT
            outer APP add [1 (PRC "fake" intT)]
        """.trimIndent()

        val ex = assertThrows(AuthoringException::class.java) {
            Authoring.compileToJsonObject(text)
        }
        val shapeErrors = ex.errors.filterIsInstance<AuthoringError.ArgShapeMismatch>()
        assertTrue(shapeErrors.isNotEmpty()) {
            "expected ArgShapeMismatch errors; got ${ex.errors}"
        }
    }

    @Test
    fun `nested expression composes with Slice 3 auto-VarRef on PRC binder`() {
        // `n` inside (APP add [n 1]) should auto-VarRef to a synthesized
        // VarRef pointing at the PRC `n` declared in the LAM's params.
        val text = """
            @v=1 root=lam
            intT PRM Int
            addT FNT [intT intT] intT
            add FN "strand-builtin:Int.Add" addT
            lam LAM [n:intT] (APP add [n 1])
        """.trimIndent()

        val json = Authoring.compileToJsonObject(text)
        val nodes = json.jsonObject["nodes"]!!.jsonObject
        // The lam.body should be __expr0 (the nested APP).
        val lam = nodes["lam"]!!.jsonObject
        assertEquals("__expr0", lam["body"]!!.jsonPrimitive.content)
        // __expr0's arguments[0] should be a VarRef synthesized for `n`.
        val expr0 = nodes["__expr0"]!!.jsonObject
        val args = expr0["arguments"]!!.jsonArray.map { it.jsonPrimitive.content }
        // First arg is the VarRef wrapping `n`; second arg is an inline literal.
        val firstArgNode = nodes[args[0]]!!.jsonObject
        assertEquals("VarRef", firstArgNode["type"]!!.jsonPrimitive.content)
        assertEquals("n", firstArgNode["binder"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested expression in FIELD_LIST value position works`() {
        // Toggle machine's transitionResult uses `state=(APP not [s])`.
        val text = """
            @v=1 root=pv
            stateFieldT PRF "state" boolT
            resultT PRD [stateFieldT]
            notT FNT [boolT] boolT
            not FN "strand-builtin:Bool.Not" notT
            pv PV resultT [state=(APP not [s])]
        """.trimIndent()

        val json = Authoring.compileToJsonObject(text)
        val nodes = json.jsonObject["nodes"]!!.jsonObject
        // The pv should have a PFV in its fields; that PFV's value points
        // at the synthesized __expr0 (nested APP).
        val pv = nodes["pv"]!!.jsonObject
        val fields = pv["fields"]!!.jsonArray.map { it.jsonPrimitive.content }
        val pfvId = fields.single()
        val pfv = nodes[pfvId]!!.jsonObject
        assertEquals("ProductFieldValue", pfv["type"]!!.jsonPrimitive.content)
        assertEquals("state", pfv["fieldName"]!!.jsonPrimitive.content)
        // The PFV's value should resolve to __expr0 — the nested APP.
        val valueId = pfv["value"]!!.jsonPrimitive.content
        // Note: it might go through one extra level of wrapping; just
        // verify there's an Application reachable from PFV.value.
        val nestedNode = nodes[valueId]!!.jsonObject
        assertEquals("Application", nestedNode["type"]!!.jsonPrimitive.content)
    }
}
