# Reference: density-sugars — full sugar detail

These shorthand forms produce byte-identical canonical JSON to their
fully-explicit equivalents. Use them — they substantially reduce
per-emission token count.

## IF sugar — Match on Bool

    <id> IF <scrutinee> <thenBranch> <elseBranch>

Expands to a Match with two literal-pattern MatchCases over `boolT`. The
`boolT` referenced by the synthesized patterns resolves via the implicit
prelude unless you shadow it.

Example: `r IF cond v_true v_false`

## WHEN sugar — pattern-match on a sum

    <id> WHEN <scrutinee> <sumType> "Case1 -> body | Case2(binder) -> body | ..."

The cases-string is parsed at emit time. Each case is `CaseName -> body` for
nullary cases or `CaseName(binderName) -> body` for cases with payloads.
Cases are separated by ` | `. The `body` may be:

- An inline literal (Int/Float/Bool — e.g., `42`, `true`, `-1`).
- An identifier — the case's binder, a PRC binder in scope, or any
  declared node id.
- A **nested expression** `(CODE args...)` — composes recursively, so
  `Cons(p) -> (APP add [(PFG p "head") (APP recurse [(PFG p "tail")])])`
  works inline. The nested code follows the same Slice 10 rules as
  nested expressions elsewhere.

`<sumType>` may be either a SUM node id directly, or an RT-wrapped node
whose body resolves to a SUM (e.g., a recursive list type — the WHEN
parser follows up to 8 RT wrappers to find the underlying SUM and uses
its SCS cases for binder-type inference). If the parser can't resolve
the SumType to a SUM via RT-following, binders are typed as the
placeholder `unknownT` which the verifier rejects.

Example: `r WHEN someValue optT "Some(n) -> n | None -> 0"`

Example with nested body: `r WHEN xs listT "Cons(p) -> (APP add [(PFG p \"head\") 1]) | Nil -> 0"`

## Compact LAM parameters

A Lambda's `parameters` slot accepts either bare PRC references (legacy
form) or `name:typeRef` compact entries. When you write `LAM [x:intT
y:boolT] body`, the emitter synthesizes a PRC per entry — no explicit PRC
declaration is needed. Many compact-param types can ALSO be elided when the
Elaborator can infer them from context (call-site argument types, Fixpoint
recursionType, state-machine transition signatures, etc.). Bare names like
`LAM [x y] body` lower to PRCs with paramType filled in by inference.

## Auto-VarRef on PRC binders

A bare PRC name in an expression slot (Application argument, Let value, IF
or WHEN scrutinee, nested expression args, FIELD_LIST values, WHEN case
bodies, ...) automatically lowers to a VarRef binding to that PRC. So
`APP add [x y]` works without writing out a VarRef declaration when `x`/`y`
are PRC names in scope.

**Important:** "PRC name" here means the PRC node's **author id**, not its
`name:` field. If you declare `xParam PRC "x" intT` and then reference `x`
in an expression, auto-VarRef looks for a PRC with id `x` (not the
`name:` field) and will fail to resolve. Use the author id directly:
write `xParam PRC "x" intT` then `APP gt [xParam 0]`, or — preferred —
use the compact-LAM form `LAM [x:intT] (APP gt [x 0])` where the
LAM-entry name IS the author id.

PRC binders introduced by compact-LAM entries (whether typed `[x:intT]`
or bare `[x]`) are recognized as binder ids and trigger auto-VarRef.
WHEN's scrutinee and case-body positions respect the same rule.

## Inline literals at REFERENCE positions

REFERENCE, LIST_REF, and NULLABLE_REF positions accept inline literals.

    APP add [42 7]          — two IntLits inline
    SV optT "Some" 42       — IntLit payload inline
    LET "tmp" "hello" body  — StringLit value inline

## Anonymous ids and @last

An anonymous declaration uses `_` for the id slot; refer to it via `@last`:

    _ STR "intermediate"
    next APP doSomething [@last]

Useful for one-shot intermediates that need not be named.

## Inline FIELD_LIST on PV

ProductValue's `fields` slot accepts `name=ref` entries in addition to bare
PFV references:

    PV resultT [state=true outputs=emptyOutputs]

The emitter synthesizes a PFV per entry — explicit PFV declarations are not
needed unless you reuse the same PFV across multiple values.

## Nested expressions (CODE args...)

Any **value-producing** code (APP, LET, VAR, LAM, NRF, TAB, MAT, IF,
WHEN, FIX, PV, PFG, SV, FN, H, CAP) may appear in parentheses at any
REFERENCE / LIST_REF / NULLABLE_REF position. **Type-producing** codes
(PRM, PRD, SUM, FNT, TPM, FAL, RT) may also appear nested, but only in
**type-position** slots — PRF.fieldType, FNT.parameters/result,
PRC.paramType, SCS.caseType, TPM.bound, SV.ofType, PV.ofType,
SCH.valueType, FIX.recursionType, FN.foreignType. The emitter assigns
each nested form a synthetic id and inserts the declaration:

    APP mul [n (APP recurse [(APP sub [n 1])])]
    PRC "x" (PRM Int)
    SCS "Cons" (PRD [headField tailField])

The first lowers to three Applications plus the literal 1 — five
declarations in canonical form, one line in density v4. The second
inlines a PrimitiveType into a ParameterDecl's paramType slot. The
third inlines a ProductType into a SumTypeCase's caseType slot.

**RS cannot be nested.** RecursiveSelf is type-only and the synthesized
standalone `__expr<n> RS` form would lose its lexical RT binder context
at canonical-encoding time. Declare RS as a standalone node and
reference it by id from inside the lexical RT subtree.

**Nested `(LAM ...)` expressions bind their own parameter names** inside
their bodies, so a higher-order call like
`APP List.Map [xs (LAM [n:intT] (APP mul [n 2]))]` works without a
standalone Lambda declaration.

Structural codes (PRC, MC, Pattern variants, EFC, EFD, ESE/ESI/ESO,
SCH, INV, SCS, PRF, TR) are rejected when nested — declare those as
standalone nodes.

Nested expressions combine with auto-VarRef so a bare PRC name like
`n` lowers to a VarRef on the parameter in scope. WHEN case binders
(`Cons(p) -> ...`) ARE in scope inside nested expressions in the
case body, so `Cons(p) -> (APP add [(PFG p "head") (PFG p "tail")])`
works directly — `p` resolves to the synthesized PVR for the case.

**Compact-LAM param names must be unique across Lambdas in the same
program.** Two `LAM [xs:T1]` and `LAM [xs:T2]` declarations with
different `T1` and `T2` produce an `ArgShapeMismatch` error because
the synthesized PRC would silently alias to the later declaration.
Rename one of the params (`xs_inner` vs `xs_outer`, or similar) so
each Lambda has its own PRC.

## Recursive types REQUIRE the inner/outer ProductType split

The ProductType that holds the recursive field has two valid forms, and
real programs need BOTH:

    selfRef RS                                  # standalone RS
    headFieldInner PRF "head" intT
    tailFieldInner PRF "tail" selfRef           # INNER: uses RS
    consInner PRD [headFieldInner tailFieldInner]
    consCase SCS "Cons" consInner               # SCS uses INNER (inside RT walk)
    nilCase SCS "Nil" _
    listSum SUM [consCase nilCase]
    listT RT listSum                            # lexical RT wraps the SUM

    headFieldOuter PRF "head" intT
    tailFieldOuter PRF "tail" listT             # OUTER: uses listT (the RT itself)
    consOuter PRD [headFieldOuter tailFieldOuter]

    # Value construction sites use the OUTER product:
    nilV SV listT "Nil" _
    one ILT 1
    consV SV listT "Cons" (PV consOuter [head=1 tail=nilV])

Why the split: the canonical encoder requires `RecursiveSelf` to be
reachable only through a path that traverses the enclosing
`RecursiveType` first. The SumTypeCase resolves its `caseType` *during*
the RT body walk (depth>0), so the inner product's RS reference is
well-bound. ProductValue and SumValue resolve their `ofType` at
top-level (depth=0), so a top-level reference to the inner product
trips `UnboundRecursiveSelf`. The outer product uses the RT node
directly so it's safe to use at top-level construction sites.

Both products are equirecursively equal — the verifier and the
canonical encoder treat them as the same type, so the program's hash
doesn't change based on which is used where; what matters is using
each in the correct context. Corpus program 31 (recursive-list-head)
is the canonical reference.

## Density v5 — bare dotted registry builtins (slice a)

A registry builtin's bare dotted canonical name in callee position
expands to its ForeignNode and FunctionType with no hand-declared
FNT + FN pair:

    @v=1 root=mapped
    ... (recursive intT-list tower as above, list bound to `list`) ...
    double LAM [x] (APP mul [x 2])
    mapped APP List.Map [list double]

The Elaborator instantiates the builtin's table signature (here
`(List<A>, (A) -> B) -> List<B>`) by matching argument types against
parameter shapes positionally — local instantiation from known types,
never unification. The synthesized FNT + FN pair carries the builtin's
effects and Q-039 projections, and matched user type towers are reused
so the result is byte-identical to the hand-declared counterpart.

The matching is bidirectional in one useful direction: a lambda
argument whose parameter annotations the signature determines gets them
pushed in — `double LAM [x] ...` becomes `x:intT` under `List.Map` once
the list argument binds `A`.

Underdetermined instantiations fail with an `ElaborationGap` naming the
annotation needed, and never guess. Known underdetermined sites:
`List.Empty` (element type), `Map.Get` (value type `V`), an unannotated
`Map.Merge` conflict lambda. Declare an explicit FNT + FN at those
sites, or annotate the lambda.

Author ids cannot contain dots, so the dotted form cannot collide with
user declarations. Coverage: every non-prelude registry builtin except
six documented exclusions — see the builtins reference for the
signature-table coverage and exclusion list. Prelude builtins keep
their reserved short names (`add`, `fsWrite`, ...); the dotted form is
for the registry families outside the prelude (`List.*`, `Map.*`,
`Set.*`, `String.Split`, `Json.Parse`, `Fs.List`, `Http.Request`, ...).

## Density v5 — opt-in @auto effect synthesis (slice b)

An `@auto` marker in an Application's effect-instances slot — or
standing in for the whole optional tail — directs the Elaborator to
synthesize the EffectDecl list from the callee's declared effects and
projections:

    @v=1 root=w
    p STR "/safe/log"
    d BYT "64617461"
    w APP fsWrite [p d] @auto

is byte-identical to the explicit form:

    wd EFD writeFx [p]
    w APP fsWrite [p d] [] [wd]

`ArgRef(i)` projection sources project the call's own value arguments;
parameterless categories synthesize parameterless declarations; a
parameterized category without a projection is a gap (`ElaborationGap`),
never a guess. Explicit declarations remain the default — `@auto` is
opt-in.

`@auto` works with synthesized callees too, combining both v5 slices:

    @v=1 root=entries
    dir STR "/safe"
    entries APP Fs.List [dir] @auto

Handler interaction is pinned: N-043 interception keys on the callee's
declared effect categories, never on the effect-instances list, and the
synthesized EffectDecls are structurally identical to explicit ones, so
an `@auto` call site under a Handler compiles byte-identical to — and
evaluates identically to — the explicit form.

The canonical fixture pairs live under `corpus/layer-a/density-v5/`
(each density form against its explicit counterpart).
