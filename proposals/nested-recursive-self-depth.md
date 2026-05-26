# Nested RecursiveSelf — depth field

**Document:** `proposals/nested-recursive-self-depth.md`
**Status:** Draft 2026-05-26 (Slice 3 of stdlib expansion round 2)
**Concerns:** [`design/node-algebra.md`](../design/node-algebra.md), [`impl/core/src/main/kotlin/org/strand/core/Node.kt`](../impl/core/src/main/kotlin/org/strand/core/Node.kt), [`impl/hashing/src/main/kotlin/org/strand/hashing/CanonicalEncoder.kt`](../impl/hashing/src/main/kotlin/org/strand/hashing/CanonicalEncoder.kt), [`impl/verifier/`](../impl/verifier/), [`impl/corpus/src/main/resources/corpus/54-json-value-primitives.json`](../impl/corpus/src/main/resources/corpus/54-json-value-primitives.json), [`impl/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt`](../impl/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt)
**Scope:** Small — N-042 RecursiveSelf gains an optional `depth: Int = 0` field, encoder/verifier are already structured to handle it, JsonValue gets nested-array/object cases

## 1. The blocker today

The blessed `JsonValue` sum (corpus 54) currently has four primitive cases:

    JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String)

The two structural cases (`JsonArray`, `JsonObject`) are unexpressable because they need recursion. The natural shape would be:

    JsonArray(List<JsonValue>)
    JsonObject(List<{key: String, value: JsonValue}>)

Both require a `μ list. Cons(head: JsonValue, tail: list) | Nil` form nested *inside* the outer `μ jv. ...` JsonValue type. The inner μ's `RecursiveSelf` correctly refers to the inner list itself (depth 0). But the inner list's `head` field needs to refer to the *outer* JsonValue μ — depth 1.

The current implementation of N-042 RecursiveSelf is `object RecursiveSelf` with no depth field. The canonical encoder always emits depth `0`. There is no way today to express "refer to the second-innermost recursive binder."

The design (node-algebra.md §N-042) already specifies: *"Its canonical encoding emits the de Bruijn depth from the innermost enclosing binder (depth 0 = the immediate enclosing RecursiveType)."* So depth is implicit in the design and explicit in the encoder; making it a node field is the missing piece.

## 2. Design call

**Extend `Node.RecursiveSelf` from `object` to `data class RecursiveSelf(val depth: Int = 0)`**. Default `0` preserves backward compatibility — every existing JSON program parses to depth-0 RecursiveSelf nodes (the JSON syntax `{"type": "RecursiveSelf"}` continues to work; programs that need a higher depth add `{"type": "RecursiveSelf", "depth": 1}`).

The canonical encoding already serializes depth — the change is to read it from the node rather than hardcoding `0L`. Existing hashes are preserved because all existing programs have depth = 0.

The verifier extends `UnboundRecursiveSelf` to fire when `depth >= number-of-enclosing-RecursiveType-binders`. Currently it fires when `depth >= 1 && currentDepth == 0`; the new check handles arbitrary nesting.

The Hasher's walk skip-list (`is Node.ParameterDecl || is Node.TypeParameter || is Node.RecursiveSelf`) continues to apply: RecursiveSelf nodes don't get standalone hashes (they're positional references); only the depth field affects encoding.

Layer A density does not currently project RecursiveSelf depth and does not need to in this slice — nested μ is a fairly advanced pattern; agents using nested recursion can emit the canonical `RS depth=N` form directly. A follow-up density-vN slice can add sugar.

## 3. JsonValue extension

**Implementation pivot (2026-05-26).** The depth-field extension is correct as a type-algebra primitive (verifier tests pass, encoder works), but it doesn't compose with value construction in Strand's content-addressed type system. The inner μ-type (e.g., `arrListT` for a List<JsonValue>) has the same canonical hash regardless of context, but its `RecursiveSelf(depth=1)` reference only resolves correctly when the type is traversed *as part of* its enclosing outer μ. A direct construction site like `SumValue.ofType = arrListT` resolves the inner type standalone, finds the depth-1 reference unbound (only 1 RT in the resolution walk), and aborts with `UnboundRecursiveSelf`.

Two viable solutions:

1. **Inline ("spliced") variants.** Collapse the inner-list μ into the outer JsonValue μ as tagged variants directly: `JsonArrayCons(head: jv, tail: jv) | JsonArrayNil | JsonObjectCons(key: String, value: jv, tail: jv) | JsonObjectNil`. All `RecursiveSelf` references stay at depth=0 (the single enclosing μ). The trade-off: `JsonArrayNil` alone is a legal `JsonValue`, which is semantically meaningless outside an array context — but the type checks fine and the runtime is unambiguous.

2. **Nominal recursive types or type-level abstraction.** A larger node-algebra change (named RTs, or `Forall<T> Recursive<T>` with type application). Defers indefinitely.

Slice 3.2 ships solution 1 (spliced variants). The shape:

    jsonValueT = μ jv.
        JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String) |
        JsonArrayCons(head: jv, tail: jv) | JsonArrayNil |
        JsonObjectCons(key: String, value: jv, tail: jv) | JsonObjectNil

`Json.Parse` builds these values: an array `[1,2]` becomes `JsonArrayCons(JsonNumber(1), JsonArrayCons(JsonNumber(2), JsonArrayNil))`. An object `{"k": 1}` becomes `JsonObjectCons("k", JsonNumber(1), JsonObjectNil)`. `Json.Stringify` walks the chain symmetrically. The four primitive cases are unchanged.

The depth field ships alongside (it's already implemented + tested) as a sound foundational primitive — its expressiveness will become useful when Strand adds polymorphic recursive types or other constructs that don't have the value-construction issue.

## 4. Phased delivery

1. **Phase 3.2a** — extend `Node.RecursiveSelf` to `data class(depth: Int = 0)`; update Json parser, canonical encoder, verifier. Add unit tests for nested-RT depth-N references. All existing hashes preserved.
2. **Phase 3.2b** — replace corpus 54's flat JsonValue with the six-case nested-μ version. Add corpus exemplars 66 (nested-array JsonValue) and 67 (nested-object JsonValue). Update `Json.Parse` to build the full recursive structure.
3. **Phase 3.3** — update strand-system.md: remove the nested-μ caveat, document the depth field, document the new JsonArray / JsonObject cases.

`Json.Stringify` and the round-trip property follow naturally after 3.2b — the existing four-case Stringify code stays unchanged, two new cases are added.

## 5. Out of scope

- Layer A density sugar for `RS depth=N` (a follow-up density slice can add `RS1` / `RS@1` notation).
- Polymorphic recursive types via ForallType + RecursiveType + TypeApplication — the depth approach is simpler and sufficient for the immediate blocker.
- `Json.Get(obj, key)` and `Json.Index(arr, i)` traversal helpers — these are pure operations on the new JsonValue and can be added later if agents need them; the higher-order List ops (`List.Find`, `List.Filter`) already cover most needs once `JsonObject` is just `List<{key, value}>`.
- General `Map<K, V>` data structure — JsonObject as `List<{key, value}>` is sufficient for JSON; a real map would need its own design.

## 6. References

**Outgoing:**
- [`design/node-algebra.md`](../design/node-algebra.md) §N-042 (the depth semantics are already specified, just not realized in the node field).
- [`proposals/implemented/layer-4-step-2-real-io.md`](implemented/layer-4-step-2-real-io.md) — `Json.Parse` documented the nested-μ blocker explicitly as a follow-up.
- [`proposals/stdlib-expansion-round-2.md`](stdlib-expansion-round-2.md) — slice 3 of that proposal.

**Incoming:**
- [`proposals/README.md`](README.md) — listed in Active proposals during draft state.
