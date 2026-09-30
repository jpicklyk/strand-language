package org.strand.corpus.soundness

import kotlin.random.Random

/**
 * The generator's only source of nondeterminism: a sequence of bounded
 * integer draws, either taken from a seeded [Random] or replayed from a
 * recorded trace.
 *
 * Every generated case is a pure function of its trace, which is what makes
 * shrinking generic: the [Shrinker] edits the trace (deletes spans, zeroes
 * and lowers entries) and re-runs the generator, so every candidate is a
 * well-typed program by construction and no program-level shrink rules are
 * needed. The generator cooperates by mapping the draw `0` to the simplest
 * alternative at every decision point.
 *
 * A replayed trace that runs out yields `0` for every further draw, and an
 * entry outside the requested bound is reduced modulo the bound, so any
 * integer array is a valid trace. [trace] returns the draws as actually
 * taken (normalized), which is the form the shrinker compares.
 */
class Choices private constructor(
    private val rng: Random?,
    private val replay: IntArray?,
    private val maxDraws: Int,
) {
    private val recorded = ArrayList<Int>()

    /** The normalized draws taken so far. */
    val trace: IntArray get() = recorded.toIntArray()

    /** A draw in `[0, bound)`. Past [maxDraws] every draw is `0`. */
    fun int(bound: Int): Int {
        require(bound > 0) { "bound must be positive, got $bound" }
        val pos = recorded.size
        val v = when {
            pos >= maxDraws -> 0
            replay != null -> if (pos < replay.size) Math.floorMod(replay[pos], bound) else 0
            else -> rng!!.nextInt(bound)
        }
        recorded += v
        return v
    }

    fun bool(): Boolean = int(2) == 1

    /** True about once in [n] draws; the simple draw `0` is false. */
    fun oneIn(n: Int): Boolean = int(n) == n - 1

    /** True for [num] draws out of [den]; the simple draw `0` is false. */
    fun chance(num: Int, den: Int): Boolean = int(den) >= den - num

    fun <T> pick(xs: List<T>): T = xs[int(xs.size)]

    /**
     * Index into [weights] drawn proportionally. Index 0 is the simple
     * alternative: the draw `0` always selects it.
     */
    fun weighted(weights: IntArray): Int {
        val total = weights.sum()
        require(total > 0) { "weights sum to zero" }
        var r = int(total)
        for (i in weights.indices) {
            if (r < weights[i]) return i
            r -= weights[i]
        }
        return weights.size - 1
    }

    /** A subset of [xs], each element kept with probability one half. */
    fun <T> subset(xs: Collection<T>): Set<T> {
        val out = LinkedHashSet<T>()
        for (x in xs) if (bool()) out += x
        return out
    }

    companion object {
        /** Bound on draws per case; keeps generated programs small. */
        const val DEFAULT_MAX_DRAWS = 6000

        fun seeded(seed: Long, maxDraws: Int = DEFAULT_MAX_DRAWS): Choices =
            Choices(Random(seed), null, maxDraws)

        fun replay(trace: IntArray, maxDraws: Int = DEFAULT_MAX_DRAWS): Choices =
            Choices(null, trace, maxDraws)
    }
}
