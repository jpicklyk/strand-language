# Persistent local store and run-by-hash

**Document:** `proposals/persistent-store.md`
**Status:** Draft proposal
**Date:** 2026-06-13
**Concerns:** [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md), [`design/canonical-encoding.md`](../design/canonical-encoding.md) (the Epoch log), [Q-058](../open-questions.md#Q-058), [Q-043](../open-questions.md#Q-043) (federation: `NodeResolver` chain, `NameRegistry`, `--peer-store`), [Q-024](../open-questions.md#Q-024) (versioning, which this makes load-bearing), [Q-054](../open-questions.md#Q-054) (the `StrandRuntime` facade and `ProgramImage`), [Q-063](../open-questions.md#Q-063) (the bundled prelude module the registry resolves against)
**Scope:** Medium

This proposal closes the gap that the content-addressed store is an in-memory map per CLI invocation. It defines an on-disk, hash-keyed store; admit-and-verify-once semantics with cached verdicts and read-back integrity; a run-by-hash path on the Q-054 facade and the CLI; and registry integration so a name resolved through `strand registry` dereferences against the local store. It composes with the Q-043 federation machinery rather than duplicating it: the persistent store is exposed as a `NodeResolver` that slots into the existing resolver chain, and admission reuses the existing trust-minimizing Merkle re-hash.

## 1. Problem statement

The reference implementation holds every graph in an in-memory `NodeStore` (and the post-finalize `hashToNodeId` reverse map) for the duration of one process. `strand run app.json` ingests the authored dag-json, runs `Hasher.finalize`, verifies, and evaluates — and discards all of it on exit. The next invocation repeats the entire pipeline from the file. Across runs, content addressing buys none of its advertised operational properties:

- **No admit-once / run-by-hash.** The unit of operation is a file path, as in a scripting language, except the file is by design not human-readable (ADR-001, ADR-002), which makes the absence of a store-backed workflow more costly than it would be elsewhere. There is no way to say "run the program whose root hash is X" because nothing persists X.
- **No dedup across programs.** Two programs that share a subgraph (a common library export referenced by hash) re-ingest and re-hash that subgraph independently every time.
- **No cached verdicts.** Verification is recomputed on every run even when the exact same root hash was verified yesterday and admitted clean.
- **The registry is half a mechanism.** `strand registry resolve <name>` (Q-043 § 4.6) returns a content hash, but nothing can dereference that hash without a hand-passed `--peer-store <file>` pointing at a program that happens to contain it. A resolved name is not runnable.

Q-043 deferred persistent caching of resolver fetches (proposal § 10). This question covers the broader substrate: an on-disk hash-keyed store with a defined layout that records its format version and the canonical-encoding epoch per ADR-003's multi-hash discipline, admit-and-verify-once with recorded verdicts, run/verify/machine/group by root hash, and registry integration. Once graphs persist, the Q-024 migration story carries real weight: a store written under one canonical-encoding epoch must not be silently misread by a reader compiled against a later epoch (`design/canonical-encoding.md` Epoch log; `CanonicalEncoding.EPOCH`).

## 2. Prior art

- **Git object database** — content-addressed blobs/trees/commits under `.git/objects/`, sharded by the first byte of the SHA hex (`ab/cdef…`). Objects are immutable, deduplicated by hash, and integrity is implied by re-hashing on read (`git fsck`). The sharded-by-prefix directory layout and the "the hash is the name, re-hash to verify" discipline are directly applicable.
- **IPFS / IPLD block stores** — blocks keyed by CID (a self-describing multihash with a codec prefix), with the codec and hash function recorded *in the key*. Strand's `Hash` already carries the BLAKE3 multihash prefix; the store additionally records the canonical-encoding epoch out-of-band (there is no in-band epoch marker per the Q-062 policy), so a single store directory is single-epoch and fails closed on mismatch.
- **Nix store** — `/nix/store/<hash>-<name>`, where the hash is over the build inputs; a path's mere presence is the admit-once signal, and a separate SQLite database records validity (the analogue of a cached verdict). The split between the content-addressed object set and the side metadata recording "this was admitted clean" is the shape adopted here.
- **Bazel action cache** — caches the *result* of an expensive computation (an action) keyed by a hash of its inputs, so a repeated action is a cache hit rather than a recomputation. The verify-verdict cache is exactly this pattern applied to the verifier: the verdict is the result, the root hash is the input key.

## 3. Recommended approach

A `PersistentStore` is a directory with a recorded format version and encoding epoch, holding one file per hashable node (keyed by the node's full multihash hex, sharded by the leading byte) and one file per cached verify verdict (keyed by root hash). It composes with Q-043 in three places rather than duplicating any of it:

1. **Reading** is exposed as a `DiskStoreResolver : NodeResolver` that serves a `SubgraphFetch` for any held hash. It slots into the existing `ChainedResolver` ahead of (or instead of) `--peer-store` resolvers, so every backend that already threads `resolveTarget` (verifier, interpreter, VM lowerer, `StateMachineRuntime`, `SchemaChecker`) dereferences the local store with no new threading. Admission reuses `FederatedProgram.fetchAndAdmit`'s trust-minimizing Merkle root re-hash unchanged.
2. **Writing** walks a finalized program's reachable nodes and persists each hashable node once, keyed by hash (dedup is automatic — a node already on disk is a no-op write).
3. **Run-by-hash** on the facade builds a `ProgramImage` by reading the root's subgraph from the store into a fresh `NodeStore`, then proceeds exactly as the file path does.

The store is the foundational layer; admit-and-verify-once and the verdict cache sit on top; the facade and CLI surfaces sit on top of that. Local single-machine scope only — networked resolvers, cross-store garbage collection, and compaction are deferred (§ 8).

## 4. Detailed mechanism

### 4.1 Store layout

A store is a directory (default `~/.strand/store`, overridable). Layout:

```
<store-dir>/
  store.json                      format + epoch record (see § 4.2)
  nodes/
    1e/                           shard = first multihash byte as 2 hex chars (0x1e = BLAKE3 prefix)
      1e7a3c….json                one hashable node, named by its full multihash hex
      1e9f01….json
    …
  verdicts/
    1e/
      1e7a3c….json                cached verify verdict, keyed by ROOT hash
```

Sharding is by the first byte of the multihash rendered as two hex chars. With the BLAKE3-only reference implementation every shard is `1e` today; the scheme generalizes when SHA-256 (a different prefix, ADR-003) is added, and keeps any single directory from growing without bound once the second shard level is introduced (deferred — see § 8). The file name is the full lowercase-hex multihash (`Hash.toString()`), so the key is recoverable from the name and a corrupt/renamed file is detectable.

### 4.2 Format and epoch record

`store.json` is the self-describing header, written when the store is first initialized and checked on every open:

```json
{
  "storeFormatVersion": 1,
  "encodingEpoch": 1,
  "hashFunction": "blake3"
}
```

- `storeFormatVersion` is the on-disk layout/codec version, independent of the canonical encoding. A reader that understands only version 1 rejects a version-2 store with a structured error rather than misreading it.
- `encodingEpoch` records `CanonicalEncoding.EPOCH` at the time the store was written. The store is **single-epoch by construction** — there is no in-band epoch marker per the Q-062 policy, so a store directory carries exactly one epoch. A reader compiled against epoch 2 opening an epoch-1 store **fails closed** with a structured `StoreEpochMismatch(expected=2, found=1)` rather than admitting nodes whose bytes would re-hash differently under the new encoding. This is the point at which Q-024 becomes load-bearing: the migration story (re-ingest under the new epoch, or a future epoch-aware transcoder) has a concrete artifact to migrate. Writing into an existing store whose recorded epoch differs from the running `CanonicalEncoding.EPOCH` is the same hard error — a store is never silently mixed-epoch.
- `hashFunction` records the multihash family for human inspection; the prefix byte in every key is authoritative.

A missing `store.json` on an otherwise-empty directory is "uninitialized" — the first write initializes it with the running epoch and format version. A missing `store.json` beside existing `nodes/` is a corrupt store and is a hard error (fail closed).

### 4.3 Node file format

Each `nodes/<shard>/<hash>.json` holds one canonical hashable node, re-expressed in a context-free Merkle form so it can stand alone and be re-hashed:

```json
{
  "storeFormatVersion": 1,
  "hash": "1e7a3c…",
  "node": { "type": "<NodeType>", …fields… }
}
```

The `node` object is the canonical node with its child edges rewritten:

- A child NodeId that points at a **hashable** node is rewritten to that child's hash, written as `{"$ref": "1e…"}` in the position the child id occupied (e.g. `Application.function` becomes `{"$ref": "…"}`; `Lambda.parameters` is a list whose entries are bound-node inlines, see below; `Application.arguments` is a list of `{"$ref": …}`).
- A child NodeId that points at a **bound** node — `ParameterDecl`, `TypeParameter`, `RecursiveSelf` — is **inlined** recursively in place (these have no standalone hash; the canonical encoder encodes them inline within their parent anyway). A `ParameterDecl`'s own `paramType` child is itself rewritten by the same rules (it is typically hashable, so a `$ref`).
- A `NodeRef.target` (already a `Hash`) and each `ManifestExport.target` (already a `Hash`) are written as the hash hex directly — they are the existing cross-subgraph boundary and need no rewriting.
- `VarRef.binder` points back at an enclosing binder (a `ParameterDecl` or `Let`). Within a single stored node this is never a forward hashable child; binder back-references are reconstructed during admission exactly as the federation re-base does (the binder node is re-admitted as part of the same subgraph and the back-reference is re-targeted). The store records `VarRef.binder` using a within-subgraph **local index** into the subgraph's node list rather than a hash, mirroring how `SubgraphFetch` keys nodes by foreign NodeId.

In practice this codec is the persistent twin of the in-memory `SubgraphFetch`: the simplest faithful implementation serializes, per root hash, the whole `SubgraphFetch` (the map of foreign-NodeId → node plus the `nodeIdToHash` map) and shards the *hashable* members out as individual `nodes/` files for dedup, with bound nodes carried inline in the file of the hashable node that owns them. Reading reassembles a `SubgraphFetch` and hands it to `FederatedProgram.fetchAndAdmit`. This keeps the read path bit-identical to the federation admission already proven by `CorpusFederationTest` and avoids a second, divergent re-base implementation.

### 4.4 Verdict file format

Each `verdicts/<shard>/<rootHash>.json` records the verify outcome for a root hash:

```json
{
  "storeFormatVersion": 1,
  "encodingEpoch": 1,
  "rootHash": "1e…",
  "verdict": {
    "kind": "ok",
    "rootType": "<rendered TypeExpr>",
    "nodeTypes": { "1e…": "<rendered TypeExpr>", … },
    "warnings": [ "<rendered VerifyWarning>", … ]
  }
}
```

or, for a failed verify:

```json
{ …, "verdict": { "kind": "failed", "errors": [ "<rendered VerifyError>", … ] } }
```

The verdict is keyed by root hash and is only consulted when the store also holds the full subgraph for that root (so the verdict and the nodes it certifies cannot drift apart). The `nodeTypes` map is keyed by node hash (stable across runs) rather than NodeId (per-run, unstable), and is re-projected onto the freshly-admitted NodeIds on read. A verdict whose recorded `encodingEpoch` differs from the running epoch is ignored (re-verify) rather than trusted — defense in depth beneath the store-level epoch gate.

The rendered-string form of types/warnings/errors is the reference-implementation choice (it matches what the CLI already prints and what `RunOutcome` carries to the CLI); the verdict cache restores enough to reproduce the CLI's rendering and the facade's `RunOutcome.Ok.verify` shape for the schema-obligation and capability-grant paths. The `nodeTypes` `TypeExpr` values that the interpreter's schema-obligation wiring needs are reconstructed by re-projection on the admitted store; where a faithful structured round-trip of `TypeExpr` is required (the schema-obligation map feeds `Interpreter`), the verdict cache stores the structured `nodeTypes` rather than only the rendered string. (See § 8 — the structured `TypeExpr` codec is the one piece with real surface area; the conservative fallback is to cache only the pass/fail decision plus warnings and re-derive `nodeTypes` by a cheap re-verify of the already-admitted local store, which still skips re-ingest and re-hashing.)

### 4.5 Admit-and-verify-once

`ingest` of a program into the store:

1. Finalize the authored dag-json to a `FinalizedProgram` (existing path).
2. For each reachable hashable node, write its `nodes/` file if absent (dedup: present → skip). Bound-node descendants are carried inline in their owning hashable node's file.
3. Verify the root (existing `Verifier`), and write the `verdicts/` file keyed by root hash.

A subsequent `run`/`verify`/`machine`/`group` **by root hash**:

1. Open the store; check `store.json` format + epoch (fail closed on mismatch).
2. If the verdict file for the root hash is present and its epoch matches, reuse it — **no re-ingest, no re-hash, no re-verify**. If the cached verdict is `failed`, the operation reports the cached errors and stops, exactly as a fresh verify would.
3. Read the root subgraph into a fresh `NodeStore` via the `DiskStoreResolver` + `fetchAndAdmit` path. The admission re-hashes the admitted root and **fails closed** if it does not equal the requested hash (`NodeResolverIntegrityViolation`) — a corrupted or tampered node file is rejected, never trusted.
4. Build a `ProgramImage` and proceed (run / runMachine / runGroup).

Admit-once means **verification happened once and was recorded**, never that verification is skipped on first admission. The first ingest always verifies; only the recorded verdict is reused thereafter.

### 4.6 Integrity (fail closed)

Two integrity layers, both fail-closed:

- **Per-node re-hash on read.** A `DiskStoreResolver` serving a hash re-hashes the node it read and requires it to equal the file's key (and the requested hash). This catches a bit-flip or a tampered file at the leaf.
- **Merkle root re-hash on admission.** `FederatedProgram.fetchAndAdmit` recomputes the canonical hash of the admitted local root and requires it to equal the requested hash. By the Merkle property the root hash binds the entire reachable subgraph, so this single check detects any unfaithful admission anywhere in the subgraph (this is the existing Q-043 trust-minimizing check, reused unchanged).

A failure at either layer raises a structured error and halts; a corrupted store entry is never silently used.

### 4.7 Registry integration

The CLI's default registry already resolves `prelude` and every reserved name through the bundled prelude module (Q-063). This proposal adds: when a name resolves to a hash, that hash is **runnable** because the local store can dereference it. Concretely:

- `strand registry put <name> <hash>` records the name→hash binding (unchanged). The hash is expected to be present in the local store (put it there with `strand store ingest`).
- A `strand run --store <dir> <name-or-hash>` (or the registry-resolved hash) dereferences against the local store via the `DiskStoreResolver`, with no hand-passed `--peer-store`. The registry stays the off-graph name layer (Q-043 § 4.6); the store is the on-disk content layer; the `DiskStoreResolver` is the bridge. The prelude's resolved names dereference against the bundled prelude snapshot ingested into the store (or served by the existing prelude resolver in the chain), so a resolved prelude export is runnable too.

The `NameRegistry` and `NodeResolver` chain are composed, not duplicated: the registry maps name→hash; the chain (disk store first, then any `--peer-store`, then the prelude resolver) maps hash→subgraph.

### 4.8 Worked example

`strand store ingest app.json --store ~/.strand/store` where `app.json` is the corpus "add 40 + 2" program:

- Finalize → root hash `1eADD…`, plus hashes for the two `IntLit`s, the `Application`, the `Int.Add` ForeignNode, etc.
- Write `nodes/1e/1eADD….json` (the Application, children as `$ref`s), `nodes/1e/1e2A….json` (IntLit 42 — wait, 40), and so on. Bound nodes (none here) would be inlined.
- Verify → Ok, `rootType = Int`. Write `verdicts/1e/1eADD….json`.

Then `strand run --store ~/.strand/store 1eADD…`:

- Open store: format 1, epoch 1 — match.
- Verdict for `1eADD…` present, epoch 1 — reuse (`rootType = Int`, no warnings). No re-ingest, no re-verify.
- `DiskStoreResolver` + `fetchAndAdmit(1eADD…)` reconstructs the NodeStore; root re-hashes to `1eADD…` — integrity OK.
- Build `ProgramImage`, `runtime.run` → `value: 42`. Identical to `strand run app.json`.

Now `strand store ingest app2.json` where `app2.json` shares the `Int.Add` ForeignNode subgraph: that node's hash is already on disk, so its file write is a no-op — shared subgraph stored once.

## 5. Verifier rules

None. The store consumes the existing verifier verdict and the existing federation admission; it adds no node category, no canonical-encoding change, and no new well-formedness rule. The verify-before-admit invariant is preserved (admit-once records a verdict that a real verify produced).

## 6. Interpreter / runtime semantics

No change to evaluation. The store provides a `ProgramImage` (store + root + `hashToNodeId` + `resolveTarget`) identical in shape to the one the file path produces; the interpreter, VM, `StateMachineRuntime`, and `SchemaChecker` see exactly what they see today. The new surface is on the facade:

- `StrandRuntime.loadImageFromStore(store, rootHash): ProgramImage?` — read-by-hash, returning null when the root is not held.
- `StrandRuntime.ingestToStore(store, finalizedProgram, verifyResult)` — write nodes + verdict.
- The `run` / `runMachine` / `runGroup` / `verifyAndCheckSchema` entry points are unchanged; they take a `ProgramImage` whether it came from a file or from the store.

The `DiskStoreResolver` is a `NodeResolver`, so it composes into the existing chain and is threaded by the existing `resolveTarget` plumbing — no backend changes.

## 7. Test scenarios

1. **Round-trip** — ingest a finalized corpus program into a fresh store, read it back by root hash into a new `NodeStore`, admit, and assert the admitted root re-hashes to the original root hash and the program runs to the same value as the file path.
2. **Node-level dedup** — ingest two programs sharing a subgraph; assert the shared hashable node's file is written once (second ingest is a no-op for that hash) and the `nodes/` count reflects the union, not the sum.
3. **Corruption fails closed** — ingest, then flip a byte in a `nodes/` file; reading that hash raises a structured integrity error (per-node re-hash or Merkle root re-hash), never returns the corrupted node.
4. **Epoch mismatch fails closed** — write a store with `encodingEpoch: 1`, open it with a reader whose `CanonicalEncoding.EPOCH` is stubbed to 2; the open raises `StoreEpochMismatch` and admits nothing.
5. **Format-version rejection** — `store.json` with `storeFormatVersion: 2` is rejected by a version-1 reader with a structured error.
6. **Verdict reuse skips re-verify** — ingest (verify runs once, recorded); a second run-by-hash with a verifier instrumented to count invocations performs zero verifier calls and reuses the recorded `rootType`/warnings.
7. **Cached failed verdict** — ingest a program that fails verification; the verdict records the failure; a run-by-hash reports the cached errors and exits non-zero without re-verifying.
8. **Run-by-hash equals run-from-file** — a `run` (and a `machine` trajectory) launched by root hash against the store produces the identical value/trace to the same program launched from its file.
9. **Registry dereference** — `registry put name hash` after `store ingest`; `run --store <dir> name` resolves the name to the hash and runs it with no `--peer-store`.
10. **Missing-root null** — `loadImageFromStore` for a hash the store does not hold returns null (the CLI reports "not found", exit non-zero) rather than throwing.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Garbage collection and compaction.** Nothing reclaims unreachable nodes or compacts the directory. A real deployment wants `strand store gc --roots <registry>` (mark-and-sweep from the registry's live hashes) and a packed-object format. Deferred; the layout (sharded, immutable, hash-keyed) is GC-friendly when it lands.
- **Network-backed sync and remote resolvers.** The store is local single-machine. Pulling a missing hash from a remote peer over HTTP/IPFS/S3 is a `NodeResolver` implementation that chains beneath the `DiskStoreResolver`; it is a downstream extension of the same interface (Q-043 already names networked resolvers as deferred).
- **Cross-store dedup beyond local.** Dedup is within a single store directory. Sharing one node-object set across multiple stores (a global content-addressed cache) is a layout generalization, not a semantic change, and is deferred.
- **Second shard level.** One shard byte suffices for the reference workloads; a balanced two-level shard (`1e/7a/…`) for stores with millions of nodes is a layout-version-2 change gated on need.
- **Concurrent writers.** Single-process, single-writer assumed (matching the Q-054 facade's sequential-embedding scope). File-locking for concurrent `strand store ingest` is deferred.

**Real research questions:**

- *Structured `TypeExpr` round-trip for the verdict cache.* The verdict's `nodeTypes` map feeds the interpreter's schema-obligation wiring and the CLI's rendering. A faithful structured `TypeExpr` JSON codec is the one piece with real surface area. The conservative fallback — cache the pass/fail decision plus warnings, and re-derive `nodeTypes` by re-verifying the already-admitted *local* store (which still skips re-ingest, re-hash, and the file parse) — is acceptable for the first slice if the structured codec proves heavy; the dedup, integrity, and run-by-hash properties hold either way. The implementing session picks based on how much of the verifier's cost is in `nodeTypes` synthesis versus the structural walk.
- *Verdict invalidation across verifier changes.* A cached verdict is only sound for the verifier version that produced it. The epoch gate covers encoding changes; a verifier-logic change that is not an encoding change (a new warning class, a tightened rule) is not caught by the epoch. The first slice scopes verdicts to the running build (a `verifierVersion` field beside `encodingEpoch`, bumped when verifier behavior changes) or simply re-verifies the admitted local store — recorded as the implementer's call.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `hashing/src/main/kotlin/org/strand/hashing/PersistentStore.kt` | New. The store: `init`/`open` with `store.json` format+epoch check (fail closed), `writeNode`/`readNode` with per-node re-hash, `writeVerdict`/`readVerdict`, sharded path layout, `StoreFormatError` / `StoreEpochMismatch`. | Medium |
| `hashing/src/main/kotlin/org/strand/hashing/NodeStoreCodec.kt` | New. The context-free node↔JSON codec (`$ref` hashable children, inline bound nodes, local-index binder back-refs) and the `SubgraphFetch` ↔ on-disk reassembly. | Medium |
| `hashing/src/main/kotlin/org/strand/hashing/NodeResolver.kt` | Add `DiskStoreResolver(store): NodeResolver` serving a `SubgraphFetch` per held hash, with per-node integrity re-hash. | Small |
| `runtime/src/main/kotlin/org/strand/runtime/StrandRuntime.kt` | Add `loadImageFromStore(storeDir, rootHash): ProgramImage?` and `ingestToStore(...)` plus a `VerdictCache` read on the by-hash paths; the `ProgramImage` shape is unchanged. (`:runtime` would gain a `:hashing` edge for the store — verify no cycle; if undesired, the store-read helper lives in a thin `:cli`-side adapter and the facade takes a pre-built `ProgramImage`.) | Medium |
| `cli/src/main/kotlin/org/strand/cli/Main.kt` | `--store <dir>` flag (with a `STRAND_STORE` env default); accept a root hash (or registry name) in place of a file path on `run`/`verify`/`machine`/`group`; new `strand store ingest <file> [--store <dir>]` subcommand; registry `resolve` documents the now-runnable hash. Existing file-path invocation unchanged. | Medium |
| `hashing/src/test/.../PersistentStoreTest.kt` | New. Round-trip, dedup, corruption fail-closed, epoch/format rejection, verdict reuse. | Medium |
| `cli/src/test/.../StoreCliTest.kt` (or `corpus`) | New. Run-by-hash equals run-from-file; registry dereference; missing-root null; verdict reuse skips re-verify; federation tests stay green. | Medium |

**Order of work.** (2) the store + node codec + tests; (3) admit-and-verify-once + verdict cache + run-by-hash on the facade + tests; (4) CLI surface + registry dereference + tests. Each step commits independently.

**Not in this slice.** GC/compaction, network-backed resolvers, cross-store dedup, second shard level, concurrent writers, structured `TypeExpr` verdict codec (fallback re-verify of the admitted local store is acceptable). The verify-before-admit invariant is not weakened: admit-once means verify ran once and was recorded.

## References

**Outgoing references:**
- [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — the multihash discipline the store keys on and records.
- [`design/canonical-encoding.md`](../design/canonical-encoding.md) — the Epoch log; the store records `CanonicalEncoding.EPOCH` and fails closed on mismatch.
- [`proposals/cross-store-federation.md`](cross-store-federation.md) — the `NodeResolver` chain, `SubgraphFetch`, `FederatedProgram.fetchAndAdmit` Merkle integrity, `NameRegistry`, and `--peer-store` this proposal reuses.
- [`proposals/implemented/embeddable-runtime.md`](implemented/embeddable-runtime.md) — the Q-054 `StrandRuntime` facade and `ProgramImage` the run-by-hash path extends.
- [`proposals/implemented/prelude-as-module.md`](implemented/prelude-as-module.md) — the bundled prelude snapshot the registry's resolved names dereference against.

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-058 points at this proposal.
- [`proposals/README.md`](README.md)
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section.
