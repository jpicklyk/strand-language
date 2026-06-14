package org.strand.runtime

import java.nio.file.Files
import java.nio.file.Path

/**
 * The agent platform, demonstrated end to end.
 *
 * This is not a new mechanism — it is the spine that ties the suite's twelve
 * separate guarantees into one platform story. It walks ONE untrusted,
 * agent-generated task — a retrieval-backed support agent — through the full
 * lifecycle of running it on Strand, drawing each guarantee from the
 * demonstration that isolates it, narrated as a single task's journey.
 *
 * The platform thesis: across the whole lifecycle of running untrusted,
 * agent-generated code, the maximum harm is computable from the artifact
 * *before* execution and bounded *during* it — the property no
 * "LLM-writes-Python-in-a-sandbox" stack can offer, because a sandbox observes a
 * running process while Strand reads a graph that cannot *express* the harm it
 * does not declare.
 *
 * Five lifecycle stages, each a real property the shipped runtime enforces:
 *
 *  - **ADMIT — bound it before running.** The platform receives a program it has
 *    never seen, computes its maximum harm from the artifact (the surfaced effect
 *    closure), confirms it is within the tenant's grant and free of egress, and
 *    admits it. An over-reaching submission is REJECTED at admission, before any
 *    execution. (From [ContainmentDemo] S1 + [CleanRoomDemo] CR1/CR2.)
 *  - **RUN — bounded execution.** The admitted agent runs under exactly its
 *    declared capabilities: it retrieves from one refined knowledge-base index
 *    (`Vector.Read{store}` only) and calls the model under its bound. (From
 *    [BoundedRagDemo] R1 + [AgentWorkflowDemo] A1.)
 *  - **VERIFY — the model's output is checked.** The model's structured response
 *    is constrained (N-045) and the returned value is verified at runtime (Q-047)
 *    before it is used; a semantically-invalid response is contained before
 *    output. (From [VerifiedLlmOutputDemo] VO1/VO2.)
 *  - **WITHSTAND — attacks are contained.** An over-reaching tool effect is denied
 *    with a `DenialReport`; a query against a non-granted index is denied; a
 *    poisoned input is contained as data and cannot expand the action set; and at
 *    scale, a rogue agent in a group is denied while the others survive. Untrusted
 *    CODE is bounded; untrusted DATA is contained. (From [AgentWorkflowDemo] A2 +
 *    [BoundedRagDemo] R2 + [SkillWorkflowDemo] SK2a + [MultiAgentSupervisorDemo]
 *    MA2.)
 *  - **AUDIT — the run is reproducible and content-addressed.** The run replays
 *    bit-identically and the task is admitted-once / run-by-hash. (From
 *    [ReplayDemo] R1 + [ContainmentDemo] S4.)
 *
 * Every underlying scenario method manages its own
 * `Builtins.installTestBuiltin`/`clearTestBuiltins` discipline; sequencing them
 * here proves there is no cross-contamination — the assertion net
 * ([AgentPlatformDemoTest]) re-runs the same calls in lifecycle order and pins
 * each stage's key property. This driver introduces no language feature, node
 * category, encoding change, or verifier rule; the "single task" is a narrative
 * frame over the suite's mechanisms, each of which isolates its own subject with
 * deterministic mocks/stand-ins and hand-authored programs. It is NOT first-pass
 * correctness or inference cost (the deferred Run 8 study).
 *
 * The composed results are returned from [run] so both [main] (the transcript)
 * and [AgentPlatformDemoTest] (the assertions) consume one body of code — the
 * discipline every demonstration in the suite follows.
 */
object AgentPlatformDemo {

    /**
     * The full lifecycle, composed once. Each field is the structured result of
     * the underlying demonstration's scenario method, sequenced into the five
     * stages. The scenarios that need a temp directory ([CleanRoomDemo.scenarioCR1],
     * [SkillWorkflowDemo.scenarioSK2a], [ContainmentDemo.scenarioS4]) share the
     * single [tmp] this driver creates.
     */
    data class Lifecycle(
        // --- ADMIT ---
        val admitReject: ContainmentDemo.S1Result,
        val admitCleanRoom: CleanRoomDemo.CR1Result,
        val admitExfiltrator: CleanRoomDemo.CR2Result,
        // --- RUN ---
        val runRag: BoundedRagDemo.R1Result,
        val runAgent: AgentWorkflowDemo.A1Result,
        // --- VERIFY ---
        val verifyValid: VerifiedLlmOutputDemo.VO1Result,
        val verifyInvalid: VerifiedLlmOutputDemo.VO2Result,
        // --- WITHSTAND ---
        val withstandTool: AgentWorkflowDemo.A2Result,
        val withstandStore: BoundedRagDemo.R2Result,
        val withstandPoison: SkillWorkflowDemo.SK2aResult,
        val withstandRogue: MultiAgentSupervisorDemo.MA2Result,
        // --- AUDIT ---
        val auditReplay: ReplayDemo.R1Result,
        val auditByHash: ContainmentDemo.S4Result,
    )

    /**
     * Run all five stages in lifecycle order, composing the proven scenario
     * methods. Each call is already-passing code with its own builtin-install
     * discipline; sequencing them is the robustness guarantee — the demonstration
     * reuses the suite's regression-tested bodies rather than re-implementing the
     * mechanisms.
     */
    fun run(tmp: Path): Lifecycle = Lifecycle(
        // ADMIT — bound it before running.
        admitReject = ContainmentDemo.scenarioS1(),
        admitCleanRoom = CleanRoomDemo.scenarioCR1(tmp.resolve("admit-clean-room")),
        admitExfiltrator = CleanRoomDemo.scenarioCR2(),
        // RUN — bounded execution.
        runRag = BoundedRagDemo.scenarioR1(),
        runAgent = AgentWorkflowDemo.scenarioA1(),
        // VERIFY — the model's output is checked.
        verifyValid = VerifiedLlmOutputDemo.scenarioVO1(),
        verifyInvalid = VerifiedLlmOutputDemo.scenarioVO2(),
        // WITHSTAND — attacks are contained.
        withstandTool = AgentWorkflowDemo.scenarioA2(),
        withstandStore = BoundedRagDemo.scenarioR2(),
        withstandPoison = SkillWorkflowDemo.scenarioSK2a(tmp.resolve("withstand-poison")),
        withstandRogue = MultiAgentSupervisorDemo.scenarioMA2(),
        // AUDIT — the run is reproducible and content-addressed.
        auditReplay = ReplayDemo.scenarioR1(),
        auditByHash = ContainmentDemo.scenarioS4(tmp.resolve("audit-store")),
    )

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val tmp = Files.createTempDirectory("strand-agent-platform-demo")
        val lc = run(tmp)
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)
        fun rule() = line("-".repeat(72))

        line("=".repeat(72))
        line("Strand -- the agent platform, end to end")
        line("One untrusted, agent-generated task (a retrieval-backed support agent)")
        line("walked through the full lifecycle of running it on Strand. Each stage")
        line("draws its guarantee from the demonstration that isolates it.")
        line()
        line("Thesis: across the whole lifecycle of running untrusted agent code, the")
        line("maximum harm is COMPUTABLE before execution and BOUNDED during it -- the")
        line("property no 'LLM-writes-Python-in-a-sandbox' stack can offer.")
        line("=".repeat(72))
        line()

        // ----------------------------------------------------------------
        // Stage 1 — ADMIT
        // ----------------------------------------------------------------
        line("STAGE 1  ADMIT -- bound it before running")
        line("=".repeat(72))
        line("The platform receives a program it has never seen. Before any code")
        line("runs, it computes the task's maximum harm from the artifact alone, and")
        line("admits the task only if that harm is within the tenant's grant.")
        line()

        line("  Admit the support agent: no egress in its closure (the clean-room proof)")
        rule()
        val cr1 = lc.admitCleanRoom
        line("  The host reads the verifier's surfaced effect closure (Q-067) -- the")
        line("  task's maximum harm, computed from the artifact before it runs:")
        line("    surfaced closure          = ${cr1.surfacedClosure}")
        line("    egress categories watched = ${CleanRoomDemo.EGRESS_CATEGORIES}")
        line("    egress in closure         = ${cr1.egressInClosure}")
        line("  egress in closure = ${cr1.egressInClosure} -> the task CANNOT exfiltrate, by")
        line("  construction: the admitted graph has no node that opens a socket. This")
        line("  is a proof of the negative, not an observation of one run.")
        line("    host decision             = ${if (cr1.admitted) "ADMITTED under {${CleanRoomDemo.GRANT_CATEGORY}}" else "(not admitted)"}")
        line("    ran over the real dataset = ${cr1.analyticResult} (the analytic result)")
        line()

        line("  Contrast: an over-reaching submission is REJECTED at admission")
        rule()
        val s1 = lc.admitReject
        line("  A different submission declares a write to one path but calls with")
        line("  another (an over-reach the verifier catches structurally):")
        line("    reachable effect closure  = ${s1.harmBound.reachable}")
        line("    granted categories (C)    = {Time.Now}")
        line("    beyond the grant          = ${s1.harmBound.beyondGrant}")
        line("    host decision             = ${if (s1.rejected) "REJECTED at admission (verify), before any execution" else "ADMITTED"}")
        line("  A conventional runtime, with no effect declaration to check the call")
        line("  argument against, would have run this. Strand stops it before execution.")
        line()

        line("  Contrast: an exfiltrator (egress in its closure) is REFUSED")
        rule()
        val cr2 = lc.admitExfiltrator
        line("  The same support agent PLUS a Net.Connect egress edge has the egress")
        line("  category structurally present in its closure, beyond the grant:")
        line("    reachable effect closure  = ${cr2.harmBound.reachable}")
        line("    beyond the clean-room grant = ${cr2.harmBound.beyondGrant}")
        line("    host decision             = ${if (cr2.refusedAdmission) "REFUSED at admission (before any run)" else "(admitted)"}")
        line("  The egress cannot be hidden, because it is a declared, reachable node.")
        line()

        // ----------------------------------------------------------------
        // Stage 2 — RUN
        // ----------------------------------------------------------------
        line("STAGE 2  RUN -- bounded execution")
        line("=".repeat(72))
        line("The admitted agent runs under EXACTLY its declared capabilities -- the")
        line("grant covers precisely the surfaced closure, nothing more.")
        line()

        line("  Retrieve from one refined index (Vector.Read{store} only)")
        rule()
        val r1 = lc.runRag
        line("  The task embeds the query, retrieves from the corp-kb index, and")
        line("  generates an answer. Its data-access manifest is the surfaced closure,")
        line("  read off the verified artifact BEFORE running:")
        line("    surfaced closure (manifest) = ${r1.surfacedClosure}")
        line("    grant covers exactly it     = ${r1.grantMatchesClosure}")
        line("  Vector.Read is granted refined to {provider=pinecone, store=corp-kb} --")
        line("  the agent provably can query only that index.")
        line("    embed / query / generate    = ${r1.embedCalls} / ${r1.queryCalls} / ${r1.generateCalls}")
        line("    retrieved hits              = ${r1.retrievedHitCount} (first: ${r1.firstHit})")
        line("    generated answer            = \"${r1.answerText}\"")
        line()

        line("  Call the model under its surfaced bound")
        rule()
        val a1 = lc.runAgent
        line("  The agent's model call runs under a grant of exactly {LLM.Generate} --")
        line("  the bound read off the artifact before the run:")
        line("    surfaced closure (Q-067)  = ${a1.surfacedClosure}")
        line("    grant covers exactly it   = ${a1.grantMatchesClosure}")
        line("    LLM turns / completed     = ${a1.llmCallCount} / ${a1.completed}")
        line("    model result              = \"${a1.resultText}\"")
        line("  Useful agent work under a bound readable from the artifact.")
        line()

        // ----------------------------------------------------------------
        // Stage 3 — VERIFY
        // ----------------------------------------------------------------
        line("STAGE 3  VERIFY -- the model's output is checked")
        line("=".repeat(72))
        line("Constraining a model is not guaranteeing its output. The structured")
        line("response is constrained going out (N-045) and the returned value is")
        line("verified coming back (Q-047) before it is used.")
        line()

        line("  A valid structured response is constrained and passes")
        rule()
        val vo1 = lc.verifyValid
        line("  The model call carries a ResponseSchemaSpec (N-045) that constrains")
        line("  what the model may emit; a valid score flows into a Schema-typed")
        line("  position and the runtime invariant (0 <= score <= 100) passes:")
        line("    model returned score      = ${vo1.modelScore}")
        line("    model transport invoked   = ${vo1.modelCallCount} time(s) (a genuine model call)")
        line("    invariant passed          = ${vo1.completed}")
        line("    validated result          = ${vo1.resultScore}")
        line()

        line("  A semantically-invalid response is CONTAINED before output")
        rule()
        val vo2 = lc.verifyInvalid
        line("  The SAME pipeline, but the model returns a value that is structurally")
        line("  an Int yet out of its declared range (> 100) -- a constrained model")
        line("  can still return this. The Q-047 obligation fires BEFORE output:")
        line("    model returned score      = ${vo2.modelScore}")
        line("    raised at runtime         = ${vo2.raisedAtRuntime}")
        line("    runtime error             = ${vo2.violation?.let { it::class.simpleName }}")
        line("    offending value           = ${vo2.offendingValueDescription}")
        line("    contained before output   = ${vo2.contained}")
        line("  Constraining narrows what the model MAY emit; verifying catches what a")
        line("  constrained model STILL returns. The malformed output never escapes.")
        line()

        // ----------------------------------------------------------------
        // Stage 4 — WITHSTAND
        // ----------------------------------------------------------------
        line("STAGE 4  WITHSTAND -- attacks are contained")
        line("=".repeat(72))
        line("The threat model holds across four attack shapes. The principle:")
        line("untrusted CODE is bounded; untrusted DATA is contained.")
        line()

        line("  An over-reaching tool effect is DENIED (untrusted code, bounded)")
        rule()
        val a2 = lc.withstandTool
        line("  The model invokes a tool whose implementation reaches Filesystem.Write,")
        line("  an effect absent from the {LLM.Generate} grant. The static bound looks")
        line("  identical to the clean run (a ToolDef's effects fire at dispatch, not")
        line("  in the root closure); the ungranted reach is denied at runtime:")
        line("    surfaced closure (Q-067)  = ${a2.surfacedClosure}")
        line("    denied                    = ${a2.denied} (${a2.denialError?.let { it::class.simpleName }})")
        a2.denialReport?.let { line("    DenialReport: ${AgentWorkflowDemo.renderDenial(it)}") }
        line("  The prompt-injection-contained story: a tool reaching for an ungranted")
        line("  effect is stopped at the foreign-call boundary.")
        line()

        line("  A query against a non-granted index is DENIED (untrusted code, bounded)")
        rule()
        val r2 = lc.withstandStore
        line("  A variant opens corp-kb (granted) then issues its retrieval query")
        line("  declaring Vector.Read{pinecone, hr-private} -- a store the grant does")
        line("  not cover. The read is denied at the foreign-call boundary:")
        line("    denied                    = ${r2.denied} (${r2.denialError?.let { it::class.simpleName }})")
        line("    query reached the wire     = ${!r2.queryNeverIssued} (query HTTP calls = ${r2.queryCalls})")
        r2.denialReport?.let { line("    DenialReport: ${BoundedRagDemo.renderDenial(it)}") }
        line("  The index an agent may reach is a refinement-checked capability, not a")
        line("  config string the code can overwrite -- denied before any request leaves.")
        line()

        line("  A poisoned input is CONTAINED as data (untrusted data, contained)")
        rule()
        val sk = lc.withstandPoison
        line("  The model's decisions flow into a verified actuator graph as DATA the")
        line("  graph consumes, not CODE it runs. One decision carries prompt-injection")
        line("  text. The surfaced closure is computed from the actuator, not the data:")
        line("    closure(benign decisions)   = ${sk.benignClosure}")
        line("    closure(poisoned decisions) = ${sk.poisonedClosure}")
        line("    closures identical          = ${sk.closuresIdentical}")
        line("    injection landed as content = ${sk.injectionContained} (inert digest text)")
        line("    egress added                = ${sk.egressInClosure} (none)")
        line("  The poisoned note is data; it cannot add a graph node, so it cannot add")
        line("  an action. The action set is graph structure, fixed at admission.")
        line()

        line("  At scale, a rogue agent is DENIED while the group survives")
        rule()
        val ma = lc.withstandRogue
        line("  In a supervised multi-agent group, one worker reaches an effect beyond")
        line("  the grant. It is denied at its actor boundary; the OTHER agents keep")
        line("  running and complete -- a rogue agent contained without whole-system failure:")
        ma.rogueDenial?.let {
            line("    rogue worker (${ma.rogueWorker}) halted = ${ma.rogueHalted}")
            line("    DenialReport: ${MultiAgentSupervisorDemo.renderDenial(it)}")
        }
        line("    surviving worker A final  = ${ma.okWorkerFinalStates["workerA"]} (completed)")
        line("    surviving worker B final  = ${ma.okWorkerFinalStates["workerB"]} (completed)")
        line("    supervisor stayed up      = ${ma.supervisorOutputs.isNotEmpty()}")
        line("    contained (denied + survived) = ${ma.contained}")
        line()

        // ----------------------------------------------------------------
        // Stage 5 — AUDIT
        // ----------------------------------------------------------------
        line("STAGE 5  AUDIT -- the run is reproducible and content-addressed")
        line("=".repeat(72))
        line("After the fact, the run is reproducible and the task is identified by")
        line("the hash of its graph.")
        line()

        line("  The run replays bit-identically, with zero live IO")
        rule()
        val rp = lc.auditReplay
        line("  A recorded run (stamps read from the live clock) replays from its log")
        line("  under a clock that THROWS if read -- a clean replay proves zero live IO:")
        line("    replay touched no live IO = ${rp.replayTouchedNoLiveIo}")
        line("    trajectories identical    = ${rp.trajectoriesIdentical}")
        line("    outputs identical         = ${rp.outputsIdentical}")
        line("    replay == live != contrast= ${rp.replayMatchesLiveButContrastDiffers}")
        line("  A fresh live run under a different clock genuinely differs; replay does")
        line("  not. A conventional service cannot promise this: effects are implicit,")
        line("  so a second pass reads fresh clocks/IO and diverges.")
        line()

        line("  The task is admitted-once and re-run by hash")
        rule()
        val s4 = lc.auditByHash
        line("  The task is ingested into a persistent store (admitted and verified")
        line("  once), then re-run by its root hash with no re-verification:")
        line("    root hash                 = ${s4.rootHashHex.take(20)}...")
        line("    verdict cached at ingest  = ${s4.verdictCachedAfterIngest}")
        line("    first-run value           = ${s4.firstRunValue}")
        line("    run-by-hash value         = ${s4.byHashRunValue}")
        line("    identical result          = ${s4.identicalResult}")
        line("  The task IS its content hash -- the same graph always runs the same way.")
        line()

        line("=".repeat(72))
        line("The platform thesis, demonstrated: across the whole lifecycle of running")
        line("untrusted, agent-generated code, the maximum harm is COMPUTABLE from the")
        line("artifact before execution (ADMIT) and BOUNDED during it (RUN, VERIFY,")
        line("WITHSTAND), and the run is reproducible and content-addressed (AUDIT).")
        line("No 'LLM-writes-Python-in-a-sandbox' stack can offer this: a sandbox")
        line("watches a running process, while Strand reads a graph that cannot express")
        line("the harm it does not declare.")
        line()
        line("Honest scope: this COMPOSES proven demo scenarios -- the 'single task' is")
        line("a narrative frame over the suite's mechanisms, each of which isolates its")
        line("own subject with deterministic mocks/stand-ins and hand-authored programs.")
        line("It is NOT first-pass correctness or inference cost (the deferred Run 8")
        line("study). Every property is one the shipped runtime enforces; the assertion")
        line("net (AgentPlatformDemoTest) pins each stage in lifecycle order.")
        line("=".repeat(72))

        print(out)
    }
}
