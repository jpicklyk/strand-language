```layer-a
@v=1 root=app
headField PRF "head" intT
tailField PRF "tail" listT
consPayload PRD [headField tailField]
consCase SCS "Cons" consPayload
nilCase SCS "Nil" _
listSum SUM [consCase nilCase]
listT RT listSum
isNonEmpty LAM [xs:listT] (WHEN xs listT "Cons(p) -> true | Nil -> false")
nonEmptyInv INV "nonEmpty" nonEmptyListSchema isNonEmpty
nonEmptyListSchema SCH "NonEmptyList" listT [nonEmptyInv]
identityOfNonEmpty LAM [xs_schema:nonEmptyListSchema] xs_schema
nilV SV listT "Nil" _
oneElemList SV listT "Cons" (PV consPayload [head=1 tail=nilV])
app APP identityOfNonEmpty [oneElemList]
```
