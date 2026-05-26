# Turn 00 of session strand-04-option-unwrap-default

Task: `04-option-unwrap-default` | Config: `strand-layer-a-density-v4` | Model: `claude-sonnet-4-7`
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

FunctionType signatures (62):

    addT eqIntT ltT leT gtT geT     — (Int, Int) -> Int  or  (Int, Int) -> Bool
    subT mulT divT modT             — (Int, Int) -> Int
    negT                            — (Int) -> Int
    notT                            — (Bool) -> Bool
    andT orT                        — (Bool, Bool) -> Bool
    concatT                         — (String, String) -> String
    eqStrT                          — (String, String) -> Bool
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

Foreign-node builtins (68):

    add sub mul div mod neg         — Int arithmetic (mod is JVM `%`, sign-of-dividend)
    eqInt lt le gt ge               — Int comparisons returning Bool
    not and or                      — Bool combinators
    concat eqStr                    — String operations
    now                             — Time.Now (effectful; declares nowFx)
    abs sign min max mmod           — Math.* Int operations (mmod is true math modulo, always >= 0 for positive divisor)
    floor ceil round                — Math.* Float -> Int rounding
    sqrt pow ln exp sin cos tan     — Math.* Float -> Float
    toFloat toIntTrunc              — Float.FromInt / Int.FromFloatTrunc coercions
    blake3 sha256 md5               — Hash.* digests (Bytes -> Bytes, raw output, no multi-hash prefix)
    randInt randFloat randBytes     — Random.* (effectful; each declares cryptoFx for E-024 Crypto.RandomBytes)
    hexOf                           — Bytes.FormatHex (lowercase output)
    fsRead fsWrite fsAppend fsExists fsDelete   — Fs.* filesystem (effectful; readFx for Read/Exists, writeFx for Write/Append/Delete)
    netConnect netSend netRecv netClose         — Net.* sockets (effectful; netConnect→connectFx, netSend→netSendFx, netRecv→netRecvFx, netClose has no effect — closing the dual of opening)
    httpReq                                     — Http.Request → {status: Int, body: Bytes} (effectful; declares connectFx, netSendFx, netRecvFx)
    procWait                                    — Process.Wait → exit code Int (effectful; declares procWaitFx)
    sleep                                       — Time.Sleep (effectful; declares sleepFx)
    strLen subStr indexOf contains replace      — String.* core (pure)
    upper lower trim                            — String.* casing/trim (pure)
    intToStr floatToStr boolToStr               — String.FromInt / FromFloat / FromBool coercions
    bytesLen bytesSlice bytesCat fromUtf8 b64Of — Bytes.* core (pure; b64Of is FormatBase64)

Effect categories (13):

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

A state machine with input streams must declare `receiveFx` in its `effects`
list. A state machine with output streams must also declare `sendFx`.

**Builtins NOT in the prelude (require explicit FN + FNT declarations
at the use site):** the polymorphic / Option-returning / blessed-library-typed
ones — `List.*` operations (Map / Filter / Fold / Find / Any / All / Length /
Reverse / Take / Drop / Concat / Nth, all polymorphic in element type),
`Fs.List` (returns List<String>), `Process.Spawn` (takes List<String>),
`Process.EnvVar` / `String.ParseInt` / `String.ParseFloat` /
`Bytes.ParseUtf8` / `Bytes.ParseHex` / `Bytes.ParseBase64` (Option-returning),
`String.Split` / `String.Join` (polymorphic List<String>),
`Json.Parse` / `Json.Stringify` (typed against a specific JsonValue schema
— corpus 54 flat or corpus 66 JsonValueFull), `Markdown.Parse` (typed against
the corpus 61 MarkdownDocument schema). When using these, declare the FN with
the appropriate target string and an FNT for the concrete type at this call
site.

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
    strand-builtin:Http.Request(method: String, url: String, body: Bytes)
        -> {status: Int, body: Bytes}

`Http.Request` wraps URL parsing + socket + HTTP/1.1 framing. HTTPS via
the JVM's default truststore. Effect categories: declare `Network.Connect`,
`Network.Send`, `Network.Receive` (or a single `Network.*` if your
policy is broad).

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

# Task 04 — Option unwrap with default

Define an Option-like sum type and a function (or match expression)
that unwraps a `Some(n)` value, returning the inner integer, and
returns 0 for the `None` case. Apply the unwrap to `Some(42)` so the
program produces 42.

The reference implementation must:
- Define an `Option` sum type with two cases: `Some(Int)` and `None`.
- Construct the value `Some(42)`.
- Pattern-match on the value: the `Some(n)` arm returns the inner `n`;
  the `None` arm returns the default `0`.
- The program's final value is `42`.

This task exercises: sum-type declaration, constructor pattern
matching with a variable payload binder, variable extraction from a
sum payload, fallback / default arm. Maps to corpus program 25.

The Python reference uses `@dataclass(frozen=True)` cases joined by a
`Union` alias and a `match`/`case` block to dispatch. The Strand
reference uses `Pattern.kind = constructor` with a nested
`VariablePattern` for the `Some` payload.


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.