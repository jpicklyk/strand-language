package org.strand.interpreter

/**
 * Q-042: opaque wrapper around a credential string. Returned by
 * [CredentialProvider.resolve] and [CredentialProvider.apiKey] in place
 * of the bare `String?` that previously flowed through the per-provider
 * implementations.
 *
 * The wrapper enforces three discipline rules at the type system level:
 *
 *  1. [toString] is always `[REDACTED:<provider>:<credentialKey>]`. The
 *     underlying value is unreachable through string interpolation,
 *     concatenation, logging, exception messages, or any code path that
 *     calls `toString()` implicitly. Mirrors Rust's `secrecy::Secret<T>`.
 *  2. [reveal] is the single explicit method that returns the raw value.
 *     Every call site is auditable in `git grep -n '\.reveal()'` — by
 *     convention there is exactly one per provider (the HTTP header
 *     builder). Each site documents *why* the value is being revealed.
 *  3. [equals] and [hashCode] use reference identity. Two [Credential]
 *     instances minted from the same raw String are unequal. This
 *     prevents value-comparing credentials in maps, sets, or `==`
 *     checks — patterns that suggest the code is treating credentials
 *     as opaque tokens but in practice leak their concrete values into
 *     comparison logic.
 *
 * The constructor is `internal` so only `:interpreter` code can mint a
 * Credential. Tests construct via the public [StaticCredentialProvider]
 * / [InMemoryCredentialProvider] / [EnvCredentialProvider] entry points,
 * which call the internal constructor and register the value with the
 * [CredentialScrubber] before returning.
 *
 * @property provider the provider string the credential is bound to —
 *   e.g., `"anthropic"`, `"openai"`, `"gemini"`, `"pinecone"`, `"chroma"`.
 *   Used in the redacted toString placeholder.
 * @property credentialKey the credential-key string the credential is
 *   bound to — `"api_key"` for single-key providers, more specific
 *   names for multi-credential providers. Used in the redacted
 *   toString placeholder.
 */
class Credential internal constructor(
    private val value: String,
    val provider: String,
    val credentialKey: String,
) {
    /**
     * Return the underlying credential string. Every call site of this
     * method is a deliberate "I am about to use this secret" intent —
     * typically an HTTP header builder or an auth-handshake protocol.
     * Audit with `git grep -n '\.reveal()'`. By convention every call
     * site adjacent comment explains the use of the raw value so a
     * security reviewer can verify the leak surface.
     */
    fun reveal(): String = value

    /**
     * Always redacted. Never returns the underlying value. Format:
     * `[REDACTED:<provider>:<credentialKey>]`. Matches the placeholder
     * used by [CredentialScrubber] so a logged credential string and a
     * scrubbed credential value share a single visible form.
     */
    override fun toString(): String = "[REDACTED:$provider:$credentialKey]"

    /**
     * Reference identity. Value-comparing two credentials is suspicious
     * — see § 4.1 of `proposals/implemented/credential-isolation.md`.
     * Disabling structural equality also blocks the `Set<Credential>`,
     * `Map<Credential, _>`, and `credentials.contains(suspect)` patterns
     * that lookup-by-value the raw string under the hood.
     */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
