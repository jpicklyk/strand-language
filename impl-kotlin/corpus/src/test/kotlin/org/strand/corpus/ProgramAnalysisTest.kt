package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher
import org.strand.verifier.CapabilityRequirement
import org.strand.verifier.ProgramAnalysis
import org.strand.verifier.RefinementRequirement
import org.strand.verifier.RefinementValue
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * Q-072 (ADR-010): the machine-facing reasoning surface [ProgramAnalysis] over
 * the verified artifact. Companion to [SurfacedEffectClosureTest] (Q-067) and
 * [LatentEffectClosureTest] (Q-070/Q-071): those pin what the verifier
 * *surfaces*; this pins the typed *queries* a machine consumer runs against it.
 *
 * The effectful-ToolDef and higher-order-callback programs are built inline
 * (verify-only fixtures) so no file enters the golden-hash corpus; the
 * clean-room, harm-bound, and reachability cases reuse committed corpus
 * programs.
 */
class ProgramAnalysisTest {

    private data class Loaded(
        val store: NodeStore,
        val root: NodeId,
        val verify: VerifyResult.Ok,
        val nameToId: Map<String, NodeId>,
    ) {
        fun analysis() = ProgramAnalysis(store, verify, root)
    }

    private fun ingest(text: String): Loaded {
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok, "verifier failed: $verify")
        verify as VerifyResult.Ok
        return Loaded(finalized.store, finalized.root, verify, ingest.nameMap)
    }

    private fun loadResource(resource: String): Loaded {
        val text = ProgramAnalysisTest::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.readText()
            ?: error("missing resource $resource")
        return ingest(text)
    }

    private fun categoryNames(store: NodeStore, ids: Set<NodeId>): Set<String> =
        ids.mapNotNull { (store.getOrNull(it) as? Node.EffectCategory)?.categoryName }
            .toSortedSet()

    /** The NodeId of the (unique) EffectCategory with [name] in [store], or null. */
    private fun categoryId(store: NodeStore, name: String): NodeId? {
        for (id in store.ids()) {
            val n = store.getOrNull(id)
            if (n is Node.EffectCategory && n.categoryName == name) return id
        }
        return null
    }

    // ------------------------------------------------------------------
    // Fixtures (shared shapes with LatentEffectClosureTest).
    // ------------------------------------------------------------------

    /** Generate call handed a Filesystem.Write ToolDef (root {LLM.Generate}, latent {Filesystem.Write}). */
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

    /** Higher-order function applied to an effectful Filesystem.Write callback (root {}, latent {Filesystem.Write}). */
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

    // ------------------------------------------------------------------
    // effectClosure vs latentEffectClosure vs totalClosure.
    // ------------------------------------------------------------------

    @Test
    fun `effectClosure latentEffectClosure and totalClosure separate direct from indirect reach`() {
        val p = ingest(effectfulToolDefProgram)
        val a = p.analysis()

        assertEquals(
            setOf("LLM.Generate"), categoryNames(p.store, a.effectClosure()),
            "the Generate call is directly performed",
        )
        assertEquals(
            setOf("Filesystem.Write"), categoryNames(p.store, a.latentEffectClosure()),
            "the tool implementation's write is reachable only indirectly",
        )
        assertEquals(
            setOf("LLM.Generate", "Filesystem.Write"), categoryNames(p.store, a.totalClosure()),
            "totalClosure = effectClosure ∪ latentEffectClosure",
        )
        // Keying by an explicit subgraph node defaults to root's answer here.
        assertEquals(a.effectClosure(), a.effectClosure(p.root))
    }

    // ------------------------------------------------------------------
    // egressSet: clean-room shape.
    // ------------------------------------------------------------------

    @Test
    fun `egressSet is empty for a filesystem-only program against a network watched set and non-empty for a network program`() {
        // Corpus 16: Time.Now under a granted capability — reaches no egress.
        val readOnly = loadResource("/corpus/16-builtin-time-now-under-capability.json")
        val readAnalysis = readOnly.analysis()

        // A watched egress set naming network categories, resolved by name if present.
        // Corpus 16 reaches only Time.Now, so its egress against any network set is empty.
        val watchedNamesNet = setOf("Network.Connect", "Network.Send", "Network.Receive")
        val watchedInStore = watchedNamesNet.mapNotNull { categoryId(readOnly.store, it) }.toSet()
        assertEquals(
            emptySet<NodeId>(), readAnalysis.egressSet(watchedInStore),
            "a program that reaches no watched category has empty egress",
        )
        // Even watching Time.Now would show up — sanity that egressSet actually intersects.
        val timeNow = categoryId(readOnly.store, "Time.Now")!!
        assertEquals(
            setOf(timeNow), readAnalysis.egressSet(setOf(timeNow)),
            "egressSet picks out a watched category that IS in the closure",
        )

        // The effectful-ToolDef program's total closure includes an egress-ish
        // category (LLM.Generate); watch it and confirm egressSet is non-empty.
        val net = ingest(effectfulToolDefProgram)
        val netAnalysis = net.analysis()
        val gen = categoryId(net.store, "LLM.Generate")!!
        assertEquals(
            setOf(gen), netAnalysis.egressSet(setOf(gen)),
            "a program reaching a watched category has non-empty egress",
        )
    }

    // ------------------------------------------------------------------
    // harmBound: narrowing under a partial grant.
    // ------------------------------------------------------------------

    @Test
    fun `harmBound narrows the total closure to what a partial grant admits`() {
        val p = ingest(effectfulToolDefProgram)
        val a = p.analysis()
        val gen = categoryId(p.store, "LLM.Generate")!!
        val write = categoryId(p.store, "Filesystem.Write")!!

        // Grant only LLM.Generate: harm bound is {LLM.Generate}; the latent
        // Filesystem.Write is beyond the grant and drops out.
        assertEquals(
            setOf(gen), a.harmBound(setOf(gen)),
            "under a {LLM.Generate}-only grant, only LLM.Generate survives",
        )
        // Grant both: harm bound is the whole total closure.
        assertEquals(
            setOf(gen, write), a.harmBound(setOf(gen, write)),
            "granting both categories admits the whole total closure",
        )
        // Grant a category the program never reaches: nothing survives.
        assertEquals(
            emptySet<NodeId>(), a.harmBound(setOf(NodeId(-99))),
            "granting an unrelated category admits nothing",
        )
    }

    // ------------------------------------------------------------------
    // reachesEffect true/false.
    // ------------------------------------------------------------------

    @Test
    fun `reachesEffect reports category reachability across the direct and latent channels`() {
        val p = ingest(effectfulToolDefProgram)
        val a = p.analysis()
        val gen = categoryId(p.store, "LLM.Generate")!!
        val write = categoryId(p.store, "Filesystem.Write")!!

        assertTrue(a.reachesEffect(gen), "LLM.Generate is directly performed — reachable")
        assertTrue(a.reachesEffect(write), "Filesystem.Write is latent — still reachable")
        assertFalse(a.reachesEffect(NodeId(-99)), "an unrelated category is not reachable")
    }

    // ------------------------------------------------------------------
    // capabilityRequirement: refined vs wildcard.
    // ------------------------------------------------------------------

    @Test
    fun `capabilityRequirement pins refinement literals at reachable EffectDecls and wildcards the rest`() {
        val p = ingest(effectfulToolDefProgram)
        val a = p.analysis()
        val req: CapabilityRequirement = a.capabilityRequirement()
        val gen = categoryId(p.store, "LLM.Generate")!!
        val write = categoryId(p.store, "Filesystem.Write")!!

        assertEquals(
            setOf(gen, write), req.categories,
            "both direct and latent categories are requirements",
        )

        // LLM.Generate is reached at an Application whose EffectDecl pins two
        // string literals — a concrete Refined requirement.
        val genReq = req.perCategory[gen]
        assertTrue(genReq is RefinementRequirement.Refined, "LLM.Generate carries a pinned refinement")
        genReq as RefinementRequirement.Refined
        assertEquals(
            listOf(listOf(
                RefinementValue.StringValue("anthropic"),
                RefinementValue.StringValue("claude"),
            )),
            genReq.patterns,
            "the EffectDecl's two string literals are the pinned refinement pattern",
        )

        // Filesystem.Write is latent-only (no walked Application) — Wildcard.
        assertEquals(
            RefinementRequirement.Wildcard, req.perCategory[write],
            "a latent-only category has no call site to pin, so it is a wildcard",
        )
    }

    @Test
    fun `capabilityRequirement wildcards a refinement-free call site`() {
        // Corpus 16: Time.Now under a granted capability, called with no
        // refinement EffectDecl parameters — so the requirement is a wildcard.
        val p = loadResource("/corpus/16-builtin-time-now-under-capability.json")
        val a = p.analysis()
        val req = a.capabilityRequirement()
        val timeNow = categoryId(p.store, "Time.Now")!!

        assertEquals(setOf(timeNow), req.categories, "Time.Now is the sole requirement")
        // Time.Now takes no parameters; its EffectDecl (if any) pins nothing,
        // so the requirement is Wildcard (an empty pattern list would also be
        // acceptable, but a zero-arity category yields no concrete pattern).
        val tnReq = req.perCategory[timeNow]
        assertTrue(
            tnReq == RefinementRequirement.Wildcard ||
                (tnReq is RefinementRequirement.Refined && tnReq.patterns.all { it.isEmpty() }),
            "a refinement-free / zero-arity category is a wildcard (or an empty pattern)",
        )
    }

    // ------------------------------------------------------------------
    // capabilityDiff between two programs.
    // ------------------------------------------------------------------

    @Test
    fun `capabilityDiff reports the new authority one program requires over another`() {
        // The effectful-ToolDef program requires {LLM.Generate, Filesystem.Write}
        // (direct + latent). The benign Time.Now program requires {Time.Now}.
        val richer = ingest(effectfulToolDefProgram).analysis()
        val benign = loadResource("/corpus/16-builtin-time-now-under-capability.json").analysis()

        // richer requires LLM.Generate + Filesystem.Write that benign does not.
        val diff = richer.capabilityDiff(benign)
        assertEquals(
            setOf("LLM.Generate", "Filesystem.Write"),
            categoryNames(ingest(effectfulToolDefProgram).store, diff),
            "the diff is every category the richer program requires that the benign one does not",
        )

        // The reverse diff surfaces Time.Now, which the richer program never reaches.
        val reverse = benign.capabilityDiff(richer)
        assertEquals(1, reverse.size, "the benign program requires exactly one category the richer lacks")
    }

    // ------------------------------------------------------------------
    // A higher-order callback program: latent-only requirement.
    // ------------------------------------------------------------------

    @Test
    fun `a higher-order callback program requires its latent effect as a wildcard`() {
        val p = ingest(higherOrderCallbackProgram)
        val a = p.analysis()
        val write = categoryId(p.store, "Filesystem.Write")!!

        assertEquals(emptySet<NodeId>(), a.effectClosure(), "no directly-performed effect")
        assertEquals(setOf(write), a.latentEffectClosure(), "the callback's write is latent")
        val req = a.capabilityRequirement()
        assertEquals(setOf(write), req.categories, "the latent write is still a requirement")
        assertEquals(
            RefinementRequirement.Wildcard, req.perCategory[write],
            "a latent callback has no walked call site to pin its refinement",
        )
    }
}
