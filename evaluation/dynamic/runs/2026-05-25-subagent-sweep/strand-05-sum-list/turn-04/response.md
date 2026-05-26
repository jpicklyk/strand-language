```layer-a
@v=1 root=app
nilCase SCS "Nil" _
selfRef RS
consPayloadTail PRF "tail" selfRef
consPayloadHead PRF "head" intT
consPayloadT PRD [consPayloadHead consPayloadTail]
consCase SCS "Cons" consPayloadT
listBody SUM [consCase nilCase]
listT RT listBody
sumT FNT [listT] intT
nil SV listT "Nil" _
lit3 ILT 3
pv3 PV consPayloadT [head=lit3 tail=nil]
c3 SV listT "Cons" pv3
lit2 ILT 2
pv2 PV consPayloadT [head=lit2 tail=c3]
c2 SV listT "Cons" pv2
lit1 ILT 1
pv1 PV consPayloadT [head=lit1 tail=c2]
c1 SV listT "Cons" pv1
lst PRC "lst" listT
recurse PRC "recurse" sumT
sumBody WHEN lst listT "Cons(p) -> (APP add [(PFG p \"head\") (APP recurse [(PFG p \"tail\")])]) | Nil -> 0"
sumLam LAM [recurse lst] sumBody
sumFn FIX sumT sumLam
app APP sumFn [c1]
```
