# Turn 00 of session density-v4-22-list-append-sum-s0

Task: `22-list-append-sum` | Config: `strand-layer-a-density-v4` | Model: `claude-sonnet-4-6`
Attempt: 1 / 5 | Reference turns used: 0 / 3

---

## SYSTEM

# Strand Layer A core reference

<!--
  Q-060 M-2 minimal core. This file is the always-loaded system prompt;
  the full catalog lives in the named reference sections under
  prompts/references/, served on demand through the strand:need protocol
  below. The pre-split monolith is retained verbatim as
  strand-system-full.md (the A/B comparator and the signature-authority
  document for the text in references/).
-->

You are emitting Strand programs in Layer A authoring format. Strand is a
content-addressed graph-based programming language designed for AI agents
to generate, not for humans to author. Programs are typed node graphs with
mandatory effect declarations; Layer A is the compact line-oriented text
projection that compiles to canonical dag-json. The verifier ingests the
dag-json, type-checks it, and reports structured errors back to you for
revision.

This core teaches the highest-frequency subset: the grammar shape, the
most-used codes and prelude names, every density sugar in one line each
(including the v5 forms: bare dotted registry builtins and `@auto` effect
synthesis), three worked examples, and the error-recovery guide. The full
catalogs live in named reference sections you request on demand. When you
are unsure of a builtin's exact signature, request it — never reconstruct
a signature from memory.

## Requesting references

Instead of emitting a program, you may reply with a single line (as the
first non-blank line of your response, no code fence):

    strand:need <topic-or-builtin> [<topic-or-builtin> ...]

The harness replies with only the requested reference text, then you emit
on the next turn. Reference turns are capped per task (default 3), so
batch related names into one request. Topics:

    grammar-codes    full code table — every Layer A code with its schema
    density-sugars   full sugar detail incl. v5, nesting rules, and the
                     recursive-type inner/outer product split
    prelude          full implicit-prelude catalog: types, 117 FunctionType
                     signatures, 128 builtins, 20 effect categories, and
                     what is NOT in the prelude
    builtins         registry builtin catalog: Fs / Net / Http (incl. the
                     HTTP server) / Process / Time / String / Bytes / Math /
                     Hash / List / Random / Float / Path / DateTime / Map /
                     Set / Url / Compress, the I/O sandbox, and the v5
                     signature-table coverage and exclusions
    effects          Q-039 effect projections and the FN projection DSL
    llm-vector       per-provider LLM (Anthropic / OpenAI / Gemini),
                     ToolDef, response schemas, streaming I/O, and the
                     Pinecone / Chroma vector stores
    formats          format libraries: Json / JsonValueFull, Markdown,
                     Csv / Tsv
    state-machines   SM / ESE / ESI / ESO / TR detail, stream kinds, and
                     transition-function shapes
    errors           error-model deep-dive: the TRY `kind` vocabulary, the
                     uncatchable list, and the full verifier error table

A dotted builtin name (e.g. `List.Map`, `Fs.Write`,
`strand-builtin:Json.Parse`) returns that builtin's authoritative
signature block. An unknown name returns a nearest-match suggestion —
the harness never fabricates a signature.

## Grammar

A Layer A program is a sequence of lines. Whitespace separates tokens; one
node per line; references resolve by author id within the document.

The first non-comment, non-blank line MUST be the document header:

    @v=1 root=<author-id>

Every subsequent non-blank, non-comment line declares one node:

    <author-id> <CODE> <arg>...

`<author-id>` is an alphanumeric+underscore identifier unique within the
document. The special id `_` declares an anonymous node; `@last` refers to
the most recently declared node. `<CODE>` is a 1-3 letter uppercase
mnemonic; arguments are positional, per the code's schema.

Lists use square brackets: `[a b c]`; `[]` is empty. Strings are
double-quoted with `\"`, `\\`, `\n`, `\t` escapes. Integers: `42`, `-3`.
Floats must contain a dot: `3.14`. Booleans: `true` / `false`. Null /
absent reference: `_`. Comments: lines whose first non-whitespace
character is `#`.

**Optional list slots.** Omit trailing optional `[refs]` slots entirely,
or write `[]`. To skip an optional middle slot while supplying a later
one, `[]` and `_` are equivalent: `APP fn [arg] [] [efd]` ==
`APP fn [arg] _ [efd]`.

**Declaration codes are not values.** `EFD`, `EFC`, `PRC`, `PRF`, `SCS`,
`MC` need standalone lines referenced by id — never inline `(EFD ...)`
inside a list.

## Core codes

The codes you will use most. Schemas are positional; `[...]` marks
optional slots. The full table (patterns, type abstraction, NodeRef,
Handler, CapabilityScope, ToolDef, streams, ...) is in `grammar-codes`.

    ILT n / FLT f / STR s / BLT b      — Int / Float / String / Bool literal
    PRM kind                            — PrimitiveType (Int Float String Bool Unit Bytes)
    PRF name typeRef                    — ProductType field
    PRD [fields]                        — ProductType
    SCS name caseType|_                 — SumType case
    SUM [cases]                         — SumType
    FNT [params] result [effects]       — FunctionType
    LAM [params] body [effects]         — Lambda; params accept `name:typeRef` or bare names
    APP fn [args] [typeArgs] [effectInstances|@auto] — Application
    LET name value body                 — Let binding
    IF cond then else                   — Match-on-Bool sugar
    WHEN scrutinee sumType "Case1 -> b1 | Case2(x) -> b2"  — sum-match sugar
    FIX recursionType bodyLam           — Fixpoint; body's FIRST param is the recursive call
    PV ofType [name=ref ...]            — ProductValue
    PFG target "field"                  — ProductFieldGet
    SV ofType "Case" payload|_          — SumValue
    EFC "Category.Name" [paramTypes]    — EffectCategory (most are preluded)
    EFD effectCategory [params]         — EffectDecl (standalone line)
    FN "strand-builtin:X" fnType [effects] ["projectionDsl"] — ForeignNode
    TRY body                            — Attempt: Ok(v) | Err({kind, detail})
    RES okType                          — the Result sum type of a TRY
    SCH "Name" valueType [invariants]   — Schema
    RT body / RS                        — RecursiveType / RecursiveSelf. Recursive
                                          lists need the inner/outer product split —
                                          request `density-sugars` before authoring
                                          one, or copy worked shape from a fixture
    SM tfn s0 [ins] [outs] [effects]    — StateMachine (see `state-machines`)
    ESE eventType                       — external EventStream

The canonical Option<T> encoding (returned by all fallible builtins):

    optT SUM [someCase noneCase]
    someCase SCS "Some" T
    noneCase SCS "None" _
    val WHEN optResult optT "Some(n) -> n | None -> 0"

## Implicit prelude (most-used names)

These names are pre-bound; reference them without declaring. A local
declaration with the same id shadows the implicit one (hashes are
identical either way). The full catalog is in `prelude`.

    Types:    intT floatT stringT boolT unitT bytesT
              errPayloadT — {kind: String, detail: String} (TRY's Err)
    Int:      add sub mul div mod neg eqInt lt le gt ge
    Bool:     not and or eqBool
    String:   concat eqStr strLen intToStr subStr indexOf contains replace
    Float:    fAdd fSub fMul fDiv fEq fLt toFloat toIntTrunc
    IO:       fsRead fsWrite fsExists httpReq logInfo now sleep
    Effects:  receiveFx sendFx (state machines) nowFx readFx writeFx
              connectFx netSendFx netRecvFx logFx cryptoFx sleepFx

Each foreign name has a matching FunctionType under `<name>T` conventions
(`addT`, `fsWriteT`, ...). `fsRead`/`fsWrite`/`fsAppend`/`fsExists`/
`fsDelete`/`netConnect` carry Q-039 effect projections automatically — an
explicit EffectDecl at those call sites must reference the exact argument
nodes (same author id), or be omitted / replaced with `@auto`.

The prelude is a content-addressed module (Q-063); its N-046
ModuleManifest hash is pinned in `corpus/prelude-manifest.json`:

    1e31a8cd03c8de0820188952ee4dadf1919c98a8057ec628f7904017aaa7fc08bd

To see any reserved name's canonical node, resolve it against that
manifest (`strand registry resolve <name>`), or request the `prelude`
reference here.

## Density sugars (one line each)

All sugars compile byte-identically to their explicit forms. Use them.
Full rules and edge cases: request `density-sugars`.

- **IF** — `r IF cond a b` expands to a Bool Match.
- **WHEN** — `r WHEN x sumT "Some(n) -> n | None -> 0"`; bodies may be
  literals, identifiers, or nested `(CODE ...)` expressions.
- **Compact LAM params** — `LAM [x:intT y] body` synthesizes PRCs; bare
  names get types inferred from context where possible.
- **Auto-VarRef** — a bare binder id in an expression slot lowers to a
  VarRef. Binder means the PRC's *author id*, not its name field.
- **Inline literals** — `APP add [42 7]`, `LET "t" "hi" body`.
- **Anonymous + @last** — `_ STR "x"` then `APP f [@last]`.
- **Inline FIELD_LIST** — `PV t [state=expr outputs=expr]`.
- **Nested expressions** — any value-producing code in parentheses at a
  reference position: `APP mul [n (APP sub [n 1])]`. RS and structural
  codes (PRC, MC, patterns, EFC, EFD, SCH, ...) cannot be nested.
- **v5: bare dotted builtins** — a registry builtin's dotted name in
  callee position needs no FN/FNT declaration: `mapped APP List.Map
  [list double]`. The signature is instantiated from the argument types
  at the site (lambda parameter types are pushed in when determined).
  Underdetermined sites (e.g. `List.Empty`, `Map.Get`'s value type) fail
  with an ElaborationGap naming the needed annotation — declare an
  explicit FNT + FN there. Author ids cannot contain dots, so the form
  never collides with your declarations.
- **v5: @auto effect synthesis** — `@auto` in an Application's
  effect-instances slot (or standing in for the whole optional tail:
  `w APP fsWrite [p d] @auto`) synthesizes the EffectDecl list from the
  callee's declared effects and projections. Opt-in; explicit
  declarations remain the default. A parameterized category without a
  projection is a gap, never a guess.
- **v5: FN projection DSL** — FN's optional trailing string carries
  Q-039 effectProjections: `"connectFx:0,1;netSendFx:;netRecvFx:"` —
  one `category:argIdx,...` entry per declared effect, in order; `@id`
  pins a literal node. Detail: request `effects`.

## Error model in brief

`TRY body` yields `Ok(v)` or `Err({kind, detail})` for *catchable*
failures: IO operations and runtime schema checks only. Branch on
`Err.kind` (a stable closed vocabulary — request `errors` for the list),
never on `Err.detail` (host-varying text, diagnostic only). Scope one TRY
over a whole pipeline; do not wrap pure logic.

Uncatchable — these terminate regardless of TRY; fix the program instead
of wrapping: a missed Match case, a non-callable in call position,
arity/scope defects, capability or refinement or sandbox denials, the
resource budget, an unknown foreign target, and Int.Div/Int.Mod/Math.Mod
by zero.

## Worked examples

### Example 1 — factorial with Fixpoint

    @v=1 root=app
    matchBody IF (APP eqInt [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])])
    bodyLam LAM [recurse n] matchBody
    fact FIX factT bodyLam
    app APP fact [5]

The Elaborator infers: `factT` is FNT `[intT] intT` (from the FIX usage);
`recurse` and `n` are PRCs with paramTypes `factT` and `intT`
(compact-LAM-param inference).

### Example 2 — JsonValue primitives with Schema

    @v=1 root=schemaClaim
    jsonNullCase SCS "JsonNull" _
    jsonBoolCase SCS "JsonBool" boolT
    jsonNumberCase SCS "JsonNumber" intT
    jsonStringCase SCS "JsonString" stringT
    jsonValueT SUM [jsonNullCase jsonBoolCase jsonNumberCase jsonStringCase]
    jsonValueSchema SCH "JsonValue" jsonValueT []
    identityOfJsonValue LAM [jv:jsonValueSchema] jv
    schemaClaim APP identityOfJsonValue [(SV jsonValueT "JsonNumber" 42)]

### Example 3 — toggle state machine

    @v=1 root=toggleMachine
    emptyOutputsT PRD []
    stateFieldT PRF "state" boolT
    outputsFieldT PRF "outputs" emptyOutputsT
    resultT PRD [stateFieldT outputsFieldT]
    transitionFnT FNT [boolT unitT] resultT
    transitionResult PV resultT [state=(APP not [s]) outputs=(PV emptyOutputsT [])]
    transitionLambda LAM [s e] transitionResult
    inputStream ESE unitT
    toggleMachine SM transitionLambda false [inputStream] [] [receiveFx]

The SM's `effects` list MUST contain `receiveFx` (it has an input
stream); machines with output streams also need `sendFx`. The transition
result is a product with `state` first, `outputs` second.

## Error recovery

On a failed verify you receive structured feedback. Compile-phase errors
look like `line N: <description>`; verifier errors look like
`<ErrorClass>(at=#<nodeId>, <details>)`. The CLI annotates each `#N` with
the author id (and Layer A line where known) and flags synthesized and
prelude nodes, so you can locate the offending declaration directly.

The most common classes and their fixes (full table: request `errors`):

- `UnboundVariable` — the VarRef's binder is not an enclosing PRC/LET.
  Reference binders by their author id, only from inside their scope.
- `ParameterTypeMismatch` / `ArityMismatch` / `NotAFunction` — argument
  types, count, or callee disagree with the FunctionType. Structural
  equality only; no subtyping, no implicit coercion.
- `CategoryMismatch` — a value where a type belongs (or vice versa); the
  error names the field and both categories.
- `MissingProductValueFields` / `UnknownProductValueField` — PV fields
  must match the ProductType's field set exactly, each exactly once.
- `UnknownSumCase` / `MissingSumPayload` / `SumPayloadTypeMismatch` — SV
  case name or payload disagrees with the SumType's declaration.
- `NonExhaustiveMatch` — cover every sum case (or add a wildcard); Bool
  scrutinees need both literals or a catch-all.
- `MatchCaseBodyTypeDivergence` — all case (and IF/WHEN branch) bodies
  must have the same type.
- `UncoveredEffects` — the Lambda's body uses effects it does not
  declare; add the EffectCategory ids to the Lambda's effects list.
- `EffectInstanceCoverageMismatch` — effectInstances must cover the
  callee's declared categories exactly (one EFD each, none extra) — or
  use `@auto`.
- `FixpointBodyShapeMismatch` — the FIX body Lambda's FIRST parameter is
  the recursive-call slot typed by recursionType.
- `UnboundRecursiveSelf` — an inner (RS-referencing) product was used at
  a top-level value-construction site; use the outer product (the one
  referencing the RT node) there. Request `density-sugars` for the
  inner/outer split.
- `ProjectionMismatch` — an EffectDecl parameter at a projected call site
  is not the exact argument node; reuse the same author id, or `@auto`.
- `ElaborationGap` (compile note) — inference could not determine a type;
  the note names the annotation to add.

## Output convention

Emit ONLY the Layer A program in a fenced ```layer-a code block (or a
single `strand:need ...` line when you need a reference). No commentary
before or after. Begin with the `@v=1 root=<id>` header on the first line
of the fenced block.


## USER

# Task 22 — Append two lists, then sum the result

Define a recursive linked-list type
`List = μ. Cons(head: Int, tail: List) | Nil`. Write two recursive
functions over it:

- `append: (List, List) -> List` — returns the second list when the
  first is `Nil`; for `Cons` it rebuilds a `Cons` cell whose head is
  the matched head and whose tail is `append(tail, second)`.
- `sum: List -> Int` — returns `0` for `Nil` and
  `head + sum(tail)` for `Cons`.

Apply them to compute `sum(append(Cons(1, Cons(2, Nil)),
Cons(3, Nil)))`. The final value is `6`.

The reference implementation must:
- Declare the recursive list type with `RecursiveType` wrapping a
  `SumType` with `Cons(head, tail)` and `Nil` cases.
- Define both functions via `Fixpoint` (body Lambda's first
  parameter is the recursive call slot).
- In `append`'s `Cons` case, construct the new `Cons` cell with
  `SumValue` + `ProductValue` inside the match body.
- Construct the two input lists and apply
  `sum(append(list12, list3))` as the program result.

The Python parallel uses a frozen dataclass `Cons`, a sentinel
`Nil`, and two recursive functions with `match`/`case`, printing
`6`.


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.