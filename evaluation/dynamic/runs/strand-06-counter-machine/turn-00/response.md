```layer-a
@v=1 root=counterMachine
incCase SCS "Increment" _
decCase SCS "Decrement" _
resetCase SCS "Reset" _
eventT SUM [incCase decCase resetCase]
emptyOutputsT PRD []
stateFieldT PRF "state" intT
outputsFieldT PRF "outputs" emptyOutputsT
resultT PRD [stateFieldT outputsFieldT]
addOne APP add [s 1]
subOne APP sub [s 1]
patInc PCN eventT "Increment" _
patDec PCN eventT "Decrement" _
patReset PCN eventT "Reset" _
caseInc MC patInc addOne
caseDec MC patDec subOne
caseReset MC patReset 0
newState MAT e [caseInc caseDec caseReset]
transitionResult PV resultT [state=newState outputs=(PV emptyOutputsT [])]
transitionLambda LAM [s:intT e:eventT] transitionResult
inputStream ESE eventT
counterMachine SM transitionLambda 0 [inputStream] [] [receiveFx]
```
