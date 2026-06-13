# Correct-by-construction demonstration documents

Hand-authored Strand programs for the correct-by-construction structured-output
demonstration driven by the `:runtime` test-source-set driver
`OutputByConstructionDemo.kt`
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and its companion
`OutputByConstructionDemoTest`. The narrative is the parent
[`README.md`](../README.md).

Each program is authored directly as canonical dag-json. Unlike the other demos'
tenant programs, there is no paired Layer A source: N-048 `RecursiveProjection`
has no Layer A sugar yet (deferred under the N-048 proposal § 8), so the genuine
nested-array document is written in the canonical form an agent would emit
through the dag-json surface. The three programs share one type/schema/invariant
prelude and differ only in the array's middle element.

## Programs

- `well-formed-array` — the genuine N-048 nested array `[1, 2, 3]` (a real
  `List<JsonValue>` `Cons`/`Nil` spine of `ProductV` cells) carrying the
  `NonNegativeJsonArray` schema. The statically-known value satisfies the
  `all_elements_non_negative` invariant, so it verifies and evaluates to the
  document value. Used by W1.
- `malformed-static-array` — the same document with the middle element a
  statically-known negative literal (`[1, -7, 3]`). The Q-035 `SchemaChecker`
  folds the value at admission and rejects the graph with a verify-time
  `SchemaInvariantViolation` before any execution. Used by W2.
- `malformed-dynamic-array` — the same document with the middle element computed
  at runtime as `Int.Sub(3, 10) = -7`. The verifier cannot fold the dynamic value
  and defers; the graph verifies clean and the Q-047 runtime obligation raises
  `InterpretError.SchemaInvariantViolation` at the value-flow site before the
  malformed document is returned. Used by W3.

The document type, abbreviated:

```
jsonValueT = mu jv. JsonNumber(Int)
                  | JsonArray( mu list. Cons(head: jv, tail: list) | Nil )
```

The inner list is a real nested mu whose `Cons.head` reaches the outer binder
(`RecursiveSelf` depth 1) and whose `Cons.tail` is the inner list (depth 0). Each
value-construction site names a position inside the closed outer mu through an
N-048 `RecursiveProjection` (a `Case`/`Field`/`Unfold` path), so the inner
self-reference is bound when the verifier resolves it. This is the precise model
described in [`proposals/implemented/nested-recursive-types.md`](../../../proposals/implemented/nested-recursive-types.md);
its shape mirrors corpus 88 (`88-json-array-via-projection`).

These are demonstration fixtures, not corpus conformance programs, so they sit
under the top-level `demos/` tree rather than in `corpus/` and stay out of the
golden-hash regression net. The `:runtime` build copies them onto the test
classpath under `/demo/programs/` so the driver and its assertion test load them
without a fragile working-directory dependency.
