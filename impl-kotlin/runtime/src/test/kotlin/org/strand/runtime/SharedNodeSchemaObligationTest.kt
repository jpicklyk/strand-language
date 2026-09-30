package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Value

/**
 * Q-076: two different Schema obligations on one shared node. Strand graphs
 * are content-addressed DAGs, so one value node routinely flows into several
 * parents; a node that reaches both a `PositiveInt` position (n > 0) and a
 * `SmallInt` position (n < 10) carries both obligations. Each is checked
 * independently — at verify time for a statically-known value, at runtime for
 * a dynamic one — whichever order the two uses appear in. Before the
 * per-obligation record, the obligation recorded last replaced the first, so
 * one of the two orders admitted a value violating the other schema.
 */
class SharedNodeSchemaObligationTest {

    private data class Program(val image: ProgramImage, val names: Map<String, NodeId>)

    /**
     * `Int.Add(Int.Add(first(v), second(v)), v)` where `posSink : PositiveInt
     * -> Int` and `smallSink : SmallInt -> Int` both return 1 without reading
     * their parameter, and `v` is one shared node. The sinks never reference
     * the parameter, so the value-flow obligation on `v` itself is the only
     * check each schema gets (a VarRef to a schema-typed parameter would carry
     * its own obligation and mask the shared-node record). The trailing plain
     * use of `v` re-infers it at `Int` after both schema uses.
     * [positiveFirst] picks which use the argument loop reaches first;
     * [valueNodes] supplies the `v` node and any helpers it needs.
     */
    private fun program(positiveFirst: Boolean, valueNodes: String): Program {
        val (a, b) = if (positiveFirst) "posApp" to "smallApp" else "smallApp" to "posApp"
        val json = """{
          "version": 1, "root": "root",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "boolT":     { "type": "PrimitiveType", "kind": "Bool" },
            "cmpT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "gt":        { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "cmpT" },
            "lt":        { "type": "ForeignNode", "target": "strand-builtin:Int.Lt", "foreignType": "cmpT" },
            "zero":      { "type": "IntLit", "value": 0 },
            "one":       { "type": "IntLit", "value": 1 },
            "ten":       { "type": "IntLit", "value": 10 },
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
            "posApp":    { "type": "Application", "function": "posSink", "arguments": ["v"] },
            "smallApp":  { "type": "Application", "function": "smallSink", "arguments": ["v"] },
            "addT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":       { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "both":      { "type": "Application", "function": "add", "arguments": ["$a", "$b"] },
            "root":      { "type": "Application", "function": "add", "arguments": ["both", "v"] },
            $valueNodes
          }
        }"""
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Program(ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId), ingest.nameMap)
    }

    private fun staticValue(n: Long) = """"v": { "type": "IntLit", "value": $n }"""

    /** `v = Int.Sub(a, b)`: an Application, never statically known. */
    private fun dynamicValue(a: Long, b: Long) = """
        "subT": { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
        "sub":  { "type": "ForeignNode", "target": "strand-builtin:Int.Sub", "foreignType": "subT" },
        "va":   { "type": "IntLit", "value": $a },
        "vb":   { "type": "IntLit", "value": $b },
        "v":    { "type": "Application", "function": "sub", "arguments": ["va", "vb"] }"""

    private val runtime = StrandRuntime(HostPolicy.OPEN)

    /** Run [check] with the PositiveInt use first, then with the SmallInt use first. */
    private fun bothOrders(check: (positiveFirst: Boolean) -> Unit) {
        for (positiveFirst in listOf(true, false)) {
            try {
                check(positiveFirst)
            } catch (e: AssertionError) {
                throw AssertionError("positiveFirst=$positiveFirst: ${e.message}", e)
            }
        }
    }

    private fun Program.id(name: String): NodeId = names.getValue(name)

    // ---- static: the SchemaChecker rejects at verify time ------------------

    @Test
    fun `a shared literal violating SmallInt is rejected whichever use comes first`() = bothOrders { positiveFirst ->
        val p = program(positiveFirst, staticValue(42))
        val outcome = assertInstanceOf(RunOutcome.SchemaViolation::class.java, runtime.run(p.image))
        val violation = outcome.schema.violations.singleOrNull()
            ?: throw AssertionError("expected exactly the SmallInt violation: ${outcome.schema.violations}")
        assertEquals(p.id("smallInt"), violation.schema)
        assertEquals(p.id("v"), violation.at)
    }

    @Test
    fun `a shared literal violating PositiveInt is rejected whichever use comes first`() = bothOrders { positiveFirst ->
        val p = program(positiveFirst, staticValue(-3))
        val outcome = assertInstanceOf(RunOutcome.SchemaViolation::class.java, runtime.run(p.image))
        val violation = outcome.schema.violations.singleOrNull()
            ?: throw AssertionError("expected exactly the PositiveInt violation: ${outcome.schema.violations}")
        assertEquals(p.id("posInt"), violation.schema)
        assertEquals(p.id("v"), violation.at)
    }

    @Test
    fun `a shared literal satisfying both schemas runs to its value`() = bothOrders { positiveFirst ->
        val p = program(positiveFirst, staticValue(5))
        val outcome = assertInstanceOf(RunOutcome.Ok::class.java, runtime.run(p.image))
        assertEquals(Value.IntV(7), outcome.value)
        assertEquals(
            setOf(p.id("posInt"), p.id("smallInt")),
            outcome.verify.schemaObligations[p.id("v")].orEmpty().map { it.schemaId }.toSet(),
            "both obligations are recorded on the shared literal",
        )
        // Statically known: checked at verify time, not deferred.
        assertTrue(outcome.schema.deferred.none { it.at == p.id("v") }) { "${outcome.schema.deferred}" }
    }

    // ---- dynamic: the interpreter enforces at runtime ----------------------

    @Test
    fun `a shared dynamic value violating SmallInt raises at runtime whichever use comes first`() = bothOrders { positiveFirst ->
        val p = program(positiveFirst, dynamicValue(50, 8))
        val ex = assertThrows(InterpretException::class.java) { runtime.run(p.image) }
        val err = assertInstanceOf(InterpretError.SchemaInvariantViolation::class.java, ex.error)
        assertEquals(p.id("smallInt"), err.schema)
        assertEquals(p.id("v"), err.at)
        assertTrue(err.valueDescription.contains("42")) { err.valueDescription }
    }

    @Test
    fun `a shared dynamic value violating PositiveInt raises at runtime whichever use comes first`() = bothOrders { positiveFirst ->
        val p = program(positiveFirst, dynamicValue(3, 6))
        val ex = assertThrows(InterpretException::class.java) { runtime.run(p.image) }
        val err = assertInstanceOf(InterpretError.SchemaInvariantViolation::class.java, ex.error)
        assertEquals(p.id("posInt"), err.schema)
        assertEquals(p.id("v"), err.at)
        assertTrue(err.valueDescription.contains("-3")) { err.valueDescription }
    }

    @Test
    fun `a shared dynamic value satisfying both schemas runs and defers each obligation`() = bothOrders { positiveFirst ->
        val p = program(positiveFirst, dynamicValue(8, 3))
        val outcome = assertInstanceOf(RunOutcome.Ok::class.java, runtime.run(p.image))
        assertEquals(Value.IntV(7), outcome.value)
        val deferredOnV = outcome.schema.deferred.filter { it.at == p.id("v") }
        assertEquals(2, deferredOnV.size, "one deferral per obligation on the shared node: $deferredOnV")
        assertEquals(setOf(p.id("posInt"), p.id("smallInt")), deferredOnV.map { it.schema }.toSet())
    }
}
