```layer-a
@v=1 root=program
connectFx EFC "Network.Connect" [stringT intT]
netConnectT FNT [stringT intT] intT [connectFx]
netConnect FN "strand-builtin:Net.Connect" netConnectT [connectFx]
host STR "localhost"
port ILT 8080
callDecl EFD connectFx [host port]
connectCall APP netConnect [host port] [] [callDecl]
addOne APP add [connectCall 1]
fakeHandler LAM [h:stringT p:intT] 42
program H netConnect fakeHandler addOne
```
