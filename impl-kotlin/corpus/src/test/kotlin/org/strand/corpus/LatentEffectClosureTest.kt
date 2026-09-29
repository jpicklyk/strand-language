package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * Q-070 / Q-071: the verifier surfaces a distinct *latent* effect channel on
 * [VerifyResult.Ok] — [VerifyResult.Ok.latentClosures], keyed by NodeId exactly
 * as `nodeClosures` is, with a [VerifyResult.Ok.rootLatentClosure] accessor and
 * a [VerifyResult.Ok.totalClosure] = rootClosure ∪ rootLatentClosure. It folds
 * the effect surface of effects reachable only through INDIRECT invocation:
 *
 *  - N-044 ToolDef implementations. A tool the model may invoke during a
 *    Generate call carries its own effect surface; because the model invokes it
 *    indirectly, that surface never reaches an Application the verifier walks and
 *    so is absent from the (directly-performed) root closure. It surfaces in the
 *    latent channel instead.
 *  - Higher-order callbacks. Any effectful value passed as an ARGUMENT into an
 *    Application (a `List.Map`-shaped pattern) is potentially invoked indirectly
 *    inside the callee; its effect surface surfaces latently, not in the root
 *    closure.
 *
 * The parallel to Q-067's [SurfacedEffectClosureTest] is deliberate: that pins
 * the directly-performed closure; this pins the indirectly-reachable one. The
 * effectful-ToolDef and higher-order-callback programs are built inline (as
 * verify-only fixtures) so no file enters the golden-hash corpus; the pure-tool
 * and benign cases reuse committed corpus programs.
 */
class LatentEffectClosureTest {

    private data class Loaded(
        val store: NodeStore,
        val root: NodeId,
        val verify: VerifyResult.Ok,
        val nameToId: Map<String, NodeId>,
    )

    private fun ingest(text: String): Loaded {
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok, "verifier failed: $verify")
        verify as VerifyResult.Ok
        return Loaded(finalized.store, finalized.root, verify, ingest.nameMap)
    }

    private fun loadResource(resource: String): Loaded {
        val text = LatentEffectClosureTest::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.readText()
            ?: error("missing resource $resource")
        return ingest(text)
    }

    /** Render a set of EffectCategory NodeIds to their category names. */
    private fun categoryNames(store: NodeStore, ids: Set<NodeId>): Set<String> =
        ids.mapNotNull { (store.getOrNull(it) as? Node.EffectCategory)?.categoryName }
            .toSortedSet()

    /**
     * A Generate call handed a `Filesystem.Write`-bearing ToolDef. The Generate
     * effect is directly performed (it reaches the Application the verifier
     * walks) so it surfaces in the ROOT closure; the tool's write is reachable
     * only through the model's tool-use loop so it surfaces in the LATENT
     * channel — and NOT in the root closure.
     */
    private val effectfulToolDefProgram = """
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

    /**
     * A higher-order function applied to an effectful `Filesystem.Write`
     * ForeignNode callback (the `List.Map`-shaped pattern). The applied function
     * ignores its callback argument — modelling a builtin whose internal
     * invocation the verifier never walks — so the callback's write does NOT
     * reach the root closure; it surfaces in the latent channel.
     */
    private val higherOrderCallbackProgram = """
    {
      "version": 1,
      "root": "topApp",
      "nodes": {
        "strT":       { "type": "PrimitiveType", "kind": "String" },
        "bytesT":     { "type": "PrimitiveType", "kind": "Bytes" },
        "intT":       { "type": "PrimitiveType", "kind": "Int" },
        "unitT":      { "type": "PrimitiveType", "kind": "Unit" },

        "writeFx":    { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                        "parameters": ["strT"] },

        "cbT":        { "type": "FunctionType", "parameters": ["strT", "bytesT"],
                        "result": "intT", "effects": ["writeFx"] },
        "writeFn":    { "type": "ForeignNode", "target": "strand-builtin:Fs.Write",
                        "foreignType": "cbT", "effects": ["writeFx"] },

        "cbParam":    { "type": "ParameterDecl", "name": "cb", "paramType": "cbT" },
        "unitLit":    { "type": "UnitLit" },
        "mapLike":    { "type": "Lambda", "parameters": ["cbParam"], "body": "unitLit" },

        "topApp":     { "type": "Application", "function": "mapLike",
                        "arguments": ["writeFn"] }
      }
    }
    """.trimIndent()

    @Test
    fun `effectful ToolDef surfaces the tool write in latent and the Generate call in root`() {
        val p = ingest(effectfulToolDefProgram)

        val rootDirect = categoryNames(p.store, p.verify.rootClosure(p.root))
        val rootLatent = categoryNames(p.store, p.verify.rootLatentClosure(p.root))
        val total = categoryNames(p.store, p.verify.totalClosure(p.root))

        assertEquals(
            setOf("LLM.Generate"), rootDirect,
            "the Generate call is directly performed; only it belongs in the root closure",
        )
        assertEquals(
            setOf("Filesystem.Write"), rootLatent,
            "the tool implementation's write is reachable only indirectly — it belongs in latent",
        )
        assertTrue(
            "Filesystem.Write" !in rootDirect,
            "the tool's write must NOT leak into the directly-performed root closure",
        )
        assertEquals(
            setOf("LLM.Generate", "Filesystem.Write"), total,
            "totalClosure = rootClosure ∪ rootLatentClosure",
        )

        // The latent contribution is keyed at the ToolDef NodeId, not only the root.
        val toolId = p.nameToId.getValue("lookupTool")
        assertEquals(
            setOf("Filesystem.Write"),
            categoryNames(p.store, p.verify.latentClosures[toolId] ?: emptySet()),
            "the ToolDef site carries the latent write keyed by its own NodeId",
        )
    }

    @Test
    fun `higher-order effectful callback surfaces its effect in latent and not in root`() {
        val p = ingest(higherOrderCallbackProgram)

        val rootDirect = categoryNames(p.store, p.verify.rootClosure(p.root))
        val rootLatent = categoryNames(p.store, p.verify.rootLatentClosure(p.root))

        assertEquals(
            emptySet<String>(), rootDirect,
            "the callback is passed as data and never applied in a walked position — root closure is empty",
        )
        assertEquals(
            setOf("Filesystem.Write"), rootLatent,
            "the effectful callback argument surfaces its write in the latent channel",
        )
        assertEquals(
            setOf("Filesystem.Write"),
            categoryNames(p.store, p.verify.totalClosure(p.root)),
            "totalClosure picks up the latent callback effect",
        )
    }

    @Test
    fun `a pure-implementation ToolDef contributes nothing to the latent channel`() {
        // Corpus 67 carries a real Schema-backed ToolDef whose implementation is
        // a pure Lambda (returns a StringLit). The directly-performed closure is
        // {LLM.Generate} (the machine's transition calls the model); the tool
        // contributes no effect, so the latent channel is empty.
        val p = loadResource("/corpus/67-llm-state-machine-with-tool.json")

        val rootLatent = categoryNames(p.store, p.verify.rootLatentClosure(p.root))
        assertEquals(
            emptySet<String>(), rootLatent,
            "a pure ToolDef implementation surfaces no latent effect",
        )
        assertTrue(
            p.verify.latentClosures.values.all { it.isEmpty() },
            "no site records a non-empty latent surface for a pure-tool program",
        )
    }

    @Test
    fun `a benign program surfaces an empty latent channel`() {
        // Corpus 16: Time.Now under a granted capability. No ToolDef, no
        // effectful callback argument — the latent channel is empty and
        // totalClosure equals rootClosure.
        val p = loadResource("/corpus/16-builtin-time-now-under-capability.json")

        assertEquals(
            emptySet<String>(),
            categoryNames(p.store, p.verify.rootLatentClosure(p.root)),
            "a program with no indirect invocation surfaces an empty latent channel",
        )
        assertTrue(
            p.verify.latentClosures.isEmpty(),
            "no latent contribution is recorded anywhere for a benign program",
        )
        assertEquals(
            categoryNames(p.store, p.verify.rootClosure(p.root)),
            categoryNames(p.store, p.verify.totalClosure(p.root)),
            "with an empty latent channel, totalClosure equals rootClosure",
        )
    }
}
