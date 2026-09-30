package org.strand.corpus.soundness

import org.strand.core.EvaluationLimits
import org.strand.interpreter.AuditRecord
import org.strand.interpreter.AuditSink
import org.strand.interpreter.Builtins
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.StaticCredentialProvider
import org.strand.interpreter.Value
import java.nio.file.Files
import java.nio.file.Path

/**
 * The effect vocabulary of generated programs. Each entry becomes one
 * EffectCategory node; [params] is the refinement parameter shape.
 */
enum class Cat(val categoryName: String, val params: List<Ty>) {
    A("Fuzz.A", emptyList()),
    B("Fuzz.B", emptyList()),
    P("Fuzz.P", listOf(Ty.IntT)),
    R("Fuzz.R", listOf(Ty.StrT, Ty.IntT));

    /** Author id of the category's node in every generated document. */
    val nodeId: String get() = "cat$name"
}

/** Signature family of an effectful callable. */
enum class Sig { II, SS }

/**
 * Effectful stand-in builtins, installed under the `strand-builtin:Test.`
 * namespace (exempt from the builtin effect floor, so the generator decides
 * how each ForeignNode declares its row). A stand-in performs no I/O: its
 * body appends a [Event.Performed] to [FuzzLog], which is the ground truth
 * for "this effect happened" that the properties compare the verifier's
 * closure, the grant, and the audit log against.
 *
 * [row] is what the stand-in performs. A [projected] stand-in is only ever
 * bound by ForeignNodes carrying Q-039 projections that name the argument
 * the effect acts on; [trueParams] is that resource, read off the arguments
 * the builtin actually received.
 */
enum class StandIn(val sig: Sig, val row: List<Cat>, val projected: Boolean) {
    FA(Sig.II, listOf(Cat.A), false),
    FB(Sig.II, listOf(Cat.B), false),
    FAB(Sig.II, listOf(Cat.A, Cat.B), false),
    FP(Sig.II, listOf(Cat.P), false),
    FPp(Sig.II, listOf(Cat.P), true),
    FR(Sig.II, listOf(Cat.R), false),
    FRp(Sig.II, listOf(Cat.R), true),
    FPAp(Sig.II, listOf(Cat.P, Cat.A), true),
    SA(Sig.SS, listOf(Cat.A), false);

    val target: String get() = "strand-builtin:Test.Fuzz.$name"

    /** The refinement parameters the performed effect truly has for [cat]. */
    fun trueParams(cat: Cat, args: List<Value>): List<Value> = when (cat) {
        Cat.A, Cat.B -> emptyList()
        Cat.P -> listOf(args[0])
        Cat.R -> listOf(Value.StringV(R_RESOURCE), args[0])
    }

    companion object {
        /** The literal first parameter every projected `Fuzz.R` binding pins. */
        const val R_RESOURCE = "res"
    }
}

/** One entry of the per-run ground-truth timeline. */
sealed class Event {
    /** A stand-in's body ran: the effects in `standIn.row` were performed. */
    data class Performed(val standIn: StandIn, val args: List<Value>, val scopes: List<Int>) : Event()

    /** The backend under test emitted a Q-055 audit record. */
    data class Audit(val record: AuditRecord) : Event()

    /** A value reached a parameter typed by schema [schema]. */
    data class Consumed(val schema: Int, val value: Value) : Event()

    /** A stand-in received an argument list its declared type rules out. */
    data class Confused(val target: String, val detail: String) : Event()
}

/**
 * Ground-truth log shared by the stand-ins and the audit sink. One run at a
 * time: [reset] before a run, read [events] after it.
 */
object FuzzLog {
    private val timeline = ArrayList<Event>()
    private val scopeStack = ArrayList<Int>()

    val events: List<Event> get() = timeline.toList()

    fun reset() {
        timeline.clear()
        scopeStack.clear()
    }

    fun add(e: Event) { timeline += e }
    fun enterScope(tag: Int) { scopeStack += tag }
    fun exitScope(tag: Int) { scopeStack.remove(tag as Any) }
    fun scopes(): List<Int> = scopeStack.toList()

    val sink: AuditSink = AuditSink { timeline += Event.Audit(it) }
}

/** The two schemas generated programs use, both over `Int`. */
object Schemas {
    const val COUNT = 2

    /** Bound of schema [k]: schema 0 holds `x > 3`, schema 1 holds `x < 7`. */
    fun holds(k: Int, x: Long): Boolean = if (k == 0) x > 3 else x < 7
}

/**
 * Installs the stand-ins and builds the host policy generated programs run
 * under. Nothing here touches the network, a process, or the wall clock;
 * the sandbox is rooted at a throwaway directory that no stand-in writes.
 */
object FuzzHost {
    const val ENTER = "strand-builtin:Test.Fuzz.Enter"
    const val EXIT = "strand-builtin:Test.Fuzz.Exit"
    const val CALL_TOOL = "strand-builtin:Test.Fuzz.CallTool"
    fun consume(k: Int): String = "strand-builtin:Test.Fuzz.Consume$k"

    /** Evaluation budget for one run: small programs, fast failure on runaways. */
    val limits: EvaluationLimits = EvaluationLimits.DEFAULTS.copy(
        maxSteps = 200_000L,
        maxStackDepth = 300,
        maxAllocatedValues = 200_000L,
        wallClockBudgetMillis = 10_000L,
    )

    private var workspace: Path? = null

    fun install() {
        for (s in StandIn.entries) {
            Builtins.installTestBuiltin(s.target, effectful = true, Builtins.Determinism.Stateful) { _, args ->
                when (s.sig) {
                    Sig.II -> {
                        val x = intArg(s.target, args)
                        FuzzLog.add(Event.Performed(s, args, FuzzLog.scopes()))
                        Value.IntV((x + s.ordinal + 1) % 10)
                    }
                    Sig.SS -> {
                        val x = args.singleOrNull() as? Value.StringV
                            ?: confused(s.target, "expected (String), got ${describe(args)}")
                        FuzzLog.add(Event.Performed(s, args, FuzzLog.scopes()))
                        Value.StringV(x.v.take(3) + "x")
                    }
                }
            }
        }
        // Dynamic CapabilityScope markers: effect-free, so they run under any
        // grant, and they tell the log which scopes enclose a performed effect.
        Builtins.installTestBuiltin(ENTER, effectful = false, Builtins.Determinism.Deterministic) { _, args ->
            FuzzLog.enterScope(intArg(ENTER, args).toInt())
            Value.IntV(0)
        }
        Builtins.installTestBuiltin(EXIT, effectful = false, Builtins.Determinism.Deterministic) { _, args ->
            FuzzLog.exitScope(intArg(EXIT, args).toInt())
            Value.IntV(0)
        }
        for (k in 0 until Schemas.COUNT) {
            val target = consume(k)
            Builtins.installTestBuiltin(target, effectful = false, Builtins.Determinism.Deterministic) { _, args ->
                val v = args.singleOrNull() ?: confused(target, "expected 1 arg, got ${describe(args)}")
                FuzzLog.add(Event.Consumed(k, v))
                v
            }
        }
        // Applies a ToolDef's implementation, standing in for a provider's
        // tool-use loop. Higher-order, so the dispatch is a propagating site.
        Builtins.installTestHigherOrderBuiltin(
            CALL_TOOL, effectful = false, Builtins.Determinism.Deterministic,
        ) { _, args, apply ->
            val tool = args.getOrNull(0) as? Value.ToolDefV
                ?: confused(CALL_TOOL, "expected (ToolDef, Int), got ${describe(args)}")
            apply.apply(tool.implementation, listOf(args[1]))
        }
        workspace = Files.createTempDirectory("strand-soundness-fuzz")
    }

    fun uninstall() {
        Builtins.clearTestBuiltins()
        workspace?.let { root ->
            runCatching {
                Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
        workspace = null
    }

    /** The policy every backend runs under; its audit sink feeds [FuzzLog]. */
    fun policy(): HostPolicy = HostPolicy.SECURE.copy(
        limits = limits,
        sandbox = SandboxPolicy.SECURE_DEFAULT.copy(
            fs = FsPolicy(
                workspaceRoot = checkNotNull(workspace) { "FuzzHost.install() was not called" },
                escape = EscapePolicy.Deny,
                followSymlinks = false,
            ),
        ),
        clock = Builtins.FixedClock(Builtins.FIXED_REPLAY_TIMESTAMP),
        random = java.util.Random(0L),
        credentialProvider = StaticCredentialProvider(emptyMap()),
        exitHandler = Builtins.TestExitHandler(),
        auditSink = FuzzLog.sink,
    )

    private fun intArg(target: String, args: List<Value>): Long =
        (args.singleOrNull() as? Value.IntV)?.v ?: confused(target, "expected (Int), got ${describe(args)}")

    private fun describe(args: List<Value>): String =
        args.joinToString(", ", "(", ")") { it::class.simpleName ?: "?" }

    /**
     * Record a type confusion and fail the call. The `IllegalArgumentException`
     * surfaces as a structured `BuiltinContractViolation`; the log entry is
     * what the type-preservation check reads.
     */
    private fun confused(target: String, detail: String): Nothing {
        FuzzLog.add(Event.Confused(target, detail))
        throw IllegalArgumentException("$target: $detail")
    }
}
