```layer-a
@v=1 root=program
handleT FNT [] intT
handleLam LAM [] 99
nowDecl EFD nowFx []
nowCall APP now [] [] [nowDecl]
body APP add [nowCall 1]
program H nowFx handleLam body
```
