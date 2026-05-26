package org.strand.interpreter

/**
 * Credential resolution interface for the agent-native LLM and vector-store
 * builtins (Q-037 / Q-038). Per the agent-native-capabilities proposal § 4.4
 * and the agent-native-vector-stores proposal § 4.2, credentials are NOT
 * capabilities — a capability `LLM.Generate{provider: "anthropic", model: *}`
 * or `Vector.Write{provider: "pinecone", store: "main"}` authorizes the
 * effect category and refinement parameters; it does not carry the API key.
 * The key is part of the execution environment, not the content-addressed
 * graph — rotating an API key does not change a program's hash, and a
 * program copied between hosts works under whatever credentials the target
 * host has configured.
 *
 * The interface exposes two methods. [resolve] is the general form keyed by
 * `(provider, credentialKey)` to support providers with multiple credentials
 * (OAuth client_id + client_secret, separate region + key, etc.). [apiKey]
 * is the convenience for the common single-key case and delegates to
 * `resolve(provider, "api_key")` by default. Implementations override
 * [resolve]; [apiKey] inherits the default delegation.
 *
 * Default implementation [EnvCredentialProvider] reads from environment
 * variables — the standard pattern across model-API and vector-store client
 * libraries. Tests install [StaticCredentialProvider] (single-key per provider)
 * or [InMemoryCredentialProvider] (general (provider, key) → value map).
 *
 * Injection mirrors the `Builtins.clock` / `Builtins.random` / `Builtins.logSink`
 * pattern: a `@Volatile var credentialProvider` on [Builtins] is mutated in
 * test setup and restored in teardown.
 */
interface CredentialProvider {
    /**
     * Resolve a credential for [provider] keyed by [credentialKey]. The
     * standard credentialKey for single-credential providers is `"api_key"`.
     * Returns `null` when no credential is configured for that pair — the
     * calling builtin surfaces this as a structured runtime error (an
     * [IoFailure] with kind `<provider>-credentials-missing`) so the agent's
     * feedback identifies the absent credential by name.
     *
     * Provider strings recognized in Phase 1: `"anthropic"`, `"openai"`,
     * `"gemini"` / `"google"` (Q-037); `"pinecone"`, `"chroma"` (Q-038).
     * Provider strings are case-sensitive and lowercased by convention.
     * Future providers extend the set; the provider string is the same one
     * pinned in the effect category's `provider` refinement parameter.
     */
    fun resolve(provider: String, credentialKey: String): String?

    /**
     * Convenience for the common single-key case. Delegates to
     * `resolve(provider, "api_key")`. Most callers should use this; the
     * general [resolve] form is for providers requiring multiple credentials.
     */
    fun apiKey(provider: String): String? = resolve(provider, "api_key")
}

/**
 * Reads credentials from environment variables.
 *
 * Naming convention: the env var is `<PROVIDER>_<CREDENTIALKEY>` uppercased
 * (so `anthropic` + `api_key` → `ANTHROPIC_API_KEY`, `pinecone` + `api_key`
 * → `PINECONE_API_KEY`). Gemini accepts either `GEMINI_API_KEY` or
 * `GOOGLE_API_KEY` (the Google AI surface lives on
 * `generativelanguage.googleapis.com` and the canonical Google env-var is
 * `GOOGLE_API_KEY`); we check `GEMINI_API_KEY` first then fall back.
 *
 * Production deployments either rely on this directly (export
 * `ANTHROPIC_API_KEY=...` / `PINECONE_API_KEY=...` before invoking the
 * runtime) or replace it with a secret-manager-aware implementation.
 */
object EnvCredentialProvider : CredentialProvider {
    override fun resolve(provider: String, credentialKey: String): String? {
        // Gemini convention: either GEMINI_API_KEY or GOOGLE_API_KEY.
        if (credentialKey == "api_key" && (provider == "gemini" || provider == "google")) {
            return System.getenv("GEMINI_API_KEY") ?: System.getenv("GOOGLE_API_KEY")
        }
        val envName = provider.uppercase() + "_" + credentialKey.uppercase()
        return System.getenv(envName)
    }
}

/**
 * Test-only credential provider for the common single-key-per-provider case.
 * The map's keys are provider strings (`"anthropic"`, `"openai"`, etc.) and
 * the values are the API keys. Only the `"api_key"` credentialKey is
 * answered; other keys return `null`. Use [InMemoryCredentialProvider] for
 * multi-credential providers.
 *
 * Installed in `@BeforeAll` / `@BeforeEach`; the default is restored in
 * `@AfterAll` / `@AfterEach`.
 */
class StaticCredentialProvider(private val keys: Map<String, String>) : CredentialProvider {
    override fun resolve(provider: String, credentialKey: String): String? =
        if (credentialKey == "api_key") keys[provider] else null
}

/**
 * In-memory credential provider for tests with multi-credential providers
 * or asymmetric credential layouts. Construct with a map of
 * `(provider, credentialKey) -> value` entries. Returns `null` for any
 * pair not in the map.
 */
class InMemoryCredentialProvider(
    private val entries: Map<Pair<String, String>, String>,
) : CredentialProvider {
    override fun resolve(provider: String, credentialKey: String): String? =
        entries[provider to credentialKey]

    companion object {
        /** Build an empty in-memory provider; useful as a deny-all baseline in tests. */
        fun empty(): InMemoryCredentialProvider = InMemoryCredentialProvider(emptyMap())
    }
}
