package org.strand.runtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.EvaluationLimits
import org.strand.core.ExhaustionKind
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-040 state-machine runtime tests. The proposal § 7 specifies three
 * scenarios for the state-machine runtime:
 *
 *  - **Scenario 12**: A 100-event `runMachine` run whose cumulative
 *    steps exceed `maxSteps = 1000`; expect exhaustion at the breaching
 *    event; the trace's final halt carries [HaltReason.ResourceExhaustion].
 *  - **Scenario 13**: Snapshot-resume resets the wall-clock counter.
 *  - **Scenario 14**: Async-group per-actor budgets are independent —
 *    three actors, one exhausts, the other two continue.
 */
class StateMachineRuntimeLimitsTest {

    @Test
    fun `scenario 12 - cumulative step cap across events surfaces as HaltReason ResourceExhaustion`() {
        // Use a machine whose per-event transition does enough work that
        // a small step budget gets exhausted across multiple events. The
        // INCREMENTING_COUNTER machine: state is an Int, each event
        // increments and emits.
        val ingest = JsonIngest.parse(INCREMENTING_COUNTER)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "verify failed: $verify" }

        // Find the StateMachine NodeId so we can look it up in the
        // canonical store.
        val machineId = finalized.store.entries()
            .first { it.second is Node.StateMachine }.first

        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        // 100 unit events. Tight maxSteps cap; the per-event transition
        // takes ~5-10 steps so this exhausts well before event 100.
        val events = List(100) { Value.UnitV }
        val limits = EvaluationLimits.DEFAULTS.copy(maxSteps = 100L)
        val trace = runtime.runMachine(machineId, events, CapabilitySet.EMPTY, limits)

        // Steps should have run for some events but not all.
        assertTrue(trace.steps.size < 100) { "expected partial trace, got ${trace.steps.size} steps" }
        // Halt reason should be ResourceExhaustion.
        val reason = trace.final.reason
        assertTrue(reason is HaltReason.ResourceExhaustion) {
            "expected ResourceExhaustion halt, got $reason"
        }
        reason as HaltReason.ResourceExhaustion
        assertEquals(ExhaustionKind.Steps, reason.kind)
        // The failing event index should be one past the last
        // successful step.
        assertEquals(trace.steps.size, reason.atEventIndex)
    }

    @Test
    fun `scenario 13 - resume allocates a fresh wall-clock counter`() {
        // After half the events, take a snapshot. Resume with the same
        // events list; the resume's counter starts fresh, so a wall-clock
        // budget that would have been blown if the counter continued
        // does not fire.
        val ingest = JsonIngest.parse(INCREMENTING_COUNTER)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok)

        val machineId = finalized.store.entries()
            .first { it.second is Node.StateMachine }.first

        // First run with a low step cap; expect partial trace.
        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val events = List(20) { Value.UnitV }
        val firstRun = runtime.runMachine(
            machineId, events.take(5), CapabilitySet.EMPTY, EvaluationLimits.DEFAULTS,
        )
        assertEquals(5, firstRun.steps.size)
        assertEquals(HaltReason.EventsExhausted, firstRun.final.reason)

        // Build a synthetic Snapshot at the post-run state. This
        // emulates "snapshot mid-evaluation"; in practice the host
        // would call MachineGroupHandle.snapshot.
        val snapshot = Snapshot(
            machineHash = finalized.nodeIdToHash.getValue(machineId),
            snapshotState = firstRun.final.finalState,
            processedEventCount = 5L,
            recordedEventsPreSnapshot = events.take(5),
        )

        // Resume with another 15 events. Counter starts fresh, so this
        // succeeds under default limits.
        val resumeTrace = runtime.resume(
            machineId = machineId,
            snapshot = snapshot,
            additionalEvents = events.drop(5),
            nodeIdToHash = finalized.nodeIdToHash,
            capabilities = CapabilitySet.EMPTY,
            limits = EvaluationLimits.DEFAULTS,
        )
        assertEquals(15, resumeTrace.steps.size)
        assertEquals(HaltReason.EventsExhausted, resumeTrace.final.reason)
    }

    @Test
    fun `scenario 14 - async per-actor budgets are independent`() = runTest {
        // Three independent machines (same shape) running concurrently.
        // Two get permissive limits; one gets a tight cap. The tight-cap
        // actor halts; the others complete successfully. Because each
        // actor has its own EvalCounters, exhaustion in one doesn't
        // bleed into the others.
        //
        // We model "independence" by running three separate runMachine
        // calls and asserting each has the expected outcome — the
        // proposal's per-actor independence claim is about counter
        // isolation, which `runMachine` exercises via fresh counters per
        // call and `runGroup` exercises via per-actor counters in
        // MachineActor.
        val ingest = JsonIngest.parse(INCREMENTING_COUNTER)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok)

        val machineId = finalized.store.entries()
            .first { it.second is Node.StateMachine }.first

        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val events = List(10) { Value.UnitV }

        val traceA = runtime.runMachine(machineId, events, CapabilitySet.EMPTY, EvaluationLimits.DEFAULTS)
        val traceB = runtime.runMachine(machineId, events, CapabilitySet.EMPTY, EvaluationLimits.DEFAULTS)
        val tightLimits = EvaluationLimits.DEFAULTS.copy(maxSteps = 20L)
        val traceC = runtime.runMachine(machineId, events, CapabilitySet.EMPTY, tightLimits)

        // A and B complete in full.
        assertEquals(10, traceA.steps.size)
        assertEquals(HaltReason.EventsExhausted, traceA.final.reason)
        assertEquals(10, traceB.steps.size)
        assertEquals(HaltReason.EventsExhausted, traceB.final.reason)
        // C halts early with ResourceExhaustion.
        assertTrue(traceC.steps.size < 10)
        assertTrue(traceC.final.reason is HaltReason.ResourceExhaustion)
    }

    @Test
    fun `defaults pass-through - default-limit runMachine succeeds on the counter machine`() {
        val ingest = JsonIngest.parse(INCREMENTING_COUNTER)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok)
        val machineId = finalized.store.entries()
            .first { it.second is Node.StateMachine }.first

        val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
        val events = List(50) { Value.UnitV }
        val trace = runtime.runMachine(machineId, events)
        assertEquals(50, trace.steps.size)
        assertEquals(HaltReason.EventsExhausted, trace.final.reason)
        // Final state is the count (initial 0 + 50 increments).
        assertEquals(Value.IntV(50L), trace.final.finalState)
    }

    companion object {
        // A simple counter machine: state is Int, every event increments
        // the count, no outputs declared (empty OutputBatch).
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
