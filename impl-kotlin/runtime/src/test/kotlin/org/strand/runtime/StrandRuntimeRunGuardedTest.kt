package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.Value

/**
 * Q-073 (ADR-010): the self-gating primitive [StrandRuntime.runGuarded]. Gates
 * a program's execution on a declared budget BEFORE any effect runs, closing
 * the write / reason / decide / run loop within a single principal.
 *
 * Companion to [StrandRuntimeAnalyzeTest] (Q-072, the `analyze` facade this
 * builds on) and [org.strand.corpus.LatentEffectClosureTest] /
 * [org.strand.corpus.ProgramAnalysisTest] (the latent-channel fixtures reused
 * here as inline verify-only programs, so no file enters the golden-hash
 * corpus).
 */
class StrandRuntimeRunGuardedTest {

    private data class Loaded(val image: ProgramImage, val nameToId: Map<String, NodeId>)

    private fun load(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val image = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        return Loaded(image, ingest.nameMap)
    }

    private fun categoryId(store: NodeStore, name: String): NodeId? {
        for (id in store.ids()) {
            val n = store.getOrNull(id)
            if (n is Node.EffectCategory && n.categoryName == name) return id
        }
        return null
    }

    // ------------------------------------------------------------------
    // (a) within budget: runs, returns Ran with the expected value.
    // ------------------------------------------------------------------

    @Test
    fun `a program whose total closure is within budget runs and returns Ran`() {
        val loaded = load(TIME_NOW)
        val rt = StrandRuntime(HostPolicy.OPEN)
        val nowFx = categoryId(loaded.image.store, "Time.Now")!!
        val budget = CapabilitySet.ofCategories(setOf(nowFx))

        val outcome = rt.runGuarded(loaded.image, budget)
        val ran = assertInstanceOf(GuardedOutcome.Ran::class.java, outcome)
        val ok = assertInstanceOf(RunOutcome.Ok::class.java, ran.outcome)
        // Time.Now is a fixed replay timestamp builtin — assert a value was produced.
        assertTrue(ok.value is Value.IntV, "Time.Now under budget runs and produces its Int value")
    }

    // ------------------------------------------------------------------
    // (b) direct closure exceeds budget: Refused before execution, marked direct.
    // ------------------------------------------------------------------

    @Test
    fun `a program whose direct closure exceeds budget is refused before execution with the category marked direct`() {
        val loaded = load(FS_WRITE_DIRECT)
        val rt = StrandRuntime(HostPolicy.OPEN)
        // Budget grants nothing — Filesystem.Write is directly performed and exceeds it.
        val budget = CapabilitySet.EMPTY

        val outcome = rt.runGuarded(loaded.image, budget)
        val refused = assertInstanceOf(GuardedOutcome.Refused::class.java, outcome)

        val writeFx = categoryId(loaded.image.store, "Filesystem.Write")!!
        assertEquals(
            setOf(writeFx), refused.report.exceeding.keys,
            "Filesystem.Write is the sole exceeding category",
        )
        assertEquals(
            EffectChannel.DIRECT, refused.report.exceeding[writeFx],
            "the write is directly performed (in rootClosure), not latent",
        )
        assertEquals(setOf(writeFx), refused.report.requested, "requested is the program's total closure")
        assertEquals(emptySet<NodeId>(), refused.report.granted, "granted mirrors the empty budget")

        // No effect happened: the write target file must not exist.
        assertTrue(
            !java.nio.file.Files.exists(java.nio.file.Path.of(FS_WRITE_TARGET_PATH)),
            "the refusal must occur before any effect — the write must never happen",
        )
    }

    // ------------------------------------------------------------------
    // (c) within direct budget but latent closure exceeds: Refused, marked latent.
    // ------------------------------------------------------------------

    @Test
    fun `a program within its direct budget but whose latent closure exceeds budget is refused with the category marked latent`() {
        val loaded = load(EFFECTFUL_TOOL_DEF_PROGRAM)
        val rt = StrandRuntime(HostPolicy.OPEN)
        val genFx = categoryId(loaded.image.store, "LLM.Generate")!!
        val writeFx = categoryId(loaded.image.store, "Filesystem.Write")!!
        // Budget grants the direct call (LLM.Generate) but NOT the tool's latent write.
        val budget = CapabilitySet.ofCategories(setOf(genFx))

        val outcome = rt.runGuarded(loaded.image, budget)
        val refused = assertInstanceOf(GuardedOutcome.Refused::class.java, outcome)

        assertEquals(
            setOf(writeFx), refused.report.exceeding.keys,
            "only the latent Filesystem.Write exceeds; the direct LLM.Generate is within budget",
        )
        assertEquals(
            EffectChannel.LATENT, refused.report.exceeding[writeFx],
            "the tool's write is reachable only indirectly — a naive direct-only gate would miss this",
        )
        assertEquals(
            setOf(genFx, writeFx), refused.report.requested,
            "requested is the total closure — direct + latent",
        )
        assertEquals(setOf(genFx), refused.report.granted, "granted mirrors the declared budget")
    }

    // ------------------------------------------------------------------
    // (d) non-verifying program: VerifyFailed.
    // ------------------------------------------------------------------

    @Test
    fun `a non-verifying program returns VerifyFailed`() {
        val loaded = load(ILL_TYPED)
        val rt = StrandRuntime(HostPolicy.OPEN)

        val outcome = rt.runGuarded(loaded.image, CapabilitySet.EMPTY)
        val failed = assertInstanceOf(GuardedOutcome.VerifyFailed::class.java, outcome)
        assertTrue(failed.errors.isNotEmpty(), "the verify diagnostics are carried on the failure outcome")
    }

    companion object {
        // Time.Now under a granted capability — verifies, reaches {Time.Now}, no filesystem I/O.
        val TIME_NOW = """
        {
          "version": 1,
          "root": "app",
          "nodes": {
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "nowFx":    { "type": "EffectCategory", "categoryName": "Time.Now", "parameters": [] },
            "nowT":     { "type": "FunctionType", "parameters": [], "result": "intT", "effects": ["nowFx"] },
            "nowFn":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                          "foreignType": "nowT", "effects": ["nowFx"] },
            "nowDecl":  { "type": "EffectDecl", "effectType": "nowFx", "parameters": [] },
            "app":      { "type": "Application", "function": "nowFn", "arguments": [],
                          "effectInstances": ["nowDecl"] }
          }
        }
        """.trimIndent()

        // A quarantined scratch path — never actually reached if the refusal fires
        // correctly, since runGuarded must refuse before invoking the write.
        val FS_WRITE_TARGET_PATH: String =
            System.getProperty("java.io.tmpdir")
                .replace("\\", "/")
                .trimEnd('/') + "/strand-run-guarded-test-must-not-exist.txt"

        // A program that DIRECTLY performs Filesystem.Write (reaches the
        // Application the verifier walks) — over-budget under an empty grant,
        // exceeding category marked DIRECT.
        val FS_WRITE_DIRECT = """
        {
          "version": 1,
          "root": "app",
          "nodes": {
            "strT":     { "type": "PrimitiveType", "kind": "String" },
            "intT":     { "type": "PrimitiveType", "kind": "Int" },
            "writeFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                          "parameters": ["strT"] },
            "pathT":    { "type": "ProductTypeField", "name": "path", "fieldType": "strT" },
            "contentT": { "type": "ProductTypeField", "name": "content", "fieldType": "strT" },
            "argT":     { "type": "ProductType", "fields": ["pathT", "contentT"] },
            "writeT":   { "type": "FunctionType", "parameters": ["argT"], "result": "intT",
                          "effects": ["writeFx"] },
            "writeFn":  { "type": "ForeignNode", "target": "strand-builtin:Fs.Write",
                          "foreignType": "writeT", "effects": ["writeFx"] },
            "pathLit":  { "type": "StringLit", "value": "${'$'}FS_WRITE_TARGET_PATH_PLACEHOLDER" },
            "pathLit2": { "type": "StringLit", "value": "${'$'}FS_WRITE_TARGET_PATH_PLACEHOLDER" },
            "contentLit": { "type": "StringLit", "value": "should never be written" },
            "pathFV":   { "type": "ProductFieldValue", "fieldName": "path", "value": "pathLit" },
            "contentFV": { "type": "ProductFieldValue", "fieldName": "content", "value": "contentLit" },
            "argV":     { "type": "ProductValue", "ofType": "argT", "fields": ["pathFV", "contentFV"] },
            "writeDecl": { "type": "EffectDecl", "effectType": "writeFx", "parameters": ["pathLit2"] },
            "app":      { "type": "Application", "function": "writeFn", "arguments": ["argV"],
                          "effectInstances": ["writeDecl"] }
          }
        }
        """.trimIndent().replace("\$FS_WRITE_TARGET_PATH_PLACEHOLDER", FS_WRITE_TARGET_PATH)

        // Reuse of the LatentEffectClosureTest / ProgramAnalysisTest fixture shape:
        // a Generate call (direct closure {LLM.Generate}) handed a Filesystem.Write
        // ToolDef (latent closure {Filesystem.Write}).
        val EFFECTFUL_TOOL_DEF_PROGRAM = """
        {
          "version": 1,
          "root": "genApp",
          "nodes": {
            "strT":        { "type": "PrimitiveType", "kind": "String" },
            "bytesT":      { "type": "PrimitiveType", "kind": "Bytes" },
            "intT":        { "type": "PrimitiveType", "kind": "Int" },

            "llmGenFx":    { "type": "EffectCategory", "categoryName": "LLM.Generate",
                             "parameters": ["strT", "strT"] },
            "writeFx":     { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                             "parameters": ["strT"] },

            "cityFldT":    { "type": "ProductTypeField", "name": "city", "fieldType": "strT" },
            "lookupParamsT": { "type": "ProductType", "fields": ["cityFldT"] },
            "lookupSchema": { "type": "Schema", "schemaName": "LookupParams",
                              "valueType": "lookupParamsT", "invariants": [] },

            "writeT":      { "type": "FunctionType", "parameters": ["lookupParamsT"],
                             "result": "intT" },
            "writeImpl":   { "type": "ForeignNode", "target": "strand-builtin:Fs.Write",
                             "foreignType": "writeT", "effects": ["writeFx"] },

            "lookupTool":  { "type": "ToolDef", "name": "lookup",
                             "description": "writes then looks up",
                             "parameterSchema": "lookupSchema",
                             "implementation": "writeImpl" },

            "modelFieldT": { "type": "ProductTypeField", "name": "model",    "fieldType": "strT" },
            "toolsFieldT": { "type": "ProductTypeField", "name": "tools",    "fieldType": "bytesT" },
            "genRequestT": { "type": "ProductType", "fields": ["modelFieldT", "toolsFieldT"] },

            "genResultT":  { "type": "PrimitiveType", "kind": "String" },
            "genFnT":      { "type": "FunctionType", "parameters": ["genRequestT"],
                             "result": "genResultT", "effects": ["llmGenFx"] },
            "genFn":       { "type": "ForeignNode", "target": "strand-builtin:Anthropic.Messages.Create",
                             "foreignType": "genFnT", "effects": ["llmGenFx"] },

            "modelLit":    { "type": "StringLit", "value": "claude" },
            "modelLit2":   { "type": "StringLit", "value": "claude" },
            "providerLit": { "type": "StringLit", "value": "anthropic" },
            "toolsBytes":  { "type": "BytesLit", "value": "" },
            "modelFV":     { "type": "ProductFieldValue", "fieldName": "model", "value": "modelLit" },
            "toolsFV":     { "type": "ProductFieldValue", "fieldName": "tools", "value": "toolsBytes" },
            "genRequest":  { "type": "ProductValue", "ofType": "genRequestT",
                             "fields": ["modelFV", "toolsFV"] },

            "genDecl":     { "type": "EffectDecl", "effectType": "llmGenFx",
                             "parameters": ["providerLit", "modelLit2"] },
            "callApp":     { "type": "Application", "function": "genFn",
                             "arguments": ["genRequest"], "effectInstances": ["genDecl"] },

            "genApp":      { "type": "Let", "name": "_tool",
                             "value": "lookupTool", "body": "callApp" }
          }
        }
        """.trimIndent()

        // An Application of a non-function (an IntLit) — fails verification with NotAFunction.
        val ILL_TYPED = """
        {
          "version": 1,
          "root": "app",
          "nodes": {
            "notFn":  { "type": "IntLit", "value": 7 },
            "arg":    { "type": "IntLit", "value": 3 },
            "app":    { "type": "Application", "function": "notFn", "arguments": ["arg"] }
          }
        }
        """.trimIndent()
    }
}
