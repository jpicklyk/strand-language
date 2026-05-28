```layer-a
@v=1 root=toggle
emptyOutT PRD []
stateF PRF "state" boolT
outF PRF "outputs" emptyOutT
resultT PRD [stateF outF]
emptyOutV PV emptyOutT []
transFn LAM [s:boolT e:unitT] (PV resultT [state=(APP not [s]) outputs=emptyOutV])
inStream ESE unitT
toggle SM transFn false [inStream] [] [receiveFx]
```
