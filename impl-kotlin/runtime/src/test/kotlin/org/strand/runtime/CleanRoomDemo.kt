package org.strand.runtime

import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.childNodeIds
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.NetPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * A data clean-room, demonstrated: the effect closure as a positive structural
 * proof of non-exfiltration.
 *
 * This is the mode-2 reframing of containment. The [ContainmentHost][ContainmentDemo]
 * demonstration shows the host *bounding harm* — admitting an unfamiliar program,
 * computing its maximum harm from the artifact, and isolating it at runtime. This
 * demonstration shows the *positive* face of the same mechanism: a data clean-room
 * admits a partner-submitted analytic computation over a host-held sensitive
 * dataset only after confirming the computation's declared effect closure contains
 * no egress category. `Network.*` and `Process.*` are absent from the closure, so
 * the computation cannot phone home — not because a monitor watches the run, but
 * because the admitted graph cannot *express* the egress at all. The verifier's
 * mandatory effect-closure rule (`UncoveredEffects`) guarantees no admitted graph
 * performs an effect absent from its declarations, so "no egress in the closure"
 * is a proof of the negative, not an observation of one run.
 *
 * The host (an ordinary JVM caller of the Q-054 [StrandRuntime] facade and the
 * Q-067 surfaced effect closure) introduces no language feature, node category,
 * encoding change, or verifier rule. The two submissions are hand-authored
 * stand-ins for partner submissions; hand-authoring isolates the clean-room's
 * admission decision from the separate question of how a partner generates a
 * computation.
 *
 * Two scenarios:
 *
 *  - **CR1 Admit with proof.** The clean submission `clean-analytic` reads the
 *    host's dataset and reduces it to a byte count — its closure is
 *    `{Filesystem.Read}`. The host reads the *surfaced* closure (Q-067) off
 *    `VerifyResult.Ok`, intersects it with the egress categories, finds the
 *    intersection empty, and admits the computation under a grant of exactly
 *    `{Filesystem.Read}`. Empty egress is a proof by construction: the admitted
 *    graph has no node that can open a socket. The host then runs it over a real
 *    dataset file in a SECURE workspace and shows the analytic result.
 *  - **CR2 Reject the exfiltrator.** The exfiltrator submission
 *    `exfiltrator-analytic` is the clean computation plus a `Net.Connect` egress
 *    edge; its closure contains `Network.Connect`. Under the clean-room grant
 *    `{Filesystem.Read}` the host's admission harm bound shows `beyondGrant =
 *    {Network.Connect}` (non-empty), so the host refuses admission *before
 *    running* — the egress is structurally present and outside the grant.
 *
 * The scenario methods return structured results that both [main] (the transcript)
 * and `CleanRoomDemoTest` (the assertions) consume, so the printed demonstration
 * and the regression net share one body of code. Modelled on [ContainmentDemo];
 * the helper shapes ([loadProgramJson], [image], [nameMap], [declaredEffectClosure],
 * [surfacedRootClosure], [admissionHarmBound]) are the same.
 */
object CleanRoomDemo {

    /** The egress effect categories a clean-room computation must not reach. */
    val EGRESS_CATEGORIES: Set<String> = sortedSetOf(
        "Network.Connect",
        "Network.Send",
        "Network.Receive",
        "Process.Spawn",
    )

    /** The clean-room grant: read the host's dataset, nothing else. */
    const val GRANT_CATEGORY = "Filesystem.Read"

    // ------------------------------------------------------------------
    // Program loading — the clean-room admits the canonical dag-json artifact.
    // ------------------------------------------------------------------

    /**
     * Load a committed submission (canonical dag-json) from the classpath. The
     * source of truth is `demos/clean-room/programs/<name>.json`; the `:runtime`
     * build copies those onto the test classpath under `/demo/programs/` (see
     * `runtime/build.gradle.kts`), so this resource path is the classpath
     * namespace, not a filesystem location.
     */
    fun loadProgramJson(name: String): String =
        CleanRoomDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    /** Finalize a submission's authored dag-json to the host's [ProgramImage]. */
    fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    /** The author-id → NodeId map for a submission (used to build refined grants). */
    fun nameMap(json: String): Map<String, NodeId> = JsonIngest.parse(json).nameMap

    // ------------------------------------------------------------------
    // The declared effect closure — computed from the artifact.
    // ------------------------------------------------------------------

    /**
     * The declared effect closure of [program]: the set of EffectCategory names
     * reachable from the root by structural induction over the graph. A sound
     * upper bound on `closure(g)` — the verifier's mandatory `UncoveredEffects`
     * rule guarantees no admitted graph performs an effect absent from its
     * declarations, so every effect a run can reach corresponds to an
     * EffectCategory reachable here. Used for the harm bound of the *rejected*
     * exfiltrator (CR2): a rejected program has no surfaced verifier closure to
     * read, so the host re-derives this sound structural bound. The clean
     * submission (CR1) verifies, so the host prefers the surfaced closure
     * ([surfacedRootClosure]).
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
     * Q-067: the verifier-computed effect closure of [program]'s root, read
     * directly from [verify] (`VerifyResult.Ok.rootClosure`) and rendered to
     * EffectCategory names. This is `closure(g)` exactly as the verifier enforced
     * it — Handler-aware, unlike the structural walk. Available only on the
     * success path (the clean submission), which is why CR2's rejected artifact
     * falls back to [declaredEffectClosure].
     */
    fun surfacedRootClosure(program: ProgramImage, verify: VerifyResult.Ok): Set<String> =
        verify.rootClosure(program.root)
            .mapNotNull { (program.store.getOrNull(it) as? Node.EffectCategory)?.categoryName }
            .toSortedSet()

    /**
     * The host's admission harm bound for [program] under the categories the grant
     * [grantedCategories] permits: `closure(g) ∩ C` at the category level.
     * `reachable` is the declared closure; `permittedByGrant` is the part the grant
     * would allow; `beyondGrant` is the part the grant denies — non-empty means the
     * program reaches an effect the clean-room will not authorize, so the host
     * refuses admission.
     */
    data class AdmissionHarmBound(
        val reachable: Set<String>,
        val permittedByGrant: Set<String>,
        val beyondGrant: Set<String>,
    ) {
        val withinGrant: Boolean get() = beyondGrant.isEmpty()
    }

    fun admissionHarmBound(program: ProgramImage, grantedCategories: Set<String>): AdmissionHarmBound {
        val reachable = declaredEffectClosure(program)
        return AdmissionHarmBound(
            reachable = reachable,
            permittedByGrant = reachable intersect grantedCategories,
            beyondGrant = reachable - grantedCategories,
        )
    }

    // ==================================================================
    // CR1 — Admit with proof
    // ==================================================================

    /**
     * Result of CR1. [surfacedClosure] is the verifier's own effect closure;
     * [egressInClosure] is its intersection with [EGRESS_CATEGORIES] (empty =
     * cannot exfiltrate, by construction); [admitted] is whether the host admitted
     * the computation under the clean-room grant; [analyticResult] is the value of
     * the run over the real dataset (the byte count).
     */
    data class CR1Result(
        val verifiedClean: Boolean,
        val surfacedClosure: Set<String>,
        val egressInClosure: Set<String>,
        val admitted: Boolean,
        val analyticResult: Value?,
        val datasetByteCount: Int,
    ) {
        val cannotExfiltrateByConstruction: Boolean get() = egressInClosure.isEmpty()
    }

    /**
     * Admit the clean submission with a structural proof of non-exfiltration, then
     * run it over a real dataset file in a SECURE workspace.
     *
     * The host writes a dataset into a SECURE workspace, verifies the submission
     * (reading the surfaced effect closure off `VerifyResult.Ok`), confirms the
     * egress categories intersect the closure to ∅ — the proof — and admits the
     * computation under a grant of exactly `{Filesystem.Read}` (refined to the
     * dataset path). It then runs the computation; the analytic result is the
     * dataset's byte count, computed without any egress edge in the graph to phone
     * it home.
     */
    fun scenarioCR1(tmp: Path): CR1Result {
        // The host's sensitive dataset, in a workspace the SECURE sandbox roots to.
        val workspace = tmp.resolve("clean-room-workspace").also { Files.createDirectories(it) }
        val datasetContents = "id,score\n1,42\n2,7\n3,99\n"
        val datasetBytes = datasetContents.toByteArray()
        Files.write(workspace.resolve("dataset.csv"), datasetBytes)

        val json = loadProgramJson("clean-analytic")
        val program = image(json)

        // The clean-room host runs under a SECURE workspace sandbox so the
        // submission's reads are rooted to the dataset workspace and cannot escape
        // it — the network half of SECURE is irrelevant here precisely because the
        // submission has no network node to exercise.
        val host = StrandRuntime(
            HostPolicy.OPEN.copy(
                sandbox = SandboxPolicy(
                    fs = FsPolicy(workspaceRoot = workspace, escape = EscapePolicy.Deny),
                    net = NetPolicy(defaultDeny = true),
                ),
            ),
        )

        val verify = host.verify(program)
        val verifiedClean = verify is VerifyResult.Ok
        val surfaced = (verify as? VerifyResult.Ok)?.let { surfacedRootClosure(program, it) } ?: emptySet()
        val egressInClosure = surfaced intersect EGRESS_CATEGORIES

        // Admission decision: the closure carries Filesystem.Read and no egress, so
        // the host admits under a grant of exactly the clean-room category, refined
        // to the dataset path. The egress-empty intersection is the admission proof.
        val admit = verifiedClean && egressInClosure.isEmpty()

        var analyticResult: Value? = null
        if (admit) {
            val readFx = nameMap(json).getValue("readFx")
            val grant = CapabilitySet.ofCategories(setOf(readFx))
            val outcome = host.run(program, grant)
            analyticResult = (outcome as? RunOutcome.Ok)?.value
        }

        return CR1Result(
            verifiedClean = verifiedClean,
            surfacedClosure = surfaced,
            egressInClosure = egressInClosure,
            admitted = admit,
            analyticResult = analyticResult,
            datasetByteCount = datasetBytes.size,
        )
    }

    // ==================================================================
    // CR2 — Reject the exfiltrator
    // ==================================================================

    /**
     * Result of CR2. [harmBound] is the host's admission decision computed from the
     * artifact under the clean-room grant; [refusedAdmission] is whether the host
     * refused to admit. The exfiltrator never runs.
     */
    data class CR2Result(
        val harmBound: AdmissionHarmBound,
        val refusedAdmission: Boolean,
    )

    /**
     * Refuse the exfiltrator before it runs. The submission is the clean
     * computation plus a `Net.Connect` egress edge, so its declared closure
     * contains `Network.Connect`. Under the clean-room grant `{Filesystem.Read}`
     * the admission harm bound's `beyondGrant` is `{Network.Connect}` (non-empty),
     * so the host refuses admission — the egress category is structurally present
     * and outside the grant, decided from the artifact with no execution.
     */
    fun scenarioCR2(): CR2Result {
        val json = loadProgramJson("exfiltrator-analytic")
        val program = image(json)
        val harmBound = admissionHarmBound(program, grantedCategories = setOf(GRANT_CATEGORY))
        return CR2Result(
            harmBound = harmBound,
            refusedAdmission = !harmBound.withinGrant,
        )
    }

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val tmp = Files.createTempDirectory("strand-clean-room-demo")
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)

        line("=".repeat(72))
        line("Strand -- data clean-room (proof-of-no-exfiltration demonstration)")
        line("The effect closure with no egress category is a structural proof:")
        line("the admitted graph cannot express egress, so it cannot exfiltrate.")
        line("=".repeat(72))
        line()

        // --- CR1 ---
        line("CR1  Admit with proof -- egress empty by construction")
        line("-".repeat(72))
        val cr1 = scenarioCR1(tmp)
        line("  Submission: clean-analytic (reads the host dataset, reduces it to a")
        line("              byte count -- a pure analytic over Filesystem.Read).")
        line("  The host reads the verifier's surfaced effect closure (Q-067):")
        line("    surfaced closure (Ok)     = ${cr1.surfacedClosure}")
        line("    egress categories watched = $EGRESS_CATEGORIES")
        line("    egress in closure         = ${cr1.egressInClosure}")
        line("  egress in closure = ${cr1.egressInClosure} -> cannot exfiltrate, by construction.")
        line("  The admitted graph has no node that opens a socket or spawns a")
        line("  process; empty egress is a proof, not an observation of one run.")
        line("  Host decision: ${if (cr1.admitted) "ADMITTED under grant {$GRANT_CATEGORY}" else "(not admitted -- unexpected)"}")
        line("  Run over the real ${cr1.datasetByteCount}-byte dataset in a SECURE workspace:")
        line("    analytic result (byte count) = ${cr1.analyticResult}")
        line()

        // --- CR2 ---
        line("CR2  Reject the exfiltrator -- egress in closure, beyond the grant")
        line("-".repeat(72))
        val cr2 = scenarioCR2()
        line("  Submission: exfiltrator-analytic (the same clean analytic, PLUS a")
        line("              Net.Connect egress edge to collector.evil.example.com).")
        line("  Host computes the admission harm bound from the artifact alone:")
        line("    reachable effect closure  = ${cr2.harmBound.reachable}")
        line("    clean-room grant (C)      = {$GRANT_CATEGORY}")
        line("    closure(g) intersect C    = ${cr2.harmBound.permittedByGrant}")
        line("    beyond the grant          = ${cr2.harmBound.beyondGrant}")
        line("  Host decision: ${if (cr2.refusedAdmission) "REFUSED at admission (before any run)" else "(admitted -- unexpected)"}")
        line("  Network.Connect is structurally present in the closure and outside")
        line("  the clean-room grant, so the host refuses to admit -- the egress")
        line("  cannot be hidden, because it is a declared, reachable graph node.")
        line()

        // --- CR3 honest scope ---
        line("CR3  Scope of the proof (honest caveat)")
        line("-".repeat(72))
        line("  The non-exfiltration proof holds for the clean-room PROFILE: typed")
        line("  builtins whose effects are declared and surfaced in the closure. It")
        line("  does NOT extend to a Process.Spawn shell-out, whose child process's")
        line("  internal effects are opaque to the closure -- which is exactly why")
        line("  Process.Spawn is among the watched egress categories and a clean-room")
        line("  grant of {Filesystem.Read} would refuse it. The proof is about what")
        line("  the admitted graph can EXPRESS, not about what already-trusted host")
        line("  builtins do internally.")
        line()

        line("=".repeat(72))
        line("What this demonstrates: the effect closure as a positive structural")
        line("proof of a negative (non-exfiltration) -- the mode-2 reframing of the")
        line("Q-044 containment bound. NOT first-pass correctness of the submission,")
        line("nor its inference cost. The submissions are hand-authored stand-ins,")
        line("isolating admission from the agent-generation question.")
        line("=".repeat(72))

        print(out)
    }
}
