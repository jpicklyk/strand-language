package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher

/**
 * Q-037 Phase 1 — provider-scoped capability tests. Verifies the
 * operation-shaped E-035 LLM.Generate{provider, model} effect is
 * refined by the per-provider ForeignNode's pinned `provider`
 * parameter string. A capability that authorizes only Anthropic
 * cannot dispatch through the OpenAI ForeignNode and vice versa.
 *
 * The test installs a mock HTTP client so no real network call is
 * issued — the test is purely about the refinement-lattice check
 * that fires *before* the builtin runs.
 */
class ProviderRefinementTest {

    private val savedClient = Builtins.llmHttpClient
    private val savedCredentials = Builtins.credentialProvider
    private lateinit var captured: RecordingHttpClient

    @BeforeEach
    fun setUp() {
        captured = RecordingHttpClient()
        captured.canned = LlmHttpClient.HttpResponse(
            200,
            """{"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn", "usage": {}}""".toByteArray(),
        )
        Builtins.llmHttpClient = captured
        Builtins.credentialProvider = StaticCredentialProvider(mapOf(
            "anthropic" to "sk-anthropic-test",
            "openai" to "sk-openai-test",
        ))
    }

    @AfterEach
    fun tearDown() {
        Builtins.llmHttpClient = savedClient
        Builtins.credentialProvider = savedCredentials
    }

    private data class Loaded(
        val store: NodeStore,
        val root: NodeId,
        val names: Map<String, NodeId>,
        val hashToNodeId: Map<Hash, NodeId>,
    )

    private fun ingest(json: String): Loaded {
        val parsed = JsonIngest.parse(json)
        val finalized = Hasher(parsed.rawStore).finalize(parsed.root)
        return Loaded(finalized.store, finalized.root, parsed.nameMap, finalized.hashToNodeId)
    }

    /**
     * Build a Strand graph that calls one provider's Generate ForeignNode
     * with a literal model string and an empty messages list. The call site
     * supplies an EffectDecl pinning the LLM.Generate `provider` parameter
     * to the given provider string.
     *
     * Note this graph uses the simpler placeholder ProductV-as-bytes
     * shape — the request product is built directly here (no full
     * GenerateRequest typing) since the refinement check is independent
     * of the request structure.
     */
    private fun buildLlmCall(
        targetBuiltin: String,
        providerString: String,
        modelString: String,
    ): Loaded = ingest("""{
        "version": 1, "root": "callApp",
        "nodes": {
          "intT":         { "type": "PrimitiveType", "kind": "Int" },
          "strT":         { "type": "PrimitiveType", "kind": "String" },
          "bytesT":       { "type": "PrimitiveType", "kind": "Bytes" },
          "llmGenFx":     { "type": "EffectCategory", "categoryName": "LLM.Generate",
                            "parameters": ["strT", "strT"] },
          "modelFieldT":  { "type": "ProductTypeField", "name": "model", "fieldType": "strT" },
          "messagesFldT": { "type": "ProductTypeField", "name": "messages", "fieldType": "bytesT" },
          "toolsFldT":    { "type": "ProductTypeField", "name": "tools", "fieldType": "bytesT" },
          "reqT":         { "type": "ProductType", "fields": ["modelFieldT", "messagesFldT", "toolsFldT"] },
          "resT":         { "type": "ProductType", "fields": [] },
          "llmGenT":      { "type": "FunctionType", "parameters": ["reqT"], "result": "resT",
                            "effects": ["llmGenFx"] },
          "llmGenFn":     { "type": "ForeignNode", "target": "$targetBuiltin",
                            "foreignType": "llmGenT", "effects": ["llmGenFx"] },
          "providerLit":  { "type": "StringLit", "value": "$providerString" },
          "modelLit":     { "type": "StringLit", "value": "$modelString" },
          "callDecl":     { "type": "EffectDecl", "effectType": "llmGenFx",
                            "parameters": ["providerLit", "modelLit"] },
          "modelLitR":    { "type": "StringLit", "value": "$modelString" },
          "modelFV":      { "type": "ProductFieldValue", "fieldName": "model", "value": "modelLitR" },
          "emptyList":    { "type": "BytesLit", "value": "" },
          "messagesFV":   { "type": "ProductFieldValue", "fieldName": "messages", "value": "emptyList" },
          "toolsFV":      { "type": "ProductFieldValue", "fieldName": "tools", "value": "emptyList" },
          "reqV":         { "type": "ProductValue", "ofType": "reqT",
                            "fields": ["modelFV", "messagesFV", "toolsFV"] },
          "callApp":      { "type": "Application", "function": "llmGenFn",
                            "arguments": ["reqV"], "effectInstances": ["callDecl"] }
        }
    }""")

    @Test
    fun `Anthropic builtin under wildcard provider capability proceeds (refinement covers)`() {
        // Capability: LLM.Generate{provider: *, model: *} — covers any provider.
        // The builtin requires a real ProductV-shaped GenerateRequest at
        // runtime, so we expect either a successful dispatch (if the
        // request shape is parsed correctly) OR an IoFailure related to
        // shape parsing — but NOT a CapabilityViolation /
        // RefinementViolation. This test asserts the refinement check
        // does NOT fire; the structural shape is secondary.
        val (store, root, names, hashToNodeId) = buildLlmCall(
            "strand-builtin:Anthropic.Messages.Create", "anthropic", "claude-opus-4-7",
        )
        val llmGen = names.getValue("llmGenFx")
        val granted = CapabilitySet(mapOf(llmGen to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Wildcard, CapabilityArgument.Wildcard))
        )))
        // The graph's reqV is a ProductV with bytesT fields; the builtin
        // expects a true Strand-shape GenerateRequest. The call will
        // either succeed (if the parse is tolerant) or surface an
        // IoFailure / type cast — neither indicates a refinement
        // problem, and that's the point.
        try {
            Interpreter(store, hashToNodeId).eval(root, granted)
        } catch (ex: InterpretException) {
            // Anything except a capability / refinement violation
            // proves the capability check passed before the call.
            assertTrue(ex.error !is InterpretError.CapabilityViolation,
                "should not be CapabilityViolation: ${ex.error}")
            assertTrue(ex.error !is InterpretError.RefinementViolation,
                "should not be RefinementViolation: ${ex.error}")
        } catch (_: Throwable) {
            // Same point — anything from the builtin's parse layer is fine
            // for this test; the refinement check ran and passed.
        }
    }

    @Test
    fun `Anthropic builtin under anthropic-pinned capability is allowed`() {
        val (store, root, names, hashToNodeId) = buildLlmCall(
            "strand-builtin:Anthropic.Messages.Create", "anthropic", "claude-opus-4-7",
        )
        val llmGen = names.getValue("llmGenFx")
        val granted = CapabilitySet(mapOf(llmGen to listOf(
            CapabilityPattern(listOf(
                CapabilityArgument.Concrete(Value.StringV("anthropic")),
                CapabilityArgument.Wildcard,
            ))
        )))
        try {
            Interpreter(store, hashToNodeId).eval(root, granted)
        } catch (ex: InterpretException) {
            assertTrue(ex.error !is InterpretError.CapabilityViolation)
            assertTrue(ex.error !is InterpretError.RefinementViolation,
                "anthropic call under anthropic-pinned capability should not refinement-fail: ${ex.error}")
        } catch (_: Throwable) {}
    }

    @Test
    fun `OpenAI builtin under anthropic-only capability raises RefinementViolation`() {
        // The graph calls OpenAI.Chat.Completions but the EffectDecl
        // pins provider="openai" at the call site. The capability
        // grants only provider="anthropic" — the runtime must reject
        // with RefinementViolation (NOT CapabilityViolation, because
        // the category IS present in the context).
        val (store, root, names, hashToNodeId) = buildLlmCall(
            "strand-builtin:OpenAI.Chat.Completions", "openai", "gpt-5",
        )
        val llmGen = names.getValue("llmGenFx")
        val granted = CapabilitySet(mapOf(llmGen to listOf(
            CapabilityPattern(listOf(
                CapabilityArgument.Concrete(Value.StringV("anthropic")),
                CapabilityArgument.Wildcard,
            ))
        )))
        val ex = assertThrows<InterpretException> {
            Interpreter(store, hashToNodeId).eval(root, granted)
        }
        val err = ex.error
        assertTrue(err is InterpretError.RefinementViolation,
            "expected RefinementViolation, got ${err::class.simpleName}: $err")
        val rv = err as InterpretError.RefinementViolation
        assertEquals(llmGen, rv.category)
        assertEquals(Value.StringV("openai"), rv.requirement[0])
        assertEquals(Value.StringV("gpt-5"), rv.requirement[1])
    }

    @Test
    fun `OpenAI builtin under no LLM_Generate capability raises CapabilityViolation`() {
        // Empty capability set — even the category is missing. The
        // runtime should raise CapabilityViolation, distinct from
        // RefinementViolation (the policy author granted nothing for
        // this category at all).
        val (store, root, names, hashToNodeId) = buildLlmCall(
            "strand-builtin:OpenAI.Chat.Completions", "openai", "gpt-5",
        )
        val ex = assertThrows<InterpretException> {
            Interpreter(store, hashToNodeId).eval(root, CapabilitySet.EMPTY)
        }
        assertTrue(ex.error is InterpretError.CapabilityViolation,
            "expected CapabilityViolation, got ${ex.error::class.simpleName}: ${ex.error}")
    }
}
