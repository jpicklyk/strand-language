# Turn 00 of session strand-06-counter-machine

Task: `06-counter-machine` | Config: `strand-layer-a-density-v4` | Model: `claude-sonnet-4-7`
Attempt: 1 / 5

---

## SYSTEM

# Strand Layer A reference

You are emitting Strand programs in Layer A authoring format. Strand is a
content-addressed graph-based programming language designed for AI agents to
generate, not for humans to author. Programs are typed node graphs with
mandatory effect declarations; Layer A is the compact line-oriented text
projection that compiles to canonical dag-json. The verifier ingests the
dag-json, type-checks it, and reports structured errors back to you for
revision.

This prompt teaches the full grammar including the density-v4 sugars (IF,
WHEN, compact LAM params, inline literals, auto-VarRef on PRC binders,
anonymous ids with @last, inline FIELD_LIST, nested expressions). The
density sugars are recommended — they reduce per-emission token count by
roughly 4x relative to canonical dag-json without changing what the
verifier accepts.

## Grammar

A Layer A program is a sequence of lines. Whitespace separates tokens; one
node per line; references resolve by author id within the document.

The first non-comment, non-blank line MUST be the document header:

    @v=1 root=<author-id>

Every subsequent non-blank, non-comment line declares one node:

    <author-id> <CODE> <arg>...

`<author-id>` is an alphanumeric+underscore identifier unique within the
document. The special id `_` denotes an anonymous node whose body is
inaccessible by id (use `@last` to refer to the immediately preceding line).
The special token `@last` refers to whichever node was declared most
recently — handy for one-shot intermediates.

`<CODE>` is a 1-3 letter uppercase mnemonic chosen from the codes table
below. Arguments are positional, per the code's schema.

Lists use square brackets: `[a b c]` is a three-element reference list;
`[]` is empty.

Strings are double-quoted with `\"`, `\\`, `\n`, `\t` escapes.

Integers: `42`, `-3`, `0`. Floats must contain a dot: `3.14`, `-0.5`, `1.0`.
Booleans: `true` or `false`. Null / absent reference: `_` (single underscore).

Comments: any line whose first non-whitespace character is `#`.

## Codes

Each code is listed below with its `jsonType`, required and optional
positional arguments, and a tiny example.

### Literals (all produce values)

- `ILT value:Int` — IntLit. `n1 ILT 42`
- `FLT value:Float` — FloatLit. `f1 FLT 3.14`
- `STR value:String` — StringLit. `s1 STR "hello"`
- `BLT value:Bool` — BoolLit. `b1 BLT true`
- `ULT` — UnitLit (no args). `u1 ULT`
- `BYT value:String` — BytesLit (base64). `bs1 BYT "aGVsbG8="`

### Types

- `PRM kind:Keyword` — PrimitiveType. `intT PRM Int` (kinds: Int, Float, String, Bool, Unit, Bytes).
- `PRD fields:[refs]` — ProductType. `pT PRD [fa fb]`
- `PRF name:String fieldType:ref` — ProductTypeField. `fa PRF "x" intT`
- `SUM cases:[refs]` — SumType. `sT SUM [ca cb]`
- `SCS name:String caseType:nullable-ref` — SumTypeCase. `ca SCS "Some" intT` or `cb SCS "None" _`
- `FNT parameters:[refs] result:ref [effects:[refs]]` — FunctionType. `fT FNT [intT intT] intT`
- `TPM name:String [bound:ref]` — TypeParameter. `tp1 TPM "A"`

### Functions and binding

- `LAM parameters:PARAM_LIST body:ref [effects:[refs]]` — Lambda (produces value). Parameters accept either bare PRC references or compact `name:typeRef` entries. `bodyL LAM [x:intT y:intT] expr`
- `PRC name:String [paramType:ref]` — ParameterDecl. `x PRC "x" intT`. With compact LAM params the standalone PRC is unnecessary.
- `APP function:ref arguments:[refs] [typeArguments:[refs]] [effectInstances:[refs]]` — Application (produces value). `r APP add [a b]`
- `LET name:String value:ref body:ref` — Let (produces value). `e LET "tmp" v inner`
- `VAR binder:ref` — VarRef (produces value). `v1 VAR x`. With auto-VarRef sugar a bare PRC name in an expression slot lowers to a VarRef automatically.

### References

- `NRF target:ref` — NodeRef (produces value). `n1 NRF closed_subgraph`

### Type abstraction

- `TAB typeParameters:[refs] body:ref` — TypeAbstraction (produces value). `pT TAB [tp1] body`
- `FAL typeParameters:[refs] body:ref` — ForallType. `fT FAL [tp1] inner`

### Effects and capabilities

- `EFC categoryName:String [parameters:[refs]]` — EffectCategory. `recvEf EFC "StateMachine.Receive"`. Common categories are pre-bound in the implicit prelude.
- `EFD effectType:ref parameters:[refs]` — EffectDecl. `ed1 EFD writeEf [path]`
- `CAP capabilities:[refs] body:ref` — CapabilityScope (produces value). `scope CAP [ed1] inner`

### Foreign function interface

- `FN target:String foreignType:ref [effects:[refs]]` — ForeignNode (produces value). `myAdd FN "strand-builtin:Int.Add" addT`. Most common builtins are pre-bound in the implicit prelude.

### Control flow

- `MAT scrutinee:ref cases:[refs]` — Match (produces value). `m MAT v [c1 c2]`
- `MC pattern:ref body:ref` — MatchCase. `c1 MC pat body`
- `IF scrutinee:ref then:ref else:ref` — Match-on-Bool sugar (produces value). Expands to a Match + two Pattern + two MatchCase + two BoolLit. `r IF cond v_true v_false`
- `WHEN scrutinee:ref sumType:ref cases:String` — pattern-match-on-sum sugar (produces value). Cases string format: `Case1 -> body | Case2(binder) -> body | ...`. `r WHEN x optT "Some(n) -> n | None -> 0"`
- `PLT patternType:ref literal:ref` — Pattern (literal kind). `p1 PLT intT lit42`
- `PVR patternType:ref name:String` — Pattern (variable kind, binds a name). `p1 PVR intT "n"`
- `PWC patternType:ref` — Pattern (wildcard kind). `p1 PWC intT`
- `PCN patternType:ref caseName:String [payloadPattern:nullable-ref]` — Pattern (constructor kind). `p1 PCN optT "Some" p2`

### Fixpoint and composite values

- `FIX recursionType:ref body:ref` — Fixpoint (produces value). `fact FIX factT bodyLam`. The body Lambda's FIRST parameter is the recursive call slot; remaining parameters are the user-facing ones.
- `PV ofType:ref fields:FIELD_LIST` — ProductValue (produces value). `pv PV resultT [state=expr outputs=expr]`. Fields accept either bare PFV references or `name=ref` entries.
- `PFV fieldName:String value:ref` — ProductFieldValue. `pf PFV "x" expr`
- `PFG target:ref fieldName:String` — ProductFieldGet (produces value). `g PFG record "x"`
- `SV ofType:ref caseName:String payload:nullable-ref` — SumValue (produces value). `v SV optT "Some" lit42` or `v SV optT "None" _`

### Recursive types

- `RT body:ref` — RecursiveType. `lT RT consSum`
- `RS` — RecursiveSelf (no args). `s RS`

### Handler

- `H intercept:ref handle:ref body:ref` — Handler (produces value). `h H writeFx noopFn protected_body`

### State machines

- `SM transitionFn:ref initialState:ref inputStreams:[refs] [outputStreams:[refs]] [effects:[refs]]` — StateMachine. `m SM tfn s0 [in] [out] [recvEf sendEf]`
- `ESE eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (external). `in ESE intT`
- `ESI eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (internal). `mid ESI intT`
- `ESO eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (output). `out ESO intT`
- `TR guard:nullable-ref body:ref` — Transition.

### Schema and Invariant

- `SCH schemaName:String valueType:ref invariants:[refs]` — Schema. `posInt SCH "PositiveInt" intT [posInv]`
- `INV invariantName:String targetSchema:ref body:ref` — Invariant. `posInv INV "positive" posInt isPosLambda`

## Implicit prelude

The following 49 names are pre-bound — you may reference them in any node
without declaring them locally. A local declaration with the same id
shadows the implicit one. Because Strand is content-addressed by structure,
the local and implicit forms hash identically.

Primitive types (6):

    intT       — PrimitiveType Int
    floatT     — PrimitiveType Float
    stringT    — PrimitiveType String
    boolT      — PrimitiveType Bool
    unitT      — PrimitiveType Unit
    bytesT     — PrimitiveType Bytes

FunctionType signatures for builtins (17):

    addT eqIntT ltT leT gtT geT     — (Int, Int) -> Int  or  (Int, Int) -> Bool
    subT mulT divT modT             — (Int, Int) -> Int
    negT                            — (Int) -> Int
    notT                            — (Bool) -> Bool
    andT orT                        — (Bool, Bool) -> Bool
    concatT                         — (String, String) -> String
    eqStrT                          — (String, String) -> Bool
    nowT                            — () -> Int

Foreign-node builtins (17):

    add sub mul div mod neg         — Int arithmetic
    eqInt lt le gt ge               — Int comparisons returning Bool
    not and or                      — Bool combinators
    concat eqStr                    — String operations
    now                             — Time.Now (effectful; declares nowFx)

Effect categories (7):

    receiveFx     — StateMachine.Receive (every state machine needs this)
    sendFx        — StateMachine.Send (state machines with outputs need this)
    spawnFx       — StateMachine.Spawn
    terminateFx   — StateMachine.Terminate
    nowFx         — Time.Now
    writeFx       — Filesystem.Write
    connectFx     — Network.Connect

A state machine with input streams must declare `receiveFx` in its `effects`
list. A state machine with output streams must also declare `sendFx`.

## Density sugars

These shorthand forms produce byte-identical canonical JSON to their
fully-explicit equivalents. Use them — they substantially reduce per-emission
token count.

### IF sugar — Match on Bool

    <id> IF <scrutinee> <thenBranch> <elseBranch>

Expands to a Match with two literal-pattern MatchCases over `boolT`. The
`boolT` referenced by the synthesized patterns resolves via the implicit
prelude unless you shadow it.

Example: `r IF cond v_true v_false`

### WHEN sugar — pattern-match on a sum

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

### Compact LAM parameters

A Lambda's `parameters` slot accepts either bare PRC references (legacy
form) or `name:typeRef` compact entries. When you write `LAM [x:intT
y:boolT] body`, the emitter synthesizes a PRC per entry — no explicit PRC
declaration is needed. Many compact-param types can ALSO be elided when the
Elaborator can infer them from context (call-site argument types, Fixpoint
recursionType, state-machine transition signatures, etc.). Bare names like
`LAM [x y] body` lower to PRCs with paramType filled in by inference.

### Auto-VarRef on PRC binders

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

### Inline literals at REFERENCE positions

REFERENCE, LIST_REF, and NULLABLE_REF positions accept inline literals.

    APP add [42 7]          — two IntLits inline
    SV optT "Some" 42       — IntLit payload inline
    LET "tmp" "hello" body  — StringLit value inline

### Anonymous ids and @last

An anonymous declaration uses `_` for the id slot; refer to it via `@last`:

    _ STR "intermediate"
    next APP doSomething [@last]

Useful for one-shot intermediates that need not be named.

### Inline FIELD_LIST on PV

ProductValue's `fields` slot accepts `name=ref` entries in addition to bare
PFV references:

    PV resultT [state=true outputs=emptyOutputs]

The emitter synthesizes a PFV per entry — explicit PFV declarations are not
needed unless you reuse the same PFV across multiple values.

### Nested expressions (CODE args...)

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
reference it by id from inside the lexical RT subtree:

    selfRef RS                          # standalone RS
    tailField PRF "tail" selfRef        # PRF references it by id
    payload PRD [headField tailField]
    consCase SCS "Cons" payload
    nilCase SCS "Nil" _
    listSum SUM [consCase nilCase]
    listT RT listSum                    # lexical RT wraps the SUM

Structural codes (PRC, MC, Pattern variants, EFC, EFD, ESE/ESI/ESO,
SCH, INV, SCS, PRF, TR) are rejected when nested — declare those as
standalone nodes.

Nested expressions combine with auto-VarRef so a bare PRC name like
`n` lowers to a VarRef on the parameter in scope. WHEN case binders
(`Cons(p) -> ...`) ARE now in scope inside nested expressions in the
case body, so `Cons(p) -> (APP add [(PFG p "head") (PFG p "tail")])`
works directly — `p` resolves to the synthesized PVR for the case.

**Compact-LAM param names must be unique across Lambdas in the same
program.** Two `LAM [xs:T1]` and `LAM [xs:T2]` declarations with
different `T1` and `T2` produce an `ArgShapeMismatch` error because
the synthesized PRC would silently alias to the later declaration.
Rename one of the params (`xs_inner` vs `xs_outer`, or similar) so
each Lambda has its own PRC.

## Worked examples

### Example 1 — factorial with Fixpoint

Recursion + IF + nested expressions. Five user-visible lines emit ~30
canonical-JSON nodes.

    @v=1 root=app
    matchBody IF (APP eqInt [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])])
    bodyLam LAM [recurse n] matchBody
    fact FIX factT bodyLam
    app APP fact [5]

The Elaborator infers: `factT` is FNT `[intT] intT` (from `fact`'s
recursionType reference and the FIX usage); `recurse` and `n` are PRCs
with paramTypes `factT` and `intT` (compact-LAM-param inference).

### Example 2 — JsonValue primitives with Schema

Sum type + Schema declaration + nested SV inside an Application. The
JsonValue sum is the type contract; the Schema wraps it with no invariants
so downstream consumers see a typed alias.

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

A Bool-state machine driven by `unitT` events. Per-event output is empty
(empty product). Compact-LAM params let the transition lambda elide its
parameter types; the Elaborator picks them up from the SM's transitionFn
signature.

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

Note: the SM's `effects` list MUST contain `receiveFx` (because the machine
has at least one input stream). If outputs were declared, `sendFx` would
also be required.

## Errors

On a failed verify, you receive structured feedback. Compile-phase errors
look like:

    line N: <description>

Verifier errors look like:

    <ErrorClass>(at=#<nodeId>, <details>)

Common error classes you may encounter:

- `UnboundTypeParameter` — a TypeParameter is referenced from outside any
  enclosing TypeAbstraction or ForallType that lists it. Add the TPM to the
  binder's `typeParameters` list, or wrap the body in a TAB.
- `MissingProductValueFields` / `UnknownProductValueField` / `DuplicateProductValueField`
  — your ProductValue's `fields` list does not match the ProductType's
  declared field set exactly. Every field must appear once.
- `UnknownSumCase` — a SumValue or ConstructorPattern names a case the
  SumType does not declare.
- `MissingSumPayload` / `UnexpectedSumPayload` — a SumValue's payload is
  required (declared) or forbidden (not declared) by the case's caseType.
- `SumPayloadTypeMismatch` — the payload value has the wrong type for the
  case.
- `TypeArgumentArityMismatch` — an Application of a polymorphic value
  supplies the wrong number of type arguments.
- `PartialTypeInstantiation` — type-argument substitution did not reduce
  the function's type to a plain FunctionType. Supply more type arguments.
- `ParameterTypeMismatch` — a value argument's type disagrees with the
  function's parameter type. Structural equality only; no subtyping.
- `ArityMismatch` — wrong number of arguments at an Application.
- `NotAFunction` — the Application's function position has a non-function
  type.
- `UncoveredEffects` — a Lambda's body uses effects the Lambda failed to
  declare. Add the missing EffectCategory NodeIds to the Lambda's `effects`
  list.
- `StateMachineMissingImplicitEffect` — a StateMachine is missing
  `receiveFx` or `sendFx`. Add the appropriate effect category to the SM's
  `effects` list.
- `StateMachineTransitionFnShapeMismatch` — the transition function's
  type is not `(State, Event) -> (State, Outputs)` for either the
  OutputBatch product shape or the tagged-list recursive shape. Check the
  result PRD's field order: `state` first, `outputs` second.
- `OutputStreamEventTypeMismatch` — an output stream's eventType disagrees
  with the corresponding `output_i: Option<...>` slot of the transition's
  OutputBatch product.
- `SchemaInvariantViolation` — a statically-known value flowing into a
  Schema-typed position failed one of the Schema's invariants. Check the
  invariant body and the value being supplied.

Use the `at=#<nodeId>` field to locate the offending node in your program;
the node id is the position in your document's declaration order.

## Output convention

Emit ONLY the Layer A program in a fenced ```layer-a code block. No
commentary before or after. Begin with the `@v=1 root=<id>` header on the
first line of the fenced block.


## USER

# Task 06 — Counter state machine

Implement a state machine whose state is an `Int` counter and whose
single input stream carries a sum-typed event with three cases:
`Increment`, `Decrement`, `Reset`. Each event updates the counter:
`Increment` adds 1, `Decrement` subtracts 1, `Reset` returns the
counter to 0.

The reference implementation must:
- Define the machine with `state: Int, initial state 0`.
- Define an event type as a sum `Increment | Decrement | Reset` with
  no payloads.
- Define a transition function `(state, event) -> {state: Int, outputs: ...}`
  that dispatches on the event case.
- The output set may be empty (no per-event emission).

Apply the machine to the sequence `[Increment, Increment, Decrement,
Reset, Increment]`. The expected final state is `1`.

This task exercises: state-machine declaration, sum-typed events,
event-stream declaration, transition-function lambda with Match on
the event, Int arithmetic builtins, OutputBatch positional-encoding
convention. Maps to corpus program 42 (synchronous version).

The Python reference uses a sum-shaped `Union` over three frozen
dataclasses for the event type and dispatches with `match`/`case`.


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.