# Correct-by-construction structured output demonstration {#output-by-construction-demo}

**Document:** `demos/output-by-construction/README.md`
**Status:** Executable companion to the Q-053/N-048 nested-recursive-types work and the Q-035/Q-047 schema mechanism
**Last revised:** 2026-06-13

## What this demonstration is

A conventional generator emits a document — a string, a JSON blob, a DOM tree —
and validates it afterward, if at all. For the span between emission and
validation a malformed artifact exists, and a missing or incomplete validation
pass lets it leak to a consumer. Strand inverts the order. A structured document
is a typed value carrying a Schema, and the schema's invariant is checked
*before* the value is admitted into the store (verify time) or *before* it is
produced at runtime. A malformed document never reaches output: correctness is
structural, not a post-hoc lint.

Two properties make this precise, and this demonstration exercises both on
shipped material.

The first is that the document is a genuine nested composite, not a stringly
typed blob. The subject is a `JsonValue` whose `JsonArray` case carries a *real*
nested `List<JsonValue>` — a `Cons`/`Nil` spine of product cells, each cell a
typed `{head, tail}`. This is the precise model the Q-053 N-048
`RecursiveProjection` work makes constructible: the inner list is a true nested
μ whose `Cons.head` reaches the outer `JsonValue` binder, and every
value-construction site names a position inside the closed outer μ through a
`Case`/`Field`/`Unfold` projection path. It is not the flat spliced
tagged-variant workaround corpus 66 used, where an array's tail was typed as the
whole `JsonValue` sum and the spine's well-formedness was unprovable in the type.
Because the array is a genuine list, the schema invariant can walk it and
constrain it element-by-element.

The second is that the schema mechanism is enforced at both phases. The Q-035
verify-time `SchemaChecker` folds a statically-known value at admission and
rejects a violation before any execution. The Q-047 runtime obligation evaluates
the same invariant on a dynamically-computed value at the value-flow site and
halts the run before the malformed document is returned. A schema violation is
therefore caught at verify time or at runtime — never silently emitted.

The document type, abbreviated:

```
jsonValueT = mu jv. JsonNumber(Int)
                  | JsonArray( mu list. Cons(head: jv, tail: list) | Nil )
```

The `NonNegativeJsonArray` schema wraps the array's inner list with one
invariant, `all_elements_non_negative`: a `Fixpoint` `(array) -> Bool` that walks
the `Cons`/`Nil` spine and requires each element be a `JsonNumber` whose value is
at least zero. A document whose array contains a negative number is malformed.
The invariant body is an ordinary Strand expression — the same shape the JSON
blessed library's `unique_keys` invariant uses — and it reaches the genuine list
because N-048 gave the array a real list type to begin with.

The driver is an ordinary JVM caller of the shipped runtime: the Q-054
`StrandRuntime` facade (`verifyAndCheckSchema` for the verify-then-schema-check
pass, `run` for evaluation), the `HostPolicy` it takes, and the canonical
dag-json the demo admits. It introduces no language feature, no node category, no
encoding change, and no verifier rule. Every property the demonstration claims is
one the runtime enforces, and the assertion net
(`OutputByConstructionDemoTest`) protects each one from silently rotting.

The three documents are hand-authored as canonical dag-json under
[`programs/`](programs/). They are authored directly in the canonical form (not
Layer A) because N-048 `RecursiveProjection` has no Layer A sugar yet (deferred
under the N-048 proposal § 8); the shape mirrors corpus 88. Authoring them
directly isolates the demonstration's correct-by-construction property from the
separate question of how an agent generates the program.

## How to run it

From `impl-kotlin/`, print the transcript:

```sh
./gradlew :runtime:outputByConstructionDemo -q
```

Run the assertion-backed test that pins every property:

```sh
./gradlew :runtime:test --tests "org.strand.runtime.OutputByConstructionDemoTest"
```

The driver `OutputByConstructionDemo` and the test `OutputByConstructionDemoTest`
live in the `:runtime` test source set
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and share one body of
scenario code, so the printed demonstration and the regression net cannot
diverge. They stay in `:runtime` because they compile against the runtime
modules. The driver loads the committed canonical dag-json from
[`programs/`](programs/) through the test classpath (`runtime/build.gradle.kts`
copies the directory in via `processTestResources`), so the artifact the driver
runs is the content-addressed graph, not a human-facing projection.

## The scenarios

### W1 Well-formed document produced

The genuine N-048 array `[1, 2, 3]` carries the `NonNegativeJsonArray` schema.
The value is statically known, the verifier re-records it with the schema type,
and the `SchemaChecker` folds it and runs `all_elements_non_negative`, which
passes — so the graph verifies and evaluates to the document value. The driver
then walks the produced runtime value to prove it is a genuine nested
`List<JsonValue>`: a `SumV(Cons)` whose payload is a `ProductV` with `head` and
`tail` fields, recursively, terminating in `Nil`. The transcript prints the
structure, the element list read off the spine, and a JSON rendering produced by
walking the real structure. The point is that the array is a real list the schema
constrains element-by-element, not a flat blob.

### W2 Malformed caught at verify time

The same document with the middle element a statically-known negative literal,
`[1, -7, 3]`. The verifier re-records the array value with the schema type; the
Q-035 `SchemaChecker` folds the statically-known value at admission and runs the
invariant, which fails on `-7`. The graph is rejected at admission with the real
`VerifyError.SchemaInvariantViolation`, blaming the array's value-flow node and
naming the failed invariant — before any execution. No malformed artifact is
ever produced, because the value never leaves the verifier.

### W3 Malformed caught at runtime

The same document with the middle element computed at runtime as
`Int.Sub(3, 10) = -7`. Because `Int.Sub` is non-static, the verifier cannot fold
the array and records a `SchemaInvariantDeferred` informational diagnostic — the
graph verifies clean, so a verify-time check alone would let it through. At
runtime the interpreter materializes the array and the schema obligation fires at
the value-flow site, raising the real `InterpretError.SchemaInvariantViolation`
before the malformed document is returned. This is the Q-047 runtime path, which
is **interpreter-only**: the bytecode VM erases schemas pre-bytecode, so this
enforcement runs the tree-walking interpreter. The demonstration states this
caveat honestly and runs the interpreter path.

## Transcript

The transcript below is the output of `./gradlew :runtime:outputByConstructionDemo -q`.

```
========================================================================
Strand -- correct-by-construction structured output
Subject: a JsonValue document with a GENUINE N-048 nested array,
carrying a Schema whose invariant makes a malformed array unemittable.
========================================================================

Document type:
  jsonValueT = mu jv. JsonNumber(Int)
                    | JsonArray( mu list. Cons(head: jv, tail: list) | Nil )
Schema NonNegativeJsonArray over the array's inner list, invariant
  all_elements_non_negative: every element is a JsonNumber with value >= 0.
A conventional generator emits a document and validates AFTER; Strand
checks the invariant BEFORE the value is admitted (W2) or produced (W3),
so a malformed document never reaches output.

W1  Well-formed document produced -- a genuine N-048 nested array
------------------------------------------------------------------------
  Built via N-048 RecursiveProjection: a real List<JsonValue> spine.
    structure (runtime Value)   = Cons{head=JsonNumber(1), tail=Cons{head=JsonNumber(2), tail=Cons{head=JsonNumber(3), tail=Nil}}}
    is a genuine Cons/Nil list  = true
    Cons cells in the spine     = 3
    elements read off the spine = [1, 2, 3]
    rendered JSON (from struct) = [1,2,3]
  The value satisfies all_elements_non_negative, so it verifies and
  evaluates. The structure is a real nested list -- NOT a flat spliced
  blob -- so the schema can constrain it element-by-element.

W2  Malformed caught at VERIFY time -- statically-known value
------------------------------------------------------------------------
  Document [1, -7, 3]: the middle element is a static negative literal.
  The Q-035 SchemaChecker folds the statically-known value at admission
  and runs the invariant, which fails on -7.
    rejected before execution   = true
    verify-time error           = SchemaInvariantViolation
    blamed node (value-flow)    = #36
    failed invariant            = #70
    offending value             = SumV(case=Cons, payload=ProductV(fields={head=SumV(case=JsonNumber, payload=IntV(v=1)), tail=SumV(case=Cons, payload=ProductV(fields={head=SumV(case=JsonNumber, payload=IntV(v=-7)), tail=SumV(case=Cons, payload=ProductV(fields={head=SumV(case=JsonNumber, payload=IntV(v=3)), tail=SumV(case=Nil, payload=null)}))}))}))
  The malformed document is rejected at the door -- no artifact exists.

W3  Malformed caught at RUNTIME -- dynamic value (Q-047, interpreter-only)
------------------------------------------------------------------------
  Document [1, Int.Sub(3, 10), 3] = [1, -7, 3]: the middle element is
  computed at runtime, so the verifier cannot fold it -- it defers.
    verifies clean              = true
    array value-flow deferred   = true
  At runtime the obligation fires at the value-flow site before the
  document is returned:
    raised at runtime           = true
    runtime error               = SchemaInvariantViolation
    blamed node (value-flow)    = #35
    failed invariant            = #69
    offending value             = SumV(case=Cons, payload=ProductV(fields={head=SumV(case=JsonNumber, payload=IntV(v=1)), tail=SumV(case=Cons, payload=ProductV(fields={head=SumV(case=JsonNumber, payload=IntV(v=-7)), tail=SumV(case=Cons, payload=ProductV(fields={head=SumV(case=JsonNumber, payload=IntV(v=3)), tail=SumV(case=Nil, payload=null)}))}))}))
  The Q-047 runtime check is interpreter-only: the bytecode VM erases
  schemas pre-bytecode, so this enforcement runs the interpreter path.

========================================================================
What this demonstrates: correct-by-construction STRUCTURED output --
a malformed document cannot reach output because the schema invariant
is checked BEFORE the value is admitted (W2) or produced (W3), over a
genuine N-048 nested composite the schema constrains precisely.
NOT shown: the full HTML5/SVG blessed libraries (a separate roadmap
item this mechanism would underpin), first-pass correctness, or cost.
========================================================================
```

## What this demonstrates and what it does not

This demonstration shows correct-by-construction structured output: a malformed
document cannot reach output because the schema invariant is checked before the
value is admitted (W2) or produced (W3). The structured document is a genuine
N-048 nested composite — a real `List<JsonValue>` the schema constrains
element-by-element — so the constraint is carried precisely by the type and
checked by the invariant, not approximated by a stringly-typed validation pass
over an already-emitted blob. W1, W2, and W3 use one invariant; the difference is
only whether the value is statically known (rejected at admission) or computed at
runtime (rejected at the obligation site).

It does **not** build the full HTML5 and SVG blessed libraries. Those are a
separate roadmap item — element trees whose children are lists of elements, the
canonical nested-μ shape that N-048 was built to make constructible — and this
mechanism is exactly what would underpin them, but they are not built here. This
demonstration shows the correct-by-construction *mechanism* on shipped material:
the N-048 nested composite and the Q-035/Q-047 schema invariants, on a JSON-shaped
document. It does not show first-pass correctness — whether the document is the
one an author intended — nor execution cost.

The Q-047 runtime check (W3) is interpreter-only. The bytecode VM erases schemas
before lowering (Q-017), so a runtime-violating program raises under the
tree-walking interpreter but would run under the VM — a bounded divergence
documented in the runtime-schema-enforcement proposal. W3 runs the interpreter
path, and the demonstration states this plainly rather than implying VM parity.

A note on serialization. The optional round-trip-via-`Json.Stringify` scenario
was cut: the shipped `Json.Stringify` builtin recognizes only corpus 66's
*spliced* `JsonArrayCons`/`JsonArrayNil` model, not the precise N-048 nested
`JsonArray(list)` model (migrating `Json.*` to the precise model is deferred
under the N-048 proposal § 8). Rather than lean on a builtin that does not
support the precise model, W1 renders the array as JSON text by a driver-side
walk of the genuine runtime structure — which is itself the proof that the
correct-by-construction value yields correct output, taken straight off the real
nested list.

It is a demonstration, not a proof. The correct-by-construction property is argued
from the mechanisms — the N-048 nested type the schema can constrain precisely,
the verify-time `SchemaChecker`, and the Q-047 runtime obligation — with these
executed scenarios as spot-checks driving the mechanisms through the embedding
surface a host would actually use.

## References

**Outgoing references:**
- [`proposals/implemented/nested-recursive-types.md`](../../proposals/implemented/nested-recursive-types.md)
  — Q-053 / N-048 `RecursiveProjection`, the mechanism by which the document's
  `JsonArray` carries a genuine nested `List<JsonValue>` (the closed-outer-μ +
  `Case`/`Field`/`Unfold` path); corpus 88 is the worked reference whose shape
  the demonstration's documents reuse.
- [`proposals/implemented/schema-and-invariant.md`](../../proposals/implemented/schema-and-invariant.md)
  — Q-035 N-032 Schema + N-033 Invariant, the verify-time `SchemaChecker` that
  evaluates the invariant on the statically-known value W1/W2 carry and produces
  the `SchemaInvariantViolation` W2 exercises.
- [`proposals/implemented/runtime-schema-enforcement.md`](../../proposals/implemented/runtime-schema-enforcement.md)
  — Q-047 runtime evaluation of invariants on dynamic values, the
  `InterpretError.SchemaInvariantViolation` W3 exercises and the interpreter-only
  caveat the demonstration states.
- [`proposals/implemented/json-blessed-library.md`](../../proposals/implemented/json-blessed-library.md)
  — the shipped `JsonValue` schema and its `unique_keys` invariant, the
  precedent for the invariant body shape; its design note records the
  nested-array limitation N-048 closes and which this demonstration builds on.
- [`proposals/implemented/embeddable-runtime.md`](../../proposals/implemented/embeddable-runtime.md)
  — Q-054, the `StrandRuntime` facade (`verifyAndCheckSchema` / `run`) and
  `HostPolicy` the driver is built on.

**Incoming references:**
- [`demos/README.md`](../README.md) — index entry.
- [`INDEX.md`](../../INDEX.md) — changelog entry (2026-06-13).
