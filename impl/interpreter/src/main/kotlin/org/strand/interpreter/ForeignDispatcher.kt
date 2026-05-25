package org.strand.interpreter

/**
 * Pluggable interceptor for ForeignNode dispatch. Threaded into [Interpreter]
 * via its constructor; consulted at every foreign-call site before
 * [Builtins.lookup] runs.
 *
 * Returning a non-null [Value] uses that result as the call's return value,
 * short-circuiting the default builtins registry. Returning null falls
 * through to [Builtins.lookup] (which then either dispatches to the
 * registered builtin or throws [InterpretError.UnknownForeignTarget]).
 *
 * **Slice 3.2 use case (Layer 6 step 3 — supervision).** The runtime
 * supplies a dispatcher that recognizes `strand-runtime:StateMachine.Spawn`
 * and `strand-runtime:StateMachine.Terminate` calls inside a transition
 * function and applies the corresponding side effect on the surrounding
 * [org.strand.runtime.MachineGroup] (allocating a new instance, cancelling
 * an existing one). The dispatcher returns the new InstanceId (as a
 * [Value.StringV]) for Spawn and [Value.UnitV] for Terminate.
 *
 * Dispatchers must be pure functions of `(target, args)` from the
 * interpreter's perspective: side effects on host state are the dispatcher's
 * own concern, but it must return deterministically given the same args.
 * For Spawn, this means returning a STABLE-FOR-THIS-CALL InstanceId; the
 * runtime allocates a fresh one and that allocation is the dispatcher's
 * side effect — the interpreter just sees the returned value.
 */
fun interface ForeignDispatcher {
    /**
     * Try to dispatch the [target] foreign call with the supplied [args].
     * Return the produced [Value] to handle the call, or null to fall
     * through to the default [Builtins] registry.
     */
    fun dispatch(target: String, args: List<Value>): Value?
}
