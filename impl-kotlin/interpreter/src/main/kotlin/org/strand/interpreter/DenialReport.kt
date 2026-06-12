package org.strand.interpreter

import org.strand.core.ErrorVerbosity
import org.strand.core.NodeId

/**
 * Q-064: where in a run's lifecycle a capability or refinement denial
 * fired. Carried on [DenialReport.phase]; the wire string is what the CLI's
 * `strand:denial` JSON line emits.
 *
 *  - [Expression] — ordinary expression evaluation (`strand run`, a
 *    transition-function *construction* at machine start, any plain
 *    `Interpreter.eval`). The interpreter's default.
 *  - [Transition] — inside a state machine's per-event transition call.
 *    Attached by the runtime when it translates the denial into a
 *    [HaltReason]-style halt (sync fold) or a clean actor halt (async
 *    group).
 *  - [Invariant] — while evaluating a schema invariant body (the
 *    interpreter's `inInvariant` re-entrancy guard is set). Unreachable on
 *    verified graphs (invariant bodies are verifier-enforced pure) but
 *    represented honestly for the trusting-interpreter path.
 *  - [GroupStart] — during `runGroup` startup, before any actor processes
 *    an event: initial instance spawning (transitionFn / initialState
 *    evaluation) or a Q-046 source-opener evaluation.
 */
enum class DenialPhase(val wire: String) {
    Expression("expression"),
    Transition("transition"),
    Invariant("invariant"),
    GroupStart("group-start"),
}

/**
 * Q-064 (`proposals/capability-denial-observability.md`): the structured
 * denial outcome an orchestrating principal receives when a capability or
 * refinement denial terminates evaluation. Constructed at the existing
 * denial site (the interpreter's capability check; the VM's CALL-site
 * category check) from values already in scope, and carried on
 * [InterpretError.CapabilityViolation] / [InterpretError.RefinementViolation]
 * plus the runtime's denial-caused halt reasons.
 *
 * Nothing about the denial decision changes: the error remains uncatchable
 * (`isCatchable = false`), no new [Value] exists, and the report is not
 * observable from inside the graph. The report structures the
 * already-decided outcome for the principal that granted the context.
 *
 * Field semantics:
 *
 *  - [category] — the denied EffectCategory's `categoryName` (for example
 *    `Filesystem.Write`). When several categories are missing at one call
 *    site (a multi-effect callee under a context granting none of them),
 *    the names are joined with `", "` in the callee's declaration order.
 *    On the bytecode-VM path the name is unavailable (the VM holds only a
 *    ChunkTable, no store), so the category renders as the NodeId (`#N`).
 *  - [requested] — the call site's refinement parameter values, rendered
 *    and passed through [CredentialScrubber.scrub] at construction.
 *    Positional (EffectCategory parameters carry no names in the node
 *    algebra). An empty list means the call site supplied no refinement
 *    parameters; `null` means the values are withheld
 *    ([ErrorVerbosity.RedactedWithKindOnly]) or structurally unknown (the
 *    VM checks category presence only and never sees refinement
 *    parameters — that coarseness is represented honestly, not filled in).
 *  - [held] — a rendered summary of the grants the context held *for the
 *    denied category* (the mismatch view: requested versus held). Empty
 *    for a [InterpretError.CapabilityViolation] (the category was absent —
 *    nothing was held); the granted pattern list for a
 *    [InterpretError.RefinementViolation]. `null` under
 *    [ErrorVerbosity.RedactedWithKindOnly].
 *  - [node] — the denying Application's NodeId. Null on the VM path
 *    (opcodes carry no NodeIds in slice 1, matching the
 *    [InterpretError.ResourceExhaustion] precedent) and for the
 *    `NodeId(-1)` runtime-boundary sentinel of `Interpreter.applyCallable`.
 *  - [instanceId] / [eventIndex] — the machine instance and zero-based
 *    event index when the denial occurred inside a state-machine runtime;
 *    attached by the runtime at halt translation, null otherwise.
 *  - [phase] — see [DenialPhase].
 */
data class DenialReport(
    val category: String,
    val requested: List<String>?,
    val held: List<String>?,
    val node: NodeId?,
    val instanceId: String?,
    val eventIndex: Int?,
    val phase: DenialPhase,
) {
    companion object {

        /**
         * Render one refinement parameter value for the report. Primitive
         * payloads render bare (a String parameter renders as its content,
         * not `StringV(v=...)`); structured values fall back to their
         * data-class rendering. Every result passes through
         * [CredentialScrubber.scrub] so a credential-bearing parameter
         * value cannot leak through the denial surface.
         */
        fun renderParameter(v: Value): String = CredentialScrubber.scrub(
            when (v) {
                is Value.StringV -> v.v
                is Value.IntV -> v.v.toString()
                is Value.FloatV -> v.v.toString()
                is Value.BoolV -> v.v.toString()
                Value.UnitV -> "()"
                is Value.BytesV -> "bytes[${v.v.size}]"
                else -> v.toString()
            }
        )

        /**
         * Render one granted [CapabilityPattern] for the [DenialReport.held]
         * summary: `CategoryName{slot, slot, ...}` with `*` for wildcard
         * slots and scrubbed values for concrete ones. A parameterless
         * pattern renders as the bare category name.
         */
        fun renderGrant(categoryName: String, pattern: CapabilityPattern): String {
            if (pattern.arguments.isEmpty()) return categoryName
            val slots = pattern.arguments.joinToString(", ") { arg ->
                when (arg) {
                    CapabilityArgument.Wildcard -> "*"
                    is CapabilityArgument.Concrete -> renderParameter(arg.value)
                }
            }
            return "$categoryName{$slots}"
        }
    }
}
