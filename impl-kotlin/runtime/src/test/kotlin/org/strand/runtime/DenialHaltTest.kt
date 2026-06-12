package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.core.StreamKind
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.DenialPhase
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-064 — denial-caused state-machine halts expose the [DenialReport] on
 * the halt reason. Scenario 2 of the proposal's § 6 (group transition
 * denial carries instance id, event index, and phase `transition`) plus
 * the sync-fold [HaltReason.CapabilityDenial] translation, the group-start
 * phase re-tagging, and the negative case (non-denial halts carry no
 * report — scenario 7's runtime half).
 *
 * The write machine: state Int starting at 0; each String event is passed
 * to an effectful `Test.EffectfulNoOp` call whose EffectDecl refines
 * `Filesystem.Write` with the event value. Granting
 * `Filesystem.Write{"ok"}` makes "ok" events transition and any other
 * event deny at the call-site refinement check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DenialHaltTest {

    // ----- Sync fold (runMachine) -----

    private fun loadWriteMachine(): Triple<NodeStore, org.strand.core.NodeId, Map<String, org.strand.core.NodeId>> {
        val ingest = JsonIngest.parse(WRITE_MACHINE)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        check(verify is VerifyResult.Ok) { "fixture failed verify: $verify" }
        return Triple(finalized.store, finalized.root, ingest.nameMap)
    }

    private fun okOnlyGrant(category: org.strand.core.NodeId): CapabilitySet = CapabilitySet(mapOf(
        category to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("ok")))),
        )
    ))

    @Test
    fun `sync runMachine translates a transition denial into HaltReason CapabilityDenial`() {
        val (store, root, names) = loadWriteMachine()
        val runtime = StateMachineRuntime(store)
        val events = listOf(Value.StringV("ok"), Value.StringV("evil"), Value.StringV("ok"))
        val trace = runtime.runMachine(root, events, okOnlyGrant(names.getValue("fsWriteFx")))

        // The first event transitioned; the second denied; the third never ran.
        assertEquals(1, trace.steps.size)
        val reason = trace.final.reason
        assertTrue(reason is HaltReason.CapabilityDenial) { "expected denial halt, got $reason" }
        val report = (reason as HaltReason.CapabilityDenial).report
        assertEquals("Filesystem.Write", report.category)
        assertEquals(listOf("evil"), report.requested)
        assertEquals(listOf("Filesystem.Write{ok}"), report.held)
        assertEquals(1, report.eventIndex)
        assertNotNull(report.instanceId)
        assertEquals(DenialPhase.Transition, report.phase)
        assertEquals(names.getValue("call"), report.node)
    }

    @Test
    fun `sync runMachine without denial halts EventsExhausted as before`() {
        val (store, root, names) = loadWriteMachine()
        val runtime = StateMachineRuntime(store)
        val events = listOf(Value.StringV("ok"), Value.StringV("ok"))
        val trace = runtime.runMachine(root, events, okOnlyGrant(names.getValue("fsWriteFx")))
        assertEquals(2, trace.steps.size)
        assertEquals(HaltReason.EventsExhausted, trace.final.reason)
    }

    // ----- Async group (runGroup) — proposal § 6 scenario 2 -----

    /**
     * Programmatic equivalent of [WRITE_MACHINE] (the MachineGroupTest
     * convention — no ingest/verifier dependency for the actor-loop tests).
     * Returns (machineId, inputStreamId, fsWriteCategoryId).
     */
    private fun buildWriteMachine(store: NodeStore): Triple<org.strand.core.NodeId, org.strand.core.NodeId, org.strand.core.NodeId> {
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val strT = store.add(Node.PrimitiveType(Primitive.String))
        val emptyProductType = store.add(Node.ProductType(emptyList()))
        val stateField = store.add(Node.ProductTypeField("state", intT))
        val outputsField = store.add(Node.ProductTypeField("outputs", emptyProductType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))

        val fsWriteFx = store.add(Node.EffectCategory("Filesystem.Write", parameters = listOf(strT)))
        val writeT = store.add(Node.FunctionType(
            parameters = listOf(strT), result = intT, effects = listOf(fsWriteFx),
        ))
        val write = store.add(Node.ForeignNode(
            target = "strand-builtin:Test.EffectfulNoOp",
            foreignType = writeT,
            effects = listOf(fsWriteFx),
        ))

        val sParam = store.add(Node.ParameterDecl("s", intT))
        val eParam = store.add(Node.ParameterDecl("e", strT))
        val eRef = store.add(Node.VarRef(eParam))
        val writeDecl = store.add(Node.EffectDecl(effectType = fsWriteFx, parameters = listOf(eRef)))
        val call = store.add(Node.Application(
            function = write, arguments = listOf(eRef), effectInstances = listOf(writeDecl),
        ))
        val newStateField = store.add(Node.ProductFieldValue("state", call))
        val emptyValue = store.add(Node.ProductValue(emptyProductType, emptyList()))
        val newOutputsField = store.add(Node.ProductFieldValue("outputs", emptyValue))
        val resultValue = store.add(Node.ProductValue(resultType, listOf(newStateField, newOutputsField)))
        val lam = store.add(Node.Lambda(
            parameters = listOf(sParam, eParam), body = resultValue, effects = listOf(fsWriteFx),
        ))

        val init = store.add(Node.IntLit(0))
        val inputStream = store.add(Node.EventStream(eventType = strT, streamKind = StreamKind.External))
        val machine = store.add(Node.StateMachine(
            transitionFn = lam,
            initialState = init,
            inputStreams = listOf(inputStream),
            outputStreams = emptyList(),
            effects = listOf(fsWriteFx),
        ))
        return Triple(machine, inputStream, fsWriteFx)
    }

    @Test
    fun `group transition denial halts the actor with instance id, event index, and phase transition`() = runTest {
        val store = NodeStore()
        val (machineId, inputStreamId, fsWriteFx) = buildWriteMachine(store)
        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machineId),
            capabilities = okOnlyGrant(fsWriteFx),
        )
        val handle = runtime.runGroup(group, this)
        val input = handle.externalInputs.getValue(inputStreamId)
        input.send(Value.StringV("ok"))
        input.send(Value.StringV("evil"))
        input.close()
        handle.await()

        val (instanceId, instance) = handle.instances.entries.single()
        assertTrue(instance.halted)
        val report = instance.denialReport
        assertNotNull(report) { "expected a denial report on the halted instance" }
        assertEquals(instanceId.value, report!!.instanceId)
        assertEquals(1, report.eventIndex)
        assertEquals(DenialPhase.Transition, report.phase)
        assertEquals("Filesystem.Write", report.category)
        assertEquals(listOf("evil"), report.requested)
        assertEquals(listOf("Filesystem.Write{ok}"), report.held)
    }

    @Test
    fun `group run without denial leaves denialReport null`() = runTest {
        val store = NodeStore()
        val (machineId, inputStreamId, fsWriteFx) = buildWriteMachine(store)
        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machineId),
            capabilities = okOnlyGrant(fsWriteFx),
        )
        val handle = runtime.runGroup(group, this)
        val input = handle.externalInputs.getValue(inputStreamId)
        input.send(Value.StringV("ok"))
        input.close()
        handle.await()

        val instance = handle.instances.values.single()
        assertTrue(instance.halted)
        assertNull(instance.denialReport)
    }

    @Test
    fun `denial during group startup rethrows with phase group-start`() = runTest {
        // initialState is itself an effectful call; spawning the initial
        // instance evaluates it under the group's (empty) capability
        // context, so runGroup throws before any actor processes an event.
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val strT = store.add(Node.PrimitiveType(Primitive.String))
        val emptyProductType = store.add(Node.ProductType(emptyList()))
        val stateField = store.add(Node.ProductTypeField("state", intT))
        val outputsField = store.add(Node.ProductTypeField("outputs", emptyProductType))
        val resultType = store.add(Node.ProductType(listOf(stateField, outputsField)))
        val fsWriteFx = store.add(Node.EffectCategory("Filesystem.Write", parameters = listOf(strT)))
        val writeT = store.add(Node.FunctionType(
            parameters = listOf(strT), result = intT, effects = listOf(fsWriteFx),
        ))
        val write = store.add(Node.ForeignNode(
            target = "strand-builtin:Test.EffectfulNoOp",
            foreignType = writeT,
            effects = listOf(fsWriteFx),
        ))
        val pathLit = store.add(Node.StringLit("boot"))
        val initCall = store.add(Node.Application(function = write, arguments = listOf(pathLit)))
        val sParam = store.add(Node.ParameterDecl("s", intT))
        val eParam = store.add(Node.ParameterDecl("e", strT))
        val sRef = store.add(Node.VarRef(sParam))
        val newStateField = store.add(Node.ProductFieldValue("state", sRef))
        val emptyValue = store.add(Node.ProductValue(emptyProductType, emptyList()))
        val newOutputsField = store.add(Node.ProductFieldValue("outputs", emptyValue))
        val resultValue = store.add(Node.ProductValue(resultType, listOf(newStateField, newOutputsField)))
        val lam = store.add(Node.Lambda(parameters = listOf(sParam, eParam), body = resultValue))
        val inputStream = store.add(Node.EventStream(eventType = strT, streamKind = StreamKind.External))
        val machine = store.add(Node.StateMachine(
            transitionFn = lam,
            initialState = initCall,
            inputStreams = listOf(inputStream),
            outputStreams = emptyList(),
            effects = listOf(fsWriteFx),
        ))

        val runtime = StateMachineRuntime(store)
        val group = MachineGroup(
            store = store,
            hashToNodeId = emptyMap(),
            machines = listOf(machine),
            capabilities = CapabilitySet.EMPTY,
        )
        val ex = assertThrows(InterpretException::class.java) {
            runtime.runGroup(group, this)
        }
        val err = ex.error as InterpretError.CapabilityViolation
        assertEquals(DenialPhase.GroupStart, err.report.phase)
        assertEquals("Filesystem.Write", err.report.category)
    }

    companion object {
        private val WRITE_MACHINE = """
        {
          "version": 1,
          "root": "m",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "strT":      { "type": "PrimitiveType", "kind": "String" },
            "emptyT":    { "type": "ProductType", "fields": [] },
            "sft":       { "type": "ProductTypeField", "name": "state",   "fieldType": "intT" },
            "oft":       { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":      { "type": "ProductType", "fields": ["sft", "oft"] },
            "fsWriteFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                           "parameters": ["strT"] },
            "writeT":    { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                           "effects": ["fsWriteFx"] },
            "write":     { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                           "foreignType": "writeT", "effects": ["fsWriteFx"] },
            "sP":        { "type": "ParameterDecl", "name": "s", "paramType": "intT" },
            "eP":        { "type": "ParameterDecl", "name": "e", "paramType": "strT" },
            "eRef":      { "type": "VarRef", "binder": "eP" },
            "writeDecl": { "type": "EffectDecl", "effectType": "fsWriteFx", "parameters": ["eRef"] },
            "call":      { "type": "Application", "function": "write", "arguments": ["eRef"],
                           "effectInstances": ["writeDecl"] },
            "sV":        { "type": "ProductFieldValue", "fieldName": "state",   "value": "call" },
            "emptyV":    { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":        { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":    { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
            "lam":       { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result",
                           "effects": ["fsWriteFx"] },
            "init":      { "type": "IntLit", "value": 0 },
            "stream":    { "type": "EventStream", "eventType": "strT", "streamKind": "external" },
            "receiveFx": { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "m": {
              "type": "StateMachine",
              "transitionFn": "lam",
              "initialState": "init",
              "inputStreams": ["stream"],
              "outputStreams": [],
              "effects": ["receiveFx", "fsWriteFx"]
            }
          }
        }
        """.trimIndent()
    }
}
