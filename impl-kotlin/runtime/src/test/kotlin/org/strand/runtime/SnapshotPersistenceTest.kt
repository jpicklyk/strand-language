package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.Value
import java.nio.file.Path

/**
 * Q-059 piece 2: snapshot persistence across a (simulated) process restart.
 *
 * The load-bearing test is the equivalence property: run a group partway,
 * snapshot, serialize to disk, drop the in-memory objects, deserialize in a
 * FRESH `StrandRuntime` (a new `HostPolicy` — the "new process"), resume over
 * the remaining events, and assert the trajectory equals an uninterrupted run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotPersistenceTest {

    @Test
    fun `SnapshotCodec round-trips a snapshot with a recorder tail`() {
        val machineHash = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { it.toByte() })
        val snapshot = Snapshot(
            machineHash = machineHash,
            snapshotState = Value.ProductV(
                linkedMapOf("count" to Value.IntV(6L), "label" to Value.StringV("svc"))
            ),
            processedEventCount = 3L,
            recordedEventsPreSnapshot = listOf(Value.IntV(1L), Value.IntV(2L), Value.IntV(3L)),
        )
        val text = SnapshotCodec.encode(snapshot)
        val decoded = SnapshotCodec.decode(text)
        assertEquals(snapshot.machineHash, decoded.machineHash)
        assertEquals(snapshot.snapshotState, decoded.snapshotState)
        assertEquals(snapshot.processedEventCount, decoded.processedEventCount)
        assertEquals(snapshot.recordedEventsPreSnapshot, decoded.recordedEventsPreSnapshot)
    }

    @Test
    fun `SnapshotCodec round-trips a snapshot without a recorder tail`() {
        val machineHash = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32))
        val snapshot = Snapshot(
            machineHash = machineHash,
            snapshotState = Value.IntV(42L),
            processedEventCount = 7L,
            recordedEventsPreSnapshot = null,
        )
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))
        assertEquals(snapshot.machineHash, decoded.machineHash)
        assertEquals(snapshot.snapshotState, decoded.snapshotState)
        assertEquals(7L, decoded.processedEventCount)
        assertNull(decoded.recordedEventsPreSnapshot)
    }

    @Test
    fun `SnapshotCodec rejects a non-snapshotable state`() {
        val machineHash = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32))
        val bad = Snapshot(
            machineHash = machineHash,
            snapshotState = Value.Resource(id = 1L, kind = "socket"),
            processedEventCount = 0L,
        )
        assertThrows(ValueCodecError.NotSnapshotable::class.java) { SnapshotCodec.encode(bad) }
    }

    @Test
    fun `SnapshotCodec rejects a malformed document`() {
        assertThrows(SnapshotCodecError::class.java) { SnapshotCodec.decode("""{"machineHash":"zz"}""") }
        assertThrows(SnapshotCodecError::class.java) { SnapshotCodec.decode("""not json""") }
    }

    @Test
    fun `snapshot survives a simulated restart and the resumed trajectory equals an uninterrupted run`(
        @TempDir tmp: Path,
    ) = runTest {
        val ingest = JsonIngest.parse(RuntimeMetricsTestSeed.COUNTER_JSON)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val machineId = finalized.root

        // --- "Process A": run the first 3 of 6 events, snapshot, write to disk.
        val runtimeA = StrandRuntime(HostPolicy.OPEN)
        val imageA = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        val snapshotFile = tmp.resolve("counter.snapshot.json")

        val rawRuntimeA = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val group = MachineGroup(
            store = finalized.store,
            hashToNodeId = finalized.hashToNodeId,
            machines = listOf(machineId),
            nodeIdToHash = finalized.nodeIdToHash,
        )
        val handle = rawRuntimeA.runGroup(group, this)
        val input = handle.externalInputs.values.single()
        val output = handle.externalOutputs.values.single()
        for (i in 1..3) input.send(Value.IntV(i.toLong()))
        repeat(3) { output.receive() }
        val snapshot = handle.snapshot(handle.instances.keys.single())
        input.close()
        handle.await()

        // Persist through the facade. From here on the test never touches the
        // in-memory `snapshot` object again — only the file — modeling the
        // process boundary: nothing crosses it but the bytes on disk.
        runtimeA.writeSnapshot(snapshot, snapshotFile)
        assertEquals(Value.IntV(6L), snapshot.snapshotState)  // 1+2+3
        // imageA / runtimeA are Process A's program image and runtime; they
        // play no further part — Process B reconstructs everything from bytes.
        @Suppress("UNUSED_VARIABLE") val processADropped = listOf(imageA, runtimeA)

        // --- "Process B": a fresh runtime + fresh HostPolicy reads the snapshot
        // and resumes over the remaining events. This models the new process.
        val runtimeB = StrandRuntime(HostPolicy.OPEN)
        val imageB = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        val restored = runtimeB.readSnapshot(snapshotFile)
        assertEquals(Value.IntV(6L), restored.snapshotState)
        assertEquals(3L, restored.processedEventCount)
        assertNotNull(restored.recordedEventsPreSnapshot)

        val remaining = listOf(Value.IntV(4L), Value.IntV(5L), Value.IntV(6L))
        val resumedTrace = runtimeB.resume(
            program = imageB,
            machine = machineId,
            snapshot = restored,
            additionalEvents = remaining,
            nodeIdToHash = finalized.nodeIdToHash,
        )

        // --- Equivalence: an uninterrupted single run over all 6 events.
        val uninterrupted = runtimeB.runMachine(
            program = imageB,
            machine = machineId,
            events = (1..6).map { Value.IntV(it.toLong()) },
        )

        // Final state equal (1+...+6 = 21).
        assertEquals(Value.IntV(21L), resumedTrace.final.finalState)
        assertEquals(uninterrupted.final.finalState, resumedTrace.final.finalState)
        // Per-step state-after and outputs equal across the post-snapshot overlap.
        for (i in 0 until 3) {
            assertEquals(uninterrupted.steps[i + 3].after, resumedTrace.steps[i].after, "step $i state")
            assertEquals(uninterrupted.steps[i + 3].outputs, resumedTrace.steps[i].outputs, "step $i outputs")
        }
    }

    @Test
    fun `resume through the facade still enforces the machine-hash integrity check`() = runTest {
        val ingest = JsonIngest.parse(RuntimeMetricsTestSeed.COUNTER_JSON)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val machineId = finalized.root

        val wrongHash = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32))
        val badSnapshot = Snapshot(
            machineHash = wrongHash,
            snapshotState = Value.IntV(99L),
            processedEventCount = 0L,
        )
        val runtime = StrandRuntime(HostPolicy.OPEN)
        val image = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        val ex = assertThrows(SnapshotMachineHashMismatch::class.java) {
            runtime.resume(
                program = image,
                machine = machineId,
                snapshot = badSnapshot,
                additionalEvents = listOf(Value.IntV(1L)),
                nodeIdToHash = finalized.nodeIdToHash,
            )
        }
        assertEquals(wrongHash, ex.expected)
        assertNotEquals(wrongHash, ex.actual)
    }
}
