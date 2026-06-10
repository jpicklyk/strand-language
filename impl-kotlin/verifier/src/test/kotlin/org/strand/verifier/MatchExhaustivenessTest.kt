package org.strand.verifier

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.strand.core.JsonIngest
import org.strand.core.Primitive
import org.strand.hashing.Hasher

/**
 * Top-level Match exhaustiveness verification (VerifyError.NonExhaustiveMatch).
 *
 * Scope under test mirrors the error's KDoc: catch-alls (wildcard / variable
 * patterns) make any Match exhaustive; Sum scrutinees require every case name
 * covered by a top-level constructor pattern; Bool scrutinees accept literal
 * `true` + `false` as exhaustive; every other scrutinee type needs a
 * catch-all. Nested payload patterns are deliberately NOT analyzed.
 */
class MatchExhaustivenessTest {

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    private val optionHeader = """
        "intT":     { "type": "PrimitiveType", "kind": "Int" },
        "someCase": { "type": "SumTypeCase", "name": "Some", "caseType": "intT" },
        "noneCase": { "type": "SumTypeCase", "name": "None", "caseType": null },
        "optionT":  { "type": "SumType", "cases": ["someCase", "noneCase"] },
        "lit42":    { "type": "IntLit", "value": 42 },
    """.trimIndent()

    @Test
    fun `sum match missing a case is rejected with the missing case name`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            $optionHeader
            "v":        { "type": "SumValue", "ofType": "optionT", "caseName": "Some", "payload": "lit42" },
            "varN":     { "type": "Pattern", "kind": "variable", "patternType": "intT", "name": "n" },
            "patSome":  { "type": "Pattern", "kind": "constructor", "patternType": "optionT", "caseName": "Some", "payloadPattern": "varN" },
            "nRef":     { "type": "VarRef", "binder": "varN" },
            "caseSome": { "type": "MatchCase", "pattern": "patSome", "body": "nRef" },
            "m":        { "type": "Match", "scrutinee": "v", "cases": ["caseSome"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        val err = failed.errors.filterIsInstance<VerifyError.NonExhaustiveMatch>().singleOrNull()
        assertNotNull(err) { "expected NonExhaustiveMatch, got: ${failed.errors}" }
        assertEquals(listOf("None"), err!!.missingCases)
        assertTrue(err.toString().contains("case 'None'")) {
            "agent-actionable message should name the missing case: $err"
        }
    }

    @Test
    fun `sum match covering every case verifies`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            $optionHeader
            "v":        { "type": "SumValue", "ofType": "optionT", "caseName": "Some", "payload": "lit42" },
            "varN":     { "type": "Pattern", "kind": "variable", "patternType": "intT", "name": "n" },
            "patSome":  { "type": "Pattern", "kind": "constructor", "patternType": "optionT", "caseName": "Some", "payloadPattern": "varN" },
            "nRef":     { "type": "VarRef", "binder": "varN" },
            "caseSome": { "type": "MatchCase", "pattern": "patSome", "body": "nRef" },
            "patNone":  { "type": "Pattern", "kind": "constructor", "patternType": "optionT", "caseName": "None" },
            "zero":     { "type": "IntLit", "value": 0 },
            "caseNone": { "type": "MatchCase", "pattern": "patNone", "body": "zero" },
            "m":        { "type": "Match", "scrutinee": "v", "cases": ["caseSome", "caseNone"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Int), ok.rootType)
    }

    @Test
    fun `sum match with wildcard instead of a constructor case verifies`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            $optionHeader
            "v":        { "type": "SumValue", "ofType": "optionT", "caseName": "Some", "payload": "lit42" },
            "varN":     { "type": "Pattern", "kind": "variable", "patternType": "intT", "name": "n" },
            "patSome":  { "type": "Pattern", "kind": "constructor", "patternType": "optionT", "caseName": "Some", "payloadPattern": "varN" },
            "nRef":     { "type": "VarRef", "binder": "varN" },
            "caseSome": { "type": "MatchCase", "pattern": "patSome", "body": "nRef" },
            "wild":     { "type": "Pattern", "kind": "wildcard", "patternType": "optionT" },
            "zero":     { "type": "IntLit", "value": 0 },
            "caseWild": { "type": "MatchCase", "pattern": "wild", "body": "zero" },
            "m":        { "type": "Match", "scrutinee": "v", "cases": ["caseSome", "caseWild"] }
          }
        }""")
        assertTrue(r is VerifyResult.Ok) { "expected Ok, got: $r" }
    }

    @Test
    fun `bool match with literal true and false verifies`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "boolT":     { "type": "PrimitiveType", "kind": "Bool" },
            "scrut":     { "type": "BoolLit", "value": true },
            "litTrue":   { "type": "BoolLit", "value": true },
            "litFalse":  { "type": "BoolLit", "value": false },
            "patTrue":   { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litTrue" },
            "patFalse":  { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litFalse" },
            "one":       { "type": "IntLit", "value": 1 },
            "zero":      { "type": "IntLit", "value": 0 },
            "caseTrue":  { "type": "MatchCase", "pattern": "patTrue", "body": "one" },
            "caseFalse": { "type": "MatchCase", "pattern": "patFalse", "body": "zero" },
            "m":         { "type": "Match", "scrutinee": "scrut", "cases": ["caseTrue", "caseFalse"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(TypeExpr.Prim(Primitive.Int), ok.rootType)
    }

    @Test
    fun `bool match with a single literal is rejected naming the missing literal`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "boolT":    { "type": "PrimitiveType", "kind": "Bool" },
            "scrut":    { "type": "BoolLit", "value": true },
            "litTrue":  { "type": "BoolLit", "value": true },
            "patTrue":  { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litTrue" },
            "one":      { "type": "IntLit", "value": 1 },
            "caseTrue": { "type": "MatchCase", "pattern": "patTrue", "body": "one" },
            "m":        { "type": "Match", "scrutinee": "scrut", "cases": ["caseTrue"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        val err = failed.errors.filterIsInstance<VerifyError.NonExhaustiveMatch>().singleOrNull()
        assertNotNull(err) { "expected NonExhaustiveMatch, got: ${failed.errors}" }
        assertEquals(listOf("false"), err!!.missingCases)
        assertEquals("Bool", err.scrutineeTypeDescription)
    }

    @Test
    fun `int literal match with wildcard catch-all verifies`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "scrut":    { "type": "IntLit", "value": 7 },
            "litSeven": { "type": "IntLit", "value": 7 },
            "pat7":     { "type": "Pattern", "kind": "literal", "patternType": "intT", "literal": "litSeven" },
            "one":      { "type": "IntLit", "value": 1 },
            "case7":    { "type": "MatchCase", "pattern": "pat7", "body": "one" },
            "wild":     { "type": "Pattern", "kind": "wildcard", "patternType": "intT" },
            "zero":     { "type": "IntLit", "value": 0 },
            "caseWild": { "type": "MatchCase", "pattern": "wild", "body": "zero" },
            "m":        { "type": "Match", "scrutinee": "scrut", "cases": ["case7", "caseWild"] }
          }
        }""")
        assertTrue(r is VerifyResult.Ok) { "expected Ok, got: $r" }
    }

    @Test
    fun `int literal match without catch-all is rejected with empty missing cases`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "scrut":    { "type": "IntLit", "value": 7 },
            "litSeven": { "type": "IntLit", "value": 7 },
            "pat7":     { "type": "Pattern", "kind": "literal", "patternType": "intT", "literal": "litSeven" },
            "one":      { "type": "IntLit", "value": 1 },
            "case7":    { "type": "MatchCase", "pattern": "pat7", "body": "one" },
            "m":        { "type": "Match", "scrutinee": "scrut", "cases": ["case7"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        val err = failed.errors.filterIsInstance<VerifyError.NonExhaustiveMatch>().singleOrNull()
        assertNotNull(err) { "expected NonExhaustiveMatch, got: ${failed.errors}" }
        assertTrue(err!!.missingCases.isEmpty())
        assertTrue(err.toString().contains("wildcard")) {
            "agent-actionable message should suggest a wildcard: $err"
        }
    }

    /**
     * Nested constructor payload patterns are not analyzed for coverage:
     * a Some(Just(n)) pattern covers the top-level Some case even though
     * the nested Just pattern does not cover Nothing. Top-level Some +
     * None coverage is exhaustive by the documented (top-level-only) rule.
     */
    @Test
    fun `nested constructor payload patterns produce no false positives`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "justCase":  { "type": "SumTypeCase", "name": "Just", "caseType": "intT" },
            "nothCase":  { "type": "SumTypeCase", "name": "Nothing", "caseType": null },
            "innerT":    { "type": "SumType", "cases": ["justCase", "nothCase"] },
            "someCase":  { "type": "SumTypeCase", "name": "Some", "caseType": "innerT" },
            "noneCase":  { "type": "SumTypeCase", "name": "None", "caseType": null },
            "outerT":    { "type": "SumType", "cases": ["someCase", "noneCase"] },
            "lit42":     { "type": "IntLit", "value": 42 },
            "inner":     { "type": "SumValue", "ofType": "innerT", "caseName": "Just", "payload": "lit42" },
            "v":         { "type": "SumValue", "ofType": "outerT", "caseName": "Some", "payload": "inner" },
            "varN":      { "type": "Pattern", "kind": "variable", "patternType": "intT", "name": "n" },
            "patJust":   { "type": "Pattern", "kind": "constructor", "patternType": "innerT", "caseName": "Just", "payloadPattern": "varN" },
            "patSome":   { "type": "Pattern", "kind": "constructor", "patternType": "outerT", "caseName": "Some", "payloadPattern": "patJust" },
            "nRef":      { "type": "VarRef", "binder": "varN" },
            "caseSome":  { "type": "MatchCase", "pattern": "patSome", "body": "nRef" },
            "patNone":   { "type": "Pattern", "kind": "constructor", "patternType": "outerT", "caseName": "None" },
            "zero":      { "type": "IntLit", "value": 0 },
            "caseNone":  { "type": "MatchCase", "pattern": "patNone", "body": "zero" },
            "m":         { "type": "Match", "scrutinee": "v", "cases": ["caseSome", "caseNone"] }
          }
        }""")
        assertTrue(r is VerifyResult.Ok) { "expected Ok (nested payload coverage is out of scope), got: $r" }
    }

    /**
     * A Recursive scrutinee that unfolds to a Sum follows the Sum rule —
     * the same unfold the constructor-pattern type check performs.
     */
    @Test
    fun `recursive sum match missing the Nil case is rejected`() {
        val r = verify("""{
          "version": 1, "root": "m",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "self":      { "type": "RecursiveSelf" },
            "headF":     { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tailF":     { "type": "ProductTypeField", "name": "tail", "fieldType": "self" },
            "consPayload": { "type": "ProductType", "fields": ["headF", "tailF"] },
            "consCase":  { "type": "SumTypeCase", "name": "Cons", "caseType": "consPayload" },
            "nilCase":   { "type": "SumTypeCase", "name": "Nil", "caseType": null },
            "listSum":   { "type": "SumType", "cases": ["consCase", "nilCase"] },
            "listT":     { "type": "RecursiveType", "body": "listSum" },
            "v":         { "type": "SumValue", "ofType": "listT", "caseName": "Nil" },
            "varP":      { "type": "Pattern", "kind": "variable", "patternType": "consPayloadUnfolded", "name": "p" },
            "headFU":    { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tailFU":    { "type": "ProductTypeField", "name": "tail", "fieldType": "listT" },
            "consPayloadUnfolded": { "type": "ProductType", "fields": ["headFU", "tailFU"] },
            "patCons":   { "type": "Pattern", "kind": "constructor", "patternType": "listT", "caseName": "Cons", "payloadPattern": "varP" },
            "zero":      { "type": "IntLit", "value": 0 },
            "caseCons":  { "type": "MatchCase", "pattern": "patCons", "body": "zero" },
            "m":         { "type": "Match", "scrutinee": "v", "cases": ["caseCons"] }
          }
        }""")
        val failed = r as VerifyResult.Failed
        val err = failed.errors.filterIsInstance<VerifyError.NonExhaustiveMatch>().singleOrNull()
        assertNotNull(err) { "expected NonExhaustiveMatch, got: ${failed.errors}" }
        assertEquals(listOf("Nil"), err!!.missingCases)
    }
}
