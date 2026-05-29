# Security Model {#security-model}

**Document:** `design/security-model.md`
**Status:** Wave 3 draft
**Last revised:** 2026-05-23

## Summary

This document specifies Strand's security model: the threats the design intends to defend against, the mechanisms by which defense is achieved, the attestation protocols that connect graph identity to physical execution, and the limits the design acknowledges. It synthesizes material from [effects-and-capabilities.md](effects-and-capabilities.md), [encryption-model.md](encryption-model.md), and the foreign-node trust model into a single threat-oriented account.

The design's central security claim is that the security properties of a Strand program are structural rather than behavioral. A property like "this program does not access the network" is a graph query over effect declarations, not a runtime observation about absence of network calls. The mechanisms that make this property reliable — mandatory effect declarations, capability mediation, content-addressed identity, per-node encryption, attestation — are the subject of earlier documents; this document explains how they compose into a coherent defense.

Resolves [Q-005](../open-questions.md#Q-005) (confused deputy synthesis), [Q-006](../open-questions.md#Q-006) (foreign binding trust), [Q-012](../open-questions.md#Q-012) (TEE attestation), and [Q-013](../open-questions.md#Q-013) (obfuscation) as proposed designs.

## Threat model {#threat-model}

The security design considers six categories of adversary and the harms each is positioned to cause. The threats are listed in approximate order of frequency for the agent-generation use case; the design defends against all of them but the relative emphasis varies.

**Malicious AI agent.** An AI generating Strand graphs may attempt to construct programs that exfiltrate data, escalate privilege, or perform unauthorized actions on behalf of the agent's operator or as a result of prompt injection. The defense is that an agent cannot construct a graph that performs effects the principal's capability context does not authorize: a graph that requires `Filesystem.Read{path: "/etc/passwd"}` is rejected at admission if the context does not grant that capability. Capability-mediated execution is the primary defense.

**Compromised principal.** A principal's private key may be stolen; an attacker holding the key can act with the principal's full authority within the principal's capability context. The defense is key revocation (specified in [encryption-model.md](encryption-model.md)), capability scoping that limits the harm any specific operation can cause, and observability that surfaces unusual activity to operators. The defense is not absolute: a compromised principal retains the principal's authority until the compromise is detected and the key revoked.

**Untrusted worker.** Distributed execution places parts of a graph on workers the principal does not fully trust (a public compute service, a peer in a federation, a delegated executor). The worker can observe, modify, or omit operations on the parts of the graph it holds. The defense is per-node encryption ([encryption-model.md](encryption-model.md)): subgraphs the principal wishes to keep confidential are encrypted with envelopes the worker cannot decrypt; the worker routes and stores the encrypted nodes but does not see their contents. For computations whose outputs the worker can be expected to produce honestly (the worker has the right capabilities and is incentivized to comply), encryption is sufficient. For computations where the worker may lie about the result, additional verification is required (verifiable computation, redundant execution with consensus, or TEE-based isolation are the relevant techniques; full coverage is beyond the immediate scope).

**Network attacker.** An attacker on the network between graph holders can observe or modify in-flight graphs. The defense is that envelopes are authenticated (the AEAD construction detects modification) and that confidentiality-required nodes are encrypted in transit by virtue of their on-disk encryption. The graph protocol itself uses authenticated transport (TLS or equivalent) for additional metadata protection. A pure observer with no key material learns the topology of the graph and the interface declarations of encrypted nodes; this is the residual leakage discussed under obfuscation.

**Compromised foreign code.** A ForeignNode binding may declare effects that the foreign code does not actually perform, or may perform effects beyond what it declares. The defense is the foreign-binding trust model specified below: provenance signing, reproducible binding generation, registry curation, and runtime sandbox observation. The defense is not perfect; misdeclared bindings remain the largest residual attack surface for graphs that use FFI.

**Malicious model provider.** The provider of an AI model used to generate Strand graphs may attempt to learn the structure of generated programs by retaining traces, by inferring patterns across many users' interactions, or by submitting prompts that elicit specific design decisions. The defense is compositional opacity (no single agent sees the whole graph), per-node encryption that makes subgraphs opaque to agents lacking the relevant keys, and operational discipline about what is exposed to which agents. This threat is partially addressed and is the subject of [Q-022](../open-questions.md#Q-022), which is currently deferred.

**Insider.** A legitimate participant who has been granted capabilities may misuse them. The defense overlaps with the compromised-principal case: capability scoping minimizes blast radius, revocation enables containment, and audit trails support detection. The design does not solve the insider problem; no language-level mechanism can, and the defense is operational.

## Attack surfaces {#attack-surfaces}

The attack surfaces of a Strand deployment are:

- The graph admission boundary, where new graphs are constructed by agents and submitted to the runtime.
- The capability grant boundary, where capabilities are conferred on execution contexts at runtime startup or at TEE attestation time.
- Foreign nodes and their bindings, where Strand-side guarantees meet external code that the verifier cannot independently audit.
- Encrypted nodes whose interface declarations are trusted; an envelope that misdeclares its effects is an attack.
- The distribution channel between workers, where graphs traverse network paths under adversary observation.
- The persistent storage where graphs and their envelopes reside; physical access to storage is equivalent to network observation for envelopes whose recipients do not include the holder.
- The runtime itself: a compromised Strand runtime can ignore capability checks, expose key material, or lie about effect closures. Runtime integrity is the foundation on which other defenses rest.

The security argument is per-surface. The mechanisms below address each surface in turn.

## Defenses by mechanism {#defenses}

**Mandatory effect declarations** ([ADR-004](../decisions/ADR-004-effects-as-edges.md)) cover the graph admission surface. Effects cannot be omitted; the verifier rejects any graph whose declared effects are inconsistent with its closure or whose closure exceeds the calling context's capabilities.

**Capability mediation** ([effects-and-capabilities.md](effects-and-capabilities.md)) covers the runtime-execution surface. Every effectful operation is checked against the capability context at the point of execution; capability mismatches halt execution before the effect is performed. Capabilities are non-forgeable and flow according to the rules in the effects-and-capabilities specification.

**Content addressing** ([ADR-003](../decisions/ADR-003-content-addressing.md)) provides tamper resistance for graphs at rest and in transit. A graph whose root hash matches an expected value is unchanged from the moment that hash was computed; any modification is detectable.

**Per-node encryption** ([encryption-model.md](encryption-model.md)) covers the untrusted-worker and persistent-storage surfaces. Encrypted nodes are routed, stored, and replicated without their contents being legible to parties who do not hold appropriate keys.

**Provenance metadata and signing** support the foreign-binding and graph-admission surfaces. A graph or binding signed by a trusted authority can be admitted with capabilities that an unsigned graph would not receive.

**TEE attestation** ([encryption-model.md](encryption-model.md), and below) supports execution-environment integrity: a TEE attests to its launched workload identity, and capabilities can be conditional on the attestation. A graph that requires a sensitive capability can be admitted only into an execution context that the relying party has attested to.

**Sandboxing** for foreign code limits the harm misdeclared bindings can cause. WebAssembly modules executed under a runtime that enforces declared imports cannot perform undeclared syscalls; native libraries executed under seccomp or in restricted processes have similar (weaker) constraints.

**Audit and observability** are operational rather than algorithmic. The runtime emits structured logs and metrics; persistent recording of graph admissions, capability grants, and effect violations supports detection of unusual activity. The design specifies what is observable; the operational policy specifies what is monitored.

## Foreign binding trust {#foreign-binding-trust}

[Q-006](../open-questions.md#Q-006) asks how a Strand deployment decides whether to admit a ForeignNode binding. The chosen design layers four mechanisms.

**Signed provenance.** A binding carries a signature by its publisher. The signature covers (a) the binding's declared interface (function signature, declared effects, declared capability requirements), (b) the foreign target identifier (library, version, symbol), and (c) optional source-of-origin metadata. The runtime is configured with a set of trusted signers; a binding signed by a trusted signer is admissible.

**Reproducible binding generation.** For libraries with available source, a binding can be generated reproducibly from canonical inputs (the source, the WIT interface description, the platform target). Two independent regenerations of the binding from the same inputs produce byte-identical bindings (and therefore identical hashes). A relying party can confirm a binding's provenance by re-generating from sources and matching the hash. Reproducible generation is the mechanism by which open-source binding ecosystems can be trusted without a single central signing authority.

**Curated registry.** A registry of vetted bindings serves the role of a package repository: it holds bindings whose effect declarations have been reviewed and accepted by the registry's curators. A deployment configured to trust the registry admits any binding it hosts. The registry is itself a Strand graph (content-addressed nodes) whose root is signed by the registry's authority.

**Runtime sandbox observation.** A binding executed under a sandbox (WebAssembly, seccomp-restricted process, TEE) can be observed at the sandbox boundary. The runtime checks that the observed syscall set is consistent with the binding's declared effects; violations are detected and treated as binding-contract violations. This is the runtime safety net for environments where pre-admission trust is uncertain.

These mechanisms are complementary, not alternatives. A deployment may require all four for high-sensitivity bindings, or only one for development use. The configuration is operational policy; the language specifies the mechanisms.

## TEE attestation chain {#tee-attestation}

[Q-012](../open-questions.md#Q-012) asks how Strand's content-addressed graph identity maps onto TEE attestation primitives. The chosen design treats attestation as a structured chain whose links can be examined by verifiers.

The chain has four levels:

**Platform identity.** The underlying hardware platform (Intel TDX, AMD SEV-SNP, ARM CCA, AWS Nitro, etc.) has an attestation key whose root of trust is the platform manufacturer. Quotes signed by this key attest that the platform itself is genuine.

**Launched-workload measurement.** When a TEE is launched, the platform measures the loaded image (code + initial data + configuration) and includes this measurement in attestation quotes. A relying party that knows what measurement to expect can verify that the running workload is the intended one.

**Strand-runtime identity.** The Strand runtime running inside the TEE has its own identity: a content hash over its binary form. The runtime's identity is part of the launched-workload measurement and can be verified independently.

**Graph identity.** A graph executing in the TEE has a content hash. The runtime can include the graph's root hash in attestation responses, enabling a relying party to verify both that a specific runtime is executing in a specific TEE *and* that a specific graph is being evaluated.

The full attestation chain ties (platform identity → workload measurement → runtime identity → graph identity) into a single verifiable claim: this platform, attested by the manufacturer, is running this measured Strand runtime, which is evaluating this specific graph. Capabilities granted by the relying party can be conditional on this claim being verified.

The protocol for presenting attestations as capabilities is straightforward: a `Trust.Attestation{scheme: ...}` capability is conferred by the runtime when it has verified a quote matching the policy the principal specified. The graph can then perform attestation-bound operations (TEE-sealed storage access, attestation-bound key decryption) because it holds the requisite capability.

Cross-platform attestation portability is achieved by adopting a uniform abstraction (the IETF RATS architecture and its evidence/attestation-result distinction provides a reference) and implementing platform-specific verification logic behind it. The reference implementation supports one platform initially; cross-platform support follows.

## Confused deputy synthesis {#confused-deputy}

[Q-005](../open-questions.md#Q-005) is addressed in detail in [effects-and-capabilities.md](effects-and-capabilities.md). The synthesis: parameter-tagged capabilities, capability minimization at scope entry, and argument provenance checks together reduce the confused-deputy attack surface to cases that are application-level design problems rather than language-level vulnerabilities.

The point of synthesis is that confused deputy is fundamentally about authority being broader than necessary. The language provides the mechanism for narrowing authority (CapabilityScope, parameter-tagged capabilities); the application's design must use these mechanisms to express its actual authority requirements. A program that holds `Filesystem.Write{path: *}` because the programmer was lazy is vulnerable; a program that holds `Filesystem.Write{path: "/var/log/app.log"}` because the design called for that capability specifically is not vulnerable to confused-deputy through the path parameter.

The language cannot enforce careful design; it can only make careful design expressible. The expressiveness gap that conventional languages exhibit (no way to express "this code may write only this file") is closed in Strand. The remaining gap is the discipline gap.

## Obfuscation {#obfuscation}

[Q-013](../open-questions.md#Q-013) asks about the strength of obfuscation guarantees from subgraph encryption, hash-only metadata, and semantic-preserving transformations.

The design's position is conservative. Per-node encryption ([encryption-model.md](encryption-model.md)) provides cryptographic confidentiality for node contents to parties lacking decryption keys. The confidentiality is strong: under standard cryptographic assumptions, the ciphertext does not leak information about the plaintext beyond its length and (subject to the guess-and-verify caveat) its hash.

However, the graph *topology* — which nodes reference which, the type-level structure of public interface declarations, the number and category of effects declared — is not encrypted. An observer who sees an encrypted graph learns about its overall structure even without decrypting any node. This residual leakage is acceptable for most threat models but is not adequate against adversaries who can infer significant information from topology alone (e.g., distinguishing a graph that implements algorithm A from one that implements algorithm B based on the shape of the computation).

Stronger obfuscation requires structural transformations that preserve semantics while changing topology: insertion of decoy nodes, splitting of large nodes into many smaller ones, merging of small nodes into composite forms, randomization of edge orderings within nodes (subject to canonical encoding constraints). These transformations are possible and would be valuable for sensitive applications; their formal specification, their guarantees against various adversary models, and their interaction with the verifier are open research directions and are not part of the current design.

The design's commitment is that *node contents* are protected by per-node encryption when applied; the protection of *graph topology* is treated as a future research direction. Deployments with strong topology-confidentiality requirements should treat the present design as a starting point and assess the topology leakage relative to their threat model.

## Limits and known gaps {#limits}

The security model has several acknowledged limits that operators should consider when deploying Strand.

**Runtime integrity is foundational.** All defenses presume the runtime is honest. A compromised runtime — one that has been modified to ignore capability checks, or whose interpretation of bytecode differs from the specification — defeats every higher-level mechanism. Defenses against runtime compromise (signed runtime binaries, attestation of the runtime as part of the platform attestation chain, redundant execution with consensus) are available but require operational configuration.

**Side channels are out of scope.** Timing, power, cache, and other side channels in the execution of effect-mediated code may leak information that the language-level model does not address. Mitigation is the platform's responsibility (constant-time cryptographic implementations, cache partitioning, etc.).

**Confused-deputy discipline gap.** As discussed, the language provides mechanisms but cannot enforce their use. A program with over-broad capabilities is still over-broad even though the language could express tighter scoping.

**Foreign code reach.** A foreign binding's declared effects are trusted under the trust model; if the binding lies and the runtime sandbox cannot detect the lie (because the platform offers weak sandboxing for native code), the lie succeeds. The mitigation is to prefer sandbox-supporting foreign targets (WebAssembly) over weakly-sandboxed alternatives (native processes with seccomp only), and to favor reproducible bindings over hand-curated ones.

**Quantum resistance is not yet built in.** The cryptographic primitives ([encryption-model.md](encryption-model.md)) are standard pre-quantum choices. A future Strand will need to migrate to post-quantum primitives as standards mature; the multi-hash and algorithm-identifier formats accommodate migration but the migration itself has not been specified.

**Agent intent confidentiality is partial.** [Q-022](../open-questions.md#Q-022) acknowledges this and is currently deferred. Operators who require strong agent-intent confidentiality should layer additional mechanisms (compositional opacity across multiple agents, differential privacy in traces) on top of the current design.

These limits are not failures of the design; they are the boundary between what the language can guarantee structurally and what depends on operational discipline, platform features, or research directions outside the immediate scope.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — capability execution and security claims
- [`ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — tamper resistance
- [`ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — effect mediation
- [`ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — foreign trust model
- [`ADR-006-per-node-encryption.md`](../decisions/ADR-006-per-node-encryption.md) — encryption integration
- [`effects-and-capabilities.md`](effects-and-capabilities.md) — capability mediation and confused deputy
- [`encryption-model.md`](encryption-model.md) — encryption envelope and TEE protocol
- [`distribution-model.md`](distribution-model.md) — defenses across worker boundary
- [`open-questions.md`](../open-questions.md) — Q-005, Q-006, Q-012, Q-013 addressed here

**Incoming references:**
- [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md)
- [`effects-and-capabilities.md`](effects-and-capabilities.md)
- [`encryption-model.md`](encryption-model.md)
- [`distribution-model.md`](distribution-model.md)
- [`research-plan.md`](../research-plan.md)
- [`rendering-and-views.md`](rendering-and-views.md) — trust model extended to invariant checkers
- [`evaluation/containment-results.md`](../evaluation/containment-results.md) — Q-044 containment measurement operationalizing this threat model
