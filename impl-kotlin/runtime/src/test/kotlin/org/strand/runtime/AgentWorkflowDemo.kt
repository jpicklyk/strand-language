package org.strand.runtime

import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.DenialReport
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.LlmHttpClient
import org.strand.interpreter.StaticCredentialProvider
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.core.Node

/**
 * A bounded agent workflow, demonstrated.
 *
 * This is the keystone demonstration that puts Strand's AI-native primitives on
 * screen: a tool-using agent loop running under a *visible, machine-checked
 * effect bound*. The subject is a Strand program that calls a per-provider LLM
 * ForeignNode (`Anthropic.Messages.Create`, declaring E-035 `LLM.Generate`)
 * carrying a tool the model may invoke. The LLM transport is a mock
 * `LlmHttpClient` injected through the Q-054 `HostPolicy.copy(llmHttpClient =
 * ...)`, so the run is deterministic and performs no real network I/O — the
 * containment properties, not the agent-generation question, are the subject.
 *
 * The agent program is hand-authored canonical dag-json (see
 * `demos/agent-workflow/programs/`), isolating the *host's* containment of
 * useful agent work from the separate question of how an agent generates a
 * program (the deferred Q-021 Run 8 dynamic study).
 *
 * Three scenarios:
 *
 *  - **A1 Bounded run (keystone).** The agent workflow runs to completion under
 *    a `CapabilitySet` granting exactly the categories its *surfaced effect
 *    closure* (Q-067 `VerifyResult.Ok.rootClosure`) declares. The host prints
 *    the surfaced closure — `{LLM.Generate}` — as the machine-checked statement
 *    of everything the agent can reach, read off the artifact *before* running.
 *    The mock returns a deterministic completion and the program produces its
 *    `GenerateResult`. Useful agent work under a bound readable from the
 *    artifact.
 *  - **A2 Over-reach contained.** A variant whose tool, when the model invokes
 *    it, reaches `Filesystem.Write` — an effect the host did *not* grant (the
 *    grant is `{LLM.Generate}` only). The mock drives the model to call the
 *    tool; at the foreign-call boundary inside the tool-dispatch loop the
 *    runtime denies the write with a structured Q-064 `DenialReport`. This is
 *    the prompt-injection-contained story: the static bound looks identical to
 *    A1's (the ToolDef's effects are deliberately *not* in the root closure —
 *    they fire at dispatch and are checked against the live grant), yet the
 *    ungranted reach is denied at runtime.
 *  - **A3 Deterministic re-run (scoped).** The mock is fully deterministic, so
 *    running A1 twice under the same injected transport yields a bit-identical
 *    `GenerateResult`. This is the honest, narrow form of the replay claim: it
 *    demonstrates that the workflow is a pure function of (program, grant, mock
 *    transport), *not* a full state-machine snapshot/replay (that is the
 *    `replay-timetravel` demonstration's R1). The claim is scoped explicitly so
 *    nothing is overstated.
 *
 * The scenario methods return structured results that both [main] (the
 * transcript) and `AgentWorkflowDemoTest` (the assertions) consume, so the
 * printed demonstration and the regression net share one body of code — the
 * discipline `ContainmentDemo` and `ReplayDemo` follow.
 */
object AgentWorkflowDemo {

    // ------------------------------------------------------------------
    // Program loading — the host admits the canonical dag-json artifact.
    // ------------------------------------------------------------------

    fun loadProgramJson(name: String): String =
        AgentWorkflowDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText() ?: error("demo program /demo/programs/$name.json not on test classpath")

    fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    fun nameMap(json: String): Map<String, NodeId> = JsonIngest.parse(json).nameMap

    // ------------------------------------------------------------------
    // The deterministic LLM transport — a mock LlmHttpClient.
    // ------------------------------------------------------------------

    /**
     * A mock [LlmHttpClient] that returns canned Anthropic Messages API JSON,
     * one response per `post` call from [queue] (or a fixed [single] response
     * for single-shot turns). No real network I/O. This is the same technique
     * the per-provider LLM provider tests (`BuiltinsAnthropicTest`) use; it is
     * reproduced here because the interpreter module's `RecordingHttpClient` is
     * `internal` and not on `:runtime`'s classpath.
     *
     * Injected via `HostPolicy.OPEN.copy(llmHttpClient = mock)`, so the program
     * the host runs reaches this transport instead of the default
     * `DefaultLlmHttpClient`, and the agent loop is reproducible.
     */
    class MockLlmTransport(
        private val single: LlmHttpClient.HttpResponse? = null,
        responses: List<LlmHttpClient.HttpResponse> = emptyList(),
    ) : LlmHttpClient {
        private val queue = ArrayDeque(responses)
        var callCount: Int = 0
            private set

        override fun post(
            url: String,
            headers: List<Pair<String, String>>,
            body: ByteArray,
        ): LlmHttpClient.HttpResponse {
            callCount++
            return single ?: queue.removeFirstOrNull()
                ?: error("MockLlmTransport: no response configured for call $callCount")
        }
    }

    /** A canned Anthropic response with one text block and end_turn. */
    private fun textResponse(text: String): LlmHttpClient.HttpResponse =
        LlmHttpClient.HttpResponse(
            200,
            """
            {
              "content": [{"type": "text", "text": "$text"}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 12, "output_tokens": 7}
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

    /**
     * A canned Anthropic response that requests the named tool (stop_reason
     * `tool_use`), driving the builtin's tool-dispatch loop to invoke the
     * program's ToolDef implementation.
     */
    private fun toolUseResponse(toolName: String): LlmHttpClient.HttpResponse =
        LlmHttpClient.HttpResponse(
            200,
            """
            {
              "content": [{"type": "tool_use", "id": "tu_1", "name": "$toolName", "input": {"city": "SF"}}],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 9, "output_tokens": 3}
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

    /**
     * A host policy with the mock transport and a static credential for the
     * `anthropic` provider (the provider library requires a key before it
     * issues a request — the mock never sees the network, but the credential
     * gate runs). The base is [HostPolicy.OPEN]: the bound is the granted
     * `CapabilitySet`, not a sandbox.
     */
    private fun policyWith(mock: LlmHttpClient): HostPolicy =
        HostPolicy.OPEN.copy(
            llmHttpClient = mock,
            credentialProvider = StaticCredentialProvider(mapOf("anthropic" to "sk-ant-demo-key")),
        )

    // ------------------------------------------------------------------
    // The surfaced bound — read off the artifact, before running.
    // ------------------------------------------------------------------

    /**
     * The verifier-computed effect closure of [program]'s root, read directly
     * from a successful [verify] (`VerifyResult.Ok.rootClosure`, Q-067) and
     * rendered to `EffectCategory` names. This is `closure(g)` in the Q-044 harm
     * bound, exactly as the verifier enforced it — the machine-checked statement
     * of everything the agent program can reach, available before any execution.
     */
    fun surfacedClosure(program: ProgramImage, verify: VerifyResult.Ok): Set<String> =
        verify.rootClosure(program.root)
            .mapNotNull { (program.store.getOrNull(it) as? Node.EffectCategory)?.categoryName }
            .toSortedSet()

    /** Render a [DenialReport] the way an orchestrating principal sees it. */
    fun renderDenial(report: DenialReport): String = buildString {
        append("category=").append(report.category)
        append(" requested=").append(report.requested ?: "(withheld)")
        append(" held=").append(report.held ?: "(withheld)")
        append(" node=").append(report.node?.toString() ?: "null")
        append(" phase=").append(report.phase.wire)
    }

    /** A compact rendering of a GenerateResult's first content block. */
    fun firstTextBlock(result: Value): String {
        val content = (result as Value.ProductV).fields.getValue("content")
        val cons = content as Value.SumV
        if (cons.case != "Cons") return "(no content)"
        val cell = cons.payload as Value.ProductV
        val head = cell.fields.getValue("head") as Value.SumV
        return when (head.case) {
            "Text" -> (head.payload as Value.StringV).v
            else -> "(${head.case})"
        }
    }

    // ==================================================================
    // A1 — Bounded run (keystone)
    // ==================================================================

    data class A1Result(
        /** The surfaced effect closure read off VerifyResult.Ok before running. */
        val surfacedClosure: Set<String>,
        /** The category names the grant permits. */
        val grantedCategories: Set<String>,
        /** Whether the program verified clean. */
        val verifiedClean: Boolean,
        /** The produced GenerateResult value (or null if the run did not complete). */
        val resultValue: Value?,
        /** The first text block of the produced result (for the transcript). */
        val resultText: String,
        /** How many times the LLM transport was called (one turn here). */
        val llmCallCount: Int,
    ) {
        /** The grant covers exactly the surfaced closure — the bound is tight. */
        val grantMatchesClosure: Boolean get() = grantedCategories == surfacedClosure
        val completed: Boolean get() = resultValue != null
    }

    /**
     * A1: verify the agent program, read its surfaced effect closure off the Ok
     * result, grant exactly those categories, and run it under the deterministic
     * mock. The LLM returns a single text completion; the program produces its
     * `GenerateResult`. The bound — `{LLM.Generate}` — is read from the artifact
     * before the run, and the grant covers exactly it.
     */
    fun scenarioA1(): A1Result {
        val json = loadProgramJson("agent-loop")
        val program = image(json)
        val names = nameMap(json)

        val host = StrandRuntime(policyWith(MockLlmTransport(single = textResponse("It is sunny and 72F."))))
        val verify = host.verify(program)
        val verifiedClean = verify is VerifyResult.Ok
        val closure = (verify as? VerifyResult.Ok)?.let { surfacedClosure(program, it) } ?: emptySet()

        // Grant exactly the surfaced closure: LLM.Generate.
        val llmGenFx = names.getValue("llmGenFx")
        val grant = CapabilitySet.ofCategories(setOf(llmGenFx))
        val grantedCategories = setOf((program.store.getOrNull(llmGenFx) as Node.EffectCategory).categoryName)

        val mock = MockLlmTransport(single = textResponse("It is sunny and 72F."))
        val runHost = StrandRuntime(policyWith(mock))
        val outcome = runHost.run(program, grant)
        val value = (outcome as? RunOutcome.Ok)?.value

        return A1Result(
            surfacedClosure = closure,
            grantedCategories = grantedCategories,
            verifiedClean = verifiedClean,
            resultValue = value,
            resultText = value?.let { firstTextBlock(it) } ?: "(no result)",
            llmCallCount = mock.callCount,
        )
    }

    // ==================================================================
    // A2 — Over-reach contained
    // ==================================================================

    data class A2Result(
        /** The surfaced closure of the over-reach program (still {LLM.Generate}). */
        val surfacedClosure: Set<String>,
        /** Whether the program verified clean (it does — the bound looks benign). */
        val verifiedClean: Boolean,
        /** The structured denial outcome captured at the foreign-call boundary. */
        val denialReport: DenialReport?,
        /** The raw error the runtime raised (for class-name display). */
        val denialError: InterpretError?,
        /** How many LLM turns ran before the tool-dispatch denial. */
        val llmCallCount: Int,
    ) {
        val denied: Boolean
            get() = denialError is InterpretError.CapabilityViolation ||
                denialError is InterpretError.RefinementViolation
    }

    /**
     * A2: run the over-reach variant under a grant of `{LLM.Generate}` only. The
     * mock drives the model to invoke the program's tool (stop_reason
     * `tool_use`); the tool's implementation reaches `Filesystem.Write`, a
     * category absent from the grant. At the foreign-call boundary inside the
     * tool-dispatch loop the runtime denies the write with a structured
     * `CapabilityViolation` carrying a `DenialReport`. The host captures and
     * renders it.
     */
    fun scenarioA2(): A2Result {
        val json = loadProgramJson("agent-loop-overreach")
        val program = image(json)
        val names = nameMap(json)

        val verify = StrandRuntime(policyWith(MockLlmTransport())).verify(program)
        val verifiedClean = verify is VerifyResult.Ok
        val closure = (verify as? VerifyResult.Ok)?.let { surfacedClosure(program, it) } ?: emptySet()

        // Grant LLM.Generate only — the tool's Filesystem.Write is beyond it.
        val llmGenFx = names.getValue("llmGenFx")
        val grant = CapabilitySet.ofCategories(setOf(llmGenFx))

        // The model asks for the tool on the first turn, which triggers the
        // tool-dispatch loop and the denied write. A trailing end_turn is
        // queued in case the loop somehow continues; it should not be reached.
        val mock = MockLlmTransport(
            responses = listOf(
                toolUseResponse("get_weather"),
                textResponse("done"),
            ),
        )
        val host = StrandRuntime(policyWith(mock))
        val outcome = runCatching { host.run(program, grant) }
        val ex = outcome.exceptionOrNull() as? InterpretException
        val error = ex?.error
        val report = when (error) {
            is InterpretError.CapabilityViolation -> error.report
            is InterpretError.RefinementViolation -> error.report
            else -> null
        }

        return A2Result(
            surfacedClosure = closure,
            verifiedClean = verifiedClean,
            denialReport = report,
            denialError = error,
            llmCallCount = mock.callCount,
        )
    }

    // ==================================================================
    // A3 — Deterministic re-run (scoped)
    // ==================================================================

    data class A3Result(
        val firstResult: Value?,
        val secondResult: Value?,
    ) {
        val identical: Boolean get() = firstResult != null && firstResult == secondResult
    }

    /**
     * A3: run A1 twice under the same deterministic mock transport and assert the
     * two `GenerateResult` values are bit-identical. The workflow is a pure
     * function of (program, grant, mock transport), so re-running reproduces the
     * result exactly. This is the *scoped* replay claim — same-mock determinism,
     * not a full state-machine snapshot/replay (that is the `replay-timetravel`
     * R1 demonstration over a stateful machine).
     */
    fun scenarioA3(): A3Result {
        val json = loadProgramJson("agent-loop")
        val program = image(json)
        val names = nameMap(json)
        val grant = CapabilitySet.ofCategories(setOf(names.getValue("llmGenFx")))

        fun runOnce(): Value? {
            val mock = MockLlmTransport(single = textResponse("It is sunny and 72F."))
            val outcome = StrandRuntime(policyWith(mock)).run(program, grant)
            return (outcome as? RunOutcome.Ok)?.value
        }

        return A3Result(firstResult = runOnce(), secondResult = runOnce())
    }

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)

        line("=".repeat(72))
        line("Strand -- a bounded agent workflow (tool-using agent under an")
        line("effect bound readable from the artifact before it runs).")
        line("LLM transport is a deterministic mock; no real network I/O.")
        line("=".repeat(72))
        line()

        // --- A1 ---
        line("A1  Bounded run -- useful agent work under a visible, checked bound")
        line("-".repeat(72))
        val a1 = scenarioA1()
        line("  Program: agent-loop (calls Anthropic.Messages.Create carrying a")
        line("           pure get_weather tool the model may invoke).")
        line("  The bound is read off the verified artifact BEFORE running:")
        line("    surfaced closure (Q-067)  = ${a1.surfacedClosure}")
        line("    granted categories        = ${a1.grantedCategories}")
        line("    grant covers exactly it   = ${a1.grantMatchesClosure}")
        line("  Host runs under that grant with the deterministic mock LLM:")
        line("    verified clean            = ${a1.verifiedClean}")
        line("    LLM turns                 = ${a1.llmCallCount}")
        line("    completed                 = ${a1.completed}")
        line("    result (first text block) = \"${a1.resultText}\"")
        line("  Everything the agent can reach is the surfaced closure -- a")
        line("  machine-checked statement, not a promise. The grant is tight.")
        line()

        // --- A2 ---
        line("A2  Over-reach contained -- ungranted tool effect denied at dispatch")
        line("-".repeat(72))
        val a2 = scenarioA2()
        line("  Program: agent-loop-overreach (same shape, but get_weather's")
        line("           implementation reaches Filesystem.Write on /etc/shadow).")
        line("  The static bound looks identical to A1 -- the ToolDef's effects")
        line("  are NOT in the root closure; they fire at dispatch and are checked")
        line("  against the live grant:")
        line("    surfaced closure (Q-067)  = ${a2.surfacedClosure}")
        line("    verified clean            = ${a2.verifiedClean}")
        line("  Host grants {LLM.Generate} only. The mock drives the model to")
        line("  invoke the tool; the tool's write is beyond the grant:")
        line("    LLM turns before denial   = ${a2.llmCallCount}")
        line("    denied                    = ${a2.denied} (${a2.denialError?.let { it::class.simpleName }})")
        if (a2.denialReport != null) {
            line("    DenialReport: ${renderDenial(a2.denialReport)}")
        } else {
            line("    DenialReport: (none -- unexpected) ${a2.denialError}")
        }
        line("  This is the prompt-injection-contained story: a tool reaching for")
        line("  an ungranted effect is stopped at the foreign-call boundary.")
        line()

        // --- A3 ---
        line("A3  Deterministic re-run (scoped) -- pure function of program+grant+mock")
        line("-".repeat(72))
        val a3 = scenarioA3()
        line("  Running A1 twice under the same deterministic mock transport")
        line("  reproduces the GenerateResult bit-identically:")
        line("    result 1 == result 2      = ${a3.identical}")
        line("    result (first text block) = \"${a3.firstResult?.let { firstTextBlock(it) }}\"")
        line("  Scope: this is same-mock determinism, NOT a state-machine")
        line("  snapshot/replay -- that is the replay-timetravel R1 demonstration.")
        line()

        line("=".repeat(72))
        line("What this demonstrates: a tool-using agent loop runs useful work")
        line("under a machine-checked effect bound readable from the artifact, and")
        line("an over-reaching tool is contained at the foreign-call boundary. NOT")
        line("first-pass correctness or inference cost (the deferred Run 8 study);")
        line("the LLM transport is a mock and the program is hand-authored.")
        line("=".repeat(72))

        print(out)
    }
}
