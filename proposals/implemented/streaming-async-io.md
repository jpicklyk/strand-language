# Streaming and asynchronous I/O

**Document:** `proposals/implemented/streaming-async-io.md`
**Status:** Implemented 2026-05-31 (Kotlin/JVM reference implementation, core slice)
**Original date:** 2026-05-29
**Concerns:** [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md), [`decisions/ADR-004-effects-as-edges.md`](../../decisions/ADR-004-effects-as-edges.md), [`design/state-machines.md`](../../design/state-machines.md), [`decisions/ADR-005-foreign-nodes.md`](../../decisions/ADR-005-foreign-nodes.md), [`proposals/implemented/layer-4-step-2-real-io.md`](layer-4-step-2-real-io.md), [`proposals/implemented/agent-native-capabilities.md`](agent-native-capabilities.md) § 4.6, [Q-037](../../open-questions.md#Q-037), [Q-040](../../open-questions.md#Q-040) (resource limits), [Q-041](../../open-questions.md#Q-041) (sandboxing), [Q-042](../../open-questions.md#Q-042) (error redaction)
**Scope:** small-medium (core); medium-large if the deferred actor-runtime bridge is taken up in the same pass

## Implementation note (2026-05-31)

The core slice landed via the `strand-add-builtin` workflow (no new node category, ADR, or effect-category identifier; every existing program hash is preserved because streaming handles are runtime-only `Value.Resource`s that never enter the store). Changes:

- **`core/EvaluationLimits.kt`** — new `streamReceiveTimeoutMillis` field (default `30_000`, aligned with `wallClockBudgetMillis`; `Long.MAX_VALUE` in `PERMISSIVE`).
- **`interpreter/ResourceTable.kt`** — `KIND_LLM_STREAM = "llm_stream"` constant and an `LlmStreamHolder(provider, model, stream)` class.
- **`interpreter/LlmProviders.kt`** — a streaming seam on `LlmHttpClient`: `openStream(url, headers, body): LlmStream` with a default that performs a normal `post` and serves the full body as a single chunk (so existing post-only mock transports support streaming unchanged), plus `SingleChunkLlmStream` and `InputStreamLlmStream`. `DefaultLlmHttpClient.openStream` overrides with true incremental reads off the HTTP response, installing the per-read timeout from `Builtins.streamReceiveTimeoutMillis`.
- **`interpreter/AnthropicProvider.kt` / `OpenAIProvider.kt` / `GeminiProvider.kt`** — each gains a stateless `generateStreamOpen(req, client, credentials): LlmHttpClient.LlmStream`; the request-body builder was extracted to a shared `buildBody` so the blocking and streaming forms cannot drift (Gemini uses the `:streamGenerateContent?alt=sse` endpoint with an identical body).
- **`interpreter/Builtins.kt`** — a `streamReceiveTimeoutMillis` `@Volatile` carrier (set the socket `SO_TIMEOUT` at `Net.Connect` open); `Net.Stream.Receive`, the three `*.CreateStream` opens, `LLM.Stream.Receive`, and `LLM.Stream.Close` (all first-order — the initial slice runs no tool loop over a stream), plus an `openLlmStream` helper reusing `parseGenerateRequest`.
- **`cli/Main.kt`** — `--stream-receive-timeout-ms` flag; the value is installed on `Builtins.streamReceiveTimeoutMillis` (saved/restored alongside `sandboxPolicy`) on `run`, `machine`, and `group`.
- **`authoring/LayerAGrammar.kt`** — prelude entries for `LLM.Stream.Close` (`llmStreamClose` / `llmStreamCloseT`); the agent-typed opens and the `Option<Bytes>`-returning drains stay out of the prelude per the documented exceptions. **`evaluation/dynamic/prompts/strand-system.md`** — a "Streaming I/O" section plus prelude-table updates.
- **Tests** — `StreamingReceiveTest` (7 scenarios), `StreamingPreludeTest`, and verify-only corpus 81 registered in `CorpusTest` + `CorpusHashingTest`.

Deviations and corrections worth recording:

1. **Credential signature corrected in the proposal text (no longer a deviation).** § 4.2 and § 4.4 originally sketched the open as `(request, credential: Int)`, threading an explicit credential handle. That was a design error: credentials are not capabilities (Q-037 § 4.4), Q-042 deliberately keeps key material off the agent-visible structural surface, and the blocking `*.Create` variants already resolve the key internally via `Builtins.credentialProvider` keyed by provider string — there is no Strand-level credential-handle concept, and a `credential` graph argument would be transport-specific noise that breaks drop-in swapping with the blocking form. The shipped opens take `(GenerateRequest)` and authenticate exactly as the blocking variants do; **§ 4.2, § 4.4, and a new § 8 recorded decision have been corrected to match the as-built design**, so this is no longer a deviation from the proposal — it is a correction *to* it. Provider names match the existing blocking targets (`OpenAI.Chat.CompletionsStream`, `Gemini.GenerateContentStream`) rather than the proposal's illustrative `OpenAI.Chat.Create`.
2. **No prelude entry for the opens or the drains.** § 9's sketch listed prelude entries with `Option<Bytes>` result types; the prelude cannot express `Option<T>` today (the documented Option-returning exception), and the opens are typed against the agent-chosen `GenerateRequest` (the documented agent-typed exception). Only `LLM.Stream.Close` (monomorphic `(Int) -> Unit`) is preluded, mirroring `netClose`.
3. **The timeout reaches `Builtins` via a `@Volatile` carrier installed by the CLI, not threaded through the interpreter.** This mirrors the existing `sandboxPolicy` / `nameResolver` injection pattern; library/test callers that bypass the CLI set the carrier directly (as `StreamingReceiveTest` does implicitly via the 30 s default). Setting `SO_TIMEOUT` at `Net.Connect` also bounds the legacy `Net.Receive` — a strict improvement consistent with Q-040's intent.
4. **Corpus 81 is verify-only.** Like the LLM/Vector demonstrators (corpus 67/68), a real run needs a streaming HTTP transport and a credential; the runtime drain is covered end-to-end by `StreamingReceiveTest` with an injected chunk transport. The exemplar still positively exercises the `{LLM.Generate, Network.Receive}` effect closure through the verifier.

5. **No `NetSandbox` check on the LLM open — proposal text corrected (no longer a deviation).** § 3, § 4.4, § 6, and § 7 originally stated the open runs a sandbox net check (with DNS pinning). That is true only for the **socket** path: `Net.Connect` takes an agent-supplied host and runs `NetSandbox.checkConnect`. The per-provider LLM HTTP surface uses a hardcoded endpoint (`api.anthropic.com`, etc.) and sits outside `NetSandbox` — exactly as the blocking `*.Create` variants do, and sensibly so, since there is no agent-controlled host to constrain (the model is a string in the request body, not a URL). The agent-facing control on the LLM open is the E-035 capability with its `{provider, model}` refinement. § 3 / § 4.4 / § 6 / § 7 have been corrected to draw the LLM-vs-socket distinction.

The original proposal text follows, with § 3 / § 4.2 / § 4.4 / § 6 / § 7 / § 8 corrected as described in items 1 and 5 above (the credential signature and the LLM-open sandbox claim); the remainder is unchanged.

---

This proposal closes Tier 2 part 3 of the 2026-05-28 roadmap: incremental and streaming I/O. It implements the streaming sketch left open in Q-037 § 4.6 (a `Resource(kind = "llm_stream")` drained via `*.Stream.Receive`) and generalizes it into a single uniform streaming-handle contract shared by streaming LLM generation and socket reads. The actor-runtime integration (external streams as event sources feeding state machines) is specified as a deferred follow-up with a concrete seam, not built in this slice.

## 1. Problem statement

Strand's real-I/O surface (Layer 4 step 2) is synchronous and one-shot. The model is: a builtin makes a call, blocks the calling thread, and returns the complete result. `Http.Request` returns the full response after the body is read; each provider's LLM ForeignNode returns the complete `GenerateResult` after the model emits a stop reason. There is no way for an agent program to consume a result incrementally — to begin acting on the first tokens of an LLM response before the last token arrives, or to read a socket as an open-ended sequence of chunks.

The implementation already contains the pieces a streaming surface needs but never composed them:

- `Net.Receive(handle: Int, maxBytes: Int) -> Bytes` already performs a single blocking read off an open socket and returns the bytes read, or empty `Bytes` on EOF. This is a chunked receive in everything but name — but its EOF representation (empty bytes) conflates end-of-stream with a legitimate zero-length read, and nothing connects it to the LLM or process surfaces.
- `ResourceTable` already mints, looks up, and removes opaque `Value.Resource(id, kind)` handles, with a registered-but-unused `KIND_LLM_CONVERSATION = "llm_conversation"` kind. There is no `"llm_stream"` kind and no streaming builtin of any sort.
- Q-037 § 4.6 specifies the LLM streaming path verbatim — `Resource(kind = "llm_stream")` returned immediately from a streaming generate variant, drained via `LLM.Stream.Receive(handle, maxBytes)`, "matching the existing `Net.Receive` pattern" — and explicitly defers all of it.

A second, independent mechanism for incremental delivery already exists: the state-machine actor runtime (Layer 6). Machines consume unbounded event streams through Kotlin coroutines over `Channel<Value>` with `select`-based merge, with full backpressure (`OverflowPolicy`: BlockProducer / DropNewest / DropOldest / Sample), fan-in/fan-out (`StreamBus` / `ConsumerMode`), event recording for deterministic replay, and per-instance metrics. But this machinery is purely intra-process: events are *delivered into* a machine's input channels by host Kotlin code via `MachineGroupHandle.externalInputs`; nothing in the runtime today reads a real socket or LLM stream into an `External` EventStream. The actor model is push-based (the runtime delivers; the machine never pulls), and the interpreter is synchronous with atomic, pure transitions — so external I/O cannot be a blocking pull *inside* a transition.

The gap is therefore: (a) no first-class incremental-receive surface for foreign I/O, and (b) no bridge between live external producers and the existing stream runtime.

## 2. Prior art

- **POSIX `read(2)` / Go `io.Reader`** — the canonical chunked-receive contract: a blocking call returning *up to* N bytes, with a distinguished EOF signal (`0`/`io.EOF`) separate from a short read. Strand's `Net.Receive` follows the shape but uses an ambiguous empty-bytes EOF; Go's explicit `io.EOF` is the cleaner model this proposal adopts via `Option`.
- **Server-Sent Events (SSE)** — the wire format every major LLM streaming API uses (`text/event-stream`, `data:`-prefixed frames terminated by blank lines). Streaming generation is fundamentally "drain an HTTP response body as it arrives and parse SSE frames." This argues for a raw-`Bytes` primitive with SSE decoding layered above, rather than baking SSE into the builtin.
- **Reactive Streams / `java.util.concurrent.Flow`** — demand-driven backpressure: the consumer signals how much it can accept, the producer never outruns it. Strand's synchronous chunked-receive gets this for free — the program pulls the next chunk only when ready, so backpressure is implicit. The actor-runtime bridge (deferred) instead uses the explicit `OverflowPolicy` already built for intra-process streams.
- **Erlang/OTP ports and `gen_statem`** — external I/O is modeled as messages delivered to a process mailbox, never as a synchronous pull inside a state callback. This is exactly Strand's actor constraint and motivates the deferred bridge: external streams become `External` EventStreams whose events arrive as ordinary messages.
- **Effect-system multiplicity** — languages with effect rows (Koka, Frank, OCaml 5 effects) treat an effect as present-or-absent in a function's row, never "occurs N times." Strand matches this: the effect closure is a static *set*, so a loop that receives a thousand chunks contributes the receive effect to the closure exactly once. Streaming therefore requires no new effect-algebra construct.

## 3. Recommended approach

Adopt a single **uniform streaming-handle contract** built on the existing `ResourceTable` and `Net.Receive` pattern (demand-driven synchronous chunked receive). Build the LLM streaming surface on it, clean up the socket surface to match, and specify — but do not build — the actor-runtime bridge.

The contract has three operations per streaming namespace:

1. **Open.** A streaming-source builtin opens the underlying transport and returns a `Value.Resource` immediately, before the body arrives. It declares its *semantic* effect (E-035 `LLM.Generate` for streaming generation; the socket case reuses `Net.Connect`/E-001, which already mints a socket handle). The capability check happens here, once. The Q-041 `NetSandbox` check also happens once at open **for the socket path** (`Net.Connect` resolves and pins the host IP) — but **not** for the LLM path: the per-provider LLM HTTP surface uses a hardcoded per-provider endpoint (`api.anthropic.com`, etc.) with no agent-controlled host to constrain, so it sits outside `NetSandbox` exactly as the blocking `*.Create` variants do. The agent-facing control on the LLM open is therefore the E-035 capability (with its `{provider, model}` refinement), not the sandbox.
2. **Receive.** `<Namespace>.Stream.Receive(handle: Int, maxBytes: Int) -> Option<Bytes>` performs one blocking read and returns `Some(chunk)` for each chunk or `None` at end-of-stream. It declares E-004 `Network.Receive` (a transport-level read off a live connection). Backpressure is implicit — the program calls Receive when it is ready for more.
3. **Close.** `<Namespace>.Stream.Close(handle: Int) -> Unit` releases the handle and underlying transport. Idempotent (a second close, or close of an unknown id, is a no-op). No declared effect.

This is opinionated on six points; the settled rationale and rejected alternatives are recorded in § 8:

- **EOF is `Option<Bytes>` (`None`), not an empty-bytes sentinel.** Clean end-of-stream, distinguishable from a zero-length chunk.
- **Chunks are raw `Bytes`.** The primitive transports bytes; SSE/JSON decoding is a deferred layer (§ 8). Uniform across LLM, socket, and (future) file/process streams.
- **The drain declares E-004 `Network.Receive`, unprojected; no new effect category.** The open builtin declares the refinement-bearing *semantic* effect (E-035 `LLM.Generate{provider, model}`, or E-001 `Network.Connect{host, port}` for the socket path); the drain declares the *transport* effect E-004. This is load-bearing for soundness, not stylistic: if the drain carried no effect, a stream handle would be an ambient capability-free network channel — code inside a narrowed `CapabilityScope` (N-036) that had `Network.Receive` revoked could still pull bytes from a handle it holds, making the effect closure an unsound bound on network I/O and breaking the structural-safety harm bound `closure(g) ∩ C ∩ B ∩ P` (Q-044). The static-set closure model means a drain loop contributes E-004 exactly once regardless of chunk count. A streaming-LLM program therefore declares both E-035 and E-004 — an intentional, honest split (the blocking generate hides its transport inside one atomic builtin; the streaming form performs the receives as distinct operations and surfaces them).
- **Per-receive blocking is bounded by host policy, not a graph argument.** A `streamReceiveTimeoutMillis` field on Q-040's `EvaluationLimits` is installed as the socket/HTTP read timeout at open, so the OS enforces a ceiling on every blocking read. A `timeoutMillis` *argument* on the builtin is rejected: resource bounds are host-configured precisely so an adversarial graph cannot set its own ceiling to infinity (Q-040's invariant).
- **The synchronous chunked-receive model is the core; the actor-runtime bridge is deferred.** The bridge is built *on top of* this primitive (its host-side feeder calls `Stream.Receive` in a loop) and carries an unresolved graph-surface question of its own (§ 8) — it is a genuine future question, not merely the second half of this slice.
- **Per-namespace Receive builtins, not one polymorphic `Stream.Receive`.** A single builtin cannot carry a kind-specific transport effect (a file stream would want E-006 `Filesystem.Read`, not E-004). Per-namespace builtins keep each drain's declared effect honest while the *contract* stays uniform.

## 4. Detailed mechanism

No new node category, ADR, or effect-category identifier. The change is builtins, prelude entries, per-provider streaming transports, and one new `ResourceTable` kind. No canonical encoding changes (resources are runtime-only `Value`s that never enter the store), so every existing program hash is preserved.

### 4.1 Resource kinds

- `KIND_LLM_STREAM = "llm_stream"` — new. Underlying object: a provider-specific `LlmStreamHolder` wrapping the open HTTP response's `InputStream` (or the injected test transport's chunk iterator) plus provider/model metadata for error context.
- `KIND_SOCKET = "socket"` — reused unchanged. Streaming socket reads operate on the same handle `Net.Connect` already returns.

### 4.2 LLM streaming builtins (per provider)

For each provider already shipping a blocking generate (`Anthropic.Messages.Create`, `OpenAI.Chat.Create`, `Gemini.GenerateContent`), add a streaming variant:

- `strand-builtin:Anthropic.Messages.CreateStream` (and per-provider analogues `OpenAI.Chat.CompletionsStream`, `Gemini.GenerateContentStream`). Signature `(request: GenerateRequest) -> Int` (handle) — identical to the blocking `*.Create` variant. It issues the same request with the provider's streaming flag set (`stream: true` / SSE `Accept`), opens the response, registers an `LlmStreamHolder` via `ResourceTable.register(KIND_LLM_STREAM, holder)`, and returns the handle. The API key is **not** a graph argument: it is resolved internally by the host `CredentialProvider`, keyed by provider, exactly as the blocking variant resolves it. Credentials are not capabilities — the capability `LLM.Generate{provider, model}` authorizes the call but does not carry the key (Q-037 § 4.4), and Q-042 deliberately keeps key material off the agent-visible structural surface — so the streaming open authenticates the same way the blocking call does and takes no credential parameter (see § 8). Declares E-035 `LLM.Generate{provider, model}` exactly as the blocking variant does. Lives in `higherOrderRegistry` only if it must run the tool loop; an initial streaming slice omits tool use and lives in the first-order `registry`.
- `strand-builtin:LLM.Stream.Receive` — `(handle: Int, maxBytes: Int) -> Option<Bytes>`. Reads up to `maxBytes` raw response bytes (one or more SSE frames, unparsed); `Some(BytesV(chunk))` per read, `SumV("None", null)` when the response body is exhausted. Declares E-004 `Network.Receive`, unprojected (matching `Net.Receive`).
- `strand-builtin:LLM.Stream.Close` — `(handle: Int) -> Unit`. `ResourceTable.remove`, close the underlying stream/connection, swallow `IOException`. No declared effect.

SSE-frame parsing (turning `data: {...}` chunks into text deltas) is Strand-level code over the raw bytes, or a deferred blessed `Sse.*` decoder (§ 8). The primitive deliberately does not decode.

### 4.3 Socket streaming cleanup

Add `strand-builtin:Net.Stream.Receive` — `(handle: Int, maxBytes: Int) -> Option<Bytes>`, declaring E-004 `Network.Receive`. Identical to `Net.Receive` except EOF is `None` rather than empty `Bytes`. The legacy `Net.Receive` (empty-on-EOF) is retained unchanged so corpus hashes and existing fixtures are untouched; new programs are steered to the `Option` form via the system-prompt docs. `Net.Close` already exists and serves as `Net.Stream.Close`.

### 4.4 Worked example — streaming an LLM completion

A Strand program that streams a completion and concatenates the raw chunks:

1. Construct a `GenerateRequest` (existing shape). No credential appears in the graph — the host `CredentialProvider` resolves the provider's key internally at open, exactly as the blocking `*.Create` variant does (see § 8).
2. `Application` of `Anthropic.Messages.CreateStream` with `effectInstances = [LLM.Generate{provider="anthropic", model="claude-..."}]`. The verifier admits the E-035 declaration (Q-039 projection enforcement applies if and when the LLM bindings carry projections — that migration is deferred, so the streaming open declares E-035 unprojected, like the blocking variant today); the interpreter checks the E-035 capability against the context, then opens the SSE response to the hardcoded Anthropic endpoint and returns `Value.Resource(id = 7, kind = "llm_stream")` → surfaced as `IntV(7)`. (No `NetSandbox` check runs on the LLM path — the endpoint is fixed per provider; the sandbox applies to the socket path, where `Net.Connect` takes an agent-supplied host. See § 3 and § 6.)
3. A `Fixpoint` loop: each iteration applies `LLM.Stream.Receive(7, 4096)`. The verifier requires E-004 `Network.Receive` in the enclosing Lambda's effect row (else `UncoveredEffects`); the loop contributes E-004 to the closure exactly once. Each call returns `SumV("Some", BytesV(...))`; the loop `Match`es `Some`/`None`, appends the chunk, and recurses on `Some`, terminating on `None`. The program's root Lambda thus declares both E-035 `LLM.Generate{...}` (from the open) and E-004 `Network.Receive` (from the drain), and the capability context must grant both.
4. `LLM.Stream.Close(7)`. A subsequent `LLM.Stream.Receive(7, 4096)` raises `IoFailure("resource-not-found", ...)` → `InterpretError.IoFailure`.

No node hashes change; the program is ordinary Layer 4 graph structure over three new ForeignNodes.

## 5. Verifier rules

No new `VerifyError` variants. Streaming is admitted entirely by the existing effect-closure discipline:

- An `Application` of a `*.Stream.Receive` ForeignNode releases E-004 `Network.Receive` into the enclosing closure (Application release rule, `closure = closureOf(fn) ∪ closures(args) ∪ funType.effects`). Every enclosing `Lambda` must declare E-004 or fail the existing `UncoveredEffects` check. A drain loop expressed as `Fixpoint` contributes the category once — multiplicity is invisible to the algebra, by design.
- Streaming-open builtins declare their semantic effect (E-035 for LLM) exactly as the blocking variants do; Q-039 `effectProjections` enforcement is unchanged.
- `*.Stream.Close` declares no effect and needs no rule.

The well-known-effect registry (`WellKnownEffect.kt`) is *not* extended — these effects are exercised directly by user code at the foreign-call site, not implicitly by the runtime on the program's behalf (the distinction that registry encodes). Streaming differs from `StateMachine.Receive` (E-029) precisely here: the chunked-receive model has the program pull, so the effect surfaces in the value-level closure normally.

## 6. Interpreter / runtime semantics

- **Open** (`*.CreateStream`): capability check → issue streaming request → `ResourceTable.register(KIND_LLM_STREAM, holder)` → return `Value.Resource`. The LLM path does **not** run a `NetSandbox` check (the per-provider endpoint is hardcoded — `api.anthropic.com`, etc. — so there is no agent-controlled host to constrain, matching the blocking `*.Create` variants; the agent-facing control is the E-035 capability). The socket streaming path is different: `Net.Connect` takes an agent-supplied host, so its `NetSandbox.checkConnect` resolves and pins the `InetAddress` once at open (foreclosing DNS rebinding for the lifetime of the stream, no per-chunk recheck). A transport injected via the existing `@Volatile var llmHttpClient` pattern (its `openStream` method) lets tests feed a deterministic chunk iterator with no network.
- **Receive** (`*.Stream.Receive`): `ResourceTable.get(handle, expectedKind)` (kind mismatch / unknown id → `IoFailure`) → one blocking `read` of up to `maxBytes` → `SumV("Some", BytesV(...))`, or `SumV("None", null)` on exhaustion. A transport-level `IOException` (broken pipe, reset, timeout) → `throw IoFailure("...-stream-receive", detail)` → `translateIoFailure` → `InterpretError.IoFailure`, scrubbed per the active `ErrorVerbosity` (Q-042).
- **Close** (`*.Stream.Close`): `ResourceTable.remove(handle)`; if present, close the underlying stream/connection, swallowing `IOException`. Idempotent.
- **Resource-limit interaction (Q-040):** two layers bound a blocking receive. The aggregate `EvaluationLimits.wallClockBudgetMillis` (sampled every `wallClockSampleEvery` steps) caps total evaluation, surfacing as `InterpretError.ResourceExhaustion(kind = WallClock)`. Because a single native `read` that never returns advances no interpreter step and so escapes that sampler, a new `EvaluationLimits.streamReceiveTimeoutMillis` field (host policy, default aligned with `wallClockBudgetMillis`) is installed as the underlying socket's `SO_TIMEOUT` (and the HTTP response `setReadTimeout` for LLM streams) at open time, so the OS enforces a per-read ceiling. A read that exceeds it throws `SocketTimeoutException` → `IoFailure("...-stream-timeout", detail)` → recoverable `InterpretError.IoFailure`. The timeout is host-configured, never a builtin argument, so a hostile graph cannot raise its own ceiling.
- **Threading:** the synchronous interpreter blocks the calling thread on each `Receive`. This is acceptable for an agent driving one stream at a time; concurrent multi-stream consumption is what the deferred actor-runtime bridge addresses.

## 7. Test scenarios

1. **LLM streaming happy path** — `CreateStream` returns a handle; successive `LLM.Stream.Receive` calls return `Some(chunk)` for each injected SSE frame, then `None`; `Close` succeeds. Driven by an injected `llmHttpClient` chunk iterator (no network).
2. **EOF is `None`** — `Net.Stream.Receive` against a socket whose peer has closed returns `SumV("None", null)`, distinct from a `Some(empty Bytes)` short read earlier in the same stream.
3. **Receive after close** — `Close(h)` then `Receive(h, n)` raises `IoFailure("resource-not-found", ...)` → `InterpretError.IoFailure`.
4. **Kind mismatch** — calling `LLM.Stream.Receive` on a `"socket"` handle (or vice versa) raises `IoFailure("resource-kind-mismatch", ...)`.
5. **Uncovered effect** — a Lambda whose body drains a stream but omits E-004 `Network.Receive` from its declared `effects` is rejected with `UncoveredEffects` at verification.
6. **Capability missing** — a program draining a stream without the `Network.Receive` capability in its context is rejected (capability-coverage check).
7. **Sandbox at open (socket path)** — a streaming socket open (`Net.Connect`) to a blocked/loopback/metadata host raises `SandboxViolation` at open time; no partial stream is created. (The LLM `CreateStream` path has no sandbox check — its endpoint is hardcoded per provider — so this scenario applies only to the socket path. Net.Connect sandbox enforcement is covered by `SandboxPolicyTest`.)
8. **Wall-clock budget on stalled receive** — a transport that yields no data and the interpreter loop exhausts `wallClockBudgetMillis` surfaces `ResourceExhaustion(WallClock)`.
9. **Mid-stream transport failure** — the injected transport throws after two chunks; the third `Receive` surfaces `InterpretError.IoFailure` with a scrubbed `detail` (Q-042), not a raw exception.
10. **Double close idempotent** — `Close(h)` twice succeeds; the second is a no-op.
11. **Corpus exemplar** — a verify-and-run program that opens a streaming source (injected transport), drains via `Fixpoint`+`Match` into a concatenated `Bytes`, and closes; asserts `interpreter == VM` equivalence where the VM path applies.

## 8. Tradeoffs, recorded decisions, and deferred work

**Deferred intentionally:**

- **Actor-runtime bridge (external streams as event sources) — registered as [Q-046](../../open-questions.md#Q-046).** A host-side coroutine that drains a streaming handle and feeds each chunk into an `External` EventStream channel via `MachineGroupHandle.externalInputs`, letting a state machine consume a network/LLM stream as ordinary events with the existing `OverflowPolicy`, `StreamBus` fan-in/out, `EventRecorder` replay, and metrics. This is built *on top of* the pull primitive in this proposal — its feeder coroutine calls `Stream.Receive` in a loop. It is deferred not merely as sequencing but because it carries an unresolved design question this proposal does not answer: how to *declare at the graph level* that a given `External` EventStream is backed by a specific IO source (a new `streamKind`-adjacent binding, a ForeignNode that produces a stream, or a `strand group --bind-stream` CLI affordance). That graph-surface question deserves its own research pass when multi-stream concurrent consumption becomes a real workload; taking it up turns the work medium-large. Now tracked as Q-046, which also carries the effect-accounting (E-004 vs. runtime-implicit E-029), replay-determinism, and feeder-lifecycle sub-questions.
- **Decoded stream events.** A blessed `StreamEvent` sum (e.g. `TextDelta(String) | Usage(...) | Stop(reason)`) and a `*.Stream.ReceiveEvent -> Option<StreamEvent>` variant. This is harder than a one-shot codec and is why the raw-`Bytes` primitive ships first: `Stream.Receive(handle, maxBytes)` returns *arbitrary* byte chunks that can split an SSE frame mid-frame, so a clean decoded experience requires a **stateful** decoder that buffers across reads until a complete frame is available — most naturally a wrapping decoder *resource* (`Sse.Open(streamHandle) -> sseHandle`, `Sse.Receive(sseHandle) -> Option<SseEvent>`) reusing the same `ResourceTable` handle pattern, with provider-specific JSON extraction of the `data:` payload layered above the generic SSE framing. A normalized cross-provider `StreamEvent` additionally waits on blessing the streaming `GenerateResult` shape. With the raw primitive, an agent can already either accumulate-then-parse (if it does not need incremental display) or do buffer-split framing in Strand; the stateful decoder is the ergonomic improvement.
- **Process stdout/stderr streaming.** `Process.Spawn` currently uses `inheritIO()` — no capture. A capture-mode spawn returning readable stream handles needs a new transport effect (a `Process.Read` category, since E-004 `Network.Receive` is semantically wrong for a pipe) and is deferred to avoid effect-category churn in this slice.
- **File streaming.** `Fs.Stream.Receive` over a large file under E-006 `Filesystem.Read` is a mechanical addition once the contract lands; not in this slice.
- **TLS/mTLS and WebSocket.** Separate open-design items in [`stdlib-future-builtins.md`](stdlib-future-builtins.md); streaming over a raw TCP socket is in scope, streaming over a TLS-wrapped or WebSocket-framed connection is not.
- **Streaming send / bidirectional streams.** This slice is receive-only (the dominant agent workload: stream a model response in). `*.Stream.Send` and full-duplex streams are a follow-up.

**Recorded decisions (settled here; alternative rejected):**

- *Drain effect — E-004 `Network.Receive`, not a no-effect drain.* Rejected the "capability spent at open" model because it makes the effect closure an unsound bound on network I/O: a handle would be drainable inside a `CapabilityScope` that revoked `Network.Receive`, breaking the Q-044 harm bound and reopening the capability-gap class the Q-039/Q-041/Q-042 audit work closed. The drain is unprojected (the handle is opaque — nothing to refine against), and the open carries the refinement-bearing semantic effect. See § 3.
- *Per-receive timeout — host policy on `EvaluationLimits`, not a builtin argument.* A graph-authored `timeoutMillis` would let an adversarial program set its own ceiling to infinity; Q-040's invariant is that resource bounds are host-configured. `streamReceiveTimeoutMillis` is installed as the OS read timeout at open. See § 6.
- *Socket receive — additive `Net.Stream.Receive`, legacy `Net.Receive` retained.* In-place migration is foreclosed by hash stability (changing the return type rehashes the `FunctionType` node and every program referencing it); removal would break existing programs. New emissions are steered to the `Option`-EOF form via system-prompt docs only — the established `Http.Request` / `Http.RequestFromUrl` dual-form precedent.

- *Credential — resolved by the host `CredentialProvider`, never a graph argument.* An earlier draft of § 4.2/§ 4.4 sketched the open as `(request, credential: Int)`, threading an explicit credential handle by analogy to how OS resources surface as `Int` handles. Rejected. Credentials are not capabilities (Q-037 § 4.4): the capability `LLM.Generate{provider, model}` authorizes the call, it does not carry the key — and Q-042 deliberately keeps key material off the agent-visible structural surface via the opaque `Credential` wrapper and the process-global scrubber. A `credential` graph argument would reintroduce a credential-shaped slot into emitted programs, cutting against that. It is also transport-specific noise on what is otherwise a uniform contract: the shared Receive/Close operations are credential-free, sockets and (future) file streams have no credential notion, and the blocking `*.Create` variants already resolve the key internally by provider string — so a streaming-only credential parameter would make the streaming open authenticate differently from the blocking one for no semantic reason and break drop-in swapping between the two forms. The streaming open therefore takes `(GenerateRequest)` and authenticates exactly as the blocking call does. The one legitimate need an explicit credential could serve — selecting among multiple keys for a single provider (multi-tenancy) — is better met by a non-secret credential *alias* on the host `CredentialProvider` (a string id, not the raw key), applies equally to blocking and streaming, and is out of scope here.

**Known limitation:**

- *Replay determinism.* The handle-drain model does not record chunks, so a streamed run is not replayable. External sources are non-deterministic regardless; only recording makes them reproducible. The deferred actor-runtime bridge inherits `EventRecorder` and becomes replayable — one more reason the bridge is the eventual home for streams that must be deterministic.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `core/EvaluationLimits.kt` | Add `streamReceiveTimeoutMillis` field (default aligned with `wallClockBudgetMillis`) + `--stream-receive-timeout-ms` CLI flag on `run`/`machine`/`group`; runtime policy, no hash impact | Small |
| `interpreter/ResourceTable.kt` | Add `KIND_LLM_STREAM` constant; `LlmStreamHolder` data class | Small |
| `interpreter/LlmProviders.kt` + per-provider `*Provider.kt` | Streaming request variant on the injected `LlmHttpClient`; open SSE response, return holder | Medium |
| `interpreter/Builtins.kt` | Register `*.CreateStream` (3 providers), `LLM.Stream.Receive`, `LLM.Stream.Close`, `Net.Stream.Receive` | Medium |
| `authoring/LayerAGrammar.kt` (prelude) | Three-part entries (EffectCategory reuse + FunctionType + ForeignNode) per new builtin; `Option<Bytes>` result types | Medium |
| `interpreter/<systemprompt>` agent docs | New "Streaming I/O" section: open/receive/close contract, EOF-as-`None`, SSE-decode-in-Strand guidance | Small |
| `corpus/<NN>-llm-stream-drain/` (+ `81`-ish slot) | Verify-and-run streaming exemplar over an injected transport | Small |
| `interpreter` tests (`BuiltinsAnthropicTest`, new `StreamingReceiveTest`) | Scenarios 1–10 | Medium |
| `runtime`/VM equivalence + corpus tests | Scenario 11; ensure `interpreter == VM` where applicable | Small |

**Order of work.** (1) `ResourceTable` kind + holder + `EvaluationLimits.streamReceiveTimeoutMillis` carrier. (2) `Net.Stream.Receive` and its prelude entry + read-timeout-at-open + tests — smallest end-to-end slice, exercises the full contract (Option-EOF, timeout, sandbox-at-open) with no provider work. (3) Per-provider streaming transport + `*.CreateStream` + `LLM.Stream.Receive`/`Close` + tests. (4) Prelude + system-prompt docs + corpus exemplar. (5) The deferred actor-runtime bridge is out of this slice entirely (its own future Q per § 8).

**Not in this slice.** The actor-runtime bridge, decoded `StreamEvent`s, process/file streaming, TLS/WebSocket streaming, streaming send, per-receive timeouts, and replay recording for streams. Each is listed in § 8 with its unblocker.

This is best executed with the `strand-add-builtin` skill (three-part registration per builtin), not `strand-add-node` — no new node category is introduced.

## References

**Outgoing references:**
- [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md) — the static-set effect closure; why streaming needs no effect-multiplicity construct
- [`decisions/ADR-004-effects-as-edges.md`](../../decisions/ADR-004-effects-as-edges.md) — mandatory effect declarations; the closure-release rule at Application
- [`design/state-machines.md`](../../design/state-machines.md) — the push-based actor runtime, backpressure policies, and the external-stream seam the deferred bridge targets
- [`decisions/ADR-005-foreign-nodes.md`](../../decisions/ADR-005-foreign-nodes.md) — foreign binding trust; the sandbox lives in the runtime boundary, not the graph
- [`proposals/implemented/layer-4-step-2-real-io.md`](layer-4-step-2-real-io.md) — `Net.Receive`, `ResourceTable`, the synchronous one-shot baseline this generalizes
- [`proposals/implemented/agent-native-capabilities.md`](agent-native-capabilities.md) — Q-037 § 4.6 streaming sketch and the `llm_stream` kind reservation
- [`stdlib-future-builtins.md`](../stdlib-future-builtins.md) — TLS/WebSocket open-design items kept out of scope

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-045 points at this proposal
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — Known gaps section
