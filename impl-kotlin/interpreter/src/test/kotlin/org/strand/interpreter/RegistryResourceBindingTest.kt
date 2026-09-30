package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.BuiltinEffectTable
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.ResourceSource
import org.strand.hashing.Hasher
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * The registry binds the refinement of its I/O builtins
 * ([BuiltinEffectTable.resourceProjections], resolved by
 * [RegistryResources]): the value a capability is matched against is the
 * resource the builtin is about to act on, whatever the call site's
 * EffectDecl says. `ForeignEffectTrustTest` covers the filesystem family;
 * these cover the sources that are not a plain argument (the provider a
 * target is bound to, a request's model, a URL's host and port, the store
 * behind a handle) and the effects a higher-order builtin performs itself.
 *
 * Every program here is denied before its builtin runs. The hosts use the
 * reserved `.invalid` TLD and the LLM and vector transports are mocks, so a
 * regression that let one through would still reach no network.
 */
class RegistryResourceBindingTest {

    private data class Loaded(
        val store: NodeStore,
        val root: NodeId,
        val names: Map<String, NodeId>,
        val hashToNodeId: Map<Hash, NodeId>,
    )

    private fun load(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val l = Loaded(finalized.store, finalized.root, ingest.nameMap, finalized.hashToNodeId)
        val verdict = Verifier(l.store, l.hashToNodeId).verify(l.root)
        assertTrue(verdict is VerifyResult.Ok) { "the verifier admits the program: $verdict" }
        return l
    }

    private val savedClient = Builtins.llmHttpClient
    private val savedCredentials = Builtins.credentialProvider
    private val savedTransport = Builtins.vectorHttpTransport
    private lateinit var llm: RecordingHttpClient

    @BeforeEach
    fun setUp() {
        CredentialScrubber.resetForTesting()
        llm = RecordingHttpClient()
        llm.canned = LlmHttpClient.HttpResponse(
            200,
            """{"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn", "usage": {}}""".toByteArray(),
        )
        Builtins.llmHttpClient = llm
        Builtins.credentialProvider = StaticCredentialProvider(mapOf(
            "anthropic" to "sk-anthropic-test",
            "openai" to "sk-openai-test",
        ))
        val transport = InMemoryHttpTransport()
        transport.on({ true }, HttpResponse(200, "{}".toByteArray()))
        Builtins.vectorHttpTransport = transport
    }

    @AfterEach
    fun tearDown() {
        Builtins.llmHttpClient = savedClient
        Builtins.credentialProvider = savedCredentials
        Builtins.vectorHttpTransport = savedTransport
        Builtins.clearTestBuiltins()
        ResourceTable.resetForTest()
        CredentialScrubber.resetForTesting()
    }

    private fun concrete(vararg values: Value) =
        CapabilityPattern(values.map { CapabilityArgument.Concrete(it) })

    private fun str(s: String) = Value.StringV(s)

    private fun denied(l: Loaded, grant: CapabilitySet): InterpretError.RefinementViolation {
        val ex = assertThrows<InterpretException> { Interpreter(l.store, l.hashToNodeId).eval(l.root, grant) }
        return ex.error as? InterpretError.RefinementViolation
            ?: error("expected RefinementViolation, got ${ex.error}")
    }

    // ---- LLM providers: provider from the target, model from the request ----

    /**
     * One call of a provider's generate binding. [declared] is the
     * (provider, model) the call site's EffectDecl claims, or null for a
     * call with no EffectDecl; [model] is what the request actually names.
     */
    private fun llmCall(target: String, model: String, declared: Pair<String, String>?): Loaded {
        val decl = declared?.let { (p, m) ->
            """
              "declP":   { "type": "StringLit", "value": "$p" },
              "declM":   { "type": "StringLit", "value": "$m" },
              "decl":    { "type": "EffectDecl", "effectType": "genFx", "parameters": ["declP", "declM"] },"""
        } ?: ""
        val instances = if (declared != null) """, "effectInstances": ["decl"]""" else ""
        return load("""{
            "version": 1, "root": "app",
            "nodes": {
              "strT":    { "type": "PrimitiveType", "kind": "String" },
              "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
              "genFx":   { "type": "EffectCategory", "categoryName": "LLM.Generate", "parameters": ["strT", "strT"] },
              "modelF":  { "type": "ProductTypeField", "name": "model", "fieldType": "strT" },
              "msgsF":   { "type": "ProductTypeField", "name": "messages", "fieldType": "bytesT" },
              "reqT":    { "type": "ProductType", "fields": ["modelF", "msgsF"] },
              "resT":    { "type": "ProductType", "fields": [] },
              "genT":    { "type": "FunctionType", "parameters": ["reqT"], "result": "resT", "effects": ["genFx"] },
              "gen":     { "type": "ForeignNode", "target": "$target", "foreignType": "genT", "effects": ["genFx"] },$decl
              "model":   { "type": "StringLit", "value": "$model" },
              "modelV":  { "type": "ProductFieldValue", "fieldName": "model", "value": "model" },
              "msgs":    { "type": "BytesLit", "value": "" },
              "msgsV":   { "type": "ProductFieldValue", "fieldName": "messages", "value": "msgs" },
              "req":     { "type": "ProductValue", "ofType": "reqT", "fields": ["modelV", "msgsV"] },
              "app":     { "type": "Application", "function": "gen", "arguments": ["req"]$instances }
            }
          }""")
    }

    @Test
    fun `an EffectDecl cannot present the OpenAI binding as Anthropic`() {
        val l = llmCall("strand-builtin:OpenAI.Chat.Completions", "gpt-5", declared = "anthropic" to "gpt-5")
        val genFx = l.names.getValue("genFx")
        val anthropicOnly = CapabilitySet(mapOf(genFx to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(str("anthropic")), CapabilityArgument.Wildcard)),
        )))
        val err = denied(l, anthropicOnly)
        assertEquals(listOf<Value>(str("openai"), str("gpt-5")), err.requirement)
        assertTrue(llm.calls.isEmpty()) { "no request may leave under a denied capability" }
    }

    @Test
    fun `an EffectDecl cannot present one model as another`() {
        val l = llmCall("strand-builtin:Anthropic.Messages.Create", "large", declared = "anthropic" to "small")
        val genFx = l.names.getValue("genFx")
        val err = denied(l, CapabilitySet(mapOf(genFx to listOf(concrete(str("anthropic"), str("small"))))))
        assertEquals(listOf<Value>(str("anthropic"), str("large")), err.requirement)
        assertTrue(llm.calls.isEmpty())
    }

    @Test
    fun `a provider call with no EffectDecl is matched against the model it requests`() {
        // A higher-order builtin's own effect is performed at its dispatch:
        // omitting the EffectDecl must not make a refined grant cover it.
        val genFxOf = { l: Loaded -> l.names.getValue("genFx") }
        val large = llmCall("strand-builtin:Anthropic.Messages.Create", "large", declared = null)
        val smallOnly = { l: Loaded ->
            CapabilitySet(mapOf(genFxOf(l) to listOf(concrete(str("anthropic"), str("small")))))
        }
        val err = denied(large, smallOnly(large))
        assertEquals(listOf<Value>(str("anthropic"), str("large")), err.requirement)
        assertTrue(llm.calls.isEmpty())

        // The granted model passes the capability check (the request is
        // then rejected for its placeholder message list, which is not a
        // capability matter).
        val small = llmCall("strand-builtin:Anthropic.Messages.Create", "small", declared = null)
        val outcome = runCatching { Interpreter(small.store, small.hashToNodeId).eval(small.root, smallOnly(small)) }
        val error = (outcome.exceptionOrNull() as? InterpretException)?.error
        assertTrue(error !is InterpretError.RefinementViolation && error !is InterpretError.CapabilityViolation) {
            "the granted model must pass the capability check, got $error"
        }
    }

    // ---- HTTP: host and port from the arguments, or parsed from the URL ------

    private val httpPrelude = """
              "strT":    { "type": "PrimitiveType", "kind": "String" },
              "intT":    { "type": "PrimitiveType", "kind": "Int" },
              "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
              "connFx":  { "type": "EffectCategory", "categoryName": "Network.Connect", "parameters": ["strT", "intT"] },
              "sendFx":  { "type": "EffectCategory", "categoryName": "Network.Send" },
              "recvFx":  { "type": "EffectCategory", "categoryName": "Network.Receive" },
              "resT":    { "type": "ProductType", "fields": [] },
              "goodH":   { "type": "StringLit", "value": "good.invalid" },
              "goodP":   { "type": "IntLit", "value": 80 },
              "decl":    { "type": "EffectDecl", "effectType": "connFx", "parameters": ["goodH", "goodP"] },
              "sendD":   { "type": "EffectDecl", "effectType": "sendFx", "parameters": [] },
              "recvD":   { "type": "EffectDecl", "effectType": "recvFx", "parameters": [] },
              "body":    { "type": "BytesLit", "value": "" },
              "get":     { "type": "StringLit", "value": "GET" },"""

    private fun httpGrant(l: Loaded) = CapabilitySet(mapOf(
        l.names.getValue("connFx") to listOf(concrete(str("good.invalid"), Value.IntV(80))),
        l.names.getValue("sendFx") to listOf(CapabilityPattern(emptyList())),
        l.names.getValue("recvFx") to listOf(CapabilityPattern(emptyList())),
    ))

    @Test
    fun `an EffectDecl cannot redirect the component HTTP request`() {
        val l = load("""{
            "version": 1, "root": "app",
            "nodes": {$httpPrelude
              "reqT":    { "type": "FunctionType",
                           "parameters": ["strT", "intT", "strT", "strT", "strT", "bytesT", "bytesT"],
                           "result": "resT", "effects": ["connFx", "sendFx", "recvFx"] },
              "req":     { "type": "ForeignNode", "target": "strand-builtin:Http.Request", "foreignType": "reqT",
                           "effects": ["connFx", "sendFx", "recvFx"] },
              "evilH":   { "type": "StringLit", "value": "evil.invalid" },
              "evilP":   { "type": "IntLit", "value": 8080 },
              "scheme":  { "type": "StringLit", "value": "http" },
              "path":    { "type": "StringLit", "value": "/" },
              "app":     { "type": "Application", "function": "req",
                           "arguments": ["evilH", "evilP", "scheme", "path", "get", "body", "body"],
                           "effectInstances": ["decl", "sendD", "recvD"] }
            }
          }""")
        val err = denied(l, httpGrant(l))
        assertEquals(listOf<Value>(str("evil.invalid"), Value.IntV(8080)), err.requirement)
    }

    @Test
    fun `an EffectDecl cannot redirect the URL-form HTTP request`() {
        val l = load("""{
            "version": 1, "root": "app",
            "nodes": {$httpPrelude
              "reqT":    { "type": "FunctionType", "parameters": ["strT", "strT", "bytesT"],
                           "result": "resT", "effects": ["connFx", "sendFx", "recvFx"] },
              "req":     { "type": "ForeignNode", "target": "strand-builtin:Http.RequestFromUrl",
                           "foreignType": "reqT", "effects": ["connFx", "sendFx", "recvFx"] },
              "url":     { "type": "StringLit", "value": "https://evil.invalid/steal?x=1" },
              "app":     { "type": "Application", "function": "req", "arguments": ["get", "url", "body"],
                           "effectInstances": ["decl", "sendD", "recvD"] }
            }
          }""")
        val err = denied(l, httpGrant(l))
        // Host and port as the builtin itself will dispatch them: the
        // scheme's default port, not a declared one.
        assertEquals(listOf<Value>(str("evil.invalid"), Value.IntV(443)), err.requirement)
    }

    @Test
    fun `URL sources resolve with the builtin's own parser and fail closed to the builtin`() {
        val sources = listOf(ResourceSource.UrlHost(1), ResourceSource.UrlPort(1))
        fun resolve(url: Value) = RegistryResources.resolve(sources, listOf(str("GET"), url, Value.BytesV(ByteArray(0))))
        assertEquals(listOf<Value>(str("a.invalid"), Value.IntV(80)), resolve(str("http://a.invalid/p")))
        assertEquals(listOf<Value>(str("a.invalid"), Value.IntV(443)), resolve(str("https://a.invalid")))
        assertEquals(listOf<Value>(str("a.invalid"), Value.IntV(8443)), resolve(str("https://a.invalid:8443/p?q=1")))
        // The userinfo trick: the host is what follows the '@'.
        assertEquals(listOf<Value>(str("b.invalid"), Value.IntV(80)), resolve(str("http://a.invalid@b.invalid/")))
        // Arguments the builtin rejects before doing anything resolve to nothing.
        assertNull(resolve(str("http://")))
        assertNull(resolve(str("not a url")))
        assertNull(resolve(Value.IntV(1)))
        assertNull(RegistryResources.resolve(sources, emptyList()))
    }

    // ---- Vector stores: provider from the target, store from config or handle

    @Test
    fun `an EffectDecl cannot present one index as another when opening it`() {
        val l = load("""{
            "version": 1, "root": "app",
            "nodes": {
              "strT":    { "type": "PrimitiveType", "kind": "String" },
              "intT":    { "type": "PrimitiveType", "kind": "Int" },
              "readFx":  { "type": "EffectCategory", "categoryName": "Vector.Read", "parameters": ["strT", "strT"] },
              "nameF":   { "type": "ProductTypeField", "name": "indexName", "fieldType": "strT" },
              "cfgT":    { "type": "ProductType", "fields": ["nameF"] },
              "openT":   { "type": "FunctionType", "parameters": ["cfgT"], "result": "intT", "effects": ["readFx"] },
              "open":    { "type": "ForeignNode", "target": "strand-builtin:Pinecone.Index.Open",
                           "foreignType": "openT", "effects": ["readFx"] },
              "prov":    { "type": "StringLit", "value": "pinecone" },
              "public":  { "type": "StringLit", "value": "public" },
              "decl":    { "type": "EffectDecl", "effectType": "readFx", "parameters": ["prov", "public"] },
              "secret":  { "type": "StringLit", "value": "secret" },
              "nameV":   { "type": "ProductFieldValue", "fieldName": "indexName", "value": "secret" },
              "cfg":     { "type": "ProductValue", "ofType": "cfgT", "fields": ["nameV"] },
              "app":     { "type": "Application", "function": "open", "arguments": ["cfg"],
                           "effectInstances": ["decl"] }
            }
          }""")
        val readFx = l.names.getValue("readFx")
        val err = denied(l, CapabilitySet(mapOf(readFx to listOf(concrete(str("pinecone"), str("public"))))))
        assertEquals(listOf<Value>(str("pinecone"), str("secret")), err.requirement)
    }

    @Test
    fun `a handle operation is matched against the store the handle was opened on`() {
        // (handle) -> Query(handle, request), with an EffectDecl claiming
        // the granted store. The handle is a runtime value, so the program
        // is a Lambda applied to a registered handle.
        val l = load("""{
            "version": 1, "root": "lam",
            "nodes": {
              "strT":    { "type": "PrimitiveType", "kind": "String" },
              "intT":    { "type": "PrimitiveType", "kind": "Int" },
              "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
              "readFx":  { "type": "EffectCategory", "categoryName": "Vector.Read", "parameters": ["strT", "strT"] },
              "queryT":  { "type": "FunctionType", "parameters": ["intT", "bytesT"], "result": "bytesT",
                           "effects": ["readFx"] },
              "query":   { "type": "ForeignNode", "target": "strand-builtin:Pinecone.Index.Query",
                           "foreignType": "queryT", "effects": ["readFx"] },
              "h":       { "type": "ParameterDecl", "name": "h", "paramType": "intT" },
              "hRef":    { "type": "VarRef", "binder": "h" },
              "prov":    { "type": "StringLit", "value": "pinecone" },
              "public":  { "type": "StringLit", "value": "public" },
              "decl":    { "type": "EffectDecl", "effectType": "readFx", "parameters": ["prov", "public"] },
              "req":     { "type": "BytesLit", "value": "" },
              "app":     { "type": "Application", "function": "query", "arguments": ["hRef", "req"],
                           "effectInstances": ["decl"] },
              "lam":     { "type": "Lambda", "parameters": ["h"], "body": "app", "effects": ["readFx"] }
            }
          }""")
        val readFx = l.names.getValue("readFx")
        val grant = CapabilitySet(mapOf(readFx to listOf(concrete(str("pinecone"), str("public")))))
        fun handleOn(index: String) = ResourceTable.register("pinecone_index", PineconeIndexHandle(
            PineconeIndexConfig(index, "us-east-1", VectorMetric.Cosine, 3, "http://stub.invalid"),
            Credential("pk-test-1234abcd", "pinecone", "api_key"),
            "http://stub.invalid",
        ))
        val interpreter = Interpreter(l.store, l.hashToNodeId)
        val fn = interpreter.eval(l.root, grant)

        val ex = assertThrows<InterpretException> { interpreter.applyCallable(fn, listOf(handleOn("secret")), grant) }
        val err = ex.error as? InterpretError.RefinementViolation ?: error("expected RefinementViolation, got ${ex.error}")
        assertEquals(listOf<Value>(str("pinecone"), str("secret")), err.requirement)

        // A handle on the granted store passes the capability check.
        val outcome = runCatching { interpreter.applyCallable(fn, listOf(handleOn("public")), grant) }
        val error = (outcome.exceptionOrNull() as? InterpretException)?.error
        assertTrue(error !is InterpretError.RefinementViolation && error !is InterpretError.CapabilityViolation) {
            "the granted store must pass the capability check, got $error"
        }
    }

    // ---- Higher-order builtins: own effects perform, callback rows propagate -

    private val ownEffectTarget = "strand-builtin:Test.OwnEffect"

    /** `Test.OwnEffect(x)` declared with the parameterized category `Audit.Write{String}`. */
    private fun ownEffectCall() = load("""{
        "version": 1, "root": "app",
        "nodes": {
          "strT":    { "type": "PrimitiveType", "kind": "String" },
          "intT":    { "type": "PrimitiveType", "kind": "Int" },
          "auditFx": { "type": "EffectCategory", "categoryName": "Audit.Write", "parameters": ["strT"] },
          "fnT":     { "type": "FunctionType", "parameters": ["intT"], "result": "intT", "effects": ["auditFx"] },
          "fn":      { "type": "ForeignNode", "target": "$ownEffectTarget", "foreignType": "fnT",
                       "effects": ["auditFx"] },
          "one":     { "type": "IntLit", "value": 1 },
          "app":     { "type": "Application", "function": "fn", "arguments": ["one"] }
        }
      }""")

    private fun installOwnEffect(performs: Set<String>, ran: MutableList<Value>) {
        Builtins.installTestHigherOrderBuiltin(
            ownEffectTarget, effectful = true, determinism = Builtins.Determinism.Stateful, performs = performs,
        ) { _, args, _ ->
            ran += args[0]
            args[0]
        }
    }

    @Test
    fun `an effect a higher-order builtin performs itself takes the performing-site rules`() {
        val ran = mutableListOf<Value>()
        installOwnEffect(setOf("Audit.Write"), ran)
        val l = ownEffectCall()
        val auditFx = l.names.getValue("auditFx")

        // Uninstantiated and parameterized: a refined grant does not cover it.
        val refined = CapabilitySet(mapOf(auditFx to listOf(concrete(str("channel-a")))))
        denied(l, refined)
        assertTrue(ran.isEmpty())

        // An unrefined grant does, and the dispatch is audited.
        val sink = CollectingAuditSink()
        val interpreter = Interpreter(
            l.store, l.hashToNodeId, hostContext = HostContext.processDefault().copy(auditSink = sink),
        )
        assertEquals(Value.IntV(1), interpreter.eval(l.root, setOf(auditFx)))
        assertEquals(listOf<Value>(Value.IntV(1)), ran)
        val record = sink.records.single()
        assertEquals(AuditOutcome.Allowed, record.outcome)
        assertEquals("Audit.Write", record.effectCategory)
        assertEquals(l.names.getValue("app"), record.callSiteNodeId)
    }

    @Test
    fun `a category a higher-order builtin only propagates stays a propagating site`() {
        // The same program with the builtin declaring no effect of its own:
        // its row is its callbacks' row, so the refined grant passes here
        // and the callbacks are checked where they run.
        val ran = mutableListOf<Value>()
        installOwnEffect(emptySet(), ran)
        val l = ownEffectCall()
        val auditFx = l.names.getValue("auditFx")
        val refined = CapabilitySet(mapOf(auditFx to listOf(concrete(str("channel-a")))))
        assertEquals(Value.IntV(1), Interpreter(l.store, l.hashToNodeId).eval(l.root, refined))
        assertEquals(listOf<Value>(Value.IntV(1)), ran)
    }

    // ---- The declaration stays a claim ---------------------------------------

    @Test
    fun `a call that declares more than was granted is refused though its resource is granted`() {
        // Registry binding adds a requirement and removes none: the path
        // read is the granted one, the EffectDecl names another, and the
        // grant covers only the first.
        val l = load("""{
            "version": 1, "root": "app",
            "nodes": {
              "strT":    { "type": "PrimitiveType", "kind": "String" },
              "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
              "readFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Read", "parameters": ["strT"] },
              "readT":   { "type": "FunctionType", "parameters": ["strT"], "result": "bytesT" },
              "fsRead":  { "type": "ForeignNode", "target": "strand-builtin:Fs.Read", "foreignType": "readT",
                           "effects": ["readFx"] },
              "declared": { "type": "StringLit", "value": "/etc/shadow" },
              "actual":  { "type": "StringLit", "value": "strand-registry-binding-granted.txt" },
              "decl":    { "type": "EffectDecl", "effectType": "readFx", "parameters": ["declared"] },
              "app":     { "type": "Application", "function": "fsRead", "arguments": ["actual"],
                           "effectInstances": ["decl"] }
            }
          }""")
        val readFx = l.names.getValue("readFx")
        val grant = CapabilitySet(mapOf(readFx to listOf(concrete(str("strand-registry-binding-granted.txt")))))
        val err = denied(l, grant)
        assertEquals(listOf<Value>(str("/etc/shadow")), err.requirement)

        // Granting both the resource and the claim admits the call; the
        // file does not exist, so what follows is an I/O failure.
        val both = CapabilitySet(mapOf(readFx to listOf(
            concrete(str("strand-registry-binding-granted.txt")), concrete(str("/etc/shadow")),
        )))
        val ex = assertThrows<InterpretException> { Interpreter(l.store, l.hashToNodeId).eval(l.root, both) }
        assertTrue(ex.error is InterpretError.IoFailure || ex.error is InterpretError.SandboxViolation) {
            "expected the read itself to fail, got ${ex.error}"
        }
    }

    // ---- The table itself ---------------------------------------------------

    @Test
    fun `every projected target is registered and projects only its own floor`() {
        for ((target, projection) in BuiltinEffectTable.resourceProjections) {
            assertTrue(target in Builtins.registeredTargets()) { "$target is not a registered builtin" }
            val floor = BuiltinEffectTable.requiredCategories(target)
            assertTrue(floor != null && floor.containsAll(projection.keys)) {
                "$target projects ${projection.keys}, outside its floor $floor"
            }
        }
    }
}
