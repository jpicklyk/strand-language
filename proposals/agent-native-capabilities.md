# Agent-native LLM capabilities

**Document:** `proposals/agent-native-capabilities.md`
**Status:** Draft proposal
**Date:** 2026-05-26
**Revised:** 2026-05-26 (second revision: operation-shaped effect categories with provider as a refinement parameter, consistent with Strand's existing E-001..E-034 pattern and with WIT / WASI / effect-systems research. Per-provider ForeignNodes preserved; per-provider effect categories were over-commitment without prior-art support — the first revision conflated SDK-level per-provider packaging with effect-category granularity)
**Concerns:** [Q-037](../open-questions.md#Q-037), [Q-038](../open-questions.md#Q-038), [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md), [`design/state-machines.md`](../design/state-machines.md), [`design/rendering-and-views.md`](../design/rendering-and-views.md), [`design/security-model.md`](../design/security-model.md), [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md), [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md), [`decisions/ADR-009-structured-outputs.md`](../decisions/ADR-009-structured-outputs.md)
**Scope:** medium (two shippable phases after vector storage split out)

A Strand-distinctive surface that exposes language-model generation and embedding as first-class builtins through per-provider ForeignNodes under operation-shaped effect categories. Strand's positioning as a language for AI agents to generate (per [`00-motivation.md`](../00-motivation.md)) suggests that this surface is likely the most-used builtin family in the long run, and its shape may constrain the stdlib and the runtime resource model before further generic stdlib expansion picks the next round. Vector storage and retrieval — the natural downstream consumer of `LLM.Embed`'s output — is split to a sibling proposal [Q-038](agent-native-vector-stores.md) because its API surface raises independent design questions (metric grain, index opacity, filter expression language, typed metadata) that warrant separate treatment.

## 1. Problem statement

The existing ~110 builtins (after stdlib expansion rounds 1–3) cover the conventional surface — arithmetic, strings, filesystem, network, processes, HTTP, JSON. None of them give a Strand program direct access to the capabilities an AI agent operates on: calling a language model, producing an embedding, validating structured output. An agent generating Strand programs that *do* agent work today must either go out through `Http.Request` (treating Anthropic's API as a raw HTTP endpoint and hand-assembling the JSON) or have the host inject the model call as a custom ForeignNode at every binding boundary.

Both options leak abstractions and break the structural-reasoning property:

- A `Http.Request` to `api.anthropic.com` is an effect-typed `Network.Connect/Send/Receive` triple. The effect closure of an agent that uses three providers and a vector store looks identical to any other HTTP-using program. The verifier cannot reject a graph that "should not be allowed to call models" — only "should not be allowed to call api.anthropic.com over the network," which is a much coarser policy.
- Each host integration ships its own ForeignNode bindings. The provenance trust model in [`design/security-model.md`](../design/security-model.md) presumes a small, stable set of trusted bindings. Per-deployment bindings multiply the surface area for trust review.
- Structured-output and tool-use calls are exactly the workloads that benefit from Strand's Schema mechanism (N-032, N-033) — but only if the language has an obvious place to plug Schema into the LLM call. Today there is no place.

The gap is not that agents *cannot* be written in Strand. It is that the natural mapping — "a Strand state machine that responds to user messages by calling a model, optionally invoking Strand-side tools, and emitting structured output validated by a Schema" — is more painful than it should be, and the verifier sees less than it could.

The question this proposal answers is what builtin surface should sit between Strand programs and language-model providers, and what changes to the effect taxonomy, the resource model, and the existing node algebra it implies.

## 2. Prior art

The model-API ecosystem has converged on a small set of primitive operations across an otherwise heterogeneous landscape. The convergence is itself evidence that the abstractions are stable enough to bake in.

**Language-model calls.** The Anthropic Messages API, OpenAI Chat Completions and Responses APIs, Gemini's generateContent, and Bedrock Converse have all converged on a request shape of `(messages, model, max_tokens, tools?, system?, response_format?, stream?, cache_control?)` returning `{content_blocks, stop_reason, usage}`. Content blocks are a sum: text, tool-use, tool-result, image, document. The tool-use loop is identical across providers: the model emits a tool-use block, the host runs the tool, the host sends the tool-result back in the next message, the loop continues until the model emits a stop-reason of `end_turn`. Structured output via JSON Schema is supported in three forms — Anthropic's tool-as-response-shape pattern, OpenAI's `response_format: json_schema`, and Gemini's `responseSchema`. All three constrain decoding so the model output conforms to the schema.

**Embedding calls.** Anthropic's voyage embeddings, OpenAI's `embeddings.create`, Cohere's embed, Voyage's embed all return a fixed-dimension float vector keyed by model. Input is `(text, model, dimensions?, input_type?)`; output is `{vector: float[], usage}`. Dimension and dtype are determined by the model.

**Tool definitions.** Every provider accepts tool definitions as `(name, description, parameter_schema_in_json_schema, ...)`. Strand-distinctive: the cleanest mapping is for tools to be Strand callables (Lambdas or ForeignNodes) whose signature determines the JSON schema. The host runtime is the bridge between the provider's tool-use protocol and the Strand side.

**Prompt caching.** Anthropic's prompt caching and Gemini's context caching expose an API where prefix segments of the conversation are marked for caching; subsequent calls reuse the cached prefix at a fraction of the input-token cost. OpenAI's caching is automatic and not user-controllable. From a language-design perspective, caching is a provider-side optimization that does not change the call's semantics — but it can be substantial (10× cost reduction is common). A first-class language hook is not required; an option on the LLM call is sufficient.

**Per-provider library design (binding granularity).** Vercel's AI SDK ships one package per provider (`@ai-sdk/anthropic`, `@ai-sdk/openai`, `@ai-sdk/google`). LangChain ships per-provider subclasses of `BaseChatModel` (`ChatAnthropic`, `ChatOpenAI`, etc.). The Rust ecosystem ships per-provider crates (`anthropic-rs`, `openai-api-rs`). MCP standardizes at the protocol layer with per-server bindings. Only LiteLLM uses a single-call dispatch-on-string surface, and LiteLLM optimizes for Python ergonomics where no structural verifier consumes the call shape. The cross-ecosystem convergence is on per-provider *bindings*, which Strand reflects as per-provider ForeignNodes.

**Effect-system granularity (category shape).** Per-operation effect categories are universal in the effect-systems research literature. Koka uses `io`, `exn`, `console`, `net`, `state`, `div`. OCaml 5 effect handlers, Eff, Frank — all use operation-shaped abstract effects. WIT / WASI use operation-shaped interfaces (`wasi:filesystem`, `wasi:http`, `wasi:sockets`) with per-implementation bindings under shared interfaces. POSIX syscalls are operation-shaped; different libc implementations all share the same effect surface. Capability-based OS designs (seL4, Genode) parameterize capabilities by resource (this file, this endpoint), not by vendor. Strand's existing E-001..E-034 follow the same operation-shaped convention — `Filesystem.Read{path}` is one category whether ext4, NFS, or btrfs handles it. The position this prior art puts us in: per-provider *bindings* are well-supported and Strand follows that pattern at the ForeignNode layer, but per-provider *effect categories* would be a departure from both the research literature and Strand's own established taxonomy. The clean design separates the two.

## 3. Recommended approach

Per-provider ForeignNodes under operation-shaped effect categories. Provider identity lives at the binding layer; the effect category captures the kind-of-side-effect. Provider scoping in capabilities flows through the existing refinement-lattice (Q-031) parameter slots, exactly as `Network.Connect{host}` scopes by host.

### 3.1 Effect categories (additions to [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md))

| ID | Category | Parameters | Description |
|----|----------|-----------|-------------|
| E-035 | LLM.Generate | provider: String, model: String | Invoke a language model for text or structured generation |
| E-036 | LLM.Embed | provider: String, model: String | Compute an embedding from text |

The category is operation-shaped — `LLM.Generate` is one effect whether Anthropic, OpenAI, Gemini, or a future provider executes it. The `provider` and `model` parameters discriminate at the refinement-lattice level. This is consistent with:

- **Strand's existing E-001..E-034.** `Filesystem.Read{path}` is one category across all filesystem implementations; `Network.Connect{host, port}` is one category across all network stacks. Per-vendor effect categories would be a departure from the established taxonomy.
- **WIT / WASI.** `wasi:http` is a single interface; different runtimes (Wasmtime, Wasmer, Spin) ship distinct *bindings* under the shared interface.
- **Effect-systems literature.** Koka, OCaml 5, Eff, Frank all use operation-shaped abstract effects with implementation distinction at the binding/handler layer.

Capability scoping works through the refinement-lattice:

- `LLM.Generate{provider: *, model: *}` — wildcard, authorizes any model call to any provider.
- `LLM.Generate{provider: "anthropic", model: *}` — Anthropic only, any model.
- `LLM.Generate{provider: "anthropic", model: "claude-opus-4-7"}` — exactly one model.

This is the same mechanism `Network.Connect{host: "api.example.com"}` uses. Writing a refinement clause to scope by provider is the *design* of refinement-lattice capability matching, not a flaw.

### 3.2 Per-provider ForeignNodes

Provider identity lives at the binding layer. Each provider's calls are distinct content-addressed ForeignNodes:

- `strand-builtin:Anthropic.Messages.Create` — Anthropic's Messages API
- `strand-builtin:Anthropic.Embeddings.Create` — Anthropic's embeddings (via Voyage)
- `strand-builtin:OpenAI.Chat.Completions` — OpenAI's Chat Completions API
- `strand-builtin:OpenAI.Embeddings.Create` — OpenAI's embeddings
- `strand-builtin:Gemini.GenerateContent` — Gemini's generateContent
- `strand-builtin:Gemini.EmbedContent` — Gemini's embedContent

Each ForeignNode has its own provenance metadata, its own credential resolution, its own HTTP/auth shape. The ForeignNode's effect declaration pins the `provider` refinement parameter to a string literal (`"anthropic"` for the Anthropic targets, `"openai"` for OpenAI, etc.); the `model` parameter resolves from the call site's argument.

This gives three load-bearing properties that Strand's design requires:

- **Content addressing.** Swapping providers means swapping ForeignNodes, which changes the graph's hash per ADR-005. A graph using `Anthropic.Messages.Create` and a graph using `OpenAI.Chat.Completions` are distinct content-addressed graphs even if every other field is identical.
- **Provenance trust.** Each binding is signed and reviewed independently per the [`design/security-model.md`](../design/security-model.md) § Foreign binding trust mechanisms. The trust review is per-binding, not per-effect-category.
- **No central runtime dispatch.** The choice of provider is structural in the graph, not a string lookup in a runtime registry. There is no `LlmProviders.registry` that conflates provider trust into one surface.

### 3.3 Generate request shape

The `GenerateRequest` shape is unified across providers; provider identity is encoded by which ForeignNode is called, not by a field on the request.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `model` | String | yes | Model identifier (e.g., `"claude-opus-4-7"`, `"gpt-5"`, `"gemini-pro-2.0"`) |
| `messages` | List<Message> | yes | Conversation history |
| `system` | Option<String> | no | System prompt |
| `maxTokens` | Option<Int> | no | Output token budget |
| `tools` | List<ToolDef> | no | Available tools the model may invoke |
| `responseSchema` | Option<Schema> | no | If present, output must satisfy this Schema |
| `cache` | Option<CacheControl> | no | Prompt-caching configuration |
| `temperature` | Option<Float> | no | Sampling temperature |
| `providerExtras` | Option<JsonValue> | no | Provider-specific fields not in the unified shape |

`Message` is a sum: `User(content: List<Block>) | Assistant(content: List<Block>) | ToolResult(toolUseId: String, content: Bytes)`. `Block` is a sum: `Text(String) | ToolUse(id: String, name: String, input: JsonValue) | Image(Bytes, mediaType: String) | Document(Bytes, mediaType: String)`. The shape is structurally identical to the Anthropic Messages content blocks and maps trivially onto the OpenAI / Gemini equivalents at the provider-library boundary.

Each provider's ForeignNode accepts a `GenerateRequest` and returns a `GenerateResult`:

```
GenerateResult = {
  content: List<Block>,
  stopReason: StopReason,         // EndTurn | MaxTokens | StopSequence | ToolUseLimit
  usage: TokenUsage,
  finalMessages: List<Message>
}
```

Where the unified shape is genuinely lossy for a provider, the provider-side library translates `providerExtras` JsonValue fields into provider-native request fields. The cost of mismatch is opaque at the language level (the field name is a string) but the agent can read provider-specific documentation to know what is accepted.

### 3.4 Multi-provider graph pattern

Switching providers is structural: emitting a different ForeignNode. Two patterns are common.

**Static choice.** The agent's graph commits to one provider per call site. `Anthropic.Messages.Create` and `OpenAI.Chat.Completions` are different ForeignNodes; the graph's hash reflects which one is in use.

**Dynamic dispatch via Match.** When an agent needs to pick a provider at runtime (cost-based routing, fallback on failure, capability-based selection), the pattern is a `Provider` SumValue plus a Match:

```
Provider = Anthropic(model: String) | OpenAI(model: String) | Gemini(model: String)

Lambda dispatchGenerate(p: Provider, messages: List<Message>) -> GenerateResult =
  Match p
    Anthropic(m) -> Anthropic.Messages.Create({model = m, messages = messages, ...})
    OpenAI(m)   -> OpenAI.Chat.Completions({model = m, messages = messages, ...})
    Gemini(m)   -> Gemini.GenerateContent({model = m, messages = messages, ...})
```

This Lambda's effect closure is the *union* of every branch's effect — three EffectDecls under the same `LLM.Generate` category but with different `provider` refinement values:

- `LLM.Generate{provider: "anthropic", model: <Anthropic branch's model>}`
- `LLM.Generate{provider: "openai", model: <OpenAI branch's model>}`
- `LLM.Generate{provider: "gemini", model: <Gemini branch's model>}`

A caller that holds only `LLM.Generate{provider: "anthropic", model: *}` cannot invoke the dispatch Lambda — the OpenAI and Gemini branches' refinement requirements are not covered. A caller that holds `LLM.Generate{provider: *, model: *}` (wildcard) can invoke it, as can a caller that holds three concrete grants for the three providers. This is exactly the structural-reasoning property — the verifier sees the full effect surface, not a runtime-resolved subset.

### 3.5 Agent state — use state machines

The existing state machine model (Layer 6 steps 1–3, see [`design/state-machines.md`](../design/state-machines.md)) is the right model for long-running agents. The five-call review examined eight concrete agent workloads (single-turn, multi-turn, tool-using, RAG, persistent, multi-agent, backtracking, streaming) and confirmed state machines handle each — often *better* than mutable cells would, because the snapshot/replay/supervision machinery shipped in Layer 6 step 3 is content-addressed and deterministic. Concretely:

- The agent's State is the conversation history (`List<Message>`) plus any auxiliary memory the agent maintains (a running summary, working memory, retrieved-context cache).
- The transition function is `(State, Event) -> (State, Outputs)` where `Event` is a user message (or observation, or tool result) and `Outputs` are emitted assistant messages, tool calls, observations to the broader world.
- The transition body calls one of the provider ForeignNodes (or the dispatch Lambda above), interprets the result, and produces the next state.
- Input streams: user messages, sensor data, timer ticks, supervisor events. Output streams: assistant messages to the user, tool effects to the world, log lines, metrics.

This pattern reuses the existing supervisor / restart / snapshot / replay machinery from step 3 of the state-machine runtime work. Long-running agents survive process restarts via the existing snapshot mechanism; the conversation history is content-addressed because it is a Strand value; provenance and audit fall out for free.

E-017 Memory.MutableState stays absent. Mutable state for agents flows through state-machine state, not through a separate mutable-cell primitive. The "absent by design" status is preserved.

### 3.6 Conversation handles — opt-in opaque resource

For workloads that benefit from provider-side conversation state (Anthropic prompt-cache lifetimes longer than a single request, OpenAI Assistants threads, Gemini context caching as a separate resource), the GenerateRequest accepts an optional conversation handle:

```
GenerateRequest.conversation: Option<Resource>   // kind = "llm_conversation"
```

Created by a provider-specific Open builtin (`strand-builtin:Anthropic.Conversation.Open`, etc.) and closed by the corresponding Close. The handle's lifecycle is host-managed via the existing `ResourceTable`. When a handle is supplied, the provider library can elide already-sent prefix bytes from the next request; when absent, every call carries the full message list. The state machine pattern works in both cases — the handle is just another opaque value in the State value.

### 3.7 Schema-constrained output

The `responseSchema: Option<Schema>` field on `GenerateRequest` is the integration point with N-032 / N-033. When present:

- Provider libraries that support native structured-output (Anthropic tool-as-response, OpenAI JSON Schema mode, Gemini responseSchema) translate the Schema's `valueType` into JSON Schema and pass it to the provider for constrained decoding.
- Provider libraries that do not natively support structured output post-validate against the Schema and re-prompt on failure (up to a retry bound declared in the request).
- In both cases, the final returned value is checked against the Schema's invariants by the existing `SchemaChecker.tryEvaluateStatically` path. Static invariants on the literal-bearing output produce verdicts at the call site; non-static invariants produce `SchemaInvariantDeferred` diagnostics in the standard way.

### 3.8 Tools as Strand callables (tight binding)

The `ToolDef.implementation` is a Strand NodeRef pointing at a Lambda or ForeignNode whose type matches `parameterSchema.valueType -> R`. When the model emits a tool-use block, the LLM.Generate loop:

1. Parses the model's `input` JSON into a value of `parameterSchema.valueType` via the existing `Json.Parse` + Schema-validation path.
2. Invokes the `implementation` callable on the parsed value (via the existing `Interpreter.applyCallable` mechanism the runtime already uses for state-machine transition functions).
3. Captures the result (typically a `Bytes` or `String` rendering of the tool output, or a structured `JsonValue` the next iteration serializes).
4. Appends a `Message.ToolResult` to the conversation and re-calls the provider.

The tool implementation runs in the surrounding capability context — the provider's capability check is for the LLM call itself; the tool's effects are checked separately when its implementation runs. A side benefit: tools authored as Strand callables compose with effect handlers (N-043). An agent author can wrap a tool that does `Fs.Write` with a Handler that mocks the write for testing, all within the language.

#### 3.8.1 Translator scope — irreducible subset

The `parameterSchema.valueType` must be expressible in JSON Schema. The translator supports an irreducible subset of `TypeExpr` and explicitly rejects the rest. The subset is fixed at proposal time so the verifier can produce a clear error rather than degrading silently.

| TypeExpr variant | JSON Schema mapping |
|------------------|---------------------|
| `Primitive.Int` | `{"type": "integer"}` |
| `Primitive.Float` | `{"type": "number"}` |
| `Primitive.String` | `{"type": "string"}` |
| `Primitive.Bool` | `{"type": "boolean"}` |
| `Primitive.Bytes` | `{"type": "string", "contentEncoding": "base64"}` |
| `Primitive.Unit` | `{"type": "object", "properties": {}}` |
| `Product` | `{"type": "object", "properties": {...}, "required": [...]}` with every field required (Strand has no optional fields outside `Option<T>`) |
| `Sum` | `{"oneOf": [...]}` with a `tag` discriminator per case (matching Strand's SumValue canonical encoding) |
| `RecursiveType` (list-like) | `$defs` entry with `$ref` self-reference |
| `Option<T>` | translates to JSON Schema's nullable convention (`{"oneOf": [T, {"type": "null"}]}`) |

Rejected variants (verifier raises `ToolParamTypeUnsupported(at, toolDefId, type)`):

- `FunctionType` — JSON Schema has no callback representation; tools accepting callbacks are nonsensical at the provider boundary.
- `ForallType` — JSON Schema has no parametric polymorphism; generic tools must be monomorphized at the ToolDef construction site.
- `Invariant` bodies that aren't expressible as JSON Schema keywords (range bounds, length bounds, regex patterns *are* expressible; arithmetic like `x + y < 100` is not). Non-expressible invariants are still checked by `SchemaChecker` after the model's input arrives — they just don't constrain decoding.

The two-tier validation contract makes this layering explicit:

1. The provider receives a JSON Schema (the subset expressible) and constrains its decoding.
2. After the provider returns a value, Strand parses it and re-validates the parsed value against the full Schema (including non-translatable invariants) via `SchemaChecker.tryEvaluateStatically`. Invariant failures produce `LLM.OutputInvalid` runtime errors.

This is the cleanest path from "agent emits text" to "agent emits a value whose validity the verifier vouches for." The Schema mechanism's whole point is to provide that vouching; making LLM output a first-class Schema target is the obvious composition.

### 3.9 Embedding consumers — Bytes, with documented upgrade path

Embed builtins return `Bytes` (IEEE 754 float32 little-endian, length = `4 * dimensions`). The Bytes representation is intentionally provider-agnostic and matches the wire formats used by every major vector store. Conversions to higher-level shapes (Vector typed value, List<Float>) are pure Bytes manipulations callers can layer on top.

The choice to use raw Bytes rather than a typed `Vector<dim, dtype>` primitive is informed by the [`nested-recursive-self-depth`](implemented/nested-recursive-self-depth.md) precedent — Strand has burned once on speculatively adding foundational type-system primitives (the RecursiveSelf depth field) that ended up not being load-bearing in the way originally planned. Adding a parameterized `Vector` primitive opens the door to `Tensor<rank, dims..., dtype>` and other parameterized type families that Strand has so far drawn the line against. The upgrade gate is explicit: graduate from Bytes to a `TypeExpr.Vector` primitive (unparameterized at the encoding level — dimensions and dtype carried as content fields, like `Primitive.Bytes` carries length at the value level) when (a) a vector-math builtin family ships in the stdlib AND (b) at least three concrete dimension-mismatch bugs from real agent corpora demonstrate that runtime detection at the vector-store insert/query boundary is not catching them early enough.

Until both gates are met, the verifier's leverage over dimension mismatches is recoverable: the vector store's Insert / Query immediately rejects mismatched-dimension input as a runtime error (one effectful call removed from where a typed Vector would catch it at the verifier). Vector storage and retrieval is the subject of [Q-038](agent-native-vector-stores.md); embedding consumers downstream of `LLM.Embed` should pass the Bytes through without manipulation in most cases.

## 4. Detailed mechanism

### 4.1 Effect-closure rules

Each provider ForeignNode declares its `LLM.Generate` or `LLM.Embed` effect with the `provider` parameter pinned to a string literal (`"anthropic"`, `"openai"`, `"gemini"`) and the `model` parameter bound to the call site's `request.model` argument. The verifier's existing `Application.effectInstances` mechanism handles this without change — at each call site, the EffectDecl carries the concrete `(provider, model)` pair and the refinement-lattice matcher checks the runtime capability.

A subtle point: each provider's tool-use loop must include the union of all `ToolDef.implementation` effects in its closure, because the loop may invoke any tool. The verifier computes this at the call site by walking the `tools` argument (which is a Strand value built from ToolDef constructors). For dynamic tool lists (computed at runtime), the verifier requires an explicit effect declaration on the call site covering the union; the value-level type checking ensures the implementations conform.

### 4.2 Resource lifecycle

`Value.Resource(id, kind)` is the same opaque-handle mechanism Layer 4 step 2 uses. One new kind for this proposal:

| Kind | Underlying | Released by |
|------|-----------|-------------|
| `llm_conversation` | provider conversation/thread id | provider-specific `*.Conversation.Close` |

Handles are non-graph values: they exist only in a live runtime and cannot be content-addressed. This is consistent with the existing handle pattern. A program that wants to "save" a conversation across runs persists the underlying provider identifier as a String and reopens; the handle itself is process-local.

Vector store handles are defined in [Q-038](agent-native-vector-stores.md) under the same `ResourceTable` mechanism.

### 4.3 Per-provider implementation

Each provider's ForeignNode is implemented as a distinct entry in `interpreter/Builtins.kt`. There is no central runtime registry that dispatches across providers — the choice of provider is structural in the graph, and the dispatch happens at the `Builtins.lookup` level via the target identifier.

```kotlin
val registry: Map<String, Fn> = mapOf(
    "strand-builtin:Anthropic.Messages.Create" to AnthropicMessagesCreate,
    "strand-builtin:Anthropic.Embeddings.Create" to AnthropicEmbeddings,
    "strand-builtin:OpenAI.Chat.Completions" to OpenAIChatCompletions,
    "strand-builtin:OpenAI.Embeddings.Create" to OpenAIEmbeddings,
    "strand-builtin:Gemini.GenerateContent" to GeminiGenerateContent,
    "strand-builtin:Gemini.EmbedContent" to GeminiEmbedContent,
    // ...
)
```

Each provider implementation is its own Kotlin object (`AnthropicMessagesCreate`, etc.) with its own HTTP client setup, its own credential resolution, its own error translation. Each entry's effect declaration pins the `provider` refinement parameter to the appropriate string literal. Tests inject mocks per-provider by overriding individual entries (the same `Builtins.clock`-style pattern, but per-target rather than global).

### 4.4 Credentials and API keys

Credentials are not capabilities. A capability `LLM.Generate{provider: "anthropic", model: *}` authorizes calling Anthropic models; it does not carry the API key. The API key is configured at the runtime boundary — either via environment variable (`ANTHROPIC_API_KEY`), via a runtime config the host installs at startup, or via a credential-provider abstraction the host injects. Each provider's ForeignNode reads its own credential source (Anthropic reads ANTHROPIC_API_KEY, OpenAI reads OPENAI_API_KEY, etc.).

This keeps credentials out of the content-addressed surface — graphs do not have to be re-hashed when an API key rotates, and a graph copied between hosts works under whatever credentials the target host has configured. The credential is part of the execution environment, not the program.

### 4.5 Prompt caching

`GenerateRequest.cache: Option<CacheControl>` is a structured value:

```
CacheControl = Auto                            // provider-driven
             | Breakpoints(List<Int>)          // message indices at which to mark prefixes cacheable
             | Ephemeral                       // do not cache
```

Each provider's library translates the CacheControl into its native cache-config representation. Anthropic's breakpoint-based prompt caching (where most cost lives today) is preserved with the `Breakpoints` variant; providers without explicit cache control treat the field as informational. Cache usage statistics appear in the returned `TokenUsage` so the agent can observe cache hit rates and adjust strategy.

### 4.6 Streaming

Initial scope: blocking calls only. Each provider's ForeignNode returns the full `GenerateResult` after the model emits a stop reason. Streaming is a natural extension, but it requires either (a) integrating with the state-machine actor runtime (the LLM call becomes an event source feeding into the agent's input streams) or (b) a new builtin pattern that exposes a chunked-receive interface. Both are viable; neither is needed to ship the initial primitive surface.

When streaming lands, the design path is: a streaming LLM call returns a `Resource(kind = "llm_stream")` immediately; subsequent `LLM.Stream.Receive(handle, maxBytes)` calls drain incrementally. This matches the existing `Net.Receive` pattern.

## 5. Verifier rules

The new effect categories propagate through the existing closure machinery without modification. Four rules deserve callouts:

- **Operation-shaped effect propagation.** Each provider ForeignNode declares an `LLM.Generate` or `LLM.Embed` effect with `provider` pinned to its provider's string and `model` bound to the call argument. The refinement-lattice matcher checks each instance against the runtime capability. A graph that uses multiple providers carries multiple EffectDecls under the same category with different `provider` parameter values; the verifier sees the union and demands the capability cover all of them.
- **Tool closure.** When a static tool list is supplied, the verifier walks the `tools` argument to compute the union of `ToolDef.implementation` effects and adds them to the call site's effect closure. When the tool list is computed (a List value built at runtime), the call site must declare the union explicitly via `effectInstances` to satisfy the closure check. Runtime tool dispatch verifies that each tool's actual closure is a subset of the declared union.
- **ToolParamTypeUnsupported.** New verifier error raised when a `ToolDef.parameterSchema.valueType` contains a `FunctionType`, `ForallType`, or a recursive shape the translator cannot project to JSON Schema. The error identifies the offending NodeId and the rejected type variant. Documentation lists the supported subset (§ 3.8.1).
- **Schema-typed return values.** When `responseSchema` is present on a `GenerateRequest`, the provider ForeignNode's return type narrows from `GenerateResult` to a `SchemaType` over the schema's valueType. This is the same `typesCompatible(expected, actual)` relaxation the Q-035 work introduced.

No new node category is added. No verifier-rule rewrites required beyond standard effect-closure propagation and the new `ToolParamTypeUnsupported` rule.

## 6. Runtime semantics

The interpreter dispatches LLM-related builtins through the standard `Builtins.lookup` path. The runtime adds:

- **Per-provider builtin implementations** in `interpreter/Builtins.kt`: one entry per provider per call type (Generate, Embed). Each is a distinct Kotlin object.
- **`ResourceTable` extensions**: one new `kind` string (`llm_conversation`) shared across providers (the host-side library knows the originating provider from the open-time configuration).
- **Credential resolution**: a host-supplied `CredentialProvider` interface that the provider implementations call into to obtain API keys at call time. Default implementation reads from environment variables; production deployments wire to a secret manager.
- **Tool dispatch in the generate loop**: each provider's loop uses `Interpreter.applyCallable` to invoke tool implementations (the same mechanism state machine transitions use). Recursive (tool → generate → tool → ...) calls are bounded by the request's max-tool-call depth (default 10, configurable).

Each provider's generate loop is itself a small state machine inside the builtin — request, await response, dispatch tool if any, repeat. This is intentional: the loop is the right place to enforce the per-call budget (max tools, max tokens, max wall time), and keeping it inside the builtin means agent code does not have to reimplement the loop.

For long-running agents that prefer to *see* every tool call, an alternative builtin `<Provider>.Messages.Step` (or equivalent) performs exactly one round trip and returns immediately (no internal loop); the agent's outer state machine handles the tool-use cycle. Both surfaces are available; the loop builtin is a convenience over the step builtin.

## 7. Test scenarios

Happy path, error path, edge cases the test suite must cover:

1. **Plain generation.** A request to `strand-builtin:Anthropic.Messages.Create` with a single user message, no tools, no schema, returns a non-empty text block and `stopReason = EndTurn`. Provider mock returns a canned response; assert the loop completes in one round trip. Effect closure at the call site is `LLM.Generate{provider: "anthropic", model: <request.model>}`.
2. **Tool-use loop.** Request with one tool. Provider mock returns a `ToolUse` block on call 1, an `EndTurn` text block on call 2 after seeing the tool result. Assert the tool implementation was invoked exactly once with the parsed input value, the result was appended to messages, and the second provider call carried the full message list.
3. **Schema-constrained output, valid response.** `responseSchema` supplies a Schema describing a `{x: Int, y: Int}` value. Provider mock returns text that parses to `{"x": 1, "y": 2}`. The final result is the typed product, Schema invariants pass, no diagnostics raised.
4. **Schema-constrained output, invalid response.** Same Schema, provider returns `{"x": -1, "y": 0}`, Schema invariant `x > 0` rejects. Surface `LLM.OutputInvalid` runtime error with the failing invariant identifier.
5. **Tool with translatable parameter type.** Tool whose `parameterSchema.valueType` is `Product({a: Int, b: String})`. Translator produces `{"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "string"}}, "required": ["a", "b"]}`. Provider mock invokes the tool with valid input; the parsed value reaches the implementation Lambda.
6. **Tool with rejected parameter type.** Tool whose `parameterSchema.valueType` is a `FunctionType`. Verifier rejects the graph at admission with `ToolParamTypeUnsupported(at, toolDefId, FunctionType)`. Confirm the error identifies the right NodeId.
7. **Provider-scoped capability.** A graph that uses `strand-builtin:Anthropic.Messages.Create` runs under `LLM.Generate{provider: "anthropic", model: *}`; succeeds. The same graph under `LLM.Generate{provider: "openai", model: *}` fails with a refinement violation (the provider parameter mismatches).
8. **Multi-provider dispatch Lambda.** A `Provider` SumValue + Match Lambda that selects per-call. Verifier sees three EffectDecls under `LLM.Generate` with three distinct `provider` refinement values. Caller with wildcard provider grant passes; caller with single-provider grant fails.
9. **Static tools, multi-effect closure.** Two tools, one declares `Filesystem.Write`, one declares `Network.Connect`. Call site declares both in `effectInstances`. Verifier accepts; running under a capability set missing `Filesystem.Write` fails at the right call, not at the LLM call.
10. **Dynamic tools, declared union.** Tools list is computed at runtime. Call site declares `{Filesystem.Write, Network.Connect}` in `effectInstances`. Verifier accepts at the call site. At runtime, the actual computed tools have closure `{Filesystem.Write}` only; the runtime verifies the tool's actual closure ⊆ declared union; OK.
11. **Embedding round trip.** `strand-builtin:Anthropic.Embeddings.Create` returns Bytes of length `4 * model.dimensions`. The provider mock returns a deterministic vector; assert bit-identical bytes.
12. **Conversation handle reuse.** Open an Anthropic conversation handle, generate twice with the same handle, assert both calls reuse the cache (assert via the returned `TokenUsage.cacheHits` field). Close the handle; subsequent generation with the handle fails with `ResourceTable.UnknownHandle`.
13. **Tool implementation as Strand Lambda.** A pure tool that adds two integers; the LLM emits a tool-use call; the runtime parses the input, applies the Lambda, returns the result. Confirms the tool dispatch composes with the standard Lambda apply path.
14. **Tool implementation as ForeignNode with effects.** A tool whose implementation is a `Fs.Write` ForeignNode. The LLM emits the tool call. The capability check at the tool's call site fires under the surrounding context. With Filesystem.Write granted, the write succeeds; without, the runtime fails the tool dispatch.
15. **Tool mocked via N-043 Handler.** A test wraps the LLM call in a `Handler(Filesystem.Write, mockWrite, body)`. The tool emits a Fs.Write effect; the Handler intercepts; the test asserts the mock was called with the right arguments and no real filesystem write occurred.
16. **Long-running agent state machine.** A state machine whose State is `List<Message>`, whose transition function calls a provider's generate builtin, runs over a 5-event input stream. Assert determinism: identical event stream produces identical state trajectory (provider mock returns deterministic responses).
17. **Snapshot and replay an agent.** Snapshot the state machine after event 3, restart from snapshot, feed events 4 and 5, assert the trajectory matches a clean 5-event run.
18. **Credential rotation.** Provider mock observes an updated API key on a second call (after host config changes); the graph is unchanged; both calls succeed.

## 8. Tradeoffs and open questions

This section records the tradeoffs reviewed during the five-call analysis (see [`open-questions.md` Q-037](../open-questions.md#Q-037) for the history). Decisions taken are marked **Resolved**; pending tradeoffs are marked **Open**.

- **Provider abstraction — Resolved (second revision).** Per-provider ForeignNodes under operation-shaped effect categories (this proposal § 3.1, § 3.2). The first revision proposed per-provider effect categories (`Anthropic.Generate`, `OpenAI.Generate`); a follow-up prior-art check found that recommendation conflated SDK-level per-provider packaging (which has strong prior art — Vercel AI SDK, LangChain, Rust crates, WIT bindings) with effect-category granularity (which has weak prior art — Koka, Eff, WIT interfaces, and Strand's own E-001..E-034 are all operation-shaped). The second revision keeps per-provider ForeignNodes (the load-bearing piece for content addressing and provenance trust) and reverts to unified operation-shaped categories with `provider` as a refinement parameter (consistent with `Network.Connect{host}`, `Filesystem.Read{path}`, etc.).

- **Tool definitions — Resolved.** Tight binding to Strand callables with a documented irreducible-subset translator (§ 3.8, § 3.8.1). The translator supports the JSON-Schema-expressible TypeExpr variants and explicitly rejects FunctionType, ForallType, and non-translatable invariant bodies. The two-tier validation contract (provider constrains decoding; Strand re-validates with the full Schema after parse) makes the layering legible.

- **Memory.MutableState (E-017) — Resolved.** Stays absent. The five-call review examined eight agent workloads (single-turn, multi-turn, tool-using, RAG, persistent, multi-agent, backtracking, streaming) and found state-machine modeling handles each, often better than mutable cells. The large-memory case (gigabyte profile store) is solved structurally via Resource handles ([Q-038](agent-native-vector-stores.md)), not by reopening E-017.

- **Vector.* scope — Resolved.** Split into [Q-038](agent-native-vector-stores.md). The LLM.Embed → Vector.Insert coupling is weaker than originally framed — once embeddings are Bytes, downstream consumers are independent. Vector storage raises substantial design questions (metric grain, index opacity, filter expression language, typed metadata, batching, pagination, idempotency) that warrant a dedicated proposal.

- **Embedding result encoding — Resolved.** Bytes (IEEE 754 float32 LE) with documented upgrade gate. The upgrade target is an unparameterized `TypeExpr.Vector` primitive (dimensions and dtype as content fields, like `Primitive.Bytes` carries length). Upgrade gates: vector-math stdlib lands AND ≥3 concrete dimension-mismatch bugs from real agent corpora demonstrate runtime detection at the vector-store boundary is insufficient. The [`nested-recursive-self-depth`](implemented/nested-recursive-self-depth.md) precedent informs this: speculatively adding foundational type-system primitives has burned the project before. Refinement (SchemaType<Bytes> with deferred invariants) rejected because embedding values are not statically known and every invariant would defer.

- **Conversation handles — Open.** Currently opt-in via `GenerateRequest.conversation: Option<Resource>`. The alternative (mandatory handles for every call) is uniform but forces threading. Pick: opt-in for the initial slice; mandatory if real workloads demand it.

- **Streaming — Open / Deferred.** Not in the initial slice. When it ships, the design path is `Resource(kind = "llm_stream")` returned from a streaming builtin variant, drained via `*.Stream.Receive` calls. The state-machine-actor-loop alternative (LLM stream as an internal stream) is more invasive and waits for use-case demand.

- **Cache control granularity — Resolved.** Structured `CacheControl` variant (Auto / Breakpoints(List<Int>) / Ephemeral) rather than a Bool. Anthropic's breakpoint-based caching (where most cost lives today) needs the granularity; providers without explicit cache control degrade to Auto.

- **Multi-modal content — Open.** The proposal includes `Block.Image(Bytes, mediaType)` and `Block.Document(Bytes, mediaType)`. Open: whether a separate `LLM.Vision` effect category is needed, or whether the `model` parameter is sufficient (vision-capable models are different model identifiers). Initial answer: the `model` parameter suffices.

- **Resource-kind-as-type — Open / Deferred.** `Resource` is a single TypeExpr variant with kind discrimination at runtime. The parameterized alternative (`Resource<"llm_conversation">`) would catch kind confusion at the verifier. Deferred to a broader resource-type design pass.

## 9. Implementation sketch

Two phases. Each phase is self-contained and ships independently.

### Phase 1: LLM.Generate + LLM.Embed for Anthropic, OpenAI, Gemini (medium)

| File | Change | Scope |
|------|--------|-------|
| `design/effects-and-capabilities.md` | Add E-035 LLM.Generate, E-036 LLM.Embed (operation-shaped, with `provider: String, model: String` parameters) | small |
| `INDEX.md` | Register E-035, E-036 in the identifier table | small |
| `impl-kotlin/interpreter/Builtins.kt` | Add per-provider Generate + Embed builtins (Anthropic / OpenAI / Gemini) each declaring `LLM.Generate{provider="X", model=<varref>}` or `LLM.Embed{provider="X", model=<varref>}` | medium |
| `impl-kotlin/interpreter/AnthropicProvider.kt`, `OpenAIProvider.kt`, `GeminiProvider.kt` (new) | Per-provider Kotlin objects implementing HTTP/auth/translation | medium-large |
| `impl-kotlin/interpreter/CredentialProvider.kt` (new) | Credential resolution interface; env-var-backed default | small |
| `impl-kotlin/interpreter/ResourceTable.kt` | Register `llm_conversation` kind | small |
| `impl-kotlin/interpreter/JsonSchemaProjection.kt` (new) | TypeExpr → JSON Schema translator for the irreducible subset | medium |
| `impl-kotlin/verifier/Verifier.kt` | Add `ToolParamTypeUnsupported` rule | small |
| `impl-kotlin/authoring/LayerAGrammar.kt` | Add prelude entries for the two effect categories and the six initial ForeignNodes | small |
| `evaluation/dynamic/prompts/strand-system.md` | Document the per-provider builtin surface for agent prompts | small |
| Tests | `BuiltinsAnthropicTest`, `BuiltinsOpenAITest`, `BuiltinsGeminiTest`, `JsonSchemaProjectionTest`, `ToolDispatchLoopTest`, `ProviderRefinementTest` | medium |
| Corpus | One demo program: a state machine that answers a user prompt and uses one tool, parameterized to test against any provider | small |

Estimated effort: 2 sessions of focused implementation, mostly because the three provider integrations are independent but each requires its own HTTP/auth shape.

### Phase 2: Agent-pattern documentation and corpus (small)

| File | Change | Scope |
|------|--------|-------|
| `design/state-machines.md` | Cross-reference the agent-as-state-machine pattern with concrete provider builtin usage in the transition function | small |
| `design/rendering-and-views.md` | Cross-reference how structured-output calls compose with Schema | small |
| Corpus | Two reference programs: (a) a multi-turn conversational agent with tool-use, (b) a multi-provider dispatch Lambda using the Match pattern | small |
| `evaluation/dynamic/tasks/` | Add agent-shaped tasks to the dynamic evaluation suite | small |

Estimated effort: half a session.

### Identifier assignments

- New effect categories: E-035 LLM.Generate, E-036 LLM.Embed (operation-shaped, with `provider: String, model: String` parameters)
- This open question: Q-037
- Sibling open question: Q-038 (agent-native vector stores; see [`agent-native-vector-stores.md`](agent-native-vector-stores.md))
- No new node categories
- No new ADRs (the foundational decisions in ADR-004 / ADR-005 / ADR-009 cover this work)

## 10. Relationship to other questions

- **Q-021 (evaluation).** Agent-shaped tasks (retrieval, tool-use, multi-turn) are the natural extension of the static-cost evaluation suite. Phase 2's corpus extension feeds directly into Q-021's dynamic-cost measurement.
- **Q-034 (authoring layer).** The new prelude entries for E-035 / E-036 and the six initial ForeignNodes sit in the same machinery as the round-2 / round-3 prelude additions. No new authoring-layer mechanism required.
- **Q-008 (high-throughput state machines).** Long-running agents are an instance of the high-throughput-machines workload Q-008 calls out. The agent-shaped corpus programs help validate the runtime engineering as it ships.
- **Q-012 (TEE attestation).** A future hardened agent runs in a TEE; the LLM call site's capability requirement composes with attestation-bound capabilities. No design change here, just a composition target.
- **Q-022 (agent intent confidentiality).** The conversation history is the agent's intent. Encrypting the State value via the existing per-node encryption mechanism is the natural defense. This proposal does not solve Q-022 but is compatible with it.
- **Q-038 (agent-native vector stores).** Companion proposal. LLM.Embed produces Bytes that vector stores consume. Q-038 covers the storage and retrieval surface, taking E-037 (Vector.Read) and E-038 (Vector.Write).

## References

**Outgoing references:**
- [`00-motivation.md`](../00-motivation.md) — the AI-first framing this proposal is downstream of
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — new effect categories follow this taxonomy
- [`design/state-machines.md`](../design/state-machines.md) — long-running agent pattern
- [`design/rendering-and-views.md`](../design/rendering-and-views.md) — Schema-constrained output composition
- [`design/security-model.md`](../design/security-model.md) — foreign binding trust applies to per-provider libraries
- [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — effects as mandatory edges
- [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — per-provider ForeignNodes follow this trust model
- [`decisions/ADR-009-structured-outputs.md`](../decisions/ADR-009-structured-outputs.md) — Schema mechanism integration
- [`proposals/implemented/refinement-lattice-capability-matching.md`](implemented/refinement-lattice-capability-matching.md) — refinement-lattice mechanism the provider/model parameters use
- [`proposals/implemented/state-machines-runtime-step-3.md`](implemented/state-machines-runtime-step-3.md) — snapshot / supervision / metrics integration
- [`proposals/implemented/json-blessed-library.md`](implemented/json-blessed-library.md) — JsonValue prior art for structured payloads
- [`proposals/implemented/nested-recursive-self-depth.md`](implemented/nested-recursive-self-depth.md) — precedent for speculative type-system primitives
- [`proposals/agent-native-vector-stores.md`](agent-native-vector-stores.md) — companion Q-038 proposal
- [`proposals/stdlib-future-builtins.md`](stdlib-future-builtins.md) — broader stdlib gap catalog
- [`open-questions.md`](../open-questions.md) — Q-037 registered here

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-037
- [`proposals/agent-native-vector-stores.md`](agent-native-vector-stores.md) — sibling proposal references Q-037 for the embedding source
- [`proposals/stdlib-future-builtins.md`](stdlib-future-builtins.md) — agent-native section points here
