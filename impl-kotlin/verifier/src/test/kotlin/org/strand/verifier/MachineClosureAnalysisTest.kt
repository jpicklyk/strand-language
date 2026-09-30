package org.strand.verifier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher

/**
 * Q-073: the machine-shaped closure queries on [ProgramAnalysis].
 * [ProgramAnalysis.totalClosure] on a StateMachine node reports nothing
 * direct (evaluating the node performs nothing); [ProgramAnalysis.machineClosure]
 * bounds what driving the machine performs, and
 * [ProgramAnalysis.groupClosure] what running a group performs.
 */
class MachineClosureAnalysisTest {

    private class Loaded(val analysis: ProgramAnalysis, val verify: VerifyResult.Ok, val names: Map<String, NodeId>) {
        fun id(name: String): NodeId = names.getValue(name)
    }

    private fun load(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        val ok = assertInstanceOf(VerifyResult.Ok::class.java, verify) { "fixture failed verify: $verify" }
        return Loaded(ProgramAnalysis(finalized.store, ok, finalized.root, finalized.hashToNodeId), ok, ingest.nameMap)
    }

    @Test
    fun `a direct transition effect is in the machine closure and not in totalClosure of the node`() {
        val p = load(MachineFixtures.program(transition = MachineFixtures.DIRECT_WRITE, effects = """["receiveFx", "writeFx"]"""))
        val closure = p.analysis.machineClosure(p.id("m"))
        assertEquals(setOf(p.id("receiveFx"), p.id("writeFx")), closure.direct)
        assertEquals(emptySet<NodeId>(), closure.latent)
        assertEquals(emptySet<NodeId>(), p.analysis.effectClosure(p.id("m")), "the StateMachine node itself performs nothing")
    }

    @Test
    fun `an effect reached only through initialState is in the direct channel`() {
        val p = load(MachineFixtures.program(initial = MachineFixtures.WRITING_INITIAL, effects = """["receiveFx", "writeFx"]"""))
        val closure = p.analysis.machineClosure(p.id("m"))
        assertTrue(p.id("writeFx") in closure.direct)
        assertEquals(emptySet<NodeId>(), closure.latent)
    }

    /**
     * The coverage rule checks the declared row against the transition's
     * effect row and the initial state's closure, neither of which carries a
     * callback's row: a machine whose transition hands an effectful callback
     * to `List.Map` verifies with a row that omits the callback's effect. The
     * machine closure carries it on the latent channel.
     */
    @Test
    fun `a callback handed to List Map inside a transition is latent and outside the declared row`() {
        val p = load(MachineFixtures.program(transition = MachineFixtures.MAP_CALLBACK))
        val closure = p.analysis.machineClosure(p.id("m"))
        assertEquals(setOf(p.id("receiveFx")), closure.direct, "the declared row")
        assertEquals(setOf(p.id("writeFx")), closure.latent)
        assertFalse(p.id("writeFx") in closure.direct, "the declared row is not a bound on its own")
    }

    @Test
    fun `a Let-bound callback defined outside the machine is followed through its VarRef`() {
        val p = load(MachineFixtures.program(transition = MachineFixtures.MAP_OUTER_CALLBACK, root = "outer"))
        val closure = p.analysis.machineClosure(p.id("m"))
        assertEquals(setOf(p.id("writeFx")), closure.latent)
    }

    @Test
    fun `a pure machine's closure is its declared implicit effects`() {
        val p = load(MachineFixtures.program())
        val closure = p.analysis.machineClosure(p.id("m"))
        assertEquals(setOf(p.id("receiveFx")), closure.total)
    }

    @Test
    fun `a node that is not a StateMachine is rejected`() {
        val p = load(MachineFixtures.program())
        assertThrows(IllegalArgumentException::class.java) { p.analysis.machineClosure(p.id("lam")) }
    }

    @Test
    fun `the group closure names a source opener's effect and the transport category`() {
        val p = load(MachineFixtures.BRIDGED)
        val group = p.analysis.groupClosure(listOf(p.id("m")))
        val source = group.sources.single()
        assertEquals(p.id("inStr"), source.stream)
        assertEquals(setOf(p.id("netConnectCat")), source.direct)
        assertEquals(setOf(p.id("netReceiveCat")), source.transportCategories)
        assertEquals(setOf(p.id("receiveFx"), p.id("netReceiveCat"), p.id("netConnectCat")), group.direct)
        assertTrue(group.unresolvedCategoryNames.isEmpty())
        assertFalse(p.id("netConnectCat") in p.analysis.machineClosure(p.id("m")).total, "opening is the group's, not the machine's")
    }

    /**
     * The verifier infers a source opener like any other expression the
     * runtime evaluates, so an effectful argument is in the opener's
     * recorded closure and from there in the source bound.
     */
    @Test
    fun `an effect in a source opener's argument is in the source bound`() {
        val p = load(
            MachineFixtures.BRIDGED.replace(
                """"portLit": { "type": "IntLit", "value": 9 },""",
                """"writeFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
                "writeT":   { "type": "FunctionType", "parameters": ["strT"], "result": "intT", "effects": ["writeFx"] },
                "write":    { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp", "foreignType": "writeT", "effects": ["writeFx"] },
                "pDecl":    { "type": "EffectDecl", "effectType": "writeFx", "parameters": ["hostLit"] },
                "portLit":  { "type": "Application", "function": "write", "arguments": ["hostLit"], "effectInstances": ["pDecl"] },""",
            ),
        )
        assertTrue(p.id("writeFx") in p.verify.nodeClosures[p.id("openApp")].orEmpty(), "the opener's recorded closure")
        val source = p.analysis.groupClosure(listOf(p.id("m"))).sources.single()
        assertTrue(p.id("writeFx") in source.direct) { "${source.direct}" }
        assertTrue(p.id("netConnectCat") in source.direct)
    }

    @Test
    fun `a transport category absent from the store is reported by name`() {
        val p = load(
            MachineFixtures.BRIDGED
                .replace(""""netReceiveCat": { "type": "EffectCategory", "categoryName": "Network.Receive" },""", "")
                .replace(""""effects": ["receiveFx", "netReceiveCat"]""", """"effects": ["receiveFx"]"""),
        )
        val group = p.analysis.groupClosure(listOf(p.id("m")))
        assertEquals(setOf("Network.Receive"), group.unresolvedCategoryNames)
    }

    @Test
    fun `a machine that can spawn widens the group to every StateMachine in the store`() {
        val p = load(MachineFixtures.SPAWNING_PAIR)
        val group = p.analysis.groupClosure(listOf(p.id("mA")))
        assertEquals(setOf(p.id("mA"), p.id("mB")), group.machines.keys)
        assertTrue(p.id("writeFx") in group.total, "the spawnable machine's effect is in the bound")
    }
}

/** Machine-shaped dag-json fixtures shared by the analysis tests. */
internal object MachineFixtures {

    /** A transition that writes on every event (state unchanged). */
    val DIRECT_WRITE = """
        "wLit":   { "type": "StringLit", "value": "t" },
        "wDecl":  { "type": "EffectDecl", "effectType": "writeFx", "parameters": ["wLit"] },
        "wCall":  { "type": "Application", "function": "write", "arguments": ["wLit"], "effectInstances": ["wDecl"] },
        "body":   { "type": "Let", "name": "_w", "value": "wCall", "body": "result" },
        "lam":    { "type": "Lambda", "parameters": ["sP", "eP"], "body": "body", "effects": ["writeFx"] }"""

    /** A pure transition (state unchanged). */
    val PURE = """
        "lam":    { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" }"""

    /** A callback writing once per element, defined inline. */
    private val CALLBACK = """
        "cbX":    { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
        "cbLit":  { "type": "StringLit", "value": "cb" },
        "cbDecl": { "type": "EffectDecl", "effectType": "writeFx", "parameters": ["cbLit"] },
        "cbBody": { "type": "Application", "function": "write", "arguments": ["cbLit"], "effectInstances": ["cbDecl"] },
        "cb":     { "type": "Lambda", "parameters": ["cbX"], "body": "cbBody", "effects": ["writeFx"] },
        "cbT":    { "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": ["writeFx"] },
        "mapT":   { "type": "FunctionType", "parameters": ["listT", "cbT"], "result": "listT" },
        "map":    { "type": "ForeignNode", "target": "strand-builtin:List.Map", "foreignType": "mapT" }"""

    /** A transition that maps the write callback over a one-element list. */
    val MAP_CALLBACK = CALLBACK + """,
        "mapApp": { "type": "Application", "function": "map", "arguments": ["oneList", "cb"] },
        "body":   { "type": "Let", "name": "_mapped", "value": "mapApp", "body": "result" },
        "lam":    { "type": "Lambda", "parameters": ["sP", "eP"], "body": "body" }"""

    /** As [MAP_CALLBACK], but the callback is bound by a Let enclosing the machine. */
    val MAP_OUTER_CALLBACK = CALLBACK + """,
        "cbRef":  { "type": "VarRef", "binder": "outer" },
        "mapApp": { "type": "Application", "function": "map", "arguments": ["oneList", "cbRef"] },
        "body":   { "type": "Let", "name": "_mapped", "value": "mapApp", "body": "result" },
        "lam":    { "type": "Lambda", "parameters": ["sP", "eP"], "body": "body" },
        "outer":  { "type": "Let", "name": "cbOuter", "value": "cb", "body": "m" }"""

    /** An initial state that writes once. */
    val WRITING_INITIAL = """
        "iLit":   { "type": "StringLit", "value": "boot" },
        "iDecl":  { "type": "EffectDecl", "effectType": "writeFx", "parameters": ["iLit"] },
        "init":   { "type": "Application", "function": "write", "arguments": ["iLit"], "effectInstances": ["iDecl"] }"""

    val ZERO_INITIAL = """
        "init":   { "type": "IntLit", "value": 0 }"""

    /**
     * One machine `m`: state Int (unchanged by the transition), events Int,
     * no outputs. `write` is `Test.EffectfulNoOp : String -> Int` declaring
     * Filesystem.Write; `oneList` is the list `[1]`.
     */
    fun program(
        transition: String = PURE,
        initial: String = ZERO_INITIAL,
        effects: String = """["receiveFx"]""",
        root: String = "m",
    ): String = """{
      "version": 1, "root": "$root",
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

    /**
     * `mA` declares `StateMachine.Spawn` (a declared row may exceed what the
     * transition performs); `mB`, on a distinct stream, writes in its
     * transition. A Let holds both so verification reaches them.
     */
    val SPAWNING_PAIR = """{
      "version": 1, "root": "both",
      "nodes": {
        $COMMON,
        $DIRECT_WRITE,
        "pureLam":  { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
        $ZERO_INITIAL,
        "spawnFx":  { "type": "EffectCategory", "categoryName": "StateMachine.Spawn" },
        "streamA":  { "type": "EventStream", "eventType": "intT", "streamKind": "external", "bufferSize": 8 },
        "streamB":  { "type": "EventStream", "eventType": "intT", "streamKind": "external", "bufferSize": 9 },
        "mA": {
          "type": "StateMachine", "transitionFn": "pureLam", "initialState": "init",
          "inputStreams": ["streamA"], "outputStreams": [], "effects": ["receiveFx", "spawnFx"]
        },
        "mB": {
          "type": "StateMachine", "transitionFn": "lam", "initialState": "init",
          "inputStreams": ["streamB"], "outputStreams": [], "effects": ["receiveFx", "writeFx"]
        },
        "both": { "type": "Let", "name": "_a", "value": "mA", "body": "mB" }
      }
    }"""
}
