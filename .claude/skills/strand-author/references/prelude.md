# Strand implicit prelude

The following names are pre-bound — reference them in any Strand program without declaring them locally. A local declaration with the same id shadows the implicit one. Because Strand is content-addressed by structure, the local and implicit forms hash identically.

## Primitive types (6)

```
intT       — PrimitiveType Int
floatT     — PrimitiveType Float
stringT    — PrimitiveType String
boolT      — PrimitiveType Bool
unitT      — PrimitiveType Unit
bytesT     — PrimitiveType Bytes
```

## FunctionType signatures (selection)

The full set covers ~115 entries; the most commonly used:

```
addT subT mulT divT modT          — (Int, Int) -> Int
eqIntT ltT leT gtT geT            — (Int, Int) -> Bool
negT                              — (Int) -> Int
notT                              — (Bool) -> Bool
andT orT eqBoolT                  — (Bool, Bool) -> Bool
concatT eqStrT                    — String operations
eqBytesT                          — Bytes equality
nowT                              — () -> Int  (Time.Now)
sqrtT lnT expT sinT cosT tanT     — (Float) -> Float
powT                              — (Float, Float) -> Float
toFloatT toIntTruncT              — Float ↔ Int coercions
blake3T sha256T md5T              — (Bytes) -> Bytes
randIntT randFloatT randBytesT    — Random.* signatures
fsReadT fsWriteT fsAppendT        — Filesystem.* signatures
netConnectT netSendT netRecvT     — Network.* signatures
httpReqT                          — Http.RequestFromUrl signature
sleepT                            — (Int) -> Unit
strLenT subStrT indexOfT          — String.* signatures
containsT replaceT                — String predicate / transform
upperT lowerT trimT               — (String) -> String
intToStrT floatToStrT boolToStrT  — coercions to String
bytesLenT bytesSliceT bytesCatT   — Bytes.* signatures
fromUtf8T b64OfT                  — String <-> Bytes coercions
logT                              — (String) -> Unit
exitT                             — (Int) -> Unit
fAddT fSubT fMulT fDivT           — Float arithmetic
fEqT fLtT fLeT fGtT fGeT          — Float comparisons
pathJoinT pathBaseT pathDirT      — Path utilities
dtFormatIsoT                      — DateTime formatting
```

## Foreign-node builtins (selection of ~127)

```
add sub mul div mod neg           — Int arithmetic
eqInt lt le gt ge                 — Int comparisons -> Bool
not and or eqBool                 — Bool combinators
concat eqStr eqBytes              — equality predicates
now                               — Time.Now (effectful; declares nowFx)
abs sign min max mmod             — Math.* Int operations
floor ceil round                  — Math.* Float -> Int
sqrt pow ln exp sin cos tan       — Math.* Float -> Float
toFloat toIntTrunc                — Float / Int coercions
blake3 sha256 md5                 — Hash.* digests (Bytes -> Bytes)
randInt randFloat randBytes       — Random.* (effectful; declares cryptoFx)
hexOf                             — Bytes.FormatHex
fsRead fsWrite fsAppend fsExists fsDelete  — Fs.* (effectful; readFx / writeFx)
                                            Q-039: each pins its effect's `path` refinement to ArgRef(0)
netConnect netSend netRecv netClose        — Net.* sockets (effectful)
                                            Q-039: netConnect pins (host, port) to (ArgRef(0), ArgRef(1))
httpReq                                    — Http.RequestFromUrl (effectful)
procWait                                   — Process.Wait (effectful)
sleep                                      — Time.Sleep (effectful)
strLen subStr indexOf contains replace     — String.* core (pure)
upper lower trim                           — String.* casing/trim
intToStr floatToStr boolToStr              — String.FromInt / FromFloat / FromBool
bytesLen bytesSlice bytesCat fromUtf8 b64Of  — Bytes.* core
logInfo logWarn logError                   — Log.* (effectful; logFx)
hostname platform cwd                      — OS.* (effectful; osReadFx)
exit                                       — System.Exit (effectful; exitFx)
reReplace                                  — Regex.Replace (pure)
fAdd fSub fMul fDiv fNeg                   — Float arithmetic
fEq fLt fLe fGt fGe                        — Float comparisons
pathJoin pathBase pathDir pathExt pathNorm — Path operations (pure)
dtFormatIso                                — DateTime.FormatIso (Int millis -> ISO 8601)
dtYear dtMonth dtDay dtHour dtMinute dtSecond  — DateTime extractors
dtAddDays dtAddHours dtAddMinutes dtAddSeconds — DateTime arithmetic
padLeft padRight strRepeat                 — String formatting (round-5)
urlEncode                                  — Url.QueryEncode
gzip                                       — Compress.Gzip
```

## Effect categories (20)

```
receiveFx     — StateMachine.Receive (every state machine with inputs needs this)
sendFx        — StateMachine.Send (state machines with outputs need this)
spawnFx       — StateMachine.Spawn
terminateFx   — StateMachine.Terminate
nowFx         — Time.Now
writeFx       — Filesystem.Write (declared by Fs.Write/Append/Delete)
readFx        — Filesystem.Read (declared by Fs.Read/Exists)
connectFx     — Network.Connect (declared by Net.Connect, Http.Request)
netSendFx     — Network.Send (declared by Net.Send, Http.Request)
netRecvFx     — Network.Receive (declared by Net.Receive, Http.Request)
cryptoFx      — Crypto.RandomBytes (declared by every Random.* call)
procWaitFx    — Process.Wait
sleepFx       — Time.Sleep
logFx         — Log.Write (declared by every Log.* call)
osReadFx      — OS.Read (declared by every OS.* call)
exitFx        — System.Exit
llmGenerateFx — LLM.Generate(provider: String, model: String)
llmEmbedFx    — LLM.Embed(provider: String, model: String)
vectorReadFx  — Vector.Read{provider, store}
vectorWriteFx — Vector.Write{provider, store}
```

## Builtins NOT in the prelude

These exist but need explicit FN + FNT declarations at the use site (mostly because they're polymorphic, Option-returning, or typed against agent-chosen payload types):

- `List.*` operations (Map, Filter, Fold, Find, Any, All, Length, Reverse, Take, Drop, Concat, Nth, Sort, Range, Zip, Unzip, Distinct, Sum, Product, Min, Max)
- `Fs.List`, `Process.Spawn` (take/return polymorphic List)
- `Process.EnvVar`, `String.ParseInt`, `String.ParseFloat`, `Bytes.ParseUtf8`, `Bytes.ParseHex`, `Bytes.ParseBase64`, `DateTime.ParseIso`, `String.CharAt`, `Url.QueryDecode`, `Compress.Gunzip` (Option-returning)
- `String.Split`, `String.Join`, `String.Format`, `String.Lines`, `String.Chars` (polymorphic List<String>)
- `Url.Parse` (Option<ProductType>)
- `Csv.*`, `Tsv.*` (List<List<String>>)
- `Json.Parse`, `Json.Stringify`, `Markdown.Parse`, `Markdown.Stringify` (typed against a specific blessed library shape)
- `Regex.Match`, `Regex.FindAll`, `Regex.Split` (Option / List)
- `Map.*`, `Set.*` (opaque handle types)
- `Http.Listen`, `Http.Accept`, `Http.Respond`, `Http.ServerClose` (return product with recursive headers)

For these, declare:

```layer-a
mapT FNT [listT funcT] listT          -- list FIRST, fn second; pick concrete element types
mapFn FN "strand-builtin:List.Map" mapT
```

then `APP mapFn [xs doubleFn]`.

## Using prelude entries

When an agent's program needs `add`, just reference `add` — no local declaration required:

```layer-a
sum APP add [a b]
```

The verifier and elaborator resolve the reserved name to the prelude's `ForeignNode` automatically. Same for `intT`, `nowFx`, `fsWrite`, etc.
