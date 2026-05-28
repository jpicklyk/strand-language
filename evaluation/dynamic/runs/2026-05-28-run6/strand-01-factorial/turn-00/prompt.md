# Turn 00 of session strand-01-factorial

Task: `01-factorial` | Config: `strand-layer-a-density-v4` | Model: `claude-sonnet-4-7`
Attempt: 1 / 5

---

## SYSTEM

# Strand Layer A reference

You are emitting Strand programs in Layer A authoring format. Strand is a
content-addressed graph-based programming language designed for AI agents to
generate, not for humans to author. Programs are typed node graphs with
mandatory effect declarations; Layer A is the compact line-oriented text
projection that compiles to canonical dag-json. The verifier ingests the
dag-json, type-checks it, and reports structured errors back to you for
revision.

This prompt teaches the full grammar including the density-v4 sugars (IF,
WHEN, compact LAM params, inline literals, auto-VarRef on PRC binders,
anonymous ids with @last, inline FIELD_LIST, nested expressions). The
density sugars are recommended — they reduce per-emission token count by
roughly 4x relative to canonical dag-json without changing what the
verifier accepts.

## Grammar

A Layer A program is a sequence of lines. Whitespace separates tokens; one
node per line; references resolve by author id within the document.

The first non-comment, non-blank line MUST be the document header:

    @v=1 root=<author-id>

Every subsequent non-blank, non-comment line declares one node:

    <author-id> <CODE> <arg>...

`<author-id>` is an alphanumeric+underscore identifier unique within the
document. The special id `_` denotes an anonymous node whose body is
inaccessible by id (use `@last` to refer to the immediately preceding line).
The special token `@last` refers to whichever node was declared most
recently — handy for one-shot intermediates.

`<CODE>` is a 1-3 letter uppercase mnemonic chosen from the codes table
below. Arguments are positional, per the code's schema.

Lists use square brackets: `[a b c]` is a three-element reference list;
`[]` is empty.

Strings are double-quoted with `\"`, `\\`, `\n`, `\t` escapes.

Integers: `42`, `-3`, `0`. Floats must contain a dot: `3.14`, `-0.5`, `1.0`.
Booleans: `true` or `false`. Null / absent reference: `_` (single underscore).

Comments: any line whose first non-whitespace character is `#`.

## Codes

Each code is listed below with its `jsonType`, required and optional
positional arguments, and a tiny example.

### Literals (all produce values)

- `ILT value:Int` — IntLit. `n1 ILT 42`
- `FLT value:Float` — FloatLit. `f1 FLT 3.14`
- `STR value:String` — StringLit. `s1 STR "hello"`
- `BLT value:Bool` — BoolLit. `b1 BLT true`
- `ULT` — UnitLit (no args). `u1 ULT`
- `BYT value:String` — BytesLit (base64). `bs1 BYT "aGVsbG8="`

### Types

- `PRM kind:Keyword` — PrimitiveType. `intT PRM Int` (kinds: Int, Float, String, Bool, Unit, Bytes).
- `PRD fields:[refs]` — ProductType. `pT PRD [fa fb]`
- `PRF name:String fieldType:ref` — ProductTypeField. `fa PRF "x" intT`
- `SUM cases:[refs]` — SumType. `sT SUM [ca cb]`
- `SCS name:String caseType:nullable-ref` — SumTypeCase. `ca SCS "Some" intT` or `cb SCS "None" _`
- `FNT parameters:[refs] result:ref [effects:[refs]]` — FunctionType. `fT FNT [intT intT] intT`
- `TPM name:String [bound:ref]` — TypeParameter. `tp1 TPM "A"`

### Functions and binding

- `LAM parameters:PARAM_LIST body:ref [effects:[refs]]` — Lambda (produces value). Parameters accept either bare PRC references or compact `name:typeRef` entries. `bodyL LAM [x:intT y:intT] expr`
- `PRC name:String [paramType:ref]` — ParameterDecl. `x PRC "x" intT`. With compact LAM params the standalone PRC is unnecessary.
- `APP function:ref arguments:[refs] [typeArguments:[refs]] [effectInstances:[refs]]` — Application (produces value). `r APP add [a b]`
- `LET name:String value:ref body:ref` — Let (produces value). `e LET "tmp" v inner`
- `VAR binder:ref` — VarRef (produces value). `v1 VAR x`. With auto-VarRef sugar a bare PRC name in an expression slot lowers to a VarRef automatically.

### References

- `NRF target:ref` — NodeRef (produces value). `n1 NRF closed_subgraph`

### Type abstraction

- `TAB typeParameters:[refs] body:ref` — TypeAbstraction (produces value). `pT TAB [tp1] body`
- `FAL typeParameters:[refs] body:ref` — ForallType. `fT FAL [tp1] inner`

### Effects and capabilities

- `EFC categoryName:String [parameters:[refs]]` — EffectCategory. `recvEf EFC "StateMachine.Receive"`. Common categories are pre-bound in the implicit prelude.
- `EFD effectType:ref parameters:[refs]` — EffectDecl. `ed1 EFD writeEf [path]`
- `CAP capabilities:[refs] body:ref` — CapabilityScope (produces value). `scope CAP [ed1] inner`

### Foreign function interface

- `FN target:String foreignType:ref [effects:[refs]]` — ForeignNode (produces value). `myAdd FN "strand-builtin:Int.Add" addT`. Most common builtins are pre-bound in the implicit prelude.

ForeignNodes (and FunctionType nodes for symmetry) also accept an optional
`effectProjections` field — see § Foreign effect projections (Q-039) below
for the canonical dag-json shape. Layer A does not yet have a code for the
inline projection objects; programs that need explicit projections on
non-prelude ForeignNodes emit canonical dag-json directly. The implicit
prelude entries for `Fs.*` and `Net.Connect` carry their projections
automatically — agents using `fsWrite`, `netConnect`, etc. by reserved
name get the security property for free.

### Control flow

- `MAT scrutinee:ref cases:[refs]` — Match (produces value). `m MAT v [c1 c2]`
- `MC pattern:ref body:ref` — MatchCase. `c1 MC pat body`
- `IF scrutinee:ref then:ref else:ref` — Match-on-Bool sugar (produces value). Expands to a Match + two Pattern + two MatchCase + two BoolLit. `r IF cond v_true v_false`
- `WHEN scrutinee:ref sumType:ref cases:String` — pattern-match-on-sum sugar (produces value). Cases string format: `Case1 -> body | Case2(binder) -> body | ...`. `r WHEN x optT "Some(n) -> n | None -> 0"`
- `PLT patternType:ref literal:ref` — Pattern (literal kind). `p1 PLT intT lit42`
- `PVR patternType:ref name:String` — Pattern (variable kind, binds a name). `p1 PVR intT "n"`
- `PWC patternType:ref` — Pattern (wildcard kind). `p1 PWC intT`
- `PCN patternType:ref caseName:String [payloadPattern:nullable-ref]` — Pattern (constructor kind). `p1 PCN optT "Some" p2`

### Fixpoint and composite values

- `FIX recursionType:ref body:ref` — Fixpoint (produces value). `fact FIX factT bodyLam`. The body Lambda's FIRST parameter is the recursive call slot; remaining parameters are the user-facing ones.
- `PV ofType:ref fields:FIELD_LIST` — ProductValue (produces value). `pv PV resultT [state=expr outputs=expr]`. Fields accept either bare PFV references or `name=ref` entries.
- `PFV fieldName:String value:ref` — ProductFieldValue. `pf PFV "x" expr`
- `PFG target:ref fieldName:String` — ProductFieldGet (produces value). `g PFG record "x"`
- `SV ofType:ref caseName:String payload:nullable-ref` — SumValue (produces value). `v SV optT "Some" lit42` or `v SV optT "None" _`

### Recursive types

- `RT body:ref` — RecursiveType. `lT RT consSum`
- `RS` — RecursiveSelf (no args). `s RS`

### Handler

- `H intercept:ref handle:ref body:ref` — Handler (produces value). `h H writeFx noopFn protected_body`

### State machines

- `SM transitionFn:ref initialState:ref inputStreams:[refs] [outputStreams:[refs]] [effects:[refs]]` — StateMachine. `m SM tfn s0 [in] [out] [recvEf sendEf]`
- `ESE eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (external). `in ESE intT`
- `ESI eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (internal). `mid ESI intT`
- `ESO eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (output). `out ESO intT`
- `TR guard:nullable-ref body:ref` — Transition.

### Schema and Invariant

- `SCH schemaName:String valueType:ref invariants:[refs]` — Schema. `posInt SCH "PositiveInt" intT [posInv]`
- `INV invariantName:String targetSchema:ref body:ref` — Invariant. `posInv INV "positive" posInt isPosLambda`

## Foreign effect projections (Q-039)

When a `ForeignNode` declares parameterized effect categories (e.g.,
`Filesystem.Write{path}`, `Network.Connect{host, port}`,
`LLM.Generate{provider, model}`), the security model needs the
capability-check parameter values to be the same values the foreign
code actually consumes. Strand expresses this binding via an optional
`effectProjections` field on `ForeignNode` (and symmetrically on
`FunctionType`).

Each entry in `effectProjections` covers one of the function's
declared effect categories positionally — entry `i` projects
`effects[i]`. The projection lists one `ProjectionSource` per
EffectCategory parameter; the runtime synthesizes the capability-
check value from that source plus the actual evaluated argument
values.

Two source variants in V1:

- `{"kind": "ArgRef", "index": N}` — the parameter value is the
  function's positional argument at index `N`. The interpreter passes
  `argumentValues[N]` straight to the capability check, so the
  capability-check value IS the value the foreign code receives. No
  drift possible. Used for `path` on `Fs.Write` (`ArgRef(0)`), for
  `(host, port)` on `Net.Connect` (`[ArgRef(0), ArgRef(1)]`), and so
  on.
- `{"kind": "LiteralNode", "target": "<author-id>"}` — the parameter
  value is the binding-controlled literal node at `target`. Used to
  pin a `provider` slot on per-provider LLM/Vector bindings (e.g.,
  `LiteralNode("anthropicLit")` where `anthropicLit` is a
  `StringLit("anthropic")`). The agent cannot spoof a different
  provider via an authored EffectDecl — the verifier rejects any
  EffectDecl whose corresponding parameter does not canonical-hash-
  equal the pinned literal.

Canonical dag-json shape for a projected `Fs.Write`:

```
{
  "type": "ForeignNode",
  "target": "strand-builtin:Fs.Write",
  "foreignType": "writeT",
  "effects": ["writeFx"],
  "effectProjections": [
    {
      "category": "writeFx",
      "sources": [{"kind": "ArgRef", "index": 0}]
    }
  ]
}
```

`Application.effectInstances` is optional at every call of a
projected ForeignNode. When omitted, the interpreter synthesizes
capability-check values from the projection plus the evaluated
arguments. When supplied, the verifier requires the authored
EffectDecl to match the projection structurally:

- `ArgRef(j)` source → EffectDecl parameter at the same position
  must be the exact same NodeId as `Application.arguments[j]`. A
  drift attempt — fresh literal with the same value but a different
  NodeId — raises `ProjectionMismatch`. This is the load-bearing
  Q-039 verifier rule.
- `LiteralNode(t)` source → EffectDecl parameter must be a literal
  node whose canonical-form bytes equal `t`'s canonical-form bytes.

Reserved prelude entries (`fsRead`, `fsWrite`, `fsAppend`, `fsExists`,
`fsDelete`, `netConnect`) carry their projections automatically.
Programs that use these names by reserved id inherit the security
property — the agent does not need to author `effectProjections`
manually. Programs that emit ForeignNodes outside the prelude need
the explicit `effectProjections` field in canonical dag-json (Layer
A does not have a compact code for inline projection objects in
this slice).

Verifier rules at admission of a ForeignNode with projections:

- `ProjectionArityMismatch` — projection list length does not equal
  `effects.size`.
- `ProjectionCategoryMismatch` — the projection at position `i`
  declares a different `category` from `effects[i]`.
- `ProjectionSourceArityMismatch` — the projection's `sources` list
  length does not equal the EffectCategory's parameter count.
- `ProjectionArgRefOutOfRange` — an `ArgRef(i)` references an index
  outside the function's parameter range.
- `ProjectionLiteralNotConstant` — a `LiteralNode` target does not
  resolve to a literal node (IntLit/FloatLit/StringLit/BoolLit/
  UnitLit/BytesLit/ProductValue/SumValue over literals).
- `ProjectionLiteralTypeMismatch` — a `LiteralNode` target's type
  does not structurally equal the EffectCategory parameter type at
  the same position.

Verifier rule at every Application of a projected ForeignNode with
non-empty `effectInstances`:

- `ProjectionMismatch` — an EffectDecl parameter does not match the
  projection's source at the same position.

Migrated bindings (initial Q-039 slice): `Fs.Read`, `Fs.Write`,
`Fs.Append`, `Fs.Exists`, `Fs.Delete`, `Net.Connect`. The
per-provider LLM and Vector bindings, `Http.Request`, `Process.Spawn`,
and `Crypto.Sign/Encrypt/Decrypt` are deferred to follow-up slices —
their signatures need redesign (Http.Request via Q-041) or
EffectCategory parameter changes (Crypto.RandomBytes) that exceed
the scope of the initial security-restoration slice. ForeignNodes
without `effectProjections` continue under legacy Q-031 semantics;
the security gap on those bindings persists until migration
completes.

## Implicit prelude

The following names are pre-bound — you may reference them in any node
without declaring them locally. A local declaration with the same id
shadows the implicit one. Because Strand is content-addressed by structure,
the local and implicit forms hash identically.

Primitive types (6):

    intT       — PrimitiveType Int
    floatT     — PrimitiveType Float
    stringT    — PrimitiveType String
    boolT      — PrimitiveType Bool
    unitT      — PrimitiveType Unit
    bytesT     — PrimitiveType Bytes

FunctionType signatures (115):

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
    httpReqT                        — (String, String, Bytes) -> httpRespT
    httpRespT                       — ProductType {status: Int, body: Bytes}
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
    llmGenerateT                    — (Bytes) -> Bytes  (opaque GenerateRequest placeholder — see "Per-provider LLM" below)
    llmEmbedT                       — (Bytes) -> Bytes  (opaque EmbedRequest placeholder)
    pineconeOpenT chromaOpenT       — (Bytes) -> Int  (config ProductV -> opaque handle Int; see Vector stores section for the ProductV shapes)
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

Foreign-node builtins (127):

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
    httpReq                                     — Http.RequestFromUrl → {status: Int, body: Bytes} (effectful; declares connectFx, netSendFx, netRecvFx). Q-041 legacy single-URL wrapper; the new seven-arg Http.Request signature stays out of the prelude (its response shape includes a recursive header list that the implicit prelude can't express). Construct the seven-arg form via explicit FNT + FRN at the use site.
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
    anthropicGenerate anthropicEmbed            — Anthropic.Messages.Create / Anthropic.Embeddings.Create (effectful; each declares llmGenerateFx / llmEmbedFx with provider="anthropic" — see "Per-provider LLM" below). anthropicEmbed surfaces an IoFailure ("anthropic-embed-not-supported") — Anthropic recommends Voyage AI for embeddings.
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
    writeFx       — Filesystem.Write (declared by Fs.Write/Append/Delete)
    connectFx     — Network.Connect (declared by Net.Connect and Http.Request)
    cryptoFx      — Crypto.RandomBytes (declared by every Random.* call)
    readFx        — Filesystem.Read (declared by Fs.Read/Exists)
    netSendFx     — Network.Send (declared by Net.Send and Http.Request); distinct from sendFx (StateMachine.Send)
    netRecvFx     — Network.Receive (declared by Net.Receive and Http.Request); distinct from receiveFx
    procWaitFx    — Process.Wait (declared by procWait)
    sleepFx       — Time.Sleep (declared by sleep)
    logFx         — Log.Write (declared by every Log.* call)
    osReadFx      — OS.Read (declared by every OS.* call)
    exitFx        — System.Exit (declared by exit)
    llmGenerateFx — LLM.Generate(provider: String, model: String) — declared by Anthropic/OpenAI/Gemini Generate ForeignNodes
    llmEmbedFx    — LLM.Embed(provider: String, model: String) — declared by Anthropic/OpenAI/Gemini Embed ForeignNodes
    vectorReadFx  — Vector.Read{provider, store} (E-037; declared by all Pinecone/Chroma read ops — see Vector stores section)
    vectorWriteFx — Vector.Write{provider, store} (E-038; declared by all Pinecone/Chroma write ops)

A state machine with input streams must declare `receiveFx` in its `effects`
list. A state machine with output streams must also declare `sendFx`.

**Builtins NOT in the prelude (require explicit FN + FNT declarations
at the use site):** the polymorphic / Option-returning / blessed-library-typed
ones — `List.*` operations (Map / Filter / Fold / Find / Any / All / Length /
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
`Json.Parse` / `Json.Stringify` (typed against a specific JsonValue schema
— corpus 54 flat or corpus 66 JsonValueFull), `Markdown.Parse` /
`Markdown.Stringify` (typed against the corpus 61 MarkdownDocument schema),
`Regex.Match` (Option<String>) /
`Regex.FindAll` (List<String>) / `Regex.Split` (List<String>),
`Map.Empty` / `Map.Get` / `Map.Put` / `Map.Remove` / `Map.Has` / `Map.Size` /
`Map.Keys` / `Map.Values` / `Map.Entries` / `Map.Fold` / `Map.Map` /
`Map.Merge` / `Map.Filter` (opaque-handle Map<K,V> — see the Map.* block
below for the surface-type pattern),
`Set.Empty` / `Set.Add` / `Set.Remove` / `Set.Has` / `Set.Size` /
`Set.Union` / `Set.Intersect` / `Set.Difference` / `Set.ToList` /
`Set.FromList` / `Set.Fold` (opaque-handle Set<T>, mirror Map.* surface
pattern). When using these, declare the FN with the appropriate target
string and an FNT for the concrete type at this call site.

### HTTP server (`Http.Listen` / `Http.Accept` / `Http.Respond` / `Http.ServerClose`)

Synchronous accept/respond model (mirrors `Net.*` sync sockets).
Backed by the JDK's `com.sun.net.httpserver.HttpServer`; the
handler thread enqueues each request and blocks on a latch until
the Strand-side calls `Http.Respond`.

    strand-builtin:Http.Listen(port: Int) -> serverHandle (Int)
        -- declares E-002 Network.Listen
    strand-builtin:Http.Accept(server: serverHandle)
        -> {method: String, path: String, body: Bytes, responder: Int}
        -- declares E-004 Network.Receive; blocks until a request arrives
    strand-builtin:Http.Respond(responder: Int, status: Int, body: Bytes) -> Unit
        -- declares E-003 Network.Send; releases the handler thread.
        -- One-shot per responder; the responder handle is freed after.
    strand-builtin:Http.ServerClose(server: serverHandle) -> Unit
        -- idempotent; tears down the server and frees the port.

Headers and query-string parsing aren't exposed in this initial
slice — the request `path` includes the query string verbatim, and
the agent does its own parsing (e.g., `Regex.FindAll` for
`name=value` pairs). A follow-up slice can add header lists once a
prelude shape for `List<{name, value}>` is decided.

Typical server loop in Layer A:

    listenT FNT [intT] intT
    httpListen FN "strand-builtin:Http.Listen" listenT [listenFx]
    server APP httpListen [8080]
    -- ... FIX loop calling Accept / Respond / recurse ...

NOT in the prelude: HTTP server builtins return product types that
overlap with the existing prelude `httpRespT`. A follow-up slice
could prelude-encode `httpRequestT` for the Accept result; until
then declare it explicitly at the use site.

### Map.* (opaque persistent map)

`Map<K, V>` is an opaque persistent value backed by an immutable
hash trie. O(log n) reads and writes; path-copy persistence (writes
return a new Map without mutating the input).

**Surface-type pattern.** Strand has no parametric `Map<K, V>`
primitive type today. Agents declare Map values using `bytesT` as
the placeholder surface type (mirrors the opaque-handle pattern
that sockets / processes use). The runtime checks the actual
Value.MapV at dispatch; passing a non-Map value of type Bytes will
throw at the call site, not at verify time.

    -- declaring a Map.Get use site:
    mapGetT FNT [bytesT stringT] optionStringT
    mapGet FN "strand-builtin:Map.Get" mapGetT
    result APP mapGet [someMap someKey]

The full set:

    strand-builtin:Map.Empty()                        -> Map<K,V>
    strand-builtin:Map.Get(map, key)                  -> Option<V>
    strand-builtin:Map.Put(map, key, value)           -> Map<K,V>
    strand-builtin:Map.Remove(map, key)               -> Map<K,V>
    strand-builtin:Map.Has(map, key)                  -> Bool
    strand-builtin:Map.Size(map)                      -> Int
    strand-builtin:Map.Keys(map)                      -> List<K>
    strand-builtin:Map.Values(map)                    -> List<V>
    strand-builtin:Map.Entries(map)                   -> List<{key, value}>
    strand-builtin:Map.Fold(map, init, fn)            -> acc
        -- fn: (acc, key, value) -> acc; iterated in insertion order

Key ordering for Keys / Values / Entries / Fold is insertion order
(deterministic for replay). Two maps with the same key/value pairs
are structurally equal (Value-equality walks the structure).

Persist a Map across runs via Map.Entries → serialize the
List<{key, value}> → reconstruct via fold of Map.Put. Maps
themselves never enter the canonical store (runtime-only, like
Closure / Resource).

### Per-provider LLM (Q-037 Phase 1)

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

The Generate builtin runs the tool-use loop internally (proposal § 3.8 /
§ 6): on each iteration, if the model emits a `ToolUse` block, the loop
parses the input JSON to a value of `parameterSchema.valueType`, invokes
the named tool's `implementation` callable with that value, appends a
`ToolResult` message, and re-calls the provider. Bounded at 10
iterations by default; a `ToolUseLimit` stop reason indicates the cap
fired.

    GenerateResult = {
      content: List<Block>,                       -- final assistant blocks
      stopReason: EndTurn | MaxTokens | StopSequence | ToolUseLimit,
      usage: {inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens},
      finalMessages: List<Message>                -- conversation incl. tool turns
    }

**Tool parameter schemas** (proposal § 3.8.1) must use the irreducible
JSON-Schema-expressible TypeExpr subset: Primitives, Products (all
fields required), Sums (tag discriminator or `Option<T>`-as-nullable),
Recursives (`$defs`/`$ref`). `FunctionType`, `ForallType`, and unbound
type parameters are rejected — the verifier statically rejects any
`TLD` whose `parameterSchema`'s valueType contains one of those
variants, raising `ToolParamTypeUnsupported`. The check fires on every
ToolDef at admission, not just at provider call time.

**Response schemas** (proposal § 3.7) are first-class graph nodes
(N-045 ResponseSchemaSpec). Each response schema wrapper is a `RSC`
node with one edge:

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

## Layer 4 step 2 builtins (real IO + stdlib)

These ForeignNode targets are registered in the in-process Builtins
registry and may be invoked from any Strand program. They are NOT in
the implicit prelude (no short reserved name); declare a `ForeignNode`
referencing the target string and an appropriate `FunctionType`.

### Filesystem (`Fs.*`)

Declare effect category `EFC "Filesystem.Write"` for write/append/delete
and `EFC "Filesystem.Read"` for read/exists/list. The granted
CapabilitySet covers the call site (refinement-lattice match).

    strand-builtin:Fs.Read(path: String) -> Bytes
    strand-builtin:Fs.Write(path: String, bytes: Bytes) -> Int  (bytes written)
    strand-builtin:Fs.Append(path: String, bytes: Bytes) -> Int (bytes written)
    strand-builtin:Fs.Exists(path: String) -> Bool
    strand-builtin:Fs.Delete(path: String) -> Bool  (true if deleted, false if absent)
    strand-builtin:Fs.List(dir: String) -> List<String>  (alphabetically sorted)

Errors (missing file, permission denied, etc.) throw a runtime
`InterpretError.IoFailure(at, kind, detail)` carrying the call-site
NodeId — verifier-level errors don't apply here, the agent sees the
exception at runtime.

### Network sockets (`Net.*`) and HTTP (`Http.*`)

Sync JVM sockets. Async actor-loop integration is a follow-up; for now
`Net.Receive` blocks the calling thread.

    strand-builtin:Net.Connect(host: String, port: Int) -> SocketHandle  (declared as Int)
    strand-builtin:Net.Send(handle: Int, bytes: Bytes) -> Int  (bytes written)
    strand-builtin:Net.Receive(handle: Int, maxBytes: Int) -> Bytes  (empty on EOF)
    strand-builtin:Net.Close(handle: Int) -> Unit  (idempotent)
    strand-builtin:Http.Request(
        host: String, port: Int, scheme: String, path: String,
        method: String, headers: List<{name: String, value: String}>,
        body: Bytes,
    ) -> {status: Int, body: Bytes, headers: List<{name: String, value: String}>}
    strand-builtin:Http.RequestFromUrl(method: String, url: String, body: Bytes)
        -> {status: Int, body: Bytes}

`Http.Request` is the canonical seven-arg signature — the
`(host, port)` arguments are positional so Q-039's projection vocabulary
can bind capability-check parameters to them via `ArgRef(0)` /
`ArgRef(1)`. The scheme is validated to be `http` or `https`; other
schemes (e.g., `file://`) raise `SandboxViolation(HttpSchemeRejected)`.
Effect categories: declare `Network.Connect`, `Network.Send`,
`Network.Receive` (or a single `Network.*` if your policy is broad).

`Http.RequestFromUrl` is a legacy single-URL convenience wrapper that
parses host-side and dispatches to the seven-arg form, so the sandbox
runs uniformly. Returns the pre-Q-041 shape `{status, body}` (no
headers). Use the seven-arg form for new code so capability-check
values bind to actual function arguments; use `Http.RequestFromUrl`
when you have a URL string already in hand and don't care about
fine-grained projection.

### I/O sandbox (`SandboxPolicy`)

Every `Fs.*`, `Net.Connect`, and `Http.Request` call passes through a
host-configured `SandboxPolicy(fs: FsPolicy, net: NetPolicy)` at the
foreign-call boundary. Default (CLI invocations): workspace-rooted
filesystem (current working directory; escape via `..`, absolute
paths outside, or symlinks raises `SandboxViolation(FsPathEscape |
FsSymlinkRejected)`); network default-deny on loopback, RFC1918,
link-local, multicast, broadcast, IPv6 ULA, and cloud-metadata IPs
(`169.254.169.254`, `metadata.google.internal`, etc.); DNS
`PinAtCheck` so the second resolution cannot subvert the first.
Violations raise `InterpretError.SandboxViolation(at, kind, detail)`
distinct from `IoFailure` (host OS error), `CapabilityViolation`
(category absent), and `RefinementViolation` (category present but
pattern doesn't cover).

The sandbox is **runtime policy, not a graph property** — the verifier
does not see it, the canonical encoding does not record it. Two
evaluations of the same canonical graph under different policies
produce different results.

CLI relaxation flags (default-deny otherwise):

    --workspace-root <path>      directory below which Fs.* paths must lie
    --allow-fs-escape            permit Fs.* paths outside workspace root
    --allow-host <glob>          add host glob to network allowlist (repeatable)
    --allow-net-internal         disable network default-deny + blocked-range list

### Process + env (`Process.*`)

    strand-builtin:Process.Spawn(cmd: String, args: List<String>) -> ProcessHandle (Int)
    strand-builtin:Process.Wait(handle: Int) -> Int  (exit code)
    strand-builtin:Process.EnvVar(name: String) -> Option<String>

Effect categories: `Process.Spawn`, `Process.Wait` for the first two.
`EnvVar` is conventionally treated as `Process.*` too; the registry
doesn't enforce.

### Time (`Time.*`)

    strand-builtin:Time.Now() -> Int            — Unix-millis from the active clock
    strand-builtin:Time.Sleep(millis: Int) -> Unit

Effect category for both: `EFC "Time.Now"` (or "Time.Sleep" for Sleep
if you want the distinction). The default clock is real wall-clock
time; tests install a FixedClock so deterministic-replay scenarios
work. Don't assume Time.Now returns a specific value.

### String stdlib (pure, no declared effects required)

    strand-builtin:String.Length(s) -> Int
    strand-builtin:String.Substring(s, start: Int, end: Int) -> String
    strand-builtin:String.IndexOf(haystack, needle) -> Int  (-1 if not found)
    strand-builtin:String.Contains(haystack, needle) -> Bool
    strand-builtin:String.Replace(s, find, replace) -> String  (literal, not regex)
    strand-builtin:String.Split(s, sep) -> List<String>  (sep must be non-empty)
    strand-builtin:String.Join(parts: List<String>, sep) -> String
    strand-builtin:String.ToUpper(s) -> String
    strand-builtin:String.ToLower(s) -> String
    strand-builtin:String.Trim(s) -> String
    strand-builtin:String.ParseInt(s) -> Option<Int>
    strand-builtin:String.ParseFloat(s) -> Option<Float>
    strand-builtin:String.FromInt(n: Int) -> String
    strand-builtin:String.FromFloat(f: Float) -> String
    strand-builtin:String.FromBool(b: Bool) -> String  ("true" or "false")

### Bytes stdlib (pure)

    strand-builtin:Bytes.Length(b) -> Int
    strand-builtin:Bytes.Slice(b, start: Int, end: Int) -> Bytes
    strand-builtin:Bytes.Concat(a, b) -> Bytes
    strand-builtin:Bytes.ParseUtf8(b) -> Option<String>  (None on invalid UTF-8)
    strand-builtin:Bytes.FromUtf8(s) -> Bytes
    strand-builtin:Bytes.FormatBase64(b) -> String
    strand-builtin:Bytes.ParseBase64(s) -> Option<Bytes>

### Json + Markdown parsers (pure)

    strand-builtin:Json.Parse(s: String) -> Option<JsonValue>
    strand-builtin:Markdown.Parse(s: String) -> Option<MarkdownDocument>

`Json.Parse` recognizes the four primitive cases (null → JsonNull, true/
false → JsonBool, integer → JsonNumber, "string" → JsonString). Arrays
and objects parse as valid JSON but degrade to JsonNull because the
JsonValue blessed library has no recursive cases for them
(nested-μ blocker — a future RecursiveSelf protocol extension can lift
this). Use `Option<JsonValue>` for fallible parse handling.

### Canonical Option<T> pattern

All fallible builtins (`String.ParseInt`, `Bytes.ParseUtf8`,
`Process.EnvVar`, `Json.Parse`, ...) return `Option<T>` with the
canonical sum encoding:

    optionT SUM [someCase noneCase]
    someCase SCS "Some" T
    noneCase SCS "None" _

Unwrap-with-default via Match:

    val WHEN optResult optT "Some(n) -> n | None -> 0"

Or with explicit MAT for non-WHEN-sugared code, the standard
Cons-style constructor pattern with a variable-binding payload.

## Stdlib expansion round 2

Pure-utility additions on top of the Layer 4 step 2 IO surface.
None require new effect categories — `Random.*` declares the
existing `E-024 Crypto.RandomBytes`.

### Math (`Math.*`) and Int↔Float coercion

Pure (no declared effects required). Int-typed compose with the
existing arithmetic surface; Float-typed are irreducibly real;
Floor/Ceil/Round take Float and return Int.

    strand-builtin:Math.Abs(n: Int) -> Int
    strand-builtin:Math.Sign(n: Int) -> Int      -- -1, 0, or 1
    strand-builtin:Math.Min(a, b: Int) -> Int
    strand-builtin:Math.Max(a, b: Int) -> Int
    strand-builtin:Math.Mod(a, b: Int) -> Int    -- true modulo (always >= 0 for b > 0)
    strand-builtin:Math.Floor(f: Float) -> Int   -- round toward -infinity
    strand-builtin:Math.Ceil(f: Float) -> Int    -- round toward +infinity
    strand-builtin:Math.Round(f: Float) -> Int   -- half-to-even
    strand-builtin:Math.Sqrt(f: Float) -> Float
    strand-builtin:Math.Pow(base, exp: Float) -> Float
    strand-builtin:Math.Log(f: Float) -> Float   -- natural log
    strand-builtin:Math.Exp(f: Float) -> Float
    strand-builtin:Math.Sin(f: Float) -> Float
    strand-builtin:Math.Cos(f: Float) -> Float
    strand-builtin:Math.Tan(f: Float) -> Float

    strand-builtin:Float.FromInt(n: Int) -> Float
    strand-builtin:Int.FromFloatTrunc(f: Float) -> Int  -- truncate toward zero

`Math.Mod` is distinct from `Int.Mod` (the JVM `%` semantics with
sign-of-dividend); use `Math.Mod` when you want the mathematical
"always-non-negative for positive divisors" behavior.

### Hash (`Hash.*`)

Pure. All take Bytes and return Bytes (raw digest, no multi-hash
prefix). Compose with `Bytes.FormatHex` if you need a hex string.

    strand-builtin:Hash.Blake3(b: Bytes) -> Bytes   -- 32-byte digest
    strand-builtin:Hash.Sha256(b: Bytes) -> Bytes   -- 32-byte digest
    strand-builtin:Hash.Md5(b: Bytes) -> Bytes      -- 16-byte digest

### List primitives (`List.*`)

Pure. Walk the canonical Cons/Nil SumV encoding (the same shape
`Fs.List`, `String.Split`, `Process.Spawn` args use). Polymorphic
in head type. Higher-order operations (Map / Filter / Fold / Find /
Any / All) are a separate slice that needs lambda-callback infra.

    strand-builtin:List.Empty() -> List<T>             -- returns Nil
    strand-builtin:List.IsEmpty(list) -> Bool
    strand-builtin:List.Length(list) -> Int
    strand-builtin:List.Reverse(list) -> list
    strand-builtin:List.Take(list, n: Int) -> list
    strand-builtin:List.Drop(list, n: Int) -> list
    strand-builtin:List.Concat(a, b) -> list
    strand-builtin:List.Nth(list, i: Int) -> Option<T>

### Json.Stringify and Bytes hex codecs

    strand-builtin:Json.Stringify(j: JsonValue) -> String  -- handles 4 primitive cases
    strand-builtin:Bytes.FormatHex(b: Bytes) -> String     -- lowercase
    strand-builtin:Bytes.ParseHex(s: String) -> Option<Bytes>  -- case-insensitive input

`Json.Stringify` is the inverse of `Json.Parse` for primitive
cases. Until round 3 lifts the nested-μ blocker, both stay
primitive-only.

### Random (`Random.*`)

Effectful. Declare `EFC "Crypto.RandomBytes"` (E-024). All read
from a cryptographically-secure entropy source (SecureRandom in
production; tests inject a seeded Random for reproducibility).

    strand-builtin:Random.Int(min, max: Int) -> Int   -- inclusive min, exclusive max
    strand-builtin:Random.Float() -> Float            -- uniform in [0.0, 1.0)
    strand-builtin:Random.Bytes(n: Int) -> Bytes      -- exactly n bytes

### RecursiveSelf depth field

`RecursiveSelf` accepts an optional `depth: Int = 0` field. Default 0
behaves identically to the bare form — the reference resolves to the
innermost enclosing `RecursiveType`. A non-zero depth resolves to the
N-th outer binder (de Bruijn index against the recursive-binder stack).

    { "type": "RecursiveSelf", "depth": 1 }   -- the next-outer enclosing RT

**Practical caveat.** The depth field is a sound type-algebra primitive
but doesn't currently compose with value construction across nested
RecursiveTypes. An inner μ-type with a depth>0 reference is correct
only when traversed *as part of* its enclosing outer μ; a direct
construction site like `SumValue.ofType = innerType` resolves the
inner standalone and fails `UnboundRecursiveSelf`. For nested-list
shapes (JSON arrays inside JsonValue, trees, etc.) use the spliced-
variants pattern instead — see the JsonValueFull section below.

### JsonValueFull and the spliced-variants pattern

The blessed `JsonValueFull` schema (corpus 66) extends the original
flat `JsonValue` (corpus 54, four primitive cases) to handle arrays
and objects without nested μ-types. Eight cases:

    JsonValueFull = μ jv.
        JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String) |
        JsonArrayCons(head: jv, tail: jv) | JsonArrayNil |
        JsonObjectCons(key: String, value: jv, tail: jv) | JsonObjectNil

The four primitives match the corpus-54 shape. Arrays use spliced
`JsonArrayCons` / `JsonArrayNil` instead of a separate Cons/Nil μ.
Objects use `JsonObjectCons(key, value, tail) | JsonObjectNil`.

`Json.Parse` builds the spliced encoding directly: `[1,2]` becomes
`JsonArrayCons(JsonNumber(1), JsonArrayCons(JsonNumber(2), JsonArrayNil))`.
`Json.Stringify` walks the chain back to canonical JSON text. Both
round-trip cleanly for arbitrary nesting.

The four primitive cases of corpus 54 stay legal under both blessed
shapes — agents that only handle primitives can keep using the flat
`JsonValue`; agents that need arrays / objects use `JsonValueFull`.

### Higher-order List ops (`List.Map/Filter/Fold/Find/Any/All`)

These take a Strand lambda (a `LAM` or, less commonly, a `FXP`)
as the function argument. The interpreter invokes the lambda once
per element. Lambdas inherit the calling site's capability
context — any effects the lambda declares must be covered by the
context surrounding the higher-order call.

    strand-builtin:List.Map(list, fn: A -> B) -> List<B>
    strand-builtin:List.Filter(list, predicate: A -> Bool) -> List<A>
    strand-builtin:List.Fold(list, init: B, fn: (B, A) -> B) -> B
    strand-builtin:List.Find(list, predicate: A -> Bool) -> Option<A>
    strand-builtin:List.Any(list, predicate: A -> Bool) -> Bool
    strand-builtin:List.All(list, predicate: A -> Bool) -> Bool

Find / Any / All short-circuit on the first hit; Fold processes
left-to-right; Map and Filter preserve order. Empty list inputs
produce empty results (Nil) or the init value (Fold) or the
appropriate boolean (Any → false, All → true vacuously).

Typical Layer A density usage:

    val LAM x intT (Application Int.Mul (xRef intT) two)
    mapResult APP mapFn list double

The lambda's `parameters` and `effects` follow the standard LAM
shape; the FunctionType for the lambda parameter of the higher-
order builtin must match its arity.

## Stdlib expansion round 4

Mechanical additions on top of Layer 4 step 2 + Round 2. Six
families: Float arithmetic + comparisons, the missing equality
variants Bool.Eq / Bytes.Eq, polymorphic List structure ops +
Int-specialized reducers, pure Path manipulation, DateTime,
Markdown.Stringify.

### Float arithmetic and comparisons (`Float.*`)

Pure. Mirror the Int.* arithmetic surface. Strand has no implicit
numeric coercion, so use `toFloat` / `toIntTrunc` to move between
Int and Float when needed.

    strand-builtin:Float.Add(a, b: Float) -> Float
    strand-builtin:Float.Sub(a, b: Float) -> Float
    strand-builtin:Float.Mul(a, b: Float) -> Float
    strand-builtin:Float.Div(a, b: Float) -> Float    -- IEEE 754; div by zero is +/- Infinity, 0.0/0.0 is NaN
    strand-builtin:Float.Neg(a: Float) -> Float

    strand-builtin:Float.Eq(a, b: Float) -> Bool      -- IEEE 754 == (NaN != NaN)
    strand-builtin:Float.Lt(a, b: Float) -> Bool
    strand-builtin:Float.Le(a, b: Float) -> Bool
    strand-builtin:Float.Gt(a, b: Float) -> Bool
    strand-builtin:Float.Ge(a, b: Float) -> Bool

Prelude shortcuts: `fAdd`, `fSub`, `fMul`, `fDiv`, `fNeg`,
`fEq`, `fLt`, `fLe`, `fGt`, `fGe`.

### Missing equality variants

    strand-builtin:Bool.Eq(a, b: Bool) -> Bool
    strand-builtin:Bytes.Eq(a, b: Bytes) -> Bool      -- content equality, not reference

Prelude shortcuts: `eqBool`, `eqBytes` (mirror the existing `eqInt`,
`eqStr`).

### List structure ops and reducers (`List.*` round-4 additions)

Polymorphic in element type (or Int-typed payload for the reducers).
NOT in the prelude — declare explicit FN + FNT at the use site.

    strand-builtin:List.Sort(list, comparator: (A, A) -> Bool) -> List<A>
        -- Stable sort. Comparator returns true when first arg should
        -- come before second. Pass `lt` for ascending Int, `gt` for
        -- descending, etc. Higher-order (lives in higher-order registry).
    strand-builtin:List.Range(start, end: Int) -> List<Int>
        -- Inclusive start, exclusive end. Empty if start >= end.
    strand-builtin:List.Zip(a: List<A>, b: List<B>) -> List<{first: A, second: B}>
        -- Stops at shorter list's end.
    strand-builtin:List.Unzip(pairs: List<{first, second}>) -> {first: List<A>, second: List<B>}
        -- Inverse of List.Zip.
    strand-builtin:List.Distinct(list: List<A>) -> List<A>
        -- Preserves first occurrence; uses Value structural equality.
    strand-builtin:List.Sum(list: List<Int>) -> Int       -- 0 for empty
    strand-builtin:List.Product(list: List<Int>) -> Int   -- 1 for empty
    strand-builtin:List.Min(list: List<Int>) -> Option<Int>  -- None for empty
    strand-builtin:List.Max(list: List<Int>) -> Option<Int>  -- None for empty

### Path operations (`Path.*`)

Pure path-string manipulation. NO filesystem access, NO effect
category. Uses java.nio.file.Paths under the hood — separator
behavior is platform-aware (forward slash on POSIX, backslash on
Windows). Lexical-only normalization; resolving symlinks needs
`Fs.*` under capability.

    strand-builtin:Path.Join(a, b: String) -> String      -- joins with platform separator
    strand-builtin:Path.Basename(path: String) -> String  -- last component
    strand-builtin:Path.Dirname(path: String) -> String   -- parent dir, "" if none
    strand-builtin:Path.Extension(path: String) -> String -- ext without leading dot
                                                          -- "" for no ext, hidden files, trailing dot
    strand-builtin:Path.Normalize(path: String) -> String -- collapses . and .. lexically

Prelude shortcuts: `pathJoin`, `pathBase`, `pathDir`, `pathExt`, `pathNorm`.

### DateTime (`DateTime.*`)

All pure. Operates on Int millis the caller provides (typically
from `Time.Now`, but any source works). UTC throughout — local-
time and timezone handling are not in this slice.

    strand-builtin:DateTime.FormatIso(millis: Int) -> String
        -- ISO 8601 UTC with millisecond precision, e.g.,
        -- "2026-05-27T15:30:45.123Z"
    strand-builtin:DateTime.ParseIso(s: String) -> Option<Int>
        -- Some(millis) on success, None on parse failure.
        -- Accepts any ISO 8601 instant the JVM parser handles.

    strand-builtin:DateTime.Year(millis: Int) -> Int    -- full year (e.g., 2026)
    strand-builtin:DateTime.Month(millis: Int) -> Int   -- 1-12
    strand-builtin:DateTime.Day(millis: Int) -> Int     -- 1-31 (day-of-month)
    strand-builtin:DateTime.Hour(millis: Int) -> Int    -- 0-23
    strand-builtin:DateTime.Minute(millis: Int) -> Int  -- 0-59
    strand-builtin:DateTime.Second(millis: Int) -> Int  -- 0-59

    strand-builtin:DateTime.AddDays(millis, days: Int) -> Int
        -- Calendar-aware (handles month/year boundaries, leap days).
    strand-builtin:DateTime.AddHours(millis, hours: Int) -> Int
    strand-builtin:DateTime.AddMinutes(millis, minutes: Int) -> Int
    strand-builtin:DateTime.AddSeconds(millis, seconds: Int) -> Int

Prelude shortcuts: `dtFormatIso`, `dtYear`, `dtMonth`, `dtDay`,
`dtHour`, `dtMinute`, `dtSecond`, `dtAddDays`, `dtAddHours`,
`dtAddMinutes`, `dtAddSeconds`. `dtParseIso` is NOT in the prelude
(Option-returning).

### Markdown.Stringify

Inverse of `Markdown.Parse`, typed against the canonical corpus-61
MarkdownDocument shape:

    MarkdownDocument = μ. Cons({head: MarkdownBlock, tail: <self>}) | Nil
    MarkdownBlock = Heading{level: Int, text: String}
                  | Paragraph{text: String}
                  | CodeBlock{language: String, code: String}
                  | HorizontalRule

NOT in the prelude — the MarkdownDocument shape is agent-chosen
and not expressible as a single monomorphic FNT.

    strand-builtin:Markdown.Stringify(doc: MarkdownDocument) -> String

Heading level is clamped to 1-6 on output. Multiple blocks are
joined by `\n\n` (blank line). Backward compat: a Paragraph block
whose payload is a bare StringV (the shape `Markdown.Parse`
currently produces) is treated as the text directly, so
`Markdown.Parse → Markdown.Stringify` round-trips a single
paragraph verbatim.

## Stdlib expansion round 5

Six families on top of Round 4: String formatting helpers, opaque
persistent Set parallel to Map, three higher-order Map extensions,
CSV / TSV tabular parsing, URL parsing + query-string codec, and
Gzip compression. Five preludable shortcuts (`padLeft`, `padRight`,
`strRepeat`, `urlEncode`, `gzip`); the rest follow the documented
polymorphic / Option-returning / agent-typed exceptions.

### String formatting (`String.*` round-5 additions)

All pure.

    strand-builtin:String.Format(template: String, args: List<String>) -> String
        -- Positional placeholders {0}, {1}, etc. Out-of-range or
        -- non-numeric placeholders left verbatim. Same index may be
        -- used multiple times.
    strand-builtin:String.PadLeft(s: String, n: Int, pad: String) -> String
        -- Pads on the left with `pad` (must be non-empty) until length
        -- >= n. If s is already >= n chars, returns s unchanged.
    strand-builtin:String.PadRight(s: String, n: Int, pad: String) -> String
    strand-builtin:String.Repeat(s: String, n: Int) -> String
        -- n must be non-negative; n=0 yields "".
    strand-builtin:String.Lines(s: String) -> List<String>
        -- Splits on \n. A trailing newline produces a trailing empty
        -- entry. CRLF: the \r stays on the preceding line.
    strand-builtin:String.Chars(s: String) -> List<String>
        -- One single-char String per UTF-16 code unit.
    strand-builtin:String.CharAt(s: String, i: Int) -> Option<String>
        -- None for negative or out-of-range index.

Prelude shortcuts (monomorphic only): `padLeft`, `padRight`, `strRepeat`.
The others are polymorphic-list / Option-returning.

### Set operations (`Set.*` opaque persistent set)

Opaque persistent Set backed by `kotlinx.collections.immutable.PersistentSet`.
Surface-type pattern matches Map.*: agents declare Set values with
`bytesT` as the placeholder; the runtime checks `Value.SetV` at
dispatch. NOT in the prelude.

    strand-builtin:Set.Empty() -> Set<T>
    strand-builtin:Set.Add(set, val) -> Set<T>           -- idempotent
    strand-builtin:Set.Remove(set, val) -> Set<T>        -- no-op if absent
    strand-builtin:Set.Has(set, val) -> Bool
    strand-builtin:Set.Size(set) -> Int
    strand-builtin:Set.Union(a, b) -> Set<T>
    strand-builtin:Set.Intersect(a, b) -> Set<T>
    strand-builtin:Set.Difference(a, b) -> Set<T>        -- elements of a not in b
    strand-builtin:Set.ToList(set) -> List<T>            -- insertion order
    strand-builtin:Set.FromList(list) -> Set<T>          -- duplicates collapse
    strand-builtin:Set.Fold(set, init, fn) -> acc        -- fn: (acc, elem) -> acc

Two Sets with the same elements compare equal regardless of insertion
order (PersistentSet equals walks the structure). Sets never enter
the canonical store — persist via Set.ToList + Set.FromList.

### Map extensions (`Map.Map` / `Map.Merge` / `Map.Filter`)

Higher-order extensions to Round 3's Map.*. Same surface-type
caveat (bytesT placeholder).

    strand-builtin:Map.Map(map, fn: V -> W) -> Map<K, W>
        -- Transforms each value; keys + insertion order preserved.
    strand-builtin:Map.Merge(a, b, conflict: (V, V) -> V) -> Map<K, V>
        -- Keys in only a or only b carry through; keys in both invoke
        -- `conflict(a_value, b_value)`. Result order: a's keys first,
        -- then b's new keys.
    strand-builtin:Map.Filter(map, fn: (K, V) -> Bool) -> Map<K, V>
        -- Keep entries where fn returns true; order preserved.

### CSV / TSV (`Csv.*` / `Tsv.*`)

Tabular parsing and stringification. Csv.* implements RFC 4180
basic rules (comma cells, double-quote quoting, `""` as escaped
quote, CRLF + LF row separators). Tsv.* is simpler — tab cells,
no quoting (tabs and newlines inside cells are unsupported by the
TSV convention).

    strand-builtin:Csv.Parse(s: String) -> List<List<String>>
    strand-builtin:Csv.Stringify(rows: List<List<String>>) -> String
        -- Quotes any cell containing , " \r or \n; doubles embedded
        -- quotes per RFC 4180. Rows joined by CRLF.
    strand-builtin:Tsv.Parse(s: String) -> List<List<String>>
    strand-builtin:Tsv.Stringify(rows: List<List<String>>) -> String

NOT in the prelude — return shape is List<List<String>>.

### URL (`Url.*`)

URL parsing + application/x-www-form-urlencoded codec.

    strand-builtin:Url.Parse(s: String)
        -> Option<{scheme: String, host: String, port: Int,
                   path: String, query: String, fragment: String}>
        -- None if the URL has no scheme or fails URI syntax. `port`
        -- is the explicit port or -1 if absent. host/path/query/
        -- fragment default to empty string when omitted.
    strand-builtin:Url.QueryEncode(s: String) -> String
        -- Spaces become +; reserved chars become %XX.
    strand-builtin:Url.QueryDecode(s: String) -> Option<String>
        -- None on malformed percent-encoding.

Preludable: `urlEncode`. `Url.Parse` and `Url.QueryDecode` are
Option-returning / product-returning.

### Compression (`Compress.*`)

JDK-native gzip via `java.util.zip.GZIPOutputStream` and
`GZIPInputStream`. Zstd / Unzstd are deferred — would require
adding `com.github.luben:zstd-jni` as a build dependency.

    strand-builtin:Compress.Gzip(b: Bytes) -> Bytes
    strand-builtin:Compress.Gunzip(b: Bytes) -> Option<Bytes>
        -- None on malformed gzip (truncated header / CRC mismatch / etc.)

Preludable: `gzip`. `Gunzip` is Option-returning.

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

## Density sugars

These shorthand forms produce byte-identical canonical JSON to their
fully-explicit equivalents. Use them — they substantially reduce per-emission
token count.

### IF sugar — Match on Bool

    <id> IF <scrutinee> <thenBranch> <elseBranch>

Expands to a Match with two literal-pattern MatchCases over `boolT`. The
`boolT` referenced by the synthesized patterns resolves via the implicit
prelude unless you shadow it.

Example: `r IF cond v_true v_false`

### WHEN sugar — pattern-match on a sum

    <id> WHEN <scrutinee> <sumType> "Case1 -> body | Case2(binder) -> body | ..."

The cases-string is parsed at emit time. Each case is `CaseName -> body` for
nullary cases or `CaseName(binderName) -> body` for cases with payloads.
Cases are separated by ` | `. The `body` may be:

- An inline literal (Int/Float/Bool — e.g., `42`, `true`, `-1`).
- An identifier — the case's binder, a PRC binder in scope, or any
  declared node id.
- A **nested expression** `(CODE args...)` — composes recursively, so
  `Cons(p) -> (APP add [(PFG p "head") (APP recurse [(PFG p "tail")])])`
  works inline. The nested code follows the same Slice 10 rules as
  nested expressions elsewhere.

`<sumType>` may be either a SUM node id directly, or an RT-wrapped node
whose body resolves to a SUM (e.g., a recursive list type — the WHEN
parser follows up to 8 RT wrappers to find the underlying SUM and uses
its SCS cases for binder-type inference). If the parser can't resolve
the SumType to a SUM via RT-following, binders are typed as the
placeholder `unknownT` which the verifier rejects.

Example: `r WHEN someValue optT "Some(n) -> n | None -> 0"`

Example with nested body: `r WHEN xs listT "Cons(p) -> (APP add [(PFG p \"head\") 1]) | Nil -> 0"`

### Compact LAM parameters

A Lambda's `parameters` slot accepts either bare PRC references (legacy
form) or `name:typeRef` compact entries. When you write `LAM [x:intT
y:boolT] body`, the emitter synthesizes a PRC per entry — no explicit PRC
declaration is needed. Many compact-param types can ALSO be elided when the
Elaborator can infer them from context (call-site argument types, Fixpoint
recursionType, state-machine transition signatures, etc.). Bare names like
`LAM [x y] body` lower to PRCs with paramType filled in by inference.

### Auto-VarRef on PRC binders

A bare PRC name in an expression slot (Application argument, Let value, IF
or WHEN scrutinee, nested expression args, FIELD_LIST values, WHEN case
bodies, ...) automatically lowers to a VarRef binding to that PRC. So
`APP add [x y]` works without writing out a VarRef declaration when `x`/`y`
are PRC names in scope.

**Important:** "PRC name" here means the PRC node's **author id**, not its
`name:` field. If you declare `xParam PRC "x" intT` and then reference `x`
in an expression, auto-VarRef looks for a PRC with id `x` (not the
`name:` field) and will fail to resolve. Use the author id directly:
write `xParam PRC "x" intT` then `APP gt [xParam 0]`, or — preferred —
use the compact-LAM form `LAM [x:intT] (APP gt [x 0])` where the
LAM-entry name IS the author id.

PRC binders introduced by compact-LAM entries (whether typed `[x:intT]`
or bare `[x]`) are recognized as binder ids and trigger auto-VarRef.
WHEN's scrutinee and case-body positions respect the same rule.

### Inline literals at REFERENCE positions

REFERENCE, LIST_REF, and NULLABLE_REF positions accept inline literals.

    APP add [42 7]          — two IntLits inline
    SV optT "Some" 42       — IntLit payload inline
    LET "tmp" "hello" body  — StringLit value inline

### Anonymous ids and @last

An anonymous declaration uses `_` for the id slot; refer to it via `@last`:

    _ STR "intermediate"
    next APP doSomething [@last]

Useful for one-shot intermediates that need not be named.

### Inline FIELD_LIST on PV

ProductValue's `fields` slot accepts `name=ref` entries in addition to bare
PFV references:

    PV resultT [state=true outputs=emptyOutputs]

The emitter synthesizes a PFV per entry — explicit PFV declarations are not
needed unless you reuse the same PFV across multiple values.

### Nested expressions (CODE args...)

Any **value-producing** code (APP, LET, VAR, LAM, NRF, TAB, MAT, IF,
WHEN, FIX, PV, PFG, SV, FN, H, CAP) may appear in parentheses at any
REFERENCE / LIST_REF / NULLABLE_REF position. **Type-producing** codes
(PRM, PRD, SUM, FNT, TPM, FAL, RT) may also appear nested, but only in
**type-position** slots — PRF.fieldType, FNT.parameters/result,
PRC.paramType, SCS.caseType, TPM.bound, SV.ofType, PV.ofType,
SCH.valueType, FIX.recursionType, FN.foreignType. The emitter assigns
each nested form a synthetic id and inserts the declaration:

    APP mul [n (APP recurse [(APP sub [n 1])])]
    PRC "x" (PRM Int)
    SCS "Cons" (PRD [headField tailField])

The first lowers to three Applications plus the literal 1 — five
declarations in canonical form, one line in density v4. The second
inlines a PrimitiveType into a ParameterDecl's paramType slot. The
third inlines a ProductType into a SumTypeCase's caseType slot.

**RS cannot be nested.** RecursiveSelf is type-only and the synthesized
standalone `__expr<n> RS` form would lose its lexical RT binder context
at canonical-encoding time. Declare RS as a standalone node and
reference it by id from inside the lexical RT subtree.

**Recursive types REQUIRE the inner/outer ProductType split.** The
ProductType that holds the recursive field has two valid forms, and
real programs need BOTH:

    selfRef RS                                  # standalone RS
    headFieldInner PRF "head" intT
    tailFieldInner PRF "tail" selfRef           # INNER: uses RS
    consInner PRD [headFieldInner tailFieldInner]
    consCase SCS "Cons" consInner               # SCS uses INNER (inside RT walk)
    nilCase SCS "Nil" _
    listSum SUM [consCase nilCase]
    listT RT listSum                            # lexical RT wraps the SUM

    headFieldOuter PRF "head" intT
    tailFieldOuter PRF "tail" listT             # OUTER: uses listT (the RT itself)
    consOuter PRD [headFieldOuter tailFieldOuter]

    # Value construction sites use the OUTER product:
    nilV SV listT "Nil" _
    one ILT 1
    consV SV listT "Cons" (PV consOuter [head=1 tail=nilV])

Why the split: the canonical encoder requires `RecursiveSelf` to be
reachable only through a path that traverses the enclosing
`RecursiveType` first. The SumTypeCase resolves its `caseType` *during*
the RT body walk (depth>0), so the inner product's RS reference is
well-bound. ProductValue and SumValue resolve their `ofType` at
top-level (depth=0), so a top-level reference to the inner product
trips `UnboundRecursiveSelf`. The outer product uses the RT node
directly so it's safe to use at top-level construction sites.

Both products are equirecursively equal — the verifier and the
canonical encoder treat them as the same type, so the program's hash
doesn't change based on which is used where; what matters is using
each in the correct context. Corpus program 31 (recursive-list-head)
is the canonical reference.

Structural codes (PRC, MC, Pattern variants, EFC, EFD, ESE/ESI/ESO,
SCH, INV, SCS, PRF, TR) are rejected when nested — declare those as
standalone nodes.

Nested expressions combine with auto-VarRef so a bare PRC name like
`n` lowers to a VarRef on the parameter in scope. WHEN case binders
(`Cons(p) -> ...`) ARE now in scope inside nested expressions in the
case body, so `Cons(p) -> (APP add [(PFG p "head") (PFG p "tail")])`
works directly — `p` resolves to the synthesized PVR for the case.

**Compact-LAM param names must be unique across Lambdas in the same
program.** Two `LAM [xs:T1]` and `LAM [xs:T2]` declarations with
different `T1` and `T2` produce an `ArgShapeMismatch` error because
the synthesized PRC would silently alias to the later declaration.
Rename one of the params (`xs_inner` vs `xs_outer`, or similar) so
each Lambda has its own PRC.

## Worked examples

### Example 1 — factorial with Fixpoint

Recursion + IF + nested expressions. Five user-visible lines emit ~30
canonical-JSON nodes.

    @v=1 root=app
    matchBody IF (APP eqInt [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])])
    bodyLam LAM [recurse n] matchBody
    fact FIX factT bodyLam
    app APP fact [5]

The Elaborator infers: `factT` is FNT `[intT] intT` (from `fact`'s
recursionType reference and the FIX usage); `recurse` and `n` are PRCs
with paramTypes `factT` and `intT` (compact-LAM-param inference).

### Example 2 — JsonValue primitives with Schema

Sum type + Schema declaration + nested SV inside an Application. The
JsonValue sum is the type contract; the Schema wraps it with no invariants
so downstream consumers see a typed alias.

    @v=1 root=schemaClaim
    jsonNullCase SCS "JsonNull" _
    jsonBoolCase SCS "JsonBool" boolT
    jsonNumberCase SCS "JsonNumber" intT
    jsonStringCase SCS "JsonString" stringT
    jsonValueT SUM [jsonNullCase jsonBoolCase jsonNumberCase jsonStringCase]
    jsonValueSchema SCH "JsonValue" jsonValueT []
    identityOfJsonValue LAM [jv:jsonValueSchema] jv
    schemaClaim APP identityOfJsonValue [(SV jsonValueT "JsonNumber" 42)]

### Example 3 — toggle state machine

A Bool-state machine driven by `unitT` events. Per-event output is empty
(empty product). Compact-LAM params let the transition lambda elide its
parameter types; the Elaborator picks them up from the SM's transitionFn
signature.

    @v=1 root=toggleMachine
    emptyOutputsT PRD []
    stateFieldT PRF "state" boolT
    outputsFieldT PRF "outputs" emptyOutputsT
    resultT PRD [stateFieldT outputsFieldT]
    transitionFnT FNT [boolT unitT] resultT
    transitionResult PV resultT [state=(APP not [s]) outputs=(PV emptyOutputsT [])]
    transitionLambda LAM [s e] transitionResult
    inputStream ESE unitT
    toggleMachine SM transitionLambda false [inputStream] [] [receiveFx]

Note: the SM's `effects` list MUST contain `receiveFx` (because the machine
has at least one input stream). If outputs were declared, `sendFx` would
also be required.

## Errors

On a failed verify, you receive structured feedback. Compile-phase errors
look like:

    line N: <description>

Verifier errors look like:

    <ErrorClass>(at=#<nodeId>, <details>)

Common error classes you may encounter:

- `UnboundTypeParameter` — a TypeParameter is referenced from outside any
  enclosing TypeAbstraction or ForallType that lists it. Add the TPM to the
  binder's `typeParameters` list, or wrap the body in a TAB.
- `MissingProductValueFields` / `UnknownProductValueField` / `DuplicateProductValueField`
  — your ProductValue's `fields` list does not match the ProductType's
  declared field set exactly. Every field must appear once.
- `UnknownSumCase` — a SumValue or ConstructorPattern names a case the
  SumType does not declare.
- `MissingSumPayload` / `UnexpectedSumPayload` — a SumValue's payload is
  required (declared) or forbidden (not declared) by the case's caseType.
- `SumPayloadTypeMismatch` — the payload value has the wrong type for the
  case.
- `TypeArgumentArityMismatch` — an Application of a polymorphic value
  supplies the wrong number of type arguments.
- `PartialTypeInstantiation` — type-argument substitution did not reduce
  the function's type to a plain FunctionType. Supply more type arguments.
- `ParameterTypeMismatch` — a value argument's type disagrees with the
  function's parameter type. Structural equality only; no subtyping.
- `ArityMismatch` — wrong number of arguments at an Application.
- `NotAFunction` — the Application's function position has a non-function
  type.
- `UncoveredEffects` — a Lambda's body uses effects the Lambda failed to
  declare. Add the missing EffectCategory NodeIds to the Lambda's `effects`
  list.
- `StateMachineMissingImplicitEffect` — a StateMachine is missing
  `receiveFx` or `sendFx`. Add the appropriate effect category to the SM's
  `effects` list.
- `StateMachineTransitionFnShapeMismatch` — the transition function's
  type is not `(State, Event) -> (State, Outputs)` for either the
  OutputBatch product shape or the tagged-list recursive shape. Check the
  result PRD's field order: `state` first, `outputs` second.
- `OutputStreamEventTypeMismatch` — an output stream's eventType disagrees
  with the corresponding `output_i: Option<...>` slot of the transition's
  OutputBatch product.
- `SchemaInvariantViolation` — a statically-known value flowing into a
  Schema-typed position failed one of the Schema's invariants. Check the
  invariant body and the value being supplied.

Use the `at=#<nodeId>` field to locate the offending node in your program;
the node id is the position in your document's declaration order.

## Output convention

Emit ONLY the Layer A program in a fenced ```layer-a code block. No
commentary before or after. Begin with the `@v=1 root=<id>` header on the
first line of the fenced block.


## USER

# Task 01 — Factorial

Implement the factorial function for non-negative integers and apply
it to `5` so the program produces `120`.

```
factorial(0) = 1
factorial(n) = n * factorial(n - 1)    (n > 0)
```

The reference implementation must:
- Accept a non-negative integer argument.
- Return its factorial.
- Recurse via the language's standard fixpoint mechanism (no iteration).
- Match on `n == 0` for the base case.
- The program's final value is `factorial(5) = 120`. For Python, `main()`
  should `print(factorial(5))`. For Strand, the root Application is
  `APP fact [5]`.

This task exercises: function definition, recursion / fixpoint, integer
literals, Match dispatch, conditional dispatch on a primitive value.
Maps to corpus program 21.


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.