```layer-a
@v=1 root=schemaClaim
nilCase SCS "Nil" _
consHead PRF "head" intT
consTail PRF "tail" listT
consT PRD [consHead consTail]
consCase SCS "Cons" consT
listSumT SUM [consCase nilCase]
listT RT listSumT
invBody WHEN xs listT "Cons(c) -> true | Nil -> false"
invLam LAM [xs:listT] invBody
nonEmptyInv INV "nonEmpty" nonEmptyList invLam
nonEmptyList SCH "NonEmptyList" listT [nonEmptyInv]
identityNEL LAM [l:nonEmptyList] l
nilVal SV listT "Nil" _
consVal SV listT "Cons" (PV consT [head=1 tail=nilVal])
schemaClaim APP identityNEL [consVal]
```
