package org.strand.runtime

import org.strand.core.Hash
import org.strand.core.NodeId
import org.strand.interpreter.Value

/**
 * Content-addressed snapshot of a [MachineInstance] at a point in time
 * (Layer 6 step 3 slice 3.3).
 *
 * A `Snapshot` is enough information to rebuild the actor's state and replay
 * any subsequent events deterministically. The host persists snapshots
 * out-of-band (to disk, S3, content-addressed blob storage); the runtime is
 * agnostic about storage. `Snapshot` is a plain data class; serialization
 * into bytes is the host's responsibility.
 *
 * Replay semantics: given a snapshot S and a list of post-snapshot events E,
 * `runtime.resume(machineId, S, E)` produces a [Trace] equivalent to the
 * trace the live actor would have produced if it had received E after the
 * snapshot point. Because Strand transitions are pure by spec, this equality
 * is by construction — the snapshot captures the only mutable state
 * (`currentState`).
 *
 * **Integrity verification.** [machineHash] records the content-addressed
 * hash of the StateMachine NodeId the snapshot was taken from. On resume,
 * the host MUST pass the same machine NodeId; the runtime asserts the
 * machine's hash matches the snapshot's recorded hash before replaying. If
 * the machine has been re-compiled with a different graph (different hash),
 * resume fails fast with [SnapshotMachineHashMismatch] rather than running
 * the wrong logic against the snapshotted state.
 *
 * **Recorder integration.** If the group enabled `recordInputs`, the
 * snapshot includes the actor's recorded event list pre-snapshot in
 * [recordedEventsPreSnapshot]. Combined with the post-snapshot events
 * passed to `resume`, this gives the host a full replay trail. When
 * `recordInputs` is false, the field is null.
 *
 * **Three deferred features** from the proposal §4.3 are out of this slice:
 *  * A `recorderHash: Hash` field that hashes the recorded event list —
 *    requires a canonical encoder over runtime [Value] (same blocker as
 *    `currentStateHash` in [InstanceMetrics]). Hosts that need integrity
 *    on the recorder list can compute their own digest over the list bytes.
 *  * Control-mailbox-coordinated snapshot — the slice 3.3 MVP reads
 *    `instance.currentState` directly from the host thread, which is
 *    consistent between transitions (the actor writes `currentState`
 *    atomically with a field assignment) but races with mid-transition
 *    snapshots. The proposal §6.3 describes the control-mailbox protocol
 *    for tight consistency; landing it requires per-actor control channels
 *    and a separate scheduled-message handling pass.
 *  * A wire format for cross-process snapshot transport — deferred until
 *    distribution lands (Milestone 2.5+).
 */
data class Snapshot(
    /**
     * The StateMachine NodeId's canonical content hash, from the
     * [org.strand.hashing.Hasher.finalize] result. Used at resume time to
     * verify the machine hasn't been re-compiled into a different graph.
     */
    val machineHash: Hash,
    /**
     * The actor's [currentState][MachineInstance.currentState] at snapshot
     * time. Resume re-injects this as the rebuilt instance's initial state.
     */
    val snapshotState: Value,
    /**
     * Number of events the actor had consumed pre-snapshot. Combined with
     * the recorder list, this lets the host compute "events since
     * snapshot" for replay slicing.
     */
    val processedEventCount: Long,
    /**
     * Recorder snapshot if [MachineGroup.recordInputs] was true; null
     * otherwise. The list is the events the actor consumed in arrival
     * order, identical to [MachineGroupHandle.recordedEvents].
     */
    val recordedEventsPreSnapshot: List<Value>? = null,
)

/**
 * Thrown by [StateMachineRuntime.resume] when the supplied [machineId]'s
 * canonical hash does not match the snapshot's [Snapshot.machineHash]. The
 * snapshot was taken against a different StateMachine graph; resuming would
 * apply the new logic to the old state, which is a content-addressing
 * violation. The host must either rebuild a fresh instance from
 * `runMachine` or recompile against the original graph.
 */
class SnapshotMachineHashMismatch(
    val expected: Hash,
    val actual: Hash,
) : RuntimeException(
    "snapshot was taken against StateMachine with hash $expected, " +
        "but resume was called against a machine with hash $actual; " +
        "the machine has been recompiled and replay is unsafe",
)

/**
 * Thrown by [MachineGroupHandle.snapshot] when the requested [InstanceId]
 * is not a member of this group. The host typically iterates
 * `MachineGroupHandle.instances` for valid ids.
 */
class SnapshotUnknownInstance(val instance: InstanceId) :
    RuntimeException("no instance with id $instance is part of this group")
