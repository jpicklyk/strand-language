# ADR-003: Content-Addressed Node Identity {#adr-003}

**Document:** `decisions/ADR-003-content-addressing.md`
**Status:** Accepted
**Date:** 2026-05-23
**Last revised:** 2026-06-13 (Clarifying amendment to the Consequences section: before the language's declared stability point, breaking changes to the canonical encoding beneath the hash are permitted when batched into named epochs per [Q-062](../open-questions.md#Q-062), each shipped with regenerated conformance vectors and an Epoch log entry in `design/canonical-encoding.md`; at 1.0 or first external adoption the additive-only discipline of Q-024 takes over. The hash-construction scheme decided here is unchanged — the amendment records how the encoding underneath it may evolve before stability.) 2026-05-28 (Clarifying amendment to the Consequences section: the "no import system" stance is specifically about name-based identity, import-path resolution, visibility annotations, and version constraints — it does not rule out content-addressed grouping primitives such as the N-046 ModuleManifest node introduced by Q-043. The amendment sharpens the conclusion to match what the argument actually supports.) 2026-05-23 (Alternatives considered: separated full-semantic-equivalence rejection from alpha-equivalence-of-bound-variables, which is in fact adopted per `design/node-algebra.md` § Hash construction)
**Supersedes:** none
**Superseded by:** none

## Context {#context}

Every program representation requires a scheme for identifying its pieces. Conventional languages identify code by names that are bound in scopes: a function `f` in module `m` is `m.f`, and references to it resolve by lookup in a name environment. Names are mutable in the sense that renaming a function changes every reference to it. Scoping rules govern which names are visible from where. Module systems impose visibility and dependency boundaries.

Name-based identity has well-known costs. Renames produce diffs proportional to the number of references. Two functions with identical bodies but different names are distinct, defeating deduplication. Names must be unique within a scope, requiring conventions or automated suffixing for similar functions. Cross-codebase use of code requires resolving names across module systems, package managers, and version constraints. For AI-generated code, where the model frequently refactors, the cost of name-based identity compounds: every rename is a cascade, every name collision is an error, every cross-project reference requires resolving a path through an import system.

Content addressing is the alternative: identify each piece of code by a function of its content rather than by an assigned name. Unison demonstrated that this is viable at the level of a production language. Strand inherits the approach and integrates it with graph representation: each node in the Strand graph is identified by a cryptographic hash of its structured content and the recursive hashes of nodes it references.

## Decision {#decision}

Every Strand node has an identity that is the cryptographic hash of a canonical encoding of (a) its type, (b) its structured content fields, and (c) the hashes of every node referenced by an outgoing edge. The hash is the node's identifier. References to a node use the hash directly.

Names, where present, are metadata edges attached to nodes for tooling purposes. The hash of a node does not include the names that point to it, and renaming does not change the node's hash. The hash also does not include extra-program metadata such as provenance, timestamps, or author signatures, which are likewise carried by separate edges or in a separate registry.

The hash construction is recursive. A node's hash depends on the hashes of its referenced nodes, which depend on their referenced nodes, and so on. The result is a Merkle DAG: any change to any node in the program changes the hashes of every node that transitively references it. Two graphs with the same root hash are identical at every reachable node.

The hash function is specified through a multi-hash identifier (in the IPFS sense): each hash is prefixed with a function identifier so the same scheme accommodates multiple hash functions. The default function for the reference implementation is BLAKE3, and it is the only function the reference implementation implements; the multi-hash prefix reserves space for alternatives such as SHA-256 for compatibility with content-addressed storage systems that have not adopted BLAKE3. The function may be changed in the future without changing the scheme; the multi-hash prefix preserves forward compatibility.

Cyclic references are not represented by self-referential hashes. Recursion through a node's own hash is undecidable: the hash depends on the content, which depends on the hash. Strand follows Unison's approach: recursive functions are expressed through a fixed-point indirection or through named recursion against a separate registry, not through direct self-reference. The detailed mechanism is specified in the node algebra (see [Q-019](../open-questions.md#Q-019), iterative computation primitives).

## Alternatives considered {#alternatives}

Four alternatives were evaluated and rejected.

**Name-based identity (the conventional approach).** Functions are identified by names bound in scopes; references resolve by name lookup. This is the dominant pattern and is supported by every existing language tool. It is rejected because every benefit Strand draws from content addressing — mechanical refactoring, deduplication, global identity across machines, tamper resistance, no import system — depends on identity being a function of content rather than a binding to a mutable name. The full argument is in [`02-core-thesis.md`](../02-core-thesis.md#claim-content-addressed).

**Randomly-assigned identifiers (UUIDs, GUIDs).** Each node receives a freshly-generated identifier at creation time, unrelated to its content. References use these identifiers. This eliminates the renaming cascade and provides global uniqueness, but does not deduplicate (two structurally identical nodes have different identifiers), does not provide tamper resistance (modifying a node does not change its identifier), and does not give a verification mechanism (no way to confirm a node retrieved from storage is the node that was requested). The remaining benefits are weaker than content addressing for no compensating advantage.

**Hash of source text only (Git blob model).** Each node's identifier is the hash of its serialized form, but the serialization does not recurse through referenced nodes. This is how Git identifies blobs and how many file-level content-addressed stores work. It is rejected because Strand nodes are fine-grained: a function body is many nodes, not one. Without recursive hashing, equality of two function bodies requires comparing the whole subgraph, and references to a node by its hash do not transitively guarantee the integrity of the nodes it depends on. The Merkle DAG construction is needed because graphs are the unit of program structure.

**Hash of a full semantic canonical form (program-equivalence hash).** Each node is identified by a hash whose equality coincides with semantic equivalence of programs — two functions of the same type that compute the same result for every input would hash the same. This is attractive in principle but is undecidable in the general case (program equivalence is non-trivial; in the presence of effects it is intractable even for restricted fragments). Full semantic-equivalence hashing is therefore not the basis for primary node identity.

A bounded form — alpha-equivalence of bound variables and bound type parameters via positional encoding of binders — *is* decidable, has a tractable encoder, and is adopted as part of the primary hash construction. Lambda parameters and TypeAbstraction/ForallType type-parameter slots are encoded by position rather than by binder hash; variable and type-parameter references are encoded as `(depth, index)` paths to their binder in the enclosing binder stack. Two lambdas that differ only in parameter naming hash to the same value; two type abstractions that differ only in type-parameter naming hash to the same value. This automatic dedup of alpha-variants matters for AI-generated programs, where models routinely vary binder names without semantic intent — without it, every agent that emits an identity function adds a new entry to the standard library. The detailed encoding scheme is specified in [`design/node-algebra.md`](../design/node-algebra.md) § Hash construction and § Variables and binding.

## Consequences {#consequences}

Refactoring becomes mechanical. Renaming a node changes a metadata edge but does not change the node's hash, so every reference to it continues to resolve. Restructuring a graph (replacing one node with another) produces a new graph with a new root hash; old references continue to point to the original node by hash, which remains intact. The distinction between "modifying a node" and "creating a new node with similar content" disappears: every change is the second.

Deduplication is automatic. Two identical subgraphs anywhere in any program have the same root hash and are stored once. This is significant when many graphs share standard library nodes, common patterns, or transitively-equivalent transformations.

Equality is identity. Two nodes have the same hash if and only if their structured content is identical. Reference equality and structural equality coincide. This simplifies comparison, caching, and synchronization.

Distributed identity is global. A node with hash H is the same node on every machine. The runtime needs only to fetch nodes by hash from any machine that holds them, with no name resolution, import resolution, or version negotiation. The fetched node is self-verifying: the receiver computes the hash and confirms it matches the requested hash.

There is no import system. A reference is a hash, not a path. Name-based identity, import-path resolution, visibility annotations, and version constraints are not part of the language. Nodes can reference any node whose hash they hold, subject to capability constraints at execution time (see [ADR-004](ADR-004-effects-as-edges.md)).

What this conclusion specifically rejects is identifying code by *mutable name* and resolving references through a *path-based lookup system*. It does not rule out content-addressed grouping primitives — nodes whose role is to bundle a set of exported hashes plus aggregate metadata (display names as metadata, aggregate effect declarations, optional signing surface). Such a primitive remains content-addressed (its hash binds its export set), does not introduce names as identity (display strings are metadata, excluded from the canonical encoding), does not introduce import-path resolution (consumers still reference exports by hash), does not introduce visibility (every reachable node remains callable by any code that holds its hash), and does not introduce version constraints (a different export set is a different manifest hash). The N-046 ModuleManifest node introduced by [Q-043](../open-questions.md#Q-043) is exactly this kind of primitive — a positive grouping of exports identified by hash, not a name-based namespace. Its compatibility with this ADR is by design, not by exception.

Versioning is reframed. There is no notion of "version 2.0 of function f," because a modified function is a different node with a different hash. Tools that need version-like semantics (e.g., "the latest sanctioned implementation of an interface") maintain a mutable registry that maps a name or interface identifier to a current hash; the registry is metadata over the immutable graph, not part of the graph itself. The migration story for the language itself ([Q-024](../open-questions.md#Q-024)) is bounded by this property: old graphs remain valid because their hashes still resolve; new graphs may use new node types but coexist.

The canonical encoding beneath the hash is itself versioned by epoch before the language's declared stability point ([Q-062](../open-questions.md#Q-062)). Pre-1.0, a breaking change to the canonical encoding is permitted when batched into a named epoch — defined by its own proposal, shipped with regenerated conformance vectors, and recorded in the Epoch log of [`design/canonical-encoding.md`](../design/canonical-encoding.md). The encoding carries no in-band epoch marker: an implementation accepts exactly the epoch it implements, and a store mixing artifacts hashed under different epochs fails closed on hash mismatch. At 1.0 or first external adoption the policy flips to Q-024's additive-only discipline; the multihash prefix above remains the mechanism for the orthogonal case of hash-function migration.

Tamper resistance is intrinsic. A node's hash binds its content; any modification changes the hash, breaking every reference. A graph whose root hash matches an expected value is unchanged from the moment that root was computed. This property is the basis for distribution security (a worker that fetches a node by hash knows it has the right node) and for replay determinism (a graph with a given root hash produces a given output, assuming pure subgraphs and identical capability contexts).

Garbage collection is by reachability. The set of live nodes is the transitive closure of the set of root references; nodes not in the closure can be removed. Reference counting does not apply because references are content-derived and persist after their referrer is removed; only the absence of any path from a known root marks a node as collectible.

The hash function choice has consequences for performance and security. BLAKE3 is faster than SHA-256 by roughly an order of magnitude in software, supports incremental verification and parallel hashing, and has been analyzed against the same security bar. SHA-256 is more conservative in the sense of longer scrutiny in deployed systems and is the function used by IPFS by default and by Git's transition path. The multi-hash format permits both, and a migration to a future function is supported by extending the prefix space.

The encryption interaction is not resolved here. When a node is encrypted ([ADR-006](ADR-006-per-node-encryption.md)), whether the hash covers the plaintext canonical form, the ciphertext, or both has implications for caching, deduplication, and confidentiality. The question is open ([Q-011](../open-questions.md#Q-011)) and resolved in the encryption ADR.

## References

**Outgoing references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — Claim 4, content addressing
- [`01-prior-art.md`](../01-prior-art.md) — Unison comparison
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — graph-native foundation
- [`ADR-004-effects-as-edges.md`](ADR-004-effects-as-edges.md) — capability constraints on references
- [`ADR-006-per-node-encryption.md`](ADR-006-per-node-encryption.md) — encryption interaction
- [`design/node-algebra.md`](../design/node-algebra.md) — formal hash construction
- [`design/canonical-encoding.md`](../design/canonical-encoding.md) — byte-level encoding and the pre-1.0 Epoch log
- [`open-questions.md`](../open-questions.md) — Q-011, Q-019, Q-024, Q-062

**Incoming references:**
- [`02-core-thesis.md`](../02-core-thesis.md) — cites this ADR from Claim 4
- [`ADR-001-graph-not-text.md`](ADR-001-graph-not-text.md) — defers identity scheme to this ADR
- [`ADR-005-foreign-nodes.md`](ADR-005-foreign-nodes.md) — ForeignNodes are content-addressed
- [`ADR-006-per-node-encryption.md`](ADR-006-per-node-encryption.md) — canonical hash construction under encryption
- [`ADR-007-state-machines.md`](ADR-007-state-machines.md) — content-addressed transition functions
- [`ADR-008-compilation-target.md`](ADR-008-compilation-target.md) — content-addressed identity in the VM
- [`design/node-algebra.md`](../design/node-algebra.md) — hash construction details
- [`design/encryption-model.md`](../design/encryption-model.md) — hash semantics under encryption
- [`design/security-model.md`](../design/security-model.md) — tamper resistance
- [`design/state-machines.md`](../design/state-machines.md) — content-addressed transitions
- [`design/distribution-model.md`](../design/distribution-model.md) — content addressing for fetches
- [`ADR-009-structured-outputs.md`](ADR-009-structured-outputs.md) — provenance manifest depends on content-addressed identity
- [`design/rendering-and-views.md`](../design/rendering-and-views.md) — content-addressed structured output values
