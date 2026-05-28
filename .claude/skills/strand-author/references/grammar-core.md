# Strand Layer A — grammar core

The essential codes for Strand emission. Each code below shows its `jsonType`, positional arguments, and a small example. Codes are listed in approximate order of how often they appear in real programs.

## Literals (all produce values)

- `ILT value:Int` — IntLit. `n1 ILT 42`
- `FLT value:Float` — FloatLit. `f1 FLT 3.14`
- `STR value:String` — StringLit. `s1 STR "hello"`
- `BLT value:Bool` — BoolLit. `b1 BLT true`
- `ULT` — UnitLit (no args). `u1 ULT`
- `BYT value:String` — BytesLit (base64). `bs1 BYT "aGVsbG8="`

## Types

- `PRM kind:Keyword` — PrimitiveType. `intT PRM Int` (kinds: `Int`, `Float`, `String`, `Bool`, `Unit`, `Bytes`).
- `PRD fields:[refs]` — ProductType. `pT PRD [fa fb]`
- `PRF name:String fieldType:ref` — ProductTypeField. `fa PRF "x" intT`
- `SUM cases:[refs]` — SumType. `sT SUM [ca cb]`
- `SCS name:String caseType:nullable-ref` — SumTypeCase. `ca SCS "Some" intT` or `cb SCS "None" _`
- `FNT parameters:[refs] result:ref [effects:[refs]]` — FunctionType. `fT FNT [intT intT] intT`
- `TPM name:String [bound:ref]` — TypeParameter. `tp1 TPM "A"`

## Functions and binding

- `LAM parameters:PARAM_LIST body:ref [effects:[refs]]` — Lambda (produces value). Parameters accept either bare PRC references or compact `name:typeRef` entries. `bodyL LAM [x:intT y:intT] expr`
- `PRC name:String [paramType:ref]` — ParameterDecl. `x PRC "x" intT`. With compact LAM params the standalone PRC is unnecessary.
- `APP function:ref arguments:[refs] [typeArguments:[refs]] [effectInstances:[refs]]` — Application (produces value). `r APP add [a b]`
- `LET name:String value:ref body:ref` — Let (produces value). `e LET "tmp" v inner`
- `VAR binder:ref` — VarRef (produces value). `v1 VAR x`. With auto-VarRef sugar a bare PRC name in an expression slot lowers to a VarRef automatically.

## References

- `NRF target:ref` — NodeRef (produces value). `n1 NRF closed_subgraph`

## Type abstraction (System F)

- `TAB typeParameters:[refs] body:ref` — TypeAbstraction (produces value). `pT TAB [tp1] body`
- `FAL typeParameters:[refs] body:ref` — ForallType. `fT FAL [tp1] inner`

Layer A density v4 rarely needs explicit polymorphism — most agent emissions are monomorphic. The implicit prelude is monomorphic.

## Control flow

- `MAT scrutinee:ref cases:[refs]` — Match (produces value). `m MAT v [c1 c2]`
- `MC pattern:ref body:ref` — MatchCase. `c1 MC pat body`
- `IF scrutinee:ref then:ref else:ref` — Match-on-Bool sugar (produces value). Expands to a Match + two Pattern + two MatchCase + two BoolLit. `r IF cond v_true v_false`
- `WHEN scrutinee:ref sumType:ref cases:String` — pattern-match-on-sum sugar (produces value). Cases string format: `Case1 -> body | Case2(binder) -> body | ...`. `r WHEN x optT "Some(n) -> n | None -> 0"`
- `PLT patternType:ref literal:ref` — Pattern (literal kind). `p1 PLT intT lit42`
- `PVR patternType:ref name:String` — Pattern (variable kind, binds a name). `p1 PVR intT "n"`
- `PWC patternType:ref` — Pattern (wildcard kind). `p1 PWC intT`
- `PCN patternType:ref caseName:String [payloadPattern:nullable-ref]` — Pattern (constructor kind). `p1 PCN optT "Some" p2`

## Fixpoint and composite values

- `FIX recursionType:ref body:ref` — Fixpoint (produces value). `fact FIX factT bodyLam`. **The body Lambda's FIRST parameter is the recursive call slot**; remaining parameters are user-facing. So a `(Int) -> Int` Fixpoint's body Lambda has signature `(recurse: (Int) -> Int, n: Int) -> Int`.
- `PV ofType:ref fields:FIELD_LIST` — ProductValue (produces value). `pv PV resultT [state=expr outputs=expr]`. Fields accept either bare PFV references or `name=ref` entries.
- `PFV fieldName:String value:ref` — ProductFieldValue. `pf PFV "x" expr`
- `PFG target:ref fieldName:String` — ProductFieldGet (produces value). `g PFG record "x"`
- `SV ofType:ref caseName:String payload:nullable-ref` — SumValue (produces value). `v SV optT "Some" lit42` or `v SV optT "None" _`

## Recursive types

- `RT body:ref` — RecursiveType. `lT RT consSum`
- `RS [depth:Int]` — RecursiveSelf (depth defaults to 0). `s RS`

For a recursive linked list `μ. Cons(head: Int, tail: <self>) | Nil`:

```layer-a
recSelf RS
headField PRF "head" intT
tailField PRF "tail" recSelf
consProd PRD [headField tailField]
consCase SCS "Cons" consProd
nilCase SCS "Nil" _
listBody SUM [consCase nilCase]
listT RT listBody
```

When constructing values of a recursive type, agents historically needed both an "inner" PRD (used at the SumTypeCase position with RS inside) and an "outer" PRD (used at SumValue construction with listT inside). The Elaborator's auto-Outer-PRD synthesis now handles this automatically in most cases — a single PRD that uses RS works at both positions.

## Effects and capabilities (overview — see effects.md for depth)

- `EFC categoryName:String [parameters:[refs]]` — EffectCategory. `recvFx EFC "StateMachine.Receive"`. Common categories are pre-bound in the implicit prelude.
- `EFD effectType:ref parameters:[refs]` — EffectDecl. `ed1 EFD writeFx [path]`
- `CAP capabilities:[refs] body:ref` — CapabilityScope (produces value). `scope CAP [ed1] inner`

## Foreign function interface

- `FN target:String foreignType:ref [effects:[refs]]` — ForeignNode (produces value). `myAdd FN "strand-builtin:Int.Add" addT`. Most common builtins are pre-bound in the implicit prelude — see [prelude.md](prelude.md).

## Handler

- `H intercept:ref handle:ref body:ref` — Handler (produces value). `h H writeFx noopFn protected_body`

The intercept must be an EffectCategory (not a ForeignNode). The `handle` is evaluated once at Handler-entry and must produce a function value whose signature matches the intercepted function. The body is evaluated with the handler installed.

## State machines (overview — see state-machines.md if present)

- `SM transitionFn:ref initialState:ref inputStreams:[refs] [outputStreams:[refs]] [effects:[refs]]` — StateMachine.
- `ESE eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (external).
- `ESI eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (internal).
- `ESO eventType:ref [bufferSize:Int] [overflowPolicy:Keyword] [consumerMode:Keyword]` — EventStream (output).
- `TR guard:nullable-ref body:ref` — Transition.

## Schema and Invariant

- `SCH schemaName:String valueType:ref invariants:[refs]` — Schema. `posInt SCH "PositiveInt" intT [posInv]`
- `INV invariantName:String targetSchema:ref body:ref` — Invariant. `posInv INV "positive" posInt isPosLambda`

The invariant body must be a pure `(valueType) -> Bool` Lambda. Effectful bodies or polymorphic bodies are rejected.

## Tool definitions and response schemas (for LLM integrations)

- `TLD name:String description:String parameterSchema:ref implementation:ref` — ToolDef.
- `RSC schema:ref` — ResponseSchemaSpec.

Both wrap a Schema (N-032) reference; the verifier projects the schema's valueType to JSON Schema for the provider library.
