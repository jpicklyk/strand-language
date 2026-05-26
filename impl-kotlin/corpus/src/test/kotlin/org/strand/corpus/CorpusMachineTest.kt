package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Value
import org.strand.runtime.EventCodec
import org.strand.runtime.HaltReason
import org.strand.runtime.StateMachineRuntime
import org.strand.runtime.Trace
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * End-to-end Layer 6 step 1 corpus tests. For each StateMachine corpus
 * program in 41..45:
 *  - the program ingests, verifies, and runs through [StateMachineRuntime]
 *    against a paired hand-authored event list,
 *  - the resulting [Trace] is asserted against expected per-step transitions
 *    and emitted outputs,
 *  - replay determinism is verified per program (same inputs → same trace).
 */
class CorpusMachineTest {

    private data class Case(
        val programResource: String,
        val eventsResource: String,
        val expectedSteps: List<StepAssertion>,
        val expectedFinalState: Value,
        val notes: String = "",
    )

    /**
     * A single per-step assertion: the state before, the state after, the
     * list of emitted values (empty when no output streams or all slots
     * suppressed). The event itself is not asserted here — the event list
     * is what drives the run; per-step assertions focus on the *response*
     * (state transition and emissions).
     */
    private data class StepAssertion(
        val before: Value,
        val after: Value,
        val outputs: List<Value>,
    )

    /** Convenience for product fixture building. */
    private fun product(vararg fields: Pair<String, Value>): Value.ProductV =
        Value.ProductV(linkedMapOf(*fields))

    private val cases: List<Case> = run {
        val cases = mutableListOf<Case>()

        // 41 — toggle machine. State is Bool starting at false. Three Unit
        // events flip the state each time. No outputs.
        cases += Case(
            programResource = "/corpus/41-toggle-machine.json",
            eventsResource  = "/corpus/41-toggle-machine.events.json",
            expectedSteps = listOf(
                StepAssertion(Value.BoolV(false), Value.BoolV(true),  emptyList()),
                StepAssertion(Value.BoolV(true),  Value.BoolV(false), emptyList()),
                StepAssertion(Value.BoolV(false), Value.BoolV(true),  emptyList()),
            ),
            expectedFinalState = Value.BoolV(true),
            notes = "Toggle: false → true → false → true. No outputs declared.",
        )

        // 42 — counter machine. State is Int starting at 0. Events are
        // Increment/Decrement/Reset (nullary sums). No outputs.
        cases += Case(
            programResource = "/corpus/42-counter-machine.json",
            eventsResource  = "/corpus/42-counter-machine.events.json",
            expectedSteps = listOf(
                // events: Increment x3, Decrement, Reset, Increment
                StepAssertion(Value.IntV(0), Value.IntV(1), emptyList()),
                StepAssertion(Value.IntV(1), Value.IntV(2), emptyList()),
                StepAssertion(Value.IntV(2), Value.IntV(3), emptyList()),
                StepAssertion(Value.IntV(3), Value.IntV(2), emptyList()),
                StepAssertion(Value.IntV(2), Value.IntV(0), emptyList()),
                StepAssertion(Value.IntV(0), Value.IntV(1), emptyList()),
            ),
            expectedFinalState = Value.IntV(1),
            notes = "Counter via Match on Increment/Decrement/Reset sum. No outputs.",
        )

        // 43 — counter with overflow output. State is Int starting at 0.
        // Events are Increment/Decrement. output_0 emits Some(newState) when
        // newState > 5, otherwise None. Events: Inc x7, Dec.
        // Trajectory: 0→1, 1→2, 2→3, 3→4, 4→5, 5→6, 6→7, 7→6.
        // Output: 5→6 emits 6, 6→7 emits 7, 7→6 emits 6.
        cases += Case(
            programResource = "/corpus/43-counter-with-overflow-output.json",
            eventsResource  = "/corpus/43-counter-with-overflow-output.events.json",
            expectedSteps = listOf(
                StepAssertion(Value.IntV(0), Value.IntV(1), emptyList()),
                StepAssertion(Value.IntV(1), Value.IntV(2), emptyList()),
                StepAssertion(Value.IntV(2), Value.IntV(3), emptyList()),
                StepAssertion(Value.IntV(3), Value.IntV(4), emptyList()),
                StepAssertion(Value.IntV(4), Value.IntV(5), emptyList()),
                StepAssertion(Value.IntV(5), Value.IntV(6), listOf(Value.IntV(6))),
                StepAssertion(Value.IntV(6), Value.IntV(7), listOf(Value.IntV(7))),
                StepAssertion(Value.IntV(7), Value.IntV(6), listOf(Value.IntV(6))),
            ),
            expectedFinalState = Value.IntV(6),
            notes = "Threshold of 5; output_0 is Some(state) on overflow, None otherwise.",
        )

        // 44 — request-response echo. State is Unit. Each request event is
        // {requestId, payload}; the transition emits Some({requestId,
        // response: payload}) to the output stream. Events: two requests.
        run {
            val req1 = product("requestId" to Value.IntV(1), "payload" to Value.StringV("ping"))
            val req2 = product("requestId" to Value.IntV(2), "payload" to Value.StringV("hello"))
            val resp1 = product("requestId" to Value.IntV(1), "response" to Value.StringV("ping"))
            val resp2 = product("requestId" to Value.IntV(2), "response" to Value.StringV("hello"))
            cases += Case(
                programResource = "/corpus/44-request-response-echo.json",
                eventsResource  = "/corpus/44-request-response-echo.events.json",
                expectedSteps = listOf(
                    StepAssertion(Value.UnitV, Value.UnitV, listOf(resp1)),
                    StepAssertion(Value.UnitV, Value.UnitV, listOf(resp2)),
                ),
                expectedFinalState = Value.UnitV,
                notes = "Stateless echo; emits a response for every request. " +
                    "req1 = ${req1}, req2 = ${req2}",
            )
        }

        // 45 — bank account capstone. State is {balance, transactionCount},
        // starts at {0, 0}. Events:
        //   Deposit(100) → state {100, 1}, no output
        //   Deposit(50)  → state {150, 2}, no output
        //   Query        → state {150, 2}, output Some(150)
        //   Withdraw(30) → state {120, 3}, no output
        //   Withdraw(1000) → state UNCHANGED {120, 3}, output Some(-1)
        //                    (overdraft signal — Withdraw fails when
        //                     balance < requested)
        //   Query        → state {120, 3}, output Some(120)
        run {
            val state0   = product("balance" to Value.IntV(0),   "transactionCount" to Value.IntV(0))
            val state100 = product("balance" to Value.IntV(100), "transactionCount" to Value.IntV(1))
            val state150 = product("balance" to Value.IntV(150), "transactionCount" to Value.IntV(2))
            val state120 = product("balance" to Value.IntV(120), "transactionCount" to Value.IntV(3))
            cases += Case(
                programResource = "/corpus/45-bank-account-machine.json",
                eventsResource  = "/corpus/45-bank-account-machine.events.json",
                expectedSteps = listOf(
                    StepAssertion(state0,   state100, emptyList()),                   // Deposit 100
                    StepAssertion(state100, state150, emptyList()),                   // Deposit 50
                    StepAssertion(state150, state150, listOf(Value.IntV(150))),       // Query
                    StepAssertion(state150, state120, emptyList()),                   // Withdraw 30
                    StepAssertion(state120, state120, listOf(Value.IntV(-1))),        // Withdraw 1000 (overdraft)
                    StepAssertion(state120, state120, listOf(Value.IntV(120))),       // Query
                ),
                expectedFinalState = state120,
                notes = "Capstone: Match + products + sums + conditional outputs.",
            )
        }

        cases
    }

    @TestFactory
    fun corpusMachines(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.programResource.substringAfterLast('/')) {
            val program = loadResource(case.programResource)
            val ingest = JsonIngest.parse(program)
            val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
            val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
            assertTrue(verify is VerifyResult.Ok) {
                "verifier failed for ${case.programResource}: $verify"
            }

            val eventsText = loadResource(case.eventsResource)
            val events = EventCodec.parseEventList(eventsText)

            val runtime = StateMachineRuntime(finalized.store, finalized.hashToNodeId)
            val trace = runtime.runMachine(finalized.root, events)

            assertTraceMatches(trace, case)

            // Replay determinism: re-run with the same inputs and require
            // identical Traces. This pins the proposal § 6 property at the
            // corpus level.
            val replay = runtime.runMachine(finalized.root, events)
            assertEquals(trace, replay) {
                "Replay determinism failed for ${case.programResource}"
            }
        }
    }

    private fun assertTraceMatches(trace: Trace, case: Case) {
        assertEquals(case.expectedSteps.size, trace.steps.size) {
            "Step count mismatch for ${case.programResource}: " +
                "expected ${case.expectedSteps.size}, got ${trace.steps.size}\n" +
                "Trace: $trace"
        }
        for ((i, exp) in case.expectedSteps.withIndex()) {
            val actual = trace.steps[i]
            assertEquals(exp.before, actual.before) {
                "Step $i 'before' mismatch in ${case.programResource}"
            }
            assertEquals(exp.after, actual.after) {
                "Step $i 'after' mismatch in ${case.programResource}"
            }
            assertEquals(exp.outputs, actual.outputs) {
                "Step $i outputs mismatch in ${case.programResource}"
            }
        }
        assertEquals(HaltReason.EventsExhausted, trace.final.reason)
        assertEquals(case.expectedFinalState, trace.final.finalState) {
            "Final state mismatch for ${case.programResource}"
        }
    }

    private fun loadResource(resource: String): String {
        val stream = CorpusMachineTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }
}
