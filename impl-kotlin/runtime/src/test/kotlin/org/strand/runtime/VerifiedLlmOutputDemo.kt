package org.strand.runtime

import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Value
import org.strand.verifier.JsonSchemaProjection
import org.strand.verifier.TypeExpr
import org.strand.verifier.VerifyResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Constrain the model, then verify what it returns, demonstrated.
 *
 * Constraining a model is not the same as guaranteeing its output. A
 * structured-output request narrows what the model *may* emit — but a model
 * under a JSON-schema constraint can still return a structurally-typed value
 * that is *semantically* wrong: a score outside its declared range, a field that
 * type-checks but means nothing. This demonstration shows Strand's two
 * complementary layers, on shipped material:
 *
 *  - **The constrain layer (N-045 `ResponseSchemaSpec`).** A `Generate` call
 *    carries a `ResponseSchemaSpec` that projects to the provider's
 *    structured-output JSON schema — the constraint on what the model may emit.
 *    The driver projects the same `BoundedScore` schema the program carries
 *    through the verifier's own `JsonSchemaProjection` (the exact projection the
 *    N-045 dispatch path runs internally) and prints it: this is the constraint
 *    the model receives.
 *
 *  - **The verify layer (Q-047 runtime Schema invariant — the keystone).** The
 *    value the (mock) model returns flows into a Strand `Schema`-typed position
 *    whose invariant is a pure `(Int) -> Bool` checked at runtime. A response
 *    that is structurally an `Int` but out of range raises
 *    `InterpretError.SchemaInvariantViolation` *before* it reaches output, so
 *    malformed model output never escapes.
 *
 * The pipeline is a single shared program ([PROGRAM]) whose `Demo.ScoreModel`
 * ForeignNode (a `(String) -> Int` stand-in for a constrained model call,
 * declaring `LLM.Generate`) returns the model's structured score; that returned
 * value flows into a `recordScore` Lambda whose parameter carries the
 * `BoundedScore` Schema (range `0..100`). The verified value therefore
 * *originates from the model call*, not from a hand-built literal — that is the
 * keystone over the existing `output-by-construction` demonstration (which
 * verified a hand-built value). The two scenarios share the program byte-for-byte
 * and differ only in what the model returns, which the driver controls by
 * installing the canned response on the `Demo.ScoreModel` builtin.
 *
 * The scenario methods return structured results that both [main] (the
 * transcript) and `VerifiedLlmOutputDemoTest` (the assertions) consume, so the
 * printed demonstration and the regression net share one body of code — the
 * discipline `ContainmentDemo`, `AgentWorkflowDemo`, and `LlmVirtualizationDemo`
 * follow.
 */
object VerifiedLlmOutputDemo {

    /** The shared program: model call -> BoundedScore-typed position. */
    const val PROGRAM = "verify-model-score"

    /** The stand-in constrained-model call's builtin target. */
    const val MODEL_TARGET = "strand-builtin:Demo.ScoreModel"

    /** A valid structured score the constrained model returns (in 0..100). */
    const val VALID_SCORE = 87L

    /** A semantically-invalid score a constrained model can still return (> 100). */
    const val INVALID_SCORE = 142L

    // ------------------------------------------------------------------
    // Program loading — the host admits the canonical dag-json artifact.
    // ------------------------------------------------------------------

    /**
     * Load a committed program (canonical dag-json) from the classpath. Source
     * of truth: `demos/verified-llm-output/programs/<name>.json`; the `:runtime`
     * build copies those onto the test classpath under `/demo/programs/` (see
     * `runtime/build.gradle.kts`), so this resource path is the classpath
     * namespace, not a filesystem location.
     */
    fun loadProgramJson(name: String): String =
        VerifiedLlmOutputDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    /** Finalize a program's authored dag-json to the host's [ProgramImage]. */
    fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    /** The author-id -> NodeId map for a program (used to build the grant). */
    fun nameMap(json: String): Map<String, NodeId> = JsonIngest.parse(json).nameMap

    private val prettyJson = Json { prettyPrint = true }

    /**
     * Install the stand-in constrained-model builtin returning [score]. The
     * model is effectful (it declares `LLM.Generate`) and [Stateful][Builtins.Determinism.Stateful]
     * because a real model call depends on the world. Returns the call counter so
     * the demonstration can assert the model transport was actually reached (this
     * is a genuine model call, not a hand-built value). The returned value is the
     * structured score the constrained model produced.
     */
    private fun installModel(score: Long): java.util.concurrent.atomic.AtomicInteger {
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        Builtins.installTestBuiltin(
            MODEL_TARGET,
            effectful = true,
            determinism = Builtins.Determinism.Stateful,
            fn = Builtins.Fn { _, _ ->
                callCount.incrementAndGet()
                Value.IntV(score)
            },
        )
        return callCount
    }

    /**
     * The N-045 constraint the model receives: the program's `BoundedScore`
     * Schema, projected to its provider-format JSON schema through the verifier's
     * own [JsonSchemaProjection] — the exact projection the N-045 dispatch path
     * (`parseResponseSchemaField`) runs internally when it forwards the schema to
     * the provider library.
     *
     * The SchemaType the projection consumes is read off the verified artifact's
     * `nodeTypes`: the verifier re-records the `BoundedScore` Schema as a
     * `TypeExpr.SchemaType` on the value-flow node where the model's response
     * enters the schema-typed position (the same node the Q-047 obligation
     * keys on), so this is the SchemaType the actual graph carries — found by
     * matching `schemaId` to the `boundedScore` Schema NodeId, not re-derived.
     */
    fun projectedConstraint(
        verify: VerifyResult.Ok,
        schemaNodeId: NodeId,
    ): JsonElement {
        val schemaType = verify.nodeTypes.values
            .filterIsInstance<TypeExpr.SchemaType>()
            .firstOrNull { it.schemaId == schemaNodeId }
            ?: error("BoundedScore schema did not appear as a SchemaType in nodeTypes")
        return when (val r = JsonSchemaProjection.project(schemaType.valueType)) {
            is JsonSchemaProjection.Result.Success -> r.schema
            is JsonSchemaProjection.Result.Rejected ->
                error("BoundedScore valueType unexpectedly rejected by JsonSchemaProjection: ${r.reason}")
        }
    }

    // ==================================================================
    // VO1 — Constrain + valid: the model's response satisfies the invariant.
    // ==================================================================

    data class VO1Result(
        /** The N-045 constraint the model receives (projected JSON schema). */
        val constraint: JsonElement,
        /** Whether the program verified clean. */
        val verifiedClean: Boolean,
        /** The score the (mock) constrained model returned. */
        val modelScore: Long,
        /** How many times the model transport was actually invoked (must be 1). */
        val modelCallCount: Int,
        /** The validated result value (the BoundedScore-typed position's output). */
        val resultValue: Value?,
    ) {
        val completed: Boolean get() = resultValue != null
        /** The validated result, as an Int (the score that passed the invariant). */
        val resultScore: Long? get() = (resultValue as? Value.IntV)?.v
    }

    /**
     * VO1: project the program's N-045 `ResponseSchemaSpec` to the provider JSON
     * schema (the constraint the model receives), then run the pipeline with the
     * model returning a VALID structured score ([VALID_SCORE], in `0..100`). The
     * returned value flows into the `BoundedScore` position; the Q-047 runtime
     * invariant passes; the program produces the validated result.
     */
    fun scenarioVO1(): VO1Result {
        val callCount = installModel(VALID_SCORE)
        try {
            val json = loadProgramJson(PROGRAM)
            val program = image(json)
            val names = nameMap(json)

            val host = StrandRuntime(HostPolicy.OPEN)
            val verify = host.verify(program)
            val verifiedClean = verify is VerifyResult.Ok
            val constraint = (verify as? VerifyResult.Ok)
                ?.let { projectedConstraint(it, names.getValue("boundedScore")) }
                ?: error("VO1 program must verify clean to project its constraint")

            // Grant LLM.Generate: the model call is the only effect.
            val grant = CapabilitySet.ofCategories(setOf(names.getValue("llmGenFx")))
            val outcome = host.run(program, grant)
            val value = (outcome as? RunOutcome.Ok)?.value

            return VO1Result(
                constraint = constraint,
                verifiedClean = verifiedClean,
                modelScore = VALID_SCORE,
                modelCallCount = callCount.get(),
                resultValue = value,
            )
        } finally {
            Builtins.clearTestBuiltins()
        }
    }

    // ==================================================================
    // VO2 — Verify the output (keystone): the model's response violates it.
    // ==================================================================

    data class VO2Result(
        /** The score the (mock) constrained model returned (out of range). */
        val modelScore: Long,
        /** How many times the model transport was invoked (1 — the value is genuine). */
        val modelCallCount: Int,
        /** Whether the run halted with a runtime schema violation (no value reached output). */
        val raisedAtRuntime: Boolean,
        /** The real runtime violation the interpreter raised at the value-flow site. */
        val violation: InterpretError.SchemaInvariantViolation?,
        /** The offending value's rendering, as the interpreter recorded it. */
        val offendingValueDescription: String?,
    ) {
        val contained: Boolean get() = raisedAtRuntime && violation != null
    }

    /**
     * VO2 (keystone): run the SAME pipeline with the model returning a response
     * that is structurally an `Int` but SEMANTICALLY invalid per the Strand
     * `BoundedScore` invariant ([INVALID_SCORE], `> 100`). A constrained model can
     * still return this. The value flows into the `BoundedScore` position; the
     * Q-047 runtime obligation fires at the value-flow site and raises
     * `InterpretError.SchemaInvariantViolation` BEFORE the value reaches output.
     * Strand verifies what came back, so the malformed output never escapes.
     */
    fun scenarioVO2(): VO2Result {
        val callCount = installModel(INVALID_SCORE)
        try {
            val json = loadProgramJson(PROGRAM)
            val program = image(json)
            val names = nameMap(json)

            val grant = CapabilitySet.ofCategories(setOf(names.getValue("llmGenFx")))
            val host = StrandRuntime(HostPolicy.OPEN)

            var violation: InterpretError.SchemaInvariantViolation? = null
            var raised = false
            try {
                host.run(program, grant)
            } catch (e: InterpretException) {
                raised = true
                violation = e.error as? InterpretError.SchemaInvariantViolation
            }

            return VO2Result(
                modelScore = INVALID_SCORE,
                modelCallCount = callCount.get(),
                raisedAtRuntime = raised,
                violation = violation,
                offendingValueDescription = violation?.valueDescription,
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
        line("Strand -- constrain the model, then verify what it returns")
        line("Constraining is not guaranteeing: a model under a JSON-schema")
        line("constraint can still return a structurally-typed but semantically")
        line("invalid value. N-045 constrains what the model may emit; Q-047")
        line("verifies the value that came back, so malformed output never escapes.")
        line("The 'model' is a (String) -> Int stand-in under LLM.Generate; the")
        line("verified value ORIGINATES from the (mock) model call.")
        line("=".repeat(72))
        line()

        // --- VO1 ---
        line("VO1  Constrain + valid -- the model's response satisfies the invariant")
        line("-".repeat(72))
        val vo1 = scenarioVO1()
        line("  The constrain layer (N-045 ResponseSchemaSpec). The program's")
        line("  BoundedScore schema projects to the provider's structured-output")
        line("  JSON schema -- the constraint the model receives:")
        for (l in prettyJson.encodeToString(JsonElement.serializer(), vo1.constraint).lines()) {
            line("    $l")
        }
        line("  The verify layer (Q-047). The model returns a VALID structured")
        line("  score; it flows into the BoundedScore position; the invariant")
        line("  (0 <= score <= 100) PASSES:")
        line("    verified clean            = ${vo1.verifiedClean}")
        line("    model transport invoked   = ${vo1.modelCallCount} time(s)")
        line("    model returned score      = ${vo1.modelScore}")
        line("    invariant passed          = ${vo1.completed}")
        line("    validated result          = ${vo1.resultScore}")
        line("  The value flowed from the model call through the Schema position")
        line("  and out -- a genuine model response, verified before use.")
        line()

        // --- VO2 ---
        line("VO2  Verify the output (keystone) -- the model's response violates it")
        line("-".repeat(72))
        val vo2 = scenarioVO2()
        line("  The SAME pipeline, but the constrained model returns a response")
        line("  that is structurally an Int but out of its declared range (> 100).")
        line("  A constrained model can still return this. The Q-047 runtime")
        line("  obligation fires at the value-flow site BEFORE output:")
        line("    model transport invoked   = ${vo2.modelCallCount} time(s)")
        line("    model returned score      = ${vo2.modelScore}")
        line("    raised at runtime         = ${vo2.raisedAtRuntime}")
        line("    runtime error             = ${vo2.violation?.let { it::class.simpleName }}")
        line("    blamed node (value-flow)  = #${vo2.violation?.at?.value}")
        line("    failed schema             = #${vo2.violation?.schema?.value}")
        line("    failed invariant          = #${vo2.violation?.invariant?.value}")
        line("    offending value           = ${vo2.offendingValueDescription}")
        line("  Strand verifies what came back, so the malformed model output is")
        line("  contained -- it never reaches output. Constraining narrows what the")
        line("  model MAY emit; verifying catches what a constrained model STILL")
        line("  returns.")
        line()

        // --- VO3 framing ---
        line("VO3  Two complementary layers -- constrain (N-045) AND verify (Q-047)")
        line("-".repeat(72))
        line("  N-045 ResponseSchemaSpec is the constraint sent TO the provider:")
        line("  the projected JSON schema bounds what the model may emit. A real")
        line("  provider enforces it server-side. But the constraint is advisory")
        line("  about SEMANTICS -- a 0..100 score field can still come back as 142.")
        line("  Q-047 is the in-process check on what came BACK: the Strand Schema")
        line("  invariant, enforced at the value-flow site. The two layers are")
        line("  complementary: constraining reduces malformed responses; verifying")
        line("  guarantees a malformed one never escapes.")
        line()

        line("=".repeat(72))
        line("What this demonstrates: a value originating from a (mock) model call")
        line("is verified against a Strand Schema invariant at runtime, so a")
        line("structurally-typed-but-semantically-invalid model response is")
        line("contained before output (Q-047), complementing the N-045 constraint")
        line("on what the model may emit. NOT shown: a real provider enforcing the")
        line("constraint server-side (the model is a mock returning what the demo")
        line("scripts); the Q-047 check is interpreter-only (the VM erases schemas);")
        line("the program is hand-authored; NOT first-pass correctness or cost.")
        line("=".repeat(72))

        print(out)
    }
}
