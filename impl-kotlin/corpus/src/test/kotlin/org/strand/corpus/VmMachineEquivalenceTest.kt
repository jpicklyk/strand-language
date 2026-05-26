package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.bytecode.Lowerer
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Value
import org.strand.runtime.HaltReason
import org.strand.runtime.StateMachineRuntime
import org.strand.runtime.Trace
import org.strand.runtime.TraceStep
import org.strand.runtime.EventCodec
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import org.strand.vm.Vm

/**
 * Q-017 step 1 Track A.4: state-machine dispatch through the VM.
 *
 * Each test loads a corpus state-machine program + its companion events
 * file, runs it twice — once through the existing interpreter-backed
 * [StateMachineRuntime.runMachine], and once through the VM-based driver
 * defined below — and asserts the two traces are equal.
 *
 * The VM-based driver mirrors the runtime's single-input-stream sync
 * runMachine logic but uses the VM for transition dispatch:
 *
 *  1. Lower the state machine's `transitionFn` NodeId via [Lowerer] into
 *     a [ChunkTable]. The chunk graph contains the transition's body
 *     plus any captured-environment chunks.
 *  2. Evaluate the root chunk via [Vm.evaluate] under the supplied caps
 *     — this produces a `VmClosure` (or similar callable) representing
 *     the transition function.
 *  3. Lower and evaluate `initialState` similarly to get the starting
 *     state Value.
 *  4. Per event: invoke [Vm.applyClosure] with `(state, event)` as args
 *     under caps; decompose the resulting `{state, outputs}` ProductV the
 *     same way [StateMachineRuntime.stepOnce] does.
 *  5. Accumulate trace steps; final halt is `EventsExhausted` (step 1
 *     semantics — no explicit termination yet).
 *
 * Programs covered (single-input-stream sync corpus): 41 toggle, 42
 * counter, 43 counter-with-overflow, 44 request-response, 45 bank-account.
 * Multi-input programs (47) and async programs (46, 48, 49) need the
 * actor loop and are out of this slice's scope — they'd be covered by
 * extending [StateMachineRuntime.runGroup] to use the VM dispatcher (a
 * larger architectural change).
 */
class VmMachineEquivalenceTest {

    private data class MachinePair(val baseName: String)

    private val machines = listOf(
        MachinePair("41-toggle-machine"),
        MachinePair("42-counter-machine"),
        MachinePair("43-counter-with-overflow-output"),
        MachinePair("44-request-response-echo"),
        MachinePair("45-bank-account-machine"),
        // 46 and 57 are single-input/single-output programs whose corpus
        // tests run through `runGroup` for the async path, but they can
        // equally run through the sync `runMachine` path used here.
        MachinePair("46-async-single-machine-counter"),
        MachinePair("57-dropoldest-overflow"),
    )

    @TestFactory
    fun vmEquivalentToRuntimeForSingleInputMachines(): List<DynamicTest> = machines.map { pair ->
        DynamicTest.dynamicTest(pair.baseName) {
            val programText = loadResource("/corpus/${pair.baseName}.json")
            val eventsText = loadResource("/corpus/${pair.baseName}.events.json")

            val ingest = JsonIngest.parse(programText)
            val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
            val verifyResult = Verifier(finalized.store, finalized.hashToNodeId)
                .verify(finalized.root)
            assertTrue(verifyResult is VerifyResult.Ok) {
                "${pair.baseName}: verifier failed: $verifyResult"
            }

            val events = EventCodec.parseEventList(eventsText)
            val effectCategoryIds: Set<NodeId> = finalized.store.entries()
                .asSequence()
                .filter { it.second is Node.EffectCategory }
                .map { it.first }
                .toSet()
            val capsForInterp = CapabilitySet.ofCategories(effectCategoryIds)
            val capsForVm: Set<Int> = effectCategoryIds.map { it.value }.toSet()

            // Reference trace: existing interpreter-backed runtime.
            val interpRuntime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
            val interpTrace = interpRuntime.runMachine(
                machine = finalized.root,
                events = events,
                capabilities = capsForInterp,
            )

            // VM trace: parallel driver using bytecode dispatch.
            val vmTrace = runMachineViaVm(
                store = finalized.store,
                hashToNodeId = finalized.hashToNodeId,
                machineId = finalized.root,
                events = events,
                caps = capsForVm,
            )

            assertTraceEqual(pair.baseName, interpTrace, vmTrace)
        }
    }

    /**
     * Parallel implementation of [StateMachineRuntime.runMachine] that
     * dispatches the transition function through the bytecode VM.
     * Mirrors the runtime's single-input-stream sync fold + OutputBatch
     * decomposition (see `StateMachineRuntime.stepOnce`).
     */
    private fun runMachineViaVm(
        store: NodeStore,
        hashToNodeId: Map<Hash, NodeId>,
        machineId: NodeId,
        events: List<Value>,
        caps: Set<Int>,
    ): Trace {
        val machine = store.get(machineId) as? Node.StateMachine
            ?: error("runMachineViaVm: $machineId is not a StateMachine")
        require(machine.inputStreams.size == 1) {
            "VM runMachine slice supports single-input-stream machines only; " +
                "got ${machine.inputStreams.size}"
        }

        // Lower the transition function and initial state, evaluate each
        // once via the VM. The closure produced by evaluating transitionFn
        // is reused across all events.
        val txTable = Lowerer(store, hashToNodeId).lower(machine.transitionFn)
        val txVm = Vm(txTable)
        val transitionClosure = txVm.evaluate(caps)

        val initTable = Lowerer(store, hashToNodeId).lower(machine.initialState)
        val initVm = Vm(initTable)
        val initialState = initVm.run(caps)

        var currentState: Value = initialState
        val steps = mutableListOf<TraceStep.Step>()

        for (event in events) {
            val resultValue = txVm.applyClosure(
                closure = transitionClosure,
                args = listOf(currentState, event),
                caps = caps,
            )
            val resultProduct = resultValue as? Value.ProductV
                ?: error("transition returned non-product: ${resultValue::class.simpleName}")
            val newState = resultProduct.fields.getValue("state")
            val outputBatch = resultProduct.fields.getValue("outputs") as Value.ProductV

            val emitted = mutableListOf<Value>()
            for ((i, _) in machine.outputStreams.withIndex()) {
                val slotName = "output_$i"
                val slot = outputBatch.fields.getValue(slotName) as Value.SumV
                when (slot.case) {
                    "Some" -> emitted += slot.payload
                        ?: error("Some slot '$slotName' has null payload")
                    "None" -> Unit
                    else -> error("unexpected case '${slot.case}'")
                }
            }
            steps += TraceStep.Step(
                event = event,
                before = currentState,
                after = newState,
                outputs = emitted,
            )
            currentState = newState
        }

        return Trace(
            steps = steps,
            final = TraceStep.Halt(
                finalState = currentState,
                reason = HaltReason.EventsExhausted,
            ),
        )
    }

    private fun assertTraceEqual(name: String, expected: Trace, actual: Trace) {
        assertEquals(expected.steps.size, actual.steps.size) {
            "$name: trace step count differs"
        }
        for ((i, expectedStep) in expected.steps.withIndex()) {
            val actualStep = actual.steps[i]
            assertEquals(expectedStep.event, actualStep.event) { "$name step $i event" }
            assertEquals(expectedStep.before, actualStep.before) { "$name step $i before" }
            assertEquals(expectedStep.after, actualStep.after) { "$name step $i after" }
            assertEquals(expectedStep.outputs, actualStep.outputs) { "$name step $i outputs" }
        }
        assertEquals(expected.final.finalState, actual.final.finalState) {
            "$name: final state differs"
        }
        assertEquals(expected.final.reason, actual.final.reason) {
            "$name: halt reason differs"
        }
    }

    private fun loadResource(resource: String): String {
        val stream = VmMachineEquivalenceTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }
}
