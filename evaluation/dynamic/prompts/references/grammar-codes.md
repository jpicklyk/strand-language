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
