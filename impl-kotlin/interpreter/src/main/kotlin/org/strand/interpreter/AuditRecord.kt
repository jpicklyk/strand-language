package org.strand.interpreter

import org.strand.core.NodeId

/**
 * Q-055 (`proposals/effect-audit-log.md`): a structured audit record emitted
 * at the single foreign-dispatch capability boundary — the runtime half of the
 * reason-first story. Where the reasoning surface (ADR-010) reports what a
 * program *may* do before execution, the audit log records what it *did*,
 * letting an operator reconcile the declared closure against the per-run
 * performed effects.
 *
 * Emitted through a per-context [AuditSink] (see [HostContext.auditSink]),
 * defaulting to the no-op sink so the log is opt-in and per-tenant. The
 * *allowed* path is the new information Q-055 adds; the *denied* path reuses
 * the Q-064 [DenialReport]'s fields so the two reconcile — every field except
 * [outcome] mirrors a [DenialReport] field with the same meaning.
 *
 * Field semantics:
 *
 *  - [callSiteNodeId] — the denying / dispatching Application's NodeId (the
 *    same site the [DenialReport.node] carries). Null for the `NodeId(-1)`
 *    runtime-boundary sentinel of [Interpreter.applyCallable].
 *  - [effectCategory] — the exercised (allowed) or denied EffectCategory's
 *    `categoryName`. On a denial with several missing categories the names are
 *    joined in the callee's declaration order, matching [DenialReport.category].
 *  - [refinementParameters] — the call site's refinement parameter values,
 *    rendered and passed through the per-context credential [Scrubber] exactly
 *    as [DenialReport] scrubs its `requested` list. An empty list means the
 *    call site supplied no refinement parameters.
 *  - [outcome] — [AuditOutcome.Allowed] when the capability check passed for a
 *    category the call site concretely exercised (an EffectDecl instance was
 *    present), or [AuditOutcome.Denied] carrying the reconciling
 *    [DenialReport].
 *  - [instanceId] / [eventIndex] / [phase] — the state-machine instance, the
 *    zero-based event index, and the lifecycle phase, mirroring the Q-064
 *    [DenialReport] fields; null / [DenialPhase.Expression] outside a
 *    state-machine transition. For a denied record these are read off the
 *    reused [DenialReport].
 */
data class AuditRecord(
    val callSiteNodeId: NodeId?,
    val effectCategory: String,
    val refinementParameters: List<String>,
    val outcome: AuditOutcome,
    val instanceId: String? = null,
    val eventIndex: Int? = null,
    val phase: DenialPhase = DenialPhase.Expression,
)

/** Q-055: the outcome discriminator on an [AuditRecord]. */
sealed class AuditOutcome {
    /** The capability check passed; the effect was exercised. */
    object Allowed : AuditOutcome() {
        override fun toString(): String = "Allowed"
    }

    /**
     * The capability check failed. Carries the Q-064 [DenialReport] built at
     * the same site, so the audit log and the denial surface reconcile.
     */
    data class Denied(val report: DenialReport) : AuditOutcome()
}

/**
 * Q-055: the per-context audit sink. A host installs one on its [HostPolicy]
 * to collect [AuditRecord]s; the default [NoOpAuditSink] discards them so the
 * audit log is opt-in and every existing run behaves identically.
 *
 * Implementations must be safe to call from the actor runtime's coroutine
 * threads: the async group threads the per-tenant context (and thus this sink)
 * into each per-actor [Interpreter], so a group run may call [record]
 * concurrently from several actors. The reference [CollectingAuditSink] and the
 * CLI's file sink synchronize their append.
 */
fun interface AuditSink {
    fun record(record: AuditRecord)
}

/** Q-055: the opt-in default — discards every record. */
object NoOpAuditSink : AuditSink {
    override fun record(record: AuditRecord) = Unit
}

/**
 * Q-055: a thread-safe collecting sink for tests and in-process hosts.
 * Appends every record under a lock so concurrent actor emissions are safe.
 */
class CollectingAuditSink : AuditSink {
    private val lock = Any()
    private val backing = mutableListOf<AuditRecord>()

    override fun record(record: AuditRecord) {
        synchronized(lock) { backing.add(record) }
    }

    /** A stable snapshot of the records collected so far. */
    val records: List<AuditRecord>
        get() = synchronized(lock) { backing.toList() }
}
