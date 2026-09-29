package org.strand.interpreter

import org.strand.core.EvaluationLimits
import org.strand.core.ExhaustionKind

/**
 * Review H7 / M2: host-policy bounds applied *inside* builtins, where the
 * interpreter's per-step counters cannot see. A builtin that allocates a
 * large JVM object in one call (`List.Range(0, 1e10)`), blocks in one
 * native read (`Http.Request` against a stalled server), or sleeps in one
 * call (`Time.Sleep`) advances no interpreter step, so neither
 * [EvaluationLimits.maxAllocatedValues] nor the sampled
 * [EvaluationLimits.wallClockBudgetMillis] would fire. These bounds close
 * that gap by checking the requested size or duration before the work is
 * done.
 *
 * Like every other limit, these are host policy, never builtin arguments:
 * an adversarial graph cannot raise them. They ride on [HostContext]
 * ([HostContext.builtinLimits]); [HostContext.fromPolicy] derives them
 * from the policy's [EvaluationLimits] via [from], and
 * [HostContext.processDefault] uses [DEFAULT] (derived from
 * [EvaluationLimits.DEFAULTS]).
 *
 * @property connectTimeoutMillis TCP connect timeout for every
 *   `HttpURLConnection` a builtin opens. Derived from the wall-clock budget.
 * @property readTimeoutMillis per-read timeout for the same connections.
 *   Derived from the wall-clock budget.
 * @property acceptTimeoutMillis how long `Http.Accept` waits for an
 *   inbound request before failing with a catchable `IoFailure`.
 * @property maxSleepMillis the longest single `Time.Sleep`. Derived from
 *   the wall-clock budget: a sleep longer than the whole budget can never
 *   complete inside it, so it is refused up front.
 * @property maxResponseBytes the largest HTTP body (response bodies of
 *   `Http.Request` and the vector / LLM transports, request bodies read by
 *   `Http.Accept`) a builtin will buffer. Default 16 MiB.
 * @property maxCollectionElements the largest collection a single builtin
 *   call may materialise (`List.Range`). Derived from
 *   [EvaluationLimits.maxAllocatedValues].
 * @property maxBuiltinBytes the largest string / byte array a single
 *   builtin call may materialise (`String.Repeat`, `Random.Bytes`, the
 *   `maxBytes` of `Net.Receive` / `Net.Stream.Receive` /
 *   `LLM.Stream.Receive`, `Compress.Gunzip` output). Default 16 MiB.
 * @property maxRegexInputChars the longest input (and pattern) the
 *   `Regex.*` builtins accept. Bounding the input bounds the cost of a
 *   linear-time match; it does NOT bound catastrophic backtracking, which
 *   `java.util.regex` offers no step limit for (see the `Regex.*`
 *   registrations).
 */
data class BuiltinLimits(
    val connectTimeoutMillis: Long = 30_000L,
    val readTimeoutMillis: Long = 30_000L,
    val acceptTimeoutMillis: Long = 30_000L,
    val maxSleepMillis: Long = 30_000L,
    val maxResponseBytes: Long = DEFAULT_MAX_BYTES,
    val maxCollectionElements: Long = 1_000_000L,
    val maxBuiltinBytes: Long = DEFAULT_MAX_BYTES,
    val maxRegexInputChars: Int = 1_000_000,
) {
    /** [connectTimeoutMillis] as a `URLConnection` timeout (`0` = none, for values outside the positive `Int` range). */
    val connectTimeoutInt: Int get() = toSocketTimeout(connectTimeoutMillis)

    /** [readTimeoutMillis] as a `URLConnection` / `SO_TIMEOUT` value. */
    val readTimeoutInt: Int get() = toSocketTimeout(readTimeoutMillis)

    /** Apply [connectTimeoutMillis] / [readTimeoutMillis] to [conn]. */
    fun applyTimeouts(conn: java.net.URLConnection) {
        conn.connectTimeout = connectTimeoutInt
        conn.readTimeout = readTimeoutInt
    }

    /**
     * Refuse to materialise [requested] elements when that exceeds
     * [maxCollectionElements]. Throws an uncatchable
     * [InterpretError.ResourceExhaustion] of kind
     * [ExhaustionKind.AllocatedValues].
     */
    fun checkElements(requested: Long) {
        if (requested > maxCollectionElements) exhausted(ExhaustionKind.AllocatedValues, requested, maxCollectionElements)
    }

    /** Refuse to materialise a string / byte array of [requested] units when that exceeds [maxBuiltinBytes]. */
    fun checkBytes(requested: Long) {
        if (requested > maxBuiltinBytes) exhausted(ExhaustionKind.AllocatedValues, requested, maxBuiltinBytes)
    }

    /** Refuse a sleep longer than [maxSleepMillis] (it could never finish inside the wall-clock budget). */
    fun checkSleep(millis: Long) {
        if (millis > maxSleepMillis) exhausted(ExhaustionKind.WallClock, millis, maxSleepMillis)
    }

    /** Refuse a `Regex.*` input or pattern longer than [maxRegexInputChars]. */
    fun checkRegexInput(length: Int) {
        if (length > maxRegexInputChars) {
            exhausted(ExhaustionKind.AllocatedValues, length.toLong(), maxRegexInputChars.toLong())
        }
    }

    /**
     * Read [stream] to its end, refusing to buffer more than
     * [maxResponseBytes]. Overflow is an uncatchable
     * [InterpretError.ResourceExhaustion] raised as soon as the cap is
     * crossed, before the rest of the body is read.
     */
    fun readBounded(stream: java.io.InputStream): ByteArray = readBounded(stream, maxResponseBytes)

    companion object {
        /** 16 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 16L * 1024L * 1024L

        /** Derived from [EvaluationLimits.DEFAULTS]. */
        val DEFAULT: BuiltinLimits = from(EvaluationLimits.DEFAULTS)

        /**
         * Derive builtin bounds from the host's [EvaluationLimits]:
         * timeouts and the sleep cap follow [EvaluationLimits.wallClockBudgetMillis],
         * the collection cap follows [EvaluationLimits.maxAllocatedValues].
         * Byte caps are independent of the evaluation limits and keep their
         * defaults ([EvaluationLimits.PERMISSIVE] relaxes resource limits,
         * not the per-call memory ceiling).
         */
        fun from(limits: EvaluationLimits): BuiltinLimits = BuiltinLimits(
            connectTimeoutMillis = limits.wallClockBudgetMillis,
            readTimeoutMillis = limits.wallClockBudgetMillis,
            acceptTimeoutMillis = limits.wallClockBudgetMillis,
            maxSleepMillis = limits.wallClockBudgetMillis,
            maxCollectionElements = limits.maxAllocatedValues,
        )

        /** Map a millisecond value to a JVM socket timeout: outside `1..Int.MAX_VALUE` means `0` (no timeout). */
        fun toSocketTimeout(millis: Long): Int = if (millis in 1..Int.MAX_VALUE.toLong()) millis.toInt() else 0

        /** Bounded read with an explicit cap. See [BuiltinLimits.readBounded]. */
        fun readBounded(stream: java.io.InputStream, max: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > max) exhausted(ExhaustionKind.AllocatedValues, total, max)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        internal fun exhausted(kind: ExhaustionKind, current: Long, limit: Long): Nothing =
            throw InterpretException(InterpretError.ResourceExhaustion(at = null, kind = kind, current = current, limit = limit))
    }
}
