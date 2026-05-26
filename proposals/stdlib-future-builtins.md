# Stdlib future builtins — catalog

**Document:** `proposals/stdlib-future-builtins.md`
**Status:** Tracking catalog. Persists across rounds and is updated as gaps close or new ones surface.
**Last revised:** 2026-05-26

## Purpose

Catalogues builtin capabilities that are absent from the current registry (~110 builtins across 18 namespaces after stdlib expansion rounds 1–3). Each entry is grouped by readiness: agreed slice for the next round, candidate for a future round, open design question requiring a dedicated proposal before any builtin lands, or deferred to a specific milestone. Strand-distinctive agent-native capabilities — `LLM.*`, vectors and embeddings, agent-state primitives, model routing — are tracked separately as a research proposal because they may shape stdlib and node-algebra requirements before the rest of this catalog ships.

This document is not a draft proposal in the strict sense used elsewhere in `proposals/`. It is a living menu that persists as rounds are shipped. When a slice graduates from this catalog to execution, the corresponding entries are deleted in the same commit that lands the builtins.

## Round 4 — agreed next slice

Mechanical execution against the `strand-add-builtin` skill. None of the entries below need new node algebra, new effect categories, or any design pass — they are gaps that the round-1 / round-2 / round-3 sweeps left behind.

### Float arithmetic and comparisons

`Float.FromInt` is currently the only Float-typed primitive. `Math.*` operates on Float values via `Sqrt`/`Pow`/`Log`/`Exp`/`Sin`/`Cos`/`Tan`, but there is no way to add two Floats.

- `Float.Add`, `Float.Sub`, `Float.Mul`, `Float.Div`, `Float.Neg`
- `Float.Eq`, `Float.Lt`, `Float.Le`, `Float.Gt`, `Float.Ge`

### Missing equality variants

- `Bool.Eq`, `Bytes.Eq`

### List reducers and structure ops

Higher-order infrastructure shipped in round-2 slice 2 covers the underlying machinery. These are convenience reducers and structural ops.

- `List.Sort` (uses the round-2 `FnH` higher-order interface for the comparator callback)
- `List.Range` (Int start, Int end → Int list)
- `List.Zip`, `List.Unzip`
- `List.Distinct`
- `List.Sum`, `List.Product`, `List.Min`, `List.Max` (Int-specific specializations of Fold)

### Path operations

Pure path-string manipulation. No filesystem effects — only `Fs.*` carries E-006 / E-007.

- `Path.Join`, `Path.Basename`, `Path.Dirname`, `Path.Extension`, `Path.Normalize`

### DateTime

`Time.Now` returns Int millis. Agents currently cannot determine the day, format a timestamp, or parse a date.

- `DateTime.FormatIso` (Int millis → String, ISO 8601)
- `DateTime.ParseIso` (String → Option<Int>)
- `DateTime.Year`, `DateTime.Month`, `DateTime.Day`, `DateTime.Hour`, `DateTime.Minute`, `DateTime.Second`
- `DateTime.AddDays`, `DateTime.AddHours`, `DateTime.AddMinutes`, `DateTime.AddSeconds`

### Markdown symmetry

- `Markdown.Stringify` (mirrors `Json.Stringify`)

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

Not stdlib gaps in the conventional sense. These are capabilities specific to Strand's positioning as a language for AI agents, and they may shape stdlib and node-algebra requirements before the rest of this catalog ships. Investigation tracked across two sibling proposals: [`agent-native-capabilities.md`](agent-native-capabilities.md) (Q-037, revised 2026-05-26 after five-call analysis pass — per-provider ForeignNodes for LLM generation and embedding) and [`agent-native-vector-stores.md`](agent-native-vector-stores.md) (Q-038, drafted 2026-05-26 — per-provider ForeignNodes for vector storage and similarity search). In scope of those proposals:

- `LLM.*` — generation, embedding, structured-output, tool-use calls as first-class builtins
- `Vector.*` / `Embedding.*` — vector storage and similarity search for retrieval workflows
- Agent-state primitives — long-running memory, prompt caches, conversation handles
- Model routing and provider abstraction

The investigation is expected to surface implications for at least: capability-context shape (model-API keys as a new effect category), `Memory.MutableState` (long-running memory may demand it), schema design (structured-output calls intersect with N-032 / N-033), and the runtime resource model (`ResourceTable` for conversation and cache handles).

## References

**Outgoing references:**
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — current builtin inventory and the `strand-add-builtin` skill workflow
- [`proposals/implemented/stdlib-expansion-round-2.md`](implemented/stdlib-expansion-round-2.md) — prior round template
- [`proposals/implemented/layer-4-step-2-real-io.md`](implemented/layer-4-step-2-real-io.md) — real-IO builtin work

**Incoming references:**
- [`proposals/README.md`](README.md)
