package org.strand.corpus.soundness

import org.strand.bytecode.ChunkTable
import org.strand.bytecode.Lowerer
import org.strand.bytecode.LoweringNotImplemented
import org.strand.core.IngestError
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.AuditOutcome
import org.strand.interpreter.AuditRecord
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.HostContext
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Value
import org.strand.runtime.GuardedOutcome
import org.strand.runtime.HaltReason
import org.strand.runtime.ProgramImage
import org.strand.runtime.RunOutcome
import org.strand.runtime.StrandRuntime
import org.strand.runtime.VerifyOutcome
import org.strand.verifier.ProgramAnalysis
import org.strand.verifier.VerifyResult
import org.strand.vm.Vm

/**
 * One property violation. [key] identifies the failure class: the shrinker
 * keeps a candidate only if it still produces the same key, and a campaign
 * reports one example per distinct key.
 */
data class Violation(
    val property: String,
    val backend: String,
    val kind: String,
    val grant: String,
    val detail: String,
) {
    val key: String get() = "$property/$backend/$kind"
}

sealed class CaseOutcome {
    /** The pipeline refused the program with a structured error before any run. */
    data class Rejected(val stage: String, val family: String) : CaseOutcome()

    /** The program was admitted and run; [violations] is empty when every property held. */
    data class Checked(
        val violations: List<Violation>,
        val vmSupported: Boolean,
        val performed: Int,
        val denials: Int,
    ) : CaseOutcome()
}

/**
 * Runs one generated case through the pipeline and checks the soundness
 * properties against two independent records of what happened: the
 * ground-truth log ([FuzzLog]) and the Q-055 effect-audit log the backend
 * under test emits. The ground truth is written by the stand-in builtins
 * themselves and, for the two registry builtins the generator uses, by the
 * host: `Time.Now` is seen through the policy's clock and `Fs.Write` as the
 * files left in the throwaway workspace. Neither depends on the verifier,
 * the capability check, or the audit path.
 *
 * For an admitted program P and every grant G (full, empty, exact closure,
 * and the generated category-only and refined grants):
 *
 *  - **S1 closure soundness.** Every category a stand-in performed, and
 *    every category an `Allowed` audit record names, is in
 *    `ProgramAnalysis.totalClosure()`.
 *  - **S2 capability confinement.** Every performed effect is in G, inside
 *    every dynamically enclosing CapabilityScope, and covered by one of G's
 *    patterns: for a projected binding the resource the builtin actually
 *    received, for an instantiated site the audited parameters, and for an
 *    uninstantiated parameterized site an unrefined pattern. The run under G
 *    performs a prefix of what the full-grant run performs and either ends
 *    in a capability denial or reaches the full-grant outcome.
 *  - **S3 guard agreement.** `StrandRuntime.runGuarded(P, G)` refuses, with
 *    no effect and no audit record, exactly when the total closure is not
 *    within G's categories; otherwise it behaves as `run(P, G)`.
 *  - **S4 backend parity.** The bytecode VM reaches the same value or the
 *    same denial (variant and category) as the interpreter, performing the
 *    same effects and emitting the same audit records.
 *  - **S5 no raw failures.** Every stage ends in a value or a structured
 *    `IngestError` / `VerifyError` / `InterpretError`.
 *
 * Three supporting checks keep the oracles honest: **A1** (every performed
 * effect has an adjacent `Allowed` audit record, so the audit log is a
 * complete record of what ran), **T1** (the root value has the verified
 * type and no stand-in received a mistyped argument, since a type hole can
 * be laundered into an effect hole), and **S6** (no value violating a
 * schema's invariant reaches a parameter typed by that schema).
 *
 * A StateMachine-rooted case is run through `runMachine`; its bound is the
 * machine's declared effect row, since the closure surface gates only the
 * plain `run` path. S6 is checked on the interpreter under `run` only:
 * neither the VM nor a machine transition enforces runtime schema
 * obligations (both deferred under Q-047).
 */
class SoundnessHarness(private val vmAudit: Boolean = true) {

    private sealed class Out {
        data class Val(val value: Value) : Out()
        data class Err(val error: InterpretError) : Out()
        data class Raw(val thrown: Throwable) : Out()
        data class Halt(val summary: String, val denial: Boolean, val exhausted: Boolean) : Out()
        object StaticSchema : Out()
    }

    private class Run(val out: Out, val events: List<Event>) {
        val performed: List<Event.Performed> = events.filterIsInstance<Event.Performed>()
        val audits: List<AuditRecord> = events.filterIsInstance<Event.Audit>().map { it.record }
    }

    /** The run's events, after sweeping the workspace for files it wrote. */
    private fun events(s: Subject): List<Event> {
        if ("fs-write" in s.case.features) FuzzHost.collectWrites()
        return FuzzLog.events
    }

    /** Everything about an admitted program the checks need. */
    private class Subject(
        val case: GenCase,
        val image: ProgramImage,
        val verify: VerifyResult.Ok,
        val runtime: StrandRuntime,
        val catIds: Map<Cat, NodeId>,
        /** Category names an effect may carry: the bound S1 checks against. */
        val bound: Set<String>,
        val total: Set<NodeId>,
    )

    fun check(case: GenCase): CaseOutcome {
        val violations = ArrayList<Violation>()
        fun raw(stage: String, t: Throwable): CaseOutcome {
            violations += Violation("S5", stage, rawKind(t), "-", "${t::class.qualifiedName}: ${t.message}")
            return CaseOutcome.Checked(violations, vmSupported = false, performed = 0, denials = 0)
        }

        val ingest = try {
            JsonIngest.parse(case.json)
        } catch (e: IngestError) {
            return CaseOutcome.Rejected("ingest", e::class.simpleName ?: "IngestError")
        } catch (t: Throwable) {
            return raw("ingest", t)
        }
        val finalized = try {
            Hasher(ingest.rawStore).finalize(ingest.root)
        } catch (e: IngestError) {
            return CaseOutcome.Rejected("ingest", e::class.simpleName ?: "IngestError")
        } catch (t: Throwable) {
            return raw("hash", t)
        }
        val image = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        val runtime = StrandRuntime(FuzzHost.policy())
        val verify = try {
            runtime.verify(image)
        } catch (t: Throwable) {
            return raw("verify", t)
        }
        if (verify is VerifyResult.Failed) {
            return CaseOutcome.Rejected("verify", verify.errors.first()::class.simpleName ?: "VerifyError")
        }
        verify as VerifyResult.Ok
        // The verify-time schema pass is part of admission on every path
        // (`run` performs it itself; a host driving `runMachine` calls
        // `verifyAndCheckSchema` first, as the CLI does).
        val schemaOk = try {
            (runtime.verifyAndCheckSchema(image) as? VerifyOutcome.Ok)?.schema?.hasViolations == false
        } catch (t: Throwable) {
            return raw("schema", t)
        }
        if (!schemaOk) return CaseOutcome.Rejected("schema", "SchemaInvariantViolation")

        val analysis = ProgramAnalysis(finalized.store, verify, finalized.root)
        val total = analysis.totalClosure()
        val catIds = Cat.entries.mapNotNull { c -> ingest.nameMap[c.nodeId]?.let { c to it } }.toMap()
        fun names(ids: Collection<NodeId>): Set<String> =
            ids.mapNotNullTo(LinkedHashSet()) { (finalized.store.getOrNull(it) as? Node.EffectCategory)?.categoryName }

        val bound = when (case.mode) {
            Mode.Expression -> names(total)
            Mode.Machine -> {
                val machine = finalized.store.get(finalized.root) as Node.StateMachine
                names(machine.effects) + names(analysis.latentEffectClosure())
            }
        }
        val subject = Subject(case, image, verify, runtime, catIds, bound, total)
        val allCategories = finalized.store.entries()
            .filter { (_, n) -> n is Node.EffectCategory }
            .mapTo(LinkedHashSet()) { (id, _) -> id }
        val grants = ArrayList<Pair<String, CapabilitySet>>()
        grants += "full" to CapabilitySet.ofCategories(allCategories)
        grants += "empty" to CapabilitySet.EMPTY
        if (case.mode == Mode.Expression) grants += "exact-closure" to CapabilitySet.ofCategories(total)
        for (g in case.grants) grants += g.toString() to resolve(g, catIds)

        return when (case.mode) {
            Mode.Expression -> checkExpression(subject, grants, violations)
            Mode.Machine -> checkMachine(subject, grants, violations)
        }
    }

    // ------------------------------------------------------------------
    // Expression-rooted programs
    // ------------------------------------------------------------------

    private fun checkExpression(
        s: Subject,
        grants: List<Pair<String, CapabilitySet>>,
        violations: MutableList<Violation>,
    ): CaseOutcome {
        val table: ChunkTable? = try {
            Lowerer(s.image.store, s.image.hashToNodeId).lower(s.image.root)
        } catch (_: LoweringNotImplemented) {
            null // the VM declines the program before running any of it
        } catch (t: Throwable) {
            violations += Violation("S5", "vm", rawKind(t), "-", "lowering: ${t::class.qualifiedName}: ${t.message}")
            null
        }
        var performed = 0
        var denials = 0
        var interpFull: Run? = null
        var vmFull: Run? = null
        for ((label, grant) in grants) {
            val interp = runInterpreter(s, grant)
            if (interp.out is Out.StaticSchema) {
                return CaseOutcome.Rejected("schema", "SchemaInvariantViolation")
            }
            if (interpFull == null) interpFull = interp
            performed += interp.performed.size
            if (isDenial(interp.out)) denials++
            checkRun(s, "interpreter", label, grant, interp, interpFull, auditOn = true, schemaEnforced = true, violations)
            checkGuard(s, label, grant, interp, violations)
            if (table != null) {
                val vm = runVm(s, table, grant)
                if (vmFull == null) vmFull = vm
                checkRun(s, "vm", label, grant, vm, vmFull, auditOn = vmAudit, schemaEnforced = false, violations)
                checkParity(label, interp, vm, violations)
            }
        }
        return CaseOutcome.Checked(violations, vmSupported = table != null, performed, denials)
    }

    private fun runInterpreter(s: Subject, grant: CapabilitySet): Run {
        FuzzLog.reset()
        val out = try {
            when (val o = s.runtime.run(s.image, grant)) {
                is RunOutcome.Ok -> Out.Val(o.value)
                is RunOutcome.SchemaViolation -> Out.StaticSchema
                is RunOutcome.VerifyFailed -> Out.Raw(IllegalStateException("verified program failed re-verification"))
            }
        } catch (e: InterpretException) {
            Out.Err(e.error)
        } catch (t: Throwable) {
            Out.Raw(t)
        }
        return Run(out, events(s))
    }

    private fun runVm(s: Subject, table: ChunkTable, grant: CapabilitySet): Run {
        FuzzLog.reset()
        val out = try {
            val context = HostContext.fromPolicy(
                FuzzHost.policy(), s.verify.nodeTypes, s.verify.verifiedInterceptions,
            )
            Out.Val(Vm(table, context).run(grant, FuzzHost.limits))
        } catch (e: InterpretException) {
            Out.Err(e.error)
        } catch (t: Throwable) {
            Out.Raw(t)
        }
        return Run(out, events(s))
    }

    /** S3: the guard refuses exactly when the closure exceeds the budget, before any effect. */
    private fun checkGuard(
        s: Subject,
        label: String,
        grant: CapabilitySet,
        interp: Run,
        violations: MutableList<Violation>,
    ) {
        FuzzLog.reset()
        var refused: GuardedOutcome.Refused? = null
        val out: Out? = try {
            when (val g = s.runtime.runGuarded(s.image, grant)) {
                is GuardedOutcome.Refused -> { refused = g; null }
                is GuardedOutcome.VerifyFailed -> Out.Raw(IllegalStateException("verified program failed re-verification"))
                is GuardedOutcome.Ran -> when (val o = g.outcome) {
                    is RunOutcome.Ok -> Out.Val(o.value)
                    is RunOutcome.SchemaViolation -> Out.StaticSchema
                    is RunOutcome.VerifyFailed -> Out.Raw(IllegalStateException("verified program failed re-verification"))
                }
            }
        } catch (e: InterpretException) {
            Out.Err(e.error)
        } catch (t: Throwable) {
            Out.Raw(t)
        }
        val guarded = Run(out ?: Out.StaticSchema, events(s))
        val exceeding = s.total - grant.grants.keys
        fun fail(kind: String, detail: String) {
            violations += Violation("S3", "guard", kind, label, detail)
        }
        val r = refused
        when {
            exceeding.isNotEmpty() && r == null ->
                fail("ran-over-budget", "total closure exceeds the budget by $exceeding but runGuarded ran the program")
            exceeding.isNotEmpty() && guarded.events.isNotEmpty() ->
                fail("effect-before-refusal", "refused run left events: ${guarded.events}")
            exceeding.isNotEmpty() && r!!.report.exceeding.keys != exceeding ->
                fail("refusal-report", "report names ${r.report.exceeding.keys}, expected $exceeding")
            exceeding.isEmpty() && r != null ->
                fail("refused-within-budget", "total closure ${s.total} is within the budget but runGuarded refused")
            exceeding.isEmpty() && !isExhaustion(interp.out) && !isExhaustion(guarded.out) &&
                (!sameOutcome(interp.out, guarded.out) || trace(interp) != trace(guarded) ||
                    writes(interp) != writes(guarded)) ->
                fail("guarded-run-diverged", "run: ${describe(interp.out)}; runGuarded: ${describe(guarded.out)}")
        }
    }

    /** S4: the VM agrees with the interpreter on outcome, performed effects, and audit records. */
    private fun checkParity(label: String, interp: Run, vm: Run, violations: MutableList<Violation>) {
        if (interp.out is Out.Raw || vm.out is Out.Raw) return // already reported under S5
        if (isExhaustion(interp.out) || isExhaustion(vm.out)) return // budgets count different units
        // The VM does not enforce runtime schema obligations (the interpreter
        // is the only backend StrandRuntime.run drives), so a run the
        // interpreter stops on an invariant is not comparable.
        if ((interp.out as? Out.Err)?.error is InterpretError.SchemaInvariantViolation) return
        fun fail(kind: String, detail: String) {
            violations += Violation("S4", "vm", kind, label, detail)
        }
        when {
            !sameOutcome(interp.out, vm.out) ->
                fail(
                    "outcome:${shortKind(interp.out)}-vs-${shortKind(vm.out)}",
                    "interpreter: ${describe(interp.out)}; vm: ${describe(vm.out)}",
                )
            trace(interp) != trace(vm) || writes(interp) != writes(vm) ->
                fail("performed-trace",
                    "interpreter performed ${trace(interp)} ${writes(interp)}; vm performed ${trace(vm)} ${writes(vm)}")
            vmAudit && interp.audits != vm.audits ->
                fail("audit-trace", "interpreter audit: ${interp.audits}; vm audit: ${vm.audits}")
        }
    }

    // ------------------------------------------------------------------
    // StateMachine-rooted programs
    // ------------------------------------------------------------------

    private fun checkMachine(
        s: Subject,
        grants: List<Pair<String, CapabilitySet>>,
        violations: MutableList<Violation>,
    ): CaseOutcome {
        val events = listOf<Value>(Value.IntV(1), Value.IntV(2))
        var performed = 0
        var denials = 0
        var full: Run? = null
        for ((label, grant) in grants) {
            FuzzLog.reset()
            val out = try {
                val trace = s.runtime.runMachine(
                    s.image, s.image.root, events, grant, s.verify.nodeTypes, s.verify.verifiedInterceptions,
                )
                val reason = trace.final.reason
                Out.Halt(
                    summary = "${reason::class.simpleName}:${trace.final.finalState}",
                    denial = reason is HaltReason.CapabilityDenial,
                    exhausted = reason is HaltReason.ResourceExhaustion,
                )
            } catch (e: InterpretException) {
                Out.Err(e.error)
            } catch (t: Throwable) {
                Out.Raw(t)
            }
            val run = Run(out, events(s))
            if (full == null) full = run
            performed += run.performed.size
            if (isDenial(run.out)) denials++
            // Runtime schema obligations are not enforced inside machine
            // transitions (Q-047's deferred scope), so S6 is not checked here.
            checkRun(s, "machine", label, grant, run, full, auditOn = true, schemaEnforced = false, violations)
        }
        return CaseOutcome.Checked(violations, vmSupported = false, performed, denials)
    }

    // ------------------------------------------------------------------
    // Per-run checks shared by every backend
    // ------------------------------------------------------------------

    private fun checkRun(
        s: Subject,
        backend: String,
        label: String,
        grant: CapabilitySet,
        run: Run,
        reference: Run,
        auditOn: Boolean,
        schemaEnforced: Boolean,
        violations: MutableList<Violation>,
    ) {
        fun fail(property: String, kind: String, detail: String) {
            violations += Violation(property, backend, kind, label, detail)
        }

        // S5: a raw JVM failure is never an acceptable outcome.
        (run.out as? Out.Raw)?.let { fail("S5", rawKind(it.thrown), "${it.thrown::class.qualifiedName}: ${it.thrown.message}") }

        // T1: type preservation at the root and at the foreign boundary.
        for (e in run.events) if (e is Event.Confused) fail("T1", "foreign-argument-type", "${e.target}: ${e.detail}")
        (run.out as? Out.Val)?.let { v ->
            if (s.case.mode == Mode.Expression && !conforms(v.value, s.case.rootTy)) {
                fail("T1", "root-value-type", "root typed ${s.case.rootTy} evaluated to ${v.value}")
            }
        }

        // S6: schema-typed parameters only ever receive conforming values.
        if (schemaEnforced) {
            for (e in run.events) {
                if (e !is Event.Consumed) continue
                val x = (e.value as? Value.IntV)?.v
                if (x == null || !Schemas.holds(e.schema, x)) {
                    fail("S6", "schema-invariant-bypassed", "value ${e.value} reached a Schema${e.schema}<Int> parameter")
                }
            }
        }

        // S1 on the audit log: an Allowed record names a category the closure contains.
        var denied = false
        for (e in run.events) {
            when (e) {
                is Event.Audit -> when (e.record.outcome) {
                    is AuditOutcome.Denied -> denied = true
                    AuditOutcome.Allowed ->
                        if (e.record.effectCategory !in s.bound) {
                            fail("S1", "audited-outside-closure:${e.record.effectCategory}",
                                "Allowed audit record for ${e.record.effectCategory}; bound is ${s.bound}")
                        }
                }
                is Event.Performed -> if (denied && e.ordered) {
                    fail("S2", "performed-after-denial", "${e.source.label} ran after a Denied audit record")
                }
                else -> Unit
            }
        }
        if (auditOn && denied && !isDenial(run.out)) {
            fail("A1", "denied-record-without-denial", "a Denied audit record was emitted but the run ended in ${describe(run.out)}")
        }

        for ((index, e) in run.events.withIndex()) {
            if (e !is Event.Performed) continue
            val who = e.source.label
            // The Allowed records for an ordered dispatch sit immediately
            // before it; an effect observed after the run is matched against
            // any Allowed record for its category.
            val allowed = LinkedHashMap<String, List<String>>()
            if (e.ordered) {
                var site: NodeId? = null
                var j = index - 1
                while (j >= 0) {
                    val record = (run.events[j] as? Event.Audit)?.record ?: break
                    if (record.outcome !is AuditOutcome.Allowed) break
                    if (j == index - 1) site = record.callSiteNodeId else if (record.callSiteNodeId != site) break
                    allowed.putIfAbsent(record.effectCategory, record.refinementParameters)
                    j--
                }
            } else {
                for (record in run.audits) {
                    if (record.outcome is AuditOutcome.Allowed) {
                        allowed.putIfAbsent(record.effectCategory, record.refinementParameters)
                    }
                }
            }
            for (cat in e.source.row) {
                // S1 on the ground truth.
                if (cat.categoryName !in s.bound) {
                    fail("S1", "performed-outside-closure:${cat.categoryName}",
                        "$who performed ${cat.categoryName}; bound is ${s.bound}")
                }
                // S2: category presence, dynamic scopes, refinement.
                val patterns = s.catIds[cat]?.let { grant.grants[it] }
                if (patterns == null) {
                    fail("S2", "performed-outside-grant:${cat.categoryName}", "$who performed ${cat.categoryName} under $label")
                    continue
                }
                for (tag in e.scopes) {
                    val caps = s.case.scopeCaps[tag] ?: continue
                    if (cat !in caps) {
                        fail("S2", "performed-outside-scope:${cat.categoryName}",
                            "$who performed ${cat.categoryName} inside a CapabilityScope retaining $caps")
                    }
                }
                val record = allowed[cat.categoryName]
                if (auditOn && record == null) {
                    fail("A1", "performed-without-audit-record:${cat.categoryName}",
                        "$who performed ${cat.categoryName} with no Allowed audit record at its dispatch")
                }
                if (cat.params.isEmpty()) continue
                if (e.source.resourceKnown) {
                    // The grant is the host's policy over the resource the
                    // builtin actually touched, whatever the program declared.
                    val truth = e.source.trueParams(cat, e.args).map(::render)
                    if (patterns.none { covers(it, truth) }) {
                        fail("S2", "refinement-not-covered:${cat.categoryName}",
                            "$who acted on $truth; grant holds ${patterns.map(::renderPattern)}")
                    }
                    if (e.ordered && record != null && record != truth) {
                        fail("S2", "audited-parameters-drift:${cat.categoryName}",
                            "$who acted on $truth but the audit record says $record")
                    }
                } else if (record != null) {
                    if (record.isEmpty()) {
                        if (patterns.none(::unrefined)) {
                            fail("S2", "unrefined-grant-required:${cat.categoryName}",
                                "$who ran uninstantiated under ${patterns.map(::renderPattern)}")
                        }
                    } else if (patterns.none { covers(it, record) }) {
                        fail("S2", "refinement-not-covered:${cat.categoryName}",
                            "$who ran with audited $record; grant holds ${patterns.map(::renderPattern)}")
                    }
                }
            }
        }

        // S2, monotonicity: a narrower grant only ever cuts the full-grant run short with a denial.
        if (run !== reference && !isUnstable(run.out) && !isUnstable(reference.out)) {
            val mine = trace(run)
            val theirs = trace(reference)
            if (mine.size > theirs.size || theirs.subList(0, mine.size) != mine ||
                !writes(reference).containsAll(writes(run))) {
                fail("S2", "trace-not-a-prefix",
                    "under $label: $mine ${writes(run)}; under the full grant: $theirs ${writes(reference)}")
            } else if (!isDenial(run.out) &&
                (!sameOutcome(run.out, reference.out) || mine != theirs || writes(run) != writes(reference))) {
                fail("S2", "diverged-without-denial",
                    "under $label: ${describe(run.out)} after $mine; under the full grant: ${describe(reference.out)} after $theirs")
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The effects performed in order (stand-ins, clock reads). */
    private fun trace(run: Run): List<Pair<String, List<Value>>> =
        run.performed.filter { it.ordered }.map { it.source.label to it.args }

    /** The files the run left behind, observed after it ended. */
    private fun writes(run: Run): Set<List<Value>> =
        run.performed.filter { !it.ordered }.mapTo(LinkedHashSet()) { it.args }

    private fun isDenial(out: Out): Boolean = when (out) {
        is Out.Err -> out.error is InterpretError.CapabilityViolation || out.error is InterpretError.RefinementViolation
        is Out.Halt -> out.denial
        else -> false
    }

    private fun isExhaustion(out: Out): Boolean = when (out) {
        is Out.Err -> out.error is InterpretError.ResourceExhaustion
        is Out.Halt -> out.exhausted
        else -> false
    }

    /** Outcomes the monotonicity comparison cannot use: raw failures and budget exhaustion. */
    private fun isUnstable(out: Out): Boolean = out is Out.Raw || isExhaustion(out)

    private fun sameOutcome(a: Out, b: Out): Boolean = when {
        a is Out.Val && b is Out.Val -> a.value == b.value
        a is Out.Halt && b is Out.Halt -> a == b
        a is Out.Err && b is Out.Err -> sameError(a.error, b.error)
        else -> false
    }

    private fun sameError(a: InterpretError, b: InterpretError): Boolean = when {
        a is InterpretError.CapabilityViolation && b is InterpretError.CapabilityViolation -> a.missing == b.missing
        a is InterpretError.RefinementViolation && b is InterpretError.RefinementViolation -> a.category == b.category
        else -> a::class == b::class
    }

    private fun shortKind(out: Out): String = when (out) {
        is Out.Val -> "value"
        is Out.Err -> out.error::class.simpleName ?: "error"
        is Out.Raw -> "raw"
        is Out.Halt -> "halt"
        Out.StaticSchema -> "static-schema"
    }

    private fun describe(out: Out): String = when (out) {
        is Out.Val -> "value ${out.value}"
        is Out.Err -> "${out.error::class.simpleName}: ${out.error}"
        is Out.Raw -> "raw ${out.thrown::class.qualifiedName}: ${out.thrown.message}"
        is Out.Halt -> "halt ${out.summary}"
        Out.StaticSchema -> "static schema violation"
    }

    /** A stable class for a raw failure: the exception type and its message with digits masked. */
    private fun rawKind(t: Throwable): String {
        val message = (t.message ?: "").lineSequence().firstOrNull().orEmpty().replace(Regex("[0-9]+"), "#").take(70)
        return "raw:${t::class.simpleName}:$message"
    }

    private fun conforms(v: Value, ty: Ty): Boolean = when (ty) {
        Ty.IntT -> v is Value.IntV
        Ty.StrT -> v is Value.StringV
        Ty.BoolT -> v is Value.BoolV
        Ty.OptInt -> v is Value.SumV &&
            ((v.case == "None" && v.payload == null) || (v.case == "Some" && v.payload is Value.IntV))
        Ty.ListInt -> {
            var cur: Value? = v
            var ok = true
            while (ok) {
                val sum = cur as? Value.SumV
                if (sum == null) { ok = false; break }
                if (sum.case == "Nil") break
                val cell = sum.payload as? Value.ProductV
                if (sum.case != "Cons" || cell == null || cell.fields["head"] !is Value.IntV) { ok = false; break }
                cur = cell.fields["tail"]
            }
            ok
        }
        else -> true
    }

    private fun render(v: Value): String = when (v) {
        is Value.StringV -> v.v
        is Value.IntV -> v.v.toString()
        else -> v.toString()
    }

    private fun renderPattern(p: CapabilityPattern): String = p.arguments.joinToString(", ", "{", "}") { a ->
        when (a) {
            CapabilityArgument.Wildcard -> "*"
            is CapabilityArgument.Concrete -> render(a.value)
        }
    }

    /**
     * Pattern coverage as a host means it, restated independently of
     * `CapabilitySet.covers`: a pattern with no concrete slot covers
     * anything; otherwise arities agree and each slot is a wildcard or
     * equals the requested value.
     */
    private fun covers(p: CapabilityPattern, requested: List<String>): Boolean {
        val slots = p.arguments
        if (slots.all { it is CapabilityArgument.Wildcard }) return true
        if (slots.size != requested.size) return false
        return slots.indices.all { i ->
            when (val slot = slots[i]) {
                CapabilityArgument.Wildcard -> true
                is CapabilityArgument.Concrete -> render(slot.value) == requested[i]
            }
        }
    }

    private fun unrefined(p: CapabilityPattern): Boolean =
        p.arguments.isNotEmpty() && p.arguments.all { it is CapabilityArgument.Wildcard }

    private fun resolve(spec: GrantSpec, catIds: Map<Cat, NodeId>): CapabilitySet {
        val grants = LinkedHashMap<NodeId, List<CapabilityPattern>>()
        for ((cat, patterns) in spec.patterns) {
            val id = catIds[cat] ?: continue // the program never mentions this category
            grants[id] = patterns.map { p ->
                if (p.sentinel) CapabilityPattern(listOf(CapabilityArgument.Wildcard))
                else CapabilityPattern(p.slots.map { slot ->
                    when (slot) {
                        Slot.Wild -> CapabilityArgument.Wildcard
                        is Slot.IntC -> CapabilityArgument.Concrete(Value.IntV(slot.v))
                        is Slot.StrC -> CapabilityArgument.Concrete(Value.StringV(slot.v))
                    }
                })
            }
        }
        return CapabilitySet(grants)
    }
}
