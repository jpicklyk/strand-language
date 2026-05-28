# Strand density sugars (v4)

The density sugars compile to byte-identical canonical dag-json compared to the long form. They reduce per-emission token cost by roughly 4× without changing what the verifier accepts. Use them by default unless explicitly asked to emit canonical form.

## Slice 1 — Implicit prelude

Reserved names (intT, add, gt, now, nowFx, fsWrite, writeFx, …) resolve without local declaration. See [prelude.md](prelude.md) for the full table.

```layer-a
-- Long form:
intT PRM Int
addT FNT [intT intT] intT
add FN "strand-builtin:Int.Add" addT
result APP add [1 2]

-- Density form (prelude implicit):
result APP add [1 2]
```

## Slice 2 — Inline literals at reference positions

Anywhere a REFERENCE, LIST_REF, or NULLABLE_REF slot expects a node id, you can write the literal directly. The elaborator synthesizes a `__litN` child literal node.

```layer-a
-- Long form:
five ILT 5
result APP add [n five]

-- Density form:
result APP add [n 5]
```

Inline literals: integers `42 -3 0`, floats `3.14 -0.5 1.0` (must contain a dot), booleans `true false`, strings `"hello"`. Combine: `APP add [42 7]`.

## Slice 3 — Auto-VarRef for PRC and LET binders

A bare reference to a `PRC` parameter name or a `LET` binder name at an expression position lowers to a `VarRef` automatically. You almost never need to write `VAR`.

```layer-a
-- Long form:
xVar VAR x
result APP add [xVar 1]

-- Density form:
result APP add [x 1]
```

## Slice 4 — IF sugar

`IF cond then else` expands to a Match + two Pattern + two MatchCase + two BoolLit. Cleaner than writing the Match tower for boolean dispatch.

```layer-a
-- Long form:
truePat PLT boolT trueLit
trueLit BLT true
falsePat PLT boolT falseLit
falseLit BLT false
trueCase MC truePat thenBranch
falseCase MC falsePat elseBranch
result MAT cond [trueCase falseCase]

-- Density form:
result IF cond thenBranch elseBranch
```

Combined with nested expressions:

```layer-a
result IF (APP eqInt [n 0]) 1 (APP mul [n nMinus1Fact])
```

## Slice 5 — WHEN sugar (pattern-match on SumType)

`WHEN scrutinee sumType "Case1 -> body1 | Case2(binder) -> body2 | ..."` expands the Match tower with constructor patterns and payload binders.

```layer-a
-- Long form:
somePat PCN optT "Some" (PVR intT "n")
somCase MC somePat (VAR @last)   -- n bound by the PVR
nonePat PCN optT "None" _
noneCase MC nonePat 0
result MAT v [somCase noneCase]

-- Density form:
result WHEN v optT "Some(n) -> n | None -> 0"
```

Constructor cases with payloads bind a name; payload-less cases use no binder. Body can be any value-producing expression including nested `(CODE args...)`.

## Slice 6 — Compact LAM parameters

In `LAM [params] body`, each entry can be a compact `name:typeRef` instead of a bare PRC reference. The compact form synthesizes the PRC inline.

```layer-a
-- Long form:
xParam PRC "x" intT
yParam PRC "y" intT
addLam LAM [xParam yParam] (APP add [x y])

-- Density form:
addLam LAM [x:intT y:intT] (APP add [x y])
```

The Fixpoint recursion-slot parameter uses the same form — declare the recursive call slot as the FIRST compact-LAM entry:

```layer-a
factBody LAM [recurse:factT n:intT] body
```

## Slice 7 — Anonymous ids and `@last`

`_` as an author id makes the node anonymous; reference it via `@last`, which always resolves to the most-recently-declared node.

```layer-a
-- Long form:
oneVal ILT 1
twoVal ILT 2
result APP add [oneVal twoVal]

-- Density form:
_ ILT 1
_ ILT 2
result APP add [@last @last]   -- but each @last resolves to the previous line, so:
                               -- the right operand is the second _, the left would resolve to itself

-- Realistic use:
result APP add [42 7]    -- inline literals are usually cleaner
```

`@last` is most useful when you have a one-shot intermediate you don't want to name:

```layer-a
_ APP mul [a b]
result APP add [@last 1]
```

## Slice 8 — Inline FIELD_LIST for ProductValue

ProductValue's `fields` slot accepts `name=ref` entries in addition to bare PFV references. The elaborator synthesizes the PFV with an internal author id.

```layer-a
-- Long form:
headFV PFV "head" headExpr
tailFV PFV "tail" tailExpr
listProd PV consProductT [headFV tailFV]

-- Density form:
listProd PV consProductT [head=headExpr tail=tailExpr]
```

Combine with nested expressions: `PV resultT [state=(APP add [s 1]) outputs=(...) ]`.

## Slice 10 — Nested `(CODE args...)` expressions

Anywhere a value-producing node is expected, a parenthesized `(CODE args...)` form synthesizes the node inline with a `__exprN` internal id.

```layer-a
-- Long form:
add1 APP add [n 1]
result APP mul [add1 2]

-- Density form:
result APP mul [(APP add [n 1]) 2]
```

Nesting is unlimited. The most readable pattern is one level of nesting per line; deeper nesting is fine but can hurt human inspection.

**Important:** nested expressions must be **value-producing**. Declaration-only codes (EFD, EFC, PRC, PRF, SCS, MC) cannot be inlined. They need their own lines.

```layer-a
-- WRONG: EFD is a declaration, not a value
result APP write [path] [] [(EFD writeFx [path])]

-- CORRECT:
writeDecl EFD writeFx [path]
result APP write [path] [] [writeDecl]
```

## Combining sugars in practice

Real density-v4 emissions combine multiple sugars per line. Factorial:

```layer-a
@v=1 root=app
factT FNT [intT] intT
factBody LAM [recurse:factT n:intT] (IF (APP eqInt [n 0]) 1 (APP mul [n (APP recurse [(APP sub [n 1])])]))
fact FIX factT factBody
app APP fact [5]
```

Five sugars in three lines: compact-LAM params, IF, three levels of nested APP, inline literals (0, 1, 5), and auto-VarRef on `recurse` and `n`. Compared to canonical dag-json, this is roughly 4× denser.
