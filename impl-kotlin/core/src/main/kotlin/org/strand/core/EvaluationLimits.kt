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
 *  - [maxJsonDepth]: JSON ingest brace-nesting limit. Checked by a linear
 *    pre-scan before `kotlinx.serialization.json.Json.parseToJsonElement`
 *    sees the input. Reaching this cap raises
 *    [ExhaustionKind.JsonDepth].
 *  - [maxNodeCount]: maximum number of nodes in a single ingested program.
 *    Reaching this cap raises [ExhaustionKind.NodeCount].
 *  - [maxIngestBytes]: maximum byte size of an ingested program. Reaching
 *    this cap raises [ExhaustionKind.IngestBytes].
 */
data class EvaluationLimits(
    val maxSteps: Long = 10_000_000L,
    val maxStackDepth: Int = 4096,
    val maxAllocatedValues: Long = 1_000_000L,
    val wallClockBudgetMillis: Long = 30_000L,
    val wallClockSampleEvery: Int = 1024,
    val maxJsonDepth: Int = 512,
    val maxNodeCount: Int = 100_000,
    val maxIngestBytes: Long = 64L * 1024L * 1024L,
) {
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
         */
        val PERMISSIVE = EvaluationLimits(
            maxSteps = Long.MAX_VALUE,
            maxStackDepth = Int.MAX_VALUE,
            maxAllocatedValues = Long.MAX_VALUE,
            wallClockBudgetMillis = Long.MAX_VALUE,
            wallClockSampleEvery = Int.MAX_VALUE,
            maxJsonDepth = Int.MAX_VALUE,
            maxNodeCount = Int.MAX_VALUE,
            maxIngestBytes = Long.MAX_VALUE,
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
