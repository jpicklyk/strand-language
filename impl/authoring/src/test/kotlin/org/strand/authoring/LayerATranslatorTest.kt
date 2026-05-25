package org.strand.authoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [LayerATranslator] (canonical dag-json → [LayerADocument]).
 *
 * Step 1 scope: canonical-form output only. Sugar codes (IF, WHEN) and
 * density-form list kinds (PARAM_LIST, FIELD_LIST) are not yet detected;
 * those land in later steps of the Q-036 reverse-projection work.
 *
 * Round-trip correctness against the full corpus is tested by
 * [org.strand.corpus.LayerAReverseRoundTripTest] in the corpus module —
 * this file covers focused unit behavior (per-field-kind translation,
 * discriminator-based code selection, error reporting).
 */
class LayerATranslatorTest {

    @Test
    fun `IntLit translates to ILT with integer arg`() {
        val json = """{
            "version": 1,
            "root": "n",
            "nodes": {
                "n": { "type": "IntLit", "value": 42 }
            }
        }"""
        val doc = LayerATranslator.translate(json)
        assertEquals(1, doc.version)
        assertEquals("n", doc.rootId)
        assertEquals(1, doc.nodes.size)
        val decl = doc.nodes[0]
        assertEquals("n", decl.id)
        assertEquals("ILT", decl.code)
        assertEquals(listOf<Arg>(Arg.IntL(42L)), decl.args)
    }

    @Test
    fun `BoolLit and UnitLit and StringLit translate to their codes`() {
        val json = """{
            "version": 1,
            "root": "u",
            "nodes": {
                "b": { "type": "BoolLit", "value": true },
                "u": { "type": "UnitLit" },
                "s": { "type": "StringLit", "value": "hello\nworld" }
            }
        }"""
        val doc = LayerATranslator.translate(json)
        assertEquals(3, doc.nodes.size)
        assertEquals("BLT", doc.nodes[0].code)
        assertEquals(listOf<Arg>(Arg.BoolL(true)), doc.nodes[0].args)
        assertEquals("ULT", doc.nodes[1].code)
        assertEquals(emptyList<Arg>(), doc.nodes[1].args)
        assertEquals("STR", doc.nodes[2].code)
        assertEquals(listOf<Arg>(Arg.Str("hello\nworld")), doc.nodes[2].args)
    }

    @Test
    fun `PrimitiveType translates to PRM with keyword kind`() {
        val json = """{
            "version": 1,
            "root": "t",
            "nodes": {
                "t": { "type": "PrimitiveType", "kind": "Int" }
            }
        }"""
        val doc = LayerATranslator.translate(json)
        assertEquals("PRM", doc.nodes[0].code)
        assertEquals(listOf<Arg>(Arg.Bare("Int")), doc.nodes[0].args)
    }

    @Test
    fun `Lambda translates with parameters as bare-ref list`() {
        // Step 1 always produces the legacy bare-ref list form for LAM
        // parameters (PARAM_LIST kind), never the compact `name:typeRef`.
        val json = """{
            "version": 1,
            "root": "lam",
            "nodes": {
                "intT": { "type": "PrimitiveType", "kind": "Int" },
                "x": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
                "xRef": { "type": "VarRef", "binder": "x" },
                "lam": { "type": "Lambda", "parameters": ["x"], "body": "xRef" }
            }
        }"""
        val doc = LayerATranslator.translate(json)
        val lam = doc.nodes.first { it.id == "lam" }
        assertEquals("LAM", lam.code)
        // parameters is a list of bare refs, body is a bare ref
        assertEquals(2, lam.args.size)
        assertEquals(Arg.Listing(listOf(Arg.Bare("x"))), lam.args[0])
        assertEquals(Arg.Bare("xRef"), lam.args[1])
    }

    @Test
    fun `Match without IF sugar detection produces canonical MAT`() {
        // Even when the Match looks like an IF (two cases, BoolLit patterns),
        // Step 1 always picks MAT — the canonical code. Sugar projection
        // is a later step.
        val json = """{
            "version": 1,
            "root": "m",
            "nodes": {
                "boolT": { "type": "PrimitiveType", "kind": "Bool" },
                "scrut": { "type": "BoolLit", "value": true },
                "tLit": { "type": "BoolLit", "value": true },
                "fLit": { "type": "BoolLit", "value": false },
                "tPat": { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "tLit" },
                "fPat": { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "fLit" },
                "intT": { "type": "PrimitiveType", "kind": "Int" },
                "one": { "type": "IntLit", "value": 1 },
                "zero": { "type": "IntLit", "value": 0 },
                "tCase": { "type": "MatchCase", "pattern": "tPat", "body": "one" },
                "fCase": { "type": "MatchCase", "pattern": "fPat", "body": "zero" },
                "m": { "type": "Match", "scrutinee": "scrut", "cases": ["tCase", "fCase"] }
            }
        }"""
        val doc = LayerATranslator.translate(json)
        val m = doc.nodes.first { it.id == "m" }
        assertEquals("MAT", m.code)
    }

    @Test
    fun `Pattern variants use discriminator to pick the right code`() {
        val json = """{
            "version": 1,
            "root": "lit",
            "nodes": {
                "boolT": { "type": "PrimitiveType", "kind": "Bool" },
                "tl": { "type": "BoolLit", "value": true },
                "lit": { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "tl" },
                "var": { "type": "Pattern", "kind": "variable", "patternType": "boolT", "name": "v" },
                "wc": { "type": "Pattern", "kind": "wildcard", "patternType": "boolT" }
            }
        }"""
        val doc = LayerATranslator.translate(json)
        assertEquals("PLT", doc.nodes.first { it.id == "lit" }.code)
        assertEquals("PVR", doc.nodes.first { it.id == "var" }.code)
        assertEquals("PWC", doc.nodes.first { it.id == "wc" }.code)
    }

    @Test
    fun `SumValue with null payload becomes Arg-Null`() {
        val json = """{
            "version": 1,
            "root": "n",
            "nodes": {
                "intT": { "type": "PrimitiveType", "kind": "Int" },
                "noneCase": { "type": "SumTypeCase", "name": "None", "caseType": null },
                "someCase": { "type": "SumTypeCase", "name": "Some", "caseType": "intT" },
                "optT": { "type": "SumType", "cases": ["someCase", "noneCase"] },
                "n": { "type": "SumValue", "ofType": "optT", "caseName": "None", "payload": null }
            }
        }"""
        val doc = LayerATranslator.translate(json)
        val sv = doc.nodes.first { it.id == "n" }
        assertEquals("SV", sv.code)
        // SV's args: ofType, caseName, payload
        assertEquals(Arg.Bare("optT"), sv.args[0])
        assertEquals(Arg.Str("None"), sv.args[1])
        assertEquals(Arg.Null, sv.args[2])
    }

    @Test
    fun `Application with effectInstances present but typeArguments absent fills placeholder`() {
        // APP optional fields are: typeArguments (LIST_REF), then
        // effectInstances (LIST_REF). When effectInstances is present but
        // typeArguments is absent, the translator must emit an empty-list
        // placeholder for typeArguments so positional order stays correct.
        val json = """{
            "version": 1,
            "root": "app",
            "nodes": {
                "intT": { "type": "PrimitiveType", "kind": "Int" },
                "nowFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
                "nowT": { "type": "FunctionType", "parameters": [], "result": "intT" },
                "now": { "type": "ForeignNode", "target": "strand-builtin:Time.Now", "foreignType": "nowT", "effects": ["nowFx"] },
                "nowInst": { "type": "EffectDecl", "effectType": "nowFx", "parameters": [] },
                "app": { "type": "Application", "function": "now", "arguments": [], "effectInstances": ["nowInst"] }
            }
        }"""
        val doc = LayerATranslator.translate(json)
        val app = doc.nodes.first { it.id == "app" }
        // APP has: function, arguments, typeArguments?, effectInstances?
        // Required: function, arguments. Optional: typeArguments, effectInstances.
        // With effectInstances present and typeArguments absent, expect
        // the placeholder empty list filling the typeArguments slot.
        assertEquals(4, app.args.size)
        assertEquals(Arg.Bare("now"), app.args[0])
        assertEquals(Arg.Listing(emptyList()), app.args[1])  // arguments
        assertEquals(Arg.Listing(emptyList()), app.args[2])  // typeArguments placeholder
        assertEquals(Arg.Listing(listOf(Arg.Bare("nowInst"))), app.args[3])
    }

    @Test
    fun `unknown JSON type throws AuthoringException`() {
        val json = """{
            "version": 1,
            "root": "n",
            "nodes": {
                "n": { "type": "NotAThing" }
            }
        }"""
        val ex = assertThrows<AuthoringException> { LayerATranslator.translate(json) }
        assertTrue(ex.errors.any { it.detail.contains("NotAThing") }) {
            "expected error mentioning NotAThing, got: ${ex.errors}"
        }
    }

    @Test
    fun `missing root field is a header error`() {
        val json = """{ "version": 1, "nodes": {} }"""
        val ex = assertThrows<AuthoringException> { LayerATranslator.translate(json) }
        assertTrue(ex.errors.any { it.detail.contains("root") }) {
            "expected error mentioning 'root', got: ${ex.errors}"
        }
    }

    @Test
    fun `missing required field is reported with node id and field name`() {
        val json = """{
            "version": 1,
            "root": "n",
            "nodes": {
                "n": { "type": "IntLit" }
            }
        }"""
        val ex = assertThrows<AuthoringException> { LayerATranslator.translate(json) }
        assertTrue(ex.errors.any { it.detail.contains("'n'") && it.detail.contains("value") }) {
            "expected error mentioning node 'n' and field 'value', got: ${ex.errors}"
        }
    }

    @Test
    fun `version and root and node order are preserved in document`() {
        // The translator must visit nodes in the JsonObject's insertion order
        // so the rendered Layer A text has a stable line order matching the
        // canonical JSON's declaration order.
        val json = """{
            "version": 1,
            "root": "c",
            "nodes": {
                "a": { "type": "IntLit", "value": 1 },
                "b": { "type": "IntLit", "value": 2 },
                "c": { "type": "IntLit", "value": 3 }
            }
        }"""
        val doc = LayerATranslator.translate(json)
        assertEquals(listOf("a", "b", "c"), doc.nodes.map { it.id })
    }
}
