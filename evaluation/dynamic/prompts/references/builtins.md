# Reference: builtins — registry builtin catalog

These ForeignNode targets are registered in the in-process Builtins
registry and may be invoked from any Strand program. They are NOT in the
implicit prelude (no short reserved name). Since density v5 you reach
them by writing the bare dotted name in callee position (`APP List.Map
[xs f]`) — the Elaborator instantiates the signature from the argument
types — or, where the arguments underdetermine the instantiation, by
declaring a `ForeignNode` referencing the target string and an
appropriate `FunctionType` at the use site. See "Density-v5 signature
table" at the end for coverage and exclusions.

Format libraries (`Json.*`, `Markdown.*`, `Csv.*`, `Tsv.*`) are in the
formats reference; per-provider LLM, streaming I/O, and vector stores
are in the llm-vector reference.

## Filesystem (`Fs.*`)

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

## Network sockets (`Net.*`) and HTTP (`Http.*`)

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
(prelude name `httpReq`) when you have a URL string already in hand and
don't care about fine-grained projection.

For incremental socket reads, `Net.Stream.Receive` (Option<Bytes>, EOF
as None) is documented with the streaming-handle contract in the
llm-vector reference.

## HTTP server (`Http.Listen` / `Http.Accept` / `Http.Respond` / `Http.ServerClose`)

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

## I/O sandbox (`SandboxPolicy`)

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

## Process + env (`Process.*`)

    strand-builtin:Process.Spawn(cmd: String, args: List<String>) -> ProcessHandle (Int)
    strand-builtin:Process.Wait(handle: Int) -> Int  (exit code)
    strand-builtin:Process.EnvVar(name: String) -> Option<String>

Effect categories: `Process.Spawn`, `Process.Wait` for the first two.
`EnvVar` is conventionally treated as `Process.*` too; the registry
doesn't enforce. (The density-v5 signature table types `Process.EnvVar`
under E-033 `OS.Read` — the reserved host-environment category — so the
bare-name form stays usable without an unregistered category.)

## Time (`Time.*`)

    strand-builtin:Time.Now() -> Int            — Unix-millis from the active clock
    strand-builtin:Time.Sleep(millis: Int) -> Unit

Effect category for both: `EFC "Time.Now"` (or "Time.Sleep" for Sleep
if you want the distinction). The default clock is real wall-clock
time; tests install a FixedClock so deterministic-replay scenarios
work. Don't assume Time.Now returns a specific value.

## String stdlib (pure, no declared effects required)

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

### String formatting (round-5 additions)

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

## Bytes stdlib (pure)

    strand-builtin:Bytes.Length(b) -> Int
    strand-builtin:Bytes.Slice(b, start: Int, end: Int) -> Bytes
    strand-builtin:Bytes.Concat(a, b) -> Bytes
    strand-builtin:Bytes.ParseUtf8(b) -> Option<String>  (None on invalid UTF-8)
    strand-builtin:Bytes.FromUtf8(s) -> Bytes
    strand-builtin:Bytes.FormatBase64(b) -> String
    strand-builtin:Bytes.ParseBase64(s) -> Option<Bytes>
    strand-builtin:Bytes.FormatHex(b: Bytes) -> String     -- lowercase
    strand-builtin:Bytes.ParseHex(s: String) -> Option<Bytes>  -- case-insensitive input

## Math (`Math.*`) and Int↔Float coercion

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

## Hash (`Hash.*`)

Pure. All take Bytes and return Bytes (raw digest, no multi-hash
prefix). Compose with `Bytes.FormatHex` if you need a hex string.

    strand-builtin:Hash.Blake3(b: Bytes) -> Bytes   -- 32-byte digest
    strand-builtin:Hash.Sha256(b: Bytes) -> Bytes   -- 32-byte digest
    strand-builtin:Hash.Md5(b: Bytes) -> Bytes      -- 16-byte digest

## Float arithmetic and comparisons (`Float.*`)

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

## Missing equality variants

    strand-builtin:Bool.Eq(a, b: Bool) -> Bool
    strand-builtin:Bytes.Eq(a, b: Bytes) -> Bool      -- content equality, not reference

Prelude shortcuts: `eqBool`, `eqBytes` (mirror the existing `eqInt`,
`eqStr`).

## List primitives (`List.*`)

Pure. Walk the canonical Cons/Nil SumV encoding (the same shape
`Fs.List`, `String.Split`, `Process.Spawn` args use). Polymorphic
in head type.

    strand-builtin:List.Empty() -> List<T>             -- returns Nil
    strand-builtin:List.IsEmpty(list) -> Bool
    strand-builtin:List.Length(list) -> Int
    strand-builtin:List.Reverse(list) -> list
    strand-builtin:List.Take(list, n: Int) -> list
    strand-builtin:List.Drop(list, n: Int) -> list
    strand-builtin:List.Concat(a, b) -> list
    strand-builtin:List.Nth(list, i: Int) -> Option<T>

## Higher-order List ops (`List.Map/Filter/Fold/Find/Any/All`)

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

These builtins are polymorphic and NOT in the prelude. Since density v5
the bare dotted name suffices — the element types instantiate from the
list argument, and lambda parameter annotations are pushed in:

    @v=1 root=mapped
    selfRef RS
    headInner PRF "head" intT
    tailInner PRF "tail" selfRef
    consInner PRD [headInner tailInner]
    consCase SCS "Cons" consInner
    nilCase SCS "Nil" _
    listSum SUM [consCase nilCase]
    listT RT listSum
    headOuter PRF "head" intT
    tailOuter PRF "tail" listT
    consOuter PRD [headOuter tailOuter]
    nilV SV listT "Nil" _
    five SV listT "Cons" (PV consOuter [head=5 tail=nilV])
    list SV listT "Cons" (PV consOuter [head=3 tail=five])
    double LAM [x] (APP mul [x 2])
    mapped APP List.Map [list double]

The explicit form remains available (declare `mapT FNT [listT fnT]
listT` and `mapFn FN "strand-builtin:List.Map" mapT`, with `fnT FNT
[intT] intT`); it is required where the instantiation is
underdetermined (e.g. `List.Empty`'s element type). The fn-parameter
slot of an explicit higher-order FNT must match the lambda's arity —
List.Fold's fn slot is a two-parameter `FNT [accT elemT] accT`.

## List structure ops and reducers (round-4 additions)

Polymorphic in element type (or Int-typed payload for the reducers).

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

## Random (`Random.*`)

Effectful. Declare `EFC "Crypto.RandomBytes"` (E-024). All read
from a cryptographically-secure entropy source (SecureRandom in
production; tests inject a seeded Random for reproducibility).

    strand-builtin:Random.Int(min, max: Int) -> Int   -- inclusive min, exclusive max
    strand-builtin:Random.Float() -> Float            -- uniform in [0.0, 1.0)
    strand-builtin:Random.Bytes(n: Int) -> Bytes      -- exactly n bytes

## Path operations (`Path.*`)

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

## DateTime (`DateTime.*`)

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

## Map.* (opaque persistent map)

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

### Map extensions (`Map.Map` / `Map.Merge` / `Map.Filter`)

Higher-order extensions. Same surface-type caveat (bytesT placeholder).

    strand-builtin:Map.Map(map, fn: V -> W) -> Map<K, W>
        -- Transforms each value; keys + insertion order preserved.
    strand-builtin:Map.Merge(a, b, conflict: (V, V) -> V) -> Map<K, V>
        -- Keys in only a or only b carry through; keys in both invoke
        -- `conflict(a_value, b_value)`. Result order: a's keys first,
        -- then b's new keys.
    strand-builtin:Map.Filter(map, fn: (K, V) -> Bool) -> Map<K, V>
        -- Keep entries where fn returns true; order preserved.

## Set operations (`Set.*` opaque persistent set)

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

## URL (`Url.*`)

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

## Compression (`Compress.*`)

JDK-native gzip via `java.util.zip.GZIPOutputStream` and
`GZIPInputStream`. Zstd / Unzstd are deferred — would require
adding `com.github.luben:zstd-jni` as a build dependency.

    strand-builtin:Compress.Gzip(b: Bytes) -> Bytes
    strand-builtin:Compress.Gunzip(b: Bytes) -> Option<Bytes>
        -- None on malformed gzip (truncated header / CRC mismatch / etc.)

Preludable: `gzip`. `Gunzip` is Option-returning.

## Regex (`Regex.*`)

`Regex.Replace` is preluded as `reReplace` (pure; supports `$1`/`$2`
backrefs via java.util.regex semantics). The others stay outside:

    strand-builtin:Regex.Match(pattern, input) -> Option<String>
    strand-builtin:Regex.FindAll(pattern, input) -> List<String>
    strand-builtin:Regex.Split(pattern, input) -> List<String>

## Density-v5 signature table — coverage and exclusions

The Layer A bare-dotted-name expansion is backed by an authoritative
Kotlin-side signature table (`BuiltinSignatures.kt`) expressing 83
non-prelude registry signatures parameterized over type variables, with
structural macros for the canonical `List<T>` / `Option<T>` shapes, the
precise N-048 JsonValue tower (a `JsonArray` over a real `List<JsonValue>`
and a `JsonObject` over a real entry list, the model corpus 88/89
construct), and the corpus-61 MarkdownDocument
tower; `Map<K,V>` / `Set<T>` follow the documented opaque-`bytesT`
surface convention. The table carries each builtin's effects and Q-039
projections, so the synthesized ForeignNode is byte-identical to the
hand-declared counterpart.

Registry coverage is total and pinned by test: of 218 registry targets,
129 are prelude-covered (reserved short names), 83 are table-covered
(bare dotted names work), and 6 are excluded with documented reasons:

- `Filesystem.Write`, `Network.Connect` — legacy Q-031 reference stubs,
  superseded by `Fs.Write` / `Net.Connect`.
- `Test.EffectfulNoOp` — test-only.
- `Anthropic.Messages.CreateStream`, `OpenAI.Chat.CompletionsStream`,
  `Gemini.GenerateContentStream` — the Q-045 streaming-LLM opens take
  the agent-shaped `GenerateRequest` product (the expected
  bytesT-payload exclusion class); declare an explicit FNT + FN at the
  use site (see the llm-vector reference).

Underdetermined instantiations (e.g. `List.Empty`'s element type,
`Map.Get`'s `V`, an unannotated `Map.Merge` conflict lambda) fail with
an `ElaborationGap` naming the annotation needed — the expansion never
guesses.
