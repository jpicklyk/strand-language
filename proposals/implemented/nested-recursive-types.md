# Nested recursive types at value-construction sites

**Document:** `proposals/implemented/nested-recursive-types.md`
**Status:** Implemented (2026-06-13) — N-048 RecursiveProjection in the Kotlin/JVM reference implementation
**Date:** 2026-06-13
**Concerns:** [`design/node-algebra.md`](../../design/node-algebra.md) § Recursive types (N-041, N-042), [`design/canonical-encoding.md`](../../design/canonical-encoding.md) § Per-category encodings and § Epoch log, [`proposals/implemented/nested-recursive-self-depth.md`](nested-recursive-self-depth.md), [`proposals/implemented/stdlib-expansion-round-2.md`](stdlib-expansion-round-2.md), [`proposals/implemented/encoding-epochs.md`](encoding-epochs.md), [Q-053](../../open-questions.md#Q-053), [Q-026](../../open-questions.md#Q-026), [Q-029](../../open-questions.md#Q-029), [Q-047](../../open-questions.md#Q-047), [Q-049](../../open-questions.md#Q-049), [Q-062](../../open-questions.md#Q-062)
**Scope:** medium-large

## Implementation note

N-048 RecursiveProjection shipped in the Kotlin/JVM reference implementation on 2026-06-13, substantially as designed below. What shipped:

- The node category `Node.RecursiveProjection(recursiveType, path)` with a top-level `ProjectionStep` sealed class (Case / Field / Unfold); JSON ingest; the `childNodeIds` / `translateNodeIds` plumbing (only `recursiveType` is a NodeId edge); the persistent `NodeStoreCodec`.
- Canonical tag 48: `H(recursiveType)` then a positional path array, each step nested CBOR with the frozen `Case 0 / Field 1 / Unfold 2` discriminator, exactly as § 4.2 specifies. A `CanonicalEncodingSpecTest` byte trace and the `design/canonical-encoding.md` tag-48 entry pin it.
- The verifier `resolveType` RecursiveProjection case: a closed-ness check, then a path walk with one-step unfolds so an inner `RecursiveSelf(depth=N)` is bound by the time resolution reaches the focus. The six `VerifyError` variants of § 5 all shipped.
- New corpus 88-91 (true `List<JsonValue>` array, true JSON object entry-map, AST with a child list, HTML/SVG element tree-of-lists) and negative near-misses 27-32. The independent Python encoder reproduces all golden hashes.

The most consequential deviation from the proposal as written: **the N-048 tag landed additively under epoch 1, not as part of epoch 2.** Section 4.2 and § 9 framed tag 48 as riding the epoch-2 golden-hash regeneration and epoch bump (because the proposal bundled it with the optional-field normalization). Implementation showed the N-048 portion is purely additive — a brand-new category tag changes no existing program's hash — so it was decoupled: `CanonicalEncoding.EPOCH` stays 1, `corpus/golden-hashes.json` gains only the four new program entries (every 01-87 hash byte-identical), and the chartered epoch-2 normalization (gated optional fields to a uniform presence-prefix rule), the epoch bump, and the Q-049 `bound` decision remain the separate epoch-2 charter item. Where the text below says the change "rides epoch 2" or "regenerates every golden hash," read it as the original framing superseded by this additive landing.

Two smaller deviations record refinements the proposal under-specified:

- **Equirecursive equality at value-flow and pattern sites.** Section 7 scenario 11 ("equirecursive equality through projection") asserted a value built through a projection is assignable where the unfolded type is expected, but did not specify the mechanism. A `[Unfold]` projection resolves to the *unfolded* body, while the enclosing type a value flows into is often the *folded* `μ.T`; these are the same equirecursive type. The implementation gave `typesCompatible` (value-flow) and the Match pattern-type check a fold/unfold-up-to-one-step relaxation reusing the existing `unfoldRecursive`. The relaxation only widens acceptance — every previously-accepted program still type-checks and no hash moves — and strict `==` is preserved at the structural-equivalence sites (Match-body divergence, Fixpoint shape, Handler/state-machine signatures).
- **Closed-ness check ordering.** Section 5 lists "resolve recursiveType" as step 1 and the closed-ness check as step 2. Resolving an open μ's body aborts with the lower-level `UnboundRecursiveSelf` before the dedicated rule can run, so the implementation runs the structural closed-ness check *first*; an open target then yields the dedicated `RecursiveProjectionTargetNotClosed`. A non-Recursive target passes the closed-ness walk vacuously and is caught as `RecursiveProjectionTargetNotRecursive` next.

The § 8 path-canonicalization research question (whether a redundant leading `Unfold` should be forbidden) was not implemented as a rejection rule; the implementation hashes paths verbatim and the corpus never emits a redundant leading `Unfold` (the first step is always a `Case` or a single `Unfold`). Forbidding the redundant form remains a possible future tightening. Polymorphic recursive types, higher-arity μ binders, Layer A sugar for the projection, and migrating corpus 66's splice remain deferred as the proposal states.

This proposal designs the Q-053 portion of the chartered epoch-2 encoding change ([Q-062](../../open-questions.md#Q-062)): the mechanism by which an agent directly constructs a value whose type is a *nested* recursive type — a `μX. ... List<X> ...` shape such as a JSON value with real arrays and objects, an AST whose nodes carry child lists, or an HTML/SVG document as a tree of element lists. The N-042 `depth` field already in the codebase resolves nested μ for type-equality reasoning; this proposal closes the value-construction gap that the depth field could not, and in doing so unblocks the HTML5 and SVG blessed libraries deferred under Q-026 and Q-047. It defines the encoding budget Q-062's epoch-2 charter reserved for the Q-053 fix; optional-field normalization and the Q-049 `bound` decision are the charter's other two items and are out of this proposal's scope, coordinated with it only in that all three ride the same golden-hash regeneration.

## 1. Problem statement

A recursive type is introduced by `RecursiveType` (N-041), a positional μ-binder over its body; a back-edge to that binder is a `RecursiveSelf` (N-042) whose `depth` field is a de Bruijn index counting enclosing μ-binders, 0 = innermost ([`design/node-algebra.md`](../../design/node-algebra.md) § Recursive types). The verifier's `resolveType` maintains a `recursiveDepth` counter incremented for the duration of a RecursiveType body and decremented after; a `RecursiveSelf` is well-formed only when `0 <= depth < recursiveDepth`, and otherwise reports `UnboundRecursiveSelf` ([`impl-kotlin/.../Verifier.kt`](../../impl-kotlin/verifier/src/main/kotlin/org/strand/verifier/Verifier.kt), the `resolveType` RecursiveType/RecursiveSelf cases). The canonical encoder mirrors this exactly: its `EncodingKey` includes `currentRecDepth`, so a sub-graph that references an enclosing recursive binder hashes *differently at different depths*, in precise analogy to how a `VarRef` hashes differently under different binder stacks ([`design/canonical-encoding.md`](../../design/canonical-encoding.md) § Hashes are contextual).

The depth field works for type-level reasoning because type-equality always traverses a type *in context*: comparing `μjv. ...` against another `μjv. ...` enters both outer binders, so by the time the walk reaches an inner `RecursiveSelf(depth=1)` the counter is 2 and the reference resolves. Value construction has no such enclosing traversal. A `SumValue` or `ProductValue` carries an `ofType: NodeId` edge naming the type it inhabits, and the verifier resolves it by calling `resolveType(node.ofType, typeParams)` with `recursiveDepth = 0` — the construction site is not lexically inside any μ-binder. This is the exact failure the `nested-recursive-self-depth` proposal recorded at its § 3 pivot and `stdlib-expansion-round-2` recorded at its slice-3 pivot.

**What fails today, concretely.** The natural JSON model is one outer μ over a sum whose array case carries a list of the *outer* type:

```
jsonValueT = μ jv.
    JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String) |
    JsonArray( μ list. Cons(head: RecursiveSelf depth=1, tail: RecursiveSelf depth=0) | Nil )
```

The inner `Cons.head` is `RecursiveSelf(depth=1)` — reach past the inner `list` binder to the outer `jv`. To build the array `[1]` an agent emits `SumValue(ofType = innerListT, caseName = "Cons", payload = ...)` where `innerListT` is the inner `μ list. ...` node. The verifier resolves `innerListT` standalone: it enters exactly one RecursiveType (the `list` binder), so `recursiveDepth` reaches 1, the `Cons.head` reference at `depth=1` fails `depth < recursiveDepth`, and the whole construction aborts with `UnboundRecursiveSelf`. The inner μ has one canonical hash regardless of context, but its *meaning* — what `depth=1` points at — is not determined by the node alone; it depends on which outer μ encloses it. A standalone reference to it is therefore not well-defined, which is the same property that makes a bare `VarRef` standalone-meaningless and that the `NodeRefTargetMustBeClosed` rule already enforces for cross-store references.

**The splice the gap forces.** The shipped `JsonValueFull` (corpus 66, `66-json-value-nested.json`) sidesteps this by collapsing the inner list μ into the outer sum as flat tagged variants, all references staying at depth 0:

```
jsonValueT = μ jv.
    JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String) |
    JsonArrayCons(head: jv, tail: jv) | JsonArrayNil |
    JsonObjectCons(key: String, value: jv, tail: jv) | JsonObjectNil
```

The precision lost is the typing of `tail`. In the true model `tail` is the inner list type, so the only legal `tail` values are `Cons` or `Nil` — the type system rejects a malformed array whose tail is a bare `JsonNumber`. In the splice `tail: jv` is the *whole* sum, so `JsonArrayCons(JsonNumber(1), JsonString("x"))` type-checks: the type system can no longer distinguish a well-formed array spine from arbitrary nesting, and a bare `JsonArrayNil` or `JsonObjectCons` is itself a legal top-level `JsonValue` though it is meaningless outside an array context. Every recovered invariant (a `JsonArray`'s tail is always a list) has to be re-imposed as a runtime schema check rather than carried by the type. The same splice is the only reason HTML5 and SVG — element trees whose children are lists of elements, the canonical nested-μ shape — remain unshippable as blessed libraries (Q-026, Q-047): their splice would be combinatorially worse than JSON's, because an element has many child-bearing positions.

## 2. Prior art

- **Isorecursive vs equirecursive μ-types** (Pierce, *Types and Programming Languages*, ch. 20–21). Equirecursive systems treat `μX.T` and its unfolding `[X ↦ μX.T]T` as definitionally equal; isorecursive systems make the two distinct types mediated by explicit `fold`/`unfold` coercions at introduction and elimination. Strand is equirecursive and decides equality by canonical-hash identity, with the verifier's `unfoldRecursive` performing the one-step unfold when a Sum scrutinee or value-of-type is a `Recursive`. The relevant lesson is that introduction (value construction) is exactly the `fold` site: in an isorecursive calculus the `fold` annotation carries the *whole* `μX.T`, never a bare open sub-term, because the open sub-term has no standalone meaning. The mechanism below adopts that discipline — a construction site names the closed enclosing μ — without adopting isorecursion's runtime coercions.

- **de Bruijn indices vs named binders** (de Bruijn 1972; the basis of Strand's positional encoding). The depth field is a de Bruijn index, and the value-construction failure is the textbook hazard of de Bruijn indices: an index is only meaningful relative to a known enclosing-binder count, so a sub-term lifted out of its binding context must have its indices *shifted* or be carried together with that context. Standalone resolution of the inner μ is exactly "interpreting a de Bruijn term with the wrong ambient depth." The fix is to never lift the inner term out of its context at a construction site — carry the outer μ and re-enter it.

- **Unison's type representation.** Unison content-addresses definitions by a normalized hash of their syntax trees, with locally-nameless (de Bruijn-style) binders so alpha-equivalent definitions share a hash — the same design Strand uses for terms and types. Unison's data declarations are nominal at the surface but each constructor is identified by `(typeHash, constructorId)`: a constructor reference is always relative to its declaring type's hash, never a free-floating reference to an inner structural fragment. This is the same move the mechanism below makes: a value's `ofType` plus a projection path is `(enclosing-μ-hash, position)`, structurally analogous to `(typeHash, constructorId)`, giving every construction site a closed, content-addressed anchor.

- **Equirecursive type construction via "self" application** (the standard List/Tree encodings in Coq/Agda's positive inductive families, and in the System Fω μ literature). The introduction form for a value of nested-recursive type always supplies the value *as an inhabitant of the named recursive type*, and the inner self-reference is resolved by the type-checker re-entering the recursive binder, not by the programmer naming the open inner type. No mainstream system lets a value name a bare open μ-sub-term as its type; they all either inline (Strand's current splice) or carry the closed enclosing type (the mechanism below).

- **Why not nominal recursive types.** A nominal scheme (named type declarations, a value referencing its type by name) trivially solves the resolution problem — a name resolves to a fixed declaration regardless of position. Strand rejects this at the ADR level: ADR-001/ADR-002 forbid name-based identity, and a mutable name reintroduced as type identity would violate content-addressing. The mechanism must therefore recover the *convenience* of a nominal anchor (one stable thing a construction site points at) without a name — which is what a content-addressed projection path achieves.

## 3. Recommended approach

**Introduce a new node category, `RecursiveProjection` (N-048), that names a position inside a closed enclosing recursive type, and require value-construction `ofType` edges that target a nested-recursive component to use it.** A `RecursiveProjection` carries one edge to the *whole* enclosing `RecursiveType` (the closed outer μ, e.g. `jsonValueT`) and a positional `path` that selects the component being constructed (a case of the unfolded outer sum, then optionally a field, then optionally into a nested μ). Resolution enters the outer μ's binder context *before* walking to the selected component, so by the time the resolver reaches an inner `RecursiveSelf(depth=N)` the `recursiveDepth` counter is already `N+1` and the reference resolves deterministically. Because the node's only type edge is the closed outer μ, the projection is itself closed: it has one canonical hash and one canonical interpretation independent of where it is referenced, exactly as `NodeRefTargetMustBeClosed` demands of cross-store references.

This is the equirecursive `fold`-site discipline (§ 2, isorecursive prior art) realized as a node: a construction site stops naming a bare open inner μ and instead names `(closed outer μ, path to this position)`. The depth field stays exactly as shipped — it remains the back-edge representation *inside* a type, and `RecursiveProjection` is the front-door that makes a depth-bearing inner type reachable from a construction site. The two compose: `RecursiveProjection` supplies the binder context, `RecursiveSelf depth` is resolved within it.

The agent-facing model becomes: declare the composite type once as a single outer μ (with real nested μ-lists, full precision); at every construction site, point `ofType` at a `RecursiveProjection` of that outer μ selecting the position you are building. The lost precision is recovered — a JSON array's `tail` is typed as the inner list, so a malformed spine is a verify-time `CategoryMismatch`, not a deferred runtime schema check.

## 4. Detailed mechanism

### 4.1 Node category — N-048 RecursiveProjection

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-048 | RecursiveProjection | `recursiveType`(1) → RecursiveType (the closed outer μ), `path`(1) → ProjectionPath (content field) | A type expression denoting a component *position* inside `recursiveType`, resolved by entering the outer μ's binder context. Appears only in type position (any `ofType`, `paramType`, field type, or case payload type). It is closed iff `recursiveType` is closed; the verifier rejects a `recursiveType` that is not a self-contained μ (`RecursiveProjectionTargetNotClosed`). |

`recursiveType` must be an actual `RecursiveType` node (N-041), not an arbitrary type; a non-recursive target is `RecursiveProjectionTargetNotRecursive`. The `path` is a content field, not a child node — a non-empty ordered list of selector steps, each one of:

- `Case(caseName: String)` — after one unfold of the current μ to a Sum, select the named case; the current focus becomes that case's payload type (which may be a Product, a nested μ, or a `RecursiveSelf`).
- `Field(fieldName: String)` — when the current focus is a Product, select the named field's type.
- `Unfold` — when the current focus is a `RecursiveType` (a nested μ such as the inner list), enter it: increment the binder context and make the focus the unfolded body. This is the step that makes an inner `RecursiveSelf(depth=1)` resolvable, because it raises the live `recursiveDepth`.

A path of `[Unfold]` (or empty, treated as a single implicit unfold of the outer μ) selects the outer μ's own unfolded body — the common case where a value directly inhabits the outer type (e.g. constructing a top-level `JsonNumber`). The selector vocabulary is deliberately the three structural type constructors the algebra already has (Sum case, Product field, μ unfold); no path step can name a binder by anything but its structural position, so no mutable name enters type identity.

**Why this shape and not another.** The alternative of letting `ofType` keep targeting the bare inner μ and having the verifier *infer* the missing outer context is rejected (§ 8): the inner μ has no canonical standalone meaning, so any inference is a guess that breaks content-addressing's "one canonical interpretation" rule. The alternative of a richer `RecursiveSelf` that carries an absolute binder snapshot is rejected because it would make `RecursiveSelf` context-dependent in its *content*, defeating the positional encoding. `RecursiveProjection` instead keeps both N-041 and N-042 exactly as they are and adds a single closed front-door node — the minimal change that gives every construction site a deterministic anchor.

### 4.2 Canonical encoding — epoch-2 change

`RecursiveProjection` is assigned category tag **48**. This is the Q-053 payload of epoch 2; it is additive (a new tag) and would on its own preserve all existing hashes. **However, epoch 2 also normalizes the gated optional fields and may carry the Q-049 `bound` removal (both out of this proposal's scope), so the epoch-2 commit regenerates every golden hash regardless.** This proposal therefore states plainly: the N-048 tag rides epoch 2, the golden regeneration is part of epoch-2 execution, and the epoch field in `corpus/golden-hashes.json` and `corpus/prelude-manifest.json` bumps from 1 to 2 in that same commit per [Q-062](../../open-questions.md#Q-062).

RecursiveProjection (48) encodes as the field sequence following the four tag bytes:

```
H(recursiveType)            -- hash reference to the outer μ, computed in the current binder context
array( step_1, ..., step_n )  -- the path, declaration order (positional)
```

where each `step` is well-formed nested CBOR:

```
step = array( uint(0), bytes(utf8(caseName)) )    -- Case
     | array( uint(1), bytes(utf8(fieldName)) )    -- Field
     | array( uint(2) )                            -- Unfold
```

The path is positional (order is semantically load-bearing — `Case` then `Field` differs from `Field` then `Case`), so it is *not* hash-sorted. The selector-tag discriminator (`Case 0, Field 1, Unfold 2`) is frozen on assignment per the § Discriminators convention. `RecursiveProjection` introduces no binder of its own — the binder motion is entirely the resolver's, driven by the `Unfold` steps — so the `recursiveType` child hashes in the surrounding context exactly like any other `H(c)` reference.

Two `RecursiveProjection` nodes are the same node iff they have hash-identical `recursiveType` and identical `path`. Because `recursiveType` is required to be closed, its hash is context-independent, so a projection's hash is context-independent: the property that makes the construction site's anchor deterministic is enforced structurally by the closed-target rule, then realized in the encoding by the plain `H(recursiveType)` reference.

### 4.3 Worked example

Build the JSON array `[1]` — a one-element array of the integer 1 — against the true nested model. Let `jsonValueT` be the outer μ:

```
jsonValueT = μ jv.  JsonNull | JsonBool(Bool) | JsonNumber(Int) | JsonString(String) |
                    JsonArray( innerListT )
innerListT = μ list. Cons(head: RecursiveSelf depth=1, tail: RecursiveSelf depth=0) | Nil
```

The integer `1` as a `JsonValue`:

- `ofType = RecursiveProjection(recursiveType = jsonValueT, path = [Unfold])` — selects the outer μ's unfolded sum.
- `SumValue(ofType = that projection, caseName = "JsonNumber", payload = IntLit(1))`.

The element list `Cons(JsonNumber(1), Nil)`:

- The list type is `RecursiveProjection(recursiveType = jsonValueT, path = [Case("JsonArray"), Unfold])`: unfold the outer μ to its sum, select `JsonArray` (focus becomes `innerListT`, a μ), `Unfold` it. During resolution the outer-μ unfold raises `recursiveDepth` to 1 and the `Unfold` of `innerListT` raises it to 2, so the `Cons.head` reference at `depth=1` resolves to the outer `jv` and `Cons.tail` at `depth=0` to `innerListT` — both bound, no abort.
- `Cons.head` is the `JsonNumber(1)` `SumValue` above; `Cons.tail` is `SumValue(ofType = RecursiveProjection(jsonValueT, [Case("JsonArray"), Unfold]), caseName = "Nil", payload = none)`.
- The whole `[1]` is `SumValue(ofType = RecursiveProjection(jsonValueT, [Unfold]), caseName = "JsonArray", payload = the Cons value)`.

Encoding the list-type projection: tag `00 00 00 30` (48), then `58 21 <H(jsonValueT)>` (the closed outer μ's 33-byte hash), then the path array `82` (2 elements) followed by `82 00 49 4a 73 6f 6e 41 72 72 61 79` (`Case "JsonArray"`: array-2, `uint(0)`, `bytes` of the 9 UTF-8 bytes of `JsonArray`) and `81 02` (`Unfold`: array-1, `uint(2)`). The verifier resolves this projection to `TypeExpr.Recursive(Cons(head: RecursiveSelf(1), tail: RecursiveSelf(0)) | Nil)` with the outer-μ context attached, and `SumValue` type-checks `Cons`/`Nil` against that. Constructing `JsonArrayCons(JsonNumber(1), JsonString("x"))` is now impossible: `tail` must inhabit the inner list, and `JsonString` does not, so the construction is a verify-time `CategoryMismatch`.

## 5. Verifier rules

The verifier's `resolveType` gains a `RecursiveProjection` case and the value-construction sites (`SumValue`/`ProductValue` `ofType` resolution, `Pattern.patternType`, `ParameterDecl.paramType`, field and case-payload types) accept it transparently because they already call `resolveType`.

`resolveType(RecursiveProjection p)`:

1. Resolve `p.recursiveType`. If it does not resolve to a `TypeExpr.Recursive`, report `RecursiveProjectionTargetNotRecursive(at)` and abort.
2. Run a closed-ness check: the resolved `recursiveType` must reference no binder outside itself (no free `VarRef`, no `TypeParameter` reference unbound within it, no `RecursiveSelf` whose depth escapes its own μ-nesting). If open, report `RecursiveProjectionTargetNotClosed(at)`. This is the type-position analogue of `NodeRefTargetMustBeClosed` and is what guarantees the projection's context-independence.
3. Walk `p.path` from the resolved outer `Recursive`, maintaining a local recursive-binder depth seeded so the outer μ is at the base. For each step, with `focus` starting as the outer `Recursive`:
   - `Unfold`: require `focus` is a `Recursive`; set `focus` to its one-step unfold, incrementing the live binder depth so inner `RecursiveSelf` references resolve. A non-`Recursive` focus is `RecursiveProjectionPathStepMismatch(at, step)`.
   - `Case(name)`: require `focus` (after an implicit unfold if it is still a bare `Recursive`) is a `Sum` containing `name`; set `focus` to that case's payload type (a nullary case is `RecursiveProjectionPathSelectsNullaryCase(at, name)`). A missing case is `RecursiveProjectionCaseNotFound(at, name)`; a non-Sum focus is `RecursiveProjectionPathStepMismatch`.
   - `Field(name)`: require `focus` is a `Product` containing `name`; set `focus` to that field's type. A missing field is `RecursiveProjectionFieldNotFound(at, name)`; a non-Product focus is `RecursiveProjectionPathStepMismatch`.
4. The resolved type is `focus`, carried *with* the accumulated binder context, so subsequent `RecursiveSelf` references inside it are already bound. An empty path is treated as a single `Unfold` of the outer μ.

Existing rules are unchanged: `UnboundRecursiveSelf` still fires for a `RecursiveSelf` whose depth escapes the live context — but inside a correctly-pathed projection the context is now deep enough, so the well-formed nested-μ construction no longer trips it. `NonContractiveRecursiveType` is unaffected. The contractivity check on `recursiveType` runs when that μ is itself resolved, before any projection uses it.

New `VerifyError` variants: `RecursiveProjectionTargetNotRecursive`, `RecursiveProjectionTargetNotClosed`, `RecursiveProjectionPathStepMismatch`, `RecursiveProjectionCaseNotFound`, `RecursiveProjectionFieldNotFound`, `RecursiveProjectionPathSelectsNullaryCase`. All carry the projection's `NodeId` and the offending step or name for the Q-051 annotated-feedback path.

## 6. Interpreter / runtime semantics

`RecursiveProjection` is a *type-position* node only; it never evaluates to a runtime value and never appears in term position. The interpreter and the bytecode VM treat it exactly as they treat any other resolved type: types are erased before evaluation (the VM erases schemas and types pre-bytecode, the tree-walker carries them only for verification and runtime-schema obligations). Construction of a `SumValue`/`ProductValue` whose `ofType` is a projection proceeds identically to today — the runtime builds a `SumV(caseName, payload)` / `ProductV(fields)` and never inspects the projection. Pattern matching against such a value is unchanged: `Match` already unfolds a `Recursive` scrutinee one step (`unfoldRecursive`) and dispatches on `caseName`, and the projection-resolved type unfolds the same way.

The one runtime-adjacent consequence is positive: because the nested-μ type now carries true precision, the verifier rejects malformed-spine constructions statically that the spliced model could only catch (if at all) via a runtime schema invariant. Programs that previously leaned on a `unique_keys`/`well_formed_array` schema invariant to recover precision can drop those invariants where the type now carries the constraint; existing invariants remain valid and are not required to change.

Replay determinism (Q-065) is unaffected: no new builtin, no new effect, no nondeterminism. The harm bound (Q-044) is unaffected: `RecursiveProjection` declares no effects and `closureOf` is unchanged.

## 7. Test scenarios

1. **True JSON array round-trips with precision** — `jsonValueT` with `JsonArray(innerListT)`; construct `[1, 2]` via projections; verify it type-checks, and `Json.Stringify` over it produces `[1,2]`. Replaces corpus 66's splice as the precise model.
2. **Malformed array spine is a verify error** — construct `JsonArray(Cons(JsonNumber(1), JsonString("x")))`; expect `CategoryMismatch` at the `tail` position, because `JsonString` does not inhabit `innerListT`. This is the precision the splice cannot express.
3. **True JSON object** — `JsonObject(innerEntriesListT)` where each entry is `{key: String, value: jv}`; construct `{"k": 1}`; verify the nested Product-inside-list-inside-outer-μ resolves and type-checks.
4. **AST with child lists** — `μ ast. Lit(Int) | Node(children: List<ast>)`; construct `Node([Lit(1), Lit(2)])`; verify the `children` list typed as `List<ast>` accepts `Lit` elements and rejects a non-`ast` element.
5. **HTML5/SVG element tree shape (representative)** — a minimal `μ el. Text(String) | Element(tag: String, children: List<el>)`; construct a two-level tree; verify it resolves, unblocking the Q-026/Q-047 library shape end to end.
6. **Top-level inhabitant via empty/`[Unfold]` path** — construct a bare `JsonNumber(1)` as a `JsonValue` via `RecursiveProjection(jsonValueT, [Unfold])`; verify the common non-nested case still works through the new node.
7. **Projection target not recursive** — `RecursiveProjection` whose `recursiveType` resolves to a plain `SumType`; expect `RecursiveProjectionTargetNotRecursive`.
8. **Projection target not closed** — `recursiveType` is a μ whose body references an outer binder it does not itself bind; expect `RecursiveProjectionTargetNotClosed`.
9. **Path case/field not found** — `path = [Case("Nope")]` on `jsonValueT`; expect `RecursiveProjectionCaseNotFound`. A `Field` step on a Sum focus; expect `RecursiveProjectionPathStepMismatch`.
10. **Canonical-encoding byte trace** — the § 4.3 list-type projection encodes to the stated bytes; assert verbatim in `CanonicalEncodingSpecTest`, and confirm two projections with reordered paths hash differently (positional path).
11. **Equirecursive equality through projection** — a projection-resolved inner list type and the same inner list reached by unfolding the outer μ in a type-equality comparison hash-equal, so a value built via the projection is assignable where the unfolded type is expected.
12. **VM equivalence** — a program constructing and matching a true nested-μ JSON value evaluates identically on the tree-walker and the bytecode VM (`VmEquivalenceTest`), since types are erased and only `caseName`/payload structure drives evaluation.

## 8. Tradeoffs and open questions

The committed mechanism is a new closed front-door node (`RecursiveProjection`) over the unchanged N-041/N-042 pair. The principal alternative considered and rejected:

**Rejected: verifier-inferred outer context (no new node).** Let `ofType` keep targeting the bare inner μ and have `resolveType` search the surrounding graph for an enclosing μ that "would make the depth references resolve," then resolve under that. This needs no encoding change. It is rejected because it violates content-addressing's central rule that a type has *one* canonical interpretation: the inner μ has no canonical standalone meaning (its `depth=1` reference is unresolvable in isolation), and there may be zero, one, or several outer μ candidates in a graph, so the inference is either a guess or position-dependent — precisely the "depth-N reference depends on traversal context" defect Q-053 names. It also reintroduces a context-dependent hash for the construction site, breaking the `NodeRefTargetMustBeClosed` invariant that cross-store references rely on. `RecursiveProjection` makes the outer context *explicit and closed* instead of inferred, which is the only way to keep the resolution deterministic and the hash context-independent.

**Rejected: bless the splice as the documented lowering.** Keep the flat spliced shape (corpus 66) as the canonical model and add authoring-layer tooling that generates it and re-imposes the lost precision as schema invariants (the third candidate direction Q-053 names). Rejected because it permanently relocates a *type-system* guarantee into *runtime* schema checks: a malformed array spine becomes a runtime `SchemaInvariantViolation` instead of a verify-time `CategoryMismatch`, weakening the structural-safety lead claim (Q-044) for every composite recursive value, and it does not scale to HTML/SVG where the splice is combinatorially large. It is a lowering this mechanism could still *emit* as an optimization, but it is the wrong canonical model.

**Rejected: higher-arity μ binders.** A single μ binding several mutually-recursive types at once (named as a possible extension under Q-029 and Q-053) would also express the JSON shape. Rejected for this slice because it is a larger algebra change to N-041 (the binder becomes a vector, the depth field becomes a `(depth, index)` pair, contractivity generalizes) and is not required: single-arity μ with `RecursiveProjection` covers JSON, ASTs, and HTML/SVG. It remains the right tool for genuinely mutual recursion and is deferred, not foreclosed — `RecursiveProjection`'s path vocabulary is forward-compatible with a future `(depth, index)` self-reference.

**Deferred intentionally:**

- **Polymorphic recursive types** — `Forall<T>. μX. ... List<X> ... T ...`, i.e. a generic `List<T>` / `Tree<T>` as a single reusable definition rather than a monomorphic μ per element type. Out of scope; the depth field's original note flagged this as the place its expressiveness eventually pays off, and it composes with `RecursiveProjection` (the projection target becomes a `ForallType` over a μ) but needs its own type-application-at-construction design pass.
- **Authoring-layer (Layer A) sugar for `RecursiveProjection`** — agents emit the canonical projection form directly in this slice; a density sugar (e.g. `RP <outer> .JsonArray ^`) is a follow-up density slice, mirroring how the depth field shipped without Layer A sugar.
- **Migrating corpus 66 / `Json.Parse` / `Json.Stringify` to the precise model** — corpus 66 stays as the historical splice exemplar; a new corpus program demonstrates the precise model, and the `Json.*` builtins migrate to it as a follow-up once the node lands (they walk `caseName`/payload, so the change is mechanical).

**Real research questions:**

- *Path canonicalization* — whether two paths that select the same position by different routes (e.g. an explicit leading `Unfold` vs. an implicit one) should be normalized to a single canonical path before hashing, or kept distinct. The proposal treats an empty path as one implicit outer unfold and otherwise hashes paths verbatim; if surface tooling can generate both forms for one position, a normalization pass (or a verifier rule forbidding the redundant leading `Unfold`) avoids two hashes for one type. Recommended resolution at implementation: forbid a redundant leading `Unfold` (the outer unfold is always implied by the first `Case`), so each position has one canonical path.

## 9. Implementation sketch

This is **epoch-2 execution work** ([Q-062](../../open-questions.md#Q-062)). The commit that lands it regenerates `corpus/golden-hashes.json` (every root hash moves) and `corpus/prelude-manifest.json`, bumps the `"epoch"` field and `CanonicalEncoding.EPOCH` from 1 to 2, updates the Python conformance encoder's mirrored `CANONICAL_ENCODING_EPOCH`, and updates the `design/canonical-encoding.md` Epoch log with an epoch-2 entry plus the new tag-48 layout and selector discriminator. It must be coordinated with — but does not itself implement — the charter's other two epoch-2 items: optional-field normalization (`effectProjections`, EventStream `source`) and the Q-049 `bound` decision if encoding-touching. Those land in the same epoch-2 commit per their own resolutions; this proposal owns only the N-048 portion.

| File | Change | Size |
|------|--------|------|
| `INDEX.md` | register N-048 RecursiveProjection in the identifier registry; bump the node range; changelog entry | Small |
| `design/node-algebra.md` | N-048 row in the Types section; a paragraph on value construction over nested recursion; References update | Small |
| `design/canonical-encoding.md` | tag-48 row; per-category RecursiveProjection layout; selector-tag discriminator; epoch-2 log entry | Small |
| `impl-kotlin/core/.../Node.kt` | new `Node.RecursiveProjection(recursiveType, path)` sealed-class variant; `ProjectionPath`/`ProjectionStep` data types | Small |
| `impl-kotlin/.../JsonIngest*.kt` | parse the new node and its path content field; childNodeIds/translateNodeIds walk the `recursiveType` edge | Small |
| `impl-kotlin/hashing/.../CanonicalEncoder.kt` | `encodeRecursiveProjection`; tag 48; positional path encoding | Small-Medium |
| `impl-kotlin/hashing/.../CategoryTag.kt` | `val RecursiveProjection = CategoryTag(48)` | Small |
| `impl-kotlin/hashing/.../Hasher.kt` | walk the `recursiveType` child; the projection itself gets a standalone hash (it is closed) | Small |
| `impl-kotlin/verifier/.../TypeExpr.kt` | a resolved-form representation if needed (likely none — resolution yields an ordinary `TypeExpr` with context attached) | Small |
| `impl-kotlin/verifier/.../Verifier.kt` | `resolveType` RecursiveProjection case; closed-ness check; path walk with binder-depth motion | Medium |
| `impl-kotlin/verifier/.../VerifyError.kt` | six new variants | Small |
| `impl-kotlin/.../NodeRefAnnotator` (cli) | annotate the new error variants' `#N` references (Q-051) | Small |
| `corpus/` | new corpus programs: precise nested-μ JSON value, AST-with-child-lists, element-tree (HTML/SVG shape); negative near-misses for `corpus/negative/` (Q-066) | Medium |
| `corpus/golden-hashes.json`, `corpus/prelude-manifest.json` | regenerated under epoch 2; `"epoch": 2` | Small (mechanical, large diff) |
| `evaluation/conformance/` (Python encoder) | tag-48 encoding; bump `CANONICAL_ENCODING_EPOCH` to 2; reproduce regenerated goldens | Small-Medium |
| `impl-kotlin/.../CanonicalEncodingSpecTest`, `VerifierTest`, `MatchExhaustivenessTest`, `VmEquivalenceTest`, `CorpusGoldenHashTest` | byte trace; the twelve § 7 scenarios; epoch assertion | Medium |
| `evaluation/dynamic/prompts/strand-system*.md`, strand-author skill references | document the precise nested-μ construction pattern; remove the nested-μ caveat | Small |

**Order of work.** (1) Land N-048 in the algebra + encoding spec + INDEX. (2) Add the node, ingest, encoder, hasher. (3) Add the verifier resolution and error variants with unit tests against hand-built nested-μ programs (no corpus, no golden touch yet — this proves the resolution before any hash moves). (4) Coordinate the epoch-2 cutover: regenerate goldens with the N-048 corpus programs *and* the optional-field normalization *and* the Q-049 removal in one commit, bump the epoch, update the Python encoder and the spec Epoch log. (5) Migrate `Json.*` and documentation as a follow-up.

**Not in this slice.** Polymorphic recursive types; higher-arity μ binders; Layer A sugar for the projection; migrating corpus 66's splice (the new precise corpus programs are additions, corpus 66 stays as the historical exemplar). The optional-field normalization and Q-049 `bound` decision are epoch-2 co-travelers owned by their own resolutions, not by this proposal.

## References

**Outgoing references:**
- [`design/node-algebra.md`](../../design/node-algebra.md) — N-041 RecursiveType, N-042 RecursiveSelf, the value-construction nodes (N-037/N-040) whose `ofType` this extends, and the equirecursive-equality model the projection composes with
- [`design/canonical-encoding.md`](../../design/canonical-encoding.md) — the contextual-hash and positional-binder rules this fix respects; § Epoch log, whose epoch-2 charter this proposal's encoding change fills
- [`proposals/implemented/nested-recursive-self-depth.md`](nested-recursive-self-depth.md) — the N-042 depth field that shipped as foundational infra and recorded the value-construction failure this proposal closes
- [`proposals/implemented/stdlib-expansion-round-2.md`](stdlib-expansion-round-2.md) — the corpus-66 JsonValueFull splice workaround and the precision it loses
- [`proposals/implemented/encoding-epochs.md`](encoding-epochs.md) — the Q-062 epoch policy and the epoch-2 charter this proposal's change rides
- [`open-questions.md`](../../open-questions.md) — Q-053 (this proposal resolves it; Resolved as of the 2026-06-13 N-048 landing), Q-026, Q-029, Q-047, Q-049, Q-062
- [`design/canonical-encoding.md`](../../design/canonical-encoding.md) — § Types, the tag-48 RecursiveProjection encoding entry added by the implementation

**Incoming references:**
- [`open-questions.md`](../../open-questions.md) — Q-053 points at this proposal
- [`design/canonical-encoding.md`](../../design/canonical-encoding.md) — § References cites this proposal as the tag-48 source
- [`proposals/README.md`](../README.md) — implemented-proposals row
- [`impl-kotlin/CLAUDE.md`](../../impl-kotlin/CLAUDE.md) — Known gaps section
