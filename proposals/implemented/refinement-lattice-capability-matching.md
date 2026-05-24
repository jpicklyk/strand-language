# Refinement-Lattice Capability Matching

**Document:** `proposals/implemented/refinement-lattice-capability-matching.md`
**Status:** Implemented (Layer 3 step 2 of the Kotlin/JVM reference implementation, 2026-05-23)
**Date:** 2026-05-23 (proposed); 2026-05-23 (implemented)
**Concerns:** [`design/effects-and-capabilities.md`](../../design/effects-and-capabilities.md), [`decisions/ADR-004-effects-as-edges.md`](../../decisions/ADR-004-effects-as-edges.md), [Q-031](../../open-questions.md#Q-031)
**Scope:** Medium

> **Implementation note (2026-05-23).** The proposal below was executed as-described with one runtime-semantics refinement worth recording: the per-category refinement check fires *only* for categories with an explicit EffectDecl at the call site. Categories declared by the callee but absent from `Application.effectInstances` pass through after the category-presence check (so a caller forwarding a capability down the chain — e.g. the outer call to a logger Lambda — is not denied at its own call). The classic confused-deputy denial (caller passes a forbidden path through a privileged logger) fires at the innermost call where the EffectDecl is actually supplied, which is exactly the point at which the concrete refinement value is known. The pre-Q-031 `eval(root, Set<NodeId>)` API is preserved as a thin wrapper over `CapabilitySet.ofCategories(...)`; the wrapper produces a single-slot wildcard sentinel that the `covers()` algorithm treats as "matches any-arity requirement," so every pre-Q-031 corpus program runs unchanged. See `impl/CLAUDE.md` Layer 3 step 2 notes and `impl/interpreter/src/main/kotlin/org/strand/interpreter/CapabilitySet.kt` for the runtime API surface.

This is "Layer 3 step 2" of the reference implementation: make `EffectDecl` (N-022) load-bearing so capabilities can authorize specific resource interactions rather than entire effect categories. Layer 3 step 1 is in production today; this proposal builds on that without changing it.

## 1. Problem statement

Capability matching today is by `EffectCategory` NodeId identity only. Every declaration site (`Lambda.effects`, `FunctionType.effects`, `ForeignNode.effects`, `CapabilityScope.capabilities`) is `List<NodeId>` pointing at `EffectCategory` nodes. The runtime capability context is `Set<NodeId>` over the same identities. Coverage is set subtraction.

`EffectDecl` (N-022) — "an EffectCategory instance with concrete parameter expressions" — is in the schema, has JSON ingest, has a canonical encoding, has unused verifier error variants reserved (`EffectDeclArityMismatch`, `EffectDeclParameterTypeMismatch`). Nothing produces or consumes it. The verifier explicitly aborts if an `EffectDecl` appears in an expression position.

What is missing is the bridge between "this call site connects to `api.example.com:443`" (a concrete EffectDecl) and "this evaluation has permission to connect to anything on port 443" (a wildcard-bearing capability). Both sides need to carry parameter content, and the matching algorithm needs to compare them by refinement rather than identity.

The corpus (`design/effects-and-capabilities.md` § Effect closure semantics) is explicit: `Network.Connect{host: "api.example.com", port: 443}` is more specific than `Network.Connect{host: *, port: 443}`, which is more specific than `Network.Connect{host: *, port: *}`. Capability matching is by refinement — a capability covers a required effect iff the capability's specification is at-least-as-general.

This is not yet pressing for Layer 4 step 1: the only builtin that declares an effect is `Time.Now`, which is parameterless. It becomes pressing the moment Layer 4 step 2 introduces a `Filesystem.Read` builtin or a `Network.Connect` binding whose parameters are known at the call site. Confused-deputy mitigation (Q-005) depends on it.

## 2. Decisions to make

| # | Choice | Recommendation |
|---|--------|----------------|
| D1 | Where do EffectDecls attach? | **At ForeignNode call-site instantiation sites only initially.** Lambdas keep their `effects: List<EffectCategory>` (the effect-category set is a static type-system property). EffectDecls live at *use* sites where concrete arguments make refinement observable. |
| D2 | What is the dual at *invocation*? | Introduce a third edge `Application.effectInstances: List<EffectDecl>`, non-empty only when the called function's `FunctionType.effects` is non-empty. The verifier checks the EffectDecls' shape; the interpreter consults them for the capability check. |
| D3 | Capability context representation | Replace `Set<NodeId>` with a structured `CapabilitySet` keyed by `EffectCategory` NodeId mapping to a list of `CapabilityPattern`. A pattern has one entry per category parameter, each either a `Concrete(Value)` or `Wildcard`. |
| D4 | What does "wildcard" canonicalize to? | Capabilities are runtime-only artifacts — they never enter the node store, so the wildcard concept doesn't interact with the canonical encoder. They're constructed by the host runtime via a small Kotlin API. |
| D5 | Static vs runtime check | **Both, with the verifier doing what it can.** Verifier statically validates EffectDecl shape (arity, parameter types). Refinement coverage is checked at runtime because parameter expressions may be computed values. |
| D6 | Confused-deputy alignment | Refinement matching *is* the parameter-tagged-capability mechanism. No additional design is required for this scope step; the matching algorithm enables it. |

## 3. Proposed effect-decl placement

The corpus is unambiguous on one point: `EffectCategory` is the *type-system* artifact, `EffectDecl` is the *instance*. Lambda effect edges declare which categories a function may exercise — that is a property of the function's signature. The corresponding refinement instances appear when the function actually runs, with concrete arguments.

**Lambda.effects: unchanged.** Stays `List<NodeId>` over `EffectCategory`. A `Lambda` is a value, not a call. Its declared effects describe the *kinds* of effect its body may perform; the body is parameterized over its own arguments, so it cannot yet know concrete refinement values. Same for `FunctionType.effects` and `ForeignNode.effects`.

**Application gains a new `effectInstances` edge.**

```
N-016 Application now carries:
  function: NodeId
  arguments: List<NodeId>
  typeArguments: List<NodeId>
  effectInstances: List<NodeId>   // each an EffectDecl; new in this slice
```

Each EffectDecl in `effectInstances` corresponds positionally to one of the EffectCategory entries in the callee's `FunctionType.effects`. The `EffectDecl.parameters` are expression NodeIds. The expressions are typically VarRefs into the application's arguments (which is how the "host" and "port" arguments to a `connect` builtin become the EffectDecl's parameters) but may be any expression of the right type.

The canonical encoder gains one field on Application whose presence is gated on `effectInstances.size > 0` for backward-compat with the existing 32 corpus programs.

## 4. Capability representation

Today: `Set<NodeId>` where each NodeId is an `EffectCategory`. The interpreter receives this through `Interpreter.eval(root: NodeId, capabilities: Set<NodeId>)`.

Proposed:

```kotlin
// New file: interpreter/CapabilitySet.kt
data class CapabilitySet(
    val grants: Map<NodeId, List<CapabilityPattern>>  // key = EffectCategory NodeId
) {
    fun isEmpty() = grants.isEmpty()
    fun intersect(other: Set<NodeId>): CapabilitySet  // for CapabilityScope narrowing
    companion object {
        val EMPTY = CapabilitySet(emptyMap())
        fun ofCategories(cats: Set<NodeId>): CapabilitySet  // back-compat: wildcards everywhere
    }
}

data class CapabilityPattern(
    val arguments: List<CapabilityArgument>  // length must match EffectCategory.parameters.size
)

sealed class CapabilityArgument {
    object Wildcard : CapabilityArgument()
    data class Concrete(val value: Value) : CapabilityArgument()
}
```

Key points:

- The map key remains `EffectCategory NodeId`. Lookup is O(1) by category; the per-category pattern list is short in practice.
- A category mapping to `listOf(CapabilityPattern(listOf(Wildcard, Wildcard)))` is fully unconstrained — the back-compat path. `CapabilitySet.ofCategories` produces this, so existing call sites that hand a `Set<NodeId>` keep working.
- Multiple patterns for one category means disjunction: any pattern matching is sufficient. This is the "you may write `/var/log/app.log` AND `/var/log/audit.log`" case.
- `Value` (the existing runtime value ADT in `Value.kt`) carries the concrete value: `Value.StringV("api.example.com")`, `Value.IntV(443)`, etc.

**Why map keyed by category, not flat list of patterns**: matching by category-then-refinement is O(category lookup + |patterns for category|). A flat list would be O(|all capabilities|). Category lookup is the dominant filter and matches how policies are typically expressed ("grant Filesystem.Write on these paths").

**CapabilityScope narrowing under refinement**: `CapabilityScope.capabilities: List<NodeId>` stays category-level — the language-level node only names which categories to retain. Narrowing intersects categories, preserving the surrounding context's per-category pattern list verbatim for retained categories. Schema unchanged. A future step may want a refinement-narrowing scope (intersecting patterns within a retained category); deferred behind a new question.

## 5. Matching algorithm

```kotlin
fun covers(capability: CapabilityPattern, requirement: List<Value>): Boolean {
    if (capability.arguments.size != requirement.size) return false   // verifier-prevented
    for (i in capability.arguments.indices) {
        if (!coversArg(capability.arguments[i], requirement[i])) return false
    }
    return true
}

fun coversArg(arg: CapabilityArgument, req: Value): Boolean = when (arg) {
    CapabilityArgument.Wildcard -> true
    is CapabilityArgument.Concrete -> valueEquals(arg.value, req)
}
```

Dispatch at a call site:

```kotlin
fun checkRefined(
    at: NodeId,
    declaredCategories: List<NodeId>,                  // from Lambda/ForeignNode.effects
    instances: Map<NodeId, List<Value>>,               // category → evaluated EffectDecl params
    context: CapabilitySet,
) {
    for (category in declaredCategories) {
        val grants = context.grants[category]
            ?: throw CapabilityViolation(at, missingCategory = category)
        val reqParams = instances[category]
            ?: emptyList()   // category with no parameters; wildcard pattern matches trivially
        val matched = grants.any { covers(it, reqParams) }
        if (!matched) throw RefinementViolation(at, category, reqParams, grants)
    }
}
```

**Per-parameter-type semantics:**

| Parameter type | Wildcard | Concrete |
|----|----|----|
| `String` (host, path, key id) | matches any String | string equality |
| `Int` (port) | matches any Int | int equality |
| `Bytes` | matches any Bytes | contentEquals |
| `Bool` | matches any Bool | equality |
| Product / Sum | matches any | structural equality (Value equals already correct) |
| Function / Forall | not a valid effect parameter type — verifier-rejected | — |

Equality is Strand's existing value equality (`equals` methods on `Value` subtypes).

**Sub-string and pattern wildcards (e.g. `host: "*.example.com"`) are deliberately out of scope.** Adding glob/regex semantics interacts with canonical encoding (what is the canonical form of `"*.example.com"` for hashing?) and is best a separate `CapabilityArgument.Glob(pattern: String)` follow-up.

## 6. Verifier-side static check

The verifier already enforces category-level coverage. Additions for this slice:

**EffectDecl shape (was deferred — these variants already exist as unused):**
- `EffectDecl.effectType` must reference an `EffectCategory` node — emit `EffectDeclTypeMismatch`
- `EffectDecl.parameters.size` must equal `EffectCategory.parameters.size` — emit `EffectDeclArityMismatch`
- Each `EffectDecl.parameters[i]` expression must type-check to a TypeExpr equal to the resolved `EffectCategory.parameters[i]` type — emit `EffectDeclParameterTypeMismatch`

**Application.effectInstances coverage (new):**
- The set of `EffectCategory NodeIds` in `Application.effectInstances` (one EffectDecl per category) must equal the set of EffectCategory NodeIds in the callee's `FunctionType.effects`. New variant:
  ```
  EffectInstanceCoverageMismatch(at: NodeId,
                                 missing: Set<NodeId>,
                                 extra: Set<NodeId>)
  ```
- Empty `effectInstances` is permitted when the callee has no declared effects. Otherwise every declared category must have an EffectDecl with the right shape.

**Optional static refinement pre-check:**
When EffectDecl parameters are literal nodes, the verifier *can* evaluate them at verify time and compare against any literally-known host capabilities — useful for "verify before ship" workflows. Recommendation: not in this slice; runtime-only first.

## 7. Runtime check

`Interpreter.eval` gains an overload taking `CapabilitySet`:

```kotlin
fun eval(root: NodeId, capabilities: CapabilitySet): Value
// existing fun eval(root: NodeId, capabilities: Set<NodeId>) becomes a thin
// wrapper that calls eval(root, CapabilitySet.ofCategories(capabilities))
```

`checkCapabilities` is rewritten per § 5. `CapabilityScope` narrowing becomes intersection of categories preserving per-category pattern lists.

**New `InterpretError` variant:**

```kotlin
data class RefinementViolation(
    override val at: NodeId,
    val category: NodeId,
    val requirement: List<Value>,
    val available: List<CapabilityPattern>,
) : InterpretError()
```

`CapabilityViolation` keeps its meaning (category not present at all). `RefinementViolation` is the new failure mode (category present but no pattern covers the concrete arguments). The split is intentional: the policy author sees which kind of denial happened.

## 8. Test scenarios

Minimum spread for the test suite:

1. Literal-host on literal-port matches concrete capability. Expect: success.
2. Literal-host doesn't match different-literal-host. Expect: `RefinementViolation`.
3. Literal-port matches wildcard-host. Expect: success.
4. Wildcard-port matches literal-port. Expect: success.
5. Multiple capabilities, any-match. Two grants for Filesystem.Write: `{path: "/tmp/a"}` and `{path: "/tmp/b"}`. Write to `/tmp/a`. Expect: success.
6. Category present, refinement misses all. Same two grants; write to `/tmp/c`. Expect: `RefinementViolation` (NOT `CapabilityViolation`).
7. EffectDecl arity mismatch caught by verifier.
8. EffectDecl parameter type mismatch caught by verifier.
9. **Confused-deputy scenario.** Logger holds `Filesystem.Write{path: "/var/log/app.log"}`. Caller passes `path: "/etc/passwd"` to the logger. Expect: `RefinementViolation`. The canonical demo of the security property.
10. Backward compatibility. A pure program verifies and runs unchanged. An effectful program whose Application has no `effectInstances` field verifies and runs against a `CapabilitySet.ofCategories(...)` context.

(9) and (10) are the load-bearing tests.

## 9. Tradeoffs and open questions

**Structured-value capabilities (Connection, KeyId).** The matching algorithm works as long as the structured value has an `equals`. What it does NOT model is *transitive* refinement — "you may send bytes on any connection that came from a `Network.Connect{host: api.com}`" requires tracking value provenance, which is a separate substantial design problem.

**Application.effectInstances placement.** Alternative: a wrapping node `EffectfulCall(application, effectInstances)`. Pro: keeps Application schema unchanged. Con: every effectful call doubles its node count. Recommendation: put it on Application; empty-list default keeps the wire format compatible for pure calls.

**Refinement-narrowing on CapabilityScope.** Currently category-level. A natural extension is `CapabilityScope` carrying `EffectDecl`-style patterns for refinement narrowing. Meaningful for confused-deputy mitigation at trust-domain boundaries. Recommendation: defer; open as a follow-up question.

**Q-003 (effect granularity)** becomes substantively resolved by this design. **Q-005 (confused deputy)** is partially resolved: parameter-tagged capabilities are the primary defense, now implementable. Test (9) is the concrete instance.

## 10. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `core/Node.kt` | Add `effectInstances: List<NodeId> = emptyList()` to Application. | Small |
| `core/Json.kt` | Parse the optional `effectInstances` field; default empty. | Small |
| `verifier/VerifyError.kt` | Add `EffectInstanceCoverageMismatch`; the `EffectDecl*` variants already exist. | Small |
| `verifier/Verifier.kt` | New `inferEffectDecl(id, node, scope, typeParams)` that validates effectType + arity + parameter types. `inferApplication` calls it for each entry in `effectInstances`, then checks the set of categories equals the FunctionType's effect set. EffectDecl in expression-position dispatch still errors. | Medium |
| `interpreter/CapabilitySet.kt` (new) | `CapabilitySet`, `CapabilityPattern`, `CapabilityArgument` data types plus `covers()` and `intersect()`. | Small |
| `interpreter/InterpretError.kt` | Add `RefinementViolation` variant. | Small |
| `interpreter/Interpreter.kt` | Replace `Set<NodeId>` context with `CapabilitySet`. Rewrite `checkCapabilities`. Add the public `eval(root, CapabilitySet)` overload; keep the old `eval(root, Set<NodeId>)` as a thin wrapper. Evaluate EffectDecl parameter expressions in `applyCall` before dispatch. | Medium |
| `hashing/CanonicalEncoder.kt` | Add the new `effectInstances` field to `encodeApplication` — sorted-set of effect-instance hashes. EffectDecl already has an encoding. | Small |
| `interpreter/test/...InterpreterTest.kt` | Add the 10 scenarios. | Medium |
| `verifier/test/...VerifierTest.kt` | Add tests for new verifier errors. | Small |
| `corpus/...` | Add 2-3 new corpus programs exercising refinement; existing corpus continues to pass. | Small |

**Order of work.** (1) Schema change (Node + Json + canonical encoder) with hashing tests. (2) Verifier changes with `EffectDecl*` shape errors and the new coverage error. (3) `CapabilitySet` data type with unit tests for `covers()` and `intersect()`. (4) Interpreter rewrite. (5) Integration tests.

Each step independently testable. Total scope: medium.

**Not in this slice.** Static refinement pre-check, pattern/glob wildcards, refinement-narrowing CapabilityScope, graph-level capability grants as nodes. All clean follow-up slices that this design does not foreclose.

## References

**Outgoing references:**
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — § Effect closure semantics, § Capability mechanism, § Confused deputy mitigation
- [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md)
- [`open-questions.md`](../open-questions.md) — Q-003, Q-005, Q-031

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-031 points at this proposal
- [`proposals/README.md`](README.md)
- [`impl/CLAUDE.md`](../impl/CLAUDE.md) — Known gaps section
