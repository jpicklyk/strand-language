# Layer 4 step 2 — real IO builtins

**Document:** `proposals/layer-4-step-2-real-io.md`
**Status:** Draft (executing autonomously over a 4-8 hour window)
**Date:** 2026-05-25
**Concerns:** [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md), [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md), [`design/security-model.md`](../design/security-model.md), [`impl/interpreter/`](../impl/interpreter/), Q-006 (deferred trust model), Q-007 (effect inference for unannotated foreign code)
**Scope:** Medium — ~15-20 new builtins across 4 phases, each shippable independently

Layer 4 step 1 shipped the foreign function interface (N-020 ForeignNode) and an in-process Builtins registry with arithmetic + comparison + the three effectful *stubs* `Time.Now`, `Filesystem.Write`, `Network.Connect`. The stubs validate argument shapes and return placeholder values — no actual IO. This proposal documents the design calls for replacing them with real implementations, plus expanding the effect surface to cover what a real application would need.

The full security model around foreign bindings (Q-006: signed provenance, reproducible bindings, curated registries, WASM sandboxing — Milestone 2.4) remains deferred. The prototype path is **honest in-process trusted code**: the in-process registry is the trust boundary, and the `strand-builtin:` namespace is reserved for the runtime's own implementations. Other namespaces (`wasm:`, `process:`) get separate dispatchers once Milestone 2.4 begins.

## 1. Design calls (locked here so they don't relitigate per-builtin)

### 1.1 Resource handles

Sockets, processes, and file descriptors don't have value-semantics — they're stateful references to OS resources. To represent them in Strand:

- New `Value.Resource(id: Long, kind: String)` variant in `interpreter/Value.kt`.
- The interpreter maintains a runtime resource table mapping `id` to the JVM-side object (Socket, Process, FileChannel, etc.).
- `kind` is a tag like `"socket"`, `"process"`, `"file"` — used by per-resource builtins to validate the argument-type at dispatch.
- The Strand-level type for a resource is a `PrimitiveType` with kind `Bytes` (an opaque 16-byte handle), OR a sealed sum type per resource kind. Initial choice: opaque-Int handles (using `PrimitiveType` Int as the surface type) with the runtime table doing the dispatch. Avoids needing a new node category; the trade-off is no compile-time guard against passing a socket where a process is expected. Mitigated by the runtime `kind` check producing a clean `InterpretError.WrongResourceKind`.

Canonical encoding: `Value.Resource` is a runtime-only value — it never enters the canonical store (it's a handle to ephemeral OS state). Programs that need to *persist* a resource reference would round-trip through a serializable form (e.g., a file path, a connection URL).

### 1.2 IO failure semantics

When IO fails (permission denied, disk full, broken pipe, etc.), the builtin **throws `InterpretError.IoFailure(kind: String, detail: String)`**. This matches existing builtin behavior:

- `Int.Div` by zero throws `InterpretError.DivideByZero`.
- `MAT` with no matching case throws `InterpretError.NoMatchingCase`.

The agent's program either runs to completion or aborts with a structured error. Future work can introduce a blessed `Result<T, IoError>` sum type with explicit error-handling conventions; until then, exceptions are the prototype path. This matches how the Strand verifier handles "expected error" runtime conditions today.

### 1.3 Sockets and processes

- **Sockets**: synchronous JVM `Socket` / `ServerSocket`. Async wrapping into the state-machine actor runtime is a follow-up — when a state machine wants to await socket data, the actor loop polls. For now, `Receive` blocks the calling thread until data arrives or timeout fires.
- **Processes**: JVM `ProcessBuilder`. Default stdio is inherited (child's stdout/stderr go to the Strand runtime's stdout/stderr). Output capture (`Process.SpawnCapture` returning `Bytes`) is a follow-up.
- **HTTP**: built on `Network.Connect` + manual request/response framing. HTTP/1.1 only, supports HTTPS via the JVM's default truststore.

### 1.4 String encoding

UTF-8 throughout. Filesystem paths, environment variables, network host strings, `ParseUtf8(bytes) → Option<String>` all assume UTF-8. Latin-1 / other encodings are out of scope; programs that need them can decode bytes manually.

### 1.5 Effect category coverage

The new builtins exercise these existing effect categories from the E-001..E-031 registry:

| Effect | Builtins |
|---|---|
| E-001 Network.Connect | `Network.Connect`, `Http.Request` |
| E-003 Network.Send | `Network.Send`, `Http.Request` |
| E-004 Network.Receive | `Network.Receive`, `Http.Request` |
| E-006 Filesystem.Read | `Filesystem.Read`, `Filesystem.Exists`, `Filesystem.List` |
| E-007 Filesystem.Write | `Filesystem.Write`, `Filesystem.Append`, `Filesystem.Delete` |
| E-010 Time.Now | `Time.Now` |
| E-011 Time.Sleep | `Time.Sleep` |
| E-013 Process.Spawn | `Process.Spawn` |
| E-015 Process.Wait | `Process.Wait` |
| E-017 Memory.MutableState | (out of scope — represented via state machines) |

No new effect categories needed. The registry was specified ahead of implementation.

## 2. Phased delivery (each phase an independent commit window)

### Phase 1 — Filesystem + time + process (3-4 hours)

1. Task #22: Auto-synthesize Outer ProductType in Elaborator.
2. Real `Time.Now` (replace constant with `System.currentTimeMillis()`); `Time.Sleep(millis)`.
3. Filesystem: `Read`, `Write`, `Append`, `Exists`, `List`, `Delete`.
4. Process: `Spawn`, `Wait`, `EnvVar`.

Acceptance: tests written for each builtin against temp directories / subprocess invocations. All existing gradle tests stay green. Each builtin gets at minimum one test in `interpreter/src/test/`.

### Phase 2 — Network + HTTP (1-2 hours)

5. Network: `Connect`, `Send`, `Receive`, `Close` (sync sockets).
6. `Http.Request(method, url, headers, body) → {status, headers, body}` convenience builtin.

Acceptance: tests against a `ServerSocket` running on a free port in the test JVM. HTTP test against an in-process server.

### Phase 3 — String + Bytes stdlib (1-2 hours)

7. String: `Length`, `Substring`, `IndexOf`, `Contains`, `Replace`, `Split`, `Join`, `ToUpper`, `ToLower`, `Trim`, `ParseInt`, `ParseFloat`, `Format`.
8. Bytes: `Length`, `Slice`, `Concat`, `ParseUtf8`, `FormatBase64`, `ParseBase64`.

Acceptance: per-builtin unit tests. `ParseInt` / `ParseFloat` return Option-typed values (using the existing Option pattern from the corpus).

### Phase 4 — Blessed library completion (optional, if time)

9. `Json.Parse(bytes) → Result<JsonValue, ParseError>` and `Markdown.Parse(bytes) → Result<MarkdownDocument, ParseError>`.
10. Blessed `Result<T, E>` and `Option<T>` libraries — corpus programs defining canonical sum types and helper Lambdas (map, andThen, unwrapOr, etc.). No new node categories.

Acceptance: corpus programs verify and run. The new blessed libraries get round-trip tests like the existing JSON/Markdown libraries.

## 3. Verification gates

- `./gradlew test` green after every commit.
- New corpus programs (if any) hash deterministically across runs.
- Each phase's builtins exercised by at least one corpus program that uses them end-to-end via `strand run --grant-all`.

## 4. Out of scope (explicitly deferred)

- WASM sandboxing (Milestone 2.4) — non-builtin namespaces wait for that work.
- Async socket I/O integration with state-machine actors. Sync sockets are usable from non-actor programs; state-machine integration is a follow-up.
- Process stdio capture (currently inherited only).
- HTTP/2, gRPC, WebSocket — HTTP/1.1 only.
- TLS configuration beyond the JVM default truststore. Custom CA bundles, client certs, etc. are follow-up.
- Crypto builtins (`Crypto.Sign`, `Crypto.Encrypt`, etc.) — needs key-material design.
- Mutable state (`Memory.MutableState`) — Strand's answer is state machines; explicit refs/atoms are not in this slice.

## 5. References

**Outgoing:**
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — defines E-001..E-031, the effect categories these builtins exercise.
- [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — ForeignNode + trust model.
- [`design/security-model.md`](../design/security-model.md) — foreign binding trust (Q-006, deferred).
- [`impl/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt`](../impl/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt) — the registry being extended.
- Q-006, Q-007 in [`open-questions.md`](../open-questions.md) — both stay open.

**Incoming:**
- [`proposals/README.md`](README.md) — this proposal listed in Current proposals.
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — Known gaps section.
