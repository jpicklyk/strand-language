# Turn 00 of session strand-01-factorial

Task: `01-factorial` | Config: `strand-layer-a-density-v4` | Model: `claude-sonnet-4-7`
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
Cases are separated by ` | `. The `body` is a single token — an identifier
(a declared node, the case's binder, or any PRC binder in scope) or an
inline literal (Int/Float/Bool/Str).

Example: `r WHEN someValue optT "Some(n) -> n | None -> 0"`

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
scrutinee, ...) automatically lowers to a VarRef binding to that PRC. So
`APP add [x y]` works without writing out a VarRef declaration when `x`/`y`
are PRC names in scope.

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

Any value-producing code may appear in parentheses at a REFERENCE,
LIST_REF, or NULLABLE_REF position. The emitter assigns it a synthetic id
and inserts the declaration at the appropriate place:

    APP mul [n (APP recurse [(APP sub [n 1])])]

The above lowers to three Applications plus the literal 1 — five
declarations in canonical form, one line in density v4. Nested expressions
combine with auto-VarRef so `n` is a bare PRC name pointing at a parameter
in scope.

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

# Task 01 — Factorial

Implement the factorial function for non-negative integers.

```
factorial(0) = 1
factorial(n) = n * factorial(n - 1)    (n > 0)
```

The reference implementation must:
- Accept a non-negative integer argument.
- Return its factorial.
- Recurse via the language's standard fixpoint mechanism (no iteration).
- Match on `n == 0` for the base case.

This task exercises: function definition, recursion / fixpoint, integer
literals, Match dispatch, conditional dispatch on a primitive value.
Maps to corpus program 21.


---

Write your response in `response.md` in this turn directory, then re-run `strand-eval step --session <session_dir>` to advance.