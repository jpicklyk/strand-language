```layer-a
@v=1 root=schemaClaim
nilCase SCS "Nil" _
consHead PRF "head" intT
consTail PRF "tail" listT
consT PRD [consHead consTail]
consCase SCS "Cons" consT
listSumT SUM [consCase nilCase]
listT RT listSumT
xs PRC "xs" listT
c PRC "c" consT
nilPat PCN listT "Nil" _
cPat PVR consT "c"
consPat PCN listT "Cons" cPat
trueLit BLT true
falseLit BLT false
consMC MC consPat trueLit
nilMC MC nilPat falseLit
invBody MAT xs [consMC nilMC]
invLam LAM [xs] invBody
nonEmptyInv INV "nonEmpty" nonEmptyList invLam
nonEmptyList SCH "NonEmptyList" listT [nonEmptyInv]
identityNEL LAM [l:nonEmptyList] l
nilVal SV listT "Nil" _
consVal SV listT "Cons" (PV consT [head=1 tail=nilVal])
schemaClaim APP identityNEL [consVal]
```
