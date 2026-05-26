# Layer A density — reducing emission cost further

**Document:** `proposals/implemented/layer-a-density.md`
**Status:** v1 through v4 implemented 2026-05-25 in an extended session
across eleven git commits. Geomean dropped from a 2.20× Python+type-hints
baseline (re-measured post-cleanup) to **0.81× across the three-task MVP**
— well below conventional-language baseline cost on bytes-as-proxy-for-
tokens, and beyond the proposal's 1.30× projected floor for the non-
tokenizer-aligned stack. The v4 work added one new authoring-grammar
slice (Slice 10 nested expressions) plus seven new Elaborator inference
cases beyond the original four Layer C cases from Q-034 step 1.
**Date:** 2026-05-25
**Concerns:** [`evaluation/results.md`](../../evaluation/results.md),
[`impl-kotlin/authoring/`](../../impl-kotlin/authoring/),
[Q-021](../../open-questions.md#Q-021), [Q-034](../../open-questions.md#Q-034)

> **Implementation note (2026-05-25).** All 10 slices plus seven
> Elaborator inference extensions landed in one session across eleven
> git commits (v1, v1.5, v2, v2.5, v3, plan promotion, cleanup pass,
> v4 nested expressions, v4 deeper type inference, v4 integration-gap
> follow-up — the first five per the Recommended shipping order, the
> last six surfaced during implementation). Geomean trajectory across
> the three-task evaluation suite (factorial / json-value / toggle):
>
> | Form | factorial | json-value | toggle | geomean |
> |------|----------:|-----------:|-------:|--------:|
> | Strand Layer A (baseline, post-cleanup) | 4.81× | 1.12× | 2.00× | 2.20× |
> | v1 (Slices 1+2+3) | 2.30× | 0.91× | 1.12× | 1.33× |
> | v1.5 (+ Slice 4 IF) | 1.50× | 0.91× | 1.12× | 1.15× |
> | v2 (+ Slices 5+6+7) | 1.29× | 0.86× | 1.00× | 1.03× |
> | v2.5 (+ Slice 8) | 1.29× | 0.86× | 0.88× | 0.99× |
> | v3 (+ Slice 9 WHEN) | 1.29× | 0.86× | 0.88× | 0.99× |
> | v4 (+ Slice 10 nested + 7 inference cases) | 0.87× | 0.81× | 0.76× | 0.81× |
>
> All shipping increments preserved the additive-versioning property:
> the density-vN corpus fixtures plus the broader test suite remain
> green; canonical dag-json output for programs that do NOT use any new
> sugar is byte-identical to today's. The baseline column reflects the
> re-measurement after the v3-to-v4 cleanup pass retired the legacy
> hand-authored Layer A fixtures and the JSON→Layer A reverse-translation
> surface; the 2.20× there replaces the 2.28× figure quoted before the
> retirement, with the small shift due to corpus-mix differences and not
> to any change in the v1-v3 emitter behavior.
>
> **Plan deviations worth recording.**
>
> *(1) Slice 3 auto-VarRef restricted to PRC binders only.* The plan
> flagged "extend to Let binders?" as an open question. The resolved
> answer is **no** — a bare Let-id in a value-position slot is ambiguous
> between "the Let expression as a sub-tree" (structural use; corpus
> programs 06, 07, 10 rely on this) and "the value bound by Let letId"
> (name lookup). Auto-VarRef on LET breaks the structural-use programs.
> PRC binders are unambiguous so the rule is safe there. The
> `EmitContext.binderIds` set filters to PRC-only with a comment citing
> the corpus programs that motivated the choice.
>
> *(2) Slice 4 IF and Slice 9 WHEN both carried a `sugarOnly` marker on
> their `CodeSchema` during v1.5-v3.* Discovery during the v1.5 fixup:
> the now-deleted `LayerATranslator` (JSON → Layer A reverse direction)
> picked the first code with matching `jsonType`, and with two codes
> mapping to `jsonType="Match"` (canonical `MAT` plus IF/WHEN), it picked
> the wrong one. Adding `sugarOnly: Boolean = false` to `CodeSchema` and
> skipping sugar-only codes in `LayerATranslator.resolveCode` resolved
> the ambiguity in the reverse direction without affecting forward
> emission. The cleanup pass deleted both the translator and the marker;
> v4 emission keeps the canonical-MAT-when-possible behavior implicitly
> because there is no longer a reverse direction to disambiguate.
>
> *(3) Slice 5 compact LAM params synthesize PRCs whose author id IS the
> parameter name.* The plan suggested `__param_<idx>` ids; using the
> parameter name directly means a Lambda body's reference to `x` Just
> Works through the existing Slice 3 auto-VarRef path. The `binderIds`
> pre-pass scans LAM nodes for compact-form entries to populate the
> binder set.
>
> *(4) Slice 8 reuses `[...]` brackets rather than the plan's
> `{name=ref ...}` curly-brace syntax.* The existing tokenizer already
> accepts `=` inside bare tokens (so `name=ref` lexes as one `Arg.Bare`),
> so `[...]` works without parser changes. The dag-json output is
> identical either way; user-facing surface is purely cosmetic.
>
> *(5) Slice 9 WHEN takes a quoted-string case list rather than
> `[CaseName(b) -> body | ...]` brackets.* The existing tokenizer
> doesn't recognize `|` / `->` / parens; introducing them as separators
> would be a deep parser change. Using a STRING sidesteps the parser
> work and the dag-json output is identical to the explicit form.
> Trade-off: `ConstraintGrammar` (Layer B GBNF) overapproximates the
> case-list content as any string. A tighter LLM-side constraint
> requires either a sub-grammar or the bracketed form's parser work;
> left as a follow-up.
>
> *(6) Slice 7 anonymous-id mechanism uses `_` (the existing null marker
> token in the id position) and `@last` to reference the most recent
> anonymous declaration.* The plan offered (a) positional-only or (b)
> `@last`; this implementation shipped (b) because the @last form
> generalizes to non-adjacent references. Anonymous ids are minted as
> `__anon<lineNum>` so re-parsing the same source produces identical ids.
>
> *(7) Slice 6 (inline literal in LiteralPattern) is subsumed by Slice
> 2's general "inline literal at REFERENCE slot" rule.* Slice 2's
> emitter logic already accepts an IntL/FloatL/BoolL/Str token in any
> REFERENCE / LIST_REF / NULLABLE_REF slot, including `PLT.literal`. No
> additional code was needed. The IF expansion implicitly exercises this
> when it synthesizes `Pattern{kind=literal, literal=<inline BoolLit
> ref>}`. Documented as a sub-property of Slice 2 rather than a
> standalone slice.
>
> *(8) Slice 10 nested expressions reuse the `(...)` parenthesization
> token already present in the tokenizer alongside an `Arg.Nested(code,
> args)` variant on `LayerADocument` rather than a separate s-expression
> mode.* The recursive `readNested()` lexes `(CODE arg arg ...)` with
> args following the same shape rules as top-level args, so every
> Slice 1-9 emit-time mechanism (reserved-name resolution, inline
> literals, auto-VarRef, IF/WHEN sugar) composes at the recursive
> `emitNode()` call. A new `CodeSchema.producesValue` flag (true for
> value-producing codes ILT/FLT/STR/BLT/ULT/BYT/LAM/APP/LET/VAR/NRF/TAB/
> CAP/FN/IF/WHEN/MAT/FIX/PV/PFG/SV/H; false for type-only PRM/FNT/PRD/
> SUM/FAL/TPM/RT/RS and structural PRC/MC/EFC/EFD/ESE/ESI/ESO/TR/SM/SCH/
> INV/Pattern variants) gates which codes are legal at expression
> positions, with `ArgShapeMismatch` reported for the rest. FIELD_LIST
> required one additional parser tweak so a compact-form entry
> `name=(NESTED)` lexes as a trailing-`=` bare token paired with the
> following parenthesized value rather than as a single bare token.
>
> *(9) Slice 10's IF expansion interacts with nested expressions at the
> scrutinee/then/else slots.* The expansion path already accepts a
> single REFERENCE arg per slot, and `resolveExpressionRef` was extended
> to recognize nested forms there. Factorial's v4 fixture uses a single
> IF with three nested `APP` chains (the `eqInt` test plus the two
> branch expressions), collapsing the conditional scaffold to one line.
>
> *(10) Compact-LAM-param inference in the json-value v4 fixture keeps
> the `jv:jsonValueSchema` annotation explicit.* The call site provides
> a value of type `jsonValueT`, not the `SchemaType`-wrapped
> `jsonValueSchema` referenced by the canonical's `paramType`. Inferring
> `jv: jsonValueT` from the call site would hash differently than the
> canonical; bidirectional inference cannot resolve the SchemaType↔T
> ambiguity without unification, which is out of scope per Q-034's
> design boundary. The annotation stays in source.
>
> **What this work doesn't ship** (per plan §"What this plan deliberately
> doesn't ship", all still deferred):
> - Library / import mechanism (general `@use <hash>`)
> - Operator-like sugar (`(eq n 0)` s-expression form — Slice 10's
>   nested-expression form lands `(APP eq [n 0])` instead, keeping
>   the explicit code prefix at every nested node)
> - Tokenizer alignment (Q-034 §3.3 Phase 4)
> - Tool-call assembly as alternative interface (Q-034 §3.6)
> - Nested constructor patterns and or-patterns in WHEN
> - Unification-based inference (the SchemaType↔T case in v4 json-value
>   would need it)
> - The single concrete project-scope follow-up surfaced during
>   implementation: a sum-consumer task in the evaluation MVP would let
>   WHEN's compression register in the headline geomean. Today none of
>   the three MVP tasks consume sum types, so WHEN's value is visible
>   only in corpus fixtures.
>
> All increments preserved the constraint-list: no changes to
> `impl-kotlin/core/`, `impl-kotlin/verifier/`, `impl-kotlin/interpreter/`, `impl-kotlin/hashing/`,
> `impl-kotlin/schema/`, `impl-kotlin/runtime/`, `impl-kotlin/bytecode/`, `impl-kotlin/vm/`, or the
> canonical CBOR encoder. All work lives in `impl-kotlin/authoring/` + its
> tests + corpus fixtures + evaluation files.
>
> **What got added beyond the original plan.** The original plan
> specified nine slices stopping at v3. Three follow-on shipping units
> landed during the same session and were folded into the implementation
> note above rather than spun off as separate proposals.
>
> *Cleanup pass.* With the elaborate-then-emit pipeline established as
> the only supported authoring path, the legacy JSON→Layer A reverse-
> translation surface was retired. Deleted: `LayerATranslator.kt`,
> `LayerARenderer.kt`, `LayerATranslatorTest.kt`, `LayerARoundTripTest.kt`,
> 33 hand-authored `*.layer-a` corpus fixtures directly under
> `corpus/layer-a/`, the `corpus/layer-a/elaborated/` subdirectory, the
> `--elaborate` flag on `strand author` (elaboration is now always-on),
> the `strand translate` CLI subcommand, the `sugarOnly` field on
> `CodeSchema` (only the reverse translator consumed it). The density-vN
> fixtures plus `LayerADensityTest` are the surviving regression net for
> Layer A authoring.
>
> *Slice 10 nested expressions.* New `Arg.Nested(code, args)` variant in
> `LayerADocument` extends the grammar so `(CODE args...)` can appear
> inline inside `[...]` lists and at REFERENCE / NULLABLE_REF slots. The
> parent expression's emitter calls
> `DagJsonEmitter.synthesizeNestedIfNested()` which mints a fresh
> `__expr<n>` id and recursively re-enters per-code schema validation —
> reserved-name resolution, inline literals, auto-VarRef, IF/WHEN sugar
> all compose at the recursive call. `CodeSchema.producesValue` gates
> which codes are legal at expression positions. FIELD_LIST gained a
> trailing-`=` pairing rule for `name=(NESTED)` entries. Six new
> `NestedExpressionTest` unit cases (basic shape, deep nesting, type-
> code rejection, structural-code rejection, auto-VarRef composition,
> FIELD_LIST integration); three new corpus fixtures under density-v4/;
> `~250 lines` of parser/emitter changes.
>
> *Seven new Elaborator inference cases (Layer C extensions).* The
> Q-034 step 1 proposal sketched four Layer C inference cases (Lambda
> effects, Application effectInstances defaulting, Application
> typeArguments, Lambda paramType). v4 extends `Elaborator` with seven
> more so most explicit type declarations can be elided: recursion-slot
> `paramType` (FIX body Lambda's first param fills from
> `FIX.recursionType`); FunctionType synthesis (a FIX whose
> `recursionType` references an undeclared name synthesizes the FNT
> with parameters from the body LAM signature after the recursion slot
> and result from the body's return type); SumTypeCase `caseType`
> inference from `SumValue` payload; compact-LAM param inference from
> call sites + reserved-name builtins + StateMachine `transitionFn`
> signature + Match scrutinee context + ProductFieldValue context +
> ProductFieldGet target. Extended internal `typeOfArg` covers
> foreign-call results, lambda bodies, SV/PV `ofType`, PFG field
> lookup, MAT/IF/WHEN first-case-body, NRF target, TAB body, with a
> reserved-name fallback for callees (so `add`/`not`/`eqInt`/etc.
> resolve through `LayerAGrammar.reservedNodes` when the document
> doesn't declare them). The passes run in a fixed-point loop with an
> 8-iteration defensive bound because earlier passes (recursion-slot,
> FNT synthesis) feed later ones (paramType call-site inference) and
> vice versa. Eight new `ElaboratorTest` cases cover each inference
> extension end-to-end.
>
> *Integration-gap follow-up.* The merge of Slice 10 and the seven new
> inference cases left a real gap: Agent B's compact-LAM-param
> inference walked `doc.nodes` looking for binder use-sites, but Agent
> A's nested expressions are stored as `Arg.Nested(code, args)` inside
> parent nodes' arg lists — so a param `n` referenced only inside
> `(APP eqInt [n 0])` was invisible to the inference scan. The fix
> added `Elaborator.allNodesIncludingNested(doc)` which surfaces every
> `Arg.Nested` as a synthetic `NodeDecl` with placeholder id
> (`__nested<n>`); the `callSitesByLam` table-build and the per-binder
> usage-site scan (`inferTypeForCompactParam`'s 2a/2b/2c/2d branches)
> both iterate that expanded list. After this fix, the toggle machine's
> compact LAM `[s e]` infers `s:boolT` from the nested `(APP not [s])`
> body and `e:unitT` from the StateMachine `transitionFn` context, and
> factorial's compact-LAM `[recurse n]` infers both slots without any
> annotation. Three-task geomean fell from 0.87× (post-Agent B alone)
> to 0.81× (post-follow-up).

**Context:** Q-034 step 1 fully implemented. `evaluation/measure.sh`
reports Strand Layer A at **2.28× Python+type-hints geomean** across
the three-task MVP (factorial 4.94×, JSON value 1.16×, toggle machine
2.05×). Canonical dag-json is **5.82×**. Q-034 §6 projected Layer A at
0.8–1.3× post-elaboration — the measured gap is larger than projected
on small programs because per-program structural overhead amortizes
poorly.

This plan addresses the diagnostic from the conversation: ~45% of the
factorial Layer A's bytes are mechanical wrapping (type/builtin
boilerplate + parameter/literal/VarRef declarations + repeated
pattern-with-literal pairs). Concrete density improvements in the
Layer A grammar can close most of the projection gap without changing
the canonical dag-json the verifier consumes.

## Goals

Primary: bring Strand Layer A's geomean ratio against Python+type-hints
below **1.7× at v1 (Slices 1+2+3)** and **at or below 1.3× across all
slices (1-8)** on the three-task suite (from 2.28× today). The 1.3×
endpoint matches Q-034 §6's projected floor for the non-tokenizer-
aligned stack — that projection assumed tokenizer alignment (deferred
to Phase 4 per §3.3) to go below, so 1.3× is the realistic ceiling
on bytes-as-proxy-for-tokens compression with grammar-only changes.

Secondary: per-task ratios under 3× across the suite (current worst
is factorial at 4.94×). v1 alone leaves factorial at ~3.5×; Slice 4
(IF sugar) is the smallest addition that drives every task under 3×
in one step.

Tertiary: the dag-json output of Layer A compilation is **byte-identical**
to today's compilation for any program that doesn't use the new
shorthand forms. This is the additive-versioning property the grammar
has preserved through every prior extension (slice 3.1's bufferSize,
slice 3.6's consumerMode, etc.). Slice 4's `IF` and Slice 8's
`{name=ref}` field list are particularly load-bearing here — they
synthesize multiple wrapper nodes per source line, and the synthesis
must match the canonical bytes of the hand-authored equivalents to
preserve hash stability for any program that round-trips between
the two forms.

## Non-goals

- Changing the canonical dag-json schema (the verifier's input is
  unchanged).
- Changing the node algebra (the underlying nodes are the same).
- Changing the constraint grammar's semantic-correctness floor
  (lexical/syntactic only — semantic constraints stay verifier-side).
- Tokenizer alignment (Q-034 §3.3 Phase 4 work; tracked separately).
- Tool-call assembly as the emission interface (Q-034 §3.6 — a
  different track that trades static per-emission cost against
  per-call validation latency; orthogonal to Layer A density work
  and measured against different metrics).
- Real model-API integration to validate dynamic metrics (Phase 1
  follow-up; this plan is bytes-only).

## Slices

Ordered by leverage. Each slice ships independently; later slices
multiply earlier ones' savings without depending on them.

### Slice 1 — Implicit prelude (~22% projected savings)

**Idea.** Every Strand program redeclares the same primitive type nodes
(`PRM Int`, `PRM Bool`, ...) and the same foreign-builtin
declarations (`FN "strand-builtin:Int.Add" addT` etc.). These are
universal. Reserve a fixed set of names — `intT`, `floatT`, `stringT`,
`boolT`, `unitT`, `bytesT`, plus the existing ~16 `Int.*`/`Bool.*`/
`String.*` builtins — and treat references to those names without local
declaration as references to synthetic nodes injected at emit time.

**Mechanism.**
1. New `LayerAGrammar.reservedNodes: Map<String, ReservedNode>` mapping
   each reserved name to its synthetic dag-json shape.
2. `LayerAParser` already allows undeclared references (any bare token
   could refer to a forward declaration). Resolution happens at
   `DagJsonEmitter.emit` time when it walks references against the
   declared-id set.
3. `DagJsonEmitter.emit` extension: before emitting, scan all
   `Arg.Bare` references; for any that match a reserved name AND aren't
   declared locally, append the synthetic node to the output dag-json
   document. The synthetic node gets the reserved name as its author id
   (the canonical dag-json author id is opaque, so reusing the reserved
   string is fine).
4. Round-trip integrity: the synthetic node has the same canonical
   bytes as a hand-authored equivalent (same `type` + `kind` JSON
   fields), so hashes stay byte-identical. `LayerARoundTripTest`
   continues to pass without changes.

**Reserved-name candidates.**
Types: `intT`, `floatT`, `stringT`, `boolT`, `unitT`, `bytesT`.
Function types for common builtins: `addT`, `subT`, `mulT`, `divT`,
`modT`, `negT`, `eqIntT`, `ltT`, `leT`, `gtT`, `geT`, `notT`, `andT`,
`orT`, `concatT`, `eqStrT`, `nowT`.
Foreign nodes: `add`, `sub`, `mul`, `div`, `mod`, `neg`, `eqInt`, `lt`,
`le`, `gt`, `ge`, `not`, `and`, `or`, `concat`, `eqStr`, `now`.
Effect categories (same mechanism, separate table): `receiveFx`,
`sendFx`, `spawnFx`, `terminateFx`, `nowFx`, `writeFx`, `connectFx`
synthesizing `EFC` nodes with the corresponding `StateMachine.Receive`,
`StateMachine.Send`, `StateMachine.Spawn`, `StateMachine.Terminate`,
`Time.Now`, `Filesystem.Write`, `Network.Connect` category names. Every
state-machine corpus program declares these by hand today — the toggle
machine spends ~30 bytes on `receiveFx EFC "StateMachine.Receive"`
alone, and the multi-machine programs (47-49, 57) carry both `receiveFx`
and `sendFx`. Reserving them removes a per-program line each.

The 6+18+18+7 = 49 names cover every primitive use in the existing
corpus that does numeric, string, boolean computation, or named-effect
declaration. Layer 7 blessed-library names (JSON, PlainText, Markdown)
are NOT reserved at this layer — those are user-domain rather than
language-primitive and have separate import/library mechanics
in scope for a future plan (see § "What this plan deliberately
doesn't ship").

**Open question.** Do we surface a warning when a corpus program
shadows a reserved name with its own local declaration? Probably
**no** — content addressing makes equivalent declarations identical
in the canonical store. A program that writes its own `intT PRM Int`
gets the same hash as one that uses the implicit one. The shadow is
silent. (We add a test that asserts this equivalence.)

**Test changes.** `LayerARoundTripTest` adds a fixture per shorthand
form — e.g., a `01-int-literal-shorthand.layer-a` that uses the
implicit `intT` and asserts hash-equality with the existing
`01-int-literal.layer-a`.

**Size estimate.** ~150 lines in `LayerAGrammar.kt` (reserved table),
~30 lines in `DagJsonEmitter.kt` (synthesis), ~10 lines test fixtures.
Net source size: ~200 lines.

### Slice 2 — Inline literals at argument positions (~10% projected savings)

**Idea.** `arg ILT 42` followed by `app APP id [arg]` requires two
nodes for what Python writes as `id(42)`. If `APP`'s `arguments`
list-of-refs accepts inline literal tokens, the parser materializes a
synthetic literal node and points the reference at it. Same for
`LET`'s `value` slot when the value is a literal.

**Mechanism.**
1. Extend `LayerAGrammar.ArgKind` with `REFERENCE_OR_LITERAL`. The
   existing `LIST_REF` kind becomes `LIST_REF_OR_LITERAL` for slots
   that accept either.
2. `LayerAParser` already recognizes `IntL` / `FloatL` / `StringL` /
   `BoolL` / `Null` / `Bare` tokens. The grammar's per-code field
   spec dictates which is legal at each position; widening certain
   slots accepts both.
3. `DagJsonEmitter` extension: when an arg in a list is a literal
   token, synthesize a child IntLit / FloatLit / StringLit / BoolLit /
   UnitLit / BytesLit node with a fresh internal author id (e.g.,
   `__lit_0`, `__lit_1`, ...) and point the parent's reference at it.

**Slots that gain inline-literal support.** `APP.arguments`,
`LET.value`, possibly `MC.body` (a Match case body that's literally
just a constant), `SV.payload` (SumValue's payload).

**Open question.** Do we also accept inline literals at `LAM.body`
position? Lambda bodies of just `42` are rare in practice; adding it
costs nothing but probably saves nothing either.

**Test changes.** Add corpus fixtures with inline literals + assert
hash-equality with the named-literal forms. Round-trip test asserts
parser+emitter handles both.

**Size estimate.** ~50 lines `LayerAGrammar` (kind extension),
~40 lines `LayerAParser` (already mostly there), ~80 lines
`DagJsonEmitter` (synthesis + child-id generation), ~30 lines tests.
Net: ~200 lines.

### Slice 3 — Inline VarRef at argument positions (~5% projected savings)

**Idea.** `nRef VAR n` is a single-purpose VarRef declaration referenced
exactly once at `nIsZero APP eq [nRef zero]`. Strand's semantics
guarantee that a reference to a `PRC` (ParameterDecl) at an
expression-position argument can ONLY be a VarRef — binders aren't
themselves values. The grammar can recognize `n` at an arg position
as an implicit VarRef around the binder.

**Mechanism.**
1. `DagJsonEmitter` resolution step: when an arg references a node
   whose declared type is `PRC` (or a `Let`'s name slot — same
   reasoning) AND the reference appears at an expression-arg position,
   synthesize a VarRef node pointing at the binder.
2. Disambiguating: a reference appearing as the `binder` field of an
   explicit VarRef (i.e., the existing `nRef VAR n` declaration) is
   not synthesized — that's the binder declaration; the VarRef is the
   wrapper.
3. The parser doesn't change — it already accepts bare references at
   arg positions. The change is in resolution.

**Risk.** The synthetic-VarRef rule needs to fire only at *value-position*
arg slots, not at *binder-list* slots (e.g., a Lambda's `parameters`
list lists PRC ids directly, not VarRef around them). The grammar
already distinguishes these via `ArgKind` (LIST_REF for binder lists,
REFERENCE for expression-position single args). Implementation must
respect that.

**Open question.** Does this rule extend to `Let` binders? `letX LET
"x" valExpr bodyExpr` — references to `letX` inside `bodyExpr` are
VarRefs to the Let binder. Yes, same logic applies. But this is rare
in well-written programs because the Let's `name` field is metadata
only; programs reference the Let's NODE-id (`letX` itself).

**Test changes.** Round-trip fixtures + an explicit "synthesizes
VarRef around PRC reference" unit test.

**Size estimate.** ~40 lines `DagJsonEmitter`, ~20 lines tests.
Net: ~60 lines.

### Slice 4 — IF/Match-on-Bool sugar (~10-15% savings on conditional-heavy programs)

**Idea.** Match-on-Bool is universal — every program with conditional
branching uses it — and today costs seven lines for what Python writes
as `if cond: thenExpr else: elseExpr`:

    litTrue BLT true
    patTrue PLT boolT litTrue
    caseTrue MC patTrue thenExpr
    litFalse BLT false
    patFalse PLT boolT litFalse
    caseFalse MC patFalse elseExpr
    result MAT scrutinee [caseTrue caseFalse]

The new form is one line:

    result IF scrutinee thenExpr elseExpr

For factorial (the worst-case task in the evaluation suite at 4.94×
Python) this collapses seven lines into one — ~140 bytes saved,
~16% of the program. The dag-json output is byte-identical to the
seven-line explicit form, so `LayerARoundTripTest` continues to pass
when the IF-form is hashed against the existing factorial fixture.

**Mechanism.**
1. New `LayerAGrammar` code `IF` with required args
   `(scrutinee REFERENCE, then REFERENCE, else REFERENCE)`. Unlike
   every other code, `IF` has no one-to-one dag-json mapping — it
   expands at emit time.
2. `DagJsonEmitter` synthesizes seven dag-json nodes per `IF`: two
   `BoolLit`s (true / false), two `Pattern` records (literal kind,
   patternType pointing at the implicit `boolT` from Slice 1), two
   `MatchCase` records (true-case body = `thenExpr`, false-case body
   = `elseExpr`), and one `Match`. Internal author ids
   (`__if<n>_true`, `__if<n>_pat_true`, etc.) keep collisions out of
   the user namespace.
3. The synthesized `Match` has the same canonical bytes as the
   explicit seven-line form — round-trip property holds.

**Depends on Slice 1.** The synthesized patterns reference `boolT`.
If Slice 1's implicit prelude has not landed, `IF` must either
auto-declare `boolT PRM Bool` at emit time or require an explicit
declaration. The cleanest sequencing ships Slice 1 first and treats
`IF` as a consumer of the reserved-name infrastructure.

**Composes with Slice 2.** Once inline literals at arg positions
ship, `IF nIsZero 1 0` works directly, saving the `then`/`else`
declarations at literal-returning branches.

**Why this is its own slice, not absorbed by Slice 6's inline-literal-
pattern sugar.** Slice 6 only collapses `litTrue BLT true; patTrue
PLT boolT litTrue` to `patTrue PLT boolT true` — it saves the
literal-binding line but leaves the pattern, case, and match
structure intact. Slice 4 collapses the entire conditional scaffold
into one line. Both can ship; their savings stack on programs that
mix Bool-match and other-literal-match cases.

**Companion slice — WHEN for constructor patterns.** Slice 9 ships
the sum-type analog: `WHEN scrutinee [Some(n) -> body | None ->
otherBody]` expands to the equivalent PCN/PVR/MC/MAT tower. IF and
WHEN emit disjoint synthesized-id namespaces (`__if<n>_*` vs
`__when<n>_*`) so they compose cleanly in programs that mix both.

**Test changes.** Add corpus fixture `21-fixpoint-factorial-if.layer-a`
matching the existing factorial's behavior — hash-equality assertion
with the explicit form. Add a unit test asserting `IF`'s internal-id
minting is deterministic across reparses (same source → same canonical
hash).

**Size estimate.** ~20 lines `LayerAGrammar` (code definition),
~100 lines `DagJsonEmitter` (multi-node synthesis with deterministic
id minting), ~30 lines tests. Net: ~150 lines.

### Slice 5 — Combined Lambda parameter declarations (~5-10% savings on Lambda-heavy programs)

**Idea.** A Lambda with multiple parameters today emits one PRC per
parameter plus the LAM. `x PRC "x" intT; y PRC "y" intT; lam LAM [x y]
body` (3 lines) becomes `lam LAM [x:intT y:intT] body` (1 line).

**Mechanism.**
1. New `ArgKind.PARAM_LIST` for LAM's first slot. Accepts entries of
   the form `name:typeRef` (each becoming a synthetic PRC at emit
   time) OR plain refs (for backward-compat with the explicit PRC
   form).
2. `LayerAParser` tokenizer: extend to recognize the colon in
   `name:typeRef` form. Adjust line-tokenization to handle the colon
   inside list brackets.
3. `DagJsonEmitter` synthesizes one PRC per entry, generates a stable
   author id (e.g., `__param_<idx>` or use the name verbatim).

**Open question.** What about Lambda with effects? Today: `LAM
[params] body [effects]`. The new form: `LAM [name:type ...] body
[effects]`. Same shape; only the params slot changes.

**Test changes.** Add a fixture with the compact form + assert
hash-equality with the explicit-PRC form.

**Size estimate.** ~30 lines `LayerAGrammar` (PARAM_LIST kind),
~40 lines `LayerAParser` (colon-aware tokenization), ~50 lines
`DagJsonEmitter` (PRC synthesis), ~20 lines tests. Net: ~140 lines.

### Slice 6 — Inline literal in LiteralPattern (~3% savings on Match-heavy programs)

**Idea.** `litTrue BLT true; patTrue PLT boolT litTrue` becomes
`patTrue PLT boolT true` (or even `patTrue PLT true` if the type is
inferable from the matched scrutinee).

**Mechanism.** PLT's `literal` slot accepts an inline literal token in
addition to a reference. Same pattern as Slice 2's APP.arguments
extension.

**Size estimate.** ~30 lines parser/emitter changes, ~20 lines tests.
Net: ~50 lines.

### Slice 7 — Anonymous one-shot nodes (~5-10% savings, broad applicability)

**Idea.** Nodes referenced exactly once don't need an author id; the
parser auto-generates one. Mark with `_` in the id position.

**Mechanism.**
1. `LayerAParser` accepts `_` as the id. Internally rewrites to a
   fresh unique id (e.g., `__anon_<line>`).
2. The emitter doesn't change — author ids are opaque in canonical
   dag-json.
3. Restriction: an anonymous node CANNOT be referenced by name (since
   it has none). The next-following code is the only place it can
   appear. If an anonymous node ends up unreferenced, that's an
   authorship error (the verifier may or may not surface it; the
   grammar doesn't enforce single-reference).

**Risk.** This conflicts with forward references — if a node is
declared anonymously and meant to be referenced later, the author has
to name it. We can either:
(a) Require all anonymous nodes to be referenced by the *immediately
following* code (positional), or
(b) Allow an anonymous node to be referenced via a special token like
`@last` referring to the most recent anonymous declaration.

Option (a) is simpler; option (b) is more expressive. Probably ship
(a) first.

**Open question.** How does this interact with anonymous nodes that
need to appear in `LIST_REF` slots (e.g., a list of three anonymous
elements)? Would need `@last1`, `@last2`, `@last3` or just disallow
anonymous nodes inside lists.

**Size estimate.** ~40 lines parser, ~30 lines tests. Net: ~70 lines.

### Slice 8 — Inline ProductFieldValue list at PV positions (~3-5% savings on product-heavy programs)

**Idea.** A `ProductValue` today requires one `PFV` declaration per
field plus the `PV` itself. The toggle machine's transition result:

    stateFieldV PFV "state" negatedState
    outputsFieldV PFV "outputs" emptyOutputsV
    transitionResult PV resultT [stateFieldV outputsFieldV]

The new form folds the field list directly into the PV:

    transitionResult PV resultT {state=negatedState outputs=emptyOutputsV}

State-machine transitions (which always return a `{state, outputs}`
product) and record-style values are the main beneficiaries. The
toggle machine saves ~60 bytes from collapsing the three lines above
into one. Programs that build many product values in series (the
Layer 7 corpus, JSON construction, future composite-message programs)
benefit proportionally.

**Mechanism.**
1. New `ArgKind.FIELD_LIST` for `PV`'s `fields` slot. Accepts
   `{name=ref [name=ref ...]}` syntax in addition to the existing
   `[ref ref ...]` form (which keeps explicit `PFV` declarations
   working unchanged).
2. `LayerAParser` tokenizer extension: recognize `{`, `}`, and `=`
   inside the field-list slot. Each `name=ref` entry is parsed
   together; whitespace separates entries.
3. `DagJsonEmitter` synthesizes one `PFV` node per entry, with the
   declared name and the ref as value, using internal author ids
   (`__pfv<idx>_<fieldname>`). The synthesized `PFV`s appear in
   `PV.fields` in source order. Canonical sorting in the hashing
   layer keeps the canonical bytes identical to the explicit-PFV form.

**Composes with Slice 2.** `{state=true outputs=1}` works once Slice 2
ships, saving another two literal declarations per inline-literal
field.

**Open question — empty product.** Should `PV emptyT {}` be equivalent
to `PV emptyT []`? Yes — both are empty field lists; the parser
normalizes `{}` to the empty list. This means the toggle machine's
`emptyOutputsV PV emptyOutputsT []` becomes the more readable
`emptyOutputsV PV emptyOutputsT {}` for free.

**Test changes.** Add a fixture with the inline form + assert
hash-equality with the explicit-PFV form. Round-trip test asserts
both forms parse and emit identically.

**Size estimate.** ~40 lines `LayerAGrammar` (FIELD_LIST kind), ~30
lines `LayerAParser` (brace-aware tokenization), ~60 lines
`DagJsonEmitter` (PFV synthesis with deterministic id minting),
~20 lines tests. Net: ~150 lines.

### Slice 9 — WHEN/constructor-pattern sugar (~5-15% savings on sum-type Match programs)

**Idea.** The constructor analog of Slice 4's IF. A sum-type `Match`
today requires per case one `PCN` (constructor pattern), optionally
one `PVR` (variable pattern for payload binding), and one `MC` (match
case), plus the enclosing `MAT`. For a four-case `JsonValue` consumer:

    nilPat PCN jsonValueT "JsonNull"
    nilCase MC nilPat body0
    bPat PVR boolT "b"
    boolPat PCN jsonValueT "JsonBool" bPat
    boolCase MC boolPat body1
    nPat PVR intT "n"
    numberPat PCN jsonValueT "JsonNumber" nPat
    numberCase MC numberPat body2
    sPat PVR stringT "s"
    stringPat PCN jsonValueT "JsonString" sPat
    stringCase MC stringPat body3
    result MAT scrutinee [nilCase boolCase numberCase stringCase]

12 lines for what `WHEN` writes in one:

    result WHEN scrutinee [JsonNull -> body0 | JsonBool(b) -> body1 | JsonNumber(n) -> body2 | JsonString(s) -> body3]

The dag-json output is byte-identical to the explicit form.

**Three-task impact: zero.** None of the evaluation MVP's three tasks
do sum-type pattern matching (factorial uses Match-on-Bool → IF;
JSON-value constructs but doesn't consume; toggle has no Match). So
WHEN contributes 0 to the headline 1.30× endpoint in the impact table.
Its value is in the **broader corpus** — Option/Result-style programs,
the JSON-consuming half of the Layer 7 blessed library, recursive-list
folds, tagged-event handlers — which together represent a much larger
share of real Strand programs than the three-task suite captures.

**Mechanism.**
1. New `LayerAGrammar` code `WHEN` with required args
   `(scrutinee REFERENCE, cases CASE_LIST)`. The `CASE_LIST` arg
   kind is new — accepts the `[Constructor[(binder)]? -> body | ...]`
   syntax inside `[ ... ]`.
2. Each case has shape `Constructor` (no payload), or
   `Constructor(binder)` (single variable binder). The `Constructor`
   identifier matches a `SumTypeCase.name` on the scrutinee's
   `SumType`. The `(binder)` is omitted for cases whose `caseType`
   is null.
3. `DagJsonEmitter` synthesizes per case: one `PCN`, optionally one
   `PVR` for the binder (whose `patternType` is the case's
   `caseType`), and one `MatchCase`. Plus one `Match` wrapping the
   case list. Synthesized author ids follow the `__when<n>_*`
   convention (e.g., `__when0_pat_JsonBool`, `__when0_bind_b`,
   `__when0_case_JsonBool`, `__when0_match`).
4. The scrutinee's `SumType` is the source of truth for case-name
   resolution and per-case payload typing. `DagJsonEmitter` resolves
   the scrutinee's type from the document graph (the same lookup
   path Slice 3's auto-VarRef rule uses).
5. The case's `body` is a full expression — Slice 2 inline literals
   and Slice 3 auto-VarRef both work inside it. The synthesized
   binder is visible to auto-VarRef resolution so `WHEN x [Some(n)
   -> n | None -> 0]` Just Works once Slice 3 ships.

**Scope of this slice.** Three case shapes covered:
- `Constructor` (bare; cases whose `caseType` is null)
- `Constructor(binder)` (single variable binder, payload bound to a
  named PVR)
- Case ordering matches the SumType's declaration order in the
  scrutinee's type. The verifier enforces exhaustivity / first-match
  semantics downstream as it does today; WHEN does not change those
  rules.

**Out of scope for this slice (require explicit MC + PCN/PVR tower):**
- **Nested constructor patterns** (`Some(Cons(h, t))`). The inner PCN
  needs an explicit `patternType` reference to the inner sum's type;
  WHEN's inline syntax doesn't have a place for that. Programs that
  destructure nested sums bind a single payload variable in WHEN and
  destructure it in the case body with an explicit inner `MAT`. This
  matches what most pattern-matching languages do without an `as`
  pattern.
- **Wildcard or literal payload patterns** (`Some(_)`, `Some(42)`).
  Rare enough in practice that explicit form is acceptable.
- **Or-patterns** (`Some(_) | None -> body`). Not in the current node
  algebra at all — each `MatchCase` has one pattern. Out of scope
  until the algebra adds disjunctive patterns.

**Depends on Slice 1.** WHEN's synthesized PVR patterns for primitive
binders reference `intT` / `boolT` / `stringT` etc. Slice 1's reserved
prelude provides those. For binders of user-declared product types,
the user must declare the product type (the case's `caseType`)
explicitly — same as today.

**Composes with Slice 4 (IF).** Disjoint id namespaces; programs
mixing Bool conditionals and sum-type matches use both. The two
emit-time synthesis paths share a deterministic-id-minting helper
in `DagJsonEmitter`.

**Composes with Slice 2 and Slice 3.** Inline literals and auto-VarRef
work inside case bodies. `WHEN opt [None -> 0 | Some(n) -> n]` is the
most compact form of Option-default; an explicit-form equivalent runs
to ~12 lines.

**Why this is its own slice, not absorbed by Slice 4 IF.** IF is
positional with three references (scrutinee, then, else). WHEN needs
a structured case list with constructor names, optional binders, body
references, and case separators. The parser work — a small
recursive-descent pass on the case list inside `[ ... ]` — is more
significant than IF's straight three-arg form. The synthesis logic
is also variable-arity (one to many cases, per-case binder presence)
vs IF's fixed-shape pair. Splitting them keeps Slice 4 small and
ships the high-value Bool case without paying for the parser work
WHEN needs.

**Open question — case-list separator.** Three options:
(a) `|` between cases (this draft).
(b) `;` between cases.
(c) Newline between cases (multi-line WHEN).
(c) breaks the one-node-per-line invariant of Layer A. (a) and (b)
both fit; `|` is more conventional for pattern matching. Decision at
implementation time; the dag-json output is identical either way.

**Open question — scrutinee type annotation.** Should `WHEN
scrutinee:jsonValueT [...]` be legal? The elaborator infers the
scrutinee's `SumType` from the scrutinee's value type chain, so the
annotation is redundant when inference succeeds. Add only if a
concrete corpus program hits a case where inference falls short.

**Test changes.** Add a corpus fixture using `WHEN` against an
existing sum-type Match program (the JSON-consumer programs in the
blessed-libraries corpus are a candidate, as are the recursive-list
folds in the Markdown blessed library). Hash-equality assertion with
the explicit-form canonical bytes. Unit tests covering: bare case
(no payload), single-binder case, multi-case match, and a program
mixing IF and WHEN.

**Size estimate.** ~40 lines `LayerAGrammar` (CASE_LIST kind + WHEN
code), ~120 lines `LayerAParser` (case-list mini-parser for arrows
+ binders + separators inside brackets), ~120 lines `DagJsonEmitter`
(per-case multi-node synthesis + scrutinee-type resolution + Match
wrapper), ~40 lines tests. Net: ~320 lines.

This is the most parser-heavy slice — its complexity is comparable
to Slice 1+2+3 combined. Ship it as its own increment, after Slice 4
has demonstrated the multi-node-synthesis pattern in production.

## Sequencing & total projected impact

Slices 1+2+3 are the most synergistic (they share the same Layer A
parser/emitter touch). Ship as a single "Layer A density v1" slice
(~460 lines total source code). Slice 4 (IF sugar) is the natural
"v1.5" follow-on — depends on Slice 1's reserved `boolT` but is
otherwise a standalone synthesis. Slices 5-9 are individually
shippable extras.

| Task | Layer A today | v1 (Slices 1+2+3) | v1.5 (+ Slice 4 IF) | All slices 1-9 |
|------|--------------:|------------------:|--------------------:|---------------:|
| 01 factorial | 890 | ~630 | ~510 | ~430 |
| 02 JSON value | 534 | ~410 | ~410 | ~360 |
| 03 toggle | 1051 | ~770 | ~770 | ~610 |
| × Python geomean | 2.28× | ~1.66× | ~1.56× | ~1.30× |

The 1.30× endpoint is at the floor of Q-034 §6's projection for the
non-tokenizer-aligned stack. Slice 4 (IF) is the single highest-leverage
addition for the factorial task — small programs with conditional
branching pay the heaviest pattern-wrapping ceremony and benefit
disproportionately from the sugar. Slice 8 (inline PFV) is the
highest-leverage addition for the toggle task and forward-leverages
state-machine and record-style programs throughout the corpus.

**Slice 9 (WHEN) does not move the three-task table** — none of the
MVP tasks consume sum types — but is the highest-leverage addition
for the **broader corpus**: any program that pattern-matches a sum
(Option/Result, recursive lists, the JSON-consumer half of the Layer 7
blessed library, tagged-event handlers) pays a per-case 2-3 line
PCN/PVR/MC ceremony today. WHEN collapses each case to a single
`Constructor(binder) -> body` line. Expanding the evaluation MVP to
include a sum-consumer task is a Phase 1 follow-up that would let
WHEN's impact register in the headline number; today's three-task
metric understates its value.

**Recommended shipping order.**
1. v1 = Slices 1 + 2 + 3 (~460 lines, biggest measured win)
2. v1.5 = Slice 4 IF (~150 lines, brings every MVP task under 3×)
3. v2 = Slice 5 (combined Lambda params) + Slice 6 (inline literal
   patterns) + Slice 7 (anonymous nodes) (~260 lines combined)
4. v2.5 = Slice 8 (inline PFV) (~150 lines, big for state-machine
   and record programs)
5. v3 = Slice 9 (WHEN) (~320 lines; this is the parser-heaviest slice
   and pays off on a workload the MVP doesn't measure, so ships after
   the MVP-visible wins have landed)

## What this plan deliberately doesn't ship

- **Library / import mechanism.** Slice 1 reserves a fixed name table
  baked into the grammar. A general `@use <hash>` import for shared
  Strand libraries is a richer (and more invasive) idea — content-
  addressed library identity, transitive resolution, version
  compatibility — that doesn't pay off until programs are large enough
  for the per-program reserved set to fall short. Today's corpus
  doesn't exercise that.
- **Operator-like sugar.** `(eq n 0)` instead of `APP eq [n 0]` is
  cute and saves bytes but restructures the grammar from
  line-oriented to s-expression-shaped. Larger design slice; defer.
- **Token-aware emission.** The constraint grammar (Layer B) doesn't
  change as part of this plan; the model still emits Layer A under
  the same GBNF. Token-aware emission optimization is tokenizer
  alignment (Phase 4) — out of scope here.
- **Tool-call assembly as alternative interface.** Q-034 §3.6 names
  tool-call assembly (an LLM emits structured tool invocations
  validated incrementally by the platform) as an alternative to
  serialized text emission. It's orthogonal to Layer A density:
  per-call JSON overhead dominates on programs of any size, but
  per-call validation may close the dynamic-cost gap by reducing
  retries. The two interfaces share Layer C elaboration as a common
  back-end, so a future tool-call front-end can land independently
  of this plan's static-density work. Sequencing-wise, this plan
  ships first; a tool-call front-end is tracked separately when
  Phase 1 model integration is available.
- **Nested constructor patterns and or-patterns.** Slice 9's WHEN
  ships the single-binder, single-level case. Patterns like
  `Some(Cons(h, t))` (nested constructors) or `Some(_) | None`
  (or-patterns) still require explicit MC+PCN+PVR towers. The
  or-pattern case in particular needs a node-algebra extension —
  current `MatchCase` has one pattern, not a disjunction — and is
  out of scope here.

## Open questions to settle before promoting to a real proposal

1. **Reserved-name backward compatibility.** A future Strand version
   adds new reserved names. Old programs that locally declare a name
   that's now reserved continue to compile (the local declaration
   wins; the implicit one is shadowed). Test: assert this for at
   least one case.
2. **Tokenizer model dependence.** The 4-bytes-per-token heuristic
   the MVP uses doesn't match what specific model families produce.
   Slice 1-3 reduce bytes; per-tokenizer savings vary. We should
   document this clearly in `evaluation/results.md` after the slice
   lands.
3. **Layer C interaction.** Slice 1's reserved nodes are dag-json
   nodes synthesized at emit. The Layer C elaborator sees them just
   like any other node — no special handling needed. Verify in
   testing.
4. **CLI surface.** `strand author` already handles Layer A → dag-json.
   No new flags needed for the slice. The existing `--emit-json`
   shows the synthesized dag-json (useful for debugging the reserved-
   name expansion).

## Next action

When ready to execute: promote `layer-a-density.md` (this file) to
`../proposals/layer-a-density.md`, register Q-NNN if a new question is
generated, and execute slice by slice. Re-run `evaluation/measure.sh`
after each slice; commit the updated `results.md` so the cost trajectory
is reviewable.
