```layer-a
@v=1 root=app
# Recursive list type: mu. Cons(head: Int, tail: <self>) | Nil
selfRef RS
headFieldInner PRF "head" intT
tailFieldInner PRF "tail" selfRef
consInner PRD [headFieldInner tailFieldInner]
consCase SCS "Cons" consInner
nilCase SCS "Nil" _
listSum SUM [consCase nilCase]
listT RT listSum

# Outer product (uses the RT directly) for value construction sites
headFieldOuter PRF "head" intT
tailFieldOuter PRF "tail" listT
consOuter PRD [headFieldOuter tailFieldOuter]

# Invariant body: (xs: List) -> Bool returning true on Cons, false on Nil
invariantBody WHEN xs listT "Cons(p) -> true | Nil -> false"
invariantLambda LAM [xs:listT] invariantBody

# Schema with invariant rejecting Nil
nonEmptyInvariant INV "nonEmpty" nonEmptyListSchema invariantLambda
nonEmptyListSchema SCH "NonEmptyList" listT [nonEmptyInvariant]

# Construct Cons(1, Nil) and flow through identity lambda typed by the Schema
nilV SV listT "Nil" _
consV SV listT "Cons" (PV consOuter [head=1 tail=nilV])
identityFn LAM [v:nonEmptyListSchema] v
app APP identityFn [consV]
```
