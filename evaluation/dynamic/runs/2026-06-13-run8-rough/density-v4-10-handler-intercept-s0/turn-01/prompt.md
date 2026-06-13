# Turn 01 of session density-v4-10-handler-intercept-s0

Task: `10-handler-intercept` | Config: `strand-layer-a-density-v4` | Model: `claude-sonnet-4-6`
Attempt: 1 / 5 | Reference turns used: 1 / 3

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

# Task 10 — Effect handler intercepts logger

Nested effect handlers for the same effect category (`Time.Now`) —
the innermost handler wins. The body calls `now()`; the inner handler
intercepts and returns `2`; the outer handler intercepts and returns
`1`. The interpreter's innermost-wins semantics selects the inner
handler. The program produces `2`.

The reference implementation must:
- Declare an `EffectCategory` named `Time.Now`.
- Declare a `ForeignNode` for `strand-builtin:Time.Now` (declaring the
  effect), and a body that calls it.
- Wrap the body in a `Handler` that intercepts `Time.Now` and whose
  `handle` is a zero-arg lambda returning `2`.
- Wrap that handler in an outer `Handler` that intercepts the same
  category and whose `handle` returns `1`.
- The program runs under empty capabilities — the closure-subtraction
  rule (`closureOf(handler) = (closureOf(body) - {intercept}) ∪ ...`)
  removes `Time.Now` from the surrounding requirements.

This task exercises: `Handler` declaration, intercept dispatch on a
declared effect, nested handler stack (innermost wins via `findLast`
over the active-handler stack), closure-subtraction semantics that
removes the intercepted effect from the body's required capabilities.
Maps to corpus program 38.

Python has no effect-handler primitive. The reference uses a global
list of "active handlers" (a stack) and a `now()` shim that consults
the top of the stack instead of producing a real timestamp. The
match for Strand's innermost-wins semantics is the stack's last-in-
first-out behavior.


## ASSISTANT

strand:need grammar-codes


## USER

[strand reference response]

# Reference: grammar-codes — full Layer A code table

Each code is listed with its `jsonType`, required and optional positional
arguments, and a tiny example. Document structure (header line, lists,
strings, literals, `_`, optional slots, comments) is in the core prompt.

## Literals (all produce values)

- `ILT value:Int` — IntLit. `n1 ILT 42`
- `FLT value:Float` — FloatLit. `f1 FLT 3.14`
- `STR value:String` — StringLit. `s1 STR "hello"`
- `BLT value:Bool` — BoolLit. `b1 BLT true`
- `ULT` — UnitLit (no args). `u1 ULT`
- `BYT value:String` — BytesLit (base64). `bs1 BYT "aGVsbG8="`

## Types

- `PRM kind:Keyword` — PrimitiveType. `intT PRM Int` (kinds: Int, Float, String, Bool, Unit, Bytes).
- `PRD fields:[refs]` — ProductType. `pT PRD [fa fb]`
- `PRF name:String fieldType:ref` — ProductTypeField. `fa PRF "x" intT`
- `SUM cases:[refs]` — SumType. `sT SUM [ca cb]`
- `SCS name:String caseType:nullable-ref` — SumTypeCase. `ca SCS "Some" intT` or `cb SCS "None" _`
- `FNT parameters:[refs] result:ref [effects:[refs]]` — FunctionType. `fT FNT [intT intT] intT`
- `TPM name:String [bound:ref]` — TypeParameter. `tp1 TPM "A"`

## Functions and binding

- `LAM parameters:PARAM_LIST body:ref [effects:[refs]]` — Lambda (produces value). Parameters accept either bare PRC references or compact `name:typeRef` entries. `bodyL LAM [x:intT y:intT] expr`
- `PRC name:String [paramType:ref]` — ParameterDecl. `x PRC "x" intT`. With compact LAM params the standalone PRC is unnecessary.
- `APP function:ref arguments:[refs] [typeArguments:[refs]] [effectInstances:[refs] | @auto]` — Application (produces value). `r APP add [a b]`. The callee may also be a bare dotted registry builtin name (density v5) — see the density-sugars reference.
- `LET name:String value:ref body:ref` — Let (produces value). `e LET "tmp" v inner`
- `VAR binder:ref` — VarRef (produces value). `v1 VAR x`. With auto-VarRef sugar a bare PRC name in an expression slot lowers to a VarRef automatically.

## References

- `NRF target:ref` — NodeRef (produces value). `n1 NRF closed_subgraph`

## Type abstraction

- `TAB typeParameters:[refs] body:ref` — TypeAbstraction (produces value). `pT TAB [tp1] body`
- `FAL typeParameters:[refs] body:ref` — ForallType. `fT FAL [tp1] inner`

## Effects and capabilities

- `EFC categoryName:String [parameters:[refs]]` — EffectCategory. `recvEf EFC "StateMachine.Receive"`. Common categories are pre-bound in the implicit prelude.
- `EFD effectType:ref parameters:[refs]` — EffectDecl. `ed1 EFD writeEf [path]`
- `CAP capabilities:[refs] body:ref` — CapabilityScope (produces value). `scope CAP [ed1] inner`

## Foreign function interface

- `FN target:String foreignType:ref [effects:[refs]] [effectProjections:String]` — ForeignNode (produces value). `myAdd FN "strand-builtin:Int.Add" addT`. Most common builtins are pre-bound in the implicit prelude; registry builtins outside the prelude are also reachable by their bare dotted name in callee position (density v5).

The optional trailing string is the Q-039 projection DSL
(`"connectFx:0,1;netSendFx:;netRecvFx:"`) — it gives a hand-authored
ForeignNode `effectProjections` directly from Layer A. FunctionType nodes
accept `effectProjections` in canonical dag-json for symmetry. Format and
verifier rules: see the effects reference. The implicit prelude entries
for `Fs.*` and `Net.Connect` carry their projections automatically —
agents using `fsWrite`, `netConnect`, etc. by reserved name get the
security property for free.

## Control flow

- `MAT scrutinee:ref cases:[refs]` — Match (produces value). `m MAT v [c1 c2]`
- `MC pattern:ref body:ref` — MatchCase. `c1 MC pat body`
- `IF scrutinee:ref then:ref else:ref` — Match-on-Bool sugar (produces value). Expands to a Match + two Pattern + two MatchCase + two BoolLit. `r IF cond v_true v_false`
- `WHEN scrutinee:ref sumType:ref cases:String` — pattern-match-on-sum sugar (produces value). Cases string format: `Case1 -> body | Case2(binder) -> body | ...`. `r WHEN x optT "Some(n) -> n | None -> 0"`
- `PLT patternType:ref literal:ref` — Pattern (literal kind). `p1 PLT intT lit42`
- `PVR patternType:ref name:String` — Pattern (variable kind, binds a name). `p1 PVR intT "n"`
- `PWC patternType:ref` — Pattern (wildcard kind). `p1 PWC intT`
- `PCN patternType:ref caseName:String [payloadPattern:nullable-ref]` — Pattern (constructor kind). `p1 PCN optT "Some" p2`

## Error recovery

- `TRY body:ref` — Attempt (produces value). Runs `body`; if it succeeds with `v`, yields `Ok(v)`; if `body` raises a *catchable* runtime failure, yields `Err({kind, detail})` and evaluation continues. Uncatchable failures propagate through. `t TRY readApp`
- `RES okType:ref` — Result-sum type sugar. Expands to the SumType `Ok(okType) | Err(errPayloadT)`, the type of `TRY (... : okType)`. Match the result with `WHEN t (RES bytesT) "Ok(b) -> b | Err(e) -> defaultB"`.

See the errors reference for the catchable `kind` vocabulary and the
uncatchable list.

## Fixpoint and composite values

- `FIX recursionType:ref body:ref` — Fixpoint (produces value). `fact FIX factT bodyLam`. The body Lambda's FIRST parameter is the recursive call slot; remaining parameters are the user-facing ones.
- `PV ofType:ref fields:FIELD_LIST` — ProductValue (produces value). `pv PV resultT [state=expr outputs=expr]`. Fields accept either bare PFV references or `name=ref` entries.
- `PFV fieldName:String value:ref` — ProductFieldValue. `pf PFV "x" expr`
- `PFG target:ref fieldName:String` — ProductFieldGet (produces value). `g PFG record "x"`
- `SV ofType:ref caseName:String payload:nullable-ref` — SumValue (produces value). `v SV optT "Some" lit42` or `v SV optT "None" _`

## Recursive types

- `RT body:ref` — RecursiveType. `lT RT consSum`
- `RS` — RecursiveSelf (no args). `s RS`

Value construction over recursive types requires the inner/outer
ProductType split — see the density-sugars reference.

### RecursiveSelf depth field

`RecursiveSelf` accepts an optional `depth: Int = 0` field. Default 0
behaves identically to the bare form — the reference resolves to the
innermost enclosing `RecursiveType`. A non-zero depth resolves to the
N-th outer binder (de Bruijn index against the recursive-binder stack).

    { "type": "RecursiveSelf", "depth": 1 }   -- the next-outer enclosing RT

**Practical caveat.** The depth field is a sound type-algebra primitive
but doesn't currently compose with value construction across nested
RecursiveTypes. An inner μ-type with a depth>0 reference is correct
only when traversed *as part of* its enclosing outer μ; a direct
construction site like `SumValue.ofType = innerType` resolves the
inner standalone and fails `UnboundRecursiveSelf`. For nested-list
shapes (JSON arrays inside JsonValue, trees, etc.) use the spliced-
variants pattern instead — see the formats reference (JsonValueFull).

## Handler

- `H intercept:ref handle:ref body:ref` — Handler (produces value). `h H writeFx noopFn protected_body`

## State machines

- `SM transitionFn:ref initialState:ref inputStreams:[refs] [outputStreams:[refs]] [effects:[refs]]` — StateMachine. `m SM tfn s0 [in] [out] [recvEf sendEf]`
- `ESE eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (external). `in ESE intT`
- `ESI eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (internal). `mid ESI intT`
- `ESO eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (output). `out ESO intT`
- `TR guard:nullable-ref body:ref` — Transition.

Semantics, required effects, and transition-function shapes: see the
state-machines reference.

## Schema and Invariant

- `SCH schemaName:String valueType:ref invariants:[refs]` — Schema. `posInt SCH "PositiveInt" intT [posInv]`
- `INV invariantName:String targetSchema:ref body:ref` — Invariant. `posInv INV "positive" posInt isPosLambda`

## LLM tool and response-schema wrappers

- `TLD name:String description:String parameterSchema:ref implementation:ref` — ToolDef. The `parameterSchema` must reference a `SCH` node; the implementation is a Lambda or ForeignNode of type `(parameterSchema.valueType) -> R`.
- `RSC schema:ref` — ResponseSchemaSpec (produces value). The `schema` must reference a `SCH` node.

Verifier checks, the JSON-Schema-projectable type subset, and usage in
GenerateRequest: see the llm-vector reference.

Reference turns used: 1 of 3. Emit the Layer A program next, or request further references.

---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.