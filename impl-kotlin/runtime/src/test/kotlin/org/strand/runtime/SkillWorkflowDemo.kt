package org.strand.runtime

import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.childNodeIds
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.DenialReport
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.NetPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * A skill workflow, demonstrated: model-as-policy / graph-as-actuator.
 *
 * Every other demonstration contains untrusted **code** — `containment-host` runs
 * an unfamiliar program under a bound, `plugin-host` attenuates a delegated
 * capability, `clean-room` proves a submitted computation cannot exfiltrate. This
 * one flips the threat model: it contains untrusted **data** — the decisions an
 * LLM emits — flowing into a trusted, verified, capability-bounded actuator graph.
 *
 * A procedural, effect-bearing skill (triage-and-notify, apply-review-decisions,
 * post-a-digest) splits into two parts that a conventional agent harness entangles:
 *
 *  - **Policy — the model.** The fuzzy judgment: which items matter, how to
 *    classify them, what to say. Non-deterministic, unbounded, *outside* the graph.
 *  - **Actuator — the graph.** The deterministic, effect-bearing spine that
 *    executes the decided actions: fold the decisions into a digest, write it to
 *    the one declared path. Verified, content-addressed, capability-bounded.
 *
 * In a conventional harness the model emits tool calls that are *executed* — the
 * model's output *is* the action set, so a poisoned or confused model expands what
 * runs. Strand lets the actions be a verified graph the model merely
 * *parameterizes*: the model's output is **data the graph consumes, not code the
 * graph runs**. The action set is graph structure — fixed and verified at admission
 * — so a poisoned decision can at worst supply bad data to an already-bounded
 * action; it can never add an action.
 *
 * The "model" is represented here by a hand-authored `decisions` value baked into
 * each program — the stand-in for untrusted LLM output, exactly as every other
 * demonstration hand-authors its programs to isolate the generation question. All
 * three programs share the SAME actuator graph: fold the decisions list into a
 * digest string, then `Fs.Write(reportName, digest)` — one effectful node,
 * `Filesystem.Write`. They differ ONLY in their literal decisions:
 *
 *  - `skill-benign` — `reportName = "report.md"`, benign triage entries.
 *  - `skill-poisoned-content` — `reportName = "report.md"`, one entry's note
 *    carries prompt-injection text.
 *  - `skill-poisoned-path` — `reportName = "../../etc/shadow"`, benign entries.
 *
 * The host is an ordinary JVM caller of the shipped embedding surface — the Q-054
 * [StrandRuntime] facade, the Q-067 surfaced effect closure, the Q-031 refined
 * [CapabilitySet] grant, and the Q-064 [DenialReport]. It introduces no language
 * feature, no node category, no encoding change, and no verifier rule. Every
 * property the demonstration claims is one the runtime enforces; the assertion net
 * ([SkillWorkflowDemoTest]) protects each one.
 *
 * Four scenarios:
 *
 *  - **SK1 The actuator runs under its published bound.** The host loads the
 *    verified actuator, renders its harm-bound manifest (the Q-067 surfaced closure
 *    `{Filesystem.Write}`), and runs `skill-benign` under a grant of
 *    `Filesystem.Write` refined to the one report path. The digest is written; the
 *    file on disk carries the model's content. *The model decided; the graph acted;
 *    the bound is provable from the graph alone.*
 *  - **SK2a The action set is graph structure (keystone, part 1).** The surfaced
 *    closure of `skill-poisoned-content` is IDENTICAL to `skill-benign`'s — both
 *    `{Filesystem.Write}` — because the closure is computed from the actuator, not
 *    the data. Run under the grant, the write to `report.md` succeeds; the file on
 *    disk carries the injection text as CONTAINED digest content, and no other
 *    effect occurred. The poisoned note is data; it cannot add an action.
 *  - **SK2b The model parameter is bounded by refinement (keystone, part 2).**
 *    `skill-poisoned-path` steers the write toward `../../etc/shadow`; the
 *    refined `Filesystem.Write{path = report.md}` capability denies it at the
 *    foreign-call boundary with a real `RefinementViolation` [DenialReport]. The
 *    SECURE-workspace sandbox (`FsPathEscape`) is a second, independent containment
 *    of the same escape.
 *  - **SK3 The skill replays deterministically.** `skill-benign` run twice under
 *    the same grant writes a byte-identical digest both times — a skill invocation
 *    is reproducible given the same decisions, separating "was the action correct"
 *    (graph, replayable) from "was the judgment correct" (model, out of scope).
 *
 * The scenario methods return structured results that both [main] (the transcript)
 * and [SkillWorkflowDemoTest] (the assertions) consume, so the printed
 * demonstration and the regression net share one body of code. Modelled on
 * [CleanRoomDemo] and [PluginHostDemo]; the helper shapes ([loadProgramJson],
 * [image], [nameMap], [surfacedRootClosure], [renderDenial], the SECURE-workspace
 * setup) are the same.
 */
object SkillWorkflowDemo {

    /** The egress effect categories a watcher would screen for (none should appear). */
    val EGRESS_CATEGORIES: Set<String> = sortedSetOf(
        "Network.Connect",
        "Network.Send",
        "Network.Receive",
        "Process.Spawn",
    )

    /** The skill's one declared report path — the grant pins the write to it. */
    const val REPORT_PATH = "report.md"

    // ------------------------------------------------------------------
    // Program loading — the host admits the canonical dag-json artifact.
    // ------------------------------------------------------------------

    /**
     * Load a committed actuator program (canonical dag-json) from the classpath.
     * The source of truth is `demos/skill-workflow/programs/<name>.json`; the
     * `:runtime` build copies those onto the test classpath under `/demo/programs/`
     * (see `runtime/build.gradle.kts`), so this resource path is the classpath
     * namespace, not a filesystem location.
     */
    fun loadProgramJson(name: String): String =
        SkillWorkflowDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    /** Finalize an actuator's authored dag-json to the host's [ProgramImage]. */
    fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    /** The author-id → NodeId map for a program (used to build refined grants). */
    fun nameMap(json: String): Map<String, NodeId> = JsonIngest.parse(json).nameMap

    /**
     * Q-067: the verifier-computed effect closure of [program]'s root, read directly
     * off [verify] (`VerifyResult.Ok.rootClosure`) and rendered to EffectCategory
     * names. This is `closure(g)` exactly as the verifier enforced it — the
     * actuator's published harm-bound manifest. It is computed from the graph, not
     * the decisions, which is why it is identical across all three programs.
     */
    fun surfacedRootClosure(program: ProgramImage, verify: VerifyResult.Ok): Set<String> =
        verify.rootClosure(program.root)
            .mapNotNull { (program.store.getOrNull(it) as? Node.EffectCategory)?.categoryName }
            .toSortedSet()

    /**
     * The declared effect closure of [program] by structural induction over the
     * graph — the set of EffectCategory names reachable from the root. Used only to
     * cross-check the surfaced closure; for these actuators (no Handler) the two
     * agree.
     */
    fun declaredEffectClosure(program: ProgramImage): Set<String> {
        val store = program.store
        val seen = HashSet<NodeId>()
        val stack = ArrayDeque<NodeId>()
        stack.addLast(program.root)
        val categories = sortedSetOf<String>()
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            val node = store.getOrNull(id) ?: continue
            if (node is Node.EffectCategory) categories += node.categoryName
            for (child in node.childNodeIds()) stack.addLast(child)
        }
        return categories
    }

    /**
     * The grant the skill publishes: `Filesystem.Write` refined to exactly the one
     * report path. The category NodeId is resolved from the program's own author id
     * (capability grants are keyed by the category NodeId the program declares).
     */
    fun reportWriteGrant(programJson: String): CapabilitySet {
        val writeFx = nameMap(programJson).getValue("writeFx")
        return CapabilitySet(
            mapOf(writeFx to listOf(
                CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV(REPORT_PATH)))),
            )),
        )
    }

    /**
     * A host whose filesystem sandbox roots writes under [workspace] and denies any
     * escape outside it — the SECURE-workspace setup. Network is irrelevant here
     * (the actuator has no network node), but default-deny keeps the profile honest.
     */
    fun secureHost(workspace: Path): StrandRuntime =
        StrandRuntime(
            HostPolicy.OPEN.copy(
                sandbox = SandboxPolicy(
                    fs = FsPolicy(workspaceRoot = workspace, escape = EscapePolicy.Deny),
                    net = NetPolicy(defaultDeny = true),
                ),
            ),
        )

    /**
     * Render a [DenialReport] the way an orchestrating principal sees it — the same
     * field set the CLI's `strand:denial` line carries.
     */
    fun renderDenial(report: DenialReport): String = buildString {
        append("category=").append(report.category)
        append(" requested=").append(report.requested ?: "(withheld)")
        append(" held=").append(report.held ?: "(withheld)")
        append(" node=").append(report.node?.toString() ?: "null")
        append(" phase=").append(report.phase.wire)
    }

    // ==================================================================
    // SK1 — The actuator runs under its published bound.
    // ==================================================================

    /**
     * Result of SK1. [surfacedClosure] is the actuator's harm-bound manifest;
     * [egressInClosure] is the (empty) intersection with watched egress;
     * [bytesWritten] is the actuator's run result; [fileContent] is what landed on
     * disk; [contentCarriesModelData] confirms the model's note is in the digest.
     */
    data class SK1Result(
        val verifiedClean: Boolean,
        val surfacedClosure: Set<String>,
        val egressInClosure: Set<String>,
        val bytesWritten: Value?,
        val fileContent: String,
        val contentCarriesModelData: Boolean,
    )

    /**
     * Load the benign actuator, render its published bound (the Q-067 surfaced
     * closure `{Filesystem.Write}`), run it under a grant refined to the one report
     * path in a SECURE workspace, then read the temp file and confirm the digest was
     * written with the model's content. The bound is read off the verified artifact
     * before running; the model's decisions only parameterize the data written.
     */
    fun scenarioSK1(tmp: Path): SK1Result {
        val workspace = tmp.resolve("sk1").also { Files.createDirectories(it) }
        val json = loadProgramJson("skill-benign")
        val program = image(json)
        val host = secureHost(workspace)

        val verify = host.verify(program)
        val verifiedClean = verify is VerifyResult.Ok
        val surfaced = (verify as? VerifyResult.Ok)?.let { surfacedRootClosure(program, it) } ?: emptySet()
        val egressInClosure = surfaced intersect EGRESS_CATEGORIES

        val outcome = host.run(program, reportWriteGrant(json))
        val bytesWritten = (outcome as? RunOutcome.Ok)?.value

        val file = workspace.resolve(REPORT_PATH)
        val content = if (Files.exists(file)) Files.readString(file) else ""
        // The model's first note flows into the written digest.
        val modelNote = "checkout button overlaps footer"

        return SK1Result(
            verifiedClean = verifiedClean,
            surfacedClosure = surfaced,
            egressInClosure = egressInClosure,
            bytesWritten = bytesWritten,
            fileContent = content,
            contentCarriesModelData = content.contains(modelNote),
        )
    }

    // ==================================================================
    // SK2a — The action set is graph structure (keystone, part 1).
    // ==================================================================

    /**
     * Result of SK2a. [benignClosure] and [poisonedClosure] are the two surfaced
     * closures (identical by construction); [bytesWritten] is the poisoned run's
     * result; [fileContent] is the written digest; [injectionContained] confirms the
     * injection text landed as inert digest content; [egressInClosure] confirms no
     * egress was added.
     */
    data class SK2aResult(
        val benignClosure: Set<String>,
        val poisonedClosure: Set<String>,
        val bytesWritten: Value?,
        val fileContent: String,
        val injectionContained: Boolean,
        val egressInClosure: Set<String>,
    ) {
        val closuresIdentical: Boolean get() = benignClosure == poisonedClosure
    }

    /**
     * Compute both actuators' surfaced closures and show they are identical, then run
     * the poisoned-content actuator under the same report grant. The injection text
     * is consumed as DATA — it lands in the digest content — and no new effect
     * occurs, because the closure is computed from the actuator, not the decisions.
     * A poisoned note cannot add a graph node, so it cannot add an action.
     */
    fun scenarioSK2a(tmp: Path): SK2aResult {
        val workspace = tmp.resolve("sk2a").also { Files.createDirectories(it) }

        val benignJson = loadProgramJson("skill-benign")
        val benignImg = image(benignJson)
        val poisonedJson = loadProgramJson("skill-poisoned-content")
        val poisonedImg = image(poisonedJson)

        val host = secureHost(workspace)
        val benignClosure = (host.verify(benignImg) as VerifyResult.Ok).let { surfacedRootClosure(benignImg, it) }
        val poisonedClosure = (host.verify(poisonedImg) as VerifyResult.Ok).let { surfacedRootClosure(poisonedImg, it) }

        val outcome = host.run(poisonedImg, reportWriteGrant(poisonedJson))
        val bytesWritten = (outcome as? RunOutcome.Ok)?.value

        val file = workspace.resolve(REPORT_PATH)
        val content = if (Files.exists(file)) Files.readString(file) else ""
        // The injection substring the poisoned note carries.
        val injection = "IGNORE ALL PRIOR INSTRUCTIONS"

        return SK2aResult(
            benignClosure = benignClosure,
            poisonedClosure = poisonedClosure,
            bytesWritten = bytesWritten,
            fileContent = content,
            injectionContained = content.contains(injection),
            egressInClosure = poisonedClosure intersect EGRESS_CATEGORIES,
        )
    }

    // ==================================================================
    // SK2b — The model parameter is bounded by refinement (keystone, part 2).
    // ==================================================================

    /**
     * Result of SK2b. [verified] confirms the actuator admits cleanly (the path is
     * data, not a structural defect); [denialReport]/[denialError] are the real
     * refinement denial; [shadowFileAbsent] confirms nothing was written to the
     * escape target.
     */
    data class SK2bResult(
        val verified: Boolean,
        val denialReport: DenialReport?,
        val denialError: InterpretError?,
        val shadowFileAbsent: Boolean,
    )

    /**
     * Run the poisoned-path actuator (`reportName = "../../etc/shadow"`) under the
     * grant refined to `report.md`. The write is denied at the foreign-call boundary
     * with a real `RefinementViolation` [DenialReport] (requested escape path vs held
     * `report.md`). The SECURE-workspace sandbox (`FsPathEscape`) is a second,
     * independent containment of the same escape — the refinement denial fires first,
     * at the capability check, before any IO.
     */
    fun scenarioSK2b(tmp: Path): SK2bResult {
        val workspace = tmp.resolve("sk2b").also { Files.createDirectories(it) }
        val json = loadProgramJson("skill-poisoned-path")
        val program = image(json)
        val host = secureHost(workspace)

        val verified = host.verify(program) is VerifyResult.Ok

        val outcome = runCatching { host.run(program, reportWriteGrant(json)) }
        val ex = outcome.exceptionOrNull() as? InterpretException
        val error = ex?.error
        val report = when (error) {
            is InterpretError.RefinementViolation -> error.report
            is InterpretError.CapabilityViolation -> error.report
            else -> null
        }
        // Nothing reached disk: the would-be escape target (resolved naively against
        // the workspace) does not exist.
        val shadowAbsent = !Files.exists(workspace.resolve("../../etc/shadow").normalize())

        return SK2bResult(
            verified = verified,
            denialReport = report,
            denialError = error,
            shadowFileAbsent = shadowAbsent,
        )
    }

    // ==================================================================
    // SK3 — The skill replays deterministically.
    // ==================================================================

    /**
     * Result of SK3. [firstDigest] and [secondDigest] are the bytes written by two
     * runs of the benign actuator under the same grant.
     */
    data class SK3Result(
        val firstDigest: ByteArray,
        val secondDigest: ByteArray,
    ) {
        val byteIdentical: Boolean get() = firstDigest.contentEquals(secondDigest)
    }

    /**
     * Run the benign actuator twice under the same grant, into two separate
     * workspaces, and read both written digests. Given the same decisions, the
     * actuator produces a byte-identical digest both times — a skill invocation
     * replays deterministically. This separates "was the action correct" (the graph,
     * replayable) from "was the judgment correct" (the model, out of scope).
     */
    fun scenarioSK3(tmp: Path): SK3Result {
        val json = loadProgramJson("skill-benign")
        val program = image(json)
        val grant = reportWriteGrant(json)

        fun runOnce(slot: String): ByteArray {
            val ws = tmp.resolve(slot).also { Files.createDirectories(it) }
            secureHost(ws).run(program, grant)
            return Files.readAllBytes(ws.resolve(REPORT_PATH))
        }

        return SK3Result(firstDigest = runOnce("sk3-a"), secondDigest = runOnce("sk3-b"))
    }

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val tmp = Files.createTempDirectory("strand-skill-workflow-demo")
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)

        line("=".repeat(72))
        line("Strand -- skill workflow (model-as-policy / graph-as-actuator)")
        line("The model's output is DATA the graph consumes, not CODE the graph")
        line("runs: the action set is graph structure, fixed and verified at")
        line("admission, so a poisoned decision cannot expand it.")
        line("=".repeat(72))
        line()

        // --- SK1 ---
        line("SK1  The actuator runs under its published bound")
        line("-".repeat(72))
        val sk1 = scenarioSK1(tmp)
        line("  Actuator: skill-benign (folds the model's decisions into a digest,")
        line("            writes it to report.md -- one Filesystem.Write node).")
        line("  The host reads the verifier's surfaced effect closure (Q-067) --")
        line("  the skill's published harm-bound manifest:")
        line("    surfaced closure (Ok)     = ${sk1.surfacedClosure}")
        line("    egress categories watched = $EGRESS_CATEGORIES")
        line("    egress in closure         = ${sk1.egressInClosure}")
        line("  The harm bound is provable from the graph alone, before running.")
        line("  Host runs the actuator under Filesystem.Write{path=report.md}:")
        line("    bytes written             = ${sk1.bytesWritten}")
        line("    digest carries model data = ${sk1.contentCarriesModelData}")
        line("  The model decided; the graph acted; the bound came from the graph.")
        line("  Written digest:")
        for (l in sk1.fileContent.lines()) line("    | $l")
        line()

        // --- SK2a ---
        line("SK2a The action set is graph structure (keystone, part 1)")
        line("-".repeat(72))
        val sk2a = scenarioSK2a(tmp)
        line("  A prompt-injected model could emit a malicious decision. Here one")
        line("  entry's note carries injection text. The surfaced closure is")
        line("  computed from the ACTUATOR, not the data, so it is unchanged:")
        line("    closure(skill-benign)            = ${sk2a.benignClosure}")
        line("    closure(skill-poisoned-content)  = ${sk2a.poisonedClosure}")
        line("    closures identical               = ${sk2a.closuresIdentical}")
        line("  Run the poisoned-content actuator under the same report grant:")
        line("    bytes written             = ${sk2a.bytesWritten}")
        line("    injection text contained  = ${sk2a.injectionContained} (as inert digest content)")
        line("    egress in closure         = ${sk2a.egressInClosure} (no action added)")
        line("  The poisoned note is DATA; it cannot add a graph node, so it cannot")
        line("  add an action. The write still targets report.md and nothing else.")
        line("  Written digest (injection landed as contained text):")
        for (l in sk2a.fileContent.lines()) line("    | $l")
        line()

        // --- SK2b ---
        line("SK2b The model parameter is bounded by refinement (keystone, part 2)")
        line("-".repeat(72))
        val sk2b = scenarioSK2b(tmp)
        line("  Actuator: skill-poisoned-path. The model-supplied reportName is")
        line("            ../../etc/shadow -- a path escape steering the write out")
        line("            of bounds. Host grant: Filesystem.Write{path=report.md}.")
        line("    actuator admitted (verified) = ${sk2b.verified}")
        if (sk2b.denialReport != null) {
            line("  Host decision: DENIED at runtime (${sk2b.denialError?.let { it::class.simpleName }})")
            line("    DenialReport: ${renderDenial(sk2b.denialReport!!)}")
        } else {
            line("  Host decision: (no denial -- unexpected) ${sk2b.denialError}")
        }
        line("    escape target written        = ${!sk2b.shadowFileAbsent}")
        line("  The model supplied the path as DATA; the refined Filesystem.Write")
        line("  capability denies it at the capability check, before any IO. The")
        line("  SECURE-workspace sandbox (FsPathEscape) is a second, independent")
        line("  containment of the same escape.")
        line()

        // --- SK3 ---
        line("SK3  The skill replays deterministically")
        line("-".repeat(72))
        val sk3 = scenarioSK3(tmp)
        line("  Run skill-benign twice under the same grant, into two workspaces.")
        line("    digest bytes (run 1)      = ${sk3.firstDigest.size}")
        line("    digest bytes (run 2)      = ${sk3.secondDigest.size}")
        line("    byte-identical            = ${sk3.byteIdentical}")
        line("  Given the same decisions, the actuator writes the identical digest.")
        line("  A skill invocation is reproducible: 'was the action correct' (the")
        line("  graph, replayable) is separated from 'was the judgment correct'")
        line("  (the model, out of scope).")
        line()

        line("=".repeat(72))
        line("What this demonstrates: the judgment <-> action boundary. The model is")
        line("the policy (untrusted, out of the graph); the graph is the actuator")
        line("(verified, capability-bounded). A poisoned decision is contained as")
        line("data; it cannot expand the action set, which is graph structure fixed")
        line("at admission. NOT first-pass correctness or inference cost. The model")
        line("decisions are hand-authored literal stand-ins for LLM output.")
        line("=".repeat(72))

        print(out)
    }
}
