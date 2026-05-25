```layer-a
@v=1 root=main
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
sumType FNT [listT] intT
varP PVR consProductOuter "p"
patCons PCN listT "Cons" varP
pRef VAR varP
hExtract PFG pRef "head"
tExtract PFG pRef "tail"
sumOfTail APP recurse [tExtract]
sumBody APP add [hExtract sumOfTail]
caseCons MC patCons sumBody
patNil PCN listT "Nil" _
caseNil MC patNil 0
matchBody MAT xs [caseCons caseNil]
bodyLam LAM [recurse:sumType xs:listT] matchBody
sumFn FIX sumType bodyLam
nilVal SV listT "Nil" _
node3 SV listT "Cons" (PV consProductOuter [head= 3 tail= nilVal])
node2 SV listT "Cons" (PV consProductOuter [head= 2 tail= node3])
list123 SV listT "Cons" (PV consProductOuter [head= 1 tail= node2])
main APP sumFn [list123]
```
