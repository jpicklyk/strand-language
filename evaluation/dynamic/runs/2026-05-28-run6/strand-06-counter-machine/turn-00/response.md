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
transitionFnT FNT [intT eventT] resultT
emptyOutputs PV emptyOutputsT []
newState WHEN e eventT "Increment -> (APP add [s 1]) | Decrement -> (APP sub [s 1]) | Reset -> 0"
transitionResult PV resultT [state=newState outputs=emptyOutputs]
transitionLambda LAM [s:intT e:eventT] transitionResult
inputStream ESE eventT
counterMachine SM transitionLambda 0 [inputStream] [] [receiveFx]
```
