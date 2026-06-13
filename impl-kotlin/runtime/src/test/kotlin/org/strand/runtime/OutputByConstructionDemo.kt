package org.strand.runtime

import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Value
import org.strand.verifier.VerifyError

/**
 * Correct-by-construction structured output, demonstrated.
 *
 * A conventional generator emits a document — a string, a JSON blob, a DOM tree
 * — and *then* validates it (or never does), so a malformed artifact briefly
 * exists and can leak to a consumer. Strand inverts this: a structured document
 * is a typed value carrying a [org.strand.core.Node.Schema], and the schema's
 * invariant is checked **before the value is admitted (verify time) or produced
 * (runtime)**, so a malformed document never reaches output. Correctness is
 * structural, not a post-hoc lint.
 *
 * Two things make the schema's grip precise. First, the document is a genuine
 * **N-048 nested composite** (Q-053 `RecursiveProjection`): the subject is a
 * `JsonValue` whose `JsonArray` case carries a *real* nested `List<JsonValue>` —
 * a `Cons`/`Nil` spine of `ProductV` cells, not the flat spliced tagged-variant
 * workaround corpus 66 used. The invariant therefore walks an actual list and
 * can constrain it element-by-element. Second, the schema mechanism is enforced
 * at both phases: the Q-035 verify-time `SchemaChecker` rejects a
 * statically-known malformed value at admission, and the Q-047 runtime obligation
 * raises on a dynamically-computed malformed value at the value-flow site.
 *
 * The document type, abbreviated:
 *
 * ```
 * jsonValueT = mu jv. JsonNumber(Int) | JsonArray( mu list. Cons(head: jv, tail: list) | Nil )
 * ```
 *
 * The `NonNegativeJsonArray` schema wraps the array's inner list with one
 * invariant, `all_elements_non_negative`: a `Fixpoint` `(array) -> Bool` that
 * walks the `Cons`/`Nil` spine and requires each element be a `JsonNumber` whose
 * value is `>= 0`. A document whose array contains a negative number is malformed.
 *
 * Three scenarios, each exercising a real shipped property:
 *
 *  - **W1 Well-formed document produced.** The genuine N-048 array `[1, 2, 3]`
 *    carries the schema, satisfies the invariant, verifies, and evaluates to the
 *    document value. The transcript shows the value is a real nested
 *    `List<JsonValue>` — a `Cons`-spine of `ProductV` cells — not a flat blob.
 *  - **W2 Malformed caught at VERIFY time.** The statically-known array
 *    `[1, -7, 3]` violates the invariant; the Q-035 `SchemaChecker` folds the
 *    value at admission and the graph is rejected with the real verify-time
 *    [VerifyError.SchemaInvariantViolation] — before any execution.
 *  - **W3 Malformed caught at RUNTIME (Q-047, interpreter-only).** The middle
 *    element is computed at runtime as `Int.Sub(3, 10) = -7`, so the verifier
 *    cannot fold it and defers; the graph verifies clean. At runtime the
 *    obligation fires at the value-flow site and the run halts with the real
 *    [InterpretError.SchemaInvariantViolation] — before the malformed document
 *    is returned.
 *
 * The scenario methods return structured results that both [main] (the
 * transcript) and `OutputByConstructionDemoTest` (the assertions) consume, so the
 * printed demonstration and the regression net share one body of code — the same
 * discipline `ContainmentDemo` / `ReplayDemo` follow.
 */
object OutputByConstructionDemo {

    // ------------------------------------------------------------------
    // Program loading — the canonical dag-json documents.
    // ------------------------------------------------------------------

    /**
     * Load a committed demo program (canonical dag-json) from the classpath.
     * Source of truth: `demos/output-by-construction/programs/<name>.json`; the
     * `:runtime` build copies it onto the test classpath under `/demo/programs/`
     * (see `runtime/build.gradle.kts`).
     */
    fun loadProgramJson(name: String): String =
        OutputByConstructionDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    /** Finalize a program's authored dag-json to a [ProgramImage]. */
    fun image(name: String): ProgramImage {
        val ingest = JsonIngest.parse(loadProgramJson(name))
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    private fun runtime(): StrandRuntime = StrandRuntime(HostPolicy.OPEN)

    // ==================================================================
    // W1 — Well-formed structured document produced.
    // ==================================================================

    data class W1Result(
        /** The produced document value (a genuine N-048 nested array). */
        val value: Value,
        /** The array rendered as JSON text by a driver-side walk of the real structure. */
        val renderedJson: String,
        /** The integer elements read out of the genuine Cons-spine, in order. */
        val elements: List<Long>,
        /** Whether the runtime structure is a real Cons/Nil list (not a flat blob). */
        val isGenuineNestedList: Boolean,
        /** The number of Cons cells in the spine. */
        val consDepth: Int,
    )

    /**
     * W1: the well-formed `[1, 2, 3]` document. It verifies, its statically-known
     * value satisfies `all_elements_non_negative`, and it evaluates to the
     * document value. We then walk the produced [Value] to prove it is a genuine
     * nested `List<JsonValue>` (an N-048 `Cons`/`Nil` spine of `ProductV` cells),
     * the precise model this session's N-048 work makes constructible.
     */
    fun scenarioW1(): W1Result {
        val img = image("well-formed-array")
        val outcome = runtime().run(img)
        check(outcome is RunOutcome.Ok) { "W1 must verify and run cleanly; got $outcome" }
        val value = outcome.value
        val elements = arrayElements(value)
        return W1Result(
            value = value,
            renderedJson = renderJsonArray(value),
            elements = elements,
            isGenuineNestedList = isConsNilList(value),
            consDepth = elements.size,
        )
    }

    // ==================================================================
    // W2 — Malformed caught at VERIFY time (statically-known value).
    // ==================================================================

    data class W2Result(
        /** Whether the graph was rejected before any execution. */
        val rejectedAtVerify: Boolean,
        /** The real verify-time violation the SchemaChecker produced. */
        val violation: VerifyError.SchemaInvariantViolation?,
        /** The offending value's rendering, as the checker recorded it. */
        val offendingValueDescription: String?,
    )

    /**
     * W2: the statically-known `[1, -7, 3]` document. The verifier re-records the
     * array value with the schema type; the Q-035 `SchemaChecker` folds the
     * statically-known value and runs the invariant, which fails on `-7`. The
     * graph is rejected at admission with the real
     * [VerifyError.SchemaInvariantViolation] — no execution occurs.
     */
    fun scenarioW2(): W2Result {
        val img = image("malformed-static-array")
        val outcome = runtime().verifyAndCheckSchema(img)
        check(outcome is VerifyOutcome.Ok) { "W2 must pass type-checking (the violation is a schema, not type, error); got $outcome" }
        val violation = outcome.schema.violations.firstOrNull()
        return W2Result(
            rejectedAtVerify = outcome.schema.hasViolations,
            violation = violation,
            offendingValueDescription = violation?.valueDescription,
        )
    }

    // ==================================================================
    // W3 — Malformed caught at RUNTIME (dynamic value, Q-047).
    // ==================================================================

    data class W3Result(
        /** Whether verify-time schema checking passed (the value is dynamic, so it must). */
        val verifiesClean: Boolean,
        /** The deferred diagnostic the verifier recorded for the dynamic array value. */
        val deferredOnArray: Boolean,
        /** Whether the run halted with a runtime schema violation (no value returned). */
        val raisedAtRuntime: Boolean,
        /** The real runtime violation the interpreter raised at the obligation site. */
        val violation: InterpretError.SchemaInvariantViolation?,
        /** The offending value's rendering, as the interpreter recorded it. */
        val offendingValueDescription: String?,
    )

    /**
     * W3: the dynamic `[1, Int.Sub(3, 10), 3] = [1, -7, 3]` document. Because
     * `Int.Sub` is non-static, the verifier cannot fold the array and records a
     * `SchemaInvariantDeferred` — the graph verifies clean. At runtime the
     * interpreter materializes the array and the obligation fires at the
     * value-flow site, raising the real [InterpretError.SchemaInvariantViolation]
     * before the malformed document is returned. This is the Q-047 runtime path,
     * which is **interpreter-only** (the bytecode VM erases schemas pre-bytecode).
     */
    fun scenarioW3(): W3Result {
        val img = image("malformed-dynamic-array")

        // Verify-time: clean, with the dynamic array value deferred.
        val verifyOutcome = runtime().verifyAndCheckSchema(img)
        check(verifyOutcome is VerifyOutcome.Ok) { "W3 must verify cleanly; got $verifyOutcome" }
        val verifiesClean = !verifyOutcome.schema.hasViolations
        // The array value-flow site is deferred (its value is dynamic), which is
        // why no static violation fires — the runtime check is the backstop.
        val deferredOnArray = verifyOutcome.schema.deferred.isNotEmpty()

        // Runtime: the obligation raises before the document is returned.
        var violation: InterpretError.SchemaInvariantViolation? = null
        var raised = false
        try {
            runtime().run(img)
        } catch (e: InterpretException) {
            raised = true
            violation = e.error as? InterpretError.SchemaInvariantViolation
        }
        return W3Result(
            verifiesClean = verifiesClean,
            deferredOnArray = deferredOnArray,
            raisedAtRuntime = raised,
            violation = violation,
            offendingValueDescription = violation?.valueDescription,
        )
    }

    // ------------------------------------------------------------------
    // Structure helpers — walk the GENUINE nested value (no builtin).
    // ------------------------------------------------------------------

    /**
     * True iff [v] is a genuine `Cons`/`Nil` list value (an N-048 array spine),
     * i.e. a `SumV` whose case is `Cons` (payload a `ProductV` with `head`/`tail`)
     * or `Nil`. This is the structural proof that the document is a real nested
     * `List<JsonValue>`, not a flat spliced blob.
     */
    fun isConsNilList(v: Value): Boolean {
        if (v !is Value.SumV) return false
        return when (v.case) {
            "Nil" -> true
            "Cons" -> {
                val p = v.payload as? Value.ProductV ?: return false
                p.fields.containsKey("head") && p.fields.containsKey("tail") &&
                    isConsNilList(p.fields.getValue("tail"))
            }
            else -> false
        }
    }

    /** Read the integer elements out of a genuine `Cons`/`Nil` `JsonNumber` array spine. */
    fun arrayElements(v: Value): List<Long> {
        val out = mutableListOf<Long>()
        var cur = v
        while (cur is Value.SumV && cur.case == "Cons") {
            val cell = cur.payload as Value.ProductV
            val head = cell.fields.getValue("head") as Value.SumV  // JsonNumber(Int)
            out += (head.payload as Value.IntV).v
            cur = cell.fields.getValue("tail")
        }
        return out
    }

    /**
     * Render the genuine nested array as JSON text by walking the real structure.
     * The shipped `Json.Stringify` builtin recognizes only corpus 66's *spliced*
     * `JsonArrayCons`/`JsonArrayNil` model, not the precise N-048 nested model
     * (migrating `Json.*` to the precise model is deferred under the N-048
     * proposal § 8), so this driver-side walk is what shows the value yields
     * correct output — straight off the genuine structure.
     */
    fun renderJsonArray(v: Value): String =
        "[" + arrayElements(v).joinToString(",") + "]"

    /** Render a (possibly nested) JsonValue / list value compactly for the transcript. */
    fun renderValue(v: Value): String = when (v) {
        is Value.SumV -> when (v.case) {
            "Nil" -> "Nil"
            "Cons" -> {
                val p = v.payload as Value.ProductV
                "Cons{head=${renderValue(p.fields.getValue("head"))}, tail=${renderValue(p.fields.getValue("tail"))}}"
            }
            "JsonNumber" -> "JsonNumber(${(v.payload as Value.IntV).v})"
            "JsonArray" -> "JsonArray(${renderValue(v.payload!!)})"
            else -> v.toString()
        }
        else -> v.toString()
    }

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)

        line("=".repeat(72))
        line("Strand -- correct-by-construction structured output")
        line("Subject: a JsonValue document with a GENUINE N-048 nested array,")
        line("carrying a Schema whose invariant makes a malformed array unemittable.")
        line("=".repeat(72))
        line()
        line("Document type:")
        line("  jsonValueT = mu jv. JsonNumber(Int)")
        line("                    | JsonArray( mu list. Cons(head: jv, tail: list) | Nil )")
        line("Schema NonNegativeJsonArray over the array's inner list, invariant")
        line("  all_elements_non_negative: every element is a JsonNumber with value >= 0.")
        line("A conventional generator emits a document and validates AFTER; Strand")
        line("checks the invariant BEFORE the value is admitted (W2) or produced (W3),")
        line("so a malformed document never reaches output.")
        line()

        // --- W1 ---
        line("W1  Well-formed document produced -- a genuine N-048 nested array")
        line("-".repeat(72))
        val w1 = scenarioW1()
        line("  Built via N-048 RecursiveProjection: a real List<JsonValue> spine.")
        line("    structure (runtime Value)   = ${renderValue(w1.value)}")
        line("    is a genuine Cons/Nil list  = ${w1.isGenuineNestedList}")
        line("    Cons cells in the spine     = ${w1.consDepth}")
        line("    elements read off the spine = ${w1.elements}")
        line("    rendered JSON (from struct) = ${w1.renderedJson}")
        line("  The value satisfies all_elements_non_negative, so it verifies and")
        line("  evaluates. The structure is a real nested list -- NOT a flat spliced")
        line("  blob -- so the schema can constrain it element-by-element.")
        line()

        // --- W2 ---
        line("W2  Malformed caught at VERIFY time -- statically-known value")
        line("-".repeat(72))
        val w2 = scenarioW2()
        line("  Document [1, -7, 3]: the middle element is a static negative literal.")
        line("  The Q-035 SchemaChecker folds the statically-known value at admission")
        line("  and runs the invariant, which fails on -7.")
        line("    rejected before execution   = ${w2.rejectedAtVerify}")
        line("    verify-time error           = ${w2.violation?.let { errName(it) }}")
        line("    blamed node (value-flow)    = #${w2.violation?.at?.value}")
        line("    failed invariant            = #${w2.violation?.invariant?.value}")
        line("    offending value             = ${w2.offendingValueDescription}")
        line("  The malformed document is rejected at the door -- no artifact exists.")
        line()

        // --- W3 ---
        line("W3  Malformed caught at RUNTIME -- dynamic value (Q-047, interpreter-only)")
        line("-".repeat(72))
        val w3 = scenarioW3()
        line("  Document [1, Int.Sub(3, 10), 3] = [1, -7, 3]: the middle element is")
        line("  computed at runtime, so the verifier cannot fold it -- it defers.")
        line("    verifies clean              = ${w3.verifiesClean}")
        line("    array value-flow deferred   = ${w3.deferredOnArray}")
        line("  At runtime the obligation fires at the value-flow site before the")
        line("  document is returned:")
        line("    raised at runtime           = ${w3.raisedAtRuntime}")
        line("    runtime error               = ${w3.violation?.let { errName(it) }}")
        line("    blamed node (value-flow)    = #${w3.violation?.at?.value}")
        line("    failed invariant            = #${w3.violation?.invariant?.value}")
        line("    offending value             = ${w3.offendingValueDescription}")
        line("  The Q-047 runtime check is interpreter-only: the bytecode VM erases")
        line("  schemas pre-bytecode, so this enforcement runs the interpreter path.")
        line()

        line("=".repeat(72))
        line("What this demonstrates: correct-by-construction STRUCTURED output --")
        line("a malformed document cannot reach output because the schema invariant")
        line("is checked BEFORE the value is admitted (W2) or produced (W3), over a")
        line("genuine N-048 nested composite the schema constrains precisely.")
        line("NOT shown: the full HTML5/SVG blessed libraries (a separate roadmap")
        line("item this mechanism would underpin), first-pass correctness, or cost.")
        line("=".repeat(72))

        print(out)
    }

    /** Short class-name of an error, for compact transcript lines. */
    private fun errName(e: Any): String = e::class.simpleName ?: e.toString()
}
