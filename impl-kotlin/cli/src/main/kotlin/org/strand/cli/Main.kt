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
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.HostPattern
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.InterpretException
import org.strand.interpreter.NetPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.Value
import org.strand.runtime.EventCodec
import org.strand.runtime.MachineGroup
import org.strand.runtime.ProgramImage
import org.strand.runtime.RunOutcome
import org.strand.runtime.RuntimeMetrics
import org.strand.runtime.StrandRuntime
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
 * Q-054: build the per-run [HostPolicy] from the CLI's parsed flags. The base
 * is [HostPolicy.OPEN] — the CLI historically installed only the sandbox
 * policy, the stream-receive timeout (carried on [EvaluationLimits]), and the
 * verifier node-types, leaving the clock / RNG / credential provider / HTTP
 * clients at their library defaults. Keeping the OPEN base preserves that:
 * the sandbox comes from the flags (default-secure, relaxed by flags), the
 * limits from the flags, and everything else stays at the OPEN defaults.
 */
private fun hostPolicyFor(sandboxPolicy: SandboxPolicy, limits: EvaluationLimits): HostPolicy =
    HostPolicy.OPEN.copy(sandbox = sandboxPolicy, limits = limits)

/**
 * Q-054: build a [ProgramImage] (the facade's program-supply form) from the
 * resolved canonical store / root / reverse map plus the optional Q-043
 * cross-store resolution callback.
 */
private fun programImageOf(
    store: org.strand.core.NodeStore,
    root: NodeId,
    hashToNodeId: Map<Hash, NodeId>,
    resolveTarget: ((Hash) -> NodeId?)?,
): ProgramImage = ProgramImage(store, root, hashToNodeId, resolveTarget)

/**
 * Q-058: pull a single `--store <dir>` out of [flags], returning the path (or
 * null for "no store, file-path mode") plus the residual flags. An absent flag
 * falls back to the `STRAND_STORE` env var only when a store reference is
 * actually used (the resolver consults it), so a plain `strand run app.json`
 * with no flag and no env stays file-path mode.
 */
private fun extractStore(flags: List<String>): Pair<String?, List<String>> {
    var path: String? = null
    val rest = mutableListOf<String>()
    var i = 0
    while (i < flags.size) {
        if (flags[i] == "--store") {
            path = flags.getOrNull(i + 1) ?: run {
                System.err.println("--store requires a directory argument")
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
 * Q-058: the effective store directory for a subcommand — the explicit
 * `--store <dir>` flag if given, else the `STRAND_STORE` environment variable
 * if set, else null (file-path mode, no store). A null result means a plain
 * file-path invocation: the positional is always a file.
 */
private fun effectiveStoreDir(flagDir: String?): String? {
    if (flagDir != null) return flagDir
    val env = System.getenv("STRAND_STORE")
    return if (env.isNullOrBlank()) null else env
}

/**
 * Q-058: a program resolved for a subcommand — either ingested from a file (the
 * pre-Q-058 path, [ingest] non-null) or loaded by root hash from the persistent
 * store ([ingest] null, [cachedVerdict] possibly present). The store / root /
 * hashToNodeId / resolveCb fields are what the verifier, schema-checker, and
 * facade consume; [schemaProgram] is the FinalizedProgram view the SchemaChecker
 * and grant-all need.
 */
private class ResolvedProgram(
    val store: org.strand.core.NodeStore,
    val root: NodeId,
    val hashToNodeId: Map<Hash, NodeId>,
    val resolveCb: ((Hash) -> NodeId?)?,
    val nameMap: Map<String, NodeId>,
    val schemaProgram: FinalizedProgram,
    val cachedVerdict: org.strand.hashing.StoredVerdict?,
    val rootHash: Hash?,
)

/**
 * Q-058: resolve a subcommand's positional argument to a [ResolvedProgram].
 *
 * When [storeDir] is null, [positional] is a file path (pre-Q-058 behavior,
 * preserved bit-for-bit): ingest + finalize + optional `--peer-store`
 * federation. When [storeDir] is set:
 *  - if [positional] names an existing file, it is still a file path (so an
 *    invocation that passes both `--store` and a file keeps working);
 *  - otherwise [positional] is a store reference — a root-hash hex, or a
 *    registry name that resolves to one (Q-063 prelude defaults + the
 *    `--registry` file underneath) — dereferenced against the local store.
 *    A reference the store does not hold is a hard error.
 *
 * Run-by-hash never re-ingests or re-hashes the authored JSON; it admits the
 * subgraph from the store (Merkle root re-hash fails closed on corruption).
 */
private fun resolveProgram(
    positional: String,
    storeDir: String?,
    peerPaths: List<String>,
    limits: EvaluationLimits,
    noCache: Boolean,
    registryFile: java.io.File,
): ResolvedProgram {
    val useStore = storeDir != null && !File(positional).isFile
    if (!useStore) {
        // ----- File-path mode (unchanged) -----
        val text = File(positional).readText()
        val (ingest, finalized) = try {
            loadFinalizedWithIngest(text, limits)
        } catch (e: IngestError) {
            System.err.println("ingest failed: ${e.message}")
            exitProcess(1)
        }
        val app = federateWithPeers(finalized, peerPaths, limits, noCache)
        val store = app?.store ?: finalized.store
        val hashToNodeId = app?.hashToNodeId ?: finalized.hashToNodeId
        val root = app?.root ?: finalized.root
        val resolveCb = app?.let { fp -> fp::fetchAndAdmit }
        val schemaProgram = app?.let { FinalizedProgram(it.store, it.root, it.nodeIdToHash, it.hashToNodeId) } ?: finalized
        return ResolvedProgram(store, root, hashToNodeId, resolveCb, ingest.nameMap, schemaProgram, cachedVerdict = null, rootHash = null)
    }

    // ----- Store-by-hash mode -----
    val dir = java.nio.file.Paths.get(storeDir!!)
    val persistent = try {
        org.strand.hashing.PersistentStore.open(dir)
    } catch (e: RuntimeException) {
        System.err.println("store open failed ($dir): ${e.message}")
        exitProcess(1)
    }
    val rootHash = resolveStoreReference(positional, registryFile)
    if (rootHash == null) {
        System.err.println(
            "store: '$positional' is neither an existing file, a valid root-hash hex, nor a known registry name"
        )
        exitProcess(1)
    }
    val runtime = StrandRuntime(HostPolicy.OPEN.copy(limits = limits))
    val image = try {
        runtime.loadImageFromStore(persistent, rootHash)
    } catch (e: org.strand.hashing.NodeResolverIntegrityViolation) {
        System.err.println("store integrity violation reading $rootHash: ${e.message}")
        exitProcess(1)
    }
    if (image == null) {
        System.err.println("store: no program rooted at $rootHash held in $dir (ingest it with `strand store ingest`)")
        exitProcess(1)
    }
    val cached = runtime.cachedVerdict(persistent, rootHash)
    val schemaProgram = FinalizedProgram(image.store, image.root, emptyMap(), image.hashToNodeId)
    return ResolvedProgram(
        store = image.store,
        root = image.root,
        hashToNodeId = image.hashToNodeId,
        resolveCb = image.resolveTarget,
        nameMap = emptyMap(),
        schemaProgram = schemaProgram,
        cachedVerdict = cached,
        rootHash = rootHash,
    )
}

/**
 * Resolve a store reference to a root hash: a 66-hex-char multihash parses
 * directly; otherwise it is looked up as a registry name (the Q-063 prelude
 * defaults beneath the `--registry` file's entries). Returns null when it is
 * neither.
 */
private fun resolveStoreReference(reference: String, registryFile: java.io.File): Hash? {
    // A literal hash hex.
    runCatching { Hash.fromHex(reference) }.getOrNull()?.let { return it }
    // A registry name (prelude defaults + file).
    return effectiveRegistry(registryFile).resolve(reference)
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
        "store" -> runStore(args)
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
    val (storeDir, afterStore) = extractStore(args.drop(2))
    val (registryPath, afterRegistry) = extractRegistryPath(afterStore)
    val (peerPaths, afterPeers) = extractPeerStores(afterRegistry)
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
    // Q-058: resolve the positional to a program — a file path (ingest +
    // finalize + optional --peer-store federation) or, with --store, a root
    // hash / registry name dereferenced against the local store.
    val resolved = resolveProgram(
        positional = path,
        storeDir = effectiveStoreDir(storeDir),
        peerPaths = peerPaths,
        limits = limits,
        noCache = noCache,
        registryFile = File(registryPath ?: DEFAULT_REGISTRY_FILE),
    )
    val store = resolved.store
    val hashToNodeId = resolved.hashToNodeId
    val root = resolved.root
    val resolveCb = resolved.resolveCb
    val schemaProgram = resolved.schemaProgram
    // Error rendering: map opaque #N NodeIds back to author ids (empty for
    // store-by-hash loads, which carry no author names).
    val annotator = NodeRefAnnotator(resolved.nameMap)

    // Q-058: admit-and-verify-once. For the `verify` command on a store-by-hash
    // load with a cached verdict, reuse the recorded verdict and skip the live
    // verifier entirely — the recorded rootType/warnings/failure stand. `run`
    // falls through to the live path (the facade re-derives the structured
    // schema obligations from the admitted local store).
    val cached = resolved.cachedVerdict
    if (command == "verify" && cached != null) {
        when (cached) {
            is org.strand.hashing.StoredVerdict.Failed -> {
                System.err.println("verification failed (cached verdict):")
                for (e in cached.errors) System.err.println("  $e")
                exitProcess(1)
            }
            is org.strand.hashing.StoredVerdict.Ok -> {
                println("type: ${cached.rootType}")
                for (w in cached.warnings) System.err.println("warning: $w")
                System.err.println("note: reused cached verify verdict for ${resolved.rootHash}")
                return
            }
        }
    }

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
                // Q-054: the StrandRuntime facade owns the host-context
                // install/restore (Q-041 sandbox, Q-045 stream timeout,
                // verifier nodeTypes for N-044/N-045 schema projection) and
                // the Q-047 runtime-schema-obligation wiring. The CLI is a
                // thin client: build the policy + program image, call run,
                // render the outcome. (The facade re-verifies and
                // re-schema-checks internally; the CLI's earlier passes above
                // produced the warning/diagnostic rendering and the exit on a
                // static violation — by here both are known clean.)
                val runtime = StrandRuntime(hostPolicyFor(sandboxPolicy, limits))
                val image = programImageOf(store, root, hashToNodeId, resolveCb)
                val caps = if (grantAll) grantAllCapabilities(schemaProgram) else CapabilitySet.EMPTY
                try {
                    when (val outcome = runtime.run(image, caps)) {
                        is RunOutcome.Ok -> println("value: ${outcome.value}")
                        // The CLI already rendered verify failures and schema
                        // violations above and would have exited; reaching
                        // these here would mean the facade's re-check diverged,
                        // which is a hard inconsistency.
                        is RunOutcome.VerifyFailed -> {
                            System.err.println("verification failed:")
                            for (e in outcome.errors) System.err.println("  ${annotator.annotate(e.toString())}")
                            exitProcess(1)
                        }
                        is RunOutcome.SchemaViolation -> {
                            System.err.println("schema-check failed:")
                            for (v in outcome.schema.violations) System.err.println("  ${annotator.annotate(v.toString())}")
                            exitProcess(1)
                        }
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
    val (storeDir, afterStore) = extractStore(args.drop(4))
    val (registryPath, afterRegistry) = extractRegistryPath(afterStore)
    val (peerPaths, afterPeers) = extractPeerStores(afterRegistry)
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

    // Q-058: file path or store-by-hash, per --store.
    val resolved = resolveProgram(programPath, effectiveStoreDir(storeDir), peerPaths, limits, noCache, File(registryPath ?: DEFAULT_REGISTRY_FILE))
    val store = resolved.store
    val hashToNodeId = resolved.hashToNodeId
    val root = resolved.root
    val resolveCb = resolved.resolveCb
    val schemaProgram = resolved.schemaProgram
    val annotator = NodeRefAnnotator(resolved.nameMap)

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
            // Q-054: the facade owns the host-context install/restore. The CLI
            // builds the policy + program image, drives one machine, and
            // renders the trace.
            val runtime = StrandRuntime(hostPolicyFor(sandboxPolicy, limits))
            val image = programImageOf(store, root, hashToNodeId, resolveCb)
            val caps = if (grantAll) grantAllCapabilities(schemaProgram) else CapabilitySet.EMPTY
            try {
                val trace = runtime.runMachine(image, root, events, caps, result.nodeTypes)
                printTrace(trace)
                // Q-064: a denial-caused halt is a denial-caused termination —
                // the trace above is the human rendering; emit the one
                // machine-readable line and exit non-zero.
                val haltReason = trace.final.reason
                if (haltReason is org.strand.runtime.HaltReason.CapabilityDenial) {
                    DenialLine.emitReport(haltReason.report, annotator)
                    exitProcess(1)
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
    val (storeDir, afterStore) = extractStore(args.drop(4))
    val (registryPath, afterRegistry) = extractRegistryPath(afterStore)
    val (peerPaths, afterPeers) = extractPeerStores(afterRegistry)
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

    // Q-058: file path or store-by-hash, per --store. Routed events name input
    // streams by author id; a store-by-hash group carries no author names (the
    // nameMap is empty), so routed-event invocations require the file path.
    val resolved = resolveProgram(programPath, effectiveStoreDir(storeDir), peerPaths, limits, noCache, File(registryPath ?: DEFAULT_REGISTRY_FILE))
    val store = resolved.store
    val hashToNodeId = resolved.hashToNodeId
    val root = resolved.root
    val resolveCb = resolved.resolveCb
    val schemaProgram = resolved.schemaProgram
    val ingestNameMap = resolved.nameMap

    val annotator = NodeRefAnnotator(ingestNameMap)
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
        val streamId = ingestNameMap[streamName]
            ?: run {
                System.err.println(
                    "group: routed event names unknown stream '$streamName'; " +
                        "known names: ${ingestNameMap.keys.sorted().joinToString(", ")}" +
                        if (ingestNameMap.isEmpty()) " (a store-by-hash group carries no author names; route by file path)" else ""
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
    val nameByNodeId: Map<NodeId, String> = ingestNameMap.entries
        .associate { (name, id) -> id to name }

    // Q-054: the facade scopes the host-context install/restore around the
    // entire group lifecycle (the actors evaluate asynchronously, so the
    // install must outlive `runGroup`'s return until the group halts). The CLI
    // builds the policy + program image and drives the group through the
    // facade's runGroup.
    val runtime = StrandRuntime(hostPolicyFor(sandboxPolicy, limits))
    val image = programImageOf(store, root, hashToNodeId, resolveCb)
    try {
        runtime.withGroupInstalled(verifyResult.nodeTypes) {
            runBlocking {
                val handle = runtime.runGroup(image, group, this, verifyResult.nodeTypes)

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

/**
 * Q-058 `strand store` subcommands. The persistent store makes content
 * addressing operative across runs: a program ingested once is runnable by its
 * root hash from any later invocation, with the verify verdict recorded
 * (admit-and-verify-once) and shared subgraphs stored once (dedup).
 *
 *   strand store ingest <file.json> [--store <dir>]   ingest + verify, write nodes + verdict, print the root hash
 *
 * The default store directory is `$STRAND_STORE` if set, else `~/.strand/store`.
 */
private fun runStore(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: strand store ingest <file.json> [--store <dir>]")
        exitProcess(2)
    }
    val (storeDir, rest) = extractStore(args.drop(2))
    when (val sub = args[1]) {
        "ingest" -> {
            if (rest.size != 1) {
                System.err.println("usage: strand store ingest <file.json> [--store <dir>]")
                exitProcess(2)
            }
            val dirArg = effectiveStoreDir(storeDir)
                ?: org.strand.hashing.PersistentStore.defaultDir().toString()
            val store = try {
                org.strand.hashing.PersistentStore.open(java.nio.file.Paths.get(dirArg))
            } catch (e: RuntimeException) {
                System.err.println("store open failed ($dirArg): ${e.message}")
                exitProcess(1)
            }
            val text = File(rest[0]).readText()
            val (ingest, finalized) = try {
                loadFinalizedWithIngest(text)
            } catch (e: IngestError) {
                System.err.println("ingest failed: ${e.message}")
                exitProcess(1)
            }
            // Admit-and-verify-once: the first ingest is the one verify, recorded.
            val runtime = StrandRuntime(HostPolicy.OPEN)
            val verify = runtime.verify(programImageOf(finalized.store, finalized.root, finalized.hashToNodeId, null))
            val written = runtime.ingestToStore(store, finalized, verify, finalized.nodeIdToHash)
            val rootHash = finalized.nodeIdToHash.getValue(finalized.root)
            val annotator = NodeRefAnnotator(ingest.nameMap)
            when (verify) {
                is VerifyResult.Failed -> {
                    System.err.println("ingested $rootHash with a FAILED verdict ($written new node(s)):")
                    for (e in verify.errors) System.err.println("  ${annotator.annotate(e.toString())}")
                    // The program and its failed verdict are recorded; a later
                    // run-by-hash reports the cached failure. Exit non-zero so a
                    // script sees the verify failure.
                    println(rootHash)
                    exitProcess(1)
                }
                is VerifyResult.Ok -> {
                    System.err.println(
                        "store: ingested $rootHash into $dirArg ($written new node record(s); verdict: Ok, type ${verify.rootType})"
                    )
                    println(rootHash)
                }
            }
        }
        else -> {
            System.err.println("unknown store subcommand: '$sub' (expected 'ingest')")
            exitProcess(2)
        }
    }
}

private fun usage() {
    System.err.println("usage:")
    System.err.println("  strand verify    <file.json|root-hash|name> [--store <dir>] [--peer-store <lib.json>]... [<federation>...]")
    System.err.println("  strand run       <file.json|root-hash|name> [--store <dir>] [--peer-store <lib.json>]... [--grant-all] [<federation>...] [<limits>...]")
    System.err.println("  strand machine   <file.json|root-hash|name> --events <events.json> [--store <dir>] [--peer-store <lib.json>]... [--grant-all] [<federation>...] [<limits>...]")
    System.err.println("  strand group     <file.json|root-hash|name> --events <events.json> [--store <dir>] [--peer-store <lib.json>]... [--grant-all] [--metrics] [<federation>...] [<limits>...]")
    System.err.println("  strand store     ingest <file.json> [--store <dir>]  → admit + verify-once, print the root hash")
    System.err.println("  strand author    <file.layer-a|file.familiar> [--emit-json] [--surface layer-a|familiar]")
    System.err.println("  strand translate <file.json>  → emit Layer A reverse projection (Q-036)")
    System.err.println("  strand registry  resolve <name> | put <name> <hash> | list  [--registry <file>]")
    System.err.println("  strand grammar                → emit Layer B constraint grammar (GBNF)")
    System.err.println()
    System.err.println("  Q-058 persistent store (verify / run / machine / group):")
    System.err.println("  --store <dir>: dereference the positional against an on-disk hash-keyed store")
    System.err.println("               (default \$STRAND_STORE, else ~/.strand/store). With --store, a positional")
    System.err.println("               that is not an existing file is treated as a root-hash hex or a registry")
    System.err.println("               name (Q-063 prelude defaults + the --registry file) and run by hash — no")
    System.err.println("               re-ingest, no re-hash; admission fails closed on a corrupted entry. A plain")
    System.err.println("               file path with no --store stays file-path mode, unchanged. `strand store")
    System.err.println("               ingest` populates the store and records the verify verdict (admit-once).")
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
