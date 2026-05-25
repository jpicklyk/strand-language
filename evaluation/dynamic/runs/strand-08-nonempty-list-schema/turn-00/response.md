```layer-a
@v=1 root=schemaClaim
headFieldInner PRF "head" intT
tailFieldInner PRF "tail" recSelf
consProductInner PRD [headFieldInner tailFieldInner]
consCase SCS "Cons" consProductInner
nilCase SCS "Nil" _
listBody SUM [consCase nilCase]
recSelf RS
listT RT listBody
headFieldOuter PRF "head" intT
tailFieldOuter PRF "tail" listT
consProductOuter PRD [headFieldOuter tailFieldOuter]
patConsWild PWC consProductOuter
patCons PCN listT "Cons" patConsWild
caseCons MC patCons true
patNil PCN listT "Nil" _
caseNil MC patNil false
isNonEmptyBody MAT xs [caseCons caseNil]
isNonEmptyLam LAM [xs:listT] isNonEmptyBody
nonEmptyInvariant INV "non_empty" nonEmptyList isNonEmptyLam
nonEmptyList SCH "NonEmptyList" listT [nonEmptyInvariant]
nilVal SV listT "Nil" _
oneItemList SV listT "Cons" (PV consProductOuter [head= 1 tail= nilVal])
identityOfNonEmpty LAM [p:nonEmptyList] p
schemaClaim APP identityOfNonEmpty [oneItemList]
```
