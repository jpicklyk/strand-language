package org.strand.interpreter

/**
 * Q-042 / Q-054: a credential-redaction registry. Records every credential
 * value issued by a [CredentialProvider] during a run, and [scrub] replaces
 * literal occurrences of any registered value in arbitrary text with a
 * redacted placeholder.
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
 * **Q-054 (per-context isolation).** Each [HostContext] carries its own
 * [Scrubber] instance, so a multi-tenant host running two programs
 * concurrently never lets tenant A's credential register in tenant B's
 * error-text scrubbing. A credential resolved through a tenant's
 * [CredentialProvider] is registered into THAT tenant's scrubber by the
 * builtin lambda, and `IoFailure`/`SandboxViolation` detail is scrubbed at
 * the interpreter's error-translation point using the active context's
 * scrubber. The process-global [CredentialScrubber] object remains as the
 * single-tenant default (used by the process-default [HostContext] and by
 * the default-parameter provider/transport paths) so existing call sites
 * that do not thread a context keep working.
 *
 * The registry is `@Volatile Map<String, String>` mutated by copy-on-write
 * — every [register] call replaces the entire map. This keeps the [scrub]
 * read path lock-free without synchronization machinery for the few-keys-
 * per-deployment scale the proposal expects. A future
 * hundreds-of-tenant-credentials deployment would replace the linear scan
 * with an Aho-Corasick automaton (§ 8 of the proposal).
 *
 * Memory: registered credentials are retained for the lifetime of the
 * scrubber instance. Heap-clearing on rotation is deferred (§ 8 D5). On the
 * JVM, `String` interning means the original credential bytes are already
 * unzeroable in practice.
 */
class Scrubber {

    @Volatile
    private var registry: Map<String, String> = emptyMap()

    /**
     * Register [credential] for redaction. Called by the builtin lambdas
     * (which carry the active [HostContext]) after resolving a credential
     * through the context's [CredentialProvider], and by the default
     * [CredentialProvider] implementations into the process-global default
     * scrubber.
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
    fun register(credential: Credential) {
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
     * test starts with an empty scrubber.
     */
    internal fun resetForTesting() {
        registry = emptyMap()
    }
}

/**
 * The process-global default [Scrubber]. Used by the process-default
 * [HostContext] (so single-tenant CLI / library / pre-Q-054-test paths see
 * unchanged behaviour) and by the default-parameter provider / transport
 * paths that have no [HostContext] in scope. A multi-tenant host that wants
 * per-tenant scrubbing constructs each [HostContext] with its own [Scrubber].
 *
 * State is process-global, matching the existing `Builtins.credentialProvider`
 * / `Builtins.clock` / `Builtins.random` pattern (see § 4.3 D3 of
 * `proposals/implemented/credential-isolation.md`). The same test-isolation
 * caveat applies for tests that drive the default scrubber: tests that mutate
 * this registry must not run in parallel; the [resetForTesting] entry point
 * restores the empty registry in `@AfterEach` blocks.
 */
object CredentialScrubber {

    /** The backing default [Scrubber] instance. */
    val default: Scrubber = Scrubber()

    /** Register [credential] into the process-default scrubber. */
    internal fun register(credential: Credential) = default.register(credential)

    /** Scrub [text] using the process-default scrubber. */
    fun scrub(text: String): String = default.scrub(text)

    /** Test-isolation entry point for the process-default scrubber. */
    internal fun resetForTesting() = default.resetForTesting()
}
