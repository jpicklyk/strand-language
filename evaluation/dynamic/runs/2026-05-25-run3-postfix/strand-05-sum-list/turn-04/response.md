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
sumBody LAM [recurse:sumT xs:listT] (MAT xs [consMc nilMc])
consPat PCN listT "Cons" consPayloadPat
consPayloadPat PVR consPayload "p"
consMc MC consPat (APP add [(PFG (VAR consPayloadPat) "head") (APP recurse [(PFG (VAR consPayloadPat) "tail")])])
nilPat PCN listT "Nil" _
nilMc MC nilPat 0
sumFn FIX sumT sumBody
l3 SV listT "Nil" _
l2 SV listT "Cons" (PV consPayload [head=3 tail=l3])
l1 SV listT "Cons" (PV consPayload [head=2 tail=l2])
l0 SV listT "Cons" (PV consPayload [head=1 tail=l1])
app APP sumFn [l0]
```
