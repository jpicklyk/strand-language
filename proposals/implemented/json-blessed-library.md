# First blessed output library: JSON

**Document:** `proposals/implemented/json-blessed-library.md`
**Status:** Implemented (Layer 7 step 1.5 of the Kotlin/JVM reference implementation, 2026-05-24)
**Date:** 2026-05-24
**Concerns:** [`decisions/ADR-009-structured-outputs.md`](../../decisions/ADR-009-structured-outputs.md), [`design/rendering-and-views.md`](../../design/rendering-and-views.md), [`proposals/implemented/schema-and-invariant.md`](schema-and-invariant.md), [Q-026](../../open-questions.md#Q-026), [Q-035](../../open-questions.md#Q-035)
**Scope:** Small-medium — no new node category, no verifier change, three builtins added, three corpus programs

The first concrete demonstration of the Q-035 Schema + Invariant mechanism on a non-synthetic value type. The PositiveInt and NonEmptyList programs that landed with Q-035 are synthetic schemas designed to exercise the mechanism end-to-end on the smallest possible value types. This proposal records the first real library — JSON — and the design choices its implementation required.

## 1. Problem statement

`design/rendering-and-views.md` § Blessed library set commits Strand to six reference output libraries: HTML5, SVG, JSON, PDF, plain text, and Markdown. JSON is the smallest of the six by structural complexity (no rendering pipeline, no accessibility extensions, no graphics primitives) and the most useful as a first demonstration: most agent outputs are JSON-shaped, and JSON has well-defined structural invariants (unique object keys, valid string escapes, proper number ranges) that are checkable at verify time on statically-known values.

The Q-035 mechanism shipped with two synthetic schemas — `PositiveInt` (Int + `x > 0`) and `NonEmptyList` (recursive list + `Cons-vs-Nil` Match). These exercise the mechanism on a single primitive and on the canonical recursive list. They do not exercise the mechanism on a structured value type with library-specific invariants. JSON closes that gap.

## 2. Recommended approach

A two-schema library:

- **`JsonValue`** — a flat sum `JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String)`. No invariants in the initial slice; the schema serves as the type wrapper that makes "this is a JsonValue" structurally distinguishable from a bare sum at type-position sites.
- **`UniqueKeyJsonObject`** — a Schema over `JsonObject`, where `JsonObject = μ. Cons({head: JsonEntry, tail: <self>}) | Nil` and `JsonEntry = {key: String, value: JsonValue}`. The Schema has one invariant `unique_keys` that walks the entries list and rejects duplicate keys.

The `unique_keys` invariant body is non-trivial: it requires a recursive walk over the list, with a nested recursive helper (`contains: (String, JsonObject) -> Bool`) to check whether each head's key appears later in the tail. Both helpers are implemented via `Fixpoint` + `Match` (the standard Strand recursion pattern, also used by corpus 32's `sum-of-list`). The boolean combinators `Bool.And`, `Bool.Or`, and the string equality `String.Eq` are added to `interpreter/Builtins.kt` because they are needed for the invariant body and were not previously present.

The blessed library is a graph subgraph, not a Kotlin object. Future programs that want to use `UniqueKeyJsonObject` would either inline the schema definition (the corpus pattern, since there is no library-loading mechanism beyond `NodeRef`-by-hash yet) or reference it through a stable hash once a "library registry" exists. The current implementation inlines.

## 3. Design choice: arrays and nested objects are deferred

The literal description in `design/rendering-and-views.md` and the user's authoring brief for this work asked for the full JSON shape:

```
JsonValue = JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String)
          | JsonArray(List<JsonValue>) | JsonObject(List<JsonEntry>)
```

This is not implementable cleanly in Strand today. The issue is recursion. `List<JsonValue>` and `List<JsonEntry>` each need their own μ binder for the list's `tail`, and the list elements need to reference JsonValue. `JsonValue` itself is a μ binder. So:

```
JsonValue = μ JV.
  JsonNull | ... |
  JsonArray(μ AL. Nil | Cons({head: JV, tail: AL}))
  ...
```

Inside the inner `μ AL`, `tail` must reference `AL` (the list) and `head` must reference `JV` (JsonValue). But Strand's `RecursiveSelf` (N-042) is defined to always resolve to the **innermost** enclosing `RecursiveType` binder (per `Verifier.resolveType` and `Verifier.kt`'s comment "depth 0 is the canonical representation"). There is no way to write a `RecursiveSelf` that refers to the outer binder while inside an inner binder.

The implementation note in `impl-kotlin/CLAUDE.md` acknowledges this: "Mutual recursion between types. Currently encodable via single-product `RecursiveType` + projection (the textbook lowering); higher-arity recursive binders are a possible future extension if a corpus program needs more direct encoding." JSON is the corpus program that motivates the extension — but extending RecursiveSelf to support multi-binder positional references is a substantive design change that should be its own proposal, not bundled into the first blessed library.

Three workarounds were considered and rejected:

- **Single big sum.** Define one μ binder whose body is a sum containing all cases (JsonValue cases + JsonArrayCons/Nil + JsonObjectCons/Nil). `JsonArray`'s payload type is the whole sum, not a list specifically. The type system permits the payload to be any case; the runtime invariant that array payloads are list-shaped is checked nowhere. Rejected because it loses the structural typing JSON deserves.
- **Mutual recursion via single-product + projection.** Define one `μ Self` whose body is `{json: <sum of JsonValue cases>, arrayList: <list of Self.json>, objectList: <list of Self.json entries>}`. Then `JsonValue = Self.json`, etc. Requires a "project field from a type-level product" operation Strand does not have. Rejected.
- **Flatten arrays/objects out of JsonValue entirely.** Have a `JsonValue` of primitives only, and a separate `JsonObject` type whose values are `JsonValue` (no cycle). This is what was implemented. The cost is that JSON's nested-structure aspect is gone — a JsonObject's values are flat JsonValue primitives, not arbitrary nested JSON.

The third option ships. The first two options are noted as design directions for a future "richer recursion" proposal that would extend the JSON library to a full implementation.

## 4. Implementation note

The work is purely additive — no existing test breaks. Three new builtins (`Bool.And`, `Bool.Or`, `String.Eq`) in `interpreter/Builtins.kt`. Three new corpus programs (54, 55, 56) in `corpus/`. Three new entries in `CorpusSchemaTest.kt`'s case list. README updates in the corpus and in `impl-kotlin/CLAUDE.md`. All 303 pre-existing tests continue to pass alongside the 3 new ones.

The `unique_keys` invariant body is the most substantial single piece of Strand code in the seed corpus — it composes `Fixpoint`, `Match`, `ConstructorPattern`, `VariablePattern`, `ProductFieldGet`, four boolean/string builtins, and a nested `Fixpoint` helper. It is the existence proof that the Q-035 mechanism handles structural invariants on recursive types over non-trivial value structures, not just numeric bounds on primitives.

## 5. Deferred concerns

- **Nested JSON (arrays of JsonValue, objects of JsonValue).** Requires either a richer `RecursiveSelf` (multi-binder positional references) or a different recursion mechanism. Belongs in its own proposal that addresses the mutual-recursion limitation across all recursive types, not just JSON.
- **Float support.** `JsonNumber` is currently `Int`. JSON natively allows IEEE 754 doubles. Adding `JsonFloat(Float)` is mechanical (the primitive exists), but the proposal scopes to Int for the demonstration. A future revision can extend the sum.
- **String escape invariants.** A real JSON library would constrain `JsonString` payloads to "well-formed UTF-16 with proper escape sequences". Strand strings are already UTF-8 by host runtime convention; the invariant is mostly vacuous for our purposes. Adding it would demonstrate string-level invariants but does not move the mechanism's expressive frontier.
- **Number range invariants.** `JsonNumber(Int)` permits any Int. JSON RFC 8259 § 6 permits implementations to limit ranges; a future schema variant could add `BoundedJsonNumber(min, max)` style refinements. Out of scope here.
- **A library-loading mechanism.** The schema is currently inlined in every program that uses it. A future "library registry" — perhaps via `NodeRef` to a well-known canonical hash, perhaps via a richer module system — would let programs reference `UniqueKeyJsonObject` by hash. This belongs to the rendering-pipeline proposal, not to this slice.
- **Serializer.** Q-027 (provenance encoding) and the rendering pipeline both need a JsonValue → bytes serializer. This proposal ships the *type and validation* side of JSON; the *output emission* side (the actual `String` of canonical JSON bytes) lands when the rendering pipeline is implemented.
- **Composing invariants across schemas.** Q-028 asks how multiple schemas claiming the same value compose their invariants. The JSON library does not yet compose with other schemas (no `UniqueKeyJsonObject` ∧ `JsonObjectWithRequiredField("name")` example). Future work.

## 6. Implementation footprint

| File | Change | Size |
|------|--------|------|
| `impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt` | Add `Bool.And`, `Bool.Or`, `String.Eq` foreign callables | Small |
| `corpus/54-json-value-primitives.json` | NEW — JsonValue schema demonstration | Small |
| `corpus/55-json-object-unique-keys.json` | NEW — UniqueKeyJsonObject with 3 distinct keys; passes | Medium (large because the invariant body is a non-trivial `Fixpoint` + nested `Fixpoint` over recursive list) |
| `corpus/56-json-object-duplicate-keys-fail.json` | NEW — UniqueKeyJsonObject with 2 duplicate keys; rejected | Medium |
| `impl-kotlin/corpus/src/test/kotlin/org/strand/corpus/CorpusSchemaTest.kt` | EXTEND — add 3 cases to the case list | Small |
| `corpus/README.md` | EXTEND — describe 54, 55, 56 | Small |
| `impl-kotlin/CLAUDE.md` | EXTEND — Layer 7 step 1.5 status entry | Small |
| `open-questions.md` | EXTEND — Q-026 noting JSON has shipped | Small |
| `proposals/README.md` | EXTEND — add this proposal to implemented table | Trivial |
| `proposals/implemented/json-blessed-library.md` | THIS DOCUMENT | Small |

## References

**Outgoing references:**
- [`proposals/implemented/schema-and-invariant.md`](schema-and-invariant.md) — the Q-035 mechanism this library builds on
- [`decisions/ADR-009-structured-outputs.md`](../../decisions/ADR-009-structured-outputs.md)
- [`design/rendering-and-views.md`](../../design/rendering-and-views.md) — § Blessed library set names JsonValue as one of the six
- [`design/node-algebra.md`](../../design/node-algebra.md) — N-041 RecursiveType / N-042 RecursiveSelf semantics that constrain the design
- [`open-questions.md`](../../open-questions.md) — Q-026 (blessed library set), Q-035 (schema mechanism)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — Layer 7 step 1.5 status

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-026 points at this proposal as the JSON implementation
- [`proposals/README.md`](../README.md)
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md)
