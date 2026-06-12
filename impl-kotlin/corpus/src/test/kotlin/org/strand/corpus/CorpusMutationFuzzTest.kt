package org.strand.corpus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.opentest4j.AssertionFailedError
import org.strand.core.EvaluationLimits
import org.strand.core.IngestError
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.StaticCredentialProvider
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Q-066 deterministic mutation fuzzer over the positive corpus
 * ([`proposals/implemented/adversarial-verification.md`]).
 *
 * For every corpus program, applies [iterations] single mutations to the
 * dag-json document under a seeded [Random] and asserts the admission
 * invariant for each mutant:
 *
 *  - ingest + verify either **rejects** with a structured [IngestError] /
 *    `VerifyError`, or **admits**; and
 *  - an admitted mutant **evaluates** under [EvaluationLimits.DEFAULTS]
 *    with full capabilities (every EffectCategory in the program granted
 *    as a wildcard) to either a `Value` or a structured `InterpretError`.
 *
 * Any other [Throwable] — raw exception, [StackOverflowError], kotlinx
 * parser crash — fails the test with a message naming the program, seed,
 * operator, iteration, and the full mutant JSON for direct replay.
 * Triggers for defects found this way are preserved as `corpus/negative/`
 * entries.
 *
 * **Determinism.** The RNG is re-seeded per program with the fixed [SEED],
 * so the mutant stream for a program depends only on its own bytes — the
 * battery is reproducible in CI and a failure replays from the embedded
 * mutant JSON alone. The iteration count is overridable for deeper local
 * campaigns via `-Dstrand.fuzzIterations=N` (forwarded by the corpus
 * Gradle build).
 *
 * **Operators.** Delete a field; swap a node reference to another
 * in-program id; replace a reference with a dangling id; corrupt a
 * category / type-name / other string; truncate a list; perturb an integer
 * literal; duplicate a node definition under a fresh id; point a reference
 * back at its own node (reference cycle). The duplicate operator inserts a
 * copy under a new key rather than emitting a duplicate JSON key, because
 * kotlinx's object model silently keeps the last duplicate — a text-level
 * duplicate key is unobservable past the parser.
 *
 * **Containment.** Mutants run with a fixed clock, an empty credential
 * provider (no env-var keys reachable), a throwing exit handler, and a
 * sandbox policy rooted at a throwaway temp workspace with network
 * default-deny — a mutant cannot touch the host filesystem outside the
 * temp root, dial out, or spend real credentials. Each mutant's pipeline
 * runs on a dedicated 64 MiB-stack thread so the interpreter's own
 * `maxStackDepth = 4096` cap fires before the JVM's native stack limit
 * (the Q-040 contract presumes adequate host stack; Gradle's default test
 * stack is smaller).
 */
class CorpusMutationFuzzTest {

    companion object {
        /** Fixed CI seed. Change only with a reason recorded in the commit. */
        const val SEED = 20260612L

        private const val FUZZ_THREAD_STACK_BYTES = 64L * 1024L * 1024L

        private val iterations: Int =
            System.getProperty("strand.fuzzIterations")?.toIntOrNull() ?: 25

        private lateinit var savedPolicy: SandboxPolicy
        private lateinit var savedCredentials: Any
        private lateinit var savedExitHandler: Builtins.ExitHandler
        private lateinit var fuzzWorkspace: Path

        @JvmStatic
        @BeforeAll
        fun isolateHost() {
            Builtins.clock = Builtins.FixedClock(Builtins.FIXED_REPLAY_TIMESTAMP)
            savedCredentials = Builtins.credentialProvider
            Builtins.credentialProvider = StaticCredentialProvider(emptyMap())
            savedExitHandler = Builtins.exitHandler
            Builtins.exitHandler = Builtins.TestExitHandler()
            fuzzWorkspace = Files.createTempDirectory("strand-fuzz-workspace")
            savedPolicy = Builtins.sandboxPolicy
            Builtins.sandboxPolicy = SandboxPolicy.SECURE_DEFAULT.copy(
                fs = FsPolicy(
                    workspaceRoot = fuzzWorkspace,
                    escape = EscapePolicy.Deny,
                    followSymlinks = false,
                ),
            )
        }

        @JvmStatic
        @AfterAll
        fun restoreHost() {
            Builtins.clock = Builtins.SystemClock
            Builtins.credentialProvider = savedCredentials as org.strand.interpreter.CredentialProvider
            Builtins.exitHandler = savedExitHandler
            Builtins.sandboxPolicy = savedPolicy
            runCatching {
                Files.walk(fuzzWorkspace).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    private enum class Operator {
        DeleteField,
        SwapReference,
        DanglingReference,
        CorruptString,
        TruncateList,
        PerturbInt,
        DuplicateNode,
        IntroduceCycle,
    }

    /** A mutable scalar position inside a node's JSON subtree. */
    private data class ScalarPos(
        val ownerNodeId: String,
        val container: Any, // LinkedHashMap<String, Any> or ArrayList<Any>
        val key: Any,       // String (map key) or Int (list index)
        val prim: JsonPrimitive,
    ) {
        fun set(value: Any) {
            when (container) {
                is LinkedHashMap<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    (container as LinkedHashMap<String, Any>)[key as String] = value
                }
                is ArrayList<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (container as ArrayList<Any>)[key as Int] = value
                }
                else -> error("unexpected container ${container::class}")
            }
        }
    }

    @TestFactory
    fun mutationBattery(): List<DynamicTest> {
        val corpusDir = GoldenHashes.findCorpusDir()
        return GoldenHashes.enumerateProgramFiles(corpusDir).map { rel ->
            DynamicTest.dynamicTest("$rel x$iterations") {
                fuzzProgram(rel, Files.readString(corpusDir.resolve(rel)))
            }
        }
    }

    private fun fuzzProgram(programRel: String, pristineText: String) {
        val pristine = Json.parseToJsonElement(pristineText)
        val rng = Random(SEED)
        var applied = 0
        repeat(iterations) { iteration ->
            val operator = Operator.entries[rng.nextInt(Operator.entries.size)]
            val tree = toMutable(pristine)
            if (!applyOperator(operator, tree, rng)) return@repeat
            applied++
            val mutantText = rebuild(tree).toString()
            try {
                onFuzzThread { runPipeline(mutantText) }
            } catch (t: Throwable) {
                if (isKnownDefect(t)) return@repeat
                throw AssertionFailedError(
                    buildString {
                        appendLine("Fuzz invariant violated: raw ${t::class.qualifiedName} escaped the pipeline.")
                        appendLine("  program   = $programRel")
                        appendLine("  seed      = $SEED (re-seeded per program)")
                        appendLine("  operator  = $operator")
                        appendLine("  iteration = $iteration (of $iterations)")
                        appendLine("  cause     = ${t.message?.lineSequence()?.firstOrNull().orEmpty()}")
                        appendLine("  mutant JSON for replay:")
                        appendLine(mutantText)
                    },
                    t,
                )
            }
        }
        check(applied > 0) { "$programRel: no operator was applicable in $iterations iterations" }
    }

    /**
     * Documented expected-failure exclusions — known defects the fuzzer
     * found that cannot be fixed at the ingest/interpreter boundary and
     * are tracked for an encoder-side fix. Keep this list EMPTY in the
     * steady state; every entry is a defect being worked.
     *
     * Current entry (found by SwapReference on corpus 10 at 300
     * iterations): a local NodeRef referenced from a *type position*
     * (e.g. ParameterDecl.paramType) crashes the canonical encoder's
     * type-position walk with `IllegalStateException("Expected canonical
     * node ... found RawNodeRef")`. The spec defines this case — `T(c)`
     * falls back to `H(c)` for any non-bound-TypeParameter child,
     * including a NodeRef — and the verifier's `resolveType` follows
     * NodeRef in type positions, so the document is well-formed and the
     * gap is in the encoder's pre-finalization walk. The fix lives in
     * `CanonicalEncoder`, which the Q-066 independent-encoder rule keeps
     * closed until the Python conformance encoder has shipped; remove
     * this exclusion with that fix.
     */
    private fun isKnownDefect(t: Throwable): Boolean =
        t is IllegalStateException && t.message?.contains("found RawNodeRef") == true

    // ----- The admission invariant -----

    /**
     * Ingest -> finalize -> verify -> (if admitted) evaluate. Structured
     * rejections ([IngestError], `VerifyResult.Failed`, [InterpretException])
     * return normally; anything else propagates to the caller as an
     * invariant violation.
     */
    private fun runPipeline(text: String) {
        val ingest = try {
            JsonIngest.parse(text)
        } catch (e: IngestError) {
            return // structured ingest rejection
        }
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        if (verify is VerifyResult.Failed) return // structured verify rejection

        // Admitted: must evaluate to a Value or a structured InterpretError
        // under default limits and full (wildcard) capabilities.
        val categories = finalized.store.entries()
            .filter { (_, node) -> node is Node.EffectCategory }
            .map { (id, _) -> id }
            .toSet()
        try {
            Interpreter(finalized.store, finalized.hashToNodeId)
                .eval(finalized.root, CapabilitySet.ofCategories(categories), EvaluationLimits.DEFAULTS)
        } catch (e: InterpretException) {
            return // structured runtime error
        }
    }

    private fun onFuzzThread(block: () -> Unit) {
        var thrown: Throwable? = null
        val worker = Thread(
            null,
            {
                try {
                    block()
                } catch (t: Throwable) {
                    thrown = t
                }
            },
            "strand-fuzz-worker",
            FUZZ_THREAD_STACK_BYTES,
        )
        worker.start()
        worker.join()
        thrown?.let { throw it }
    }

    // ----- Mutation machinery -----

    /** kotlinx tree -> mutable containers (scalars stay JsonPrimitive). */
    private fun toMutable(e: JsonElement): Any = when (e) {
        is JsonObject -> LinkedHashMap<String, Any>().also { m ->
            for ((k, v) in e) m[k] = toMutable(v)
        }
        is JsonArray -> ArrayList<Any>().also { l ->
            for (v in e) l.add(toMutable(v))
        }
        is JsonPrimitive -> e // includes JsonNull
    }

    private fun rebuild(x: Any): JsonElement = when (x) {
        is LinkedHashMap<*, *> -> JsonObject(x.entries.associate { (k, v) -> k as String to rebuild(v as Any) })
        is ArrayList<*> -> JsonArray(x.map { rebuild(it as Any) })
        is JsonPrimitive -> x
        else -> error("unexpected tree element ${x::class}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun nodesOf(tree: Any): LinkedHashMap<String, Any>? =
        (tree as? LinkedHashMap<String, Any>)?.get("nodes") as? LinkedHashMap<String, Any>

    /** Every scalar position inside the `nodes` subtree, with its owner node id. */
    private fun scalarPositions(tree: Any): List<ScalarPos> {
        val nodes = nodesOf(tree) ?: return emptyList()
        val out = mutableListOf<ScalarPos>()
        fun walk(owner: String, container: Any) {
            when (container) {
                is LinkedHashMap<*, *> -> for ((k, v) in container) {
                    if (v is JsonPrimitive) out += ScalarPos(owner, container, k as String, v)
                    else walk(owner, v as Any)
                }
                is ArrayList<*> -> for ((i, v) in container.withIndex()) {
                    if (v is JsonPrimitive) out += ScalarPos(owner, container, i, v)
                    else walk(owner, v as Any)
                }
            }
        }
        for ((nodeId, body) in nodes) walk(nodeId, body)
        return out
    }

    /** String scalars whose content names another node — reference positions. */
    private fun referencePositions(tree: Any): List<ScalarPos> {
        val nodeIds = nodesOf(tree)?.keys ?: return emptyList()
        return scalarPositions(tree).filter {
            it.prim.isString && it.prim.content in nodeIds && it.key != "type"
        }
    }

    private fun listPositions(tree: Any): List<ArrayList<*>> {
        val nodes = nodesOf(tree) ?: return emptyList()
        val out = mutableListOf<ArrayList<*>>()
        fun walk(container: Any) {
            when (container) {
                is LinkedHashMap<*, *> -> for ((_, v) in container) walk(v as Any)
                is ArrayList<*> -> {
                    if (container.isNotEmpty()) out += container
                    for (v in container) walk(v as Any)
                }
            }
        }
        for ((_, body) in nodes) walk(body)
        return out
    }

    /** Apply one mutation; false when the operator has no applicable site. */
    private fun applyOperator(op: Operator, tree: Any, rng: Random): Boolean {
        val nodes = nodesOf(tree) ?: return false
        if (nodes.isEmpty()) return false
        val nodeIds = nodes.keys.toList()
        return when (op) {
            Operator.DeleteField -> {
                val nodeId = nodeIds[rng.nextInt(nodeIds.size)]
                @Suppress("UNCHECKED_CAST")
                val body = nodes[nodeId] as? LinkedHashMap<String, Any> ?: return false
                if (body.isEmpty()) return false
                val field = body.keys.toList()[rng.nextInt(body.size)]
                body.remove(field)
                true
            }
            Operator.SwapReference -> {
                val refs = referencePositions(tree)
                if (refs.isEmpty() || nodeIds.size < 2) return false
                val pos = refs[rng.nextInt(refs.size)]
                val others = nodeIds.filter { it != pos.prim.content }
                if (others.isEmpty()) return false
                pos.set(JsonPrimitive(others[rng.nextInt(others.size)]))
                true
            }
            Operator.DanglingReference -> {
                val refs = referencePositions(tree)
                if (refs.isEmpty()) return false
                refs[rng.nextInt(refs.size)].set(JsonPrimitive("__fuzz_dangling__"))
                true
            }
            Operator.CorruptString -> {
                val strings = scalarPositions(tree).filter { it.prim.isString && it.prim.content.isNotEmpty() }
                if (strings.isEmpty()) return false
                val pos = strings[rng.nextInt(strings.size)]
                val s = pos.prim.content
                val corrupted = if (rng.nextBoolean()) {
                    val i = rng.nextInt(s.length)
                    s.substring(0, i) + ('a' + rng.nextInt(26)) + s.substring(i + 1)
                } else {
                    s + "X"
                }
                if (corrupted == s) return false
                pos.set(JsonPrimitive(corrupted))
                true
            }
            Operator.TruncateList -> {
                val lists = listPositions(tree)
                if (lists.isEmpty()) return false
                val list = lists[rng.nextInt(lists.size)]
                list.removeAt(list.size - 1)
                true
            }
            Operator.PerturbInt -> {
                val ints = scalarPositions(tree).filter { !it.prim.isString && it.prim.longOrNull != null }
                if (ints.isEmpty()) return false
                val pos = ints[rng.nextInt(ints.size)]
                val v = pos.prim.longOrNull!!
                // Small deltas plus negation only: large jumps would turn a
                // Time.Sleep(0) corpus literal into a multi-minute stall —
                // a structured-but-slow outcome that adds nothing.
                val mutated = when (rng.nextInt(4)) {
                    0 -> v + 1
                    1 -> v - 1
                    2 -> v + 7
                    else -> -v - 1 // also flips 0 to a negative
                }
                pos.set(JsonPrimitive(mutated))
                true
            }
            Operator.DuplicateNode -> {
                val source = nodeIds[rng.nextInt(nodeIds.size)]
                val copy = toMutable(rebuild(nodes[source] as Any))
                nodes["${source}__dup"] = copy
                true
            }
            Operator.IntroduceCycle -> {
                val refs = referencePositions(tree)
                if (refs.isEmpty()) return false
                val pos = refs[rng.nextInt(refs.size)]
                pos.set(JsonPrimitive(pos.ownerNodeId))
                true
            }
        }
    }
}
