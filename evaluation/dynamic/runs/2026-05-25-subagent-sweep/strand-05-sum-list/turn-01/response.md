```layer-a
@v=1 root=app
nilCase SCS "Nil" _
tailBody RT (SUM [(SCS "Cons" (PRD [(PRF "head" intT) (PRF "tail" (RS))])) (SCS "Nil" _)])
consPayloadHead PRF "head" intT
consPayloadTail PRF "tail" tailBody
consPayloadT PRD [consPayloadHead consPayloadTail]
consCase SCS "Cons" consPayloadT
listBody SUM [consCase nilCase]
listT RT listBody
sumT FNT [listT] intT
nil SV listT "Nil" _
c3 SV listT "Cons" (PV consPayloadT [head=3 tail=nil])
c2 SV listT "Cons" (PV consPayloadT [head=2 tail=c3])
c1 SV listT "Cons" (PV consPayloadT [head=1 tail=c2])
sumBody WHEN lst listT "Cons(p) -> (APP add [(PFG p \"head\") (APP recurse [(PFG p \"tail\")])]) | Nil -> 0"
sumLam LAM [recurse lst] sumBody
sumFn FIX sumT sumLam
app APP sumFn [c1]
```
