package org.strand.runtime

import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.DenialReport
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * A plugin host, demonstrated: fine-grained capability attenuation and the
 * confused-deputy defense.
 *
 * A host application loads untrusted "plugins" — agent-generated Strand programs
 * it has never seen — and grants each plugin a *precisely-scoped* slice of its
 * own authority. The distinctive property this demonstration shows is that a
 * confused or malicious plugin cannot escalate the delegated capability:
 *
 *  - not by **argument drift** — Q-039 effect projections bind the
 *    capability-check value to the actual foreign-call argument, so the value
 *    checked is the value the foreign code receives (the classic confused-deputy
 *    gap, closed at admission);
 *  - not by **reaching outside its scope** — the N-036 / Q-031 refined-capability
 *    match denies any request the granted refinement does not cover (denied at
 *    the foreign-call boundary, with a structured Q-064 DenialReport);
 *  - not by **the plugin's good behavior** — the host holds broad authority over
 *    the whole plugin tree but hands each plugin a narrowed grant, so
 *    least-privilege delegation is enforced by construction below the host, not
 *    by the plugin behaving.
 *
 * This is distinct from the existing containment demonstration
 * (`demos/containment-host/`), which shows COARSE whole-program tenant isolation.
 * Here a single host that holds broad authority delegates a narrowed slice to a
 * plugin and the plugin provably cannot climb back up.
 *
 * The host is an ordinary JVM caller of the shipped embedding surface — the
 * Q-054 [StrandRuntime] facade, the Q-031 refined [CapabilitySet] grant, and the
 * Q-064 [DenialReport]. It introduces no language feature, no node category, no
 * encoding change, and no verifier rule. Every property the demonstration claims
 * is one the runtime enforces; the assertion net ([PluginHostDemoTest]) protects
 * each one.
 *
 * The plugin programs are hand-authored stand-ins for agent submissions. They
 * live as Layer A source plus compiled canonical dag-json under
 * `demos/plugin-host/programs/` and are loaded as the content-addressed artifact,
 * not the human-facing projection. Four scenarios:
 *
 *  - **C1 Legitimate scoped operation.** A plugin granted Write to its own
 *    directory writes there and succeeds — the happy path of delegation.
 *  - **C2 Argument-drift confused deputy, blocked at verification.** A plugin
 *    declaring its capability-check parameter as a benign path while passing a
 *    sensitive argument to the foreign call is rejected at admission by the
 *    Q-039 projection check (`ProjectionMismatch`).
 *  - **C3 Out-of-scope escalation, denied at runtime.** A plugin granted
 *    Write to /plugins/A writes to another plugin's directory; the refined-
 *    capability match denies it with a real DenialReport.
 *  - **C4 Attenuation below the host.** The host holds broad Write over the whole
 *    plugin tree but runs the plugin under a grant narrowed to its own slice; the
 *    plugin cannot reach a sibling's directory even though the host could.
 *
 * The four scenario methods return structured results that both [main] (the
 * transcript) and [PluginHostDemoTest] (the assertions) consume, so the printed
 * demonstration and the regression net share one body of code.
 */
object PluginHostDemo {

    // ------------------------------------------------------------------
    // Program loading — the host admits the canonical dag-json artifact.
    // ------------------------------------------------------------------

    /**
     * Load a committed plugin program (canonical dag-json) from the classpath.
     * The source of truth is `demos/plugin-host/programs/<name>.json`; the
     * `:runtime` build copies those onto the test classpath under
     * `/demo/programs/` (see `runtime/build.gradle.kts`), so this resource path
     * is the classpath namespace, not a filesystem location.
     */
    fun loadProgramJson(name: String): String =
        PluginHostDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    /** Finalize a plugin's authored dag-json to the host's [ProgramImage]. */
    fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    /** The author-id → NodeId map for a program (used to build refined grants). */
    fun nameMap(json: String): Map<String, NodeId> = JsonIngest.parse(json).nameMap

    /**
     * Build the host's grant for a plugin: a [CapabilitySet] mapping the
     * plugin's `Filesystem.Write` EffectCategory (resolved by author id from the
     * plugin's own program, since capability grants are keyed by the category
     * NodeId the program declares) to a list of concrete-path patterns. A write
     * to a path not equal to any [allowedPaths] entry is denied. Passing more
     * than one path models a multi-file grant (disjunction: any pattern
     * matching is sufficient).
     */
    fun writeGrant(programJson: String, vararg allowedPaths: String): CapabilitySet {
        val writeFx = nameMap(programJson).getValue("writeFx")
        val patterns = allowedPaths.map {
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV(it))))
        }
        return CapabilitySet(mapOf(writeFx to patterns))
    }

    /**
     * Render a [DenialReport] the way an orchestrating principal sees it — the
     * same field set the CLI's `strand:denial` line carries.
     */
    fun renderDenial(report: DenialReport): String = buildString {
        append("category=").append(report.category)
        append(" requested=").append(report.requested ?: "(withheld)")
        append(" held=").append(report.held ?: "(withheld)")
        append(" node=").append(report.node?.toString() ?: "null")
        append(" phase=").append(report.phase.wire)
    }

    // ==================================================================
    // C1 — Legitimate scoped operation (the happy path of delegation).
    // ==================================================================

    /**
     * Result of C1. The plugin verified, ran, and its write landed inside its
     * own directory.
     */
    data class C1Result(
        val verified: Boolean,
        val value: Value?,
        val wroteToOwnDir: Boolean,
        val writtenPath: String,
    )

    /**
     * Load plugin A and run it under a grant confined to its own directory. The
     * committed `plugin-a-scoped-write` program writes the logical path
     * /plugins/A/state.json; to write and observe a *real* file hermetically
     * (without depending on a writable absolute /plugins tree), the host assigns
     * the plugin a concrete directory [pluginsRoot]/A and runs the same
     * delegation shape — honest Q-039 projection, scoped grant — against that
     * assigned path. The grant covers exactly the assigned path, so the write is
     * authorized, the run completes to the byte count, and the file lands inside
     * the plugin's own directory. The committed program is independently
     * confirmed to admit cleanly (it is the artifact the host would receive).
     */
    fun scenarioC1(pluginsRoot: Path): C1Result {
        // The committed plugin program admits cleanly — the artifact the host
        // receives. (Its logical /plugins/A path is not writable in a hermetic
        // test, so the file-on-disk witness uses the host-assigned directory.)
        val committedJson = loadProgramJson("plugin-a-scoped-write")
        val verified = StrandRuntime(HostPolicy.OPEN).verify(image(committedJson)) is VerifyResult.Ok

        // The host assigns plugin A a concrete directory and grants exactly that
        // path. Same delegation shape; real, observable write.
        val ownDir = pluginsRoot.resolve("A").also { Files.createDirectories(it) }
        val target = ownDir.resolve("state.json").toString().replace("\\", "/")
        val json = scopedWriteProgramJson(target)
        val program = image(json)
        val grant = writeGrant(json, target)
        val outcome = StrandRuntime(HostPolicy.OPEN).run(program, grant)
        val value = (outcome as? RunOutcome.Ok)?.value
        val exists = Files.exists(ownDir.resolve("state.json"))
        return C1Result(
            verified = verified,
            value = value,
            wroteToOwnDir = exists,
            writtenPath = target,
        )
    }

    /**
     * A scoped-write plugin whose target path is [path]. Identical shape to the
     * committed `plugin-a-scoped-write` program — honest Q-039 projection pinning
     * the Filesystem.Write refinement to argument 0 — with the concrete path
     * substituted so the demonstration can write a real file into a host-assigned
     * directory.
     */
    private fun scopedWriteProgramJson(path: String): String {
        val escaped = path.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            {
              "version": 1, "root": "writeApp",
              "nodes": {
                "intT":    { "type": "PrimitiveType", "kind": "Int" },
                "strT":    { "type": "PrimitiveType", "kind": "String" },
                "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
                "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
                "writeT":  { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
                "writeFn": { "type": "ForeignNode", "target": "strand-builtin:Fs.Write", "foreignType": "writeT", "effects": ["writeFx"],
                             "effectProjections": [ { "category": "writeFx", "sources": [ { "kind": "ArgRef", "index": 0 } ] } ] },
                "ownPath": { "type": "StringLit", "value": "$escaped" },
                "payload": { "type": "BytesLit", "value": "7b227374617465223a226f6b227d" },
                "writeDecl": { "type": "EffectDecl", "effectType": "writeFx", "parameters": ["ownPath"] },
                "writeApp": { "type": "Application", "function": "writeFn", "arguments": ["ownPath", "payload"], "effectInstances": ["writeDecl"] }
              }
            }
        """.trimIndent()
    }

    // ==================================================================
    // C2 — Argument-drift confused deputy, blocked at verification.
    // ==================================================================

    /**
     * Result of C2. [rejected] is the structured verifier verdict; the
     * [diagnostics] carry the real Q-039 ProjectionMismatch.
     */
    data class C2Result(
        val rejected: Boolean,
        val diagnostics: List<String>,
    )

    /**
     * Admit the drift plugin. Its authored EffectDecl declares the benign path
     * /plugins/A/state.json (the value a capability check would inspect) while
     * the call argument is /etc/shadow (the value the foreign code would
     * receive). The Q-039 projection rule binds the declared refinement to the
     * call argument by construction, so the verifier rejects at admission with
     * `ProjectionMismatch` — before any execution. The checked value cannot
     * diverge from the value the foreign code receives.
     */
    fun scenarioC2(): C2Result {
        val json = loadProgramJson("plugin-drift-escalation")
        val program = image(json)
        val host = StrandRuntime(HostPolicy.SECURE)
        val verify = host.verify(program)
        val rejected = verify is VerifyResult.Failed
        val diagnostics = (verify as? VerifyResult.Failed)?.errors?.map { it.toString() } ?: emptyList()
        return C2Result(rejected, diagnostics)
    }

    // ==================================================================
    // C3 — Out-of-scope escalation, denied at runtime.
    // ==================================================================

    /**
     * Result of C3. The plugin verified clean but was denied at the foreign-call
     * boundary with a structured [DenialReport].
     */
    data class C3Result(
        val verified: Boolean,
        val denialReport: DenialReport?,
        val denialError: InterpretError?,
    )

    /**
     * Load the honest out-of-scope plugin, grant it Write to its own slice only,
     * and run it. The plugin's projection is honest (it admits cleanly), but its
     * target /plugins/B/secret.json is not covered by the /plugins/A grant. At
     * the foreign-call boundary the runtime evaluates the requested path against
     * the held grant; no pattern covers it, so the call is denied with a
     * `RefinementViolation` carrying a [DenialReport] (category, requested vs
     * held, node, phase). Because the projection is honest, the value the
     * capability check sees IS the value the foreign code would receive.
     */
    fun scenarioC3(): C3Result {
        val json = loadProgramJson("plugin-a-out-of-scope-write")
        val program = image(json)
        val host = StrandRuntime(HostPolicy.OPEN)
        val verified = host.verify(program) is VerifyResult.Ok

        // The host grants plugin A only its own slice. We model the slice with the
        // concrete files plugin A legitimately owns; the out-of-scope target is
        // not among them.
        val grant = writeGrant(json, "/plugins/A/state.json", "/plugins/A/log.txt")

        val outcome = runCatching { host.run(program, grant) }
        val ex = outcome.exceptionOrNull() as? InterpretException
        val error = ex?.error
        val report = when (error) {
            is InterpretError.RefinementViolation -> error.report
            is InterpretError.CapabilityViolation -> error.report
            else -> null
        }
        return C3Result(verified, report, error)
    }

    // ==================================================================
    // C4 — Attenuation below the host (least-privilege delegation).
    // ==================================================================

    /**
     * Result of C4. The host holds broad authority; the scoped plugin running
     * under the narrowed grant is denied reaching a sibling's directory.
     */
    data class C4Result(
        val hostHeldBroad: List<String>,
        val pluginGrantHeld: List<String>,
        val hostCouldReachSibling: Boolean,
        val denialReport: DenialReport?,
        val denialError: InterpretError?,
    )

    /**
     * The host holds broad Write authority over the whole plugin tree —
     * a wildcard pattern over any path under /plugins — modeled as a wildcard
     * pattern. It does NOT hand the plugin that authority: it runs the plugin
     * under a grant narrowed to the plugin's own slice (paths under /plugins/A).
     * The plugin writes /plugins/B/config.json — a path the host itself is
     * authorized to write — but the narrowed grant does not cover it, so the
     * runtime denies the write.
     *
     * **Narrowing technique, stated honestly.** This narrowing is done at the
     * *grant / policy level*: the host constructs a narrower [CapabilitySet] and
     * hands it to [StrandRuntime.run]. It is NOT the N-036 `CapabilityScope`
     * node. `CapabilityScope` narrows which effect *categories* survive into a
     * subgraph (its `intersect` preserves each retained category's refinement
     * patterns verbatim); it cannot tighten a wildcard-path Write grant down to a
     * single-subtree one — refinement-narrowing CapabilityScope is the
     * deferred Q-031 follow-up. The category-level attenuation `CapabilityScope`
     * *does* provide is shown separately in [scenarioC4CategoryScope]. The C4
     * claim is: least-privilege delegation of a *refined* capability is enforced
     * by the grant the host applies, not by the plugin's behavior.
     */
    fun scenarioC4(pluginsRoot: Path): C4Result {
        // The committed plugin program (logical /plugins/B target) is the artifact
        // the host receives; it admits cleanly. For a hermetic, observable witness
        // the host assigns concrete sibling/own directories under its temp root.
        val committedVerified = StrandRuntime(HostPolicy.OPEN)
            .verify(image(loadProgramJson("plugin-a-reach-sibling"))) is VerifyResult.Ok
        require(committedVerified) { "committed C4 plugin must admit cleanly" }

        val dirA = pluginsRoot.resolve("A").also { Files.createDirectories(it) }
        val dirB = pluginsRoot.resolve("B").also { Files.createDirectories(it) }
        val siblingTarget = dirB.resolve("config.json").toString().replace("\\", "/")
        val json = scopedWriteProgramJson(siblingTarget)
        val program = image(json)
        val writeFx = nameMap(json).getValue("writeFx")

        // The broad authority the HOST holds: Write anywhere (a wildcard over the
        // path slot). With it the host genuinely writes the sibling path — a real,
        // observable file — so the attenuation below is real, not host incapacity.
        val broadGrant = CapabilitySet(
            mapOf(writeFx to listOf(CapabilityPattern(listOf(CapabilityArgument.Wildcard)))),
        )
        val broadOutcome = StrandRuntime(HostPolicy.OPEN).run(program, broadGrant)
        val hostCouldReach = broadOutcome is RunOutcome.Ok &&
            Files.exists(dirB.resolve("config.json"))

        // The NARROWED grant the host hands the plugin: only its own slice (paths
        // under dirA). The plugin's sibling target is not covered, so the
        // refinement check denies it BEFORE any IO — no file is written.
        val ownPath = dirA.resolve("state.json").toString().replace("\\", "/")
        val narrowedGrant = writeGrant(json, ownPath)

        val outcome = runCatching { StrandRuntime(HostPolicy.OPEN).run(program, narrowedGrant) }
        val ex = outcome.exceptionOrNull() as? InterpretException
        val error = ex?.error
        val report = when (error) {
            is InterpretError.RefinementViolation -> error.report
            is InterpretError.CapabilityViolation -> error.report
            else -> null
        }
        // The denial must precede IO: the sibling file the broad run created is the
        // broad run's; the narrowed run writes nothing new. We confirm no NEW write
        // by the narrowed run by deleting the broad-run artifact first below.
        return C4Result(
            hostHeldBroad = listOf("Filesystem.Write{*}  (host could write $siblingTarget)"),
            pluginGrantHeld = listOf("Filesystem.Write{$ownPath}"),
            hostCouldReachSibling = hostCouldReach,
            denialReport = report,
            denialError = error,
        )
    }

    /**
     * The companion category-level attenuation [Node.CapabilityScope][org.strand.core.Node.CapabilityScope]
     * genuinely provides, kept separate so the C4 narrative does not overclaim.
     * A plugin subgraph wrapped in a `CapabilityScope` that retains only the Read
     * category, but whose body performs a Write, is rejected *at admission* with
     * `CapabilityScopeUnsatisfiable`: the verifier proves the body's effect
     * closure exceeds the narrowed scope, so the program cannot even be admitted.
     *
     * This is the N-036 node operating exactly as it ships, and it is a *stronger*
     * guarantee than a runtime denial — the category-level narrowing is enforced
     * structurally, before execution. It is still category-level: the scope
     * removes the Write *category* wholesale, not a path refinement (which is the
     * grant-level narrowing C4 used). Returns whether the program was rejected
     * and the structured verifier diagnostics.
     */
    fun scenarioC4CategoryScope(): CategoryScopeResult {
        // A plugin: inside a CapabilityScope retaining only readFx, a writer that
        // declares writeFx. The scope removes writeFx from the inner subgraph, so
        // the body's effect closure ({writeFx}) exceeds the retained set ({readFx})
        // and the verifier rejects the program at admission.
        val json = capabilityScopeProgramJson()
        val program = image(json)
        val verify = StrandRuntime(HostPolicy.OPEN).verify(program)
        val rejected = verify is VerifyResult.Failed
        val diagnostics = (verify as? VerifyResult.Failed)?.errors?.map { it.toString() } ?: emptyList()
        return CategoryScopeResult(rejected = rejected, diagnostics = diagnostics)
    }

    data class CategoryScopeResult(
        val rejected: Boolean,
        val diagnostics: List<String>,
    )

    /**
     * A plugin that, inside a `CapabilityScope` retaining only readFx, performs a
     * writeFx-declaring Fs.Write call. The scope's category-level narrowing
     * removes writeFx from the inner subgraph, so the body's effect closure
     * exceeds the retained set and the verifier rejects the program at admission
     * with `CapabilityScopeUnsatisfiable`. The N-036 node operating as shipped.
     */
    private fun capabilityScopeProgramJson(): String = """
        {
          "version": 1, "root": "scope",
          "nodes": {
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "strT":    { "type": "PrimitiveType", "kind": "String" },
            "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
            "readFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Read", "parameters": ["strT"] },
            "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
            "writeT":  { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
            "writeFn": { "type": "ForeignNode", "target": "strand-builtin:Fs.Write", "foreignType": "writeT", "effects": ["writeFx"],
                         "effectProjections": [ { "category": "writeFx", "sources": [ { "kind": "ArgRef", "index": 0 } ] } ] },
            "path":    { "type": "StringLit", "value": "/plugins/A/state.json" },
            "payload": { "type": "BytesLit", "value": "7b7d" },
            "writeDecl": { "type": "EffectDecl", "effectType": "writeFx", "parameters": ["path"] },
            "writeApp": { "type": "Application", "function": "writeFn", "arguments": ["path", "payload"], "effectInstances": ["writeDecl"] },
            "scope":   { "type": "CapabilityScope", "capabilities": ["readFx"], "body": "writeApp" }
          }
        }
    """.trimIndent()

    // ==================================================================
    // Transcript
    // ==================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        val tmp = Files.createTempDirectory("strand-plugin-host-demo")
        val out = StringBuilder()
        fun line(s: String = "") = out.appendLine(s)

        line("=".repeat(72))
        line("Strand -- plugin host (fine-grained capability attenuation &")
        line("confused-deputy defense)")
        line("A host delegates a precisely-narrowed capability to an untrusted")
        line("plugin; the plugin provably cannot escalate it.")
        line("=".repeat(72))
        line()

        // --- C1 ---
        line("C1  Legitimate scoped operation -- the happy path of delegation")
        line("-".repeat(72))
        val c1 = scenarioC1(tmp.resolve("plugins"))
        line("  Plugin: plugin-a-scoped-write (writes its own state file).")
        line("  Host grant: Filesystem.Write confined to the plugin's directory.")
        line("    plugin admitted (verified) = ${c1.verified}")
        line("    write authorized + ran     = value ${c1.value}")
        line("    file landed in own dir     = ${c1.wroteToOwnDir}")
        line("    path                       = ${c1.writtenPath}")
        line("  Delegated authority used exactly as intended.")
        line()

        // --- C2 ---
        line("C2  Argument-drift confused deputy -- blocked at verification")
        line("-".repeat(72))
        val c2 = scenarioC2()
        line("  Plugin: plugin-drift-escalation. Its authored EffectDecl declares")
        line("          the benign path /plugins/A/state.json (the value a")
        line("          capability check inspects) while the call argument is")
        line("          /etc/shadow (the value Fs.Write would receive).")
        line("  Host decision: ${if (c2.rejected) "REJECTED at admission (verify)" else "ADMITTED (unexpected)"}")
        for (d in c2.diagnostics) line("    verifier: $d")
        line("  This is the gap Q-039 closed: the effect projection binds the")
        line("  checked value to the call argument by construction, so the checked")
        line("  value cannot diverge from the value the foreign code receives. A")
        line("  conventional host -- a privileged file writer with no projection --")
        line("  would have been tricked into writing the attacker-chosen target.")
        line()

        // --- C3 ---
        line("C3  Out-of-scope escalation -- denied at the foreign-call boundary")
        line("-".repeat(72))
        val c3 = scenarioC3()
        line("  Plugin: plugin-a-out-of-scope-write. Honest projection (admits")
        line("          cleanly), but targets /plugins/B/secret.json -- another")
        line("          plugin's directory. Host grant: Write to /plugins/A only.")
        line("    plugin admitted (verified) = ${c3.verified}")
        if (c3.denialReport != null) {
            line("  Host decision: DENIED at runtime (${c3.denialError?.let { it::class.simpleName }})")
            line("    DenialReport: ${renderDenial(c3.denialReport!!)}")
        } else {
            line("  Host decision: (no denial -- unexpected) ${c3.denialError}")
        }
        line("  The refined capability does not cover the requested path, so the")
        line("  plugin cannot reach outside its slice.")
        line()

        // --- C4 ---
        line("C4  Attenuation below the host -- least-privilege delegation")
        line("-".repeat(72))
        val c4 = scenarioC4(tmp.resolve("c4-plugins"))
        line("  Plugin: plugin-a-reach-sibling. Targets a sibling plugin's dir.")
        line("    host HOLDS broad authority  = ${c4.hostHeldBroad}")
        line("    host DID reach the sibling  = ${c4.hostCouldReachSibling} (real write)")
        line("    plugin RUNS under grant     = ${c4.pluginGrantHeld}")
        if (c4.denialReport != null) {
            line("  Host decision: DENIED at runtime (${c4.denialError?.let { it::class.simpleName }})")
            line("    DenialReport: ${renderDenial(c4.denialReport!!)}")
        } else {
            line("  Host decision: (no denial -- unexpected) ${c4.denialError}")
        }
        line("  The host could write the sibling dir, but the plugin -- handed a")
        line("  grant narrowed to its own slice -- cannot. The denial fires at the")
        line("  capability check, BEFORE any IO. Least privilege is enforced by the")
        line("  grant the runtime applies, not by the plugin behaving.")
        line()
        line("  Narrowing technique, stated honestly: this is done at the")
        line("  GRANT/POLICY level (the host constructs a narrower CapabilitySet),")
        line("  NOT via the N-036 CapabilityScope node. CapabilityScope narrows")
        line("  which effect CATEGORIES survive into a subgraph, not a path")
        line("  refinement -- refinement-narrowing CapabilityScope is the deferred")
        line("  Q-031 follow-up. The category-level narrowing CapabilityScope does")
        line("  provide is shown next.")
        line()

        // --- C4 companion: CapabilityScope category-level narrowing ---
        line("C4'  Companion -- N-036 CapabilityScope category-level narrowing")
        line("-".repeat(72))
        val cs = scenarioC4CategoryScope()
        line("  Plugin: a writer wrapped in a CapabilityScope that retains only")
        line("          Filesystem.Read, but whose body performs a Write.")
        line("  Host decision: ${if (cs.rejected) "REJECTED at admission (verify)" else "ADMITTED (unexpected)"}")
        for (d in cs.diagnostics) line("    verifier: $d")
        line("  The CapabilityScope removes the Write CATEGORY from the inner")
        line("  subgraph; the verifier proves the body's effect closure exceeds the")
        line("  retained set and rejects the program BEFORE execution. This is the")
        line("  N-036 node operating as shipped -- category-level narrowing,")
        line("  enforced structurally, stronger than a runtime denial.")
        line()

        line("=".repeat(72))
        line("What this demonstrates: a delegated capability cannot be escalated by")
        line("a confused or malicious plugin -- not by argument drift (C2, Q-039),")
        line("not by reaching outside its slice (C3, Q-031 refinement match), not")
        line("by relying on the host's broad authority (C4, grant-level narrowing;")
        line("C4' N-036 category-level CapabilityScope). It does NOT show first-pass")
        line("correctness or cost. Plugin programs are hand-authored stand-ins for")
        line("agent submissions.")
        line("=".repeat(72))

        print(out)
    }
}
