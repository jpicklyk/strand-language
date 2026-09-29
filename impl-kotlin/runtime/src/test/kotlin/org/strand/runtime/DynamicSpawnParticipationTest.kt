package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.ConsumerMode
import org.strand.core.Hash
import org.strand.interpreter.Value
import kotlin.time.Duration.Companion.seconds

/**
 * Review M3: dynamically spawned instances were second-class — `cancel()`
 * cancelled only the frozen initial job list, `snapshot` / `metrics` /
 * `recordedEvents` consulted the frozen initial map, and two instances of one
 * machine on a broadcast stream shared one consumer channel (each saw a
 * fraction of the events). Spawned instances now live in the registry the
 * handle consults, and broadcast consumers are keyed by instance id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DynamicSpawnParticipationTest {

    private val hash = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { 3 })

    @Test
    fun `cancel tears down dynamically spawned instances`() = runTest {
        val m = StringMachines()
        val input = m.externalStream()
        val machine = m.sink(input)
        val handle = StateMachineRuntime(m.store).runGroup(
            MachineGroup(store = m.store, hashToNodeId = emptyMap(), machines = listOf(machine)),
            this,
        )
        handle.spawn(machine)
        // The external input is never closed: only cancellation ends the
        // actors. await() after cancel() must not wait on a live spawned actor.
        withTimeout(5.seconds) {
            handle.cancel()
            handle.await()
        }
        assertEquals(2, handle.allInstances.size)
    }

    @Test
    fun `snapshot, recordedEvents and metrics cover a spawned instance`() = runTest {
        val m = StringMachines()
        val input = m.externalStream()
        val machine = m.sink(input)
        val handle = StateMachineRuntime(m.store).runGroup(
            MachineGroup(
                store = m.store,
                hashToNodeId = emptyMap(),
                machines = listOf(machine),
                nodeIdToHash = mapOf(machine to hash),
            ),
            this,
        )
        val spawned = handle.spawn(machine)
        val ch = handle.externalInputs.getValue(input)
        repeat(10) { ch.send(Value.StringV("e$it")) }
        ch.close()
        withTimeout(5.seconds) { handle.await() }

        val snapshot = handle.snapshot(spawned)
        assertEquals(hash, snapshot.machineHash)
        val recorded = handle.recordedEvents(spawned)
        assertNotNull(recorded)
        assertEquals(snapshot.processedEventCount, recorded!!.size.toLong())
        assertTrue(spawned in handle.metrics().perInstance.keys)
        // Both instances share the Direct input, so together they consumed all ten.
        val total = handle.allInstances.keys.sumOf { handle.recordedEvents(it)!!.size }
        assertEquals(10, total)
    }

    @Test
    fun `two instances of one machine on a broadcast stream each receive every event`() = runTest {
        val m = StringMachines()
        val input = m.externalStream()
        val internal = m.internalStream(mode = ConsumerMode.Broadcast)
        val producer = m.forwarder(input, internal)
        val consumer = m.sink(internal)
        val handle = StateMachineRuntime(m.store).runGroup(
            MachineGroup(store = m.store, hashToNodeId = emptyMap(), machines = listOf(producer, consumer)),
            this,
        )
        val spawned = handle.spawn(consumer)
        val ch = handle.externalInputs.getValue(input)
        repeat(25) { ch.send(Value.StringV("e$it")) }
        ch.close()
        withTimeout(5.seconds) { handle.await() }

        val consumerFn = (m.store.get(consumer) as org.strand.core.Node.StateMachine).transitionFn
        val consumers = handle.allInstances.values.filter { it.machineId == consumerFn }
        assertEquals(2, consumers.size)
        assertTrue(consumers.any { it.instanceId == spawned })
        for (c in consumers) {
            assertEquals(25, c.recordedEvents()!!.size) { "instance ${c.instanceId} missed events" }
            assertEquals(Value.StringV("e24"), c.currentState)
        }
    }
}
