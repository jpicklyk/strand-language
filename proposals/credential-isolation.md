# Credential Isolation and Error Redaction

**Document:** `proposals/credential-isolation.md`
**Status:** Draft proposal
**Date:** 2026-05-26
**Concerns:** [`design/security-model.md`](../design/security-model.md), [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md), [`proposals/implemented/agent-native-capabilities.md`](implemented/agent-native-capabilities.md) § 4.4 (credential provider design), [`proposals/implemented/agent-native-vector-stores.md`](implemented/agent-native-vector-stores.md) § 4.2, [Q-042](../open-questions.md#Q-042), [`security-index.md`](../security-index.md) § Finding 4
**Scope:** Small-medium

This proposal closes the credential-leakage gap recorded as Finding 4 in the 2026-05-26 audit ([`security-index.md`](../security-index.md)). Today, an API key resolved via [`CredentialProvider.resolve`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/CredentialProvider.kt) is a bare `String?` that flows freely through provider implementations and into `IoFailure(detail: String)` whenever the upstream HTTP response is echoed in an error message. The proposal wraps credential values in an opaque `Credential` type, runs a centralised scrubber over every `IoFailure` detail before it surfaces to the agent, and adds host-configurable error verbosity so production deployments can default to redacted-only while dev environments may opt in to fuller diagnostics.

## 1. Problem statement

Finding 4 in [`security-index.md`](../security-index.md) records three leakage paths verified against the cited sources:

1. **Upstream response body echoed verbatim in `IoFailure.detail`.** [`AnthropicProvider.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/AnthropicProvider.kt) line ~93 constructs `IoFailure("anthropic-http-status", "status ${response.status}: ${String(response.body, Charsets.UTF_8).take(500)}")`. The first 500 bytes of the upstream body land in `detail`. If the upstream service echoes the request's `x-api-key` header in its error response (some misconfigured gateways and proxies do this), or if the API returns the request payload for debugging (mistakes happen), the raw API key reaches `InterpretError.IoFailure` and any downstream logging. [`ChromaProvider.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/ChromaProvider.kt) lines ~78 and ~126 follow the same pattern; the per-provider files for OpenAI, Gemini, and Pinecone all echo response bodies the same way.

2. **JVM `IOException.message` may include URL credentials.** A URL of the form `https://user:secret@host/path` passed to `URI(urlStr).toURL().openConnection()` ([`Builtins.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt) line ~627) produces JVM exception messages that frequently include the full URL (or significant portions of it). The current `IoFailure("http-request", "$urlStr: ${e.message}")` interpolation surfaces both.

3. **No structural "this is a secret" marker.** Once a credential is read out of `CredentialProvider.resolve`, it is a plain `kotlin.String`. There is no compiler-enforced barrier preventing it from being concatenated into a log line, embedded in an exception message, or stored anywhere a `String` is admitted. Code review and convention are the only safeguards. The existing `CredentialProvider.kt` documents the intent ("credentials are NOT capabilities … the key is part of the execution environment, not the content-addressed graph") but provides no runtime enforcement.

The threat model in [`design/security-model.md`](../design/security-model.md) § Threat model lists the "compromised principal" adversary — an attacker who acquires a principal's key. Q-042 narrows one acquisition surface: an agent or operator who can read the runtime's error stream should not learn the principal's API keys. This complements but does not replace key rotation and revocation (Q-011).

A secondary concern, in the same audit row, is the absence of certificate pinning beyond the default JVM trust store. A compromised CA — or a network attacker holding a valid cert chained to the JVM's trust store — can intercept the TLS handshake to e.g. `api.anthropic.com`; the API key in the request headers is then exposed to the attacker. Strand currently has no per-host pinning mechanism. The proposal scopes pinning out of V1 (deferred to a follow-up) but specifies the hook so it is straightforward to add later.

## 2. Prior art

- **Rust's `secrecy` crate.** `Secret<T>` wrapper whose `Debug` and `Display` implementations print `[REDACTED]`; explicit `.expose_secret()` is required for the raw value, making leakage sites visible at code review. The exact ergonomic this proposal copies.
- **Java's `javax.security.auth.Subject` and `PasswordCallback`.** Credentials live in cleared-on-use `char[]` rather than `String` (because `String` cannot be zeroed). Strand defers heap-clearing as a higher-cost mitigation but adopts the wrapper-type discipline.
- **HashiCorp Vault's audit log redaction.** Every audit-log entry passes through a sanitiser that replaces known token patterns (Vault tokens, Kubernetes service account tokens, AWS secret access keys) with hashed placeholders. The pattern-based fallback this proposal flags as a follow-up.
- **OpenTelemetry's attribute-redaction processor.** Centralised sanitisation at the boundary between the application and the observability backend; attributes matching configurable patterns are redacted before export. Strand's analogue is the `IoFailure` boundary between the foreign-call site and the agent-visible error stream.
- **Logback / SLF4J `PatternLayout` masking encoders.** Pattern-replacement passes applied to every log event. Cheap to adopt; effective against accidental interpolation; vulnerable to format transformations (base64, hashing) on the credential before it lands in the log.

Cross-cut: every production secret-handling story combines (a) a wrapper type that makes the secret syntactically distinct from a `String` so accidents are caught at the compiler and (b) a boundary scrubber that catches what the wrapper does not (upstream echoes, third-party library logs, exception chains that include unwrapped values). This proposal adopts both for V1; the third common piece (pattern-based generic redaction) is deferred.

## 3. Recommended approach

Three coordinated mechanisms:

| # | Mechanism | Defends against |
|---|-----------|-----------------|
| M1 | A `Credential` wrapper type returned by `CredentialProvider.resolve`. `toString` returns `[REDACTED:<provider>:<key>]`; `equals`/`hashCode` default to reference identity so value comparisons cannot leak. The raw String is reachable only through `Credential.reveal()`, a single explicit method whose call sites are auditable in `git grep`. | Accidental string interpolation, accidental logging, accidental serialisation. |
| M2 | A `CredentialScrubber` singleton that maintains the set of credential string values issued by every `CredentialProvider.resolve` call during the runtime's lifetime. Every `IoFailure(kind, detail)` construction runs `detail` through `scrubber.scrub(...)`, which replaces literal occurrences of any active credential value with the same `[REDACTED:<provider>:<key>]` placeholder. | Upstream response-body echo, JVM exception messages embedding the credential, third-party library `toString` calls. |
| M3 | Host-configurable `ErrorVerbosity` enum on the runtime: `Redacted` (default), `Full`, `RedactedWithKindOnly`. `Redacted` runs M2's scrubber. `Full` skips scrubbing (dev/debug only; logged at runtime entry with an explicit warning). `RedactedWithKindOnly` strips `detail` entirely, surfacing only the structured `IoFailure.kind` field. | Operator choice between safety and diagnostics. |

The combination yields: secrets are syntactically distinct (M1), runtime accidents are caught at the boundary (M2), and production deployments can choose how aggressively to suppress diagnostic detail (M3). Certificate pinning is deferred to a follow-up but the hook is specified in § 8.

| # | Decision | Recommendation |
|---|----------|----------------|
| D1 | Wrapper type or just scrubber? | Both. Wrapper for compile-time discipline; scrubber for runtime safety net. Single mechanism is insufficient. |
| D2 | `reveal()` named explicitly, not via implicit conversion. | Explicit. Auditable at code review. Mirrors Rust `secrecy`. |
| D3 | Scrubber is process-global or per-evaluation? | Process-global singleton, matching the existing `Builtins.credentialProvider` / `Builtins.clock` / `Builtins.random` pattern. Carries the same test-isolation caveat. |
| D4 | Pattern-based scrubbing (regex for Bearer tokens, sk- prefixes, etc.)? | Deferred. Active-credential scrubbing is precise; pattern-based is best-effort and risks over-redaction. Open question for a follow-up once dynamic-cost evaluation reveals real false-negative cases. |
| D5 | Heap-clearing the credential on rotation? | Deferred. `String` cannot be zeroed in JVM. A `char[]`-based credential interior is a separate proposal with significant API churn. |
| D6 | Certificate pinning? | Deferred to follow-up. Hook specified (the per-provider `LlmHttpClient` / `VectorHttpTransport` injection point already isolates the HTTP boundary; pinning is a transport-layer concern). |

## 4. Detailed mechanism

### 4.1 The `Credential` wrapper

```kotlin
// New file: interpreter/Credential.kt
class Credential internal constructor(
    private val value: String,
    val provider: String,
    val credentialKey: String,
) {
    /**
     * Return the underlying credential string. Every call site of [reveal]
     * is a deliberate "I am about to use this secret" intent — typically
     * an HTTP header builder or an auth-handshake protocol. Audit with
     * `git grep -n '\.reveal()'`. Surface a structured rationale via code
     * comments at every call site so review can confirm.
     */
    fun reveal(): String = value

    /** Always redacted. Never returns the underlying value. */
    override fun toString(): String = "[REDACTED:$provider:$credentialKey]"

    /** Reference identity. Value-comparing two credentials is suspicious. */
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
```

The `internal` constructor restricts construction to the `interpreter` module — only `CredentialProvider` implementations can mint a `Credential`. Test code constructs through `StaticCredentialProvider` / `InMemoryCredentialProvider`, which call the internal constructor.

### 4.2 `CredentialProvider` signature change

```kotlin
interface CredentialProvider {
    fun resolve(provider: String, credentialKey: String): Credential?
    fun apiKey(provider: String): Credential? = resolve(provider, "api_key")
}
```

The return type changes from `String?` to `Credential?`. Existing call sites in `AnthropicProvider`, `OpenAIProvider`, `GeminiProvider`, `PineconeProvider`, `ChromaProvider` are updated to call `.reveal()` exactly once — at the header-builder site. The diff per provider file is ~3 lines.

### 4.3 The `CredentialScrubber`

```kotlin
// New file: interpreter/CredentialScrubber.kt
object CredentialScrubber {
    @Volatile
    private var registry: Map<String, String> = emptyMap()  // value -> placeholder

    /**
     * Register a [Credential] for redaction. Called automatically by
     * every [CredentialProvider] implementation when [resolve] returns a
     * non-null value. The scrubber retains the raw string for the
     * lifetime of the runtime — heap-clearing on rotation is a separate
     * mitigation (D5). Process-global state.
     */
    internal fun register(credential: Credential) {
        val raw = credential.reveal()
        if (raw.isBlank() || raw.length < 8) return  // skip empty / suspiciously short
        val placeholder = "[REDACTED:${credential.provider}:${credential.credentialKey}]"
        registry = registry + (raw to placeholder)
    }

    /**
     * Replace every literal occurrence of any registered credential value
     * in [text] with its placeholder. Linear in (text.length × registry.size);
     * registry is small (one entry per provider in practice).
     */
    fun scrub(text: String): String {
        if (registry.isEmpty()) return text
        var result = text
        for ((raw, placeholder) in registry) {
            if (raw in result) result = result.replace(raw, placeholder)
        }
        return result
    }

    /** Reset for test isolation. Called in @AfterEach. */
    internal fun resetForTesting() { registry = emptyMap() }
}
```

Skipping credentials shorter than 8 characters avoids accidental over-redaction of common substrings ("test", "demo") in error messages from tests using placeholder keys.

### 4.4 `IoFailure` scrubbing path

```kotlin
// Existing: interpreter/IoFailure.kt
class IoFailure(val kind: String, detail: String) : RuntimeException() {
    val detail: String = CredentialScrubber.scrub(detail)
    override val message: String get() = "$kind: $detail"
}
```

Every existing `throw IoFailure("anthropic-http-status", "status 401: ...")` automatically scrubs without changes to provider code. The construction-time scrub is the single mandatory pass.

### 4.5 `ErrorVerbosity` and the `EvaluationLimits` companion

```kotlin
enum class ErrorVerbosity {
    /** Default. Scrub IoFailure.detail through CredentialScrubber. */
    Redacted,

    /** Skip scrubbing. Logged once at runtime entry. */
    Full,

    /** Strip detail entirely; only kind survives. Most restrictive. */
    RedactedWithKindOnly,
}

// In EvaluationLimits (Q-040): a sibling field
data class EvaluationLimits(
    // ... existing fields from Q-040 ...
    val errorVerbosity: ErrorVerbosity = ErrorVerbosity.Redacted,
)
```

`InterpretError.IoFailure` (the verifier-translated form, not the runtime exception) consults the active `ErrorVerbosity` when materialising `detail`:

```kotlin
// In InterpretError.IoFailure construction
val effectiveDetail = when (verbosity) {
    ErrorVerbosity.Redacted -> ioFailure.detail   // already scrubbed at construction
    ErrorVerbosity.Full -> ioFailure.unscrubedDetail  // explicit field, opt-in
    ErrorVerbosity.RedactedWithKindOnly -> "(detail suppressed)"
}
```

The `IoFailure` constructor stores both `detail` (scrubbed) and `unscrubedDetail` (raw) — the unscrubed field is read only under the `Full` verbosity and is otherwise inert.

### 4.6 Worked example: Anthropic 401 with echoed key

A misconfigured Anthropic proxy returns `{"error":"invalid api key: sk-ant-api03-xxxxxxxx", "status":401}`. The provider code constructs:

```
IoFailure("anthropic-http-status",
  "status 401: {\"error\":\"invalid api key: sk-ant-api03-xxxxxxxx\", \"status\":401}")
```

The `IoFailure` constructor runs `CredentialScrubber.scrub` on the detail. Because `sk-ant-api03-xxxxxxxx` was registered earlier by `CredentialProvider.apiKey("anthropic")`, the substring is replaced. The agent sees:

```
anthropic-http-status: status 401: {"error":"invalid api key: [REDACTED:anthropic:api_key]", "status":401}
```

The structural diagnostic ("invalid api key") survives; the secret does not.

## 5. Verifier rules

None new. Credential handling is runtime policy, not graph well-formedness. The proposal does not introduce a node category and does not change any verifier algorithm.

## 6. Interpreter / runtime semantics

The interpreter changes are localised to three surfaces:

1. **`CredentialProvider.resolve` return type and `Credential.reveal` call sites** — § 4.2. Every per-provider file (Anthropic, OpenAI, Gemini, Pinecone, Chroma) adds one `.reveal()` call at its header-builder line.
2. **`IoFailure` constructor scrubs `detail` once** — § 4.4. No per-provider change required for the scrubbing; the existing `throw IoFailure(...)` call sites benefit automatically.
3. **`InterpretError.IoFailure` materialisation respects `ErrorVerbosity`** — § 4.5. The runtime entry point (`Interpreter.eval`, `Vm.evaluate`, `StateMachineRuntime.runMachine`) accepts the `EvaluationLimits.errorVerbosity` and threads it through to the `IoFailure` → `InterpretError.IoFailure` translation in `applyForeign`.

`CredentialProvider` implementations (the three Strand ships: `EnvCredentialProvider`, `StaticCredentialProvider`, `InMemoryCredentialProvider`) register every credential they mint with `CredentialScrubber` before returning. Custom host implementations adopt the same discipline; failing to do so means the host's secrets are not scrubbed (the proposal documents this as a host responsibility).

## 7. Test scenarios

1. **`Credential.toString` is redacted.** `EnvCredentialProvider.resolve("anthropic", "api_key")?.toString()` returns `[REDACTED:anthropic:api_key]`, never the env-var value.
2. **`Credential.reveal` returns the raw value.** Used by per-provider header builders. Direct unit-test coverage.
3. **`IoFailure.detail` is scrubbed at construction.** Construct an `IoFailure("test", "the secret is sk-test-xxxxxxxx")` after registering `sk-test-xxxxxxxx` with the scrubber. Expected: `detail` reads `the secret is [REDACTED:...]`.
4. **Upstream response echo is scrubbed.** A mocked `LlmHttpClient` returns a 401 whose body literally echoes the API key. `IoFailure` raised by `AnthropicProvider.generate` has the key replaced in its detail.
5. **JVM `IOException` message containing the URL is scrubbed.** A malformed URL with `https://attacker:sk-leaked@example.com/path` triggers a `MalformedURLException`. The resulting `IoFailure("http-request", ...)` detail has the credential portion scrubbed if `sk-leaked` was registered.
6. **`ErrorVerbosity.Full` skips scrubbing.** Set `errorVerbosity = Full`; the same upstream-echo scenario surfaces the raw credential in the agent-visible detail. Verifies the opt-in path works.
7. **`ErrorVerbosity.RedactedWithKindOnly` strips detail entirely.** Same scenario; agent sees only `"anthropic-http-status"`, no `detail`. Verifies the strict path.
8. **Credential equality is reference, not value.** Two `Credential` instances minted from the same raw String are unequal. Prevents `credentials.containsValue(suspect)` lookups from succeeding.
9. **`CredentialScrubber` skips short / blank values.** Registering an 8-character placeholder ("password") still scrubs; registering "abc" (3 chars) is no-op (would over-redact common substrings). Boundary cases tested.
10. **Test isolation.** `CredentialScrubber.resetForTesting()` clears the registry. Concurrent test runs that mutate the singleton document the test-ordering caveat the existing `Builtins.clock` pattern carries.
11. **Per-provider `.reveal()` call sites are the only ones.** A `git grep` style integration test (or a build-time check) confirms `\.reveal\(\)` appears only in the five per-provider files plus the test scaffolding.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Pattern-based scrubbing.** A regex pass for `Bearer\s+[A-Za-z0-9._~+/-]{20,}`, `sk-[a-z0-9-]{40,}`, `x-api-key:\s+\S+`, etc. Catches credentials issued outside `CredentialProvider` (e.g., a custom binding that injects a key directly). Open until a real case surfaces; over-redaction risk in pattern design is non-trivial.
- **Heap-clearing on rotation.** JVM `String` interns and may share storage; zeroing is not possible. A `char[]`-backed credential interior with explicit `clear()` is a larger API change. Deferred until a deployment requires it.
- **Certificate pinning.** Per-host SHA-256 pin sets, threaded through the `LlmHttpClient` / `VectorHttpTransport` injection point. The transport-layer hook already isolates the work; the proposal does not specify the pinning policy data model. Open as a follow-up — likely a `TransportPolicy(pins: Map<String, Set<ByteArray>>)` sitting alongside `SandboxPolicy` from Q-041.
- **Audit log for credential reads.** Every `CredentialProvider.resolve` could emit a host-side audit event (provider, credentialKey, timestamp). Useful for compliance; out of scope for V1.
- **Encrypted credential storage at rest.** The `EnvCredentialProvider` reads from process environment. A `KmsBackedCredentialProvider` that decrypts on first use, caches in-process, and zeroes on rotation is a host-side mitigation. The interface supports it without change.

**Real research questions:**

- *Scrubbing performance under high-volume IoFailure rates.* A `replace` per registered credential per IoFailure is acceptable at the current few-keys-per-deployment scale. A deployment with hundreds of per-tenant credentials would benefit from an Aho-Corasick automaton built lazily as the registry grows.
- *Cross-process credential surfaces.* Subprocess invocation via `Process.Spawn` (E-013) inherits the parent's environment by default, exposing credentials to any child process. Q-041's SandboxPolicy is the right hook for filtering subprocess environment; the work is cross-cutting between Q-041 and Q-042 and should be tracked explicitly.
- *State-machine recorded events.* `MachineGroupHandle.recordedEvents(instance)` (Q-033 step 2) replays inputs verbatim. If a credential ever appears in an input event (it should not — credentials are runtime, events are graph data — but `IoFailure` payloads embedded in supervisor input events could carry leaked detail), the recorder propagates them. The scrubbed `IoFailure.detail` already covers this path, but it deserves a documented invariant.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `interpreter/Credential.kt` (new) | New `Credential` data class with `provider`, `credentialKey`, redacted `toString`, explicit `reveal()`, reference-identity equality. | Small |
| `interpreter/CredentialScrubber.kt` (new) | Process-global registry + `scrub` function + `resetForTesting`. | Small |
| `interpreter/CredentialProvider.kt` | Change `resolve` return type from `String?` to `Credential?`. `EnvCredentialProvider`, `StaticCredentialProvider`, `InMemoryCredentialProvider` updated to mint `Credential` and call `CredentialScrubber.register`. | Small |
| `interpreter/IoFailure.kt` (or wherever defined) | Constructor runs `CredentialScrubber.scrub(detail)` on the stored field; preserve `unscrubedDetail` for the `Full` verbosity path. | Small |
| `interpreter/AnthropicProvider.kt`, `OpenAIProvider.kt`, `GeminiProvider.kt`, `PineconeProvider.kt`, `ChromaProvider.kt` | One `.reveal()` call site per provider at the header builder. ~3-line diff per file. | Small |
| `interpreter/InterpretError.kt` | Add `unscrubedDetail` field to the `IoFailure` variant; thread `errorVerbosity` from runtime config through `applyForeign`. | Small |
| `core/EvaluationLimits.kt` (Q-040, depends on it) | Add `errorVerbosity: ErrorVerbosity = Redacted` field plus the `ErrorVerbosity` enum sibling. | Small |
| `interpreter/Builtins.kt` | The `applyForeign` `IoFailure` → `InterpretError.IoFailure` translation reads the verbosity from the active `EvaluationLimits` (threaded from the runtime entry). | Small |
| `cli/` | A `--error-verbosity {redacted|full|kind-only}` flag forwarded to `EvaluationLimits`. | Small |
| `interpreter/test/CredentialTest.kt` (new) | Unit tests for the wrapper (scenarios 1, 2, 8). | Small |
| `interpreter/test/CredentialScrubberTest.kt` (new) | Unit tests for the scrubber (scenarios 3, 9, 10). | Small |
| `interpreter/test/AnthropicProviderTest.kt`, `OpenAIProviderTest.kt`, etc. | Per-provider tests for scenarios 4, 5 (echo and IOException paths). | Medium |
| `interpreter/test/ErrorVerbosityTest.kt` (new) | Verbosity-toggle tests (scenarios 6, 7). | Small |

**Order of work.** (1) `Credential` and `CredentialScrubber` modules with unit tests. (2) `CredentialProvider` signature change with the three default implementations updated. (3) Per-provider `.reveal()` migrations in lockstep (a compile error at any unmigrated site is the safety net). (4) `IoFailure` scrubbing path. (5) `ErrorVerbosity` enum and `EvaluationLimits` field — depends on Q-040 landing first. (6) `InterpretError.IoFailure` materialisation under verbosity. (7) CLI flag. (8) Integration tests with mocked HTTP transports replaying upstream-echo cases.

**Depends on.** Q-040 (`EvaluationLimits`). Land Q-040 first; this proposal grafts onto its policy carrier.

**Not in this slice.**

- Pattern-based scrubbing (Bearer, sk-, x-api-key regex).
- Heap-clearing of credential strings on rotation.
- Certificate pinning (separate follow-up; transport-layer concern).
- Audit logging of credential reads.
- KMS / secret-manager-backed `CredentialProvider` implementations (host responsibility).
- Subprocess environment filtering for `Process.Spawn` (cross-cuts with Q-041's `SandboxPolicy`; tracked explicitly).

## References

**Outgoing references:**
- [`design/security-model.md`](../design/security-model.md) — § Threat model (compromised principal adversary)
- [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — runtime trust boundary at the foreign-call site
- [`proposals/implemented/agent-native-capabilities.md`](implemented/agent-native-capabilities.md) — § 4.4 credential provider design (Q-037 Phase 1)
- [`proposals/implemented/agent-native-vector-stores.md`](implemented/agent-native-vector-stores.md) — § 4.2 credential provider design (Q-038 Phase 1)
- [`proposals/interpreter-resource-limits.md`](interpreter-resource-limits.md) — `EvaluationLimits` carrier this proposal grafts onto
- [`proposals/io-builtin-sandboxing.md`](io-builtin-sandboxing.md) — `SandboxPolicy` neighbour; subprocess-environment filtering is shared territory
- [`security-index.md`](../security-index.md) — Finding 4, the audit entry that motivated this proposal
- [`open-questions.md`](../open-questions.md) — Q-042

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-042 points at this proposal
- [`proposals/README.md`](README.md)
- [`security-index.md`](../security-index.md) — Finding 4 row links here
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section
