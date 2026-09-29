package org.strand.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.interpreter.Value
import kotlin.time.Duration.Companion.seconds

/**
 * Review M1: `captureSnapshot` read the state, the transition counter and the
 * recorder separately while the actor mutated them on another thread, so a
 * mid-transition snapshot could carry N+1 recorded events with an N-event
 * state (and replaying it would apply an event twice). Under
 * `Dispatchers.Default`, with a busy actor and a concurrent snapshot loop,
 * every snapshot must be internally consistent: the recorded prefix has
 * exactly `processedEventCount` events and the state is the one those events
 * produce.
 */
class SnapshotAtomicityTest {

    @Test
    fun `snapshots taken during a busy actor are atomic with respect to a transition`() = runBlocking {
        val m = StringMachines()
        val input = m.externalStream()
        val machine = m.sink(input)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val group = MachineGroup(
                store = m.store,
                hashToNodeId = emptyMap(),
                machines = listOf(machine),
                nodeIdToHash = mapOf(machine to Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { 7 })),
                bufferCapacity = 64,
            )
            val handle = StateMachineRuntime(m.store).runGroup(group, scope)
            val instanceId = handle.instances.keys.single()
            val total = 5_000
            withTimeout(30.seconds) {
                // Each snapshot is checked as it is taken (holding thousands of
                // snapshots, each with a copy of the recording, would be
                // quadratic in memory).
                val checked = async(Dispatchers.Default) {
                    var count = 0
                    while (!handle.instances.getValue(instanceId).halted) {
                        val s = handle.snapshot(instanceId)
                        val recorded = s.recordedEventsPreSnapshot!!
                        assertEquals(s.processedEventCount, recorded.size.toLong()) {
                            "snapshot carries ${recorded.size} events but ${s.processedEventCount} transitions"
                        }
                        val n = s.processedEventCount.toInt()
                        val expectedState = if (n == 0) Value.StringV("") else Value.StringV("e${n - 1}")
                        assertEquals(expectedState, s.snapshotState) { "state does not match $n processed events" }
                        count++
                    }
                    count
                }
                launch(Dispatchers.Default) {
                    val ch = handle.externalInputs.getValue(input)
                    for (i in 0 until total) ch.send(Value.StringV("e$i"))
                    ch.close()
                }.join()
                handle.await()
                assertTrue(checked.await() > 0)
            }
        } finally {
            scope.cancel()
        }
    }
}
