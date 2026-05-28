```layer-a
@v=1 root=app
# Recursive list type with inner/outer PRD split
selfRef RS
headFieldInner PRF "head" intT
tailFieldInner PRF "tail" selfRef
consInner PRD [headFieldInner tailFieldInner]
consCase SCS "Cons" consInner
nilCase SCS "Nil" _
listSum SUM [consCase nilCase]
listT RT listSum

# Outer product for value construction sites
headFieldOuter PRF "head" intT
tailFieldOuter PRF "tail" listT
consOuter PRD [headFieldOuter tailFieldOuter]

# length: List -> Int via Fixpoint
lengthT FNT [listT] intT
lengthBody WHEN xs listT "Cons(p) -> (APP add [1 (APP recurse [(PFG p \"tail\")])]) | Nil -> 0"
bodyLam LAM [recurse xs] lengthBody
length FIX lengthT bodyLam

# Construct Cons(7, Cons(8, Nil))
nilV SV listT "Nil" _
innerCons SV listT "Cons" (PV consOuter [head=8 tail=nilV])
outerCons SV listT "Cons" (PV consOuter [head=7 tail=innerCons])

# Apply length to the list
app APP length [outerCons]
```
