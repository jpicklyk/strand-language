package org.strand.corpus

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.strand.bytecode.Lowerer
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher
import org.strand.interpreter.AuditOutcome
import org.strand.interpreter.AuditRecord
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.CollectingAuditSink
import org.strand.interpreter.HostContext
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult
import org.strand.vm.Vm

/**
 * N-044 ToolDef on the bytecode VM. The Lowerer lowers a ToolDef to its
 * implementation expression plus `MAKE_TOOLDEF`, which builds the
 * interpreter's `Value.ToolDefV` with the VM callable boxed as the
 * implementation. A builtin that runs the tool hands the implementation back
 * through `Builtins.ApplyFn`, so it runs in the VM with the checks every
 * higher-order callback gets.
 *
 * Each case runs one verified program on both backends, each with its own
 * collecting audit sink, and asserts equal values or equal errors and equal
 * audit records. The tool is run by `strand-builtin:Test.VmTool.Call`, a
 * higher-order test builtin standing in for a provider's tool-use loop (it
 * applies the ToolDef's implementation to its second argument).
 */
class VmToolDefParityTest {

    @BeforeEach
    fun installCaller() {
        Builtins.installTestHigherOrderBuiltin(CALL, effectful = false, Builtins.Determinism.Deterministic) { _, args, apply ->
            val tool = args[0] as Value.ToolDefV
            apply.apply(tool.implementation, listOf(args[1]))
        }
    }

    @AfterEach
    fun clearCaller() = Builtins.clearTestBuiltins()

    private class Program(
        val store: NodeStore,
        val root: NodeId,
        val hashToNodeId: Map<Hash, NodeId>,
        val verify: VerifyResult.Ok,
        val names: Map<String, NodeId>,
    ) {
        fun id(name: String): NodeId = names.getValue(name)
    }

    private fun load(nodes: String): Program {
        val json = """{ "version": 1, "root": "root", "nodes": { $COMMON, $nodes } }"""
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "program failed verify: $verify" }
        return Program(finalized.store, finalized.root, finalized.hashToNodeId, verify as VerifyResult.Ok, ingest.nameMap)
    }

    private sealed interface Outcome {
        data class Val(val value: Value) : Outcome
        data class Err(val error: InterpretError) : Outcome
    }

    private fun host(p: Program, sink: CollectingAuditSink): HostContext =
        HostContext.processDefault().copy(auditSink = sink, verifiedInterceptions = p.verify.verifiedInterceptions)

    private fun outcome(block: () -> Value): Outcome = try {
        Outcome.Val(block())
    } catch (e: InterpretException) {
        Outcome.Err(e.error)
    }

    /** Run [p] on both backends under [caps]; assert outcome and audit parity; return both. */
    private fun parity(p: Program, caps: CapabilitySet): Pair<Outcome, List<AuditRecord>> {
        val iSink = CollectingAuditSink()
        val vSink = CollectingAuditSink()
        val i = outcome { Interpreter(p.store, p.hashToNodeId, hostContext = host(p, iSink)).eval(p.root, caps) }
        val v = outcome { Vm(Lowerer(p.store, p.hashToNodeId).lower(p.root), host(p, vSink)).run(caps) }
        assertEquals(i, v, "VM outcome must equal the interpreter's")
        assertEquals(iSink.records, vSink.records, "audit records must be equal")
        return v to vSink.records
    }

    private fun refined(category: NodeId, value: String) = CapabilitySet(mapOf(
        category to listOf(CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV(value))))),
    ))

    // ------------------------------------------------------------------

    /** impl = \s -> tick(s), a Lambda declaring Test.Tick. */
    private val lambdaTool = """
        "s":     { "type": "ParameterDecl", "name": "s", "paramType": "strT" },
        "sRef":  { "type": "VarRef", "binder": "s" },
        "tickS": { "type": "Application", "function": "tick", "arguments": ["sRef"] },
        "impl":  { "type": "Lambda", "parameters": ["s"], "body": "tickS", "effects": ["tickFx"] },
        "tool":  { "type": "ToolDef", "name": "t", "description": "d", "parameterSchema": "toolSchema", "implementation": "impl" }
    """.trimIndent()

    @Test
    fun `a Lambda tool implementation run by a builtin has value and audit parity`() {
        val p = load("""$lambdaTool,
            "root": { "type": "Application", "function": "callTool", "arguments": ["tool", "str"] }
        """)
        val (out, audits) = parity(p, CapabilitySet.ofCategories(setOf(p.id("tickFx"))))
        assertEquals(Outcome.Val(Value.IntV(0)), out)
        assertEquals(listOf("Test.Tick"), audits.filter { it.outcome == AuditOutcome.Allowed }.map { it.effectCategory })
    }

    @Test
    fun `a tool implementation whose effect is not granted is denied identically`() {
        val p = load("""$lambdaTool,
            "root": { "type": "Application", "function": "callTool", "arguments": ["tool", "str"] }
        """)
        val (out, _) = parity(p, CapabilitySet.EMPTY)
        val err = assertInstanceOf(Outcome.Err::class.java, out).error
        val denial = assertInstanceOf(InterpretError.CapabilityViolation::class.java, err)
        assertEquals(p.id("root"), denial.at)
    }

    @Test
    fun `a projected foreign tool implementation is refinement-checked on its argument`() {
        val p = load("""
            "writeT": { "type": "FunctionType", "parameters": ["strT"], "result": "intT", "effects": ["catW"] },
            "impl":   { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp", "foreignType": "writeT",
                        "effects": ["catW"],
                        "effectProjections": [ { "category": "catW", "sources": [ { "kind": "ArgRef", "index": 0 } ] } ] },
            "tool":   { "type": "ToolDef", "name": "t", "description": "d", "parameterSchema": "toolSchema", "implementation": "impl" },
            "root":   { "type": "Application", "function": "callTool", "arguments": ["tool", "str"] }
        """)
        val (allowed, audits) = parity(p, refined(p.id("catW"), "t"))
        assertEquals(Outcome.Val(Value.IntV(0)), allowed)
        assertEquals(listOf(listOf("t")), audits.map { it.refinementParameters })
        val (denied, _) = parity(p, refined(p.id("catW"), "u"))
        val err = assertInstanceOf(Outcome.Err::class.java, denied).error
        assertInstanceOf(InterpretError.RefinementViolation::class.java, err)
    }

    @Test
    fun `a Handler around the tool call intercepts the implementation's effect`() {
        val p = load("""$lambdaTool,
            "h":     { "type": "ParameterDecl", "name": "h", "paramType": "strT" },
            "fake":  { "type": "Lambda", "parameters": ["h"], "body": "seven" },
            "call":  { "type": "Application", "function": "callTool", "arguments": ["tool", "str"] },
            "root":  { "type": "Handler", "intercept": "tickFx", "handle": "fake", "body": "call" }
        """)
        assertTrue(p.verify.verifiedInterceptions[p.id("tickS")].orEmpty().contains(p.id("root"))) {
            "the verifier checks the interception inside the tool implementation"
        }
        // The implementation Lambda's declared Test.Tick is checked where the
        // builtin applies it (category presence); the tick call inside it is
        // the Handler's, so nothing is performed.
        val (out, audits) = parity(p, CapabilitySet.ofCategories(setOf(p.id("tickFx"))))
        assertEquals(Outcome.Val(Value.IntV(7)), out)
        assertTrue(audits.isEmpty()) { "an intercepted call performs nothing: $audits" }
    }

    @Test
    fun `a tool implementation capturing an outer binder sees its value`() {
        // let k = "abcd" in callTool(ToolDef(\s -> len(k) + len(s)), "t") = 5,
        // run twice so the boxed implementation is reused.
        val p = load("""
            "s":     { "type": "ParameterDecl", "name": "s", "paramType": "strT" },
            "sRef":  { "type": "VarRef", "binder": "s" },
            "kRef":  { "type": "VarRef", "binder": "root" },
            "lenK":  { "type": "Application", "function": "len", "arguments": ["kRef"] },
            "lenS":  { "type": "Application", "function": "len", "arguments": ["sRef"] },
            "sum":   { "type": "Application", "function": "add", "arguments": ["lenK", "lenS"] },
            "impl":  { "type": "Lambda", "parameters": ["s"], "body": "sum" },
            "tool":  { "type": "ToolDef", "name": "t", "description": "d", "parameterSchema": "toolSchema", "implementation": "impl" },
            "call":  { "type": "Application", "function": "callTool", "arguments": ["tool", "str"] },
            "twice": { "type": "Application", "function": "add", "arguments": ["call", "call"] },
            "abcd":  { "type": "StringLit", "value": "abcd" },
            "root":  { "type": "Let", "name": "k", "value": "abcd", "body": "twice" }
        """)
        val (out, _) = parity(p, CapabilitySet.EMPTY)
        assertEquals(Outcome.Val(Value.IntV(10)), out)
    }

    private companion object {
        const val CALL = "strand-builtin:Test.VmTool.Call"

        val COMMON = """
            "intT":       { "type": "PrimitiveType", "kind": "Int" },
            "strT":       { "type": "PrimitiveType", "kind": "String" },
            "bytesT":     { "type": "PrimitiveType", "kind": "Bytes" },
            "tickFx":     { "type": "EffectCategory", "categoryName": "Test.Tick" },
            "catW":       { "type": "EffectCategory", "categoryName": "Test.W", "parameters": ["strT"] },
            "tickT":      { "type": "FunctionType", "parameters": ["strT"], "result": "intT", "effects": ["tickFx"] },
            "tick":       { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                            "foreignType": "tickT", "effects": ["tickFx"] },
            "lenT":       { "type": "FunctionType", "parameters": ["strT"], "result": "intT" },
            "len":        { "type": "ForeignNode", "target": "strand-builtin:String.Length", "foreignType": "lenT" },
            "addT":       { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":        { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "str":        { "type": "StringLit", "value": "t" },
            "seven":      { "type": "IntLit", "value": 7 },
            "toolSchema": { "type": "Schema", "schemaName": "ToolParam", "valueType": "strT", "invariants": [] },
            "callToolT":  { "type": "FunctionType", "parameters": ["bytesT", "strT"], "result": "intT" },
            "callTool":   { "type": "ForeignNode", "target": "$CALL", "foreignType": "callToolT" }
        """.trimIndent()
    }
}
