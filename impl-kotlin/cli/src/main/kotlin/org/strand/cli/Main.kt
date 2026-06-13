package org.strand.cli

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.strand.authoring.Authoring
import org.strand.authoring.AuthoringException
import org.strand.authoring.ConstraintGrammar
import org.strand.authoring.LayerAGrammar
import org.strand.authoring.PreludeModule
import org.strand.core.ErrorVerbosity
import org.strand.core.EvaluationLimits
import org.strand.core.Hash
import org.strand.core.IngestError
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.CachingResolver
import org.strand.hashing.ChainedResolver
import org.strand.hashing.FederatedProgram
import org.strand.hashing.FinalizedProgram
import org.strand.hashing.Hasher
import org.strand.hashing.LocalProgramResolver
import org.strand.hashing.NameRegistry
import org.strand.hashing.federated
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.HostPattern
import org.strand.interpreter.Interpreter
import org.strand.interpreter.InterpretException
import org.strand.interpreter.NetPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.Value
import org.strand.runtime.EventCodec
import org.strand.runtime.MachineGroup
import org.strand.runtime.RuntimeMetrics
import org.strand.runtime.StateMachineRuntime
import org.strand.runtime.Trace
import org.strand.runtime.TraceStep
import org.strand.schema.SchemaChecker
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import java.io.File
import kotlin.system.exitProcess

/**
 * Q-040 + Q-041 + Q-042 CLI flag set. `--max-steps`, `--max-stack-depth`,
 * `--max-allocated-values`, `--wall-clock-ms`, `--max-json-depth`,
 * `--max-node-count`, and `--max-ingest-bytes` all take a single
 * numeric value (Long for the {steps, allocated, wall-clock, ingest-bytes}
 * dimensions; Int for {stack-depth, json-depth, node-count}). Q-042
 * adds `--error-verbosity {redacted|full|kind-only}` for the credential-
 * isolation surface. Absent flags inherit from [EvaluationLimits.DEFAULTS].
 *
 * Q-041 adds `--workspace-root <path>`, `--allow-fs-escape`,
 * `--allow-host <glob>` (repeatable), and `--allow-net-internal` for
 * the sandbox surface. Absent flags inherit from
 * [SandboxPolicy.SECURE_DEFAULT] — CLI invocations are agent-facing so
 * they get the default-deny policy. (The library default on
 * [Builtins.sandboxPolicy] is the open variant so the 895-test
 * baseline runs unchanged; CLI overrides the singleton at startup.)
 *
 * Returns the parsed [EvaluationLimits], the parsed [SandboxPolicy],
 * and the residual flag set (flags not in the Q-040 / Q-041 / Q-042
 * vocabulary, for the per-subcommand parser to dispatch against
 * `--grant-all` / `--metrics` / `--emit-json` / etc.).
 */
private fun parseLimits(flags: List<String>): Triple<EvaluationLimits, SandboxPolicy, Set<String>> {
    var limits = EvaluationLimits.DEFAULTS
    // CLI default is secure; flags relax it.
    var fsPolicy = SandboxPolicy.SECURE_DEFAULT.fs
    var netPolicy = SandboxPolicy.SECURE_DEFAULT.net
    val remaining = mutableSetOf<String>()
    var i = 0
    while (i < flags.size) {
        val flag = flags[i]
        val v = { f: String ->
            val raw = flags.getOrNull(i + 1)
                ?: error("$f requires an argument")
            i++
            raw
        }
        when (flag) {
            "--max-steps" -> {
                val n = v(flag).toLongOrNull() ?: error("--max-steps requires a Long")
                limits = limits.copy(maxSteps = n)
            }
            "--max-stack-depth" -> {
                val n = v(flag).toIntOrNull() ?: error("--max-stack-depth requires an Int")
                limits = limits.copy(maxStackDepth = n)
            }
            "--max-allocated-values" -> {
                val n = v(flag).toLongOrNull() ?: error("--max-allocated-values requires a Long")
                limits = limits.copy(maxAllocatedValues = n)
            }
            "--wall-clock-ms" -> {
                val n = v(flag).toLongOrNull() ?: error("--wall-clock-ms requires a Long")
                limits = limits.copy(wallClockBudgetMillis = n)
            }
            "--stream-receive-timeout-ms" -> {
                val n = v(flag).toLongOrNull() ?: error("--stream-receive-timeout-ms requires a Long")
                limits = limits.copy(streamReceiveTimeoutMillis = n)
            }
            "--max-json-depth" -> {
                val n = v(flag).toIntOrNull() ?: error("--max-json-depth requires an Int")
                limits = limits.copy(maxJsonDepth = n)
            }
            "--max-node-count" -> {
                val n = v(flag).toIntOrNull() ?: error("--max-node-count requires an Int")
                limits = limits.copy(maxNodeCount = n)
            }
            "--max-ingest-bytes" -> {
                val n = v(flag).toLongOrNull() ?: error("--max-ingest-bytes requires a Long")
                limits = limits.copy(maxIngestBytes = n)
            }
            "--error-verbosity" -> {
                val raw = v(flag)
                val verbosity = when (raw) {
                    "redacted" -> ErrorVerbosity.Redacted
                    "full" -> {
                        // Q-042 § 4.5: Full mode logs a warning at runtime
                        // entry so operators see the non-default surfacing.
                        System.err.println(
                            "warning: --error-verbosity=full surfaces unscrubbed IoFailure detail " +
                                "(may include credential values); use only in dev/debug environments"
                        )
                        ErrorVerbosity.Full
                    }
                    "kind-only" -> ErrorVerbosity.RedactedWithKindOnly
                    else -> error("--error-verbosity expects {redacted|full|kind-only}, got '$raw'")
                }
                limits = limits.copy(errorVerbosity = verbosity)
            }
            "--workspace-root" -> {
                val rawPath = v(flag)
                val root = java.nio.file.Paths.get(rawPath).toAbsolutePath()
                fsPolicy = fsPolicy.copy(workspaceRoot = root)
            }
            "--allow-fs-escape" -> {
                fsPolicy = fsPolicy.copy(escape = EscapePolicy.Allow)
            }
            "--allow-host" -> {
                val pattern = v(flag)
                netPolicy = netPolicy.copy(
                    allowedHosts = netPolicy.allowedHosts + HostPattern(pattern),
                )
            }
            "--allow-net-internal" -> {
                netPolicy = netPolicy.copy(defaultDeny = false, blockedRanges = emptyList())
            }
            else -> remaining += flag
        }
        i++
    }
    return Triple(limits, SandboxPolicy(fs = fsPolicy, net = netPolicy), remaining)
}

/**
 * Q-043 step 3a: pull `--peer-store <path>` occurrences (repeatable) out of the
 * flag list, returning the peer-store paths in command-line order plus the
 * residual flags (for [parseLimits]). Each `--peer-store` consumes the
 * following token as its path argument.
 */
private fun extractPeerStores(flags: List<String>): Pair<List<String>, List<String>> {
    val peers = mutableListOf<String>()
    val rest = mutableListOf<String>()
    var i = 0
    while (i < flags.size) {
        if (flags[i] == "--peer-store") {
            peers += flags.getOrNull(i + 1) ?: error("--peer-store requires a path argument")
            i += 2
        } else {
            rest += flags[i]
            i++
        }
    }
    return peers to rest
}

/**
 * Q-043 step 3a: wrap [app] as a [FederatedProgram] whose resolver chains a
 * [LocalProgramResolver] over each `--peer-store` program (in command-line
 * order), or return null when no peer stores were supplied (the single-store
 * path, preserved bit-for-bit). Peer programs are finalized but not separately
 * verified — a fetched subgraph is verified on admission, and the post-admission
 * re-hash rejects any corrupted fetch.
 *
 * Unless [noCache] is set, the chain is wrapped in a [CachingResolver] so a hash
 * fetched more than once in a session hits the underlying peers only once
 * (semantics are identical with or without caching). The integrity check (the
 * per-target Merkle root re-hash in `fetchAndAdmit`) is always on regardless of
 * any flag — see the `--strict-integrity` handling at the call sites, which is
 * an explicit-declaration no-op per the proposal § 6.
 */
private fun federateWithPeers(
    app: FinalizedProgram,
    peerPaths: List<String>,
    limits: EvaluationLimits,
    noCache: Boolean,
): FederatedProgram? {
    if (peerPaths.isEmpty()) return null
    val peerResolvers = peerPaths.map { peerPath ->
        val peerFinal = try {
            loadFinalized(File(peerPath).readText(), limits)
        } catch (e: IngestError) {
            System.err.println("peer store ingest failed ($peerPath): ${e.message}")
            exitProcess(1)
        }
        LocalProgramResolver(peerFinal)
    }
    val chained = ChainedResolver(peerResolvers)
    val resolver = if (noCache) chained else CachingResolver(chained)
    return app.federated(resolver)
}

/**
 * Q-043 § 6: `--no-cache` and `--strict-integrity` only mean something when a
 * federation chain exists. `--strict-integrity` is purely an explicit-
 * declaration flag — the per-target Merkle root re-hash in `fetchAndAdmit` is
 * unconditional, so federated runs are integrity-checked whether or not it is
 * passed. Warn when either flag is supplied with no `--peer-store` so a typo'd
 * scripted invocation does not silently do nothing.
 */
private fun reportFederationFlags(peerPaths: List<String>, noCache: Boolean, strictIntegrity: Boolean) {
    if (peerPaths.isEmpty() && (noCache || strictIntegrity)) {
        System.err.println(
            "note: --no-cache / --strict-integrity have no effect without --peer-store"
        )
    }
}

/**
 * Parse, finalize, and return the canonical [FinalizedProgram] alongside
 * the ingest result. The ingest is preserved (rather than just the
 * finalized form) so commands that need the author-id-to-NodeId map
 * (`strand group`'s routed events) can resolve user-named streams.
 */
private fun loadFinalizedWithIngest(text: String, limits: EvaluationLimits = EvaluationLimits.DEFAULTS): Pair<JsonIngest.IngestResult, FinalizedProgram> {
    val ingest = JsonIngest.parse(text, limits)
    val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
    return ingest to finalized
}

/**
 * Parse, finalize, and return the canonical [FinalizedProgram]. Every
 * command after the JSON-parse step operates on the finalized form — the
 * verifier and interpreter need the canonical [NodeStore] (with
 * Hash-bearing NodeRefs) plus the `hashToNodeId` reverse map.
 */
private fun loadFinalized(text: String, limits: EvaluationLimits = EvaluationLimits.DEFAULTS): FinalizedProgram =
    loadFinalizedWithIngest(text, limits).second

/**
 * Install the per-run host context on the [Builtins] singleton, run [block],
 * and restore the prior values in a `finally`. Three slots are installed:
 *
 *  * [Builtins.sandboxPolicy] — Q-041. The library default is open; CLI
 *    invocations override to secure (or to whatever the flags request).
 *  * [Builtins.streamReceiveTimeoutMillis] — Q-045 per-read streaming-receive
 *    ceiling, taken from [EvaluationLimits.streamReceiveTimeoutMillis].
 *  * [Builtins.verifierNodeTypes] — the verifier's `nodeTypes` map from the
 *    successful `VerifyResult.Ok`, so the N-044 ToolDef parameter-schema and
 *    N-045 ResponseSchemaSpec projections can resolve a Schema NodeId to its
 *    `TypeExpr.SchemaType`. Without this the LLM tool-dispatch path silently
 *    degrades to empty `{}` JSON schemas on every real CLI run.
 *
 * Every CLI path that evaluates a verified program (`run`, `machine`,
 * `group`) goes through this helper so the install/restore discipline cannot
 * drift between subcommands. Internal (not private) so the CLI test suite
 * can prove the wiring directly.
 */
internal fun <T> withProgramEvaluationContext(
    sandboxPolicy: SandboxPolicy,
    limits: EvaluationLimits,
    verifierNodeTypes: Map<NodeId, org.strand.verifier.TypeExpr>?,
    block: () -> T,
): T {
    val priorSandbox = Builtins.sandboxPolicy
    val priorStreamTimeout = Builtins.streamReceiveTimeoutMillis
    val priorNodeTypes = Builtins.verifierNodeTypes
    Builtins.sandboxPolicy = sandboxPolicy
    Builtins.streamReceiveTimeoutMillis = limits.streamReceiveTimeoutMillis
    Builtins.verifierNodeTypes = verifierNodeTypes
    try {
        return block()
    } finally {
        Builtins.sandboxPolicy = priorSandbox
        Builtins.streamReceiveTimeoutMillis = priorStreamTimeout
        Builtins.verifierNodeTypes = priorNodeTypes
    }
}

/**
 * Build a permissive [CapabilitySet] that grants wildcard patterns for every
 * EffectCategory NodeId reachable in the verified store. Used by the
 * `--grant-all` CLI flag so capability-requiring corpus programs can run
 * end-to-end from the command line without a policy file. This is demo /
 * dev-mode only — production deployments build CapabilitySets from policy.
 */
private fun grantAllCapabilities(finalized: FinalizedProgram): CapabilitySet {
    val categories: Set<NodeId> = finalized.store.entries()
        .filter { it.second is Node.EffectCategory }
        .map { it.first }
        .toSet()
    return CapabilitySet.ofCategories(categories)
}

/**
 * CLI for the Strand reference implementation.
 *
 * Usage:
 *   strand verify  <file.json>
 *   strand run     <file.json>
 *   strand machine <file.json> --events <events.json>
 *   strand group   <file.json> --events <events.json>
 *   strand author  <file.layer-a|file.familiar> [--emit-json] [--surface layer-a|familiar]
 *   strand grammar
 *
 * `verify` ingests and type-checks; `run` additionally evaluates pure
 * programs (Layer 1–5); `machine` drives a single StateMachine over a JSON
 * event list (Layer 6 step 1, synchronous fold); `group` drives every
 * StateMachine reachable in the program as a MachineGroup over a routed
 * event list (Layer 6 step 2, per-machine coroutine actors); `author`
 * compiles a Layer A authoring-format file (Q-034 step 1) to canonical
 * dag-json (always elaborating absent annotations) and runs the verifier
 * — with `--emit-json` it prints the generated JSON instead of running
 * the pipeline; `grammar` emits the Layer B GBNF constraint grammar.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        exitProcess(2)
    }
    when (val command = args[0]) {
        "verify", "run" -> runVerifyOrEval(command, args)
        "machine" -> runMachine(args)
        "group" -> runGroup(args)
        "author" -> runAuthor(args)
        "translate" -> runTranslate(args)
        "registry" -> runRegistry(args)
        "grammar" -> runGrammar(args)
        else -> {
            usage()
            exitProcess(2)
        }
    }
}

/**
 * Q-034 step 1 Layer B: emit the constraint grammar (GBNF) that describes
 * exactly the well-formed Layer A documents. The grammar is consumable by
 * `llama.cpp --grammar-file`, Outlines, LMQL, and any other GBNF-aware
 * decoder. Lexical / syntactic correctness only; semantic correctness
 * (reference validity, id uniqueness) is out of GBNF's expressive range
 * and not enforced by the emitted grammar.
 */
private fun runGrammar(args: Array<String>) {
    if (args.size > 1) {
        usage()
        exitProcess(2)
    }
    print(ConstraintGrammar.emitGbnf())
}

private fun runVerifyOrEval(command: String, args: Array<String>) {
    if (args.size < 2) {
        usage()
        exitProcess(2)
    }
    val path = args[1]
    val (peerPaths, afterPeers) = extractPeerStores(args.drop(2))
    val (limits, sandboxPolicy, remaining) = parseLimits(afterPeers)
    val grantAll = "--grant-all" in remaining
    val noCache = "--no-cache" in remaining
    val strictIntegrity = "--strict-integrity" in remaining
    val unknown = remaining - setOf("--grant-all", "--no-cache", "--strict-integrity")
    if (unknown.isNotEmpty()) {
        System.err.println("unknown flags: ${unknown.joinToString(", ")}")
        usage()
        exitProcess(2)
    }
    reportFederationFlags(peerPaths, noCache, strictIntegrity)
    val text = File(path).readText()
    val (ingest, finalized) = try {
        loadFinalizedWithIngest(text, limits)
    } catch (e: IngestError) {
        System.err.println("ingest failed: ${e.message}")
        exitProcess(1)
    }
    // Error rendering: map opaque #N NodeIds in verifier/interpreter/schema
    // output back to the author ids the agent wrote.
    val annotator = NodeRefAnnotator(ingest.nameMap)
    // Q-043: with --peer-store programs, federate so cross-store NodeRefs
    // resolve through the peer chain (fetched + re-based into the shared
    // store); without, stay single-store (no resolver callback, behaviour
    // preserved bit-for-bit). `store` / `hashToNodeId` / `root` and the
    // `resolveCb` are taken from the FederatedProgram when present.
    val app = federateWithPeers(finalized, peerPaths, limits, noCache)
    val store = app?.store ?: finalized.store
    val hashToNodeId = app?.hashToNodeId ?: finalized.hashToNodeId
    val root = app?.root ?: finalized.root
    val resolveCb = app?.let { fp -> fp::fetchAndAdmit }
    // SchemaChecker / grant-all view: when federated, the mutable maps carry
    // any nodes admitted during verification.
    val schemaProgram = app?.let { FinalizedProgram(it.store, it.root, it.nodeIdToHash, it.hashToNodeId) } ?: finalized

    val verifier = Verifier(store, hashToNodeId, resolveCb)
    when (val result = verifier.verify(root)) {
        is VerifyResult.Failed -> {
            System.err.println("verification failed:")
            for (e in result.errors) System.err.println("  ${annotator.annotate(e.toString())}")
            exitProcess(1)
        }
        is VerifyResult.Ok -> {
            println("type: ${result.rootType}")
            // Informational diagnostics (e.g. unreachable nodes): surfaced,
            // never a failure. Route through the annotator so UnreachableNode
            // warnings show #N ('authorId') matching the error rendering.
            for (w in result.warnings) System.err.println("warning: ${annotator.annotate(w.toString())}")
            // Layer 7 step 1: after type-checking, run the SchemaChecker to
            // evaluate any pure-expression invariants on statically-known
            // values. Violations halt; deferred diagnostics are surfaced
            // (informational, per the proposal's default disposition).
            if (!runSchemaCheck(schemaProgram, result, annotator, limits, resolveCb)) exitProcess(1)
            if (command == "run") {
                // Install the host context (Q-041 sandbox, Q-045 stream
                // timeout, verifier nodeTypes for N-044/N-045 schema
                // projection) for the duration of the run.
                try {
                    withProgramEvaluationContext(sandboxPolicy, limits, result.nodeTypes) {
                        // Q-047 (Layer 7 step 2): runtime schema enforcement.
                        // The verifier re-records a SchemaType at every value-flow
                        // site; pass those obligations to the interpreter so it
                        // enforces invariants on dynamic values the verify-time
                        // SchemaChecker could only defer.
                        val schemaObligations = result.nodeTypes.mapNotNull { (nid, t) ->
                            (t as? org.strand.verifier.TypeExpr.SchemaType)?.let { nid to it }
                        }.toMap()
                        val interp = Interpreter(
                            store, hashToNodeId,
                            resolveTarget = resolveCb,
                            schemaObligations = schemaObligations,
                        )
                        val caps = if (grantAll) grantAllCapabilities(schemaProgram) else CapabilitySet.EMPTY
                        val value = interp.eval(root, caps, limits)
                        println("value: $value")
                    }
                } catch (e: InterpretException) {
                    // Q-064: one machine-readable strand:denial line on a
                    // denial-caused termination, alongside the human
                    // rendering below. No line for non-denial errors.
                    DenialLine.emitIfDenial(e.error, annotator)
                    System.err.println("interpretation failed: ${annotator.annotate(e.error.toString())}")
                    exitProcess(1)
                }
            }
        }
    }
}

/**
 * Run the Layer 7 step 1 [SchemaChecker] over a verified graph. Prints
 * deferred diagnostics to stderr (informational); returns false (and
 * prints violations) when any pure-expression invariant fails over a
 * statically-known value. Returns true when the schema-check passes or no
 * Schema-typed positions exist in the graph.
 */
private fun runSchemaCheck(
    finalized: FinalizedProgram,
    verifyResult: VerifyResult.Ok,
    annotator: NodeRefAnnotator,
    limits: EvaluationLimits = EvaluationLimits.DEFAULTS,
    resolveTarget: ((Hash) -> NodeId?)? = null,
): Boolean {
    val schemaResult = SchemaChecker(
        finalized.store,
        finalized.hashToNodeId,
        verifyResult,
        resolveTarget = resolveTarget,
        limits = limits,
    ).check()
    for (deferred in schemaResult.deferred) {
        System.err.println("schema-check deferred: ${annotator.annotate(deferred.toString())}")
    }
    if (schemaResult.violations.isNotEmpty()) {
        System.err.println("schema-check failed:")
        for (v in schemaResult.violations) System.err.println("  ${annotator.annotate(v.toString())}")
        return false
    }
    return true
}

private fun runMachine(args: Array<String>) {
    if (args.size < 4 || args[2] != "--events") {
        usage()
        exitProcess(2)
    }
    val programPath = args[1]
    val eventsPath = args[3]
    val (peerPaths, afterPeers) = extractPeerStores(args.drop(4))
    val (limits, sandboxPolicy, remaining) = parseLimits(afterPeers)
    val grantAll = "--grant-all" in remaining
    val noCache = "--no-cache" in remaining
    val strictIntegrity = "--strict-integrity" in remaining
    val unknown = remaining - setOf("--grant-all", "--no-cache", "--strict-integrity")
    if (unknown.isNotEmpty()) {
        System.err.println("unknown flags: ${unknown.joinToString(", ")}")
        usage()
        exitProcess(2)
    }
    reportFederationFlags(peerPaths, noCache, strictIntegrity)

    val programText = File(programPath).readText()
    val (ingest, finalized) = try {
        loadFinalizedWithIngest(programText, limits)
    } catch (e: IngestError) {
        System.err.println("ingest failed: ${e.message}")
        exitProcess(1)
    }
    val annotator = NodeRefAnnotator(ingest.nameMap)
    // Q-043: federate over --peer-store programs (single-store when none),
    // threading the resolver into the verifier, SchemaChecker, and runtime so a
    // transition function may reference a cross-store helper by targetHash.
    val app = federateWithPeers(finalized, peerPaths, limits, noCache)
    val store = app?.store ?: finalized.store
    val hashToNodeId = app?.hashToNodeId ?: finalized.hashToNodeId
    val root = app?.root ?: finalized.root
    val resolveCb = app?.let { fp -> fp::fetchAndAdmit }
    val schemaProgram = app?.let { FinalizedProgram(it.store, it.root, it.nodeIdToHash, it.hashToNodeId) } ?: finalized

    val verifier = Verifier(store, hashToNodeId, resolveCb)
    when (val result = verifier.verify(root)) {
        is VerifyResult.Failed -> {
            System.err.println("verification failed:")
            for (e in result.errors) System.err.println("  ${annotator.annotate(e.toString())}")
            exitProcess(1)
        }
        is VerifyResult.Ok -> {
            // Layer 7 step 1: SchemaChecker also runs for `strand machine`,
            // matching `verify` / `run` semantics so a malformed Schema-bearing
            // value halts before any state-machine evaluation occurs.
            if (!runSchemaCheck(schemaProgram, result, annotator, limits, resolveCb)) exitProcess(1)
            val eventsText = File(eventsPath).readText()
            val events = EventCodec.parseEventList(eventsText)
            val runtime = StateMachineRuntime(store, hashToNodeId, resolveCb)
            // Install the host context (Q-041 sandbox, Q-045 stream timeout,
            // verifier nodeTypes for N-044/N-045) for the duration of the run.
            try {
                withProgramEvaluationContext(sandboxPolicy, limits, result.nodeTypes) {
                    val caps = if (grantAll) grantAllCapabilities(schemaProgram) else CapabilitySet.EMPTY
                    val trace = runtime.runMachine(root, events, caps, limits)
                    printTrace(trace)
                    // Q-064: a denial-caused halt is a denial-caused
                    // termination — the trace above is the human rendering;
                    // emit the one machine-readable line and exit non-zero.
                    val haltReason = trace.final.reason
                    if (haltReason is org.strand.runtime.HaltReason.CapabilityDenial) {
                        DenialLine.emitReport(haltReason.report, annotator)
                        exitProcess(1)
                    }
                }
            } catch (e: InterpretException) {
                // Q-064: denials thrown outside the per-event fold (e.g.
                // transitionFn construction) surface here as exceptions.
                DenialLine.emitIfDenial(e.error, annotator)
                System.err.println("machine evaluation failed: ${annotator.annotate(e.error.toString())}")
                exitProcess(1)
            }
        }
    }
}

/**
 * Pretty-print a [Trace] in a single readable form. The format is structured
 * but human-friendly — one line per step plus a final halt record. We do
 * not emit JSON here because the trace is informational; a structured codec
 * (for snapshot / replay) is step 3 work, not step 1.
 */
private fun printTrace(trace: Trace) {
    println("trace (${trace.steps.size} step${if (trace.steps.size == 1) "" else "s"}):")
    for ((i, step) in trace.steps.withIndex()) {
        val outputsStr = if (step.outputs.isEmpty()) {
            ""
        } else {
            "  outputs=${step.outputs}"
        }
        println("  [$i] event=${step.event}  ${formatStateTransition(step.before, step.after)}$outputsStr")
    }
    println("halt: reason=${trace.final.reason}  final=${trace.final.finalState}")
}

private fun formatStateTransition(before: Value, after: Value): String =
    if (before == after) "state=$before (unchanged)" else "state: $before -> $after"

/**
 * Layer 6 step 2: drive every StateMachine reachable from the program as
 * a [MachineGroup]. The events file uses the "routed" format — each entry
 * carries a `stream` field naming the external input EventStream by its
 * author id, plus the standard [EventCodec] payload encoding.
 *
 * Output streams are drained concurrently and each emission is printed as
 * it arrives, prefixed with the output stream's author id (so multi-output
 * programs are distinguishable in the trace). The CLI exits when every
 * actor has halted (all input channels closed and all events processed).
 */
private fun runGroup(args: Array<String>) {
    if (args.size < 4 || args[2] != "--events") {
        usage()
        exitProcess(2)
    }
    val programPath = args[1]
    val eventsPath = args[3]
    val (peerPaths, afterPeers) = extractPeerStores(args.drop(4))
    val (limits, sandboxPolicy, remaining) = parseLimits(afterPeers)
    val grantAll = "--grant-all" in remaining
    val emitMetrics = "--metrics" in remaining
    val noCache = "--no-cache" in remaining
    val strictIntegrity = "--strict-integrity" in remaining
    val unknown = remaining - setOf("--grant-all", "--metrics", "--no-cache", "--strict-integrity")
    if (unknown.isNotEmpty()) {
        System.err.println("unknown flags: ${unknown.joinToString(", ")}")
        usage()
        exitProcess(2)
    }
    reportFederationFlags(peerPaths, noCache, strictIntegrity)

    val programText = File(programPath).readText()
    val (ingest, finalized) = try {
        loadFinalizedWithIngest(programText, limits)
    } catch (e: IngestError) {
        System.err.println("ingest failed: ${e.message}")
        exitProcess(1)
    }
    // Q-043: federate over --peer-store programs (single-store when none),
    // threading the resolver through the verifier, SchemaChecker, MachineGroup,
    // and runtime so a machine may reference a cross-store helper by targetHash.
    val app = federateWithPeers(finalized, peerPaths, limits, noCache)
    val store = app?.store ?: finalized.store
    val hashToNodeId = app?.hashToNodeId ?: finalized.hashToNodeId
    val root = app?.root ?: finalized.root
    val resolveCb = app?.let { fp -> fp::fetchAndAdmit }
    val schemaProgram = app?.let { FinalizedProgram(it.store, it.root, it.nodeIdToHash, it.hashToNodeId) } ?: finalized

    val annotator = NodeRefAnnotator(ingest.nameMap)
    val verifier = Verifier(store, hashToNodeId, resolveCb)
    val verifyResult = verifier.verify(root)
    if (verifyResult is VerifyResult.Failed) {
        System.err.println("verification failed:")
        for (e in verifyResult.errors) System.err.println("  ${annotator.annotate(e.toString())}")
        exitProcess(1)
    }
    if (!runSchemaCheck(schemaProgram, verifyResult as VerifyResult.Ok, annotator, limits, resolveCb)) exitProcess(1)

    // Collect every StateMachine NodeId from the canonical store. The
    // group includes ALL reachable StateMachines, regardless of whether
    // they appear at the root or are buried inside a Let chain (the
    // multi-machine corpus 48 pattern).
    val machineIds: List<NodeId> = store.entries()
        .filter { it.second is Node.StateMachine }
        .map { it.first }
    if (machineIds.isEmpty()) {
        System.err.println("group: no StateMachine nodes found in $programPath")
        exitProcess(1)
    }

    // Parse the routed event list. The "stream" field names the external
    // input EventStream by author id; we resolve it to the NodeId via
    // the ingest's nameMap (which the canonical store has rewritten to
    // opaque NodeIds but the author names are preserved here for routing).
    val eventsText = File(eventsPath).readText()
    val routedEvents = parseRoutedEvents(eventsText)
    val resolvedRouted = routedEvents.map { (streamName, payload) ->
        val streamId = ingest.nameMap[streamName]
            ?: run {
                System.err.println(
                    "group: routed event names unknown stream '$streamName'; " +
                        "known names: ${ingest.nameMap.keys.sorted().joinToString(", ")}"
                )
                exitProcess(1)
            }
        streamId to payload
    }

    val group = MachineGroup(
        store = store,
        hashToNodeId = hashToNodeId,
        machines = machineIds,
        capabilities = if (grantAll) grantAllCapabilities(schemaProgram) else CapabilitySet.EMPTY,
        recordInputs = false,  // CLI runs are not replay-determinism tests
    )

    // Inverse name map for nicer output labelling (NodeId → author name).
    val nameByNodeId: Map<NodeId, String> = ingest.nameMap.entries
        .associate { (name, id) -> id to name }

    // Install the host context (Q-041 sandbox, Q-045 stream timeout,
    // verifier nodeTypes for N-044/N-045) for the duration of the group run.
    try {
        withProgramEvaluationContext(sandboxPolicy, limits, verifyResult.nodeTypes) {
            runBlocking {
                val runtime = StateMachineRuntime(store, hashToNodeId, resolveCb)
                val handle = runtime.runGroup(group, this, limits)

                // Send routed events on their designated input streams, then
                // close all host-feedable external inputs so the actors halt
                // naturally. Q-046 source-bound streams are absent from
                // externalInputs — the runtime's feeder is their sole
                // producer and owns their closure, so the close loop below
                // cannot touch them.
                for ((streamId, payload) in resolvedRouted) {
                    val channel = handle.externalInputs[streamId]
                        ?: run {
                            val streamName = nameByNodeId[streamId] ?: "$streamId"
                            val node = store.getOrNull(streamId) as? Node.EventStream
                            if (node?.source != null) {
                                error(
                                    "group: stream '$streamName' is source-bound (Q-046 — fed by " +
                                        "the runtime from its IO source); routed events cannot be " +
                                        "sent to it"
                                )
                            }
                            error("group: stream '$streamName' is not an external input")
                        }
                    channel.send(payload)
                }
                for (channel in handle.externalInputs.values) channel.close()

                // Drain output streams concurrently. Each emission is printed
                // as it arrives; the actors continue until their input
                // channels close and any pending transitions complete.
                coroutineScope {
                    for ((streamId, channel) in handle.externalOutputs) {
                        val name = nameByNodeId[streamId] ?: "<unnamed:$streamId>"
                        launch {
                            for (value in channel) println("output $name: $value")
                        }
                    }
                }
                handle.await()
                if (emitMetrics) printMetrics(handle.metrics(), nameByNodeId)
                // Q-064: an actor that halted on a capability/refinement
                // denial is a denial-caused termination of the run. Emit
                // exactly one strand:denial line (the first denial in
                // deterministic instance-id order — one line per run, even
                // if several actors denied), a human-readable summary, and
                // exit non-zero.
                val denied = handle.allInstances.values
                    .mapNotNull { it.denialReport }
                    .sortedBy { it.instanceId }
                if (denied.isNotEmpty()) {
                    val report = denied.first()
                    DenialLine.emitReport(report, annotator)
                    System.err.println(
                        "group evaluation halted on capability denial: " +
                            "category ${report.category}, instance ${report.instanceId}, " +
                            "event ${report.eventIndex}"
                    )
                    exitProcess(1)
                }
            }
        }
    } catch (e: InterpretException) {
        // Q-064: group-start denials (initial spawn, source openers) and
        // any denial outside an actor's per-event loop surface here.
        DenialLine.emitIfDenial(e.error, annotator)
        System.err.println("group evaluation failed: ${annotator.annotate(e.error.toString())}")
        exitProcess(1)
    }
}

/**
 * Print a [RuntimeMetrics] snapshot in a human-readable form. Per-instance
 * counters are listed first, then per-stream. Stream NodeIds are labeled with
 * their author name when known.
 */
private fun printMetrics(metrics: RuntimeMetrics, nameByNodeId: Map<NodeId, String>) {
    println("metrics:")
    println("  instances (${metrics.perInstance.size}):")
    for ((id, m) in metrics.perInstance) {
        println("    $id:")
        println("      eventsReceived=${m.eventsReceived}")
        println("      transitionsExecuted=${m.transitionsExecuted}")
        println("      lastTransitionLatencyNanos=${m.lastTransitionLatencyNanos}")
        println("      halted=${m.halted}")
        println("      currentState=${m.currentState}")
    }
    println("  streams (${metrics.perStream.size}):")
    for ((id, m) in metrics.perStream) {
        val name = nameByNodeId[id] ?: "<unnamed:$id>"
        println("    $name: overflowDrops=${m.overflowDrops}  closed=${m.closed}")
    }
}

/**
 * Parse a routed event list of the form
 * `{"events": [{"stream": "<name>", "tag": "<tag>", ...}, ...]}`. Each
 * entry's `stream` field names an external input EventStream by author
 * id; the remaining fields decode to a [Value] via the standard
 * [EventCodec] format. Returns the (stream name, decoded value) pairs in
 * the order they appear in the file.
 */
private fun parseRoutedEvents(text: String): List<Pair<String, Value>> {
    val parser = Json { ignoreUnknownKeys = true }
    val root = parser.parseToJsonElement(text) as? JsonObject
        ?: error("routed event list: top-level value must be an object")
    val events = root["events"] as? JsonArray
        ?: error("routed event list: missing or non-array 'events' field")
    return events.mapIndexed { i, elt ->
        val obj = elt as? JsonObject
            ?: error("routed event list: events[$i] must be an object")
        val streamName = obj["stream"]?.jsonPrimitive?.contentOrNull
            ?: error("routed event list: events[$i] missing 'stream' field")
        val payload = EventCodec.decodeValue(obj, ctx = "events[$i]")
        streamName to payload
    }
}

/**
 * Q-034 step 1: compile an authoring-surface file to canonical
 * dag-json, ingest, finalize, verify. Elaboration (Layer C — fills in
 * absent Lambda effects, Application effectInstances / typeArguments,
 * Lambda paramType) runs unconditionally.
 *
 * Q-061: `--surface familiar` selects the Layer F dialect
 * (proposals/familiar-surface-lowering.md); the default is Layer A,
 * except that a `.familiar` file extension auto-selects Layer F. Both
 * surfaces share the elaborator/emitter pipeline and the Q-051
 * error-annotation path (source lines point into whichever surface
 * the agent wrote).
 *
 * Flags:
 *   `--emit-json`             print the compiled JSON to stdout, skip
 *                             the verify pipeline
 *   `--surface <layer-a|familiar>`  the authoring surface of the input
 */
private fun runAuthor(args: Array<String>) {
    if (args.size < 2) {
        usage()
        exitProcess(2)
    }
    val path = args[1]
    val rest = args.drop(2)
    var emitOnly = false
    var surfaceArg: String? = null
    var i = 0
    while (i < rest.size) {
        when (val flag = rest[i]) {
            "--emit-json" -> emitOnly = true
            "--surface" -> {
                surfaceArg = rest.getOrNull(i + 1)
                if (surfaceArg == null) {
                    System.err.println("--surface needs an argument: layer-a or familiar")
                    exitProcess(2)
                }
                i++
            }
            else -> {
                System.err.println("unknown flags: $flag")
                usage()
                exitProcess(2)
            }
        }
        i++
    }
    val surface = when (surfaceArg) {
        null -> if (path.endsWith(".familiar")) Authoring.Surface.FAMILIAR else Authoring.Surface.LAYER_A
        "layer-a" -> Authoring.Surface.LAYER_A
        "familiar" -> Authoring.Surface.FAMILIAR
        else -> {
            System.err.println("unknown surface '$surfaceArg' — expected layer-a or familiar")
            exitProcess(2)
        }
    }
    val surfaceLabel = if (surface == Authoring.Surface.FAMILIAR) "Layer F" else "Layer A"
    val sourceText = File(path).readText()
    val compiled = try {
        Authoring.compile(sourceText, surface)
    } catch (e: AuthoringException) {
        System.err.println("$surfaceLabel compilation failed:")
        for (err in e.errors) {
            System.err.println("  line ${err.line}: ${err.detail}")
        }
        printElaborationNotes(e.elaborationGaps)
        exitProcess(1)
    }
    if (emitOnly) {
        println(compiled.dagJson)
        return
    }
    val (ingest, finalized) = try {
        loadFinalizedWithIngest(compiled.dagJson)
    } catch (e: IngestError) {
        System.err.println("ingest failed for $path (after $surfaceLabel compile): ${e.message}")
        printElaborationNotes(compiled.elaborationGaps)
        exitProcess(1)
    }
    // Error rendering for the author path: annotate #N references with the
    // Layer A author id and source line, and flag sugar-synthesized
    // (`__if*` / `__when*` / ...) and implicit-prelude nodes as such so the
    // agent knows whether an error sits in a line it wrote or an expansion.
    val annotator = NodeRefAnnotator(
        ingest.nameMap,
        preludeNames = LayerAGrammar.reservedNodes.keys,
        sourceLines = compiled.sourceLines,
    )
    val verifier = Verifier(finalized.store, finalized.hashToNodeId)
    when (val result = verifier.verify(finalized.root)) {
        is VerifyResult.Failed -> {
            System.err.println("verification failed for $path (after $surfaceLabel compile):")
            for (e in result.errors) System.err.println("  ${annotator.annotate(e.toString())}")
            printElaborationNotes(compiled.elaborationGaps)
            exitProcess(1)
        }
        is VerifyResult.Ok -> {
            println("type: ${result.rootType}")
            for (w in result.warnings) System.err.println("warning: ${annotator.annotate(w.toString())}")
            if (!runSchemaCheck(finalized, result, annotator)) {
                printElaborationNotes(compiled.elaborationGaps)
                exitProcess(1)
            }
        }
    }
}

/**
 * Q-034 gap policy: surface the Elaborator's attempted-but-failed
 * inference cases as `elaboration note:` lines. Called ONLY from failure
 * paths of `strand author` (compilation, ingest, verification, or
 * schema-check failure) — a successful compile prints nothing, so the
 * notes are zero-noise. The note frequently names the root cause of a
 * cryptic downstream error (e.g. an "Unknown node id 'n'" ingest failure
 * caused by an untypable compact-LAM parameter `n`).
 */
private fun printElaborationNotes(gaps: List<org.strand.authoring.ElaborationGap>) {
    for (g in gaps) {
        val where = if (g.line > 0) " (line ${g.line})" else ""
        System.err.println(
            "elaboration note: '${g.nodeId}'$where ${g.field} " +
                "[${g.inferenceCase}]: ${g.reason}"
        )
    }
}

/**
 * Q-036 reverse projection: canonical dag-json → Layer A text.
 *
 * Reads a canonical dag-json file, runs [Authoring.projectFromDagJson]
 * (translator + SAFE elaboration-omission + probe-and-fallback + implicit
 * prelude), and prints the projected Layer A text to stdout. The output is
 * suitable as an LLM prompt context (compact authoring form) or as input to
 * `strand author` for a re-emit round-trip.
 */
private fun runTranslate(args: Array<String>) {
    if (args.size < 2) {
        usage()
        exitProcess(2)
    }
    val path = args[1]
    if (args.size > 2) {
        System.err.println("unexpected arguments: ${args.drop(2).joinToString(" ")}")
        usage()
        exitProcess(2)
    }
    val canonicalText = File(path).readText()
    val layerAText = try {
        Authoring.projectFromDagJson(canonicalText)
    } catch (e: AuthoringException) {
        System.err.println("reverse projection failed for $path:")
        for (err in e.errors) {
            System.err.println("  ${err.detail}")
        }
        exitProcess(1)
    }
    print(layerAText)
}

private const val DEFAULT_REGISTRY_FILE = "strand-registry.json"

/**
 * Q-063: the default registry resident — the prelude. The bundled prelude
 * module ([PreludeModule]) supplies `prelude` → manifest hash plus every
 * reserved name → its admitted node's content hash, so
 * `strand registry resolve fsWrite` answers from a clean checkout with no
 * registry file. User registry-file entries overlay these defaults (a file
 * entry with the same name wins); `put` writes only file entries, never
 * the built-ins.
 */
private val preludeRegistryDefaults: NameRegistry by lazy {
    val entries = LinkedHashMap<String, Hash>()
    entries[PreludeModule.REGISTRY_NAME] = PreludeModule.loaded.manifestHash
    entries.putAll(PreludeModule.loaded.nodeHashes)
    NameRegistry(entries)
}

/**
 * Q-043 § 4.6 off-graph name-registry tooling. `strand registry` reads and
 * writes a flat name→hash JSON file (default [DEFAULT_REGISTRY_FILE], overridden
 * with `--registry <file>`). The registry is *not* part of any canonical graph —
 * it is a pure tooling affordance for discovering published hashes by a human
 * name, exactly the role [NameRegistry] documents. Q-063: resolution and
 * listing consult the built-in prelude defaults ([preludeRegistryDefaults])
 * beneath the file's entries, so the prelude's names answer with no file
 * present. Subcommands:
 *
 *   strand registry resolve <name> [--registry <file>]    print the bound hash (exit 1 if unknown)
 *   strand registry put <name> <hash> [--registry <file>] add/update an entry, rewrite the file
 *   strand registry list [--registry <file>]              list every name → hash, sorted
 */
private fun runRegistry(args: Array<String>) {
    if (args.size < 2) {
        usage()
        exitProcess(2)
    }
    val (registryPath, rest) = extractRegistryPath(args.drop(2))
    val file = File(registryPath ?: DEFAULT_REGISTRY_FILE)
    when (val sub = args[1]) {
        "resolve" -> {
            if (rest.size != 1) {
                System.err.println("usage: strand registry resolve <name> [--registry <file>]")
                exitProcess(2)
            }
            val registry = effectiveRegistry(file)
            val hash = registry.resolve(rest[0])
            if (hash == null) {
                System.err.println(
                    "registry: no entry for '${rest[0]}' in ${file.path} or the built-in prelude defaults"
                )
                exitProcess(1)
            }
            println(hash)
        }
        "put" -> {
            if (rest.size != 2) {
                System.err.println("usage: strand registry put <name> <hash> [--registry <file>]")
                exitProcess(2)
            }
            val (name, hashHex) = rest
            val hash = try {
                Hash.fromHex(hashHex)
            } catch (e: IllegalArgumentException) {
                System.err.println("registry: invalid hash '$hashHex': ${e.message}")
                exitProcess(2)
            }
            val updated = loadRegistryFile(file).put(name, hash)
            file.writeText(NameRegistry.toJson(updated))
            println("registry: $name -> $hash  (${file.path})")
        }
        "list" -> {
            if (rest.isNotEmpty()) {
                System.err.println("usage: strand registry list [--registry <file>]")
                exitProcess(2)
            }
            val registry = effectiveRegistry(file)
            if (registry.entries.isEmpty()) {
                println("(registry ${file.path} is empty)")
            } else {
                for (name in registry.names()) println("$name  ${registry.resolve(name)}")
            }
        }
        else -> {
            System.err.println("unknown registry subcommand: '$sub'")
            usage()
            exitProcess(2)
        }
    }
}

/**
 * Pull a single `--registry <file>` out of [flags], returning the path (or null
 * for the default) plus the residual positional arguments.
 */
private fun extractRegistryPath(flags: List<String>): Pair<String?, List<String>> {
    var path: String? = null
    val rest = mutableListOf<String>()
    var i = 0
    while (i < flags.size) {
        if (flags[i] == "--registry") {
            path = flags.getOrNull(i + 1) ?: run {
                System.err.println("--registry requires a path argument")
                exitProcess(2)
            }
            i += 2
        } else {
            rest += flags[i]
            i++
        }
    }
    return path to rest
}

/**
 * Load a [NameRegistry] from [file]'s entries alone. A missing file yields
 * [NameRegistry.EMPTY] (so the first `put` creates the file); a malformed
 * file is a hard error.
 */
private fun loadRegistryFile(file: File): NameRegistry {
    if (!file.exists()) return NameRegistry.EMPTY
    return try {
        NameRegistry.fromJson(file.readText())
    } catch (e: IllegalArgumentException) {
        System.err.println("registry: malformed registry ${file.path}: ${e.message}")
        exitProcess(1)
    }
}

/**
 * Q-063: the registry view `resolve` and `list` consult — the built-in
 * prelude defaults with [file]'s entries overlaid (file entries win on
 * name collision). A missing file is no longer an error: the prelude
 * answers from a clean checkout.
 */
private fun effectiveRegistry(file: File): NameRegistry =
    NameRegistry(preludeRegistryDefaults.entries + loadRegistryFile(file).entries)

private fun usage() {
    System.err.println("usage:")
    System.err.println("  strand verify    <file.json> [--peer-store <lib.json>]... [<federation>...]")
    System.err.println("  strand run       <file.json> [--peer-store <lib.json>]... [--grant-all] [<federation>...] [<limits>...]")
    System.err.println("  strand machine   <file.json> --events <events.json> [--peer-store <lib.json>]... [--grant-all] [<federation>...] [<limits>...]")
    System.err.println("  strand group     <file.json> --events <events.json> [--peer-store <lib.json>]... [--grant-all] [--metrics] [<federation>...] [<limits>...]")
    System.err.println("  strand author    <file.layer-a|file.familiar> [--emit-json] [--surface layer-a|familiar]")
    System.err.println("  strand translate <file.json>  → emit Layer A reverse projection (Q-036)")
    System.err.println("  strand registry  resolve <name> | put <name> <hash> | list  [--registry <file>]")
    System.err.println("  strand grammar                → emit Layer B constraint grammar (GBNF)")
    System.err.println()
    System.err.println("  Q-043 federation (verify / run / machine / group):")
    System.err.println("  --peer-store <file.json>: (repeatable) add a peer store to the federation resolver")
    System.err.println("               chain. A NodeRef with a `targetHash` not held locally is fetched from")
    System.err.println("               the first peer that holds it, re-based into the local store, verified,")
    System.err.println("               and (for run / machine / group) evaluated. Order is priority order.")
    System.err.println("  --no-cache:  do not wrap the resolver chain in a CachingResolver (default caches")
    System.err.println("               fetched subgraphs for the session; semantics are identical either way).")
    System.err.println("  --strict-integrity: explicit-declaration flag only — the per-target Merkle root")
    System.err.println("               re-hash that rejects a lying resolver is always on for federated runs.")
    System.err.println("  --registry <file>: name registry for `strand registry` (default $DEFAULT_REGISTRY_FILE).")
    System.err.println("               resolve/list consult the built-in prelude defaults beneath the file's")
    System.err.println("               entries ('prelude' -> manifest hash, every reserved name -> node hash),")
    System.err.println("               so prelude names answer with no registry file present (Q-063).")
    System.err.println("  --grant-all: auto-grant wildcard capabilities for every EffectCategory")
    System.err.println("               in the verified store (demo / dev-mode convenience; not for")
    System.err.println("               production use).")
    System.err.println("  --metrics:   after `strand group` completes, print a RuntimeMetrics snapshot")
    System.err.println("               (Layer 6 step 3 slice 3.4) showing per-instance and per-stream")
    System.err.println("               counters.")
    System.err.println()
    System.err.println("  Q-040 evaluation limits (each takes a numeric arg):")
    System.err.println("    --max-steps <Long>           total dispatch steps (default 10_000_000)")
    System.err.println("    --max-stack-depth <Int>      max recursion depth (default 4096)")
    System.err.println("    --max-allocated-values <Long> total Value allocations (default 1_000_000)")
    System.err.println("    --wall-clock-ms <Long>       wall-clock budget (default 30_000)")
    System.err.println("    --stream-receive-timeout-ms <Long> per-read streaming-receive ceiling (Q-045, default 30_000)")
    System.err.println("    --max-json-depth <Int>       ingest JSON nesting cap (default 512)")
    System.err.println("    --max-node-count <Int>       ingest node-count cap (default 100_000)")
    System.err.println("    --max-ingest-bytes <Long>    ingest byte-size cap (default 67_108_864)")
    System.err.println()
    System.err.println("  Q-042 error-redaction (single flag):")
    System.err.println("    --error-verbosity <mode>     where <mode> is one of:")
    System.err.println("                                   redacted   (default — IoFailure.detail scrubbed of registered")
    System.err.println("                                              credentials via CredentialScrubber)")
    System.err.println("                                   full       (dev/debug only — surfaces unscrubbed detail; logs warning)")
    System.err.println("                                   kind-only  (most restrictive — strips detail entirely)")
    System.err.println()
    System.err.println("  Q-041 sandbox flags (default-deny; flags relax the secure default):")
    System.err.println("    --workspace-root <path>      directory below which Fs.* paths must lie (default cwd)")
    System.err.println("    --allow-fs-escape            permit Fs.* paths outside workspace root (default deny)")
    System.err.println("    --allow-host <glob>          add host glob to network allowlist (repeatable)")
    System.err.println("    --allow-net-internal         disable network default-deny + blocked-range list")
    System.err.println("                                 (use only for trusted environments — opens RFC1918,")
    System.err.println("                                 loopback, link-local, cloud-metadata to outbound calls)")
}
