package org.strand.core

/**
 * Host-configured resource limits honored at admission (JSON ingest) and at
 * evaluation (tree-walking interpreter, bytecode VM, state-machine runtime).
 * See [`proposals/implemented/interpreter-resource-limits.md`](../../../../../../../proposals/implemented/interpreter-resource-limits.md)
 * for the design rationale and the threat model that motivates each cap.
 *
 * Defaults are sized for the existing seed corpus plus three orders of
 * magnitude of headroom — every existing program runs unchanged under
 * [DEFAULTS]. Production deployments tighten via host configuration;
 * benchmarking harnesses use [PERMISSIVE] or hand-tuned values.
 *
 * Limits are fixed at evaluator entry. The host may not change limits during
 * a running evaluation — see proposal § 4.4 D4.
 *
 * **Field semantics:**
 *  - [maxSteps]: total dispatch steps per evaluation. One step per `eval()`
 *    call in the interpreter; one step per opcode in the VM. Reaching this
 *    cap raises [ExhaustionKind.Steps].
 *  - [maxStackDepth]: maximum recursion depth at any point during
 *    evaluation. Interpreter uses `eval`'s nesting depth; VM uses
 *    `frames.size`. Reaching this cap raises [ExhaustionKind.StackDepth]
 *    *before* the JVM raises `StackOverflowError`.
 *  - [maxAllocatedValues]: total `Value.*` constructions per evaluation. A
 *    coarse memory proxy; precise `maxBytesAllocated` accounting is
 *    deferred. Reaching this cap raises [ExhaustionKind.AllocatedValues]
 *    *before* the JVM raises `OutOfMemoryError`.
 *  - [wallClockBudgetMillis]: wall-clock budget for the entire evaluation.
 *    Checked by sampling `System.nanoTime()` every [wallClockSampleEvery]
 *    dispatch steps. Reaching this cap raises [ExhaustionKind.WallClock].
 *  - [wallClockSampleEvery]: how often (in steps) to sample the wall clock.
 *    Default 1024 keeps the per-step overhead of `System.nanoTime()`
 *    amortized below the dispatch noise floor.
 *  - [streamReceiveTimeoutMillis] (Q-045): per-read ceiling for a
 *    blocking streaming receive. Installed as the underlying socket's
 *    `SO_TIMEOUT` (and the LLM stream's HTTP read timeout) at open
 *    time, so a single native `read` that never returns — which
 *    advances no interpreter step and so escapes the
 *    [wallClockBudgetMillis] sampler — is still bounded by the OS.
 *    Default aligned with [wallClockBudgetMillis]. Like every other
 *    field here it is host policy, never a builtin argument: an
 *    adversarial graph cannot raise its own per-read ceiling. A value
 *    outside the positive `Int` range (notably [PERMISSIVE]'s
 *    `Long.MAX_VALUE`) maps to the JVM's "no timeout" (`0`) at the
 *    socket layer.
 *  - [maxJsonDepth]: JSON ingest brace-nesting limit. Checked by a linear
 *    pre-scan before `kotlinx.serialization.json.Json.parseToJsonElement`
 *    sees the input. Reaching this cap raises
 *    [ExhaustionKind.JsonDepth].
 *  - [maxNodeCount]: maximum number of nodes in a single ingested program.
 *    Reaching this cap raises [ExhaustionKind.NodeCount].
 *  - [maxIngestBytes]: maximum byte size of an ingested program. Reaching
 *    this cap raises [ExhaustionKind.IngestBytes].
 *  - [errorVerbosity] (Q-042): how aggressively agent-visible runtime
 *    error messages are scrubbed of credential values. See
 *    [ErrorVerbosity] for variant semantics. Default is
 *    [ErrorVerbosity.Redacted] — every `IoFailure.detail` runs through
 *    the centralised `CredentialScrubber` before exposure. Production
 *    deployments stay on this default; dev environments may opt up to
 *    [ErrorVerbosity.Full] with the understood leakage risk.
 *  - [perEventStepBudget] (Q-059): an opt-in per-event step ceiling for
 *    the async state-machine actor path. When null (the default), an
 *    actor accumulates [maxSteps] / [maxAllocatedValues] / the
 *    [wallClockBudgetMillis] across its WHOLE lifetime — the batch model,
 *    where one `runGroup`/`runMachine` is one logical evaluation. That
 *    model kills a long-lived service-shaped actor at the 30 s wall-clock
 *    for being alive, even when each event does trivial work. When
 *    non-null, the actor allocates a FRESH budget per event (its own
 *    step / allocation / wall-clock counters reset between events), so a
 *    server processing one small event per minute is never killed for
 *    uptime, while each event's processing stays independently bounded.
 *    Idle blocking `select` waits between events advance no step and so
 *    consume no budget under either model — the same property
 *    [streamReceiveTimeoutMillis] gives blocking receives. The Q-044 harm
 *    bound is preserved: a per-event budget still bounds the work any one
 *    event can do, and capabilities still gate every effect. The
 *    synchronous fold (`runMachine` / `resume`) ignores this field —
 *    it is batch by definition. Like every other field here it is host
 *    policy, never a builtin argument.
 *  - [perEventWallClockBudgetMillis] (Q-059): the per-event wall-clock
 *    ceiling that pairs with [perEventStepBudget]. Used only when
 *    [perEventStepBudget] is non-null; null falls back to
 *    [wallClockBudgetMillis] for the per-event budget (now interpreted
 *    per event rather than per lifetime).
 */
data class EvaluationLimits(
    val maxSteps: Long = 10_000_000L,
    val maxStackDepth: Int = 4096,
    val maxAllocatedValues: Long = 1_000_000L,
    val wallClockBudgetMillis: Long = 30_000L,
    val wallClockSampleEvery: Int = 1024,
    val streamReceiveTimeoutMillis: Long = 30_000L,
    val maxJsonDepth: Int = 512,
    val maxNodeCount: Int = 100_000,
    val maxIngestBytes: Long = 64L * 1024L * 1024L,
    val errorVerbosity: ErrorVerbosity = ErrorVerbosity.Redacted,
    val perEventStepBudget: Long? = null,
    val perEventWallClockBudgetMillis: Long? = null,
) {
    /**
     * Q-059: the per-event [EvaluationLimits] an async actor uses for one
     * event's processing when [perEventStepBudget] is set. Derives a fresh
     * budget — [maxSteps] becomes the per-event step ceiling and
     * [wallClockBudgetMillis] becomes the per-event wall-clock ceiling
     * (falling back to the lifetime wall-clock when no per-event wall-clock
     * is given). All other dimensions (stack depth, allocation, sampling,
     * verbosity) carry through unchanged. Returns `this` untouched when
     * [perEventStepBudget] is null, so callers can derive unconditionally.
     */
    fun perEvent(): EvaluationLimits {
        val budget = perEventStepBudget ?: return this
        return copy(
            maxSteps = budget,
            wallClockBudgetMillis = perEventWallClockBudgetMillis ?: wallClockBudgetMillis,
            // A fresh budget per event also means the allocation counter
            // resets per event; cap it at the per-event step budget so a
            // single event cannot allocate the whole lifetime ceiling.
            maxAllocatedValues = minOf(maxAllocatedValues, budget),
        )
    }
    companion object {
        /**
         * The shipping defaults: pass every existing corpus program under
         * unchanged, catch the hostile-graph shapes recorded in the audit,
         * impose no observable performance penalty.
         */
        val DEFAULTS = EvaluationLimits()

        /**
         * Effectively unbounded limits. Useful for benchmarking harnesses
         * (factorial(1000), unbounded fixpoints with provable termination)
         * and for tests that need to exhaust a single specific dimension
         * without tripping any of the others. Production callers should
         * use [DEFAULTS] or a custom tightened policy, not [PERMISSIVE].
         *
         * **Q-042 note:** [errorVerbosity] is deliberately preserved at
         * [ErrorVerbosity.Redacted] in PERMISSIVE — "permissive" means
         * resource limits, not credential leakage. A benchmark that
         * happens to interact with the credential surface should not
         * accidentally expose secrets.
         */
        val PERMISSIVE = EvaluationLimits(
            maxSteps = Long.MAX_VALUE,
            maxStackDepth = Int.MAX_VALUE,
            maxAllocatedValues = Long.MAX_VALUE,
            wallClockBudgetMillis = Long.MAX_VALUE,
            wallClockSampleEvery = Int.MAX_VALUE,
            streamReceiveTimeoutMillis = Long.MAX_VALUE,
            maxJsonDepth = Int.MAX_VALUE,
            maxNodeCount = Int.MAX_VALUE,
            maxIngestBytes = Long.MAX_VALUE,
            errorVerbosity = ErrorVerbosity.Redacted,
        )
    }
}

/**
 * Discriminator for the dimension that triggered a resource-exhaustion
 * error. Shared between ingest-time and evaluation-time error variants so
 * host code can branch on the failure mode without string parsing.
 *
 * Each variant corresponds to one field of [EvaluationLimits] and to one
 * counter or pre-scan check in the ingest / interpreter / VM / runtime
 * pipeline. The split is by *what was exhausted*, not by *who exhausted
 * it*: the same `Steps` value fires whether the offending program is a
 * fixpoint with no base case or a productive non-terminator under tight
 * limits.
 */
enum class ExhaustionKind {
    /** Evaluation-time step counter exceeded [EvaluationLimits.maxSteps]. */
    Steps,

    /** Evaluation-time recursion depth exceeded [EvaluationLimits.maxStackDepth]. */
    StackDepth,

    /** Evaluation-time allocated-value counter exceeded [EvaluationLimits.maxAllocatedValues]. */
    AllocatedValues,

    /** Evaluation-time wall-clock budget exceeded [EvaluationLimits.wallClockBudgetMillis]. */
    WallClock,

    /** Ingest-time JSON nesting depth exceeded [EvaluationLimits.maxJsonDepth]. */
    JsonDepth,

    /** Ingest-time node count exceeded [EvaluationLimits.maxNodeCount]. */
    NodeCount,

    /** Ingest-time byte count exceeded [EvaluationLimits.maxIngestBytes]. */
    IngestBytes,
}
