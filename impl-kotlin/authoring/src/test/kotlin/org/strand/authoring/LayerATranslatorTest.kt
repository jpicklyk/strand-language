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
    fun `Lambda parameters render as bare-ref list in canonical form, compact form after sugar`() {
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
        // Canonical form: explicit PRC, bare-ref list.
        val canonical = LayerATranslator.translateCanonical(json)
        val canonicalLam = canonical.nodes.first { it.id == "lam" }
        assertEquals("LAM", canonicalLam.code)
        assertEquals(2, canonicalLam.args.size)
        assertEquals(Arg.Listing(listOf(Arg.Bare("x"))), canonicalLam.args[0])
        assertEquals(Arg.Bare("xRef"), canonicalLam.args[1])
        assertTrue(canonical.nodes.any { it.id == "x" && it.code == "PRC" }) {
            "canonical form must retain the explicit PRC declaration"
        }

        // Full pipeline: Slice 5 compact-LAM lifts `x:intT` and drops the PRC.
        val full = LayerATranslator.translate(json)
        val fullLam = full.nodes.first { it.id == "lam" }
        assertEquals(Arg.Listing(listOf(Arg.Bare("x:intT"))), fullLam.args[0])
        assertTrue(full.nodes.none { it.id == "x" }) {
            "PRC declaration should have been dropped by Slice 5"
        }
    }

    @Test
    fun `Match-on-Bool produces canonical MAT in canonical form, collapses to IF after sugar`() {
        // Verifies both Step 1's canonical-form behavior (MAT, no sugar
        // detection) AND Step 4 Slice 4's IF projection (Match-on-Bool
        // folds to IF + drops six wrapper nodes).
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
        // Canonical-form translator picks MAT.
        val canonical = LayerATranslator.translateCanonical(json)
        assertEquals("MAT", canonical.nodes.first { it.id == "m" }.code)

        // Full pipeline detects IF and collapses the six wrappers. Slices
        // 2 + 3 then inline the single-use scrutinee BoolLit and the two
        // IntLit branch bodies into the IF's arg positions.
        val full = LayerATranslator.translate(json)
        val m = full.nodes.first { it.id == "m" }
        assertEquals("IF", m.code)
        assertEquals(3, m.args.size)
        assertEquals(Arg.BoolL(true), m.args[0])
        assertEquals(Arg.IntL(1), m.args[1])
        assertEquals(Arg.IntL(0), m.args[2])
        // Wrapper ids removed from the doc.
        val ids = full.nodes.map { it.id }.toSet()
        for (wrapper in listOf("tCase", "fCase", "tPat", "fPat", "tLit", "fLit", "scrut", "one", "zero")) {
            assertTrue(wrapper !in ids) { "wrapper $wrapper should have been removed by IF + inline" }
        }
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
    fun `Application with effectInstances present but typeArguments absent fills placeholder (canonical form)`() {
        // Verifies Step 1's positional placeholder + FORCE_ALL_OPTIONALS
        // behavior on the canonical-form translator. The full `translate`
        // pipeline (Steps 2+3) probes for and accepts effectInstances
        // omission when the Elaborator's case 2 can re-derive the value
        // unambiguously, so a separate test below covers the post-probe
        // shape.
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
        val doc = LayerATranslator.translateCanonical(json)
        val app = doc.nodes.first { it.id == "app" }
        assertEquals(4, app.args.size)
        assertEquals(Arg.Bare("now"), app.args[0])
        assertEquals(Arg.Listing(emptyList()), app.args[1])  // arguments
        assertEquals(Arg.Listing(emptyList()), app.args[2])  // typeArguments placeholder
        assertEquals(Arg.Listing(listOf(Arg.Bare("nowInst"))), app.args[3])
    }

    @Test
    fun `Application effectInstances is probe-stripped when Elaborator case 2 can re-derive it`() {
        // Same program as the canonical-form test above; the full `translate`
        // pipeline runs Step 3's probe. The probe tentatively omits
        // effectInstances, re-elaborates, and observes that case 2 inserts
        // [nowInst] back identically (only one EffectDecl matches the
        // category). The strip is accepted; both typeArguments and
        // effectInstances are then removed, leaving the minimal 2-arg APP.
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
        assertEquals(2, app.args.size)
        assertEquals(Arg.Bare("now"), app.args[0])
        assertEquals(Arg.Listing(emptyList()), app.args[1])
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
