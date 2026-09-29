package org.strand.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.ConsumerMode
import org.strand.core.NodeId
import org.strand.interpreter.Value
import kotlin.time.Duration.Companion.seconds

/**
 * Review H1: a halted actor must not wedge its upstream. Pipeline A → B where
 * B halts on a capability denial on its first event while A keeps producing
 * 5,000 more; with a small buffer and the default BlockProducer policy, A
 * used to suspend forever on the full internal channel and `await()` hung.
 *
 * The virtual-time tests wrap the run in a `withTimeout` measured on the test
 * scheduler: a deadlock surfaces immediately as a virtual timeout instead of
 * a real-time hang, and a completed run consumes zero virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HaltedConsumerBackpressureTest {

    private val extraEvents = 5_000

    private data class Pipeline(
        val m: StringMachines,
        val a: NodeId,
        val b: NodeId,
        val c: NodeId?,
        val input: NodeId,
    )

    private fun pipeline(broadcast: Boolean): Pipeline {
        val m = StringMachines()
        val input = m.externalStream()
        val internal = m.internalStream(
            mode = if (broadcast) ConsumerMode.Broadcast else null,
            bufferSize = 4,
        )
        val a = m.forwarder(input, internal)
        val b = m.writer(internal)
        val c = if (broadcast) m.sink(internal) else null
        return Pipeline(m, a, b, c, input)
    }

    private fun group(p: Pipeline) = MachineGroup(
        store = p.m.store,
        hashToNodeId = emptyMap(),
        machines = listOfNotNull(p.a, p.b, p.c),
        capabilities = p.m.okOnlyGrant(),
        bufferCapacity = 4,
    )

    private suspend fun feed(handle: MachineGroupHandle, input: NodeId) {
        val ch = handle.externalInputs.getValue(input)
        ch.send(Value.StringV("evil"))
        repeat(extraEvents) { ch.send(Value.StringV("ok")) }
        ch.close()
    }

    private fun instanceOf(handle: MachineGroupHandle, store: StringMachines, machine: NodeId): MachineInstanceHandle {
        val transitionFn = (store.store.get(machine) as org.strand.core.Node.StateMachine).transitionFn
        return handle.instances.values.single { it.machineId == transitionFn }
    }

    @Test
    fun `direct pipeline - halted consumer does not wedge upstream (virtual time)`() = runTest {
        val p = pipeline(broadcast = false)
        val handle = StateMachineRuntime(p.m.store).runGroup(group(p), this)
        withTimeout(5.seconds) {
            feed(handle, p.input)
            handle.await()
        }
        assertEquals(0L, testScheduler.currentTime) { "no virtual time should elapse" }
        val a = instanceOf(handle, p.m, p.a)
        val b = instanceOf(handle, p.m, p.b)
        assertTrue(a.halted)
        assertNull(a.denialReport)
        assertEquals(Value.StringV("ok"), a.currentState)
        assertEquals(extraEvents + 1, a.recordedEvents()!!.size) { "A processed every event" }
        assertTrue(b.halted)
        assertNotNull(b.denialReport)
        assertEquals(0, b.denialReport!!.eventIndex)
        assertEquals(1, b.recordedEvents()!!.size) { "B recorded only the event it transitioned on" }
        val metrics = handle.metrics().perInstance.getValue(b.instanceId)
        assertEquals(extraEvents.toLong(), metrics.eventsDiscardedAfterHalt)
    }

    @Test
    fun `broadcast pipeline - halted consumer does not stall the pump or its siblings (virtual time)`() = runTest {
        val p = pipeline(broadcast = true)
        val handle = StateMachineRuntime(p.m.store).runGroup(group(p), this)
        withTimeout(5.seconds) {
            feed(handle, p.input)
            handle.await()
        }
        assertEquals(0L, testScheduler.currentTime)
        val b = instanceOf(handle, p.m, p.b)
        val c = instanceOf(handle, p.m, p.c!!)
        assertNotNull(b.denialReport)
        assertTrue(c.halted)
        assertNull(c.denialReport)
        assertEquals(extraEvents + 1, c.recordedEvents()!!.size) { "sibling consumer saw every event" }
    }

    @Test
    fun `direct pipeline - halted consumer does not wedge upstream (real dispatcher)`() = runBlocking {
        val p = pipeline(broadcast = false)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val handle = StateMachineRuntime(p.m.store).runGroup(group(p), scope)
            withTimeout(10.seconds) {
                feed(handle, p.input)
                handle.await()
            }
            val a = instanceOf(handle, p.m, p.a)
            assertTrue(a.halted)
            assertEquals(extraEvents + 1, a.recordedEvents()!!.size)
            assertNotNull(instanceOf(handle, p.m, p.b).denialReport)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `broadcast pipeline - halted consumer does not stall siblings (real dispatcher)`() = runBlocking {
        val p = pipeline(broadcast = true)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val handle = StateMachineRuntime(p.m.store).runGroup(group(p), scope)
            withTimeout(10.seconds) {
                feed(handle, p.input)
                handle.await()
            }
            val c = instanceOf(handle, p.m, p.c!!)
            assertFalse(c.denialReport != null)
            assertEquals(extraEvents + 1, c.recordedEvents()!!.size)
        } finally {
            scope.cancel()
        }
    }
}
