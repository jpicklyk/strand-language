```layer-a
@v=1 root=app
selfRef RS
headFieldInner PRF "head" intT
tailFieldInner PRF "tail" selfRef
consInner PRD [headFieldInner tailFieldInner]
consCase SCS "Cons" consInner
nilCase SCS "Nil" _
listSum SUM [consCase nilCase]
listT RT listSum
headFieldOuter PRF "head" intT
tailFieldOuter PRF "tail" listT
consOuter PRD [headFieldOuter tailFieldOuter]
sumT FNT [listT] intT
sumBody LAM [recurse xs] (WHEN xs listT "Cons(p) -> (APP add [(PFG p \"head\") (APP recurse [(PFG p \"tail\")])]) | Nil -> 0")
sumFn FIX sumT sumBody
nilV SV listT "Nil" _
list3 SV listT "Cons" (PV consOuter [head=3 tail=nilV])
list23 SV listT "Cons" (PV consOuter [head=2 tail=list3])
list123 SV listT "Cons" (PV consOuter [head=1 tail=list23])
app APP sumFn [list123]
```
