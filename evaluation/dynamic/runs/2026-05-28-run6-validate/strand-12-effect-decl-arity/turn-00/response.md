```layer-a
@v=1 root=program
hostLit STR "localhost"
portLit ILT 8080
connectFxCat EFC "Network.Connect" [stringT intT]
netConnectT FNT [stringT intT] intT
netConnect FN "strand-builtin:Net.Connect" netConnectT [connectFxCat]
connDecl EFD connectFxCat [hostLit portLit]
connectCall APP netConnect [hostLit portLit] _ [connDecl]
addOne APP add [connectCall 1]
fakeHandle ILT 42
handleLam LAM [h:stringT p:intT] fakeHandle
program H netConnect handleLam addOne
```
