package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.EvaluationLimits
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.HostContext
import org.strand.interpreter.Value

/**
 * Review M2 (a): the [TransitionDispatcher] path ignored per-event limits and
 * tenant wiring, and [InterpreterDispatcherFactory] shared one interpreter
 * built without the group's host context or in-band foreign dispatcher.
 * Review M4 (b): replaying a recording of a machine whose transition reaches
 * a stateful or nondeterministic builtin silently diverged; the replay entry
 * point now refuses with a typed error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherWiringAndReplayTest {

    private fun timeMachine(m: StringMachines): Pair<NodeId, NodeId> {
        val timeFx = m.store.add(Node.EffectCategory("Time.Now"))
        val nowT = m.store.add(Node.FunctionType(parameters = emptyList(), result = m.intT, effects = listOf(timeFx)))
        val now = m.store.add(Node.ForeignNode(target = "strand-builtin:Time.Now", foreignType = nowT, effects = listOf(timeFx)))
        val toStrT = m.store.add(Node.FunctionType(parameters = listOf(m.intT), result = m.strT))
        val toStr = m.store.add(Node.ForeignNode(target = "strand-builtin:String.FromInt", foreignType = toStrT))
        val input = m.externalStream()
        val machine = m.sinkWithBody(input, effects = listOf(timeFx)) { _ ->
            val call = m.store.add(Node.Application(function = now, arguments = emptyList()))
            m.store.add(Node.Application(function = toStr, arguments = listOf(call)))
        }
        return machine to timeFx
    }

    @Test
    fun `dispatcher-backed actors read the tenant host context`() = runTest {
        val m = StringMachines()
        val (machine, timeFx) = timeMachine(m)
        val input = (m.store.get(machine) as Node.StateMachine).inputStreams.single()
        val tenant = HostContext.processDefault().copy(clock = Builtins.FixedClock(424242L))
        val runtime = StateMachineRuntime(m.store, hostContext = tenant)
        val handle = runtime.runGroup(
            MachineGroup(
                store = m.store,
                hashToNodeId = emptyMap(),
                machines = listOf(machine),
                capabilities = CapabilitySet.ofCategories(setOf(timeFx)),
                dispatcherFactory = InterpreterDispatcherFactory(m.store, emptyMap()),
            ),
            this,
        )
        val ch = handle.externalInputs.getValue(input)
        ch.send(Value.StringV("tick"))
        ch.close()
        handle.await()
        assertEquals(Value.StringV("424242"), handle.instances.values.single().currentState)
    }

    @Test
    fun `dispatcher-backed actors honour the group's evaluation limits`() = runTest {
        val limits = EvaluationLimits.DEFAULTS.copy(maxSteps = 40)
        suspend fun haltReasonWith(withDispatcher: Boolean): HaltReason? {
            val m = StringMachines()
            val input = m.externalStream()
            val machine = m.sink(input)
            val factory = if (withDispatcher) InterpreterDispatcherFactory(m.store, emptyMap()) else null
            val handle = StateMachineRuntime(m.store).runGroup(
                MachineGroup(store = m.store, hashToNodeId = emptyMap(), machines = listOf(machine), dispatcherFactory = factory),
                this,
                limits,
            )
            val ch = handle.externalInputs.getValue(input)
            repeat(200) { ch.send(Value.StringV("e$it")) }
            ch.close()
            handle.await()
            return handle.instances.values.single().haltReason
        }
        val direct = haltReasonWith(withDispatcher = false)
        assertTrue(direct is HaltReason.ResourceExhaustion) { "fixture must exhaust on the direct path, got $direct" }
        assertEquals(direct, haltReasonWith(withDispatcher = true))
    }

    @Test
    fun `replay of a recording refuses a machine whose transition reaches a stateful builtin`() {
        val m = StringMachines()
        val (machine, timeFx) = timeMachine(m)
        val runtime = StateMachineRuntime(m.store)
        val ex = assertThrows<ReplayNotDeterministic> {
            runtime.replay(machine, listOf(Value.StringV("tick")), CapabilitySet.ofCategories(setOf(timeFx)))
        }
        assertEquals(machine, ex.machine)
        assertEquals(listOf("strand-builtin:Time.Now"), ex.builtins)
    }

    @Test
    fun `replay of a pure machine reproduces the runMachine trace`() {
        val m = StringMachines()
        val input = m.externalStream()
        val machine = m.sink(input)
        val runtime = StateMachineRuntime(m.store)
        val events = listOf(Value.StringV("a"), Value.StringV("b"))
        val replayed = runtime.replay(machine, events)
        assertEquals(runtime.runMachine(machine, events).steps, replayed.steps)
        assertEquals(Value.StringV("b"), replayed.final.finalState)
    }
}
