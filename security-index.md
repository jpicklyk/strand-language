# Security Index {#security-index}

**Document:** `security-index.md`
**Status:** Living document; updated as security questions are opened, advanced, or resolved
**Last revised:** 2026-05-26 (initial creation following implementation security audit)

## Purpose

This document is a cross-cutting view of Strand's security architecture: which mechanisms are specified, which are implemented, which remain open, and where the implementation diverges from the design's promises. It is not a replacement for [`INDEX.md`](INDEX.md) or [`open-questions.md`](open-questions.md); it supplements them by collecting security-relevant entries into a single index useful for security review.

When a security question is opened, advanced, or resolved, the entry is updated in both this index and the canonical sources (`open-questions.md`, the specific design document) per the standard convention recorded in [`CLAUDE.md`](CLAUDE.md).

## Security architecture

The design's security story rests on five composed mechanisms. The implementation status reflects the Kotlin/JVM reference implementation as of the 2026-05-26 audit.

| Mechanism | Specification | Implementation status |
|-----------|---------------|-----------------------|
| Mandatory effect declarations (effects-as-edges) | [`decisions/ADR-004-effects-as-edges.md`](decisions/ADR-004-effects-as-edges.md), [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) | Implemented across Layer 3 steps 1, 2, 3. Verifier closure check is load-bearing and rigorous. |
| Capability mediation with refinement lattice | [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md), [`proposals/implemented/refinement-lattice-capability-matching.md`](proposals/implemented/refinement-lattice-capability-matching.md), [`proposals/implemented/foreign-effect-projections.md`](proposals/implemented/foreign-effect-projections.md) | Verifier and runtime structure implemented. Argument-binding gap closed by Q-039 for the six migrated `Fs.*` + `Net.Connect` prelude entries — the verifier and runtime synthesize capability-check parameter values directly from the function's actual evaluated arguments. The gap persists for the unmigrated parameterized-effect ForeignNodes (per-provider LLM/Vector, `Crypto.RandomBytes`, `Http.Request`) until follow-up slices redesign their signatures. |
| Content-addressed tamper resistance | [`decisions/ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md) | Implemented across Layer 2 steps 1 and 2. BLAKE3 over canonical CBOR with positional encoding. |
| Per-node encryption (multi-recipient envelopes, TEE attestation) | [`decisions/ADR-006-per-node-encryption.md`](decisions/ADR-006-per-node-encryption.md), [`design/encryption-model.md`](design/encryption-model.md), [`design/security-model.md`](design/security-model.md) § TEE attestation chain | **Design only.** No envelope, no AES-256-GCM, no X25519 KEM, no attestation chain in the reference implementation. Tracked under Q-011, Q-012. |
| Foreign function trust model (signed provenance + sandbox) | [`decisions/ADR-005-foreign-nodes.md`](decisions/ADR-005-foreign-nodes.md), [`design/security-model.md`](design/security-model.md) § Foreign binding trust | **Sandbox observation implemented (Q-041, 2026-05-27);** signed provenance / reproducible-binding compare not implemented (Q-006). The in-process Kotlin `Builtins` registry is still unconditionally trusted at admission, but every `Fs.*` / `Net.Connect` / `Http.Request` foreign call now passes through a host-configured `SandboxPolicy(fs, net)` at the boundary: workspace-rooted filesystem with canonical-path escape detection + symlink rejection; network default-deny on loopback, RFC1918, link-local, multicast, broadcast, IPv6 ULA, cloud-metadata; DNS `PinAtCheck`. |

The integrated threat model is in [`design/security-model.md`](design/security-model.md) § Threat model. Six adversary categories are considered: malicious AI agent, compromised principal, untrusted worker, network attacker, compromised foreign code, malicious model provider, insider. The mechanisms above compose to address them; the implementation gaps below indicate where the composition is currently weaker than the threat model assumes.

## Security questions

Cross-cut of `open-questions.md` for security-relevant questions, in identifier order. Status reflects [`open-questions.md`](open-questions.md) — the canonical record.

### Already catalogued

| Q-NNN | Topic | Status | Primary spec |
|-------|-------|--------|--------------|
| [Q-005](open-questions.md#Q-005) | Confused deputy mitigation | Proposed (parameter-tagged capabilities + CapabilityScope). Implementation-level argument-binding gap closed by Q-039 for the six migrated `Fs.*` + `Net.Connect` prelude entries; the gap persists for unmigrated parameterized-effect ForeignNodes (per-provider LLM/Vector, `Crypto.RandomBytes`, `Http.Request`) until their signatures are redesigned in follow-up slices. | [`design/security-model.md`](design/security-model.md), [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) |
| [Q-006](open-questions.md#Q-006) | Trust and signing model for foreign bindings | Proposed (signed provenance + reproducible + curated + sandbox). Not implemented; in-process trusted registry only. | [`design/security-model.md`](design/security-model.md), [`decisions/ADR-005-foreign-nodes.md`](decisions/ADR-005-foreign-nodes.md) |
| [Q-007](open-questions.md#Q-007) | Effect inference for unannotated foreign code | Proposed (manual annotation + advisory static analysis; refuse rather than admit unusable conservative bindings). | [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) |
| [Q-011](open-questions.md#Q-011) | Per-node encryption key management | Proposed (envelope format, four key types, multi-recipient protocol, revocation Merkle tree). Not implemented. | [`design/encryption-model.md`](design/encryption-model.md) |
| [Q-012](open-questions.md#Q-012) | TEE attestation chain integration | Proposed (four-level chain: platform → workload → runtime → graph). Not implemented. | [`design/security-model.md`](design/security-model.md), [`design/encryption-model.md`](design/encryption-model.md) |
| [Q-013](open-questions.md#Q-013) | Obfuscation guarantees and limits | Deferred — topology obfuscation is post-Phase-2 research. | [`design/security-model.md`](design/security-model.md) |
| [Q-022](open-questions.md#Q-022) | Confidentiality of agent intent | Deferred. | [`design/security-model.md`](design/security-model.md) |

### Audit-surfaced questions (2026-05-26)

A security audit of the Kotlin reference implementation surfaced four implementation-level gaps not previously catalogued. Each will receive a new Q-NNN identifier when its proposal is drafted via the [`strand-research-proposal`](.claude/skills/strand-research-proposal/) skill. Identifiers below are the planned slots, allocated in order of severity.

| Planned Q-NNN | Topic | Severity | Proposal status |
|---------------|-------|----------|-----------------|
| [Q-039](open-questions.md#Q-039) | EffectDecl–argument coupling: bind capability-check parameters to the actual foreign-call arguments at the verifier level | Critical | **Resolved (2026-05-27)** — [`proposals/implemented/foreign-effect-projections.md`](proposals/implemented/foreign-effect-projections.md) |
| [Q-040](open-questions.md#Q-040) | Interpreter resource limits: ingest depth caps, evaluation step counter, recursion/Fixpoint bounds, memory ceiling | High | **Resolved (2026-05-27)** — [`proposals/implemented/interpreter-resource-limits.md`](proposals/implemented/interpreter-resource-limits.md) |
| [Q-041](open-questions.md#Q-041) | I/O builtin sandboxing: workspace-rooted Fs.* paths, SSRF guard on Http.Request and Net.Connect, defense-in-depth at the foreign-call boundary | High | **Resolved (2026-05-27)** — [`proposals/implemented/io-builtin-sandboxing.md`](proposals/implemented/io-builtin-sandboxing.md) |
| [Q-042](open-questions.md#Q-042) | Credential isolation and error redaction: scrub upstream HTTP error content before it surfaces through `InterpretError.IoFailure` | Medium | **Resolved (2026-05-27)** — [`proposals/implemented/credential-isolation.md`](proposals/implemented/credential-isolation.md) |

### Already resolved (security-relevant)

| Q-NNN | Topic | Resolution |
|-------|-------|------------|
| [Q-030](open-questions.md#Q-030) | Effect handler algebra | Resolved — N-043 Handler with no-continuation semantics, closure-subtraction rule, innermost-wins dispatch. |
| [Q-031](open-questions.md#Q-031) | Refinement-lattice capability matching | Resolved — `CapabilitySet` keyed by EffectCategory NodeId; per-slot `CapabilityArgument.Wildcard | Concrete(Value)`. |

## Implementation audit findings (2026-05-26)

Each finding below corresponds to a planned Q-NNN entry above. Severity is calibrated against the threat model in [`design/security-model.md`](design/security-model.md).

### Finding 1 (Q-039): EffectDecl–argument coupling

**Severity:** Critical
**Status:** **Closed (2026-05-27)** — implemented in the Kotlin/JVM reference implementation; proposal at [`proposals/implemented/foreign-effect-projections.md`](proposals/implemented/foreign-effect-projections.md).
**Sites:** [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt) (`applyForeign` now synthesizes the capability-check `instances` map from the function's `effectProjections` plus the already-evaluated `argumentValues`, so the value passed to `checkCapabilities` for an `ArgRef(j)` source IS the value the foreign code receives); [`impl-kotlin/verifier/src/main/kotlin/org/strand/verifier/Verifier.kt`](impl-kotlin/verifier/src/main/kotlin/org/strand/verifier/Verifier.kt) (seven new error variants `ProjectionArityMismatch`, `ProjectionCategoryMismatch`, `ProjectionSourceArityMismatch`, `ProjectionArgRefOutOfRange`, `ProjectionLiteralNotConstant`, `ProjectionLiteralTypeMismatch`, `ProjectionMismatch` enforce admission-time and call-site-time alignment); [`impl-kotlin/authoring/src/main/kotlin/org/strand/authoring/LayerAGrammar.kt`](impl-kotlin/authoring/src/main/kotlin/org/strand/authoring/LayerAGrammar.kt) (the six migrated `Fs.*` + `Net.Connect` prelude entries carry their projections).

`Application.effectInstances` carries EffectDecl expressions whose evaluated values are checked against the granted `CapabilityPattern`. The foreign function's value arguments are evaluated separately and passed straight to the builtin. Before Q-039 nothing bound the two together. Q-039 added optional `effectProjections` content to `ForeignNode` and `FunctionType` that pins each effect's parameter values to specific function arguments (`ArgRef(i)`) or binding-controlled literal nodes (`LiteralNode(t)`). The verifier requires authored EffectDecls at projected call sites to match the projection structurally (NodeId equality for ArgRef sources, canonical-form equality for LiteralNode sources); the interpreter synthesizes the capability-check parameter values from the projection plus the actual evaluated arguments, eliminating drift by construction. Six prelude entries (`fsRead`, `fsWrite`, `fsAppend`, `fsExists`, `fsDelete`, `netConnect`) migrated in the initial slice. Three migrations deferred to follow-up slices per the proposal's Implementation note: per-provider LLM/Vector bindings (binding signatures need redesign to surface `model`/`store` as positional arguments), `Crypto.RandomBytes` (the prelude's `cryptoFx` is parameter-less), and `Http.Request` (paired with the Q-041 sandboxing redesign). ForeignNodes without `effectProjections` continue under legacy Q-031 semantics; the security gap persists for those unmigrated bindings until follow-up slices land.

### Finding 2 (Q-040): Interpreter resource limits

**Severity:** High
**Status:** **Closed (2026-05-27)** — implemented in the Kotlin/JVM reference implementation; proposal at [`proposals/implemented/interpreter-resource-limits.md`](proposals/implemented/interpreter-resource-limits.md).
**Sites (historical):** [`impl-kotlin/core/src/main/kotlin/org/strand/core/Json.kt`](impl-kotlin/core/src/main/kotlin/org/strand/core/Json.kt) and adjacent ingest code (no depth cap); [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt) (tree-walking eval; no step counter)

JSON ingest has no nesting-depth cap — deeply nested input causes `StackOverflowError` in the JVM. The tree-walking interpreter has no recursion-depth bound; an Application chain or `Fixpoint` with no base case loops indefinitely or exhausts the JVM stack. No per-evaluation step counter, memory cap, or wall-clock timeout. For a language whose primary author is an AI agent that may misgenerate, this is a hostile-graph problem, not just an accidental-crash problem.

**Resolution.** A unified `core/EvaluationLimits` data class is honored at admission (JSON ingest depth cap, total node count, total ingest bytes) and at evaluation (step counter, stack-depth bound, allocated-value count, wall-clock budget) by both the tree-walking interpreter and the bytecode VM. Breaches surface as `InterpretError.ResourceExhaustion(at: NodeId?, kind, current, limit)`; ingest breaches surface as `IngestError.ResourceExhaustion(kind, current, limit)`. State-machine runtimes inherit per-evaluation budgets. CLI flags `--max-steps`, `--max-stack-depth`, `--max-allocated-values`, `--wall-clock-ms`, `--max-json-depth`, `--max-node-count`, `--max-ingest-bytes` work on `run`, `machine`, `group`. Default values target the seed-corpus envelope plus three orders of magnitude of headroom; the corpus runs unchanged under defaults.

### Finding 3 (Q-041): I/O builtin sandboxing

**Severity:** High
**Status:** **Closed (2026-05-27)** — implemented in the Kotlin/JVM reference implementation; proposal at [`proposals/implemented/io-builtin-sandboxing.md`](proposals/implemented/io-builtin-sandboxing.md).
**Sites:** [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/SandboxPolicy.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/SandboxPolicy.kt) (the new host-configured `SandboxPolicy(fs, net)` ADT, `FsSandbox.resolve` for workspace containment + symlink rejection, `NetSandbox.checkConnect` for hostname/IP blocklist + allowlist + multi-A SSRF defence + DNS pin-at-check); [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/ResourceTable.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/ResourceTable.kt) (the new `SandboxViolation` runtime exception and `SandboxViolationKind` enum); [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/InterpretError.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/InterpretError.kt) (the new `InterpretError.SandboxViolation` variant); [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt) (`Fs.*` migrated to `FsSandbox.resolve` before JVM file API; `Net.Connect` migrated to `NetSandbox.checkConnect` with pin-at-check; `Http.Request` redesigned to seven-arg `(host, port, scheme, path, method, headers, body)` signature for Q-039 alignment; `Http.RequestFromUrl` preserved as legacy wrapper); [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt) (the new `translateSandboxViolation` helper at every `applyForeign` catch site).

Path arguments were passed directly to `java.nio.file.Paths.get(path)` with no traversal check or workspace-root sandbox. URL arguments to `Http.Request` were passed to `URI.toURL().openConnection()` with **no SSRF guard** against cloud-metadata addresses (e.g., `169.254.169.254` for AWS/GCP), loopback (`127.0.0.1`, `::1`), RFC1918, or link-local ranges. `Net.Connect` accepted any `(host, port)`. Programs granted wildcard capabilities — common in `--grant-all` demo mode and in many tests — had full OS reach.

Even after Q-039 landed (binding capability arguments to foreign-call arguments), defense-in-depth at the builtin layer remained the right shape: the foreign-call boundary validates the actual argument against the policy independently of the capability check.

**Resolution.** A host-configured `SandboxPolicy(fs: FsPolicy, net: NetPolicy)` mediates every `Fs.*`, `Net.Connect`, and `Http.Request` foreign call at the boundary inside each builtin's `Fn`. `FsSandbox.resolve` canonicalises path arguments via `Path.toRealPath(NOFOLLOW_LINKS)` and rejects paths whose canonical form escapes the configured workspace root (`SandboxViolation(FsPathEscape)`) or traverses a symlink under `followSymlinks=false` (`FsSymlinkRejected`). `NetSandbox.checkConnect` resolves the host through an injectable `NameResolver` (default `SystemNameResolver` → `InetAddress.getAllByName`), checks every returned address against `NetPolicy.blockedRanges` (defeats multi-A SSRF), denies cloud-metadata hostnames via `NetPolicy.blockedHostnames`, enforces an optional allowlist of host globs, and returns the resolved `InetAddress` for `Socket(InetAddress, port)` pin-at-check. `Http.Request` redesigned to the seven-arg signature `(host, port, scheme, path, method, headers, body) -> {status, body, headers}` so Q-039's projection vocabulary binds host/port via `ArgRef(0)` / `ArgRef(1)` directly; legacy `Http.RequestFromUrl(method, url, body) -> {status, body}` preserved as wrapper. CLI flags `--workspace-root`, `--allow-fs-escape`, `--allow-host` (repeatable), `--allow-net-internal` on `run`, `machine`, `group`; CLI default is `SandboxPolicy.SECURE_DEFAULT` (workspace = JVM working directory, default-deny network with full blocked-range list, `PinAtCheck` DNS). The library singleton default on `Builtins.sandboxPolicy` is `OPEN_DEFAULT` (opt-out) so the 895-test pre-Q-041 baseline runs unchanged; CLI overrides at startup. 27 new tests (25 in `SandboxPolicyTest` covering all 12 § 7 scenarios + auxiliary policy primitives, 1 skipped on Windows for the symlink fixture; 2 in `CorpusSandboxTest` driving corpus 74 + 75). Full `gradle test` clean — 922 tests pass with zero regressions across the 895 pre-Q-041 baseline. Hash invariance preserved across all 68 pre-Q-041 corpus programs (the proposal touches no node encoding). Five deviations recorded in the proposal's Implementation note: singleton vs CLI default split, `httpReq` prelude entry redirected to the legacy wrapper, header representation as Cons/Nil SumV chain, Windows symlink skipped, hostname blocklist factored to its own `NetPolicy.blockedHostnames` field.

### Finding 4 (Q-042): Credential isolation and error redaction

**Severity:** Medium
**Status:** **Closed (2026-05-27)** — implemented in the Kotlin/JVM reference implementation; proposal at [`proposals/implemented/credential-isolation.md`](proposals/implemented/credential-isolation.md).
**Sites:** [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Credential.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Credential.kt) (new opaque wrapper); [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/CredentialScrubber.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/CredentialScrubber.kt) (new process-global registry); [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/CredentialProvider.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/CredentialProvider.kt) (return type now `Credential?`); per-provider files migrated with seven total `.reveal()` call sites; [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/ResourceTable.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/ResourceTable.kt) (`IoFailure` constructor scrubs `detail` and stores `unscrubbedDetail`); [`impl-kotlin/core/src/main/kotlin/org/strand/core/ErrorVerbosity.kt`](impl-kotlin/core/src/main/kotlin/org/strand/core/ErrorVerbosity.kt) (new three-variant enum carried on `EvaluationLimits.errorVerbosity`); [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt) (centralised `translateIoFailure` helper).

`EnvCredentialProvider` previously read API keys from environment variables and returned them as bare `String?`. The LLM and Vector ForeignNodes caught HTTP failures and embedded response detail into `InterpretError.IoFailure(detail: String)`. If an upstream HTTP error response echoed Authorization headers or otherwise included credential material, that surfaced to the calling Strand program and to any logging downstream. No redaction at the `IoFailure` boundary. Cert pinning beyond the default JVM trust store remains deferred.

**Resolution.** Three coordinated mechanisms ship in the 2026-05-27 implementation. (1) `Credential` wrapper type returned by `CredentialProvider.resolve` and `apiKey` — `toString` returns `[REDACTED:<provider>:<credentialKey>]`, `equals`/`hashCode` are reference-identity, and the raw String is reachable only through an explicit `reveal()` method (seven call sites total in production code, all at HTTP header builders, auditable via `git grep -n '\.reveal()'`). (2) Process-global `CredentialScrubber` registers every credential value minted by `CredentialProvider` and scrubs every `IoFailure.detail` at construction by replacing literal occurrences with the same placeholder; skips registration for blank or sub-8-character values to avoid over-redacting common substrings. (3) Host-configurable `ErrorVerbosity` enum on `EvaluationLimits` (`Redacted` default; `Full` opt-in for dev/debug with a one-time stderr warning; `RedactedWithKindOnly` for maximum suppression) threaded through `Interpreter.translateIoFailure` at the `IoFailure → InterpretError.IoFailure` boundary. CLI flag `--error-verbosity {redacted|full|kind-only}` on `run`, `machine`, `group` subcommands. 34 new tests; full `gradle test` green with 895 tests passing. Cert pinning, pattern-based scrubbing, heap-clearing on rotation, audit logging of credential reads, and subprocess-environment filtering for `Process.Spawn` remain deferred to follow-ups; hooks documented in the proposal's § 8.

## Threat-model coverage matrix

How current implementation coverage maps onto the [`security-model.md`](design/security-model.md) § Threat model adversaries.

| Adversary | Primary defense | Implementation status |
|-----------|------------------|------------------------|
| Malicious AI agent | Mandatory effects + capability mediation | Verifier strong; runtime path/host enforcement strengthened by Q-039 for the migrated `Fs.*` and `Net.Connect` bindings (capability-check value bound to foreign-call argument by construction); the orthogonal Q-041 sandbox-policy gap closed (workspace-rooted filesystem + network default-deny on loopback / RFC1918 / cloud-metadata + DNS pin-at-check, default-on for CLI invocations). Unmigrated Q-039 bindings (per-provider LLM/Vector, `Crypto.RandomBytes`) remain — sandboxed by Q-041 against fs/net argument abuse, but their capability-check value still drifts from the argument until their signatures are redesigned. |
| Compromised principal | Key revocation + capability scoping + audit | Not implemented (encryption, revocation tree absent) |
| Untrusted worker | Per-node encryption | **Not implemented** (Q-011) |
| Network attacker | AEAD envelopes + authenticated transport | Not implemented for graphs at rest / in transit |
| Compromised foreign code | Signed provenance + sandbox observation | **Sandbox observation implemented** (Q-041, 2026-05-27); signed provenance / reproducible-binding compare not implemented (Q-006). Q-041 limits what a compromised binding can reach (fs paths inside workspace; outbound network to non-private destinations only); Q-006 would prevent the compromised binding from being admitted in the first place. |
| Malicious model provider | Compositional opacity + encryption | Not implemented (Q-013, Q-022 deferred) |
| Insider | Capability scoping + audit | Operational only — outside language scope. |

## References

**Outgoing:**
- [`INDEX.md`](INDEX.md) — corpus index and identifier registry
- [`open-questions.md`](open-questions.md) — Q-NNN registry
- [`design/security-model.md`](design/security-model.md) — integrated threat model
- [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) — capability mediation algebra
- [`design/encryption-model.md`](design/encryption-model.md) — encryption envelope and TEE protocol
- [`decisions/ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md) — tamper resistance
- [`decisions/ADR-004-effects-as-edges.md`](decisions/ADR-004-effects-as-edges.md) — mandatory effect declarations
- [`decisions/ADR-005-foreign-nodes.md`](decisions/ADR-005-foreign-nodes.md) — foreign function interface
- [`decisions/ADR-006-per-node-encryption.md`](decisions/ADR-006-per-node-encryption.md) — per-node encryption
- [`proposals/implemented/refinement-lattice-capability-matching.md`](proposals/implemented/refinement-lattice-capability-matching.md) — Q-031 implementation
- [`proposals/implemented/effect-handlers.md`](proposals/implemented/effect-handlers.md) — Q-030 implementation

**Incoming:**
- [`INDEX.md`](INDEX.md) — references this index from the document tree and the 2026-05-26 revision notes
- [`proposals/foreign-effect-projections.md`](proposals/foreign-effect-projections.md) — Q-039 proposal cites § Finding 1 as the audit motivation
- [`proposals/interpreter-resource-limits.md`](proposals/interpreter-resource-limits.md) — Q-040 proposal cites § Finding 2 as the audit motivation
- [`proposals/implemented/io-builtin-sandboxing.md`](proposals/implemented/io-builtin-sandboxing.md) — Q-041 proposal cites § Finding 3 as the audit motivation
- [`proposals/credential-isolation.md`](proposals/credential-isolation.md) — Q-042 proposal cites § Finding 4 as the audit motivation
- [`open-questions.md`](open-questions.md) — Q-039, Q-040, Q-041, Q-042 entries cite this index
- [`impl-kotlin/CLAUDE.md`](impl-kotlin/CLAUDE.md) — Known gaps section references this index from the "With proposals" subsection

All four audit-surfaced findings are now resolved (Q-039 / Q-040 / Q-041 / Q-042 all landed 2026-05-27); this index is the cross-cut and stays as the historical record.
