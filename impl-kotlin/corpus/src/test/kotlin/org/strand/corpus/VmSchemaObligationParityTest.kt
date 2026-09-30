package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.bytecode.Lowerer
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.TypeExpr
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult
import org.strand.vm.Vm

/**
 * Q-047: the bytecode VM enforces runtime schema obligations with the
 * interpreter's semantics. Each program runs on the interpreter and on the VM,
 * both given `VerifyResult.Ok.schemaObligations`, and the two must reach the
 * same value or the same [InterpretError.SchemaInvariantViolation] (site,
 * schema, invariant and value description).
 *
 * The cases cover the node positions the Lowerer treats differently from the
 * interpreter's `eval`: an Application argument, a Let-bound VarRef, a NodeRef,
 * a shared node carrying two obligations, a Lambda body the `List.Map`
 * builtin calls once per element, and sites inside a Handler body, a
 * CapabilityScope and an Attempt.
 */
class VmSchemaObligationParityTest {

    private class Program(
        val store: NodeStore,
        val root: NodeId,
        val hashToNodeId: Map<Hash, NodeId>,
        val obligations: Map<NodeId, List<TypeExpr.SchemaType>>,
        val names: Map<String, NodeId>,
    ) {
        fun id(name: String): NodeId = names.getValue(name)
    }

    private sealed interface Outcome {
        data class Val(val value: Value) : Outcome
        data class Err(val error: InterpretError) : Outcome
    }

    private fun load(nodes: String, root: String = "root"): Program {
        val json = """{ "version": 1, "root": "$root", "nodes": { $COMMON, $nodes } }"""
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "program failed verify: $verify" }
        verify as VerifyResult.Ok
        return Program(finalized.store, finalized.root, finalized.hashToNodeId, verify.schemaObligations, ingest.nameMap)
    }

    private fun interp(p: Program, caps: CapabilitySet): Outcome = try {
        Outcome.Val(Interpreter(p.store, p.hashToNodeId, schemaObligations = p.obligations).eval(p.root, caps))
    } catch (e: InterpretException) {
        Outcome.Err(e.error)
    }

    private fun vm(p: Program, caps: CapabilitySet, obligations: Map<NodeId, List<TypeExpr.SchemaType>> = p.obligations): Outcome = try {
        Outcome.Val(Vm(Lowerer(p.store, p.hashToNodeId, schemaObligations = obligations).lower(p.root)).run(caps))
    } catch (e: InterpretException) {
        Outcome.Err(e.error)
    }

    /** Run [p] on both backends and assert they agree; returns the shared outcome. */
    private fun parity(p: Program, caps: CapabilitySet = CapabilitySet.EMPTY): Outcome {
        val i = interp(p, caps)
        val v = vm(p, caps)
        assertEquals(i, v, "VM with obligations must agree with the interpreter with obligations")
        return v
    }

    private fun violation(o: Outcome): InterpretError.SchemaInvariantViolation {
        val err = assertInstanceOf(Outcome.Err::class.java, o).error
        return assertInstanceOf(InterpretError.SchemaInvariantViolation::class.java, err)
    }

    // ------------------------------------------------------------------

    @Test
    fun `a dynamic value violating the schema at an Application argument raises on both backends`() {
        val p = load(""""root": { "type": "Application", "function": "posSink", "arguments": ["bad"] }""")
        val v = violation(parity(p))
        assertEquals(p.id("bad"), v.at)
        assertEquals(p.id("posInt"), v.schema)
        assertEquals(p.id("posInv"), v.invariant)
        assertEquals(Value.IntV(-2).toString(), v.valueDescription)
    }

    @Test
    fun `a dynamic value satisfying the schema runs to the same value`() {
        val p = load("""
            "good": { "type": "Application", "function": "sub", "arguments": ["five", "three"] },
            "sink": { "type": "Application", "function": "posSink", "arguments": ["good"] },
            "ident": { "type": "Application", "function": "posId", "arguments": ["good"] },
            "root": { "type": "Application", "function": "add", "arguments": ["sink", "ident"] }
        """)
        assertEquals(Outcome.Val(Value.IntV(3)), parity(p))
    }

    @Test
    fun `a Let-bound value checked through its VarRef blames the VarRef`() {
        val p = load("""
            "vRef": { "type": "VarRef", "binder": "root" },
            "use":  { "type": "Application", "function": "posSink", "arguments": ["vRef"] },
            "root": { "type": "Let", "name": "v", "value": "bad", "body": "use" }
        """)
        assertEquals(p.id("vRef"), violation(parity(p)).at)
    }

    @Test
    fun `a NodeRef argument blames the NodeRef`() {
        val p = load("""
            "ref":  { "type": "NodeRef", "target": "bad" },
            "root": { "type": "Application", "function": "posSink", "arguments": ["ref"] }
        """)
        assertEquals(p.id("ref"), violation(parity(p)).at)
    }

    @Test
    fun `a shared node with two obligations fails on the second whichever use comes first`() {
        for (positiveFirst in listOf(true, false)) {
            val (a, b) = if (positiveFirst) "posApp" to "smallApp" else "smallApp" to "posApp"
            val p = load("""
                "v":        { "type": "Application", "function": "sub", "arguments": ["fifty", "eight"] },
                "posApp":   { "type": "Application", "function": "posSink", "arguments": ["v"] },
                "smallApp": { "type": "Application", "function": "smallSink", "arguments": ["v"] },
                "both":     { "type": "Application", "function": "add", "arguments": ["$a", "$b"] },
                "root":     { "type": "Application", "function": "add", "arguments": ["both", "v"] }
            """)
            assertEquals(2, p.obligations[p.id("v")]?.size, "both obligations recorded on the shared node")
            val v = violation(parity(p))
            assertEquals(p.id("v"), v.at)
            assertEquals(p.id("smallInt"), v.schema, "positiveFirst=$positiveFirst")
            assertEquals(Value.IntV(42).toString(), v.valueDescription)
        }
    }

    @Test
    fun `an obligation in a List Map callback body is checked on every call`() {
        // cb = \x -> match Attempt(posSink(x - 3)) { Ok(n) -> n; Err(_) -> 100 }
        // over [5, 2, 4, 1]: the second and fourth calls violate, and each
        // violation is caught inside that call, so the result (posSink
        // returns 1 on a pass) records which calls were checked.
        val p = load(LISTS + """,
            "x":      { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":   { "type": "VarRef", "binder": "x" },
            "shift":  { "type": "Application", "function": "sub", "arguments": ["xRef", "three"] },
            "guard":  { "type": "Application", "function": "posSink", "arguments": ["shift"] },
            "try":    { "type": "Attempt", "body": "guard" },
            "nV":     { "type": "Pattern", "kind": "variable", "patternType": "intT", "name": "n" },
            "okP":    { "type": "Pattern", "kind": "constructor", "patternType": "resultT", "caseName": "Ok", "payloadPattern": "nV" },
            "nRef":   { "type": "VarRef", "binder": "nV" },
            "okC":    { "type": "MatchCase", "pattern": "okP", "body": "nRef" },
            "anyP":   { "type": "Pattern", "kind": "wildcard", "patternType": "resultT" },
            "errC":   { "type": "MatchCase", "pattern": "anyP", "body": "hundred" },
            "cbBody": { "type": "Match", "scrutinee": "try", "cases": ["okC", "errC"] },
            "cb":     { "type": "Lambda", "parameters": ["x"], "body": "cbBody" },
            "root":   { "type": "Application", "function": "map", "arguments": ["list", "cb"] }
        """)
        val out = assertInstanceOf(Outcome.Val::class.java, parity(p)).value
        assertEquals(listOf(1L, 100L, 1L, 100L), ints(out))
    }

    @Test
    fun `an uncaught violation in a List Map callback stops at the violating call`() {
        val p = load(LISTS + """,
            "x":      { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":   { "type": "VarRef", "binder": "x" },
            "shift":  { "type": "Application", "function": "sub", "arguments": ["xRef", "three"] },
            "guard":  { "type": "Application", "function": "posSink", "arguments": ["shift"] },
            "cb":     { "type": "Lambda", "parameters": ["x"], "body": "guard" },
            "root":   { "type": "Application", "function": "map", "arguments": ["list", "cb"] }
        """)
        val v = violation(parity(p))
        assertEquals(p.id("shift"), v.at)
        assertEquals(Value.IntV(-1).toString(), v.valueDescription)
    }

    @Test
    fun `an obligation site inside a Handler body is checked`() {
        val p = load("""
            "s":       { "type": "ParameterDecl", "name": "s", "paramType": "strT" },
            "fake":    { "type": "Lambda", "parameters": ["s"], "body": "one" },
            "tickCall":{ "type": "Application", "function": "tick", "arguments": ["str"] },
            "guard":   { "type": "Application", "function": "posSink", "arguments": ["bad"] },
            "body":    { "type": "Application", "function": "add", "arguments": ["tickCall", "guard"] },
            "root":    { "type": "Handler", "intercept": "tickFx", "handle": "fake", "body": "body" }
        """)
        assertEquals(p.id("bad"), violation(parity(p)).at)
    }

    @Test
    fun `an obligation site inside a CapabilityScope is checked under the narrowed context`() {
        val p = load("""
            "tickCall":{ "type": "Application", "function": "tick", "arguments": ["str"] },
            "guard":   { "type": "Application", "function": "posSink", "arguments": ["bad"] },
            "body":    { "type": "Application", "function": "add", "arguments": ["tickCall", "guard"] },
            "root":    { "type": "CapabilityScope", "capabilities": ["tickFx"], "body": "body" }
        """)
        val grant = CapabilitySet.ofCategories(setOf(p.id("tickFx")))
        assertEquals(p.id("bad"), violation(parity(p, grant)).at)
    }

    @Test
    fun `an Attempt catches the violation on both backends`() {
        val p = load(RESULT + """,
            "guard": { "type": "Application", "function": "posSink", "arguments": ["bad"] },
            "root":  { "type": "Attempt", "body": "guard" }
        """)
        val out = assertInstanceOf(Outcome.Val::class.java, parity(p)).value
        assertEquals(
            Value.SumV("Err", Value.ProductV(linkedMapOf(
                "kind" to Value.StringV("schema-invariant"),
                "detail" to Value.StringV(Value.IntV(-2).toString()),
            ))),
            out,
        )
    }

    @Test
    fun `without obligations the lowering and the VM behave as before`() {
        val p = load(""""root": { "type": "Application", "function": "posSink", "arguments": ["bad"] }""")
        assertTrue(p.obligations.isNotEmpty())
        val plain = Lowerer(p.store, p.hashToNodeId).lower(p.root)
        assertEquals(plain, Lowerer(p.store, p.hashToNodeId, schemaObligations = emptyMap()).lower(p.root))
        assertNotEquals(plain, Lowerer(p.store, p.hashToNodeId, schemaObligations = p.obligations).lower(p.root))
        // Unchecked: the violating value flows through, as before Q-047's VM step.
        assertEquals(Outcome.Val(Value.IntV(1)), vm(p, CapabilitySet.EMPTY, obligations = emptyMap()))
    }

    /** The Int elements of a `Cons`/`Nil` list value. */
    private fun ints(list: Value): List<Long> {
        val out = ArrayList<Long>()
        var cur = list
        while (cur is Value.SumV && cur.case == "Cons") {
            val cell = cur.payload as Value.ProductV
            out += (cell.fields.getValue("head") as Value.IntV).v
            cur = cell.fields.getValue("tail")
        }
        return out
    }

    private companion object {
        /**
         * `PositiveInt` (n > 0) and `SmallInt` (n < 10) over Int; sinks that
         * take each and return 1 without reading the parameter; `posId`, the
         * identity on `PositiveInt`; `bad = 3 - 5`, a dynamic -2; a Test.Tick
         * effect bound to the floor-exempt `Test.EffectfulNoOp`.
         */
        val COMMON = """
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "boolT":     { "type": "PrimitiveType", "kind": "Bool" },
            "strT":      { "type": "PrimitiveType", "kind": "String" },
            "cmpT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "arithT":    { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "gt":        { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "cmpT" },
            "lt":        { "type": "ForeignNode", "target": "strand-builtin:Int.Lt", "foreignType": "cmpT" },
            "sub":       { "type": "ForeignNode", "target": "strand-builtin:Int.Sub", "foreignType": "arithT" },
            "add":       { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "arithT" },
            "zero":      { "type": "IntLit", "value": 0 },
            "one":       { "type": "IntLit", "value": 1 },
            "three":     { "type": "IntLit", "value": 3 },
            "five":      { "type": "IntLit", "value": 5 },
            "eight":     { "type": "IntLit", "value": 8 },
            "ten":       { "type": "IntLit", "value": 10 },
            "fifty":     { "type": "IntLit", "value": 50 },
            "hundred":   { "type": "IntLit", "value": 100 },
            "str":       { "type": "StringLit", "value": "t" },
            "pX":        { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "pXRef":     { "type": "VarRef", "binder": "pX" },
            "posBody":   { "type": "Application", "function": "gt", "arguments": ["pXRef", "zero"] },
            "posPred":   { "type": "Lambda", "parameters": ["pX"], "body": "posBody" },
            "posInv":    { "type": "Invariant", "invariantName": "positive", "targetSchema": "posInt", "body": "posPred" },
            "posInt":    { "type": "Schema", "schemaName": "PositiveInt", "valueType": "intT", "invariants": ["posInv"] },
            "sX":        { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "sXRef":     { "type": "VarRef", "binder": "sX" },
            "smallBody": { "type": "Application", "function": "lt", "arguments": ["sXRef", "ten"] },
            "smallPred": { "type": "Lambda", "parameters": ["sX"], "body": "smallBody" },
            "smallInv":  { "type": "Invariant", "invariantName": "small", "targetSchema": "smallInt", "body": "smallPred" },
            "smallInt":  { "type": "Schema", "schemaName": "SmallInt", "valueType": "intT", "invariants": ["smallInv"] },
            "pIn":       { "type": "ParameterDecl", "name": "p", "paramType": "posInt" },
            "posSink":   { "type": "Lambda", "parameters": ["pIn"], "body": "one" },
            "sIn":       { "type": "ParameterDecl", "name": "s", "paramType": "smallInt" },
            "smallSink": { "type": "Lambda", "parameters": ["sIn"], "body": "one" },
            "qIn":       { "type": "ParameterDecl", "name": "q", "paramType": "posInt" },
            "qRef":      { "type": "VarRef", "binder": "qIn" },
            "posId":     { "type": "Lambda", "parameters": ["qIn"], "body": "qRef" },
            "bad":       { "type": "Application", "function": "sub", "arguments": ["three", "five"] },
            "tickFx":    { "type": "EffectCategory", "categoryName": "Test.Tick" },
            "tickT":     { "type": "FunctionType", "parameters": ["strT"], "result": "intT", "effects": ["tickFx"] },
            "tick":      { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                           "foreignType": "tickT", "effects": ["tickFx"] }
        """.trimIndent()

        /** The Attempt result type `Ok(Int) | Err({kind, detail})`. */
        val RESULT = """
            "kindF":   { "type": "ProductTypeField", "name": "kind", "fieldType": "strT" },
            "detailF": { "type": "ProductTypeField", "name": "detail", "fieldType": "strT" },
            "errT":    { "type": "ProductType", "fields": ["kindF", "detailF"] },
            "okCase":  { "type": "SumTypeCase", "name": "Ok", "caseType": "intT" },
            "errCase": { "type": "SumTypeCase", "name": "Err", "caseType": "errT" },
            "resultT": { "type": "SumType", "cases": ["okCase", "errCase"] }
        """.trimIndent()

        /** An Int list type, the list [5, 2, 4, 1], `List.Map` at `(Int) -> Int`, and [RESULT]. */
        val LISTS = RESULT + ",\n" + """
            "iiT":      { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "listSelf": { "type": "RecursiveSelf" },
            "headIn":   { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tailIn":   { "type": "ProductTypeField", "name": "tail", "fieldType": "listSelf" },
            "consIn":   { "type": "ProductType", "fields": ["headIn", "tailIn"] },
            "consCase": { "type": "SumTypeCase", "name": "Cons", "caseType": "consIn" },
            "nilCase":  { "type": "SumTypeCase", "name": "Nil" },
            "listBody": { "type": "SumType", "cases": ["consCase", "nilCase"] },
            "listT":    { "type": "RecursiveType", "body": "listBody" },
            "headOut":  { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tailOut":  { "type": "ProductTypeField", "name": "tail", "fieldType": "listT" },
            "consOut":  { "type": "ProductType", "fields": ["headOut", "tailOut"] },
            "two":      { "type": "IntLit", "value": 2 },
            "four":     { "type": "IntLit", "value": 4 },
            "nil":      { "type": "SumValue", "ofType": "listT", "caseName": "Nil" },
            "h4": { "type": "ProductFieldValue", "fieldName": "head", "value": "one" },
            "t4": { "type": "ProductFieldValue", "fieldName": "tail", "value": "nil" },
            "c4": { "type": "ProductValue", "ofType": "consOut", "fields": ["h4", "t4"] },
            "l4": { "type": "SumValue", "ofType": "listT", "caseName": "Cons", "payload": "c4" },
            "h3": { "type": "ProductFieldValue", "fieldName": "head", "value": "four" },
            "t3": { "type": "ProductFieldValue", "fieldName": "tail", "value": "l4" },
            "c3": { "type": "ProductValue", "ofType": "consOut", "fields": ["h3", "t3"] },
            "l3": { "type": "SumValue", "ofType": "listT", "caseName": "Cons", "payload": "c3" },
            "h2": { "type": "ProductFieldValue", "fieldName": "head", "value": "two" },
            "t2": { "type": "ProductFieldValue", "fieldName": "tail", "value": "l3" },
            "c2": { "type": "ProductValue", "ofType": "consOut", "fields": ["h2", "t2"] },
            "l2": { "type": "SumValue", "ofType": "listT", "caseName": "Cons", "payload": "c2" },
            "h1": { "type": "ProductFieldValue", "fieldName": "head", "value": "five" },
            "t1": { "type": "ProductFieldValue", "fieldName": "tail", "value": "l2" },
            "c1": { "type": "ProductValue", "ofType": "consOut", "fields": ["h1", "t1"] },
            "list": { "type": "SumValue", "ofType": "listT", "caseName": "Cons", "payload": "c1" },
            "mapT": { "type": "FunctionType", "parameters": ["listT", "iiT"], "result": "listT" },
            "map":  { "type": "ForeignNode", "target": "strand-builtin:List.Map", "foreignType": "mapT" }
        """.trimIndent()
    }
}
