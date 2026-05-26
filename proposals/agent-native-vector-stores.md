# Agent-native vector stores

**Document:** `proposals/agent-native-vector-stores.md`
**Status:** Draft proposal
**Date:** 2026-05-26
**Revised:** 2026-05-26 (second revision: operation-shaped effect categories with `provider` as a refinement parameter, consistent with [Q-037](agent-native-capabilities.md)'s second revision and with Strand's existing E-001..E-034 pattern. Per-provider ForeignNodes preserved)
**Concerns:** [Q-037](../open-questions.md#Q-037), [Q-038](../open-questions.md#Q-038), [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md), [`design/security-model.md`](../design/security-model.md), [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md)
**Scope:** medium (three shippable phases — Pinecone + Chroma first, then pgvector + FAISS, then richer providers)

The vector-storage and similarity-search surface that retrieval-augmented agents depend on. Split out of [Q-037](agent-native-capabilities.md) so its API-shape questions — metric grain, index opacity, filter expression language, typed metadata, batching, pagination, idempotency — can be reviewed and decided as a coherent set rather than as appendices to the LLM proposal. Consumes Bytes-encoded embeddings produced by Q-037's `*.Embed` builtins; produces vector-store handles that downstream queries traverse.

## 1. Problem statement

Retrieval-augmented agents are the canonical example of an agent workload that exceeds the single-call LLM surface. The pattern is: embed a query, look up nearest neighbors in a vector store, retrieve the associated documents, inject the documents into the LLM prompt as context, generate. The lookup step is irreducible — Strand programs cannot do it through Math.* primitives at any practical scale, and routing it through `Http.Request` against a vector-store HTTP endpoint loses the same structural-reasoning properties that motivated per-provider LLM ForeignNodes in Q-037.

The seven API design questions Vector.* must answer:

1. **Metric choice grain.** Per-collection (Pinecone, Weaviate model — pick at create time) or per-query (pgvector model — choose via operator on each query). The choice affects how a Vector.Query call is typed.
2. **Index-type opacity.** Self-hosted vector libraries (FAISS) expose ANN index types (Flat, IVF, HNSW, PQ) and require explicit construction. Managed services (Pinecone) hide them entirely. The Strand surface must commit to one shape or carry the variation explicitly.
3. **Filter expression language.** A query against vectors plus metadata needs a filter language. Options span (a) none — just vector queries, (b) simple key-equality maps, (c) structured boolean expressions (Qdrant, Weaviate), (d) full SQL (pgvector). The right answer depends on what the verifier can usefully reason about.
4. **Metadata shape.** Loose `JsonValue` for the prototype is the cheapest answer. A typed metadata story via the Schema mechanism (N-032 / N-033) is more powerful but adds surface area to design.
5. **Batching.** Real workloads insert by 100–1000 records at a time. The API must commit to single-record or bulk shape (or both).
6. **Pagination.** Query results past ~1000 need cursor-based pagination (Qdrant scroll API). Initial slice may punt.
7. **Idempotency.** `Vector.Insert(id="x", ...)` against an existing id can upsert (replace), fail with conflict, or be undefined. Providers diverge; the cross-provider abstraction must pick.

The question this proposal answers is what builtin surface Strand provides for vector storage and similarity search, and what answers it commits to on the seven questions above.

## 2. Prior art

The vector-store ecosystem is more fragmented than the LLM-provider ecosystem because the underlying engineering tradeoffs (managed vs self-hosted, ANN algorithm choice, scale tiers) are genuinely incommensurate.

**Pinecone.** Managed service. Indexes are created with a fixed metric (cosine, dotproduct, euclidean) and dimensionality. Operations: `index.upsert(vectors)`, `index.query(vector, top_k, filter, includeMetadata)`, `index.delete(ids)`, `index.fetch(ids)`. Filter is a structured boolean expression over metadata (`{"genre": {"$in": ["comedy", "documentary"]}, "year": {"$gte": 2019}}`). Idempotency: upsert semantics; insert with existing id replaces. ANN algorithm is hidden. Batching is the default (vectors is a list).

**Weaviate.** Managed or self-hosted. Collections (schemas) have configured vectorizers; manual vector input is also supported. Operations via GraphQL or REST. Rich filter language (`where: {operator: And, operands: [...]}`) with operators for ranges, GeoCoordinates, etc. Batching first-class. ANN parameters configurable per-collection.

**Chroma.** Open-source, Python-first, simple. Collection has add (upsert), query (vector + n_results + where_filter), delete. Filter is a flat key-equality dict at the basic tier; richer filters via Python predicates. ANN is HNSW under the hood, hidden. Designed for local dev / small scale.

**pgvector.** Postgres extension. Vectors are a SQL column type. Queries are SQL: `SELECT id FROM embeddings ORDER BY vector <-> '[1,2,3]'::vector LIMIT 10`. Metric chosen per-query via operator (`<->` L2, `<=>` cosine, `<#>` negative inner product). Indexes (HNSW, IVFFlat) are CREATE INDEX statements. Filter is full SQL WHERE clause. Idempotency: standard SQL primary-key constraints.

**FAISS.** In-process Python/C++ library. No persistence layer — programs build, train, save, and load indexes themselves. Many index types (`IndexFlatL2`, `IndexIVFPQ`, `IndexHNSWFlat`). No metadata storage; programs maintain a separate id-to-payload mapping. No filter language — filter the candidate set in Python before / after the search.

**Qdrant.** Managed or self-hosted. Collections have payload schemas. Search returns scored points with payloads. Filter is structured boolean (must/should/must_not). Batching and scroll API for pagination.

**Milvus.** Cluster-shaped. Collections with schemas, multiple index types (HNSW, IVF_FLAT, IVF_SQ8, etc.). Filter expressions via a DSL. Scale-out architecture.

The cross-provider intersection is narrow: open/close a handle, insert by id with vector + metadata, query by vector returning top-k with scores and metadata, delete by id. Everything beyond that is provider-specific.

**LangChain VectorStore abstraction.** Tries to paper over the differences with a unified `add_texts`, `similarity_search`, `delete` interface. Loses provider-specific features (advanced filtering, scroll pagination, custom index params) and adds an awkward "kwargs" escape hatch for each provider. Often cited as an example of where unification leaked: per-provider classes inherit the base but override most methods.

**LlamaIndex retrievers.** Similar story; per-provider `VectorStoreIndex` subclasses with shared base interface but heavy provider-specific overrides.

**Effect-system granularity for storage backends.** No effect-system research (Koka, Eff, OCaml 5, Frank) splits filesystem effects by underlying filesystem implementation, or splits database effects by vendor. The norm is one operation-shaped effect category with implementation discrimination at the binding/handler layer or via parameter refinement. Strand's existing `Filesystem.Read{path}` follows this convention; this proposal extends it to vector storage in the same shape.

The position this prior art puts us in: there is a cross-provider intersection (open/insert/query/delete) and substantial cross-provider variation. The language design question is how to expose the intersection in a way that doesn't lie about the variation, with one operation-shaped effect category per direction (read / write) and per-provider ForeignNodes as the bindings.

## 3. Recommended approach

Per-provider ForeignNodes under operation-shaped effect categories, consistent with [Q-037](agent-native-capabilities.md)'s second revision. The trust surface is per-binding, the graph's hash reflects which provider is in use, and capability scoping operates at the refinement-lattice parameter slots — exactly like Filesystem.Read scopes by path.

### 3.1 Effect categories (additions to [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md))

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-037 | Vector.Read | provider: String, store: String | Query / fetch from a vector store |
| E-038 | Vector.Write | provider: String, store: String | Insert / upsert / delete in a vector store |

The category is operation-shaped — `Vector.Read` is one effect whether Pinecone, Weaviate, Chroma, or pgvector executes it. The `provider` and `store` parameters discriminate at the refinement-lattice level. The Read / Write split mirrors `Filesystem.Read` / `Filesystem.Write` (E-006 / E-007); most retrieval workloads read more than they write, so capability minimization naturally exploits the split.

Capability scoping examples:

- `Vector.Read{provider: *, store: *}` — wildcard, authorizes any read from any store.
- `Vector.Read{provider: "pinecone", store: *}` — Pinecone reads only.
- `Vector.Read{provider: "pinecone", store: "main"}` — exactly one Pinecone index.
- `Vector.Write{provider: "pinecone", store: "main"}` — write access to one Pinecone index.

A workflow that builds an index has `Vector.Write` at build time and only `Vector.Read` at query time. The pattern is identical to the `Filesystem.Write{path}` / `Filesystem.Read{path}` separation Strand already uses.

### 3.2 Per-provider ForeignNodes

Each provider has its own targets under the `strand-builtin:` namespace. The provider parameter in each ForeignNode's effect declaration is pinned to a string literal; the `store` parameter resolves to the call site's argument.

**Pinecone:**
- `strand-builtin:Pinecone.Index.Open` — opens an index by name and returns a Resource handle (declares `Vector.Read{provider="pinecone"}` and `Vector.Write{provider="pinecone"}` — Open registers both because the handle subsequently supports both directions)
- `strand-builtin:Pinecone.Index.Close` — closes the handle
- `strand-builtin:Pinecone.Index.Upsert` — bulk upsert (`Vector.Write{provider="pinecone"}`)
- `strand-builtin:Pinecone.Index.Query` — top-k query with optional filter (`Vector.Read{provider="pinecone"}`)
- `strand-builtin:Pinecone.Index.Delete` — delete by ids (`Vector.Write{provider="pinecone"}`)
- `strand-builtin:Pinecone.Index.Fetch` — fetch by ids (no similarity search) (`Vector.Read{provider="pinecone"}`)

**Chroma:**
- `strand-builtin:Chroma.Collection.Open`, `.Close`, `.Add`, `.Query`, `.Delete`, `.Get` — each declares the appropriate `Vector.Read` or `Vector.Write` with `provider="chroma"`

**Pgvector:**
- `strand-builtin:Pgvector.Connection.Open`, `.Close` — connection lifecycle
- `strand-builtin:Pgvector.Insert`, `.Query`, `.Delete` — each with `provider="pgvector"`

**Weaviate, Qdrant, Milvus:** follow the same pattern in later phases.

Each provider's open / close pair manages a Resource handle whose `kind` field is provider-specific:

| Kind | Underlying | Released by |
|------|-----------|-------------|
| `pinecone_index` | Pinecone client + index handle | `Pinecone.Index.Close` |
| `chroma_collection` | Chroma client + collection handle | `Chroma.Collection.Close` |
| `weaviate_collection` | Weaviate client + collection ref | `Weaviate.Collection.Close` |
| `pgvector_connection` | JDBC / Postgres connection | `Pgvector.Connection.Close` |

A graph that uses `Pinecone.Index.Query` and a graph that uses `Chroma.Collection.Query` are distinct content-addressed graphs even if every other field is identical — content addressing on the binding layer remains intact, just as in Q-037.

### 3.3 Common shape across providers

Where providers genuinely agree, the request and result types are shared. The shape is the cross-provider intersection.

**UpsertItem (insert request element):**
```
UpsertItem = {
  id: String,
  vector: Bytes,           // float32 LE, length = 4 * dimensions
  metadata: JsonValue
}
```

**QueryResult (query response element):**
```
QueryHit = {
  id: String,
  score: Float,
  metadata: JsonValue,
  vector: Option<Bytes>     // included if requested
}
```

Each provider's Upsert takes `(handle, items: List<UpsertItem>)` and returns `Unit`. Each provider's Query takes `(handle, query: QueryRequest)` and returns `List<QueryHit>`.

**QueryRequest** is provider-shaped at the top level but shares fields:
```
QueryRequest = {
  vector: Bytes,
  k: Int,
  filter: Option<FilterExpr>,     // see § 3.4
  includeVector: Bool,
  includeMetadata: Bool
}
```

### 3.4 The seven API design questions — recommended initial answers

The seven questions are taken individually because the answers cascade.

**Q3.4.1: Metric choice grain.** Per-collection at Open time for managed services (Pinecone, Weaviate, Chroma — pass metric in the Open config). Per-query for pgvector (pgvector's operator-per-query model is core to its identity; flattening to per-collection would be a fiction). The QueryRequest carries an optional `metric: Option<Metric>` field that providers with per-collection metric reject if set; providers with per-query metric require it set.

```
Metric = Cosine | DotProduct | Euclidean | Manhattan
```

**Q3.4.2: Index-type opacity.** Managed services hide it (no field). Self-hosted providers expose it via Open config:

```
PineconeIndexConfig = {
  metric: Metric,
  dimensions: Int,
  // no ANN config — Pinecone hides it
}

PgvectorIndexConfig = {
  table: String,
  indexType: PgvectorIndex,    // HNSW | IVFFlat | Flat
  indexParams: JsonValue        // m, efConstruction for HNSW; lists for IVFFlat
}
```

The "Strand has no opinion on which ANN you pick" stance applies. The cost is that agent code must know whether a given provider needs index config — but the type system makes this explicit at the Open call site.

**Q3.4.3: Filter expression language.** Loose `JsonValue` filters for the initial slice, with a strong recommendation that a future Q-NNN designs a typed `FilterExpr` Schema-validated against the collection's metadata shape. The initial slice accepts the provider's native filter format (Pinecone's structured boolean, Chroma's key-equality dict, pgvector's WHERE clause string) carried as `JsonValue` content. This is honest about the cross-provider divergence and leaves room for later unification.

```
FilterExpr = JsonValue   // provider-shaped for now
```

Future direction: a typed `FilterExpr` sum type with `Eq(field, value) | In(field, values) | And(es) | Or(es) | Range(field, min, max) | ...` would let the verifier check that filters reference only fields declared in the collection's metadata Schema.

**Q3.4.4: Metadata shape.** Loose `JsonValue` for the initial slice. A typed-metadata story via the Schema mechanism is the natural extension — `Pinecone.Index.Open` could take a `metadataSchema: Option<Schema>` argument that constrains every Upsert and tags every QueryHit's metadata with the same SchemaType. Deferred to a follow-up: the value is real but the design effort to translate metadata Schemas across providers is non-trivial and worth a dedicated proposal.

**Q3.4.5: Batching.** Upsert is bulk by default (`items: List<UpsertItem>`). Single-record convenience wrappers can be authored as Strand Lambdas if needed. Bulk delete (`items: List<String>`) follows the same shape.

**Q3.4.6: Pagination.** Not in the initial slice. Queries return up to `k = 10000` hits in a single response; agents that need more should paginate at the application layer by issuing multiple queries with different filters. Cursor-based pagination (Qdrant scroll API, Pinecone listing) lands as a follow-up if real workloads demand it.

**Q3.4.7: Idempotency.** Upsert semantics across the board: insert with an existing id replaces. Strand provides no explicit "insert-fail-on-conflict" primitive in the initial slice; agents that need conflict detection do a Fetch first. This matches Pinecone and Chroma; pgvector requires an explicit `ON CONFLICT DO UPDATE` clause that the provider library emits automatically.

### 3.5 Embeddings as inputs

Vector stores consume the Bytes produced by Q-037's `*.Embed` builtins directly. No conversion. The `UpsertItem.vector` and `QueryRequest.vector` fields are Bytes typed; agent code computes them via `LLM.Embed` and passes them through. The upgrade path to a typed Vector primitive (Q-037 § 3.9) propagates: when Vector primitives ship, the `vector: Bytes` field becomes `vector: Vector` across both proposals' surfaces.

### 3.6 Resource lifecycle

Vector store handles follow the existing Layer 4 step 2 `Value.Resource(id, kind)` pattern. Handles are non-graph values — they exist only in a live runtime and cannot be content-addressed. A program that wants to "save" a store across runs persists the store's identifier (the index name, the collection name, the connection string) as a String and reopens.

Handles are non-shareable across machines — each state machine instance that uses a vector store opens its own handle. A shared store is a shared *resource identifier* (the collection name), not a shared handle.

### 3.7 Effect closure

Each provider ForeignNode declares the appropriate `Vector.Read` or `Vector.Write` effect with `provider` pinned to its string literal and `store` bound to the call site's argument. A graph that uses Pinecone has EffectDecls under `Vector.Read` / `Vector.Write` with `provider="pinecone"`; a graph that uses Chroma has the same effect category names with `provider="chroma"`. Multi-provider graphs carry multiple EffectDecls under the same categories with different `provider` parameter values; the verifier sees the union and demands the capability cover all of them. This is identical to how Q-037's `LLM.Generate{provider}` works.

A dispatch Lambda over a `VectorStore` SumValue (analogous to Q-037 § 3.4's Provider dispatch) lets agents switch stores at runtime while making the union of all possible store effects visible to the verifier.

## 4. Detailed mechanism

### 4.1 Verifier rules

No new node category; no verifier-rule rewrites beyond standard effect-closure propagation. The existing refinement-lattice mechanism handles per-provider / per-store scoping at the parameter slots.

### 4.2 Runtime semantics

Per-provider implementations in `interpreter/Builtins.kt`. Each provider's open / close pair manages the underlying client lifecycle:

- **Pinecone**: HTTP client to the Pinecone API endpoint with API key from `PINECONE_API_KEY`. Handle wraps the index name + environment.
- **Chroma**: HTTP client to a Chroma server endpoint (or in-process for embedded mode). Handle wraps the collection name.
- **Pgvector**: JDBC connection to a Postgres database. Handle wraps the connection + table name.
- **Weaviate**: HTTP client to the Weaviate endpoint. Handle wraps the collection ref.

Credential resolution follows Q-037's per-provider pattern: `PINECONE_API_KEY`, `WEAVIATE_API_KEY`, `PGVECTOR_URL` (Postgres connection string), etc.

Failure semantics: provider-specific errors map to `IoFailure` via the same translation Layer 4 step 2 added for filesystem and network. Per-provider error codes (Pinecone 429 rate limiting, pgvector unique-key violation, etc.) surface in the `IoFailure.detail` field.

### 4.3 Cross-provider dispatch

The `VectorStore` SumValue + Match pattern works the same way as Q-037 § 3.4's Provider dispatch. A unified `query(store: VectorStore, vec: Bytes, k: Int)` Lambda dispatches to the appropriate provider ForeignNode by Match. The Lambda's effect closure is the union of every store's EffectDecls under `Vector.Read` with the appropriate `provider` and `store` refinement values — the verifier sees the full surface.

## 5. Verifier rules

- Standard effect-closure propagation; Q-031 refinement-lattice matching on `provider` and `store`.
- Read / Write split enforced via parameter-tagged capability matching (Q-031).
- No new error variants beyond standard refinement-violation cases.

## 6. Runtime semantics

Standard `Builtins.lookup` dispatch. Provider implementations are independent Kotlin objects in `interpreter/`.

## 7. Test scenarios

1. **Open and close.** `Pinecone.Index.Open(config)` returns a Resource handle. `Pinecone.Index.Close(handle)` releases. Subsequent operation on the closed handle returns `ResourceTable.UnknownHandle`.
2. **Upsert and query — same provider.** Open a Chroma collection, upsert three vectors with ids and metadata, query with a vector close to one, assert the right id ranks first. Effect closure includes `Vector.Write{provider: "chroma", store: <opened collection>}` and `Vector.Read{provider: "chroma", store: <opened collection>}`.
3. **Filter application.** Upsert vectors with category metadata; query with a filter restricting to one category; assert only matching ids appear in results.
4. **Per-store capability scoping.** A program with only `Vector.Read{provider: "pinecone", store: "main"}` calls `Pinecone.Index.Upsert` against that index; refinement violation (Write effect not covered). Same program calls `Query`; success.
5. **Per-provider capability scoping.** A program with only `Vector.Read{provider: "pinecone", store: *}` calls `Chroma.Collection.Query`; refinement violation (provider parameter mismatches).
6. **Multi-provider dispatch.** A `VectorStore` SumValue with `Pinecone(name)` and `Chroma(name)` cases; a dispatch Lambda over both. Verifier asserts the Lambda's effect closure includes EffectDecls under `Vector.Read` and `Vector.Write` with both `provider="pinecone"` and `provider="chroma"`.
7. **Pgvector per-query metric.** A query with `metric: Some(Cosine)` against a pgvector connection succeeds; the same against a Pinecone index (metric is per-collection) raises a runtime error.
8. **Pgvector without metric.** A query with `metric: None` against pgvector raises a runtime error (pgvector requires explicit metric per query).
9. **Bulk upsert.** Upsert 1000 items in one call; assert all 1000 appear in subsequent queries.
10. **Upsert with existing id.** Upsert id "x" with vector A. Upsert id "x" again with vector B. Query close to B; assert "x" ranks above the prior-A result.
11. **Embedding → upsert end-to-end.** `LLM.Embed` produces Bytes; pass directly into `Pinecone.Index.Upsert`; assert the round-trip works.
12. **Credential failure.** Provider returns 401; the call surfaces `IoFailure` with the right detail; the agent's surrounding code can match on it.
13. **Rate limit failure.** Provider returns 429; surfaces as `IoFailure`; the agent can retry with backoff.

## 8. Tradeoffs and open questions

- **Provider abstraction — Resolved.** Per-provider ForeignNodes under operation-shaped effect categories, consistent with Q-037. The same rationale: content addressing and provenance trust live at the binding layer; operation-shaped categories match Strand's E-001..E-034 precedent and effect-systems research. Per-provider effect categories were considered and rejected (see Q-037 § 8 for the prior-art analysis).
- **Metric grain — Resolved.** Per-collection at Open time for managed services; per-query for pgvector. The QueryRequest's optional `metric` field is provider-validated.
- **Index-type opacity — Resolved.** Hidden by managed services; exposed via Open config for self-hosted. No unified surface.
- **Filter expression language — Open / Deferred.** Loose `JsonValue` for the initial slice. A typed `FilterExpr` sum type with Schema-aware validation is a strong future direction; deferred to a follow-up proposal because the cross-provider unification is genuinely hard (provider filter languages differ substantially).
- **Metadata shape — Open / Deferred.** Loose `JsonValue` for the initial slice. Typed metadata via Schema (N-032) is the natural extension; deferred for the same reason — the Schema-cross-provider story needs design.
- **Batching — Resolved.** Bulk by default.
- **Pagination — Open / Deferred.** Single-shot queries with k up to 10000 for the initial slice. Cursor pagination lands when real workloads demand it.
- **Idempotency — Resolved.** Upsert semantics across the board. Conflict-detecting insert is a Strand-side Fetch-then-Insert pattern.
- **Cross-provider tests** would help validate the unified-shape commitment. A standard test suite that runs each operation against every provider (via test doubles) is worth implementing — analogous to the JsonValue corpus that exercises every JSON shape.

## 9. Implementation sketch

Three phases. Each is self-contained and ships independently.

### Phase 1: Pinecone + Chroma (medium)

| File | Change | Scope |
|------|--------|-------|
| `design/effects-and-capabilities.md` | Add E-037 Vector.Read, E-038 Vector.Write (operation-shaped, with `provider: String, store: String` parameters) | small |
| `INDEX.md` | Register E-037, E-038 | small |
| `impl/interpreter/Builtins.kt` | Add Pinecone.* and Chroma.* builtins, each declaring `Vector.Read{provider="X"}` or `Vector.Write{provider="X"}` with `store` bound to the call argument | medium |
| `impl/interpreter/PineconeProvider.kt`, `ChromaProvider.kt` (new) | Per-provider Kotlin objects | medium |
| `impl/interpreter/ResourceTable.kt` | Register `pinecone_index`, `chroma_collection` kinds | small |
| `impl/authoring/LayerAGrammar.kt` | Add prelude entries for E-037 / E-038 and the initial ForeignNodes | small |
| `evaluation/dynamic/prompts/strand-system.md` | Document the Pinecone + Chroma surface | small |
| Tests | `BuiltinsPineconeTest`, `BuiltinsChromaTest` with mock backends, plus a `VectorRefinementTest` covering provider/store scoping | medium |
| Corpus | RAG-style retrieve-then-generate state machine | small |

Estimated effort: 1.5 sessions.

### Phase 2: pgvector + FAISS (medium)

| File | Change | Scope |
|------|--------|-------|
| `impl/interpreter/Builtins.kt` | Pgvector.* and FAISS.* builtins | medium |
| `impl/interpreter/PgvectorProvider.kt`, `FaissProvider.kt` (new) | Per-provider Kotlin objects | medium |
| `impl/interpreter/ResourceTable.kt` | Register `pgvector_connection`, `faiss_index` kinds | small |
| Tests | `BuiltinsPgvectorTest`, `BuiltinsFaissTest` | medium |

Estimated effort: 1 session.

### Phase 3: Weaviate + Qdrant + agent-pattern documentation (small)

| File | Change | Scope |
|------|--------|-------|
| `impl/interpreter/Builtins.kt` | Weaviate.* and Qdrant.* builtins | medium |
| `impl/interpreter/WeaviateProvider.kt`, `QdrantProvider.kt` (new) | Per-provider Kotlin objects | medium |
| Corpus | Multi-store dispatch Lambda demo | small |
| `evaluation/dynamic/tasks/` | RAG-shaped tasks for dynamic evaluation | small |

Estimated effort: 1 session.

### Identifier assignments

- New effect categories: E-037 Vector.Read, E-038 Vector.Write (operation-shaped, with `provider: String, store: String` parameters)
- This open question: Q-038
- No new node categories
- No new ADRs (the foundational decisions in ADR-004 / ADR-005 cover this work)

## 10. Relationship to other questions

- **Q-037 (agent-native LLM capabilities).** Companion proposal. `LLM.Embed` produces the Bytes that vector stores consume. Effect-category shape (operation-shaped with `provider` refinement) is shared.
- **Q-021 (evaluation).** RAG tasks extend the evaluation suite into multi-call agentic workloads.
- **Q-031 (refinement-lattice).** Per-provider / per-store scoping uses the existing mechanism.
- **Q-035 (schema + invariant).** Typed metadata via Schema is the natural extension once the cross-provider Schema unification is designed.
- **Future Q-NNN (filter expression language).** A typed `FilterExpr` sum type unifying provider filter languages is a strong follow-up.
- **Future Q-NNN (typed metadata).** Schema-typed metadata constraint validation at Upsert time is a strong follow-up.

## References

**Outgoing references:**
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — new effect categories follow this taxonomy
- [`design/security-model.md`](../design/security-model.md) — foreign binding trust applies to per-provider vector libraries
- [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — per-provider ForeignNodes follow this trust model
- [`proposals/agent-native-capabilities.md`](agent-native-capabilities.md) — companion Q-037 proposal; LLM.Embed produces the inputs this proposal consumes
- [`proposals/implemented/refinement-lattice-capability-matching.md`](implemented/refinement-lattice-capability-matching.md) — refinement-lattice mechanism the provider / store parameters use
- [`proposals/implemented/layer-4-step-2-real-io.md`](implemented/layer-4-step-2-real-io.md) — ResourceTable pattern this proposal extends
- [`proposals/stdlib-future-builtins.md`](stdlib-future-builtins.md) — broader stdlib gap catalog
- [`open-questions.md`](../open-questions.md) — Q-038 registered here

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-038
- [`proposals/agent-native-capabilities.md`](agent-native-capabilities.md) — Q-037 forward-references this as the consumer of LLM.Embed output
- [`proposals/stdlib-future-builtins.md`](stdlib-future-builtins.md) — agent-native section
