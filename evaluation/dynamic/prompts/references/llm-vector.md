# Reference: llm-vector — per-provider LLM, tools, streaming I/O, vector stores

## Per-provider LLM (Q-037 Phase 1)

Six ForeignNodes — three Generate, three Embed — under operation-shaped
E-035 `LLM.Generate{provider: String, model: String}` and E-036
`LLM.Embed{provider: String, model: String}` effect categories.
Provider identity is encoded by which ForeignNode is called; the
EffectDecl at the call site pins the `provider` parameter to a string
literal (`"anthropic"` / `"openai"` / `"gemini"`) and `model` to the
request's model field.

    strand-builtin:Anthropic.Messages.Create(req: GenerateRequest) -> GenerateResult
    strand-builtin:Anthropic.Embeddings.Create(req: EmbedRequest)  -> Bytes
        -- declares llmGenerateFx / llmEmbedFx with provider="anthropic"
        -- Anthropic does NOT ship embeddings; this surfaces a structured
        -- IoFailure recommending Voyage AI.
    strand-builtin:OpenAI.Chat.Completions(req: GenerateRequest)   -> GenerateResult
    strand-builtin:OpenAI.Embeddings.Create(req: EmbedRequest)     -> Bytes
        -- declares llmGenerateFx / llmEmbedFx with provider="openai"
    strand-builtin:Gemini.GenerateContent(req: GenerateRequest)    -> GenerateResult
    strand-builtin:Gemini.EmbedContent(req: EmbedRequest)          -> Bytes
        -- declares llmGenerateFx / llmEmbedFx with provider="gemini"

**Request shape (`GenerateRequest`)** — a Strand ProductV constructed by
the agent. Field order is unconstrained (the canonical encoder sorts
by field name). Use the prelude EffectCategory `llmGenerateFx` /
`llmEmbedFx` and the ForeignNode short names `anthropicGenerate` /
`openaiGenerate` / `geminiGenerate` / `anthropicEmbed` / `openaiEmbed` /
`geminiEmbed`.

    GenerateRequest = {
      model: String,                              -- e.g., "claude-opus-4-7"
      messages: List<Message>,                    -- Cons/Nil chain
      system: Option<String>,                     -- system prompt
      maxTokens: Option<Int>,                     -- output token budget
      tools: List<ToolDef>,                       -- (empty list when no tools)
      responseSchema: Option<ResponseSchemaSpec>, -- Schema for constrained output (N-045 wrapper)
      temperature: Option<Float>,
      providerExtras: Option<JsonValue>           -- pass-through provider-native fields
    }

    Message = User(content: List<Block>)
            | Assistant(content: List<Block>)
            | ToolResult(toolUseId: String, content: Bytes)

    Block = Text(String)
          | ToolUse(id: String, name: String, input: JsonValue)
          | Image(bytes: Bytes, mediaType: String)
          | Document(bytes: Bytes, mediaType: String)

**Tool definitions** are first-class graph nodes (N-044 ToolDef).
Each tool is a `ToolDef` node with four edges:

    ToolDef:
      name: String                                -- metadata, forwarded to provider
      description: String                         -- metadata, forwarded to provider
      parameterSchema: Schema (N-032)             -- structural schema for the tool input
      implementation: Lambda | ForeignNode        -- callable of type `parameterSchema.valueType -> R`

The Layer A code is `TLD <name> <description> <parameterSchema> <implementation>`.
The `parameterSchema` reference MUST point at a `SCH` (Schema) node;
the verifier statically checks that the schema's `valueType` projects
to JSON Schema (else `ToolParamTypeUnsupported`). The implementation's
type must agree: `(parameterSchema.valueType) -> R` for some `R`.

The Generate builtin runs the tool-use loop internally: on each
iteration, if the model emits a `ToolUse` block, the loop parses the
input JSON to a value of `parameterSchema.valueType`, invokes the named
tool's `implementation` callable with that value, appends a
`ToolResult` message, and re-calls the provider. Bounded at 10
iterations by default; a `ToolUseLimit` stop reason indicates the cap
fired.

    GenerateResult = {
      content: List<Block>,                       -- final assistant blocks
      stopReason: EndTurn | MaxTokens | StopSequence | ToolUseLimit,
      usage: {inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens},
      finalMessages: List<Message>                -- conversation incl. tool turns
    }

**Tool parameter schemas** must use the irreducible
JSON-Schema-expressible TypeExpr subset: Primitives, Products (all
fields required), Sums (tag discriminator or `Option<T>`-as-nullable),
Recursives (`$defs`/`$ref`). `FunctionType`, `ForallType`, and unbound
type parameters are rejected — the verifier statically rejects any
`TLD` whose `parameterSchema`'s valueType contains one of those
variants, raising `ToolParamTypeUnsupported`. The check fires on every
ToolDef at admission, not just at provider call time.

**Response schemas** are first-class graph nodes (N-045
ResponseSchemaSpec). Each response schema wrapper is a `RSC` node with
one edge:

    ResponseSchemaSpec:
      schema: Schema (N-032)                    -- structural schema for the output

The Layer A code is `RSC <schema>`. The `schema` reference MUST point
at a `SCH` (Schema) node; the verifier statically checks that the
schema's `valueType` projects to JSON Schema (else
`ResponseSchemaTypeUnsupported`) using the same irreducible TypeExpr
subset documented for tool parameter schemas above. The wrapper is
value-producing — it evaluates to a runtime carrier that the
LLM.Generate builtin walks at dispatch time to obtain the projected
JSON Schema (forwarded as `response_format.json_schema.schema` on
OpenAI, `generationConfig.responseSchema` on Gemini, etc.).

Place the wrapper in the GenerateRequest's `responseSchema` field as
the `Some` payload of an `Option`:

    responseFieldT PRF "responseSchema" optBytesT
    ...
    answerFldT PRF "answer" strT
    answerT PRD [answerFldT]
    responseSchema SCH "Answer" answerT []
    answerSpec RSC responseSchema
    -- at the call site:
    schemaSomeFV PFV "responseSchema" (SV optBytesT "Some" answerSpec)
    requestV PV reqT [modelFV messagesFV toolsFV schemaSomeFV ...]

The two Schema-bearing positions on `GenerateRequest` — `tools`
(N-044 ToolDef) and `responseSchema` (N-045 ResponseSchemaSpec) — are
symmetric: both wrap an N-032 Schema through a graph edge, both have
their valueType projected to JSON Schema at admission, both produce a
runtime carrier the LLM.Generate builtin reads through
`Builtins.verifierNodeTypes`.

**Embed shape (`EmbedRequest`)** — agent constructs a ProductV:

    EmbedRequest = { model: String, text: String, dimensions: Option<Int> }

Returns IEEE 754 float32 little-endian Bytes (`4 * dimensions` length).
Pass these Bytes through to a vector-store ForeignNode (Q-038) or
compute cosine similarity host-side.

**Credentials** flow through `Builtins.credentialProvider` (default
reads `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `GEMINI_API_KEY` /
`GOOGLE_API_KEY`). They are NOT part of the capability lattice — a
capability authorizes a category, the credential authorizes the
actual host call.

**Layer A emission example.** A minimal Anthropic generation with one
tool. The tool's parameterSchema declares its input shape structurally:

    intT PRM Int
    strT PRM String
    bytesT PRM Bytes
    llmGenFx EFC "LLM.Generate" [strT strT]
    -- declare the unified GenerateRequest product (model + messages + tools fields)
    modelFieldT PRF "model" strT
    messagesFldT PRF "messages" bytesT
    toolsFldT PRF "tools" bytesT
    reqT PRD [modelFieldT messagesFldT toolsFldT]
    -- declare the tool's input shape (city: String) as a Schema, plus
    -- the implementation Lambda and the ToolDef node
    cityFldT PRF "city" strT
    cityT PRD [cityFldT]
    lookupSchema SCH "lookup-input" cityT []
    lookupParam PRC "params" cityT
    lookupBody STR "sunny, 72F"
    lookupImpl LAM [lookupParam] lookupBody
    lookupTool TLD "lookup" "Look up the weather" lookupSchema lookupImpl
    -- ForeignNode wired through the prelude:
    -- "anthropicGenerate" reaches the registered builtin under llmGenerateFx
    providerLit STR "anthropic"
    modelLit STR "claude-opus-4-7"
    callDecl EFD llmGenFx [providerLit modelLit]
    -- build the GenerateRequest ProductV at the call site
    -- (real corpus programs use messagesV / toolsV from earlier nodes
    -- and reference lookupTool in the tools list via a NodeRef)
    callApp APP anthropicGenerate [requestV] _ [callDecl]

**Provider scoping example.** A graph that holds only
`LLM.Generate{provider: "anthropic", model: *}` cannot dispatch through
`openaiGenerate` — the runtime raises `RefinementViolation`. A graph
that holds the wildcard `LLM.Generate{provider: *, model: *}` works
with any of the six bindings; the cost is the broader trust grant.

## Streaming I/O (`*.CreateStream` / `*.Stream.Receive` / `*.Stream.Close`)

Q-045. For consuming a result incrementally — acting on the first tokens of
an LLM response before the last arrives, or reading a socket as an
open-ended chunk sequence — use the uniform streaming-handle contract. Three
operations per streaming namespace:

1. **Open.** A streaming-source builtin opens the transport and returns a
   handle (`Int`) immediately, before the body arrives. It declares the
   *semantic* effect.
2. **Receive.** `*.Stream.Receive(handle: Int, maxBytes: Int) -> Option<Bytes>`
   performs one blocking read: `Some(chunk)` per chunk, `None` at
   end-of-stream. It declares E-004 `Network.Receive` (the transport-level
   read). EOF is `None`, distinct from a `Some(empty Bytes)` short read.
3. **Close.** `*.Stream.Close(handle: Int) -> Unit` releases the handle.
   Idempotent (a second close, or close of an unknown id, is a no-op).

LLM streaming generation:

    strand-builtin:Anthropic.Messages.CreateStream(req: GenerateRequest) -> Int
    strand-builtin:OpenAI.Chat.CompletionsStream(req: GenerateRequest)   -> Int
    strand-builtin:Gemini.GenerateContentStream(req: GenerateRequest)    -> Int
        -- each declares E-035 LLM.Generate{provider, model}, like the
        -- blocking *.Create variants. Returns an llm_stream handle.
    strand-builtin:LLM.Stream.Receive(handle: Int, maxBytes: Int) -> Option<Bytes>
        -- declares E-004 Network.Receive; one blocking read of raw bytes.
    strand-builtin:LLM.Stream.Close(handle: Int) -> Unit   (prelude: llmStreamClose)

Socket streaming reuses the existing socket handle; `Net.Close` is its close:

    strand-builtin:Net.Stream.Receive(handle: Int, maxBytes: Int) -> Option<Bytes>
        -- identical to Net.Receive except EOF is None, not empty Bytes.
        -- Prefer this over Net.Receive for new code.

A streaming-LLM program declares **both** E-035 `LLM.Generate{...}` (from
the open) and E-004 `Network.Receive` (from the drain) — an intentional,
honest split, and the capability context must grant both. A `Fixpoint`
drain loop contributes E-004 to the effect closure exactly once regardless
of chunk count (the closure is a static set). The drain *must* declare
E-004 — it is not a no-effect read; otherwise the effect closure would be an
unsound bound on network I/O.

Chunks are raw `Bytes` (one or more unparsed SSE `data:` frames). SSE
decoding is done in Strand over the raw bytes, or by accumulating all
chunks then parsing once — there is no built-in SSE/JSON decoder in this
slice. Backpressure is implicit: call Receive only when ready for more. The
per-read blocking ceiling is host policy (`--stream-receive-timeout-ms`),
never a builtin argument; a stalled read surfaces `IoFailure` with kind
`llm-stream-timeout` / `network-stream-timeout`. Drains are not replayable
(chunks are not recorded). Typical drain in Layer A (explicit FNT + FN,
since the streaming opens are excluded from the v5 signature table and
the drains are Option-returning):

    optBytesT SUM [someCase noneCase]   -- Option<Bytes>
    streamRecvT FNT [intT intT] optBytesT [netRecvFx]
    streamRecv FN "strand-builtin:LLM.Stream.Receive" streamRecvT [netRecvFx]
    -- FIX over (Int, Bytes) -> Bytes, Match Some(chunk)→recurse, None→acc;
    -- close with llmStreamClose at the end. See corpus 81.

## Vector stores (`Pinecone.*`, `Chroma.*`)

Q-038 Phase 1: per-provider ForeignNodes under two operation-shaped
effect categories, `Vector.Read` (E-037) and `Vector.Write` (E-038),
both parameterized by `provider: String` and `store: String`. The
provider parameter is pinned by which ForeignNode is called
(`Pinecone.*` → `"pinecone"`, `Chroma.*` → `"chroma"`); the store
parameter is the index / collection name from the open config.

Prelude entries are pre-bound:

    Effect categories: vectorReadFx, vectorWriteFx

    Pinecone (six builtins): pineconeOpen, pineconeClose,
        pineconeUpsert, pineconeQuery, pineconeDelete, pineconeFetch

    Chroma (six builtins): chromaOpen, chromaClose, chromaAdd,
        chromaQuery, chromaDelete, chromaGet

Open returns an opaque handle (Resource of kind `pinecone_index` or
`chroma_collection`) typed at the Strand surface as `intT`. The
handle declares BOTH `vectorReadFx` and `vectorWriteFx`: the
returned handle supports both directions. Per-operation builtins
declare only the direction they exercise.

### Open config shape (ProductV)

Pinecone:
    {indexName: String, environment: String, metric: Metric,
     dimensions: Int, host: Option<String>}

Chroma:
    {collectionName: String, serverUrl: String, metric: Metric}

Where `Metric` is a sum: `Cosine | DotProduct | Euclidean | Manhattan`
(SumV cases with no payload). Metric is per-collection (set at open
time); a non-`None` `metric` field on a per-query request is rejected
with a runtime `IoFailure` of kind `"vector-metric-fixed"`. pgvector's
per-query metric lands in Phase 2.

### UpsertItem and QueryHit shape

UpsertItem (ProductV):
    {id: String, vector: Bytes, metadata: JsonValueFull}

QueryRequest (ProductV):
    {vector: Bytes, k: Int, filter: Option<JsonValueFull>,
     includeVector: Bool, includeMetadata: Bool,
     metric: Option<Metric>}

QueryHit (ProductV):
    {id: String, score: Float, metadata: JsonValueFull,
     vector: Option<Bytes>}

Vectors are float32 little-endian Bytes (length = 4 * dimensions),
matching the encoding produced by Q-037's `LLM.Embed` builtins.

### Emission example — Pinecone upsert + query

    cfg PV (PRD [...]) [indexName="main" environment="us-east-1-aws"
                        metric=cosineMetric dimensions=384
                        host=hostNone]
    handle APP pineconeOpen [cfg]
    -- ... build items list of {id, vector, metadata} ProductVs ...
    _ APP pineconeUpsert [handle items]
    hits APP pineconeQuery [handle queryRequest]
    _ APP pineconeClose [handle]

### Emission example — Chroma read-only capability

    -- Capability scope grants only Vector.Read{provider: "chroma",
    -- store: "docs"}; a subsequent chromaAdd call would refinement-fail.
    scope CAP [readCap] body

Idempotency: upsert across both providers is replace-on-conflict
(matches Pinecone's upsert semantics and Chroma's `upsert` endpoint).
Filters are loose `JsonValueFull` values whose shape is provider-
specific — Pinecone expects a structured boolean over metadata,
Chroma expects a where-filter dict.

Errors surface as `InterpretError.IoFailure` with kind prefixes
`pinecone-*` / `chroma-*` / `vector-metric-fixed`. HTTP transport is
injectable for tests; the production default uses
`java.net.HttpURLConnection` consistent with `Http.Request`.
