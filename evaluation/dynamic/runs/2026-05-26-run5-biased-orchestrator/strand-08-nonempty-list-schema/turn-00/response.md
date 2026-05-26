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
nonEmptyT SCH "NonEmptyList" listT [nonEmptyInv]
isNonEmptyLam LAM [xs:listT] (WHEN xs listT "Cons(p) -> true | Nil -> false")
nonEmptyInv INV "nonempty" nonEmptyT isNonEmptyLam
nilV SV listT "Nil" _
oneList SV listT "Cons" (PV consOuter [head=1 tail=nilV])
identityFn LAM [v:nonEmptyT] v
app APP identityFn [oneList]
```
