package org.strand.interpreter

/**
 * Q-042: process-global registry that records every credential value
 * issued by every [CredentialProvider] implementation during a runtime's
 * lifetime, and a [scrub] entry point that replaces literal occurrences
 * of any registered value in arbitrary text with a redacted placeholder.
 *
 * The scrubber is the runtime safety net the [Credential] wrapper does
 * not provide on its own. The wrapper catches direct logging /
 * string-interpolation accidents at the type level; the scrubber catches
 * the second-order leakage paths the wrapper cannot see:
 *
 *  - upstream HTTP error bodies that echo the API key back in their
 *    error payload (a misconfigured Anthropic proxy returning
 *    `{"error":"invalid api key: sk-ant-..."}`),
 *  - JVM `IOException.message` strings that interpolate a URL of the
 *    form `https://user:secret@host/path` into the exception text,
 *  - third-party library `toString` calls that embed unwrapped values
 *    in their string output before the value reaches the runtime
 *    boundary.
 *
 * State is process-global, matching the existing `Builtins.credentialProvider`
 * / `Builtins.clock` / `Builtins.random` pattern (see § 4.3 D3 of
 * `proposals/implemented/credential-isolation.md`). The same test-isolation
 * caveat applies: tests that mutate the scrubber's registry must not run
 * in parallel; the [resetForTesting] entry point restores the empty
 * registry in `@AfterEach` blocks.
 *
 * The registry is `@Volatile Map<String, String>` mutated by copy-on-write
 * — every [register] call replaces the entire map. This keeps the [scrub]
 * read path lock-free without synchronization machinery for the few-keys-
 * per-deployment scale the proposal expects. A future
 * hundreds-of-tenant-credentials deployment would replace the linear scan
 * with an Aho-Corasick automaton (§ 8 of the proposal).
 *
 * Memory: registered credentials are retained for the lifetime of the
 * runtime. Heap-clearing on rotation is deferred (§ 8 D5). On the JVM,
 * `String` interning means the original credential bytes are already
 * unzeroable in practice.
 */
object CredentialScrubber {

    @Volatile
    private var registry: Map<String, String> = emptyMap()

    /**
     * Register [credential] for redaction. Called automatically by
     * every default [CredentialProvider] implementation before
     * returning a freshly-minted [Credential] to the caller. Custom
     * host implementations adopt the same discipline — failing to
     * register means the scrubber will not see those credentials at
     * IoFailure-construction time.
     *
     * Skips registration when the credential value is blank or shorter
     * than 8 characters. The short-value guard avoids accidental over-
     * redaction of common substrings (`"test"`, `"demo"`, four-byte
     * placeholders in test fixtures) in error messages that legitimately
     * contain those literals. Eight characters is below the entropy
     * floor of any plausible real credential — sub-eight values landing
     * in this code path are test placeholders, not production secrets.
     *
     * The placeholder is the same `[REDACTED:<provider>:<credentialKey>]`
     * shape [Credential.toString] uses, so a scrubbed location reads
     * identically to a directly-logged Credential.
     */
    internal fun register(credential: Credential) {
        val raw = credential.reveal()
        if (raw.isBlank() || raw.length < 8) return
        val placeholder = "[REDACTED:${credential.provider}:${credential.credentialKey}]"
        registry = registry + (raw to placeholder)
    }

    /**
     * Replace every literal occurrence of any registered credential
     * value in [text] with its placeholder. Returns [text] unchanged
     * when the registry is empty (fast path for the common case where
     * an evaluation does not touch credentials).
     *
     * Linear in `text.length × registry.size`; the registry is small
     * (one entry per provider in practice; ≤ 10 in the maximum-tenant
     * deployments envisioned for V1). Each iteration of the inner loop
     * runs an `in` check first (rejecting unrelated text in O(n) without
     * allocating a result) and only allocates a new string when an
     * actual replacement is needed.
     */
    fun scrub(text: String): String {
        if (registry.isEmpty()) return text
        var result = text
        for ((raw, placeholder) in registry) {
            if (raw in result) result = result.replace(raw, placeholder)
        }
        return result
    }

    /**
     * Test-isolation entry point. Clears the registry so a subsequent
     * test starts with an empty scrubber. Called from `@AfterEach`
     * blocks alongside the existing `Builtins.credentialProvider =
     * EnvCredentialProvider` reset.
     *
     * Internal: production code never needs to clear the registry;
     * credentials live for the lifetime of the process.
     */
    internal fun resetForTesting() {
        registry = emptyMap()
    }
}
