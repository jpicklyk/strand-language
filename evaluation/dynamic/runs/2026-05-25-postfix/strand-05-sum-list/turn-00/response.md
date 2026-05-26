```layer-a
@v=1 root=app
selfRef RS
headField PRF "head" intT
tailField PRF "tail" selfRef
consPayload PRD [headField tailField]
consCase SCS "Cons" consPayload
nilCase SCS "Nil" _
listSum SUM [consCase nilCase]
listT RT listSum
sumT FNT [listT] intT
sumBody WHEN xs listT "Cons(p) -> (APP add [(PFG p \"head\") (APP recurse [(PFG p \"tail\")])]) | Nil -> 0"
sumLam LAM [recurse xs] sumBody
sumFn FIX sumT sumLam
nilV SV listT "Nil" _
cons3 SV listT "Cons" (PV consPayload [head=3 tail=nilV])
cons2 SV listT "Cons" (PV consPayload [head=2 tail=cons3])
cons1 SV listT "Cons" (PV consPayload [head=1 tail=cons2])
app APP sumFn [cons1]
```
