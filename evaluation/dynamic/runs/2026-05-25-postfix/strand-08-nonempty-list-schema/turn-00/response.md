```layer-a
@v=1 root=schemaClaim
selfRef RS
headField PRF "head" intT
tailField PRF "tail" selfRef
consPayload PRD [headField tailField]
consCase SCS "Cons" consPayload
nilCase SCS "Nil" _
listSum SUM [consCase nilCase]
listT RT listSum
invBody LAM [xs:listT] (WHEN xs listT "Cons(p) -> true | Nil -> false")
nonEmptyList SCH "NonEmptyList" listT [nonEmptyInv]
nonEmptyInv INV "nonEmpty" nonEmptyList invBody
nilValue SV listT "Nil" _
consPayloadValue PV consPayload [head=1 tail=nilValue]
consValue SV listT "Cons" consPayloadValue
identityLam LAM [xs:nonEmptyList] xs
schemaClaim APP identityLam [consValue]
```
