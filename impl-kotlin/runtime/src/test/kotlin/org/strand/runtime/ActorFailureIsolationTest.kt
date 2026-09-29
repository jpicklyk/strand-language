package org.strand.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Node
import org.strand.interpreter.Builtins
import org.strand.interpreter.Value
import kotlin.time.Duration.Companion.seconds

/**
 * Review M6: an unexpected throwable inside one actor used to propagate out of
 * its coroutine and cancel every sibling plus the caller's scope. The actor is
 * now its own supervision boundary: the failure becomes that instance's halt
 * ([HaltReason.InstanceFailure] carrying the throwable's class), siblings run
 * to completion, and the group handle reports the failed instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActorFailureIsolationTest {

    private val boom = "strand-builtin:Test.Boom"

    @AfterEach
    fun clear() = Builtins.clearTestBuiltins()

    @Test
    fun `one actor's IllegalStateException halts that instance only`() = runTest {
        Builtins.installTestBuiltin(
            boom,
            effectful = false,
            determinism = Builtins.Determinism.Deterministic,
            fn = Builtins.Fn { _, args ->
                if ((args[0] as Value.StringV).v == "explode") throw IllegalStateException("deliberate")
                args[0]
            },
        )
        val m = StringMachines()
        val failingInput = m.externalStream()
        // A distinct stream node for the healthy sibling (different buffer size
        // keeps content addressing from merging the two streams).
        val healthyInput = m.store.add(Node.EventStream(
            eventType = m.strT, streamKind = org.strand.core.StreamKind.External, bufferSize = 8,
        ))
        val failing = m.foreignSink(failingInput, boom)
        val healthy = m.sink(healthyInput)
        val handle = StateMachineRuntime(m.store).runGroup(
            MachineGroup(store = m.store, hashToNodeId = emptyMap(), machines = listOf(failing, healthy)),
            this,
        )
        withTimeout(5.seconds) {
            val f = handle.externalInputs.getValue(failingInput)
            f.send(Value.StringV("fine"))
            f.send(Value.StringV("explode"))
            repeat(100) { f.send(Value.StringV("after")) }
            f.close()
            val h = handle.externalInputs.getValue(healthyInput)
            repeat(50) { h.send(Value.StringV("h$it")) }
            h.close()
            handle.await()
        }
        assertTrue(isActive) { "the caller's scope must not be cancelled" }

        val failingFn = (m.store.get(failing) as Node.StateMachine).transitionFn
        val failed = handle.instances.values.single { it.machineId == failingFn }
        val sibling = handle.instances.values.single { it.machineId != failingFn }

        assertTrue(failed.halted)
        val reason = failed.haltReason
        assertTrue(reason is HaltReason.InstanceFailure) { "expected InstanceFailure, got $reason" }
        reason as HaltReason.InstanceFailure
        assertEquals("java.lang.IllegalStateException", reason.throwableClass)
        assertEquals("deliberate", reason.message)
        assertEquals(1, reason.atEventIndex)
        assertEquals(Value.StringV("fine"), failed.currentState) { "state before the failing event is kept" }

        assertTrue(sibling.halted)
        assertEquals(HaltReason.EventsExhausted, sibling.haltReason)
        assertEquals(Value.StringV("h49"), sibling.currentState)
        assertNull(sibling.denialReport)

        assertEquals(setOf(failed.instanceId), handle.failedInstances().keys)
        assertEquals(reason, handle.failedInstances().getValue(failed.instanceId))
    }
}
