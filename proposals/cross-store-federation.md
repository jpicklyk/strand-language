# Cross-Store Federation and Hash-Pinned Composition

**Document:** `proposals/cross-store-federation.md`
**Status:** Partially implemented — N-046 ModuleManifest (step 3b) landed 2026-05-28; the step-3a federation runtime landed 2026-05-29 through the verifier+interpreter+CLI path: cross-store admission with bound-node re-basing + Merkle integrity (`translateNodeIds` + `fetchAndAdmit`), verifier/interpreter threading, the `targetHash` external-hash NodeRef ingest form, corpus 76, and `strand run/verify --peer-store`. Remaining for step 3a: VM / StateMachineRuntime / SchemaChecker threading, `--peer-store` on `machine`/`group`, the `strand registry` subcommands + `--no-cache`/`--strict-integrity` flags, and corpus 77/78. See § 10 Implementation status.
**Date:** 2026-05-28
**Concerns:** [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) (amended 2026-05-28 to clarify that content-addressed grouping primitives are compatible with the no-import-system stance), [`design/node-algebra.md`](../design/node-algebra.md) § N-019 NodeRef, [`design/distribution-model.md`](../design/distribution-model.md), [Q-006](../open-questions.md#Q-006), [Q-014](../open-questions.md#Q-014), [Q-016](../open-questions.md#Q-016), [Q-024](../open-questions.md#Q-024), [Q-043](../open-questions.md#Q-043)
**Scope:** Medium-Large

Layer 2 step 2 shipped NodeRef-by-hash within a single FinalizedProgram. Cross-store fetches, distribution, and any composition of independently-authored graphs remain out of reach. This proposal scopes Layer 2 step 3 to two coordinated additions: a resolver-based federation protocol over multiple HashStores, and a new content-addressed grouping primitive — N-046 ModuleManifest — that bundles related exports with aggregate effect declarations and provides the natural unit for signing, distribution, and cognitive grouping. Both pieces honor the amended ADR-003: composition remains hash-as-reference; the manifest is a *positive grouping* of hash-identified exports, not a name-based namespace or an import system. The result is the smallest mechanism sufficient to compose independently-authored Strand programs and to declare aggregate contracts over export bundles, without reintroducing any of the features ADR-003 specifically rejects.

## 1. Problem statement

Every Strand program shipped in the Kotlin reference implementation is a single FinalizedProgram: one `NodeStore`, one `nodeIdToHash`, one `hashToNodeId`, all produced by a single `Hasher.finalize` pass over one JSON document. NodeRef (N-019) carries a `Hash` in canonical form, but resolution is performed against the single `hashToNodeId` map carried in `FinalizedProgram`. There is no protocol for asking "which store holds the node with hash H?" — the question cannot arise because there is only ever one store.

This restriction has a concrete cost. An agent that produces a 200-node program containing a 60-node JSON parser, a 40-node HTTP-response handler, and 100 nodes of business logic must produce all 200 nodes in one document. There is no mechanism for the agent to import the parser and the handler from previously-authored stores and write only the 100 application-specific nodes. Every program is monolithic by construction. Q-021 dynamic-cost evaluation cannot scale to tasks whose natural decomposition spans multiple authored components, because there is no decomposition.

A second cost compounds the first. Even when a single store holds related exports — a "library" of helpers, a schema family, a state-machine collection — the language has no primitive for asserting that they are a coherent bundle with an aggregate capability contract. CapabilityScope (N-036) narrows capabilities within one subexpression; it does not declare that a collection of exports, considered together, exposes a particular surface. The lack of a bundle primitive forces ecosystem developments — signing, trust delegation, distribution, agent cognitive grouping — into ad-hoc tooling conventions instead of language-level support.

ADR-003 commits Strand to hash-as-reference for the composition mechanism. The amended ADR clarifies that this stance specifically rules out name-based identity, import-path resolution, visibility annotations, and version constraints — and does not rule out content-addressed grouping primitives. The gap this proposal closes is therefore twofold: the runtime mechanism for cross-store NodeRef resolution, and the algebra-level primitive for declaring aggregate contracts over exported hashes.

## 2. Prior art

- **IPFS / IPLD** — content-addressed Merkle DAG with peer-to-peer fetch by content identifier. A node is requested by hash; any peer that holds the node returns it; the receiver verifies the hash. The federation layer is the network protocol; the data layer is just hash-keyed bytes. Strand's resolver chain is the same shape minus the network.
- **Unison** — content-addressed code with a separate "namespace tree" maintained by the Unison Codebase Manager (UCM). Code references are hashes; humans interact with the namespace tree to find or publish hashes by name. The namespace tree is mutable tooling state, not part of the codebase. Strand's NameRegistry follows the same pattern.
- **Nix flakes** — content-addressed store paths plus a `flake.lock` file pinning each named input to a specific store-path hash, plus a `flake.nix` declaring the package's outputs (its exports). The flake's outputs are the closest existing analog to a ModuleManifest: a declared bundle of named hashes plus aggregate metadata, content-addressed by the flake's narHash.
- **OCI image manifests** — a manifest is a JSON document listing the digest of every layer in the image plus aggregate metadata (config, signatures). The image is referenced by its manifest digest; the manifest is the unit of signing and trust. Strand's ModuleManifest is conceptually identical: a content-addressed declaration that bundles a set of hashes with aggregate properties.
- **Sigstore / in-toto attestations** — an attestation is a content-addressed document asserting properties about a software artifact; the attestation is the signing surface. The Strand manifest is the bundle that an attestation would attach to.

The recurring pattern across these systems is a *content-addressed bundle primitive* that aggregates hashes with declared properties, used for signing, distribution, and discovery. Strand has the substrate (content-addressed nodes, hash-as-reference) but no bundle primitive. This proposal closes that gap with N-046 ModuleManifest while keeping all the existing primitives unchanged.

## 3. Recommended approach

**Two coordinated mechanisms.**

**Mechanism A: Federation by a `Resolver` interface, composed in a chain.** The verifier and interpreter no longer carry a single `hashToNodeId` map; they accept a `NodeResolver` that, given a Hash, returns a self-contained `SubgraphFetch` — the requested root node plus every transitively-referenced internal node (stopping at NodeRef boundaries), plus the foreign store's `nodeIdToHash` mapping for those nodes. The local `FederatedProgram` admits the fetched subgraph by translating each Node's internal `NodeId` references from the foreign-store ID space to local IDs (via the foreign `nodeIdToHash` plus the local `hashToNodeId`). The simplest resolver is `LocalHashStoreResolver` over a single `HashStore` plus its `nodeIdToHash`. A `ChainedResolver` tries each delegate in order, returning the first hit. A `FileSystemResolver` reads canonical-bytes-plus-metadata snapshots from a directory keyed by hash; each on-disk file is itself a self-contained subgraph. A `CachingResolver` caches fetched subgraphs. The interface is small enough that future network-backed resolvers, signed-binding resolvers, or capability-gated resolvers slot in without changing the verifier or interpreter.

**Why `SubgraphFetch` and not single Node.** A Strand `Node` in the in-memory ADT carries internal `NodeId` references for non-NodeRef edges (Lambda.body, Application.arguments, ProductValue.fields, and so on). Those NodeIds are meaningful only in the store they were assigned in. Returning a single Node from the resolver leaves its internal references pointing into a foreign-store ID space the local verifier cannot resolve — silent corruption at best, dangling-reference errors at worst. The fix is to fetch the whole self-contained subgraph (everything reachable from the root that is not itself a NodeRef-boundary), carry the foreign `nodeIdToHash` mapping along, and translate each admitted Node's internal NodeIds into the local ID space at admission time. The translation is mechanical (every Node variant has a known list of NodeId fields) and the foreign-to-local mapping is built once per fetch.

**Mechanism B: N-046 ModuleManifest as a content-addressed grouping primitive.** A new node category bundles a list of exports — each export is a triple of `(target: Hash, displayName: String, declaredEffects: List<EffectCategory>)`. The manifest's hash binds its export set; two manifests with structurally identical exports hash identically. The verifier admits a manifest by checking that each export's `declaredEffects` exactly matches the closure of the targeted node. Consumers reference exports by the export's target hash directly (the manifest is not an indirection point); the manifest serves as a certified declaration and the natural unit for signing, distribution, and discovery.

**No conventional module concept.** The manifest does not introduce names as identity (`displayName` is metadata, excluded from canonical encoding — two manifests with identical exports but different display strings hash identically). It does not introduce imports (consumers reference export hashes directly; the manifest is informational). It does not introduce visibility (every reachable hash remains callable). It does not introduce version constraints (a different export set is a different manifest hash). ADR-003 was amended on 2026-05-28 to clarify that this kind of content-addressed grouping is compatible with the no-import-system stance.

**Off-graph NameRegistry as the discovery layer.** A `NameRegistry` is a flat mapping from human-chosen names to hashes. Registry entries typically point at manifest hashes ("`stdlib/json-parser`" → `manifestHashH`) but may point at any node hash. The registry is a JSON file, exists purely to give tooling a name-discovery affordance, and is not part of the canonical graph. The CLI subcommand `strand registry resolve <name>` reads the registry and prints the hash.

**Integrity check on every cross-resolver fetch.** When a resolver returns a node for a requested hash, the receiver recomputes the canonical hash of the returned node and confirms it equals the request. A mismatch is a hard error (`NodeResolverIntegrityViolation`). This is what makes the protocol trust-minimizing: the resolver itself need not be trusted, because the hash binds the content.

**Effect closure across stores and across manifests.** A NodeRef whose target declares effects contributes those effects to the caller's closure, exactly as for local nodes. The verifier computes closures across resolver-fetched subgraphs. A manifest's `declaredEffects` is verified to equal the closure of each export; consumers may read the manifest to determine a bundle's aggregate effect surface without walking into each export.

## 4. Detailed mechanism

### 4.1 The `NodeResolver` interface

```kotlin
// New file: hashing/src/main/kotlin/org/strand/hashing/NodeResolver.kt
interface NodeResolver {
    /**
     * Returns a self-contained [SubgraphFetch] rooted at [hash], or null
     * if the resolver does not hold the hash.
     */
    fun resolve(hash: Hash): SubgraphFetch?
}

data class SubgraphFetch(
    /** The hash that was requested; equals the hash of the root node. */
    val rootHash: Hash,
    /**
     * Every node reachable from the root that is not itself a NodeRef
     * boundary — including the root. Keyed by canonical hash so the
     * receiver can build a foreign-to-local NodeId mapping by looking up
     * each entry in the local [hashToNodeId] (or by admitting and
     * extending the local maps on first encounter).
     */
    val nodes: Map<Hash, Node>,
    /**
     * The foreign store's NodeId → Hash mapping for the contained
     * [nodes]. Used by the receiver to translate the foreign NodeId
     * references inside each admitted node into the local ID space.
     */
    val nodeIdToHash: Map<NodeId, Hash>,
)
```

The resolver returns a whole subgraph because a Strand `Node`'s in-memory representation carries internal NodeIds (Lambda.body, Application.arguments, and so on) that are only meaningful in the store they came from. Returning a single Node would leave those references pointing into foreign-store ID space. The `nodeIdToHash` map lets the receiver translate every foreign NodeId reference to its canonical hash, then look up (or freshly admit) the local NodeId for that hash. The translation is mechanical — every Node variant has a closed set of NodeId-typed fields.

The subgraph stops at NodeRef boundaries: a NodeRef inside the fetched subgraph stays a NodeRef (with its target hash), and the receiver resolves it lazily on demand. This keeps each fetch bounded — a library that NodeRefs another library does not transitively pull every transitive dependency at first fetch.

### 4.2 Composing resolvers

```kotlin
class ChainedResolver(private val resolvers: List<NodeResolver>) : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? {
        for (r in resolvers) {
            r.resolve(hash)?.let { return verifyIntegrity(hash, it) }
        }
        return null
    }
    private fun verifyIntegrity(expected: Hash, fetch: SubgraphFetch): SubgraphFetch {
        // The fetch's rootHash is the resolver's claim; check it against
        // the requested hash. Per-node hash verification across the whole
        // subgraph happens at admission time via the existing canonical
        // encoder — every admitted Node is re-hashed and confirmed to
        // match its declared hash before its internal references are
        // translated.
        if (fetch.rootHash != expected) {
            throw NodeResolverIntegrityViolation(expected, fetch.rootHash)
        }
        return fetch
    }
}

class LocalHashStoreResolver(
    private val store: HashStore,
    private val nodeIdToHash: Map<NodeId, Hash>,
) : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? {
        val rootNode = store.get(hash) ?: return null
        // Walk from the root, gathering every reachable internal node
        // (stopping at NodeRef boundaries). Build the matching
        // nodeIdToHash subset by intersecting the walk with the
        // pre-finalize map.
        val reachable = walkSubgraph(rootNode, store, nodeIdToHash)
        return SubgraphFetch(rootHash = hash, nodes = reachable.nodes,
                             nodeIdToHash = reachable.nodeIdToHash)
    }
}

class FileSystemResolver(private val rootDir: Path) : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? {
        val file = rootDir.resolve(hash.toBase32())
        if (!file.exists()) return null
        return SubgraphFetchSerialization.fromBytes(file.readBytes())
    }
}

class CachingResolver(
    private val delegate: NodeResolver,
    private val cache: MutableMap<Hash, SubgraphFetch> = mutableMapOf(),
) : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? {
        cache[hash]?.let { return it }
        val fetch = delegate.resolve(hash) ?: return null
        cache[hash] = fetch
        return fetch
    }
}
```

The chain composes by ordinary list iteration. The integrity check in `ChainedResolver` confirms the resolver-claimed `rootHash` matches the requested hash; per-node hashes are confirmed at admission time as the receiver re-runs the canonical encoder over each admitted node.

### 4.3 N-046 ModuleManifest node category

A new node category bundling exports with aggregate effect declarations.

```
N-046 ModuleManifest:
  exports           : list of ManifestExport (1..*)    [positional, hash-affecting]
  description       : Provenance? (metadata)           [metadata, hash-excluded]
  manifestSignature : Bytes? (metadata)                [metadata, hash-excluded]

ManifestExport:
  target            : Hash (NodeRef-style)             [hash-affecting]
  declaredEffects   : List<EffectCategory NodeId>      [hash-affecting, set semantics]
  displayName       : String                            [metadata, hash-excluded]
```

The manifest's `exports` list is positionally ordered in the canonical encoding (matching ProductType.fields precedent — author-significant order). Each export's `declaredEffects` is a set encoded as a lex-sorted hash list (matching FunctionType.effects precedent — order does not affect identity).

`displayName` is metadata: it is recorded on the in-memory ADT and emitted in JSON output, but excluded from canonical encoding. Two manifests with the same `(target, declaredEffects)` pairs but different display strings hash identically. This matches the precedent set by ParameterDecl.name and Let.name.

`description` and `manifestSignature` are metadata edges/fields excluded from canonical encoding, matching the Provenance precedent.

The manifest is a passive declaration. It does not participate in evaluation as an expression. Its role is verifier-time certification of an export contract plus consumer-time discoverability of the bundle.

### 4.4 Canonical encoding for ModuleManifest

```
Tag                        : 46 (CategoryTag.ModuleManifest, 4-byte big-endian)
Number of exports          : varint
For each export (positional):
  target hash              : 33-byte multi-hash (CBOR byte string)
  declaredEffects count    : varint
  effect category hashes   : array of 33-byte multi-hashes, sorted lexicographically
                             (set semantics — declaration order does not affect identity)
```

The canonical encoding is field-by-field deterministic. Two manifests with the same positional sequence of (target, set-of-effect-hashes) produce byte-identical encodings and identical BLAKE3 digests. The `displayName`, `description`, and `manifestSignature` fields are omitted from canonical bytes.

### 4.5 FederatedProgram

```kotlin
data class FederatedProgram(
    val store: NodeStore,                              // mutable: extended on fetch
    val root: NodeId,
    val nodeIdToHash: MutableMap<NodeId, Hash>,        // extended on fetch
    val hashToNodeId: MutableMap<Hash, NodeId>,        // extended on fetch
    val resolver: NodeResolver,
) {
    fun fetchAndAdmit(hash: Hash): NodeId?
}
```

`resolver` is consulted only when a NodeRef's target hash is not in `hashToNodeId`. The admission protocol:

1. `resolver.resolve(hash)` returns a `SubgraphFetch?`. Null means "not held by any resolver in the chain"; the caller raises `NodeRefTargetUnresolvable`.
2. The `SubgraphFetch` contains every internal node reachable from the requested root (stopping at NodeRef boundaries), keyed by canonical hash, plus the foreign store's `nodeIdToHash` mapping for those nodes.
3. The receiver builds a **foreign → local NodeId** translation map:
   - For each foreign `nodeIdToHash` entry `(fId → h)`:
     - If `hashToNodeId[h]` is already present, the foreign-to-local mapping is `fId → hashToNodeId[h]` (the node is shared with a previously-admitted subgraph or with the local program).
     - Otherwise, admit `nodes[h]` to the local store under a fresh local NodeId `lId`, extend `nodeIdToHash[lId] = h` and `hashToNodeId[h] = lId`, then record `fId → lId`.
4. Once every fetched node has a local NodeId, walk each admitted Node and **translate its internal NodeId references** from the foreign ID space to the local one using `Node.translateNodeIds(foreignToLocal)`. The translation rewrites every `NodeId`-typed field in the Node ADT and is mechanical (every Node variant has a closed list of NodeId-typed fields).
5. The local store's entry for each admitted node is overwritten with the translated version. After translation, the verifier can walk the admitted subgraph using only local NodeIds.

`Node.translateNodeIds(fn: (NodeId) -> NodeId): Node` is a new extension defined exhaustively over the Node sealed hierarchy in `:core`. NodeRef itself is unchanged by translation (its target is a Hash, not a NodeId).

A `FinalizedProgram` is upgraded to a `FederatedProgram` by `program.federated(resolver)` returning a wrapper that lazily admits fetched nodes. Programs that never call out to non-local hashes behave identically to today.

### 4.5b Re-hashing as the per-node integrity check

After translation, each admitted node's canonical hash is recomputed and compared to its declared hash from `SubgraphFetch.nodeIdToHash` (now mapped to local NodeIds). A mismatch indicates either (a) the resolver lied about which Node corresponds to which Hash, or (b) the translation reshape was lossy. Either case raises `NodeResolverIntegrityViolation` with the mismatching pair. The check covers every fetched node, not just the root, providing per-subgraph integrity rather than only root-level integrity.

### 4.6 The `NameRegistry`

```kotlin
data class NameRegistry(val entries: Map<String, Hash>) {
    companion object {
        fun fromJson(bytes: ByteArray): NameRegistry { ... }
    }
    fun resolve(name: String): Hash? = entries[name]
    fun toJson(): ByteArray { ... }
}
```

JSON schema:

```json
{
  "version": 1,
  "entries": {
    "stdlib/json-parser": "z2BvSi8aQGhVHFnSQfwh4kJoB9D3kxRq8WeKVnFvyaL7",
    "stdlib/markdown-render": "z6BqRkLm9PwYjVsTnHbCgFXyAuKtMcEqDsLvJpHrZxNc",
    "app/main": "zABCDEf1234567890Xx9HrZxNcDsLvJpHrZxNcDsLv"
  }
}
```

Registry entries typically point at manifest hashes (one entry per published bundle), but the format does not constrain this — entries may point at any hash. Multi-version naming is registry-internal convention (`json-parser-v1`, `json-parser-v2`); the format is single-version-per-name.

### 4.7 Worked example

Two stores plus a manifest. `lib-list.json` defines two Lambdas, `lengthFn: (List<Int>) -> Int` and `reverseFn: (List<Int>) -> List<Int>`, plus a `ModuleManifest` whose exports are `[(lengthFn hash, [], "List.length"), (reverseFn hash, [], "List.reverse")]` — both pure, no effects. The manifest's canonical hash is `H_manifest`. After finalize, `lib-list.json` produces a `FederatedProgram` whose root is the manifest.

`app.json` is authored against the library. It contains a NodeRef whose target is the `lengthFn` hash plus an Application that calls it on a literal list. Note: `app.json` references the export directly by its hash, not via the manifest. The manifest is informational, not load-bearing.

`strand registry resolve stdlib/list` returns `H_manifest`. `strand run app.json --peer-store lib-list.json` runs the application. The resolver chain is `ChainedResolver(LocalHashStoreResolver(app_store, app_nodeIdToHash), LocalHashStoreResolver(lib_store, lib_nodeIdToHash))`. The verifier walks the application, sees a NodeRef to `lengthFn`'s hash, calls `resolver.resolve(...)`. The library resolver walks its store from the `lengthFn` Lambda, collecting the Lambda plus its ParameterDecl plus its body subgraph (everything reachable that is not a NodeRef target), and returns a `SubgraphFetch` containing all those nodes plus their library-store NodeId-to-Hash mapping. The chain confirms the root hash matches. The `FederatedProgram.fetchAndAdmit` builds a foreign-to-local NodeId map by admitting each fetched node under a fresh local NodeId, then re-walks each admitted node and translates its internal NodeId references via `Node.translateNodeIds`. After translation every admitted Node uses local NodeIds; the verifier proceeds to type-check the Lambda. The application runs to completion.

A separate program could verify the manifest's claims without running anything: `strand verify-manifest lib-list.json`. The verifier checks that for each export, `closureOf(target)` equals `declaredEffects`. This is the certification step that makes downstream consumers trust the manifest's effect declaration without re-walking each export.

### 4.8 Why a manifest, not a conventional module

The conventional module concept bundles eight features: namespacing, visibility/encapsulation, compilation unit, dependency management, versioning, capability boundary, documentation, distribution. ADR-003 (amended 2026-05-28) specifically rejects names as identity, import-path resolution, visibility annotations, and version constraints. It does not reject content-addressed grouping primitives.

The ModuleManifest in this proposal:

- **Does not introduce names as identity.** `displayName` is metadata excluded from canonical encoding. The manifest's identity is its export-set hash. Two libraries with identical export sets are the same manifest, even if the human strings differ.
- **Does not introduce imports.** Consumers reference exports by the export's target hash directly. The manifest does not appear in the reference path.
- **Does not introduce visibility.** Every node reachable by hash is still callable by any code that holds its hash. The manifest is a positive grouping ("these are exported"), not a negative annotation ("these are private").
- **Does not introduce version constraints.** A modified export set produces a different manifest hash. Consumers pin to a specific manifest hash. There is no version negotiation, no dependency resolution, no SAT solver for constraints.

What it does add:

- **Bundle-level effect declarations** — each export carries declared effects, certified by the verifier to match the actual closure. Consumers can read the manifest to learn the aggregate effect surface without walking exports.
- **Signing surface for Q-006** — the manifest hash is the natural granularity for signing. Sign the manifest; the signature attests to the whole bundle.
- **Distribution unit** — a "Strand package" is a manifest hash plus the transitive closure of referenced nodes.
- **Cognitive grouping for agents** — agents can natively reason about "the JSON parser manifest" as a unit when emitting or consuming code, rather than learning the registry as a separate concept.

## 5. Verifier rules

### 5.1 `NodeRefTargetMustBeResolvable`

The existing `NodeRefTargetMustBeClosed` rule is unchanged. A new rule applies during verification of any `NodeRef` whose `target: Hash` is not in `hashToNodeId`:

- Call `resolver.resolve(target)`.
- If null, report `NodeRefTargetUnresolvable(at = nodeRefId, target = hash)`.
- If non-null, the returned node is admitted to the local store under a fresh NodeId; the hash is added to `hashToNodeId`; the new node is recursively verified before the original NodeRef's verification continues.

The "verified once per session" property is preserved by checking `verifiedHashes.contains(hash)` before re-verifying a fetched subgraph. Fetched subgraphs that fail verification produce `NodeRefTargetVerificationFailed(at, target, innerError)`.

### 5.2 `NodeResolverIntegrityViolation`

A new runtime exception, not a `VerifyError`: the integrity check inside `ChainedResolver.verifyHash` throws if a resolver returns a node whose canonical hash does not match the requested hash. The CLI catches this and reports it as a hard failure with the offending resolver in the message.

### 5.3 Effect closure under fetched targets

`closureOf(nodeRef)` for a NodeRef with a fetched target is `closureOf(target)` exactly as for local targets. The resolver fetch happens during the closure computation; the fetched node's closure is computed in turn and cached in `nodeClosures`. No new rule is needed beyond making the closure computation traverse fetched subgraphs.

### 5.4 `ManifestExportEffectMismatch`

For every `ModuleManifest` admitted to the store, for every `ManifestExport` in its `exports` list, the verifier computes `closureOf(export.target)` and compares it to `export.declaredEffects`. If the two sets are not equal, report `ManifestExportEffectMismatch(at = manifestId, exportIndex, target, declared, actual)`.

The check is *exact equality*, not subset. A manifest cannot under-declare effects (security: callers would believe the export is purer than it is) or over-declare them (precision: callers would refuse the export unnecessarily). Authors who want to expose a more conservative interface should compose a wrapping Lambda whose body has the desired effect closure and export that.

### 5.5 `ManifestExportTargetUnresolvable`

If `export.target` is not resolvable through the resolver chain, report `ManifestExportTargetUnresolvable(at = manifestId, exportIndex, target)`. This is structurally identical to `NodeRefTargetUnresolvable` but specific to manifest exports for diagnostic clarity.

### 5.6 What is not changed

- The canonical encoding of NodeRef is unchanged. A NodeRef encodes its 33-byte target hash; whether the target is local or fetched is invisible to the encoder.
- Verifier rules for every other node category are unchanged.
- The bytecode VM's NodeRef handling does not change: it looks up by Hash in a resolver-extended map.
- `NodeRefTargetMustBeClosed` does not change. Fetched subgraphs and manifest export targets are independently closed.

## 6. Interpreter and runtime semantics

The interpreter accepts a `FederatedProgram` (or a `FinalizedProgram` wrapped as one). NodeRef evaluation looks up the target hash in `hashToNodeId`; if present, proceeds as today; if absent, calls `resolver.resolve(target)`, performs the integrity check, admits the node to the local store, extends `hashToNodeId`, and proceeds. The fetched-then-admitted path is a one-time cost per hash per session.

`ModuleManifest` is a passive declaration. It is not an expression; it has no runtime evaluation semantics. A manifest's value at runtime is `Value.UnitV` if ever queried, but in practice no Application ever targets a manifest. The manifest's purpose is exhausted at verifier-time (effect-closure certification) and at tooling time (signing, registry resolution, distribution metadata).

The CLI subcommands `strand verify`, `strand run`, `strand machine`, `strand group` gain four flags:

- `--peer-store <path>` (repeatable) — adds a resolver to the chain. Order is command-line order.
- `--registry <path>` — loads a `NameRegistry`. Used by `strand registry resolve` and by the authoring elaborator.
- `--no-cache` — disables `CachingResolver` wrapping (default is to cache for the session).
- `--strict-integrity` — the integrity check is on by default; this flag exists for explicit declaration in scripted runs.

New subcommands:

- `strand registry resolve <name>` — prints the hash, exit 1 if unknown.
- `strand registry put <name> <hash>`, `strand registry list` — round out the tooling.
- `strand verify-manifest <file.json>` — verifies a manifest's export effect claims without running anything.
- `strand corpus federate <lib1.json> ... <app.json>` — chained-resolver test driver.

## 7. Test scenarios

1. **Single-store program runs unchanged.** Every existing corpus program runs against `FederatedProgram(store, root, nodeIdToHash, hashToNodeId, NoOpResolver)` byte-identically.

2. **Two-store happy path.** A library store contains a Lambda; an application store NodeRefs the library Lambda's hash. With `--peer-store lib.json`, the application verifies, runs, and produces the expected output.

3. **Resolver chain order.** Two libraries each define a Lambda; the application's NodeRef hash matches the first only. The first hit short-circuits and lib2 is never consulted.

4. **Integrity violation.** A `BadResolver` returns a wrong node for a requested hash. The session halts with `NodeResolverIntegrityViolation`; the exception carries the requested hash, the returned hash, and the resolver identity.

5. **Target unresolvable.** The NodeRef target hash is in no peer store. The verifier reports `NodeRefTargetUnresolvable`; no execution begins.

6. **Transitive resolution.** Library A references library B by NodeRef; the application references A. The verifier fetches A, walks into A's NodeRef, fetches B, verifies B, then A, then the application.

7. **Effect closure across stores.** A library Lambda declares `Filesystem.Write`. An application NodeRefs it inside a context that does not grant `Filesystem.Write`. The verifier reports `CapabilityViolation` exactly as if the Lambda were local.

8. **Name registry round-trip.** A registry entry maps `stdlib/identity` to hash `H_id`. `strand registry resolve stdlib/identity` prints `H_id`. The authoring elaborator's name-to-hash mode emits a NodeRef with target `H_id`.

9. **Caching reduces resolver calls.** A program with three NodeRefs to the same hash triggers exactly one underlying resolver call when caching is enabled; three calls when disabled.

10. **Verified-once invariant.** A library Lambda referenced twice from two points in an application is verified once.

11. **Manifest with multiple exports.** A manifest exports three pure Lambdas. The verifier admits the manifest; `strand verify-manifest` reports each export's declared effects (empty set) matches its closure.

12. **Manifest with effectful export.** A manifest exports a Lambda that performs `Filesystem.Write`. The export's `declaredEffects` contains `Filesystem.Write`. The verifier admits the manifest. A consumer can read the manifest to learn the aggregate effect surface.

13. **Manifest effect under-declaration rejected.** A manifest exports a Lambda whose closure includes `Filesystem.Write`, but the export's `declaredEffects` is empty. The verifier reports `ManifestExportEffectMismatch(at = manifestId, exportIndex = 0, target = ..., declared = [], actual = [Filesystem.Write])`.

14. **Manifest effect over-declaration rejected.** A manifest exports a pure Lambda but the export's `declaredEffects` claims `Network.Connect`. The verifier reports `ManifestExportEffectMismatch` symmetrically.

15. **Manifest export target unresolvable.** A manifest's export target hash is in no peer store. The verifier reports `ManifestExportTargetUnresolvable(at = manifestId, exportIndex = 0, target = ...)`.

16. **Two manifests with identical exports hash identically.** Manifest A has exports `[E1, E2]`; manifest B (authored independently with different display names and a different description) has the same `(target, declaredEffects)` pairs in the same order. Their canonical encodings are byte-identical; their hashes match.

17. **DisplayName changes don't change manifest hash.** Renaming an export's `displayName` produces a new in-memory manifest but the canonical encoding and hash are unchanged.

18. **Manifest signature is metadata.** Two manifests differ only in `manifestSignature`. Their hashes are identical. The signature is queryable from the in-memory ADT but does not participate in identity. (Q-006 trust integration is deferred; the signing surface is in place.)

19. **Cross-store manifest demonstrator (corpus 79-80).** `lib-list-ops.json` ships two pure Lambdas plus a manifest exporting both with display names `List.length` and `List.reverse`. `lib-bool-ops.json` ships one Lambda plus a manifest exporting it as `Bool.and`. `app-program.json` references all three exports by hash and uses them in a small computation. Corpus test asserts end-to-end run.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Networked resolvers.** The proposal stops at `FileSystemResolver`. HTTP-backed, IPFS-backed, S3-backed resolvers are downstream extensions of the same interface and do not require any further protocol change. They are deferred because they bring network-failure semantics (Q-016), retry policy, and trust questions that are larger than the federation primitive itself.

- **Signed manifest verification.** The `manifestSignature` field is in place but the proposal does not specify the signature format or verification protocol. This is intentional: the Q-006 trust model will land in Milestone 2.4 and own the signature semantics. The manifest gives that work the right granularity to attach to.

- **Persistent cache.** `CachingResolver` is per-session. A persistent on-disk cache keyed by hash is straightforward (sub-`FileSystemResolver`) but unspecified here.

- **Multi-version registry semantics.** The registry entry shape is `name → hash` only. Multi-version naming is registry-internal convention; agents that want versioned access use suffixed names (`json-parser-v1`).

- **Hierarchical manifests.** A manifest that contains other manifests as exports is technically already expressible (a manifest export's target may be another manifest). Whether this is a useful pattern is left to emergent practice; the proposal does not enshrine it.

- **Garbage collection across federated stores.** Reachability-based GC within one HashStore is straightforward; cross-store GC requires global reachability information no single resolver can supply. Deferred to the distribution work.

- **Concurrent fetches.** The resolver interface is synchronous. An async variant returning `suspend` is straightforward and lands when networked resolvers do.

- **Sealed exports.** No mechanism is provided for a manifest to assert that its exports are *sealed* — that no transitively-reachable node outside the export list should be callable from the consumer's perspective. This is the "visibility" question ADR-003 explicitly defers. A future refinement could add an optional `sealed: Bool` flag whose enforcement requires capability-based reachability tracking.

**Real research questions:**

- *Registry naming conventions.* Whether stdlib registry names should be flat (`identity`) or path-like (`stdlib/list/identity`) is a tooling-layer decision the design does not pin. Emergent practice will shape the convention.

- *Manifest evolution.* When a library author updates a manifest's exports, consumers that had pinned the old manifest hash continue to resolve to the old version; consumers that pin the new hash see the new version. Whether tooling should track "this consumer is two manifest-versions behind" is open and the proposal does not address it.

- *Manifest as agent emission target.* Agents that emit code today produce a single root node. Whether agents should naturally emit a manifest (declaring the bundle of public capabilities they produced) is a Q-021 evaluation question. The proposal makes this possible but does not require it.

- *Cross-manifest effect aggregation.* If a manifest exports nodes that reference *other* manifests transitively, whether the export's `declaredEffects` should aggregate the transitive closure or stop at the immediate body is an open semantic choice. The proposal picks "exact match to the export body's closure" (which already accounts for transitive references via NodeRef closure rules) for consistency with how all closure computation works today.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `impl-kotlin/hashing/src/main/kotlin/org/strand/hashing/NodeResolver.kt` | New file — `NodeResolver` interface returning `SubgraphFetch`, plus `LocalHashStoreResolver`, `ChainedResolver`, `FileSystemResolver`, `CachingResolver`, `NoOpResolver`, `NodeResolverIntegrityViolation` exception, `SubgraphFetch` data class, subgraph-walking helper | Medium |
| `impl-kotlin/core/src/main/kotlin/org/strand/core/NodeTranslate.kt` | New file — `Node.translateNodeIds(fn: (NodeId) -> NodeId): Node` extension defined exhaustively over the Node sealed hierarchy. Mechanical case-per-variant; rewrites every `NodeId`-typed field. NodeRef is unchanged (target is a Hash). Used by `FederatedProgram.fetchAndAdmit` to re-base foreign NodeIds into the local store after admission | Medium |
| `impl-kotlin/hashing/src/main/kotlin/org/strand/hashing/NameRegistry.kt` | New file — `NameRegistry` data class + JSON load/save | Small |
| `impl-kotlin/core/src/main/kotlin/org/strand/core/Node.kt` | Add `Node.ModuleManifest(exports: List<ManifestExport>, description: NodeId?, manifestSignature: ByteArray?)`; add `ManifestExport(target: Hash, declaredEffects: List<NodeId>, displayName: String)` data class | Medium |
| `impl-kotlin/hashing/src/main/kotlin/org/strand/hashing/CategoryTag.kt` | Add `ModuleManifest = 46` | Small |
| `impl-kotlin/hashing/src/main/kotlin/org/strand/hashing/CanonicalEncoder.kt` | `encodeModuleManifest` case — positional export list, set-semantics effect-hash sorting, metadata exclusion | Medium |
| `impl-kotlin/hashing/src/main/kotlin/org/strand/hashing/Hasher.kt` | Walk case for `ModuleManifest` — visits each `export.target` and the effect-category hashes; updates `FinalizedProgram.federated(resolver): FederatedProgram` extension | Small |
| `impl-kotlin/core/src/main/kotlin/org/strand/core/JsonIngest.kt` | New JSON ingest case `{ "type": "ModuleManifest", "exports": [...] }`; per-export schema | Medium |
| `impl-kotlin/verifier/src/main/kotlin/org/strand/verifier/Verifier.kt` | NodeRef resolution via resolver fallback; ModuleManifest admission rules (effect-closure match per export, export-target resolvability); new `VerifyError.NodeRefTargetUnresolvable`, `NodeRefTargetVerificationFailed`, `ManifestExportEffectMismatch`, `ManifestExportTargetUnresolvable` | Medium-Large |
| `impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt` | NodeRef evaluation extended with resolver fallback; ModuleManifest treated as non-evaluable (any direct evaluation produces `Value.UnitV` with a debug warning) | Small |
| `impl-kotlin/vm/src/main/kotlin/org/strand/vm/Vm.kt` | Same NodeRef change for the bytecode VM; ModuleManifest opcode is a no-op | Small |
| `impl-kotlin/runtime/src/main/kotlin/org/strand/runtime/StateMachineRuntime.kt` | Carries the resolver into MachineGroup; per-actor NodeRef resolution uses it | Small |
| `impl-kotlin/schema/src/main/kotlin/org/strand/schema/SchemaChecker.kt` | Static-evaluation walk follows resolver across NodeRef boundaries | Small |
| `impl-kotlin/cli/src/main/kotlin/org/strand/cli/Cli.kt` | New flags `--peer-store`, `--registry`, `--no-cache`, `--strict-integrity`. New subcommands `strand registry resolve|put|list`, `strand verify-manifest`, `strand corpus federate` | Medium |
| `impl-kotlin/authoring/src/main/kotlin/org/strand/authoring/LayerAGrammar.kt` | New Layer A code `MFT <export>...` for ModuleManifest construction; `MEX <target> <displayName> [effects]` for individual exports | Medium |
| `impl-kotlin/authoring/src/main/kotlin/org/strand/authoring/Elaborator.kt` | Name-to-hash resolution at compile time when `(NRF "<name>")` form is used and a registry is configured | Small |
| `impl-kotlin/hashing/src/test/kotlin/org/strand/hashing/NodeResolverTest.kt` | Resolver chain, integrity violation, caching | Medium |
| `impl-kotlin/hashing/src/test/kotlin/org/strand/hashing/ModuleManifestEncodingTest.kt` | Canonical encoding tests; displayName-irrelevance; export-order-significance; effect-hash-sort | Medium |
| `impl-kotlin/verifier/src/test/kotlin/org/strand/verifier/CrossStoreVerifierTest.kt` | NodeRefTargetUnresolvable, NodeRefTargetVerificationFailed, transitive resolution, effect closure across stores | Medium |
| `impl-kotlin/verifier/src/test/kotlin/org/strand/verifier/ModuleManifestVerifierTest.kt` | Effect-closure match per export, under-declaration rejected, over-declaration rejected, export-target unresolvable | Medium |
| `impl-kotlin/cli/src/test/kotlin/org/strand/cli/CliFederationTest.kt` | Multi-`--peer-store` chain, `--registry` round-trip, `strand verify-manifest`, `strand corpus federate` driver | Medium |
| `corpus/76-multi-store-composition/` | `lib-list-ops.json` + `lib-bool-ops.json` + `app-program.json` + expected output | Medium |
| `corpus/77-name-registry-resolution/` | Registry file + app referencing registry names | Small |
| `corpus/78-integrity-violation-rejected/` | Adversarial resolver returning wrong content; expected verifier rejection | Small |
| `corpus/79-module-manifest-with-effects/` | Manifest exporting an effectful Lambda; declared effects match closure | Medium |
| `corpus/80-manifest-effect-mismatch-rejected/` | Manifest under-declaring an export's effects; expected verifier rejection | Small |
| `INDEX.md` | Q-043 registered; N-046 added to node registry; identifier-registry blurb updated; Last revised line updated | Small |
| `open-questions.md` | Q-043 entry added with status Proposed; resolution mentions both federation and N-046 ModuleManifest | Small |
| `proposals/README.md` | New row in current proposals table; reading-order updated | Small |
| `impl-kotlin/CLAUDE.md` | New entry under Known gaps pointing at this proposal; Layer 2 step 3 status | Small |
| `impl-kotlin/README.md` | Layer-scope update | Small |
| `design/node-algebra.md` | New N-046 row in node inventory; new subsection describing ModuleManifest; one-sentence addition to N-019 NodeRef noting cross-store resolution | Medium |
| `decisions/ADR-003-content-addressing.md` | Already amended 2026-05-28 to clarify content-addressed grouping is allowed | Done |

**Order of work.**

1. Add the `NodeResolver` interface and its concrete implementations to `:hashing`. Unit tests in isolation.
2. Add `FederatedProgram` and the `FinalizedProgram.federated` extension.
3. Extend `Verifier` NodeRef handling with resolver fallback. Add the two cross-store VerifyError variants.
4. Extend `Interpreter`, `Vm`, `StateMachineRuntime`, `SchemaChecker` to thread the resolver through. End-to-end cross-store tests pass.
5. Add the `Node.ModuleManifest` case to `core`. JSON ingest + canonical encoding + hasher walk.
6. Verifier admits ModuleManifest with effect-closure match per export. Add the two manifest-specific VerifyError variants.
7. Add `NameRegistry` and `strand registry` CLI subcommands.
8. Add `strand verify-manifest` CLI subcommand.
9. Add Layer A grammar codes for ModuleManifest emission.
10. Author corpus programs 76-80. Wire into `CorpusTest`. End-to-end pass.
11. Update INDEX, open-questions, proposals/README, impl-kotlin/CLAUDE.md, impl-kotlin/README.md, design/node-algebra.md.

Steps 1-4 are pure federation (could ship independently as "Layer 2 step 3a"); steps 5-9 are the manifest (could ship as "Layer 2 step 3b"). Splitting into two commits keeps the implementation work reviewable.

**Not in this slice.**

- Network-backed resolvers (HTTP, IPFS, S3).
- Signed manifest verification protocol; Q-006 trust integration.
- Persistent on-disk cache.
- Multi-version registry semantics.
- Sealed exports / capability-based visibility.
- Cross-store garbage collection.
- Async resolver interface.
- Hierarchical manifest enforcement.

## 10 Implementation status

**Step 3b — N-046 ModuleManifest — implemented 2026-05-28 (Kotlin/JVM reference implementation).** The node category ships end-to-end: `Node.ModuleManifest(exports: List<ManifestExport>, manifestSignature: ByteArray?)` with `ManifestExport(target: Hash, declaredEffects: List<NodeId>, displayName: String)` in `:core`; a `StoredNode.RawModuleManifest` / `RawManifestExport` raw intermediate that JSON ingest produces and `Hasher.finalize` rewrites to the canonical hash-carrying form (the same raw→canonical bridge `NodeRef` uses, generalized to a node embedding several hash targets); CategoryTag 46 and a dual raw/canonical canonical encoder whose two paths are byte-identical by construction (export list positional, each export's declaredEffects a lex-sorted multi-hash set, displayName + signature excluded); the verifier admission rule certifying each export's effect surface against `declaredEffects`; passive interpreter and SchemaChecker handling (`Value.UnitV`). Corpus 79 (effectful export matching declaration, accepted) and 80 (under-declaration, rejected) plus `ModuleManifestEncodingTest`, `ModuleManifestVerifierTest`, and `CorpusManifestTest` cover the encoding properties and the accept / under-declare / over-declare / unresolvable-target / non-root-certification paths.

Three deviations from the proposal text worth recording:

1. **"Closure of the target" is implemented as "effect surface."** § 5.4 says the verifier checks `declaredEffects` against `closureOf(export.target)`. In Strand's closure model a bare Lambda has an *empty* closure — constructing a closure value exercises no effects; effects release at call sites (`inferApplication` adds the callee's `FunctionType.effects` to the call's closure). Taken literally, § 5.4 would force every function export to declare `[]`, making the feature useless for the common case and contradicting test scenario 12 (an effectful-Lambda export). The implementation therefore certifies the export's *effect surface*: for a function-typed export (Fun, or a Forall over a Fun) the function's declared effect row, for any other export its construction closure. This is the quantity a consumer incurs by *using* the export, which is what the proposal's § 4.8 ("bundle-level effect declarations") intends.

2. **The `description → Provenance` metadata edge is not modelled.** Provenance (N-031) is not yet a node category in the reference implementation; the codebase already omits Provenance metadata edges on `ForeignNode` (the `binding` edge) and `Schema` (the `libraryBinding` edge). `ModuleManifest` follows that precedent — `manifestSignature: ByteArray?` (the Q-006 signing surface) is retained as the metadata field; `description` is dropped until Provenance lands.

3. **Layer A grammar codes MFT / MEX are deferred.** `ManifestExport` is an inline sub-object list (a target plus a nested declaredEffects list plus a display name); the Layer A grammar maps one line to one dag-json node with ref/string/list-of-ref fields and has no facility for emitting inline object arrays. This is the same shape the codebase already defers for Q-039 `effectProjections` ("Layer A has no compact code for the inline projection object; non-prelude nodes emit canonical dag-json directly"). Manifests are low-frequency tooling/distribution artifacts; corpus 79/80 are authored as canonical dag-json. A future authoring-layer extension (an inline-sub-object-list ArgKind) can add MFT / MEX when an agent-emission workload needs them.

Note that `ManifestExportTargetUnresolvable` is, in a single-store program, unreachable through JSON ingest — every export target author-id resolves to an in-document node that `finalize` hashes into `hashToNodeId`. The diagnostic exists for the federated path (a manifest referencing an export held in a peer store) and is exercised by a programmatically-constructed dangling reference in `ModuleManifestVerifierTest`.

**Step 3a — federation runtime — core landed 2026-05-29; tooling + remaining backends pending.** The bound-node blocker is resolved and a cross-store program now verifies and runs end-to-end.

*Landed:*
- **`Node.translateNodeIds(fn)`** (`core/NodeTranslate.kt`) — the exhaustive NodeId-rewrite (structural dual of `childNodeIds`), covering binder declarations and metadata edges that `childNodeIds` omits.
- **`SubgraphFetch` reshaped** to key nodes by *foreign NodeId* (including bound nodes, which have no hash) plus `rootId`; `LocalHashStoreResolver` renamed to **`LocalProgramResolver`** and rebased onto a `FinalizedProgram`'s `NodeStore` — a hash-keyed `HashStore` cannot serve bound nodes, the original blocker. The resolver walk enumerates references via `translateNodeIds`, so the fetch carries every node the re-base needs.
- **`FederatedProgram.fetchAndAdmit`** implemented: a post-order DFS (`translateNodeIds { admit(it) }`) with early-memoization — the local id is reserved before recursing — so reference cycles (Schema↔Invariant; a VarRef to an enclosing binder) resolve; hashable nodes dedup against the local store by hash, bound nodes admit fresh. Integrity is the trust-minimizing check refined to a single root re-hash (Merkle: the root hash binds the whole subgraph; a mis-rebased binder shifts a de Bruijn position and is caught) → `NodeResolverIntegrityViolation`.
- **Verifier and Interpreter threading** via an optional trailing `resolveTarget: ((Hash) -> NodeId?)?` callback (default null) wired to `fetchAndAdmit`. Kept `:verifier` / `:interpreter` at `→ core` (no `:hashing` dependency); all ~41 existing construction sites compile unchanged. A federated NodeRef miss reports `NodeRefTargetUnresolvable`; a non-federated miss preserves the legacy `NodeRefTargetNotFound` / `NodeRefTargetNotInStore`. Coverage: `NodeTranslateTest`, reshaped `NodeResolverTest` / `FederatedProgramTest` (incl. cross-store Lambda re-base + integrity violation), `CrossStoreVerifierTest`, `CrossStoreInterpreterTest`.

*Also landed (parts 3-4, 2026-05-29):*
- **External-hash NodeRef ingest.** A `NodeRef` may declare `targetHash` (a hex content hash) for a cross-store reference, alongside the local-author-id `target`; it materializes a canonical `Node.NodeRef` directly (exactly one of the two is allowed). `Hasher.walkChildren` now treats a canonical `NodeRef` in the raw store as a boundary (a `RawNodeRef` targets a local id; a canonical NodeRef is the external form).
- **CLI `--peer-store`** (repeatable) on `run` / `verify`. Chains a `LocalProgramResolver` per peer program and federates the app via `FinalizedProgram.federated`; threads `fetchAndAdmit` into the verifier and interpreter (only when peers are present, preserving the single-store path). Verified end-to-end on **corpus 76** (`76-multi-store-composition/` — `lib.json` exports `inc`, `app.json` references it by `targetHash` and computes `inc(inc(40))`): `strand run app.json --peer-store lib.json` → `value: IntV(42)`. `CorpusFederationTest` drives the same path in-suite.

*Still pending (the rest of step 3a):*
- **VM / `StateMachineRuntime` / `SchemaChecker` threading** — the same `resolveTarget` callback; needed only for cross-store programs that use those backends (the verifier+interpreter path already runs cross-store function/value composition).
- **`--peer-store` on `machine` / `group`** (coupled with the `StateMachineRuntime` threading), plus the `--registry` / `--no-cache` / `--strict-integrity` flags and `strand registry resolve|put|list` subcommands; **corpus 77 / 78**; a formal `CliFederationTest` (the `:cli` module has no test source set and `main()` uses `exitProcess`, so the part-4 CLI demo was verified by a real invocation rather than an automated test; the federation logic itself is covered by `CorpusFederationTest`).

## References

**Outgoing references:**

- [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — amended 2026-05-28 to clarify content-addressed grouping primitives are compatible. This proposal is the first such primitive.
- [`design/node-algebra.md`](../design/node-algebra.md) § N-019 NodeRef — describes NodeRef as "the mechanism for cross-module references"; this proposal builds the resolver that makes that real plus the manifest that gives bundles aggregate identity.
- [`design/distribution-model.md`](../design/distribution-model.md) — the federation primitives here are the substrate that future distribution work (Q-008, Q-014, Q-016) will build on.
- [`proposals/implemented/state-machines-runtime-step-3.md`](implemented/state-machines-runtime-step-3.md) — the runtime threading pattern (StateMachineRuntime taking new parameters) used here.
- [`open-questions.md`](../open-questions.md) — Q-006 (trust model), Q-014 (scheduler), Q-016 (network failure), Q-024 (versioning).

**Incoming references (to be added on implementation):**

- [`open-questions.md`](../open-questions.md) — Q-043 entry points at this proposal.
- [`proposals/README.md`](README.md) — current-proposals table row.
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section.
- [`INDEX.md`](../INDEX.md) — N-046 row in node registry; concept-index row for cross-store composition and module manifests.
- [`design/node-algebra.md`](../design/node-algebra.md) § N-046 ModuleManifest — new subsection on the manifest primitive.
