# Effect-set inclusion at value-flow arrows

**Document:** `proposals/implemented/effect-set-inclusion.md`
**Status:** Implemented 2026-07-05
**Question:** [Q-049](../../open-questions.md#Q-049) (resolves)
**Related:** [Q-062](../../open-questions.md#Q-062) (encoding epochs — schema-level `bound` removal), [Q-034](../../open-questions.md#Q-034) (Layer C elaboration), [`design/node-algebra.md`](../../design/node-algebra.md)
**Drafted:** 2026-07-05

## Problem

The verifier decides type compatibility by strict structural equality with a single relaxation at `SchemaType` boundaries. Two frictions follow from the strict discipline, both named by [Q-049](../../open-questions.md#Q-049).

The sharper one is effect-exact function-type equality at higher-order positions. Two function types compare equal only when their parameters, result, and declared effect row all match, so a pure `(Int) -> Int` cannot flow into a callback parameter declared `(Int) -> Int ! {someFx}` — although that direction is always safe, because a function that performs fewer effects than a position expects can only under-shoot the position's declared reach. Agents generating higher-order code (a pure lambda into a `List.Map` callback slot, a pure transition into an effect-declaring position) hit this immediately, and the only workaround is to over-declare the pure function's effect row to match the position exactly, which is both awkward and dishonest about what the function does.

The second is `TypeParameter.bound`. The field is parsed and carried on the ADT but silently ignored: the verifier neither checks the bound at instantiation sites nor consults it during compatibility. An agent that writes bounded polymorphism therefore receives no checking and no error — a silent acceptance of a construct the language does not honor. Q-049 calls this "worse than rejection".

## Recommended approach

Adopt the smallest of Q-049's candidate resolutions, precisely scoped in three parts.

**Effect-set inclusion at the outermost arrow, at value-flow sites only.** When a function-typed value flows into a function-typed expected position — at the three sites where the verifier already relaxes strict equality for the `SchemaType` boundary: an Application argument, a ProductFieldValue, and a SumValue payload — the position is accepted when the parameter types and the result type are strictly equal (existing discipline, including the `SchemaType` and equirecursive relaxations that already apply there) and the actual function's declared effect set is a *subset* of the expected function's effect set. Direction matters: a function with fewer effects than the position declares is always safe (the canonical friction case — a pure lambda into an effect-row callback parameter); the reverse (actual effect set exceeding expected) stays rejected. The relaxation applies only at the outermost arrow of the compared types. A function type nested inside a product field of the compared types, or in the parameter position of the compared arrows, keeps strict equality this slice — the general variance machinery of subtyping (contravariant argument and covariant result positions) is explicitly deferred.

**Structural-equivalence sites stay strict.** Match case-body divergence, Fixpoint body shape, Handler signature agreement, StateMachine transition shape, and ToolDef implementation type continue to compare by strict equality. These are equivalence checks, not assignment-compatibility checks, and are not widened.

**Non-null `TypeParameter.bound` is a hard verify error.** A new `VerifyError.TypeParameterBoundUnsupported(at, bound)` fires when a TypeParameter carries a non-null bound. The field stays on the ADT and in ingest so the error can fire with a good message; it was never part of the canonical encoding — `CanonicalEncoder.encodeBoundTypeParameter` encodes a TypeParameter as positional `(depth, index)` refs only, with no bound field emitted — so rejecting a non-null bound is hash-neutral and needs no encoding epoch. Schema-level removal of the field rides a future epoch per [Q-062](../../open-questions.md#Q-062) (unchanged).

**Soundness.** Effect-set inclusion does not weaken the effect-closure bound of [Q-044](../../open-questions.md#Q-044). `inferApplication` adds the *declared* effect row of the resolved callee/parameter type at each call site (`closure += funType.effects`), so a pure function accepted at an effectful position over-approximates — the closure keeps the position's larger declared row rather than the function's smaller actual row. Widening acceptance therefore never shrinks the statically computed harm bound.

**Layer C interaction.** The Layer C Elaborator ([`authoring/Elaborator.kt`](../../impl-kotlin/authoring/src/main/kotlin/org/strand/authoring/Elaborator.kt)) infers `Lambda.effects` from the body's effect closure and fills in other absent annotations before verification. Its inference is unchanged by this slice: no inference case performs a compatibility check that depends on effect-exact equality, so widening the verifier's *acceptance* cannot change what the elaborator *infers*. Inclusion only widens what the verifier admits after elaboration; nothing the elaborator produces becomes newly ambiguous.

## Where it lives

- `impl-kotlin/verifier/src/main/kotlin/org/strand/verifier/Verifier.kt` — `typesCompatibleAtValueFlow`, the value-flow helper, plus the `TypeParameterBoundUnsupported` rejection at TypeParameter admission.
- `impl-kotlin/verifier/src/main/kotlin/org/strand/verifier/VerifyError.kt` — the new `TypeParameterBoundUnsupported` variant.
- `design/node-algebra.md` § Type system and § Well-formedness — the specification of the two value-flow relaxations and the bound rejection.

## Deferred and open

- Nested-arrow variance (contravariant argument / covariant result positions on a function type nested below the outermost arrow) is deferred. Only the outermost arrow of the compared types is relaxed this slice.
- Full structural subtyping — width subtyping on products, general depth variance — is not adopted. Q-049's larger candidate resolution remains available for a future increment.
- Schema-level removal of `TypeParameter.bound` rides a future encoding epoch per [Q-062](../../open-questions.md#Q-062). This slice rejects a non-null bound but leaves the field on the ADT and in ingest (hash-neutral).

## Scope

Small. One value-flow compatibility helper, one new verify-error variant, verifier-only, hash-neutral. It removes the sharpest higher-order friction Q-049 identified and turns a silently-ignored field into a checked rejection, without adopting variance or touching the canonical encoding.

## References

**Outgoing references:**
- [`design/node-algebra.md`](../../design/node-algebra.md) — § Type system and § Well-formedness, where the discipline is specified
- [`open-questions.md`](../../open-questions.md) — Q-049 (resolved via effect-set inclusion), Q-062 (`bound` removal epoch), Q-034 (Layer C), Q-044 (harm bound)

## Implementation note

Built in `impl-kotlin/`. Additive and hash-neutral, verifier-only; the interpreter, VM, runtime, canonical encoder, epoch constant, and prelude are untouched.

Compatibility helper (`verifier/src/main/kotlin/org/strand/verifier/Verifier.kt`):
- `typesCompatibleAtValueFlow(expected, actual)` is `typesCompatible` plus one further step. If strict compatibility fails, it strips a `SchemaType` wrapper on either side to its `valueType`, and when both sides are then `TypeExpr.Fun` it accepts iff the parameter types are pairwise equal (via `typesCompatible`, which preserves the `SchemaType` / equirecursive relaxations but adds no variance), the result types are equal (same), and `expected.effects.containsAll(actual.effects)` — subset inclusion at the outermost effect row only. Nested arrows flow through the plain `typesCompatible` calls on parameters and result, so they stay effect-exact.
- The three value-flow call sites were switched to `typesCompatibleAtValueFlow`: `inferApplication`'s argument check, `inferProductValue`'s field-value check, `inferSumValue`'s payload check.

Caller audit. Every caller of `typesCompatible` was checked before the switch. Nine textual occurrences: the `fun typesCompatible` definition; three internal uses inside `typesCompatibleAtValueFlow` (the strict fallback and the pairwise parameter / result comparisons); the three value-flow sites (now on the new helper); and two strict sites that stay on `typesCompatible` — the ToolDef implementation-parameter check (`inferToolDef`, comparing the schema `valueType` against the implementation's parameter type) and the Q-039 EffectProjection literal check (comparing an effect-category parameter type against a literal argument). Both strict sites compare non-function types in practice, but leaving them on strict `typesCompatible` guarantees no accidental effect relaxation. No other node category calls the helper.

Bound rejection (`Verifier.kt`, `VerifyError.kt`):
- `VerifyError.TypeParameterBoundUnsupported(at: NodeId, bound: NodeId)` follows the sealed-hierarchy conventions, placed after `UnboundTypeParameter`.
- Fired at three points so a bounded parameter is caught whether or not the body references it: in `resolveType`'s `Node.TypeParameter` case (before the unbound-scope check, so the diagnostic names the agent's actual intent even when the parameter is also out of scope), and in the `Node.ForallType` / `Node.TypeAbstraction` declaration loops (an unreferenced bounded parameter is never resolved, so it must be caught at its declaration site).
- Verified hash-neutral against `CanonicalEncoder.kt`: `encodeBoundTypeParameter` emits only the `(depth, index)` positional pair; the `bound` field is absent from the encoding, so its presence or rejection moves no hash.

Tests: `verifier/src/test/kotlin/org/strand/verifier/EffectSetInclusionTest.kt`, 7 tests — (1) a pure `(Int) -> Int` lambda flows into an effectful `(Int) -> Int ! {someFx}` Application-argument callback and verifies; (2) the reverse (an effectful lambda into a pure callback parameter) is rejected with `ParameterTypeMismatch`; (3) a nested-position arrow keeps strict equality — a whole product value flowing into an Application argument, differing only in a nested function field's effect row, is rejected (the pin that variance is not adopted); (4) effect-set inclusion at a SumValue payload arrow; (5) a non-null `TypeParameter.bound` is a hard `TypeParameterBoundUnsupported`; (6) the same program with the bound removed verifies unchanged; (7) an unreferenced bounded quantified parameter is caught at the declaration site. Handler / Fixpoint / StateMachine strictness is already pinned by the existing verifier tests and needed no extension (no gap).

Corpus: one additive program, `corpus/92-pure-callback-into-effectful-param.json` (runnable → `IntV(42)`). A higher-order `apply2 : ((Int) -> Int ! {Time.Now}, Int) -> Int` invokes its callback parameter; the program passes a pure `(Int) -> Int` incrementer into that effectful position, runs under a `{Time.Now}` grant (the callback's declared row is what the runtime gates on, so `apply2` declares it), and evaluates to `apply2(pureInc, 41) = 42`. It is the first corpus program to demonstrate the pure-into-effectful-callback flow end-to-end. Paired natural-language description in `corpus/README.md` and the `CorpusTest` case string.

Golden impact: one additive golden entry for corpus 92; `CorpusGoldenHashTest` shows zero pre-existing hash movement (regenerated with `-Dstrand.regenerateGoldenHashes=true`, diff confirms a single added line). No epoch change.

Deviations from the brief: none material. The brief allowed the interpreter-and-VM run of the positive corpus program "if easy"; corpus 92 runs under the interpreter through `CorpusTest` (the VM equivalence set was not extended, matching the treatment of other capability-gated corpus programs). The two strict `typesCompatible` sites named in the brief (ToolDef implementation type, plus the EffectProjection literal check the audit surfaced) were left strict as specified.
