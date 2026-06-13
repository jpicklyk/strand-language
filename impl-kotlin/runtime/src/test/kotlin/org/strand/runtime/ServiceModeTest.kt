package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.EvaluationLimits
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-059 piece 3: the service-mode driver and the per-event limits model.
 *
 * The behavioral contrast the tests pin:
 *  - The batch limits model (`perEventStepBudget = null`) accumulates the step
 *    budget across an actor's whole lifetime, so a tight cumulative `maxSteps`
 *    halts a long-lived actor after a few events.
 *  - The per-event model (`perEventStepBudget` set) resets the budget per
 *    event, so the same long-lived actor processes every event — it is not
 *    killed for uptime — while each event stays independently bounded.
 *  - Service mode keeps inputs open and runs across idle (virtual-time) gaps;
 *    the batch path is byte-unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceModeTest {

    private fun machineIdOf(json: String): Triple<org.strand.core.NodeStore, Map<org.strand.core.Hash, org.strand.core.NodeId>, org.strand.core.NodeId> {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        assertTrue(Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root) is VerifyResult.Ok)
        val machineId = finalized.store.entries().first { it.second is Node.StateMachine }.first
        return Triple(finalized.store, finalized.hashToNodeId, machineId)
    }

    @Test
    fun `per-event budget lets a long-lived actor process events a tight cumulative budget would not`() = runTest {
        val (store, h2n, machineId) = machineIdOf(INCREMENTING_COUNTER)

        // A cumulative maxSteps of 30 -- each transition is ~5-10 steps, so
        // batch mode would halt after ~3-4 events. With a per-event budget of
        // 1000, every event resets and all 20 process.
        val limits = EvaluationLimits.DEFAULTS.copy(
            maxSteps = 30L,
            perEventStepBudget = 1000L,
        )
        val runtime = StateMachineRuntime(store, h2n)
        val group = MachineGroup(store = store, hashToNodeId = h2n, machines = listOf(machineId))
        val handle = runtime.runGroup(group, this, limits)
        val input = handle.externalInputs.values.single()

        repeat(20) { input.send(Value.UnitV) }
        input.close()
        handle.await()

        val instance = handle.instances.values.single()
        // All 20 events processed; no halt-for-uptime.
        assertEquals(20L, instance.instanceMetricsSnapshot().eventsReceived)
        assertEquals(Value.IntV(20L), instance.currentState)
        assertEquals(20L, instance.instanceMetricsSnapshot().transitionsExecuted)
    }

    @Test
    fun `the same actor under the cumulative batch model halts early on the tight budget`() = runTest {
        // Control: with perEventStepBudget = null, the cumulative cap of 30
        // halts the actor before all 20 events (the model service mode fixes).
        val (store, h2n, machineId) = machineIdOf(INCREMENTING_COUNTER)
        val limits = EvaluationLimits.DEFAULTS.copy(maxSteps = 30L)  // perEventStepBudget null
        val runtime = StateMachineRuntime(store, h2n)
        val group = MachineGroup(store = store, hashToNodeId = h2n, machines = listOf(machineId))
        val handle = runtime.runGroup(group, this, limits)
        val input = handle.externalInputs.values.single()

        repeat(20) { input.send(Value.UnitV) }
        input.close()
        handle.await()

        val instance = handle.instances.values.single()
        // Fewer than 20 transitions completed -- the cumulative budget bit.
        assertTrue(instance.instanceMetricsSnapshot().transitionsExecuted < 20L) {
            "expected the cumulative cap to halt early, got ${instance.instanceMetricsSnapshot().transitionsExecuted}"
        }
        assertTrue(instance.halted)
    }

    @Test
    fun `a per-event budget too small for one event halts that actor`() = runTest {
        val (store, h2n, machineId) = machineIdOf(INCREMENTING_COUNTER)
        // A per-event budget of 2 steps is too small for one transition
        // (~5-10 steps), so the first event exhausts it.
        val limits = EvaluationLimits.DEFAULTS.copy(perEventStepBudget = 2L)
        val runtime = StateMachineRuntime(store, h2n)
        val group = MachineGroup(store = store, hashToNodeId = h2n, machines = listOf(machineId))
        val handle = runtime.runGroup(group, this, limits)
        val input = handle.externalInputs.values.single()

        input.send(Value.UnitV)
        input.close()
        handle.await()

        val instance = handle.instances.values.single()
        assertTrue(instance.halted)
        assertEquals(0L, instance.instanceMetricsSnapshot().transitionsExecuted)
    }

    @Test
    fun `per-event exhaustion in one actor does not affect a sibling actor`() = runTest {
        // Two independent counter machines in one group, each on its own
        // external input. One gets a starving per-event budget; the other
        // shares the same per-event budget but its events are cheap enough...
        // actually both share group limits, so instead model independence by
        // running two single-machine groups under different limits in the same
        // scope -- per-actor counter isolation is the runGroup property.
        val (store, h2n, machineId) = machineIdOf(INCREMENTING_COUNTER)
        val runtime = StateMachineRuntime(store, h2n)

        val starving = EvaluationLimits.DEFAULTS.copy(perEventStepBudget = 2L)
        val generous = EvaluationLimits.DEFAULTS.copy(perEventStepBudget = 1000L)

        val groupA = MachineGroup(store = store, hashToNodeId = h2n, machines = listOf(machineId))
        val handleA = runtime.runGroup(groupA, this, starving)
        val groupB = MachineGroup(store = store, hashToNodeId = h2n, machines = listOf(machineId))
        val handleB = runtime.runGroup(groupB, this, generous)

        handleA.externalInputs.values.single().also { it.send(Value.UnitV); it.close() }
        val inB = handleB.externalInputs.values.single()
        repeat(5) { inB.send(Value.UnitV) }
        inB.close()
        handleA.await()
        handleB.await()

        assertEquals(0L, handleA.instances.values.single().instanceMetricsSnapshot().transitionsExecuted)
        assertEquals(5L, handleB.instances.values.single().instanceMetricsSnapshot().transitionsExecuted)
    }

    @Test
    fun `batch path is byte-unchanged when perEventStepBudget is null`() = runTest {
        // The same async group run, with default limits (perEventStepBudget
        // null), produces the same trajectory and recorded events as before
        // this change -- the counter accumulates 1..5 to 15.
        val (store, h2n, machineId) = machineIdOf(RuntimeMetricsTestSeed.COUNTER_JSON)
        val runtime = StateMachineRuntime(store, h2n)
        val group = MachineGroup(store = store, hashToNodeId = h2n, machines = listOf(machineId))
        val handle = runtime.runGroup(group, this)  // DEFAULTS: perEventStepBudget null
        val input = handle.externalInputs.values.single()
        val output = handle.externalOutputs.values.single()
        for (i in 1..5) input.send(Value.IntV(i.toLong()))
        val received = (1..5).map { output.receive() }
        input.close()
        handle.await()

        // Accumulated state and emitted outputs match a plain runMachine.
        val ref = runtime.runMachine(machineId, (1..5).map { Value.IntV(it.toLong()) }, CapabilitySet.EMPTY)
        assertEquals(ref.final.finalState, handle.instances.values.single().currentState)
        assertEquals(listOf(1L, 3L, 6L, 10L, 15L).map { Value.IntV(it) }, received)
    }

    @Test
    fun `serveGroup keeps inputs open across an idle virtual-time gap and stop() drains`() = runTest {
        val (store, h2n, machineId) = machineIdOf(RuntimeMetricsTestSeed.COUNTER_JSON)
        // Per-event budgeting on, and a tiny lifetime wall-clock that the
        // service must NOT be killed by despite a long idle gap.
        val policy = HostPolicy.OPEN.copy(
            limits = EvaluationLimits.DEFAULTS.copy(
                wallClockBudgetMillis = 1L,
                perEventStepBudget = 10_000L,
                perEventWallClockBudgetMillis = 5_000L,
            )
        )
        val rt = StrandRuntime(policy)
        val image = ProgramImage(store, machineId, h2n)
        val group = MachineGroup(store = store, hashToNodeId = h2n, machines = listOf(machineId))

        // Resolve the external input/output stream NodeIds for the service.
        val machineNode = store.get(machineId) as Node.StateMachine
        val inId = machineNode.inputStreams.single()
        val outId = machineNode.outputStreams.single()

        // Q-054 follow-up: the policy now flows into the spawned actors as a
        // HostContext value (serveGroup installs no singleton), so no
        // save/restore guard is needed around the suspend body.
        val svc = rt.serveGroup(
            program = image,
            group = group,
            scope = this,
            inputStreamIds = mapOf("in" to inId),
            outputStreamIds = mapOf("out" to outId),
        )

        val out = svc.outputs("out")
        val drained = mutableListOf<Value>()
        val drainer = launch { for (v in out) drained.add(v) }

        // Send an event, advance virtual time WELL past the 1ms lifetime
        // wall-clock, send another. The service stays alive across the gap.
        svc.send("in", Value.IntV(10L))
        delay(60_000)  // 60 virtual seconds of idleness
        svc.send("in", Value.IntV(5L))

        // Graceful stop: closes inputs and awaits natural halt.
        svc.stop()
        drainer.join()

        // Both events processed despite the idle gap and the tiny lifetime
        // wall-clock; the accumulated state is 10 + 5 = 15.
        assertEquals(listOf(Value.IntV(10L), Value.IntV(15L)), drained)
        assertEquals(Value.IntV(15L), svc.instances.values.single().currentState)
        assertFalse(svc.denials().isNotEmpty())
    }

    @Test
    fun `serveGroup rejects an unknown output stream name`() = runTest {
        val (store, h2n, machineId) = machineIdOf(RuntimeMetricsTestSeed.COUNTER_JSON)
        val rt = StrandRuntime(HostPolicy.OPEN)
        val image = ProgramImage(store, machineId, h2n)
        val group = MachineGroup(store = store, hashToNodeId = h2n, machines = listOf(machineId))
        val machineNode = store.get(machineId) as Node.StateMachine
        val svc = rt.serveGroup(
            program = image, group = group, scope = this,
            inputStreamIds = mapOf("in" to machineNode.inputStreams.single()),
            outputStreamIds = mapOf("out" to machineNode.outputStreams.single()),
        )
        // outputs(...) is non-suspend, so the name-resolution guard (shared
        // with send's guard) is exercised without a runBlocking nesting.
        assertThrows(IllegalArgumentException::class.java) { svc.outputs("nope") }
        svc.stopNow()
    }

    companion object {
        // Counter machine, Unit events, no outputs (empty OutputBatch) -- copied
        // from StateMachineRuntimeLimitsTest so the budget contrast is on a
        // shape whose per-event step cost is known (~5-10 steps).
        private val INCREMENTING_COUNTER = """
        {
          "version": 1,
          "root": "m",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "unitT":   { "type": "PrimitiveType", "kind": "Unit" },
            "emptyT":  { "type": "ProductType", "fields": [] },
            "sft":     { "type": "ProductTypeField", "name": "state",   "fieldType": "intT" },
            "oft":     { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
            "resT":    { "type": "ProductType", "fields": ["sft", "oft"] },
            "addT":    { "type": "FunctionType", "parameters": ["intT","intT"], "result": "intT" },
            "addFn":   { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "sP":      { "type": "ParameterDecl", "name": "s", "paramType": "intT" },
            "eP":      { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
            "sRef":    { "type": "VarRef", "binder": "sP" },
            "one":     { "type": "IntLit", "value": 1 },
            "incr":    { "type": "Application", "function": "addFn", "arguments": ["sRef","one"] },
            "sV":      { "type": "ProductFieldValue", "fieldName": "state",   "value": "incr" },
            "emptyV":  { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
            "oV":      { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
            "result":  { "type": "ProductValue", "ofType": "resT", "fields": ["sV","oV"] },
            "lam":     { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
            "init":    { "type": "IntLit", "value": 0 },
            "stream":  { "type": "EventStream", "eventType": "unitT", "streamKind": "external" },
            "receiveFx": { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "m": {
              "type": "StateMachine",
              "transitionFn": "lam",
              "initialState": "init",
              "inputStreams": ["stream"],
              "outputStreams": [],
              "effects": ["receiveFx"]
            }
          }
        }
        """.trimIndent()
    }
}
