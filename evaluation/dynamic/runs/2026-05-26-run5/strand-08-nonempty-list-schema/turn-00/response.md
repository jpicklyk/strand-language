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
isNonEmpty LAM [xs:listT] (WHEN xs listT "Cons(p) -> true | Nil -> false")
nonEmptyInv INV "nonEmpty" nonEmptyListSchema isNonEmpty
nonEmptyListSchema SCH "NonEmptyList" listT [nonEmptyInv]
nilV SV listT "Nil" _
consV SV listT "Cons" (PV consOuter [head=1 tail=nilV])
identityNel LAM [xs:nonEmptyListSchema] xs
app APP identityNel [consV]
```
