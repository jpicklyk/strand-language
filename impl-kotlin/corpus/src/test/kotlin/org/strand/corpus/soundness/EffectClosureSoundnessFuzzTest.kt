package org.strand.corpus.soundness

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path

/**
 * Effect-closure soundness, as a continuously executed property test.
 *
 * Generates seeded, well-typed Strand programs ([ProgramGen]), runs each one
 * the verifier admits under a range of grants on both backends, and checks
 * the properties [SoundnessHarness] documents (S1 closure soundness, S2
 * capability confinement, S3 guard agreement, S4 backend parity, S5 no raw
 * failures, with the supporting checks A1, T1 and S6).
 *
 * **Determinism.** Iteration `i` draws from `Random(seed * 1_000_003 + i)`,
 * so a case depends only on the seed and its own index. CI runs the fixed
 * [SEED] for [DEFAULT_ITERATIONS] iterations. Longer local campaigns:
 *
 * ```
 * ./gradlew :corpus:test --tests "*EffectClosureSoundnessFuzzTest" \
 *     -Dstrand.soundness.iterations=150000 [-Dstrand.soundness.seed=N]
 * ```
 *
 * `-Dstrand.soundness.only=I` replays the single iteration `I`, and
 * `-Dstrand.soundness.maxReported=N` raises the number of distinct failures
 * shrunk and printed. The summary and the full report are also written to
 * `corpus/build/soundness-fuzz-{summary,report}.txt`.
 *
 * **Failure report.** Every distinct failure class (property, backend, kind)
 * found in the campaign is reported once: the seed and iteration, the
 * violated property, the grant, and the program as dag-json after shrinking
 * ([Shrinker]). A distinct failure is then preserved as a permanent
 * regression: a `corpus/negative/` entry when the fix is a rejection
 * reachable from dag-json, otherwise a case in [SoundnessRegressionTest].
 *
 * **Coverage.** The campaign also asserts that the generator is healthy: at
 * least [MIN_ADMISSION_PERCENT] percent of cases are admitted, and every
 * shape in [REQUIRED_FEATURES] occurs in an admitted program.
 */
class EffectClosureSoundnessFuzzTest {

    companion object {
        /** Fixed CI seed. Change only with a reason recorded in the commit. */
        const val SEED = 20260930L
        const val DEFAULT_ITERATIONS = 10_000
        const val MIN_ADMISSION_PERCENT = 40
        private const val SHRINK_BUDGET = 4000
        private const val DEFAULT_MAX_REPORTED = 12
        private const val STACK_BYTES = 64L * 1024L * 1024L

        /**
         * Shapes the 2026-09-29 review found defects in, plus the two registry
         * builtins whose effects the host observes directly (the clock and
         * the workspace) and a higher-order builtin with an effect of its
         * own; each must occur in an admitted program.
         */
        val REQUIRED_FEATURES = listOf(
            "shared-node", "handler", "handler-nested", "foreign-type-row", "polymorphic",
            "scope", "scope-in-handler", "list-map", "list-fold", "tooldef",
            "noderef-term", "noderef-type", "projection", "effect-instance",
            "schema-position", "machine", "machine-effectful-initial-state",
            "time-now", "fs-write", "higher-order-own-effect",
        )

        private val seed: Long = System.getProperty("strand.soundness.seed")?.toLongOrNull() ?: SEED
        private val iterations: Int =
            System.getProperty("strand.soundness.iterations")?.toIntOrNull() ?: DEFAULT_ITERATIONS
        private val only: Int? = System.getProperty("strand.soundness.only")?.toIntOrNull()
        private val vmAudit: Boolean = System.getProperty("strand.soundness.vmAudit")?.toBoolean() ?: true
        private val maxReported: Int =
            System.getProperty("strand.soundness.maxReported")?.toIntOrNull() ?: DEFAULT_MAX_REPORTED

        @JvmStatic
        @BeforeAll
        fun install() = FuzzHost.install()

        @JvmStatic
        @AfterAll
        fun uninstall() = FuzzHost.uninstall()

        fun caseSeed(seed: Long, iteration: Int): Long = seed * 1_000_003L + iteration
    }

    private class Found(val iteration: Int, val probe: Shrinker.Probe, val violation: Violation, var count: Int = 1)

    @Test
    fun `admitted programs satisfy the soundness properties under every grant`() {
        onBigStack { campaign() }
    }

    private fun campaign() {
        val harness = SoundnessHarness(vmAudit)
        val found = LinkedHashMap<String, Found>()
        val rejected = sortedMapOf<String, Int>()
        val featureCounts = sortedMapOf<String, Int>()
        var admitted = 0
        var vmSupported = 0
        var performed = 0L
        var denials = 0L
        val range = only?.let { it..it } ?: (0 until iterations)
        for (i in range) {
            val choices = Choices.seeded(caseSeed(seed, i))
            val case = ProgramGen(choices).generate()
            when (val outcome = harness.check(case)) {
                is CaseOutcome.Rejected -> rejected.merge("${outcome.stage}:${outcome.family}", 1, Int::plus)
                is CaseOutcome.Checked -> {
                    admitted++
                    if (outcome.vmSupported) vmSupported++
                    performed += outcome.performed
                    denials += outcome.denials
                    for (f in case.features) featureCounts.merge(f, 1, Int::plus)
                    for (v in outcome.violations) {
                        val prior = found[v.key]
                        if (prior == null) {
                            found[v.key] = Found(i, Shrinker.Probe(choices.trace, choices.spans.toList()), v)
                        } else {
                            prior.count++
                        }
                    }
                }
            }
        }

        val total = range.count()
        val summary = buildString {
            appendLine("effect-closure soundness fuzz: seed=$seed iterations=$total")
            appendLine("  admitted=$admitted vm-supported=$vmSupported effects-performed=$performed denied-runs=$denials")
            appendLine("  rejected=$rejected")
            appendLine("  features=$featureCounts")
            appendLine("  distinct failures=${found.size}")
            for ((key, f) in found) appendLine("    $key  (first at iteration ${f.iteration}, ${f.count} occurrence(s))")
        }
        println(summary)
        runCatching {
            val dir = Path.of("build")
            if (Files.isDirectory(dir)) Files.writeString(dir.resolve("soundness-fuzz-summary.txt"), summary)
        }

        if (found.isNotEmpty()) {
            val reports = found.values.take(maxReported).joinToString("\n") { report(harness, it) }
            val message =
                "${found.size} distinct soundness failure(s) in $total iterations (seed $seed).\n$summary\n$reports"
            runCatching {
                val dir = Path.of("build")
                if (Files.isDirectory(dir)) Files.writeString(dir.resolve("soundness-fuzz-report.txt"), message)
            }
            throw AssertionFailedError(message)
        }
        if (only != null) return

        // Generator health: the properties are vacuous on a campaign that
        // admits little or never reaches the shapes that matter.
        if (admitted * 100 < total * MIN_ADMISSION_PERCENT) {
            throw AssertionFailedError("only $admitted of $total generated programs were admitted.\n$summary")
        }
        if (total >= DEFAULT_ITERATIONS) {
            val missing = REQUIRED_FEATURES.filter { (featureCounts[it] ?: 0) == 0 }
            if (missing.isNotEmpty() || vmSupported == 0 || performed == 0L || denials == 0L) {
                throw AssertionFailedError("campaign did not reach required shapes: missing=$missing.\n$summary")
            }
        }
    }

    /** Shrink one failure and render it: seed, property, grant, and the minimal program as dag-json. */
    private fun report(harness: SoundnessHarness, f: Found): String {
        val key = f.violation.key
        var shrunkCase: GenCase? = null
        var shrunkViolation: Violation = f.violation
        val minimal = Shrinker.shrink(f.probe, SHRINK_BUDGET) { candidate ->
            val choices = Choices.replay(candidate)
            val case = ProgramGen(choices).generate()
            val hit = (harness.check(case) as? CaseOutcome.Checked)?.violations?.firstOrNull { it.key == key }
            if (hit == null) null else {
                shrunkCase = case
                shrunkViolation = hit
                Shrinker.Probe(choices.trace, choices.spans.toList())
            }
        }
        val case = shrunkCase ?: ProgramGen(Choices.replay(f.probe.trace)).generate()
        return buildString {
            appendLine("---- $key")
            appendLine("  seed      = $seed, iteration = ${f.iteration}")
            appendLine("  replay    = -Dstrand.soundness.seed=$seed -Dstrand.soundness.only=${f.iteration}")
            appendLine("  property  = ${shrunkViolation.property} on ${shrunkViolation.backend}: ${shrunkViolation.kind}")
            appendLine("  grant     = ${shrunkViolation.grant}")
            appendLine("  detail    = ${shrunkViolation.detail}")
            appendLine("  shrunk    = ${f.probe.trace.size} draws -> ${minimal.size} draws, ${case.nodeCount} nodes, root type ${case.rootTy}")
            appendLine("  program (dag-json):")
            append(case.json)
        }
    }

    /**
     * Run [block] on a thread with a large stack. Verification and the
     * tree-walking interpreter recurse once per graph level; the interpreter's
     * own `maxStackDepth` cap must fire before the JVM's native limit does.
     */
    private fun onBigStack(block: () -> Unit) {
        var thrown: Throwable? = null
        val worker = Thread(null, {
            try {
                block()
            } catch (t: Throwable) {
                thrown = t
            }
        }, "strand-soundness-fuzz", STACK_BYTES)
        worker.start()
        worker.join()
        thrown?.let { throw it }
    }
}
