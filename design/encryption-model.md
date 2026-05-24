# Encryption Model {#encryption-model}

**Document:** `design/encryption-model.md`
**Status:** Wave 3 draft
**Last revised:** 2026-05-23

## Summary

This document specifies the per-node encryption mechanism for Strand, building on [ADR-006](../decisions/ADR-006-per-node-encryption.md). It defines the envelope structure, the key types and their lifecycle, the multi-recipient protocol, the interaction with content addressing, the integration with Trusted Execution Environments, and the operational concerns of generation, distribution, rotation, and revocation.

The design is conservative in its cryptographic primitives. It uses widely-deployed, well-analyzed algorithms (AES-256-GCM, X25519, Ed25519, BLAKE3) and uses them in standard constructions. The novelty of Strand's encryption story is not the primitives but their integration with content-addressed graph identity and with capability-mediated execution.

Resolves [Q-011](../open-questions.md#Q-011) (key management) as a proposed design. Connects to [Q-012](../open-questions.md#Q-012) (TEE attestation chain) which is specified in [security-model.md](security-model.md).

## Envelope structure {#envelope}

An encrypted node is represented on disk and on the wire as an *encryption envelope*: a byte structure containing the ciphertext, the metadata needed to decrypt it, and the unencrypted interface declaration that lets the verifier and the runtime use the node without decryption. The envelope is the encrypted-node serialization; the canonical hash of the underlying node is over its plaintext form, as specified in [ADR-006](../decisions/ADR-006-per-node-encryption.md).

The envelope layout (binary, little-endian for multi-byte integers):

| Field | Size | Description |
|-------|------|-------------|
| version | 1 byte | Envelope format version (currently 0x01) |
| plaintextHash | 33 bytes | Multi-hash of the plaintext canonical encoding (1-byte function ID + 32-byte digest) |
| interfaceDeclaration | varlen | Serialized interface: node type, declared effects, capability requirements, declared output types |
| recipientCount | 2 bytes | Number of recipients (max 65535) |
| recipients[recipientCount] | varlen | One recipient block per recipient |
| algorithmId | 2 bytes | Symmetric encryption algorithm identifier |
| nonce | 12 bytes | AEAD nonce |
| ciphertextLength | 4 bytes | Length of ciphertext in bytes |
| ciphertext | varlen | Symmetric-encrypted plaintext canonical encoding |
| authTag | 16 bytes | AEAD authentication tag covering ciphertext, plaintextHash, and interfaceDeclaration |

Each recipient block:

| Field | Size | Description |
|-------|------|-------------|
| keyIdLength | 1 byte | Length of the key identifier |
| keyId | varlen | Key identifier (typically a key fingerprint) |
| wrapAlgorithm | 2 bytes | Key wrap algorithm identifier |
| wrappedKeyLength | 2 bytes | Length of the wrapped session key |
| wrappedKey | varlen | Session key wrapped under the recipient's key |

The plaintextHash is included in the envelope so that a decrypting holder can confirm that the decrypted body hashes to the expected canonical identity. The interfaceDeclaration is unencrypted and is part of the AEAD's associated data, so that any tampering with it is detected.

## Algorithms {#algorithms}

The reference implementation uses:

- *Symmetric encryption*: AES-256-GCM (algorithmId 0x0001) by default; ChaCha20-Poly1305 (algorithmId 0x0002) as an alternative for environments where AES hardware acceleration is unavailable.
- *Key encapsulation for asymmetric recipients*: X25519 ECDH + HKDF-SHA-256 + AES-256-GCM key wrap (wrapAlgorithm 0x0001).
- *Symmetric key wrap for symmetric recipients*: AES-KWP (RFC 5649) (wrapAlgorithm 0x0002).
- *TEE-bound key wrap*: a platform-specific wrap that delivers the session key to a process attesting to a specified measurement (wrapAlgorithm 0x0010 through 0x001F, one per supported TEE platform).
- *Signature for provenance*: Ed25519 (when ProvenAnce edges sign attestations).
- *Hash*: BLAKE3 (matches [ADR-003](../decisions/ADR-003-content-addressing.md)) for plaintextHash; SHA-256 supported as an alternative under the multi-hash prefix.

These choices are not innovative; they reflect the consensus of mid-2020s applied cryptography. Future revisions may add post-quantum alternatives (Kyber for KEM, Dilithium for signing) as the standards mature.

## Key types and identity {#key-types}

Strand recognizes four kinds of keys, each with different operational properties:

**Principal keys** identify a participant (a human, an organization, an agent, a service). They are asymmetric key pairs whose public key fingerprint serves as the participant's stable identifier. Principal keys are typically long-lived (months to years) and are stored in hardware tokens, key management systems, or TEE-sealed storage. The private key never enters a Strand graph; only the public key fingerprint appears, in recipient blocks of envelopes addressed to the principal.

**Session keys** are ephemeral symmetric keys used for the actual encryption of a node's plaintext. Each encrypted node has a fresh session key. The session key is wrapped once per recipient and stored in the envelope; only recipients with appropriate principal keys can unwrap it.

**Attestation-bound keys** are key pairs held inside a TEE. The private key is sealed to a specific measurement (the TEE's launched workload identity). The public key, accompanied by an attestation document proving its TEE provenance, can be used as a recipient. Only a process running in the attested TEE can unwrap session keys addressed to the attestation-bound key.

**Symmetric wrap keys** are pre-shared symmetric keys used between known parties (typically for performance or for use cases where asymmetric cryptography is impractical, such as embedded systems). They are addressed in envelopes by symmetric wrap (AES-KWP).

Keys are identified in envelopes by *fingerprints*: a 32-byte hash of the public key (for asymmetric) or of the key material (for symmetric). Two keys with the same fingerprint are the same key. Fingerprints are stable; rotation produces a new key with a new fingerprint, not a change to an existing fingerprint.

## Multi-recipient encryption {#multi-recipient}

An envelope may carry many recipient blocks, each with a distinct key identifier and a distinct wrapped session key. The session key is the same across all recipients; the wrapping differs per recipient.

Encryption process:

1. Generate a fresh 32-byte session key K (cryptographically random).
2. Serialize the plaintext canonical encoding P.
3. Compute the plaintextHash H = BLAKE3(P).
4. Serialize the interface declaration I.
5. Build the AEAD associated data A = version || H || I || recipientCount || recipients.
6. Encrypt: (ciphertext, authTag) = AES-256-GCM(K, nonce, P, A).
7. For each recipient with public key R: wrappedKey = X25519-DH-AES-KW(R, K), or appropriate wrap for the recipient's key kind.

Decryption process for a recipient holding the matching private key:

1. Locate the recipient block matching the holder's key fingerprint.
2. Unwrap the wrappedKey using the holder's private key to recover K.
3. Verify the AEAD authentication: AES-256-GCM-Decrypt(K, nonce, ciphertext, authTag, A) yields the plaintext P or fails.
4. Confirm BLAKE3(P) == H; reject the envelope if not (this catches partial substitution attacks).
5. Use P as the node's plaintext canonical encoding.

Adding a recipient to an existing envelope:

1. Possess one of the existing recipient slots (to extract the session key K).
2. Unwrap K using the held private key.
3. Wrap K under the new recipient's public key, producing a new recipient block.
4. Construct a new envelope with the new recipient added. The plaintextHash, interfaceDeclaration, nonce, ciphertext, and authTag are unchanged; the recipientCount and recipients[] are updated; the AEAD associated data changes, but recomputation is unnecessary because the AEAD verification covers the (immutable) ciphertext and other immutable fields, with the recipients portion treated separately.

Actually — the AEAD covers the recipients list as part of associated data. Adding a recipient changes the associated data and thus invalidates the original authTag. The correct procedure is to re-encrypt with the same key and nonce, producing a new authTag for the new associated data. The session key may be reused safely because the (key, nonce) pair encrypts the same plaintext. (Avoiding nonce reuse across distinct plaintexts is essential; reuse with the same plaintext is acceptable.)

This adds a re-authentication cost per recipient addition; the cost is constant in the plaintext size if implementations expose AEAD interfaces that re-authenticate without re-encrypting. For the reference implementation, the simpler path is to construct a new envelope, accepting the re-encryption cost for envelopes that frequently add recipients.

## Hash semantics under encryption {#hash-semantics}

The canonical hash of an encrypted node is the hash of its plaintext canonical form. This hash is the node's identity in the graph; references to encrypted nodes use this hash.

This choice has implications:

- *Deduplication is preserved.* Two encrypted nodes with the same plaintext have the same canonical hash, regardless of which keys encrypt them. The graph deduplicates them; the underlying envelope may differ across instances of the same node.

- *Reference stability across rotation.* When a node is re-encrypted (e.g., to add or remove recipients during key rotation), the envelope changes but the plaintext does not; the hash is stable; references continue to resolve.

- *Hash-as-confirmation.* A holder who decrypts an envelope confirms the plaintext is what was expected by checking the hash. A holder who does *not* hold a key receives only the envelope; the canonical hash is included in the envelope (as `plaintextHash`) but the plaintext is not.

- *Guess-and-verify exposure.* An attacker who can guess the plaintext of an encrypted node can verify the guess by computing its BLAKE3 hash and matching against `plaintextHash`. For high-entropy code, this is not a practical attack. For low-entropy plaintexts (constants, small tables, predictable strings), the exposure is real.

The mitigation for guess-and-verify is the optional nonce field in the node's canonical encoding. A node whose plaintext requires resistance to guessing carries a high-entropy nonce as part of its content fields; the canonical encoding includes the nonce; the hash is over (nonce + content). This sacrifices deduplication (two nodes with the same content but different nonces hash differently) for confidentiality of the hash itself. Use of the nonce is opt-in per node; the default is no nonce, which preserves deduplication.

## Decryption capability flow {#capability-flow}

A key holder *holds a capability* to decrypt envelopes addressed to that key. The capability mechanism specified in [effects-and-capabilities.md](effects-and-capabilities.md) governs decryption: a capability of category `Crypto.Decrypt{keyId: K}` permits decryption of envelopes whose recipient list includes K.

The runtime, upon encountering an encrypted node in a graph that must be evaluated, checks the capability context for a matching `Crypto.Decrypt` capability. If present, decryption proceeds; if not, evaluation halts with a missing-capability error.

The capability is not the key itself. It is a runtime authorization to use the key through whatever back-end manages it (hardware token, TEE, key management service). The graph never holds the raw key material; the runtime mediates.

Capabilities flow according to the standard rules in [effects-and-capabilities.md](effects-and-capabilities.md): implicit within a graph evaluation, narrowed through CapabilityScope, explicit across foreign and encrypted boundaries. A graph that calls into an encrypted subgraph does not automatically grant the encrypted code its calling capabilities; the encrypted node's interface declares which capabilities it requires, and the runtime confirms the calling context provides them.

## Key lifecycle: generation {#key-generation}

Principal keys are generated outside the language, by a key management system the principal trusts (a hardware token, a TEE, a vetted KMS service). Generation produces an asymmetric key pair; the public key is exported and registered with whatever directory the principal participates in; the private key remains in the generation context.

Attestation-bound keys are generated inside a TEE at launch time. The TEE produces a quote that binds the public key to the TEE's measured identity; the quote is signed by the TEE platform's attestation key. Verifying the quote (against the platform's attestation infrastructure) confirms that the key is held by an enclave matching the expected measurement.

Session keys are generated at encryption time. The runtime uses a cryptographically-secure pseudorandom generator (CSPRNG) seeded from platform entropy sources. Session keys are not persisted; they are derived per-encryption and discarded after use.

Symmetric wrap keys are generated by whichever party originates them, distributed by some out-of-band mechanism (a secure channel, a manual exchange, a key derivation from a shared secret). The language does not specify the establishment protocol; it specifies the use.

## Key lifecycle: distribution {#key-distribution}

Public keys (principal and attestation-bound) are distributed through the same mechanisms used in conventional cryptographic systems: directories, certificates, manual exchange, or in-band as part of attestation documents. The language does not require a specific PKI; a graph that references a public key does so by fingerprint, and the runtime resolves the fingerprint to the corresponding key material through whatever directory the runtime is configured to consult.

Private keys are never distributed. They are generated where they will be used; if a private key needs to be available in multiple contexts (e.g., a service running in multiple TEEs), each context generates its own key pair and the public keys are aggregated into a multi-recipient envelope.

For principal keys held in key management services, the "distribution" is by reference: a graph cites a key by fingerprint; the runtime authenticates to the KMS using whatever authentication scheme the KMS requires; the KMS performs operations on the key on the runtime's behalf without revealing the key material.

## Key lifecycle: rotation {#key-rotation}

Key rotation is the replacement of a key with a new key of the same role. Rotation is necessary because keys age, may be compromised, and become bound to specific algorithms whose security may erode.

Rotation procedure for a principal key K → K':

1. Generate K' through the same mechanism as K.
2. For each envelope whose recipient list includes K, construct a new envelope that adds K' as a recipient. This requires possession of one of the envelope's existing recipient slots — typically K itself, used during the rotation transition.
3. After a transition period during which both K and K' can decrypt, remove K from the recipient list of envelopes that no longer need it.

Rotation can be incremental and partial: not all envelopes need to be updated simultaneously. A holder of K' can decrypt envelopes that include K' as a recipient; envelopes still keyed only to K remain decryptable by the holder of K (which the rotation procedure preserves for as long as the holder retains K).

The language does not impose a rotation cadence; this is operational policy. The mechanism — re-enveloping with updated recipients — is supported by primitives in the standard library.

## Key lifecycle: revocation {#key-revocation}

Revocation is the declaration that a key should no longer be honored. Revocation handles compromise: when a private key is suspected to be in unauthorized hands, the revocation list signals that envelopes addressed to that key must not be decrypted by holders who acquire the key after revocation.

The revocation mechanism is a *revocation Merkle tree*: an append-only data structure published by a revocation authority. Each revocation entry includes the revoked key's fingerprint, a timestamp, and a signature by the revocation authority's signing key. Verifiers consult the revocation tree before decrypting; an envelope addressed to a revoked key is treated as undecryptable, regardless of whether the holder possesses the key material.

The runtime's revocation check is configurable: a high-trust deployment may consult the revocation tree on every decryption; a lower-trust deployment may cache revocation status for a bounded interval; a fully-offline deployment may rely on a snapshot of the tree taken at a known good time. The policy is operational.

The revocation tree itself is a graph: revocation entries are content-addressed nodes; the tree's root is a periodically-updated reference. The mechanism reuses Strand's primitives for content-addressed data; no new infrastructure is required.

## TEE integration {#tee-integration}

Trusted Execution Environments — Intel TDX, AMD SEV-SNP, ARM CCA, AWS Nitro Enclaves, and similar — provide isolated execution contexts whose contents are protected from the host and from other tenants. Strand integrates with TEEs through attestation-bound keys: a TEE generates a key pair at launch, the platform produces a quote binding the public key to the TEE's measured identity, and envelope recipients can be addressed to the attested public key.

The interaction protocol:

1. A TEE process launches. The platform measures the loaded image (code + initial data) and produces a measurement M.
2. Inside the TEE, the process generates an attestation-bound key pair (K_pub, K_priv). K_priv is held in TEE-protected memory; K_pub is exposed to the host.
3. The TEE requests an attestation quote from the platform. The quote contains: M, K_pub, a freshness nonce (provided by the relying party), and a signature by the platform's attestation key.
4. The relying party (a Strand runtime outside the TEE) verifies the quote: confirms the signature against the platform's attestation infrastructure; checks that M matches the expected workload identity; checks the freshness nonce.
5. The relying party encrypts envelopes with K_pub as a recipient. Only the process inside the TEE matching measurement M can use K_priv to unwrap session keys.

The protocol works for any TEE platform that provides remote attestation; the specific encoding of the quote and the verification mechanism is platform-specific. The reference implementation supports at least one TEE platform initially; cross-platform attestation libraries (Open Enclave, Asylo, the IETF RATS framework) can be wrapped to provide a uniform interface across platforms.

## References

**Outgoing references:**
- [`ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — hash of plaintext form
- [`ADR-006-per-node-encryption.md`](../decisions/ADR-006-per-node-encryption.md) — envelope decision
- [`effects-and-capabilities.md`](effects-and-capabilities.md) — Crypto.Decrypt capability
- [`security-model.md`](security-model.md) — threat model, TEE attestation chain
- [`open-questions.md`](../open-questions.md) — Q-011 resolved here, Q-012 referenced

**Incoming references:**
- [`decisions/ADR-006-per-node-encryption.md`](../decisions/ADR-006-per-node-encryption.md)
- [`effects-and-capabilities.md`](effects-and-capabilities.md)
- [`security-model.md`](security-model.md)
- [`research-plan.md`](../research-plan.md)
- [`rendering-and-views.md`](rendering-and-views.md) — encrypted-value verification patterns
