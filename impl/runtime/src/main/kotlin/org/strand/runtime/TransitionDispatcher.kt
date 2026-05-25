package org.strand.runtime

import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value

/**
 * Per-instance transition-function dispatcher (Layer 6 VM-integration
 * hook). Encapsulates the "given (state, event), return result" step
 * the actor loop and the sync runtime both call once per event.
 *
 * Default impl is [InterpreterTransitionDispatcher] — uses the
 * tree-walking `Interpreter.applyCallable`. Tests inject a VM-based
 * implementation (`:corpus`'s `VmTransitionDispatcherFactory`) to drive
 * the same actor loop through the bytecode VM and assert trace equality.
 *
 * The dispatcher is per-instance so it can hold any pre-computed state
 * (an Interpreter-evaluated `Value.Closure`, a VM-lowered `ChunkTable`
 * plus an evaluated VmClosure, etc.) without re-doing setup per event.
 */
interface TransitionDispatcher {
    /**
     * Apply the transition function to one (state, event) pair and
     * return the `{state, outputs}` ProductV. The capability context is
     * baked into the dispatcher at construction (via the factory) — the
     * actor doesn't re-thread it per call.
     */
    fun applyTransition(state: Value, event: Value): Value
}

/**
 * Factory for per-instance transition dispatchers. Called once per
 * [MachineInstance] at construction (by [StateMachineRuntime.runMachine],
 * [StateMachineRuntime.runGroup], and `RuntimeContext.spawn`); the
 * returned dispatcher is stored on the instance and used for every
 * subsequent event.
 *
 * The factory has access to the StateMachine node (so it can lower or
 * evaluate `transitionFn` once), the surrounding capability context, and
 * the source machine NodeId (used by Lowerer-based factories that need
 * to identify the chunk).
 */
interface TransitionDispatcherFactory {
    fun build(
        machineNode: Node.StateMachine,
        machineId: NodeId,
        capabilities: CapabilitySet,
    ): TransitionDispatcher
}

/**
 * Default dispatcher: wraps `Interpreter.applyCallable`. The transition
 * function is evaluated once at factory `build` time (yielding a
 * `Value.Closure`/`FixpointFn`/`ForeignFn`); each `applyTransition` call
 * invokes the same callable under the supplied capabilities.
 */
class InterpreterTransitionDispatcher(
    private val interpreter: Interpreter,
    private val closure: Value,
    private val capabilities: CapabilitySet,
) : TransitionDispatcher {
    override fun applyTransition(state: Value, event: Value): Value =
        interpreter.applyCallable(
            fn = closure,
            args = listOf(state, event),
            capabilities = capabilities,
        )
}

/**
 * Default factory backing the existing runtime behavior. Pre-evaluates
 * the transition function via Interpreter at `build` time and stores the
 * resulting Value callable.
 */
class InterpreterDispatcherFactory(
    private val store: NodeStore,
    private val hashToNodeId: Map<Hash, NodeId>,
) : TransitionDispatcherFactory {
    private val interpreter = Interpreter(store, hashToNodeId)

    override fun build(
        machineNode: Node.StateMachine,
        machineId: NodeId,
        capabilities: CapabilitySet,
    ): TransitionDispatcher {
        val closure = interpreter.eval(machineNode.transitionFn, capabilities)
        return InterpreterTransitionDispatcher(interpreter, closure, capabilities)
    }

    /**
     * Pre-evaluate the StateMachine's `initialState` under the same
     * capability context. Sibling to `build` — both factory operations
     * need to run pre-Vm-integration; with the VM, initialState would
     * also lower through the same compiler.
     */
    fun evalInitialState(machineNode: Node.StateMachine, capabilities: CapabilitySet): Value =
        interpreter.eval(machineNode.initialState, capabilities)
}
