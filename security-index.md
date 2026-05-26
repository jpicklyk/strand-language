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
| Capability mediation with refinement lattice | [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md), [`proposals/implemented/refinement-lattice-capability-matching.md`](proposals/implemented/refinement-lattice-capability-matching.md) | Verifier and runtime structure implemented. **Argument-binding gap** at the call site (see Q-039) means the refinement parameters are not provably bound to the foreign-function arguments they purport to describe. |
| Content-addressed tamper resistance | [`decisions/ADR-003-content-addressing.md`](decisions/ADR-003-content-addressing.md) | Implemented across Layer 2 steps 1 and 2. BLAKE3 over canonical CBOR with positional encoding. |
| Per-node encryption (multi-recipient envelopes, TEE attestation) | [`decisions/ADR-006-per-node-encryption.md`](decisions/ADR-006-per-node-encryption.md), [`design/encryption-model.md`](design/encryption-model.md), [`design/security-model.md`](design/security-model.md) § TEE attestation chain | **Design only.** No envelope, no AES-256-GCM, no X25519 KEM, no attestation chain in the reference implementation. Tracked under Q-011, Q-012. |
| Foreign function trust model (signed provenance + sandbox) | [`decisions/ADR-005-foreign-nodes.md`](decisions/ADR-005-foreign-nodes.md), [`design/security-model.md`](design/security-model.md) § Foreign binding trust | **Design only.** The in-process Kotlin `Builtins` registry is unconditionally trusted with no signature check, no reproducible-binding compare, no sandbox observation. Tracked under Q-006. |

The integrated threat model is in [`design/security-model.md`](design/security-model.md) § Threat model. Six adversary categories are considered: malicious AI agent, compromised principal, untrusted worker, network attacker, compromised foreign code, malicious model provider, insider. The mechanisms above compose to address them; the implementation gaps below indicate where the composition is currently weaker than the threat model assumes.

## Security questions

Cross-cut of `open-questions.md` for security-relevant questions, in identifier order. Status reflects [`open-questions.md`](open-questions.md) — the canonical record.

### Already catalogued

| Q-NNN | Topic | Status | Primary spec |
|-------|-------|--------|--------------|
| [Q-005](open-questions.md#Q-005) | Confused deputy mitigation | Proposed (parameter-tagged capabilities + CapabilityScope). **Partially undermined in implementation by the argument-binding gap** — see Q-039. | [`design/security-model.md`](design/security-model.md), [`design/effects-and-capabilities.md`](design/effects-and-capabilities.md) |
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
| [Q-039](open-questions.md#Q-039) | EffectDecl–argument coupling: bind capability-check parameters to the actual foreign-call arguments at the verifier level | Critical | Drafted — [`proposals/foreign-effect-projections.md`](proposals/foreign-effect-projections.md) (2026-05-26) |
| [Q-040](open-questions.md#Q-040) | Interpreter resource limits: ingest depth caps, evaluation step counter, recursion/Fixpoint bounds, memory ceiling | High | Drafted — [`proposals/interpreter-resource-limits.md`](proposals/interpreter-resource-limits.md) (2026-05-26) |
| [Q-041](open-questions.md#Q-041) | I/O builtin sandboxing: workspace-rooted Fs.* paths, SSRF guard on Http.Request and Net.Connect, defense-in-depth at the foreign-call boundary | High | Drafted — [`proposals/io-builtin-sandboxing.md`](proposals/io-builtin-sandboxing.md) (2026-05-26) |
| Q-042 | Credential isolation and error redaction: scrub upstream HTTP error content before it surfaces through `InterpretError.IoFailure` | Medium | Pending |

### Already resolved (security-relevant)

| Q-NNN | Topic | Resolution |
|-------|-------|------------|
| [Q-030](open-questions.md#Q-030) | Effect handler algebra | Resolved — N-043 Handler with no-continuation semantics, closure-subtraction rule, innermost-wins dispatch. |
| [Q-031](open-questions.md#Q-031) | Refinement-lattice capability matching | Resolved — `CapabilitySet` keyed by EffectCategory NodeId; per-slot `CapabilityArgument.Wildcard | Concrete(Value)`. |

## Implementation audit findings (2026-05-26)

Each finding below corresponds to a planned Q-NNN entry above. Severity is calibrated against the threat model in [`design/security-model.md`](design/security-model.md).

### Finding 1 (Q-039): EffectDecl–argument coupling

**Severity:** Critical
**Sites:** [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt) (capability check at line 806; `evalEffectInstances` at line 759); [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt) (Fs.* builtins consume path arguments directly)

`Application.effectInstances` carries EffectDecl expressions whose evaluated values are checked against the granted `CapabilityPattern`. The foreign function's value arguments are evaluated separately and passed straight to the builtin. **Nothing binds the two together.** A graph can declare `Filesystem.Write{path: "/safe/path"}` as its effect instance — passing any granted refinement — while passing `/etc/passwd` as the function argument. The builtin writes `/etc/passwd`.

This undermines the parameter-tagged-capability defense from Q-005 at the implementation level. The design assumes the EffectDecl parameters describe what the call site actually does; the verifier and runtime do not enforce this.

### Finding 2 (Q-040): Interpreter resource limits

**Severity:** High
**Sites:** [`impl-kotlin/core/src/main/kotlin/org/strand/core/Json.kt`](impl-kotlin/core/src/main/kotlin/org/strand/core/Json.kt) and adjacent ingest code (no depth cap); [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt) (tree-walking eval; no step counter)

JSON ingest has no nesting-depth cap — deeply nested input causes `StackOverflowError` in the JVM. The tree-walking interpreter has no recursion-depth bound; an Application chain or `Fixpoint` with no base case loops indefinitely or exhausts the JVM stack. No per-evaluation step counter, memory cap, or wall-clock timeout. For a language whose primary author is an AI agent that may misgenerate, this is a hostile-graph problem, not just an accidental-crash problem.

### Finding 3 (Q-041): I/O builtin sandboxing

**Severity:** High
**Sites:** [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt) Fs.* (lines ~395–501), Net.Connect (line ~506), Http.Request (line ~625)

Path arguments are passed directly to `java.nio.file.Paths.get(path)` with no traversal check or workspace-root sandbox. URL arguments to `Http.Request` are passed to `URI.toURL().openConnection()` with **no SSRF guard** against cloud-metadata addresses (e.g., `169.254.169.254` for AWS/GCP), loopback (`127.0.0.1`, `::1`), RFC1918, or link-local ranges. `Net.Connect` accepts any `(host, port)`. Programs granted wildcard capabilities — common in `--grant-all` demo mode and in many tests — have full OS reach.

Even after Q-039 lands (binding capability arguments to foreign-call arguments), defense-in-depth at the builtin layer remains the right shape: the foreign-call boundary should validate the actual argument against the policy independently of the capability check.

### Finding 4 (Q-042): Credential isolation and error redaction

**Severity:** Medium
**Sites:** [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/CredentialProvider.kt`](impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/CredentialProvider.kt); per-provider files (`AnthropicProvider.kt`, `OpenAIProvider.kt`, `GeminiProvider.kt`, `PineconeProvider.kt`, `ChromaProvider.kt`)

`EnvCredentialProvider` reads API keys from environment variables. The LLM and Vector ForeignNodes catch HTTP failures and embed response detail into `InterpretError.IoFailure(detail: String)`. If an upstream HTTP error response echoes Authorization headers or otherwise includes credential material, that surfaces to the calling Strand program and to any logging downstream. No redaction at the `IoFailure` boundary. No cert pinning beyond the default JVM trust store.

## Threat-model coverage matrix

How current implementation coverage maps onto the [`security-model.md`](design/security-model.md) § Threat model adversaries.

| Adversary | Primary defense | Implementation status |
|-----------|------------------|------------------------|
| Malicious AI agent | Mandatory effects + capability mediation | Verifier strong; **runtime path/host enforcement weak** (Q-039, Q-041) |
| Compromised principal | Key revocation + capability scoping + audit | Not implemented (encryption, revocation tree absent) |
| Untrusted worker | Per-node encryption | **Not implemented** (Q-011) |
| Network attacker | AEAD envelopes + authenticated transport | Not implemented for graphs at rest / in transit |
| Compromised foreign code | Signed provenance + sandbox observation | **Not implemented** (Q-006). Largest residual attack surface for the current shipping prototype. |
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
- [`proposals/io-builtin-sandboxing.md`](proposals/io-builtin-sandboxing.md) — Q-041 proposal cites § Finding 3 as the audit motivation
- [`open-questions.md`](open-questions.md) — Q-039, Q-040, Q-041 entries cite this index
- [`impl-kotlin/CLAUDE.md`](impl-kotlin/CLAUDE.md) — Known gaps section references this index from the "With proposals" subsection

Additional incoming references will be added as the Q-042 proposal is drafted.
