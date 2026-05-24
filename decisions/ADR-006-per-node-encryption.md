# ADR-006: Per-Node Encryption with Multi-Key Support {#adr-006}

**Document:** `decisions/ADR-006-per-node-encryption.md`
**Status:** Accepted
**Date:** 2026-05-23
**Supersedes:** none
**Superseded by:** none

## Context {#context}

Strand graphs are content-addressed ([ADR-003](ADR-003-content-addressing.md)) and execute under capability mediation ([ADR-004](ADR-004-effects-as-edges.md)). These properties together enable a security model that does not exist in conventional languages: individual nodes in a program can be encrypted such that only execution contexts holding specific capabilities can decrypt them.

The use cases for per-node encryption are concrete. Distributed execution requires sending parts of a computation to workers that the program's principal does not fully trust; per-node encryption allows the workers to route and store the work without seeing its content. Multi-party computation requires that participants contribute code without revealing it to each other; per-node encryption makes this a graph-construction primitive rather than a separate protocol. Trusted Execution Environment integration requires that code in the TEE be available there but not outside; per-node encryption tied to TEE attestation provides exactly this. AI agent intent confidentiality, where the program's structure should not be learnable by the model provider or by other observers, becomes a structural property of the graph rather than a layered protocol.

Several questions must be answered for the design to be coherent. What does a node's content hash cover when the node is encrypted? How are multiple decryption keys associated with a single node? How does decryption interact with capability mediation? What role does Fully Homomorphic Encryption (FHE) play, given that practical FHE for general computation remains expensive and limited? How does the graph remain navigable when subgraphs are opaque to the navigator?

The decision adopted here specifies the encryption model at the level required for the security guarantees promised in [`02-core-thesis.md`](../02-core-thesis.md#claim-capability-execution), deferring some details to [`design/encryption-model.md`](../design/encryption-model.md) and to open questions on key management ([Q-011](../open-questions.md#Q-011)).

## Decision {#decision}

A Strand node has a canonical identity: the cryptographic hash of its plaintext canonical form, as specified in [ADR-003](ADR-003-content-addressing.md). Encryption does not change this identity. A node's hash is the same whether the node is stored in plaintext or encrypted form, so references to the node remain valid across the encrypted/decrypted boundary.

When a node is encrypted, its plaintext canonical form is wrapped in an *encryption envelope*. The envelope contains: the ciphertext (the encrypted plaintext form), one or more wrapped session keys (each session key encrypted under the public key or symmetric key of an authorized recipient), an unencrypted *interface declaration* sufficient for the verifier and the runtime to use the node without decrypting it (its type, declared effects, capability requirements), and provenance metadata. The envelope is the on-disk and on-wire representation of an encrypted node; the canonical hash is the hash of what is inside the envelope, not of the envelope itself.

Decryption is capability-mediated. A capability that authorizes decryption is a key — either a symmetric secret, a private key, or an attestation-based unsealing capability provided by a Trusted Execution Environment. The runtime, at the point where it must use an encrypted node's body (typically: evaluate a function defined by the node), checks whether its capability context holds a key that unwraps one of the envelope's session keys. If yes, it decrypts and proceeds; if no, evaluation halts with a missing-capability error.

Multi-key support is the standard multi-recipient encryption pattern: an envelope may carry many wrapped session keys, each addressed to a distinct recipient. The unencrypted interface declaration is shared by all recipients; the encrypted body decrypts to the same plaintext for any recipient that successfully unwraps a session key. Adding a new recipient to an existing encrypted node requires possession of one existing key (to derive the session key) and produces a new envelope with the additional wrapped session key. The canonical hash does not change because the plaintext does not change; the envelope is replaced but the node's identity is preserved.

Interface declarations are visible without decryption. The verifier can construct a graph that references an encrypted node, propagate its declared effects through the effect closure, and confirm capability sufficiency, all without holding any decryption key. The interface declaration is a contract that the node's body satisfies; the runtime confirms this contract when decryption occurs. Violations (the body's actual behavior exceeds the declared interface) are runtime errors in the same category as ForeignNode contract violations ([ADR-005](ADR-005-foreign-nodes.md)).

The hash-versus-confidentiality tradeoff is acknowledged. Because the canonical hash is over the plaintext form, a party who can guess the plaintext can verify the guess by computing its hash. For high-entropy code (most functions, most type definitions), this is a non-issue; for low-entropy or guessable plaintexts (constants, small finite tables, secrets that should not be in the graph in the first place), the tradeoff is real. Nodes that require resistance to guess-verification can carry a high-entropy nonce as part of their canonical form, at the cost of losing deduplication for those nodes. The default is no nonce; nonces are opt-in for nodes whose plaintext is sensitive and guessable.

Fully Homomorphic Encryption is not assumed by the design. FHE schemes that enable general computation on ciphertext exist but are impractical at the throughput and latency Strand targets. The execution model is "decrypt to evaluate, mediated by capability," not "compute without decrypting." FHE-on-Strand-subgraphs is a possible future extension and is mentioned in the integration section of [`02-core-thesis.md`](../02-core-thesis.md#integration); the design adopted here does not depend on it.

## Alternatives considered {#alternatives}

Four alternatives were evaluated and rejected.

**No encryption; rely on analysis-tool access control.** The runtime grants or denies access to graph data based on capability checks at the API level, with the underlying graph stored unencrypted. This is simpler but provides no defense against storage-level access (a worker that holds the graph file can read it directly, bypassing the API). The distribution use case — sending parts of a computation to untrusted workers — requires encryption to be cryptographic, not API-mediated. The trust boundary for Strand crosses storage and transport, not only execution APIs.

**Hash-of-ciphertext (envelope-as-identity) model.** A node's identity is the hash of its envelope, not of its plaintext. Re-encrypting the node (e.g., to add a recipient) produces a new node with a new identity. This makes encryption a confidentiality-preserving operation in the strict sense (no information leaks through hash collisions for guessed plaintexts), but it destroys deduplication: the same plaintext encrypted with different keys is now different content from the system's perspective. Reference stability is also lost: a reference to a node points to a specific envelope; re-encrypting the node breaks the reference. The tradeoff is unfavorable for the use cases Strand targets, where content-addressed identity is foundational and adding recipients is a frequent operation.

**Two-hash model (plaintext hash and ciphertext hash both as identifiers).** A node has two identities and references may use either. This solves the recipient-rotation problem but doubles the identity space, complicates analysis (which hash is canonical?), and creates a category of bugs where the two hashes get out of sync. The single canonical hash with envelope wrapping is strictly simpler.

**Coarse-grained encryption (whole graphs encrypted as opaque blobs).** Instead of per-node encryption, an entire graph is sealed with a single key and either decrypted entirely or not at all. This is much simpler to implement but loses the per-node selectivity that gives Strand its distinctive property. Distribution to untrusted workers requires that the worker route and execute parts of the graph without seeing the parts the principal wishes to keep confidential; coarse-grained encryption means the worker either sees everything or nothing.

## Consequences {#consequences}

Encrypted nodes participate fully in the graph. They can be referenced, included in effect closures, scheduled for execution, and verified against capability requirements, all without decryption. The encrypted-versus-plaintext distinction is a property of the storage and transport layer; the graph-shaped program is the same in both cases.

Capability management becomes the operational hub. The runtime that executes a Strand graph must hold the keys required for whatever subgraphs the graph reaches. Key acquisition is mediated by the same capability system that governs effect access: a key is a capability, and granting it follows the same delegation rules as any other capability ([Q-004](../open-questions.md#Q-004)). The key management story — generation, distribution, rotation, revocation — is the principal operational concern for production deployments and is the subject of [Q-011](../open-questions.md#Q-011).

TEE integration is structural. A TEE is an execution context that holds attestation-based capabilities. An encryption envelope can be addressed to a TEE's attestation key: only a process running in the attested TEE can unwrap the session key. The graph carries the attestation-binding metadata; the runtime in the TEE performs the unsealing. This composes with the rest of the model: a graph that includes a TEE-encrypted subgraph is a graph that requires a TEE-attestation capability to execute that subgraph. The protocol for what is attested, how the attestation is presented as a capability, and how attestations are revoked is open ([Q-012](../open-questions.md#Q-012)).

The interface-declaration mechanism enables type-safe interaction with encrypted code. A graph that calls into an encrypted function receives the function's declared type, declared effects, and capability requirements without seeing its body. This is similar to opaque types or sealed modules in conventional languages, with the distinction that the opacity is cryptographic and the interface is part of the encrypted node's envelope. Misuse of the interface (calling with arguments outside the type, expecting effects not in the declaration) is caught by the verifier; violations of the declaration by the encrypted code itself are caught by runtime check.

The verifier's effect closure relies on declared interfaces being honest. An encrypted node whose declared interface understates its effects breaks the closure computation in the same way that a misdeclared ForeignNode does ([ADR-005](ADR-005-foreign-nodes.md)). The trust model for encrypted nodes overlaps with the trust model for foreign bindings: provenance, signing, attestation, and observability at the boundary all apply. The encryption itself does not create the trust problem (a non-encrypted node could also misdeclare its interface), but encryption makes the trust problem harder to inspect because the body is unreadable to the verifier.

Obfuscation guarantees beyond per-node encryption are open ([Q-013](../open-questions.md#Q-013)). Encrypting individual node bodies protects their contents but does not protect the graph structure: an observer who sees the graph topology (which nodes reference which, how many nodes, what types they declare) learns information about the program even without decrypting anything. Stronger obfuscation — restructuring the graph to obscure its topology, padding with decoy nodes, structural homomorphisms that preserve semantics — is possible but not specified by this ADR.

The encryption model interacts with analysis tooling ([ADR-002](ADR-002-no-human-projection.md)). Analysis queries that traverse encrypted nodes report the declared interface but not the body. Diff between two graphs that differ only in encrypted node bodies reports a difference (the canonical hash changed) without revealing what differs. Forensic inspection of an encrypted graph requires the relevant capabilities, granted under the same policy that governs execution. This is consistent with the analysis-tooling-not-text-rendering decision: the tooling respects the same capability boundary as the runtime.

The threat of agent intent learning is partially addressed. The hypothesis in [Q-022](../open-questions.md#Q-022) — that defending against AI agents themselves learning detailed knowledge of generated programs is interesting but deferred — is partially addressed by per-node encryption: an agent that does not hold decryption capabilities for subgraphs it does not need cannot learn their contents from interaction. Full defense against intent learning requires additional mechanisms (compositional opacity, differential privacy in agent traces) that are not part of this ADR.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — Claim 4 (content addressing) and Claim 5 (capability execution)
- [`ADR-002-no-human-projection.md`](ADR-002-no-human-projection.md) — interaction with analysis tooling
- [`ADR-003-content-addressing.md`](ADR-003-content-addressing.md) — hash construction over plaintext form
- [`ADR-004-effects-as-edges.md`](ADR-004-effects-as-edges.md) — capability mediation
- [`ADR-005-foreign-nodes.md`](ADR-005-foreign-nodes.md) — interface declarations and trust model
- [`design/encryption-model.md`](../design/encryption-model.md) — detailed key and envelope specification
- [`design/security-model.md`](../design/security-model.md) — threat model and TEE protocol
- [`open-questions.md`](../open-questions.md) — Q-004, Q-011, Q-012, Q-013, Q-022

**Incoming references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — cites this ADR from the integration section
- [`ADR-002-no-human-projection.md`](ADR-002-no-human-projection.md) — references encryption interaction
- [`ADR-003-content-addressing.md`](ADR-003-content-addressing.md) — defers encryption-hash interaction here
- [`ADR-008-compilation-target.md`](ADR-008-compilation-target.md) — encrypted-node handling in the VM
- [`design/encryption-model.md`](../design/encryption-model.md) — detailed envelope and lifecycle
- [`design/security-model.md`](../design/security-model.md) — encryption integration
