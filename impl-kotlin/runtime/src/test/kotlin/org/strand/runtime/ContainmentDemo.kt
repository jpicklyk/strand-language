package org.strand.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.childNodeIds
import org.strand.hashing.Hasher
import org.strand.hashing.PersistentStore
import org.strand.interpreter.Builtins
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
import org.strand.interpreter.SandboxViolationKind
import org.strand.interpreter.StaticCredentialProvider
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier

/**
 * An untrusted-agent-program host, demonstrated.
 *
 * This is the executable companion to the Q-044 containment measurement
 * (`evaluation/containment-results.md`). It shows a host admitting agent
 * submissions it has never seen, computing each program's maximum harm from the
 * artifact alone, and running it contained — with concurrent tenants provably
 * isolated. Every scenario exercises a real property the shipped runtime
 * enforces; nothing is staged. The tenant programs are hand-authored stand-ins
 * for agent submissions (see `impl-kotlin/demo/programs/`), so the demo isolates
 * the *host's* containment from the separate agent-generation question.
 *
 * Four scenarios, mapped to the Q-044 harm classes:
 *
 *  - **S1 Admission rejection.** An over-reaching submission is rejected at
 *    verify before any execution; the host prints the harm bound it computed
 *    from the artifact. (Confused-deputy / undeclared-effect, stopped at verify.)
 *  - **S2 Concurrent isolation.** Two mutually-distrusting tenants run
 *    concurrently under distinct policies with forced overlap; neither can reach
 *    the other's workspace, and neither's credential appears in the other's
 *    scrubbed error. (Path traversal + credential leak, bounded at runtime, under
 *    genuine concurrency.)
 *  - **S3 Runtime denial.** A submission that verifies clean is denied at the
 *    foreign-call boundary by a refinement check; the host captures the
 *    structured DenialReport while a co-tenant in the same batch completes.
 *    (Ungranted-capability / over-reach on a value-dependent argument.)
 *  - **S4 Run-by-hash.** A benign submission is admitted-and-verified-once into a
 *    persistent store, then re-run by root hash with no re-verification and an
 *    identical result.
 *
 * The four scenario methods return structured results that both [main] (the
 * transcript) and `ContainmentDemoTest` (the assertions) consume, so the printed
 * demonstration and the regression net share one body of code.
 */
object ContainmentDemo {

    // ------------------------------------------------------------------
    // Program loading — the host admits the canonical dag-json artifact.
    // ------------------------------------------------------------------

    /** Load a committed tenant program (canonical dag-json) from the classpath. */
    fun loadProgramJson(name: String): String =
        ContainmentDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    /** Finalize a program's authored dag-json to the host's [ProgramImage]. */
    fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    /** The author-id → NodeId map for a program (used to build refined grants). */
    fun nameMap(json: String): Map<String, NodeId> = JsonIngest.parse(json).nameMap

    // ------------------------------------------------------------------
    // The harm bound — computed from the artifact, before execution.
    // ------------------------------------------------------------------

    /**
     * The declared effect closure of [program]: the set of EffectCategory names
     * reachable from the root by structural induction over the graph. This is the
     * `closure(g)` half of the Q-044 harm bound `closure(g) ∩ C ∩ B ∩ P`,
     * re-derived by the host from the verified artifact alone — no execution.
     *
     * It is a sound upper bound: the verifier's mandatory effect-closure rule
     * (`UncoveredEffects`) guarantees no admitted graph performs an effect absent
     * from its declarations, so every effect a run can reach corresponds to an
     * EffectCategory reachable here. The walk stops at NodeRef boundaries (a
     * cross-store subgraph the artifact references by hash); these demo programs
     * have none.
     *
     * The verifier computes the same closure internally (`VerifyState.nodeClosures`,
     * the Handler-aware computation) but does not surface it on `VerifyResult.Ok`,
     * so the host re-derives it here. Q-067 (open-questions.md) tracks surfacing it.
     * Surfacing it on `Ok` would not let this walk go away regardless: scenario S1
     * needs the harm bound for an over-reaching submission the verifier *rejects*,
     * and the verifier populates its closure only on a successful `Ok`. This
     * re-derivation stays sound by the `UncoveredEffects` argument above.
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
     * The host's admission harm bound for [program] under the categories the
     * grant [grantedCategories] permits: `closure(g) ∩ C` at the category level.
     * `reachable` is the declared closure; `permittedByGrant` is the part the
     * grant would allow; `beyondGrant` is the part the grant denies — non-empty
     * means the program reaches an effect the host will not authorize, so the
     * host refuses admission.
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

    // ------------------------------------------------------------------
    // Rendering — the DenialReport as the host surfaces it.
    // ------------------------------------------------------------------

    /**
     * Render a [DenialReport] the way an orchestrating principal sees it. Mirrors
     * the CLI's `strand:denial` line field-for-field (the `:cli` `DenialLine`
     * helper is not on `:runtime`'s classpath, so the rendering is reproduced
     * here over the same report fields).
     */
    fun renderDenial(report: DenialReport): String = buildString {
        append("category=").append(report.category)
        append(" requested=").append(report.requested ?: "(withheld)")
        append(" held=").append(report.held ?: "(withheld)")
        append(" node=").append(report.node?.toString() ?: "null")
        append(" phase=").append(report.phase.wire)
    }

    // ==================================================================
    // S1 — Admission rejection
    // ==================================================================

    /**
     * Result of S1. [rejected] is the structured verifier verdict; [harmBound] is
     * what the host computed from the artifact before deciding; [diagnostics] are
     * the rendered verifier errors.
     */
    data class S1Result(
        val rejected: Boolean,
        val harmBound: AdmissionHarmBound,
        val diagnostics: List<String>,
    )

    /**
     * Submit the over-reaching program under a restrictive HostPolicy (a SECURE
     * sandbox; the grant would permit nothing but Time.Now). The host verifies
     * before admitting: the program's declared write refinement has drifted from
     * its call argument, so the Q-039 projection rule rejects it at verify
     * (`ProjectionMismatch`) — harm bounded before any execution. The host also
     * computes the artifact's harm bound to show what the program reaches.
     */
    fun scenarioS1(): S1Result {
        val json = loadProgramJson("overreach-projection-drift")
        val program = image(json)
        // The host grants only Time.Now — the over-reaching write is beyond it.
        val harmBound = admissionHarmBound(program, grantedCategories = setOf("Time.Now"))

        val host = StrandRuntime(HostPolicy.SECURE)
        val verify = host.verify(program)
        val rejected = verify is VerifyResult.Failed
        val diagnostics = (verify as? VerifyResult.Failed)?.errors?.map { it.toString() } ?: emptyList()
        return S1Result(rejected, harmBound, diagnostics)
    }

    // ==================================================================
    // S2 — Concurrent isolation
    // ==================================================================

    /**
     * Result of S2. The two tenants ran concurrently with forced overlap; each
     * was denied reading the other's workspace, and each tenant's credential is
     * absent from the other's scrubbed error.
     */
    data class S2Result(
        val tenantADeniedKind: SandboxViolationKind?,
        val tenantBDeniedKind: SandboxViolationKind?,
        // Credential-scrub half: each tenant's error after the leak probe.
        val tenantAErrorDetail: String,
        val tenantBErrorDetail: String,
        val tenantAKey: String,
        val tenantBKey: String,
    ) {
        val sandboxIsolated: Boolean
            get() = tenantADeniedKind == SandboxViolationKind.FsPathEscape &&
                tenantBDeniedKind == SandboxViolationKind.FsPathEscape

        // A's own key redacted in A's error; B's key (which A's scrubber never
        // saw) survives verbatim — and symmetrically. Identical error text,
        // per-tenant redaction: the per-context-scrubber isolation proof.
        val credentialIsolated: Boolean
            get() = tenantAKey !in tenantAErrorDetail && tenantBKey in tenantAErrorDetail &&
                tenantBKey !in tenantBErrorDetail && tenantAKey in tenantBErrorDetail
    }

    const val TENANT_A_KEY = "sk-ant-tenant-A-aaaaaaaaaaaaaaaa"
    const val TENANT_B_KEY = "sk-ant-tenant-B-bbbbbbbbbbbbbbbb"

    /**
     * Run the two mutually-distrusting tenants concurrently under distinct
     * HostPolicies (separate workspace sandboxes, separate credentials). Two
     * forced-overlap phases, each rendezvoused on a [CyclicBarrier] *inside* the
     * effectful path so isolation is proven under genuine concurrency:
     *
     *  - **Sandbox phase.** Each tenant attempts to read the other's workspace
     *    via a relative escape; each tenant's own workspace-rooted SECURE sandbox
     *    denies it with `FsPathEscape`.
     *  - **Credential phase.** Each tenant resolves its own credential (registering
     *    it into that tenant's per-context scrubber) then throws an identical error
     *    text embedding *both* tenants' keys. Each tenant's scrubber redacts only
     *    its own key; the other tenant's key survives verbatim — the per-context
     *    scrubber isolation property.
     *
     * The credential phase reuses the `ConcurrentMultiTenantIsolationTest`
     * technique: a test builtin triggers a *real* per-context scrubber (host-side
     * scaffolding to exercise the mechanism, not a fake of it).
     */
    fun scenarioS2(tmp: Path): S2Result {
        // --- Sandbox phase -------------------------------------------------
        val wsRoot = tmp.resolve("workspaces")
        val wsA = wsRoot.resolve("tenant-a").also { Files.createDirectories(it) }
        val wsB = wsRoot.resolve("tenant-b").also { Files.createDirectories(it) }
        // Each tenant has a secret in its own workspace (the file the other tries
        // to reach); existence is irrelevant — the escape is denied before any read.
        Files.writeString(wsA.resolve("secret.txt"), "tenant-A-secret")
        Files.writeString(wsB.resolve("secret.txt"), "tenant-B-secret")

        val barrier1 = CyclicBarrier(2)
        Builtins.installTestBuiltin(
            "strand-builtin:Test.Rendezvous",
            effectful = false,
            determinism = Builtins.Determinism.Deterministic,
            fn = Builtins.Fn { _, args -> barrier1.await(); args[0] },
        )

        val progA = loadProgramJson("tenant-a-cross-read")
        val progB = loadProgramJson("tenant-b-cross-read")
        val imgA = image(progA)
        val imgB = image(progB)
        val capsA = CapabilitySet.ofCategories(setOf(nameMap(progA).getValue("readFx")))
        val capsB = CapabilitySet.ofCategories(setOf(nameMap(progB).getValue("readFx")))

        val secureFor = { ws: Path ->
            HostPolicy.OPEN.copy(
                sandbox = SandboxPolicy(
                    fs = FsPolicy(workspaceRoot = ws, escape = EscapePolicy.Deny),
                    net = NetPolicy(defaultDeny = false),
                ),
            )
        }
        val rtA = StrandRuntime(secureFor(wsA))
        val rtB = StrandRuntime(secureFor(wsB))

        val (sandboxA, sandboxB) = try {
            runBlocking {
                withContext(Dispatchers.Default) {
                    val da = async { runCatching { rtA.run(imgA, capsA) } }
                    val db = async { runCatching { rtB.run(imgB, capsB) } }
                    awaitAll(da, db)
                }
            }
        } finally {
            // Drop the rendezvous builtin before the credential phase installs its own.
        }

        val kindA = ((sandboxA.exceptionOrNull() as? InterpretException)?.error
            as? InterpretError.SandboxViolation)?.kind
        val kindB = ((sandboxB.exceptionOrNull() as? InterpretException)?.error
            as? InterpretError.SandboxViolation)?.kind

        // --- Credential phase ---------------------------------------------
        val barrier2 = CyclicBarrier(2)
        Builtins.installTestBuiltin(
            "strand-builtin:Test.LeakBoth",
            effectful = true,
            determinism = Builtins.Determinism.Stateful,
            fn = Builtins.Fn { ctx, _ ->
                // Resolve THIS tenant's key — registers it into ctx's scrubber.
                ctx.credentialProvider.resolve("anthropic", "api_key")
                    ?: error("demo: no api_key configured")
                barrier2.await()
                throw org.strand.interpreter.IoFailure(
                    "tenant-leak",
                    "upstream rejected probeA=$TENANT_A_KEY probeB=$TENANT_B_KEY",
                )
            },
        )

        val leakJson = leakProgramJson()
        val leakImg = image(leakJson)
        val leakFx = nameMap(leakJson).getValue("leakFx")
        val leakCaps = CapabilitySet.ofCategories(setOf(leakFx))
        val rtCredA = StrandRuntime(HostPolicy.OPEN.copy(
            credentialProvider = StaticCredentialProvider(mapOf("anthropic" to TENANT_A_KEY)),
        ))
        val rtCredB = StrandRuntime(HostPolicy.OPEN.copy(
            credentialProvider = StaticCredentialProvider(mapOf("anthropic" to TENANT_B_KEY)),
        ))

        val (credA, credB) = runBlocking {
            withContext(Dispatchers.Default) {
                val da = async { runCatching { rtCredA.run(leakImg, leakCaps) } }
                val db = async { runCatching { rtCredB.run(leakImg, leakCaps) } }
                awaitAll(da, db)
            }
        }

        val detailA = ((credA.exceptionOrNull() as InterpretException).error
            as InterpretError.IoFailure).detail
        val detailB = ((credB.exceptionOrNull() as InterpretException).error
            as InterpretError.IoFailure).detail

        Builtins.clearTestBuiltins()

        return S2Result(
            tenantADeniedKind = kindA,
            tenantBDeniedKind = kindB,
            tenantAErrorDetail = detailA,
            tenantBErrorDetail = detailB,
            tenantAKey = TENANT_A_KEY,
            tenantBKey = TENANT_B_KEY,
        )
    }

    /**
     * A tiny program that calls `Test.LeakBoth()` under a granted effect category.
     * Authored inline (not a committed tenant program) because it exists only to
     * trigger the per-context credential scrubber and is host-side scaffolding,
     * not an agent submission.
     */
    private fun leakProgramJson(): String = """
        {
          "version": 1, "root": "app",
          "nodes": {
            "strT":   { "type": "PrimitiveType", "kind": "String" },
            "leakFx": { "type": "EffectCategory", "categoryName": "Test.Leak" },
            "leakT":  { "type": "FunctionType", "parameters": [], "result": "strT" },
            "leak":   { "type": "ForeignNode", "target": "strand-builtin:Test.LeakBoth", "foreignType": "leakT", "effects": ["leakFx"] },
            "app":    { "type": "Application", "function": "leak", "arguments": [] }
          }
        }
    """.trimIndent()

    // ==================================================================
    // S3 — Runtime denial
    // ==================================================================

    /**
     * Result of S3. The denial tenant terminated with a structured
     * [DenialReport]; the co-tenant submitted in the same batch completed.
     */
    data class S3Result(
        val denialReport: DenialReport?,
        val denialError: InterpretError?,
        val coTenantValue: Value?,
    )

    /**
     * Submit two programs in one batch: a writer that verifies clean but whose
     * write path is *not* covered by the grant the host hands it, and the benign
     * sum. The host grants the writer `Filesystem.Write` refined to
     * `/tenant/out.log`; the writer targets `/tenant/secret.log`, so the runtime's
     * refinement check denies it at the foreign-call boundary with a
     * `RefinementViolation` carrying a [DenialReport]. The benign co-tenant
     * completes to 42 — one tenant's denial does not affect the other.
     */
    fun scenarioS3(): S3Result {
        val denialJson = loadProgramJson("runtime-denial-write")
        val denialImg = image(denialJson)
        val names = nameMap(denialJson)
        val writeFx = names.getValue("writeFx")
        // Host grant: Filesystem.Write refined to a DIFFERENT path than the
        // program writes. The program's /tenant/secret.log is not covered.
        val grant = CapabilitySet(
            mapOf(
                writeFx to listOf(
                    CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tenant/out.log")))),
                ),
            ),
        )
        val host = StrandRuntime(HostPolicy.OPEN)

        val denialOutcome = runCatching { host.run(denialImg, grant) }
        val ex = denialOutcome.exceptionOrNull() as? InterpretException
        val error = ex?.error
        val report = when (error) {
            is InterpretError.RefinementViolation -> error.report
            is InterpretError.CapabilityViolation -> error.report
            else -> null
        }

        // Co-tenant submitted in the same batch: the benign sum runs to completion.
        val coImg = image(loadProgramJson("benign-sum"))
        val coOutcome = StrandRuntime(HostPolicy.OPEN).run(coImg)
        val coValue = (coOutcome as? RunOutcome.Ok)?.value

        return S3Result(report, error, coValue)
    }

    // ==================================================================
    // S4 — Run-by-hash
    // ==================================================================

    /** Result of S4. */
    data class S4Result(
        val rootHashHex: String,
        val verdictCachedAfterIngest: Boolean,
        val firstRunValue: Value?,
        val byHashRunValue: Value?,
    ) {
        val identicalResult: Boolean get() = firstRunValue != null && firstRunValue == byHashRunValue
    }

    /**
     * Ingest the benign program into a persistent store (admit-and-verify-once),
     * then re-run it by root hash. The verdict recorded at ingest is reused (no
     * re-verification), and the by-hash run produces the identical value.
     */
    fun scenarioS4(storeDir: Path): S4Result {
        val json = loadProgramJson("benign-sum")
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val rootHash = finalized.nodeIdToHash.getValue(finalized.root)

        val host = StrandRuntime(HostPolicy.OPEN)

        // First admission: verify once, run once.
        val firstImage = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        val firstVerify = host.verify(firstImage)
        val firstRun = host.run(firstImage)
        val firstValue = (firstRun as? RunOutcome.Ok)?.value

        // Admit-and-verify-once into the store: write nodes + record the verdict.
        val store = PersistentStore.open(storeDir)
        host.ingestToStore(store, finalized, firstVerify, finalized.nodeIdToHash)
        val cached = host.cachedVerdict(store, rootHash)

        // Run by root hash: load the image from the store (no re-ingest, no
        // re-hash), reusing the recorded verdict — then run.
        val byHashImage = host.loadImageFromStore(store, rootHash)
            ?: error("run-by-hash: store did not hold root $rootHash")
        val byHashRun = host.run(byHashImage)
        val byHashValue = (byHashRun as? RunOutcome.Ok)?.value

        return S4Result(
            rootHashHex = rootHash.toString(),
            verdictCachedAfterIngest = cached != null,
            firstRunValue = firstValue,
            byHashRunValue = byHashValue,
        )
    }

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val tmp = Files.createTempDirectory("strand-containment-demo")
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)

        line("=".repeat(72))
        line("Strand -- untrusted agent-program host (containment demonstration)")
        line("Executable companion to evaluation/containment-results.md (Q-044).")
        line("=".repeat(72))
        line()

        // --- S1 ---
        line("S1  Admission rejection -- harm bounded before execution")
        line("-".repeat(72))
        val s1 = scenarioS1()
        line("  Submission: overreach-projection-drift (declares write to")
        line("              /workspace/out.txt, call argument is /etc/shadow).")
        line("  Host computes the harm bound from the artifact alone:")
        line("    reachable effect closure  = ${s1.harmBound.reachable}")
        line("    granted categories (C)    = {Time.Now}")
        line("    closure(g) intersect C    = ${s1.harmBound.permittedByGrant}")
        line("    beyond the grant          = ${s1.harmBound.beyondGrant}")
        line("  Host decision: ${if (s1.rejected) "REJECTED at admission (verify)" else "ADMITTED"}")
        for (d in s1.diagnostics) line("    verifier: $d")
        line("  Contrast: a conventional runtime, with no effect declaration to")
        line("  check the call argument against, would have run this and written")
        line("  /etc/shadow. Strand stops it structurally, before execution.")
        line()

        // --- S2 ---
        line("S2  Concurrent isolation -- two distrusting tenants, forced overlap")
        line("-".repeat(72))
        val s2 = scenarioS2(tmp)
        line("  Two tenants run concurrently under distinct policies, each")
        line("  rendezvoused on a barrier INSIDE the effectful path.")
        line("  Sandbox: tenant A reads ../tenant-b/secret.txt; tenant B reads")
        line("           ../tenant-a/secret.txt -- each escapes its own workspace.")
        line("    tenant A denial = ${s2.tenantADeniedKind}")
        line("    tenant B denial = ${s2.tenantBDeniedKind}")
        line("    sandbox-isolated = ${s2.sandboxIsolated}")
        line("  Credentials: both tenants throw the SAME error text embedding both")
        line("           keys; each per-context scrubber redacts only its own key.")
        line("    tenant A error: ${s2.tenantAErrorDetail}")
        line("    tenant B error: ${s2.tenantBErrorDetail}")
        line("    credential-isolated = ${s2.credentialIsolated}")
        line()

        // --- S3 ---
        line("S3  Runtime denial -- contained at the foreign-call boundary")
        line("-".repeat(72))
        val s3 = scenarioS3()
        line("  Submission: runtime-denial-write (verifies clean; writes")
        line("              /tenant/secret.log). Host grants Filesystem.Write")
        line("              refined to /tenant/out.log -- a DIFFERENT path.")
        if (s3.denialReport != null) {
            line("  Host decision: DENIED at runtime (${s3.denialError?.let { it::class.simpleName }})")
            line("    DenialReport: ${renderDenial(s3.denialReport)}")
        } else {
            line("  Host decision: (no denial -- unexpected) ${s3.denialError}")
        }
        line("  Co-tenant submitted in the same batch (benign-sum): " +
            "value = ${s3.coTenantValue} (completed normally)")
        line()

        // --- S4 ---
        line("S4  Run-by-hash -- admit-and-verify-once, re-run by hash")
        line("-".repeat(72))
        val s4 = scenarioS4(tmp.resolve("store"))
        line("  Submission: benign-sum, ingested into a persistent store.")
        line("    root hash               = ${s4.rootHashHex.take(20)}...")
        line("    verdict cached at ingest= ${s4.verdictCachedAfterIngest}")
        line("    first run value         = ${s4.firstRunValue}")
        line("    run-by-hash value       = ${s4.byHashRunValue}")
        line("    identical result        = ${s4.identicalResult}")
        line("  The by-hash run reused the recorded verdict -- no re-verification,")
        line("  no re-hash -- and produced the identical value.")
        line()

        line("=".repeat(72))
        line("What this demonstrates: structural containment -- the measured Q-044")
        line("benefit. NOT first-pass correctness or inference cost (the deferred")
        line("Run 8 measurement, evaluation/dynamic-results.md). Tenant programs are")
        line("hand-authored stand-ins for agent submissions, isolating the host's")
        line("containment from the separate agent-generation question.")
        line("=".repeat(72))

        print(out)
    }
}
