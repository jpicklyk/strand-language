package org.strand.corpus.soundness

/**
 * Shrinks a failing case by shrinking its [Choices] trace.
 *
 * The generator is a pure function of the trace and maps the draw `0` to the
 * simplest alternative, so a shorter or lexicographically smaller trace is a
 * smaller program, and every candidate trace still denotes a well-typed
 * program. Three passes repeat until none makes progress or the attempt
 * budget runs out: delete a span of draws, zero a span, lower single draws.
 *
 * [probe] runs the generator and the properties on a candidate and returns
 * the *normalized* trace (the draws the generator actually took) when the
 * candidate still fails with the failure being minimized, or null otherwise.
 * A candidate is kept only when its normalized trace is strictly smaller
 * than the best so far, so the loop terminates.
 */
object Shrinker {

    fun shrink(initial: IntArray, budget: Int, probe: (IntArray) -> IntArray?): IntArray {
        var best = initial
        var attempts = 0

        fun attempt(candidate: IntArray): Boolean {
            if (attempts >= budget) return false
            attempts++
            val normalized = probe(candidate) ?: return false
            if (!smaller(normalized, best)) return false
            best = normalized
            return true
        }

        var progress = true
        while (progress && attempts < budget) {
            progress = false

            // Delete spans, largest first, scanning from the end so earlier
            // (structure-defining) draws are disturbed last.
            var size = maxOf(1, best.size / 2)
            while (size >= 1 && attempts < budget) {
                var start = best.size - size
                while (start >= 0 && attempts < budget) {
                    if (start + size <= best.size) {
                        val candidate = best.copyOfRange(0, start) + best.copyOfRange(start + size, best.size)
                        if (attempt(candidate)) {
                            progress = true
                            start = minOf(start, best.size - size)
                            continue
                        }
                    }
                    start -= maxOf(1, size / 2)
                }
                size /= 2
            }

            // Zero spans: the draw 0 selects the simplest alternative.
            size = 8
            while (size >= 1 && attempts < budget) {
                var start = 0
                while (start < best.size && attempts < budget) {
                    val end = minOf(best.size, start + size)
                    if ((start until end).any { best[it] != 0 }) {
                        val candidate = best.copyOf()
                        for (i in start until end) candidate[i] = 0
                        if (attempt(candidate)) progress = true
                    }
                    start += size
                }
                size /= 2
            }

            // Lower single draws.
            var i = 0
            while (i < best.size && attempts < budget) {
                val v = best[i]
                if (v > 1) {
                    val halved = best.copyOf().also { it[i] = v / 2 }
                    if (attempt(halved)) { progress = true; continue }
                }
                if (v > 0) {
                    val lowered = best.copyOf().also { it[i] = v - 1 }
                    if (attempt(lowered)) { progress = true; continue }
                }
                i++
            }
        }
        return best
    }

    /** Shortlex order on traces. */
    private fun smaller(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return a.size < b.size
        for (i in a.indices) if (a[i] != b[i]) return a[i] < b[i]
        return false
    }
}
