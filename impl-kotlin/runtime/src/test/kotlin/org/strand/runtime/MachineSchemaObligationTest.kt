package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult

/**
 * Q-047 on the machine path. `StrandRuntime.run` hands the verify result's
 * schema obligations to its interpreter; the `VerifyResult.Ok` overloads of
 * `runMachine` / `runGroup` / `resume` do the same for every interpreter the
 * machine path builds, so a value violating a schema invariant cannot reach a
 * schema-typed parameter inside a transition or an `initialState`.
 *
 * The machine: state `Int`, events `Int`. Each transition computes
 * `v = Int.Sub(e, 10)` and passes it to `posSink : PositiveInt -> Int`
 * (PositiveInt = `n > 0`), adding the sink's `1` to the state. An event
 * above 10 transitions; any other event violates PositiveInt at `v`. The
 * initial state is `posSink(Int.Sub(a, b))`, dynamic too, so the verify-time
 * SchemaChecker defers both obligations to runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachineSchemaObligationTest {

    private class Program(
        val image: ProgramImage,
        val verified: VerifyResult.Ok,
        val names: Map<String, NodeId>,
        val nodeIdToHash: Map<NodeId, Hash>,
    ) {
        fun id(name: String): NodeId = names.getValue(name)
    }

    /** The machine nodes, parameterised by the initial value `a - b` and the machine's input stream. */
    private fun machineNodes(suffix: String, initA: Long, initB: Long, stream: String) = """
        "ia$suffix":   { "type": "IntLit", "value": $initA },
        "ib$suffix":   { "type": "IntLit", "value": $initB },
        "initV$suffix": { "type": "Application", "function": "sub", "arguments": ["ia$suffix", "ib$suffix"] },
        "init$suffix": { "type": "Application", "function": "posSink", "arguments": ["initV$suffix"] },
        "m$suffix": {
          "type": "StateMachine",
          "transitionFn": "lam",
          "initialState": "init$suffix",
          "inputStreams": ["$stream"],
          "outputStreams": [],
          "effects": ["receiveFx"]
        }"""

    private val shared = """
        "intT":      { "type": "PrimitiveType", "kind": "Int" },
        "boolT":     { "type": "PrimitiveType", "kind": "Bool" },
        "cmpT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
        "arithT":    { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
        "gt":        { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "cmpT" },
        "sub":       { "type": "ForeignNode", "target": "strand-builtin:Int.Sub", "foreignType": "arithT" },
        "add":       { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "arithT" },
        "zero":      { "type": "IntLit", "value": 0 },
        "one":       { "type": "IntLit", "value": 1 },
        "ten":       { "type": "IntLit", "value": 10 },
        "pX":        { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
        "pXRef":     { "type": "VarRef", "binder": "pX" },
        "posBody":   { "type": "Application", "function": "gt", "arguments": ["pXRef", "zero"] },
        "posPred":   { "type": "Lambda", "parameters": ["pX"], "body": "posBody" },
        "posInv":    { "type": "Invariant", "invariantName": "positive", "targetSchema": "posInt", "body": "posPred" },
        "posInt":    { "type": "Schema", "schemaName": "PositiveInt", "valueType": "intT", "invariants": ["posInv"] },
        "pIn":       { "type": "ParameterDecl", "name": "p", "paramType": "posInt" },
        "posSink":   { "type": "Lambda", "parameters": ["pIn"], "body": "one" },
        "emptyT":    { "type": "ProductType", "fields": [] },
        "sft":       { "type": "ProductTypeField", "name": "state",   "fieldType": "intT" },
        "oft":       { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
        "resT":      { "type": "ProductType", "fields": ["sft", "oft"] },
        "sP":        { "type": "ParameterDecl", "name": "s", "paramType": "intT" },
        "eP":        { "type": "ParameterDecl", "name": "e", "paramType": "intT" },
        "sRef":      { "type": "VarRef", "binder": "sP" },
        "eRef":      { "type": "VarRef", "binder": "eP" },
        "v":         { "type": "Application", "function": "sub", "arguments": ["eRef", "ten"] },
        "sinkApp":   { "type": "Application", "function": "posSink", "arguments": ["v"] },
        "next":      { "type": "Application", "function": "add", "arguments": ["sRef", "sinkApp"] },
        "sV":        { "type": "ProductFieldValue", "fieldName": "state", "value": "next" },
        "emptyV":    { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
        "oV":        { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
        "result":    { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
        "lam":       { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
        "receiveFx": { "type": "EffectCategory", "categoryName": "StateMachine.Receive" }"""

    private fun load(json: String): Program {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val image = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        val outcome = runtime.verifyAndCheckSchema(image)
        val ok = assertInstanceOf(VerifyOutcome.Ok::class.java, outcome) { "fixture failed verify: $outcome" }
        assertTrue(ok.schema.violations.isEmpty()) { "fixture failed the static schema pass: ${ok.schema.violations}" }
        return Program(image, ok.verify, ingest.nameMap, finalized.nodeIdToHash)
    }

    /** One machine rooted at `m`, initial state `posSink(initA - initB)`. */
    private fun single(initA: Long = 6, initB: Long = 3): Program = load("""{
        "version": 1, "root": "m",
        "nodes": {
          $shared,
          "stream": { "type": "EventStream", "eventType": "intT", "streamKind": "external" },
          ${machineNodes("", initA, initB, "stream")}
        }
    }""")

    /**
     * Two machines on distinct external streams (distinguished by
     * `bufferSize`, since structurally equal streams share a NodeId), held
     * by a Let so verification reaches both.
     */
    private fun pair(): Program = load("""{
        "version": 1, "root": "both",
        "nodes": {
          $shared,
          "streamA": { "type": "EventStream", "eventType": "intT", "streamKind": "external", "bufferSize": 16 },
          "streamB": { "type": "EventStream", "eventType": "intT", "streamKind": "external", "bufferSize": 32 },
          ${machineNodes("A", 6, 3, "streamA")},
          ${machineNodes("B", 7, 3, "streamB")},
          "both": { "type": "Let", "name": "_a", "value": "mA", "body": "mB" }
        }
    }""")

    private val runtime = StrandRuntime(HostPolicy.OPEN)

    private fun ints(vararg xs: Long): List<Value> = xs.map { Value.IntV(it) }

    // ---- sync fold -------------------------------------------------------

    @Test
    fun `a transition value violating the schema halts the fold with SchemaViolation`() {
        val p = single()
        val trace = runtime.runMachine(p.image, p.image.root, ints(20, 15, 3, 30), CapabilitySet.EMPTY, p.verified)

        assertEquals(2, trace.steps.size, "the two events before the violating one transitioned")
        val halt = assertInstanceOf(HaltReason.SchemaViolation::class.java, trace.final.reason)
        assertEquals(2, halt.atEventIndex)
        assertEquals(p.id("v"), halt.error.at)
        assertEquals(p.id("posInt"), halt.error.schema)
        assertEquals(p.id("posInv"), halt.error.invariant)
        assertTrue(halt.error.valueDescription.contains("-7")) { halt.error.valueDescription }
        assertEquals(Value.IntV(3), trace.final.finalState, "initial 1 plus two successful transitions")
    }

    @Test
    fun `the loose-parameter form still enforces nothing`() {
        val p = single()
        val trace = runtime.runMachine(
            p.image, p.image.root, ints(20, 15, 3, 30), CapabilitySet.EMPTY,
            p.verified.nodeTypes, p.verified.verifiedInterceptions,
        )
        assertEquals(4, trace.steps.size)
        assertEquals(HaltReason.EventsExhausted, trace.final.reason)
    }

    @Test
    fun `an initialState value violating the schema raises before any event`() {
        val p = single(initA = 3, initB = 6)
        val ex = assertThrows(InterpretException::class.java) {
            runtime.runMachine(p.image, p.image.root, ints(20), CapabilitySet.EMPTY, p.verified)
        }
        val err = assertInstanceOf(InterpretError.SchemaInvariantViolation::class.java, ex.error)
        assertEquals(p.id("initV"), err.at)
        assertEquals(p.id("posInt"), err.schema)
        assertTrue(err.valueDescription.contains("-3")) { err.valueDescription }
    }

    @Test
    fun `values satisfying the schema run to EventsExhausted`() {
        val p = single()
        val trace = runtime.runMachine(p.image, p.image.root, ints(20, 15, 30), CapabilitySet.EMPTY, p.verified)
        assertEquals(3, trace.steps.size)
        assertEquals(HaltReason.EventsExhausted, trace.final.reason)
        assertEquals(Value.IntV(4), trace.final.finalState)
    }

    @Test
    fun `resume enforces the obligations on post-snapshot transitions`() {
        val p = single()
        val snapshot = Snapshot(
            machineHash = p.nodeIdToHash.getValue(p.image.root),
            snapshotState = Value.IntV(5),
            processedEventCount = 4,
        )
        val trace = runtime.resume(
            p.image, p.image.root, snapshot, ints(11, 10, 12), p.nodeIdToHash, CapabilitySet.EMPTY, p.verified,
        )
        assertEquals(1, trace.steps.size)
        val halt = assertInstanceOf(HaltReason.SchemaViolation::class.java, trace.final.reason)
        assertEquals(1, halt.atEventIndex)
        assertEquals(p.id("v"), halt.error.at)
        assertEquals(Value.IntV(6), trace.final.finalState)
    }

    // ---- async group -----------------------------------------------------

    @Test
    fun `a transition violation halts only that actor and its sibling runs on`() = runTest {
        val p = pair()
        val group = MachineGroup(
            store = p.image.store,
            hashToNodeId = p.image.hashToNodeId,
            machines = listOf(p.id("mA"), p.id("mB")),
        )
        val handle = runtime.runGroup(p.image, group, this, p.verified)
        val inA = handle.externalInputs.getValue(p.id("streamA"))
        val inB = handle.externalInputs.getValue(p.id("streamB"))
        inA.send(Value.IntV(20))
        inA.send(Value.IntV(4))   // violates PositiveInt: 4 - 10 = -6
        inA.send(Value.IntV(30))  // discarded after the halt
        inA.close()
        for (e in ints(11, 12, 13)) inB.send(e)
        inB.close()
        handle.await()

        val byMachine = handle.allInstances.values.associateBy { it.machineNodeId }
        val a = byMachine.getValue(p.id("mA"))
        val b = byMachine.getValue(p.id("mB"))

        val halt = assertInstanceOf(HaltReason.SchemaViolation::class.java, a.haltReason)
        assertEquals(1, halt.atEventIndex)
        assertEquals(p.id("v"), halt.error.at)
        assertEquals(Value.IntV(2), a.currentState)

        assertEquals(HaltReason.EventsExhausted, b.haltReason)
        assertEquals(Value.IntV(4), b.currentState, "the sibling processed all three events")

        assertEquals(setOf(a.instanceId), handle.schemaViolations().keys)
        assertTrue(handle.failedInstances().isEmpty()) { "${handle.failedInstances()}" }
    }

    @Test
    fun `an initialState violation at group start propagates from runGroup`() = runTest {
        val p = single(initA = 3, initB = 6)
        val group = MachineGroup(
            store = p.image.store,
            hashToNodeId = p.image.hashToNodeId,
            machines = listOf(p.image.root),
        )
        val ex = assertThrows(InterpretException::class.java) {
            runtime.runGroup(p.image, group, this, p.verified)
        }
        val err = assertInstanceOf(InterpretError.SchemaInvariantViolation::class.java, ex.error)
        assertEquals(p.id("initV"), err.at)
    }

    @Test
    fun `a dispatcher factory receives the obligations through its wiring`() = runTest {
        val p = single()
        val group = MachineGroup(
            store = p.image.store,
            hashToNodeId = p.image.hashToNodeId,
            machines = listOf(p.image.root),
            dispatcherFactory = InterpreterDispatcherFactory(p.image.store, p.image.hashToNodeId),
        )
        val handle = runtime.runGroup(p.image, group, this, p.verified)
        val input = handle.externalInputs.getValue(p.id("stream"))
        input.send(Value.IntV(20))
        input.send(Value.IntV(1))
        input.close()
        handle.await()

        val instance = handle.allInstances.values.single()
        val halt = assertInstanceOf(HaltReason.SchemaViolation::class.java, instance.haltReason)
        assertEquals(1, halt.atEventIndex)
    }
}
