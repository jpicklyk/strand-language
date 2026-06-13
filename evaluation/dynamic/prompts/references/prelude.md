# Reference: prelude — full implicit-prelude catalog

The following names are pre-bound — you may reference them in any node
without declaring them locally. A local declaration with the same id
shadows the implicit one. Because Strand is content-addressed by structure,
the local and implicit forms hash identically.

The prelude is a content-addressed module (Q-063): every reserved node in
canonical form, with the 129 ForeignNode entries exported through an
N-046 ModuleManifest whose hash is pinned in
`corpus/prelude-manifest.json`
(`1e31a8cd03c8de0820188952ee4dadf1919c98a8057ec628f7904017aaa7fc08bd`).
Reserved names resolve through the bundled module; `strand registry
resolve <name>` dereferences any of them from a clean checkout.

Primitive types (6):

    intT       — PrimitiveType Int
    floatT     — PrimitiveType Float
    stringT    — PrimitiveType String
    boolT      — PrimitiveType Bool
    unitT      — PrimitiveType Unit
    bytesT     — PrimitiveType Bytes

FunctionType signatures (117):

    addT eqIntT ltT leT gtT geT     — (Int, Int) -> Int  or  (Int, Int) -> Bool
    subT mulT divT modT             — (Int, Int) -> Int
    negT                            — (Int) -> Int
    notT                            — (Bool) -> Bool
    andT orT eqBoolT                — (Bool, Bool) -> Bool
    concatT                         — (String, String) -> String
    eqStrT                          — (String, String) -> Bool
    eqBytesT                        — (Bytes, Bytes) -> Bool
    nowT                            — () -> Int
    absT signT                      — (Int) -> Int
    minT maxT mmodT                 — (Int, Int) -> Int
    floorT ceilT roundT             — (Float) -> Int
    sqrtT lnT expT sinT cosT tanT   — (Float) -> Float
    powT                            — (Float, Float) -> Float
    toFloatT                        — (Int) -> Float
    toIntTruncT                     — (Float) -> Int
    blake3T sha256T md5T            — (Bytes) -> Bytes
    randIntT                        — (Int, Int) -> Int
    randFloatT                      — () -> Float
    randBytesT                      — (Int) -> Bytes
    hexOfT                          — (Bytes) -> String
    fsReadT                         — (String) -> Bytes
    fsWriteT fsAppendT              — (String, Bytes) -> Int
    fsExistsT fsDeleteT             — (String) -> Bool
    netConnectT                     — (String, Int) -> Int  (socket handle)
    netSendT                        — (Int, Bytes) -> Int
    netRecvT                        — (Int, Int) -> Bytes
    netCloseT                       — (Int) -> Unit
    llmStreamCloseT                 — (Int) -> Unit  (Q-045 streaming-LLM handle release)
    httpReqT                        — (String, String, Bytes) -> httpRespT
    httpRespT                       — ProductType {status: Int, body: Bytes}
    errPayloadT                     — ProductType {kind: String, detail: String}  (the Err case of every TRY result; see the errors reference)
    procWaitT                       — (Int) -> Int
    sleepT                          — (Int) -> Unit
    strLenT                         — (String) -> Int
    subStrT                         — (String, Int, Int) -> String
    indexOfT                        — (String, String) -> Int
    containsT                       — (String, String) -> Bool
    replaceT                        — (String, String, String) -> String
    upperT lowerT trimT             — (String) -> String
    intToStrT                       — (Int) -> String
    floatToStrT                     — (Float) -> String
    boolToStrT                      — (Bool) -> String
    bytesLenT                       — (Bytes) -> Int
    bytesSliceT                     — (Bytes, Int, Int) -> Bytes
    bytesCatT                       — (Bytes, Bytes) -> Bytes
    fromUtf8T                       — (String) -> Bytes
    b64OfT                          — (Bytes) -> String
    logT                            — (String) -> Unit  (Log.Info/Warn/Error)
    hostT platT cwdT                — () -> String  (OS.Hostname/Platform/Cwd)
    exitT                           — (Int) -> Unit  (System.Exit)
    reReplaceT                      — (String, String, String) -> String  (Regex.Replace pattern, input, replacement)
    llmGenerateT                    — (Bytes) -> Bytes  (opaque GenerateRequest placeholder — see the llm-vector reference)
    llmEmbedT                       — (Bytes) -> Bytes  (opaque EmbedRequest placeholder)
    pineconeOpenT chromaOpenT       — (Bytes) -> Int  (config ProductV -> opaque handle Int; see the llm-vector reference for the ProductV shapes)
    pineconeCloseT chromaCloseT     — (Int) -> Unit
    pineconeUpsertT chromaAddT      — (Int, Bytes) -> Unit  (handle, items ProductV-list)
    pineconeDeleteT chromaDeleteT   — (Int, Bytes) -> Unit  (handle, ids ProductV-list)
    pineconeQueryT chromaQueryT     — (Int, Bytes) -> Bytes (handle, QueryRequest -> List<QueryHit>; both products carried in Bytes-placeholder slot)
    pineconeFetchT chromaGetT       — (Int, Bytes) -> Bytes (handle, ids list -> List<QueryHit>)
    fAddT fSubT fMulT fDivT         — (Float, Float) -> Float
    fNegT                           — (Float) -> Float
    fEqT fLtT fLeT fGtT fGeT        — (Float, Float) -> Bool
    pathJoinT                       — (String, String) -> String
    pathBaseT pathDirT pathExtT pathNormT  — (String) -> String
    dtFormatIsoT                    — (Int) -> String  (millis -> ISO 8601 UTC)
    dtYearT dtMonthT dtDayT dtHourT dtMinuteT dtSecondT  — (Int) -> Int  (UTC component extraction)
    dtAddDaysT dtAddHoursT dtAddMinutesT dtAddSecondsT   — (Int, Int) -> Int  (millis + n -> new millis)
    padLeftT padRightT              — (String, Int, String) -> String  (round-5; pad on left/right)
    strRepeatT                      — (String, Int) -> String  (round-5; repeat n times)
    urlEncodeT                      — (String) -> String  (round-5; application/x-www-form-urlencoded)
    gzipT                           — (Bytes) -> Bytes  (round-5; gzip compress)

Foreign-node builtins (128):

    add sub mul div mod neg         — Int arithmetic (mod is JVM `%`, sign-of-dividend)
    eqInt lt le gt ge               — Int comparisons returning Bool
    not and or                      — Bool combinators
    eqBool                          — Bool.Eq (Bool, Bool) -> Bool
    concat eqStr                    — String operations
    eqBytes                         — Bytes.Eq (Bytes, Bytes) -> Bool  (content equality)
    now                             — Time.Now (effectful; declares nowFx)
    abs sign min max mmod           — Math.* Int operations (mmod is true math modulo, always >= 0 for positive divisor)
    floor ceil round                — Math.* Float -> Int rounding
    sqrt pow ln exp sin cos tan     — Math.* Float -> Float
    toFloat toIntTrunc              — Float.FromInt / Int.FromFloatTrunc coercions
    blake3 sha256 md5               — Hash.* digests (Bytes -> Bytes, raw output, no multi-hash prefix)
    randInt randFloat randBytes     — Random.* (effectful; each declares cryptoFx for E-024 Crypto.RandomBytes)
    hexOf                           — Bytes.FormatHex (lowercase output)
    fsRead fsWrite fsAppend fsExists fsDelete   — Fs.* filesystem (effectful; readFx for Read/Exists, writeFx for Write/Append/Delete). Q-039: each pins its effect's `path` refinement parameter to ArgRef(0) — the verifier and runtime synthesize the capability-check value from the function's first argument.
    netConnect netSend netRecv netClose         — Net.* sockets (effectful; netConnect→connectFx, netSend→netSendFx, netRecv→netRecvFx, netClose has no effect — closing the dual of opening). Q-039: netConnect pins connectFx's (host, port) refinement parameters to ArgRef(0) and ArgRef(1).
    llmStreamClose                              — LLM.Stream.Close → Unit (Q-045; no effect — releases a streaming-LLM handle). The streaming open and the Option<Bytes>-returning drains stay out of the prelude — see the llm-vector reference and "NOT in the prelude" below.
    httpReq                                     — Http.RequestFromUrl → {status: Int, body: Bytes} (effectful; declares connectFx, netSendFx, netRecvFx). Q-041 legacy single-URL wrapper; the new seven-arg Http.Request signature stays out of the prelude (its response shape includes a recursive header list that the implicit prelude can't express). Reach the seven-arg form via the bare dotted name `Http.Request` (density v5) or an explicit FNT + FN at the use site.
    procWait                                    — Process.Wait → exit code Int (effectful; declares procWaitFx)
    sleep                                       — Time.Sleep (effectful; declares sleepFx)
    strLen subStr indexOf contains replace      — String.* core (pure)
    upper lower trim                            — String.* casing/trim (pure)
    intToStr floatToStr boolToStr               — String.FromInt / FromFloat / FromBool coercions
    bytesLen bytesSlice bytesCat fromUtf8 b64Of — Bytes.* core (pure; b64Of is FormatBase64)
    logInfo logWarn logError                    — Log.* (effectful; each declares logFx for E-032 Log.Write)
    hostname platform cwd                       — OS.* (effectful; each declares osReadFx for E-033 OS.Read)
    exit                                        — System.Exit(code: Int) -> Unit (effectful; declares exitFx for E-034 System.Exit; terminates the host process in production, captured in tests)
    reReplace                                   — Regex.Replace (pure; supports `$1`/`$2` backrefs to groups via java.util.regex semantics). The other Regex.* builtins (Match/FindAll/Split) stay explicit at the use site — see "NOT in the prelude" below.
    anthropicGenerate anthropicEmbed            — Anthropic.Messages.Create / Anthropic.Embeddings.Create (effectful; each declares llmGenerateFx / llmEmbedFx with provider="anthropic" — see the llm-vector reference). anthropicEmbed surfaces an IoFailure ("anthropic-embed-not-supported") — Anthropic recommends Voyage AI for embeddings.
    openaiGenerate openaiEmbed                  — OpenAI.Chat.Completions / OpenAI.Embeddings.Create (effectful; declare llmGenerateFx / llmEmbedFx with provider="openai")
    geminiGenerate geminiEmbed                  — Gemini.GenerateContent / Gemini.EmbedContent (effectful; declare llmGenerateFx / llmEmbedFx with provider="gemini")
    pineconeOpen pineconeClose                  — Q-038 Pinecone lifecycle. Open declares both vectorReadFx and vectorWriteFx; Close has no effect.
    pineconeUpsert pineconeDelete               — Pinecone writes (each declares vectorWriteFx).
    pineconeQuery pineconeFetch                 — Pinecone reads (each declares vectorReadFx).
    chromaOpen chromaClose                      — Q-038 Chroma lifecycle (Open declares both vectorReadFx and vectorWriteFx).
    chromaAdd chromaDelete                      — Chroma writes (each declares vectorWriteFx).
    chromaQuery chromaGet                       — Chroma reads (each declares vectorReadFx).
    fAdd fSub fMul fDiv fNeg                    — Float arithmetic (round-4). Div by zero yields +/- Infinity (IEEE 754); 0.0/0.0 is NaN.
    fEq fLt fLe fGt fGe                         — Float comparisons (round-4). NaN compared to anything is false (IEEE 754).
    pathJoin pathBase pathDir pathExt pathNorm  — Path.Join / Basename / Dirname / Extension / Normalize (round-4 pure path-string ops; no filesystem access, no effect)
    dtFormatIso                                 — DateTime.FormatIso (Int millis -> ISO 8601 UTC String). Pure.
    dtYear dtMonth dtDay dtHour dtMinute dtSecond  — DateTime UTC component extractors (Int millis -> Int). Pure.
    dtAddDays dtAddHours dtAddMinutes dtAddSeconds  — DateTime arithmetic (Int millis + Int n -> Int millis). Calendar-aware. Pure.
    padLeft padRight                            — String.PadLeft / String.PadRight (round-5; pure; pad must be non-empty)
    strRepeat                                   — String.Repeat (round-5; n must be non-negative)
    urlEncode                                   — Url.QueryEncode (round-5; application/x-www-form-urlencoded — spaces become +)
    gzip                                        — Compress.Gzip (round-5; pure; produces gzip-formatted Bytes)

Effect categories (20):

    receiveFx     — StateMachine.Receive (every state machine needs this)
    sendFx        — StateMachine.Send (state machines with outputs need this)
    spawnFx       — StateMachine.Spawn
    terminateFx   — StateMachine.Terminate
    nowFx         — Time.Now
    writeFx       — Filesystem.Write{path: String} (E-007; declared by Fs.Write/Append/Delete; Q-039 projection pins `path` to ArgRef(0))
    connectFx     — Network.Connect{host: String, port: Int} (E-001; declared by Net.Connect and Http.Request; Q-039 projection pins host→ArgRef(0), port→ArgRef(1))
    cryptoFx      — Crypto.RandomBytes (declared by every Random.* call)
    readFx        — Filesystem.Read{path: String} (E-006; declared by Fs.Read/Exists; Q-039 projection pins `path` to ArgRef(0))
    netSendFx     — Network.Send (declared by Net.Send and Http.Request); distinct from sendFx (StateMachine.Send)
    netRecvFx     — Network.Receive (declared by Net.Receive and Http.Request); distinct from receiveFx
    procWaitFx    — Process.Wait (declared by procWait)
    sleepFx       — Time.Sleep (declared by sleep)
    logFx         — Log.Write (declared by every Log.* call)
    osReadFx      — OS.Read (declared by every OS.* call)
    exitFx        — System.Exit (declared by exit)
    llmGenerateFx — LLM.Generate(provider: String, model: String) — declared by Anthropic/OpenAI/Gemini Generate ForeignNodes
    llmEmbedFx    — LLM.Embed(provider: String, model: String) — declared by Anthropic/OpenAI/Gemini Embed ForeignNodes
    vectorReadFx  — Vector.Read{provider, store} (E-037; declared by all Pinecone/Chroma read ops — see the llm-vector reference)
    vectorWriteFx — Vector.Write{provider, store} (E-038; declared by all Pinecone/Chroma write ops)

A state machine with input streams must declare `receiveFx` in its `effects`
list. A state machine with output streams must also declare `sendFx`.

**Builtins NOT in the prelude** (no reserved short name): the
polymorphic / Option-returning / blessed-library-typed ones —
`List.*` operations (Map / Filter / Fold / Find / Any / All / Length /
Reverse / Take / Drop / Concat / Nth / Sort / Range / Zip / Unzip / Distinct /
Sum / Product / Min / Max, all polymorphic in element type or returning Option
or taking List<Int>),
`Fs.List` (returns List<String>), `Process.Spawn` (takes List<String>),
`Process.EnvVar` / `String.ParseInt` / `String.ParseFloat` /
`Bytes.ParseUtf8` / `Bytes.ParseHex` / `Bytes.ParseBase64` /
`DateTime.ParseIso` / `String.CharAt` / `Url.QueryDecode` /
`Compress.Gunzip` (all Option-returning),
`String.Split` / `String.Join` / `String.Format` / `String.Lines` /
`String.Chars` (polymorphic List<String>),
`Url.Parse` (Option<{scheme, host, port, path, query, fragment}>),
`Csv.Parse` / `Csv.Stringify` / `Tsv.Parse` / `Tsv.Stringify`
(take or return List<List<String>>),
`Json.Parse` / `Json.Stringify` (typed against the precise N-048 JsonValue
— a JsonArray over a real List<JsonValue> and a JsonObject over a real entry
list, the model corpus 88/89 construct), `Markdown.Parse` /
`Markdown.Stringify` (typed against the corpus 61 MarkdownDocument schema),
`Regex.Match` (Option<String>) /
`Regex.FindAll` (List<String>) / `Regex.Split` (List<String>),
`Map.Empty` / `Map.Get` / `Map.Put` / `Map.Remove` / `Map.Has` / `Map.Size` /
`Map.Keys` / `Map.Values` / `Map.Entries` / `Map.Fold` / `Map.Map` /
`Map.Merge` / `Map.Filter` (opaque-handle Map<K,V> — see the builtins
reference for the surface-type pattern),
`Set.Empty` / `Set.Add` / `Set.Remove` / `Set.Has` / `Set.Size` /
`Set.Union` / `Set.Intersect` / `Set.Difference` / `Set.ToList` /
`Set.FromList` / `Set.Fold` (opaque-handle Set<T>, mirror Map.* surface
pattern),
`Anthropic.Messages.CreateStream` / `OpenAI.Chat.CompletionsStream` /
`Gemini.GenerateContentStream` (Q-045 streaming open — typed against the
agent-chosen `GenerateRequest` shape, like the blocking `*.Create`
variants) and `LLM.Stream.Receive` / `Net.Stream.Receive` (Q-045 drains,
return `Option<Bytes>`). `LLM.Stream.Close` *is* in the prelude as
`llmStreamClose`.

Since density v5, most of these are reachable without hand declarations:
write the bare dotted name in callee position (`APP List.Map [xs f]`)
and the Elaborator instantiates the registry signature from the argument
types. The explicit FN (with the appropriate target string) + FNT (for
the concrete type at the call site) remains available, and is required
where the arguments underdetermine the instantiation or where the
builtin is excluded from the signature table — see the builtins
reference for coverage and exclusions (the three streaming-LLM opens
among them).
