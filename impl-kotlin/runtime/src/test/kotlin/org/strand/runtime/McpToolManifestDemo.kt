package org.strand.runtime

import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.HostPolicy
import org.strand.verifier.VerifyError
import org.strand.verifier.VerifyResult

/**
 * A verifiable tool-capability-manifest host, demonstrated.
 *
 * Where an MCP server's capabilities are prose a client trusts by reading, a
 * Strand-backed tool bundle ships an N-046 [Node.ModuleManifest] whose per-export
 * declared effects are *machine-checked against the code*: the verifier admits
 * the manifest only when each export's `declaredEffects` exactly equals its
 * effect surface (the function's declared effect row for a function export, the
 * construction closure for a value export). The manifest is therefore a *verified
 * statement* of what each tool can do, not a claim — and because the manifest is
 * content-addressed, a capability change is visibly a different hash.
 *
 * This is the executable companion to the N-046 ModuleManifest mechanism
 * (`proposals/cross-store-federation.md` § 4.3–4.4) and its corpus exemplars
 * (corpus 79 accept, 80 reject). The host is an ordinary JVM caller of the
 * shipped embedding surface — the Q-054 [StrandRuntime] facade — and introduces
 * no language feature, node category, encoding change, or verifier rule. The
 * manifest programs are hand-authored canonical dag-json (Layer A has no MFT/MEX
 * code for ModuleManifest yet), so the demo isolates the *verifier's* manifest
 * certification from the separate agent-generation question.
 *
 * Three scenarios:
 *
 *  - **M1 Manifest as machine-checked contract.** A well-formed manifest bundling
 *    a read-only `fs.lookup` tool (declares `Filesystem.Read`) and a pure
 *    `text.format` tool (no effects). It verifies precisely because each export's
 *    `declaredEffects` equals its surface. The host renders, per export, its
 *    displayName + declaredEffects — "this tool can only do X" — the
 *    machine-checked capability statement. Contrast: an MCP server's capabilities
 *    are prose; here they are verified from the code.
 *  - **M2 Honesty enforced.** A manifest that *under-declares*: the `fs.lookup`
 *    export's code surface is `{Filesystem.Read}` but the manifest claims `{}`.
 *    The verifier rejects it with `ManifestExportEffectMismatch`. The host refuses
 *    to publish a manifest whose declared capabilities don't match the code.
 *  - **M3 Hash-pinned contract.** The well-formed manifest's root hash, contrasted
 *    with a second manifest that differs only in an export's declared capability
 *    set (the `fs.lookup` tool now performs `Filesystem.Write`). The capability
 *    change produces a different content hash — tamper-evident by construction.
 *
 * The three scenario methods return structured results that both [main] (the
 * transcript) and `McpToolManifestDemoTest` (the assertions) consume, so the
 * printed demonstration and the regression net share one body of code.
 */
object McpToolManifestDemo {

    // ------------------------------------------------------------------
    // Program loading — the host admits the canonical dag-json artifact.
    // ------------------------------------------------------------------

    /**
     * Load a committed manifest program (canonical dag-json) from the classpath.
     * The source of truth is `demos/mcp-tool-manifest/programs/<name>.json`; the
     * `:runtime` build copies those onto the test classpath under
     * `/demo/programs/` (see `runtime/build.gradle.kts`).
     */
    fun loadProgramJson(name: String): String =
        McpToolManifestDemo::class.java.getResourceAsStream("/demo/programs/$name.json")
            ?.bufferedReader()?.readText()
            ?: error("demo program /demo/programs/$name.json not on the test classpath")

    /** Finalize a program's authored dag-json to the host's [ProgramImage]. */
    fun image(json: String): ProgramImage {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
    }

    /** The root content hash of a program's manifest, as a hex string. */
    fun rootHashHex(json: String): String {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return finalized.nodeIdToHash.getValue(finalized.root).toString()
    }

    // ------------------------------------------------------------------
    // Rendering — the verified capability statement, per export.
    // ------------------------------------------------------------------

    /**
     * One export of a manifest as the host renders it for an agent or auditor:
     * the displayName and the *verified* effect set the export can incur. Because
     * the manifest verified, [declaredEffects] is not a claim — it equals the
     * export's actual effect surface, checked against the code.
     */
    data class RenderedExport(val displayName: String, val declaredEffects: List<String>) {
        /** "this tool can only do X" — the machine-checked capability line. */
        fun capabilityLine(): String =
            if (declaredEffects.isEmpty()) "$displayName -> pure (no effects)"
            else "$displayName -> can only: ${declaredEffects.joinToString(", ")}"
    }

    /**
     * Read the manifest at [program]'s root and render each export's verified
     * capability statement. The `declaredEffects` NodeIds are resolved to their
     * `EffectCategory.categoryName` through the program store.
     */
    fun renderManifestExports(program: ProgramImage): List<RenderedExport> {
        val manifest = program.store.getOrNull(program.root) as? Node.ModuleManifest
            ?: error("program root is not a ModuleManifest")
        return manifest.exports.map { export ->
            val effects = export.declaredEffects.mapNotNull { effId ->
                (program.store.getOrNull(effId) as? Node.EffectCategory)?.categoryName
            }
            RenderedExport(export.displayName, effects)
        }
    }

    // ==================================================================
    // M1 — Manifest as machine-checked contract
    // ==================================================================

    /** Result of M1. */
    data class M1Result(
        val admitted: Boolean,
        val exports: List<RenderedExport>,
    )

    /**
     * Admit the well-formed manifest. It verifies precisely because each export's
     * `declaredEffects` exactly equals its effect surface — the read-only lookup
     * declares `Filesystem.Read`, the pure formatter declares nothing. The host
     * then renders each export's verified capability statement.
     */
    fun scenarioM1(): M1Result {
        val json = loadProgramJson("manifest-tools")
        val program = image(json)
        val host = StrandRuntime(HostPolicy.SECURE)
        val verify = host.verify(program)
        val admitted = verify is VerifyResult.Ok
        val exports = if (admitted) renderManifestExports(program) else emptyList()
        return M1Result(admitted, exports)
    }

    // ==================================================================
    // M2 — Honesty enforced (under-declaration rejected)
    // ==================================================================

    /** Result of M2. */
    data class M2Result(
        val rejected: Boolean,
        val mismatch: VerifyError.ManifestExportEffectMismatch?,
        val diagnostics: List<String>,
    )

    /**
     * Admit the under-declaring manifest: the lookup export's code surface is
     * `{Filesystem.Read}` but the manifest claims `{}`. The verifier rejects with
     * `ManifestExportEffectMismatch` — a consumer reading the manifest must not be
     * able to believe an export is purer than it is.
     */
    fun scenarioM2(): M2Result {
        val json = loadProgramJson("manifest-underdeclared")
        val program = image(json)
        val host = StrandRuntime(HostPolicy.SECURE)
        val verify = host.verify(program)
        val errors = (verify as? VerifyResult.Failed)?.errors ?: emptyList()
        val mismatch = errors.filterIsInstance<VerifyError.ManifestExportEffectMismatch>().firstOrNull()
        return M2Result(
            rejected = verify is VerifyResult.Failed,
            mismatch = mismatch,
            diagnostics = errors.map { it.toString() },
        )
    }

    // ==================================================================
    // M3 — Hash-pinned contract
    // ==================================================================

    /** Result of M3. */
    data class M3Result(
        val baselineHashHex: String,
        val elevatedHashHex: String,
        val baselineAdmitted: Boolean,
        val elevatedAdmitted: Boolean,
        val baselineLookupEffects: List<String>,
        val elevatedLookupEffects: List<String>,
    ) {
        val hashesDiffer: Boolean get() = baselineHashHex != elevatedHashHex
    }

    /**
     * Pin the well-formed manifest's root hash, then show that a second
     * manifest differing only in the lookup tool's declared capability set
     * (Read -> Write) produces a different content hash. Both verify (both are
     * honest about their code); the difference is a capability change, and the
     * content hash makes it visible.
     */
    fun scenarioM3(): M3Result {
        val baselineJson = loadProgramJson("manifest-tools")
        val elevatedJson = loadProgramJson("manifest-tools-elevated")

        val host = StrandRuntime(HostPolicy.SECURE)
        val baselineImg = image(baselineJson)
        val elevatedImg = image(elevatedJson)

        val baseExports = renderManifestExports(baselineImg)
        val elevExports = renderManifestExports(elevatedImg)

        return M3Result(
            baselineHashHex = rootHashHex(baselineJson),
            elevatedHashHex = rootHashHex(elevatedJson),
            baselineAdmitted = host.verify(baselineImg) is VerifyResult.Ok,
            elevatedAdmitted = host.verify(elevatedImg) is VerifyResult.Ok,
            baselineLookupEffects = baseExports.first { it.displayName == "fs.lookup" }.declaredEffects,
            elevatedLookupEffects = elevExports.first { it.displayName == "fs.lookup" }.declaredEffects,
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
        line("Strand -- verifiable tool-capability manifest (MCP-tool demonstration)")
        line("An N-046 ModuleManifest whose per-export declared effects are")
        line("machine-checked against the code: the manifest is a verified")
        line("statement of what each tool CAN do, not prose a client must trust.")
        line("=".repeat(72))
        line()

        // --- M1 ---
        line("M1  Manifest as machine-checked contract")
        line("-".repeat(72))
        val m1 = scenarioM1()
        line("  Bundle: manifest-tools (a read-only lookup tool + a pure format tool).")
        line("  Host decision: ${if (m1.admitted) "ADMITTED (verified)" else "REJECTED"}")
        line("  The manifest verified precisely because each export's declaredEffects")
        line("  exactly equals its effect surface -- so each line below is a verified")
        line("  capability statement, checked against the code, not a claim:")
        for (e in m1.exports) line("    ${e.capabilityLine()}")
        line("  Contrast: an MCP server advertises its capabilities as prose a client")
        line("  trusts by reading. Here the capabilities are derived from the code and")
        line("  the manifest is admitted only when they match.")
        line()

        // --- M2 ---
        line("M2  Honesty enforced -- under-declaration rejected")
        line("-".repeat(72))
        val m2 = scenarioM2()
        line("  Bundle: manifest-underdeclared (the lookup tool's code surface is")
        line("          {Filesystem.Read}, but the manifest claims {} -- it under-")
        line("          declares, hiding what the tool can do).")
        line("  Host decision: ${if (m2.rejected) "REJECTED at admission (verify)" else "ADMITTED"}")
        for (d in m2.diagnostics) line("    verifier: $d")
        line("  The host refuses to publish a manifest whose declared capabilities do")
        line("  not match the code. A consumer reading the manifest cannot be misled")
        line("  into believing an export is purer than it is.")
        line()

        // --- M3 ---
        line("M3  Hash-pinned contract -- a capability change is a different hash")
        line("-".repeat(72))
        val m3 = scenarioM3()
        line("  manifest-tools          lookup effects = ${m3.baselineLookupEffects}")
        line("    root hash             = ${m3.baselineHashHex.take(24)}...")
        line("  manifest-tools-elevated lookup effects = ${m3.elevatedLookupEffects}")
        line("    root hash             = ${m3.elevatedHashHex.take(24)}...")
        line("  both verify (both honest) = ${m3.baselineAdmitted && m3.elevatedAdmitted}")
        line("  hashes differ             = ${m3.hashesDiffer}")
        line("  The two manifests differ only in the lookup tool's declared")
        line("  capability set; the content-addressed manifest hash makes that change")
        line("  visibly a different hash -- tamper-evident by construction.")
        line()

        line("=".repeat(72))
        line("What this demonstrates: a manifest whose declared per-export effects are")
        line("machine-checked to equal the code's effect surface, with capability")
        line("changes surfaced as hash changes. What it does NOT: it checks the")
        line("DECLARED surface equals the code's surface -- it does not bound the")
        line("transitive effects of a tool that shells out (Process.Spawn is opaque);")
        line("displayName is metadata, not hashed; the manifests are hand-authored;")
        line("and it is NOT first-pass correctness or cost.")
        line("=".repeat(72))

        print(out)
    }
}
