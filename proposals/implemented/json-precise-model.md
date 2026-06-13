# Json.* on the N-048 precise model

**Document:** `proposals/implemented/json-precise-model.md`
**Status:** Implemented (2026-06-13) — Q-069 in the Kotlin/JVM reference implementation
**Date:** 2026-06-13
**Concerns:** [`proposals/implemented/nested-recursive-types.md`](nested-recursive-types.md) (Q-053, N-048), [`proposals/implemented/json-blessed-library.md`](json-blessed-library.md) (Q-026), [`proposals/implemented/stdlib-expansion-round-2.md`](stdlib-expansion-round-2.md), [Q-053](../../open-questions.md#Q-053), [Q-026](../../open-questions.md#Q-026), [Q-069](../../open-questions.md#Q-069)
**Scope:** small-medium

## Implementation note

Shipped in the Kotlin/JVM reference implementation on 2026-06-13, substantially as designed below. What shipped:

- **`Json.Parse` and `Json.Stringify` migrated in place to the precise model.** The two converter helpers in `interpreter/Builtins.kt` (`jsonElementToValue` / `jsonValueToText`) now produce and consume the precise shape: an array is `SumV("JsonArray", <Cons/Nil list>)`, an object `SumV("JsonObject", <Cons/Nil entry list>)` whose cell head is a `{key, value}` product. The four primitive cases are byte-identical, so primitive-only programs are unchanged. **Wider than the proposal's narrow scope:** the Q-037 LLM tool-loop converter pair (`strandJsonValueToElement` / `jsonElementToStrand`, which translate a Strand `JsonValue` tool-input / `providerExtras` to/from host JSON) was migrated too — they are the same `JsonValue` model and must not diverge from one canonical model. No new builtin, no registry signature change.
- **The agent-facing `Sig.JsonValue` tower re-expressed as the precise six-case model.** `authoring/ImplicitBuiltinExpansion.kt`'s `synthJsonValueTower` now emits the precise tower (the model corpus 88/89 construct). To express the depth-1 self-references the inner lists need, the Layer A `RS` code gained an optional `depth` INT field (default 0); an absent depth arg still emits a bare depth-0 `RecursiveSelf`, so every existing `RS` use is byte-unchanged. The tower is only a type the elaborator synthesizes for a builtin signature, so it never appears in a golden corpus program and no corpus golden moved.
- **Corpus 66 retired and replaced.** The spliced `66-json-value-nested.json` is removed; `66-json-roundtrip-via-builtins.json` reuses the slot — a precise `JsonArray [1, 2]` built via `RecursiveProjection` (corpus 88's construction) passed through `Json.Stringify`, evaluating to `"[1,2]"`. This is the one deliberate golden regeneration: corpus 66's slot is the only hash that moved; every other golden is byte-identical and the epoch stays 2 (no encoding change). The independent Python encoder reproduces all 91 goldens including the new 66.
- **The output-by-construction demo's W4 round-trip closed.** `OutputByConstructionDemo` W1 now renders its array through the shipped `Json.Stringify` builtin (wrapping the produced inner list in the precise-model `JsonArray` case) instead of a driver-side walk; the demo README's note-on-serialization is rewritten to record the closed round-trip.

**The blessed-schema re-expression was deferred as the proposal § 3.3 / § 8 specify.** `UniqueKeyJsonObject` (corpus 55/56) builds its `JsonObject` on a standalone entry list whose entry values are corpus 54's flat (primitives-only) `JsonValue`; re-expressing it over the precise `jsonValueT`'s projected entry list is a self-contained corpus rewrite that would deliberately move corpus 55/56's goldens, independent of the builtin migration. Deferred to keep this slice's golden churn to the single corpus-66 slot, with the unblocker recorded in § 8. Float support, value-domain invariants, schema-claimed `Json.Parse` results, and elaborator tower hoisting (Q-063) remain deferred as the proposal states.

Full suite 2356 tests green (2355 baseline + 1 new), 0 failures, 3 expected skips.

---

Migrates the `Json.Parse` / `Json.Stringify` builtins and the agent-facing JSON type model from the corpus-66 *spliced* `JsonValueFull` to the N-048 *precise* model, closing the inconsistency the N-048 work opened. N-048 `RecursiveProjection` lets an agent build a genuine precise JSON value — a `JsonArray` carrying a real `List<JsonValue>`, a `JsonObject` carrying a real entry list — but `Json.Parse` produces and `Json.Stringify` consumes only the old spliced shape, so a program that builds JSON the precise way cannot round-trip it through the builtins. This is the follow-up the N-048 proposal § 8 names explicitly ("migrating corpus 66 / `Json.Parse` / `Json.Stringify` to the precise model").

## 1. Problem statement

Two incompatible runtime representations of a JSON value now coexist in the reference implementation.

The **spliced model** (corpus 66 `JsonValueFull`, the model `stdlib-expansion-round-2.md` slice 3 shipped) flattens the array and object spines into the top-level `JsonValue` sum. Its cases are `JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String) | JsonArrayCons({head, tail}) | JsonArrayNil | JsonObjectCons({key, value, tail}) | JsonObjectNil`, every back-edge a `RecursiveSelf` at depth 0. At runtime an array `[1, 2]` is `SumV("JsonArrayCons", ProductV{head: SumV("JsonNumber", IntV(1)), tail: SumV("JsonArrayCons", ...)})` — the `Cons` cell is itself a `JsonValue`. The precision lost is that `tail` is typed as the whole `JsonValue` sum, so `JsonArrayCons(JsonNumber(1), JsonString("x"))` type-checks: the type system cannot distinguish a well-formed array spine from arbitrary nesting, and a bare `JsonArrayNil` is itself a legal top-level `JsonValue`.

The **precise model** (corpus 88/89, the model N-048 makes constructible) keeps `JsonValue = μ jv. ... | JsonArray(μ list. Cons(head: jv, tail: list) | Nil) | JsonObject(μ. Cons(head: {key: String, value: jv}, tail: <self>) | Nil)`. At runtime the same array is `SumV("JsonArray", SumV("Cons", ProductV{head: SumV("JsonNumber", IntV(1)), tail: SumV("Cons", ...)}))` — `JsonArray` wraps a genuine list whose cells are `Cons`/`Nil`, and the list element type reaches the outer `jv` binder, so a malformed spine is a verify-time error rather than a deferred schema check.

The shipped `Json.Parse` (`jsonElementToValue` in `interpreter/Builtins.kt`) emits the spliced shape; `Json.Stringify` (`jsonValueToText`) walks it back. The Layer A authoring layer types both against `Sig.JsonValue`, whose `synthJsonValueTower` (in `authoring/ImplicitBuiltinExpansion.kt`) synthesizes the spliced 8-case `JsonValueFull` tower as the agent-facing type. Concretely: an agent that builds an array the precise way (a `JsonArray` over a real list, as corpus 88 does) and passes it to `Json.Stringify` gets a runtime error — `jsonValueToText` sees `SumV("JsonArray", ...)`, which is not a case it recognizes, and falls through to `"null"`. The correct-by-construction structured-output demonstration (`demos/output-by-construction/`) had to cut its round-trip-via-`Json.Stringify` scenario (W4) for exactly this reason and render its array by a driver-side walk instead. The two models cannot interoperate, and the precise one — the one N-048 was built to enable — is the one the builtins do not speak.

## 2. Prior art

- **One canonical AST, parser and serializer agree on it.** Every mainstream JSON library (`serde_json::Value`, Python `json` `dict`/`list`, Go `encoding/json` `interface{}`, kotlinx-serialization `JsonElement`) defines a single value type that the parser produces and the serializer consumes, with arrays and objects as genuine sequence/map containers, not flattened spine variants. None ships two parallel representations. The spliced model was a workaround for a recursion limitation that no longer exists; the natural design is a single precise model the builtins and agents share.
- **The host already has the precise structure.** `kotlinx.serialization.json.JsonArray` is a real `List<JsonElement>` and `JsonObject` a real `Map<String, JsonElement>`. The spliced conversion (`jsonElementToValue`) deliberately *destroys* this structure by flattening the host list into tagged `JsonArrayCons`/`Nil` variants; the precise conversion preserves it by building a `Cons`/`Nil` list nested under a `JsonArray` case. The precise mapping is the more faithful one — host structure maps to Strand structure one-to-one.
- **N-048 is the introduction form for exactly this shape.** The nested-recursive-types proposal (§ 2, isorecursive `fold`-site discipline) establishes that a value of nested-recursive type names a position inside the closed outer μ via a `Case`/`Field`/`Unfold` projection path. Corpus 88/89 are the worked references; `Json.Parse` building the precise value is the runtime analogue of those construction sites, with the projection types erased at runtime (the runtime builds `SumV`/`ProductV` and never inspects the projection, per the N-048 proposal § 6).

## 3. Recommended approach

**Migrate `Json.Parse` and `Json.Stringify` in place to the precise model, retire the spliced model as the canonical JSON representation, and re-express the agent-facing `Sig.JsonValue` type tower as the precise model.** One canonical JSON model going forward — the precise one — with no spliced variants left as cruft.

The canonical precise `JsonValue` is the full six-case model the JSON blessed library originally specified (`json-blessed-library.md` § 3) and that N-048 now makes constructible:

```
jsonValueT = μ jv.
    JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String) |
    JsonArray ( μ list.  Cons(head: jv,                    tail: list) | Nil ) |
    JsonObject( μ ents.  Cons(head: {key: String, value: jv}, tail: ents) | Nil )
```

`JsonArray` carries a real `List<JsonValue>` (corpus 88's `innerListT`); `JsonObject` carries a real entry list whose head is a `{key, value}` product (corpus 89's `entryListT`). Both inner lists are nested μ whose `head`/`value` reach the outer `jv` binder, and every value-construction site names its position through a `RecursiveProjection` of the closed `jsonValueT`, exactly as corpus 88/89 do.

Three sub-decisions, each committed:

1. **`Json.Parse` returns the precise model; `Json.Stringify` consumes it (migrate in place, no variants).** The runtime `Value` shape `Json.Parse` produces changes from the spliced cases to the precise cases: an array becomes `SumV("JsonArray", <Cons/Nil list>)` and an object `SumV("JsonObject", <Cons/Nil entry list>)`, with the four primitive cases (`JsonNull`/`JsonBool`/`JsonNumber`/`JsonString`) unchanged. `Json.Stringify` walks the precise shape. Adding precise variants *alongside* the spliced ones is rejected (§ 8): it leaves two models as permanent cruft, and the builtins would still have to guess which an agent meant. Migrating in place gives one model.

2. **Corpus 66 is retired and replaced by a precise round-trip demonstrator.** Corpus 66 is the only spliced-model corpus program; corpus 88/89 already demonstrate the precise *construction* model. Rather than rewrite 66 to a precise shape that would duplicate 88, corpus 66 is removed and a new corpus program — `66-json-roundtrip-via-builtins.json` reusing the same slot number — demonstrates the precise model *through the builtins*: it builds a precise `JsonArray` value via `RecursiveProjection` (corpus 88's construction) and applies `Json.Stringify` to it, asserting the emitted text. This is the round-trip the spliced model blocked, and it is the corpus's first program exercising a JSON builtin end-to-end on a constructed value. Retiring 66 deliberately changes its golden hash entry (the slot is reused for a different program); this is the one deliberate golden regeneration, documented below. No encoding change — the slot's bytes change because the *program* changed, not because the encoding did.

3. **`UniqueKeyJsonObject` re-expression is deferred (see § 8), with the deferral unblocked by this slice.** The blessed `UniqueKeyJsonObject` Schema (corpus 55/56) is built on the *standalone* `JsonObject` recursive list (`μ. Cons({head: JsonEntry, tail: <self>}) | Nil` where `JsonEntry = {key: String, value: JsonValue-flat}`), not on the spliced `JsonValueFull` — it predates corpus 66 and uses corpus 54's flat `JsonValue` for entry values. Its `unique_keys` invariant walks the entry list and is independent of the array/object splice. Re-expressing it on the precise model means making its `JsonObject` the *projected inner entry list* of the precise `jsonValueT` (so `value` reaches the full recursive `JsonValue`, not the flat primitives-only one), which is a self-contained corpus-program rewrite touching corpus 55/56 and is genuinely separable from the builtin migration. This proposal migrates the builtins and the agent-facing tower (the load-bearing inconsistency) and defers the blessed-schema re-expression with a clear unblocker: corpus 55/56 rewritten to wrap the precise `jsonValueT`'s projected `JsonObject` entry list, with the `unique_keys` invariant body unchanged in shape.

This is **additive at the encoding level**: no node category, category tag, or canonical encoding changes; `CanonicalEncoding.EPOCH` stays 1 (epoch 2 per the encoding-epochs charter is untouched). The only hash that moves is corpus 66's slot, and it moves because the program in that slot is deliberately replaced, not because any byte encoding changed — its golden is regenerated and the Python conformance encoder must reproduce it.

## 4. Detailed mechanism

### 4.1 The precise runtime value shapes

The migration is entirely in the runtime `Value` shapes the two builtins produce and consume. The mapping, host JSON element to Strand `Value`:

| Host element | Spliced `Value` (today) | Precise `Value` (after) |
|---|---|---|
| `null` | `SumV("JsonNull", null)` | `SumV("JsonNull", null)` (unchanged) |
| `true`/`false` | `SumV("JsonBool", BoolV(b))` | `SumV("JsonBool", BoolV(b))` (unchanged) |
| number | `SumV("JsonNumber", IntV(n))` | `SumV("JsonNumber", IntV(n))` (unchanged) |
| string | `SumV("JsonString", StringV(s))` | `SumV("JsonString", StringV(s))` (unchanged) |
| `[e0, e1]` | `SumV("JsonArrayCons", ProductV{head: <e0>, tail: SumV("JsonArrayCons", ProductV{head: <e1>, tail: SumV("JsonArrayNil", null)})})` | `SumV("JsonArray", SumV("Cons", ProductV{head: <e0>, tail: SumV("Cons", ProductV{head: <e1>, tail: SumV("Nil", null)})}))` |
| `{"k": v}` | `SumV("JsonObjectCons", ProductV{key: StringV("k"), value: <v>, tail: SumV("JsonObjectNil", null)})` | `SumV("JsonObject", SumV("Cons", ProductV{head: ProductV{key: StringV("k"), value: <v>}, tail: SumV("Nil", null)}))` |

The four primitive cases are byte-identical between models, so primitive-only round-trips are unaffected. The two container cases change shape: the spine variants `JsonArrayCons`/`JsonArrayNil`/`JsonObjectCons`/`JsonObjectNil` disappear, replaced by a `JsonArray`/`JsonObject` wrapper around a canonical `Cons`/`Nil` list. Note the object's precise entry: the spliced `JsonObjectCons` flattens `key`, `value`, and `tail` into one product; the precise model nests a `{key, value}` entry product as the list cell's `head`, matching corpus 89's `entryProduct`.

### 4.2 `Json.Parse` — `jsonElementToValue` rewrite

The recursive `jsonElementToValue` helper changes its array and object arms:

```kotlin
is JsonArray -> {
    var chain: Value = Value.SumV("Nil", null)
    for (entry in element.reversed()) {
        val converted = jsonElementToValue(entry) ?: return null
        chain = Value.SumV("Cons", Value.ProductV(mapOf("head" to converted, "tail" to chain)))
    }
    Value.SumV("JsonArray", chain)
}
is JsonObject -> {
    var chain: Value = Value.SumV("Nil", null)
    for ((key, value) in element.entries.reversed()) {
        val convertedValue = jsonElementToValue(value) ?: return null
        val entry = Value.ProductV(mapOf("key" to Value.StringV(key), "value" to convertedValue))
        chain = Value.SumV("Cons", Value.ProductV(mapOf("head" to entry, "tail" to chain)))
    }
    Value.SumV("JsonObject", chain)
}
```

The primitive arms are unchanged. The list is built right-to-left over the reversed elements so the head is the first element, exactly as the spliced version did, but the cells are now generic `Cons`/`Nil` and the whole list is wrapped in the `JsonArray`/`JsonObject` case.

### 4.3 `Json.Stringify` — `jsonValueToText` rewrite

`jsonValueToText` dispatches on the top-level `JsonValue` case. The array/object arms change from matching the spine variants to matching the wrapper case and walking the inner `Cons`/`Nil` list:

```kotlin
"JsonArray" -> {
    val out = StringBuilder("[")
    var first = true
    var cur: Value = v.payload as Value.SumV          // the inner Cons/Nil list
    while (cur is Value.SumV && cur.case == "Cons") {
        val cell = cur.payload as Value.ProductV
        if (!first) out.append(","); first = false
        out.append(jsonValueToText(cell.fields.getValue("head") as Value.SumV))
        cur = cell.fields.getValue("tail")
    }
    out.append("]").toString()
}
"JsonObject" -> {
    val out = StringBuilder("{")
    var first = true
    var cur: Value = v.payload as Value.SumV
    while (cur is Value.SumV && cur.case == "Cons") {
        val cell = cur.payload as Value.ProductV
        val entry = cell.fields.getValue("head") as Value.ProductV   // {key, value}
        if (!first) out.append(","); first = false
        out.append(JsonPrimitive((entry.fields.getValue("key") as Value.StringV).v).toString())
        out.append(":")
        out.append(jsonValueToText(entry.fields.getValue("value") as Value.SumV))
        cur = cell.fields.getValue("tail")
    }
    out.append("}").toString()
}
```

The primitive arms are unchanged. The empty-container forms are now `SumV("JsonArray", SumV("Nil", null))` → `"[]"` and `SumV("JsonObject", SumV("Nil", null))` → `"{}"`, replacing the spliced bare `JsonArrayNil`/`JsonObjectNil`.

### 4.4 The agent-facing tower — `synthJsonValueTower` rewrite

`Sig.JsonValue` is the type the elaborator synthesizes for `Json.Parse`'s `Option<JsonValue>` result and `Json.Stringify`'s `JsonValue` argument. `synthJsonValueTower` must emit the precise six-case tower instead of the spliced eight-case one. The precise tower is structurally corpus 88 ∪ corpus 89's `jsonValueT`: an outer μ with `JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String) | JsonArray(innerListT) | JsonObject(entryListT)`, where `innerListT = μ list. Cons(head: RecursiveSelf depth=1, tail: RecursiveSelf depth=0) | Nil` and `entryListT = μ. Cons(head: {key: String, value: RecursiveSelf depth=1}, tail: RecursiveSelf depth=0) | Nil`. The synthesized tower uses the existing `RS` (RecursiveSelf with depth), `RT`, `SUM`, `SCS`, `PRD`, `PRF` codes; the depth-1 self-references inside the inner lists are the same shape corpus 88/89 hand-author. Because this tower is only a *type* the elaborator emits for a builtin's signature (it never appears in a golden corpus program directly), changing it moves no corpus golden hash.

### 4.5 Worked example — round-trip `[1, 2]`

`Json.Parse("[1,2]")`:
1. kotlinx parses to `JsonArray[JsonPrimitive(1), JsonPrimitive(2)]`.
2. `jsonElementToValue` builds, right-to-left: `Nil`, then `Cons{head: JsonNumber(2), tail: Nil}`, then `Cons{head: JsonNumber(1), tail: Cons{...}}`, then wraps: `SumV("JsonArray", <that Cons list>)`.
3. Wrapped as `SumV("Some", <JsonArray value>)`.

`Json.Stringify` of that `JsonArray` value:
1. Matches `"JsonArray"`, takes the payload (the `Cons` list).
2. Walks: `Cons` → emit `jsonValueToText(JsonNumber(1))` = `"1"`; `Cons` → `","` + `"2"`; `Nil` → stop.
3. Result `"[1,2]"`. Round-trip identity holds.

The corpus 88 construction (`SumV("JsonArray", SumV("Cons", ProductV{head: SumV("JsonNumber", IntV(1)), ...}))`) is byte-for-byte the same runtime shape `Json.Parse("[1]")` now produces, so a value built via N-048 `RecursiveProjection` stringifies correctly — closing the W4 round-trip the demonstration cut.

## 5. Verifier rules

None. This proposal adds no node category, no type-position node, and no verifier rule. The precise `JsonValue` type is expressed entirely with existing nodes (`RecursiveType`, `RecursiveSelf`, `SumType`, `ProductType`, `RecursiveProjection`), all of which the verifier already handles. The agent-facing tower the elaborator synthesizes type-checks under the existing N-048 resolution.

## 6. Interpreter / runtime semantics

The only runtime change is the two builtins' value shapes (§ 4.2, § 4.3). Both are `Deterministic` pure builtins; their effect declarations, replay determinism (Q-065), and harm bound (Q-044) are unaffected. No new builtin is registered and no builtin is removed — `Json.Parse` and `Json.Stringify` keep their names and arities; only their internal value mapping changes. The bytecode VM is unaffected: the builtins run identically under the tree-walker and the VM since they operate on `Value`s, not on bytecode.

## 7. Test scenarios

1. **Primitive round-trips unchanged** — `Json.Parse` then `Json.Stringify` of `null`, `true`, `false`, `42`, `-99`, `"hello world"` each yields the input string. The four primitive cases are byte-identical across models, so the existing `BuiltinsJsonHexTest` primitive assertions pass verbatim.
2. **Array round-trip on the precise model** — `Json.Parse("[1,2,3]")` → `Json.Stringify` → `"[1,2,3]"`, with the intermediate value asserted to be `SumV("JsonArray", SumV("Cons", ...))`, not the spliced `JsonArrayCons`.
3. **Object round-trip on the precise model** — `Json.Parse("{\"a\":1,\"b\":\"x\"}")` → `Json.Stringify` → identical text, intermediate value `SumV("JsonObject", SumV("Cons", ProductV{head: ProductV{key, value}, ...}))`.
4. **Nested round-trip** — `[true,null,"y"]` and `{"k":[1,2]}` round-trip identically, exercising containers nested inside containers.
5. **Empty containers** — `Json.Stringify(SumV("JsonArray", SumV("Nil", null)))` = `"[]"`; `Json.Stringify(SumV("JsonObject", SumV("Nil", null)))` = `"{}"`.
6. **Build-via-N-048 then stringify** — construct corpus 88's `JsonArray([1,2])` precise value directly as a `Value` and pass it to `Json.Stringify`; assert `"[1,2]"`. This is the closing of the W4 round-trip: a value built the precise way, stringified through the builtin.
7. **Corpus round-trip demonstrator** — the new corpus `66-json-roundtrip-via-builtins.json` builds a precise `JsonArray` via `RecursiveProjection` and applies `Json.Stringify`, evaluating to the expected JSON string; pinned in `CorpusTest`.
8. **Golden regeneration is exactly one slot** — `CorpusGoldenHashTest` confirms every corpus golden hash *except* slot 66 is byte-identical to before; slot 66's golden is the deliberately regenerated entry for the replacement program; the Python conformance encoder reproduces all goldens including the new 66.
9. **Demo W4 round-trip** — `OutputByConstructionDemo` W1 renders its array via `Json.Stringify` (replacing the driver-side walk), and `OutputByConstructionDemoTest` asserts the emitted text matches the structure-walk text, proving the migration enables the round-trip.
10. **Elaborator emits the precise tower** — a Layer A program `FN Json.Stringify` whose argument is an elaborator-inferred `Sig.JsonValue` synthesizes the precise six-case tower (asserted structurally in `ImplicitBuiltinExpansionTest`), and a Layer A round-trip program compiles, verifies, and runs.

## 8. Tradeoffs and open questions

**Rejected: add precise-model variants alongside the spliced ones.** Keep `Json.Parse`/`Json.Stringify` producing/consuming the spliced model and add `Json.ParsePrecise`/`Json.StringifyPrecise` (or make the builtins accept both shapes). Rejected because it leaves two JSON models as permanent cruft: agents would have to know which model a given builtin speaks, the spliced model would persist as a trap for new code, and the inconsistency the N-048 work opened would be papered over rather than closed. One canonical model is the whole point.

**Rejected: deprecate the spliced model but retain corpus 66 verbatim as a legacy fixture.** Keep corpus 66's spliced program and golden hash unchanged, and add the precise demonstrator in a new slot. Rejected because corpus 66 is the *only* spliced-model program — once the builtins and the agent-facing tower speak only the precise model, corpus 66's spliced `JsonValueFull` is a dead shape no shipped surface produces or consumes, and retaining it as a "historical exemplar" preserves the exact confusion this proposal removes. Reusing the slot for a precise round-trip demonstrator keeps the corpus count stable and replaces a dead fixture with a live one. (The N-048 proposal § 8 had named "corpus 66 stays as the historical splice exemplar" as the deferral's expected shape; this proposal supersedes that with the cleaner retire-and-replace, justified by the spliced model having no remaining consumer.)

**Rejected: a single big-sum precise model without nested μ.** The `json-blessed-library.md` § 3 "single big sum" workaround (one μ whose body holds all cases including spine variants, `JsonArray`'s payload being the whole sum) is the spliced model under another name and loses the same precision. N-048 removed the need for it.

**Deferred intentionally:**

- **Re-expressing the `UniqueKeyJsonObject` blessed Schema on the precise model.** Corpus 55/56 build their `JsonObject` on a standalone recursive entry list whose entry values are corpus 54's *flat* `JsonValue` (primitives only). Re-expressing them so `value` is the precise recursive `JsonValue` (the projected inner entry list of `jsonValueT`) is a self-contained corpus-program rewrite, independent of the builtin migration, and would itself deliberately move corpus 55/56's golden hashes. It is deferred to keep this slice's golden churn to the single corpus-66 slot. **Unblocker:** rewrite corpus 55/56's `JsonObject` to be `RecursiveProjection(jsonValueT, [Case("JsonObject"), Unfold])` over the precise `jsonValueT`, with the `unique_keys` invariant body unchanged in shape (it walks `Cons`/`Nil` and compares `key` fields), regenerating those two goldens deliberately. The `unique_keys` invariant logic does not change; only the type the schema wraps does.
- **Float support, number-range and string-escape invariants.** As `json-blessed-library.md` § 5 records, `JsonNumber` is `Int`-only and the library carries no number/string invariants. Out of scope here; this proposal changes the *representation*, not the *value domain*.
- **A `Json.Parse` that returns the schema-claimed `JsonValue` (not bare).** `Json.Parse` returns `Option<JsonValue>` over the bare precise type; wrapping the result in the `JsonValue` blessed Schema (so a parsed value carries its schema) is a separate question tied to the library-registry mechanism `json-blessed-library.md` § 5 defers.

**Real research questions:**

- *Whether the elaborator should reuse one canonical `jsonValueT` subgraph by hash rather than re-synthesizing the tower per call site.* Today `synthJsonValueTower` emits a fresh tower each time the signature is needed; content-addressing deduplicates the result, so this is a synthesis-cost question, not a correctness one. The prelude-as-module work (Q-063) is the natural home for hoisting blessed type towers to hash-pinned shared subgraphs. Out of scope here.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `impl-kotlin/interpreter/.../Builtins.kt` | rewrite `jsonElementToValue` array/object arms and `jsonValueToText` array/object arms to the precise model; update the leading doc comments on `Json.Parse`/`Json.Stringify` | Small-Medium |
| `impl-kotlin/interpreter/.../BuiltinsJsonHexTest.kt` | rewrite the array/object assertions to the precise shape; add the build-via-N-048-then-stringify case; the primitive cases stay verbatim | Small-Medium |
| `impl-kotlin/authoring/.../ImplicitBuiltinExpansion.kt` | rewrite `synthJsonValueTower` to emit the precise six-case tower (nested-μ inner list + entry list); update the `Sig.JsonValue` doc comment | Medium |
| `impl-kotlin/authoring/.../BuiltinSignatures.kt` | update the `Sig.JsonValue` doc comment (corpus-66 → precise model) | Small |
| `impl-kotlin/authoring/.../ImplicitBuiltinExpansionTest.kt` | update/extend the `Sig.JsonValue` tower assertion to the precise shape | Small |
| `corpus/66-json-value-nested.json` → `corpus/66-json-roundtrip-via-builtins.json` | retire the spliced program; new precise round-trip demonstrator (build `JsonArray` via `RecursiveProjection`, apply `Json.Stringify`) | Medium |
| `impl-kotlin/corpus/.../CorpusTest.kt` | replace the corpus-66 spliced assertion with the new program's expected output | Small |
| `corpus/golden-hashes.json` | regenerate slot 66 only (deliberate program change); epoch stays 1 | Small (one entry) |
| `corpus/README.md` | rewrite the corpus-66 row for the new program; note 88/89 are the construction references | Small |
| `evaluation/conformance/independent_encoder.py` (run, not edit) | confirm it reproduces the regenerated slot-66 golden; epoch unchanged | Small (verification) |
| `demos/output-by-construction/OutputByConstructionDemo.kt` + `README.md` | replace the driver-side `renderJsonArray` walk with a `Json.Stringify` call; add the W4 round-trip assertion; update the README "note on serialization" | Small-Medium |
| `impl-kotlin/CLAUDE.md`, `INDEX.md`, `proposals/README.md`, `open-questions.md` | bookkeeping per Deliverable 3 | Small |

**Order of work.** (1) Migrate the two builtins + their unit test (proves the runtime model before any corpus touch). (2) Migrate the agent-facing tower + its test. (3) Retire-and-replace corpus 66, regenerate its golden, confirm the Python encoder agrees and every other golden is unchanged. (4) Migrate the demo's W4 round-trip + README. (5) Bookkeeping.

**Not in this slice.** Re-expressing the `UniqueKeyJsonObject` blessed Schema on the precise model (deferred above with its unblocker); Float support and value-domain invariants; schema-claimed `Json.Parse` results; elaborator tower hoisting (Q-063).

## References

**Outgoing references:**
- [`proposals/implemented/nested-recursive-types.md`](nested-recursive-types.md) — N-048 `RecursiveProjection`, the mechanism the precise model is built on; § 8 names this migration as the deferred follow-up; corpus 88/89 are the precise construction references
- [`proposals/implemented/json-blessed-library.md`](json-blessed-library.md) — the original flat `JsonValue` (corpus 54), the `UniqueKeyJsonObject` Schema (corpus 55/56), and § 3's full-JSON shape this proposal finally realizes
- [`proposals/implemented/stdlib-expansion-round-2.md`](stdlib-expansion-round-2.md) — the spliced `JsonValueFull` (corpus 66) and the `Json.Parse`/`Json.Stringify` rewrite that produced it
- [`open-questions.md`](../../open-questions.md) — Q-069 (this proposal resolves it), Q-053, Q-026

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-069 points at this proposal
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — Known gaps section
