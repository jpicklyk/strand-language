# Reference: errors — error model deep-dive and the full verifier error table

## Error model (TRY / Attempt)

`TRY body` observes a runtime failure as a value. It yields `Ok(v)` on success
and `Err({kind: String, detail: String})` when `body` raises a *catchable*
failure. The result type is `RES okType` = `Ok(okType) | Err(errPayloadT)`;
match it with `WHEN`/`MAT` + constructor patterns exactly like `Option`. Scope
one `TRY` over a whole pipeline — it catches the first catchable failure
anywhere in `body`. Wrap only genuinely fallible operations (IO and schema
checks); a `TRY` around pure logic only adds an `Ok` you must immediately
unwrap.

Catchable failures (these become `Err`): only IO operations and runtime schema
checks. Branch on `Err.kind`, never on `Err.detail` — `kind` is a stable closed
vocabulary; `detail` interpolates platform- and locale-varying host text and is
not portable across hosts. Use `detail` only as opaque diagnostic output (e.g.,
to feed your next generation), never as a control-flow discriminator.

`kind` vocabulary (the actual strings):

    filesystem-read filesystem-write filesystem-append filesystem-exists
    filesystem-delete filesystem-list                 — Fs.* failures
    network-connect network-send network-receive network-close
    network-stream-receive network-stream-timeout     — Net.* failures
    http-request http-listen http-accept http-respond http-server-close
    process-spawn process-wait process-envvar         — Process.* failures
    llm-stream-receive llm-stream-close llm-stream-timeout — streaming-LLM drains
    anthropic-http openai-http openai-embed gemini-http gemini-embed
    pinecone-open pinecone-query ... chroma-open ...   — provider failures
    vector-metric-fixed                               — per-query metric on a fixed-metric store
    schema-invariant                                  — a runtime Schema check failed

Uncatchable — these terminate evaluation regardless of any enclosing `TRY`
(they propagate to the host, which reports them so YOU can regenerate or so the
host can enforce policy): a missed `Match` case, a non-callable in call
position, an arity/scope defect, a capability or refinement denial, a sandbox
denial, the resource budget (steps / stack / allocations / wall clock), an
unknown foreign target, and `Int.Div`/`Int.Mod`/`Math.Mod` by zero. Do not wrap
these expecting recovery — fix the program (add the case, guard the divisor,
request the capability) rather than `TRY`ing around a defect.

## Verifier error classes (full table)

On a failed verify, you receive structured feedback. Compile-phase errors
look like:

    line N: <description>

Verifier errors look like:

    <ErrorClass>(at=#<nodeId>, <details>)

- `CategoryMismatch` — a field references a node of the wrong category
  for the position (e.g., a value where a Type belongs). The error names
  the field and both categories.
- `UnboundVariable` — a VarRef's binder (PRC or LET) is not in scope at
  the VarRef's position. Reference only enclosing binders.
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
- `NonExhaustiveMatch` — a Match misses possible scrutinee values. A Sum
  scrutinee must cover every declared case with top-level constructor
  patterns or include a wildcard/variable catch-all; a Bool scrutinee
  needs both literals or a catch-all.
- `EmptyMatch` — a Match with zero cases. At least one case is required.
- `PatternTypeMismatch` — a pattern's `patternType` does not equal the
  Match's scrutinee type (strict structural equality).
- `MatchCaseBodyTypeDivergence` — two case bodies (including IF/WHEN
  branches) have different types. All branches must agree.
- `FixpointBodyShapeMismatch` — a FIX body must be a Lambda whose FIRST
  parameter has the recursionType (the recursive-call slot) and whose
  remaining parameters and result match the recursionType's.
- `UncoveredEffects` — a Lambda's body uses effects the Lambda failed to
  declare. Add the missing EffectCategory NodeIds to the Lambda's `effects`
  list.
- `EffectDeclArityMismatch` / `EffectDeclParameterTypeMismatch` — an EFD's
  parameter list does not match its EffectCategory's declared parameter
  count or types. Supply matching positional values.
- `EffectInstanceCoverageMismatch` — an Application's effectInstances do
  not cover the callee's declared effect categories exactly (one EffectDecl
  per declared category, none extra).
- `CapabilityScopeUnsatisfiable` — a CAP narrows capabilities below the
  body's effect closure. Add the missing categories to the CAP or move the
  effectful code outside it.
- `HandlerNotAFunction` / `HandlerOverPolymorphicHandle` — a Handler's
  `handle` expression must evaluate to a monomorphic function value.
- `HandlerSignatureMismatch` — an intercepted call's argument and result
  types must equal the handler function's. Adjust the handle lambda's
  parameter types and result to match the intercepted callee.
- `StateMachineMissingImplicitEffect` — a StateMachine is missing
  `receiveFx` or `sendFx`. Add the appropriate effect category to the SM's
  `effects` list.
- `StateMachineTransitionFnShapeMismatch` — the transition function's
  type is not `(State, Event) -> (State, Outputs)` for either the
  OutputBatch product shape or the tagged-list recursive shape. Check the
  result PRD's field order: `state` first, `outputs` second.
- `StateMachineInitialStateTypeMismatch` — the SM's initialState type does
  not match the State half of the transition function's signature.
- `OutputStreamEventTypeMismatch` — an output stream's eventType disagrees
  with the corresponding `output_i: Option<...>` slot of the transition's
  OutputBatch product.
- `SchemaInvariantViolation` — a statically-known value flowing into a
  Schema-typed position failed one of the Schema's invariants. Check the
  invariant body and the value being supplied.
- `SchemaInvariantBodyMustBePure` — an Invariant's body declares effects or
  is a ForeignNode. Invariant bodies must be pure `(valueType) -> Bool`
  lambdas.
- `ToolParamTypeUnsupported` — a ToolDef's parameterSchema valueType has no
  JSON Schema projection (FunctionType, ForallType, or unbound
  TypeParameter). Tool parameter types must be plain data shapes.
- `NodeRefTargetMustBeClosed` — a NodeRef's target subgraph contains free
  VarRef / TypeParameter references to binders outside itself. NodeRef
  targets must be self-contained.
- `UnboundRecursiveSelf` — an RS was resolved outside any enclosing RT
  walk; almost always the INNER product was used at a top-level value-
  construction site. Use the OUTER product there (see the density-sugars
  reference); the error's hint field carries the full explanation.
- `ProjectionMismatch` and the projection admission errors
  (`ProjectionArityMismatch`, `ProjectionCategoryMismatch`,
  `ProjectionSourceArityMismatch`, `ProjectionArgRefOutOfRange`,
  `ProjectionLiteralNotConstant`, `ProjectionLiteralTypeMismatch`) — see
  the effects reference for the Q-039 rules these enforce.

Use the `at=#<nodeId>` field to locate the offending node. Node ids follow
the order of the compiled dag-json document: your declared nodes in line
order FIRST, then sugar-synthesized nodes (IF/WHEN expansion towers,
auto-VarRefs, inline literals, nested `(CODE ...)` forms), then implicit-
prelude nodes. The CLI annotates each `#N` with the author id (and Layer A
line where known) and flags synthesized/prelude nodes as such, so counting
positions by hand should rarely be needed.
