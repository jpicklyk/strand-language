package org.strand.runtime

import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult

/**
 * Handler-based LLM virtualization, demonstrated.
 *
 * The N-043 `Handler` is the one shipped language primitive no other demonstration
 * exercises, and it does something no conventional agent stack can: it intercepts
 * the effectful model call and replaces it, so the agent's LLM dependency
 * *disappears* from its effect closure. This is the closure-subtraction rule —
 * `closureOf(handler) = (closureOf(body) − {intercept}) ∪ closureOf(handle) ∪
 * handleFun.effects` — and Handler is the only node category that removes an
 * effect from a closure. Effect virtualization is the mechanism behind
 * deterministic agent testing, prompt-rewriting policy layers, response caching,
 * and budget enforcement, shown here as a verified language feature rather than a
 * framework convention.
 *
 * The subject is one agent body that calls a model under E-035-style
 * `LLM.Generate`, run two ways:
 *
 *  - **V1 Unhandled baseline.** The agent program calls the model ForeignNode
 *    directly. Its surfaced effect closure (Q-067 `rootClosure`) is
 *    `{LLM.Generate}`; the host grants exactly that and the program produces its
 *    result. This establishes the un-virtualized bound.
 *  - **V2 Handled (keystone).** The *same* model call, wrapped in a `Handler`
 *    intercepting `LLM.Generate` whose `handle` is a PURE function returning a
 *    canned result of the intercepted call's result type. The surfaced closure is
 *    now EMPTY — `LLM.Generate` subtracted by the verifier itself — so the
 *    program runs under `CapabilitySet.EMPTY` (no `LLM.Generate` granted) and
 *    STILL produces a result, deterministically, with the model transport never
 *    invoked. Virtualization removed the effect from the harm bound.
 *  - **V3 Framing.** The pure handle is a deterministic test double / policy
 *    substitution. The shipped semantics are no-continuation intercept-and-replace
 *    (the handler replaces the call wholesale; there is no `resume`), so this is
 *    the substitution case, not resumable algebraic effects.
 *
 * The "model" is a stand-in: a monomorphic effectful ForeignNode
 * `strand-builtin:Demo.LlmGenerate` typed `(String) -> String` under an
 * EffectCategory named `LLM.Generate`, installed as a host test builtin
 * ([Builtins.installTestBuiltin]). The brief sanctions this fallback over the
 * real per-provider `Anthropic.Messages.Create` ForeignNode (whose
 * `GenerateResult` product is impractical to hand-construct as a pure handle
 * value); the intercept and the closure-subtraction it produces are genuine
 * either way, and the narrative stays "virtualize the model call." Nothing is
 * staged — the empty surfaced closure and the empty-grant run are what the real
 * verifier and runtime produce.
 *
 * The scenario methods return structured results that both [main] (the transcript)
 * and `LlmVirtualizationDemoTest` (the assertions) consume, so the printed
 * demonstration and the regression net share one body of code — the discipline
 * `ContainmentDemo` and `AgentWorkflowDemo` follow.
 */
object LlmVirtualizationDemo {

    /** The stand-in model call's builtin target. */
    const val MODEL_TARGET = "strand-builtin:Demo.LlmGenerate"

    /** The deterministic completion the stand-in model returns when actually called (V1). */
    const val MODEL_COMPLETION = "The meeting is scheduled for 3pm today."

    /** The canned completion the pure handle returns in place of the model (V2). */
    const val VIRTUALIZED_COMPLETION = "Meeting is at 3pm."

    // ------------------------------------------------------------------
    // Program loading — the host admits the canonical dag-json artifact.
    // ------------------------------------------------------------------

    /**
     * Load a committed program (canonical dag-json) from the classpath. The
     * source of truth is `demos/llm-virtualization/programs/<name>.json`; the
     * `:runtime` build copies those onto the test classpath under `/demo/programs/`
     * (see `runtime/build.gradle.kts`), so this resource path is the classpath
     * namespace, not a filesystem location.
     */
    fun loadProgramJson(name: String): String =
        LlmVirtualizationDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    /** Finalize a program's authored dag-json to the host's [ProgramImage]. */
    fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    /** The author-id → NodeId map for a program (used to build the grant). */
    fun nameMap(json: String): Map<String, NodeId> = JsonIngest.parse(json).nameMap

    /**
     * Q-067: the verifier-computed effect closure of [program]'s root, read
     * directly from a successful [verify] (`VerifyResult.Ok.rootClosure`) and
     * rendered to `EffectCategory` names. This is `closure(g)` in the Q-044 harm
     * bound, exactly as the verifier enforced it — Handler-aware, so a Handler
     * that intercepts an effect subtracts it. Available before any execution.
     */
    fun surfacedClosure(program: ProgramImage, verify: VerifyResult.Ok): Set<String> =
        verify.rootClosure(program.root)
            .mapNotNull { (program.store.getOrNull(it) as? Node.EffectCategory)?.categoryName }
            .toSortedSet()

    /**
     * Install the stand-in model builtin. Effectful (it declares `LLM.Generate`),
     * so the capability check fires before the body runs; [Stateful][Builtins.Determinism.Stateful]
     * because a real model call depends on the world. It records each call so the
     * demonstration can assert the transport was never reached in the handled run.
     */
    private fun installModel(): java.util.concurrent.atomic.AtomicInteger {
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        Builtins.installTestBuiltin(
            MODEL_TARGET,
            effectful = true,
            determinism = Builtins.Determinism.Stateful,
            fn = Builtins.Fn { _, _ ->
                callCount.incrementAndGet()
                Value.StringV(MODEL_COMPLETION)
            },
        )
        return callCount
    }

    private fun textOf(value: Value?): String = (value as? Value.StringV)?.v ?: "(no result)"

    // ==================================================================
    // V1 — Unhandled baseline
    // ==================================================================

    data class V1Result(
        /** The surfaced effect closure read off VerifyResult.Ok before running. */
        val surfacedClosure: Set<String>,
        /** The category names the grant permits. */
        val grantedCategories: Set<String>,
        /** Whether the program verified clean. */
        val verifiedClean: Boolean,
        /** The produced completion text (or "(no result)"). */
        val resultText: String,
        /** Whether the program ran to completion. */
        val completed: Boolean,
        /** How many times the model transport was actually invoked. */
        val modelCallCount: Int,
    ) {
        /** The grant covers exactly the surfaced closure — the bound is tight. */
        val grantMatchesClosure: Boolean get() = grantedCategories == surfacedClosure
    }

    /**
     * V1: verify the unhandled agent program, read its surfaced closure off the Ok
     * result (`{LLM.Generate}`), grant exactly that category, and run it. The model
     * ForeignNode is invoked under the granted `LLM.Generate` and returns its
     * deterministic completion. This is the un-virtualized bound: the LLM
     * dependency is present in the closure and must be granted to run.
     */
    fun scenarioV1(): V1Result {
        val callCount = installModel()
        try {
            val json = loadProgramJson("agent-unhandled")
            val program = image(json)
            val names = nameMap(json)

            val host = StrandRuntime(HostPolicy.OPEN)
            val verify = host.verify(program)
            val verifiedClean = verify is VerifyResult.Ok
            val closure = (verify as? VerifyResult.Ok)?.let { surfacedClosure(program, it) } ?: emptySet()

            // Grant exactly the surfaced closure: LLM.Generate.
            val llmGenerateFx = names.getValue("llmGenerateFx")
            val grant = CapabilitySet.ofCategories(setOf(llmGenerateFx))
            val grantedCategories =
                setOf((program.store.getOrNull(llmGenerateFx) as Node.EffectCategory).categoryName)

            val outcome = host.run(program, grant)
            val value = (outcome as? RunOutcome.Ok)?.value

            return V1Result(
                surfacedClosure = closure,
                grantedCategories = grantedCategories,
                verifiedClean = verifiedClean,
                resultText = textOf(value),
                completed = value != null,
                modelCallCount = callCount.get(),
            )
        } finally {
            Builtins.clearTestBuiltins()
        }
    }

    // ==================================================================
    // V2 — Handled (keystone)
    // ==================================================================

    data class V2Result(
        /** The surfaced closure of the handled program — EMPTY (LLM.Generate subtracted). */
        val surfacedClosure: Set<String>,
        /** The surfaced closure of the unhandled program, for the side-by-side render. */
        val unhandledClosure: Set<String>,
        /** Whether the handled program verified clean. */
        val verifiedClean: Boolean,
        /** The grant the program ran under (empty — no LLM.Generate). */
        val grantedCategories: Set<String>,
        /** The produced completion text (from the pure handle, not the model). */
        val resultText: String,
        /** Whether the program ran to completion under the empty grant. */
        val completed: Boolean,
        /** How many times the model transport was invoked — must be 0 (virtualized away). */
        val modelCallCount: Int,
    ) {
        /** The surfaced closure is empty — the effect was subtracted by the Handler. */
        val effectVirtualized: Boolean get() = surfacedClosure.isEmpty()

        /** The program ran under no LLM.Generate grant and never touched the transport. */
        val ranPureWithNoTransport: Boolean get() = completed && modelCallCount == 0
    }

    /**
     * V2 (keystone): the SAME model call wrapped in a Handler intercepting
     * `LLM.Generate` with a PURE handle returning a canned String. The host reads
     * the verifier's own surfaced closure off the Ok result — it is now EMPTY,
     * because the Handler subtracted `LLM.Generate`. The host then runs the program
     * under `CapabilitySet.EMPTY` (granting no `LLM.Generate` at all); it STILL
     * produces a result, deterministically, and the model transport is never
     * invoked (the installed builtin's call count stays 0). The closure subtraction
     * is the real verifier's computation; the empty-grant run is the real runtime.
     */
    fun scenarioV2(): V2Result {
        val callCount = installModel()
        try {
            val handledJson = loadProgramJson("agent-handled")
            val handled = image(handledJson)

            // The unhandled closure, recomputed here so the transcript can render
            // the two side by side from one scenario call.
            val unhandledJson = loadProgramJson("agent-unhandled")
            val unhandled = image(unhandledJson)

            val host = StrandRuntime(HostPolicy.OPEN)
            val handledVerify = host.verify(handled)
            val verifiedClean = handledVerify is VerifyResult.Ok
            val surfaced = (handledVerify as? VerifyResult.Ok)?.let { surfacedClosure(handled, it) } ?: emptySet()
            val unhandledClosure = (host.verify(unhandled) as? VerifyResult.Ok)
                ?.let { surfacedClosure(unhandled, it) } ?: emptySet()

            // Run under the EMPTY capability set: no LLM.Generate granted. A program
            // whose closure still contained LLM.Generate could not run here.
            val outcome = host.run(handled, CapabilitySet.EMPTY)
            val value = (outcome as? RunOutcome.Ok)?.value

            return V2Result(
                surfacedClosure = surfaced,
                unhandledClosure = unhandledClosure,
                verifiedClean = verifiedClean,
                grantedCategories = emptySet(),
                resultText = textOf(value),
                completed = value != null,
                modelCallCount = callCount.get(),
            )
        } finally {
            Builtins.clearTestBuiltins()
        }
    }

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)

        line("=".repeat(72))
        line("Strand -- Handler-based LLM virtualization. The Handler (N-043)")
        line("intercepts the model call and replaces it, so the agent's LLM")
        line("dependency disappears from its effect closure (closure-subtraction).")
        line("The 'model' is a monomorphic stand-in builtin under LLM.Generate.")
        line("=".repeat(72))
        line()

        // --- V1 ---
        line("V1  Unhandled baseline -- the un-virtualized bound")
        line("-".repeat(72))
        val v1 = scenarioV1()
        line("  Program: agent-unhandled (calls the model ForeignNode directly).")
        line("  The bound is read off the verified artifact BEFORE running:")
        line("    surfaced closure (Q-067)  = ${v1.surfacedClosure}")
        line("    granted categories        = ${v1.grantedCategories}")
        line("    grant covers exactly it   = ${v1.grantMatchesClosure}")
        line("  Host runs under that grant; the model transport is invoked:")
        line("    verified clean            = ${v1.verifiedClean}")
        line("    model calls               = ${v1.modelCallCount}")
        line("    completed                 = ${v1.completed}")
        line("    result                    = \"${v1.resultText}\"")
        line("  The LLM.Generate dependency is in the closure and must be granted")
        line("  to run -- the agent's harm bound includes the model call.")
        line()

        // --- V2 ---
        line("V2  Handled (keystone) -- virtualization removes the effect from the bound")
        line("-".repeat(72))
        val v2 = scenarioV2()
        line("  Program: agent-handled (the SAME model call, wrapped in a Handler")
        line("           intercepting LLM.Generate with a PURE handle).")
        line("  The verifier's own surfaced closure, the two side by side:")
        line("    unhandled closure         = ${v2.unhandledClosure}")
        line("    handled closure (Q-067)   = ${v2.surfacedClosure}")
        line("    LLM.Generate subtracted   = ${v2.effectVirtualized}")
        line("  Host runs under CapabilitySet.EMPTY -- NO LLM.Generate granted:")
        line("    verified clean            = ${v2.verifiedClean}")
        line("    granted categories        = ${v2.grantedCategories}")
        line("    model transport invoked   = ${v2.modelCallCount} time(s)")
        line("    completed                 = ${v2.completed}")
        line("    result (from the handle)  = \"${v2.resultText}\"")
        line("  The wow: the program still produces a result, deterministically,")
        line("  under no LLM.Generate grant, with the transport never touched.")
        line("  Virtualization removed the effect from the harm bound.")
        line()

        // --- V3 framing ---
        line("V3  Framing -- the pure handle is a deterministic test double / policy")
        line("-".repeat(72))
        line("  The handle is a pure (String) -> String returning a canned result.")
        line("  This is effect virtualization as a verified language feature: the")
        line("  mechanism behind deterministic agent testing, prompt-rewriting")
        line("  policy layers, response caching, and budget enforcement. The")
        line("  shipped semantics are no-continuation intercept-and-replace -- the")
        line("  handler replaces the model call wholesale; there is no resume.")
        line()

        line("=".repeat(72))
        line("What this demonstrates: the Handler subtracts an effect from the")
        line("verified closure, so a virtualized model call runs under an empty")
        line("grant with no transport. NOT first-pass correctness or inference cost")
        line("(the deferred Run 8 study); the model is a monomorphic stand-in")
        line("builtin and the programs are hand-authored canonical dag-json.")
        line("=".repeat(72))

        print(out)
    }
}
