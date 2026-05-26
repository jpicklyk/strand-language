# Foreign Effect Projections

**Document:** `proposals/foreign-effect-projections.md`
**Status:** Draft proposal
**Date:** 2026-05-26
**Concerns:** [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md), [`design/security-model.md`](../design/security-model.md), [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md), [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md), [`proposals/implemented/refinement-lattice-capability-matching.md`](implemented/refinement-lattice-capability-matching.md), [Q-005](../open-questions.md#Q-005), [Q-031](../open-questions.md#Q-031), [Q-039](../open-questions.md#Q-039), [`security-index.md`](../security-index.md) § Finding 1
**Scope:** Medium

This proposal closes the implementation-level security gap surfaced by the 2026-05-26 audit (recorded as [Q-039](../open-questions.md#Q-039) and Finding 1 in [`security-index.md`](../security-index.md)). It does so by adding a *projection* field to `ForeignNode` and `FunctionType` that binds each foreign-binding's declared effect parameters to the function's actual call-site arguments. The proposal restores the security property that [Q-031](../open-questions.md#Q-031) and [Q-005](../open-questions.md#Q-005) jointly promise: at every refined capability check, the value being checked is the value the foreign code will actually consume.

## 1. Problem statement

[Q-031](../open-questions.md#Q-031) made `Application.effectInstances` load-bearing: at every effectful call, the agent declares EffectDecls whose evaluated parameters are matched against granted `CapabilityPattern`s in the runtime context. The matching algorithm in [`CapabilitySet.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/CapabilitySet.kt) implements refinement-lattice coverage correctly. The verifier enforces EffectDecl shape — arity, parameter types, category coverage — at admission time.

What neither the verifier nor the runtime enforces is the *coupling* between the EffectDecl's parameter expressions and the foreign function's value arguments. The proposal text for Q-031 noted this informally:

> The expressions are typically VarRefs into the application's arguments (which is how the "host" and "port" arguments to a `connect` builtin become the EffectDecl's parameters) but may be any expression of the right type.

In practice, "any expression of the right type" leaves the security model dependent on the agent's honesty. Concretely (verified against [`Interpreter.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt) lines 759–836 and [`Builtins.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt) lines 395–501):

1. `evalEffectInstances` evaluates the EffectDecl parameter expressions and produces `Map<EffectCategory NodeId, List<Value>>`.
2. `checkCapabilities` matches those evaluated parameter values against granted patterns.
3. `applyForeign` evaluates `Application.arguments` independently and passes those values to the builtin.
4. Nothing requires the values in step (1) to equal the values in step (3).

The exploit shape: a graph declares `Filesystem.Write{path: "/var/log/app.log"}` as its effect instance — matching a granted `Filesystem.Write{path: "/var/log/app.log"}` pattern — while passing `/etc/shadow` as the function's first argument. `Files.write(Paths.get("/etc/shadow"), bytes)` runs. This invalidates the parameter-tagged-capability defense that [`security-model.md`](../design/security-model.md) § Confused deputy synthesis promises:

> A program that holds `Filesystem.Write{path: "/var/log/app.log"}` because the design called for that capability specifically is not vulnerable to confused-deputy through the path parameter.

The implementation as it stands does not deliver that property. The proposal restores it.

## 2. Prior art

- **WebAssembly Component Model (WIT)** — function imports declare effect-relevant parameter types; the runtime mediates the call with the declared signature, and a binding that misdeclares is caught at boundary translation. The signature *is* the security contract.
- **Pony's reference capabilities** — authority is the reference, not a parametric refinement. Pony does not have to solve the coupling problem because no separate refinement value exists. Strand's choice to parameterize effects (which gives finer-grained policy) re-creates the coupling question that Pony eliminates.
- **Koka effect rows + handlers** — effect operations are parameterized by their arguments at the call site; there is no separate "declared refinement" the caller authors. Coupling is structural: the operation's arguments *are* the parameters.
- **Linux seccomp-bpf with per-syscall argument filtering** — the filter expression names syscall arguments by index (`arg0`, `arg1`) and matches them against allowed values. The filter cannot reference values the syscall does not see. This is precisely the coupling shape Strand's projections should adopt.
- **Capability-based file systems (Plan 9, Capsicum)** — capability tokens are tied to descriptors, not paths; once you hold the descriptor, the path is fixed. Strand's path-string parameterization is more flexible but loses the descriptor-binding property unless the language enforces argument-projection coupling.

The cross-cut: every system that parametrizes effects either eliminates the coupling problem by structure (Pony, Koka) or enforces it by making refinement values derivable from the operation's arguments (seccomp, WIT). Strand currently sits between the two — parameterized effects without enforced coupling — which is the worst position. This proposal moves Strand to the enforced-coupling regime.

## 3. Recommended approach

Add an *effect projection* field to `ForeignNode` (and to `FunctionType` for symmetry, though only ForeignNode requires it in this slice). A projection specifies, for each of the function's declared effect categories, how the parameter values for that category are derived from the function's arguments. Sources are either positional argument references or literal nodes baked into the binding.

The verifier requires that every `Application` of a ForeignNode-with-projections has `effectInstances` *consistent* with the projection: each EffectDecl parameter expression is either the same NodeId as the indicated `Application.arguments[j]`, or a literal node whose canonical-encoding value equals the indicated literal. The runtime then synthesizes the capability-check parameter values from the projection and the actual evaluated arguments — guaranteed identical to what the foreign code sees.

ForeignNodes without projections continue to work under the current Q-031 semantics, with a known security caveat tracked in [`security-index.md`](../security-index.md). Existing parameterized-effect bindings (Fs.*, Net.*, Process.*, Crypto.*, LLM providers, Vector providers) are migrated to declare projections.

Lambda-level projections are deliberately deferred to a follow-up (§ 8); the security boundary is the foreign-call site, and tightening ForeignNode closes the attack surface even when intermediate Lambdas misdeclare.

| # | Decision | Recommendation |
|---|----------|----------------|
| D1 | Where do projections attach? | On ForeignNode primarily; on FunctionType as a parallel optional field for future Lambda-level work. |
| D2 | Projection source vocabulary | `ArgRef(index: Int)` and `LiteralNode(target: NodeId)`. No derived expressions in V1. |
| D3 | Schema migration | Optional field with default empty list. Old corpus hashes unchanged when projections are absent (additive encoding). |
| D4 | Authored vs synthesized `Application.effectInstances` | For a ForeignNode call with projections: synthesized at runtime; authored EffectDecls are still permitted but must match the projection structure (verifier-enforced). |
| D5 | Migration of existing bindings | Per-category, mechanical. Fs.*, Net.*, Process.*, Crypto.*, LLM, Vector all map naturally. Http.Request needs binding-level redesign (§ 8). |
| D6 | New node category | None. Projections are inline metadata on ForeignNode/FunctionType, encoded as tagged sources. |

## 4. Detailed mechanism

### 4.1 Schema addition

`ForeignNode` and `FunctionType` gain an optional content field:

```
effectProjections: List<EffectProjection> = []   // one entry per effects[i]
```

`EffectProjection` is an inline structured value (not a node):

```kotlin
// New file: core/EffectProjection.kt
data class EffectProjection(
    val category: NodeId,           // EffectCategory; must match the corresponding effects[i]
    val sources: List<ProjectionSource>,
)

sealed class ProjectionSource {
    data class ArgRef(val index: Int) : ProjectionSource()
    data class LiteralNode(val target: NodeId) : ProjectionSource()
}
```

Constraints:

- If `effectProjections` is non-empty, its length equals `effects.size`, and the i-th projection's `category` matches `effects[i]`.
- For each projection, `sources.size` equals the `EffectCategory.parameters` arity.
- `ArgRef(i)` is valid iff `0 <= i < parameters.size` of the enclosing function signature.
- `LiteralNode(t)` is valid iff `t` resolves to a literal node (`IntLit`, `FloatLit`, `StringLit`, `BoolLit`, `BytesLit`, or `ProductValue`/`SumValue` over literals) of a type structurally equal to the EffectCategory parameter's declared type.

### 4.2 Canonical encoding

The new field is encoded only when non-empty (preserves all existing corpus hashes). The CBOR encoding for one EffectProjection:

```
[
  category-hash (32 bytes BLAKE3 over EffectCategory canonical form),
  sources: [
    [tag: 0 (ArgRef), index: u32],
    [tag: 1 (LiteralNode), target-hash: 32 bytes],
    ...
  ]
]
```

The `effectProjections` field on `ForeignNode`/`FunctionType` is emitted as a CBOR array of these structures, present only when non-empty. The omit-when-default rule mirrors the precedent set by `Application.effectInstances` (Q-031), `EventStream.bufferSize`, `EventStream.overflowPolicy`, and `EventStream.consumerMode` (Layer 6 step 3).

### 4.3 Worked example: Fs.Write

The current `Fs.Write` ForeignNode declares:

```
ForeignNode {
  target: "strand-builtin:Fs.Write"
  signature: (path: StringT, bytes: BytesT) -> IntT
  effects: [Filesystem.Write]
}
```

After migration:

```
ForeignNode {
  target: "strand-builtin:Fs.Write"
  signature: (path: StringT, bytes: BytesT) -> IntT
  effects: [Filesystem.Write]
  effectProjections: [
    EffectProjection(
      category: Filesystem.Write,
      sources: [ArgRef(0)]   // path parameter = function argument 0
    )
  ]
}
```

At every `Application` of Fs.Write, the verifier requires that `Application.effectInstances[Filesystem.Write].parameters[0]` is the same NodeId as `Application.arguments[0]`. At runtime, the capability check uses `eval(Application.arguments[0])` as the path value — guaranteed to be the same value the builtin will receive.

### 4.4 Worked example: Anthropic.Messages.Create (per-provider LLM)

The current binding ships with `effects: [LLM.Generate]` and Q-037 Phase 1's convention of authored EffectDecls at every call site carrying the literal "anthropic" plus the user-supplied model. After migration:

```
ForeignNode {
  target: "strand-builtin:Anthropic.Messages.Create"
  signature: (model: StringT, ...) -> AnthropicResponseT
  effects: [LLM.Generate]
  effectProjections: [
    EffectProjection(
      category: LLM.Generate,
      sources: [
        LiteralNode(StringLit("anthropic")),  // provider = "anthropic" (binding-pinned)
        ArgRef(0),                            // model = function argument 0
      ]
    )
  ]
}
```

An agent cannot spoof `provider: "openai"` via a forged EffectDecl literal — the verifier rejects any EffectDecl whose first parameter is not a literal `StringLit("anthropic")`.

## 5. Verifier rules

New `VerifyError` variants:

```
ProjectionArityMismatch(
    at: NodeId,                  // ForeignNode or FunctionType
    expected: Int,               // effects.size
    actual: Int,                 // effectProjections.size
)

ProjectionCategoryMismatch(
    at: NodeId,
    index: Int,
    declaredCategory: NodeId,    // effects[index]
    projectedCategory: NodeId,   // effectProjections[index].category
)

ProjectionSourceArityMismatch(
    at: NodeId,
    categoryIndex: Int,
    expected: Int,               // EffectCategory.parameters.size
    actual: Int,                 // projection.sources.size
)

ProjectionArgRefOutOfRange(
    at: NodeId,
    categoryIndex: Int,
    sourceIndex: Int,
    requested: Int,              // ArgRef.index
    maxAvailable: Int,           // signature.parameters.size - 1
)

ProjectionLiteralNotConstant(
    at: NodeId,
    categoryIndex: Int,
    sourceIndex: Int,
    target: NodeId,              // LiteralNode.target — not actually a literal
)

ProjectionLiteralTypeMismatch(
    at: NodeId,
    categoryIndex: Int,
    sourceIndex: Int,
    expected: TypeExpr,          // category parameter type
    actual: TypeExpr,            // literal node's type
)

ProjectionMismatch(
    at: NodeId,                  // Application
    categoryIndex: Int,
    sourceIndex: Int,
    expected: ProjectionSource,
    actualParam: NodeId,         // EffectDecl.parameters[sourceIndex]
)
```

Verification algorithm at ForeignNode / FunctionType admission:

1. If `effectProjections` is empty: accept (legacy path).
2. Otherwise check `effects.size == effectProjections.size`.
3. For each `(category_i, projection_i)`: check `effects[i] == projection_i.category` (or fail).
4. For each projection: check `sources.size == EffectCategory.parameters.size`.
5. For each source: validate `ArgRef.index < signature.parameters.size`; for `LiteralNode`, verify the target resolves to a literal node of the right type.

Verification at Application sites whose callee is a projected function:

1. If `Application.effectInstances` is empty: accept (the runtime synthesizes).
2. Otherwise, for each projection-source / EffectDecl-parameter pair:
   - `ArgRef(j)`: `EffectDecl.parameters[k] == Application.arguments[j]` by NodeId equality.
   - `LiteralNode(t)`: `EffectDecl.parameters[k]` is a literal node whose canonical form equals `t`'s canonical form.
3. On mismatch: emit `ProjectionMismatch`.

Step 2 allows authored EffectDecls only if they exactly match what the projection would synthesize. This keeps backward compatibility with corpus programs that author EffectDecls today while preventing drift.

## 6. Interpreter / runtime semantics

At each `Application` whose callee resolves to a projected function (ForeignNode with non-empty `effectProjections`):

1. Evaluate `Application.arguments` to a list of `Value`s (as today).
2. For each projection `effectProjections[i]`:
   - Build a `List<Value>` by mapping each source: `ArgRef(j)` → `argumentValues[j]`; `LiteralNode(t)` → evaluate the literal node to a `Value`.
   - Emit the pair `(category_i, parameterValues)` into the `instances: Map<NodeId, List<Value>>` consumed by `checkCapabilities`.
3. Run `checkCapabilities(at, declared = signature.effects, instances, context)` exactly as today.
4. Dispatch the foreign call with `argumentValues`.

The key invariant: step (2)'s `argumentValues[j]` for an `ArgRef(j)` source *is* the same Value the foreign code receives in step (4). No drift is possible.

For ForeignNodes without `effectProjections`, the existing Q-031 path runs unchanged: `evalEffectInstances` evaluates the authored EffectDecls; the gap from Finding 1 persists for those bindings until migration completes.

## 7. Test scenarios

1. **Projection synthesis: happy path.** Fs.Write with `effectProjections=[{Filesystem.Write, [ArgRef(0)]}]`. Call `Fs.Write("/safe", bytes)` under context grant `Filesystem.Write{path: "/safe"}`. Expected: success; capability check sees `/safe`.
2. **Projection synthesis: drift attempt blocked.** Same binding. Call `Fs.Write("/etc/shadow", bytes)` under grant `Filesystem.Write{path: "/safe"}`. Expected: `RefinementViolation` at the inner call (no drift possible; capability check sees `/etc/shadow`).
3. **Authored EffectDecl matches projection.** Application.effectInstances explicitly carries `Filesystem.Write{path: <same NodeId as arguments[0]>}`. Expected: success; verifier accepts the authored form because it matches.
4. **Authored EffectDecl drifts from projection.** Application.effectInstances carries `Filesystem.Write{path: StringLit("/safe")}` (a fresh literal node) while arguments[0] is `StringLit("/etc/shadow")` (a different node). Expected: `ProjectionMismatch` at the verifier.
5. **Pinned provider literal.** Anthropic.Messages.Create with `effectProjections=[{LLM.Generate, [LiteralNode(StringLit("anthropic")), ArgRef(0)]}]`. An Application whose EffectDecl carries `provider: StringLit("openai")` → `ProjectionMismatch`. The agent cannot spoof a different provider.
6. **Projection arity mismatch caught.** ForeignNode declares `effects: [Network.Connect]` (two parameters) with `effectProjections=[{Network.Connect, [ArgRef(0)]}]` (one source). Expected: `ProjectionSourceArityMismatch` at admission.
7. **Projection ArgRef out of range.** Signature has two parameters; projection uses `ArgRef(5)`. Expected: `ProjectionArgRefOutOfRange`.
8. **Projection literal type mismatch.** Category parameter is `Int`; projection source is `LiteralNode(StringLit("..."))`. Expected: `ProjectionLiteralTypeMismatch`.
9. **Legacy ForeignNode without projection.** Existing Fs.Write without `effectProjections`. Application with authored EffectDecl. Expected: existing Q-031 behavior, no new verifier errors. Marks the unmigrated case for explicit follow-up.
10. **Closure propagation through intermediate Lambda.** Lambda calls Fs.Write internally; outer call's authored effectInstance lies about the path. Inner Fs.Write call uses projection synthesis; capability check at inner site fires with the real path. Expected: `RefinementViolation` at the inner Fs.Write Application — confirming the security property survives Lambda intermediation.
11. **Multi-source projection.** Net.Connect with `effectProjections=[{Network.Connect, [ArgRef(0), ArgRef(1)]}]`. Call with `host="api.example.com", port=443`. Expected: synthesized `[StringV("api.example.com"), IntV(443)]`; capability check uses these.
12. **Hash invariance.** Existing corpus program with no `effectProjections` field hashes byte-identically to its pre-Q-039 form.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Lambda-level projections.** A Lambda whose body calls a ForeignNode does not need projections of its own — the security check fires at the inner ForeignNode call. However, Lambdas that appear in higher-order contexts (passed as values, stored in records) can mislead policy auditors via their authored effectInstances. Tightening Lambda is a follow-up; the natural mechanism is to populate `FunctionType.effectProjections` whenever a Lambda's body has a unique projection (a small inference pass). Out of scope here.
- **Http.Request signature redesign.** The current `Http.Request(url, method, headers, body)` signature has the (host, port) refinement values embedded in a parsed URL string, not directly available as function arguments. A clean projection requires either (a) splitting the binding into `(host, port, path, method, headers, body)` and forcing the caller to pre-parse, or (b) extending ProjectionSource with a small deterministic expression vocabulary (`HostOfUrl(arg(0))`). Option (a) is preferred for security clarity; option (b) opens a slippery slope. The proposal flags Http.Request as requiring a separate binding-redesign slice — Q-041 (SSRF and path-sandboxing) is the natural pairing.
- **Derived projection sources.** Beyond `ArgRef` and `LiteralNode`, one can imagine `Projected(arg(0).field("host"))` or `Selected(arg(0), 2)` for record/list arguments. These are deferred until concrete bindings need them; the proposal keeps V1's vocabulary minimal.
- **Refinement-narrowing on CapabilityScope.** The Q-031 proposal § 9 deferred this; the same deferral applies here. Projections do not currently interact with CapabilityScope narrowing beyond category-level intersection.
- **Wildcard-only effect categories.** Categories like `Time.Now` and `Crypto.RandomBytes{length}` have parameters but the refinement vocabulary in practice is "any". Projections still apply mechanically (Time.Now has zero parameters; Crypto.RandomBytes projects `length` from arg(0)) but the security benefit is marginal for these. Migration applies them anyway for consistency.

**Real research questions:**

- *Inference for Lambda projections.* When a Lambda's body has exactly one call to a single ForeignNode, the Lambda's projection is structurally derivable. When the body has multiple calls or non-trivial control flow, projection inference is undecidable in general. The Q-034 elaborator pattern (best-effort static inference, fall back to authored explicitness) is a candidate.
- *Multi-effect interactions.* A single Application calls a function with multiple declared effects (e.g., `Filesystem.Write{path}` and `Time.Now{}`). The projections are independent per category, but the call's argument shape may not naturally support both. The proposal scopes this as out-of-band (each effect's projection is independent); large-scale binding catalogs may surface cases where this is awkward.
- *Cross-binding consistency.* If `Fs.Write` and `Fs.Append` are separate bindings that share the same effect category `Filesystem.Write`, both must declare consistent projections (path = arg(0)). The verifier does not currently cross-check this; a registry-level lint is a Q-006 (foreign binding trust) follow-up.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `core/Node.kt` | Add `effectProjections: List<EffectProjection> = emptyList()` to `Node.ForeignNode` and `Node.FunctionType`. Add `EffectProjection` and `ProjectionSource` data classes. | Small |
| `core/Json.kt` (ingest) | Parse the new field. Each projection's `category` is a string author-id; each source is `{kind: "ArgRef", index: N}` or `{kind: "LiteralNode", target: "<author-id>"}`. Default empty list. | Small |
| `hashing/CanonicalEncoder.kt` | Encode `effectProjections` only when non-empty. Add `encodeEffectProjection` and `encodeProjectionSource` helpers. Preserves all existing corpus hashes. | Small |
| `verifier/VerifyError.kt` | Add the seven new variants from § 5. | Small |
| `verifier/Verifier.kt` | New `validateProjections(at, effects, effectProjections, signature)` called from `inferForeignNode` and `inferFunctionType`. New per-Application check `validateProjectionMatch(at, app, projectedFn)` called from `inferApplication` when the callee has projections. | Medium |
| `interpreter/Interpreter.kt` | In `applyForeign` (or wherever Application dispatch lands on a ForeignNode callable), synthesize the `instances: Map<NodeId, List<Value>>` from the function's projections + the evaluated `argumentValues`, rather than from `evalEffectInstances` if the function has projections. Keep the legacy path for projection-less callables. | Medium |
| `interpreter/Builtins.kt` | Add `effectProjections` declarations to every registered builtin with parameterized effects. Initial slice: `Fs.Read`, `Fs.Write`, `Fs.Append`, `Fs.Exists`, `Fs.Delete`, `Fs.List` (all use `[ArgRef(0)]` for `Filesystem.Read`/`Filesystem.Write`); `Net.Connect` (`[ArgRef(0), ArgRef(1)]`); `Crypto.RandomBytes` (`[ArgRef(0)]`); per-provider LLM and Vector bindings with their pinned-provider literals. Http.Request, Process.Spawn, Crypto.Sign/Encrypt/Decrypt deferred (see § 8 and Q-041). | Medium |
| `verifier/test/VerifierTest.kt` | Add tests for each new VerifyError variant (scenarios 4–8 from § 7). | Medium |
| `interpreter/test/InterpreterTest.kt` | Add runtime tests for synthesis path (scenarios 1, 2, 10, 11). | Medium |
| `corpus/` | Add 2 new corpus programs: one happy-path (canonical projection on Fs.Write) and one negative (ProjectionMismatch for an attempted drift). Programs 69 and 70. Hash-invariance test for all unchanged corpus programs (scenario 12). | Small |
| `authoring/LayerAGrammar.kt` | Extend the FN code (and FRN/FNT) to accept the new `effectProjections` field in Layer A density form. `EFP` (EffectProjection) and source sub-codes if needed. | Small-medium |
| `evaluation/dynamic/prompts/strand-system.md` | Document the new projection mechanism in the agent-facing prompt so models emit ForeignNodes with projections. | Small |
| `INDEX.md`, `open-questions.md`, `security-index.md`, `impl-kotlin/CLAUDE.md` | Q-039 registration, status updates, cross-references. | Small |

**Order of work.** (1) Schema and canonical encoding additions, with byte-identity tests for unchanged corpus. (2) Verifier rules with all seven new error variants. (3) Runtime synthesis path in the interpreter. (4) Migration of `Fs.*` builtins as the first concrete slice. (5) Migration of `Net.Connect`, `Crypto.RandomBytes`, then per-provider LLM and Vector. (6) Layer A grammar extension. (7) System-prompt documentation. (8) Defer Http.Request, Process.Spawn, Crypto.Sign/Encrypt/Decrypt to Q-041's binding redesign slice.

**Not in this slice.**

- Lambda-level projections (`FunctionType.effectProjections` carried into Lambda type inference).
- Derived projection sources (`HostOfUrl(arg(0))` etc.).
- Http.Request, Process.Spawn redesign (deferred to Q-041's I/O sandboxing work).
- Cross-binding projection consistency lint (Q-006).
- Refinement-narrowing CapabilityScope (Q-031 § 9 follow-up).

## References

**Outgoing references:**
- [`design/effects-and-capabilities.md`](../design/effects-and-capabilities.md) — § Effect closure semantics, § Capability mechanism, § Confused deputy mitigation
- [`design/security-model.md`](../design/security-model.md) — § Confused deputy synthesis (the property this proposal restores)
- [`decisions/ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — effect declarations as graph topology
- [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — ForeignNode as the security boundary
- [`proposals/implemented/refinement-lattice-capability-matching.md`](implemented/refinement-lattice-capability-matching.md) — Q-031, prior art for the EffectDecl mechanism
- [`security-index.md`](../security-index.md) — Finding 1, the audit entry that motivated this proposal
- [`open-questions.md`](../open-questions.md) — Q-005, Q-031, Q-039

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-039 points at this proposal
- [`proposals/README.md`](README.md)
- [`security-index.md`](../security-index.md) — Q-039 row links here
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section
