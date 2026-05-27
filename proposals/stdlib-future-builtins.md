# Stdlib future builtins — catalog

**Document:** `proposals/stdlib-future-builtins.md`
**Status:** Tracking catalog. Persists across rounds and is updated as gaps close or new ones surface.
**Last revised:** 2026-05-27 (Round 4 shipped — Float arithmetic + comparisons, Bool.Eq/Bytes.Eq, polymorphic List structure ops + Int-specialized reducers, Path.*, DateTime.*, Markdown.Stringify. ~39 new builtin entries; section deleted from this catalog.)

## Purpose

Catalogues builtin capabilities that are absent from the current registry (~150 builtins across 19 namespaces after stdlib expansion rounds 1–4). Each entry is grouped by readiness: candidate for a future round, open design question requiring a dedicated proposal before any builtin lands, or deferred to a specific milestone. Strand-distinctive agent-native capabilities — `LLM.*`, vectors and embeddings, agent-state primitives, model routing — are tracked separately as a research proposal because they may shape stdlib and node-algebra requirements before the rest of this catalog ships.

This document is not a draft proposal in the strict sense used elsewhere in `proposals/`. It is a living menu that persists as rounds are shipped. When a slice graduates from this catalog to execution, the corresponding entries are deleted in the same commit that lands the builtins.

## Round 5+ candidates

Ready to ship under the same `strand-add-builtin` pattern. Grouped so the next round can pick coherent slices.

### String formatting

- `String.Format` (template + args → String)
- `String.PadLeft`, `String.PadRight`, `String.Repeat`
- `String.Lines` (String → List<String>)
- `String.Chars` (String → List<String> of single-char strings)
- `String.CharAt` (String, Int → Option<String>)

### Set operations

Opaque persistent Set parallel to round-3's Map.

- `Set.Empty`, `Set.Add`, `Set.Remove`, `Set.Has`, `Set.Size`
- `Set.Union`, `Set.Intersect`, `Set.Difference`
- `Set.ToList`, `Set.FromList`
- `Set.Fold`

### Map extensions

- `Map.Map` (transform values)
- `Map.Merge` (conflict-resolution callback)
- `Map.Filter`

### Tabular parsing

- `Csv.Parse`, `Csv.Stringify`
- `Tsv.Parse`, `Tsv.Stringify`

### Url operations

- `Url.Parse` (String → Option<{scheme, host, port, path, query, fragment}>)
- `Url.QueryEncode`, `Url.QueryDecode`

### Compression

- `Compress.Gzip`, `Compress.Gunzip` (Bytes ↔ Bytes)
- `Compress.Zstd`, `Compress.Unzstd`

## Open design items

Each entry below needs a proposal before any builtin lands, because the API surface choices are load-bearing.

### Crypto

Reserved effect categories E-021 / E-022 / E-023 (`Crypto.Sign`, `Crypto.Encrypt`, `Crypto.Decrypt`) have no builtins. Per-node encryption (ADR-006) requires these to exist before it can be implemented end-to-end. Open API questions:

- Which AEAD is canonical (AES-GCM vs ChaCha20-Poly1305)
- Which signature scheme is canonical (Ed25519, ECDSA, RSA)
- Key-derivation primitives (PBKDF2 / scrypt / Argon2)
- HMAC alongside `Hash.*`
- Key representation (raw Bytes vs opaque handles via `ResourceTable`)
- AAD handling for AEAD modes
- Nonce-safety responsibility (caller vs builtin)

### TLS / mTLS

`Net.Connect` is plain TCP. HTTPS works only inside `Http.Request`. Adding TLS to `Net.*` would let agents speak TLS-wrapped non-HTTP protocols. Open: certificate validation policy, mTLS client cert handling, SNI configuration.

### WebSocket

Closes the protocol gap between `Http.Request` (sync request/response) and `Net.*` (raw socket). Open: framing API, ping/pong handling, close semantics.

## Deferred to specific milestones

These wait on architectural work that is not stdlib-shaped.

| Capability | Waits on |
|---|---|
| `Hardware.GPU/NPU/Sensor` (E-018, E-019, E-020) | Likely Q-017 step 2 (Rust port) plus a follow-up FFI design |
| `Trust.Attestation/SealedStorage/MeasuredLaunch` (E-025, E-026, E-027) | TEE integration design (Q-012) |
| `Memory.MutableState` (E-017) | Absent by design; mutable state currently goes through state machines. Reopening this needs its own proposal — likely entangled with the agent-native capabilities research |
| `Filesystem.Watch` (E-009) | No proposal yet; lower priority |
| `Process.Signal` (E-014) | Same |
| `Time.Schedule` (E-012) | Same; could ride with a DateTime round if scope expands |
| HTML5 / SVG blessed libraries | Nested-μ blocker; the spliced-variants pattern from corpus 66 (JsonValueFull) is the likely unblock |
| PDF blessed library | Binary-format engineering pass; separate from stdlib expansion |

## Agent-native and Strand-distinctive capabilities

Not stdlib gaps in the conventional sense. These are capabilities specific to Strand's positioning as a language for AI agents. **Phase 1 of both proposals landed 2026-05-26** in parallel-worktree commits merged at `3c8271b`: [`implemented/agent-native-capabilities.md`](implemented/agent-native-capabilities.md) (Q-037 — per-provider LLM ForeignNodes for Anthropic / OpenAI / Gemini under E-035 LLM.Generate{provider, model} + E-036 LLM.Embed{provider, model}) and [`implemented/agent-native-vector-stores.md`](implemented/agent-native-vector-stores.md) (Q-038 — per-provider vector-store ForeignNodes for Pinecone + Chroma under E-037 Vector.Read + E-038 Vector.Write on a Read/Write split). 18 new builtins, 58 new tests, all pass. Phase 2+ remain (agent-pattern documentation + reference corpus for Q-037; pgvector / FAISS + Weaviate / Qdrant + RAG demo for Q-038). Categories not directly addressed by Q-037 / Q-038 Phase 1:

- Agent-state primitives — long-running memory, prompt caches, conversation handles. Q-037 § 3.5 confirms state machines are the model; the `llm_conversation` ResourceTable kind is reserved but its Open/Close builtins are deferred. `Memory.MutableState` (E-017) stays absent.
- Embedding model coverage beyond Anthropic / OpenAI / Gemini — Voyage AI as a dedicated provider (Anthropic's recommended embeddings backend; Q-037's `AnthropicEmbed` currently surfaces a stub IoFailure recommending it).
- Multi-modal LLM input (vision, audio, video) — handled today via `Block.Image(Bytes, mediaType)` and `Block.Document(Bytes, mediaType)` in the existing GenerateRequest shape; further refinement may emerge from real workloads.
- Streaming LLM outputs — Q-037 § 4.6 sketches the path (a `Resource(kind = "llm_stream")` returned from a streaming builtin variant, drained via `*.Stream.Receive`); not implemented in Phase 1.

## References

**Outgoing references:**
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — current builtin inventory and the `strand-add-builtin` skill workflow
- [`proposals/implemented/stdlib-expansion-round-2.md`](implemented/stdlib-expansion-round-2.md) — prior round template
- [`proposals/implemented/layer-4-step-2-real-io.md`](implemented/layer-4-step-2-real-io.md) — real-IO builtin work

**Incoming references:**
- [`proposals/README.md`](README.md)
