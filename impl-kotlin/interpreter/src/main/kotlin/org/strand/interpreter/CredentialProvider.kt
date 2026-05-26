package org.strand.interpreter

/**
 * Credential resolution for external providers (Q-037 / Q-038).
 *
 * Credentials are deliberately not capabilities: a capability
 * `Vector.Write{provider: "pinecone", store: "main"}` authorizes calling
 * a Pinecone index; it does not carry the API key. Keys are part of the
 * execution environment, not the content-addressed graph — rotating an
 * API key does not change a program's hash, and a program copied
 * between hosts works under whatever credentials the target host has
 * configured.
 *
 * The default implementation reads from environment variables. Tests
 * install [InMemoryCredentialProvider] (or a custom implementation) by
 * replacing [Builtins.credentialProvider] in setup and restoring in
 * teardown, mirroring the [Builtins.clock] injection pattern.
 *
 * Recognized keys per provider:
 *
 *   pinecone: "api_key" (PINECONE_API_KEY)
 *   chroma:   "api_key" (CHROMA_API_KEY, optional for local servers)
 *
 * Returns `null` when a key is absent; provider implementations decide
 * whether absence is an error (Pinecone requires a key) or acceptable
 * (Chroma against an unauthenticated local server is fine).
 */
interface CredentialProvider {
    /**
     * Resolve a credential for [provider] keyed by [credentialKey].
     * Returns `null` when no credential is configured for that pair.
     */
    fun resolve(provider: String, credentialKey: String): String?
}

/**
 * Environment-variable-backed credential provider. Reads from the
 * standard per-provider env vars. Production deployments either rely
 * on this directly (export PINECONE_API_KEY=... before invoking the
 * runtime) or replace it with a secret-manager-aware implementation.
 *
 * Env-var naming convention: uppercase `<PROVIDER>_<KEY>`. The
 * `credentialKey` argument is expected to be lowercase (`"api_key"`)
 * and is upcased before lookup. Provider strings are likewise
 * lowercased on registration in the table so case sensitivity does
 * not surface at the call site.
 */
object EnvCredentialProvider : CredentialProvider {
    override fun resolve(provider: String, credentialKey: String): String? {
        val envName = provider.uppercase() + "_" + credentialKey.uppercase()
        return System.getenv(envName)
    }
}

/**
 * In-memory credential provider for tests. Construct with a map of
 * `(provider, key) -> value` entries. Returns `null` for any pair not
 * in the map.
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
