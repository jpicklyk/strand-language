package org.strand.interpreter

/**
 * Credential resolution interface for the agent-native LLM and vector-store
 * builtins (Q-037 / Q-038). Per the agent-native-capabilities proposal § 4.4,
 * credentials are NOT capabilities — a capability `LLM.Generate{provider:
 * "anthropic", model: *}` authorizes calling Anthropic models but does not
 * carry the API key. The API key is configured at the runtime boundary; the
 * key flows in via this interface rather than the content-addressed graph.
 *
 * Default implementation [EnvCredentialProvider] reads from environment
 * variables — the standard pattern across model API client libraries
 * (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GOOGLE_API_KEY` / `GEMINI_API_KEY`).
 *
 * Production deployments wire a custom implementation that reads from a
 * secret manager, an OS keychain, an HSM, or a TEE attestation. Tests
 * install [StaticCredentialProvider] with a fixed map so no real keys are
 * required and the test surface is hermetic.
 *
 * Injection mirrors the `Builtins.clock` / `Builtins.random` /
 * `Builtins.logSink` pattern: a `@Volatile var credentialProvider` on
 * [Builtins] is mutated in test setup and restored in teardown.
 */
interface CredentialProvider {
    /**
     * Resolve an API key for the named provider. Returns null when no key
     * is configured — the calling builtin surfaces this as a structured
     * runtime error (an [IoFailure] with kind `<provider>-credentials-missing`)
     * so the agent's feedback identifies the absent key by name.
     *
     * Known provider strings for Phase 1 of Q-037: `"anthropic"`,
     * `"openai"`, `"gemini"`. Phase 2 / Q-038 add `"voyage"` (for
     * Anthropic-recommended embeddings), `"pinecone"`, `"weaviate"`,
     * `"chroma"`, `"pgvector"`. The provider string is the same one
     * pinned in the effect category's `provider` refinement parameter.
     */
    fun apiKey(provider: String): String?
}

/**
 * Reads credentials from environment variables. The variable name is
 * `<PROVIDER>_API_KEY` uppercased (so `anthropic` → `ANTHROPIC_API_KEY`,
 * `openai` → `OPENAI_API_KEY`, etc.). Gemini accepts either
 * `GEMINI_API_KEY` or `GOOGLE_API_KEY` (the Google AI surface lives on
 * `generativelanguage.googleapis.com` and the canonical Google env-var
 * is `GOOGLE_API_KEY`); we check `GEMINI_API_KEY` first then fall back.
 */
object EnvCredentialProvider : CredentialProvider {
    override fun apiKey(provider: String): String? = when (provider) {
        "anthropic" -> System.getenv("ANTHROPIC_API_KEY")
        "openai" -> System.getenv("OPENAI_API_KEY")
        "gemini", "google" -> System.getenv("GEMINI_API_KEY") ?: System.getenv("GOOGLE_API_KEY")
        else -> System.getenv("${provider.uppercase()}_API_KEY")
    }
}

/**
 * Test-only credential provider holding a fixed map. Installed in
 * `@BeforeAll` / `@BeforeEach`; the default is restored in
 * `@AfterAll` / `@AfterEach`. The map's keys are the same provider
 * strings [EnvCredentialProvider] recognizes.
 */
class StaticCredentialProvider(private val keys: Map<String, String>) : CredentialProvider {
    override fun apiKey(provider: String): String? = keys[provider]
}
