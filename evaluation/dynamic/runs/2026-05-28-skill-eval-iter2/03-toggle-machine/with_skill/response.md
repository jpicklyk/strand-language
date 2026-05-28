```layer-a
@v=1 root=toggle
emptyOut PRD []
stateF PRF "state" boolT
outsF PRF "outputs" emptyOut
resultT PRD [stateF outsF]
emptyOutV PV emptyOut []
transFn LAM [s:boolT e:unitT] (PV resultT [state=(APP not [s]) outputs=emptyOutV])
inStream ESE unitT
toggle SM transFn false [inStream] [] [receiveFx]
```
