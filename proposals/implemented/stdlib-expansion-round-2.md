# Stdlib expansion round 2

**Document:** `proposals/implemented/stdlib-expansion-round-2.md`
**Status:** Implemented 2026-05-26 across slices 1-3 in 12 commits (commit range `393b634..28427d0`). The original slice 1 plan landed verbatim (~28 builtins); slice 2 (higher-order List ops) landed with new ApplyFn / FnH interpreter infrastructure; slice 3 (nested-Json) pivoted from the depth-field-only plan to a depth-field-plus-spliced-variants combination, as the depth field alone proved incomplete for value construction. All gradle tests green; corpus extended to 66 programs.
**Date:** 2026-05-26 (proposal and implementation)
**Concerns:** [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt`](../../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt), [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md), [`proposals/implemented/layer-4-step-2-real-io.md`](layer-4-step-2-real-io.md), [`proposals/implemented/nested-recursive-self-depth.md`](nested-recursive-self-depth.md)
**Scope:** Medium — 25 builtins in slice 1, 6 higher-order builtins + ApplyFn infra in slice 2, RecursiveSelf depth field + JsonValueFull in slice 3

> **Implementation note (2026-05-26).** All three slices landed across
> one overnight execution window. The slice 1 plan was executed verbatim
> (Math.* / Hash.* / List.* primitives / Json.Stringify / Bytes hex /
> Random.* — ~28 builtins, no design deviations).
>
> **Slice 2 deviations from plan.** Added `Builtins.ApplyFn` and
> `Builtins.FnH` (separate higher-order builtin interface) alongside the
> existing `Fn`. Builtins.lookupHigherOrder + Interpreter.applyValueToArgs
> form the dispatch path. Six higher-order List ops shipped: Map, Filter,
> Fold, Find, Any, All. Fold takes 2-arg fn (acc, elem); rest take 1-arg.
> ForeignFn callbacks work (e.g., passing Bool.Not as List.Map fn); the
> dispatcher cycles them back through Builtins.lookup.
>
> **Slice 3 pivot.** The original plan was to add a `depth: Int` field
> to RecursiveSelf and use that to express nested μ-types
> (`JsonArray(List<JsonValue>)`). The depth field shipped as planned
> (verifier + encoder + parser updates, two new VerifierTest cases
> covering depth>0 references) — but during corpus 66 development we
> discovered that nested μ-types' inner RT can't be resolved standalone:
> a direct construction site like `SumValue.ofType = arrListT` triggers
> the inner RT walk at depth 1 only, finds the depth-1 RS unbound, and
> aborts. Strand's content-addressed type semantics require every type
> to have one canonical meaning; depth-N RecursiveSelf references
> depend on traversal context, so they only work when the inner type
> is reached through its enclosing outer μ.
>
> The pragmatic fix: collapse the array / object structure into
> JsonValue's variant set directly via "spliced variants" — corpus 66
> ships JsonArrayCons / JsonArrayNil / JsonObjectCons / JsonObjectNil
> as direct cases of `JsonValueFull`'s single μ. All RecursiveSelf
> references stay depth=0; value construction works uniformly. The
> depth field itself stays in the codebase as a sound foundational
> primitive — useful when Strand adds polymorphic recursive types or
> any construct that doesn't have the value-construction issue.
>
> **Final tally.** Slice 1: 28 builtins (Math 15 + Hash 3 + List 8 +
> Json.Stringify 1 + Bytes hex 2 + Random 3, plus Float.FromInt /
> Int.FromFloatTrunc coercions — actually closer to 34). Slice 2: 6
> higher-order builtins + 2 interpreter-infra interfaces + 1
> applyValueToArgs helper. Slice 3: RecursiveSelf depth field +
> Json.Parse / Json.Stringify rewritten for spliced variants + corpus
> 66 (JsonValueFull, 8 cases). 39 total registry entries added; 11
> commits + close-out.

Layer 4 step 2 brought the stdlib from 18 to 46 builtins covering real OS-level IO. Round 2 fills the remaining "common pure utility" gaps an agent program is likely to hit immediately: math beyond `Int.Add/Sub/Mul/Div/Mod`, content hashing, list traversal without writing μ-recursion by hand, JSON serialization to balance `Json.Parse`, hex codecs to balance base64, and explicit access to randomness.

This is the *easy* round — no new node categories, no architectural changes, and the only new effect-category use is the existing `E-024 Crypto.RandomBytes`. The follow-on round (higher-order List ops + nested-Json) needs an interpreter-callback addition and a recursive-type extension respectively and ships as separate proposals.

## 1. Design calls

### 1.1 Math.* on Int vs Float

`Math.Min`, `Math.Max`, `Math.Abs`, `Math.Sign`, `Math.Mod` ship as **Int-typed** because the existing arithmetic surface (`Int.Add` etc.) is Int-only and these compose with it. `Math.Sqrt`, `Math.Pow`, `Math.Log`, `Math.Exp`, `Math.Sin`, `Math.Cos`, `Math.Tan` ship as **Float-typed** because they're irreducibly real-valued. `Math.Floor`, `Math.Ceil`, `Math.Round` take a `Float` and return an `Int` (the truncation/rounding result is integer-typed).

The Strand type system has no automatic Int↔Float coercion. Programs that want `Math.Sqrt(IntV(4))` must wrap with `String.FromInt → String.ParseFloat` (round-trip), or a future `Float.FromInt` shorthand. Adding `Float.FromInt(n: Int) → Float` and `Int.FromFloatTrunc(f: Float) → Int` as part of this slice closes the gap cleanly; both are trivial.

### 1.2 Hash.* on Bytes

All three hash functions take `Bytes` and return `Bytes`. The `Hash.Blake3` builtin reuses the project's existing `org.strand.hashing.Blake3` primitive (the same one canonical hashing uses for content addressing); `Hash.Sha256` and `Hash.Md5` use `java.security.MessageDigest`. These are pure functions — no effect category needed. Programs that want hex output compose with `Bytes.FormatHex`.

### 1.3 List.* primitives without lambdas

The recursive `Cons(head, tail) | Nil` shape is already in the corpus and produced by `Fs.List`, `String.Split`, `Process.Spawn`. The first-round List builtins handle traversal without taking lambdas — saves the agent from writing manual Fixpoint+Match recursions for the common cases. The lambda-taking ops (`Map`, `Filter`, `Fold`) require the higher-order builtin infrastructure shipping in round 3 and are not in this slice.

Builtins walk any `SumV` chain matching the canonical `"Cons"` / `"Nil"` cases with the field names `"head"` and `"tail"`. Polymorphism is shallow: the head can be any `Value`, the runtime never inspects it.

Empty-list construction: `List.Empty()` returns `SumV("Nil", null)`. This is convenient because Strand has no literal syntax for the empty list — agents currently emit a 3-node Sum-construct boilerplate. One builtin replaces the boilerplate.

`List.Nth(list, i) → Option<T>` returns `None` for out-of-range indices, matching the convention all the fallible-access builtins use.

### 1.4 Json.Stringify

The blessed `JsonValue` sum encoding (corpus 54) is the input shape. `Json.Stringify` walks it and emits the canonical JSON-text representation. The nested-μ blocker that limits `Json.Parse` to primitives applies symmetrically here — arrays and objects are unrepresentable until round 3 lifts the blocker, so `Json.Stringify(JsonArray(...))` would never have a JsonValue input to stringify. The implementation handles the four primitive cases (`JsonNull`, `JsonBool`, `JsonNumber`, `JsonString`) and falls back to emitting `"null"` for any other `SumV` case (defensive — should be unreachable until round 3 adds array/object cases, at which point this builtin gets updated).

Pure builtin, no effect declaration needed.

### 1.5 Bytes hex codecs

`Bytes.FormatHex(bytes) → String` produces lowercase hex (matching common conventions; agents that need uppercase can `String.ToUpper` the result). `Bytes.ParseHex(s) → Option<Bytes>` accepts both cases and rejects odd-length or non-hex inputs with `None`. Mirrors `Bytes.FormatBase64` / `Bytes.ParseBase64` already in the registry.

### 1.6 Random.* and effect categorization

The three Random builtins (`Random.Int`, `Random.Float`, `Random.Bytes`) all use the JVM's `SecureRandom` because there's no reason to ship a non-cryptographic PRNG in a trusted-builtin context. This means they all declare effect category `E-024 Crypto.RandomBytes` — the existing entry covers cryptographically-secure entropy access. No new effect category is added.

For replay determinism, `Random.*` reads from `Builtins.random` (a `java.util.Random` reference) the same way `Time.Now` reads from `Builtins.clock`. The default is `SecureRandom`; tests install a `Random(seed)` via the same `@BeforeAll`/`@AfterAll` pattern.

`Random.Int(min, max)` is inclusive-min / exclusive-max to match common library conventions. `Random.Float()` returns a value in `[0.0, 1.0)`. `Random.Bytes(n)` returns exactly `n` random bytes.

## 2. Phased delivery

### Phase 1 — Math.* + Float coercion (1 hour)

1.1 Implement `Math.Abs`, `Math.Sign`, `Math.Min`, `Math.Max`, `Math.Mod`, `Math.Floor`, `Math.Ceil`, `Math.Round`, `Math.Sqrt`, `Math.Pow`, `Math.Log`, `Math.Exp`, `Math.Sin`, `Math.Cos`, `Math.Tan`. Add `Float.FromInt`, `Int.FromFloatTrunc`. Per-builtin unit tests in a new `BuiltinsMathTest`.

### Phase 2 — Hash.* (30 min)

1.2 Implement `Hash.Blake3`, `Hash.Sha256`, `Hash.Md5`. Reuse the existing `Blake3` from the hashing module. Unit tests covering known test-vector inputs.

### Phase 3 — List.* primitives (1 hour)

1.3 Implement `List.Empty`, `List.IsEmpty`, `List.Length`, `List.Reverse`, `List.Take`, `List.Drop`, `List.Concat`, `List.Nth`. Unit tests with sample lists exercising each.

### Phase 4 — Json.Stringify + Bytes hex (30 min)

1.4 Implement `Json.Stringify`, `Bytes.FormatHex`, `Bytes.ParseHex`. Unit tests covering each primitive JSON case and hex round-trips.

### Phase 5 — Random.* (45 min)

1.5 Implement `Random.Int`, `Random.Float`, `Random.Bytes`. Add `Builtins.random: java.util.Random` (volatile, default `SecureRandom`). Tests install a fixed-seed `Random` via `@BeforeAll`.

### Phase 6 — Documentation (30 min)

1.6 Add a "Stdlib expansion round 2" section to `evaluation/dynamic/prompts/strand-system.md` listing all new builtins with the effect-category notes.

## 3. Verification gates

- `./gradlew test` green after every phase commit.
- No corpus program hashes change (no canonical-store touches in this slice).
- Each builtin gets at least one unit test, plus one error-case test where the function is fallible (`Math.Sqrt` of a negative, `Math.Log` of zero, `List.Nth` out-of-range, `Bytes.ParseHex` invalid input).

## 4. Out of scope (deferred)

- Higher-order List ops (`Map`, `Filter`, `Fold`, `Find`, `Any`, `All`) — requires the lambda-callback infrastructure in round 3.
- Lifting the nested-Json blocker — RecursiveSelf protocol extension, separate proposal.
- Real `Markdown.Parse` (currently a single-Paragraph stub) — needs a markdown lib binding; out of scope for this slice.
- `Map<K, V>` / `Set<T>` data structures — Strand has no general-purpose map; can be approximated with `List<{k, v}>`. A real implementation needs sorted-tree design work.
- Non-secure PRNG with explicit seeding from program — `Random.WithSeed(seed, body)` style. Currently tests inject the seed at the host level only.
- Regex (`Regex.Match`, `Regex.Find`) — non-trivial, needs an engine choice.

## 5. References

**Outgoing:**
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — E-024 Crypto.RandomBytes is the effect category Random.* declares.
- [`proposals/implemented/layer-4-step-2-real-io.md`](implemented/layer-4-step-2-real-io.md) — sets the cadence and Clock-injection pattern this slice mirrors for Random.
- [`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt) — the registry being extended.
- [`impl-kotlin/hashing/`](../impl-kotlin/hashing/) — the Blake3 primitive Hash.Blake3 reuses.

**Incoming:**
- [`proposals/README.md`](README.md) — listed in Active proposals during draft state; moved to Implemented at completion.
