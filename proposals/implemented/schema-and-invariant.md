# Layer 7: Schema and Invariant (N-032, N-033)

**Document:** `proposals/implemented/schema-and-invariant.md`
**Status:** Implemented (Layer 7 step 1 of the Kotlin/JVM reference implementation, 2026-05-24)
**Date:** 2026-05-24 (proposed); 2026-05-24 (implemented)
**Concerns:** [`decisions/ADR-009-structured-outputs.md`](../../decisions/ADR-009-structured-outputs.md), [`design/rendering-and-views.md`](../../design/rendering-and-views.md), [`design/node-algebra.md`](../../design/node-algebra.md), [`design/security-model.md`](../../design/security-model.md), [Q-025](../../open-questions.md#Q-025), [Q-026](../../open-questions.md#Q-026), [Q-027](../../open-questions.md#Q-027), [Q-028](../../open-questions.md#Q-028), [Q-035](../../open-questions.md#Q-035)
**Scope:** Medium-large for step 1; reference output libraries and the live-view runtime are subsequent shipping steps

> **Implementation note (2026-05-24).** The proposal was executed as-described with three implementation choices worth recording. (1) The proposal's literal canonical encoding for Invariant (`[tag=33, invariantName, targetSchema hash, body hash]`) would produce a Schema↔Invariant hash cycle (Schema encodes invariants by hash; Invariant encoding would include the parent Schema by hash → infinite recursion in `CanonicalEncoder.hash`). The implementation drops `targetSchema` from the canonical encoding entirely: Invariant hashes as `[tag=33, invariantName, body hash]`. This matches the pattern N-009 ProductTypeField and N-011 SumTypeCase use — the parent-child relationship is recoverable from the parent's `fields` / `cases` list, not from the child's encoding. The `targetSchema` field is retained on the in-memory ADT for the verifier's `InvariantTargetMismatch` defensive topology check, which still catches the most common authoring mistake (an Invariant wired into one Schema's invariants edge while pointing its `targetSchema` at a different Schema). (2) Schema is implemented as a first-class `TypeExpr.SchemaType(schemaId, valueType, invariants)` variant per the proposal's recommendation; type-equality for SchemaType uses structural equality on `valueType` and the *set* of invariants (the `schemaId` is ignored, matching how Product/Sum ignore their `origin`). To make assignment compatible in both directions (a plain-T value into a SchemaType<T> position, and a SchemaType<T> value into a plain-T position), the verifier introduces a `typesCompatible` helper at the three value-flow sites (Application argument, ProductFieldValue, SumValue payload). Other equality sites (Match case body divergence, Fixpoint body shape, Handler signature agreement, StateMachine signature shape) keep strict `==` semantics — those are structural-equivalence checks, not assignment-compatibility. (3) When a plain-T value flows into a SchemaType position, the verifier re-records the value's NodeId with the SchemaType (overwriting the prior `record(valueId, T)` from `infer`) so the SchemaChecker's `nodeTypes` walk picks it up. This is the seam between the verifier's pure-type pass and the SchemaChecker's invariant-evaluation pass — the SchemaChecker has no other channel for "which values flow into which schema-typed positions". See `impl/CLAUDE.md` Layer 7 step 1 notes for the JSON schema and the `SchemaChecker.check()` API.

ADR-009 and `design/rendering-and-views.md` commit Strand to a schema mechanism: libraries declare types whose well-formedness the verifier checks at graph-construction time. The algebra grows by two node categories — Schema (N-032) and Invariant (N-033) — and the verifier gains a dispatch protocol for invariant evaluation. This proposal is the first implementation slice: the node ADT, canonical encoding, a verifier extension that evaluates pure-expression invariants on statically-known values, and one synthetic demonstration schema. The blessed output libraries (HTML5, SVG, JSON, PDF, plain text, Markdown), ForeignNode-backed checkers, provenance manifests, and live-view composition with state machines are explicitly deferred to subsequent steps.

## 1. Problem statement

Strand programs construct values that downstream systems consume — HTML pages, SVG diagrams, JSON API responses, PDF reports — and the language today gives the verifier no leverage over those values. A `ProductValue` typed as an `HtmlElement` is structurally a product; whether it satisfies HTML5 well-formedness (every `img` has alt text, paragraphs are not nested, the document has a head and body) is invisible to the verifier. Errors of this class surface as malformed bytes at the rendering boundary or as unreadable artifacts in production, rather than as verification failures at graph-construction time. For agent-generated UI, where the agent's contract is precisely that the output is correct, runtime-only enforcement is too late.

ADR-009 settles the architectural question — Strand provides a schema mechanism through node categories N-032 (Schema) and N-033 (Invariant); output formats are libraries, not language primitives — and `design/rendering-and-views.md` specifies the mechanism at the design level. What does not yet exist is an implementation: the ADT additions, canonical encoding, verifier rules, and a worked example. The reference implementation has reached Layer 6 step 1 (synchronous state-machine runtime) and Layer 6 step 2 is proposed; Layer 7 (schemas + invariants + first blessed library + provenance + live views) is the next major layer in the milestone plan, and it has not been started.

This proposal scopes the **first** implementation slice. The minimum coherent thing that demonstrates the mechanism end-to-end: the two new node categories, pure-expression invariants only (no ForeignNode checkers yet), verify-time evaluation on statically-known values (literals, constant products and sums), and one synthetic schema (a `PositiveInt` or `BoundedList`) used as a corpus example. The reference output libraries, accessibility/CSP invariants over real HTML, provenance manifests, and live views are subsequent shipping steps in their own right.

## 2. Prior art

- **Refinement types (Liquid Haskell, F\*, Refinement ML)** — types refined by logical predicates checked by SMT solvers at type-checking time. Powerful; requires a decision procedure that grows with the type system's complexity. ADR-009 § Alternatives considered rejects the full refinement-type generalization as too costly for the reference implementation but acknowledges it as the future direction. The schema mechanism is a constrained subset.
- **JSON Schema** — a declarative description language for JSON document shapes, with a validation algorithm that checks documents against schemas at consumption time (not construction time). The boundary between schema-as-description and runtime-validation is exactly what Strand's mechanism inverts: schemas as construction-time well-formedness contracts.
- **Static type providers (F#)** — types generated at compile time from external schema sources (database tables, XML schemas, JSON samples). The generator-as-library pattern resembles Strand's library-supplied schema, though F# does so via code generation rather than a uniform extension point.
- **XSD (XML Schema Definition)** — a long-standing schema language for XML documents with structural and value-range invariants. XSD's complexity (occurrence constraints, derivation by restriction and extension, identity constraints) demonstrates how rich invariant languages get when they try to be expressive; Strand's invariant body is just a Strand expression, which is simpler and more uniform.
- **OCaml's `[@@deriving]` and Haskell's `deriving` with newtypes** — type-level distinctions enforced by the type system through wrapper types whose invariants are maintained by smart constructors. The library-author-supplies-the-invariant pattern parallels Strand's library-supplies-the-schema.

Strand's schema mechanism tracks the refinement-type tradition with two simplifying constraints: invariants are pure Strand expressions (no embedded SMT theory), and the first implementation evaluates them only on statically-known values (no symbolic evaluation). The mechanism remains opt-in: programs that don't reference schemas are unaffected.

## 3. Recommended approach

**Two new node categories, N-032 Schema and N-033 Invariant**, per ADR-009. No third "claim" node is introduced; instead, **Schema may appear in any type position**, acting as a refinement of its declared `valueType`. When the verifier resolves a type position to a `TypeExpr` and the resolution yields a Schema, the verifier produces a new `TypeExpr.SchemaType(schemaId, valueType, invariants)` that downstream type-equality checks treat as equal to its `valueType` when assigning *into* the schema (structural assignment) and uses the `invariants` list to evaluate well-formedness when a constant value flows *into* a schema-typed position.

**Pure-expression invariants only.** The `body` of an Invariant is a Strand expression of function type `(valueType) -> Bool` — usually a Lambda, possibly a VarRef to a Lambda bound at a Let. ForeignNode-backed checkers are deferred to step 2 (when the trust model from `design/security-model.md` is extended to checker bindings).

**Verify-time evaluation on statically-known values.** A Strand expression is *statically known* if it is built from literals (IntLit, BoolLit, etc.), ProductValues whose fields are all statically known, SumValues whose payloads are all statically known, or a Let-binding chain whose final value is statically known. When such a value is type-checked against a Schema, the verifier evaluates each invariant body on the value at verify time and rejects the graph with `SchemaInvariantViolation` if any returns false. For non-statically-known values (function parameters, function results, anything depending on a runtime computation), step 1 accepts the schema claim **without proof** — the verifier flags this as a `SchemaInvariantDeferred` informational diagnostic rather than an error. Step 2 will lift the bar by adding symbolic evaluation; step 3 (much later) is where the full refinement-type generalization would land.

**Schema and Invariant are content-addressed nodes.** Two schemas with identical `valueType` and identical invariant lists are the same schema. Two invariants with identical `targetSchema` and identical `body` are the same invariant. This is the standard ADR-003 property; it is what lets the verifier resolve checker bindings unambiguously by hash, and what makes "two libraries claiming the same schema name" structurally distinct (their `libraryBinding` metadata differs, or their valueType/invariants differ).

**One synthetic corpus schema for step 1.** A `PositiveInt` schema demonstrates the mechanism: valueType is `Int`, single invariant `(x: Int) -> Bool { x > 0 }`. The corpus program constructs `IntLit(5)` claimed as PositiveInt (verifier accepts) and `IntLit(-3)` claimed as PositiveInt (verifier rejects with the precise invariant identifier). A second program demonstrates a structural invariant over recursive types (a `NonEmptyList` schema with invariant `count > 0`). Reference output libraries (HTML5, SVG, JSON, PDF, plain text, Markdown — see § 8) are deferred.

**New `schema/` module.** The invariant-evaluation phase lives in a new Gradle module that depends on `verifier` and `interpreter`, so it can call `Interpreter.applyCallable` to evaluate invariant bodies on values. The phase runs after the verifier's type-checking pass and before the interpreter's top-level `eval`. The CLI's `strand verify` and `strand run` subcommands invoke this phase as part of the pipeline.

## 4. Detailed mechanism

### 4.1 Node category additions

**N-032 Schema.** Edges and content fields per `design/rendering-and-views.md` § Schema (N-032):

| Field | Multiplicity | Target | Role |
|-------|--------------|--------|------|
| `schemaName` | 1 (content) | String (structural) | Library-scoped identifier — included in canonical encoding |
| `valueType` | 1 | Type | The structural type values must inhabit |
| `invariants` | * | Invariant | Predicates the verifier checks |
| `libraryBinding` | 0..1 | Provenance (metadata) | Origin and trust information; excluded from canonical encoding |

`libraryBinding` is intentionally metadata-excluded — two schemas authored by different libraries with structurally identical valueType + invariants are the same content-addressed node. The distinction the spec implies (libraries are different) is recoverable from the `libraryBinding` edge when present, but it does not affect identity. This matches the precedent set by ForeignNode's `binding` Provenance edge (`design/node-algebra.md` § References).

**N-033 Invariant.** Edges and content fields per `design/rendering-and-views.md` § Invariant (N-033):

| Field | Multiplicity | Target | Role |
|-------|--------------|--------|------|
| `invariantName` | 1 (content) | String (structural) | Library-scoped identifier |
| `targetSchema` | 1 | Schema | The schema this invariant is associated with |
| `body` | 1 | Expression of FunctionType `(valueType) -> Bool` | The predicate |

Step 1 ships pure-expression invariants only. The `body` edge accepts any Expression whose type is `(valueType) -> Bool`; in practice this is a Lambda or a VarRef into a Let-bound Lambda. A ForeignNode-typed body is rejected as `SchemaInvariantBodyMustBePure` until step 2 lands the foreign-checker trust model.

### 4.2 Canonical encoding

**Schema (CategoryTag.Schema = 32):**

```
Tag                : 32 (4-byte big-endian)
schemaName bytes   : UTF-8 of schemaName (CBOR byte string)
valueType hash     : 33-byte multi-hash of valueType (CBOR byte string)
invariant hashes   : array of 33-byte multi-hashes, sorted lexicographically
                     (set semantics — invariant order does not affect identity)
```

`libraryBinding` is excluded from the canonical encoding per the metadata-edge rule. The invariant list is sorted because the order in which invariants are declared has no semantic significance; what matters is the *set* of invariants the schema commits to. Two schemas with the same set in any order hash identically.

**Invariant (CategoryTag.Invariant = 33):**

```
Tag                : 33 (4-byte big-endian)
invariantName bytes: UTF-8 of invariantName
targetSchema hash  : 33-byte multi-hash of the parent Schema
body hash          : 33-byte multi-hash of the predicate expression
```

Invariant order is positional in the schema's edge list at *authoring* time, but as noted above the canonical encoding of the Schema itself sorts invariants by hash. The Invariant node's own encoding is straightforward — no sorting because no internal sets — and is hashed in whatever binder context the parent reference is in.

### 4.3 Worked example: PositiveInt schema

```json
{
  "version": 1, "root": "schemaClaim",
  "nodes": {
    "intT":       { "type": "PrimitiveType", "kind": "Int" },
    "boolT":      { "type": "PrimitiveType", "kind": "Bool" },
    "zero":       { "type": "IntLit", "value": 0 },
    "x":          { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
    "xRef":       { "type": "VarRef", "binder": "x" },
    "gtT":        { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
    "gt":         { "type": "ForeignNode", "target": "strand-builtin:Int.Gt", "foreignType": "gtT" },
    "gtBody":     { "type": "Application", "function": "gt", "arguments": ["xRef", "zero"] },
    "predLam":    { "type": "Lambda", "parameters": ["x"], "body": "gtBody" },
    "positiveInvariant": {
      "type": "Invariant",
      "invariantName": "x_positive",
      "targetSchema": "positiveInt",
      "body": "predLam"
    },
    "positiveInt": {
      "type": "Schema",
      "schemaName": "PositiveInt",
      "valueType": "intT",
      "invariants": ["positiveInvariant"]
    },
    "five":       { "type": "IntLit", "value": 5 },
    "schemaClaim": {
      "type": "Application",
      "function": "identityOfPositiveInt",
      "arguments": ["five"]
    },
    "identityOfPositiveInt": {
      "type": "Lambda",
      "parameters": ["pIn"],
      "body": "pInRef"
    },
    "pIn":   { "type": "ParameterDecl", "name": "p", "paramType": "positiveInt" },
    "pInRef":{ "type": "VarRef", "binder": "pIn" }
  }
}
```

The Lambda `identityOfPositiveInt` takes a parameter typed as `positiveInt` — the Schema itself appears in a type position. Calling it with `IntLit(5)` triggers the verifier:

1. Resolve the call's parameter type. The Schema `positiveInt` resolves to `TypeExpr.SchemaType(valueType = Int, invariants = [positiveInvariant])`.
2. Check the argument's type. `IntLit(5)` has type `Int`. The schema's `valueType` is `Int`. Structural match.
3. Is `IntLit(5)` statically known? Yes (it's a literal).
4. Evaluate each invariant on the value. `predLam(IntLit(5))` evaluates via `Interpreter.applyCallable` to `BoolV(true)`. Pass.

Replacing `five` with `IntLit(-3)` triggers `predLam(IntLit(-3)) → BoolV(false)`, and the verifier rejects the graph with `SchemaInvariantViolation(at = <schemaClaim>, schema = positiveInt, invariant = positiveInvariant, value = IntV(-3))`.

If the argument were `Application(someFunction, [...])` whose value is not statically known, step 1 emits `SchemaInvariantDeferred(at = <schemaClaim>, schema = positiveInt, reason = "argument value not statically known")` as an informational diagnostic — the verifier accepts the graph, but flags the deferred check for the implementer's awareness.

## 5. Verifier rules

The verifier gains a new resolution case for Schema in type position and a new pass for invariant evaluation. New `VerifyError` variants:

- **`SchemaInvariantViolation(at, schema, invariant, value)`** — a statically-known value typed against a Schema failed an invariant. `at` is the node introducing the schema claim (typically an Application argument position or a Let body), `schema` and `invariant` identify the failed predicate, `value` is the actual evaluated value for error reporting.
- **`SchemaValueTypeMismatch(at, schema, expectedValueType, actualType)`** — a value's type structurally disagrees with the schema's declared `valueType`. Caught by the standard type-check; this variant just specializes the diagnostic.
- **`SchemaInvariantBodyTypeMismatch(at, schema, invariant, expectedType, actualType)`** — an Invariant's `body` does not have function type `(valueType) -> Bool`. Caught at Schema construction time.
- **`SchemaInvariantBodyMustBePure(at, invariant)`** — the body is a ForeignNode or a Lambda with non-empty effects list. Step 1 ships pure-expression invariants only; this rule is loosened in step 2 when the trust model is extended.
- **`SchemaInvariantBodyMustBeMonomorphic(at, invariant)`** — the body has type `Forall(...)`. Invariants must be monomorphic functions over the schema's specific `valueType`; polymorphic invariants would need a separate type-application protocol that step 1 does not provide.
- **`InvariantTargetMismatch(at, invariant, targetSchema, declaringSchema)`** — an Invariant's `targetSchema` does not match the Schema that lists it. (Defensive — ensures the graph topology is consistent.)

Step 1 also emits one **non-fatal informational diagnostic**:

- **`SchemaInvariantDeferred(at, schema, reason)`** — a non-statically-known value was typed against a Schema; the invariant check could not be performed at verify time. Surfaced through a new `VerifyResult.Ok` field `deferredChecks: List<SchemaInvariantDeferred>` so callers can flag, log, or fail-on-deferred according to deployment policy. The default is to surface but not fail.

The "statically known" predicate is recursive: a value node is statically known iff (a) it is a literal, (b) it is a ProductValue whose every field's value is statically known, (c) it is a SumValue whose payload (if present) is statically known, (d) it is a Let whose body is statically known after binding its value to its identifier, (e) it is a VarRef whose binder is a Let with a statically-known value. Lambdas, Applications, ForeignNode calls, Fixpoint, Match, Handler, and CapabilityScope are all non-statically-known. This is conservative — Match on a literal scrutinee with literal case bodies could be evaluated symbolically — but tractable for step 1.

## 6. Runtime semantics

The interpreter is unchanged. Schemas are erased at runtime: a value typed as `Schema(valueType, invariants)` is operated on as if it had type `valueType`. The invariant checks are entirely verify-time (or verify-time-deferred); runtime evaluation proceeds without re-checking.

The `schema/` module provides the verify-time evaluation:

```kotlin
class SchemaChecker(
    private val store: NodeStore,
    private val hashToNodeId: Map<Hash, NodeId>,
    private val verifyResult: VerifyResult.Ok,
) {
    fun check(): SchemaCheckResult {
        val violations = mutableListOf<VerifyError.SchemaInvariantViolation>()
        val deferred = mutableListOf<VerifyError.SchemaInvariantDeferred>()
        for ((nodeId, type) in verifyResult.nodeTypes) {
            if (type !is TypeExpr.SchemaType) continue
            val staticValue = tryEvaluateStatically(nodeId) ?: run {
                deferred += VerifyError.SchemaInvariantDeferred(
                    at = nodeId,
                    schema = type.schemaId,
                    reason = "value not statically known"
                )
                continue
            }
            for (invariantId in type.invariants) {
                val invariant = store.get(invariantId) as Node.Invariant
                val verdict = evaluateInvariant(invariant.body, staticValue)
                if (verdict != Value.BoolV(true)) {
                    violations += VerifyError.SchemaInvariantViolation(
                        at = nodeId, schema = type.schemaId,
                        invariant = invariantId, value = staticValue
                    )
                }
            }
        }
        return SchemaCheckResult(violations, deferred)
    }
    private fun tryEvaluateStatically(nodeId: NodeId): Value? { ... }
    private fun evaluateInvariant(bodyId: NodeId, value: Value): Value =
        Interpreter(store, hashToNodeId).applyCallable(
            fn = Interpreter(store, hashToNodeId).eval(bodyId),
            args = listOf(value)
        )
}
```

`tryEvaluateStatically` walks the recursive "statically known" definition; if it succeeds it returns the evaluated `Value`, otherwise null. `evaluateInvariant` is a thin wrapper that evaluates the body Lambda and applies it. The capability context for invariant evaluation is intentionally empty — pure-expression invariants are by construction effect-free.

## 7. Test scenarios

1. **PositiveInt accepts positive literal** — schemaClaim with IntLit(5) verifies cleanly; no violations, no deferred checks.
2. **PositiveInt rejects negative literal** — schemaClaim with IntLit(-3) produces `SchemaInvariantViolation` with the precise invariant identifier and value.
3. **PositiveInt defers on parameter** — a Lambda takes an Int parameter and wraps it in a PositiveInt-typed context. Verifier emits `SchemaInvariantDeferred` for the parameter; the graph still verifies overall (deferred is informational).
4. **NonEmptyList schema with structural invariant** — a recursive list schema with invariant `count > 0`. Verifier accepts `Cons(1, Nil)` (count 1), rejects `Nil` (count 0). Demonstrates schemas over recursive types.
5. **Schema valueType mismatch** — schemaClaim with BoolLit(true) against a PositiveInt-typed position is rejected by the standard type-checker (not even reaching the invariant phase) with `SchemaValueTypeMismatch`.
6. **Invariant body type mismatch** — an Invariant whose body returns Int instead of Bool is rejected at Schema-construction time with `SchemaInvariantBodyTypeMismatch`.
7. **Polymorphic invariant body rejected** — an Invariant whose body is `Forall a. (a) -> Bool` is rejected with `SchemaInvariantBodyMustBeMonomorphic`. Demonstrates the step 1 simplification.
8. **ForeignNode invariant body rejected** — an Invariant whose body is a ForeignNode is rejected with `SchemaInvariantBodyMustBePure`. Demonstrates step 1's pure-expression-only scope.
9. **Two identical schemas dedupe** — two Schema declarations with identical `valueType` and identical `invariants` (in any order) produce identical hashes. (Hashing-determinism test.)
10. **Schema in product field** — a ProductType whose field type is a Schema. Constructing a ProductValue with a statically-known field that satisfies the schema verifies; constructing it with a field that fails the schema is rejected with the violation pointing at the inner field.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **ForeignNode-backed invariant checkers.** The trust model from `design/security-model.md` and `design/rendering-and-views.md` § Trust model for invariant checkers (signed provenance, reproducible checkers, sandboxed execution) needs to be wired through; step 1 ships pure-expression invariants only. The `SchemaInvariantBodyMustBePure` rejection is the explicit lift point for step 2.
- **The six blessed output libraries (HTML5, SVG, JSON, PDF, plain text, Markdown).** Each is a separate substantial implementation. Step 1 ships a synthetic PositiveInt + NonEmptyList demonstration; the real libraries are subsequent shipping steps that build on this infrastructure. The HTML5 work alone — Html5Document base, Html5AccessibleAA WCAG extension, Html5StrictCSP extension — is comparable in scope to all of Layer 6.
- **Symbolic / non-static invariant evaluation.** Step 1 evaluates invariants only on statically-known values; non-static cases produce `SchemaInvariantDeferred`. Symbolic evaluation (proving invariants hold for *all* values a parameter might take, e.g., by examining the structure of a function that produces a value) requires either a domain-specific reasoner per invariant or the full refinement-type generalization. Both are deferred.
- **Provenance manifest (Q-027).** The mapping from output byte ranges back to source node hashes is a separate concern that hooks into serialization rather than into the schema mechanism itself. Step 1 does not address provenance; it lands when the first blessed library implements its serializer.
- **Cross-library invariant composition (Q-028).** When two schemas from different libraries apply to the same value, the verifier checks all invariants from all schemas. Step 1's mechanism supports this (multiple SchemaType wrappings of the same valueType compose), but no corpus program exercises it. The conflict-detection question (`design/rendering-and-views.md` § Cross-library composition) is explicitly deferred to the agent's construction loop, per the design.
- **Live views and state-machine integration.** A live view is a state machine whose output stream carries serialized renderings of a schema-bearing value. Step 1 ships the schema mechanism but does not integrate it with the state-machine runtime; that integration belongs to the Layer 7 live-view step, which depends on both this proposal and Layer 6 step 2/3 (depending on whether the live view is synchronous or async).
- **Interaction with encrypted nodes (ADR-006).** A schema-bearing value may be encrypted; the verifier needs decryption capabilities to check invariants on the plaintext. Step 1 does not implement encryption; this question waits on the per-node encryption work.
- **Differential rendering and serialization caching.** Both are optimization-layer concerns that build on the basic mechanism. Deferred to library-step work.

**Real research questions:**

- **OQ-7a: Should `SchemaType` be a first-class TypeExpr variant, or a wrapper added to existing TypeExpr at lookup time?** A first-class variant means every `when (type)` in the verifier needs a Schema case; a lookup-time wrapper means the verifier always sees the underlying `valueType` and a side-channel records "this position has Schema invariants to check." First-class is cleaner for the type-checker; side-channel is cleaner for the rest of the verifier. The proposal recommends first-class with the conservative "Schema equals its valueType under structural equality plus the invariant carry" rule; this needs implementation experience to validate.
- **OQ-7b: When does deferred-check fail-or-warn?** Step 1 emits `SchemaInvariantDeferred` as informational, but a deployment may want to reject all graphs with deferred checks. The verifier could grow a strictness mode; the proposal punts on this until at least one library exists to surface real cases.
- **OQ-7c: How does Schema interact with NodeRef closure rule (Layer 2 step 2)?** A NodeRef-targeted subgraph that contains a SchemaClaim with a non-trivial invariant could in principle be context-dependent (the invariant body may reference outer binders). The proposal's pure-expression + monomorphic restriction makes this unlikely to bite — invariant bodies don't reference outer scope — but the interaction should be confirmed during implementation.
- **OQ-7d: Should constants flow through Let-bindings into "statically known" for the purposes of invariant checking?** The proposal says yes (case (d) of the "statically known" definition), but a deeply-nested Let chain could be expensive to evaluate at verify time. A depth bound or a hard `SchemaInvariantDeferred` for Let chains over some depth is a possible step-1 refinement.
- **OQ-7e: Library-scoped schema names — what happens when two libraries register the same name?** The proposal says the libraries are different because their `libraryBinding` differs, but `libraryBinding` is metadata-excluded from the hash. So two such schemas have identical hashes — they ARE the same schema by content-addressing. The library-name distinction is only meaningful at the level of human-readable tooling, not in the graph. This is consistent with ForeignNode's identity rule but worth surfacing.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `core/src/main/kotlin/org/strand/core/Node.kt` | Add `data class Schema(schemaName, valueType, invariants)` and `data class Invariant(invariantName, targetSchema, body)` | Small |
| `core/src/main/kotlin/org/strand/core/Json.kt` | Add ingest cases for Schema and Invariant; update the rejection-message identifier range | Small |
| `hashing/src/main/kotlin/org/strand/hashing/CategoryTag.kt` | Add `val Schema = CategoryTag(32)` and `val Invariant = CategoryTag(33)` | Trivial |
| `hashing/src/main/kotlin/org/strand/hashing/CanonicalEncoder.kt` | Add `encodeSchema` and `encodeInvariant`; sort schema invariants by hash for canonical ordering | Small |
| `hashing/src/main/kotlin/org/strand/hashing/Hasher.kt` | Add walk cases for Schema and Invariant | Small |
| `verifier/src/main/kotlin/org/strand/verifier/TypeExpr.kt` | Add `data class SchemaType(schemaId: NodeId, valueType: TypeExpr, invariants: List<NodeId>): TypeExpr()` | Small |
| `verifier/src/main/kotlin/org/strand/verifier/Verifier.kt` | Schema resolution in `resolveType`: when a type position references a Schema node, return SchemaType. Schema is well-formedness-checked when reached: valueType resolves to a Type, every invariant body has the right function type, no Forall, no foreign body. Type-equality of SchemaType against its underlying valueType is structural (assignment-into); reverse is rejected (cannot assign a Schema-typed value into a plain Type position without an explicit schema-strip operation, which step 1 does not provide — assignment from Schema to Type is allowed because the verifier knows the value satisfies the structural type). | Medium |
| `verifier/src/main/kotlin/org/strand/verifier/VerifyError.kt` | Add the 6 new error variants + 1 informational variant; `categoryName` cases for Schema and Invariant | Small |
| `verifier/src/main/kotlin/org/strand/verifier/VerifyResult.kt` | Extend `VerifyResult.Ok` with `deferredChecks: List<VerifyError.SchemaInvariantDeferred>` | Trivial |
| `schema/` | NEW Gradle module: depends on `:verifier` and `:interpreter`; exposes `SchemaChecker.check(store, hashToNodeId, verifyResult): SchemaCheckResult` | Medium |
| `schema/src/main/kotlin/org/strand/schema/SchemaChecker.kt` | The static-value detector + invariant evaluator described in § 6 | Medium |
| `schema/src/main/kotlin/org/strand/schema/SchemaCheckResult.kt` | Data class for violations + deferred checks | Trivial |
| `schema/build.gradle.kts` | Module config: deps on verifier + interpreter | Trivial |
| `cli/src/main/kotlin/org/strand/cli/Main.kt` | After verifier succeeds, run `SchemaChecker.check`; fail the command if violations are non-empty | Small |
| `cli/build.gradle.kts` | Add `:schema` dependency | Trivial |
| `corpus/src/main/resources/corpus/50-positive-int-schema-pass.json` | PositiveInt schema accepts IntLit(5) | Small |
| `corpus/src/main/resources/corpus/51-positive-int-schema-fail.json` | PositiveInt schema rejects IntLit(-3); test asserts violation | Small |
| `corpus/src/main/resources/corpus/52-non-empty-list-schema-pass.json` | NonEmptyList over recursive list type, accepts Cons | Small |
| `corpus/src/main/resources/corpus/53-non-empty-list-schema-fail.json` | NonEmptyList rejects Nil | Small |
| `corpus/src/main/resources/corpus/README.md` | Per-program descriptions | Small |
| `corpus/src/test/kotlin/org/strand/corpus/CorpusSchemaTest.kt` | NEW test driver: each program parses, finalizes, verifies, schema-checks; assertions on expected violations | Medium |
| `corpus/build.gradle.kts` | Add `:schema` testImplementation | Trivial |
| `verifier/src/test/kotlin/org/strand/verifier/VerifierTest.kt` | Add ~6 tests for the Schema/Invariant well-formedness rules and SchemaType resolution | Medium |
| `schema/src/test/kotlin/org/strand/schema/SchemaCheckerTest.kt` | NEW: unit tests for static-value detection, invariant evaluation, deferred-check emission | Medium |
| `impl/CLAUDE.md` | Layer 7 step 1 status; new schema module documented; updated "Deferred to later layers" row | Small |
| `design/node-algebra.md` | Add inventory rows for N-032 Schema and N-033 Invariant (the slots are pre-reserved); add "Schema mechanism" prose subsection if not already present | Small |
| `INDEX.md` | Bump Last revised; node-types table already has the rows (32/33) — no change | Trivial |

**Order of work.**

1. **Node ADT + JSON ingest + canonical encoding + hasher walk** — gets Schema/Invariant nodes into the system; no semantic change yet. Existing tests continue to pass.
2. **Verifier well-formedness for Schema/Invariant + SchemaType resolution** — adds the type-position handling and the construction-time checks for invariant body shape. At this point a graph that constructs Schema and Invariant nodes verifies; nothing yet evaluates invariants.
3. **`schema/` module + `SchemaChecker`** — adds the static-value detector and the invariant evaluator. CLI wires the check into the pipeline. PositiveInt corpus programs land here.
4. **NonEmptyList over recursive types** — confirms the mechanism scales to structural invariants over realistic shapes.
5. **Documentation pass** — `impl/CLAUDE.md`, `node-algebra.md` prose addition, INDEX `Last revised`.

**Not in this slice.** Any of the blessed output libraries (HTML5, SVG, JSON, PDF, plain text, Markdown); ForeignNode-backed invariant checkers; symbolic / non-static invariant evaluation; provenance manifests; live-view composition with state machines; differential rendering; interaction with encrypted nodes; the schema-strip operation that would allow assigning Schema-typed values into plain-Type positions. Each is a separate shipping step.

## References

**Outgoing references:**
- [`decisions/ADR-009-structured-outputs.md`](../decisions/ADR-009-structured-outputs.md) — architectural basis: Schema and Invariant as the verifier's extension point
- [`design/rendering-and-views.md`](../design/rendering-and-views.md) — full design: schema mechanism, blessed libraries, trust model, live views, provenance
- [`design/node-algebra.md`](../design/node-algebra.md) — N-032 and N-033 slots are pre-reserved here
- [`design/security-model.md`](../design/security-model.md) — trust model that step 2 must extend for ForeignNode checkers
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — emission as existing effect categories (Network.Send, Filesystem.Write, etc.); rendering introduces no new categories
- [`open-questions.md`](../open-questions.md) — Q-025 (mechanism scope), Q-026 (blessed library set), Q-027 (provenance encoding), Q-028 (cross-library composition); Q-035 points back at this proposal

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-035 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — Known gaps section
