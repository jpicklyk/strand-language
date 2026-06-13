# Foreign nodes and builtins

This reference covers the `FN` code (ForeignNode declaration) and the most commonly needed builtins by use case. The implicit prelude (see [prelude.md](prelude.md)) handles the common cases; this file is for builtins NOT in the prelude.

## Declaring a non-prelude foreign node

```layer-a
fnTypeT FNT [paramType] resultT [effectCategories]
fnNode FN "strand-builtin:Namespace.Operation" fnTypeT [effectCategories]
```

The `target` string is the canonical builtin identifier. Use `strand-builtin:` for the in-process registry. Effect lists on the `FNT` and `FN` are usually identical.

## Arithmetic and comparisons — use prelude

For Int + Int -> Int, Int comparisons, Bool combinators, Float arithmetic, hashing, random, math operations: use the prelude (`add`, `sub`, `mul`, `eqInt`, `gt`, `and`, `or`, `not`, `sqrt`, `blake3`, `randInt`, etc.). See [prelude.md](prelude.md).

## Filesystem — use prelude OR declare explicitly

The prelude `fsRead`, `fsWrite`, `fsAppend`, `fsExists`, `fsDelete` carry Q-039 effect projections (the `path` parameter is pinned to ArgRef(0)). Using them by reserved name gets sandboxing for free.

```layer-a
path STR "/tmp/output.log"
contents BYT ""
writeDecl EFD writeFx [path]
result APP fsWrite [path contents]
```

For non-prelude variants (e.g., you want a different effect category shape), declare explicitly:

```layer-a
writeFx EFC "Filesystem.Write" [stringT]
writeT FNT [stringT] intT [writeFx]
write FN "strand-builtin:Filesystem.Write" writeT [writeFx]
writeDecl EFD writeFx [path]
result APP write [path] [] [writeDecl]
```

## Network — use prelude OR declare explicitly

Prelude `netConnect` pins `(host, port)` to `(ArgRef(0), ArgRef(1))`. Stubbed in the eval environment to return socket handle 42; sandboxed against localhost/RFC1918/cloud-metadata IPs in production.

```layer-a
host STR "example.com"
port ILT 443
handle APP netConnect [host port]
```

## Time — use prelude

`now` returns `Int` millis. `sleep` takes Int millis and returns Unit. Both are effectful.

```layer-a
ts APP now []
delayed APP sleep [1000]
```

## Process — use prelude OR explicit

`procWait` is in the prelude. `Process.Spawn` and `Process.EnvVar` are not (they return polymorphic types). Declare explicitly:

```layer-a
argsT FNT [bytesT] bytesT   -- placeholder; real signature varies
spawnT FNT [argsT stringT] intT [procWaitFx]
spawn FN "strand-builtin:Process.Spawn" spawnT [procWaitFx]
```

## HTTP — prelude or declare

The legacy single-URL form is in the prelude as `httpReq` (declares connectFx + netSendFx + netRecvFx). For the newer seven-arg form with explicit host/port/scheme/path/method/headers/body, declare explicitly at the use site.

## LLM providers — declare explicitly

Per-provider ForeignNodes: `anthropicGenerate`, `anthropicEmbed`, `openaiGenerate`, `openaiEmbed`, `geminiGenerate`, `geminiEmbed`. Each takes a Bytes-encoded GenerateRequest/EmbedRequest product and returns a Bytes-encoded response. The model and provider names are pinned via Q-039 projection.

```layer-a
genT FNT [bytesT] bytesT [llmGenerateFx]
genFn FN "strand-builtin:Anthropic.Messages.Create" genT [llmGenerateFx]
```

See the Q-037 implementation notes for the GenerateRequest product shape (model, messages, max_tokens, system, tools, response_format).

## Vector stores — declare explicitly

Per-provider: `pineconeOpen`, `pineconeClose`, `pineconeUpsert`, `pineconeQuery`, `pineconeFetch`, `pineconeDelete`, and `chromaOpen`/`chromaClose`/`chromaAdd`/`chromaQuery`/`chromaGet`/`chromaDelete`. Each takes an opaque handle (Int) plus a request product, returns a result product.

```layer-a
indexOpenT FNT [bytesT] intT [vectorReadFx vectorWriteFx]
pineconeOpen FN "strand-builtin:Pinecone.Index.Open" indexOpenT [vectorReadFx vectorWriteFx]
```

## Strings — prelude has the most

`strLen`, `subStr`, `indexOf`, `contains`, `replace`, `upper`, `lower`, `trim`, `intToStr`, `floatToStr`, `boolToStr`, `padLeft`, `padRight`, `strRepeat`, `urlEncode` are all in the prelude. Reach for the prelude name first.

For `String.Split`, `String.Join`, `String.Format`, `String.Lines`, `String.Chars`, declare explicitly (they return polymorphic List<String>):

```layer-a
splitT FNT [stringT stringT] listOfStringT
split FN "strand-builtin:String.Split" splitT
parts APP split [input ", "]
```

## Bytes — prelude has the most

`bytesLen`, `bytesSlice`, `bytesCat`, `fromUtf8`, `b64Of`, `hexOf` are in the prelude. For `Bytes.ParseUtf8`, `Bytes.ParseHex`, `Bytes.ParseBase64` (Option-returning), declare explicitly.

## JSON — declare explicitly

`Json.Parse` and `Json.Stringify` are typed against the precise N-048 JsonValue: a `JsonArray` over a real `List<JsonValue>` and a `JsonObject` over a real entry list (the model corpus 88/89 construct). The bare-dotted-name form (`Json.Parse`) synthesizes this tower automatically; declare it explicitly only if you want to inspect or constrain the shape. (A primitives-only program can use corpus 54's flat JsonValue.)

```layer-a
parseT FNT [stringT] (RT jsonValueT)
parse FN "strand-builtin:Json.Parse" parseT
result APP parse [input]
```

## Collections — declare explicitly

`List.*` higher-order operations (Map, Filter, Fold, Find, Any, All) take Lambda arguments. Pick a concrete element type:

```layer-a
mapFnT FNT [intT] intT      -- the per-element callback type
mapT FNT [listOfIntT mapFnT] listOfIntT    -- list FIRST, fn second
listMap FN "strand-builtin:List.Map" mapT
doubled APP listMap [xs doubleFn]
```

`Map.*` and `Set.*` use opaque-handle surface types (typically `bytesT` placeholder) since Strand has no parametric Map<K,V> primitive yet.

## Logging and OS — use prelude

`logInfo`, `logWarn`, `logError` (effectful, `logFx`); `hostname`, `platform`, `cwd` (effectful, `osReadFx`); `exit` (effectful, `exitFx`). All in the prelude.

## Compression / regex / hashing — prelude

`gzip`, `reReplace`, `blake3`, `sha256`, `md5` are all in the prelude.

For `Compress.Gunzip` (Option<Bytes>), `Regex.Match` (Option<String>), `Regex.FindAll` (List<String>), `Regex.Split` (List<String>), declare explicitly.

## Pattern: handling Option<T> returns

`String.ParseInt`, `Bytes.ParseUtf8`, etc. return `Option<T>` (a SumType with `Some(payload)` and `None` cases). Standard pattern:

```layer-a
parseT FNT [stringT] optIntT
parseInt FN "strand-builtin:String.ParseInt" parseT
result APP parseInt ["42"]
unwrapped WHEN result optIntT "Some(n) -> n | None -> 0"
```

Where `optIntT` is a locally-declared `μ-free` SumType: `someCase SCS "Some" intT`, `noneCase SCS "None" _`, `optIntT SUM [someCase noneCase]`.

## Tool dispatch (LLM tools)

`ToolDef` (N-044, code `TLD`) wraps a Schema-backed parameter type with a callable implementation. Pass a list of ToolDefs to an LLM Generate call's `tools` field; the model emits tool calls whose arguments are validated against the Schema before dispatch.

```layer-a
paramSchema SCH "LookupParams" lookupParamsT [/* invariants */]
implLam LAM [args:lookupParamsT] (lookup_result_expr)
lookupTool TLD "Lookup" "Look up data by id" paramSchema implLam
```

See [the Q-037/N-044 system prompt section] for the full schema, including how tool calls dispatch back into the LLM loop with bounded iteration.
