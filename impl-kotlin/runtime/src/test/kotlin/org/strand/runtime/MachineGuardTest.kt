package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.AuditOutcome
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.CollectingAuditSink
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult

/**
 * Q-073 on the machine path: [StrandRuntime.runMachineGuarded] and
 * [StrandRuntime.runGroupGuarded] refuse, before anything is evaluated, a
 * machine or group whose [org.strand.verifier.ProgramAnalysis.machineClosure]
 * / [org.strand.verifier.ProgramAnalysis.groupClosure] bound is not within
 * the budget, and otherwise run under the budget. "Nothing evaluated" is
 * observed through the audit log: every capability check the interpreter
 * makes (allowed or denied) records to [HostPolicy.auditSink], so an empty
 * log after a refusal means no effectful call site was reached.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachineGuardTest {

    private class Loaded(val image: ProgramImage, val names: Map<String, NodeId>) {
        fun id(name: String): NodeId = names.getValue(name)
        fun grant(vararg categories: String): CapabilitySet = CapabilitySet.ofCategories(categories.map(::id).toSet())
    }

    private fun load(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Loaded(ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId), ingest.nameMap)
    }

    private fun audited(): Pair<StrandRuntime, CollectingAuditSink> {
        val sink = CollectingAuditSink()
        return StrandRuntime(HostPolicy.OPEN.copy(auditSink = sink)) to sink
    }

    private val events = listOf<Value>(Value.IntV(1), Value.IntV(2))

    // ---- one machine -----------------------------------------------------

    @Test
    fun `a transition effect outside the budget is refused with nothing performed`() {
        val p = load(Fixtures.program(transition = Fixtures.DIRECT_WRITE, effects = """["receiveFx", "writeFx"]"""))
        val (rt, sink) = audited()
        val outcome = rt.runMachineGuarded(p.image, p.id("m"), events, p.grant("receiveFx"))

        val refused = assertInstanceOf(GuardedMachineOutcome.Refused::class.java, outcome)
        assertEquals(mapOf(p.id("writeFx") to EffectChannel.DIRECT), refused.report.exceeding)
        assertEquals(setOf(p.id("receiveFx"), p.id("writeFx")), refused.report.requested)
        assertEquals(setOf(p.id("receiveFx")), refused.report.granted)
        assertTrue(sink.records.isEmpty()) { "refusal must precede every capability check: ${sink.records}" }
    }

    @Test
    fun `an effect reached only through initialState is refused before initialState runs`() {
        val p = load(Fixtures.program(initial = Fixtures.WRITING_INITIAL, effects = """["receiveFx", "writeFx"]"""))
        val (rt, sink) = audited()
        val outcome = rt.runMachineGuarded(p.image, p.id("m"), events, p.grant("receiveFx"))

        val refused = assertInstanceOf(GuardedMachineOutcome.Refused::class.java, outcome)
        assertEquals(mapOf(p.id("writeFx") to EffectChannel.DIRECT), refused.report.exceeding)
        assertTrue(sink.records.isEmpty()) { "${sink.records}" }
    }

    /**
     * The machine declares only `StateMachine.Receive`, yet its transition
     * hands a Filesystem.Write callback to `List.Map`: the verifier accepts
     * the row, and an unguarded run under a grant that includes the write
     * performs it. The gate sees it on the latent channel.
     */
    @Test
    fun `an effectful callback handed to List Map in a transition is refused on the latent channel`() {
        val p = load(Fixtures.program(transition = Fixtures.MAP_CALLBACK))
        val (rt, sink) = audited()
        val outcome = rt.runMachineGuarded(p.image, p.id("m"), events, p.grant("receiveFx"))

        val refused = assertInstanceOf(GuardedMachineOutcome.Refused::class.java, outcome)
        assertEquals(mapOf(p.id("writeFx") to EffectChannel.LATENT), refused.report.exceeding)
        assertTrue(sink.records.isEmpty()) { "${sink.records}" }

        // The declared row alone is not a bound: unguarded, the write happens.
        val (rt2, sink2) = audited()
        val verify = rt2.verify(p.image) as VerifyResult.Ok
        rt2.runMachine(p.image, p.id("m"), events, p.grant("receiveFx", "writeFx"), verify)
        assertTrue(sink2.records.any { it.effectCategory == "Filesystem.Write" && it.outcome == AuditOutcome.Allowed }) {
            "${sink2.records}"
        }
    }

    @Test
    fun `a machine within budget runs and equals runMachine under the same grant`() {
        val p = load(Fixtures.program(transition = Fixtures.DIRECT_WRITE, effects = """["receiveFx", "writeFx"]"""))
        val budget = p.grant("receiveFx", "writeFx")

        val (guardedRt, guardedSink) = audited()
        val ran = assertInstanceOf(
            GuardedMachineOutcome.Ran::class.java,
            guardedRt.runMachineGuarded(p.image, p.id("m"), events, budget),
        )

        val (plainRt, plainSink) = audited()
        val verify = plainRt.verify(p.image) as VerifyResult.Ok
        val plain = plainRt.runMachine(p.image, p.id("m"), events, budget, verify)

        assertEquals(plain, ran.trace)
        assertEquals(2, ran.trace.steps.size)
        assertEquals(HaltReason.EventsExhausted, ran.trace.final.reason)
        assertEquals(plainSink.records, guardedSink.records)
        assertEquals(2, guardedSink.records.count { it.effectCategory == "Filesystem.Write" })
    }

    @Test
    fun `the guarded machine run enforces runtime schema obligations`() {
        val p = load(Fixtures.SCHEMA_MACHINE)
        val (rt, _) = audited()
        val ran = assertInstanceOf(
            GuardedMachineOutcome.Ran::class.java,
            rt.runMachineGuarded(p.image, p.id("m"), listOf(Value.IntV(20), Value.IntV(3)), p.grant("receiveFx")),
        )
        val halt = assertInstanceOf(HaltReason.SchemaViolation::class.java, ran.trace.final.reason)
        assertEquals(1, halt.atEventIndex)
    }

    @Test
    fun `a node that is not a verified machine is a host error`() {
        val p = load(Fixtures.program())
        val (rt, _) = audited()
        assertThrows(IllegalArgumentException::class.java) {
            rt.runMachineGuarded(p.image, p.id("lam"), events, CapabilitySet.EMPTY)
        }
    }

    @Test
    fun `a non-verifying program returns VerifyFailed`() {
        val p = load(Fixtures.program(transition = Fixtures.DIRECT_WRITE))  // row omits writeFx
        val (rt, _) = audited()
        val outcome = rt.runMachineGuarded(p.image, p.id("m"), events, CapabilitySet.EMPTY)
        assertInstanceOf(GuardedMachineOutcome.VerifyFailed::class.java, outcome)
    }

    // ---- groups ----------------------------------------------------------

    @Test
    fun `a group refusal names the source opener's effect before any source is opened`() = runTest {
        val p = load(Fixtures.BRIDGED)
        val (rt, sink) = audited()
        val group = MachineGroup(p.image.store, p.image.hashToNodeId, machines = listOf(p.id("m")))
        val outcome = rt.runGroupGuarded(p.image, group, p.grant("receiveFx", "netReceiveCat"), this)

        val refused = assertInstanceOf(GuardedGroupOutcome.Refused::class.java, outcome)
        assertEquals(mapOf(p.id("netConnectCat") to EffectChannel.DIRECT), refused.report.exceeding)
        assertEquals(
            setOf(p.id("receiveFx"), p.id("netReceiveCat"), p.id("netConnectCat")),
            refused.report.requested,
        )
        assertTrue(refused.report.unresolvedCategoryNames.isEmpty())
        assertTrue(sink.records.isEmpty()) { "${sink.records}" }
    }

    @Test
    fun `a group refusal names a transport category the program has no node for`() = runTest {
        val p = load(
            Fixtures.BRIDGED
                .replace(""""netReceiveCat": { "type": "EffectCategory", "categoryName": "Network.Receive" },""", "")
                .replace(""""effects": ["receiveFx", "netReceiveCat"]""", """"effects": ["receiveFx"]"""),
        )
        val (rt, sink) = audited()
        val group = MachineGroup(p.image.store, p.image.hashToNodeId, machines = listOf(p.id("m")))
        val outcome = rt.runGroupGuarded(p.image, group, p.grant("receiveFx", "netConnectCat"), this)

        val refused = assertInstanceOf(GuardedGroupOutcome.Refused::class.java, outcome)
        assertTrue(refused.report.exceeding.isEmpty()) { "${refused.report.exceeding}" }
        assertEquals(setOf("Network.Receive"), refused.report.unresolvedCategoryNames)
        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun `a group whose machine reaches a latent effect outside the budget is refused`() = runTest {
        val p = load(Fixtures.program(transition = Fixtures.MAP_CALLBACK))
        val (rt, sink) = audited()
        val group = MachineGroup(p.image.store, p.image.hashToNodeId, machines = listOf(p.id("m")))
        val refused = assertInstanceOf(
            GuardedGroupOutcome.Refused::class.java,
            rt.runGroupGuarded(p.image, group, p.grant("receiveFx"), this),
        )
        assertEquals(mapOf(p.id("writeFx") to EffectChannel.LATENT), refused.report.exceeding)
        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun `a group within budget starts under the budget as its grant`() = runTest {
        val p = load(Fixtures.program(transition = Fixtures.MAP_CALLBACK))
        val (rt, sink) = audited()
        // The group's own capabilities are empty; the budget replaces them.
        val group = MachineGroup(p.image.store, p.image.hashToNodeId, machines = listOf(p.id("m")))
        val started = assertInstanceOf(
            GuardedGroupOutcome.Started::class.java,
            rt.runGroupGuarded(p.image, group, p.grant("receiveFx", "writeFx"), this),
        )
        val input = started.handle.externalInputs.getValue(p.id("stream"))
        for (e in events) input.send(e)
        input.close()
        started.handle.await()

        assertEquals(HaltReason.EventsExhausted, started.handle.allInstances.values.single().haltReason)
        assertEquals(2, sink.records.count { it.effectCategory == "Filesystem.Write" && it.outcome == AuditOutcome.Allowed })
    }

    /** Machine-shaped dag-json fixtures (the `:verifier` MachineClosureAnalysisTest set, plus a schema machine). */
    private object Fixtures {

        val DIRECT_WRITE = """
            "wLit":   { "type": "StringLit", "value": "t" },
            "wDecl":  { "type": "EffectDecl", "effectType": "writeFx", "parameters": ["wLit"] },
            "wCall":  { "type": "Application", "function": "write", "arguments": ["wLit"], "effectInstances": ["wDecl"] },
            "body":   { "type": "Let", "name": "_w", "value": "wCall", "body": "result" },
            "lam":    { "type": "Lambda", "parameters": ["sP", "eP"], "body": "body", "effects": ["writeFx"] }"""

        val PURE = """
            "lam":    { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" }"""

        val MAP_CALLBACK = """
            "cbX":    { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "cbLit":  { "type": "StringLit", "value": "cb" },
            "cbDecl": { "type": "EffectDecl", "effectType": "writeFx", "parameters": ["cbLit"] },
            "cbBody": { "type": "Application", "function": "write", "arguments": ["cbLit"], "effectInstances": ["cbDecl"] },
            "cb":     { "type": "Lambda", "parameters": ["cbX"], "body": "cbBody", "effects": ["writeFx"] },
            "cbT":    { "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": ["writeFx"] },
            "mapT":   { "type": "FunctionType", "parameters": ["listT", "cbT"], "result": "listT" },
            "map":    { "type": "ForeignNode", "target": "strand-builtin:List.Map", "foreignType": "mapT" },
            "mapApp": { "type": "Application", "function": "map", "arguments": ["oneList", "cb"] },
            "body":   { "type": "Let", "name": "_mapped", "value": "mapApp", "body": "result" },
            "lam":    { "type": "Lambda", "parameters": ["sP", "eP"], "body": "body" }"""

        val WRITING_INITIAL = """
            "iLit":   { "type": "StringLit", "value": "boot" },
            "iDecl":  { "type": "EffectDecl", "effectType": "writeFx", "parameters": ["iLit"] },
            "init":   { "type": "Application", "function": "write", "arguments": ["iLit"], "effectInstances": ["iDecl"] }"""

        val ZERO_INITIAL = """
            "init":   { "type": "IntLit", "value": 0 }"""

        /** One machine `m` (state and events Int, no outputs); `write` is `Test.EffectfulNoOp` declaring Filesystem.Write. */
        fun program(
            transition: String = PURE,
            initial: String = ZERO_INITIAL,
            effects: String = """["receiveFx"]""",
        ): String = """{
          "version": 1, "root": "m",
          "nodes": {
            $COMMON,
            $transition,
            $initial,
            "stream": { "type": "EventStream", "eventType": "intT", "streamKind": "external" },
            "m": {
              "type": "StateMachine", "transitionFn": "lam", "initialState": "init",
              "inputStreams": ["stream"], "outputStreams": [], "effects": $effects
            }
          }
        }"""

        val COMMON = """
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "strT":     { "type": "PrimitiveType", "kind": "String" },
            "writeFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
            "receiveFx": { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "writeT":   { "type": "FunctionType", "parameters": ["strT"], "result": "intT", "effects": ["writeFx"] },
            "write":    { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp", "foreignType": "writeT", "effects": ["writeFx"] },
            "listSelf": { "type": "RecursiveSelf" },
            "hdIn":     { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tlIn":     { "type": "ProductTypeField", "name": "tail", "fieldType": "listSelf" },
            "consIn":   { "type": "ProductType", "fields": ["hdIn", "tlIn"] },
            "consCase": { "type": "SumTypeCase", "name": "Cons", "caseType": "consIn" },
            "nilCase":  { "type": "SumTypeCase", "name": "Nil" },
            "listBody": { "type": "SumType", "cases": ["consCase", "nilCase"] },
            "listT":    { "type": "RecursiveType", "body": "listBody" },
            "hdOut":    { "type": "ProductTypeField", "name": "head", "fieldType": "intT" },
            "tlOut":    { "type": "ProductTypeField", "name": "tail", "fieldType": "listT" },
            "consOut":  { "type": "ProductType", "fields": ["hdOut", "tlOut"] },
            "one":      { "type": "IntLit", "value": 1 },
            "nilV":     { "type": "SumValue", "ofType": "listT", "caseName": "Nil" },
            "hdV":      { "type": "ProductFieldValue", "fieldName": "head", "value": "one" },
            "tlV":      { "type": "ProductFieldValue", "fieldName": "tail", "value": "nilV" },
            "consV":    { "type": "ProductValue", "ofType": "consOut", "fields": ["hdV", "tlV"] },
            "oneList":  { "type": "SumValue", "ofType": "listT", "caseName": "Cons", "payload": "consV" },
            "emptyT":   { "type": "ProductType", "fields": [] },
            "sft":      { "type": "ProductTypeField", "name": "state",   "fieldType": "intT" },
            "oft":      { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":     { "type": "ProductType", "fields": ["sft", "oft"] },
            "sP":       { "type": "ParameterDecl", "name": "s", "paramType": "intT" },
            "eP":       { "type": "ParameterDecl", "name": "e", "paramType": "intT" },
            "sRef":     { "type": "VarRef", "binder": "sP" },
            "sV":       { "type": "ProductFieldValue", "fieldName": "state", "value": "sRef" },
            "emptyV":   { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":       { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":   { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] }"""

        /** A pure machine whose transition passes `e - 10` to a PositiveInt-typed sink. */
        val SCHEMA_MACHINE = """{
          "version": 1, "root": "m",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "boolT":     { "type": "PrimitiveType", "kind": "Bool" },
            "cmpT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "arithT":    { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "gt":        { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "cmpT" },
            "sub":       { "type": "ForeignNode", "target": "strand-builtin:Int.Sub", "foreignType": "arithT" },
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
            "eRef":      { "type": "VarRef", "binder": "eP" },
            "v":         { "type": "Application", "function": "sub", "arguments": ["eRef", "ten"] },
            "sinkApp":   { "type": "Application", "function": "posSink", "arguments": ["v"] },
            "sV":        { "type": "ProductFieldValue", "fieldName": "state", "value": "sinkApp" },
            "emptyV":    { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":        { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":    { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "lam":       { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "init":      { "type": "IntLit", "value": 0 },
            "stream":    { "type": "EventStream", "eventType": "intT", "streamKind": "external" },
            "receiveFx": { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "m": {
              "type": "StateMachine", "transitionFn": "lam", "initialState": "init",
              "inputStreams": ["stream"], "outputStreams": [], "effects": ["receiveFx"]
            }
          }
        }"""

        /** Corpus 84's shape: a machine fed by a `Net.Connect`-sourced external byte stream. */
        val BRIDGED = """{
          "version": 1, "root": "m",
          "nodes": {
            "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "strT":   { "type": "PrimitiveType", "kind": "String" },
            "emptyT": { "type": "ProductType", "fields": [] },
            "sft":    { "type": "ProductTypeField", "name": "state",   "fieldType": "bytesT" },
            "oft":    { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":   { "type": "ProductType", "fields": ["sft", "oft"] },
            "concatT":  { "type": "FunctionType", "parameters": ["bytesT", "bytesT"], "result": "bytesT" },
            "concatFn": { "type": "ForeignNode", "target": "strand-builtin:Bytes.Concat", "foreignType": "concatT" },
            "sP":     { "type": "ParameterDecl", "name": "s", "paramType": "bytesT" },
            "eP":     { "type": "ParameterDecl", "name": "e", "paramType": "bytesT" },
            "sRef":   { "type": "VarRef", "binder": "sP" },
            "eRef":   { "type": "VarRef", "binder": "eP" },
            "cat":    { "type": "Application", "function": "concatFn", "arguments": ["sRef", "eRef"] },
            "sV":     { "type": "ProductFieldValue", "fieldName": "state",   "value": "cat" },
            "emptyV": { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":     { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result": { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "lam":    { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "emptyStr":   { "type": "StringLit", "value": "" },
            "fromUtf8T":  { "type": "FunctionType", "parameters": ["strT"], "result": "bytesT" },
            "fromUtf8Fn": { "type": "ForeignNode", "target": "strand-builtin:Bytes.FromUtf8", "foreignType": "fromUtf8T" },
            "init":   { "type": "Application", "function": "fromUtf8Fn", "arguments": ["emptyStr"] },
            "hostLit": { "type": "StringLit", "value": "127.0.0.1" },
            "portLit": { "type": "IntLit", "value": 9 },
            "connectT": { "type": "FunctionType", "parameters": ["strT", "intT"], "result": "intT" },
            "netConnectCat":  { "type": "EffectCategory", "categoryName": "Network.Connect" },
            "netConnectFn":   { "type": "ForeignNode", "target": "strand-builtin:Net.Connect", "foreignType": "connectT", "effects": ["netConnectCat"] },
            "netConnectDecl": { "type": "EffectDecl", "effectType": "netConnectCat" },
            "openApp": { "type": "Application", "function": "netConnectFn", "arguments": ["hostLit", "portLit"], "effectInstances": ["netConnectDecl"] },
            "receiveFx":     { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "netReceiveCat": { "type": "EffectCategory", "categoryName": "Network.Receive" },
            "inStr": { "type": "EventStream", "eventType": "bytesT", "streamKind": "external", "source": "openApp" },
            "m": {
              "type": "StateMachine", "transitionFn": "lam", "initialState": "init",
              "inputStreams": ["inStr"], "outputStreams": [],
              "effects": ["receiveFx", "netReceiveCat"]
            }
          }
        }"""
    }
}
