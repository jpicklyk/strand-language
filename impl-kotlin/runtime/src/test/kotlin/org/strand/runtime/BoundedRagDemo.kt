package org.strand.runtime

import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.DenialReport
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.HttpRequest
import org.strand.interpreter.HttpResponse
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.LlmHttpClient
import org.strand.interpreter.StaticCredentialProvider
import org.strand.interpreter.Value
import org.strand.interpreter.VectorHttpTransport
import org.strand.verifier.VerifyResult

/**
 * Bounded retrieval-augmented generation, demonstrated.
 *
 * The canonical agent pattern — embed the query, retrieve from a vector index,
 * generate an answer from the retrieved context — expressed as a verified Strand
 * graph whose effect closure is `{LLM.Embed, Vector.Read, LLM.Generate}`, with
 * the data-access capability `Vector.Read` *refined to a specific store*
 * (`Vector.Read{provider=pinecone, store=corp-kb}`). The index an agent may
 * reach is not a configuration string the code can overwrite at will — it is a
 * declared, verifier-surfaced, refinement-checked property of the artifact. A
 * query against any other index is denied at the foreign-call boundary, before
 * a request leaves the process.
 *
 * Both transports are deterministic mocks injected through the Q-054
 * `HostPolicy`: a mock `LlmHttpClient` (serving the OpenAI embeddings response
 * and the Anthropic completion) and a mock `VectorHttpTransport` (serving the
 * Pinecone query matches). No real network I/O. The program is hand-authored
 * canonical dag-json (see `demos/bounded-rag/programs/`), so the demonstration
 * isolates the *containment* property — provable, bounded data access — from the
 * separate agent-generation question (first-pass correctness, cost; the deferred
 * Q-021 Run 8 study).
 *
 * Two scenarios:
 *
 *  - **R1 Bounded RAG run (keystone).** The embed → query(store=corp-kb) →
 *    generate pipeline runs under the mock transports. The host reads the
 *    program's surfaced effect closure (Q-067) off `VerifyResult.Ok` —
 *    `{LLM.Embed, Vector.Read, LLM.Generate}` — and prints it as the agent's
 *    *data-access manifest*. It grants exactly those categories, with
 *    `Vector.Read` refined to `{pinecone, corp-kb}`, and the program produces a
 *    generated answer alongside the retrieved hits.
 *  - **R2 Refined-store denial (the AI-specific security bit).** A variant that
 *    opens `corp-kb` (granted) but then issues its retrieval query declaring
 *    `Vector.Read{pinecone, hr-private}`. Under the grant refined to `corp-kb`,
 *    the runtime denies that read at the foreign-call boundary with a
 *    `RefinementViolation` carrying a structured `DenialReport` that names the
 *    requested store versus the held grant. The agent can read only the index it
 *    was granted.
 *
 * The scenario methods return structured results that both [main] (the
 * transcript) and `BoundedRagDemoTest` (the assertions) consume, so the printed
 * demonstration and the regression net share one body of code — the discipline
 * `ContainmentDemo` and `AgentWorkflowDemo` follow.
 */
object BoundedRagDemo {

    // ------------------------------------------------------------------
    // Program loading — the host admits the canonical dag-json artifact.
    // ------------------------------------------------------------------

    fun loadProgramJson(name: String): String =
        BoundedRagDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    fun nameMap(json: String): Map<String, NodeId> = JsonIngest.parse(json).nameMap

    // ------------------------------------------------------------------
    // The deterministic transports — mock LLM client + mock vector wire.
    // ------------------------------------------------------------------

    /**
     * A mock [LlmHttpClient] serving both the embedding call (OpenAI
     * `/embeddings`) and the completion call (Anthropic `/messages`). Routing is
     * by URL substring so a single transport drives the whole pipeline. No real
     * network I/O. This mirrors the technique the per-provider LLM provider tests
     * use; it is reproduced here because `:interpreter`'s `RecordingHttpClient`
     * is `internal` and off `:runtime`'s classpath.
     */
    class MockLlmTransport(
        /** The canned 3-dimensional embedding the OpenAI embed call returns. */
        private val embedding: List<Double> = listOf(0.10, 0.20, -0.05),
        /** The canned completion text the Anthropic generate call returns. */
        private val completion: String = "Travel is reimbursed up to \$75/day with receipts (per the retrieved policy).",
    ) : LlmHttpClient {
        var embedCalls: Int = 0
            private set
        var generateCalls: Int = 0
            private set

        override fun post(
            url: String,
            headers: List<Pair<String, String>>,
            body: ByteArray,
        ): LlmHttpClient.HttpResponse = when {
            "embeddings" in url -> {
                embedCalls++
                val arr = embedding.joinToString(", ")
                LlmHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "data": [{"object": "embedding", "embedding": [$arr], "index": 0}],
                      "model": "text-embedding-3-small"
                    }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
            }
            else -> {
                generateCalls++
                LlmHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "content": [{"type": "text", "text": "$completion"}],
                      "stop_reason": "end_turn",
                      "usage": {"input_tokens": 18, "output_tokens": 11}
                    }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
            }
        }
    }

    /**
     * A mock [VectorHttpTransport] serving canned Pinecone query matches. The
     * Pinecone provider issues `POST <base>/query`; this returns two matches with
     * scores and metadata, so the retrieval stage produces real hits the pipeline
     * threads into its output. No real network I/O.
     */
    class MockVectorTransport(
        private val matchesJson: String = """
            {
              "matches": [
                {"id": "kb-doc-17", "score": 0.91, "metadata": {"title": "Travel & Expense Policy"}},
                {"id": "kb-doc-42", "score": 0.78, "metadata": {"title": "Reimbursement FAQ"}}
              ]
            }
        """.trimIndent(),
    ) : VectorHttpTransport {
        var queryCalls: Int = 0
            private set

        override fun execute(request: HttpRequest): HttpResponse {
            if (request.url.endsWith("/query")) {
                queryCalls++
                return HttpResponse(200, matchesJson.toByteArray(Charsets.UTF_8))
            }
            // Open issues no HTTP request; any other endpoint is a benign 200.
            return HttpResponse(200, "{}".toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * A host policy wiring both mock transports plus the credentials the provider
     * libraries gate on (the mocks never reach the network, but the credential
     * gate runs before a request would be issued). The base is [HostPolicy.OPEN]:
     * the bound is the granted [CapabilitySet], not a sandbox.
     */
    private fun policyWith(llm: LlmHttpClient, vec: VectorHttpTransport): HostPolicy =
        HostPolicy.OPEN.copy(
            llmHttpClient = llm,
            vectorHttpTransport = vec,
            credentialProvider = StaticCredentialProvider(mapOf(
                "openai" to "sk-openai-demo-key-aaaaaaaa",
                "anthropic" to "sk-ant-demo-key-bbbbbbbb",
                "pinecone" to "pc-demo-key-cccccccc",
            )),
        )

    // ------------------------------------------------------------------
    // The surfaced bound — read off the artifact, before running.
    // ------------------------------------------------------------------

    /**
     * The verifier-computed effect closure of [program]'s root, read directly
     * from a successful [verify] (`VerifyResult.Ok.rootClosure`, Q-067) and
     * rendered to `EffectCategory` names. This is `closure(g)` in the Q-044 harm
     * bound exactly as the verifier enforced it — the machine-checked statement
     * of everything the agent program can reach, available before any execution.
     * For bounded RAG this is the agent's data-access manifest.
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

    /** Count the hits in a `List<QueryHit>` Cons/Nil SumV chain. */
    fun countHits(retrieved: Value?): Int {
        var n = 0
        var cur: Value? = retrieved
        while (cur is Value.SumV && cur.case == "Cons") {
            n++
            cur = (cur.payload as? Value.ProductV)?.fields?.get("tail")
        }
        return n
    }

    /** The id of the first retrieved hit (or "(none)" for an empty result). */
    fun firstHitId(retrieved: Value?): String {
        val cons = retrieved as? Value.SumV ?: return "(none)"
        if (cons.case != "Cons") return "(none)"
        val cell = cons.payload as? Value.ProductV ?: return "(none)"
        val head = cell.fields["head"] as? Value.ProductV ?: return "(none)"
        return (head.fields["id"] as? Value.StringV)?.v ?: "(none)"
    }

    /** The first text block of the generated answer (the `answer.content` head). */
    fun answerText(value: Value?): String {
        val product = value as? Value.ProductV ?: return "(no result)"
        val answer = product.fields["answer"] as? Value.ProductV ?: return "(no answer)"
        val content = answer.fields["content"] as? Value.SumV ?: return "(no content)"
        if (content.case != "Cons") return "(no content)"
        val cell = content.payload as? Value.ProductV ?: return "(no content)"
        val head = cell.fields["head"] as? Value.SumV ?: return "(no content)"
        return when (head.case) {
            "Text" -> (head.payload as? Value.StringV)?.v ?: "(empty text)"
            else -> "(${head.case})"
        }
    }

    // ==================================================================
    // R1 — Bounded RAG run
    // ==================================================================

    data class R1Result(
        /** The surfaced effect closure read off VerifyResult.Ok before running. */
        val surfacedClosure: Set<String>,
        /** The category names the grant permits. */
        val grantedCategories: Set<String>,
        /** Whether the program verified clean. */
        val verifiedClean: Boolean,
        /** The whole produced value (the {retrieved, answer} product) or null. */
        val resultValue: Value?,
        /** The number of hits the retrieval stage returned. */
        val retrievedHitCount: Int,
        /** The id of the first retrieved hit. */
        val firstHit: String,
        /** The generated answer's first text block. */
        val answerText: String,
        /** Transport call counts — proof each stage actually ran. */
        val embedCalls: Int,
        val queryCalls: Int,
        val generateCalls: Int,
    ) {
        /** The grant covers exactly the surfaced closure — the bound is tight. */
        val grantMatchesClosure: Boolean get() = grantedCategories == surfacedClosure
        val completed: Boolean get() = resultValue != null
        val allThreeStagesRan: Boolean get() = embedCalls == 1 && queryCalls == 1 && generateCalls == 1
    }

    /**
     * R1: verify the RAG program, read its surfaced effect closure off the Ok
     * result (the data-access manifest), grant exactly those categories with
     * `Vector.Read` refined to `{pinecone, corp-kb}`, and run the embed → query →
     * generate pipeline under the deterministic mocks. The query vector is the
     * real embed output; the retrieval returns hits; the generation produces an
     * answer; the result bundles the retrieved hits with the generated answer.
     */
    fun scenarioR1(): R1Result {
        val json = loadProgramJson("bounded-rag")
        val program = image(json)
        val names = nameMap(json)

        val llm = MockLlmTransport()
        val vec = MockVectorTransport()
        val host = StrandRuntime(policyWith(llm, vec))

        val verify = host.verify(program)
        val verifiedClean = verify is VerifyResult.Ok
        val closure = (verify as? VerifyResult.Ok)?.let { surfacedClosure(program, it) } ?: emptySet()

        // Grant exactly the surfaced closure: LLM.Embed (any), Vector.Read refined
        // to {pinecone, corp-kb}, LLM.Generate (any). The vector capability is the
        // load-bearing refinement — it pins the agent to one index.
        val llmEmbedFx = names.getValue("llmEmbedFx")
        val vectorReadFx = names.getValue("vectorReadFx")
        val llmGenFx = names.getValue("llmGenFx")
        val grant = CapabilitySet(
            mapOf(
                llmEmbedFx to listOf(CapabilityPattern(listOf(
                    CapabilityArgument.Wildcard, CapabilityArgument.Wildcard,
                ))),
                vectorReadFx to listOf(CapabilityPattern(listOf(
                    CapabilityArgument.Concrete(Value.StringV("pinecone")),
                    CapabilityArgument.Concrete(Value.StringV("corp-kb")),
                ))),
                llmGenFx to listOf(CapabilityPattern(listOf(
                    CapabilityArgument.Wildcard, CapabilityArgument.Wildcard,
                ))),
            ),
        )
        val grantedCategories = setOf(llmEmbedFx, vectorReadFx, llmGenFx)
            .map { (program.store.getOrNull(it) as Node.EffectCategory).categoryName }
            .toSortedSet()

        val outcome = host.run(program, grant)
        val value = (outcome as? RunOutcome.Ok)?.value
        val retrieved = (value as? Value.ProductV)?.fields?.get("retrieved")

        return R1Result(
            surfacedClosure = closure,
            grantedCategories = grantedCategories,
            verifiedClean = verifiedClean,
            resultValue = value,
            retrievedHitCount = countHits(retrieved),
            firstHit = firstHitId(retrieved),
            answerText = answerText(value),
            embedCalls = llm.embedCalls,
            queryCalls = vec.queryCalls,
            generateCalls = llm.generateCalls,
        )
    }

    // ==================================================================
    // R2 — Refined-store denial
    // ==================================================================

    data class R2Result(
        /** The surfaced closure of the variant (still {LLM.Embed, Vector.Read, LLM.Generate}). */
        val surfacedClosure: Set<String>,
        /** Whether the program verified clean (it does — the bound looks benign). */
        val verifiedClean: Boolean,
        /** The structured denial captured at the foreign-call boundary. */
        val denialReport: DenialReport?,
        /** The raw error the runtime raised (for class-name display). */
        val denialError: InterpretError?,
        /** Calls that ran before the denial — embed + open succeed, query is denied. */
        val embedCalls: Int,
        val queryCalls: Int,
    ) {
        val denied: Boolean
            get() = denialError is InterpretError.RefinementViolation
        /** The query never reaches the wire — denial is before the request leaves the process. */
        val queryNeverIssued: Boolean get() = queryCalls == 0
    }

    /**
     * R2: run the hr-private variant under a grant whose `Vector.Read` is refined
     * to `{pinecone, corp-kb}`. The pipeline embeds and opens `corp-kb` (both
     * granted), then issues its retrieval query declaring `Vector.Read{pinecone,
     * hr-private}` — a store the grant does not cover. At the foreign-call
     * boundary the runtime denies the read with a `RefinementViolation` carrying
     * a `DenialReport` naming the requested store (`hr-private`) against the held
     * grant (`corp-kb`). The denial is before any request reaches the vector wire.
     */
    fun scenarioR2(): R2Result {
        val json = loadProgramJson("bounded-rag-hr")
        val program = image(json)
        val names = nameMap(json)

        val llm = MockLlmTransport()
        val vec = MockVectorTransport()
        val host = StrandRuntime(policyWith(llm, vec))

        val verify = host.verify(program)
        val verifiedClean = verify is VerifyResult.Ok
        val closure = (verify as? VerifyResult.Ok)?.let { surfacedClosure(program, it) } ?: emptySet()

        // Grant Vector.Read refined to {pinecone, corp-kb}; embed and generate
        // wildcard. The hr-private query is beyond the refined grant.
        val llmEmbedFx = names.getValue("llmEmbedFx")
        val vectorReadFx = names.getValue("vectorReadFx")
        val llmGenFx = names.getValue("llmGenFx")
        val grant = CapabilitySet(
            mapOf(
                llmEmbedFx to listOf(CapabilityPattern(listOf(
                    CapabilityArgument.Wildcard, CapabilityArgument.Wildcard,
                ))),
                vectorReadFx to listOf(CapabilityPattern(listOf(
                    CapabilityArgument.Concrete(Value.StringV("pinecone")),
                    CapabilityArgument.Concrete(Value.StringV("corp-kb")),
                ))),
                llmGenFx to listOf(CapabilityPattern(listOf(
                    CapabilityArgument.Wildcard, CapabilityArgument.Wildcard,
                ))),
            ),
        )

        val outcome = runCatching { host.run(program, grant) }
        val ex = outcome.exceptionOrNull() as? InterpretException
        val error = ex?.error
        val report = when (error) {
            is InterpretError.RefinementViolation -> error.report
            is InterpretError.CapabilityViolation -> error.report
            else -> null
        }

        return R2Result(
            surfacedClosure = closure,
            verifiedClean = verifiedClean,
            denialReport = report,
            denialError = error,
            embedCalls = llm.embedCalls,
            queryCalls = vec.queryCalls,
        )
    }

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)

        line("=".repeat(72))
        line("Strand -- bounded retrieval-augmented generation (RAG).")
        line("embed -> vector-query(store=corp-kb) -> generate, as a verified graph")
        line("whose data-access capability is refined to one index. Transports are")
        line("deterministic mocks; no real network I/O.")
        line("=".repeat(72))
        line()

        // --- R1 ---
        line("R1  Bounded RAG run -- the agent's data-access manifest is the closure")
        line("-".repeat(72))
        val r1 = scenarioR1()
        line("  Program: bounded-rag (embed the query, retrieve from the corp-kb")
        line("           index, generate an answer from the retrieved context).")
        line("  The data-access manifest is read off the verified artifact BEFORE")
        line("  running -- the surfaced effect closure (Q-067):")
        line("    surfaced closure (manifest) = ${r1.surfacedClosure}")
        line("    granted categories          = ${r1.grantedCategories}")
        line("    grant covers exactly it     = ${r1.grantMatchesClosure}")
        line("  Vector.Read is granted refined to {provider=pinecone, store=corp-kb}")
        line("  -- the agent provably can query only that index.")
        line("  Host runs the pipeline under that grant with the deterministic mocks:")
        line("    verified clean              = ${r1.verifiedClean}")
        line("    embed / query / generate    = ${r1.embedCalls} / ${r1.queryCalls} / ${r1.generateCalls}")
        line("    all three stages ran        = ${r1.allThreeStagesRan}")
        line("    retrieved hits              = ${r1.retrievedHitCount} (first: ${r1.firstHit})")
        line("    completed                   = ${r1.completed}")
        line("    generated answer            = \"${r1.answerText}\"")
        line("  The query vector is the real embed output; retrieval returns real")
        line("  hits; the output bundles the retrieved context with the answer.")
        line()

        // --- R2 ---
        line("R2  Refined-store denial -- the agent can read only the granted index")
        line("-".repeat(72))
        val r2 = scenarioR2()
        line("  Program: bounded-rag-hr (opens corp-kb, then issues its retrieval")
        line("           query declaring Vector.Read{pinecone, hr-private}).")
        line("  Same surfaced closure as R1 -- the manifest is identical at the")
        line("  category level; the refinement is where the bound lives:")
        line("    surfaced closure (manifest) = ${r2.surfacedClosure}")
        line("    verified clean              = ${r2.verifiedClean}")
        line("  Host grants Vector.Read refined to corp-kb. The embed and the open")
        line("  of corp-kb succeed; the hr-private query is beyond the refined grant:")
        line("    embed calls before denial   = ${r2.embedCalls}")
        line("    query reached the wire      = ${!r2.queryNeverIssued} " +
            "(query HTTP calls = ${r2.queryCalls})")
        line("    denied                      = ${r2.denied} (${r2.denialError?.let { it::class.simpleName }})")
        if (r2.denialReport != null) {
            line("    DenialReport: ${renderDenial(r2.denialReport)}")
        } else {
            line("    DenialReport: (none -- unexpected) ${r2.denialError}")
        }
        line("  The read is denied at the foreign-call boundary, before any request")
        line("  leaves the process. The index an agent may reach is a refinement-")
        line("  checked capability, not a config string the code can overwrite.")
        line()

        line("=".repeat(72))
        line("What this demonstrates: bounded, provable data access for the canonical")
        line("agent pattern -- the index an agent can query is a verifier-surfaced,")
        line("refinement-checked capability. NOT first-pass correctness or inference")
        line("cost (the deferred Run 8 study); the transports are mocks and the")
        line("program is hand-authored. The refined-store guarantee rests on")
        line("Vector.Read being refined by {provider, store}.")
        line("=".repeat(72))

        print(out)
    }
}
